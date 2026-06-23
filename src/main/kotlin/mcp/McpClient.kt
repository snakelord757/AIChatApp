package mcp

interface McpClient : AutoCloseable {
    fun configure(configs: List<McpServerConfig>)
    fun connect(config: McpServerConfig): McpServerStatus
    fun disconnect(serverName: String)
    fun clear()
    fun listServers(): List<McpServerStatus>
    fun listTools(serverName: String): List<McpTool>
    override fun close()
}
