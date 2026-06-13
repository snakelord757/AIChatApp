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
    val branches: List<ChatBranch> = emptyList(),
    val activeBranchId: String? = null,
    val checkpoint: ChatCheckpoint? = null
)

data class ChatBranch(
    val id: String,
    val name: String,
    val messages: List<ChatMessage>
)

data class ChatCheckpoint(
    val messages: List<ChatMessage>
)

internal fun summaryUsageMessage(usage: TokenUsage?): String {
    val safeUsage = usage ?: TokenUsage.ZERO
    return "Chat summarization completed. Summary request tokens: input=${safeUsage.inputTokens}, output=${safeUsage.outputTokens}, reasoning=${safeUsage.reasoningTokens}, total=${safeUsage.totalTokens}"
}
