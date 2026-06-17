package config

import chat.ContextStrategy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LocalPropertiesConfigTest {
    @Test
    fun `summary interval zero and context settings are loaded from local properties`() {
        val path = Path.of("local.properties").toAbsolutePath().normalize()
        val hadOriginal = Files.exists(path)
        val original = if (hadOriginal) Files.readString(path, StandardCharsets.UTF_8) else null
        try {
            Files.writeString(
                path,
                """
                DEEPSEEK_API_KEY=test-key
                DEEPSEEK_BASE_URL=https://api.deepseek.com
                DEEPSEEK_MODEL=deepseek-v4-flash
                AI_CHAT_SUMMARY_INTERVAL=0
                AI_CHAT_CONTEXT_STRATEGY=facts
                AI_CHAT_CONTEXT_WINDOW_MESSAGES=9
                """.trimIndent(),
                StandardCharsets.UTF_8
            )

            val result = LocalPropertiesConfig.load()
            val success = assertIs<LocalPropertiesConfig.Result.Success>(result)
            assertEquals(0, success.settings.summaryInterval)
            assertEquals(ContextStrategy.STICKY_FACTS, success.settings.contextStrategy)
            assertEquals(9, success.settings.contextWindowMessages)
            assertEquals(false, success.settings.allowClarifyingQuestions)
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
                DEEPSEEK_API_KEY=test-key
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
}
