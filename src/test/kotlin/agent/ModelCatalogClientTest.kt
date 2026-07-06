package agent

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelCatalogClientTest {
    @Test
    fun `parses model ids from provider response`() {
        val models = ModelCatalogClient().parseModelIds(
            """
            {
              "object": "list",
              "data": [
                {"id": "llama3.1", "object": "model"},
                {"id": "qwen2.5-coder", "object": "model"},
                {"id": "llama3.1", "object": "model"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("llama3.1", "qwen2.5-coder"), models)
    }
}
