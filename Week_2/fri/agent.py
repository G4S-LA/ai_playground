#!/usr/bin/env python3

from __future__ import annotations

import math
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import requests

from chat_repository import ChatRepository
from context_strategies import (ContextSettings, STRATEGIES, facts_json, facts_request,
                                facts_repair_request, parse_facts)
from model_output import visible_answer
from token_usage import TokenCounter, completion_usage, cost_usd, totals


MAX_FACTS_REPAIR_ATTEMPTS = 3


class AgentError(RuntimeError):
    """Понятная пользователю ошибка при работе агента."""


class ContextOverflowError(AgentError):
    def __init__(self, preview: dict[str, Any]) -> None:
        self.preview = preview
        super().__init__(
            "Переполнение локального контекста: "
            f"обязательная часть запроса ≈{preview['input_tokens_estimate']} > "
            f"окно {preview['context_window_tokens']}. "
            "Сократите вопрос. Для других настроек создайте новый чат."
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
    strategy: str = "sliding_window"
    keep_recent_messages: int = 10
    facts_max_tokens: int = 1024

    def __post_init__(self) -> None:
        try:
            self.context_settings()
        except ValueError as error:
            raise AgentError(str(error)) from error
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
            strategy=_env("LLM_CONTEXT_STRATEGY", "sliding_window"),
            keep_recent_messages=_positive_int("LLM_KEEP_RECENT_MESSAGES", 10),
            facts_max_tokens=_positive_int("LLM_FACTS_MAX_TOKENS", 1024),
        )

    def context_settings(self) -> ContextSettings:
        return ContextSettings(self.strategy, self.keep_recent_messages,
                               self.context_window_tokens, self.facts_max_tokens)


