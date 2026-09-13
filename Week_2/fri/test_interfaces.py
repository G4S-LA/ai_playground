from unittest.mock import patch

from agent import SimpleAgent
from cli import execute_command, run_chat
from chat_repository import SettingsLockedError
from test_strategies import Fixture, RecordingClient
from web import create_app


class WebTest(Fixture):
    def setUp(self):
        super().setUp()
        self.llm = RecordingClient(["ok"] * 10)
        self.app = create_app(self.config, self.repo, lambda config, chat_id, repo:
                              SimpleAgent(config, chat_id, repo, self.llm))
        self.client = self.app.test_client()
        self.chat_id = self.client.post("/api/chats", json={}).json["chat"]["id"]
        self.url = f"/api/chats/{self.chat_id}"

    def test_settings_lock_api_and_reopen(self):
        result = self.client.patch(self.url + "/settings", json={"keep_recent_messages":1, "context_window_tokens":9000})
        self.assertEqual(result.status_code, 200)
        self.assertEqual(self.client.post(self.url + "/preview", json={"message":"hi"}).status_code, 200)
        self.assertEqual(self.llm.calls, [])
        result = self.client.post(self.url + "/messages", json={"message":"hi"})
        self.assertTrue(result.json["statistics"]["context"]["settings_locked"])
        self.assertEqual(self.client.patch(self.url + "/settings", json={"keep_recent_messages":2}).status_code, 409)
        self.assertEqual(self.client.post(self.url + "/messages", json={"message":"again", "settings":{}}).status_code, 400)
        restored = create_app(self.config, self.repo).test_client().get(self.url + "/messages")
        self.assertEqual(restored.json["statistics"]["context"]["context_window_tokens"], 9000)
        self.assertTrue(restored.json["statistics"]["context"]["settings_locked"])

    def test_branching_end_to_end_and_independent_switching(self):
        self.client.post(self.url + "/messages", json={"message":"start"})
        checkpoint = self.client.post(self.url + "/checkpoints", json={"title":"common"}).json["checkpoint_id"]
        left = self.client.post(f"/api/checkpoints/{checkpoint}/branches", json={"title":"A"}).json["chat"]
        right = self.client.post(f"/api/checkpoints/{checkpoint}/branches", json={"title":"B"}).json["chat"]
        self.client.post(f"/api/chats/{left['id']}/messages", json={"message":"only left"})
        self.client.post(f"/api/chats/{right['id']}/messages", json={"message":"only right"})
        for chat, text in ((left, "only left"), (right, "only right")):
            result = self.client.get(f"/api/chats/{chat['id']}/messages").json
            self.assertEqual(result["messages"][-2]["content"], text)
            self.assertTrue(result["statistics"]["context"]["settings_locked"])
        self.assertEqual(len(self.client.get(self.url + "/messages").json["messages"]), 2)
        copy = self.client.post(self.url + "/copy", json={})
        self.assertEqual(copy.status_code, 201)
        self.assertEqual(copy.json["chat"]["message_count"], 2)

    def test_preview_cannot_change_settings_or_lock_chat(self):
        before = self.repo.load_state(self.chat_id)
        response = self.client.post(self.url + "/preview", json={"message":"hi", "context_window_tokens":1})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(self.repo.load_state(self.chat_id), before)

    def test_input_validation_and_empty_copy(self):
        for data in ({}, {"message":None}, {"message":[]}, {"message":" "}, {"message":"x" * 4001}):
            self.assertEqual(self.client.post(self.url + "/messages", json=data).status_code, 400)
        self.assertEqual(self.client.patch(self.url + "/settings", json={"keep_recent_messages":False}).status_code, 400)
        self.assertEqual(self.client.post(self.url + "/copy", json={}).status_code, 400)
        self.assertEqual(self.client.get("/api/chats/missing/messages").status_code, 404)
        self.assertFalse(self.repo.load_state(self.chat_id)["locked"])

    def test_overflow_returns_locked_state(self):
        self.client.patch(self.url + "/settings", json={"context_window_tokens":1})
        response = self.client.post(self.url + "/messages", json={"message":"hi"})
        self.assertEqual(response.status_code, 413)
        self.assertTrue(response.json["statistics"]["context"]["settings_locked"])
        self.assertEqual(self.llm.calls, [])

    def test_facts_are_repaired_within_one_web_request(self):
        self.client.patch(self.url + "/settings", json={"strategy":"facts"})
        self.llm.responses = ['{"age":30}', '{"age":"30"}', "Запомнил"]
        response = self.client.post(self.url + "/messages", json={"message":"Мне 30"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json["answer"], "Запомнил")
        self.assertEqual(response.json["statistics"]["context"]["facts"], {"age":"30"})
        self.assertEqual(response.json["statistics"]["facts_totals"]["total_tokens"], 240)
        self.assertEqual(len(self.llm.calls), 3)
        self.assertEqual(len(response.json["messages"]), 2)

    def test_exhausted_facts_repairs_return_error_and_usage_to_web(self):
        self.client.patch(self.url + "/settings", json={"strategy":"facts"})
        self.llm.responses = ['{"age":30}'] * 4
        response = self.client.post(self.url + "/messages", json={"message":"Мне 30"})
        self.assertEqual(response.status_code, 502)
        self.assertIn("после 3 попыток исправления", response.json["error"])
        self.assertIn('facts["age"]', response.json["error"])
        self.assertEqual(response.json["statistics"]["facts_totals"]["total_tokens"], 480)
        self.assertTrue(response.json["statistics"]["context"]["settings_locked"])
        self.assertEqual(self.repo.load_messages(self.chat_id), [])
        self.assertEqual(len(self.llm.calls), 4)


class CliTest(Fixture):
    @patch("builtins.print")
    def test_configure_and_copy_commands(self, _print):
        agent, _ = self.make_agent(["answer"])
        execute_command("/strategy facts", agent, self.config, self.repo)
        execute_command("/strategy sliding_window", agent, self.config, self.repo)
        execute_command("/keep 1", agent, self.config, self.repo)
        agent.reply("start")
        with self.assertRaises(SettingsLockedError):
            execute_command("/keep 2", agent, self.config, self.repo)
        copied = execute_command("/copy alternative", agent, self.config, self.repo)
        self.assertNotEqual(copied.chat_id, agent.chat_id)
        checkpoint = self.repo.get_chat(copied.chat_id).checkpoint_id
        other = execute_command(f"/branch {checkpoint} second", copied, self.config, self.repo)
        self.assertEqual(self.repo.load_messages(other.chat_id), self.repo.load_messages(agent.chat_id))
        with patch("cli.choose_chat", return_value=agent.chat_id):
            self.assertEqual(execute_command("/chats", other, self.config, self.repo).chat_id, agent.chat_id)

    @patch("builtins.print")
    def test_cli_loop_sends_message_and_recovers_from_locked_setting(self, _print):
        agent, client = self.make_agent(["answer"])
        with patch("cli._open_agent", return_value=agent), patch("builtins.input", side_effect=["/keep 1", "hi", "/keep 2", "/exit"]):
            run_chat(self.config, self.repo, agent.chat_id)
        self.assertEqual(len(client.calls), 1)
        self.assertEqual(agent.statistics()["context"]["keep_recent_messages"], 1)
