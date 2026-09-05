#!/usr/bin/env python3

import os
import sys
from typing import Optional

import requests


API_KEY = os.getenv("LLM_API_KEY") or os.getenv("DASHSCOPE_API_KEY")
API_URL = os.getenv(
    "LLM_API_URL",
    "https://api.openai.com/v1/chat/completions",
)
MODEL = os.getenv("LLM_MODEL", "gpt-4o-mini")
SYSTEM_PROMPT = os.getenv(
    "LLM_SYSTEM_PROMPT",
    "Ты — полезный ассистент. Отвечай кратко и по делу.",
)

TEMPERATURES = {
    "1": 0.0,
    "2": 0.7,
    "3": 1.2,
}
EXIT_OPTION = "0"


def ask_llm(prompt: str, temperature: float) -> str:
    if not API_KEY:
        raise RuntimeError(
            "Не задана переменная окружения LLM_API_KEY "
            "или DASHSCOPE_API_KEY"
        )

    response = requests.post(
        API_URL,
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Content-Type": "application/json",
        },
        json={
            "model": MODEL,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": prompt},
            ],
            "temperature": temperature,
        },
        timeout=120,
    )

    if not response.ok:
        raise RuntimeError(f"Ошибка API {response.status_code}: {response.text}")

    result = response.json()
    content = result["choices"][0]["message"]["content"]
    if not isinstance(content, str):
        raise RuntimeError("API вернул ответ в неожиданном формате")
    return content


def choose_temperature() -> Optional[float]:
    print("\nВыберите температуру:")
    print("  1. 0 — наиболее сфокусированный ответ")
    print("  2. 0.7 — умеренная вариативность")
    print("  3. 1.2 — повышенная вариативность")
    print("  0. Выход")

    while True:
        choice = input("Ваш выбор: ").strip()
        if choice == EXIT_OPTION:
            return None
        if choice in TEMPERATURES:
            return TEMPERATURES[choice]
        print("Введите число от 0 до 3.")


def read_prompt(previous_prompt: Optional[str]) -> str:
    if previous_prompt is not None:
        prompt = input("Ваш запрос (Enter — повторить предыдущий): ").strip()
        return prompt or previous_prompt

    prompt = input("Ваш запрос: ").strip()
    if not prompt:
        raise RuntimeError("Запрос не должен быть пустым")
    return prompt


def main() -> None:
    command_line_prompt = " ".join(sys.argv[1:]).strip() or None
    previous_prompt: Optional[str] = None

    print(f"Модель: {MODEL}")
    print(f"API: {API_URL}")

    while True:
        temperature = choose_temperature()
        if temperature is None:
            return

        if command_line_prompt is not None:
            prompt = command_line_prompt
            command_line_prompt = None
            print(f"Ваш запрос: {prompt}")
        else:
            prompt = read_prompt(previous_prompt)
        previous_prompt = prompt

        print(f"\n=== Ответ при temperature={temperature:g} ===")
        print(ask_llm(prompt, temperature))


if __name__ == "__main__":
    try:
        main()
    except (
        RuntimeError,
        requests.RequestException,
        ValueError,
        KeyError,
        IndexError,
    ) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    except (KeyboardInterrupt, EOFError):
        print("\nРабота завершена.")