class SimpleAgent:
    """Два способа отбора памяти плюс независимые ветки из checkpoints."""

    def __init__(self, config: AgentConfig, chat_id: str,
                 repository: ChatRepository | None = None,
                 http_client: HttpClient | None = None,
                 token_counter: TokenCounter | None = None):
        self._config, self._chat_id = config, chat_id
        self._repository = repository or ChatRepository(config.database_path)
        self._http_client = http_client if http_client is not None else requests.Session()
        try:
            self._counter = token_counter or TokenCounter(config.token_encoding)
        except Exception as error:
            raise AgentError(f"Не удалось загрузить токенизатор: {error}") from error
        self._restored_message_count = len(self._repository.load_state(chat_id)["messages"])
        self.last_turn = None

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
    def history(self) -> tuple[dict, ...]:
        return tuple([self._system_message(), *self._repository.load_messages(self.chat_id)])

    def configure(self, **changes) -> None:
        try:
            self._repository.configure(self.chat_id, changes)
        except (ValueError, TypeError) as error:
            raise AgentError(str(error)) from error

    def set_context_window(self, tokens: int) -> None:
        self.configure(context_window_tokens=tokens)

    def checkpoint(self, title="Checkpoint") -> str:
        return self._repository.create_checkpoint(self.chat_id, title)

    def copy(self, title=None):
        return self._repository.copy_chat(self.chat_id, title)

    def branch(self, checkpoint_id, title="Новая ветка"):
        return self._repository.branch(checkpoint_id, title)

    def _context(self, state: dict) -> dict:
        return {**state["settings"], "facts": state["facts"],
                "facts_tokens_estimate": self._counter.text(facts_json(state["facts"])),
                "settings_locked": state["locked"], "message_count": len(state["messages"])}

    def _prepare_request(self, prompt: str, state: dict, facts: dict | None = None):
        settings = ContextSettings(**state["settings"])
        history = state["messages"]
        recent = [*history, {"role": "user", "content": prompt}][-settings.keep_recent_messages:]
        messages = STRATEGIES[settings.strategy].messages(
            self._system_message(), recent, state["facts"] if facts is None else facts,
        )
        tokens = self._counter.messages(messages)
        return messages, {
            "request_tokens_estimate": self._counter.text(prompt),
            "history_tokens_estimate": sum(self._counter.text(item["content"]) for item in history),
            "input_tokens_estimate": tokens,
            "included_history_messages": len(recent) - 1,
            "omitted_history_messages": len(history) - len(recent) + 1,
            "context_window_tokens": settings.context_window_tokens,
            "fits": tokens <= settings.context_window_tokens,
            "facts_update_pending": settings.strategy == "facts" and facts is None and bool(prompt),
            "encoding": self._counter.encoding,
            "context": self._context(state),
        }

    def preview(self, user_request: str) -> dict:
        # Чистый прогноз: не сохраняет настройки и не обращается к модели.
        state = self._repository.load_state(self.chat_id)
        return self._prepare_request(user_request.strip(), state)[1]

    def statistics(self) -> dict:
        state = self._repository.load_state(self.chat_id)
        turns = self._repository.load_turns(self.chat_id)
        calls = self._repository.load_facts_calls(self.chat_id)
        own_turns = [item for item in turns if not item.get("inherited")]
        own_calls = [item for item in calls if not item.get("inherited")]
        return {
            "turns": turns, "totals": totals(own_turns),
            "facts_calls": own_calls, "facts_totals": totals(own_calls),
            "overall_totals": totals([*own_turns, *own_calls]),
            "inherited_totals": totals([item for item in [*turns, *calls] if item.get("inherited")]),
            "context": self._context(state), "encoding": self._counter.encoding,
            "history_tokens_estimate": sum(self._counter.text(item["content"]) for item in state["messages"]),
        }

    def delete(self) -> None:
        self._repository.delete_chat(self.chat_id)

    def reply(self, user_request: str) -> str:
        self.last_turn = None
        prompt = user_request.strip()
        if not prompt:
            raise AgentError("Запрос не должен быть пустым")
        state = self._repository.begin_turn(self.chat_id)
        settings = ContextSettings(**state["settings"])
        facts = state["facts"]
        if settings.strategy == "facts":
            recent = [*state["messages"], {"role": "user", "content": prompt}][-settings.keep_recent_messages:]
            facts = self._update_facts(facts, recent, settings)

        request_messages, preview = self._prepare_request(prompt, state, facts)
        if not preview["fits"]:
            raise ContextOverflowError(preview)
        data = self._complete(request_messages)
        answer = self._extract_answer(data)
        finish_reason = data["choices"][0].get("finish_reason", "unknown")
        stats = {
            **{key: value for key, value in preview.items() if key != "context"},
            **self._usage_stats(data, request_messages), "strategy": settings.strategy,
            "answer_tokens_estimate": self._counter.text(answer),
            "history_after_tokens_estimate": preview["history_tokens_estimate"] +
                self._counter.text(prompt) + self._counter.text(answer),
            "finish_reason": finish_reason,
            "warning": "Ответ может быть оборван: API сообщил finish_reason=length."
                if finish_reason == "length" else None,
        }
        # История и новая память образуют один сохранённый ход. Ошибка не оставляет
        # facts от вопроса, которого нет в диалоге; повторная отправка безопасна.
        self._repository.append_turn(self.chat_id, prompt, answer, stats, facts, state["version"])
        self.last_turn = stats
        return answer

    def _update_facts(self, saved_facts: dict[str, str], recent: list[dict], settings: ContextSettings) -> dict[str, str]:
        update = facts_request(saved_facts, recent, settings.facts_max_tokens)
        for repair_attempt in range(MAX_FACTS_REPAIR_ATTEMPTS + 1):
            update_tokens = self._counter.messages(update)
            if update_tokens > settings.context_window_tokens:
                operation = "исправления" if repair_attempt else "обновления"
                raise AgentError(f"Запрос {operation} facts не помещается в окно: ≈{update_tokens} > "
                                 f"{settings.context_window_tokens}. Сократите вопрос или создайте чат с большим окном.")
            data = self._complete(update, temperature=0, allow_empty=True)
            raw_facts = self._extract_answer(data, allow_empty=True)
            validation_error = None
            try:
                if data["choices"][0].get("finish_reason") == "length":
                    raise ValueError("Ответ с facts оборван (finish_reason=length). Нужен полный JSON-объект.")
                facts = parse_facts(raw_facts)
                facts_tokens = self._counter.text(facts_json(facts))
                if facts_tokens > settings.facts_max_tokens:
                    raise ValueError(f"Facts превысили лимит токенов: ≈{facts_tokens} > {settings.facts_max_tokens}.")
            except ValueError as error:
                validation_error = str(error)

            # Учитываем каждый полученный ответ, даже невалидный. Ошибочные
            # варианты facts остаются только в запросах исправления, вне истории.
            self._repository.save_facts_call(self.chat_id, {
                **self._usage_stats(data, update), "repair_attempt": repair_attempt,
                "validation_error": validation_error,
            })
            if validation_error is None:
                return facts
            if repair_attempt == MAX_FACTS_REPAIR_ATTEMPTS:
                raise AgentError(
                    f"Модель вернула невалидные facts после {MAX_FACTS_REPAIR_ATTEMPTS} попыток исправления. "
                    f"Последняя ошибка: {validation_error}"
                )
            # Каждая правка получает исходные данные и только последний результат:
            # предыдущие неудачные варианты не накапливаются в контексте.
            update = facts_repair_request(saved_facts, recent, settings.facts_max_tokens,
                                          raw_facts, validation_error, repair_attempt + 1)

    def _complete(self, request_messages: list[dict[str, str]], *, temperature: float | None = None,
                  allow_empty: bool = False) -> dict:
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

        self._extract_answer(data, allow_empty=allow_empty)
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
    def _extract_answer(data: Any, *, allow_empty: bool = False) -> str:
        try:
            content = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as error:
            raise AgentError("LLM API вернул ответ в неожиданном формате") from error

        truncated = data["choices"][0].get("finish_reason") == "length"
        if content is None and (truncated or allow_empty):
            return ""
        if not isinstance(content, str):
            raise AgentError("LLM API вернул пустой ответ")
        answer = visible_answer(content)
        if not answer and not truncated and not allow_empty:
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
