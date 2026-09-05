#!/usr/bin/env python3

import asyncio
import os
import queue
import sys
import threading
from contextlib import suppress
from dataclasses import dataclass
from typing import Any, Dict, Optional

import httpx


PROMPT_ENGINEER_SYSTEM = (
    "Ты — специалист по проектированию промптов. Создавай точные, "
    "самодостаточные промпты для решения задач, но не решай сами задачи."
)
_END_OF_INPUT = object()


class RequestCancelled(RuntimeError):
    pass


class ConsoleInput:
    def __init__(self) -> None:
        self._lines: queue.Queue[Any] = queue.Queue()
        self._reader = threading.Thread(target=self._read_lines, daemon=True)
        self._reader.start()

    def _read_lines(self) -> None:
        while True:
            line = sys.stdin.readline()
            if line == "":
                self._lines.put(_END_OF_INPUT)
                return
            self._lines.put(line.rstrip("\r\n"))

    def read(self, prompt: str) -> str:
        print(prompt, end="", flush=True)
        value = self._lines.get()
        if value is _END_OF_INPUT:
            raise EOFError
        return normalize_console_input(value)

    def poll(self) -> Optional[str]:
        try:
            value = self._lines.get_nowait()
        except queue.Empty:
            return None
        if value is _END_OF_INPUT:
            raise EOFError
        return normalize_console_input(value)


@dataclass(frozen=True)
class Config:
    api_key: str
    api_url: str
    model: str
    system_prompt: str
    temperature: float
    timeout_seconds: int

    @classmethod
    def from_env(cls) -> "Config":
        api_key = _env("LLM_API_KEY") or _env("DASHSCOPE_API_KEY")
        if not api_key:
            raise RuntimeError(
                "Не задана переменная LLM_API_KEY или DASHSCOPE_API_KEY"
            )

        return cls(
            api_key=api_key,
            api_url=_env(
                "LLM_API_URL",
                "https://api.openai.com/v1/chat/completions",
            ),
            model=_env("LLM_MODEL", "gpt-4o-mini"),
            system_prompt=_env(
                "LLM_SYSTEM_PROMPT",
                "Ты — полезный ассистент.",
            ),
            temperature=_float_in_range(
                "LLM_TEMPERATURE",
                default=0.2,
                minimum=0.0,
                maximum=2.0,
            ),
            timeout_seconds=_positive_int("LLM_TIMEOUT_SECONDS", 360),
        )


@dataclass(frozen=True)
class PromptProfile:
    key: str
    title: str


@dataclass(frozen=True)
class LlmResult:
    content: str
    finish_reason: str
    usage: Dict[str, Any]


PROFILES = {
    "1": PromptProfile("1", "Прямой ответ без дополнительных инструкций"),
    "2": PromptProfile("2", "Решение с инструкцией «решай пошагово»"),
    "3": PromptProfile("3", "Сначала создать промпт, затем решить задачу"),
    "4": PromptProfile("4", "Решение группой экспертов"),
}

COMPARE_OPTION = "5"
EXIT_OPTION = "0"


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def _float_in_range(
    name: str,
    default: float,
    minimum: float,
    maximum: float,
) -> float:
    raw_value = _env(name, str(default))
    try:
        value = float(raw_value)
    except ValueError as error:
        raise RuntimeError(f"{name} должен быть числом") from error
    if not minimum <= value < maximum:
        raise RuntimeError(
            f"{name} должен быть в диапазоне [{minimum}, {maximum})"
        )
    return value


def _positive_int(name: str, default: int) -> int:
    raw_value = _env(name, str(default))
    try:
        value = int(raw_value)
    except ValueError as error:
        raise RuntimeError(f"{name} должен быть целым числом") from error
    if value <= 0:
        raise RuntimeError(f"{name} должен быть больше нуля")
    return value


def build_step_by_step_prompt(task: str) -> str:
    return f"{task}\n\nРешай пошагово."


