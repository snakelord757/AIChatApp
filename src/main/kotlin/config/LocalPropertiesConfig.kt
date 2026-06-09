package config

import agent.AgentSettings
import formatting.ConsoleSystemPrompt
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object LocalPropertiesConfig {
    private val configPath: Path = Path.of("local.properties").toAbsolutePath().normalize()

    sealed class Result {
        data class Success(val settings: AgentSettings) : Result()
        data class Failure(val message: String, val fallbackSettings: AgentSettings) : Result()
    }

    fun load(): Result {
        val fallback = AgentSettings(
            apiKey = "",
            systemPrompt = ConsoleSystemPrompt.value
        )

        if (!Files.exists(configPath)) {
            return Result.Failure(missingMessage("Файл local.properties не найден."), fallback)
        }

        val properties = Properties()
        Files.newInputStream(configPath).use(properties::load)

        val apiKey = properties.getProperty("DEEPSEEK_API_KEY")?.trim().orEmpty()
        if (apiKey.isBlank() || apiKey == "your_api_key_here") {
            return Result.Failure(missingMessage("В local.properties не задан обязательный DEEPSEEK_API_KEY."), fallback)
        }

        val baseUrl = properties.getProperty("DEEPSEEK_BASE_URL")?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback.baseUrl
        val model = properties.getProperty("DEEPSEEK_MODEL")?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback.model

        if (!isValidUrl(baseUrl)) {
            return Result.Failure(
                missingMessage("В local.properties задан некорректный DEEPSEEK_BASE_URL: $baseUrl"),
                fallback
            )
        }

        return Result.Success(
            fallback.copy(
                apiKey = apiKey,
                baseUrl = baseUrl.trimEnd('/'),
                model = model
            )
        )
    }

    private fun isValidUrl(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun missingMessage(reason: String): String = """
        $reason
        Ожидаемый путь: $configPath
        Обязательное свойство: DEEPSEEK_API_KEY
        Пример формата:
        DEEPSEEK_API_KEY=your_api_key_here
        DEEPSEEK_BASE_URL=https://api.deepseek.com
        DEEPSEEK_MODEL=deepseek-chat
    """.trimIndent()
}
