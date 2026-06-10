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
}
