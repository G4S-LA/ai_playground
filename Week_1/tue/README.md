# Управление форматом и длиной ответа LLM

Скрипт отправляет один и тот же пользовательский запрос в
OpenAI-compatible Chat Completions API с разным уровнем контроля ответа.

Перед каждым запросом показывается меню:

1. без ограничений;
2. только строгий формат через `response_format`: каждое предложение на новой
   пронумерованной строке;
3. только ограничение длины;
4. только завершение по `stop sequence`;
5. все ограничения одновременно;
6. автоматическое сравнение ответа без ограничений и ответа со всеми
   ограничениями.

Во всех режимах сохраняются одна модель, один пользовательский запрос и одна
температура. Меняются только инструкции и параметры управления ответом.

## Установка

Все команды выполняются из каталога `Week_1/tue`.

### Linux и macOS

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
```

Задайте переменные окружения:

```bash
export LLM_API_KEY="your-api-key"
export LLM_API_URL="https://api.openai.com/v1/chat/completions"
export LLM_MODEL="gpt-4o-mini"
```

Либо скопируйте `.env.example` в `.env`, заполните ключ и загрузите настройки:

```bash
set -a
source .env
set +a
```

### Windows PowerShell

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt

$env:LLM_API_KEY = "your-api-key"
$env:LLM_API_URL = "https://api.openai.com/v1/chat/completions"
$env:LLM_MODEL = "gpt-4o-mini"
```

## Запуск

Интерактивный режим:

```bash
python llm.py
```

Можно передать первый запрос аргументом. Меню выбора ограничений всё равно
будет показано:

```bash
python llm.py "Объясни, как работает HTTP"
```

После первого ответа скрипт снова покажет меню. Нажатие Enter вместо нового
текста повторяет предыдущий запрос, что позволяет сравнить разные профили.

Самый простой способ выполнить сравнение из задания — выбрать пункт 6. Скрипт
отправит один и тот же запрос сначала без ограничений, а затем со всеми
ограничениями.

## Как устроены ограничения

### Формат

По умолчанию используется строгий Structured Output:

```text
LLM_RESPONSE_FORMAT=json_schema
```

В запрос передаётся:

```json
{
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "controlled_sentences",
      "strict": true,
      "schema": "..."
    }
  }
}
```

Обычный структурированный ответ содержит массив `sentences`, в котором каждый
элемент — одно законченное предложение:

```json
{
  "sentences": [
    "Первое предложение.",
    "Второе предложение."
  ]
}
```

Скрипт выводит элементы массива на отдельных строках и сам добавляет номера:

```text
1. Первое предложение.
2. Второе предложение.
```

В профиле со всеми ограничениями дополнительно требуется поле:

```json
{
  "sentences": [
    "Первое предложение.",
    "Второе предложение."
  ],
  "completion_status": "completed"
}
```

Для провайдеров без поддержки JSON Schema можно выбрать менее строгий JSON
Object mode:

```text
LLM_RESPONSE_FORMAT=json_object
```

Он гарантирует валидный JSON, но не гарантирует точное соответствие схеме.

### Длина

Длина контролируется одновременно инструкцией и техническим пределом:

```text
LLM_MAX_WORDS=80
LLM_MAX_SENTENCES=3
LLM_MAX_COMPLETION_TOKENS=180
LLM_TOKEN_LIMIT_PARAMETER=max_completion_tokens
```

Если провайдер поддерживает только старое имя параметра:

```text
LLM_TOKEN_LIMIT_PARAMETER=max_tokens
```

Если предел токенов достигнут, `finish_reason` будет равен `length`, а ответ
может оказаться незавершённым. Скрипт выводит предупреждение об этом.

### Завершение

В профиле 4 используется уникальный маркер и параметр `stop`:

```json
{
  "stop": ["<END_OF_ANSWER>"]
}
```

Модель получает инструкцию вывести этот маркер после полного ответа. API
останавливает генерацию на маркере и не включает его в результат.

