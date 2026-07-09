package agent

import chat.TokenUsage

object ResponseLimitClassifier {
    private const val DEFAULT_MAX_OUTPUT = 384_000L
    private const val OBSERVED_SERVER_DEFAULT_OUTPUT_LIMIT = 8_192L

    fun classify(
        finishReason: String?,
        settings: AgentSettings,
        usage: TokenUsage
    ): ResponseLimitReason? {
        val generatedTokens = usage.outputTokens + usage.reasoningTokens
        return when {
            settings.maxTokens > 0 && generatedTokens >= settings.maxTokens ->
                ResponseLimitReason.REQUEST_MAX_TOKENS
            settings.modelContextWindowTokens > 0 && usage.totalTokens >= settings.modelContextWindowTokens ->
                ResponseLimitReason.MODEL_CONTEXT_WINDOW
            generatedTokens >= DEFAULT_MAX_OUTPUT ->
                ResponseLimitReason.MODEL_MAX_OUTPUT
            settings.maxTokens <= 0 && generatedTokens >= OBSERVED_SERVER_DEFAULT_OUTPUT_LIMIT ->
                ResponseLimitReason.SERVER_DEFAULT_OUTPUT_LIMIT
            finishReason != "length" ->
                null
            else ->
                ResponseLimitReason.UNKNOWN_LENGTH_LIMIT
        }
    }
}
