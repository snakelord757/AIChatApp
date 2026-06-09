package chat

class ChatHistoryRepository(systemPrompt: String) {
    private val messages = mutableListOf(ChatMessage(Role.SYSTEM, systemPrompt))

    fun all(): List<ChatMessage> = messages.toList()

    fun addUser(content: String) {
        messages += ChatMessage(Role.USER, content)
    }

    fun addAssistant(content: String) {
        messages += ChatMessage(Role.ASSISTANT, content)
    }

    fun clear(systemPrompt: String) {
        messages.clear()
        messages += ChatMessage(Role.SYSTEM, systemPrompt)
    }

    fun updateSystemPrompt(systemPrompt: String) {
        if (messages.firstOrNull()?.role == Role.SYSTEM) {
            messages[0] = ChatMessage(Role.SYSTEM, systemPrompt)
        } else {
            messages.add(0, ChatMessage(Role.SYSTEM, systemPrompt))
        }
    }
}
