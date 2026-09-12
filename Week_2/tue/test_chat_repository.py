import tempfile
import unittest
from pathlib import Path

from chat_repository import ChatRepository


class ChatRepositoryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.database_path = str(
            Path(self.temporary_directory.name) / "history.sqlite3"
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_messages_survive_repository_restart(self) -> None:
        first_repository = ChatRepository(self.database_path)
        chat = first_repository.create_chat()
        first_repository.append_turn(chat.id, "Меня зовут Андрей", "Запомнил")

        restarted_repository = ChatRepository(self.database_path)

        self.assertEqual(
            restarted_repository.load_messages(chat.id),
            [
                {"role": "user", "content": "Меня зовут Андрей"},
                {"role": "assistant", "content": "Запомнил"},
            ],
        )

    def test_first_message_becomes_chat_title(self) -> None:
        repository = ChatRepository(self.database_path)
        chat = repository.create_chat()
        self.assertEqual(chat.title, "Пустой чат")

        repository.append_turn(chat.id, "Обсудим хранение контекста", "Давайте")

        saved_chat = repository.get_chat(chat.id)
        self.assertIsNotNone(saved_chat)
        self.assertEqual(saved_chat.title, "Обсудим хранение контекста")
        self.assertEqual(saved_chat.message_count, 2)

    def test_chats_have_independent_histories(self) -> None:
        repository = ChatRepository(self.database_path)
        first_chat = repository.create_chat()
        second_chat = repository.create_chat()
        repository.append_turn(first_chat.id, "Первый", "Ответ 1")
        repository.append_turn(second_chat.id, "Второй", "Ответ 2")

        repository.delete_chat(first_chat.id)

        self.assertIsNone(repository.get_chat(first_chat.id))
        self.assertEqual(
            repository.load_messages(second_chat.id),
            [
                {"role": "user", "content": "Второй"},
                {"role": "assistant", "content": "Ответ 2"},
            ],
        )

    def test_empty_chats_can_be_removed_without_touching_saved_chat(self) -> None:
        repository = ChatRepository(self.database_path)
        empty_chat = repository.create_chat()
        saved_chat = repository.create_chat()
        repository.append_turn(saved_chat.id, "Вопрос", "Ответ")

        deleted_chat_ids = repository.delete_empty_chats()

        self.assertEqual(deleted_chat_ids, [empty_chat.id])
        self.assertIsNone(repository.get_chat(empty_chat.id))
        self.assertIsNotNone(repository.get_chat(saved_chat.id))
        self.assertFalse(repository.delete_chat_if_empty(saved_chat.id))


if __name__ == "__main__":
    unittest.main()
