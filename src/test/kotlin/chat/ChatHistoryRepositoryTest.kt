package chat

import agent.AgentSettings
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun `generated assistant and event blockquotes are not stored as input prompt markers`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")

        repository.addUser("> user text stays untouched")
        repository.addAssistant("Answer\n> **Conclusion:** safe")
        repository.addEvent("Stage EXECUTION: success\nSummary: ok\n\n> detail")

        val user = repository.all().first { it.role == Role.USER }.content
        val assistant = repository.all().first { it.role == Role.ASSISTANT }.content
        val event = repository.all().first { it.role == Role.EVENT }.content

        assertEquals("> user text stays untouched", user)
        assertFalse(assistant.contains("\n> "))
        assertFalse(event.contains("\n> "))
        assertContains(assistant, "Note: **Conclusion:** safe")
        assertContains(event, "Note: detail")
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
    fun `model context window sends recent dialog messages plus system prompt`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("one ".repeat(3_000))
        repository.addAssistant("two ".repeat(3_000))
        repository.addUser("three")

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.USER, "three")
            ),
            repository.apiContextMessages(
                AgentSettings(
                    apiKey = "",
                    modelContextWindowTokens = 1_100,
                    systemPrompt = "system"
                )
            )
        )
    }

    @Test
    fun `sticky facts compaction uses reported model input tokens before the local window is full`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("first")
        repository.addAssistant("answer")
        repository.recordModelInputTokens(1_040)
        repository.addUser("next")

        assertEquals(
            true,
            repository.shouldCompressWithStickyFacts(
                settings = AgentSettings(apiKey = "", modelContextWindowTokens = 1_100, systemPrompt = "system"),
                memoryMessages = emptyList()
            )
        )
    }

    @Test
    fun `context includes summary as base context when available`() {
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
                    systemPrompt = "system"
                )
            )
        )
    }

    @Test
    fun `context sends facts block plus dialog within token budget`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("goal: ship token context")
        repository.addAssistant("noted")
        repository.addUser("latest")

        val context = repository.apiContextMessages(
            AgentSettings(
                apiKey = "",
                modelContextWindowTokens = 1_100,
                systemPrompt = "system"
            )
        )

        assertEquals(Role.SYSTEM, context[0].role)
        assertEquals(Role.SYSTEM, context[1].role)
        assertContains(context[1].content, "Sticky facts:")
        assertContains(context[1].content, "goal: ship token context")
        assertEquals(
            listOf(
                ChatMessage(Role.USER, "goal: ship token context"),
                ChatMessage(Role.ASSISTANT, "noted"),
                ChatMessage(Role.USER, "latest")
            ),
            context.drop(2)
        )
    }

    @Test
    fun `context includes summary before facts when available`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("goal: ship token context")
        repository.saveSummary("compressed old dialog", TokenUsage.ZERO)
        repository.addUser("latest")

        val context = repository.apiContextMessages(
            AgentSettings(
                apiKey = "",
                systemPrompt = "system"
            )
        )

        assertEquals(ChatMessage(Role.SYSTEM, "system"), context[0])
        assertEquals(ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\ncompressed old dialog"), context[1])
        assertContains(context[2].content, "Sticky facts:")
        assertEquals(ChatMessage(Role.USER, "latest"), context[3])
    }

    @Test
    fun `memory messages are inserted after base system prompt before derived context`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("goal: keep facts")
        repository.saveSummary("compressed old dialog", TokenUsage.ZERO)
        repository.addUser("latest")

        val context = repository.apiContextMessages(
            AgentSettings(
                apiKey = "",
                systemPrompt = "system"
            ),
            memoryMessages = listOf(ChatMessage(Role.SYSTEM, "Permanent memory instructions:\nRule."))
        )

        assertEquals(ChatMessage(Role.SYSTEM, "system"), context[0])
        assertEquals(ChatMessage(Role.SYSTEM, "Permanent memory instructions:\nRule."), context[1])
        assertEquals(ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\ncompressed old dialog"), context[2])
        assertContains(context[3].content, "Sticky facts:")
        assertEquals(ChatMessage(Role.USER, "latest"), context[4])
    }

    @Test
    fun `extra system messages are not persisted in public chat history`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("hello")

        val context = repository.apiContextMessages(
            AgentSettings(apiKey = "", systemPrompt = "system"),
            memoryMessages = listOf(ChatMessage(Role.SYSTEM, "Assistant invariants:\n- Rule."))
        )

        assertContains(context.joinToString("\n") { it.content }, "Assistant invariants:")
        assertFalse(repository.all().any { it.content.contains("Assistant invariants:") })
        assertFalse(repository.state().messages.any { it.content.contains("Assistant invariants:") })
    }

    @Test
    fun `active branch id exposes main fallback and branch id`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")

        assertEquals("main", repository.activeBranchIdOrMain())
        repository.createBranch("alpha")

        assertEquals(repository.state().activeBranchId, repository.activeBranchIdOrMain())
        assertEquals("alpha", repository.activeBranchDisplayName())
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
    fun `facts are updated from extracted key value lines`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")

        repository.applyExtractedFacts(
            """
            project_goal: implement generated memory
            preferred_language: Russian
            none
            """.trimIndent()
        )

        assertEquals(
            mapOf(
                "project_goal" to "implement generated memory",
                "preferred_language" to "Russian"
            ),
            repository.facts()
        )
    }

    @Test
    fun `facts update usage is included in total usage and excluded from api context`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")

        repository.applyExtractedFacts(
            content = "project_goal: account facts usage",
            usage = TokenUsage(inputTokens = 7, outputTokens = 3, reasoningTokens = 2)
        )

        assertEquals(TokenUsage(inputTokens = 7, outputTokens = 3, reasoningTokens = 2), repository.totalUsage())
        assertEquals(TokenUsage(inputTokens = 7, outputTokens = 3, reasoningTokens = 2), repository.lastFactsUsage())
        assertEquals(true, repository.all().any { it.role == Role.EVENT && it.content.contains("Facts usage") })
        assertEquals(false, repository.apiContextMessages().any { it.role == Role.EVENT })
    }

    @Test
    fun `branches from checkpoint diverge independently and switch active chat context`() {
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

        assertEquals(true, repository.switchBranch("main"))
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.USER, "base")
            ),
            repository.all()
        )
    }

    @Test
    fun `summary is stored independently for active branch`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("main base")
        repository.saveSummary("main summary", TokenUsage(inputTokens = 1), lastMessageIndex = 1)
        repository.checkpoint()

        assertEquals(true, repository.createBranch("detail"))
        repository.addUser("branch detail")
        repository.addAssistant("branch answer")
        assertEquals(true, repository.shouldCreateSummary(interval = 1))

        repository.saveSummary(
            content = "branch summary",
            usage = TokenUsage(inputTokens = 2),
            lastMessageIndex = repository.indexBeforeLatestUserMessage()
        )

        assertEquals("main summary", repository.state().summary?.content)
        assertEquals("branch summary", repository.state().branches.single().summary?.content)
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\nbranch summary"),
                ChatMessage(Role.USER, "branch detail"),
                ChatMessage(Role.ASSISTANT, "branch answer")
            ),
            repository.apiContextMessages()
        )

        assertEquals(true, repository.switchBranch("main"))
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\nmain summary")
            ),
            repository.apiContextMessages()
        )
    }

    @Test
    fun `facts are stored independently for main and branches`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.applyExtractedFacts("goal: main goal")
        repository.checkpoint()

        assertEquals(true, repository.createBranch("alpha"))
        assertEquals(mapOf("goal" to "main goal"), repository.facts())

        repository.applyExtractedFacts("goal: branch goal\nbranch_only: yes")
        assertEquals(
            mapOf(
                "goal" to "branch goal",
                "branch_only" to "yes"
            ),
            repository.facts()
        )

        assertEquals(true, repository.switchBranch("main"))
        assertEquals(mapOf("goal" to "main goal"), repository.facts())

        repository.applyExtractedFacts("main_only: yes")
        assertEquals(
            mapOf(
                "goal" to "main goal",
                "main_only" to "yes"
            ),
            repository.facts()
        )

        assertEquals(true, repository.switchBranch("alpha"))
        assertEquals(
            mapOf(
                "goal" to "branch goal",
                "branch_only" to "yes"
            ),
            repository.facts()
        )
    }

    @Test
    fun `context uses active branch facts only`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.applyExtractedFacts("goal: main goal")
        repository.addUser("main message")
        repository.checkpoint()

        assertEquals(true, repository.createBranch("alpha"))
        repository.applyExtractedFacts("goal: branch goal")
        repository.addUser("branch message")

        val context = repository.apiContextMessages(
            AgentSettings(
                apiKey = "",
                systemPrompt = "system"
            )
        )

        val factsBlock = context.first { it.content.startsWith("Sticky facts:") }.content
        assertContains(factsBlock, "goal: branch goal")
        assertEquals(false, factsBlock.contains("main goal"))
        assertEquals(true, context.any { it.content == "branch message" })
    }

    @Test
    fun `total usage includes summary usage`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addAssistant("answer", TokenUsage(outputTokens = 3))
        repository.saveSummary("summary", TokenUsage(inputTokens = 4, outputTokens = 5, reasoningTokens = 6))

        assertEquals(TokenUsage(inputTokens = 4, outputTokens = 8, reasoningTokens = 6), repository.totalUsage())
    }
}
