package agent

import chat.ChatHistoryRepository

class MockAiAgent(
    private val historyRepository: ChatHistoryRepository,
    initialSettings: AgentSettings
) : AiAgent {
    private var settings = initialSettings

    override fun send(userMessage: String): AgentResponse {
        historyRepository.addUser(userMessage)
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
