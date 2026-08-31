#!/usr/bin/env python3

import os
import sys

import requests


API_KEY = os.getenv("LLM_API_KEY")
API_URL = os.getenv(
    "LLM_API_URL",
    "https://api.openai.com/v1/chat/completions",
)
MODEL = os.getenv("LLM_MODEL", "gpt-4o-mini")
SYSTEM_PROMPT = os.getenv(
    "LLM_SYSTEM_PROMPT",
    "Ты — полезный ассистент. Отвечай кратко и по делу.",
)


def ask_llm(prompt: str) -> str:
    if not API_KEY:
        raise RuntimeError("Не задана переменная окружения LLM_API_KEY")

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
            "temperature": 0.7,
        },
        timeout=120,
    )

    if not response.ok:
        raise RuntimeError(f"Ошибка API {response.status_code}: {response.text}")

    result = response.json()
    return result["choices"][0]["message"]["content"]


def main() -> None:
    prompt = " ".join(sys.argv[1:]).strip()
    if not prompt:
        prompt = input("Ваш запрос: ").strip()

    if not prompt:
        raise RuntimeError("Запрос не должен быть пустым")

    print(ask_llm(prompt))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, requests.RequestException, ValueError, KeyError) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        raise SystemExit(1) from error
