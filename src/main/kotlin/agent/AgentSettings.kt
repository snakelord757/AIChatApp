package agent

data class AgentSettings(
    val apiKey: String,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
    val temperature: Double = 0.7,
    val maxTokens: Int = 1024,
    val systemPrompt: String
)
