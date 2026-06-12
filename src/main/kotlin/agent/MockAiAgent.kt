package agent

import chat.ChatHistoryRepository
import chat.TokenUsage

class MockAiAgent(
    private val historyRepository: ChatHistoryRepository,
    initialSettings: AgentSettings
) : AiAgent {
    private var settings = initialSettings

    override fun send(userMessage: String, summaryEvents: SummaryEvents): AgentResponse {
        historyRepository.addUser(userMessage)
        if (historyRepository.shouldCreateSummary(settings.summaryInterval)) {
            summaryEvents.onSummaryStarted()
            historyRepository.saveSummary("Demo summary is available only in offline mode.", TokenUsage.ZERO)
            summaryEvents.onSummaryUsage(TokenUsage.ZERO)
        }
        val answer = """
            ## Демонстрационный ответ

            Сейчас приложение работает без реального ключа DeepSeek.

            Вы написали: `$userMessage`

            Добавьте `DEEPSEEK_API_KEY` в `local.properties`, чтобы получать настоящие ответы модели `${settings.model}`.
        """.trimIndent()
        historyRepository.addAssistant(answer)
        return AgentResponse(answer)
    }

    override fun updateSettings(settings: AgentSettings) {
        this.settings = settings
    }
}
