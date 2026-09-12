"""Проверка веб-чата в Chromium с задержанными подставными ответами (без LLM API)."""

import argparse
import re
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright, expect

from agent import AgentConfig, SimpleAgent
from chat_repository import ChatRepository
from test_agent import FakeResponse, response_with
from token_usage import TokenCounter
from web import create_app


class BrowserHttpClient:
    def post(self, _url, **kwargs):
        question = kwargs["json"]["messages"][-1]["content"]
        if question == "Сбой API":
            return FakeResponse({}, ok=False, status_code=503, text="API недоступен")
        payload = response_with(f"</think>Ответ на: {question}").json()
        if question == "Ответ при сбое списка":
            payload["usage"] = {"prompt_tokens": 123, "completion_tokens": 17}
        return FakeResponse(payload)


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
            config, chat_id, repository, BrowserHttpClient(),
        ))
        client = app.test_client()

        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            page = browser.new_page(viewport={"width": 1440, "height": 1000})
            errors = []
            pending = []
            fail_chat_list = False
            page.on("pageerror", lambda error: errors.append(str(error)))

            def screenshot(name):
                if options.screenshots_dir:
                    page.screenshot(path=str(options.screenshots_dir / f"{name}.png"))

            def respond(route):
                request = route.request
                response = client.open(
                    urlsplit(request.url).path, method=request.method,
                    data=request.post_data,
                    content_type=request.headers.get("content-type"),
                )
                route.fulfill(status=response.status_code, body=response.data,
                              content_type=response.content_type)

            def route_request(route):
                nonlocal fail_chat_list
                path = urlsplit(route.request.url).path
                if path.endswith("/messages") and route.request.method == "POST":
                    pending.append(route)  # Удерживаем ответ до проверки ожидающего UI.
                elif path == "/api/chats" and route.request.method == "GET" and fail_chat_list:
                    fail_chat_list = False
                    route.fulfill(status=503, content_type="application/json",
                                  body='{"error":"Список временно недоступен"}')
                else:
                    respond(route)

            page.route("http://agent.test/**", route_request)
            page.goto("http://agent.test/")
            question_input = page.locator("#message-input")
            user_messages = page.locator(".message--user")
            answers = page.locator(".message--agent:not(.message--pending)")
            expect(question_input).to_be_enabled()
            expect(page.locator("#token-preview")).to_contain_text("Контекст:")
            expect(page.locator("#last-request-tokens")).to_have_text("Последний запрос в токенах: —")
            expect(page.locator("#last-answer-tokens")).to_have_text("Последний ответ в токенах: —")

            def send_waiting(question, expected_users):
                agent = next(iter(app.extensions["llm_agents"].values()))
                preview_agent = SimpleAgent(config, agent.chat_id, repository)
                preview_agent.set_context_window(int(page.locator("#context-window").input_value()))
                input_tokens = preview_agent.preview(question)["input_tokens_estimate"]
                question_input.fill(question)
                page.locator("#send-button").click()
                expect(user_messages).to_have_count(expected_users)
                expect(user_messages.last).to_have_text(question)
                expect(page.locator(".message--pending")).to_have_text("Модель готовит ответ…")
                expect(question_input).to_be_disabled()
                expect(page.locator("#token-preview")).to_contain_text(
                    f"Контекст: ≈{input_tokens} из"
                )
                for _ in range(100):
                    if pending:
                        break
                    page.wait_for_timeout(50)
                assert len(pending) == 1

            send_waiting("Первый вопрос", 1)
            screenshot("pending")
            expect(answers).to_have_count(0)
            expect(page.locator("#usage-rows tr")).to_have_count(0)
            respond(pending.pop())
            expect(question_input).to_be_enabled()
            expect(answers).to_have_text("Ответ на: Первый вопрос")
            expect(user_messages).to_have_count(1)
            expect(page.locator(".message--pending")).to_have_count(0)
            expect(page.locator("#usage-totals")).to_contain_text("тарифы не заданы полностью")
            expect(page.locator("#last-request-tokens")).to_have_text(re.compile(r"Последний запрос в токенах: ≈\d+"))
            expect(page.locator("#last-answer-tokens")).to_have_text(re.compile(r"Последний ответ в токенах: ≈\d+"))
            first_usage = page.locator(".last-turn-usage").inner_text()

            send_waiting("Сбой API", 2)
            respond(pending.pop())
            expect(question_input).to_have_value("Сбой API")
            expect(question_input).to_be_enabled()
            expect(user_messages).to_have_count(1)
            expect(answers).to_have_count(1)
            expect(page.locator(".message--pending")).to_have_count(0)
            expect(page.locator(".last-turn-usage")).to_have_text(first_usage)

            page.locator("#context-window").fill("1")
            question_input.fill("Повтор после переполнения")
            expect(page.locator("#context-breakdown")).to_contain_text("Переполнение")
            send_waiting("Повтор после переполнения", 2)
            respond(pending.pop())
            expect(question_input).to_have_value("Повтор после переполнения")
            expect(question_input).to_be_enabled()
            expect(user_messages).to_have_count(1)
            expect(page.locator(".metrics")).not_to_contain_text(re.compile("резерв", re.I))
            expect(page.locator(".message--error").last).not_to_contain_text("резерв")
            page.locator("#context-window").fill("8192")
            send_waiting("Повтор после переполнения", 2)
            respond(pending.pop())
            expect(page.locator("#usage-rows tr")).to_have_count(2)
            expect(question_input).to_be_enabled()
            expect(user_messages).to_have_count(2)

            agent = next(iter(app.extensions["llm_agents"].values()))
            history = repository.load_messages(agent.chat_id)
            window_tokens = TokenCounter().messages([
                agent.history[0], *history[-2:],
                {"role": "user", "content": "Ответ при сбое списка"},
            ])
            page.locator("#context-window").fill(str(window_tokens))
            question_input.fill("Ответ при сбое списка")
            expect(page.locator("#context-breakdown")).to_contain_text("Старых сообщений вне контекста: 2")
            send_waiting("Ответ при сбое списка", 3)
            screenshot("sliding")
            fail_chat_list = True
            respond(pending.pop())
            expect(question_input).to_be_enabled()
            expect(question_input).to_have_value("")
            expect(user_messages).to_have_count(3)
            expect(answers).to_have_count(3)
            expect(page.locator(".message--error").last).to_contain_text("Ответ получен")

            page.reload()
            expect(user_messages).to_have_count(3)
            expect(answers).to_have_count(3)
            expect(page.locator("#messages")).not_to_contain_text("</think>")
            expect(page.locator("#usage-rows tr")).to_have_count(3)
            expect(page.locator("#token-preview")).to_contain_text("Контекст:")
            expect(page.locator("#last-request-tokens")).to_have_text("Последний запрос в токенах: 123")
            expect(page.locator("#last-answer-tokens")).to_have_text("Последний ответ в токенах: 17")
            expect(page.locator("#usage-rows tr").last.locator("td").nth(5)).to_have_text("2")
            screenshot("desktop")
            page.locator("summary").click()
            expect(page.locator("#token-details")).to_contain_text("Сохранённая история")
            for width, height in ((1440, 1000), (390, 844)):
                page.set_viewport_size({"width": width, "height": height})
                composer = page.locator("#chat-form").bounding_box()
                assert composer["y"] + composer["height"] <= height, composer
                assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
                screenshot(f"details-{width}")
            assert not errors, errors
            print("Browser OK: pending message, clean answer, errors, retry, reload, metrics, mobile.")
            browser.close()


if __name__ == "__main__":
    main()
