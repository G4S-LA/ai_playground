#!/usr/bin/env python3

import json
import os
import sys
from dataclasses import dataclass
from typing import Any, Dict, Optional

import requests


@dataclass(frozen=True)
class Config:
    api_key: str
    api_url: str
    model: str
    provider: str
    system_prompt: str
    response_format_mode: str
    token_limit_parameter: str
    max_completion_tokens: int
    max_words: int
    max_sentences: int
    temperature: float
    stop_sequence: str
    qwen_disable_thinking_for_structured: bool

    @classmethod
    def from_env(cls) -> "Config":
        api_key = os.getenv("LLM_API_KEY") or os.getenv("DASHSCOPE_API_KEY")
        if not api_key:
            raise RuntimeError(
                "Не задана переменная LLM_API_KEY или DASHSCOPE_API_KEY"
            )

        response_format_mode = os.getenv(
            "LLM_RESPONSE_FORMAT", "json_schema"
        ).strip()
        if response_format_mode not in {"json_schema", "json_object"}:
            raise RuntimeError(
                "LLM_RESPONSE_FORMAT должен быть json_schema или json_object"
            )

        token_limit_parameter = os.getenv(
            "LLM_TOKEN_LIMIT_PARAMETER", "max_completion_tokens"
        ).strip()
        if token_limit_parameter not in {
            "max_completion_tokens",
            "max_tokens",
        }:
            raise RuntimeError(
                "LLM_TOKEN_LIMIT_PARAMETER должен быть "
                "max_completion_tokens или max_tokens"
            )

        return cls(
            api_key=api_key,
            api_url=os.getenv(
                "LLM_API_URL",
                "https://api.openai.com/v1/chat/completions",
            ),
            model=os.getenv("LLM_MODEL", "gpt-4o-mini"),
            provider=os.getenv("LLM_PROVIDER", "auto").strip().lower(),
            system_prompt=os.getenv(
                "LLM_SYSTEM_PROMPT",
                "Ты — полезный ассистент.",
            ),
            response_format_mode=response_format_mode,
            token_limit_parameter=token_limit_parameter,
            max_completion_tokens=_positive_int(
                "LLM_MAX_COMPLETION_TOKENS", 180
            ),
            max_words=_positive_int("LLM_MAX_WORDS", 80),
            max_sentences=_positive_int("LLM_MAX_SENTENCES", 3),
            temperature=_float_in_range("LLM_TEMPERATURE", 0.2, 0.0, 2.0),
            stop_sequence=os.getenv("LLM_STOP_SEQUENCE", "."),
            qwen_disable_thinking_for_structured=_boolean(
                "QWEN_DISABLE_THINKING_FOR_STRUCTURED", True
            ),
        )

    @property
    def is_qwen(self) -> bool:
        if self.provider == "qwen":
            return True
        if self.provider != "auto":
            return False
        url = self.api_url.lower()
        return "aliyuncs.com" in url or "dashscope" in url


@dataclass(frozen=True)
class ControlProfile:
    key: str
    title: str
    structured: bool = False
    length_limited: bool = False
    stop_sequence: bool = False
    completion_flag: bool = False


@dataclass(frozen=True)
class LlmResult:
    content: str
    finish_reason: str
    usage: Dict[str, Any]


PROFILES = {
    "1": ControlProfile("1", "Без ограничений"),
    "2": ControlProfile(
        "2",
        "Только строгий формат (предложения по строкам)",
        structured=True,
    ),
    "3": ControlProfile("3", "Только ограничение длины", length_limited=True),
    "4": ControlProfile("4", "Только stop sequence", stop_sequence=True),
    "5": ControlProfile(
        "5",
        "Все ограничения",
        structured=True,
        length_limited=True,
        completion_flag=True,
    ),
}

COMPARE_OPTION = "6"
EXIT_OPTION = "0"


def _positive_int(name: str, default: int) -> int:
    raw_value = os.getenv(name, str(default))
    try:
        value = int(raw_value)
    except ValueError as error:
        raise RuntimeError(f"{name} должен быть целым числом") from error
    if value <= 0:
        raise RuntimeError(f"{name} должен быть больше нуля")
    return value


def _float_in_range(
    name: str,
    default: float,
    minimum: float,
    maximum: float,
) -> float:
    raw_value = os.getenv(name, str(default))
    try:
        value = float(raw_value)
    except ValueError as error:
        raise RuntimeError(f"{name} должен быть числом") from error
    if not minimum <= value < maximum:
        raise RuntimeError(
            f"{name} должен быть в диапазоне [{minimum}, {maximum})"
        )
    return value


def _boolean(name: str, default: bool) -> bool:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default
    normalized = raw_value.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise RuntimeError(f"{name} должен содержать true или false")


