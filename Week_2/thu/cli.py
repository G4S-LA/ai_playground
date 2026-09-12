#!/usr/bin/env python3

from __future__ import annotations

import sys

from agent import AgentConfig, AgentError, SimpleAgent
from chat_repository import Chat, ChatRepository, ChatRepositoryError


def choose_chat(repository: ChatRepository, *, startup: bool) -> str | None:
    chats = repository.list_chats()
    if not chats:
        chat = repository.create_chat()
        print("Создан первый чат.")
        return chat.id

    print("\nВыберите чат.")
    print("\nСохранённые чаты:")
    for index, chat in enumerate(chats, start=1):
        print(_chat_menu_line(index, chat))

    new_chat_option = len(chats) + 1
    print("\nДействия:")
    print(f"  {new_chat_option}. Создать новый чат")
    print(f"  0. {'Выход' if startup else 'Остаться в текущем чате'}")

    while True:
        try:
            choice = input("Выберите чат: ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return None

        if choice == "0":
            return None
        if choice.isdigit():
            number = int(choice)
            if 1 <= number <= len(chats):
                return chats[number - 1].id
            if number == new_chat_option:
                return repository.create_chat().id
        print(f"Введите число от 0 до {new_chat_option}.")


def _chat_menu_line(index: int, chat: Chat) -> str:
    message_word = _message_word(chat.message_count)
    return f"  {index}. {chat.title} ({chat.message_count} {message_word})"


def _message_word(count: int) -> str:
    if count % 10 == 1 and count % 100 != 11:
        return "сообщение"
    if count % 10 in {2, 3, 4} and count % 100 not in {12, 13, 14}:
        return "сообщения"
    return "сообщений"


def _open_agent(
    config: AgentConfig,
    repository: ChatRepository,
    chat_id: str,
) -> SimpleAgent:
    agent = SimpleAgent(config, chat_id, repository)
    chat = repository.get_chat(chat_id)
    title = chat.title if chat is not None else chat_id
    print(f"\nОткрыт чат: {title}")
    print(f"Восстановлено сообщений: {agent.restored_message_count}")
    return agent


def run_chat(
    config: AgentConfig,
    repository: ChatRepository,
    initial_chat_id: str,
) -> None:
    agent = _open_agent(config, repository, initial_chat_id)
    print(f"Модель: {agent.model}")
    print("Команды: /chats, /new, /delete, /stats, /window N, /compression on|off, /keep N, /every N, /summary, /exit")

    while True:
        try:
            user_request = input("\nВы: ").strip()
        except (EOFError, KeyboardInterrupt):
            repository.delete_chat_if_empty(agent.chat_id)
            print("\nРабота завершена.")
            return

        if user_request == "/exit":
            repository.delete_chat_if_empty(agent.chat_id)
            print("Работа завершена.")
            return
        if user_request == "/stats":
            print_statistics(agent)
            continue
        if user_request == "/summary":
            print(agent.statistics()["compression"]["summary"] or "Summary ещё не создано.")
            continue
        command = user_request.split(maxsplit=1)[0] if user_request else ""
        if command in {"/compression", "/keep", "/every"}:
            try:
                value = user_request.split()[1]
                if command == "/compression":
                    if value not in {"on", "off"}:
                        raise ValueError("Используйте /compression on или /compression off")
                    agent.set_compression(enabled=value == "on")
                elif command == "/keep":
                    agent.set_compression(keep_recent_messages=int(value))
                else:
                    agent.set_compression(summary_every_messages=int(value))
                print_statistics(agent)
            except (ValueError, IndexError, AgentError) as error:
                print(f"Ошибка настройки сжатия: {error}")
            continue
        if user_request.startswith("/window"):
            try:
                agent.set_context_window(int(user_request.split()[1]))
                print(f"Локальное окно: {agent.statistics()['context_window_tokens']} токенов")
            except (ValueError, IndexError, AgentError) as error:
                print(f"Используйте /window N, где N — положительное целое число. {error}")
            continue
        if user_request == "/new":
            repository.delete_chat_if_empty(agent.chat_id)
            agent = _open_agent(config, repository, repository.create_chat().id)
            continue
        if user_request == "/chats":
            selected_chat_id = choose_chat(repository, startup=False)
            if selected_chat_id is not None and selected_chat_id != agent.chat_id:
                repository.delete_chat_if_empty(agent.chat_id)
                agent = _open_agent(config, repository, selected_chat_id)
            continue
        if user_request in {"/delete", "/reset"}:
            try:
                agent.delete()
            except AgentError as error:
                print(f"Ошибка: {error}", file=sys.stderr)
            else:
                print("Текущий чат и его история удалены из SQLite.")
                selected_chat_id = choose_chat(repository, startup=True)
                if selected_chat_id is None:
                    return
                agent = _open_agent(config, repository, selected_chat_id)
            continue

        try:
            preview = agent.preview(user_request)
            print(
                f"Вопрос ≈{preview['request_tokens_estimate']}; "
                f"история ≈{preview['history_tokens_estimate']}; "
                f"весь вход ≈{preview['input_tokens_estimate']} / "
                f"окно {preview['context_window_tokens']}"
            )
            if preview["fits"] and preview["omitted_history_messages"]:
                print(
                    f"Вне контекста: {preview['omitted_history_messages']} старых сообщений. "
                    "Они сохранены в чате, но модель их не увидит."
                )
            if preview["compression"]["pending_messages"]:
                print("Перед ответом будет обновлено summary; размер входа пересчитается после сжатия.")
            answer = agent.reply(user_request)
        except AgentError as error:
            print(f"Ошибка: {error}", file=sys.stderr)
            continue

        print(f"\nАгент: {answer}")
        print_statistics(agent)


def print_statistics(agent: SimpleAgent) -> None:
    stats = agent.statistics()
    total = stats["overall_totals"]
    compression = stats["compression"]
    print(
        f"Сжатие: {'включено' if compression['enabled'] else 'выключено'}; "
        f"свежих сообщений N={compression['keep_recent_messages']}; "
        f"порог сжатия={compression['summary_every_messages']}. "
        f"В summary: {compression['summarized_messages']} сообщений "
        f"(≈{compression['summary_tokens_estimate']} токенов)."
    )
    print(f"Расход сжатия: {stats['summary_totals']['total_tokens']} токенов, включён в общий расход.")
    if stats["turns"]:
        last = stats["turns"][-1]
        print(
            f"Последний ход: вход {last['input_tokens']} ({last['input_source']}), "
            f"генерация {last['output_tokens']} ({last['output_source']}); "
            f"видимый ответ ≈{last['answer_tokens_estimate']}."
        )
        if last["warning"]:
            print(last["warning"])
        if last.get("omitted_history_messages"):
            print(f"В последнем запросе пропущено сообщений истории: {last['omitted_history_messages']}.")
    print(
        f"История ≈{stats['history_tokens_estimate']}; "
        f"расход всех учтённых ходов {'≈' if total['has_estimates'] else ''}"
        f"{total['total_tokens']} токенов "
        f"(вход {total['input_tokens']}, генерация {total['output_tokens']})."
    )
    print(
        f"Известная стоимость по тарифам: ${total['known_cost_usd']:.6f}; "
        f"ходов без цены: {total['unpriced_turns']}; "
        f"старых ходов без статистики: {stats['untracked_turns']}."
    )


def main() -> None:
    try:
        config = AgentConfig.from_env()
        repository = ChatRepository(config.database_path)
        repository.delete_empty_chats()
        chat_id = choose_chat(repository, startup=True)
        if chat_id is None:
            return
        run_chat(config, repository, chat_id)
    except (AgentError, ChatRepositoryError) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
