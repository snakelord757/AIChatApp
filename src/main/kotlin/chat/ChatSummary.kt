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
    return "Сжатие диалога выполнено. Токены summary-запроса: ввод=${safeUsage.inputTokens}, вывод=${safeUsage.outputTokens}, размышление=${safeUsage.reasoningTokens}, всего=${safeUsage.totalTokens}"
}
