#!/usr/bin/env python3

import asyncio
import os
import queue
import sys
import threading
from dataclasses import dataclass
from typing import Any, Dict, Optional

import httpx


PROMPT_ENGINEER_SYSTEM = (
    "Ты — специалист по проектированию промптов. Создавай точные, "
    "самодостаточные промпты для решения задач, но не решай сами задачи."
)
SOLUTION_OUTPUT_INSTRUCTION = (
    "Дай развёрнутое решение. В конце ответа обязательно добавь раздел "
    "«Краткий вывод» и сформулируй в нём итог в 1–3 предложениях."
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


@dataclass(frozen=True)
class ProfileExecution:
    profile: PromptProfile
    solution_prompt: str
    answer: LlmResult
    prompt_request: Optional[str] = None
    prompt_result: Optional[LlmResult] = None


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
        "результата, развёрнутый ответ и раздел «Краткий вывод» с итогом в "
        "1–3 предложениях. Верни только готовый промпт без пояснений и не "
        "решай задачу самостоятельно.\n\n"
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
        "Выведи развёрнутое решение каждого эксперта под заголовками "
        "«Аналитик», «Инженер» и «Критик». В конце раздела каждого эксперта "
        "добавь подраздел «Краткий вывод эксперта» с итогом в 1–3 "
        "предложениях. Затем добавь общий раздел «Краткий вывод» с итоговым "
        "ответом группы.\n\n"
        f"Задача:\n{task}"
    )


def build_payload(
    user_prompt: str,
    config: Config,
    system_prompt: Optional[str] = None,
    solution_request: bool = True,
) -> Dict[str, Any]:
    effective_system_prompt = (
        system_prompt if system_prompt is not None else config.system_prompt
    )
    if solution_request:
        effective_system_prompt = (
            f"{effective_system_prompt}\n\n{SOLUTION_OUTPUT_INSTRUCTION}"
        )

    payload: Dict[str, Any] = {
        "model": config.model,
        "messages": [
            {
                "role": "system",
                "content": effective_system_prompt,
            },
            {"role": "user", "content": user_prompt},
        ],
        "temperature": config.temperature,
    }

    return payload


async def send_request(
    user_prompt: str,
    config: Config,
    client: httpx.AsyncClient,
    system_prompt: Optional[str] = None,
    solution_request: bool = True,
) -> LlmResult:
    response = await client.post(
        config.api_url,
        headers={
            "Authorization": f"Bearer {config.api_key}",
            "Content-Type": "application/json",
        },
        json=build_payload(
            user_prompt,
            config,
            system_prompt,
            solution_request,
        ),
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


async def execute_profile(
    profile: PromptProfile,
    task: str,
    config: Config,
    client: httpx.AsyncClient,
) -> ProfileExecution:
    prompt_request: Optional[str] = None
    prompt_result: Optional[LlmResult] = None

    if profile.key == "1":
        solution_prompt = task
    elif profile.key == "2":
        solution_prompt = build_step_by_step_prompt(task)
    elif profile.key == "3":
        prompt_request = build_prompt_generation_request(task)
        prompt_result = await send_request(
            prompt_request,
            config,
            client,
            system_prompt=PROMPT_ENGINEER_SYSTEM,
            solution_request=False,
        )
        solution_prompt = prompt_result.content.strip()
        if not solution_prompt:
            raise RuntimeError("Модель вернула пустой промпт для решения задачи")
    elif profile.key == "4":
        solution_prompt = build_experts_prompt(task)
    else:
        raise RuntimeError(f"Неизвестный профиль: {profile.key}")

    answer = await send_request(solution_prompt, config, client)
    return ProfileExecution(
        profile=profile,
        solution_prompt=solution_prompt,
        answer=answer,
        prompt_request=prompt_request,
        prompt_result=prompt_result,
    )


async def execute_profiles_async(
    profiles: list[PromptProfile],
    task: str,
    config: Config,
    console: ConsoleInput,
) -> list[Any]:
    timeout = httpx.Timeout(config.timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        profile_tasks = [
            asyncio.create_task(execute_profile(profile, task, config, client))
            for profile in profiles
        ]

        try:
            while True:
                console_value = console.poll()
                if console_value == "0":
                    raise RequestCancelled("Запросы отменены пользователем")
                if console_value is not None:
                    print(
                        "Для отмены текущих запросов введите 0 и нажмите Enter."
                    )
                if all(profile_task.done() for profile_task in profile_tasks):
                    return list(
                        await asyncio.gather(
                            *profile_tasks,
                            return_exceptions=True,
                        )
                    )
                await asyncio.sleep(0.1)
        finally:
            for profile_task in profile_tasks:
                if not profile_task.done():
                    profile_task.cancel()
            await asyncio.gather(*profile_tasks, return_exceptions=True)


def execute_profiles(
    profiles: list[PromptProfile],
    task: str,
    config: Config,
    console: ConsoleInput,
) -> list[Any]:
    if len(profiles) > 1:
        print(
            "Четыре способа запущены параллельно. В способе 3 второй запрос "
            "начнётся после генерации промпта."
        )
    else:
        print("Запрос запущен.")
    print("Во время ожидания введите 0 и нажмите Enter, чтобы отменить.")
    return asyncio.run(execute_profiles_async(profiles, task, config, console))


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


def print_profile_execution(
    execution: ProfileExecution,
) -> None:
    profile = execution.profile
    print(f"\n=== {profile.title} ===")

    if (
        execution.prompt_request is not None
        and execution.prompt_result is not None
    ):
        print("\n--- Шаг 1: генерация промпта ---")
        print("Запрос к генератору промпта:")
        print(execution.prompt_request)
        print("\nСгенерированный промпт:")
        print_result(execution.prompt_result)
        print("\n--- Шаг 2: решение по сгенерированному промпту ---")

    print("Промпт для решения:")
    print(execution.solution_prompt)
    print("\nОтвет:")
    print_result(execution.answer)


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
    print("Формат решений: развёрнутый ответ и краткий вывод в конце")

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

        profiles = profiles_for_choice(choice)
        try:
            executions = execute_profiles(profiles, task, config, console)
        except RequestCancelled:
            print("\nТекущие запросы отменены. Возврат в меню.")
            continue
        except (httpx.RequestError, ValueError) as error:
            print(f"\nОшибка выполнения запросов: {error}", file=sys.stderr)
            continue

        for profile, execution in zip(profiles, executions):
            if isinstance(execution, BaseException):
                print(
                    f"\nОшибка для способа «{profile.title}»: {execution}",
                    file=sys.stderr,
                )
                continue
            print_profile_execution(execution)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, KeyboardInterrupt, EOFError) as error:
        if isinstance(error, RuntimeError):
            print(f"Ошибка: {error}", file=sys.stderr)
            raise SystemExit(1) from error
        print("\nРабота завершена.")
