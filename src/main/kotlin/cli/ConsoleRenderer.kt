package cli

import agent.AgentSettings
import chat.ChatMessage
import chat.Role
import chat.TokenUsage
import config.TokenPricing
import formatting.Ansi
import formatting.MarkdownConsoleFormatter
import java.util.Locale

class ConsoleRenderer(
    private val formatter: MarkdownConsoleFormatter = MarkdownConsoleFormatter()
) {
    fun renderGreeting() {
        println(Ansi.style("AIChatApp: CLI-чат с DeepSeek", Ansi.BOLD, Ansi.CYAN))
        println("Введите сообщение или команду. Доступные команды:")
        renderHelp()
    }

    fun renderHelp() {
        println("${Ansi.style("/help", Ansi.CYAN)} - показать помощь")
        println("${Ansi.style("/settings", Ansi.CYAN)} - открыть настройки")
        println("${Ansi.style("/summary", Ansi.CYAN)} - показать токены и стоимость всей истории чата")
        println("${Ansi.style("/clear", Ansi.CYAN)} - очистить историю текущей сессии")
        println("${Ansi.style("/exit", Ansi.CYAN)} - выйти")
    }

    fun renderUser(text: String) {
        println()
        println(Ansi.style("Вы:", Ansi.BOLD, Ansi.GREEN))
        println(text)
    }

    fun renderAssistant(text: String) {
        println()
        println(Ansi.style("Ассистент:", Ansi.BOLD, Ansi.MAGENTA))
        println(formatter.format(text))
        println()
    }

    fun renderHistory(messages: List<ChatMessage>) {
        messages.forEach { message ->
            when (message.role) {
                Role.USER -> renderUser(message.content)
                Role.ASSISTANT -> renderAssistant(message.content)
                Role.SYSTEM -> Unit
            }
        }
    }

    fun renderSystem(text: String) {
        println(Ansi.style("Система: $text", Ansi.BLUE))
    }

    fun renderWarning(text: String) {
        println(Ansi.style("Предупреждение: $text", Ansi.YELLOW, Ansi.BOLD))
    }

    fun renderError(text: String) {
        println(Ansi.style("Ошибка: $text", Ansi.RED, Ansi.BOLD))
    }

    fun renderSettings(settings: AgentSettings) {
        println(Ansi.style("Настройки агента", Ansi.BOLD, Ansi.CYAN))
        println("Модель: ${settings.model}")
        println("Thinking mode: ${if (settings.thinkingMode) "включен" else "выключен"}")
        println("Температура: ${settings.temperature}")
        println("Максимум токенов: ${if (settings.maxTokens > 0) settings.maxTokens else "без ограничений"}")
        println("Базовый URL: ${settings.baseUrl}")
        println("Системный промпт: ${settings.systemPrompt.take(120).replace('\n', ' ')}")
    }

    fun renderUsage(usage: TokenUsage) {
        println("Токены: ввод=${usage.inputTokens}, вывод=${usage.outputTokens}, размышление=${usage.reasoningTokens}, всего=${usage.totalTokens}")
    }

    fun renderFinishReason(finishReason: String?) {
        if (!finishReason.isNullOrBlank() && finishReason != "stop") {
            println("finish_reason: $finishReason")
        }
    }

    fun renderCost(usage: TokenUsage, pricing: TokenPricing?) {
        println()
        if (pricing == null) {
            println("Стоимость: недоступна (цена токенов не настроена)")
        } else {
            println("Стоимость: $${formatUsd(pricing.costUsd(usage))}")
        }
    }

    fun renderSummary(usage: TokenUsage, pricing: TokenPricing?) {
        println()
        println(Ansi.style("Сводка чата", Ansi.BOLD, Ansi.CYAN))
        renderUsage(usage)
        renderCost(usage, pricing)
        println()
    }

    fun prompt() {
        print(Ansi.style("> ", Ansi.BOLD, Ansi.GREEN))
    }

    private fun formatUsd(value: Double): String = String.format(Locale.US, "%.6f", value)
}
