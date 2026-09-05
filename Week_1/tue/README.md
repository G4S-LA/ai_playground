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

Во всех режимах сохраняются одна модель, один пользовательский запрос, одна
температура и один режим рассуждений. Для моделей Alibaba Model Studio thinking
по умолчанию выключен во всех пунктах меню. Меняются только инструкции и
параметры управления видимым ответом.

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

В профиле 4 в качестве наглядной `stop sequence` используется обычная точка:

```json
{
  "stop": ["."]
}
```

Модель получает инструкцию начать ответ первым законченным предложением,
завершить его точкой и затем продолжить. API встречает первую точку и сразу
останавливает генерацию, поэтому в результате остаётся только первое
предложение. Сама точка в результат не включается.

Это специально упрощённая учебная демонстрация. В реальном приложении точка
может встретиться в сокращении, домене или десятичном числе и преждевременно
обрезать ответ, поэтому там обычно выбирают уникальную последовательность.

В профиле 5 строгий JSON нельзя безопасно дополнять внешним текстовым маркером:
маркер нарушил бы JSON Schema. Поэтому используется разрешённый заданием второй
вариант — явное условие завершения. Модель обязана сначала сформировать
`sentences`, затем установить `completion_status` в `completed` и закрыть JSON.
Значение поля закреплено через `enum` в JSON Schema.

## Использование с Qwen и другими моделями Model Studio

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

Для строгого режима в примере выбрана модель `qwen3.7-plus`. Для гибридных
thinking-моделей Alibaba Model Studio, включая Qwen и DeepSeek V4, скрипт по
умолчанию передаёт `enable_thinking=false` **во всех пунктах меню**, включая
профиль «Без ограничений». Это постоянная настройка эксперимента, а не одно из
сравниваемых ограничений: благодаря ей токены не расходуются целиком на
`reasoning_content`, а между запросами меняются только средства контроля
видимого ответа. Скрипт явно печатает состояние thinking mode перед меню и рядом
с каждым результатом.

Настройку можно отменить:

```text
MODEL_STUDIO_DISABLE_THINKING=false
```

Например, у `deepseek-v4-flash-0731` thinking mode включён по умолчанию. Без
`enable_thinking=false` маленький лимит может полностью израсходоваться на
`reasoning_content`, оставив поле `content` пустым.

`enable_thinking` — расширение Alibaba Model Studio, а не универсальный параметр
OpenAI-compatible API. Поэтому скрипт не отправляет его в OpenAI и неизвестным
провайдерам: такой сервер может отклонить весь запрос. У reasoning-моделей
OpenAI используется параметр `reasoning_effort`, но значение полного отключения
зависит от конкретной модели и поддерживается не всеми моделями. Для чистого
сравнения с OpenAI в примере оставлена `gpt-4o-mini`, у которой нет отдельного
thinking mode. Если выбрать другую reasoning-модель или другого провайдера,
нужно отдельно проверить его способ отключения рассуждений.

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
- [DeepSeek в Alibaba Model Studio](https://www.alibabacloud.com/help/en/model-studio/deepseek-api)
- [OpenAI Chat Completions](https://developers.openai.com/api/reference/cli/resources/chat/subresources/completions/methods/create)

## Переменные окружения

| Переменная | Назначение | Значение по умолчанию |
|---|---|---|
| `LLM_API_KEY` | Ключ API | обязательное |
| `LLM_API_URL` | URL Chat Completions | OpenAI |
| `LLM_MODEL` | Модель | `gpt-4o-mini` |
| `LLM_PROVIDER` | `openai`, `qwen`, `deepseek`, `model_studio` или `auto` | `auto` |
| `LLM_RESPONSE_FORMAT` | `json_schema` или `json_object` | `json_schema` |
| `LLM_TOKEN_LIMIT_PARAMETER` | Имя параметра лимита | `max_completion_tokens` |
| `LLM_MAX_COMPLETION_TOKENS` | Жёсткий предел ответа | `180` |
| `LLM_MAX_WORDS` | Мягкое ограничение в словах | `80` |
| `LLM_MAX_SENTENCES` | Мягкое ограничение предложений | `3` |
| `LLM_STOP_SEQUENCE` | Строка остановки | `.` |
| `LLM_TEMPERATURE` | Случайность генерации | `0.2` |
| `MODEL_STUDIO_DISABLE_THINKING` | Отключить thinking во всех режимах Alibaba Model Studio | `true` |
