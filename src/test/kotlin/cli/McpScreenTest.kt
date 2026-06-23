package cli

import mcp.McpClient
import mcp.McpConnectionState
import mcp.McpServerConfig
import mcp.McpServerStatus
import mcp.McpServerStore
import mcp.McpTool
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class McpScreenTest {
    @Test
    fun `help list and back work inside mcp screen`() {
        val directory = Files.createTempDirectory("aichat-mcp-screen-test")
        val client = FakeMcpClient()
        client.configure(listOf(McpServerConfig("filesystem", listOf("node", "server.js"))))

        val output = captureStdout {
            McpScreen(
                renderer = ConsoleRenderer(),
                store = McpServerStore(directory.resolve("mcp-servers.json")),
                client = client,
                input = ConsoleInput(BufferedReader(StringReader("help\nlist\nback\n")))
            ).open()
        }

        assertContains(output, "MCP commands:")
        assertContains(output, "MCP Servers")
        assertContains(output, "Returning to chat.")
    }

    @Test
    fun `connect persists server and renders connected status using fake MCP client`() {
        val directory = Files.createTempDirectory("aichat-mcp-connect-test")
        val store = McpServerStore(directory.resolve("mcp-servers.json"))

        val output = captureStdout {
            McpScreen(
                renderer = ConsoleRenderer(),
                store = store,
                client = FakeMcpClient(),
                input = ConsoleInput(BufferedReader(StringReader("connect local node server.js\nback\n")))
            ).open()
        }

        assertContains(output, "local: connected")
        assertEquals(listOf(McpServerConfig("local", listOf("node", "server.js"))), store.load())
    }

    @Test
    fun `tools server renders tool names and descriptions`() {
        val directory = Files.createTempDirectory("aichat-mcp-tools-test")
        val store = McpServerStore(directory.resolve("mcp-servers.json"))
        store.save(listOf(McpServerConfig("local", listOf("node", "server.js"))))
        val client = FakeMcpClient()
        client.tools["local"] = listOf(McpTool("local", "read_file", "Read a file", "{\"type\":\"object\"}"))

        val output = captureStdout {
            McpScreen(
                renderer = ConsoleRenderer(),
                store = store,
                client = client,
                input = ConsoleInput(BufferedReader(StringReader("tools local\nback\n")))
            ).open()
        }

        assertContains(output, "read_file")
        assertContains(output, "Read a file")
    }

    @Test
    fun `tools all renders tools with source server names`() {
        val directory = Files.createTempDirectory("aichat-mcp-tools-all-test")
        val store = McpServerStore(directory.resolve("mcp-servers.json"))
        store.save(
            listOf(
                McpServerConfig("one", listOf("server-one")),
                McpServerConfig("two", listOf("server-two"))
            )
        )
        val client = FakeMcpClient()
        client.tools["one"] = listOf(McpTool("one", "alpha"))
        client.tools["two"] = listOf(McpTool("two", "beta"))

        val output = captureStdout {
            McpScreen(
                renderer = ConsoleRenderer(),
                store = store,
                client = client,
                input = ConsoleInput(BufferedReader(StringReader("tools all\nback\n")))
            ).open()
        }

        assertContains(output, "one/alpha")
        assertContains(output, "two/beta")
    }

    @Test
    fun `unknown command shows unknown mcp command`() {
        val directory = Files.createTempDirectory("aichat-mcp-unknown-test")

        val output = captureStdout {
            McpScreen(
                renderer = ConsoleRenderer(),
                store = McpServerStore(directory.resolve("mcp-servers.json")),
                client = FakeMcpClient(),
                input = ConsoleInput(BufferedReader(StringReader("wat\nback\n")))
            ).open()
        }

        assertContains(output, "Unknown MCP command. Enter help.")
    }

    @Test
    fun `remove deletes one persisted mcp server`() {
        val directory = Files.createTempDirectory("aichat-mcp-remove-test")
        val store = McpServerStore(directory.resolve("mcp-servers.json"))
        store.save(
            listOf(
                McpServerConfig("one", listOf("server-one")),
                McpServerConfig("two", listOf("server-two"))
            )
        )
        val client = FakeMcpClient()

        val output = captureStdout {
            McpScreen(
                renderer = ConsoleRenderer(),
                store = store,
                client = client,
                input = ConsoleInput(BufferedReader(StringReader("remove one\nlist\nback\n")))
            ).open()
        }

        assertContains(output, "MCP server removed: one")
        assertEquals(listOf(McpServerConfig("two", listOf("server-two"))), store.load())
        assertEquals(listOf("one"), client.disconnected)
    }

    @Test
    fun `clear deletes all persisted mcp servers`() {
        val directory = Files.createTempDirectory("aichat-mcp-clear-test")
        val store = McpServerStore(directory.resolve("mcp-servers.json"))
        store.save(
            listOf(
                McpServerConfig("one", listOf("server-one")),
                McpServerConfig("two", listOf("server-two"))
            )
        )
        val client = FakeMcpClient()

        val output = captureStdout {
            McpScreen(
                renderer = ConsoleRenderer(),
                store = store,
                client = client,
                input = ConsoleInput(BufferedReader(StringReader("clear\nlist\nback\n")))
            ).open()
        }

        assertContains(output, "All MCP servers removed.")
        assertEquals(emptyList(), store.load())
        assertEquals(true, client.cleared)
    }

    private fun captureStdout(block: () -> Unit): String {
        val originalOut = System.out
        val stream = ByteArrayOutputStream()
        System.setOut(PrintStream(stream, true, Charsets.UTF_8))
        return try {
            block()
            stream.toString(Charsets.UTF_8)
        } finally {
            System.setOut(originalOut)
        }
    }

    private class FakeMcpClient : McpClient {
        private val configs = linkedMapOf<String, McpServerConfig>()
        private val statuses = linkedMapOf<String, McpServerStatus>()
        val tools = mutableMapOf<String, List<McpTool>>()
        val disconnected = mutableListOf<String>()
        var cleared = false

        override fun configure(configs: List<McpServerConfig>) {
            configs.forEach {
                this.configs[it.name] = it
                statuses.putIfAbsent(it.name, McpServerStatus(it.name, McpConnectionState.CONFIGURED))
            }
        }

        override fun connect(config: McpServerConfig): McpServerStatus {
            configs[config.name] = config
            return McpServerStatus(config.name, McpConnectionState.CONNECTED).also { statuses[config.name] = it }
        }

        override fun disconnect(serverName: String) {
            disconnected += serverName
            configs.remove(serverName)
            statuses.remove(serverName)
        }

        override fun clear() {
            cleared = true
            configs.clear()
            statuses.clear()
        }

        override fun listServers(): List<McpServerStatus> =
            configs.keys.sorted().map { statuses[it] ?: McpServerStatus(it, McpConnectionState.CONFIGURED) }

        override fun listTools(serverName: String): List<McpTool> = tools[serverName].orEmpty()

        override fun close() = Unit
    }
}