def build_prompt_generation_request(task: str) -> str:
    return (
        "Составь эффективный промпт для другой языковой модели, которая "
        "должна решить приведённую ниже задачу. Промпт должен полностью "
        "сохранять условие задачи, требовать обоснованное решение, проверку "
        "результата и чёткий итоговый ответ. Верни только готовый промпт без "
        "пояснений и не решай задачу самостоятельно.\n\n"
        f"Задача:\n{task}"
    )


def build_experts_prompt(task: str) -> str:
    return (
        "Создай группу из трёх экспертов и реши задачу от лица каждого из них.\n\n"
        "Эксперты:\n"
        "1. Аналитик — формализует условие, выделяет данные и допущения, "
        "затем предлагает полное решение.\n"
        "2. Инженер — независимо ищет практический или алгоритмический способ "
        "решения и проверяет вычисления.\n"
        "3. Критик — независимо решает задачу, ищет ошибки и рассматривает "
        "граничные случаи.\n\n"
        "Сначала выведи отдельное решение каждого эксперта под заголовками "
        "«Аналитик», «Инженер» и «Критик». Затем добавь общий вывод с итоговым "
        "ответом группы.\n\n"
        f"Задача:\n{task}"
    )


def build_payload(
    user_prompt: str,
    config: Config,
    system_prompt: Optional[str] = None,
) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "model": config.model,
        "messages": [
            {
                "role": "system",
                "content": system_prompt or config.system_prompt,
            },
            {"role": "user", "content": user_prompt},
        ],
        "temperature": config.temperature,
    }

    return payload


