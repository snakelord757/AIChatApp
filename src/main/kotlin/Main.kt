import agent.DeepSeekAiAgent
import agent.MockAiAgent
import chat.ChatHistoryRepository
import cli.ChatApplication
import cli.ConsoleRenderer
import config.LocalPropertiesConfig
import formatting.ConsoleEncoding
import formatting.ConsoleScreen

fun main() {
    ConsoleEncoding.configureUtf8()
    ConsoleScreen.clear()

    val renderer = ConsoleRenderer()
    val configResult = LocalPropertiesConfig.load()
    val historyRepository = ChatHistoryRepository(
        when (configResult) {
            is LocalPropertiesConfig.Result.Success -> configResult.settings.systemPrompt
            is LocalPropertiesConfig.Result.Failure -> configResult.fallbackSettings.systemPrompt
        }
    )

    val (settings, agent) = when (configResult) {
        is LocalPropertiesConfig.Result.Success -> configResult.settings to DeepSeekAiAgent(
            historyRepository = historyRepository,
            initialSettings = configResult.settings
        )
        is LocalPropertiesConfig.Result.Failure -> {
            renderer.renderError(configResult.message)
            renderer.renderSystem("Реальный ключ не найден, поэтому запущен локальный демонстрационный режим без обращения к DeepSeek.")
            configResult.fallbackSettings to MockAiAgent(
                historyRepository = historyRepository,
                initialSettings = configResult.fallbackSettings
            )
        }
    }

    ChatApplication(
        agent = agent,
        initialSettings = settings,
        historyRepository = historyRepository,
        renderer = renderer
    ).run()
}
