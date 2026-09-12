import tempfile
import unittest
from pathlib import Path

from agent import AgentConfig, AgentError, SimpleAgent
from chat_repository import ChatRepository
from web import create_app
from test_agent import response_with


class EchoClient:
    def post(self, _url, **kwargs):
        return response_with(f"Ответ на: {kwargs['json']['messages'][-1]['content']}")


class FakeAgent(SimpleAgent):
    def __init__(
        self,
        _config: AgentConfig,
        chat_id: str,
        repository: ChatRepository,
    ) -> None:
        super().__init__(_config, chat_id, repository, EchoClient())


class FailingAgent(FakeAgent):
    def reply(self, user_request: str) -> str:
        raise AgentError("API недоступен")


class PersistentWebAppTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        database_path = str(
            Path(self.temporary_directory.name) / "history.sqlite3"
        )
        self.config = AgentConfig(
            api_key="test-key",
            api_url="https://llm.example/v1/chat/completions",
            model="test-model",
            system_prompt="Отвечай кратко.",
            temperature=0.3,
            timeout_seconds=10,
            database_path=database_path,
            compression_enabled=False,
        )
        self.repository = ChatRepository(database_path)
        self.app = create_app(self.config, self.repository, FakeAgent)
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_index_contains_persistent_chat_interface(self) -> None:
        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        page = response.get_data(as_text=True)
        self.assertIn("Мои чаты", page)
        self.assertIn("test-model", page)
        self.assertIn('id="new-chat-button"', page)
        self.assertIn("+ Новый чат", page)

    def test_create_and_list_chats(self) -> None:
        created = self.client.post("/api/chats", json={})
        listed = self.client.get("/api/chats")

        self.assertEqual(created.status_code, 201)
        self.assertEqual(created.get_json()["chat"]["title"], "Пустой чат")
        self.assertEqual(len(listed.get_json()["chats"]), 1)
        self.assertEqual(
            listed.get_json()["chats"][0]["id"],
            created.get_json()["chat"]["id"],
        )

    def test_new_chat_replaces_current_empty_draft(self) -> None:
        first_chat = self.repository.create_chat()

        response = self.client.post(
            "/api/chats",
            json={"discard_empty_chat_id": first_chat.id},
        )

        self.assertEqual(response.status_code, 201)
        self.assertIsNone(self.repository.get_chat(first_chat.id))
        self.assertEqual(len(self.repository.list_chats()), 1)

    def test_send_and_restore_messages_through_api(self) -> None:
        chat = self.repository.create_chat()

        response = self.client.post(
            f"/api/chats/{chat.id}/messages",
            json={"message": "Запомни слово маяк"},
        )
        restored = self.client.get(f"/api/chats/{chat.id}/messages")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()["answer"], "Ответ на: Запомни слово маяк")
        self.assertEqual(response.get_json()["chat"]["title"], "Запомни слово маяк")
        self.assertEqual(
            restored.get_json()["messages"],
            [
                {"role": "user", "content": "Запомни слово маяк"},
                {
                    "role": "assistant",
                    "content": "Ответ на: Запомни слово маяк",
                },
            ],
        )

    def test_delete_removes_only_selected_chat(self) -> None:
        first_chat = self.repository.create_chat()
        second_chat = self.repository.create_chat()
        self.repository.append_turn(first_chat.id, "Первый", "Ответ 1")
        self.repository.append_turn(second_chat.id, "Второй", "Ответ 2")

        response = self.client.delete(f"/api/chats/{first_chat.id}")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json(), {"ok": True})
        self.assertIsNone(self.repository.get_chat(first_chat.id))
        self.assertEqual(len(self.repository.load_messages(second_chat.id)), 2)

    def test_missing_chat_returns_not_found(self) -> None:
        response = self.client.get("/api/chats/missing/messages")

        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.get_json(), {"error": "Чат не найден"})

    def test_agent_error_is_returned_as_bad_gateway(self) -> None:
        chat = self.repository.create_chat()
        failing_app = create_app(self.config, self.repository, FailingAgent)
        failing_app.config["TESTING"] = True

        response = failing_app.test_client().post(
            f"/api/chats/{chat.id}/messages",
            json={"message": "Привет"},
        )

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.get_json()["error"], "API недоступен")

    def test_preview_overflow_retry_and_restored_statistics(self):
        chat = self.repository.create_chat()
        url = f"/api/chats/{chat.id}"
        preview = self.client.post(f"{url}/preview", json={
            "message": "Привет", "context_window_tokens": 1,
        }).get_json()["preview"]
        self.assertFalse(preview["fits"])
        rejected = self.client.post(f"{url}/messages", json={"message": "Привет"})
        self.assertEqual(rejected.status_code, 413)
        self.assertEqual(rejected.get_json()["code"], "context_overflow")
        self.assertEqual(self.repository.load_messages(chat.id), [])
        accepted = self.client.post(f"{url}/messages", json={
            "message": "Привет", "context_window_tokens": preview["required_tokens"],
        })
        self.assertEqual(accepted.status_code, 200)
        stats = accepted.get_json()["statistics"]
        self.assertEqual(stats["totals"]["turn_count"], 1)
        restarted = create_app(self.config, self.repository, FakeAgent).test_client()
        restored = restarted.get(f"{url}/messages").get_json()["statistics"]
        self.assertEqual(restored["totals"], stats["totals"])
        self.assertEqual(restored["turns"], stats["turns"])
        self.assertEqual(restored["context_window_tokens"], self.config.context_window_tokens)

    def test_invalid_window_is_bad_request(self):
        chat = self.repository.create_chat()
        for value in (0, -10, 1.5, True, "100"):
            with self.subTest(value=value):
                response = self.client.post(f"/api/chats/{chat.id}/messages", json={
                    "message": "Привет", "context_window_tokens": value,
                })
                self.assertEqual(response.status_code, 400)
        self.assertEqual(self.repository.load_messages(chat.id), [])

    def test_sliding_window_sends_small_context_and_restores_full_chat(self):
        chat = self.repository.create_chat()
        url = f"/api/chats/{chat.id}"
        # Минимальное окно узнаём до появления истории.
        needed = self.client.post(f"{url}/preview", json={"message": "Привет"}).get_json()["preview"]["required_tokens"]
        self.repository.append_turn(chat.id, "Старый вопрос", "Старый ответ")
        preview = self.client.post(f"{url}/preview", json={
            "message": "Привет", "context_window_tokens": needed,
        }).get_json()["preview"]
        self.assertTrue(preview["fits"])
        self.assertEqual(preview["omitted_history_messages"], 2)
        accepted = self.client.post(f"{url}/messages", json={"message": "Привет"})
        self.assertEqual(accepted.status_code, 200)
        turn = accepted.get_json()["statistics"]["turns"][0]
        self.assertEqual(turn["input_tokens"], needed)
        self.assertEqual(turn["omitted_history_messages"], 2)
        restored = self.client.get(f"{url}/messages").get_json()
        self.assertEqual(len(restored["messages"]), 4)
        self.assertEqual(restored["messages"][0]["content"], "Старый вопрос")

    def test_saved_reasoning_prefix_is_not_returned_to_web_chat(self):
        chat = self.repository.create_chat()
        self.repository.append_turn(chat.id, "Вопрос", "</think>\nОтвет")
        response = self.client.get(f"/api/chats/{chat.id}/messages")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()["messages"][1]["content"], "Ответ")


if __name__ == "__main__":
    unittest.main()
