#!/usr/bin/env python3
"""Check network access and the persistent chat API against a real model."""

from __future__ import annotations

import argparse
import json
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from server import Config


def request_json(
    base_url: str,
    path: str,
    api_key: str | None,
    *,
    method: str = "GET",
    payload: dict[str, Any] | None = None,
    timeout: int = 360,
) -> tuple[int, dict[str, Any] | None]:
    headers = {"Accept": "application/json"}
    data = None
    if api_key is not None:
        headers["Authorization"] = f"Bearer {api_key}"
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(f"{base_url}{path}", data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except HTTPError as error:
        with error:
            body = error.read()
            return error.code, json.loads(body) if body else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--api-key")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    try:
        config = Config.load()
    except RuntimeError as error:
        if args.api_key is None:
            parser.error(f"передайте --api-key: {error}")
        config = None
    api_key = args.api_key or config.api_key

    chat_id = None
    try:
        status, health = request_json(base_url, "/health", None, timeout=5)
        print(f"OK   · HTTP-доступ: {base_url}/health → {status}")
        print(f"INFO · модель: {health.get('model')} · ready={health.get('ready')}")
        if status != 200 or not health.get("ready"):
            print(f"FAIL · Ollama не готова: {health.get('ollama')}")
            return 1

        unauthorized, _ = request_json(base_url, "/api/chats", "wrong-key")
        print(f"{'OK  ' if unauthorized == 401 else 'FAIL'} · неверный ключ → {unauthorized}")

        created_status, created = request_json(
            base_url,
            "/api/chats",
            api_key,
            method="POST",
            payload={},
        )
        if created_status != 201:
            print(f"FAIL · диалог не создан: HTTP {created_status} · {created}")
            return 1
        chat_id = created["chat"]["id"]
        print(f"OK   · создан диалог {chat_id}")

        started = time.perf_counter()
        reply_status, reply = request_json(
            base_url,
            f"/api/chats/{chat_id}/messages",
            api_key,
            method="POST",
            payload={"message": "Ответь одним словом: работает"},
        )
        elapsed = time.perf_counter() - started
        if reply_status != 200:
            print(f"FAIL · модель не ответила: HTTP {reply_status} · {reply}")
            return 1
        messages = reply["chat"]["messages"]
        print(f"OK   · модель ответила за {elapsed:.2f}s: {messages[-1]['content'][:100]}")

        restored_status, restored = request_json(base_url, f"/api/chats/{chat_id}", api_key)
        stored = restored_status == 200 and len(restored["chat"]["messages"]) == 2
        print(f"{'OK  ' if stored else 'FAIL'} · пара сообщений сохранена в диалоге")

        deleted_status, _ = request_json(
            base_url,
            f"/api/chats/{chat_id}",
            api_key,
            method="DELETE",
        )
        chat_id = None
        deleted = deleted_status == 204
        print(f"{'OK  ' if deleted else 'FAIL'} · тестовый диалог удалён")
        success = unauthorized == 401 and stored and deleted
        print("PASS · все проверки пройдены" if success else "FAIL · есть непройденные проверки")
        return 0 if success else 1
    except (OSError, ValueError, KeyError, URLError) as error:
        print(f"FAIL · {error}")
        return 1
    finally:
        if chat_id is not None:
            try:
                request_json(base_url, f"/api/chats/{chat_id}", api_key, method="DELETE")
            except OSError:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
