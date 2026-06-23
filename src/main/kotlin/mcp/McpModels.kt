package mcp

data class McpServerConfig(
    val name: String,
    val command: List<String>,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val transport: McpTransport = McpTransport.STDIO,
    val url: String? = null
)

enum class McpTransport {
    STDIO,
    HTTP
}

data class McpServerStatus(
    val name: String,
    val state: McpConnectionState,
    val message: String? = null
)

data class McpTool(
    val serverName: String,
    val name: String,
    val description: String? = null,
    val inputSchema: String? = null
)

data class McpToolCallResult(
    val serverName: String,
    val toolName: String,
    val content: String,
    val isError: Boolean = false
)

enum class McpConnectionState {
    CONFIGURED,
    CONNECTED,
    FAILED
}
