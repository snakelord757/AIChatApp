package mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpToolArgumentSanitizerTest {
    @Test
    fun `removes arguments that are not declared in input schema properties`() {
        val tool = McpTool(
            serverName = "amiibo_api",
            name = "search_amiibo",
            inputSchema = """{"type":"object","properties":{"name":{"type":"string"}}}"""
        )

        val sanitized = McpToolArgumentSanitizer.sanitize("""{"name":"Zelda","limit":3}""", tool)

        assertEquals("""{"name":"Zelda"}""", sanitized)
    }

    @Test
    fun `empty object schema removes all arguments unless additional properties are allowed`() {
        val tool = McpTool(
            serverName = "amiibo_api",
            name = "list_game_series",
            inputSchema = """{"type":"object"}"""
        )

        val sanitized = McpToolArgumentSanitizer.sanitize("""{"name":"The Legend Of Zelda"}""", tool)

        assertEquals("""{}""", sanitized)
    }

    @Test
    fun `additional properties true keeps dynamic arguments`() {
        val tool = McpTool(
            serverName = "dynamic",
            name = "query",
            inputSchema = """{"type":"object","additionalProperties":true}"""
        )

        val sanitized = McpToolArgumentSanitizer.sanitize("""{"name":"Zelda"}""", tool)

        assertEquals("""{"name":"Zelda"}""", sanitized)
    }

    @Test
    fun `rejects non object arguments for object schema`() {
        val tool = McpTool(
            serverName = "amiibo_api",
            name = "load_figure",
            inputSchema = """{"type":"object","properties":{"amiiboId":{"type":"string"}}}"""
        )

        assertFailsWith<IllegalArgumentException> {
            McpToolArgumentSanitizer.sanitize("[]", tool)
        }
    }

    @Test
    fun `rejects string arguments that violate declared schema constraints`() {
        val tool = McpTool(
            serverName = "amiibo_api",
            name = "load_figure",
            inputSchema = """{"type":"object","required":["amiiboId"],"properties":{"amiiboId":{"type":"string","minLength":16,"maxLength":16,"pattern":"^[0-9a-fA-F]{16}$"}}}"""
        )

        assertFailsWith<IllegalArgumentException> {
            McpToolArgumentSanitizer.sanitize("""{"amiiboId":"Animal Crossing"}""", tool)
        }
    }
}
