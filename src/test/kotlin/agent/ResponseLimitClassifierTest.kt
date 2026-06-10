package agent

import chat.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponseLimitClassifierTest {
    @Test
    fun `returns null when finish reason is not length`() {
        val reason = ResponseLimitClassifier.classify(
            finishReason = "stop",
            settings = AgentSettings(apiKey = "", systemPrompt = "system"),
            usage = TokenUsage(inputTokens = 100, outputTokens = 50)
        )

        assertNull(reason)
    }

    @Test
    fun `detects configured max tokens limit`() {
        val reason = ResponseLimitClassifier.classify(
            finishReason = "length",
            settings = AgentSettings(apiKey = "", maxTokens = 256, systemPrompt = "system"),
            usage = TokenUsage(inputTokens = 100, outputTokens = 256)
        )

        assertEquals(ResponseLimitReason.REQUEST_MAX_TOKENS, reason)
    }

    @Test
    fun `detects server default output limit when max tokens is not sent`() {
        val reason = ResponseLimitClassifier.classify(
            finishReason = "length",
            settings = AgentSettings(apiKey = "", maxTokens = 0, systemPrompt = "system"),
            usage = TokenUsage(inputTokens = 100, outputTokens = 8_192)
        )

        assertEquals(ResponseLimitReason.SERVER_DEFAULT_OUTPUT_LIMIT, reason)
    }

    @Test
    fun `detects model context window limit`() {
        val reason = ResponseLimitClassifier.classify(
            finishReason = "length",
            settings = AgentSettings(apiKey = "", maxTokens = 0, systemPrompt = "system"),
            usage = TokenUsage(inputTokens = 999_000, outputTokens = 1_000)
        )

        assertEquals(ResponseLimitReason.MODEL_CONTEXT_WINDOW, reason)
    }

    @Test
    fun `detects model max output limit`() {
        val reason = ResponseLimitClassifier.classify(
            finishReason = "length",
            settings = AgentSettings(apiKey = "", maxTokens = 0, systemPrompt = "system"),
            usage = TokenUsage(inputTokens = 100, outputTokens = 384_000)
        )

        assertEquals(ResponseLimitReason.MODEL_MAX_OUTPUT, reason)
    }

    @Test
    fun `falls back to unknown length limit`() {
        val reason = ResponseLimitClassifier.classify(
            finishReason = "length",
            settings = AgentSettings(apiKey = "", maxTokens = 0, systemPrompt = "system"),
            usage = TokenUsage(inputTokens = 100, outputTokens = 500)
        )

        assertEquals(ResponseLimitReason.UNKNOWN_LENGTH_LIMIT, reason)
    }
}
