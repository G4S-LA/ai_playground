import json
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path
from unittest.mock import patch

from agent import AgentConfig, AgentError, ContextOverflowError, SimpleAgent
from chat_repository import ChatRepository, ConversationChangedError, SettingsLockedError
from context_strategies import ContextSettings, parse_facts


class FakeResponse:
    ok, status_code, text = True, 200, ""

    def __init__(self, content, finish="stop"):
        self.content, self.finish = content, finish

    def json(self):
        return {"choices": [{"message": {"content": self.content}, "finish_reason": self.finish}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 20}}


class RecordingClient:
    def __init__(self, responses=()):
        self.responses, self.calls = list(responses), []

    def post(self, url, **kwargs):
        self.calls.append(kwargs["json"]["messages"])
        if not self.responses:
            raise AssertionError("Незапланированный запрос к модели")
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response if isinstance(response, FakeResponse) else FakeResponse(response)


class Fixture(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.config = AgentConfig(api_key="test", api_url="https://example.invalid", model="test",
                                  system_prompt="system", temperature=0, timeout_seconds=10,
                                  database_path=str(Path(temp.name) / "history.sqlite3"))
        self.repo = ChatRepository(self.config.database_path)

    def make_agent(self, responses=(), **settings):
        chat = self.repo.create_chat(settings=ContextSettings(**settings))
        client = RecordingClient(responses)
        return SimpleAgent(self.config, chat.id, self.repo, client), client


class StrategiesTest(Fixture):
    def test_sliding_window_discards_old_messages_and_counts_current_question(self):
        agent, client = self.make_agent(["a1", "a2", "a3"], keep_recent_messages=3)
        agent.reply("secret")
        agent.reply("q2")
        preview = agent.preview("q3")
        self.assertEqual(preview["included_history_messages"], 2)
        self.assertEqual(preview["omitted_history_messages"], 2)
        agent.reply("q3")
        self.assertEqual([m["content"] for m in client.calls[-1]], ["system", "q2", "a2", "q3"])
        self.assertEqual(len(self.repo.load_messages(agent.chat_id)), 6)
        self.assertEqual(agent.statistics()["facts_calls"], [])

    def test_n_one_sends_only_current_question(self):
        agent, client = self.make_agent(["a1", "a2"], keep_recent_messages=1)
        agent.reply("q1")
        agent.reply("q2")
        self.assertEqual([m["content"] for m in client.calls[-1]], ["system", "q2"])

    def test_facts_updated_on_every_user_message_before_main_request(self):
        agent, client = self.make_agent([
            '{"goal":"launch","city":"Москва"}', "ok",
            '{"goal":"launch","city":"Казань"}', "ok",
            '{"city":"Казань"}', "ok",
        ], strategy="facts", keep_recent_messages=1)
        agent.reply("Запускаем проект в Москве")
        agent.reply("Теперь город Казань")
        agent.reply("Забудь цель")
        self.assertEqual(len(client.calls), 6)
        self.assertEqual(json.loads(client.calls[2][1]["content"])["facts"]["city"], "Москва")
        self.assertIn('"city": "Казань"', client.calls[3][1]["content"])
        self.assertEqual([m["content"] for m in client.calls[5][2:]], ["Забудь цель"])
        self.assertNotIn("launch", client.calls[5][1]["content"])
        self.assertEqual(agent.statistics()["context"]["facts"], {"city": "Казань"})
        self.assertEqual(agent.statistics()["overall_totals"]["total_tokens"], 720)

    def test_restart_restores_facts_and_all_settings(self):
        agent, _ = self.make_agent(['{"name":"Анна"}', "ok"], strategy="facts", keep_recent_messages=1)
        agent.configure(context_window_tokens=9000, facts_max_tokens=200)
        agent.reply("Я Анна")
        client = RecordingClient(['{"name":"Анна"}', "Анна"])
        repo = ChatRepository(self.config.database_path)
        restored = SimpleAgent(self.config, agent.chat_id, repo, client)
        self.assertEqual(restored.statistics()["context"]["context_window_tokens"], 9000)
        self.assertEqual(restored.statistics()["context"]["facts_max_tokens"], 200)
        restored.reply("Как меня зовут?")
        self.assertIn("Анна", client.calls[-1][1]["content"])
        with self.assertRaises(SettingsLockedError):
            restored.configure(strategy="sliding_window")

    def test_preview_is_read_only_and_makes_no_llm_calls(self):
        agent, client = self.make_agent(strategy="facts")
        before = self.repo.load_state(agent.chat_id)
        self.assertTrue(agent.preview("hello")["facts_update_pending"])
        self.assertEqual(before, self.repo.load_state(agent.chat_id))
        self.assertEqual(client.calls, [])

    def test_failed_answer_does_not_commit_facts_and_locks_settings(self):
        import requests
        agent, _ = self.make_agent(['{"name":"Анна"}', requests.ConnectionError("offline")], strategy="facts")
        with self.assertRaises(AgentError):
            agent.reply("Я Анна")
        state = self.repo.load_state(agent.chat_id)
        self.assertEqual((state["facts"], state["messages"]), ({}, []))
        self.assertTrue(state["locked"])
        self.assertEqual(agent.statistics()["facts_totals"]["turn_count"], 1)
        with self.assertRaises(SettingsLockedError):
            agent.configure(keep_recent_messages=2)

    def test_invalid_truncated_and_oversized_facts_never_replace_memory(self):
        for response in ['[]', '{"key": 1}', '{"key": null}', 'not json',
                         FakeResponse('{"key":"new"}', "length"), '{"key":"' + "word " * 100 + '"}']:
            with self.subTest(response=response):
                agent, client = self.make_agent(['{"key":"old"}', 'ok', *([response] * 4)], strategy="facts", facts_max_tokens=20)
                agent.reply("first")
                with self.assertRaisesRegex(AgentError, "после 3 попыток исправления"):
                    agent.reply("second")
                self.assertEqual(agent.statistics()["context"]["facts"], {"key": "old"})
                self.assertEqual(len(self.repo.load_messages(agent.chat_id)), 2)
                self.assertEqual(len(client.calls), 6)
                self.assertEqual(agent.statistics()["facts_totals"]["turn_count"], 5)

    def test_blank_facts_object_is_valid(self):
        agent, _ = self.make_agent(['{}', 'hello'], strategy="facts")
        self.assertEqual(agent.reply("hello"), "hello")

    def test_overflow_does_not_silently_shrink_n_or_call_api(self):
        agent, client = self.make_agent(context_window_tokens=1)
        with self.assertRaises(ContextOverflowError):
            agent.reply("hello")
        self.assertEqual(client.calls, [])

    def test_facts_extraction_has_its_own_context_limit(self):
        agent, client = self.make_agent(strategy="facts", context_window_tokens=10)
        with self.assertRaisesRegex(AgentError, "обновления facts не помещается"):
            agent.reply("hello")
        self.assertEqual(client.calls, [])

    def test_main_context_is_rechecked_after_facts_grow(self):
        large_facts = json.dumps({"note":"word " * 1200})
        agent, client = self.make_agent([large_facts], strategy="facts",
                                        context_window_tokens=1000, facts_max_tokens=2000)
        self.assertTrue(agent.preview("hi")["fits"])
        with self.assertRaises(ContextOverflowError):
            agent.reply("hi")
        self.assertEqual(len(client.calls), 1)
        self.assertEqual(self.repo.load_state(agent.chat_id)["facts"], {})
        self.assertEqual(self.repo.load_messages(agent.chat_id), [])

    def test_settings_validate_atomically_and_only_before_first_message(self):
        agent, _ = self.make_agent(["answer"])
        before = self.repo.load_state(agent.chat_id)["settings"]
        for changes in ({"strategy":"unknown"}, {"keep_recent_messages":True},
                        {"facts_max_tokens":0}, {"context_window_tokens":1.5}, {"unknown":2}):
            with self.assertRaises(AgentError):
                agent.configure(**changes)
        self.assertEqual(self.repo.load_state(agent.chat_id)["settings"], before)
        agent.configure(keep_recent_messages=5)
        agent.reply("hi")
        for field, value in asdict(ContextSettings(strategy="facts", keep_recent_messages=1,
                                                 context_window_tokens=99, facts_max_tokens=99)).items():
            with self.assertRaises(SettingsLockedError):
                agent.configure(**{field:value})
        agent.configure(keep_recent_messages=5)  # Идемпотентная повторная запись разрешена.

    def test_blank_request_does_not_lock_chat(self):
        agent, _ = self.make_agent()
        with self.assertRaises(AgentError):
            agent.reply(" ")
        self.assertFalse(self.repo.load_state(agent.chat_id)["locked"])

    def test_stale_answer_cannot_overwrite_newer_turn(self):
        agent, _ = self.make_agent(["answer"])
        before = self.repo.begin_turn(agent.chat_id)
        agent.reply("newer")
        with self.assertRaises(ConversationChangedError):
            self.repo.append_turn(agent.chat_id, "stale", "answer", {}, {"wrong":"fact"}, before["version"])
        self.assertEqual(len(self.repo.load_messages(agent.chat_id)), 2)
        self.assertEqual(self.repo.load_state(agent.chat_id)["facts"], {})

    def test_storage_failure_rolls_back_messages_facts_and_stats(self):
        agent, _ = self.make_agent(["answer"])
        with patch("chat_repository._json", side_effect=ValueError("broken serialization")):
            with self.assertRaises(ValueError):
                agent.reply("hi")
        self.assertEqual(self.repo.load_messages(agent.chat_id), [])
        self.assertEqual(self.repo.load_turns(agent.chat_id), [])


class BranchingTest(Fixture):
    def test_two_branches_restore_same_checkpoint_and_diverge_independently(self):
        source, _ = self.make_agent(['{"city":"Москва"}', "ok", '{"city":"Тула"}', "source"], strategy="facts")
        source.reply("Москва")
        checkpoint = source.checkpoint("before changes")
        source.reply("Тула")
        left = self.repo.branch(checkpoint, "left")
        right = self.repo.branch(checkpoint, "right")
        left_agent = SimpleAgent(self.config, left.id, self.repo, RecordingClient(['{"city":"Казань"}', "left"]))
        right_agent = SimpleAgent(self.config, right.id, self.repo, RecordingClient(['{"city":"Омск"}', "right"]))
        self.assertEqual(left_agent.statistics()["context"]["facts"], {"city":"Москва"})
        self.assertEqual(left_agent.statistics()["overall_totals"]["total_tokens"], 0)
        self.assertEqual(left_agent.statistics()["inherited_totals"]["total_tokens"], 240)
        left_agent.reply("Казань")
        right_agent.reply("Омск")
        for item, city in [(source, "Тула"), (left_agent, "Казань"), (right_agent, "Омск")]:
            restored = SimpleAgent(self.config, item.chat_id, ChatRepository(self.config.database_path))
            self.assertEqual(restored.statistics()["context"]["facts"], {"city":city})
            self.assertEqual(len(self.repo.load_messages(item.chat_id)), 4)
        self.assertEqual(left_agent.statistics()["overall_totals"]["total_tokens"], 240)
        with self.assertRaises(SettingsLockedError):
            left_agent.configure(strategy="sliding_window")
        source.delete()
        left_agent.delete()
        self.assertEqual(right_agent.statistics()["context"]["facts"], {"city":"Омск"})
        third = self.repo.branch(checkpoint)
        self.assertEqual(self.repo.load_state(third.id)["facts"], {"city":"Москва"})

    def test_copy_is_checkpoint_plus_branch_with_same_settings(self):
        source, _ = self.make_agent(["ok"], keep_recent_messages=3, context_window_tokens=9000)
        source.reply("start")
        copied = source.copy()
        self.assertIsNotNone(copied.checkpoint_id)
        self.assertEqual(self.repo.load_messages(copied.id), self.repo.load_messages(source.chat_id))
        self.assertEqual(self.repo.load_state(copied.id)["settings"], self.repo.load_state(source.chat_id)["settings"])
        self.assertEqual(self.repo.list_checkpoints(copied.id)[0]["id"], copied.checkpoint_id)


class FactsValidationTest(unittest.TestCase):
    def test_reports_all_fields_and_expected_types(self):
        with self.assertRaises(ValueError) as caught:
            parse_facts('{"age":30,"active":false,"city":" ","languages":[],"goal":null}')
        error = str(caught.exception)
        for key, expected in (("age", "number"), ("active", "boolean"), ("city", "пробелов"),
                              ("languages", "array"), ("goal", "null")):
            self.assertTrue(any(f'facts["{key}"]' in line and expected in line for line in error.splitlines()), error)

    def test_reports_syntax_position_root_type_and_invalid_keys(self):
        for raw, message in (('{\n"city":}', "строка 2, столбец 8"),
                             ('[]', "получен тип array"), ('"text"', "получен тип string"),
                             ('{" ":"text"}', "ключ не должен быть пустым"),
                             (json.dumps({"k" * 129:"text"}), "длина ключа 129")):
            with self.subTest(raw=raw), self.assertRaisesRegex(ValueError, message):
                parse_facts(raw)


class FactsRepairTest(Fixture):
    def test_stops_on_first_success_up_to_the_third_repair(self):
        invalid = ['{"age":30}', '{"age":false}', '[]']
        for repairs in (1, 2, 3):
            with self.subTest(repairs=repairs):
                agent, client = self.make_agent([*invalid[:repairs], '{"age":"30"}', 'answer'], strategy="facts")
                self.assertEqual(agent.reply("Мне 30"), "answer")
                self.assertEqual(len(client.calls), repairs + 2)
                for index in range(1, repairs + 1):
                    payload = json.loads(client.calls[index][1]["content"])
                    self.assertEqual(payload["repair"]["attempt"], index)
                    self.assertEqual(payload["repair"]["previous_response"], invalid[index - 1])
                    self.assertEqual(payload["facts"], {})
                    self.assertEqual(payload["recent_messages"], [{"role":"user", "content":"Мне 30"}])
                    with self.assertRaises(ValueError) as caught:
                        parse_facts(invalid[index - 1])
                    self.assertEqual(payload["repair"]["validation_error"], str(caught.exception))
                stats = agent.statistics()
                self.assertEqual(stats["context"]["facts"], {"age":"30"})
                self.assertEqual(stats["context"]["message_count"], 2)
                self.assertEqual(stats["overall_totals"]["total_tokens"], 120 * (repairs + 2))
                self.assertEqual([item["repair_attempt"] for item in stats["facts_calls"]], list(range(repairs + 1)))
                self.assertIsNone(stats["facts_calls"][-1]["validation_error"])
                self.assertTrue(all(item["validation_error"] for item in stats["facts_calls"][:-1]))
                self.assertIn('"age": "30"', client.calls[-1][1]["content"])
                self.assertNotIn("repair", json.dumps(client.calls[-1]))

    def test_exhaustion_stops_after_initial_response_and_three_repairs(self):
        agent, client = self.make_agent(['not json', '[]', '{"city":false}', '{"city":42}'], strategy="facts")
        with self.assertRaisesRegex(AgentError, "после 3 попыток исправления") as caught:
            agent.reply("Город Москва")
        self.assertIn('facts["city"]', str(caught.exception))
        self.assertIn("number", str(caught.exception))
        self.assertEqual(len(client.calls), 4)
        self.assertEqual(agent.statistics()["overall_totals"]["total_tokens"], 480)
        self.assertEqual(agent.statistics()["turns"], [])
        self.assertEqual(self.repo.load_state(agent.chat_id)["facts"], {})
        self.assertEqual(self.repo.load_messages(agent.chat_id), [])

    def test_original_memory_is_kept_in_repairs_and_budget_resets_each_turn(self):
        agent, client = self.make_agent([
            '{"goal":"launch"}', 'ok',
            '{"city":30}', '{"goal":"launch","city":"Москва"}', 'ok',
            '{"city":false}', '{"goal":"launch","city":"Казань"}', 'ok',
        ], strategy="facts", keep_recent_messages=3)
        agent.reply("Цель — запуск")
        agent.reply("Город Москва")
        agent.reply("Теперь Казань")
        repair = json.loads(client.calls[3][1]["content"])
        self.assertEqual(repair["facts"], {"goal":"launch"})
        self.assertEqual(repair["recent_messages"][-1]["content"], "Город Москва")
        self.assertEqual(json.loads(client.calls[6][1]["content"])["repair"]["attempt"], 1)
        self.assertEqual(agent.statistics()["context"]["facts"], {"goal":"launch", "city":"Казань"})

    def test_empty_truncated_and_oversized_results_can_be_repaired(self):
        for response, expected in (("", "Пустой ответ"), (None, "Пустой ответ"),
                                   (FakeResponse('{"city":"Москва"}', "length"), "оборван"),
                                   ('{"note":"' + "word " * 100 + '"}', "лимит токенов")):
            with self.subTest(response=response):
                agent, client = self.make_agent([response, '{"city":"Москва"}', 'ok'], strategy="facts", facts_max_tokens=20)
                agent.reply("Город Москва")
                self.assertEqual(len(client.calls), 3)
                self.assertIn(expected, json.loads(client.calls[1][1]["content"])["repair"]["validation_error"])
                self.assertEqual(agent.statistics()["context"]["facts"], {"city":"Москва"})

    def test_repair_request_is_checked_against_context_window(self):
        agent, client = self.make_agent(['x ' * 1500], strategy="facts", context_window_tokens=1000)
        with self.assertRaisesRegex(AgentError, "Запрос исправления facts не помещается"):
            agent.reply("hello")
        self.assertEqual(len(client.calls), 1)
        self.assertEqual(agent.statistics()["facts_totals"]["turn_count"], 1)
        self.assertEqual(self.repo.load_state(agent.chat_id)["facts"], {})

    def test_network_and_api_errors_do_not_trigger_format_repairs(self):
        import requests
        http_error = FakeResponse("unavailable")
        http_error.ok, http_error.status_code, http_error.text = False, 503, "unavailable"
        for response in (requests.ConnectionError("offline"), requests.Timeout("timeout"), http_error):
            with self.subTest(response=response):
                agent, client = self.make_agent([response], strategy="facts")
                with self.assertRaises(AgentError):
                    agent.reply("hello")
                self.assertEqual(len(client.calls), 1)
                self.assertEqual(agent.statistics()["facts_totals"]["turn_count"], 0)

    def test_main_failure_does_not_commit_repaired_facts(self):
        import requests
        agent, client = self.make_agent(['{"city":123}', '{"city":"Москва"}', requests.ConnectionError("offline")], strategy="facts")
        with self.assertRaises(AgentError):
            agent.reply("Город Москва")
        self.assertEqual(len(client.calls), 3)
        self.assertEqual(self.repo.load_state(agent.chat_id)["facts"], {})
        self.assertEqual(self.repo.load_messages(agent.chat_id), [])
        self.assertEqual(agent.statistics()["facts_totals"]["total_tokens"], 240)

    def test_empty_main_answer_is_not_repaired_as_facts(self):
        agent, client = self.make_agent(['{}', ''], strategy="facts")
        with self.assertRaisesRegex(AgentError, "пустой ответ"):
            agent.reply("hello")
        self.assertEqual(len(client.calls), 2)
        self.assertEqual(self.repo.load_messages(agent.chat_id), [])


if __name__ == "__main__":
    unittest.main()
