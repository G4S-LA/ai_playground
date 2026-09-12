"""Проверка сжатия в веб-чате через Chromium, без LLM API."""

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
        config = AgentConfig(
            api_key="test", api_url="https://example.invalid", model="test-model",
            system_prompt="Отвечай кратко.", temperature=0, timeout_seconds=10,
            database_path=str(Path(directory) / "browser.sqlite3"),
        )
        repository = ChatRepository(config.database_path)
        app = create_app(config, repository, lambda config, chat_id, repository: SimpleAgent(
            config, chat_id, repository, DemoClient(),
        ))
        client = app.test_client()
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            page = browser.new_page(viewport={"width": 1440, "height": 1000})
            errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))

            def respond(route):
                request = route.request
                response = client.open(
                    urlsplit(request.url).path, method=request.method,
                    data=request.post_data, content_type=request.headers.get("content-type"),
                )
                route.fulfill(status=response.status_code, body=response.data,
                              content_type=response.content_type)

            page.route("http://agent.test/**", respond)
            page.goto("http://agent.test/")
            question = page.locator("#message-input")
            expect(question).to_be_enabled()
            expect(page.locator("#compression-enabled")).to_be_checked()
            page.locator("#keep-recent").fill("2")
            page.locator("#summary-every").fill("2")

            def send(text, count):
                question.fill(text)
                page.locator("#send-button").click()
                expect(page.locator(".message--agent:not(.message--pending)")).to_have_count(count)
                expect(question).to_be_enabled()
                expect(page.locator(".message--error")).to_have_count(0)

            send("Моё кодовое слово: маяк", 1)
            send("Обсудим проект", 2)
            expect(page.locator("#compression-status")).to_contain_text("сожмём ещё 2")
            send("Какое моё кодовое слово?", 3)
            expect(page.locator(".message--agent").last).to_have_text("маяк")
            page.locator("#summary-details > summary").click()
            expect(page.locator("#history-summary")).to_contain_text("маяк")
            expect(page.locator("#compression-status")).to_contain_text("В summary: 2 сообщ.")
            page.locator("#compression-enabled").uncheck()
            expect(page.locator("#compression-status")).to_contain_text("Сжатие выключено")
            page.reload()
            expect(page.locator("#compression-enabled")).not_to_be_checked()
            expect(page.locator("#keep-recent")).to_have_value("2")
            expect(page.locator(".message--user")).to_have_count(3)
            page.locator("#compression-enabled").check()
            send("Продолжай", 4)
            expect(page.locator("#compression-status")).to_contain_text("В summary: 4 сообщ.")
            expect(page.locator("#usage-rows tr")).to_have_count(4)
            page.locator(".metrics > details").last.locator("summary").click()
            expect(page.locator("#compression-usage")).to_contain_text("Сжатие: 2 запросов")
            for width, height in ((1440, 1000), (390, 844)):
                page.set_viewport_size({"width": width, "height": height})
                composer = page.locator("#chat-form").bounding_box()
                assert composer["y"] + composer["height"] <= height, composer
                assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
                if options.screenshots_dir:
                    page.screenshot(path=str(options.screenshots_dir / f"compression-{width}.png"))
            assert not errors, errors
            browser.close()
            print("Browser OK: сжатие, summary, on/off, перезагрузка, настройки, расход, mobile.")


if __name__ == "__main__":
    main()
