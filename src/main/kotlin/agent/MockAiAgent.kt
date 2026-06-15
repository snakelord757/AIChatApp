package agent

import chat.ChatHistoryRepository
import chat.ContextStrategy
import chat.TokenUsage
import memory.MemoryRepository

class MockAiAgent(
    private val historyRepository: ChatHistoryRepository,
    initialSettings: AgentSettings,
    private val memoryRepository: MemoryRepository? = null
) : AiAgent {
    private var settings = initialSettings

    override fun send(userMessage: String, summaryEvents: SummaryEvents): AgentResponse {
        historyRepository.addUser(userMessage)
        memoryRepository?.contextMessages()
        memoryRepository?.reinforcePersonalSignals(userMessage)
        if (settings.contextStrategy == ContextStrategy.STICKY_FACTS) {
            historyRepository.applyExtractedFacts("latest_user_prompt: $userMessage", TokenUsage.ZERO)
        }
        if (settings.summaryInterval > 0 &&
            historyRepository.shouldCreateSummary(settings.summaryInterval)
        ) {
            summaryEvents.onSummaryStarted()
            historyRepository.saveSummary("Demo summary is available only in offline mode.", TokenUsage.ZERO)
            summaryEvents.onSummaryUsage(TokenUsage.ZERO)
        }
        val answer = """
            ## Demo response

            The app is running without a real DeepSeek key.

            You wrote: `$userMessage`

            Add `DEEPSEEK_API_KEY` to `local.properties` to receive real answers from `${settings.model}`.
        """.trimIndent()
        historyRepository.addAssistant(answer)
        return AgentResponse(answer)
    }

    override fun updateSettings(settings: AgentSettings) {
        this.settings = settings
    }
}