def build_system_prompt(profile: ControlProfile, config: Config) -> str:
    instructions = [config.system_prompt]

    if profile.structured:
        fields = "`sentences`"
        if profile.completion_flag:
            fields += " и `completion_status`"
        instructions.append(
            "Верни только JSON-объект без Markdown и пояснений вокруг него. "
            f"Объект должен содержать поля {fields}. Поле `sentences` — массив "
            "строк: каждый его элемент содержит ровно одно законченное "
            "предложение без переноса строки и без нумерации."
        )

    if profile.length_limited:
        target = (
            "Суммарный текст всех элементов массива `sentences`"
            if profile.structured
            else "Ответ"
        )
        instructions.append(
            f"{target} должно содержать не более {config.max_words} слов "
            f"и не более {config.max_sentences} коротких предложений. "
            "Сформулируй законченную мысль в пределах этого ограничения."
        )

    if profile.stop_sequence:
        instructions.append(
            "Начни ответ с одного законченного предложения и обязательно "
            f"заверши его точной последовательностью {config.stop_sequence!r}. "
            "Не используй эту последовательность раньше конца первого "
            "предложения. После неё планируй продолжить ответ вторым "
            "предложением."
        )

    if profile.completion_flag:
        instructions.append(
            "Условие завершения: сначала полностью сформируй массив `sentences`, "
            "затем установи `completion_status` в строку `completed`. После "
            "закрывающей скобки JSON ничего не добавляй."
        )

    return " ".join(instructions)


def build_response_format(
    profile: ControlProfile,
    config: Config,
) -> Dict[str, Any]:
    if config.response_format_mode == "json_object":
        return {"type": "json_object"}

    properties: Dict[str, Any] = {
        "sentences": {
            "type": "array",
            "description": (
                "Ответ, разделённый на отдельные законченные предложения."
            ),
            "items": {
                "type": "string",
                "description": (
                    "Одно законченное предложение без нумерации и переноса строки."
                ),
            },
        }
    }
    required = ["sentences"]

    if profile.completion_flag:
        properties["completion_status"] = {
            "type": "string",
            "enum": ["completed"],
            "description": "Признак того, что ответ полностью сформирован.",
        }
        required.append("completion_status")

    return {
        "type": "json_schema",
        "json_schema": {
            "name": "controlled_sentences",
            "strict": True,
            "schema": {
                "type": "object",
                "properties": properties,
                "required": required,
                "additionalProperties": False,
            },
        },
    }


def build_payload(
    prompt: str,
    profile: ControlProfile,
    config: Config,
) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "model": config.model,
        "messages": [
            {
                "role": "system",
                "content": build_system_prompt(profile, config),
            },
            {"role": "user", "content": prompt},
        ],
        "temperature": config.temperature,
    }

    if profile.structured:
        payload["response_format"] = build_response_format(profile, config)
        if config.is_qwen and config.qwen_disable_thinking_for_structured:
            payload["enable_thinking"] = False

    if profile.length_limited:
        payload[config.token_limit_parameter] = config.max_completion_tokens

    if profile.stop_sequence:
        payload["stop"] = [config.stop_sequence]

    return payload


def ask_llm(
    prompt: str,
    profile: ControlProfile,
    config: Config,
) -> LlmResult:
    response = requests.post(
        config.api_url,
        headers={
            "Authorization": f"Bearer {config.api_key}",
            "Content-Type": "application/json",
        },
        json=build_payload(prompt, profile, config),
        timeout=120,
    )

    if not response.ok:
        raise RuntimeError(f"Ошибка API {response.status_code}: {response.text}")

    result = response.json()
    choice = result["choices"][0]
    content = choice["message"]["content"]
    if not isinstance(content, str):
        raise RuntimeError("API вернул ответ в неожиданном формате")

    usage = result.get("usage") or {}
    return LlmResult(
        content=content,
        finish_reason=choice.get("finish_reason", "unknown"),
        usage=usage,
    )


def describe_controls(profile: ControlProfile, config: Config) -> str:
    controls = []
    if profile.structured:
        controls.append(f"response_format={config.response_format_mode}")
    if profile.length_limited:
        controls.append(
            f"{config.token_limit_parameter}={config.max_completion_tokens}"
        )
        controls.append(
            f"не более {config.max_words} слов/{config.max_sentences} предложений"
        )
    if profile.stop_sequence:
        controls.append(f"stop={config.stop_sequence!r}")
    if profile.completion_flag:
        controls.append("completion_status='completed'")
    return ", ".join(controls) if controls else "нет"


