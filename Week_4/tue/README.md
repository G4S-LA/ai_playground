# MCP-сервер для Git

Задание вторника четвёртой недели. Проект реализует собственный MCP-сервер
вокруг Git CLI и клиент-агент, который обнаруживает и вызывает его инструмент.

## Сценарий

```text
GitAgent
   │ tools/list + tools/call
   ▼
MCP client ── stdio ── Git MCP server ── git CLI ── repository
```

Сервер регистрирует инструмент `git_repository_summary` со входными параметрами:

| Параметр | Тип | Обязательный | Назначение |
|---|---|---:|---|
| `repositoryPath` | `string` | да | путь к локальному Git-репозиторию |
| `commitLimit` | `integer` | нет | число последних коммитов, от 1 до 20; по умолчанию 5 |

Инструмент возвращает корень репозитория, состояние рабочей ветки и последние
коммиты. Git запускается через `ProcessBuilder` без shell-интерполяции.

`GitAgent` запускает сервер отдельным JVM-процессом, устанавливает MCP-соединение
по stdio, находит инструмент через `tools/list`, вызывает его через `tools/call`
и использует полученный текст в выводе приложения.

## Требования

- JDK 17 или новее;
- установленный `git`;
- доступ к Maven Central при первом запуске.

## Запуск

Из каталога `Week_4/tue`:

```bash
./gradlew run
```

По умолчанию агент анализирует корень этого учебного репозитория. Другой путь
можно передать аргументом:

```bash
./gradlew run --args="/path/to/repository"
```

Пример результата:

```text
Agent called MCP tool 'git_repository_summary'
Agent received and used the result:
Repository: /path/to/repository
Status:
## main...origin/main
Recent commits:
836da4a | Author | Add Kotlin MCP tools client
```

## Проверка

```bash
./gradlew test
```

Интеграционный тест создаёт временный Git-репозиторий с коммитом, запускает
настоящий MCP-сервер, вызывает инструмент через клиента и проверяет полученный
результат.
