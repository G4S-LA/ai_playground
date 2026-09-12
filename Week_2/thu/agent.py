#!/usr/bin/env python3

from __future__ import annotations

import math
import os
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path
from typing import Any, Protocol

import requests

from chat_repository import ChatRepository, ChatRepositoryError
from context_compression import CompressionSettings, summary_message, summary_request
from model_output import visible_answer
from token_usage import TokenCounter, completion_usage, cost_usd, totals


class AgentError(RuntimeError):
    """Понятная пользователю ошибка при работе агента."""


class ContextOverflowError(AgentError):
    def __init__(self, preview: dict[str, Any]) -> None:
        self.preview = preview
        super().__init__(
            "Переполнение локального контекста: "
            f"обязательная часть запроса ≈{preview['input_tokens_estimate']} > "
            f"окно {preview['context_window_tokens']}. "
            "Увеличьте окно или сократите вопрос, инструкцию либо число свежих сообщений."
        )


class HttpResponse(Protocol):
    ok: bool
    status_code: int
    text: str

    def json(self) -> Any:
        ...


class HttpClient(Protocol):
    def post(self, url: str, **kwargs: Any) -> HttpResponse:
        ...


@dataclass(frozen=True)
class AgentConfig:
    api_key: str = field(repr=False)
    api_url: str
    model: str
    system_prompt: str
    temperature: float
    timeout_seconds: float
    database_path: str
    context_window_tokens: int = 8192
    token_encoding: str = "cl100k_base"
    input_price_per_million: float | None = None
    output_price_per_million: float | None = None
    cached_input_price_per_million: float | None = None
    compression_enabled: bool = True
    keep_recent_messages: int = 10
    summary_every_messages: int = 10
    summary_max_tokens: int = 512

    def __post_init__(self) -> None:
        try:
            CompressionSettings(self.compression_enabled, self.keep_recent_messages, self.summary_every_messages)
        except ValueError as error:
            raise AgentError(str(error)) from error
        if type(self.summary_max_tokens) is not int or self.summary_max_tokens <= 0:
            raise AgentError("summary_max_tokens должен быть положительным целым числом")
        if type(self.context_window_tokens) is not int or self.context_window_tokens <= 0:
            raise AgentError("context_window_tokens должен быть положительным целым числом")
        for name in (
            "input_price_per_million", "output_price_per_million",
            "cached_input_price_per_million",
        ):
            value = getattr(self, name)
            if value is not None and (not math.isfinite(value) or value < 0):
                raise AgentError(f"{name} должен быть конечным неотрицательным числом")

    @classmethod
    def from_env(cls) -> "AgentConfig":
        api_key = _env("LLM_API_KEY") or _env("DASHSCOPE_API_KEY")
        if not api_key:
            raise AgentError(
                "Не задана переменная LLM_API_KEY или DASHSCOPE_API_KEY"
            )

        return cls(
            api_key=api_key,
            api_url=_env(
                "LLM_API_URL",
                "https://api.openai.com/v1/chat/completions",
            ),
            model=_env("LLM_MODEL", "gpt-4o-mini"),
            system_prompt=_env(
                "LLM_SYSTEM_PROMPT",
                "Ты — полезный ассистент. Отвечай кратко и по делу.",
            ),
            temperature=_float_in_range(
                "LLM_TEMPERATURE",
                default=0.7,
                minimum=0.0,
                maximum=2.0,
            ),
            timeout_seconds=_positive_float("LLM_TIMEOUT_SECONDS", 120.0),
            database_path=_env(
                "LLM_HISTORY_DB",
                str(Path(__file__).with_name("chat_history.sqlite3")),
            ),
            context_window_tokens=_positive_int("LLM_CONTEXT_WINDOW_TOKENS", 8192),
            token_encoding=_env("LLM_TOKEN_ENCODING", "cl100k_base"),
            input_price_per_million=_optional_price("LLM_INPUT_PRICE_PER_MILLION"),
            output_price_per_million=_optional_price("LLM_OUTPUT_PRICE_PER_MILLION"),
            cached_input_price_per_million=_optional_price("LLM_CACHED_INPUT_PRICE_PER_MILLION"),
            compression_enabled=_boolean("LLM_COMPRESSION_ENABLED", True),
            keep_recent_messages=_positive_int("LLM_KEEP_RECENT_MESSAGES", 10),
            summary_every_messages=_positive_int("LLM_SUMMARY_EVERY_MESSAGES", 10),
            summary_max_tokens=_positive_int("LLM_SUMMARY_MAX_TOKENS", 512),
        )


