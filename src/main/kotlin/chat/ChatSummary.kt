package chat

data class ChatSummary(
    val content: String,
    val lastMessageIndex: Int,
    val usage: TokenUsage? = null
)

data class ChatHistoryState(
    val messages: List<ChatMessage> = emptyList(),
    val summary: ChatSummary? = null
)

internal fun summaryUsageMessage(usage: TokenUsage?): String {
    val safeUsage = usage ?: TokenUsage.ZERO
    return "Chat summarization completed. Summary request tokens: input=${safeUsage.inputTokens}, output=${safeUsage.outputTokens}, reasoning=${safeUsage.reasoningTokens}, total=${safeUsage.totalTokens}"
}
