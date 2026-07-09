package config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LocalPropertiesConfigTest {
    @Test
    fun `summary interval zero and model context window are loaded from local properties`() {
        val path = Path.of("local.properties").toAbsolutePath().normalize()
        val hadOriginal = Files.exists(path)
        val original = if (hadOriginal) Files.readString(path, StandardCharsets.UTF_8) else null
        try {
            Files.writeString(
                path,
                """
                MODEL_API_KEY=test-key
                MODEL_BASE_URL=http://localhost:11434/v1
                MODEL_NAME=llama3.1
                AI_CHAT_SUMMARY_INTERVAL=0
                MODEL_CONTEXT_WINDOW_TOKENS=32768
                """.trimIndent(),
                StandardCharsets.UTF_8
            )

            val result = LocalPropertiesConfig.load()
            val success = assertIs<LocalPropertiesConfig.Result.Success>(result)
            assertEquals(0, success.settings.summaryInterval)
            assertEquals(32768L, success.settings.modelContextWindowTokens)
            assertEquals("llama3.1", success.settings.model)
            assertEquals(listOf("llama3.1"), success.settings.availableModels)
            assertEquals(false, success.settings.allowClarifyingQuestions)
            assertEquals(false, success.settings.planningSwarmEnabled)
        } finally {
            if (hadOriginal) {
                Files.writeString(path, original, StandardCharsets.UTF_8)
            } else {
                Files.deleteIfExists(path)
            }
        }
    }

    @Test
    fun `clarifying questions setting is loaded from local properties`() {
        val path = Path.of("local.properties").toAbsolutePath().normalize()
        val hadOriginal = Files.exists(path)
        val original = if (hadOriginal) Files.readString(path, StandardCharsets.UTF_8) else null
        try {
            Files.writeString(
                path,
                """
                MODEL_BASE_URL=http://localhost:11434/v1
                MODEL_NAME=llama3.1
                AI_CHAT_ALLOW_CLARIFYING_QUESTIONS=true
                """.trimIndent(),
                StandardCharsets.UTF_8
            )

            val result = LocalPropertiesConfig.load()
            val success = assertIs<LocalPropertiesConfig.Result.Success>(result)
            assertEquals(true, success.settings.allowClarifyingQuestions)
        } finally {
            if (hadOriginal) {
                Files.writeString(path, original, StandardCharsets.UTF_8)
            } else {
                Files.deleteIfExists(path)
            }
        }
    }

    @Test
    fun `planning swarm setting is loaded from local properties`() {
        val path = Path.of("local.properties").toAbsolutePath().normalize()
        val hadOriginal = Files.exists(path)
        val original = if (hadOriginal) Files.readString(path, StandardCharsets.UTF_8) else null
        try {
            Files.writeString(
                path,
                """
                MODEL_BASE_URL=http://localhost:11434/v1
                MODEL_NAME=llama3.1
                AI_CHAT_PLANNING_SWARM_ENABLED=true
                """.trimIndent(),
                StandardCharsets.UTF_8
            )

            val result = LocalPropertiesConfig.load()
            val success = assertIs<LocalPropertiesConfig.Result.Success>(result)
            assertEquals(true, success.settings.planningSwarmEnabled)
        } finally {
            if (hadOriginal) {
                Files.writeString(path, original, StandardCharsets.UTF_8)
            } else {
                Files.deleteIfExists(path)
            }
        }
    }
}
