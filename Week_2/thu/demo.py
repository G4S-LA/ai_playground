"""Сравнение режимов без API-ключа: ответы и summary смоделированы."""

from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory

from agent import AgentConfig, SimpleAgent
from chat_repository import ChatRepository


class DemoResponse:
    ok = True
    status_code = 200
    text = ""

    def __init__(self, answer):
        self.answer = answer

    def json(self):
        return {"choices": [{"message": {"content": self.answer}, "finish_reason": "stop"}]}


class DemoClient:
    def post(self, _url, **kwargs):
        messages = kwargs["json"]["messages"]
        has_secret = any("маяк" in message["content"] for message in messages)
        if messages[0]["content"].startswith("Обнови краткое резюме"):
            answer = "Кодовое слово пользователя: маяк." if has_secret else "Обсуждали детали проекта."
        elif messages[-1]["content"] == "Какое моё кодовое слово?":
            answer = "маяк" if has_secret else "Кодового слова нет в доступном контексте."
        else:
            answer = "Запомнил. Продолжим обсуждение проекта."
        return DemoResponse(answer)


def main():
    with TemporaryDirectory() as directory:
        config = AgentConfig(
            api_key="demo", api_url="https://example.invalid", model="demo",
            system_prompt="Отвечай кратко.", temperature=0, timeout_seconds=10,
            database_path=str(Path(directory) / "demo.sqlite3"),
            context_window_tokens=16000, input_price_per_million=1, output_price_per_million=2,
        )
        repository = ChatRepository(config.database_path)
        agents = [SimpleAgent(replace(config, compression_enabled=enabled),
                              repository.create_chat().id, repository, DemoClient())
                  for enabled in (False, True)]
        print("Без LLM API: ответы и summary смоделированы, токены оценены локально.")
        print("Ход | Вход без сжатия ≈ | Вход со сжатием ≈ | Сообщений в summary | Вызовов сжатия")
        for turn in range(1, 17):
            question = ("Моё кодовое слово: маяк. " if turn == 1 else f"Обсудим пункт {turn}. ")
            question += "Это подробности проекта, которые полезно обсудить. " * 12
            for agent in agents:
                agent.reply(question)
            stats = agents[1].statistics()
            print(f"{turn:>3} | {agents[0].last_turn['input_tokens']:>16} | "
                  f"{agents[1].last_turn['input_tokens']:>16} | "
                  f"{stats['compression']['summarized_messages']:>19} | "
                  f"{stats['summary_totals']['turn_count']:>14}")
        for name, agent in zip(("Без сжатия", "Со сжатием"), agents):
            answer = agent.reply("Какое моё кодовое слово?")
            stats = agent.statistics()
            print(f"{name}: {answer}; сообщений в базе: {len(agent.history) - 1}; "
                  f"расход с учётом summary ≈{stats['overall_totals']['total_tokens']} токенов; "
                  f"${stats['overall_totals']['known_cost_usd']:.6f}")
        print("\nТекст summary:", agents[1].statistics()["compression"]["summary"])


if __name__ == "__main__":
    main()