def validate_structured_result(
    parsed: Any,
    profile: ControlProfile,
) -> list[str]:
    if not isinstance(parsed, dict):
        return ["верхний уровень ответа должен быть JSON-объектом"]

    warnings = []
    expected_fields = {"sentences"}

    sentences = parsed.get("sentences")
    if not isinstance(sentences, list):
        warnings.append("поле `sentences` отсутствует или не является массивом")
    elif not sentences:
        warnings.append("массив `sentences` не должен быть пустым")
    elif any(
        not isinstance(sentence, str) or not sentence.strip()
        for sentence in sentences
    ):
        warnings.append("каждый элемент `sentences` должен быть непустой строкой")

    if profile.completion_flag:
        expected_fields.add("completion_status")
        if parsed.get("completion_status") != "completed":
            warnings.append(
                "условие завершения не выполнено: "
                "`completion_status` должен быть равен `completed`"
            )

    unexpected_fields = set(parsed) - expected_fields
    if unexpected_fields:
        fields = ", ".join(sorted(unexpected_fields))
        warnings.append(f"найдены лишние поля: {fields}")

    return warnings


def print_result(
    profile: ControlProfile,
    result: LlmResult,
    config: Config,
) -> None:
    print(f"\n=== {profile.title} ===")
    print(f"Ограничения: {describe_controls(profile, config)}")
    print("Ответ:")

    if profile.structured:
        try:
            parsed = json.loads(result.content)
        except json.JSONDecodeError:
            print(result.content)
            print("Предупреждение: API не вернул валидный JSON.")
        else:
            warnings = validate_structured_result(parsed, profile)
            sentences = (
                parsed.get("sentences") if isinstance(parsed, dict) else None
            )
            if (
                isinstance(sentences, list)
                and sentences
                and all(
                    isinstance(sentence, str) and sentence.strip()
                    for sentence in sentences
                )
            ):
                for number, sentence in enumerate(sentences, start=1):
                    print(f"{number}. {sentence.strip()}")
                if profile.completion_flag:
                    print(
                        "Статус завершения: "
                        f"{parsed.get('completion_status', 'не указан')}"
                    )
            else:
                print(json.dumps(parsed, ensure_ascii=False, indent=2))
            for warning in warnings:
                print(f"Предупреждение о формате: {warning}.")
    else:
        print(result.content)

    print(f"Причина завершения: {result.finish_reason}")
    if result.finish_reason == "length":
        print("Предупреждение: ответ мог быть обрезан по лимиту токенов.")

    if result.usage:
        prompt_tokens = result.usage.get("prompt_tokens", "?")
        completion_tokens = result.usage.get("completion_tokens", "?")
        total_tokens = result.usage.get("total_tokens", "?")
        print(
            "Токены: "
            f"вход={prompt_tokens}, выход={completion_tokens}, всего={total_tokens}"
        )


def choose_menu_option() -> str:
    print("\nВыберите уровень контроля ответа:")
    for key, profile in PROFILES.items():
        print(f"  {key}. {profile.title}")
    print(f"  {COMPARE_OPTION}. Сравнить без ограничений и со всеми ограничениями")
    print(f"  {EXIT_OPTION}. Выход")

    while True:
        choice = input("Ваш выбор: ").strip()
        if choice in {*PROFILES, COMPARE_OPTION, EXIT_OPTION}:
            return choice
        print("Введите число от 0 до 6.")


def read_prompt(previous_prompt: Optional[str]) -> str:
    if previous_prompt:
        prompt = input("Запрос (Enter — повторить предыдущий): ").strip()
        return prompt or previous_prompt

    prompt = input("Ваш запрос: ").strip()
    if not prompt:
        raise RuntimeError("Запрос не должен быть пустым")
    return prompt


def profiles_for_choice(choice: str) -> list[ControlProfile]:
    if choice == COMPARE_OPTION:
        return [PROFILES["1"], PROFILES["5"]]
    return [PROFILES[choice]]


def main() -> None:
    config = Config.from_env()
    previous_prompt: Optional[str] = None
    command_line_prompt = " ".join(sys.argv[1:]).strip() or None

    print(f"Модель: {config.model}")
    print(f"API: {config.api_url}")

    while True:
        choice = choose_menu_option()
        if choice == EXIT_OPTION:
            return

        if command_line_prompt is not None:
            prompt = command_line_prompt
            command_line_prompt = None
            print(f"Запрос: {prompt}")
        else:
            prompt = read_prompt(previous_prompt)
        previous_prompt = prompt

        for profile in profiles_for_choice(choice):
            try:
                result = ask_llm(prompt, profile, config)
            except (
                RuntimeError,
                requests.RequestException,
                ValueError,
                KeyError,
                IndexError,
            ) as error:
                print(
                    f"\nОшибка для профиля «{profile.title}»: {error}",
                    file=sys.stderr,
                )
                continue
            print_result(profile, result, config)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, KeyboardInterrupt, EOFError) as error:
        if isinstance(error, RuntimeError):
            print(f"Ошибка: {error}", file=sys.stderr)
            raise SystemExit(1) from error
        print("\nРабота завершена.")
