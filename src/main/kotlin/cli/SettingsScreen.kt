package cli

import agent.AgentSettings
import java.net.URI

class SettingsScreen(
    private val renderer: ConsoleRenderer,
    private val input: ConsoleInput = ConsoleInput()
) {
    fun open(current: AgentSettings): AgentSettings {
        var settings = current
        renderHeader(settings)

        while (true) {
            print("настройки> ")
            val userInput = input.readLine()?.trimSettingsInput() ?: return settings
            if (userInput.isBlank()) {
                renderer.renderSystem("Введите команду настроек.")
                continue
            }

            val parts = userInput.split(Regex("\\s+"), limit = 3)
            when (parts.first().lowercase()) {
                "показать", "show" -> renderer.renderSettings(settings)
                "помощь", "help" -> renderHelp()
                "назад", "back", "выход", "exit" -> {
                    renderer.renderSystem("Возврат в чат.")
                    return settings
                }
                "установить", "set" -> {
                    if (parts.size < 3) {
                        renderer.renderError("Формат: set <ключ> <значение>")
                    } else {
                        settings = update(settings, parts[1], parts[2])
                    }
                }
                else -> renderer.renderError("Неизвестная команда настроек. Введите help.")
            }
        }
    }

    private fun renderHeader(settings: AgentSettings) {
        renderer.renderSettings(settings)
        renderHelp()
    }

    private fun renderHelp() {
        println("Команды настроек:")
        println("show / показать - показать текущие настройки")
        println("set model <deepseek-v4-flash|deepseek-v4-pro> - изменить модель")
        println("set thinking <on|off> - включить или отключить thinking mode")
        println("set temperature <0..2> - изменить температуру")
        println("set maxTokens <число> - изменить максимум токенов")
        println("set systemPrompt <текст> - изменить системный промпт")
        println("set baseUrl <url> - изменить базовый URL")
        println("back / назад - вернуться в чат")
    }

    private fun update(settings: AgentSettings, rawKey: String, value: String): AgentSettings {
        return when (rawKey.lowercase()) {
            "model", "модель" -> {
                val model = value.trim()
                if (model !in AgentSettings.supportedModels) invalid(settings) else settings.copy(model = model)
            }
            "thinking", "thinkingmode", "размышление" -> {
                when (value.trim().lowercase()) {
                    "on", "true", "enabled", "1", "вкл", "включить" -> settings.copy(thinkingMode = true)
                    "off", "false", "disabled", "0", "выкл", "отключить" -> settings.copy(thinkingMode = false)
                    else -> invalid(settings)
                }
            }
            "temperature", "температура" -> {
                val temperature = value.toDoubleOrNull()
                if (temperature == null || temperature !in 0.0..2.0) invalid(settings) else settings.copy(temperature = temperature)
            }
            "maxtokens", "max_tokens", "токены" -> {
                val maxTokens = value.toIntOrNull()
                if (maxTokens == null || maxTokens <= 0) invalid(settings) else settings.copy(maxTokens = maxTokens)
            }
            "systemprompt", "prompt", "промпт" -> {
                if (value.isBlank()) invalid(settings) else settings.copy(systemPrompt = value)
            }
            "baseurl", "url" -> {
                if (!isValidUrl(value)) invalid(settings) else settings.copy(baseUrl = value.trim().trimEnd('/'))
            }
            else -> {
                renderer.renderError("Неизвестная настройка: $rawKey")
                settings
            }
        }
    }

    private fun invalid(current: AgentSettings): AgentSettings {
        renderer.renderError("Некорректное значение настройки.")
        return current
    }

    private fun isValidUrl(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun String.trimSettingsInput(): String = trim { it.isWhitespace() || it == '\uFEFF' }
}
