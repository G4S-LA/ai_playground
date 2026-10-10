#!/usr/bin/env python3
"""Private local Ollama chat with persistent SQLite conversations."""

from __future__ import annotations

import hmac
import json
import os
import secrets
import socket
import sqlite3
import sys
import threading
import uuid
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
MAX_REQUEST_BYTES = 1024 * 1024


def configure_console_encoding() -> None:
    """Keep Russian CLI messages readable in terminals with a legacy locale."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


class ApiError(RuntimeError):
    def __init__(self, status: int, message: str, code: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code


def _read_dotenv(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    result: dict[str, str] = {}
    for number, source in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = source.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise RuntimeError(f"Некорректная строка {number} в {path.name}")
        name, value = line.split("=", 1)
        name = name.strip()
        if not name.replace("_", "a").isalnum() or name[0].isdigit():
            raise RuntimeError(f"Некорректное имя переменной в строке {number}")
        result[name] = value.strip().strip("\"'")
    return result


def _positive_int(settings: dict[str, str], name: str, default: int) -> int:
    try:
        value = int(settings.get(name, str(default)))
    except ValueError as error:
        raise RuntimeError(f"{name} должна быть целым числом") from error
    if value <= 0:
        raise RuntimeError(f"{name} должна быть больше нуля")
    return value


@dataclass(frozen=True)
class Config:
    host: str
    port: int
    api_key: str
    ollama_url: str
    model: str
    database_path: Path
    ollama_timeout_seconds: int

    @classmethod
    def load(
        cls,
        environment: dict[str, str] | None = None,
        dotenv_path: Path = BASE_DIR / ".env",
    ) -> "Config":
        settings = _read_dotenv(dotenv_path)
        settings.update(environment if environment is not None else os.environ)
        api_key = settings.get("SERVICE_API_KEY", "").strip()
        if len(api_key) < 24 or api_key == "replace-with-a-long-random-secret":
            raise RuntimeError(
                "Не задан безопасный SERVICE_API_KEY. Выполните: python3 server.py init"
            )
        port = _positive_int(settings, "SERVICE_PORT", 8080)
        if port > 65_535:
            raise RuntimeError("SERVICE_PORT должен быть в диапазоне 1..65535")
        database = Path(settings.get("CHAT_DATABASE", "data/local-chat.db"))
        if not database.is_absolute():
            database = BASE_DIR / database
        return cls(
            host=settings.get("SERVICE_HOST", "0.0.0.0").strip() or "0.0.0.0",
            port=port,
            api_key=api_key,
            ollama_url=settings.get("OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/"),
            model=settings.get("OLLAMA_MODEL", "qwen3:8b").strip() or "qwen3:8b",
            database_path=database.resolve(),
            ollama_timeout_seconds=_positive_int(settings, "OLLAMA_TIMEOUT_SECONDS", 300),
        )


class OllamaClient:
    def __init__(self, config: Config) -> None:
        self.config = config

    def health(self) -> dict[str, Any]:
        request = Request(f"{self.config.ollama_url}/api/tags", method="GET")
        try:
            with urlopen(request, timeout=3) as response:
                payload = json.load(response)
            models = [item.get("name", "") for item in payload.get("models", [])]
            installed = any(self._same_model(name, self.config.model) for name in models)
            return {
                "reachable": True,
                "model_installed": installed,
                "available_models": models,
            }
        except (OSError, ValueError, URLError, HTTPError) as error:
            return {
                "reachable": False,
                "model_installed": False,
                "available_models": [],
                "error": str(error),
            }

    def chat(self, messages: list[dict[str, str]]) -> str:
        body = json.dumps(
            {
                "model": self.config.model,
                "messages": messages,
                "stream": False,
            },
            ensure_ascii=False,
        ).encode("utf-8")
        request = Request(
            f"{self.config.ollama_url}/api/chat",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=self.config.ollama_timeout_seconds) as response:
                payload = json.load(response)
        except HTTPError as error:
            with error:
                details = error.read().decode("utf-8", errors="replace")[:500]
            raise ApiError(HTTPStatus.BAD_GATEWAY, f"Ollama: HTTP {error.code}: {details}", "upstream_error") from error
        except (TimeoutError, socket.timeout) as error:
            raise ApiError(HTTPStatus.GATEWAY_TIMEOUT, "Ollama не ответила вовремя", "upstream_timeout") from error
        except (OSError, ValueError, URLError) as error:
            raise ApiError(HTTPStatus.BAD_GATEWAY, f"Ollama недоступна: {error}", "upstream_unavailable") from error

        content = payload.get("message", {}).get("content", "")
        if not isinstance(content, str) or not content.strip():
            raise ApiError(HTTPStatus.BAD_GATEWAY, "Ollama вернула пустой ответ", "empty_upstream_response")
        return content.strip()

    @staticmethod
    def _same_model(installed: str, requested: str) -> bool:
        return installed == requested or installed.removesuffix(":latest") == requested.removesuffix(":latest")


class ChatRepository:
    def __init__(self, database_path: Path) -> None:
        self.database_path = database_path
        database_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA journal_mode = WAL")
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS chats (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
                );

                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
                    role TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
                );

                CREATE INDEX IF NOT EXISTS messages_chat_id ON messages(chat_id, id);
                """
            )

    def create_chat(self) -> dict[str, Any]:
        chat_id = uuid.uuid4().hex
        with self._connect() as connection:
            connection.execute(
                "INSERT INTO chats (id, title) VALUES (?, ?)",
                (chat_id, "Новый диалог"),
            )
        return self.get_chat(chat_id)

    def list_chats(self) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT c.id, c.title, c.created_at, c.updated_at, COUNT(m.id) AS message_count
                FROM chats c
                LEFT JOIN messages m ON m.chat_id = c.id
                GROUP BY c.id
                ORDER BY c.updated_at DESC, c.created_at DESC
                """
            ).fetchall()
        return [self._chat_summary(row) for row in rows]

    def get_chat(self, chat_id: str) -> dict[str, Any]:
        with self._connect() as connection:
            chat = connection.execute(
                "SELECT id, title, created_at, updated_at FROM chats WHERE id = ?",
                (chat_id,),
            ).fetchone()
            if chat is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "Диалог не найден", "chat_not_found")
            messages = connection.execute(
                """
                SELECT id, role, content, created_at
                FROM messages
                WHERE chat_id = ?
                ORDER BY id
                """,
                (chat_id,),
            ).fetchall()
        return {
            "id": chat["id"],
            "title": chat["title"],
            "createdAt": chat["created_at"],
            "updatedAt": chat["updated_at"],
            "messages": [
                {
                    "id": message["id"],
                    "role": message["role"],
                    "content": message["content"],
                    "createdAt": message["created_at"],
                }
                for message in messages
            ],
        }

    def add_exchange(self, chat_id: str, user_message: str, assistant_message: str) -> dict[str, Any]:
        with self._connect() as connection:
            chat = connection.execute(
                "SELECT title FROM chats WHERE id = ?",
                (chat_id,),
            ).fetchone()
            if chat is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "Диалог не найден", "chat_not_found")
            connection.execute(
                "INSERT INTO messages (chat_id, role, content) VALUES (?, 'user', ?)",
                (chat_id, user_message),
            )
            connection.execute(
                "INSERT INTO messages (chat_id, role, content) VALUES (?, 'assistant', ?)",
                (chat_id, assistant_message),
            )
            title = chat["title"]
            if title == "Новый диалог":
                title = self._title_from(user_message)
            connection.execute(
                """
                UPDATE chats
                SET title = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
                WHERE id = ?
                """,
                (title, chat_id),
            )
        return self.get_chat(chat_id)

    def delete_chat(self, chat_id: str) -> None:
        with self._connect() as connection:
            cursor = connection.execute("DELETE FROM chats WHERE id = ?", (chat_id,))
            if cursor.rowcount == 0:
                raise ApiError(HTTPStatus.NOT_FOUND, "Диалог не найден", "chat_not_found")

    @staticmethod
    def _chat_summary(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": row["id"],
            "title": row["title"],
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
            "messageCount": row["message_count"],
        }

    @staticmethod
    def _title_from(message: str) -> str:
        one_line = " ".join(message.split())
        return one_line[:60] + ("…" if len(one_line) > 60 else "")


class ChatService:
    def __init__(
        self,
        config: Config,
        repository: ChatRepository | None = None,
        ollama: OllamaClient | Any | None = None,
    ) -> None:
        self.config = config
        self.repository = repository or ChatRepository(config.database_path)
        self.ollama = ollama or OllamaClient(config)
        self._send_lock = threading.Lock()

    def health(self) -> dict[str, Any]:
        upstream = self.ollama.health()
        ready = bool(upstream.get("reachable") and upstream.get("model_installed"))
        return {
            "status": "ok" if ready else "degraded",
            "ready": ready,
            "service": "private-local-chat",
            "model": self.config.model,
            "storage": "SQLite",
            "ollama": upstream,
        }

    def send(self, chat_id: str, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict) or not isinstance(payload.get("message"), str):
            raise ApiError(HTTPStatus.BAD_REQUEST, "Нужен JSON вида {\"message\": \"...\"}", "invalid_message")
        message = payload["message"].strip()
        if not message:
            raise ApiError(HTTPStatus.BAD_REQUEST, "Сообщение не должно быть пустым", "empty_message")

        with self._send_lock:
            chat = self.repository.get_chat(chat_id)
            history = [
                {"role": item["role"], "content": item["content"]}
                for item in chat["messages"]
            ]
            answer = self.ollama.chat(history + [{"role": "user", "content": message}])
            return self.repository.add_exchange(chat_id, message, answer)


class LocalChatHttpServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], service: ChatService) -> None:
        super().__init__(address, LocalChatHandler)
        self.service = service


class LocalChatHandler(BaseHTTPRequestHandler):
    server: LocalChatHttpServer
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._send_json(HTTPStatus.OK, self.server.service.health())
            return
        assets = {
            "/": ("index.html", "text/html; charset=utf-8"),
            "/app.js": ("app.js", "text/javascript; charset=utf-8"),
            "/styles.css": ("styles.css", "text/css; charset=utf-8"),
        }
        if path in assets:
            filename, content_type = assets[path]
            try:
                self._send_bytes(HTTPStatus.OK, (STATIC_DIR / filename).read_bytes(), content_type)
            except OSError:
                self._send_error(HTTPStatus.INTERNAL_SERVER_ERROR, "Статический файл не найден", "asset_missing")
            return
        self._run_api(lambda: self._get_api(path))

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        self._run_api(lambda: self._post_api(path))

    def do_DELETE(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        self._run_api(lambda: self._delete_api(path))

    def _get_api(self, path: str) -> None:
        repository = self.server.service.repository
        if path == "/api/chats":
            self._send_json(HTTPStatus.OK, {"chats": repository.list_chats()})
            return
        chat_id = self._chat_id(path)
        if chat_id is not None:
            self._send_json(HTTPStatus.OK, {"chat": repository.get_chat(chat_id)})
            return
        raise ApiError(HTTPStatus.NOT_FOUND, "Маршрут не найден", "not_found")

    def _post_api(self, path: str) -> None:
        repository = self.server.service.repository
        if path == "/api/chats":
            self._read_json()
            self._send_json(HTTPStatus.CREATED, {"chat": repository.create_chat()})
            return
        chat_id = self._chat_id(path, suffix="/messages")
        if chat_id is not None:
            chat = self.server.service.send(chat_id, self._read_json())
            self._send_json(HTTPStatus.OK, {"chat": chat})
            return
        raise ApiError(HTTPStatus.NOT_FOUND, "Маршрут не найден", "not_found")

    def _delete_api(self, path: str) -> None:
        chat_id = self._chat_id(path)
        if chat_id is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "Маршрут не найден", "not_found")
        self.server.service.repository.delete_chat(chat_id)
        self._send_bytes(HTTPStatus.NO_CONTENT, b"", "application/json")

    def _run_api(self, action: Callable[[], None]) -> None:
        try:
            self._authenticate()
            action()
        except ApiError as error:
            self._send_error(error.status, str(error), error.code)
        except sqlite3.Error:
            self._send_error(HTTPStatus.INTERNAL_SERVER_ERROR, "Ошибка хранилища SQLite", "storage_error")

    def _authenticate(self) -> None:
        authorization = self.headers.get("Authorization", "")
        expected = f"Bearer {self.server.service.config.api_key}"
        if not hmac.compare_digest(authorization, expected):
            raise ApiError(HTTPStatus.UNAUTHORIZED, "Неверный или отсутствующий Bearer token", "unauthorized")

    def _read_json(self) -> Any:
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError as error:
            raise ApiError(HTTPStatus.LENGTH_REQUIRED, "Нужен Content-Length", "length_required") from error
        if length <= 0:
            raise ApiError(HTTPStatus.BAD_REQUEST, "Пустое тело запроса", "empty_body")
        if length > MAX_REQUEST_BYTES:
            raise ApiError(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "Слишком большое тело запроса", "body_too_large")
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
            raise ApiError(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "Нужен Content-Type: application/json", "invalid_content_type")
        try:
            return json.loads(self.rfile.read(length))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ApiError(HTTPStatus.BAD_REQUEST, "Некорректный JSON", "invalid_json") from error

    @staticmethod
    def _chat_id(path: str, suffix: str = "") -> str | None:
        prefix = "/api/chats/"
        if not path.startswith(prefix) or (suffix and not path.endswith(suffix)):
            return None
        end = -len(suffix) if suffix else None
        chat_id = path[len(prefix):end]
        return chat_id if chat_id and "/" not in chat_id else None

    def _send_error(self, status: int, message: str, code: str) -> None:
        self._send_json(
            status,
            {"error": {"message": message, "type": "local_chat_error", "code": code}},
        )

    def _send_json(self, status: int, payload: Any) -> None:
        self._send_bytes(
            status,
            json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            "application/json; charset=utf-8",
        )

    def _send_bytes(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, template: str, *args: Any) -> None:
        sys.stderr.write(f"{self.log_date_time_string()} · {self.client_address[0]} · {template % args}\n")


def initialize_env(path: Path = BASE_DIR / ".env") -> None:
    if path.exists():
        print(f"{path} уже существует; файл не изменён.")
        return
    content = (
        f"SERVICE_API_KEY={secrets.token_urlsafe(32)}\n"
        "SERVICE_HOST=0.0.0.0\n"
        "SERVICE_PORT=8080\n"
        "OLLAMA_URL=http://127.0.0.1:11434\n"
        "OLLAMA_MODEL=qwen3:8b\n"
        "CHAT_DATABASE=data/local-chat.db\n"
        "OLLAMA_TIMEOUT_SECONDS=300\n"
    )
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as target:
        target.write(content)
    print(f"Создан {path} с правами 600 и случайным API-ключом.")


def main() -> None:
    if len(sys.argv) == 2 and sys.argv[1] == "init":
        initialize_env()
        return
    if len(sys.argv) > 1:
        raise RuntimeError("Использование: python3 server.py [init]")
    config = Config.load()
    service = ChatService(config)
    server = LocalChatHttpServer((config.host, config.port), service)
    print(f"Private Local Chat: http://{config.host}:{config.port}")
    print(f"Ollama: {config.ollama_url} · модель: {config.model}")
    print(f"Диалоги: {config.database_path}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nСервис остановлен.")
    finally:
        server.server_close()


if __name__ == "__main__":
    configure_console_encoding()
    try:
        main()
    except RuntimeError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        raise SystemExit(1) from error
