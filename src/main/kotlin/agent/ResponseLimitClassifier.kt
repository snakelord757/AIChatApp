package agent

import chat.TokenUsage

object ResponseLimitClassifier {
    private const val DEEPSEEK_V4_CONTEXT_LENGTH = 1_000_000L
    private const val DEEPSEEK_V4_MAX_OUTPUT = 384_000L
    private const val OBSERVED_SERVER_DEFAULT_OUTPUT_LIMIT = 8_192L

    fun classify(
        finishReason: String?,
        settings: AgentSettings,
        usage: TokenUsage
    ): ResponseLimitReason? {
        if (finishReason != "length") return null

        val generatedTokens = usage.outputTokens + usage.reasoningTokens
        return when {
            settings.maxTokens > 0 && generatedTokens >= settings.maxTokens ->
                ResponseLimitReason.REQUEST_MAX_TOKENS
            usage.totalTokens >= DEEPSEEK_V4_CONTEXT_LENGTH ->
                ResponseLimitReason.MODEL_CONTEXT_WINDOW
            generatedTokens >= DEEPSEEK_V4_MAX_OUTPUT ->
                ResponseLimitReason.MODEL_MAX_OUTPUT
            settings.maxTokens <= 0 && generatedTokens >= OBSERVED_SERVER_DEFAULT_OUTPUT_LIMIT ->
                ResponseLimitReason.SERVER_DEFAULT_OUTPUT_LIMIT
            else ->
                ResponseLimitReason.UNKNOWN_LENGTH_LIMIT
        }
    }
}
