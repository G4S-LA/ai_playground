import unittest

from token_usage import TokenCounter, completion_usage, cost_usd, totals


class TokenUsageTest(unittest.TestCase):
    def test_tokenizer_handles_unicode_and_literal_special_tokens(self):
        counter = TokenCounter()
        self.assertEqual(counter.text(""), 0)
        self.assertEqual(counter.text("hello world"), 2)
        self.assertGreater(counter.text("Привет 👋 <|endoftext|>"), 0)
        self.assertGreater(counter.messages([{"role": "user", "content": "hello"}]), 1)

    def test_api_usage_is_authoritative_including_reasoning(self):
        usage = completion_usage({"usage": {
            "prompt_tokens": 100, "completion_tokens": 40,
            "completion_tokens_details": {"reasoning_tokens": 30},
            "prompt_cache_hit_tokens": 80,
        }}, 999, 2)
        self.assertEqual(usage["input_tokens"], 100)
        self.assertEqual(usage["output_tokens"], 40)  # Не добавляем reasoning дважды.
        self.assertEqual(usage["cached_input_tokens"], 80)
        self.assertEqual(usage["input_source"], "api")

    def test_partial_or_invalid_usage_falls_back_per_field(self):
        for raw in (None, [], {"prompt_tokens": -1, "completion_tokens": True}):
            with self.subTest(raw=raw):
                result = completion_usage({"usage": raw}, 20, 10)
                self.assertEqual(result["input_tokens"], 20)
                self.assertEqual(result["output_source"], "estimate")
        result = completion_usage({"usage": {"prompt_tokens": 0}}, 20, 10)
        self.assertEqual(result["input_tokens"], 0)
        self.assertEqual(result["input_source"], "api")
        self.assertEqual(result["output_source"], "estimate")

    def test_cached_pricing_and_missing_rates(self):
        usage = completion_usage({"usage": {
            "prompt_tokens": 1000, "completion_tokens": 100,
            "prompt_tokens_details": {"cached_tokens": 800},
        }}, 0, 0)
        self.assertAlmostEqual(cost_usd(usage, 2, 10, 0.5), 0.0018)
        self.assertIsNone(cost_usd(usage, 2, 10, None))
        self.assertIsNone(cost_usd(usage, None, 10, 0.5))
        self.assertEqual(cost_usd(usage, 0, 0, 0), 0)

    def test_totals_do_not_turn_unknown_price_into_free_usage(self):
        first = {**completion_usage({}, 20, 10), "cost_usd": None}
        second = {**completion_usage({}, 50, 10), "cost_usd": 0.01}
        result = totals([first, second])
        self.assertEqual(result["input_tokens"], 70)
        self.assertEqual(result["total_tokens"], 90)
        self.assertEqual(result["unpriced_turns"], 1)
        self.assertEqual(result["known_cost_usd"], 0.01)
        self.assertTrue(result["has_estimates"])
