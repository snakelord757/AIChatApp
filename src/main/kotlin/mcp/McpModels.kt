package mcp

data class McpServerConfig(
    val name: String,
    val command: List<String>
)

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

enum class McpConnectionState {
    CONFIGURED,
    CONNECTED,
    FAILED
}
