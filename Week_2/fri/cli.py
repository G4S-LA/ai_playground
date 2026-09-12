#!/usr/bin/env python3

from __future__ import annotations

import json
import sys

from agent import AgentConfig, AgentError, SimpleAgent
from chat_repository import Chat, ChatRepository, ChatRepositoryError


def choose_chat(repository: ChatRepository, config: AgentConfig, *, startup: bool) -> str | None:
    chats = repository.list_chats()
    if not chats:
        chat = repository.create_chat(settings=config.context_settings())
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
                return repository.create_chat(settings=config.context_settings()).id
        print(f"Введите число от 0 до {new_chat_option}.")


def _chat_menu_line(index: int, chat: Chat) -> str:
    message_word = _message_word(chat.message_count)
    return f"  {index}. {chat.title} ({chat.message_count} {message_word})" + (" · ветка" if chat.checkpoint_id else "")


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


HELP = """Команды:
/strategy sliding_window|facts — выбор до первого сообщения
/keep N, /window N, /facts-limit N — лимиты до первого сообщения
/facts, /stats — память и расход
/checkpoint [название] — сохранить текущую точку
/checkpoints — список сохранённых точек (включая удалённые чаты)
/branch ID [название] — создать и открыть ветку из checkpoint
/copy [название] — checkpoint + копия текущего диалога (Branching)
/chats, /new, /delete, /help, /exit
N включает текущий вопрос. Ветки переключаются через /chats.
"""


def execute_command(text: str, agent: SimpleAgent, config: AgentConfig,
                    repository: ChatRepository) -> SimpleAgent:
    command, _, argument = text.partition(" ")
    argument = argument.strip()
    if command in {"/strategy", "/keep", "/window", "/facts-limit"}:
        field = {"/strategy": "strategy", "/keep": "keep_recent_messages",
                 "/window": "context_window_tokens", "/facts-limit": "facts_max_tokens"}[command]
        agent.configure(**{field: argument if command == "/strategy" else int(argument)})
        print_statistics(agent)
    elif command == "/facts":
        print(json.dumps(agent.statistics()["context"]["facts"], ensure_ascii=False, indent=2))
    elif command == "/stats":
        print_statistics(agent)
    elif command == "/checkpoint":
        print("Checkpoint:", agent.checkpoint(argument or "Checkpoint"))
    elif command == "/checkpoints":
        for point in repository.list_checkpoints():
            print(f"{point['id']} · {point['title']} · {point['message_count']} сообщ.")
    elif command == "/branch":
        checkpoint_id, _, title = argument.partition(" ")
        chat = agent.branch(checkpoint_id, title or "Новая ветка")
        return _open_agent(config, repository, chat.id)
    elif command == "/copy":
        chat = agent.copy(argument or None)
        print(f"Создана ветка из checkpoint {chat.checkpoint_id}")
        return _open_agent(config, repository, chat.id)
    elif command == "/new":
        return _open_agent(config, repository, repository.create_chat(settings=config.context_settings()).id)
    elif command == "/chats":
        selected = choose_chat(repository, config, startup=False)
        if selected:
            return _open_agent(config, repository, selected)
    elif command == "/delete":
        agent.delete()
        print("Чат удалён. Сохранённые checkpoints и другие ветки доступны.")
        return _open_agent(config, repository, repository.create_chat(settings=config.context_settings()).id)
    elif command == "/help":
        print(HELP)
    else:
        print("Неизвестная команда. /help — список команд.")
    return agent


def run_chat(config: AgentConfig, repository: ChatRepository, initial_chat_id: str) -> None:
    agent = _open_agent(config, repository, initial_chat_id)
    print(f"Модель: {agent.model}")
    print(HELP)
    print_statistics(agent)
    while True:
        try:
            user_request = input("\nВы: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nРабота завершена.")
            return
        if user_request == "/exit":
            return
        if not user_request:
            continue
        try:
            if user_request.startswith("/"):
                agent = execute_command(user_request, agent, config, repository)
                continue
            preview = agent.preview(user_request)
            print(f"Вход ≈{preview['input_tokens_estimate']} / {preview['context_window_tokens']}; "
                  f"вне контекста {preview['omitted_history_messages']} сообщений.")
            if preview["facts_update_pending"]:
                print("Перед ответом обновим facts отдельным запросом; оценка входа изменится.")
            print(f"\nАгент: {agent.reply(user_request)}")
            print_statistics(agent)
        except (AgentError, ChatRepositoryError, ValueError) as error:
            print(f"Ошибка: {error}", file=sys.stderr)


def print_statistics(agent: SimpleAgent) -> None:
    stats = agent.statistics()
    state, total = stats["context"], stats["overall_totals"]
    print(f"Стратегия: {state['strategy']}; N={state['keep_recent_messages']}; "
          f"окно={state['context_window_tokens']}; facts={len(state['facts'])}.")
    print("Настройки заблокированы после первого сообщения." if state["settings_locked"]
          else "Настройки можно менять до первого сообщения.")
    print(f"Расход этой ветки: {total['total_tokens']} токенов, "
          f"из них обновление facts: {stats['facts_totals']['total_tokens']}; "
          f"известная стоимость ${total['known_cost_usd']:.6f}, вызовов без цены: {total['unpriced_turns']}.")
    if stats["inherited_totals"]["turn_count"]:
        print(f"Расход до ответвления: {stats['inherited_totals']['total_tokens']} токенов (не списывается повторно).")
    if stats["turns"] and stats["turns"][-1].get("warning"):
        print(stats["turns"][-1]["warning"])


def main() -> None:
    try:
        config = AgentConfig.from_env()
        repository = ChatRepository(config.database_path)
        chat_id = choose_chat(repository, config, startup=True)
        if chat_id:
            run_chat(config, repository, chat_id)
    except (AgentError, ChatRepositoryError) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
