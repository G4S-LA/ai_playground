import tempfile
import unittest
from pathlib import Path

from agent import AgentConfig, AgentError
from chat_repository import ChatRepository
from web import create_app


class FakeAgent:
    def __init__(
        self,
        _config: AgentConfig,
        chat_id: str,
        repository: ChatRepository,
    ) -> None:
        self.chat_id = chat_id
        self.repository = repository

    def reply(self, user_request: str) -> str:
        answer = f"Ответ на: {user_request}"
        self.repository.append_turn(self.chat_id, user_request, answer)
        return answer

    def delete(self) -> None:
        self.repository.delete_chat(self.chat_id)


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
        self.assertEqual(response.get_json(), {"error": "API недоступен"})


if __name__ == "__main__":
    unittest.main()
