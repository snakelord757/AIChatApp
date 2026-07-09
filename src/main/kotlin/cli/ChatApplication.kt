package cli

import agent.AgentException
import agent.AgentResponse
import agent.AgentSettings
import agent.AiAgent
import agent.ModelCatalogClient
import agent.ResponseLimitReason
import agent.SummaryEvents
import chat.ChatHistoryRepository
import config.TokenPricing
import formatting.ConsoleScreen
import invariants.InvariantRepository
import memory.MemoryRepository
import memory.TaskStatus
import mcp.McpClient
import mcp.McpServerStore
import mcp.ProcessMcpClient
import chat.AppPaths
import mcp.McpToolCallResult
import mcp.StoredMcpToolGateway
import scheduled.ScheduleParsingAgentClient
import scheduled.ScheduledTaskManager
import scheduled.ScheduledTaskSummaryAgent
import task.TaskLifecycleStatus
import task.TaskOrchestrator
import task.TaskOrchestratorEvents
import task.StageResult
import task.TaskStage

class ChatApplication(
    private val agent: AiAgent,
    initialSettings: AgentSettings,
    private val historyRepository: ChatHistoryRepository,
    private val invariantRepository: InvariantRepository? = null,
    private val fileOpener: FileOpener = SystemFileOpener(),
    private val memoryRepository: MemoryRepository? = null,
    private val taskOrchestrator: TaskOrchestrator? = null,
    private val onSettingsChanged: (AgentSettings) -> Unit = {},
    private val renderer: ConsoleRenderer,
    private val pricing: TokenPricing?,
    private val startupWarning: String? = null,
    private val showStartupWarning: Boolean = true,
    private val mcpStore: McpServerStore = McpServerStore(AppPaths.mcpServersPath()),
    private val mcpClient: McpClient = ProcessMcpClient(),
    private val scheduledTaskManager: ScheduledTaskManager? = null,
    private val scheduleParsingAgent: ScheduleParsingAgentClient? = null,
    private val scheduledTaskSummaryAgent: ScheduledTaskSummaryAgent? = null,
    private val modelCatalogClient: ModelCatalogClient? = null,
    private val mcpScreenFactory: (ConsoleRenderer, ConsoleInput) -> McpScreen = { screenRenderer, screenInput ->
        McpScreen(screenRenderer, mcpStore, mcpClient, screenInput)
    },
    private val taskScreenFactory: (ConsoleRenderer, ConsoleInput) -> TaskScreen? = { screenRenderer, screenInput ->
        val manager = scheduledTaskManager
        val parser = scheduleParsingAgent
        if (manager == null || parser == null) null else TaskScreen(
            renderer = screenRenderer,
            manager = manager,
            parser = parser,
            summaryAgent = scheduledTaskSummaryAgent,
            input = screenInput
        )
    },
    private val input: ConsoleInput = ConsoleInput()
) {
    private var settings = initialSettings
    private val settingsScreen = SettingsScreen(renderer, input)
    private val mcpScreen = mcpScreenFactory(renderer, input)
    private val taskScreen = taskScreenFactory(renderer, input)
    private val renderLock = Any()

    @Volatile
    private var activeTaskThread: Thread? = null

    fun run() {
        render { renderer.renderGreeting() }
        if (showStartupWarning && startupWarning != null) {
            render { renderer.renderWarning(startupWarning) }
        }
        render { renderer.renderHistory(historyRepository.all(), summarizeEvents = true) }
        render { renderer.renderTaskState(taskOrchestrator?.currentState()) }
        scheduledTaskManager?.startPersistedTasks()

        try {
            while (true) {
                render { renderer.prompt() }
                val rawInput = input.readLine()
                render { renderer.finishPromptInput(clearSubmittedLine = input.isInteractive()) }
                if (rawInput == null) {
                    render { renderer.renderSystem("Input stream is closed. If you started from the IDE, use an interactive Run console or stop the stale run and start again.") }
                    break
                }
                val userInput = rawInput.normalizeChatInput()

                when {
                    userInput.isBlank() -> render { renderer.renderSystem("Enter a message or command.") }
                    userInput in setOf("/exit", "/quit") -> {
                        if (isTaskRunning()) {
                            taskOrchestrator?.requestPause("Paused because CLI is exiting.")
                            activeTaskThread?.interrupt()
                            render { renderer.renderSystem("Pause requested. Stopping the current stage...") }
                            activeTaskThread?.join()
                        }
                        render { renderer.renderSystem("Goodbye!") }
                        return
                    }
                    userInput == "/help" -> render { renderer.renderHelp() }
                    userInput == "/models" -> handleModelsCommand()
                    userInput == "/summary" -> render { renderer.renderSummary(historyRepository.totalUsage(), pricing) }
                    userInput == "/facts" -> render { renderer.renderFacts(historyRepository.facts()) }
                    userInput == "/pause" -> handlePauseCommand()
                    userInput.commandName() == "/tool" -> handleToolCommand(userInput)
                    userInput.commandName() == "/resume" -> handleResumeCommand(userInput)
                    userInput.commandName() == "/edit" -> handleEditCommand(userInput)
                    userInput.commandName() == "/memory" -> handleMemoryCommand(userInput)
                    userInput in setOf("/task", "/tasks") -> {
                        val screen = taskScreen
                        if (screen == null) {
                            render { renderer.renderError("Scheduled tasks are unavailable in this session.") }
                        } else {
                            screen.open()
                            render { renderer.renderSystem("Returned to chat. History is saved.") }
                        }
                    }
                    userInput.commandName() in setOf("/task", "/tasks") ->
                        render { renderer.renderError("Enter /task to open task management, then use schedule, stop, clear, or summary inside the task screen.") }
                    userInput == "/checkpoint" -> {
                        historyRepository.checkpoint()
                        render { renderer.renderSystem("Checkpoint saved.") }
                    }
                    userInput.commandName() == "/branch" -> handleBranchCommand(userInput)
                    userInput == "/settings" -> {
                        settings = settingsScreen.open(settings)
                        agent.updateSettings(settings)
                        onSettingsChanged(settings)
                        historyRepository.updateSystemPrompt(settings.systemPrompt)
                        render { renderer.renderSystem("Returned to chat. History is saved.") }
                    }
                    userInput == "/mcp" -> {
                        mcpScreen.open()
                        render { renderer.renderSystem("Returned to chat. History is saved.") }
                    }
                    userInput == "/clear" -> {
                        historyRepository.clear(settings.systemPrompt)
                        ConsoleScreen.clear()
                        render { renderer.renderGreeting() }
                        if (startupWarning != null) {
                            render { renderer.renderWarning(startupWarning) }
                        }
                    }
                    userInput.startsWith("/") -> render { renderer.renderError("Unknown command. Enter /help for the command list.") }
                    else -> handleUserMessage(userInput)
                }
            }

            if (isTaskRunning()) {
                taskOrchestrator?.requestPause("Paused because input ended.")
                activeTaskThread?.interrupt()
                render { renderer.renderSystem("Pause requested. Stopping the current stage...") }
                activeTaskThread?.join()
            }
            render { renderer.renderSystem("Input ended. Application stopped.") }
        } finally {
            scheduledTaskManager?.shutdown()
            taskOrchestrator?.pauseActiveOnShutdown()
        }
    }

    private fun handlePauseCommand() {
        val orchestrator = taskOrchestrator
        if (orchestrator == null) {
            memoryRepository?.setWorkingStatus(TaskStatus.PAUSED)
            render { renderer.renderSystem("Task paused.") }
            return
        }
        val state = orchestrator.requestPause()
        activeTaskThread?.interrupt()
        render { renderer.renderSystem("Pause requested. Stopping the current stage: ${state.currentStage}.") }
    }

    private fun handleModelsCommand() {
        val client = modelCatalogClient
        if (client == null) {
            render { renderer.renderError("Model catalog is unavailable in this session.") }
            return
        }
        try {
            val models = client.fetch(settings)
            if (models.isEmpty()) {
                render { renderer.renderWarning("The model provider returned no models.") }
                return
            }
            settings = settings.copy(
                model = settings.model.takeIf { it in models } ?: models.first(),
                availableModels = models
            )
            agent.updateSettings(settings)
            onSettingsChanged(settings)
            render { renderer.renderModels(settings.model, models) }
        } catch (exception: AgentException) {
            render { renderer.renderError(exception.message ?: "Could not load models.") }
        } catch (exception: RuntimeException) {
            render { renderer.renderError(exception.message ?: "Could not load models.") }
        }
    }

    private fun handleResumeCommand(command: String) {
        val clarification = command.substringAfter(' ', missingDelimiterValue = "").ifBlank { null }
        startOrResumeTask(userInput = clarification.orEmpty(), resume = true, renderUserMessage = false)
    }

    private fun handleToolCommand(command: String) {
        val parts = command.split(Regex("\\s+"), limit = 4)
        if (parts.size != 4) {
            render { renderer.renderError("Usage: /tool <serverName> <toolName> <jsonArguments>") }
            return
        }
        val startedEvent = mcpToolStartedEvent(parts[1], parts[2], parts[3])
        historyRepository.addEvent(startedEvent)
        render { renderer.renderSystem(startedEvent) }
        try {
            val result = StoredMcpToolGateway(mcpStore, mcpClient).callTool(parts[1], parts[2], parts[3])
            val completedEvent = mcpToolCompletedEvent(result)
            historyRepository.addEvent(completedEvent)
            render { renderer.renderSystem(completedEvent) }
            render { renderer.renderMcpToolResult(result) }
        } catch (exception: RuntimeException) {
            val message = exception.message ?: "Could not call MCP tool."
            historyRepository.addEvent(mcpToolFailedEvent(parts[1], parts[2], message))
            render { renderer.renderError(message) }
        }
    }

    private fun handleEditCommand(command: String) {
        val parts = command.split(Regex("\\s+"))
        if (parts.size != 2 || !parts[1].equals("invariants", ignoreCase = true)) {
            render { renderer.renderError("Usage: /edit invariants") }
            return
        }
        val repository = invariantRepository
        if (repository == null) {
            render { renderer.renderError("Assistant invariants are unavailable in this session.") }
            return
        }
        repository.ensureInitialized()
        val path = repository.path().toAbsolutePath().normalize()
        try {
            fileOpener.open(path)
            render { renderer.renderSystem("Invariants file: $path") }
        } catch (exception: RuntimeException) {
            render {
                renderer.renderError(
                    "Could not open invariants file: ${exception.message ?: exception::class.simpleName}. Path: $path"
                )
            }
        } catch (exception: java.io.IOException) {
            render {
                renderer.renderError(
                    "Could not open invariants file: ${exception.message ?: exception::class.simpleName}. Path: $path"
                )
            }
        }
    }

    private fun handleBranchCommand(command: String) {
        val parts = command.split(Regex("\\s+"), limit = 3)
        when {
            parts.size >= 2 && parts[1].equals("list", ignoreCase = true) -> {
                    render { renderer.renderBranches(historyRepository.branchNames(), historyRepository.activeBranchName()) }
            }
            parts.size >= 3 && parts[1].equals("create", ignoreCase = true) -> {
                if (historyRepository.createBranch(parts[2])) {
                    render { renderer.renderSystem("Branch created and activated: ${parts[2]}") }
                } else {
                    render { renderer.renderError("Could not create branch. Use a non-empty unique name.") }
                }
            }
            parts.size >= 3 && parts[1].equals("switch", ignoreCase = true) -> {
                if (historyRepository.switchBranch(parts[2])) {
                    ConsoleScreen.clear()
                    render { renderer.renderGreeting() }
                    render { renderer.renderSystem("Switched to branch: ${parts[2]}") }
                    render { renderer.renderHistory(historyRepository.all(), summarizeEvents = true) }
                } else {
                    render { renderer.renderError("Branch not found: ${parts[2]}") }
                }
            }
            else -> render { renderer.renderError("Usage: /branch create <name>, /branch list, or /branch switch <name|main>") }
        }
    }

    private fun handleMemoryCommand(command: String) {
        val repository = memoryRepository
        if (repository == null) {
            render { renderer.renderError("Markdown memory is unavailable in this session.") }
            return
        }
        val parts = command.split(Regex("\\s+"), limit = 3)
        when {
            parts.size == 1 -> render { renderer.renderMemoryHelp() }
            parts.size >= 3 && parts[1].equals("show", ignoreCase = true) -> {
                when (parts[2].lowercase()) {
                    "permanent" -> render { renderer.renderMemory("Permanent Memory", repository.permanentMemory()) }
                    "personal" -> render { renderer.renderMemory("Personal Memory", repository.personalMemory()) }
                    "work" -> render { renderer.renderMemory("Working Memory", repository.workingMemory()) }
                    else -> render { renderer.renderError("Usage: /memory show <permanent|personal|work>") }
                }
            }
            parts.size >= 2 && parts[1].equals("status", ignoreCase = true) -> {
                render { renderer.renderSystem("Working memory status: ${repository.workingStatus().name}") }
            }
            parts.size >= 2 && parts[1].equals("done", ignoreCase = true) -> {
                repository.setWorkingStatus(TaskStatus.DONE)
                render { renderer.renderSystem("Working memory status: DONE") }
            }
            parts.size >= 2 && parts[1].equals("pending", ignoreCase = true) -> {
                repository.setWorkingStatus(TaskStatus.PENDING)
                render { renderer.renderSystem("Working memory status: PENDING") }
            }
            parts.size >= 2 && parts[1].equals("path", ignoreCase = true) -> {
                render { renderer.renderMemoryPaths(repository.paths()) }
            }
            parts.size >= 2 && parts[1].equals("reload", ignoreCase = true) -> {
                repository.ensureInitialized()
                render { renderer.renderSystem("Markdown memory files reloaded from disk.") }
            }
            else -> render { renderer.renderError("Usage: /memory, /memory show <permanent|personal|work>, /memory status, /memory done, /memory pending, /memory path") }
        }
    }

    private fun handleUserMessage(input: String) {
        if (isTaskRunning()) {
            render { renderer.renderError("A task is already running. Use /pause or wait for it to finish.") }
            return
        }
        if (isResumeRequest(input) && taskOrchestrator?.currentState()?.lifecycleStatus == TaskLifecycleStatus.PAUSED) {
            startOrResumeTask(userInput = "", resume = true, renderUserMessage = false)
            return
        }
        startOrResumeTask(userInput = input, resume = false, renderUserMessage = true)
    }

    private fun startOrResumeTask(userInput: String, resume: Boolean, renderUserMessage: Boolean) {
        if (isTaskRunning()) {
            render { renderer.renderError("A task is already running. Use /pause or wait for it to finish.") }
            return
        }
        val orchestrator = taskOrchestrator
        if (resume && orchestrator?.currentState()?.lifecycleStatus != TaskLifecycleStatus.PAUSED) {
            render { renderer.renderSystem("No paused task to resume.") }
            return
        }
        if (renderUserMessage) {
            render { renderer.renderUser(userInput) }
        }
        memoryRepository?.setWorkingStatus(TaskStatus.PENDING)

        if (orchestrator != null && !settings.shouldUseDirectChat()) {
            val thread = Thread {
                runOrchestratedTask(
                    input = userInput,
                    orchestrator = orchestrator,
                    resume = resume
                )
            }.also {
                it.name = "aichat-task-orchestrator"
                it.isDaemon = false
            }
            activeTaskThread = thread
            thread.start()
            return
        }

        try {
            render {
                renderer.renderSystem(
                    if (settings.ragEnabled) {
                        "Searching RAG indexes and sending request to the assistant..."
                    } else if (settings.systemPromptOverridden) {
                        "Sending request to the assistant with the custom system prompt..."
                    } else {
                        "Sending request to the assistant..."
                    }
                )
            }
            val response = agent.send(userInput, object : SummaryEvents {
                    override fun onSummaryStarted() {
                        render { renderer.renderSystem("Starting chat summarization.") }
                    }

                    override fun onSummaryUsage(usage: chat.TokenUsage) {
                        render {
                            renderer.renderUsage(usage)
                            renderer.renderCost(usage, pricing)
                        }
                    }

                    override fun onMcpToolCallStarted(serverName: String, toolName: String, argumentsJson: String) {
                        val event = mcpToolStartedEvent(serverName, toolName, argumentsJson)
                        historyRepository.addEvent(event)
                        render { renderer.renderSystem(event) }
                    }

                    override fun onMcpToolCallCompleted(result: McpToolCallResult) {
                        val event = mcpToolCompletedEvent(result)
                        historyRepository.addEvent(event)
                        render { renderer.renderSystem(event) }
                    }

                    override fun onMcpToolCallFailed(serverName: String, toolName: String, message: String) {
                        val event = mcpToolFailedEvent(serverName, toolName, message)
                        historyRepository.addEvent(event)
                        render { renderer.renderError(event) }
                    }
                })
            render { renderer.renderAssistant(response.content) }
            if (response.wasLimited) {
                render { renderer.renderWarning(limitWarning(response.limitReason)) }
            }
            render {
                renderer.renderUsage(response.usage)
                renderer.renderFinishReason(response.finishReason)
                renderer.renderCost(response.usage, pricing)
            }
            if (taskOrchestrator?.currentState()?.lifecycleStatus != TaskLifecycleStatus.PAUSED) {
                memoryRepository?.setWorkingStatus(TaskStatus.DONE)
            }
        } catch (exception: AgentException) {
            render { renderer.renderError(exception.message ?: "Could not get an assistant response.") }
        } catch (exception: RuntimeException) {
            render { renderer.renderError("Unexpected error: ${exception.message ?: exception::class.simpleName}") }
        }
    }

    private fun runOrchestratedTask(input: String, orchestrator: TaskOrchestrator, resume: Boolean) {
        try {
            renderBackground {
                renderer.renderSystem(if (resume) "Task resumed. You can type /pause while stages are running." else "Task started. You can type /pause while stages are running.")
            }
            val events = object : TaskOrchestratorEvents {
                override fun onStageStarted(stage: TaskStage) {
                    renderBackground {
                        renderer.renderStageStarted(stage)
                    }
                }

                override fun onStageCompleted(result: StageResult) {
                    renderBackground {
                        renderer.renderStageResult(result)
                    }
                }

                override fun onStageEvent(stage: TaskStage, content: String) {
                    renderBackground {
                        renderer.renderStageEvent(content)
                    }
                }
            }
            val response = if (resume) {
                orchestrator.resumePausedTask(input.takeIf { it.isNotBlank() }, events)
            } else {
                orchestrator.runTask(input, events)
            }
            if (orchestrator.currentState()?.lifecycleStatus == TaskLifecycleStatus.PAUSED) {
                renderBackground {
                    renderer.renderSystem(response.content)
                }
                return
            }
            renderBackground {
                renderer.renderAssistant(response.content)
                renderer.renderUsage(response.usage)
                renderer.renderCost(response.usage, pricing)
            }
            memoryRepository?.setWorkingStatus(TaskStatus.DONE)
        } catch (exception: AgentException) {
            renderBackground { renderer.renderError(exception.message ?: "Could not get an assistant response.") }
        } catch (exception: RuntimeException) {
            renderBackground { renderer.renderError("Unexpected error: ${exception.message ?: exception::class.simpleName}") }
        } catch (exception: Exception) {
            if (isExpectedTaskShutdown(exception)) {
                renderBackground { renderer.renderSystem("Task paused.") }
            } else {
                renderBackground { renderer.renderError("Unexpected error: ${exception.message ?: exception::class.simpleName}") }
            }
        } finally {
            activeTaskThread = null
        }
    }

    private fun isExpectedTaskShutdown(exception: Exception): Boolean =
        hasInterruptCause(exception)

    private fun hasInterruptCause(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if (current is InterruptedException || current is java.nio.channels.ClosedByInterruptException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isTaskRunning(): Boolean = activeTaskThread?.isAlive == true

    private fun isResumeRequest(input: String): Boolean {
        val value = input.trim().lowercase()
        return value in setOf(
            "resume",
            "resume task",
            "continue task",
            "\u043f\u0440\u043e\u0434\u043e\u043b\u0436\u0438 \u0437\u0430\u0434\u0430\u0447\u0443",
            "\u043f\u0440\u043e\u0434\u043e\u043b\u0436\u0438\u0442\u044c \u0437\u0430\u0434\u0430\u0447\u0443"
        )
    }

    private fun render(block: () -> Unit) {
        synchronized(renderLock) {
            block()
        }
    }

    private fun renderBackground(block: () -> Unit) {
        synchronized(renderLock) {
            renderer.prepareBackgroundOutput()
            block()
            renderer.prompt()
        }
    }

    private fun mcpToolStartedEvent(serverName: String, toolName: String, argumentsJson: String): String =
        "Calling MCP tool $serverName/$toolName with arguments: $argumentsJson"

    private fun mcpToolCompletedEvent(result: McpToolCallResult): String =
        "MCP tool ${result.serverName}/${result.toolName} completed${if (result.isError) " with error" else ""}. Result: ${result.content.take(500)}"

    private fun mcpToolFailedEvent(serverName: String, toolName: String, message: String): String =
        "MCP tool $serverName/$toolName failed: $message"

    private fun limitWarning(reason: ResponseLimitReason?): String = when (reason) {
        ResponseLimitReason.REQUEST_MAX_TOKENS ->
            "The response was truncated because the configured maxTokens limit was reached. Increase maxTokens or set it to <= 0 to omit max_tokens."
        ResponseLimitReason.SERVER_DEFAULT_OUTPUT_LIMIT ->
            "The response was truncated because the API applied its default output limit (about 8192 tokens). Set a larger maxTokens value if you need a longer answer."
        ResponseLimitReason.MODEL_CONTEXT_WINDOW ->
            "The response was truncated because the conversation reached the model context window. Clear history with /clear or shorten the conversation."
        ResponseLimitReason.MODEL_MAX_OUTPUT ->
            "The response was truncated because the model output limit was reached. Split the request into several parts."
        ResponseLimitReason.UNKNOWN_LENGTH_LIMIT, null ->
            "The response was truncated: the API returned finish_reason=length, but the exact cause could not be determined. Check maxTokens and history length."
    }

    private fun String.normalizeChatInput(): String {
        val trimmed = trimCliInputEdges()
            .withoutCliPromptMarker()
        if (trimmed.isEmpty()) return trimmed
        val slashLike = setOf('/', '\uFF0F', '\u2215', '\u2044')
        return if (trimmed.first() in slashLike) "/${trimmed.drop(1)}" else trimmed
    }

    private fun String.withoutCliPromptMarker(): String =
        when {
            firstOrNull() in setOf('>', '\uFF1E') -> drop(1).trimCliInputEdges()
            else -> this
        }

    private fun String.trimCliInputEdges(): String =
        trim { it.isWhitespace() || it == '\u00A0' || it == '\uFEFF' || it.isInvisibleFormat() }

    private fun String.commandName(): String =
        substringBefore(' ').lowercase()

    private fun Char.isInvisibleFormat(): Boolean =
        this in setOf('\u200B', '\u200C', '\u200D', '\u2060')
}