async def post_with_cancellation(
    user_prompt: str,
    config: Config,
    console: ConsoleInput,
    system_prompt: Optional[str] = None,
) -> httpx.Response:
    timeout = httpx.Timeout(config.timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        request_task = asyncio.create_task(
            client.post(
                config.api_url,
                headers={
                    "Authorization": f"Bearer {config.api_key}",
                    "Content-Type": "application/json",
                },
                json=build_payload(user_prompt, config, system_prompt),
            )
        )

        try:
            while True:
                console_value = console.poll()
                if console_value == "0":
                    raise RequestCancelled("Запрос отменён пользователем")
                if console_value is not None:
                    print(
                        "Для отмены текущего запроса введите 0 и нажмите Enter."
                    )
                if request_task.done():
                    return await request_task
                await asyncio.sleep(0.1)
        finally:
            if not request_task.done():
                request_task.cancel()
                with suppress(asyncio.CancelledError):
                    await request_task


def ask_llm(
    user_prompt: str,
    config: Config,
    console: ConsoleInput,
    system_prompt: Optional[str] = None,
) -> LlmResult:
    print("Ожидание ответа: введите 0 и нажмите Enter, чтобы отменить запрос.")
    response = asyncio.run(
        post_with_cancellation(
            user_prompt,
            config,
            console,
            system_prompt,
        )
    )

    if not response.is_success:
        raise RuntimeError(format_api_error(response, config.api_url))

    result = response.json()
    choice = result["choices"][0]
    content = choice["message"]["content"]
    if not isinstance(content, str):
        raise RuntimeError("API вернул ответ в неожиданном формате")

    return LlmResult(
        content=content,
        finish_reason=choice.get("finish_reason", "unknown"),
        usage=result.get("usage") or {},
    )


def format_api_error(response: httpx.Response, fallback_url: str) -> str:
    reason = str(response.reason_phrase or "").strip()
    status = f"{response.status_code} {reason}".strip()
    response_url = response.url or fallback_url
    response_body = response.text.strip() or "<пустое тело ответа>"

    lines = [
        f"Ошибка API {status}",
        f"URL: {response_url}",
    ]

    request_id = (
        response.headers.get("x-request-id")
        or response.headers.get("x-dashscope-request-id")
        or response.headers.get("request-id")
    )
    if request_id:
        lines.append(f"Request ID: {request_id}")

    lines.append(f"Ответ сервера: {response_body}")
    return "\n".join(lines)


def print_result(result: LlmResult) -> None:
    print(result.content)
    print(f"Причина завершения: {result.finish_reason}")

    if result.usage:
        prompt_tokens = result.usage.get("prompt_tokens", "?")
        completion_tokens = result.usage.get("completion_tokens", "?")
        total_tokens = result.usage.get("total_tokens", "?")
        print(
            "Токены: "
            f"вход={prompt_tokens}, выход={completion_tokens}, всего={total_tokens}"
        )


def run_profile(
    profile: PromptProfile,
    task: str,
    config: Config,
    console: ConsoleInput,
) -> None:
    print(f"\n=== {profile.title} ===")

    if profile.key == "1":
        solution_prompt = task
    elif profile.key == "2":
        solution_prompt = build_step_by_step_prompt(task)
    elif profile.key == "3":
        prompt_request = build_prompt_generation_request(task)
        print("\n--- Шаг 1: генерация промпта ---")
        print("Запрос к генератору промпта:")
        print(prompt_request)
        print("\nСгенерированный промпт:")
        prompt_result = ask_llm(
            prompt_request,
            config,
            console,
            system_prompt=PROMPT_ENGINEER_SYSTEM,
        )
        solution_prompt = prompt_result.content.strip()
        if not solution_prompt:
            raise RuntimeError("Модель вернула пустой промпт для решения задачи")
        print_result(prompt_result)
        print("\n--- Шаг 2: решение по сгенерированному промпту ---")
    elif profile.key == "4":
        solution_prompt = build_experts_prompt(task)
    else:
        raise RuntimeError(f"Неизвестный профиль: {profile.key}")

    print("Промпт для решения:")
    print(solution_prompt)
    print("\nОтвет:")
    print_result(ask_llm(solution_prompt, config, console))


def normalize_console_input(value: str) -> str:
    characters = []
    for character in value:
        if character in {"\b", "\x7f"}:
            if characters:
                characters.pop()
        elif character.isprintable() or character == "\t":
            characters.append(character)
    return "".join(characters).strip()


def choose_menu_option(console: ConsoleInput) -> str:
    print("\nВыберите способ решения:")
    for key, profile in PROFILES.items():
        print(f"  {key}. {profile.title}")
    print(f"  {COMPARE_OPTION}. Сравнить все четыре способа")
    print(f"  {EXIT_OPTION}. Выход")

    while True:
        choice = console.read("Ваш выбор: ")
        if choice in {*PROFILES, COMPARE_OPTION, EXIT_OPTION}:
            return choice
        print("Введите число от 0 до 5.")


def read_task(console: ConsoleInput, previous_task: Optional[str]) -> str:
    if previous_task:
        task = console.read("Задача (Enter — повторить предыдущую): ")
        return task or previous_task

    task = console.read("Введите задачу: ")
    if not task:
        raise RuntimeError("Задача не должна быть пустой")
    return task


def profiles_for_choice(choice: str) -> list[PromptProfile]:
    if choice == COMPARE_OPTION:
        return list(PROFILES.values())
    return [PROFILES[choice]]


def main() -> None:
    config = Config.from_env()
    console = ConsoleInput()
    previous_task: Optional[str] = None
    command_line_task = normalize_console_input(" ".join(sys.argv[1:])) or None

    print(f"Модель: {config.model}")
    print(f"API: {config.api_url}")
    print(f"Температура: {config.temperature}")
    print(f"Таймаут одного API-вызова: {config.timeout_seconds} с")

    while True:
        choice = choose_menu_option(console)
        if choice == EXIT_OPTION:
            return

        if command_line_task is not None:
            task = command_line_task
            command_line_task = None
            print(f"Задача: {task}")
        else:
            task = read_task(console, previous_task)
        previous_task = task

        for profile in profiles_for_choice(choice):
            try:
                run_profile(profile, task, config, console)
            except RequestCancelled:
                print("\nТекущий запрос отменён. Возврат в меню.")
                break
            except (
                RuntimeError,
                httpx.RequestError,
                ValueError,
                KeyError,
                IndexError,
            ) as error:
                print(
                    f"\nОшибка для способа «{profile.title}»: {error}",
                    file=sys.stderr,
                )


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, KeyboardInterrupt, EOFError) as error:
        if isinstance(error, RuntimeError):
            print(f"Ошибка: {error}", file=sys.stderr)
            raise SystemExit(1) from error
        print("\nРабота завершена.")
