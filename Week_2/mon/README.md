# Простой LLM-агент

Решение задания понедельника второй недели: консольный чат с отдельной
сущностью агента и запросами к OpenAI-compatible Chat Completions API.

## Архитектура

- `agent.py` содержит `SimpleAgent`: он хранит историю диалога, формирует
  HTTP-запрос, вызывает LLM, проверяет ответ и возвращает готовый текст.
- `cli.py` — только интерфейс: читает сообщения пользователя и печатает ответы.
- `test_agent.py` проверяет агента без реальных запросов и расходования токенов.

Таким образом, вызов API и логика запроса/ответа инкапсулированы в агенте, а не
размещены в коде интерфейса.

## Установка

Все команды выполняются из каталога `Week_2/mon`.

### Linux и macOS

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
```

### Windows PowerShell

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

## Настройка

Скопируйте `.env.example` в `.env`, укажите ключ и при необходимости измените
URL и модель. Сам файл `.env` не следует добавлять в Git.

Linux и macOS:

```bash
set -a
source .env
set +a
```

Windows PowerShell:

```powershell
$env:LLM_API_KEY = "your-api-key"
$env:LLM_API_URL = "https://api.openai.com/v1/chat/completions"
$env:LLM_MODEL = "gpt-4o-mini"
```

Для Alibaba Model Studio вместо `LLM_API_KEY` можно задать
`DASHSCOPE_API_KEY`, а в `LLM_API_URL` и `LLM_MODEL` — endpoint и ID доступной
модели из своего workspace.

## Запуск

```bash
python cli.py
```

После запуска вводите сообщения по одному. Агент сохраняет контекст текущего
диалога. Команда `/reset` очищает историю, `/exit` завершает программу.

Пример:

```text
Вы: Что такое HTTP?

Агент: HTTP — это протокол обмена данными между клиентом и сервером.
```

## Проверка без API-ключа

```bash
python -m unittest -v
```

Тесты подменяют HTTP-клиент и проверяют сформированный запрос, разбор ответа,
сохранение контекста и обработку ошибки API.

## Переменные окружения

| Переменная | Назначение | Значение по умолчанию |
|---|---|---|
| `LLM_API_KEY` | Ключ API | также читается `DASHSCOPE_API_KEY` |
| `LLM_API_URL` | Полный URL Chat Completions | OpenAI `/v1/chat/completions` |
| `LLM_MODEL` | ID модели | `gpt-4o-mini` |
| `LLM_SYSTEM_PROMPT` | Инструкция агенту | краткий полезный ассистент |
| `LLM_TEMPERATURE` | Вариативность ответа от 0 до 2 | `0.7` |
| `LLM_TIMEOUT_SECONDS` | Таймаут HTTP-запроса | `120` |
