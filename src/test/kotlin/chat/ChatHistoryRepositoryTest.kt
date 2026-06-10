package chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatHistoryRepositoryTest {
    @Test
    fun `new repository starts with system prompt without persisting`() {
        val persisted = mutableListOf<List<ChatMessage>>()

        val repository = ChatHistoryRepository(
            systemPrompt = "system",
            onChanged = persisted::add
        )

        assertEquals(listOf(ChatMessage(Role.SYSTEM, "system")), repository.all())
        assertEquals(emptyList(), persisted)
    }

    @Test
    fun `clear keeps system prompt in memory and writes empty history`() {
        val persisted = mutableListOf<List<ChatMessage>>()
        val repository = ChatHistoryRepository(
            systemPrompt = "system",
            onChanged = persisted::add
        )

        repository.addUser("hello")
        repository.clear("system")

        assertEquals(listOf(ChatMessage(Role.SYSTEM, "system")), repository.all())
        assertEquals(emptyList(), persisted.last())
    }

    @Test
    fun `total usage includes restored history`() {
        val repository = ChatHistoryRepository(
            systemPrompt = "system",
            restoredMessages = listOf(
                ChatMessage(Role.ASSISTANT, "old", TokenUsage(inputTokens = 1, outputTokens = 2, reasoningTokens = 3)),
                ChatMessage(Role.ASSISTANT, "new", TokenUsage(inputTokens = 4, outputTokens = 5, reasoningTokens = 6))
            )
        )

        assertEquals(TokenUsage(inputTokens = 5, outputTokens = 7, reasoningTokens = 9), repository.totalUsage())
    }
}
