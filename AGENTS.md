# AGENTS.md

## Назначение

Этот файл описывает локальные правила работы с проектом AIChatApp и краткую карту приложения по фичам. Пути указаны относительно корня репозитория.

## Правила для агентов

- При завершении задачи не запускать тесты и компиляцию проекта.
- Если задача завершена новым коммитом, необходимо добавить в раздел "Саммари выполненных коммитов" запись о проделанной работе.
- Запись саммари должна содержать название коммита, актуальный хэш коммита и дату выполнения.
- Если после доработок выполняется `amend commit` и `force push`, нужно дополнить текущую запись саммари и обновить в ней хэш коммита на актуальный.
- Не удалять предыдущий контекст саммари при доработках; дополнять существующую запись.
- Если коммит добавил новую фичу, нужно добавить ее в раздел "Структура приложения по фичам" с описанием по аналогии с уже описанными фичами.
- Пайплайн поднятия версий: при добавлении нового функционала поднимать минорную версию приложения; при `amend commit` поднимать hotfix-версию (последнее число).
- Перед записью саммари выполненного коммита необходимо сначала поднять версию, затем выполнить commit и push (если push требуется), и только после этого записывать саммари с актуальным хэшем коммита.

## Структура приложения по фичам

### Точка входа и запуск CLI

- `src/main/kotlin/Main.kt` - минимальная JVM-точка входа, передает аргументы в CLI.
- `src/main/kotlin/cli/AiChatCli.kt` - парсит CLI-команды, загружает конфигурацию, создает репозитории истории, памяти, invariants, MCP-клиент, агента DeepSeek или demo-агента, task orchestrator и запускает интерактивное приложение.
- `src/main/kotlin/cli/ChatApplication.kt` - основной интерактивный цикл чата: команды `/help`, `/settings`, `/mcp`, `/summary`, `/facts`, `/memory`, `/pause`, `/resume`, `/checkpoint`, `/branch`, `/clear`, `/exit`, отправка сообщений агенту и запуск/возобновление задач.
- `src/main/kotlin/cli/ConsoleInput.kt` - чтение пользовательского ввода из консоли.
- `src/main/kotlin/cli/ConsoleRenderer.kt` - рендеринг сообщений, подсказок, ошибок, usage/cost, статусов задач и экранов CLI.
- `src/main/kotlin/cli/SettingsScreen.kt` - экран интерактивного изменения настроек агента.
- `src/main/kotlin/cli/McpScreen.kt` - экран управления MCP-серверами и ручными вызовами MCP-инструментов.
- `src/main/kotlin/cli/SystemFileOpener.kt` - открывает локальные файлы, например invariants, через средства ОС.

### Агент и запросы к модели

- `src/main/kotlin/agent/AiAgent.kt` - общий интерфейс чат-агента.
- `src/main/kotlin/agent/DeepSeekAiAgent.kt` - реальный агент DeepSeek: собирает контекст, обновляет память, делает summary/facts-запросы, отправляет `/chat/completions`, обрабатывает usage, лимиты и MCP tool-call JSON.
- `src/main/kotlin/agent/MockAiAgent.kt` - локальный demo-агент для работы без API-ключа.
- `src/main/kotlin/agent/AgentSettings.kt` - настройки модели, URL, токенов, стратегии контекста, summary interval и режима planning swarm.
- `src/main/kotlin/agent/AgentResponse.kt` - модель ответа агента с usage и причиной завершения.
- `src/main/kotlin/agent/AgentException.kt` - доменное исключение агентского слоя.
- `src/main/kotlin/agent/JsonTools.kt` - утилиты для JSON: escape, извлечение content/usage/finish_reason и распознавание ошибок контекстного окна.
- `src/main/kotlin/agent/ResponseLimitClassifier.kt`, `src/main/kotlin/agent/ResponseLimitReason.kt` - классификация причин обрезанного ответа.

### История чата, контекст и ветки

- `src/main/kotlin/chat/ChatHistoryRepository.kt` - in-memory слой истории: сообщения, summary, sticky facts, checkpoint, ветки, активная ветка и подготовка контекста для API.
- `src/main/kotlin/chat/ChatHistoryStore.kt` - файловое хранение истории и блокировка файла от конкурентного доступа.
- `src/main/kotlin/chat/ChatHistoryJson.kt` - сериализация и десериализация состояния истории.
- `src/main/kotlin/chat/ChatMessage.kt`, `src/main/kotlin/chat/Role.kt` - базовые модели сообщений и ролей.
- `src/main/kotlin/chat/ChatSummary.kt` - модель summary активного диалога или ветки.
- `src/main/kotlin/chat/ContextStrategy.kt` - стратегии контекста `sliding` и `facts`.
- `src/main/kotlin/chat/TokenUsage.kt` - учет токенов.
- `src/main/kotlin/chat/AppPaths.kt` - вычисляет пути к данным приложения: история, память, MCP-серверы, task-state, audit, swarm session, invariants.
- `src/main/kotlin/chat/MojibakeRepair.kt` - восстановление текста при проблемах с кодировкой.

### Оркестрация задач

