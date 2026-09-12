#!/usr/bin/env python3
"""Локальный веб-чат. Контекст и блокировка настроек общие с CLI."""

import os
import sys
from dataclasses import asdict
from threading import RLock

from flask import Flask, jsonify, render_template, request

from agent import AgentConfig, AgentError, ContextOverflowError, SimpleAgent
from chat_repository import (ChatRepository, ChatRepositoryError, ConversationChangedError,
                             SettingsLockedError, MAX_TITLE_LENGTH, DEFAULT_CHAT_TITLE)

MAX_MESSAGE_LENGTH = 4000


def create_app(config=None, repository=None, agent_factory=SimpleAgent):
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = 32 * 1024
    config = config or AgentConfig.from_env()
    repository = repository or ChatRepository(config.database_path)
    # Сериализация хода, копирования и настроек в локальном учебном сервере.
    lock = RLock()
    agents = {}
    app.extensions["chat_repository"] = repository

    def agent(chat_id):
        if chat_id not in agents:
            agents[chat_id] = agent_factory(config, chat_id, repository)
        return agents[chat_id]

    def payload():
        value = request.get_json(silent=True)
        if not isinstance(value, dict):
            raise ValueError("Тело запроса должно быть JSON-объектом")
        return value

    def title(data, default):
        value = data.get("title", default)
        if not isinstance(value, str) or len(value) > MAX_TITLE_LENGTH:
            raise ValueError(f"Название должно быть строкой до {MAX_TITLE_LENGTH} символов")
        return value

    def chat_data(chat_id):
        chat = repository.get_chat(chat_id)
        return {"chat": asdict(chat), "messages": repository.load_messages(chat_id),
                "statistics": agent(chat_id).statistics(),
                "checkpoints": repository.list_checkpoints(chat_id)}

    @app.before_request
    def check_chat():
        chat_id = (request.view_args or {}).get("chat_id")
        if chat_id is not None and repository.get_chat(chat_id) is None:
            return jsonify(error="Чат не найден"), 404

    @app.get("/")
    def index():
        return render_template("index.html", model=config.model)

    @app.get("/api/chats")
    def chats():
        return jsonify(chats=[asdict(chat) for chat in repository.list_chats()])

    @app.post("/api/chats")
    def create_chat():
        data = payload()
        with lock:
            chat = repository.create_chat(title(data, DEFAULT_CHAT_TITLE), config.context_settings())
        return jsonify(chat=asdict(chat)), 201

    @app.get("/api/chats/<chat_id>/messages")
    def messages(chat_id):
        with lock:
            return jsonify(**chat_data(chat_id))

    @app.patch("/api/chats/<chat_id>/settings")
    def settings(chat_id):
        data = payload()
        with lock:
            agent(chat_id).configure(**data)
            return jsonify(statistics=agent(chat_id).statistics())

    def message_payload(*, allow_empty=False):
        data = payload()
        if set(data) != {"message"}:
            raise ValueError("Ожидается поле message. Настройки меняются отдельно до первого сообщения.")
        message = data["message"]
        if not isinstance(message, str) or not allow_empty and not message.strip():
            raise ValueError("Сообщение должно быть непустой строкой")
        if len(message) > MAX_MESSAGE_LENGTH:
            raise ValueError(f"Сообщение не должно быть длиннее {MAX_MESSAGE_LENGTH} символов")
        return message

    @app.post("/api/chats/<chat_id>/preview")
    def preview(chat_id):
        message = message_payload(allow_empty=True)
        with lock:
            return jsonify(preview=agent(chat_id).preview(message))

    @app.post("/api/chats/<chat_id>/messages")
    def send(chat_id):
        message = message_payload()
        with lock:
            current = agent(chat_id)
            try:
                answer = current.reply(message)
            except ContextOverflowError as error:
                return jsonify(error=str(error), code="context_overflow", preview=error.preview,
                               statistics=current.statistics()), 413
            except AgentError as error:
                return jsonify(error=str(error), statistics=current.statistics()), 502
            return jsonify(answer=answer, **chat_data(chat_id))

    @app.post("/api/chats/<chat_id>/copy")
    def copy(chat_id):
        data = payload()
        with lock:
            new_chat = agent(chat_id).copy(title(data, "") or None)
        return jsonify(chat=asdict(new_chat)), 201

    @app.post("/api/chats/<chat_id>/checkpoints")
    def checkpoint(chat_id):
        data = payload()
        with lock:
            checkpoint_id = agent(chat_id).checkpoint(title(data, "Checkpoint"))
        return jsonify(checkpoint_id=checkpoint_id), 201

    @app.get("/api/checkpoints")
    def checkpoints():
        return jsonify(checkpoints=repository.list_checkpoints())

    @app.post("/api/checkpoints/<checkpoint_id>/branches")
    def branch(checkpoint_id):
        data = payload()
        with lock:
            new_chat = repository.branch(checkpoint_id, title(data, "Новая ветка"))
        return jsonify(chat=asdict(new_chat)), 201

    @app.delete("/api/chats/<chat_id>")
    def delete(chat_id):
        with lock:
            agent(chat_id).delete()
            agents.pop(chat_id, None)
        return jsonify(ok=True)

    @app.errorhandler(SettingsLockedError)
    @app.errorhandler(ConversationChangedError)
    def conflict(error):
        return jsonify(error=str(error)), 409

    @app.errorhandler(ValueError)
    @app.errorhandler(AgentError)
    def invalid(error):
        return jsonify(error=str(error)), 400

    @app.errorhandler(ChatRepositoryError)
    def database_error(error):
        return jsonify(error=str(error)), 400

    @app.errorhandler(413)
    def too_large(error):
        return jsonify(error="Запрос слишком большой"), 413

    return app


def main():
    try:
        app = create_app()
        port = int(os.getenv("WEB_PORT", "5000"))
        if not 1 <= port <= 65535:
            raise ValueError("WEB_PORT должен быть от 1 до 65535")
    except (AgentError, ChatRepositoryError, ValueError) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    print(f"Откройте в браузере: http://127.0.0.1:{port}")
    app.run(host="127.0.0.1", port=port, debug=False)


if __name__ == "__main__":
    main()
