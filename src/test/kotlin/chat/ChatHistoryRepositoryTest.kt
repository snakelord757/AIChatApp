package chat

import agent.AgentSettings
import kotlin.test.Test
import kotlin.test.assertContains
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
                    "Chat summarization completed. Summary request tokens: input=1, output=2, reasoning=3, total=6"
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
    fun `summary trigger is disabled when interval is zero`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")

        repository.addUser("one")
        repository.addAssistant("two")
        repository.addUser("three")

        assertEquals(false, repository.shouldCreateSummary(interval = 0))
    }

    @Test
    fun `sliding window strategy sends only last dialog messages plus system prompt`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("one")
        repository.addAssistant("two")
        repository.addUser("three")

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.ASSISTANT, "two"),
                ChatMessage(Role.USER, "three")
            ),
            repository.apiContextMessages(
                AgentSettings(
                    apiKey = "",
                    contextStrategy = ContextStrategy.SLIDING_WINDOW,
                    contextWindowMessages = 2,
                    systemPrompt = "system"
                )
            )
        )
    }

    @Test
    fun `sliding window strategy includes summary as base context when available`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("old")
        repository.addAssistant("old answer")
        repository.saveSummary("compressed old dialog", TokenUsage.ZERO)
        repository.addUser("new")

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\ncompressed old dialog"),
                ChatMessage(Role.USER, "new")
            ),
            repository.apiContextMessages(
                AgentSettings(
                    apiKey = "",
                    contextStrategy = ContextStrategy.SLIDING_WINDOW,
                    contextWindowMessages = 4,
                    systemPrompt = "system"
                )
            )
        )
    }

    @Test
    fun `sticky facts strategy sends facts block plus sliding dialog window`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("goal: ship context strategies")
        repository.addAssistant("noted")
        repository.addUser("latest")

        val context = repository.apiContextMessages(
            AgentSettings(
                apiKey = "",
                contextStrategy = ContextStrategy.STICKY_FACTS,
                contextWindowMessages = 1,
                systemPrompt = "system"
            )
        )

        assertEquals(Role.SYSTEM, context[0].role)
        assertEquals(Role.SYSTEM, context[1].role)
        assertContains(context[1].content, "Sticky facts:")
        assertContains(context[1].content, "goal: ship context strategies")
        assertEquals(listOf(ChatMessage(Role.USER, "latest")), context.drop(2))
    }

    @Test
    fun `sticky facts strategy includes summary before facts when available`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("goal: ship context strategies")
        repository.saveSummary("compressed old dialog", TokenUsage.ZERO)
        repository.addUser("latest")

        val context = repository.apiContextMessages(
            AgentSettings(
                apiKey = "",
                contextStrategy = ContextStrategy.STICKY_FACTS,
                contextWindowMessages = 1,
                systemPrompt = "system"
            )
        )

        assertEquals(ChatMessage(Role.SYSTEM, "system"), context[0])
        assertEquals(ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\ncompressed old dialog"), context[1])
        assertContains(context[2].content, "Sticky facts:")
        assertEquals(ChatMessage(Role.USER, "latest"), context[3])
    }

    @Test
    fun `facts are updated from explicit english and russian markers`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")

        repository.addUser("goal: finish tests")
        repository.addUser("\u043f\u0440\u0435\u0434\u043f\u043e\u0447\u0438\u0442\u0430\u044e: \u043a\u0440\u0430\u0442\u043a\u043e")

        assertEquals(
            mapOf(
                "goal" to "finish tests",
                "preferences" to "\u043a\u0440\u0430\u0442\u043a\u043e"
            ),
            repository.facts()
        )
    }

    @Test
    fun `branches from checkpoint diverge independently and switch active context`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("base")
        repository.checkpoint()

        assertEquals(true, repository.createBranch("alpha"))
        repository.addUser("alpha message")

        assertEquals(true, repository.createBranch("beta"))
        repository.addUser("beta message")

        assertEquals(true, repository.switchBranch("alpha"))
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.USER, "base"),
                ChatMessage(Role.USER, "alpha message")
            ),
            repository.apiContextMessages(
                AgentSettings(
                    apiKey = "",
                    contextStrategy = ContextStrategy.BRANCHING,
                    systemPrompt = "system"
                )
            )
        )

        assertEquals(true, repository.switchBranch("beta"))
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.USER, "base"),
                ChatMessage(Role.USER, "beta message")
            ),
            repository.all()
        )
    }

    @Test
    fun `total usage includes summary usage`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addAssistant("answer", TokenUsage(outputTokens = 3))
        repository.saveSummary("summary", TokenUsage(inputTokens = 4, outputTokens = 5, reasoningTokens = 6))

        assertEquals(TokenUsage(inputTokens = 4, outputTokens = 8, reasoningTokens = 6), repository.totalUsage())
    }
}
