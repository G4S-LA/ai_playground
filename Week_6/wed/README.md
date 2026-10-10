# Полностью локальный RAG на Ollama

Приложение переиспользует document pipeline и SQLite vector index из предыдущего
RAG-модуля, выполняет retrieval локально и передаёт найденные чанки локальной
chat-модели через Ollama. Облачных provider-ов, API-ключей и внешних LLM в
проекте нет.

```text
вопрос
  → bge-m3 в локальной Ollama
  → cosine search по structured-чанкам в SQLite
  → найденный контекст с метаданными и citation ID
  → qwen2.5:3b в локальной Ollama
  → ответ со ссылками [S1], [S2]
```

## Что переиспользуется

В `build.gradle.kts` подключены Kotlin-исходники индексации из `Week_5/mon`:

- загрузка `.md`, `.txt`, `.kt`, `.kts`, `.java` и `.pdf`;
- structured chunking;
- локальный embedding-клиент с `/api/embed`;
- хранение в SQLite и локальный cosine similarity.

По умолчанию приложение читает уже подготовленный индекс:

```text
../../Week_5/mon/data/document-index.db
```

Индекс должен быть построен той же embedding-моделью, которая указана в
`OLLAMA_EMBEDDING_MODEL`. Если размерности не совпадают, retrieval намеренно не
использует несовместимые векторы и предлагает перестроить индекс.

Если предыдущий индекс создавался с флагом `--demo`, его hashing-векторы нельзя
использовать вместе с `bge-m3`, даже при совпадении формата SQLite. Укажите
исходный `DOCUMENTS_DIR` и один раз выполните команду `index` без `--demo`.

## Подготовка локальных моделей

Нужны JDK 17+, Ollama и две локальные модели:

```bash
ollama pull bge-m3
ollama pull qwen2.5:3b
ollama serve
```

На macOS приложение Ollama обычно запускает сервер автоматически. Обе модели
доступны через один локальный endpoint `http://127.0.0.1:11434`; наружу запросы
не отправляются.

## Проверка полного pipeline

Все команды выполняются из `Week_6/wed`:

```bash
./gradlew run --args=check
```

Команда последовательно проверяет:

1. Ollama отвечает на `/api/tags`;
2. обе модели установлены;
3. structured-индекс не пуст;
4. локальный embedding запроса находит чанки;
5. локальная chat-модель генерирует ответ по ним.

Результат содержит фактический ответ, использованные источники, similarity score
и время retrieval/generation.

Один RAG-запрос из CLI:

```bash
./gradlew run --args="ask Что такое агентный RAG?"
```

## Web UI и HTTP API

```bash
./gradlew run --args=web
```

Откройте <http://127.0.0.1:8080>. Интерфейс показывает состояние обеих моделей,
размер индекса, этапы pipeline, локальный ответ, timings и раскрываемые чанки с
`source`, `section`, `chunk_id` и similarity score.

```text
GET  /api/info
POST /api/ask    {"question":"Из каких этапов состоит RAG?","topK":5}
POST /api/index  {}
```

## Перестроение индекса

Готовый индекс можно использовать без исходных документов. Они нужны только
для перестроения после изменения корпуса или embedding-модели:

```bash
DOCUMENTS_DIR=/path/to/documents ./gradlew run --args=index
```

Либо задайте пути в `.env`, взяв за основу `.env.example`.

## Настройки

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `OLLAMA_URL` | `http://127.0.0.1:11434` | локальный endpoint Ollama |
| `OLLAMA_EMBEDDING_MODEL` | `bge-m3` | embeddings для index/query |
| `OLLAMA_CHAT_MODEL` | `qwen2.5:3b` | генерация ответа |
| `OLLAMA_TEMPERATURE` | `0.2` | вариативность генерации |
| `OLLAMA_TIMEOUT_SECONDS` | `180` | таймаут локальной генерации |
| `INDEX_DB` | индекс из `Week_5/mon` | SQLite vector index |
| `DOCUMENTS_DIR` | документы из `Week_5/mon` | корпус для перестроения |
| `WEB_HOST` | `127.0.0.1` | локальный адрес Web UI |
| `WEB_PORT` | `8080` | порт Web UI |

Настройки читаются из `Week_6/wed/.env`, затем переопределяются переменными
процесса.

## Автотесты

```bash
./gradlew test
```

Тесты не требуют установленной Ollama. Они проверяют локальный retrieval,
передачу найденного контекста в генератор, нативные `/api/tags` и `/api/chat`
без `Authorization`, Web API и отсутствие обязательной облачной конфигурации.
