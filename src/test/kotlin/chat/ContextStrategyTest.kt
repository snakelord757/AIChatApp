package chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextStrategyTest {
    @Test
    fun `summary is not accepted as a context strategy`() {
        assertEquals(null, ContextStrategy.parse("summary"))
    }

    @Test
    fun `supported context strategies parse from public values`() {
        assertEquals(ContextStrategy.SLIDING_WINDOW, ContextStrategy.parse("sliding"))
        assertEquals(ContextStrategy.STICKY_FACTS, ContextStrategy.parse("facts"))
        assertEquals(ContextStrategy.BRANCHING, ContextStrategy.parse("branching"))
    }
}
