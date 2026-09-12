import json
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path
from unittest.mock import patch

from agent import AgentConfig, AgentError, ContextOverflowError, SimpleAgent
from chat_repository import ChatRepository, ConversationChangedError, SettingsLockedError
from context_strategies import ContextSettings


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
                agent, client = self.make_agent(['{"key":"old"}', 'ok', response], strategy="facts", facts_max_tokens=20)
                agent.reply("first")
                with self.assertRaises(AgentError):
                    agent.reply("second")
                self.assertEqual(agent.statistics()["context"]["facts"], {"key": "old"})
                self.assertEqual(len(self.repo.load_messages(agent.chat_id)), 2)
                self.assertEqual(len(client.calls), 3)

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


if __name__ == "__main__":
    unittest.main()