- `src/main/kotlin/task/TaskOrchestrator.kt` - выполняет задачу как цикл стадий, поддерживает pause/resume, retries после validation, запись stage events и финальный ответ в историю.
- `src/main/kotlin/task/TaskStateMachine.kt` - допустимые переходы между стадиями `PLANNING -> EXECUTION -> VALIDATION -> COMPLETION`.
- `src/main/kotlin/task/TaskModels.kt` - модели состояния задачи, стадий, результатов, lifecycle-статусов и входа стадии.
- `src/main/kotlin/task/StageAgent.kt` - интерфейс stage-agent и фабрики/реализации стадий.
- `src/main/kotlin/task/TaskStateStore.kt` - файловое хранение текущего состояния задачи.
- `src/main/kotlin/task/TaskStageAuditStore.kt` - audit-лог вызовов стадий в JSONL.
- `src/main/kotlin/task/TaskContextProvider.kt` - готовит рабочий контекст для stage agents из истории, памяти и invariants.
- `src/main/kotlin/task/ToolExecutionPipeline.kt` - выполняет запланированные MCP tool-call chains на стадии EXECUTION: валидирует explicit dependencies/input mappings, поддерживает sequential/parallel chains, передает outputs между шагами и пишет события выполнения для консоли и audit.

### Planning swarm

- `src/main/kotlin/swarm/SwarmOrchestrator.kt` - запускает несколько swarm-ролей по раундам, проверяет консенсус и синтезирует результат планирования.
- `src/main/kotlin/swarm/SwarmAgent.kt` - агент отдельной swarm-роли.
- `src/main/kotlin/swarm/SwarmModels.kt` - модели swarm-диалога, сообщений, ролей и результатов.
- `src/main/kotlin/swarm/PlanningSwarmStageAgent.kt` - адаптер planning swarm к stage-agent для стадии планирования.
- `src/main/kotlin/swarm/SwarmSessionStore.kt` - хранение сессии swarm между запусками.
- `src/main/kotlin/swarm/README.md` - локальная документация по swarm-фиче.

### MCP-интеграция

- `src/main/kotlin/mcp/ProcessMcpClient.kt` - MCP-клиент для stdio и Streamable HTTP: connect/list tools/call tool/close.
- `src/main/kotlin/mcp/McpClient.kt` - интерфейс MCP-клиента.
- `src/main/kotlin/mcp/McpToolGateway.kt` - gateway для списка доступных инструментов и вызовов tool-call из чата/агента.
- `src/main/kotlin/mcp/McpServerStore.kt` - сохранение конфигураций MCP-серверов.
- `src/main/kotlin/mcp/McpServerConfigResolver.kt` - резолвит MCP-конфигурации из команды, HTTP URL или директории проекта.
- `src/main/kotlin/mcp/McpProcessCommand.kt` - нормализует команды запуска MCP-процессов.
- `src/main/kotlin/mcp/McpToolArgumentSanitizer.kt` - чистит аргументы tool-call относительно схемы инструмента.
- `src/main/kotlin/mcp/McpModels.kt` - модели серверов, инструментов, статусов и результатов вызова.
- `src/main/kotlin/mcp/McpJson.kt` - JSON-парсер/рендерер MCP-слоя.

### Markdown memory

- `src/main/kotlin/memory/MemoryRepository.kt` - собирает memory system blocks, управляет permanent/personal/work memory, усиливает повторяющиеся пользовательские предпочтения.
- `src/main/kotlin/memory/MemoryStore.kt` - файловое чтение/запись Markdown memory и шаблонов.
- `src/main/kotlin/memory/MemoryType.kt` - типы памяти.
- `src/main/kotlin/memory/TaskStatus.kt` - статусы working memory: pending/paused/done.

### Assistant invariants

- `src/main/kotlin/invariants/InvariantRepository.kt` - подмешивает invariants как системный контекст и управляет операциями append/remove.
- `src/main/kotlin/invariants/InvariantStore.kt` - файловое хранение `invariants.md` и шаблон.

### Конфигурация и цены

- `src/main/kotlin/config/LocalPropertiesConfig.kt` - читает `local.properties`, валидирует DeepSeek API key/base URL/model и опции приложения.
- `src/main/kotlin/config/TokenPricing.kt` - модель цен токенов для отображения стоимости.

### Консольное форматирование

- `src/main/kotlin/formatting/ConsoleScreen.kt` - низкоуровневые операции с экраном консоли.
- `src/main/kotlin/formatting/ConsoleEncoding.kt` - настройка кодировки консоли.
- `src/main/kotlin/formatting/ConsolePrintStream.kt` - печать в консоль с учетом кодировки.
- `src/main/kotlin/formatting/MarkdownConsoleFormatter.kt` - форматирование Markdown-подобного текста для консоли.
- `src/main/kotlin/formatting/ConsoleTextSanitizer.kt` - очистка текста перед выводом.
- `src/main/kotlin/formatting/CliPromptMarkerNormalizer.kt` - нормализация случайных prompt-маркеров в сгенерированном тексте.
- `src/main/kotlin/formatting/ConsoleSystemPrompt.kt` - базовый системный prompt CLI.
- `src/main/kotlin/formatting/Ansi.kt` - ANSI-стили.

