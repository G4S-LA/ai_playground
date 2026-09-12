import unittest

from model_output import visible_answer


class ModelOutputTest(unittest.TestCase):
    def test_removes_leading_reasoning_markers_and_blocks(self):
        for raw in (
            "\n </think>\n Ответ",
            "</think></think> Ответ",
            "<think>Служебные рассуждения</think>\nОтвет",
            "<think>Первый блок</think><think>Второй</think>Ответ",
        ):
            with self.subTest(raw=raw):
                self.assertEqual(visible_answer(raw), "Ответ")

    def test_does_not_show_unfinished_reasoning(self):
        self.assertEqual(visible_answer("<think>Незавершённые рассуждения"), "")

    def test_preserves_literal_tags_in_answers_and_code(self):
        for answer in (
            "Тег </think> завершает блок.",
            "`</think>` — пример тега.",
            "```xml\n<think>пример</think>\n```",
            "Обычный ответ",
        ):
            with self.subTest(answer=answer):
                self.assertEqual(visible_answer(answer), answer)
