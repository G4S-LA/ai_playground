"""Детерминированная демонстрация механики без LLM API. python demo.py [--web]."""

import argparse
import json
import re
from pathlib import Path
from tempfile import TemporaryDirectory

from agent import AgentConfig, SimpleAgent
from chat_repository import ChatRepository
from context_strategies import ContextSettings


class DemoResponse:
    ok, status_code, text = True, 200, ""

    def __init__(self, answer):
        self.answer = answer

    def json(self):
        return {"choices": [{"message": {"content": self.answer}, "finish_reason": "stop"}]}


class DemoClient:
    """Только распознавание кодового слова для наглядного сценария, не настоящая LLM."""

    def post(self, url, **kwargs):
        messages = kwargs["json"]["messages"]
        if messages[0]["content"].startswith("Обнови key-value"):
            data = json.loads(messages[1]["content"])
            facts = data["facts"]
            question = data["recent_messages"][-1]["content"]
            match = re.search(r"Кодовое слово:\s*(\w+)", question, re.I)
            if match:
                facts["code_word"] = match[1]
            if "забудь" in question.lower():
                facts.pop("code_word", None)
            return DemoResponse(json.dumps(facts, ensure_ascii=False))
        if "какое" in messages[-1]["content"].lower():
            context = "\n".join(item["content"] for item in messages)
            matches = re.findall(r'(?:Кодовое слово:\s*|"code_word":\s*")(\w+)', context, re.I)
            return DemoResponse(matches[-1] if matches else "Не помню")
        return DemoResponse("Принято.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--web", action="store_true", help="Открыть веб-демо на порту 5000")
    options = parser.parse_args()
    with TemporaryDirectory() as directory:
        config = AgentConfig(api_key="demo", api_url="https://example.invalid", model="demo (без LLM API)",
                             system_prompt="Отвечай кратко.", temperature=0, timeout_seconds=10,
                             database_path=str(Path(directory) / "demo.sqlite3"))
        repo = ChatRepository(config.database_path)
        if options.web:
            from web import create_app
            app = create_app(config, repo, lambda config, chat_id, repo: SimpleAgent(config, chat_id, repo, DemoClient()))
            print("Демо: http://127.0.0.1:5000. Сообщите «Кодовое слово: маяк», затем спросите «Какое кодовое слово?».")
            app.run(host="127.0.0.1", port=5000, debug=False)
            return
        print("Ответы смоделированы; демонстрация проверяет управление контекстом, а не качество LLM.\n")
        for strategy in ("sliding_window", "facts"):
            chat = repo.create_chat(settings=ContextSettings(strategy=strategy, keep_recent_messages=2))
            agent = SimpleAgent(config, chat.id, repo, DemoClient())
            agent.reply("Кодовое слово: маяк")
            agent.reply("Обсудим проект")
            answer = agent.reply("Какое кодовое слово?")
            print(f"{strategy:16} N=2 → {answer}")
            assert answer == ("маяк" if strategy == "facts" else "Не помню")
        point = agent.checkpoint("Общая точка")
        branches = []
        for word in ("север", "юг"):
            chat = repo.branch(point, word)
            branch = SimpleAgent(config, chat.id, repo, DemoClient())
            branch.reply(f"Кодовое слово: {word}")
            branches.append(branch)
        for branch, expected in zip(branches, ("север", "юг")):
            answer = branch.reply("Какое кодовое слово?")
            assert answer == expected
            print(f"Branching / {expected:5} → {answer}")
        assert agent.statistics()["context"]["facts"] == {"code_word":"маяк"}
        print("Исходный диалог → маяк. Все три стратегии работают.")


if __name__ == "__main__":
    main()
