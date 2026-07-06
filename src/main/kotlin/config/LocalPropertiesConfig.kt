package config

import agent.AgentSettings
import chat.ContextStrategy
import formatting.ConsoleSystemPrompt
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object LocalPropertiesConfig {
    private val configPath: Path = Path.of("local.properties").toAbsolutePath().normalize()

    sealed class Result {
        data class Success(
            val settings: AgentSettings,
            val pricing: TokenPricing?,
            val pricingWarning: String?
        ) : Result()

        data class Failure(
            val message: String,
            val fallbackSettings: AgentSettings,
            val pricing: TokenPricing?,
            val pricingWarning: String?
        ) : Result()
    }

    fun load(): Result {
        val fallback = AgentSettings(
            apiKey = "",
            systemPrompt = ConsoleSystemPrompt.value
        )

        if (!Files.exists(configPath)) {
            return Result.Failure(
                missingMessage("local.properties was not found."),
                fallback,
                pricing = null,
                pricingWarning = pricingWarning()
            )
        }

        val properties = Properties()
        Files.newInputStream(configPath).use(properties::load)
        val pricing = parsePricing(properties)
        val pricingWarning = if (pricing == null) pricingWarning() else null

        val hasProviderConfig = properties.hasAny(
            "MODEL_BASE_URL",
            "MODEL_NAME",
            "MODEL",
            "MODEL_API_KEY",
            "DEEPSEEK_BASE_URL",
            "DEEPSEEK_MODEL",
            "DEEPSEEK_API_KEY"
        )
        if (!hasProviderConfig) {
            return Result.Failure(
                missingMessage("local.properties does not define model provider settings."),
                fallback,
                pricing,
                pricingWarning
            )
        }

        val apiKey = properties.getFirst("MODEL_API_KEY", "DEEPSEEK_API_KEY")
            ?.takeIf { it !in setOf("your_api_key_here", "your_model_api_key_here") }
            .orEmpty()
        val baseUrl = properties.getFirst("MODEL_BASE_URL", "DEEPSEEK_BASE_URL")
            ?: if (properties.getFirst("DEEPSEEK_API_KEY", "DEEPSEEK_MODEL") != null) "https://api.deepseek.com" else fallback.baseUrl
        val model = properties.getFirst("MODEL_NAME", "MODEL", "DEEPSEEK_MODEL").orEmpty()
        val availableModels = listOfNotNull(model.takeIf { it.isNotBlank() })
        val summaryInterval = properties.getProperty("AI_CHAT_SUMMARY_INTERVAL")
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: fallback.summaryInterval
        val contextStrategy = properties.getProperty("AI_CHAT_CONTEXT_STRATEGY")
            ?.let(ContextStrategy::parse)
            ?: fallback.contextStrategy
        val contextWindowMessages = properties.getProperty("AI_CHAT_CONTEXT_WINDOW_MESSAGES")
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: fallback.contextWindowMessages
        val allowClarifyingQuestions = properties.getProperty("AI_CHAT_ALLOW_CLARIFYING_QUESTIONS")
            ?.trim()
            ?.lowercase()
            ?.let { it == "true" || it == "1" || it == "yes" }
            ?: fallback.allowClarifyingQuestions
        val planningSwarmEnabled = properties.getProperty("AI_CHAT_PLANNING_SWARM_ENABLED")
            ?.trim()
            ?.lowercase()
            ?.let { it == "true" || it == "1" || it == "yes" }
            ?: fallback.planningSwarmEnabled

        if (!isValidUrl(baseUrl)) {
            return Result.Failure(
                missingMessage("local.properties defines an invalid MODEL_BASE_URL: $baseUrl"),
                fallback,
                pricing,
                pricingWarning
            )
        }

        return Result.Success(
            fallback.copy(
                apiKey = apiKey,
                baseUrl = baseUrl.trimEnd('/'),
                model = model,
                availableModels = availableModels,
                summaryInterval = summaryInterval,
                contextStrategy = contextStrategy,
                contextWindowMessages = contextWindowMessages,
                allowClarifyingQuestions = allowClarifyingQuestions,
                planningSwarmEnabled = planningSwarmEnabled
            ),
            pricing,
            pricingWarning
        )
    }

    private fun parsePricing(properties: Properties): TokenPricing? {
        val unified = properties.getUsd("MODEL_TOKEN_PRICE_PER_1M_USD")
            ?: properties.getUsd("DEEPSEEK_TOKEN_PRICE_PER_1M_USD")
        if (unified != null) {
            return TokenPricing(unified, unified, unified)
        }

        val input = properties.getUsd("MODEL_INPUT_PRICE_PER_1M_USD")
            ?: properties.getUsd("DEEPSEEK_INPUT_PRICE_PER_1M_USD")
        val output = properties.getUsd("MODEL_OUTPUT_PRICE_PER_1M_USD")
            ?: properties.getUsd("DEEPSEEK_OUTPUT_PRICE_PER_1M_USD")
        val reasoning = properties.getUsd("MODEL_REASONING_PRICE_PER_1M_USD")
            ?: properties.getUsd("DEEPSEEK_REASONING_PRICE_PER_1M_USD")
            ?: output
        return if (input != null && output != null && reasoning != null) {
            TokenPricing(input, output, reasoning)
        } else {
            null
        }
    }

    private fun Properties.getUsd(key: String): Double? =
        getProperty(key)?.trim()?.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it >= 0.0 }

    private fun Properties.getFirst(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> getProperty(key)?.trim()?.takeIf { it.isNotBlank() } }

    private fun Properties.hasAny(vararg keys: String): Boolean =
        keys.any { key -> getProperty(key)?.trim()?.isNotBlank() == true }

    private fun pricingWarning(): String =
        "Token pricing is not configured in local.properties. Add MODEL_TOKEN_PRICE_PER_1M_USD or MODEL_INPUT_PRICE_PER_1M_USD/MODEL_OUTPUT_PRICE_PER_1M_USD."

    private fun isValidUrl(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun missingMessage(reason: String): String = """
        $reason
        Expected path: $configPath
        Required property: MODEL_BASE_URL
        Example format:
        MODEL_BASE_URL=http://localhost:11434/v1
        MODEL_NAME=
        MODEL_API_KEY=
    """.trimIndent()
}
