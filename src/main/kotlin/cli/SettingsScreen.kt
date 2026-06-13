package cli

import agent.AgentSettings
import chat.ContextStrategy
import java.net.URI

class SettingsScreen(
    private val renderer: ConsoleRenderer,
    private val input: ConsoleInput = ConsoleInput()
) {
    fun open(current: AgentSettings): AgentSettings {
        var settings = current
        renderHeader(settings)

        while (true) {
            print("settings> ")
            val userInput = input.readLine()?.trimSettingsInput() ?: return settings
            if (userInput.isBlank()) {
                renderer.renderSystem("Enter a settings command.")
                continue
            }

            val parts = userInput.split(Regex("\\s+"), limit = 3)
            when (parts.first().lowercase()) {
                "show" -> renderer.renderSettings(settings)
                "help" -> renderHelp()
                "back", "exit" -> {
                    renderer.renderSystem("Returning to chat.")
                    return settings
                }
                "set" -> {
                    if (parts.size < 3) {
                        renderer.renderError("Usage: set <key> <value>")
                    } else {
                        settings = update(settings, parts[1], parts[2])
                    }
                }
                else -> renderer.renderError("Unknown settings command. Enter help.")
            }
        }
    }

    private fun renderHeader(settings: AgentSettings) {
        renderer.renderSettings(settings)
        renderHelp()
    }

    private fun renderHelp() {
        println("Settings commands:")
        println("show - show current settings")
        println("set model <deepseek-v4-flash|deepseek-v4-pro> - change the model")
        println("set thinking <on|off> - enable or disable thinking mode")
        println("set temperature <0..2> - change the temperature")
        println("set maxTokens <number> - change the max token limit; <= 0 means unlimited")
        println("set contextStrategy <sliding|facts> - change context strategy")
        println("set contextWindow <number> - change sliding/facts context window")
        println("set summaryInterval <number> - change the automatic summary interval; 0 disables it")
        println("set systemPrompt <text> - change the system prompt")
        println("set baseUrl <url> - change the base URL")
        println("back - return to chat")
    }

    private fun update(settings: AgentSettings, rawKey: String, value: String): AgentSettings {
        return when (rawKey.lowercase()) {
            "model" -> {
                val model = value.trim()
                if (model !in AgentSettings.supportedModels) invalid(settings) else settings.copy(model = model)
            }
            "thinking", "thinkingmode" -> {
                when (value.trim().lowercase()) {
                    "on", "true", "enabled", "1" -> settings.copy(thinkingMode = true)
                    "off", "false", "disabled", "0" -> settings.copy(thinkingMode = false)
                    else -> invalid(settings)
                }
            }
            "temperature" -> {
                val temperature = value.toDoubleOrNull()
                if (temperature == null || temperature !in 0.0..2.0) invalid(settings) else settings.copy(temperature = temperature)
            }
            "maxtokens", "max_tokens" -> {
                val maxTokens = value.toIntOrNull()
                if (maxTokens == null) invalid(settings) else settings.copy(maxTokens = maxTokens)
            }
            "summaryinterval", "summary_interval", "summary" -> {
                val interval = value.toIntOrNull()
                if (interval == null || interval < 0) invalid(settings) else settings.copy(summaryInterval = interval)
            }
            "contextstrategy", "context_strategy", "strategy" -> {
                val strategy = ContextStrategy.parse(value)
                if (strategy == null) invalid(settings) else settings.copy(contextStrategy = strategy)
            }
            "contextwindow", "context_window", "contextwindowmessages", "context_window_messages" -> {
                val window = value.toIntOrNull()
                if (window == null || window < 0) invalid(settings) else settings.copy(contextWindowMessages = window)
            }
            "systemprompt", "prompt" -> {
                if (value.isBlank()) invalid(settings) else settings.copy(systemPrompt = value)
            }
            "baseurl", "url" -> {
                if (!isValidUrl(value)) invalid(settings) else settings.copy(baseUrl = value.trim().trimEnd('/'))
            }
            else -> {
                renderer.renderError("Unknown setting: $rawKey")
                settings
            }
        }
    }

    private fun invalid(current: AgentSettings): AgentSettings {
        renderer.renderError("Invalid setting value.")
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
