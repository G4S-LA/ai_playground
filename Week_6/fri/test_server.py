#!/usr/bin/env python3

import json
import io
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from server import ChatRepository, ChatService, Config, LocalChatHttpServer, configure_console_encoding


API_KEY = "test-api-key-that-is-long-enough"


class FakeOllama:
    def __init__(self) -> None:
        self.calls: list[list[dict[str, str]]] = []

    def health(self):
        return {
            "reachable": True,
            "model_installed": True,
            "available_models": ["test-model"],
        }

    def chat(self, messages):
        self.calls.append(messages)
        return f"Ответ: {messages[-1]['content']}"


def test_config(database_path: Path) -> Config:
    return Config(
        host="127.0.0.1",
        port=0,
        api_key=API_KEY,
        ollama_url="http://127.0.0.1:11434",
        model="test-model",
        database_path=database_path,
        ollama_timeout_seconds=2,
    )


class RunningService:
    def __init__(self, config: Config, ollama: FakeOllama) -> None:
        self.server = LocalChatHttpServer(("127.0.0.1", 0), ChatService(config, ollama=ollama))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(
        self,
        method: str,
        path: str,
        payload: dict | None = None,
        key: str | None = API_KEY,
    ) -> tuple[int, dict | None]:
        headers = {}
        if key is not None:
            headers["Authorization"] = f"Bearer {key}"
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload).encode()
        request = Request(f"{self.url}{path}", data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=4) as response:
                body = response.read()
                return response.status, json.loads(body) if body else None
        except HTTPError as error:
            with error:
                body = error.read()
                return error.code, json.loads(body) if body else None


class LocalChatServiceTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.database = Path(self.temporary_directory.name) / "chat.db"

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_create_send_list_get_and_delete_dialog(self):
        fake = FakeOllama()
        with RunningService(test_config(self.database), fake) as service:
            status, health = service.request("GET", "/health", key=None)
            self.assertEqual(200, status)
            self.assertTrue(health["ready"])
            self.assertEqual("SQLite", health["storage"])

            status, created = service.request("POST", "/api/chats", {})
            self.assertEqual(201, status)
            chat_id = created["chat"]["id"]

            status, reply = service.request(
                "POST",
                f"/api/chats/{chat_id}/messages",
                {"message": "Привет"},
            )
            self.assertEqual(200, status)
            self.assertEqual(["user", "assistant"], [item["role"] for item in reply["chat"]["messages"]])
            self.assertEqual("Привет", reply["chat"]["title"])

            status, listing = service.request("GET", "/api/chats")
            self.assertEqual(200, status)
            self.assertEqual(2, listing["chats"][0]["messageCount"])

            status, restored = service.request("GET", f"/api/chats/{chat_id}")
            self.assertEqual(200, status)
            self.assertEqual("Ответ: Привет", restored["chat"]["messages"][1]["content"])

            status, body = service.request("DELETE", f"/api/chats/{chat_id}")
            self.assertEqual(204, status)
            self.assertIsNone(body)
            self.assertEqual([], service.request("GET", "/api/chats")[1]["chats"])

    def test_dialog_survives_new_repository_instance(self):
        first = ChatRepository(self.database)
        chat_id = first.create_chat()["id"]
        first.add_exchange(chat_id, "Вопрос", "Ответ")

        restored = ChatRepository(self.database).get_chat(chat_id)
        self.assertEqual("Вопрос", restored["title"])
        self.assertEqual(["Вопрос", "Ответ"], [item["content"] for item in restored["messages"]])

    def test_saved_history_is_sent_to_model(self):
        fake = FakeOllama()
        with RunningService(test_config(self.database), fake) as service:
            chat_id = service.request("POST", "/api/chats", {})[1]["chat"]["id"]
            service.request("POST", f"/api/chats/{chat_id}/messages", {"message": "Первый"})
            service.request("POST", f"/api/chats/{chat_id}/messages", {"message": "Второй"})

        self.assertEqual(2, len(fake.calls))
        self.assertEqual(
            ["user", "assistant", "user"],
            [message["role"] for message in fake.calls[1]],
        )

    def test_api_requires_bearer_key(self):
        with RunningService(test_config(self.database), FakeOllama()) as service:
            status, response = service.request("GET", "/api/chats", key=None)
        self.assertEqual(401, status)
        self.assertEqual("unauthorized", response["error"]["code"])

    def test_console_is_configured_for_utf8(self):
        stream = io.TextIOWrapper(io.BytesIO(), encoding="ascii")
        original_stdout = sys.stdout
        try:
            sys.stdout = stream
            configure_console_encoding()
            self.assertEqual("utf-8", stream.encoding.lower())
            print("Русский текст")
            stream.flush()
        finally:
            sys.stdout = original_stdout
            stream.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
