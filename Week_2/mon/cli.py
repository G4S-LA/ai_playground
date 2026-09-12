#!/usr/bin/env python3

import sys

from agent import AgentConfig, AgentError, SimpleAgent


def run_chat(agent: SimpleAgent) -> None:
    print(f"Простой LLM-агент запущен. Модель: {agent.model}")
    print("Команды: /reset — очистить историю, /exit — выйти.")

    while True:
        try:
            user_request = input("\nВы: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nРабота завершена.")
            return

        if user_request == "/exit":
            print("Работа завершена.")
            return
        if user_request == "/reset":
            agent.reset()
            print("История диалога очищена.")
            continue

        try:
            answer = agent.reply(user_request)
        except AgentError as error:
            print(f"Ошибка: {error}", file=sys.stderr)
            continue

        print(f"\nАгент: {answer}")


def main() -> None:
    try:
        config = AgentConfig.from_env()
    except AgentError as error:
        print(f"Ошибка конфигурации: {error}", file=sys.stderr)
        raise SystemExit(1) from error

    run_chat(SimpleAgent(config))


if __name__ == "__main__":
    main()
