#!/usr/bin/env python3
"""Воспроизводимый опыт без LLM API: настоящий агент, SQLite и тестовый HTTP-клиент."""

from __future__ import annotations

from pathlib import Path
from tempfile import TemporaryDirectory

from agent import AgentConfig, ContextOverflowError, SimpleAgent
from chat_repository import ChatRepository


class DemoResponse:
    ok = True
    status_code = 200
    text = ""

    def __init__(self, answer: str, finish_reason: str = "stop") -> None:
        self.answer = answer
        self.finish_reason = finish_reason

    def json(self) -> dict:
        # Нет usage: статистика честно помечается как локальная оценка.
        return {"choices": [{
            "message": {"content": self.answer}, "finish_reason": self.finish_reason,
        }]}


class DemoClient:
    def __init__(self, response: DemoResponse) -> None:
        self.response = response
        self.call_count = 0

    def post(self, _url: str, **_kwargs) -> DemoResponse:
        self.call_count += 1
        return self.response


def main() -> None:
    print("ДЕМО: ответы смоделированы, токены ≈cl100k_base; тарифы условные.")
    print("Вход $1 / 1M; генерация $2 / 1M. Реальных расходов API нет.\n")
    with TemporaryDirectory() as directory:
        config = AgentConfig(
            api_key="demo", api_url="https://example.invalid/chat/completions",
            model="demo", system_prompt="Отвечай кратко.", temperature=0,
            timeout_seconds=10, database_path=str(Path(directory) / "demo.sqlite3"),
            context_window_tokens=8192,
            input_price_per_million=1, output_price_per_million=2,
        )
        repository = ChatRepository(config.database_path)
        client = DemoClient(DemoResponse("История снова отправляется модели."))
        agent = SimpleAgent(config, repository.create_chat().id, repository, client)
        print("После третьего хода уменьшаем окно до размера третьего входа.")
        print("Ход | Вопрос | История после | Вход | Ответ | Вне окна | Токены ∑ | USD/ход | USD ∑")
        for index in range(1, 7):
            if index == 4:
                agent.set_context_window(agent.last_turn["input_tokens_estimate"])
            agent.reply("Почему растёт расход?")
            turn = agent.last_turn
            total = agent.statistics()["totals"]
            print(
                f"{index:3} | {turn['request_tokens_estimate']:6} | "
                f"{turn['history_after_tokens_estimate']:13} | "
                f"{turn['input_tokens']:4} | {turn['output_tokens']:5} | "
                f"{turn['omitted_history_messages']:8} | "
                f"{total['total_tokens']:8} | {turn['cost_usd']:.6f} | "
                f"{total['known_cost_usd']:.6f}"
            )

        print(f"В чате сохранены все {len(agent.history) - 1} сообщений; старые пары исключаются только из запроса.")
        agent.set_context_window(1)
        needed = agent.preview("Продолжай")["required_tokens"]
        agent.set_context_window(needed - 1)
        before = client.call_count
        print("\n1. Инструкция и вопрос не помещаются даже без истории:")
        try:
            agent.reply("Продолжай")
        except ContextOverflowError as error:
            print(error)
        print(f"Дополнительных HTTP-вызовов: {client.call_count - before}; сохранено ходов: 6.")
        agent.set_context_window(needed)
        agent.reply("Продолжай")
        print("Увеличили окно до точной границы: запрос прошёл без старой истории.")

        print("\n2. Лимит генерации API (смоделированный finish_reason=length):")
        short = SimpleAgent(
            config, repository.create_chat().id,
            repository, DemoClient(DemoResponse("The answer", "length")),
        )
        print(f"Частичный ответ: {short.reply('Продолжай')!r}")
        print(short.last_turn["warning"])


if __name__ == "__main__":
    main()
