import unittest

from agent import AgentConfig, AgentError
from web import create_app


class FakeAgent:
    def __init__(self, _config: AgentConfig) -> None:
        self.requests: list[str] = []
        self.reset_count = 0

    def reply(self, user_request: str) -> str:
        self.requests.append(user_request)
        return f"Ответ на: {user_request}"

    def reset(self) -> None:
        self.reset_count += 1


class FailingAgent(FakeAgent):
    def reply(self, user_request: str) -> str:
        raise AgentError("API недоступен")


class WebAppTest(unittest.TestCase):
    def setUp(self) -> None:
        config = AgentConfig(
            api_key="test-key",
            api_url="https://llm.example/v1/chat/completions",
            model="test-model",
            system_prompt="Отвечай кратко.",
            temperature=0.3,
            timeout_seconds=10,
        )
        self.app = create_app(config, FakeAgent)
        self.app.config.update(TESTING=True, SECRET_KEY="test-secret")
        self.client = self.app.test_client()

    def test_index_contains_chat_interface(self) -> None:
        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        page = response.get_data(as_text=True)
        self.assertIn("Простой LLM-агент", page)
        self.assertIn("test-model", page)

    def test_chat_returns_answer_and_reuses_session_agent(self) -> None:
        first = self.client.post("/api/chat", json={"message": "Привет"})
        second = self.client.post("/api/chat", json={"message": "Как дела?"})

        self.assertEqual(first.get_json(), {"answer": "Ответ на: Привет"})
        self.assertEqual(second.get_json(), {"answer": "Ответ на: Как дела?"})
        agents = list(self.app.extensions["llm_agents"].values())
        self.assertEqual(len(agents), 1)
        self.assertEqual(agents[0].requests, ["Привет", "Как дела?"])

    def test_reset_resets_current_agent(self) -> None:
        self.client.post("/api/chat", json={"message": "Привет"})
        response = self.client.post("/api/reset")

        agent = next(iter(self.app.extensions["llm_agents"].values()))
        self.assertEqual(response.get_json(), {"ok": True})
        self.assertEqual(agent.reset_count, 1)

    def test_browser_sessions_have_separate_agents(self) -> None:
        first_client = self.app.test_client()
        second_client = self.app.test_client()

        first_client.post("/api/chat", json={"message": "Первый чат"})
        second_client.post("/api/chat", json={"message": "Второй чат"})

        agents = list(self.app.extensions["llm_agents"].values())
        self.assertEqual(len(agents), 2)
        self.assertEqual(agents[0].requests, ["Первый чат"])
        self.assertEqual(agents[1].requests, ["Второй чат"])

    def test_empty_message_is_rejected(self) -> None:
        response = self.client.post("/api/chat", json={"message": "   "})

        self.assertEqual(response.status_code, 400)
        self.assertIn("error", response.get_json())

    def test_agent_error_is_returned_as_bad_gateway(self) -> None:
        failing_app = create_app(
            AgentConfig(
                api_key="test-key",
                api_url="https://llm.example/v1/chat/completions",
                model="test-model",
                system_prompt="Отвечай кратко.",
                temperature=0.3,
                timeout_seconds=10,
            ),
            FailingAgent,
        )
        failing_app.config.update(TESTING=True, SECRET_KEY="test-secret")

        response = failing_app.test_client().post(
            "/api/chat", json={"message": "Привет"}
        )

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.get_json(), {"error": "API недоступен"})


if __name__ == "__main__":
    unittest.main()
