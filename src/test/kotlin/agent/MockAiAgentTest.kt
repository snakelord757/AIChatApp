package agent

import chat.ChatHistoryRepository
import chat.ContextStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

class MockAiAgentTest {
    @Test
    fun `summary is not saved when summary interval is zero`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        val agent = MockAiAgent(
            historyRepository = repository,
            initialSettings = AgentSettings(
                apiKey = "",
                summaryInterval = 0,
                systemPrompt = "system"
            )
        )

        agent.send("one", SummaryEvents.None)
        agent.send("two", SummaryEvents.None)

        assertEquals(null, repository.state().summary)
    }

    @Test
    fun `summary is saved for non summary context strategy when interval is positive`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        val agent = MockAiAgent(
            historyRepository = repository,
            initialSettings = AgentSettings(
                apiKey = "",
                summaryInterval = 1,
                contextStrategy = ContextStrategy.STICKY_FACTS,
                systemPrompt = "system"
            )
        )

        agent.send("one", SummaryEvents.None)
        agent.send("two", SummaryEvents.None)

        assertEquals("Demo summary is available only in offline mode.", repository.state().summary?.content)
    }
}
