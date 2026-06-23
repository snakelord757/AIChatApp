package agent

import mcp.McpToolCallResult

interface AiAgent {
    @Throws(AgentException::class)
    fun send(userMessage: String, summaryEvents: SummaryEvents = SummaryEvents.None): AgentResponse

    fun updateSettings(settings: AgentSettings)
}

interface SummaryEvents {
    fun onSummaryStarted() {}
    fun onSummaryUsage(usage: chat.TokenUsage) {}
    fun onMcpToolCallStarted(serverName: String, toolName: String, argumentsJson: String) {}
    fun onMcpToolCallCompleted(result: McpToolCallResult) {}
    fun onMcpToolCallFailed(serverName: String, toolName: String, message: String) {}

    object None : SummaryEvents
}
