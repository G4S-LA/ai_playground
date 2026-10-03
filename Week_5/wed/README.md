# Улучшенный RAG на Kotlin

Проект добавляет второй этап после vector search и сравнивает два retrieval-пути на одной базе знаний и одной LLM:

1. **Обычный RAG** — исходный вопрос → cosine similarity → первые `finalK` чанков → ответ.
2. **Улучшенный RAG** — query rewrite → `candidateK` кандидатов → similarity threshold → heuristic reranking → `finalK` чанков → ответ.

Оба режима передают модели не больше `finalK` фрагментов. Поэтому сравнение не выигрывает просто за счёт увеличения контекста.

## Как устроено улучшение

Query rewrite выполняет та же внешняя LLM: она превращает разговорный вопрос в короткий запрос, сохраняя термины и смысловые ограничения, но не отвечает на него.

После поиска каждый кандидат получает три оценки:

```text
similarity    = cosine(query_embedding, chunk_embedding)
lexical       = доля значимых слов вопроса, найденных в чанке
rerank_score  = 0.8 × similarity + 0.2 × lexical
```

Сначала удаляются кандидаты с `similarity < threshold`, затем оставшиеся сортируются по `rerank_score`. В интерфейсе видны все кандидаты, причины отсева и финальный контекст.

Стартовые параметры:

- `candidateK = 12` — сколько результатов получить до фильтра;
- `finalK = 5` — сколько максимум передать LLM;
- `similarityThreshold = 0.35` — порог cosine similarity.

Порог зависит от embedding-модели и корпуса. Его стоит настраивать по контрольному набору: слишком низкий пропускает шум, слишком высокий оставляет модель без контекста.

## Запуск в Windows через MinGW / Git Bash

Нужны JDK 17+, Ollama с `bge-m3` и OpenAI-compatible API для генерации:

```bash
ollama pull bge-m3

cd /c/path/to/enhanced-rag
cp .env.example .env
# Заполните .env: пути к корпусу и индексу, LLM_API_KEY, LLM_API_URL и LLM_MODEL.

./gradlew.bat run --args=index
./gradlew.bat run --args=web
```

Откройте <http://127.0.0.1:8080>. Файл `.env` читается автоматически; переменные Git Bash имеют приоритет над ним.

Если structured-индекс уже построен тем же `bge-m3`, команду `index` можно пропустить. По умолчанию приложение переиспользует существующий корпус и SQLite-индекс; альтернативные пути задаются через `DOCUMENTS_DIR` и `INDEX_DB`.

Для проверки pipeline без внешнего API и Ollama:

```bash
./gradlew.bat run --args="index --demo"
./gradlew.bat run --args="web --demo"
```

После demo-режима перестройте индекс обычной embedding-моделью: размерности demo-векторов и `bge-m3` различаются.

## Контрольное сравнение

Используются те же 10 вопросов по файлам:

- `00-introduction.md`;
- `02-context-engineering.md`;
- `03-memory-and-rag.md`.

Для каждого режима считаются покрытие ожидаемых понятий, recall ожидаемых источников и наличие цитат. Для улучшенного режима дополнительно показывается retention — доля кандидатов, попавших в контекст после фильтрации и reranking.

Полный прогон выполняет 30 генераций: 10 обычных ответов, 10 query rewrite и 10 улучшенных ответов. При использовании внешнего API учитывайте стоимость и rate limits.

## Команды

```bash
./gradlew.bat run --args="compare Зачем документы разбивают на чанки?"
./gradlew.bat run --args=questions
./gradlew.bat test
```

На macOS/Linux вместо `./gradlew.bat` используйте `./gradlew`.

## HTTP API

```text
GET  /api/info
GET  /api/questions
POST /api/index
POST /api/compare
POST /api/evaluations
GET  /api/evaluations/{id}
```

Пример сравнения:

```json
{
  "question": "Как следует располагать данные для эффективного KV Cache?",
  "candidateK": 12,
  "finalK": 5,
  "similarityThreshold": 0.35
}
```
