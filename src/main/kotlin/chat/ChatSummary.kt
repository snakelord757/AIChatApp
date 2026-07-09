package chat

data class ChatSummary(
    val content: String,
    val lastMessageIndex: Int,
    val usage: TokenUsage? = null
)

data class ChatHistoryState(
    val messages: List<ChatMessage> = emptyList(),
    val summary: ChatSummary? = null,
    val facts: Map<String, String> = emptyMap(),
    val factsUsage: TokenUsage = TokenUsage.ZERO,
    val lastFactsUsage: TokenUsage? = null,
    val branches: List<ChatBranch> = emptyList(),
    val activeBranchId: String? = null,
    val checkpoint: ChatCheckpoint? = null
)

data class ChatBranch(
    val id: String,
    val name: String,
    val messages: List<ChatMessage>,
    val summary: ChatSummary? = null,
    val facts: Map<String, String> = emptyMap(),
    val factsUsage: TokenUsage = TokenUsage.ZERO,
    val lastFactsUsage: TokenUsage? = null
)

data class ChatCheckpoint(
    val messages: List<ChatMessage>,
    val summary: ChatSummary? = null,
    val facts: Map<String, String> = emptyMap(),
    val factsUsage: TokenUsage = TokenUsage.ZERO,
    val lastFactsUsage: TokenUsage? = null
)

internal fun summaryUsageMessage(usage: TokenUsage?): String {
    val safeUsage = usage ?: TokenUsage.ZERO
    return "Chat summarization completed. Summary request tokens: input=${safeUsage.inputTokens}, output=${safeUsage.outputTokens}, reasoning=${safeUsage.reasoningTokens}, total=${safeUsage.totalTokens}"
}

internal fun factsUsageMessage(usage: TokenUsage?): String {
    val safeUsage = usage ?: TokenUsage.ZERO
    return "Sticky facts update completed. Facts usage: input=${safeUsage.inputTokens}, output=${safeUsage.outputTokens}, reasoning=${safeUsage.reasoningTokens}, total=${safeUsage.totalTokens}"
}
