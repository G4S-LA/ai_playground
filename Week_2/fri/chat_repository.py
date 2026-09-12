"""SQLite: архив диалогов, настройки, facts и неизменяемые checkpoints."""

from __future__ import annotations

import json
import sqlite3
import uuid
from contextlib import contextmanager
from dataclasses import asdict, dataclass, replace
from pathlib import Path

from context_strategies import ContextSettings
from model_output import visible_answer

DEFAULT_CHAT_TITLE = "Пустой чат"
MAX_TITLE_LENGTH = 60


class ChatRepositoryError(RuntimeError):
    pass


class SettingsLockedError(ChatRepositoryError):
    pass


class ConversationChangedError(ChatRepositoryError):
    pass


@dataclass(frozen=True)
class Chat:
    id: str
    title: str
    created_at: str
    updated_at: str
    message_count: int
    checkpoint_id: str | None = None


class ChatRepository:
    def __init__(self, database_path: str):
        self.database_path = Path(database_path)
        try:
            self.database_path.parent.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            raise ChatRepositoryError(str(error)) from error
        with self._connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS chats (
                    id TEXT PRIMARY KEY, title TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
                    role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
                    content TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS messages_chat_id_id ON messages(chat_id, id);
                CREATE TABLE IF NOT EXISTS context_state (
                    chat_id TEXT PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,
                    settings_json TEXT NOT NULL, facts_json TEXT NOT NULL DEFAULT '{}',
                    locked INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS turns (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
                    stats_json TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS facts_calls (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
                    stats_json TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS checkpoints (
                    id TEXT PRIMARY KEY, source_chat_id TEXT NOT NULL,
                    title TEXT NOT NULL, message_count INTEGER NOT NULL,
                    snapshot_json TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                CREATE TABLE IF NOT EXISTS branch_origins (
                    chat_id TEXT PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,
                    checkpoint_id TEXT NOT NULL REFERENCES checkpoints(id)
                );
            """)

    @contextmanager
    def _connection(self, *, write=False):
        db = None
        try:
            db = sqlite3.connect(str(self.database_path), timeout=10)
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA foreign_keys = ON")
            with db:
                if write:
                    db.execute("BEGIN IMMEDIATE")
                yield db
        except sqlite3.Error as error:
            raise ChatRepositoryError(f"Ошибка базы истории: {error}") from error
        finally:
            if db is not None:
                db.close()

    def create_chat(self, title=DEFAULT_CHAT_TITLE, settings: ContextSettings | None = None) -> Chat:
        chat_id = uuid.uuid4().hex
        with self._connection(write=True) as db:
            db.execute("INSERT INTO chats(id, title) VALUES (?, ?)", (chat_id, _title(title)))
            db.execute("INSERT INTO context_state(chat_id, settings_json) VALUES (?, ?)",
                       (chat_id, _json(asdict(settings or ContextSettings()))))
        return self.get_chat(chat_id)

    def list_chats(self) -> list[Chat]:
        with self._connection() as db:
            rows = db.execute("""
                SELECT c.*, (SELECT COUNT(*) FROM messages WHERE chat_id = c.id) AS message_count,
                    b.checkpoint_id FROM chats c LEFT JOIN branch_origins b ON b.chat_id = c.id
                ORDER BY c.updated_at DESC, c.rowid DESC
            """).fetchall()
        return [Chat(**dict(row)) for row in rows]

    def get_chat(self, chat_id: str) -> Chat | None:
        return next((chat for chat in self.list_chats() if chat.id == chat_id), None)

    @staticmethod
    def _state(db, chat_id):
        row = db.execute("SELECT * FROM context_state WHERE chat_id = ?", (chat_id,)).fetchone()
        if row is None:
            raise ChatRepositoryError("Чат не найден или база создана другим заданием. Используйте отдельную базу пятницы.")
        messages = [dict(row) for row in db.execute(
            "SELECT role, content FROM messages WHERE chat_id = ? ORDER BY id", (chat_id,),
        )]
        return {"settings": json.loads(row["settings_json"]), "facts": json.loads(row["facts_json"]),
                "locked": bool(row["locked"]), "version": row["version"], "messages": messages}

    def load_state(self, chat_id: str) -> dict:
        with self._connection() as db:
            db.execute("BEGIN")
            return self._state(db, chat_id)

    def load_messages(self, chat_id: str) -> list[dict]:
        with self._connection() as db:
            return [dict(row) for row in db.execute(
                "SELECT role, content FROM messages WHERE chat_id = ? ORDER BY id", (chat_id,),
            )]

    def configure(self, chat_id: str, changes: dict) -> None:
        with self._connection(write=True) as db:
            state = self._state(db, chat_id)
            settings = asdict(replace(ContextSettings(**state["settings"]), **changes))
            if settings == state["settings"]:
                return
            if state["locked"] or state["messages"]:
                raise SettingsLockedError("Настройки доступны только до первого сообщения. Создайте новый чат.")
            db.execute("UPDATE context_state SET settings_json = ?, version = version + 1 WHERE chat_id = ?",
                       (_json(settings), chat_id))

    def begin_turn(self, chat_id: str) -> dict:
        # Замораживаем настройки до сетевого вызова, даже если LLM позже вернёт ошибку.
        with self._connection(write=True) as db:
            db.execute("UPDATE context_state SET locked = 1 WHERE chat_id = ?", (chat_id,))
            return self._state(db, chat_id)

    def append_turn(self, chat_id: str, user_message: str, assistant_message: str,
                    stats: dict, facts: dict, expected_version: int) -> None:
        with self._connection(write=True) as db:
            state = self._state(db, chat_id)
            if state["version"] != expected_version:
                raise ConversationChangedError("Диалог изменился в другом окне. Обновите его и повторите запрос.")
            db.executemany("INSERT INTO messages(chat_id, role, content) VALUES (?, ?, ?)", [
                (chat_id, "user", user_message), (chat_id, "assistant", visible_answer(assistant_message)),
            ])
            db.execute("INSERT INTO turns(chat_id, stats_json) VALUES (?, ?)", (chat_id, _json(stats)))
            db.execute("UPDATE context_state SET facts_json = ?, locked = 1, version = version + 1 WHERE chat_id = ?",
                       (_json(facts), chat_id))
            db.execute("""UPDATE chats SET title = CASE WHEN title = ? THEN ? ELSE title END,
                          updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                       (DEFAULT_CHAT_TITLE, _title(user_message), chat_id))

    def save_facts_call(self, chat_id: str, stats: dict) -> None:
        with self._connection(write=True) as db:
            db.execute("INSERT INTO facts_calls(chat_id, stats_json) VALUES (?, ?)", (chat_id, _json(stats)))

    @staticmethod
    def _calls(db, chat_id, table):
        # table задаётся только константами внутри репозитория.
        return [json.loads(row[0]) for row in db.execute(
            f"SELECT stats_json FROM {table} WHERE chat_id = ? ORDER BY id", (chat_id,),
        )]

    def load_turns(self, chat_id: str) -> list[dict]:
        with self._connection() as db:
            return self._calls(db, chat_id, "turns")

    def load_facts_calls(self, chat_id: str) -> list[dict]:
        with self._connection() as db:
            return self._calls(db, chat_id, "facts_calls")

    def _checkpoint(self, db, chat_id: str, title: str) -> str:
        snapshot = self._state(db, chat_id)
        if not snapshot["messages"]:
            raise ChatRepositoryError("Checkpoint доступен после первого сохранённого ответа.")
        snapshot["turns"] = self._calls(db, chat_id, "turns")
        snapshot["facts_calls"] = self._calls(db, chat_id, "facts_calls")
        checkpoint_id = uuid.uuid4().hex
        db.execute("""INSERT INTO checkpoints(id, source_chat_id, title, message_count, snapshot_json)
                      VALUES (?, ?, ?, ?, ?)""",
                   (checkpoint_id, chat_id, _title(title), len(snapshot["messages"]), _json(snapshot)))
        return checkpoint_id

    def create_checkpoint(self, chat_id: str, title: str = "Checkpoint") -> str:
        with self._connection(write=True) as db:
            return self._checkpoint(db, chat_id, title)

    def list_checkpoints(self, chat_id: str | None = None) -> list[dict]:
        with self._connection() as db:
            return [dict(row) for row in db.execute("""
                SELECT id, source_chat_id, title, message_count, created_at FROM checkpoints
                WHERE ? IS NULL OR source_chat_id = ? OR id = (
                    SELECT checkpoint_id FROM branch_origins WHERE chat_id = ?)
                ORDER BY rowid DESC
            """, (chat_id, chat_id, chat_id))]

    def _branch(self, db, checkpoint_id: str, title: str) -> str:
        row = db.execute("SELECT snapshot_json FROM checkpoints WHERE id = ?", (checkpoint_id,)).fetchone()
        if row is None:
            raise ChatRepositoryError("Checkpoint не найден")
        snapshot = json.loads(row[0])
        chat_id = uuid.uuid4().hex
        db.execute("INSERT INTO chats(id, title) VALUES (?, ?)", (chat_id, _title(title)))
        db.execute("INSERT INTO branch_origins VALUES (?, ?)", (chat_id, checkpoint_id))
        db.execute("""INSERT INTO context_state(chat_id, settings_json, facts_json, locked)
                      VALUES (?, ?, ?, 1)""",
                   (chat_id, _json(snapshot["settings"]), _json(snapshot["facts"])))
        db.executemany("INSERT INTO messages(chat_id, role, content) VALUES (?, ?, ?)",
                       [(chat_id, item["role"], item["content"]) for item in snapshot["messages"]])
        for table in ("turns", "facts_calls"):
            db.executemany(f"INSERT INTO {table}(chat_id, stats_json) VALUES (?, ?)",
                           [(chat_id, _json({**item, "inherited": True})) for item in snapshot[table]])
        return chat_id

    def branch(self, checkpoint_id: str, title: str = "Новая ветка") -> Chat:
        with self._connection(write=True) as db:
            chat_id = self._branch(db, checkpoint_id, title)
        return self.get_chat(chat_id)

    def copy_chat(self, chat_id: str, title: str | None = None) -> Chat:
        with self._connection(write=True) as db:
            row = db.execute("SELECT title FROM chats WHERE id = ?", (chat_id,)).fetchone()
            if row is None:
                raise ChatRepositoryError("Чат не найден")
            checkpoint_id = self._checkpoint(db, chat_id, f"Копия: {row[0]}")
            branch_id = self._branch(db, checkpoint_id, title or f"Ветка: {row[0]}")
        return self.get_chat(branch_id)

    def delete_chat(self, chat_id: str) -> None:
        # Снимки остаются доступными; удаление исходника не затрагивает ветки.
        with self._connection(write=True) as db:
            if db.execute("DELETE FROM chats WHERE id = ?", (chat_id,)).rowcount == 0:
                raise ChatRepositoryError("Чат не найден")


def _json(value) -> str:
    return json.dumps(value, ensure_ascii=False, allow_nan=False)


def _title(value: str) -> str:
    return " ".join(value.split())[:MAX_TITLE_LENGTH] or DEFAULT_CHAT_TITLE
