# Simple LLM API client

Небольшой скрипт для отправки запросов в любую модель с
OpenAI-compatible Chat Completions API.

Все команды нужно выполнять из каталога `Week_1/mon`.

## Linux и macOS

Создайте виртуальное окружение и установите зависимости:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
```

Задайте настройки API в текущем терминале:

```bash
export LLM_API_KEY="your-api-key"
export LLM_API_URL="https://api.openai.com/v1/chat/completions"
export LLM_MODEL="gpt-4o-mini"
```

Также можно скопировать `.env.example` в `.env`, заполнить его и загрузить
переменные в текущий терминал:

```bash
set -a
source .env
set +a
```

Отправьте запрос:

```bash
python llm.py "Как прочитать JSON-файл в Python?"
```

## Windows PowerShell

Создайте виртуальное окружение и установите зависимости:

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

Задайте настройки API в текущем окне PowerShell:

```powershell
$env:LLM_API_KEY = "your-api-key"
$env:LLM_API_URL = "https://api.openai.com/v1/chat/completions"
$env:LLM_MODEL = "gpt-4o-mini"
```

Отправьте запрос:

```powershell
python llm.py "Как прочитать JSON-файл в Python?"
```

## Использование

Если запустить скрипт без аргумента, он попросит ввести запрос:

```bash
python llm.py
```

Для другого провайдера поменяйте `LLM_API_URL` и `LLM_MODEL`. Провайдер
должен поддерживать формат OpenAI Chat Completions.

Системный промпт можно переопределить через переменную
`LLM_SYSTEM_PROMPT`: командой `export` в Linux/macOS или через `$env:` в
PowerShell.
