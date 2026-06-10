package agent

import chat.TokenUsage

data class AgentResponse(
    val content: String,
    val usage: TokenUsage = TokenUsage.ZERO,
    val finishReason: String? = null,
    val limitReason: ResponseLimitReason? = null
) {
    val wasLimited: Boolean
        get() = limitReason != null
}
