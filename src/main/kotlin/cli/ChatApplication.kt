package cli

import agent.AgentException
import agent.AgentSettings
import agent.AiAgent
import agent.ResponseLimitReason
import agent.SummaryEvents
import chat.ChatHistoryRepository
import config.TokenPricing
import formatting.ConsoleScreen

class ChatApplication(
    private val agent: AiAgent,
    initialSettings: AgentSettings,
    private val historyRepository: ChatHistoryRepository,
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

        while (true) {
            renderer.prompt()
            val userInput = input.readLine()?.trimChatInput() ?: break

            when {
                userInput.isBlank() -> renderer.renderSystem("Enter a message or command.")
                userInput in setOf("/exit", "/quit") -> {
                    renderer.renderSystem("Goodbye!")
                    return
                }
                userInput == "/help" -> renderer.renderHelp()
                userInput == "/summary" -> renderer.renderSummary(historyRepository.totalUsage(), pricing)
                userInput == "/settings" -> {
                    settings = settingsScreen.open(settings)
                    agent.updateSettings(settings)
                    historyRepository.updateSystemPrompt(settings.systemPrompt)
                    renderer.renderSystem("Returned to chat. History is saved.")
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

    private fun handleUserMessage(input: String) {
        renderer.renderUser(input)

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
        } catch (exception: AgentException) {
            renderer.renderError(exception.message ?: "Could not get an assistant response.")
        } catch (exception: RuntimeException) {
            renderer.renderError("Unexpected error: ${exception.message ?: exception::class.simpleName}")
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

    private fun String.trimChatInput(): String = trim { it.isWhitespace() || it == '\uFEFF' }
}
