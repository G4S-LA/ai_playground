#!/usr/bin/env python3

from __future__ import annotations

import math
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import requests

from chat_repository import ChatRepository, ChatRepositoryError
from model_output import visible_answer
from token_usage import TokenCounter, completion_usage, cost_usd, totals


class AgentError(RuntimeError):
    """Понятная пользователю ошибка при работе агента."""


class ContextOverflowError(AgentError):
    def __init__(self, preview: dict[str, Any]) -> None:
        self.preview = preview
        super().__init__(
            "Переполнение локального контекста: "
            f"инструкция и текущий вопрос ≈{preview['input_tokens_estimate']} > "
            f"окно {preview['context_window_tokens']}. "
            "Даже без истории запрос не помещается. Увеличьте окно или сократите вопрос либо инструкцию."
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

    def __post_init__(self) -> None:
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

    def preview(self, user_request: str) -> dict[str, Any]:
        _, preview = self._prepare_request(user_request)
        return preview

    def _prepare_request(self, user_request: str) -> tuple[list[dict[str, str]], dict[str, Any]]:
        self._reload_history()
        prompt = user_request.strip()
        history = self._messages[1:]
        question = {"role": "user", "content": prompt}
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
        }

    def statistics(self) -> dict[str, Any]:
        self._reload_history()
        turns = self._repository.load_turns(self._chat_id)
        return {
            "turns": turns,
            "totals": totals(turns),
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
        if not preview["fits"]:
            raise ContextOverflowError(preview)

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
                    "temperature": self._config.temperature,
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

        answer = self._extract_answer(data)
        finish_reason = data["choices"][0].get("finish_reason", "unknown")
        message = data["choices"][0]["message"]
        raw_content = message.get("content") or ""
        reasoning = message.get("reasoning_content") or ""
        reasoning = reasoning if isinstance(reasoning, str) else ""
        usage = completion_usage(
            data, preview["input_tokens_estimate"],
            self._counter.text(raw_content) + self._counter.text(reasoning),
        )
        stats = {
            **preview, **usage,
            "model": self._config.model,
            "total_tokens": usage["input_tokens"] + usage["output_tokens"],
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
        try:
            self._repository.append_turn(self._chat_id, prompt, answer, stats)
        except ChatRepositoryError as error:
            raise AgentError(f"Не удалось сохранить контекст: {error}") from error

        self._messages.extend(
            [
                {"role": "user", "content": prompt},
                {"role": "assistant", "content": answer},
            ]
        )
        self.last_turn = stats
        return answer

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
