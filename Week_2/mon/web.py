#!/usr/bin/env python3

from __future__ import annotations

import os
import secrets
import sys
from typing import Callable, Protocol

from flask import Flask, jsonify, render_template, request, session

from agent import AgentConfig, AgentError, SimpleAgent


MAX_MESSAGE_LENGTH = 4_000


class ChatAgent(Protocol):
    def reply(self, user_request: str) -> str:
        ...

    def reset(self) -> None:
        ...


AgentFactory = Callable[[AgentConfig], ChatAgent]


def create_app(
    config: AgentConfig | None = None,
    agent_factory: AgentFactory = SimpleAgent,
) -> Flask:
    app = Flask(__name__)
    app.secret_key = os.getenv("WEB_SECRET_KEY") or secrets.token_hex(32)
    app.config["MAX_CONTENT_LENGTH"] = 16 * 1024

    agent_config = config or AgentConfig.from_env()
    agents: dict[str, ChatAgent] = {}
    app.extensions["llm_agents"] = agents

    def current_agent() -> ChatAgent:
        chat_id = session.get("chat_id")
        if not isinstance(chat_id, str):
            chat_id = secrets.token_urlsafe(24)
            session["chat_id"] = chat_id

        agent = agents.get(chat_id)
        if agent is None:
            agent = agent_factory(agent_config)
            agents[chat_id] = agent
        return agent

    @app.get("/")
    def index() -> str:
        return render_template("index.html", model=agent_config.model)

    @app.post("/api/chat")
    def chat():
        payload = request.get_json(silent=True)
        message = payload.get("message") if isinstance(payload, dict) else None

        if not isinstance(message, str) or not message.strip():
            return jsonify(error="Сообщение не должно быть пустым"), 400
        if len(message) > MAX_MESSAGE_LENGTH:
            return jsonify(
                error=f"Сообщение не должно быть длиннее {MAX_MESSAGE_LENGTH} символов"
            ), 400

        try:
            answer = current_agent().reply(message)
        except AgentError as error:
            return jsonify(error=str(error)), 502

        return jsonify(answer=answer)

    @app.post("/api/reset")
    def reset_chat():
        current_agent().reset()
        return jsonify(ok=True)

    @app.errorhandler(413)
    def request_too_large(_error):
        return jsonify(error="Запрос слишком большой"), 413

    return app


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
        app = create_app()
        port = _port_from_env()
    except AgentError as error:
        print(f"Ошибка конфигурации: {error}", file=sys.stderr)
        raise SystemExit(1) from error

    print(f"Откройте в браузере: http://127.0.0.1:{port}")
    app.run(host="127.0.0.1", port=port, debug=False)


if __name__ == "__main__":
    main()
