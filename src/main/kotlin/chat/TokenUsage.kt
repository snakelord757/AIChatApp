package chat

data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0
) {
    val totalTokens: Long
        get() = inputTokens + outputTokens + reasoningTokens

    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens
    )

    companion object {
        val ZERO = TokenUsage()
    }
}
