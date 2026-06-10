package cli

import agent.AgentException
import agent.AgentSettings
import agent.AiAgent
import chat.ChatHistoryRepository
import formatting.ConsoleScreen

class ChatApplication(
    private val agent: AiAgent,
    initialSettings: AgentSettings,
    private val historyRepository: ChatHistoryRepository,
    private val renderer: ConsoleRenderer,
    private val input: ConsoleInput = ConsoleInput()
) {
    private var settings = initialSettings
    private val settingsScreen = SettingsScreen(renderer, input)

    fun run() {
        renderer.renderGreeting()
        renderer.renderHistory(historyRepository.all())

        while (true) {
            renderer.prompt()
            val userInput = input.readLine()?.trim() ?: break

            when {
                userInput.isBlank() -> renderer.renderSystem("Введите сообщение или команду.")
                userInput in setOf("/exit", "/quit", "/выход") -> {
                    renderer.renderSystem("До свидания!")
                    return
                }
                userInput in setOf("/help", "/помощь") -> renderer.renderHelp()
                userInput in setOf("/settings", "/настройки") -> {
                    settings = settingsScreen.open(settings)
                    agent.updateSettings(settings)
                    historyRepository.updateSystemPrompt(settings.systemPrompt)
                    renderer.renderSystem("Возврат в чат. История сохранена.")
                }
                userInput in setOf("/clear", "/очистить") -> {
                    historyRepository.clear(settings.systemPrompt)
                    ConsoleScreen.clear()
                    renderer.renderGreeting()
                }
                userInput.startsWith("/") -> renderer.renderError("Неизвестная команда. Введите /help для списка команд.")
                else -> handleUserMessage(userInput)
            }
        }

        renderer.renderSystem("Ввод завершён. Приложение остановлено.")
    }

    private fun handleUserMessage(input: String) {
        renderer.renderUser(input)

        try {
            renderer.renderSystem("Отправляю запрос ассистенту...")
            val answer = agent.send(input)
            renderer.renderAssistant(answer)
        } catch (exception: AgentException) {
            renderer.renderError(exception.message ?: "Не удалось получить ответ ассистента.")
        } catch (exception: RuntimeException) {
            renderer.renderError("Неожиданная ошибка: ${exception.message ?: exception::class.simpleName}")
        }
    }
}
