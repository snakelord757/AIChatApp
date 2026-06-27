package scheduled

import agent.AgentException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScheduleParsingAgentTest {
    private val parser = ScheduleParsingAgent {
        error("Client is not used by parseJson tests.")
    }

    @Test
    fun `strict parser extracts task and interval`() {
        val parsed = parser.parseJson(
            """{"task":"Summarize news from news.com","interval":{"time":30,"timeUnit":"MINUTES"}}"""
        )

        assertEquals("Summarize news from news.com", parsed.task)
        assertEquals(ScheduledTaskInterval(30, ScheduledTaskTimeUnit.MINUTES), parsed.interval)
    }

    @Test
    fun `strict parser rejects invalid unit`() {
        assertFailsWith<AgentException> {
            parser.parseJson("""{"task":"Do work","interval":{"time":1,"timeUnit":"WEEKS"}}""")
        }
    }

    @Test
    fun `strict parser rejects non positive interval`() {
        assertFailsWith<AgentException> {
            parser.parseJson("""{"task":"Do work","interval":{"time":0,"timeUnit":"MINUTES"}}""")
        }
    }
}
