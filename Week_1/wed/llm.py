#!/usr/bin/env python3

import os
import sys
from dataclasses import dataclass
from typing import Any, Dict, Optional

import requests


PROMPT_ENGINEER_SYSTEM = (
    "Ты — специалист по проектированию промптов. Создавай точные, "
    "самодостаточные промпты для решения задач, но не решай сами задачи."
)


@dataclass(frozen=True)
class Config:
    api_key: str
    api_url: str
    model: str
    system_prompt: str
    temperature: float

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


def ask_llm(
    user_prompt: str,
    config: Config,
    system_prompt: Optional[str] = None,
) -> LlmResult:
    response = requests.post(
        config.api_url,
        headers={
            "Authorization": f"Bearer {config.api_key}",
            "Content-Type": "application/json",
        },
        json=build_payload(user_prompt, config, system_prompt),
        timeout=120,
    )

    if not response.ok:
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


def format_api_error(response: requests.Response, fallback_url: str) -> str:
    reason = str(response.reason or "").strip()
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


def run_profile(profile: PromptProfile, task: str, config: Config) -> None:
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
    print_result(ask_llm(solution_prompt, config))


def normalize_console_input(value: str) -> str:
    characters = []
    for character in value:
        if character in {"\b", "\x7f"}:
            if characters:
                characters.pop()
        elif character.isprintable() or character == "\t":
            characters.append(character)
    return "".join(characters).strip()


def read_console_input(prompt: str) -> str:
    return normalize_console_input(input(prompt))


def choose_menu_option() -> str:
    print("\nВыберите способ решения:")
    for key, profile in PROFILES.items():
        print(f"  {key}. {profile.title}")
    print(f"  {COMPARE_OPTION}. Сравнить все четыре способа")
    print(f"  {EXIT_OPTION}. Выход")

    while True:
        choice = read_console_input("Ваш выбор: ")
        if choice in {*PROFILES, COMPARE_OPTION, EXIT_OPTION}:
            return choice
        print("Введите число от 0 до 5.")


def read_task(previous_task: Optional[str]) -> str:
    if previous_task:
        task = read_console_input("Задача (Enter — повторить предыдущую): ")
        return task or previous_task

    task = read_console_input("Введите задачу: ")
    if not task:
        raise RuntimeError("Задача не должна быть пустой")
    return task


def profiles_for_choice(choice: str) -> list[PromptProfile]:
    if choice == COMPARE_OPTION:
        return list(PROFILES.values())
    return [PROFILES[choice]]


def main() -> None:
    config = Config.from_env()
    previous_task: Optional[str] = None
    command_line_task = normalize_console_input(" ".join(sys.argv[1:])) or None

    print(f"Модель: {config.model}")
    print(f"API: {config.api_url}")
    print(f"Температура: {config.temperature}")

    while True:
        choice = choose_menu_option()
        if choice == EXIT_OPTION:
            return

        if command_line_task is not None:
            task = command_line_task
            command_line_task = None
            print(f"Задача: {task}")
        else:
            task = read_task(previous_task)
        previous_task = task

        for profile in profiles_for_choice(choice):
            try:
                run_profile(profile, task, config)
            except (
                RuntimeError,
                requests.RequestException,
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
