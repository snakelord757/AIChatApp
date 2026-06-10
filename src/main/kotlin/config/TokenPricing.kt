package config

import chat.TokenUsage

data class TokenPricing(
    val inputPerMillionUsd: Double,
    val outputPerMillionUsd: Double,
    val reasoningPerMillionUsd: Double
) {
    fun costUsd(usage: TokenUsage): Double =
        usage.inputTokens * inputPerMillionUsd / 1_000_000.0 +
            usage.outputTokens * outputPerMillionUsd / 1_000_000.0 +
            usage.reasoningTokens * reasoningPerMillionUsd / 1_000_000.0
}
