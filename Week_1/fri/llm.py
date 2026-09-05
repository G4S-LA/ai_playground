#!/usr/bin/env python3

import asyncio
import os
import queue
import sys
import threading
import time
from dataclasses import dataclass
from typing import Any, Dict, Optional

import httpx


QUALITY_JUDGE_SYSTEM = (
    "Ты — независимый оценщик ответов языковых моделей. Оценивай только "
    "качество показанных ответов и не пытайся определить, какая модель их дала."
)
COMPARE_OPTION = "4"
EXIT_OPTION = "0"
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
class ModelProfile:
    key: str
    tier: str
    model: str
    input_price_per_million: Optional[float]
    output_price_per_million: Optional[float]


@dataclass(frozen=True)
class Config:
    api_key: str
    api_url: str
    system_prompt: str
    temperature: float
    timeout_seconds: int
    currency: str
    profiles: tuple[ModelProfile, ...]

    @classmethod
    def from_env(cls) -> "Config":
        api_key = _env("LLM_API_KEY") or _env("DASHSCOPE_API_KEY")
        if not api_key:
            raise RuntimeError(
                "Не задана переменная LLM_API_KEY или DASHSCOPE_API_KEY"
            )

        profiles = (
            _profile_from_env("1", "Слабая", "WEAK"),
            _profile_from_env("2", "Средняя", "MEDIUM"),
            _profile_from_env("3", "Сильная", "STRONG"),
        )

        return cls(
            api_key=api_key,
            api_url=_env(
                "LLM_API_URL",
                "https://api.openai.com/v1/chat/completions",
            ),
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
            currency=_env("LLM_PRICE_CURRENCY", "USD"),
            profiles=profiles,
        )


@dataclass(frozen=True)
class TokenUsage:
    input_tokens: Optional[int]
    output_tokens: Optional[int]
    total_tokens: Optional[int]


@dataclass(frozen=True)
class ModelResult:
    profile: ModelProfile
    content: str
    elapsed_seconds: float
    usage: TokenUsage
    estimated_cost: Optional[float]
    finish_reason: str


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def _required_env(name: str) -> str:
    value = _env(name)
    if not value:
        raise RuntimeError(f"Не задана переменная {name}")
    return value


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
    if not minimum <= value <= maximum:
        raise RuntimeError(
            f"{name} должен быть в диапазоне [{minimum}, {maximum}]"
        )
    return value


def _optional_nonnegative_float(name: str) -> Optional[float]:
    raw_value = _env(name)
    if not raw_value:
        return None
    try:
        value = float(raw_value)
    except ValueError as error:
        raise RuntimeError(f"{name} должен быть числом") from error
    if value < 0:
        raise RuntimeError(f"{name} не может быть отрицательным")
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


def _profile_from_env(key: str, tier: str, suffix: str) -> ModelProfile:
    return ModelProfile(
        key=key,
        tier=tier,
        model=_required_env(f"LLM_MODEL_{suffix}"),
        input_price_per_million=_optional_nonnegative_float(
            f"LLM_{suffix}_INPUT_PRICE_PER_MILLION"
        ),
        output_price_per_million=_optional_nonnegative_float(
            f"LLM_{suffix}_OUTPUT_PRICE_PER_MILLION"
        ),
    )


def normalize_console_input(value: str) -> str:
    characters = []
    for character in value:
        if character in {"\b", "\x7f"}:
            if characters:
                characters.pop()
        elif character.isprintable() or character == "\t":
            characters.append(character)
    return "".join(characters).strip()


def choose_menu_option(console: ConsoleInput, config: Config) -> str:
    print("\nВыберите модель:")
    for profile in config.profiles:
        print(f"  {profile.key}. {profile.tier}: {profile.model}")
    print(f"  {COMPARE_OPTION}. Сравнить все три модели")
    print(f"  {EXIT_OPTION}. Выход")

    valid_options = {profile.key for profile in config.profiles}
    valid_options.update({COMPARE_OPTION, EXIT_OPTION})
    while True:
        choice = console.read("Ваш выбор: ")
        if choice in valid_options:
            return choice
        print("Введите число от 0 до 4.")


