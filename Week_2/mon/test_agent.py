import unittest
from typing import Any

from agent import AgentConfig, AgentError, SimpleAgent


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


class SimpleAgentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = AgentConfig(
            api_key="test-key",
            api_url="https://llm.example/v1/chat/completions",
            model="test-model",
            system_prompt="Отвечай кратко.",
            temperature=0.3,
            timeout_seconds=10,
        )

    def test_reply_calls_api_and_keeps_conversation_history(self) -> None:
        client = RecordingHttpClient(
            [response_with("Первый ответ"), response_with("Второй ответ")]
        )
        agent = SimpleAgent(self.config, client)

        self.assertEqual(agent.reply("Первый вопрос"), "Первый ответ")
        self.assertEqual(agent.reply("Второй вопрос"), "Второй ответ")

        first_call = client.calls[0]
        self.assertEqual(first_call["url"], self.config.api_url)
        self.assertEqual(first_call["timeout"], 10)
        self.assertEqual(
            first_call["headers"]["Authorization"],
            "Bearer test-key",
        )
        self.assertEqual(first_call["json"]["model"], "test-model")
        self.assertEqual(
            first_call["json"]["messages"],
            [
                {"role": "system", "content": "Отвечай кратко."},
                {"role": "user", "content": "Первый вопрос"},
            ],
        )
        self.assertEqual(
            client.calls[1]["json"]["messages"][-3:],
            [
                {"role": "user", "content": "Первый вопрос"},
                {"role": "assistant", "content": "Первый ответ"},
                {"role": "user", "content": "Второй вопрос"},
            ],
        )

    def test_empty_request_does_not_call_api(self) -> None:
        client = RecordingHttpClient([])
        agent = SimpleAgent(self.config, client)

        with self.assertRaisesRegex(AgentError, "не должен быть пустым"):
            agent.reply("   ")

        self.assertEqual(client.calls, [])

    def test_api_error_does_not_change_history(self) -> None:
        client = RecordingHttpClient(
            [FakeResponse({}, ok=False, status_code=401, text="invalid key")]
        )
        agent = SimpleAgent(self.config, client)
        history_before_request = agent.history

        with self.assertRaisesRegex(AgentError, "ошибку 401"):
            agent.reply("Привет")

        self.assertEqual(agent.history, history_before_request)


if __name__ == "__main__":
    unittest.main()
