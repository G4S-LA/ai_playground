import tempfile
import unittest
from pathlib import Path
from typing import Any

from agent import AgentConfig, AgentError, SimpleAgent
from chat_repository import ChatRepository


class FakeResponse:
    def __init__(
        self,
        payload: Any,
        *,
        ok: bool = True,
        status_code: int = 200,
        text: str = "",
    ) -> None:
        self._payload = payload
        self.ok = ok
        self.status_code = status_code
        self.text = text

    def json(self) -> Any:
        return self._payload


class RecordingHttpClient:
    def __init__(self, responses: list[FakeResponse]) -> None:
        self.responses = responses
        self.calls: list[dict[str, Any]] = []

    def post(self, url: str, **kwargs: Any) -> FakeResponse:
        self.calls.append({"url": url, **kwargs})
        return self.responses.pop(0)


def response_with(answer: str) -> FakeResponse:
    return FakeResponse(
        {"choices": [{"message": {"role": "assistant", "content": answer}}]}
    )


class PersistentAgentTest(unittest.TestCase):
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
        self.chat = self.repository.create_chat()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_new_agent_restores_context_after_restart(self) -> None:
        first_client = RecordingHttpClient([response_with("Запомнил")])
        first_agent = SimpleAgent(
            self.config,
            self.chat.id,
            self.repository,
            first_client,
        )
        first_agent.reply("Моё кодовое слово — маяк")

        restarted_repository = ChatRepository(self.config.database_path)
        second_client = RecordingHttpClient([response_with("Ваше слово — маяк")])
        restarted_agent = SimpleAgent(
            self.config,
            self.chat.id,
            restarted_repository,
            second_client,
        )

        self.assertEqual(restarted_agent.restored_message_count, 2)
        self.assertEqual(
            restarted_agent.reply("Какое у меня кодовое слово?"),
            "Ваше слово — маяк",
        )
        self.assertEqual(
            second_client.calls[0]["json"]["messages"],
            [
                {"role": "system", "content": "Отвечай кратко."},
                {"role": "user", "content": "Моё кодовое слово — маяк"},
                {"role": "assistant", "content": "Запомнил"},
                {"role": "user", "content": "Какое у меня кодовое слово?"},
            ],
        )

    def test_api_error_is_not_saved(self) -> None:
        client = RecordingHttpClient(
            [FakeResponse({}, ok=False, status_code=401, text="invalid key")]
        )
        agent = SimpleAgent(self.config, self.chat.id, self.repository, client)

        with self.assertRaisesRegex(AgentError, "ошибку 401"):
            agent.reply("Это сообщение не должно сохраниться")

        self.assertEqual(self.repository.load_messages(self.chat.id), [])

    def test_delete_removes_chat_and_persisted_context(self) -> None:
        client = RecordingHttpClient([response_with("Ответ")])
        agent = SimpleAgent(self.config, self.chat.id, self.repository, client)
        agent.reply("Вопрос")

        agent.delete()

        restarted_repository = ChatRepository(self.config.database_path)
        self.assertIsNone(restarted_repository.get_chat(self.chat.id))
        self.assertEqual(restarted_repository.load_messages(self.chat.id), [])
        with self.assertRaisesRegex(AgentError, "удалённый чат"):
            agent.reply("Новый вопрос")


if __name__ == "__main__":
    unittest.main()
