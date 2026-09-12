#!/usr/bin/env python3

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import requests

from chat_repository import ChatRepository, ChatRepositoryError


class AgentError(RuntimeError):
    """Понятная пользователю ошибка при работе агента."""


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
        )


class SimpleAgent:
    """LLM-агент, восстанавливающий контекст выбранного чата из SQLite."""

    def __init__(
        self,
        config: AgentConfig,
        chat_id: str,
        repository: ChatRepository | None = None,
        http_client: HttpClient | None = None,
    ) -> None:
        self._config = config
        self._chat_id = chat_id
        self._repository = repository or ChatRepository(config.database_path)
        self._http_client = (
            http_client if http_client is not None else requests.Session()
        )

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

    def delete(self) -> None:
        try:
            self._repository.delete_chat(self._chat_id)
        except ChatRepositoryError as error:
            raise AgentError(f"Не удалось удалить чат: {error}") from error
        self._messages = [self._system_message()]
        self._restored_message_count = 0
        self._deleted = True

    def reply(self, user_request: str) -> str:
        if self._deleted:
            raise AgentError("Нельзя отправить сообщение в удалённый чат")

        prompt = user_request.strip()
        if not prompt:
            raise AgentError("Запрос не должен быть пустым")

        request_messages = [
            *(message.copy() for message in self._messages),
            {"role": "user", "content": prompt},
        ]

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
        try:
            self._repository.append_turn(self._chat_id, prompt, answer)
        except ChatRepositoryError as error:
            raise AgentError(f"Не удалось сохранить контекст: {error}") from error

        self._messages.extend(
            [
                {"role": "user", "content": prompt},
                {"role": "assistant", "content": answer},
            ]
        )
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

        if not isinstance(content, str) or not content.strip():
            raise AgentError("LLM API вернул пустой ответ")
        return content.strip()


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
    if value <= 0:
        raise AgentError(f"{name} должен быть больше нуля")
    return value
