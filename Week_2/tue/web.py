#!/usr/bin/env python3

from __future__ import annotations

import os
import sys
from typing import Callable, Protocol

from flask import Flask, jsonify, render_template, request

from agent import AgentConfig, AgentError, SimpleAgent
from chat_repository import (
    DEFAULT_CHAT_TITLE,
    MAX_TITLE_LENGTH,
    Chat,
    ChatRepository,
    ChatRepositoryError,
)


MAX_MESSAGE_LENGTH = 4_000


class WebAgent(Protocol):
    def reply(self, user_request: str) -> str:
        ...

    def delete(self) -> None:
        ...


AgentFactory = Callable[[AgentConfig, str, ChatRepository], WebAgent]


def create_app(
    config: AgentConfig | None = None,
    repository: ChatRepository | None = None,
    agent_factory: AgentFactory = SimpleAgent,
    cleanup_empty_chats_on_start: bool = False,
) -> Flask:
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = 16 * 1024

    agent_config = config or AgentConfig.from_env()
    chat_repository = repository or ChatRepository(agent_config.database_path)
    if cleanup_empty_chats_on_start:
        chat_repository.delete_empty_chats()
    agents: dict[str, WebAgent] = {}
    app.extensions["chat_repository"] = chat_repository
    app.extensions["llm_agents"] = agents

    def current_agent(chat_id: str) -> WebAgent:
        agent = agents.get(chat_id)
        if agent is None:
            agent = agent_factory(agent_config, chat_id, chat_repository)
            agents[chat_id] = agent
        return agent

    def existing_chat(chat_id: str) -> Chat | None:
        return chat_repository.get_chat(chat_id)

    @app.get("/")
    def index() -> str:
        return render_template("index.html", model=agent_config.model)

    @app.get("/api/chats")
    def list_chats():
        return jsonify(
            chats=[_chat_to_dict(chat) for chat in chat_repository.list_chats()]
        )

    @app.post("/api/chats")
    def create_chat():
        payload = request.get_json(silent=True)
        title = payload.get("title") if isinstance(payload, dict) else None
        discarded_chat_id = (
            payload.get("discard_empty_chat_id")
            if isinstance(payload, dict)
            else None
        )
        if title is not None and not isinstance(title, str):
            return jsonify(error="Название чата должно быть строкой"), 400
        if discarded_chat_id is not None and not isinstance(discarded_chat_id, str):
            return jsonify(error="ID удаляемого черновика должен быть строкой"), 400
        if isinstance(title, str) and len(title) > MAX_TITLE_LENGTH:
            return jsonify(
                error=f"Название не должно быть длиннее {MAX_TITLE_LENGTH} символов"
            ), 400

        if isinstance(discarded_chat_id, str):
            chat_repository.delete_chat_if_empty(discarded_chat_id)
            agents.pop(discarded_chat_id, None)

        chat = chat_repository.create_chat(title or DEFAULT_CHAT_TITLE)
        return jsonify(chat=_chat_to_dict(chat)), 201

    @app.get("/api/chats/<chat_id>/messages")
    def get_messages(chat_id: str):
        chat = existing_chat(chat_id)
        if chat is None:
            return jsonify(error="Чат не найден"), 404
        return jsonify(
            chat=_chat_to_dict(chat),
            messages=chat_repository.load_messages(chat_id),
        )

    @app.post("/api/chats/<chat_id>/messages")
    def send_message(chat_id: str):
        if existing_chat(chat_id) is None:
            return jsonify(error="Чат не найден"), 404

        payload = request.get_json(silent=True)
        message = payload.get("message") if isinstance(payload, dict) else None
        if not isinstance(message, str) or not message.strip():
            return jsonify(error="Сообщение не должно быть пустым"), 400
        if len(message) > MAX_MESSAGE_LENGTH:
            return jsonify(
                error=f"Сообщение не должно быть длиннее {MAX_MESSAGE_LENGTH} символов"
            ), 400

        try:
            answer = current_agent(chat_id).reply(message)
        except AgentError as error:
            return jsonify(error=str(error)), 502

        updated_chat = existing_chat(chat_id)
        if updated_chat is None:
            return jsonify(error="Чат не найден после сохранения ответа"), 500
        return jsonify(answer=answer, chat=_chat_to_dict(updated_chat))

    @app.delete("/api/chats/<chat_id>")
    def delete_chat(chat_id: str):
        if existing_chat(chat_id) is None:
            return jsonify(error="Чат не найден"), 404
        try:
            current_agent(chat_id).delete()
        except AgentError as error:
            return jsonify(error=str(error)), 500
        agents.pop(chat_id, None)
        return jsonify(ok=True)

    @app.errorhandler(ChatRepositoryError)
    def database_error(error: ChatRepositoryError):
        return jsonify(error=str(error)), 500

    @app.errorhandler(413)
    def request_too_large(_error):
        return jsonify(error="Запрос слишком большой"), 413

    return app


def _chat_to_dict(chat: Chat) -> dict[str, object]:
    return {
        "id": chat.id,
        "title": chat.title,
        "created_at": chat.created_at,
        "updated_at": chat.updated_at,
        "message_count": chat.message_count,
    }


def _port_from_env() -> int:
    raw_port = os.getenv("WEB_PORT", "5000").strip()
    try:
        port = int(raw_port)
    except ValueError as error:
        raise AgentError("WEB_PORT должен быть целым числом") from error
    if not 1 <= port <= 65_535:
        raise AgentError("WEB_PORT должен быть в диапазоне от 1 до 65535")
    return port


def main() -> None:
    try:
        app = create_app(cleanup_empty_chats_on_start=True)
        port = _port_from_env()
    except (AgentError, ChatRepositoryError) as error:
        print(f"Ошибка конфигурации: {error}", file=sys.stderr)
        raise SystemExit(1) from error

    print(f"Откройте в браузере: http://127.0.0.1:{port}")
    app.run(host="127.0.0.1", port=port, debug=False)


if __name__ == "__main__":
    main()
