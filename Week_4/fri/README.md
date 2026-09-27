# Multi-MCP агент: новости, курсы валют и Excel

Пятничное задание использует три MCP-сервера, а не три набора функций внутри
одного приложения:

| MCP | Откуда | Transport | Что делает |
|---|---|---|---|
| News MCP | готовый сервер из `Week_4/wed` | stdio | получает свежий top Hacker News |
| [Frankfurter MCP](https://github.com/lineofflight/frankfurter-mcp) | публичный hosted MCP | Streamable HTTP | возвращает справочные курсы валют |
| [Excel MCP](https://github.com/ralscha/excel-mcp) | готовый open-source сервер, версия 1.1.2 | stdio | создаёт, заполняет и читает `.xlsx` |

То есть новый сервер написан не был: проект переиспользует News MCP из
предыдущего задания, подключается к существующему hosted currency MCP и
запускает готовый бинарник Excel MCP.

## Выбор инструментов и длинный flow

```text
Запрос пользователя
        │
        ▼
 LLM + общий каталог tools/list
        │
        ├─ News MCP ───────── get_top_news
        │                         │
        ├─ Frankfurter MCP ── get_rates
        │                         │
        └─ Excel MCP ──────── create_workbook
                                  ↓
                             write_data_to_excel
                                  ↓
                             create_table
                                  ↓
                             get_workbook_metadata
```

Каждое подключение проходит отдельный MCP handshake. `MultiMcpGateway`
запрашивает реальные `tools/list`, объединяет схемы для модели и строит таблицу
`tool name → MCP session`. Модель выбирает инструмент по описанию и JSON Schema,
а gateway автоматически отправляет `tools/call` серверу-владельцу. Совпадающие
имена инструментов отвергаются, чтобы маршрут не был неоднозначным.

Схема выше — демонстрационный длинный flow, а не обязательный порядок. Модель
сама решает, какие tools нужны: может ответить без MCP, получить только курс
валют, запросить только новости либо собрать многошаговую цепочку с Excel.
Агент разрешает несколько tool calls за ход и продолжение flow по результатам
предыдущих серверов.

Для нового Excel-результата каждый запуск получает отдельное имя вида
`news-and-rates-20260927-143743-123-a1b2c3d4.xlsx`. `create_workbook` всегда
вызывается с `overwrite=false`; изменение существующей книги запрещено, если
она не была создана в рамках текущего запуска. Read-only Excel tools по-прежнему
могут читать существующие файлы.

## Подготовка

Нужен JDK 17 или новее и доступ к интернету для Hacker News, hosted Frankfurter
MCP и однократной загрузки Excel MCP.

Из `Week_4/fri` выполните:

```bash
./setup-mcp.sh
```

Скрипт выбирает бинарник для Windows/macOS/Linux и arm64/amd64, скачивает закреплённую
версию Excel MCP 1.1.2, проверяет SHA-256 и кладёт исполняемый файл в `.mcp/`.
На Windows запускайте его из Git Bash; варианты MINGW/MSYS/CYGWIN распознаются
автоматически. Путь можно переопределить переменной `EXCEL_MCP_EXECUTABLE`.

## Демонстрация без LLM API-ключа

```bash
./gradlew :run --args=demo
```

Детерминированная demo-модель анализирует ключевые слова запроса и вызывает
только подходящие MCP. Стандартный demo-запрос выполняет полный flow и создаёт
новый файл в `data/`. В консоли отображается фактическая маршрутизация:

```text
1. News MCP → get_top_news
2. Frankfurter MCP → get_rates
3. Excel MCP → create_workbook
4. Excel MCP → write_data_to_excel
5. Excel MCP → create_table
6. Excel MCP → get_workbook_metadata
```

Двоеточие в `:run` важно: проект подключает `Week_4/wed` как Gradle subproject,
а эта форма запускает только корневое приложение.

## Web-интерфейс и подсветка MCP

```bash
./gradlew :run --args="web --demo"
```

Откройте <http://127.0.0.1:8080>. Правая панель строится из живого ответа
`/api/topology` и показывает все обнаруженные серверы и инструменты.

- выбранный моделью tool подсвечивается внутри карточки его MCP;
- активный сервер показывает, куда сейчас маршрутизирован `tools/call`;
- использованные MCP и завершённые tools остаются отмеченными;
- в ленте видны аргументы, результат и сервер-владелец каждого вызова;
- состояние обновляется во время выполнения длинного flow.

Например, запрос «Покажи курс USD к EUR» задействует только Frankfurter MCP, а
запрос на новости без сохранения — только News MCP. `web --demo` показывает ту
же выборочную маршрутизацию без API-ключа; обычный `web` передаёт решение
настоящей OpenAI-совместимой модели.

Запуск создаётся через `POST /api/runs`, затем браузер опрашивает
`/api/runs/{id}` примерно раз в 300 мс.

## Запуск с настоящей моделью

Скопируйте настройки из `.env.example` в `.env`, задайте ключ и при
необходимости OpenAI-совместимый Chat Completions endpoint с tool calling:

```bash
./gradlew :run --args=web
./gradlew :run --args="run Собери свежие новости и курсы USD к EUR, GBP и JPY в Excel"
```

Основные параметры:

- `NEWS_API_BASE_URL` — источник Hacker News для News MCP;
- `CURRENCY_MCP_URL` — URL hosted Frankfurter MCP;
- `EXCEL_MCP_EXECUTABLE` — путь к бинарнику Excel MCP;
- `MULTI_MCP_OUTPUT_DIR` — каталог итоговой книги.

Frankfurter отдаёт справочные дневные курсы, а не биржевые котировки в реальном
времени.

## Проверка

```bash
./gradlew :test
```

Тесты не требуют сети: fake gateway с теми же MCP-контрактами проверяет
discovery, принадлежность tools, полный шестиэтапный demo-flow, одиночный вызов
только Currency MCP, ответ без tools, безопасное создание уникального Excel,
web topology и `serverId` каждого события.

Ручной smoke test с реальными MCP:

```bash
./gradlew :run --args=demo
file data/news-and-rates-*.xlsx
```
