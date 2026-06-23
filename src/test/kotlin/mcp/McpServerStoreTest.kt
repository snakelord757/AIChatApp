package mcp

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class McpServerStoreTest {
    @Test
    fun `missing store returns empty list`() {
        val path = Files.createTempDirectory("aichat-mcp-store-missing").resolve("mcp-servers.json")

        assertEquals(emptyList(), McpServerStore(path).load())
    }

    @Test
    fun `saved configs round trip`() {
        val path = Files.createTempDirectory("aichat-mcp-store-roundtrip").resolve("mcp-servers.json")
        val store = McpServerStore(path)
        val configs = listOf(
            McpServerConfig("filesystem", listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "D:\\work")),
            McpServerConfig("local", listOf("node", "D:\\mcp\\server.js"))
        )

        store.save(configs)

        assertEquals(configs, store.load())
    }

    @Test
    fun `malformed store safely returns empty list`() {
        val path = Files.createTempDirectory("aichat-mcp-store-malformed").resolve("mcp-servers.json")
        Files.writeString(path, "{not-json", StandardCharsets.UTF_8)

        assertEquals(emptyList(), McpServerStore(path).load())
    }
}
