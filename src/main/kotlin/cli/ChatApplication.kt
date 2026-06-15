package cli

import agent.AgentException
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

class ChatApplication(
    private val agent: AiAgent,
    initialSettings: AgentSettings,
    private val historyRepository: ChatHistoryRepository,
    private val memoryRepository: MemoryRepository? = null,
    private val renderer: ConsoleRenderer,
    private val pricing: TokenPricing?,
    private val startupWarning: String? = null,
    private val showStartupWarning: Boolean = true,
    private val input: ConsoleInput = ConsoleInput()
) {
    private var settings = initialSettings
    private val settingsScreen = SettingsScreen(renderer, input)

    fun run() {
        renderer.renderGreeting()
        if (showStartupWarning && startupWarning != null) {
            renderer.renderWarning(startupWarning)
        }
        renderer.renderHistory(historyRepository.all())
        renderStickyFactsIfNeeded()

        while (true) {
            renderer.prompt()
            val userInput = input.readLine()?.normalizeChatInput() ?: break

            when {
                userInput.isBlank() -> renderer.renderSystem("Enter a message or command.")
                userInput in setOf("/exit", "/quit") -> {
                    renderer.renderSystem("Goodbye!")
                    return
                }
                userInput == "/help" -> renderer.renderHelp()
                userInput == "/summary" -> renderer.renderSummary(historyRepository.totalUsage(), pricing)
                userInput == "/facts" -> renderer.renderFacts(historyRepository.facts())
                userInput.commandName() == "/memory" -> handleMemoryCommand(userInput)
                userInput == "/checkpoint" -> {
                    historyRepository.checkpoint()
                    renderer.renderSystem("Checkpoint saved.")
                }
                userInput.commandName() == "/branch" -> handleBranchCommand(userInput)
                userInput == "/settings" -> {
                    settings = settingsScreen.open(settings)
                    agent.updateSettings(settings)
                    historyRepository.updateSystemPrompt(settings.systemPrompt)
                    renderer.renderSystem("Returned to chat. History is saved.")
                    renderStickyFactsIfNeeded()
                }
                userInput == "/clear" -> {
                    historyRepository.clear(settings.systemPrompt)
                    ConsoleScreen.clear()
                    renderer.renderGreeting()
                    if (startupWarning != null) {
                        renderer.renderWarning(startupWarning)
                    }
                }
                userInput.startsWith("/") -> renderer.renderError("Unknown command. Enter /help for the command list.")
                else -> handleUserMessage(userInput)
            }
        }

        renderer.renderSystem("Input ended. Application stopped.")
    }

    private fun handleBranchCommand(command: String) {
        val parts = command.split(Regex("\\s+"), limit = 3)
        when {
            parts.size >= 2 && parts[1].equals("list", ignoreCase = true) -> {
                renderer.renderBranches(historyRepository.branchNames(), historyRepository.activeBranchName())
            }
            parts.size >= 3 && parts[1].equals("create", ignoreCase = true) -> {
                if (historyRepository.createBranch(parts[2])) {
                    renderer.renderSystem("Branch created and activated: ${parts[2]}")
                } else {
                    renderer.renderError("Could not create branch. Use a non-empty unique name.")
                }
            }
            parts.size >= 3 && parts[1].equals("switch", ignoreCase = true) -> {
                if (historyRepository.switchBranch(parts[2])) {
                    ConsoleScreen.clear()
                    renderer.renderGreeting()
                    renderer.renderSystem("Switched to branch: ${parts[2]}")
                    renderer.renderHistory(historyRepository.all())
                    renderStickyFactsIfNeeded()
                } else {
                    renderer.renderError("Branch not found: ${parts[2]}")
                }
            }
            else -> renderer.renderError("Usage: /branch create <name>, /branch list, or /branch switch <name|main>")
        }
    }

    private fun handleMemoryCommand(command: String) {
        val repository = memoryRepository
        if (repository == null) {
            renderer.renderError("Markdown memory is unavailable in this session.")
            return
        }
        val parts = command.split(Regex("\\s+"), limit = 3)
        when {
            parts.size == 1 -> renderer.renderMemoryHelp()
            parts.size >= 3 && parts[1].equals("show", ignoreCase = true) -> {
                when (parts[2].lowercase()) {
                    "permanent" -> renderer.renderMemory("Permanent Memory", repository.permanentMemory())
                    "personal" -> renderer.renderMemory("Personal Memory", repository.personalMemory())
                    "work" -> renderer.renderMemory("Working Memory", repository.workingMemory())
                    else -> renderer.renderError("Usage: /memory show <permanent|personal|work>")
                }
            }
            parts.size >= 2 && parts[1].equals("status", ignoreCase = true) -> {
                renderer.renderSystem("Working memory status: ${repository.workingStatus().name}")
            }
            parts.size >= 2 && parts[1].equals("done", ignoreCase = true) -> {
                repository.setWorkingStatus(TaskStatus.DONE)
                renderer.renderSystem("Working memory status: DONE")
            }
            parts.size >= 2 && parts[1].equals("pending", ignoreCase = true) -> {
                repository.setWorkingStatus(TaskStatus.PENDING)
                renderer.renderSystem("Working memory status: PENDING")
            }
            parts.size >= 2 && parts[1].equals("path", ignoreCase = true) -> {
                renderer.renderMemoryPaths(repository.paths())
            }
            parts.size >= 2 && parts[1].equals("reload", ignoreCase = true) -> {
                repository.ensureInitialized()
                renderer.renderSystem("Markdown memory files reloaded from disk.")
            }
            else -> renderer.renderError("Usage: /memory, /memory show <permanent|personal|work>, /memory status, /memory done, /memory pending, /memory path")
        }
    }

    private fun handleUserMessage(input: String) {
        renderer.renderUser(input)
        memoryRepository?.setWorkingStatus(TaskStatus.PENDING)

        try {
            renderer.renderSystem("Sending request to the assistant...")
            val response = agent.send(input, object : SummaryEvents {
                override fun onSummaryStarted() {
                    renderer.renderSystem("Starting chat summarization.")
                }

                override fun onSummaryUsage(usage: chat.TokenUsage) {
                    renderer.renderUsage(usage)
                    renderer.renderCost(usage, pricing)
                }
            })
            renderer.renderAssistant(response.content)
            if (response.wasLimited) {
                renderer.renderWarning(limitWarning(response.limitReason))
            }
            renderer.renderUsage(response.usage)
            renderer.renderFinishReason(response.finishReason)
            renderer.renderCost(response.usage, pricing)
            memoryRepository?.setWorkingStatus(TaskStatus.DONE)
            renderStickyFactsIfNeeded()
        } catch (exception: AgentException) {
            renderer.renderError(exception.message ?: "Could not get an assistant response.")
            renderStickyFactsIfNeeded()
        } catch (exception: RuntimeException) {
            renderer.renderError("Unexpected error: ${exception.message ?: exception::class.simpleName}")
            renderStickyFactsIfNeeded()
        }
    }

    private fun renderStickyFactsIfNeeded() {
        if (settings.contextStrategy != ContextStrategy.STICKY_FACTS) return
        val facts = historyRepository.facts()
        if (facts.isEmpty()) return
        renderer.renderFacts(facts)
        historyRepository.lastFactsUsage()?.let { usage ->
            renderer.renderUsage(usage)
            renderer.renderCost(usage, pricing)
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
        val trimmed = trim { it.isWhitespace() || it == '\uFEFF' || it.isInvisibleFormat() }
        if (trimmed.isEmpty()) return trimmed
        val slashLike = setOf('/', '\uFF0F', '\u2215', '\u2044')
        return if (trimmed.first() in slashLike) "/${trimmed.drop(1)}" else trimmed
    }

    private fun String.commandName(): String =
        substringBefore(' ').lowercase()

    private fun Char.isInvisibleFormat(): Boolean =
        this in setOf('\u200B', '\u200C', '\u200D', '\u2060')
}
