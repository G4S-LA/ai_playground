"""Проверка стратегий, блокировки и веток в Chromium без LLM API."""

import argparse
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

from agent import AgentConfig, SimpleAgent
from chat_repository import ChatRepository
from demo import DemoClient
from web import create_app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--screenshots-dir", type=Path)
    options = parser.parse_args()
    if options.screenshots_dir:
        options.screenshots_dir.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory() as directory:
        config = AgentConfig(api_key="test", api_url="https://example.invalid", model="demo",
                             system_prompt="Отвечай кратко.", temperature=0, timeout_seconds=10,
                             database_path=str(Path(directory) / "browser.sqlite3"))
        repository = ChatRepository(config.database_path)
        app = create_app(config, repository, lambda config, chat_id, repo: SimpleAgent(config, chat_id, repo, DemoClient()))
        client = app.test_client()
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            page = browser.new_page(viewport={"width":1440, "height":1000})
            errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))

            def respond(route):
                request = route.request
                response = client.open(urlsplit(request.url).path, method=request.method,
                                       data=request.post_data, content_type=request.headers.get("content-type"))
                route.fulfill(status=response.status_code, body=response.data, content_type=response.content_type)

            page.route("http://agent.test/**", respond)
            page.goto("http://agent.test/")
            question = page.locator("#message-input")
            expect(question).to_be_enabled()
            expect(page.locator("#strategy-sliding")).to_be_checked()
            expect(page.locator("#copy-button")).to_be_disabled()
            page.locator("#strategy-facts").check()
            expect(page.locator("#strategy-sliding")).not_to_be_checked()
            page.locator("#keep-recent").fill("2")
            page.locator("#facts-max-tokens").fill("200")

            def send(text, count):
                question.fill(text)
                page.locator("#send-button").click()
                expect(page.locator(".message--agent:not(.message--pending)")).to_have_count(count)
                expect(question).to_be_enabled()
                expect(page.locator(".message--error")).to_have_count(0)

            send("Кодовое слово: маяк", 1)
            source_id = repository.list_chats()[0].id
            for selector in ("#strategy-sliding", "#strategy-facts", "#keep-recent", "#context-window", "#facts-max-tokens"):
                expect(page.locator(selector)).to_be_disabled()
            send("Обсудим проект", 2)
            send("Какое кодовое слово?", 3)
            expect(page.locator(".message--agent").last).to_have_text("маяк")
            page.locator("#facts-details > summary").click()
            expect(page.locator("#history-facts")).to_contain_text("маяк")
            page.reload()
            expect(question).to_be_enabled()
            expect(page.locator("#keep-recent")).to_have_value("2")
            expect(page.locator("#facts-max-tokens")).to_have_value("200")
            expect(page.locator("#strategy-facts")).to_be_disabled()

            page.locator("#copy-button").click()
            expect(page.locator("#chat-title")).to_contain_text("Ветка:")
            expect(question).to_be_enabled()
            send("Кодовое слово: север", 4)
            page.locator("#branch-details > summary").click()
            page.locator("#checkpoint-list button").first.click()
            expect(page.locator("#chat-title")).to_have_text("Новая ветка")
            expect(question).to_be_enabled()
            expect(page.locator(".message--user")).to_have_count(3)
            send("Кодовое слово: юг", 4)
            send("Какое кодовое слово?", 5)
            expect(page.locator(".message--agent").last).to_have_text("юг")

            page.locator("#chat-list button", has_text="Ветка:").click()
            expect(page.locator("#chat-title")).to_contain_text("Ветка:")
            expect(question).to_be_enabled()
            send("Какое кодовое слово?", 5)
            expect(page.locator(".message--agent").last).to_have_text("север")
            source_title = repository.get_chat(source_id).title
            page.locator("#chat-list button").filter(has_text=source_title).last.click()
            expect(page.locator("#chat-title")).to_have_text(source_title)
            expect(question).to_be_enabled()
            expect(page.locator(".message--user")).to_have_count(3)

            page.locator("#new-chat-button").click()
            expect(page.locator("#strategy-sliding")).to_be_enabled()
            expect(page.locator("#strategy-sliding")).to_be_checked()
            page.locator("#keep-recent").fill("2")
            send("Кодовое слово: маяк", 1)
            send("Обсудим проект", 2)
            send("Какое кодовое слово?", 3)
            expect(page.locator(".message--agent").last).to_have_text("Не помню")
            expect(page.locator("#token-preview")).to_contain_text("Контекст:")
            # Ошибка первой отправки тоже оставляет настройки замороженными.
            page.locator("#new-chat-button").click()
            expect(page.locator("#context-window")).to_be_enabled()
            page.locator("#context-window").fill("1")
            question.fill("Не помещается")
            page.locator("#send-button").click()
            expect(page.locator(".message--error")).to_contain_text("Переполнение")
            expect(question).to_have_value("Не помещается")
            expect(page.locator("#context-window")).to_be_disabled()
            page.reload()
            expect(page.locator("#context-window")).to_be_disabled()
            expect(question).to_be_enabled()
            # Вновь открываем Sliding Window для проверки и снимков вёрстки.
            page.locator("#chat-list button").filter(has_text=source_title).first.click()
            expect(page.locator(".message--user")).to_have_count(3)
            expect(question).to_be_enabled()
            expect(page.locator("#token-preview")).to_contain_text("Контекст:")
            for width, height in ((1440, 1000), (390, 844)):
                page.set_viewport_size({"width":width, "height":height})
                composer = page.locator("#chat-form").bounding_box()
                assert composer["y"] + composer["height"] <= height, composer
                assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
                if options.screenshots_dir:
                    page.screenshot(path=str(options.screenshots_dir / f"strategies-{width}.png"))
            assert not errors, errors
            browser.close()
            print("Browser OK: Sliding Window, Sticky Facts, две ветки из checkpoint, переключение, блокировка, перезапуск, mobile.")


if __name__ == "__main__":
    main()
