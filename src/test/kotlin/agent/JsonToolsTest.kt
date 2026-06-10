package agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonToolsTest {
    @Test
    fun `extracts finish reason`() {
        val json = """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}"""

        assertEquals("length", JsonTools.extractFinishReason(json))
    }

    @Test
    fun `detects context limit error`() {
        val json = """{"error":{"message":"The request exceeds the maximum context length limit."}}"""

        assertTrue(JsonTools.looksLikeContextLimitError(json))
    }

    @Test
    fun `does not treat unrelated errors as context limit`() {
        val json = """{"error":{"message":"Invalid API key."}}"""

        assertFalse(JsonTools.looksLikeContextLimitError(json))
    }
}
