package chat

class ChatHistoryRepository(
    systemPrompt: String,
    restoredMessages: List<ChatMessage> = emptyList(),
    restoredSummary: ChatSummary? = null,
    private val onChanged: (ChatHistoryState) -> Unit = {}
) {
    private val messages = mutableListOf<ChatMessage>()
    private var summary: ChatSummary? = restoredSummary

    init {
        if (restoredMessages.isEmpty()) {
            messages += ChatMessage(Role.SYSTEM, systemPrompt)
        } else {
            messages += restoredMessages
        }
    }

    fun all(): List<ChatMessage> = messages.toList()

    fun state(): ChatHistoryState = ChatHistoryState(messages.toList(), summary)

    fun addUser(content: String) {
        messages += ChatMessage(Role.USER, content)
        persist()
    }

    fun addAssistant(content: String, usage: TokenUsage? = null) {
        messages += ChatMessage(Role.ASSISTANT, content, usage)
        persist()
    }

    fun totalUsage(): TokenUsage =
        (messages.mapNotNull { it.usage } + listOfNotNull(summary?.usage))
            .fold(TokenUsage.ZERO) { total, usage -> total + usage }

    fun shouldCreateSummary(interval: Int): Boolean {
        if (interval <= 0) return false
        val summaryIndex = summary?.lastMessageIndex ?: firstDialogMessageIndex()
        return messages.countAfter(summaryIndex) > interval
    }

    fun summarySourceMessages(): List<ChatMessage> {
        val currentSummary = summary
        if (currentSummary == null) return messages.apiMessages()

        return buildList {
            addSystemPromptIfPresent()
            add(summaryMessage(currentSummary.content))
            addAll(messages.drop(currentSummary.lastMessageIndex + 1).apiMessages())
        }
    }

    fun apiContextMessages(): List<ChatMessage> {
        val currentSummary = summary ?: return messages.apiMessages()
        return buildList {
            addSystemPromptIfPresent()
            add(summaryMessage(currentSummary.content))
            addAll(messages.drop(currentSummary.lastMessageIndex + 1).apiMessages())
        }
    }

    fun saveSummary(content: String, usage: TokenUsage?) {
        summary = ChatSummary(
            content = content,
            lastMessageIndex = messages.lastIndex,
            usage = usage
        )
        messages += ChatMessage(Role.EVENT, summaryUsageMessage(usage))
        persist()
    }

    fun clear(systemPrompt: String) {
        messages.clear()
        messages += ChatMessage(Role.SYSTEM, systemPrompt)
        summary = null
        onChanged(ChatHistoryState())
    }

    fun updateSystemPrompt(systemPrompt: String) {
        if (messages.firstOrNull()?.role == Role.SYSTEM) {
            messages[0] = ChatMessage(Role.SYSTEM, systemPrompt)
        } else {
            messages.add(0, ChatMessage(Role.SYSTEM, systemPrompt))
        }
        persist()
    }

    private fun persist() {
        onChanged(state())
    }

    private fun MutableList<ChatMessage>.addSystemPromptIfPresent() {
        messages.firstOrNull { it.role == Role.SYSTEM }?.let(::add)
    }

    private fun summaryMessage(content: String): ChatMessage =
        ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\n$content")

    private fun firstDialogMessageIndex(): Int =
        if (messages.firstOrNull()?.role == Role.SYSTEM) 0 else -1

    private fun List<ChatMessage>.countAfter(index: Int): Int =
        drop(index + 1).count { it.role == Role.USER || it.role == Role.ASSISTANT }

    private fun List<ChatMessage>.apiMessages(): List<ChatMessage> =
        filter { it.role == Role.SYSTEM || it.role == Role.USER || it.role == Role.ASSISTANT }
}

fun ChatHistoryRepository(
    systemPrompt: String,
    restoredState: ChatHistoryState,
    onChanged: (ChatHistoryState) -> Unit = {}
): ChatHistoryRepository {
    return ChatHistoryRepository(
        systemPrompt = systemPrompt,
        restoredMessages = restoredState.messages,
        restoredSummary = restoredState.summary,
        onChanged = onChanged
    )
}