В профиле 5 строгий JSON нельзя безопасно дополнять внешним текстовым маркером:
маркер нарушил бы JSON Schema. Поэтому используется разрешённый заданием второй
вариант — явное условие завершения. Модель обязана сначала сформировать
`sentences`, затем установить `completion_status` в `completed` и закрыть JSON.
Значение поля закреплено через `enum` в JSON Schema.

## Использование с Qwen

Alibaba Cloud Model Studio предоставляет OpenAI-compatible Chat Completions
API. Пример настроек находится в `.env.qwen.example`; в URL нужно заменить
`YOUR_WORKSPACE_ID` и при необходимости выбрать регион своего workspace.

По официальной документации Qwen поддерживает:

- `response_format={"type":"json_object"}` для большинства текстовых моделей;
- строгий `response_format={"type":"json_schema", ...}` только для выбранных
  новых моделей, включая серии Qwen3.7 Plus/Flash/Max и Qwen3.8 Max/Flash;
- `max_completion_tokens` для Qwen3.7 Max и новее, а также Qwen3.5 Plus/Flash
  и новее;
- `max_tokens` как устаревающий вариант для старых моделей;
- `stop` со строкой или массивом строк.

Для строгого режима в примере выбрана модель `qwen3.7-plus`. При использовании
структурированного вывода скрипт по умолчанию передаёт Qwen дополнительный
параметр `enable_thinking=false`, поскольку JSON-режим некоторых Qwen-моделей
несовместим с thinking mode. Это можно отключить:

```text
QWEN_DISABLE_THINKING_FOR_STRUCTURED=false
```

Пример использует endpoint региона EU. В регионе Singapore строгий JSON Schema
на момент написания не поддерживается. Набор совместимых моделей и регионов
может меняться, поэтому его нужно сверять с документацией провайдера.

Если выбранная Qwen-модель не поддерживает JSON Schema, для отдельных режимов
можно использовать менее строгий совместимый вариант:

```text
LLM_RESPONSE_FORMAT=json_object
LLM_TOKEN_LIMIT_PARAMETER=max_tokens
```

У этой комбинации есть существенный компромисс: `json_object` гарантирует лишь
валидный JSON, но не точный набор полей, а срабатывание `max_tokens` способно
обрезать JSON. Сама документация Qwen не рекомендует совмещать `max_tokens` со
Structured Output. Поэтому для пункта 5 и итогового сравнения лучше оставить
пример `qwen3.7-plus` + `json_schema` + `max_completion_tokens`. Даже в этом
случае слишком маленький жёсткий лимит может оборвать ответ; скрипт проверяет
JSON и выводит `finish_reason`.

Документация:

- [Qwen OpenAI-compatible Chat Completions](https://www.alibabacloud.com/help/en/model-studio/qwen-api-via-openai-chat-completions)
- [Qwen Structured Output](https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output)
- [OpenAI Chat Completions](https://developers.openai.com/api/reference/cli/resources/chat/subresources/completions/methods/create)

## Переменные окружения

| Переменная | Назначение | Значение по умолчанию |
|---|---|---|
| `LLM_API_KEY` | Ключ API | обязательное |
| `LLM_API_URL` | URL Chat Completions | OpenAI |
| `LLM_MODEL` | Модель | `gpt-4o-mini` |
| `LLM_PROVIDER` | `openai`, `qwen` или `auto` | `auto` |
| `LLM_RESPONSE_FORMAT` | `json_schema` или `json_object` | `json_schema` |
| `LLM_TOKEN_LIMIT_PARAMETER` | Имя параметра лимита | `max_completion_tokens` |
| `LLM_MAX_COMPLETION_TOKENS` | Жёсткий предел ответа | `180` |
| `LLM_MAX_WORDS` | Мягкое ограничение в словах | `80` |
| `LLM_MAX_SENTENCES` | Мягкое ограничение предложений | `3` |
| `LLM_STOP_SEQUENCE` | Строка остановки | `<END_OF_ANSWER>` |
| `LLM_TEMPERATURE` | Случайность генерации | `0.2` |
