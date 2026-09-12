import json
import io
import os
import sqlite3
import unittest
from contextlib import closing, redirect_stdout
from dataclasses import replace
from unittest.mock import patch

from agent import AgentConfig, AgentError, ContextOverflowError, SimpleAgent
from chat_repository import ChatRepository
from cli import run_chat
from context_compression import summary_message, summary_request
import test_agent as fixtures
from test_agent import FakeResponse, RecordingHttpClient, response_with
from token_usage import TokenCounter
from web import create_app


class CompressionTest(unittest.TestCase):
    setUp = fixtures.PersistentAgentTest.setUp
    tearDown = fixtures.PersistentAgentTest.tearDown

    def make_agent(self, responses=(), **changes):
        self.config = replace(
            self.config, **{
                "compression_enabled": True, "keep_recent_messages": 2,
                "summary_every_messages": 2, **changes,
            },
        )
        self.http = RecordingHttpClient(list(responses))
        return SimpleAgent(self.config, self.chat.id, self.repository, self.http)

    def seed(self, turns):
        for i in range(turns):
            self.repository.append_turn(self.chat.id, f"Вопрос {i}", f"Ответ {i}")
        return self.repository.load_messages(self.chat.id)

    def test_threshold_and_exact_payload_keep_recent_messages_verbatim(self):
        agent = self.make_agent([
            response_with("Ответ 0"), response_with("Ответ 1"),
            response_with("Факт из начала"), response_with("Ответ 2"),
        ])
        agent.reply("Вопрос 0")
        agent.reply("Вопрос 1")
        self.assertEqual(len(self.http.calls), 2)
        before = self.repository.load_messages(self.chat.id)
        preview = agent.preview("Вопрос 2")
        self.assertEqual(preview["compression"]["pending_messages"], 2)
        self.assertEqual(len(self.http.calls), 2)  # Прогноз не вызывает LLM.
        agent.reply("Вопрос 2")
        summary_input = json.loads(self.http.calls[2]["json"]["messages"][-1]["content"])
        self.assertEqual(summary_input, {"previous_summary": "", "messages": before[:2]})
        expected = [agent.history[0], summary_message("Факт из начала"), *before[2:],
                    {"role": "user", "content": "Вопрос 2"}]
        self.assertEqual(self.http.calls[3]["json"]["messages"], expected)
        self.assertEqual(agent.last_turn["input_tokens_estimate"], TokenCounter().messages(expected))
        self.assertEqual(agent.last_turn["summarized_history_messages"], 2)
        self.assertEqual(self.repository.load_messages(self.chat.id)[:4], before)

    def test_next_summary_merges_previous_and_only_new_old_messages(self):
        old = self.seed(2)
        agent = self.make_agent([
            response_with("Первое резюме"), response_with("Новый ответ"),
            response_with("Обновлённое резюме"), response_with("Ещё ответ"),
        ])
        agent.reply("Новый вопрос")
        agent.reply("Ещё вопрос")
        sent = json.loads(self.http.calls[2]["json"]["messages"][-1]["content"])
        self.assertEqual(sent, {"previous_summary": "Первое резюме", "messages": old[2:4]})
        self.assertEqual(agent.statistics()["compression"]["summarized_messages"], 4)

    def test_default_policy_compresses_ten_messages_and_keeps_ten(self):
        old = self.seed(10)
        agent = self.make_agent([response_with("Резюме"), response_with("Ответ")],
                                keep_recent_messages=10, summary_every_messages=10)
        agent.reply("Дальше")
        sent = self.http.calls[-1]["json"]["messages"]
        self.assertEqual(sent[2:-1], old[-10:])
        self.assertEqual(agent.last_turn["summarized_history_messages"], 10)

    def test_restart_and_off_on_reuse_summary_and_restore_full_context(self):
        self.seed(2)
        agent = self.make_agent([response_with("Сохранённый факт"), response_with("Ответ")])
        agent.reply("Дальше")
        agent.set_compression(enabled=False)
        restarted = SimpleAgent(self.config, self.chat.id, ChatRepository(self.config.database_path),
                                RecordingHttpClient([response_with("Без сжатия")]))
        self.assertFalse(restarted.statistics()["compression"]["enabled"])
        full = self.repository.load_messages(self.chat.id)
        restarted.reply("Вспомни")
        self.assertEqual(restarted._http_client.calls[0]["json"]["messages"][1:-1], full)
        self.assertEqual(restarted.statistics()["compression"]["summary"], "Сохранённый факт")
        restarted.set_compression(enabled=True)
        self.assertEqual(restarted.preview("Дальше")["compression"]["pending_messages"], 4)
        self.assertEqual(restarted.statistics()["compression"]["summarized_messages"], 2)

    def test_summary_usage_is_separate_persistent_and_included_in_overall_cost(self):
        self.seed(2)
        def billed(text, prompt, completion):
            data = response_with(text).json()
            data["usage"] = {"prompt_tokens": prompt, "completion_tokens": completion}
            return FakeResponse(data)
        agent = self.make_agent([billed("Резюме", 100, 20), billed("Ответ", 30, 10)],
                                input_price_per_million=1, output_price_per_million=2)
        agent.reply("Вопрос")
        stats = agent.statistics()
        self.assertEqual(stats["totals"]["total_tokens"], 40)
        self.assertEqual(stats["summary_totals"]["total_tokens"], 120)
        self.assertEqual(stats["overall_totals"]["total_tokens"], 160)
        self.assertAlmostEqual(stats["overall_totals"]["known_cost_usd"], 0.00019)
        restarted = SimpleAgent(self.config, self.chat.id, self.repository)
        self.assertEqual(restarted.statistics(), stats)
        agent.delete()
        self.assertIsNone(self.repository.load_context(self.chat.id))
        self.assertEqual(self.repository.load_summary_calls(self.chat.id), [])

    def test_failed_summary_keeps_full_history_and_can_be_retried(self):
        before = self.seed(2)
        agent = self.make_agent([FakeResponse({}, ok=False, status_code=503, text="unavailable"),
                                 response_with("Резюме"), response_with("Ответ")])
        with self.assertRaises(AgentError):
            agent.reply("Вопрос")
        self.assertIsNone(self.repository.load_context(self.chat.id))
        self.assertEqual(self.repository.load_messages(self.chat.id), before)
        agent.reply("Вопрос")
        self.assertEqual(len(self.repository.load_messages(self.chat.id)), 6)

    def test_failed_main_answer_keeps_summary_without_resummarizing_on_retry(self):
        before = self.seed(2)
        agent = self.make_agent([response_with("Резюме"),
                                 FakeResponse({}, ok=False, status_code=503, text="unavailable"),
                                 response_with("Ответ")])
        with self.assertRaises(AgentError):
            agent.reply("Вопрос")
        self.assertEqual(self.repository.load_messages(self.chat.id), before)
        self.assertEqual(agent.statistics()["summary_totals"]["turn_count"], 1)
        agent.reply("Вопрос")
        self.assertEqual(len(self.http.calls), 3)
        self.assertEqual(self.http.calls[1]["json"], self.http.calls[2]["json"])

    def test_summary_and_usage_share_transaction(self):
        self.seed(2)
        agent = self.make_agent([response_with("Резюме")])
        with closing(sqlite3.connect(self.config.database_path)) as connection, connection:
            connection.execute("""CREATE TRIGGER reject_summary BEFORE INSERT ON summary_calls
                                  BEGIN SELECT RAISE(ABORT, 'failure'); END""")
        with self.assertRaises(AgentError):
            agent.reply("Вопрос")
        self.assertIsNone(self.repository.load_context(self.chat.id))
        self.assertEqual(self.repository.load_summary_calls(self.chat.id), [])
        self.assertEqual(len(self.repository.load_messages(self.chat.id)), 4)

    def test_incomplete_empty_and_oversized_summaries_are_not_used(self):
        self.seed(2)
        for content, finish in (("", "stop"), ("Факт", "length"), ("много " * 600, "stop")):
            agent = self.make_agent([FakeResponse({"choices": [{
                "message": {"content": content}, "finish_reason": finish,
            }]})])
            with self.subTest(finish=finish), self.assertRaises(AgentError):
                agent.reply("Вопрос")
            self.assertIsNone(self.repository.load_context(self.chat.id))
            self.assertEqual(len(self.http.calls), 1)

    def test_summary_and_recent_messages_are_never_silently_dropped(self):
        self.seed(2)
        agent = self.make_agent([response_with("Резюме"), response_with("Ответ")])
        agent.reply("Вопрос")
        # Поднимем интервал, чтобы на следующем запросе не было сжатия.
        agent.set_compression(summary_every_messages=10)
        required = agent.preview("Ещё")["required_tokens"]
        agent.set_context_window(required - 1)
        with self.assertRaises(ContextOverflowError):
            agent.reply("Ещё")
        self.assertEqual(len(self.http.calls), 2)
        self.assertEqual(agent.preview("Ещё")["omitted_history_messages"], 0)

    def test_long_history_is_compressed_in_bounded_ordered_batches(self):
        old = self.seed(6)
        agent = self.make_agent([response_with("Факт")] * 5 + [response_with("Ответ")])
        agent.reply("Вопрос")
        self.assertEqual(len(self.http.calls), 6)
        for index, call in enumerate(self.http.calls[:-1]):
            data = json.loads(call["json"]["messages"][-1]["content"])
            self.assertEqual(data["messages"], old[index * 2:index * 2 + 2])
            self.assertEqual(data["previous_summary"], "Факт" if index else "")
        self.assertEqual(self.http.calls[-1]["json"]["messages"][2:-1], old[-2:])

    def test_summary_request_respects_window_and_cannot_drop_oversized_pair(self):
        old = self.seed(3)
        agent = self.make_agent([response_with("Факт")] * 2 + [response_with("Ответ")],
                                summary_every_messages=4)
        counter = TokenCounter()
        window = counter.messages(summary_request("Факт", old[:2], 512))
        agent.set_context_window(window)
        agent.reply("Вопрос")
        self.assertEqual(len(self.http.calls), 3)
        self.assertTrue(all(counter.messages(c["json"]["messages"]) <= window for c in self.http.calls))
        agent.set_context_window(100)
        self.repository.append_turn(self.chat.id, "ещё " * 300, "ответ " * 300)
        with self.assertRaisesRegex(AgentError, "Для сжатия"):
            agent.reply("Вопрос")

    def test_increasing_keep_restores_raw_tail_without_duplicate_summary(self):
        self.seed(3)
        agent = self.make_agent([response_with("Факт")] * 2 + [response_with("Ответ")])
        agent.reply("Вопрос")
        agent.set_compression(keep_recent_messages=8)
        state = agent.statistics()["compression"]
        self.assertEqual(state["summary"], "")
        self.assertEqual(state["verbatim_messages"], 8)
        self.assertEqual(agent.statistics()["summary_totals"]["turn_count"], 2)

    def test_settings_are_validated_and_env_defaults_enable_compression(self):
        with patch.dict(os.environ, {"LLM_API_KEY": "test"}, clear=True):
            config = AgentConfig.from_env()
        self.assertTrue(config.compression_enabled)
        self.assertEqual(config.keep_recent_messages, 10)
        agent = self.make_agent()
        for changes in ({"enabled": "false"}, {"enabled": 1}, {"keep_recent_messages": 0},
                        {"keep_recent_messages": 3}, {"summary_every_messages": True},
                        {"summary_every_messages": 2.5}, {"unknown": 2}):
            with self.subTest(changes=changes), self.assertRaises(AgentError):
                agent.set_compression(**changes)
        with patch.dict(os.environ, {"LLM_API_KEY": "test", "LLM_COMPRESSION_ENABLED": "maybe"}, clear=True):
            with self.assertRaises(AgentError):
                AgentConfig.from_env()

    def test_web_settings_preview_send_restart_and_chat_isolation(self):
        self.seed(2)
        agent = self.make_agent([response_with("Факт"), response_with("Ответ")])
        app = create_app(self.config, self.repository, lambda *_: agent)
        client = app.test_client()
        url = f"/api/chats/{self.chat.id}"
        preview = client.post(f"{url}/preview", json={"message": "Вопрос", "compression": {"enabled": True}})
        self.assertEqual(preview.status_code, 200)
        self.assertEqual(self.http.calls, [])
        response = client.post(f"{url}/messages", json={"message": "Вопрос"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json["statistics"]["compression"]["summary"], "Факт")
        client.post(f"{url}/preview", json={"message": "", "compression": {"enabled": False}})
        restored = create_app(self.config, self.repository).test_client().get(f"{url}/messages").json
        self.assertFalse(restored["statistics"]["compression"]["enabled"])
        self.assertEqual(len(restored["messages"]), 6)
        for changes in ({"enabled": "false"}, {"keep_recent_messages": 3}, None):
            rejected = client.post(f"{url}/messages", json={"message": "Вопрос", "compression": changes})
            self.assertEqual(rejected.status_code, 400)
        other = self.repository.create_chat()
        separate = SimpleAgent(self.config, other.id, self.repository)
        self.assertEqual(separate.statistics()["compression"]["summary"], "")
        self.assertTrue(separate.statistics()["compression"]["enabled"])

    def test_cli_commands_toggle_compression_and_show_persisted_summary(self):
        self.seed(2)
        agent = self.make_agent([response_with("Кодовое слово: маяк"), response_with("Ответ")])
        commands = ["/compression on", "/keep 2", "/every 2", "Вопрос", "/summary",
                    "/compression off", "/stats", "/exit"]
        output = io.StringIO()
        with patch("cli.SimpleAgent", return_value=agent), patch("builtins.input", side_effect=commands), redirect_stdout(output):
            run_chat(self.config, self.repository, self.chat.id)
        self.assertIn("Кодовое слово: маяк", output.getvalue())
        self.assertIn("Сжатие: выключено", output.getvalue())
        self.assertIn("Расход сжатия:", output.getvalue())
        self.assertFalse(self.repository.load_context(self.chat.id)["settings"]["enabled"])


if __name__ == "__main__":
    unittest.main()
