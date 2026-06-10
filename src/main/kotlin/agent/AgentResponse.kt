package agent

import chat.TokenUsage

data class AgentResponse(
    val content: String,
    val usage: TokenUsage = TokenUsage.ZERO
)
