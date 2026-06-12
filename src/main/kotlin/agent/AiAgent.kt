package agent

interface AiAgent {
    @Throws(AgentException::class)
    fun send(userMessage: String, summaryEvents: SummaryEvents = SummaryEvents.None): AgentResponse

    fun updateSettings(settings: AgentSettings)
}

interface SummaryEvents {
    fun onSummaryStarted() {}
    fun onSummaryUsage(usage: chat.TokenUsage) {}

    object None : SummaryEvents
}
