package chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatHistoryRepositoryTest {
    @Test
    fun `new repository starts with system prompt without persisting`() {
        val persisted = mutableListOf<ChatHistoryState>()

        val repository = ChatHistoryRepository(
            systemPrompt = "system",
            onChanged = persisted::add
        )

        assertEquals(listOf(ChatMessage(Role.SYSTEM, "system")), repository.all())
        assertEquals(emptyList(), persisted)
    }

    @Test
    fun `clear keeps system prompt in memory and writes empty history`() {
        val persisted = mutableListOf<ChatHistoryState>()
        val repository = ChatHistoryRepository(
            systemPrompt = "system",
            onChanged = persisted::add
        )

        repository.addUser("hello")
        repository.clear("system")

        assertEquals(listOf(ChatMessage(Role.SYSTEM, "system")), repository.all())
        assertEquals(ChatHistoryState(), persisted.last())
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

    @Test
    fun `api context uses summary and only messages after summary`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("old")
        repository.addAssistant("old answer")
        repository.saveSummary("compressed old dialog", TokenUsage(inputTokens = 1, outputTokens = 2))
        repository.addUser("new")

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\ncompressed old dialog"),
                ChatMessage(Role.USER, "new")
            ),
            repository.apiContextMessages()
        )
    }

    @Test
    fun `summary usage event is restored in display history but not sent to api`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("old")
        repository.saveSummary("compressed old dialog", TokenUsage(inputTokens = 1, outputTokens = 2, reasoningTokens = 3))
        repository.addUser("new")

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.USER, "old"),
                ChatMessage(
                    Role.EVENT,
                    "Сжатие диалога выполнено. Токены summary-запроса: ввод=1, вывод=2, размышление=3, всего=6"
                ),
                ChatMessage(Role.USER, "new")
            ),
            repository.all()
        )
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\ncompressed old dialog"),
                ChatMessage(Role.USER, "new")
            ),
            repository.apiContextMessages()
        )
    }

    @Test
    fun `summary can include triggering user message while api keeps it explicit`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("old")
        repository.addAssistant("old answer")
        repository.addUser("trigger request")

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.USER, "old"),
                ChatMessage(Role.ASSISTANT, "old answer"),
                ChatMessage(Role.USER, "trigger request")
            ),
            repository.summarySourceMessages()
        )

        repository.saveSummary(
            content = "compressed old dialog including trigger request",
            usage = TokenUsage(inputTokens = 1),
            lastMessageIndex = repository.indexBeforeLatestUserMessage()
        )

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\ncompressed old dialog including trigger request"),
                ChatMessage(Role.USER, "trigger request")
            ),
            repository.apiContextMessages()
        )
    }

    @Test
    fun `restored summary does not replace full history for display`() {
        val messages = listOf(
            ChatMessage(Role.SYSTEM, "system"),
            ChatMessage(Role.USER, "old"),
            ChatMessage(Role.ASSISTANT, "old answer"),
            ChatMessage(Role.USER, "new")
        )
        val repository = ChatHistoryRepository(
            systemPrompt = "system",
            restoredMessages = messages,
            restoredSummary = ChatSummary("compressed old dialog", lastMessageIndex = 2)
        )

        assertEquals(messages, repository.all())
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\ncompressed old dialog"),
                ChatMessage(Role.USER, "new")
            ),
            repository.apiContextMessages()
        )
    }

    @Test
    fun `summary trigger fires after interval messages beyond summary`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")

        repository.addUser("one")
        repository.addAssistant("two")
        assertEquals(false, repository.shouldCreateSummary(interval = 2))

        repository.addUser("three")
        assertEquals(true, repository.shouldCreateSummary(interval = 2))
    }

    @Test
    fun `total usage includes summary usage`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addAssistant("answer", TokenUsage(outputTokens = 3))
        repository.saveSummary("summary", TokenUsage(inputTokens = 4, outputTokens = 5, reasoningTokens = 6))

        assertEquals(TokenUsage(inputTokens = 4, outputTokens = 8, reasoningTokens = 6), repository.totalUsage())
    }
}
