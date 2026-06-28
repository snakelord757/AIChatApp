package mcp

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class McpToolGatewayTest {
    @Test
    fun `available tools includes every configured server beyond two`() {
        val store = McpServerStore(Files.createTempDirectory("aichat-mcp-gateway-test").resolve("mcp-servers.json"))
        store.save(
            listOf(
                McpServerConfig("one", listOf("server-one")),
                McpServerConfig("two", listOf("server-two")),
                McpServerConfig("three", listOf("server-three")),
                McpServerConfig("four", listOf("server-four"))
            )
        )
        val client = RecordingMcpClient(
            mapOf(
                "one" to listOf(McpTool("one", "alpha")),
                "two" to listOf(McpTool("two", "beta")),
                "three" to listOf(McpTool("three", "gamma")),
                "four" to listOf(McpTool("four", "delta"))
            )
        )

        val tools = StoredMcpToolGateway(store, client).availableTools()

        assertEquals(
            listOf("four/delta", "one/alpha", "three/gamma", "two/beta"),
            tools.map { "${it.serverName}/${it.name}" }
        )
        assertEquals(listOf(listOf("four", "one", "three", "two")), client.configuredServerNames)
        assertEquals(listOf("four", "one", "three", "two"), client.listedToolServers)
    }

    private class RecordingMcpClient(
        private val tools: Map<String, List<McpTool>>
    ) : McpClient {
        private val configs = linkedMapOf<String, McpServerConfig>()
        val configuredServerNames = mutableListOf<List<String>>()
        val listedToolServers = mutableListOf<String>()

        override fun configure(configs: List<McpServerConfig>) {
            configuredServerNames += configs.map { it.name }
            this.configs.clear()
            configs.forEach { this.configs[it.name] = it }
        }

        override fun connect(config: McpServerConfig): McpServerStatus =
            McpServerStatus(config.name, McpConnectionState.CONNECTED)

        override fun disconnect(serverName: String) {
            configs.remove(serverName)
        }

        override fun clear() {
            configs.clear()
        }

        override fun listServers(): List<McpServerStatus> =
            configs.keys.map { McpServerStatus(it, McpConnectionState.CONFIGURED) }

        override fun listTools(serverName: String): List<McpTool> {
            listedToolServers += serverName
            return tools[serverName].orEmpty()
        }

        override fun callTool(serverName: String, toolName: String, argumentsJson: String): McpToolCallResult =
            McpToolCallResult(serverName, toolName, "{}")

        override fun close() = Unit
    }
}
