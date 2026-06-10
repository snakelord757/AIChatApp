package chat

class ChatHistoryRepository(
    systemPrompt: String,
    restoredMessages: List<ChatMessage> = emptyList(),
    private val onChanged: (List<ChatMessage>) -> Unit = {}
) {
    private val messages = mutableListOf<ChatMessage>()

    init {
        if (restoredMessages.isEmpty()) {
            messages += ChatMessage(Role.SYSTEM, systemPrompt)
        } else {
            messages += restoredMessages
        }
    }

    fun all(): List<ChatMessage> = messages.toList()

    fun addUser(content: String) {
        messages += ChatMessage(Role.USER, content)
        persist()
    }

    fun addAssistant(content: String, usage: TokenUsage? = null) {
        messages += ChatMessage(Role.ASSISTANT, content, usage)
        persist()
    }

    fun totalUsage(): TokenUsage =
        messages.mapNotNull { it.usage }.fold(TokenUsage.ZERO) { total, usage -> total + usage }

    fun clear(systemPrompt: String) {
        messages.clear()
        messages += ChatMessage(Role.SYSTEM, systemPrompt)
        onChanged(emptyList())
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
        onChanged(messages)
    }
}
