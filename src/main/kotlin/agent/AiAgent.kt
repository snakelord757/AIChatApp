package agent

interface AiAgent {
    @Throws(AgentException::class)
    fun send(userMessage: String): String

    fun updateSettings(settings: AgentSettings)
}
