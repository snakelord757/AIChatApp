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
        println("set model <model-name> - change the model")
        println("set thinking <on|off> - enable or disable thinking mode")
        println("set temperature <0..2> - change the temperature")
        println("set maxTokens <number> - change the max token limit; <= 0 means unlimited")
        println("set modelContextWindow <tokens> - change the shared context window for the provider and local chat")
        println("set summaryInterval <number> - change the automatic summary interval; 0 disables it")
        println("set planningSwarmEnabled <true|false> - enable or disable experimental planning swarm")
        println("set ragEnabled <true|false> - enable or disable RAG chat mode")
        println("set ragOllamaUrl <url> - change the Ollama URL for RAG embeddings")
        println("set ragEmbeddingModel <model|index> - override embedding model or use each index model")
        println("set ragSearchTopK <number> - change initial RAG retrieval count")
        println("set ragTopK <number> - change final RAG context chunk count")
        println("set systemPrompt <text|default> - override the system prompt, or restore the staged default")
        println("set baseUrl <url> - change the base URL")
        println("back - return to chat")
    }

    private fun update(settings: AgentSettings, rawKey: String, value: String): AgentSettings {
        return when (rawKey.lowercase()) {
            "model" -> {
                val model = value.trim()
                if (model !in settings.availableModels) {
                    val hint = if (settings.availableModels.isEmpty()) {
                        "Run /models to load models from the provider first."
                    } else {
                        "Available models: ${settings.availableModels.joinToString(", ")}"
                    }
                    renderer.renderError("Unknown model. $hint")
                    settings
                } else {
                    settings.copy(model = model)
                }
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
            "modelcontextwindow", "model_context_window", "modelcontextwindowtokens", "model_context_window_tokens" -> {
                val window = value.toLongOrNull()
                if (window == null || window <= 0L) invalid(settings) else settings.copy(modelContextWindowTokens = window)
            }
            "summaryinterval", "summary_interval", "summary" -> {
                val interval = value.toIntOrNull()
                if (interval == null || interval < 0) invalid(settings) else settings.copy(summaryInterval = interval)
            }
            "planningswarmenabled", "planning_swarm_enabled", "planningswarm", "swarm" -> {
                when (value.trim().lowercase()) {
                    "on", "true", "enabled", "1", "yes" -> settings.copy(planningSwarmEnabled = true)
                    "off", "false", "disabled", "0", "no" -> settings.copy(planningSwarmEnabled = false)
                    else -> invalid(settings)
                }
            }
            "ragenabled", "rag_enabled", "rag" -> {
                when (value.trim().lowercase()) {
                    "on", "true", "enabled", "1", "yes" -> settings.copy(ragEnabled = true)
                    "off", "false", "disabled", "0", "no" -> settings.copy(ragEnabled = false)
                    else -> invalid(settings)
                }
            }
            "ragollamaurl", "rag_ollama_url" -> {
                if (!isValidUrl(value)) invalid(settings) else settings.copy(ragOllamaUrl = value.trim().trimEnd('/'))
            }
            "ragembeddingmodel", "rag_embedding_model", "embedmodel" -> {
                val model = value.trim()
                if (model.isBlank() || model.equals("index", ignoreCase = true)) {
                    settings.copy(ragEmbeddingModel = null)
                } else {
                    settings.copy(ragEmbeddingModel = model)
                }
            }
            "ragsearchtopk", "rag_search_top_k", "searchtopk" -> {
                val topK = value.toIntOrNull()
                if (topK == null || topK <= 0) invalid(settings) else settings.copy(ragSearchTopK = topK.coerceAtLeast(settings.ragTopK))
            }
            "ragtopk", "rag_top_k" -> {
                val topK = value.toIntOrNull()
                if (topK == null || topK <= 0) {
                    invalid(settings)
                } else {
                    settings.copy(ragTopK = topK, ragSearchTopK = settings.ragSearchTopK.coerceAtLeast(topK))
                }
            }
            "systemprompt", "prompt" -> {
                val prompt = value.trim()
                when {
                    prompt.equals("default", ignoreCase = true) || prompt.equals("reset", ignoreCase = true) ->
                        settings.copy(
                            systemPrompt = formatting.ConsoleSystemPrompt.value,
                            systemPromptOverridden = false
                        )
                    prompt.isBlank() -> invalid(settings)
                    else -> settings.copy(systemPrompt = prompt, systemPromptOverridden = true)
                }
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
