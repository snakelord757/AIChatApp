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

        val apiKey = properties.getProperty("DEEPSEEK_API_KEY")?.trim().orEmpty()
        if (apiKey.isBlank() || apiKey == "your_api_key_here") {
            return Result.Failure(
                missingMessage("local.properties does not define the required DEEPSEEK_API_KEY."),
                fallback,
                pricing,
                pricingWarning
            )
        }

        val baseUrl = properties.getProperty("DEEPSEEK_BASE_URL")?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback.baseUrl
        val model = properties.getProperty("DEEPSEEK_MODEL")?.trim()?.takeIf { it.isNotBlank() }
            ?.takeIf { it in AgentSettings.supportedModels }
            ?: AgentSettings.defaultModel
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

        if (!isValidUrl(baseUrl)) {
            return Result.Failure(
                missingMessage("local.properties defines an invalid DEEPSEEK_BASE_URL: $baseUrl"),
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
                summaryInterval = summaryInterval,
                contextStrategy = contextStrategy,
                contextWindowMessages = contextWindowMessages,
                allowClarifyingQuestions = allowClarifyingQuestions
            ),
            pricing,
            pricingWarning
        )
    }

    private fun parsePricing(properties: Properties): TokenPricing? {
        val unified = properties.getUsd("DEEPSEEK_TOKEN_PRICE_PER_1M_USD")
        if (unified != null) {
            return TokenPricing(unified, unified, unified)
        }

        val input = properties.getUsd("DEEPSEEK_INPUT_PRICE_PER_1M_USD")
        val output = properties.getUsd("DEEPSEEK_OUTPUT_PRICE_PER_1M_USD")
        val reasoning = properties.getUsd("DEEPSEEK_REASONING_PRICE_PER_1M_USD") ?: output
        return if (input != null && output != null && reasoning != null) {
            TokenPricing(input, output, reasoning)
        } else {
            null
        }
    }

    private fun Properties.getUsd(key: String): Double? =
        getProperty(key)?.trim()?.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it >= 0.0 }

    private fun pricingWarning(): String =
        "Token pricing is not configured in local.properties. Add DEEPSEEK_TOKEN_PRICE_PER_1M_USD or DEEPSEEK_INPUT_PRICE_PER_1M_USD/DEEPSEEK_OUTPUT_PRICE_PER_1M_USD."

    private fun isValidUrl(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun missingMessage(reason: String): String = """
        $reason
        Expected path: $configPath
        Required property: DEEPSEEK_API_KEY
        Example format:
        DEEPSEEK_API_KEY=your_api_key_here
        DEEPSEEK_BASE_URL=https://api.deepseek.com
        DEEPSEEK_MODEL=deepseek-v4-flash
    """.trimIndent()
}