class SimpleAgent:
    """LLM-агент, восстанавливающий контекст выбранного чата из SQLite."""

    def __init__(
        self,
        config: AgentConfig,
        chat_id: str,
        repository: ChatRepository | None = None,
        http_client: HttpClient | None = None,
        token_counter: TokenCounter | None = None,
    ) -> None:
        self._config = config
        self._chat_id = chat_id
        self._repository = repository or ChatRepository(config.database_path)
        self._http_client = (
            http_client if http_client is not None else requests.Session()
        )
        try:
            self._counter = token_counter or TokenCounter(config.token_encoding)
        except Exception as error:
            raise AgentError(f"Не удалось загрузить токенизатор: {error}") from error
        self._context_window_tokens = config.context_window_tokens
        self._compression = CompressionSettings(
            config.compression_enabled, config.keep_recent_messages, config.summary_every_messages,
        )
        self._summary = ""
        self._covered = 0
        self.last_turn: dict[str, Any] | None = None

        try:
            chat = self._repository.get_chat(chat_id)
            if chat is None:
                raise AgentError(f"Чат {chat_id} не найден")
            saved_messages = self._repository.load_messages(chat_id)
        except ChatRepositoryError as error:
            raise AgentError(f"Не удалось восстановить контекст: {error}") from error

        self._messages = [self._system_message(), *saved_messages]
        self._restored_message_count = len(saved_messages)
        self._deleted = False
        self._reload_history()

    @property
    def model(self) -> str:
        return self._config.model

    @property
    def chat_id(self) -> str:
        return self._chat_id

    @property
    def restored_message_count(self) -> int:
        return self._restored_message_count

    @property
    def history(self) -> tuple[dict[str, str], ...]:
        return tuple(message.copy() for message in self._messages)

    def set_context_window(self, tokens: int) -> None:
        if type(tokens) is not int or tokens <= 0:
            raise AgentError("Размер окна должен быть положительным целым числом")
        self._context_window_tokens = tokens

    def _reload_history(self) -> None:
        if self._deleted:
            raise AgentError("Нельзя отправить сообщение в удалённый чат")
        self._messages = [
            self._system_message(), *self._repository.load_messages(self._chat_id),
        ]
        state = self._repository.load_context(self._chat_id)
        if state:
            self._compression = CompressionSettings(**state["settings"])
            self._summary = state["summary"]
            self._covered = state["summarized_messages"]

    def set_compression(self, **changes: Any) -> None:
        self._reload_history()
        try:
            settings = replace(self._compression, **changes)
        except (ValueError, TypeError) as error:
            raise AgentError(str(error)) from error
        # При увеличении N восстановим нужный дословный хвост из полной истории.
        reset = self._covered > max(0, len(self._messages) - 1 - settings.keep_recent_messages)
        try:
            self._repository.save_compression_settings(self._chat_id, asdict(settings), reset_summary=reset)
        except ChatRepositoryError as error:
            raise AgentError(str(error)) from error
        self._reload_history()

    def _compression_state(self) -> dict[str, Any]:
        count = len(self._messages) - 1
        covered = self._covered if self._compression.enabled else 0
        return {
            **asdict(self._compression),
            "summary": self._summary,
            "summary_tokens_estimate": self._counter.text(self._summary),
            "summarized_messages": self._covered,
            "active_summary_messages": covered,
            "verbatim_messages": count - covered,
            "pending_messages": max(0, self._compression.target(count, self._covered) - self._covered),
        }

    def preview(self, user_request: str) -> dict[str, Any]:
        _, preview = self._prepare_request(user_request)
        return preview

    def _prepare_request(self, user_request: str) -> tuple[list[dict[str, str]], dict[str, Any]]:
        self._reload_history()
        prompt = user_request.strip()
        history = self._messages[1:]
        question = {"role": "user", "content": prompt}
        compression = self._compression_state()
        if self._compression.enabled:
            request_messages = [
                self._messages[0],
                *([summary_message(self._summary)] if self._summary else []),
                *history[self._covered:], question,
            ]
            input_tokens = self._counter.messages(request_messages)
            return request_messages, {
                "request_tokens_estimate": self._counter.text(prompt),
                "history_tokens_estimate": sum(self._counter.text(m["content"]) for m in history),
                "input_tokens_estimate": input_tokens,
                "full_input_tokens_estimate": self._counter.messages([self._messages[0], *history, question]),
                "included_history_messages": len(history) - self._covered,
                "omitted_history_messages": 0,
                "summarized_history_messages": self._covered,
                "context_window_tokens": self._context_window_tokens,
                "required_tokens": input_tokens,
                "fits": input_tokens <= self._context_window_tokens,
                "encoding": self._counter.encoding,
                "compression": compression,
            }
        input_tokens = self._counter.messages([self._messages[0], question])
        # Репозиторий записывает целые пары user/assistant одной транзакцией.
        # Общую обвязку начала ответа считаем один раз, в обязательной части.
        reply_overhead = self._counter.messages([])
        turn_tokens = [
            self._counter.messages(history[index:index + 2]) - reply_overhead
            for index in range(0, len(history), 2)
        ]
        full_input_tokens = input_tokens + sum(turn_tokens)
        start = len(history)
        for index in range(len(turn_tokens) - 1, -1, -1):
            if input_tokens + turn_tokens[index] > self._context_window_tokens:
                break
            input_tokens += turn_tokens[index]
            start = index * 2
        request_messages = [self._messages[0], *history[start:], question]
        return request_messages, {
            "request_tokens_estimate": self._counter.text(prompt),
            "history_tokens_estimate": sum(
                self._counter.text(message["content"]) for message in self._messages[1:]
            ),
            "input_tokens_estimate": input_tokens,
            "full_input_tokens_estimate": full_input_tokens,
            "included_history_messages": len(history) - start,
            "omitted_history_messages": start,
            "context_window_tokens": self._context_window_tokens,
            "required_tokens": input_tokens,
            "fits": input_tokens <= self._context_window_tokens,
            "encoding": self._counter.encoding,
            "summarized_history_messages": 0,
            "compression": compression,
        }

    def statistics(self) -> dict[str, Any]:
        self._reload_history()
        turns = self._repository.load_turns(self._chat_id)
        summary_calls = self._repository.load_summary_calls(self._chat_id)
        return {
            "turns": turns,
            "totals": totals(turns),
            "summary_calls": summary_calls,
            "summary_totals": totals(summary_calls),
            "overall_totals": totals([*turns, *summary_calls]),
            "compression": self._compression_state(),
            "history_tokens_estimate": sum(
                self._counter.text(message["content"]) for message in self._messages[1:]
            ),
            "untracked_turns": max(0, (len(self._messages) - 1) // 2 - len(turns)),
            "context_window_tokens": self._context_window_tokens,
            "encoding": self._counter.encoding,
        }

    def delete(self) -> None:
        try:
            self._repository.delete_chat(self._chat_id)
        except ChatRepositoryError as error:
            raise AgentError(f"Не удалось удалить чат: {error}") from error
        self._messages = [self._system_message()]
        self._restored_message_count = 0
        self._deleted = True

    def reply(self, user_request: str) -> str:
        self.last_turn = None
        if self._deleted:
            raise AgentError("Нельзя отправить сообщение в удалённый чат")

        prompt = user_request.strip()
        if not prompt:
            raise AgentError("Запрос не должен быть пустым")

        request_messages, preview = self._prepare_request(prompt)
        if preview["compression"]["pending_messages"]:
            # Не тратим запрос на summary, если уже system + вопрос не помещаются.
            minimum = self._counter.messages([self._system_message(), {"role": "user", "content": prompt}])
            if minimum > self._context_window_tokens:
                raise ContextOverflowError({**preview, "input_tokens_estimate": minimum, "required_tokens": minimum})
            self._compress_history()
            request_messages, preview = self._prepare_request(prompt)
        if not preview["fits"]:
            raise ContextOverflowError(preview)

        data = self._complete(request_messages)
        answer = self._extract_answer(data)
        finish_reason = data["choices"][0].get("finish_reason", "unknown")
        stats = {
            **preview, **self._usage_stats(data, request_messages),
            "answer_tokens_estimate": self._counter.text(answer),
            "history_after_tokens_estimate": (
                preview["history_tokens_estimate"]
                + preview["request_tokens_estimate"] + self._counter.text(answer)
            ),
            "finish_reason": finish_reason,
            "warning": (
                "API сообщил о достижении лимита длины (finish_reason=length). Ответ может быть оборван."
                if finish_reason == "length" else None
            ),
        }
        # В статистике хода достаточно параметров и счётчиков, без копии summary.
        stats["compression"] = {k: v for k, v in stats["compression"].items() if k != "summary"}
        try:
            self._repository.append_turn(self._chat_id, prompt, answer, stats)
        except ChatRepositoryError as error:
            raise AgentError(f"Не удалось сохранить контекст: {error}") from error

        self._messages.extend([
            {"role": "user", "content": prompt},
            {"role": "assistant", "content": answer},
        ])
        self.last_turn = stats
        return answer

    def _compress_history(self) -> None:
        history = self._messages[1:]
        target = self._compression.target(len(history), self._covered)
        while self._covered < target:
            # Обрабатываем старую историю по парам, чтобы запрос сжатия тоже
            # помещался в окно. В том числе при включении на длинном старом чате.
            end = self._covered
            request_messages = []
            for candidate in range(self._covered + 2, min(target, self._covered + self._compression.summary_every_messages) + 1, 2):
                proposed = summary_request(self._summary, history[self._covered:candidate], self._config.summary_max_tokens)
                if self._counter.messages(proposed) > self._context_window_tokens:
                    break
                end, request_messages = candidate, proposed
            if not request_messages:
                raise AgentError("Для сжатия не помещаются инструкция, summary и одна пара сообщений. Увеличьте лимит контекста.")
            data = self._complete(request_messages, temperature=0)
            summary = self._extract_answer(data)
            if data["choices"][0].get("finish_reason") == "length" or not summary:
                raise AgentError("Модель вернула оборванное или пустое summary. Повторите запрос.")
            if self._counter.text(summary) > self._config.summary_max_tokens:
                raise AgentError("Summary превысило LLM_SUMMARY_MAX_TOKENS. Увеличьте лимит summary или повторите запрос.")
            stats = {**self._usage_stats(data, request_messages), "summarized_messages": end}
            try:
                self._repository.save_summary(
                    self._chat_id, summary, end, self._covered, asdict(self._compression), stats,
                )
            except ChatRepositoryError as error:
                raise AgentError(str(error)) from error
            self._summary, self._covered = summary, end

    def _complete(self, request_messages: list[dict[str, str]], *, temperature: float | None = None) -> dict:
        try:
            response = self._http_client.post(
                self._config.api_url,
                headers={
                    "Authorization": f"Bearer {self._config.api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": self._config.model,
                    "messages": request_messages,
                    "temperature": self._config.temperature if temperature is None else temperature,
                },
                timeout=self._config.timeout_seconds,
            )
        except requests.RequestException as error:
            raise AgentError(f"Не удалось обратиться к LLM API: {error}") from error

        if not response.ok:
            details = response.text.strip() or "нет описания"
            raise AgentError(
                f"LLM API вернул ошибку {response.status_code}: {details}"
            )

        try:
            data = response.json()
        except ValueError as error:
            raise AgentError("LLM API вернул невалидный JSON") from error

        self._extract_answer(data)
        return data

    def _usage_stats(self, data: dict, request_messages: list[dict[str, str]]) -> dict:
        message = data["choices"][0]["message"]
        raw_content = message.get("content") or ""
        reasoning = message.get("reasoning_content") or ""
        reasoning = reasoning if isinstance(reasoning, str) else ""
        usage = completion_usage(
            data, self._counter.messages(request_messages),
            self._counter.text(raw_content) + self._counter.text(reasoning),
        )
        return {
            **usage,
            "model": self._config.model,
            "total_tokens": usage["input_tokens"] + usage["output_tokens"],
            "cost_usd": cost_usd(
                usage, self._config.input_price_per_million,
                self._config.output_price_per_million,
                self._config.cached_input_price_per_million,
            ),
            "rates_per_million": {
                "input": self._config.input_price_per_million,
                "output": self._config.output_price_per_million,
                "cached_input": self._config.cached_input_price_per_million,
            },
        }

    def _system_message(self) -> dict[str, str]:
        return {
            "role": "system",
            "content": self._config.system_prompt,
        }

    @staticmethod
    def _extract_answer(data: Any) -> str:
        try:
            content = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as error:
            raise AgentError("LLM API вернул ответ в неожиданном формате") from error

        truncated = data["choices"][0].get("finish_reason") == "length"
        if content is None and truncated:
            return ""
        if not isinstance(content, str):
            raise AgentError("LLM API вернул пустой ответ")
        answer = visible_answer(content)
        if not answer and not truncated:
            raise AgentError("LLM API вернул пустой ответ после удаления служебного блока")
        return answer


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def _boolean(name: str, default: bool) -> bool:
    value = _env(name, str(default)).lower()
    if value not in {"true", "false", "1", "0", "on", "off"}:
        raise AgentError(f"{name} должен быть true или false")
    return value in {"true", "1", "on"}


def _float_in_range(
    name: str,
    default: float,
    minimum: float,
    maximum: float,
) -> float:
    raw_value = _env(name, str(default))
    try:
        value = float(raw_value)
    except ValueError as error:
        raise AgentError(f"{name} должен быть числом") from error
    if not minimum <= value <= maximum:
        raise AgentError(
            f"{name} должен быть в диапазоне [{minimum}, {maximum}]"
        )
    return value


def _positive_float(name: str, default: float) -> float:
    raw_value = _env(name, str(default))
    try:
        value = float(raw_value)
    except ValueError as error:
        raise AgentError(f"{name} должен быть числом") from error
    if not math.isfinite(value) or value <= 0:
        raise AgentError(f"{name} должен быть больше нуля")
    return value


def _positive_int(name: str, default: int) -> int:
    try:
        value = int(_env(name, str(default)))
    except ValueError as error:
        raise AgentError(f"{name} должен быть целым числом") from error
    if value <= 0:
        raise AgentError(f"{name} должен быть больше нуля")
    return value


def _optional_price(name: str) -> float | None:
    raw = _env(name)
    if not raw:
        return None
    try:
        value = float(raw)
    except ValueError as error:
        raise AgentError(f"{name} должен быть числом") from error
    if not math.isfinite(value) or value < 0:
        raise AgentError(f"{name} должен быть конечным неотрицательным числом")
    return value
