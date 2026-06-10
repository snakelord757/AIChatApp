package cli

import agent.AgentException
import agent.AgentSettings
import agent.AiAgent
import agent.ResponseLimitReason
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
                userInput.isBlank() -> renderer.renderSystem("Введите сообщение или команду.")
                userInput in setOf("/exit", "/quit", "/выход") -> {
                    renderer.renderSystem("До свидания!")
                    return
                }
                userInput in setOf("/help", "/помощь") -> renderer.renderHelp()
                userInput == "/summary" -> renderer.renderSummary(historyRepository.totalUsage(), pricing)
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
                    if (startupWarning != null) {
                        renderer.renderWarning(startupWarning)
                    }
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
            val response = agent.send(input)
            renderer.renderAssistant(response.content)
            if (response.wasLimited) {
                renderer.renderWarning(limitWarning(response.limitReason))
            }
            renderer.renderUsage(response.usage)
            renderer.renderFinishReason(response.finishReason)
            renderer.renderCost(response.usage, pricing)
        } catch (exception: AgentException) {
            renderer.renderError(exception.message ?: "Не удалось получить ответ ассистента.")
        } catch (exception: RuntimeException) {
            renderer.renderError("Неожиданная ошибка: ${exception.message ?: exception::class.simpleName}")
        }
    }

    private fun limitWarning(reason: ResponseLimitReason?): String = when (reason) {
        ResponseLimitReason.REQUEST_MAX_TOKENS ->
            "Ответ был обрезан: достигнут заданный в настройках лимит maxTokens. Увеличьте maxTokens или установите <= 0, чтобы не отправлять max_tokens."
        ResponseLimitReason.SERVER_DEFAULT_OUTPUT_LIMIT ->
            "Ответ был обрезан: API применил серверный лимит вывода по умолчанию (примерно 8192 токена). Укажите больший maxTokens в настройках, если нужен более длинный ответ."
        ResponseLimitReason.MODEL_CONTEXT_WINDOW ->
            "Ответ был обрезан: диалог уперся в контекстное окно модели. Очистите историю через /clear или сократите диалог."
        ResponseLimitReason.MODEL_MAX_OUTPUT ->
            "Ответ был обрезан: достигнут максимальный размер вывода, разрешенный моделью. Разбейте запрос на несколько частей."
        ResponseLimitReason.UNKNOWN_LENGTH_LIMIT, null ->
            "Ответ был обрезан: API вернул finish_reason=length, но точную причину определить не удалось. Проверьте maxTokens и длину истории."
    }

    private fun String.trimChatInput(): String = trim { it.isWhitespace() || it == '\uFEFF' }
}
