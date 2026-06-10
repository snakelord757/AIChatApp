package agent

interface AiAgent {
    @Throws(AgentException::class)
    fun send(userMessage: String): AgentResponse

    fun updateSettings(settings: AgentSettings)
}