### Тесты

- `src/test/kotlin/agent/` - тесты агентов, JSON-утилит и классификации лимитов.
- `src/test/kotlin/chat/` - тесты истории, контекстных стратегий и восстановления текста.
- `src/test/kotlin/cli/` - тесты CLI-команд, экранов и рендеринга.
- `src/test/kotlin/config/` - тесты загрузки `local.properties`.
- `src/test/kotlin/formatting/` - тесты форматирования и sanitizer-логики.
- `src/test/kotlin/invariants/` - тесты invariants store/repository.
- `src/test/kotlin/mcp/` - тесты MCP-клиента, конфигов, команд и sanitizer.
- `src/test/kotlin/memory/` - тесты Markdown memory.
- `src/test/kotlin/swarm/` - тесты planning swarm.
- `src/test/kotlin/task/` - тесты state machine, orchestrator и stage agents.

## Саммари выполненных коммитов

Записи добавляются после завершения задачи новым коммитом. Формат:

```md
### <название коммита>

- Дата выполнения: YYYY-MM-DD
- Актуальный хэш: <hash>
- Саммари:
  - <что сделано>
  - <важные детали/доработки>
```

### Add scheduled task management

- Дата выполнения: 2026-06-27
- Актуальный хэш: 377adcfe86cb1e483a222616dd5a2ebc51d58b0d
- Саммари:
  - Добавлено управление фоновыми scheduled-задачами через экран `/task` с командами list, schedule, stop, clear, summary и совместимым алиасом `/tasks`.
  - Реализованы модели, JSON-хранилище, агенты строгого парсинга расписаний и суммаризации, менеджер фонового выполнения через существующий TaskOrchestrator с сохранением записей запусков и ошибок.
  - Добавлены focused unit tests для парсинга расписания, хранилища и базового lifecycle scheduled task manager.
  - Amend: scheduled-задачи получили MCP-каталог в orchestrator context для planning/execution, swarm planning сохраняет MCP tool instructions, а scheduled prompt явно направляет релевантные задачи через существующий MCP gateway.

### Implement ordered MCP tool execution pipeline

- Дата выполнения: 2026-06-27
- Актуальный хэш: 913186f13c18f378e5562d68eda135894bdcea0d
- Саммари:
  - Добавлен ordered MCP tool-call pipeline для стандартного task/chat flow с explicit execution plan, chain identifiers, sequential/parallel mode, dependencies и input mappings.
  - Вызовы MCP tools вынесены на стадию EXECUTION; planning теперь формирует строгий `toolExecutionPlan`, а orchestrator валидирует план, передает outputs между шагами и рендерит события выполнения/ошибки в консоль и audit.
  - Усилена устойчивость к повторным MCP/runtime ошибкам: строгая валидация аргументов tool-call, очистка stale task-state, gateway refresh схем инструментов и корректный Windows AppPaths для Gradle/IDE запусков.

### Fix task state recovery test regressions

- Дата выполнения: 2026-06-27
- Актуальный хэш: 098855f2b0777e0726491cd39665a2848675024c
- Саммари:
  - Исправлены регрессии тестов после добавления tool execution pipeline: history renderer снова может рендерить stored EVENT подробно в unit tests, а startup UI схлопывает старые task/tool events только в стандартном запуске приложения.
  - `TaskStateStore.read()` снова выполняет обычное чтение сохраненного состояния, а filtering terminal/stale MCP states вынесен в `readResumable()` для старта orchestrator.
  - Повторные validation failures снова переводят задачу в PAUSED, сохраняя совместимость pause/resume и planning swarm resume.

### Fix strict MCP tool execution contracts

- Дата выполнения: 2026-06-28
- Актуальный хэш: a9debfaa4429e6e9bff962a2dbf0deab8c2a2f1c
- Саммари:
  - Добавлена поддержка множественных MCP tool calls в основном агенте и stage-клиенте с передачей всех результатов обратно в модель одним follow-up контекстом.
  - Усилены planning-инструкции для строгого соблюдения inputSchema и синтаксиса `toolExecutionPlan.inputMappings`.
  - Доработан ordered tool execution pipeline: chain input mappings, nested cross-chain step outputs, защита от пустых resolved аргументов, полные tool results в execution context, алиасы Amiibo `amiiboId`/`games` и совместимость с ошибочным `JSON.stringify(...)` вокруг source path.
  - Добавлены regression tests для нескольких MCP calls, более чем двух MCP серверов и реального amiibo-плана из истории чата.

### Configure CI release versioning

- Дата выполнения: 2026-07-06
- Актуальный хэш: 230e55aa370df2287b33fd0127e2c51586658827
- Саммари:
  - GitHub Actions release workflow переведен на запуск при push в ветки и ручной запуск без обязательного ввода версии.
  - Версия релиза теперь берется из `project.version`, текущая версия проекта обновлена до `1.11.0`.
  - Добавлена Gradle-задача `printProjectVersion` для резолва release tag в CI и зафиксирован локальный пайплайн поднятия версий.
