"""Очистка служебного префикса reasoning в текстовом ответе провайдера."""


def visible_answer(content: str) -> str:
    text = content.strip()
    while text:
        if text.startswith("</think>"):
            text = text[len("</think>"):].lstrip()
        elif text.startswith("<think>"):
            end = text.find("</think>", len("<think>"))
            if end == -1:
                # Незавершённый блок reasoning не является ответом пользователю.
                return ""
            text = text[end + len("</think>"):].lstrip()
        else:
            break
    return text
