package mcp

interface McpToolGateway {
    fun availableTools(): List<McpTool>
    fun callTool(serverName: String, toolName: String, argumentsJson: String): McpToolCallResult
}

class StoredMcpToolGateway(
    private val store: McpServerStore,
    private val client: McpClient
) : McpToolGateway {
    override fun availableTools(): List<McpTool> {
        client.configure(store.load())
        return client.listServers().flatMap { status ->
            runCatching { client.listTools(status.name) }.getOrElse { emptyList() }
        }
    }

    override fun callTool(serverName: String, toolName: String, argumentsJson: String): McpToolCallResult {
        client.configure(store.load())
        return client.callTool(serverName, toolName, argumentsJson)
    }
}
