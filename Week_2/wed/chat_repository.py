#!/usr/bin/env python3

from __future__ import annotations

import json
import sqlite3
import uuid
from contextlib import closing
from dataclasses import dataclass
from pathlib import Path

from model_output import visible_answer


DEFAULT_CHAT_TITLE = "Пустой чат"
MAX_TITLE_LENGTH = 60


class ChatRepositoryError(RuntimeError):
    """Ошибка чтения или записи истории чатов."""


@dataclass(frozen=True)
class Chat:
    id: str
    title: str
    created_at: str
    updated_at: str
    message_count: int


class ChatRepository:
    """Хранилище чатов и сообщений в SQLite."""

    def __init__(self, database_path: str) -> None:
        self._database_path = Path(database_path)
        self._initialize()

    @property
    def database_path(self) -> Path:
        return self._database_path

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(str(self._database_path), timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        return connection

    def _initialize(self) -> None:
        try:
            self._database_path.parent.mkdir(parents=True, exist_ok=True)
            with closing(self._connect()) as connection:
                with connection:
                    connection.executescript(
                        """
                        CREATE TABLE IF NOT EXISTS chats (
                            id TEXT PRIMARY KEY,
                            title TEXT NOT NULL,
                            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        );

                        CREATE TABLE IF NOT EXISTS messages (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            chat_id TEXT NOT NULL,
                            role TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
                            content TEXT NOT NULL,
                            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            FOREIGN KEY (chat_id) REFERENCES chats(id)
                                ON DELETE CASCADE
                        );

                        CREATE INDEX IF NOT EXISTS messages_chat_id_id
                            ON messages(chat_id, id);

                        CREATE TABLE IF NOT EXISTS turns (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            chat_id TEXT NOT NULL REFERENCES chats(id)
                                ON DELETE CASCADE,
                            stats_json TEXT NOT NULL
                        );
                        CREATE INDEX IF NOT EXISTS turns_chat_id_id
                            ON turns(chat_id, id);
                        """
                    )
        except (OSError, sqlite3.Error) as error:
            raise ChatRepositoryError(
                f"Не удалось открыть базу истории {self._database_path}: {error}"
            ) from error

    def create_chat(self, title: str = DEFAULT_CHAT_TITLE) -> Chat:
        chat_id = uuid.uuid4().hex
        normalized_title = title.strip() or DEFAULT_CHAT_TITLE
        try:
            with closing(self._connect()) as connection:
                with connection:
                    connection.execute(
                        "INSERT INTO chats(id, title) VALUES (?, ?)",
                        (chat_id, normalized_title),
                    )
        except sqlite3.Error as error:
            raise ChatRepositoryError(f"Не удалось создать чат: {error}") from error

        chat = self.get_chat(chat_id)
        if chat is None:
            raise ChatRepositoryError("Созданный чат не найден в базе")
        return chat

    def get_chat(self, chat_id: str) -> Chat | None:
        query = """
            SELECT
                chats.id,
                chats.title,
                chats.created_at,
                chats.updated_at,
                COUNT(messages.id) AS message_count
            FROM chats
            LEFT JOIN messages ON messages.chat_id = chats.id
            WHERE chats.id = ?
            GROUP BY chats.id
        """
        try:
            with closing(self._connect()) as connection:
                row = connection.execute(query, (chat_id,)).fetchone()
        except sqlite3.Error as error:
            raise ChatRepositoryError(f"Не удалось прочитать чат: {error}") from error
        return _chat_from_row(row) if row is not None else None

    def list_chats(self) -> list[Chat]:
        query = """
            SELECT
                chats.id,
                chats.title,
                chats.created_at,
                chats.updated_at,
                COUNT(messages.id) AS message_count
            FROM chats
            LEFT JOIN messages ON messages.chat_id = chats.id
            GROUP BY chats.id
            ORDER BY chats.updated_at DESC, chats.created_at DESC
        """
        try:
            with closing(self._connect()) as connection:
                rows = connection.execute(query).fetchall()
        except sqlite3.Error as error:
            raise ChatRepositoryError(
                f"Не удалось получить список чатов: {error}"
            ) from error
        return [_chat_from_row(row) for row in rows]

    def load_messages(self, chat_id: str) -> list[dict[str, str]]:
        try:
            with closing(self._connect()) as connection:
                rows = connection.execute(
                    """
                    SELECT role, content
                    FROM messages
                    WHERE chat_id = ?
                    ORDER BY id
                    """,
                    (chat_id,),
                ).fetchall()
        except sqlite3.Error as error:
            raise ChatRepositoryError(
                f"Не удалось загрузить сообщения: {error}"
            ) from error
        return [
            {
                "role": str(row["role"]),
                "content": (
                    visible_answer(str(row["content"]))
                    if row["role"] == "assistant" else str(row["content"])
                ),
            }
            for row in rows
        ]

    def append_turn(
        self,
        chat_id: str,
        user_message: str,
        assistant_message: str,
        stats: dict | None = None,
    ) -> None:
        try:
            with closing(self._connect()) as connection:
                with connection:
                    chat = connection.execute(
                        "SELECT title FROM chats WHERE id = ?",
                        (chat_id,),
                    ).fetchone()
                    if chat is None:
                        raise ChatRepositoryError(f"Чат {chat_id} не найден")

                    has_messages = connection.execute(
                        "SELECT EXISTS(SELECT 1 FROM messages WHERE chat_id = ?)",
                        (chat_id,),
                    ).fetchone()[0]
                    connection.executemany(
                        """
                        INSERT INTO messages(chat_id, role, content)
                        VALUES (?, ?, ?)
                        """,
                        [
                            (chat_id, "user", user_message),
                            (chat_id, "assistant", assistant_message),
                        ],
                    )

                    if stats is not None:
                        connection.execute(
                            "INSERT INTO turns(chat_id, stats_json) VALUES (?, ?)",
                            (chat_id, json.dumps(stats, ensure_ascii=False, allow_nan=False)),
                        )

                    title = str(chat["title"])
                    if not has_messages and title == DEFAULT_CHAT_TITLE:
                        title = _title_from_message(user_message)
                    connection.execute(
                        """
                        UPDATE chats
                        SET title = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                        (title, chat_id),
                    )
        except sqlite3.Error as error:
            raise ChatRepositoryError(
                f"Не удалось сохранить сообщения: {error}"
            ) from error

    def load_turns(self, chat_id: str) -> list[dict]:
        try:
            with closing(self._connect()) as connection:
                rows = connection.execute(
                    "SELECT stats_json FROM turns WHERE chat_id = ? ORDER BY id",
                    (chat_id,),
                ).fetchall()
            return [json.loads(row["stats_json"]) for row in rows]
        except (sqlite3.Error, ValueError) as error:
            raise ChatRepositoryError(f"Не удалось прочитать статистику: {error}") from error

    def delete_chat(self, chat_id: str) -> None:
        try:
            with closing(self._connect()) as connection:
                with connection:
                    cursor = connection.execute(
                        "DELETE FROM chats WHERE id = ?",
                        (chat_id,),
                    )
                    if cursor.rowcount == 0:
                        raise ChatRepositoryError(f"Чат {chat_id} не найден")
        except sqlite3.Error as error:
            raise ChatRepositoryError(
                f"Не удалось удалить чат: {error}"
            ) from error

    def delete_chat_if_empty(self, chat_id: str) -> bool:
        try:
            with closing(self._connect()) as connection:
                with connection:
                    cursor = connection.execute(
                        """
                        DELETE FROM chats
                        WHERE id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM messages WHERE chat_id = chats.id
                          )
                        """,
                        (chat_id,),
                    )
        except sqlite3.Error as error:
            raise ChatRepositoryError(
                f"Не удалось удалить пустой чат: {error}"
            ) from error
        return cursor.rowcount > 0

    def delete_empty_chats(self) -> list[str]:
        try:
            with closing(self._connect()) as connection:
                with connection:
                    rows = connection.execute(
                        """
                        SELECT id
                        FROM chats
                        WHERE NOT EXISTS (
                            SELECT 1 FROM messages WHERE chat_id = chats.id
                        )
                        """
                    ).fetchall()
                    chat_ids = [str(row["id"]) for row in rows]
                    connection.executemany(
                        "DELETE FROM chats WHERE id = ?",
                        [(chat_id,) for chat_id in chat_ids],
                    )
        except sqlite3.Error as error:
            raise ChatRepositoryError(
                f"Не удалось удалить пустые чаты: {error}"
            ) from error
        return chat_ids


def _chat_from_row(row: sqlite3.Row) -> Chat:
    return Chat(
        id=str(row["id"]),
        title=str(row["title"]),
        created_at=str(row["created_at"]),
        updated_at=str(row["updated_at"]),
        message_count=int(row["message_count"]),
    )


def _title_from_message(message: str) -> str:
    normalized = " ".join(message.split())
    if len(normalized) <= MAX_TITLE_LENGTH:
        return normalized
    return f"{normalized[: MAX_TITLE_LENGTH - 1].rstrip()}…"
