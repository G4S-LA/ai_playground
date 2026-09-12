"""Локальные оценки контекста и учёт фактического usage Chat Completions."""

from __future__ import annotations

from decimal import Decimal
from functools import lru_cache
from typing import Any, Iterable

import tiktoken


class TokenCounter:
    # Чатовая обвязка зависит от провайдера; это оценка даже для OpenAI.
    def __init__(self, encoding: str = "cl100k_base") -> None:
        self.encoding = encoding
        self._tokenizer = tiktoken.get_encoding(encoding)

    @lru_cache(maxsize=512)
    def text(self, value: str) -> int:
        return len(self._tokenizer.encode_ordinary(value))

    def messages(self, messages: Iterable[dict[str, str]]) -> int:
        return 3 + sum(
            3 + self.text(message["role"]) + self.text(message["content"])
            for message in messages
        )


def _count(value: Any) -> int | None:
    return value if type(value) is int and value >= 0 else None


def completion_usage(
    data: dict[str, Any], estimated_input: int, estimated_output: int,
) -> dict[str, Any]:
    usage = data.get("usage")
    usage = usage if isinstance(usage, dict) else {}
    prompt = _count(usage.get("prompt_tokens"))
    output = _count(usage.get("completion_tokens"))
    details = usage.get("prompt_tokens_details") or {}
    details = details if isinstance(details, dict) else {}
    cached = _count(usage.get("prompt_cache_hit_tokens"))
    if cached is None:
        cached = _count(details.get("cached_tokens"))
    if prompt is None or cached is not None and cached > prompt:
        cached = None
    return {
        "input_tokens": prompt if prompt is not None else estimated_input,
        "output_tokens": output if output is not None else estimated_output,
        "input_source": "api" if prompt is not None else "estimate",
        "output_source": "api" if output is not None else "estimate",
        "cached_input_tokens": cached,
    }


def cost_usd(
    usage: dict[str, Any], input_rate: float | None,
    output_rate: float | None, cached_rate: float | None,
) -> float | None:
    if input_rate is None or output_rate is None:
        return None
    cached = usage["cached_input_tokens"] or 0
    if cached and cached_rate is None:
        return None
    # Тарифы в USD за миллион токенов; снимок стоимости сохраняется с ходом.
    amount = (
        Decimal(usage["input_tokens"] - cached) * Decimal(str(input_rate))
        + Decimal(cached) * Decimal(str(cached_rate or 0))
        + Decimal(usage["output_tokens"]) * Decimal(str(output_rate))
    ) / Decimal(1_000_000)
    return float(amount)


def totals(turns: list[dict[str, Any]]) -> dict[str, Any]:
    input_tokens = sum(turn["input_tokens"] for turn in turns)
    output_tokens = sum(turn["output_tokens"] for turn in turns)
    priced = [turn for turn in turns if turn["cost_usd"] is not None]
    return {
        "turn_count": len(turns),
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": input_tokens + output_tokens,
        "has_estimates": any(
            turn["input_source"] != "api" or turn["output_source"] != "api"
            for turn in turns
        ),
        "known_cost_usd": float(sum(
            (Decimal(str(turn["cost_usd"])) for turn in priced), Decimal(0)
        )),
        "unpriced_turns": len(turns) - len(priced),
    }
