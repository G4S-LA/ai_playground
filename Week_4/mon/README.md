# MCP-клиент на Kotlin

Проект использует официальный Kotlin SDK для Model Context Protocol,
подключается к общедоступному MCP-серверу
документации и выводит список его инструментов. API-ключ не нужен.

## Как это работает

- `McpClient.kt` подключается по Streamable HTTP к публичному серверу
  `https://mcp.deepwiki.com/mcp`;
- клиент выполняет MCP-handshake, вызывает `tools/list` и возвращает описания
  доступных инструментов;
- `McpClientTest.kt` проверяет настоящее удалённое соединение и наличие
  инструмента поиска по документации.

Локальный MCP-сервер проект не поднимает.

## Требования

- JDK 17 или новее;
- доступ к Maven Central при первом запуске для загрузки зависимостей;
- доступ в интернет для подключения к публичному MCP-серверу.

## Запуск

Из каталога `Week_4/mon`:

```bash
./gradlew run
```

Ожидаемый результат:

```text
MCP connection established: https://mcp.deepwiki.com/mcp
Available tools: 3
- read_wiki_structure: View a list of documentation topics for a GitHub repository...
  input schema: ToolSchema(...)
...
```

Сообщение об успешном соединении выводится только после завершения MCP-handshake
и получения ответа сервера на `tools/list`. Другой публичный MCP endpoint можно
указать через переменную окружения:

```bash
MCP_SERVER_URL="https://example.com/mcp" ./gradlew run
```

## Проверка

```bash
./gradlew test
```

Тест подключается к публичному MCP-серверу, поэтому для него нужен интернет.
