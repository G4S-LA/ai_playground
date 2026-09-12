import os
import sqlite3
import unittest
from dataclasses import replace
from unittest.mock import patch

from agent import AgentConfig, AgentError, ContextOverflowError, SimpleAgent
from chat_repository import ChatRepository, ChatRepositoryError
import test_agent as fixtures
from test_agent import RecordingHttpClient, FakeResponse, response_with
from token_usage import TokenCounter


class ContextTest(unittest.TestCase):
    setUp = fixtures.PersistentAgentTest.setUp
    tearDown = fixtures.PersistentAgentTest.tearDown

    def make_agent(self, responses=None, **config):
        self.http = RecordingHttpClient(responses or [response_with("Ответ")])
        return SimpleAgent(replace(self.config, **config), self.chat.id, self.repository, self.http)

    def test_exact_boundary_passes_and_one_token_less_blocks_without_network(self):
        agent = self.make_agent()
        preview = agent.preview("Привет")
        needed = preview["input_tokens_estimate"]
        self.assertEqual(preview["required_tokens"], needed)
        agent.set_context_window(needed - 1)
        with self.assertRaises(ContextOverflowError) as caught:
            agent.reply("Привет")
        self.assertEqual(caught.exception.preview["required_tokens"], needed)
        self.assertEqual(self.http.calls, [])
        self.assertEqual(self.repository.load_messages(self.chat.id), [])
        self.assertEqual(self.repository.load_turns(self.chat.id), [])
        agent.set_context_window(needed)
        self.assertEqual(agent.reply("Привет"), "Ответ")
        self.assertNotIn("max_tokens", self.http.calls[0]["json"])
        self.assertNotIn("context_window_tokens", self.http.calls[0]["json"])

    def test_legacy_output_limit_does_not_cap_request_or_long_answer(self):
        with patch.dict(os.environ, {
            "LLM_API_KEY": "test", "LLM_HISTORY_DB": self.config.database_path,
            "LLM_MAX_OUTPUT_TOKENS": "1024",
        }, clear=True):
            config = AgentConfig.from_env()
        answer = "Длинный ответ. " * 1200
        http = RecordingHttpClient([response_with(answer)])
        agent = SimpleAgent(config, self.chat.id, self.repository, http)
        agent.set_context_window(agent.preview("Привет")["input_tokens_estimate"])
        self.assertEqual(agent.reply("Привет"), answer.strip())
        self.assertGreater(agent.last_turn["output_tokens"], 1024)
        self.assertNotIn("max_tokens", http.calls[0]["json"])
        self.assertNotIn("max_completion_tokens", http.calls[0]["json"])
        self.assertEqual(self.repository.load_messages(self.chat.id)[-1]["content"], answer.strip())

    def test_history_and_cumulative_spend_grow_and_survive_restart(self):
        agent = self.make_agent(
            [response_with("Хорошо")] * 3,
            input_price_per_million=1, output_price_per_million=2,
        )
        for _ in range(3):
            agent.reply("Продолжай")
        stats = agent.statistics()
        inputs = [turn["input_tokens"] for turn in stats["turns"]]
        self.assertTrue(inputs[0] < inputs[1] < inputs[2])
        self.assertEqual(stats["totals"]["input_tokens"], sum(inputs))
        self.assertGreater(stats["totals"]["total_tokens"], stats["history_tokens_estimate"])
        # Смена тарифов не пересчитывает цену предыдущих запросов.
        restarted = SimpleAgent(self.config, self.chat.id, ChatRepository(self.config.database_path))
        self.assertEqual(restarted.statistics(), stats)

    def test_external_history_is_reloaded_and_window_is_applied_before_send(self):
        agent = self.make_agent()
        agent.preview("Привет")
        self.repository.append_turn(self.chat.id, "Вопрос из CLI", "Ответ из CLI")
        self.repository.append_turn(self.chat.id, "Последний вопрос", "Последний ответ")
        history = self.repository.load_messages(self.chat.id)
        expected = [agent.history[0], *history[-2:], {"role": "user", "content": "Привет"}]
        agent.set_context_window(TokenCounter().messages(expected))
        agent.reply("Привет")
        self.assertEqual(self.http.calls[0]["json"]["messages"], expected)
        self.assertEqual(agent.last_turn["omitted_history_messages"], 2)

    def test_window_keeps_latest_complete_pairs_and_restores_full_history(self):
        agent = self.make_agent([response_with("Ответ")] * 3)
        for index in range(3):
            self.repository.append_turn(self.chat.id, f"Вопрос {index}", f"Ответ {index}")
        original = self.repository.load_messages(self.chat.id)
        question = {"role": "user", "content": "Дальше"}
        expected = [agent.history[0], *original[-4:], question]
        counter = TokenCounter()
        needed = counter.messages(expected)
        agent.set_context_window(needed)
        preview = agent.preview(question["content"])
        self.assertTrue(preview["fits"])
        self.assertEqual(preview["included_history_messages"], 4)
        self.assertEqual(preview["omitted_history_messages"], 2)
        self.assertEqual(preview["input_tokens_estimate"], needed)
        self.assertEqual(preview["full_input_tokens_estimate"], counter.messages(
            [agent.history[0], *original, question],
        ))
        self.assertEqual(self.repository.load_messages(self.chat.id), original)
        agent.set_context_window(needed - 1)
        self.assertEqual(agent.preview("Дальше")["included_history_messages"], 2)
        agent.set_context_window(needed)
        agent.reply("Дальше")
        self.assertEqual(self.http.calls[0]["json"]["messages"], expected)
        self.assertEqual(agent.last_turn["input_tokens"], needed)
        self.assertEqual(agent.last_turn["input_source"], "estimate")
        self.assertEqual(self.repository.load_messages(self.chat.id)[:6], original)
        self.assertEqual(agent.last_turn["history_after_tokens_estimate"], sum(
            counter.text(message["content"]) for message in self.repository.load_messages(self.chat.id)
        ))

        restarted = SimpleAgent(self.config, self.chat.id, self.repository, self.http)
        self.assertEqual(restarted.statistics()["turns"][0], agent.last_turn)
        before = self.repository.load_messages(self.chat.id)
        restarted.reply("Ещё")
        self.assertEqual(self.http.calls[1]["json"]["messages"][1:-1], before)
        self.assertEqual(restarted.last_turn["omitted_history_messages"], 0)

    def test_oversized_latest_pair_is_not_split_or_replaced_with_older_pairs(self):
        agent = self.make_agent()
        self.repository.append_turn(self.chat.id, "Старый вопрос", "Короткий ответ")
        self.repository.append_turn(self.chat.id, "Новый вопрос", "Длинный ответ. " * 100)
        original = self.repository.load_messages(self.chat.id)
        question = {"role": "user", "content": "Продолжай"}
        agent.set_context_window(TokenCounter().messages([agent.history[0], *original[:2], question]))
        preview = agent.preview("Продолжай")
        self.assertTrue(preview["fits"])
        self.assertEqual(preview["omitted_history_messages"], 4)
        agent.reply("Продолжай")
        self.assertEqual(self.http.calls[0]["json"]["messages"], [agent.history[0], question])
        self.assertEqual(self.repository.load_messages(self.chat.id)[:4], original)

    def test_mandatory_input_overflow_keeps_history_and_skips_network(self):
        agent = self.make_agent()
        self.repository.append_turn(self.chat.id, "Вопрос", "Ответ")
        before = agent.statistics()
        minimum = TokenCounter().messages([agent.history[0], {"role": "user", "content": "Дальше"}])
        agent.set_context_window(minimum - 1)
        with self.assertRaisesRegex(ContextOverflowError, "Даже без истории") as caught:
            agent.reply("Дальше")
        self.assertEqual(caught.exception.preview["input_tokens_estimate"], minimum)
        self.assertEqual(caught.exception.preview["omitted_history_messages"], 2)
        self.assertEqual(self.http.calls, [])
        self.assertEqual(agent.statistics()["totals"], before["totals"])
        self.assertEqual(len(self.repository.load_messages(self.chat.id)), 2)

    def test_input_and_per_turn_cost_stop_growing_when_window_is_full(self):
        agent = self.make_agent([response_with("Ответ")] * 5,
                                input_price_per_million=1, output_price_per_million=2)
        agent.reply("Вопрос")
        agent.set_context_window(agent.preview("Вопрос")["input_tokens_estimate"])
        for _ in range(4):
            agent.reply("Вопрос")
        turns = agent.statistics()["turns"]
        self.assertLess(turns[0]["input_tokens"], turns[1]["input_tokens"])
        self.assertEqual([turn["input_tokens"] for turn in turns[1:]], [turns[1]["input_tokens"]] * 4)
        self.assertEqual([turn["cost_usd"] for turn in turns[1:]], [turns[1]["cost_usd"]] * 4)
        self.assertEqual([turn["omitted_history_messages"] for turn in turns], [0, 0, 2, 4, 6])
        self.assertEqual(len(self.repository.load_messages(self.chat.id)), 10)

    def test_api_usage_and_truncated_empty_answer_are_saved(self):
        agent = self.make_agent([FakeResponse({
            "choices": [{"message": {"content": None, "reasoning_content": "Думаю"},
                         "finish_reason": "length"}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 32},
        })])
        self.assertEqual(agent.reply("Вопрос"), "")
        turn = agent.statistics()["turns"][0]
        self.assertEqual(turn["output_tokens"], 32)
        self.assertEqual(turn["answer_tokens_estimate"], 0)
        self.assertIn("оборван", turn["warning"])
        self.assertIn("API", turn["warning"])
        self.assertEqual(turn["input_source"], "api")

    def test_provider_context_error_keeps_saved_history_and_usage(self):
        agent = self.make_agent([
            response_with("Первый ответ"),
            FakeResponse({}, ok=False, status_code=400, text="context_length_exceeded"),
        ])
        agent.reply("Первый вопрос")
        agent.set_context_window(TokenCounter().messages([
            agent.history[0], {"role": "user", "content": "Второй вопрос"},
        ]))
        before = agent.statistics()
        with self.assertRaisesRegex(AgentError, "context_length_exceeded"):
            agent.reply("Второй вопрос")
        self.assertEqual(agent.statistics(), before)
        self.assertIsNone(agent.last_turn)
        self.assertEqual(len(self.http.calls[-1]["json"]["messages"]), 2)
        self.assertEqual(len(self.repository.load_messages(self.chat.id)), 2)

    def test_stats_and_messages_share_transaction(self):
        agent = self.make_agent()
        with sqlite3.connect(self.config.database_path) as connection:
            connection.execute("""
                CREATE TRIGGER reject_usage BEFORE INSERT ON turns
                BEGIN SELECT RAISE(ABORT, 'stats failure'); END
            """)
        with self.assertRaisesRegex(AgentError, "сохранить"):
            agent.reply("Вопрос")
        self.assertEqual(self.repository.load_turns(self.chat.id), [])
        self.assertEqual(self.repository.load_messages(self.chat.id), [])
        self.assertEqual(self.repository.get_chat(self.chat.id).title, "Пустой чат")

    def test_deleting_chat_also_deletes_usage(self):
        agent = self.make_agent()
        agent.reply("Вопрос")
        agent.delete()
        self.assertEqual(self.repository.load_turns(self.chat.id), [])

    def test_reasoning_prefix_is_hidden_but_still_counted_as_generation(self):
        raw = "<think>Вычисления</think>\n</think>Ответ"
        agent = self.make_agent([response_with(raw), response_with("Продолжение")])
        self.assertEqual(agent.reply("Вопрос"), "Ответ")
        first = agent.statistics()["turns"][0]
        self.assertGreater(first["output_tokens"], first["answer_tokens_estimate"])
        self.assertEqual(first["output_source"], "estimate")
        self.assertEqual(self.repository.load_messages(self.chat.id)[1]["content"], "Ответ")
        agent.reply("Следующий вопрос")
        self.assertEqual(self.http.calls[1]["json"]["messages"][2]["content"], "Ответ")

    def test_api_usage_is_unchanged_when_answer_is_cleaned(self):
        agent = self.make_agent([FakeResponse({
            "choices": [{"message": {"content": "</think>Ответ"}}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 75},
        })])
        self.repository.append_turn(self.chat.id, "Старый вопрос", "Старый ответ")
        agent.set_context_window(TokenCounter().messages([
            agent.history[0], {"role": "user", "content": "Вопрос"},
        ]))
        self.assertEqual(agent.reply("Вопрос"), "Ответ")
        self.assertEqual(agent.last_turn["input_tokens"], 100)
        self.assertEqual(agent.last_turn["input_source"], "api")
        self.assertEqual(agent.last_turn["omitted_history_messages"], 2)
        self.assertEqual(agent.last_turn["output_tokens"], 75)
        self.assertEqual(agent.last_turn["output_source"], "api")

    def test_truncated_reasoning_is_saved_as_empty_visible_answer(self):
        agent = self.make_agent([FakeResponse({
            "choices": [{"message": {"content": "<think>Не закончил рассуждения"},
                         "finish_reason": "length"}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 32},
        })])
        self.assertEqual(agent.reply("Вопрос"), "")
        self.assertEqual(agent.last_turn["output_tokens"], 32)
        self.assertTrue(agent.last_turn["warning"])

    def test_old_assistant_prefix_is_cleaned_without_changing_user_text(self):
        self.repository.append_turn(self.chat.id, "</think> — что это?", "</think>Ответ")
        agent = self.make_agent()
        agent.reply("Продолжай")
        sent = self.http.calls[0]["json"]["messages"]
        self.assertEqual(sent[1]["content"], "</think> — что это?")
        self.assertEqual(sent[2]["content"], "Ответ")

    def test_invalid_window_and_rates_are_rejected(self):
        agent = self.make_agent()
        for value in (0, -1, 2.5, True, "100"):
            with self.subTest(value=value), self.assertRaises(AgentError):
                agent.set_context_window(value)
        for name, value in (("LLM_CONTEXT_WINDOW_TOKENS", "nan"),
                            ("LLM_INPUT_PRICE_PER_MILLION", "inf"),
                            ("LLM_OUTPUT_PRICE_PER_MILLION", "-1")):
            with patch.dict(os.environ, {"LLM_API_KEY": "test", name: value}, clear=True):
                with self.assertRaises(AgentError):
                    AgentConfig.from_env()


if __name__ == "__main__":
    unittest.main()
