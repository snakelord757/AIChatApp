package cli

import agent.AgentSettings
import formatting.Ansi
import formatting.MarkdownConsoleFormatter

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

    fun renderSystem(text: String) {
        println(Ansi.style("Система: $text", Ansi.BLUE))
    }

    fun renderError(text: String) {
        println(Ansi.style("Ошибка: $text", Ansi.RED, Ansi.BOLD))
    }

    fun renderSettings(settings: AgentSettings) {
        println(Ansi.style("Настройки агента", Ansi.BOLD, Ansi.CYAN))
        println("Модель: ${settings.model}")
        println("Температура: ${settings.temperature}")
        println("Максимум токенов: ${settings.maxTokens}")
        println("Базовый URL: ${settings.baseUrl}")
        println("Системный промпт: ${settings.systemPrompt.take(120).replace('\n', ' ')}")
    }

    fun prompt() {
        print(Ansi.style("> ", Ansi.BOLD, Ansi.GREEN))
    }
}