def read_prompt(console: ConsoleInput, previous_prompt: Optional[str]) -> str:
    if previous_prompt is not None:
        prompt = console.read("Ваш запрос (Enter — повторить предыдущий): ")
        return prompt or previous_prompt

    prompt = console.read("Ваш запрос: ")
    if not prompt:
        raise RuntimeError("Запрос не должен быть пустым")
    return prompt


def profiles_for_choice(choice: str, config: Config) -> list[ModelProfile]:
    if choice == COMPARE_OPTION:
        return list(config.profiles)
    return [profile for profile in config.profiles if profile.key == choice]


def build_payload(
    profile: ModelProfile,
    prompt: str,
    config: Config,
    system_prompt: Optional[str] = None,
) -> Dict[str, Any]:
    return {
        "model": profile.model,
        "messages": [
            {
                "role": "system",
                "content": system_prompt or config.system_prompt,
            },
            {"role": "user", "content": prompt},
        ],
        "temperature": config.temperature,
    }


def _token_value(usage: Dict[str, Any], *names: str) -> Optional[int]:
    for name in names:
        value = usage.get(name)
        if isinstance(value, (int, float)) and value >= 0:
            return int(value)
    return None


def parse_usage(raw_usage: Any) -> TokenUsage:
    usage = raw_usage if isinstance(raw_usage, dict) else {}
    input_tokens = _token_value(usage, "prompt_tokens", "input_tokens")
    output_tokens = _token_value(usage, "completion_tokens", "output_tokens")
    total_tokens = _token_value(usage, "total_tokens")
    if (
        total_tokens is None
        and input_tokens is not None
        and output_tokens is not None
    ):
        total_tokens = input_tokens + output_tokens
    return TokenUsage(input_tokens, output_tokens, total_tokens)


def estimate_cost(
    profile: ModelProfile,
    usage: TokenUsage,
) -> Optional[float]:
    if (
        profile.input_price_per_million is None
        or profile.output_price_per_million is None
        or usage.input_tokens is None
        or usage.output_tokens is None
    ):
        return None

    input_cost = (
        usage.input_tokens * profile.input_price_per_million / 1_000_000
    )
    output_cost = (
        usage.output_tokens * profile.output_price_per_million / 1_000_000
    )
    return input_cost + output_cost


async def ask_model(
    profile: ModelProfile,
    prompt: str,
    config: Config,
    client: httpx.AsyncClient,
    system_prompt: Optional[str] = None,
) -> ModelResult:
    started_at = time.perf_counter()
    response = await client.post(
        config.api_url,
        headers={
            "Authorization": f"Bearer {config.api_key}",
            "Content-Type": "application/json",
        },
        json=build_payload(profile, prompt, config, system_prompt),
    )
    elapsed_seconds = time.perf_counter() - started_at

    if not response.is_success:
        raise RuntimeError(format_api_error(response, config.api_url))

    response_data = response.json()
    choice = response_data["choices"][0]
    content = choice["message"]["content"]
    if not isinstance(content, str):
        raise RuntimeError("API вернул ответ в неожиданном формате")

    usage = parse_usage(response_data.get("usage"))
    return ModelResult(
        profile=profile,
        content=content,
        elapsed_seconds=elapsed_seconds,
        usage=usage,
        estimated_cost=estimate_cost(profile, usage),
        finish_reason=choice.get("finish_reason", "unknown"),
    )


