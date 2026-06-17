package cli

import agent.AgentException
import agent.AgentResponse
import agent.AgentSettings
import agent.AiAgent
import agent.ResponseLimitReason
import agent.SummaryEvents
import chat.ChatHistoryRepository
import chat.ContextStrategy
import config.TokenPricing
import formatting.ConsoleScreen
import memory.MemoryRepository
import memory.TaskStatus
import task.TaskLifecycleStatus
import task.TaskOrchestrator
import task.TaskOrchestratorEvents
import task.StageResult
import task.TaskStage

class ChatApplication(
    private val agent: AiAgent,
    initialSettings: AgentSettings,
    private val historyRepository: ChatHistoryRepository,
    private val memoryRepository: MemoryRepository? = null,
    private val taskOrchestrator: TaskOrchestrator? = null,
    private val onSettingsChanged: (AgentSettings) -> Unit = {},
    private val renderer: ConsoleRenderer,
    private val pricing: TokenPricing?,
    private val startupWarning: String? = null,
    private val showStartupWarning: Boolean = true,
    private val input: ConsoleInput = ConsoleInput()
) {
    private var settings = initialSettings
    private val settingsScreen = SettingsScreen(renderer, input)
    private val renderLock = Any()

    @Volatile
    private var activeTaskThread: Thread? = null

    fun run() {
        render { renderer.renderGreeting() }
        if (showStartupWarning && startupWarning != null) {
            render { renderer.renderWarning(startupWarning) }
        }
        render { renderer.renderHistory(historyRepository.all()) }
        render { renderer.renderTaskState(taskOrchestrator?.currentState()) }
        renderStickyFactsIfNeeded()

        try {
            while (true) {
                render { renderer.prompt() }
                val rawInput = input.readLine()
                render { renderer.finishPromptInput(clearSubmittedLine = input.isInteractive()) }
                val userInput = rawInput?.normalizeChatInput() ?: break

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
                    userInput == "/summary" -> render { renderer.renderSummary(historyRepository.totalUsage(), pricing) }
                    userInput == "/facts" -> render { renderer.renderFacts(historyRepository.facts()) }
                    userInput == "/pause" -> handlePauseCommand()
                    userInput.commandName() == "/resume" -> handleResumeCommand(userInput)
                    userInput.commandName() == "/memory" -> handleMemoryCommand(userInput)
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
                        renderStickyFactsIfNeeded()
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

    private fun handleResumeCommand(command: String) {
        val clarification = command.substringAfter(' ', missingDelimiterValue = "").ifBlank { null }
        startOrResumeTask(userInput = clarification.orEmpty(), resume = true, renderUserMessage = false)
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
                    render { renderer.renderHistory(historyRepository.all()) }
                    renderStickyFactsIfNeeded()
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

        if (orchestrator != null) {
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
            render { renderer.renderSystem("Sending request to the assistant...") }
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
            renderStickyFactsIfNeeded()
        } catch (exception: AgentException) {
            render { renderer.renderError(exception.message ?: "Could not get an assistant response.") }
            renderStickyFactsIfNeeded()
        } catch (exception: RuntimeException) {
            render { renderer.renderError("Unexpected error: ${exception.message ?: exception::class.simpleName}") }
            renderStickyFactsIfNeeded()
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
            renderStickyFactsIfNeeded(background = true)
        } catch (exception: AgentException) {
            renderBackground { renderer.renderError(exception.message ?: "Could not get an assistant response.") }
            renderStickyFactsIfNeeded(background = true)
        } catch (exception: RuntimeException) {
            renderBackground { renderer.renderError("Unexpected error: ${exception.message ?: exception::class.simpleName}") }
            renderStickyFactsIfNeeded(background = true)
        } finally {
            activeTaskThread = null
        }
    }

    private fun renderStickyFactsIfNeeded(background: Boolean = false) {
        if (settings.contextStrategy != ContextStrategy.STICKY_FACTS) return
        val facts = historyRepository.facts()
        if (facts.isEmpty()) return
        val block: () -> Unit = {
            renderer.renderFacts(facts)
            historyRepository.lastFactsUsage()?.let { usage ->
                renderer.renderUsage(usage)
                renderer.renderCost(usage, pricing)
            }
            Unit
        }
        if (background) {
            renderBackground(block)
        } else {
            render(block)
        }
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