async def execute_requests_async(
    profiles: list[ModelProfile],
    prompt: str,
    config: Config,
    console: ConsoleInput,
    system_prompt: Optional[str] = None,
) -> list[Any]:
    timeout = httpx.Timeout(config.timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        request_tasks = [
            asyncio.create_task(
                ask_model(profile, prompt, config, client, system_prompt)
            )
            for profile in profiles
        ]

        try:
            while True:
                console_value = console.poll()
                if console_value == "0":
                    raise RequestCancelled("Запросы отменены пользователем")
                if console_value is not None:
                    print(
                        "Для отмены текущих запросов введите 0 и нажмите Enter.",
                        flush=True,
                    )
                if all(request_task.done() for request_task in request_tasks):
                    return list(
                        await asyncio.gather(
                            *request_tasks,
                            return_exceptions=True,
                        )
                    )
                await asyncio.sleep(0.1)
        finally:
            for request_task in request_tasks:
                if not request_task.done():
                    request_task.cancel()
            await asyncio.gather(*request_tasks, return_exceptions=True)


def execute_requests(
    profiles: list[ModelProfile],
    prompt: str,
    config: Config,
    console: ConsoleInput,
    status_message: str,
    system_prompt: Optional[str] = None,
) -> list[Any]:
    print(status_message, flush=True)
    print(
        "Во время ожидания введите 0 и нажмите Enter, чтобы отменить.",
        flush=True,
    )
    return asyncio.run(
        execute_requests_async(
            profiles,
            prompt,
            config,
            console,
            system_prompt,
        )
    )


def format_api_error(response: httpx.Response, fallback_url: str) -> str:
    reason = str(response.reason_phrase or "").strip()
    status = f"{response.status_code} {reason}".strip()
    response_url = response.url or fallback_url
    response_body = response.text.strip() or "<пустое тело ответа>"
    return (
        f"Ошибка API {status}\n"
        f"URL: {response_url}\n"
        f"Ответ сервера: {response_body}"
    )


def format_tokens(usage: TokenUsage) -> str:
    input_tokens = usage.input_tokens if usage.input_tokens is not None else "?"
    output_tokens = (
        usage.output_tokens if usage.output_tokens is not None else "?"
    )
    total_tokens = usage.total_tokens if usage.total_tokens is not None else "?"
    return (
        f"вход={input_tokens}, выход={output_tokens}, всего={total_tokens}"
    )


def format_cost(cost: Optional[float], currency: str) -> str:
    if cost is None:
        return "не рассчитана — не указаны цены или API не вернул токены"
    return f"{cost:.8f} {currency}"


def print_model_result(result: ModelResult, config: Config) -> None:
    print(f"\n=== {result.profile.tier}: {result.profile.model} ===")
    print(result.content)
    print(f"Время ответа: {result.elapsed_seconds:.3f} с")
    print(f"Токены: {format_tokens(result.usage)}")
    print(
        "Расчётная стоимость: "
        f"{format_cost(result.estimated_cost, config.currency)}"
    )
    print(f"Причина завершения: {result.finish_reason}")


def build_metrics_summary(
    results: list[ModelResult],
    config: Config,
) -> str:
    fastest = min(results, key=lambda result: result.elapsed_seconds)
    lines = [
        "Сводка измеряемых метрик:",
        (
            f"- Самая быстрая: {fastest.profile.tier} "
            f"({fastest.profile.model}) — {fastest.elapsed_seconds:.3f} с."
        ),
    ]

    results_with_tokens = [
        result for result in results if result.usage.total_tokens is not None
    ]
    if len(results_with_tokens) == len(results):
        lightest = min(
            results_with_tokens,
            key=lambda result: result.usage.total_tokens or 0,
        )
        lines.append(
            f"- Меньше всего токенов: {lightest.profile.tier} "
            f"({lightest.profile.model}) — {lightest.usage.total_tokens}."
        )
    else:
        lines.append("- Токены: API вернул неполные данные для сравнения.")

    results_with_cost = [
        result for result in results if result.estimated_cost is not None
    ]
    if len(results_with_cost) == len(results):
        cheapest = min(
            results_with_cost,
            key=lambda result: result.estimated_cost or 0.0,
        )
        lines.append(
            f"- Самая низкая расчётная стоимость: {cheapest.profile.tier} "
            f"({cheapest.profile.model}) — "
            f"{format_cost(cheapest.estimated_cost, config.currency)}."
        )
    else:
        lines.append("- Стоимость: указаны не все тарифы или данные о токенах.")

    return "\n".join(lines)


def build_quality_evaluation_prompt(
    original_prompt: str,
    results: list[ModelResult],
    config: Config,
) -> str:
    labels = "ABC"
    answer_sections = []
    metric_sections = []
    for label, result in zip(labels, results):
        answer_sections.append(f"--- Ответ {label} ---\n{result.content}")
        metric_sections.append(
            f"{label}: время={result.elapsed_seconds:.3f} с; "
            f"токены=({format_tokens(result.usage)}); "
            f"стоимость={format_cost(result.estimated_cost, config.currency)}"
        )

    answers_text = "\n\n".join(answer_sections)
    metrics_text = "\n".join(metric_sections)
    return (
        "Сравни три анонимных ответа на один запрос. Сначала оцени качество "
        "каждого ответа по корректности, полноте, ясности и выполнению условия. "
        "Потом сопоставь качество со скоростью, количеством токенов и "
        "стоимостью. Не пытайся угадать модели.\n\n"
        f"Исходный запрос:\n{original_prompt}\n\n"
        f"{answers_text}\n\n"
        "--- Метрики ---\n"
        f"{metrics_text}\n\n"
        "Формат ответа:\n"
        "A — оценка от 1 до 10 и краткое обоснование.\n"
        "B — оценка от 1 до 10 и краткое обоснование.\n"
        "C — оценка от 1 до 10 и краткое обоснование.\n"
        "Сравнение скорости и ресурсоёмкости — один короткий абзац.\n"
        "Краткий вывод — 2–4 предложения о различиях и лучшем компромиссе."
    )


def print_judge_result(result: ModelResult, config: Config) -> None:
    print("\nСлужебный вызов оценщика (не входит в сравнение):")
    print(f"Модель: {result.profile.model}")
    print(f"Время: {result.elapsed_seconds:.3f} с")
    print(f"Токены: {format_tokens(result.usage)}")
    print(
        "Расчётная стоимость: "
        f"{format_cost(result.estimated_cost, config.currency)}"
    )
    print("\n=== Оценка качества и краткий вывод ===")
    print(result.content)


def run_quality_evaluation(
    prompt: str,
    results: list[ModelResult],
    config: Config,
    console: ConsoleInput,
) -> None:
    metrics_summary = build_metrics_summary(results, config)
    print(f"\n{metrics_summary}")

    evaluation_prompt = build_quality_evaluation_prompt(
        prompt,
        results,
        config,
    )
    strong_profile = config.profiles[2]
    try:
        outcomes = execute_requests(
            [strong_profile],
            evaluation_prompt,
            config,
            console,
            "Оценка качества запущена на сильной модели.",
            system_prompt=QUALITY_JUDGE_SYSTEM,
        )
    except RequestCancelled:
        print("\nОценка качества отменена. Возврат в меню.", flush=True)
        return

    outcome = outcomes[0]
    if isinstance(outcome, BaseException):
        print(f"\nОшибка оценки качества: {outcome}", file=sys.stderr)
        return
    print_judge_result(outcome, config)


def main() -> None:
    config = Config.from_env()
    console = ConsoleInput()
    previous_prompt: Optional[str] = None
    command_line_prompt = normalize_console_input(" ".join(sys.argv[1:])) or None

    print(f"API: {config.api_url}")
    print(f"Общая temperature: {config.temperature}")
    print(f"Таймаут одного API-вызова: {config.timeout_seconds} с")

    while True:
        choice = choose_menu_option(console, config)
        if choice == EXIT_OPTION:
            return

        if command_line_prompt is not None:
            prompt = command_line_prompt
            command_line_prompt = None
            print(f"Ваш запрос: {prompt}")
        else:
            prompt = read_prompt(console, previous_prompt)
        previous_prompt = prompt

        profiles = profiles_for_choice(choice, config)
        status_message = (
            "Три модели запущены параллельно."
            if choice == COMPARE_OPTION
            else f"Запущена модель {profiles[0].model}."
        )

        try:
            outcomes = execute_requests(
                profiles,
                prompt,
                config,
                console,
                status_message,
            )
        except RequestCancelled:
            print("\nТекущие запросы отменены. Возврат в меню.", flush=True)
            continue

        successful_results = []
        for profile, outcome in zip(profiles, outcomes):
            if isinstance(outcome, BaseException):
                print(
                    f"\nОшибка для модели {profile.model}: {outcome}",
                    file=sys.stderr,
                )
                continue
            successful_results.append(outcome)
            print_model_result(outcome, config)

        if choice == COMPARE_OPTION:
            if len(successful_results) == len(config.profiles):
                run_quality_evaluation(
                    prompt,
                    successful_results,
                    config,
                    console,
                )
            else:
                print(
                    "\nПолное сравнение качества невозможно: "
                    "не все модели вернули ответ."
                )


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, KeyboardInterrupt, EOFError) as error:
        if isinstance(error, RuntimeError):
            print(f"Ошибка: {error}", file=sys.stderr)
            raise SystemExit(1) from error
        print("\nРабота завершена.")
