package agent

data class AgentSettings(
    val apiKey: String,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-v4-flash",
    val thinkingMode: Boolean = false,
    val temperature: Double = 0.7,
    val maxTokens: Int = 0,
    val summaryInterval: Int = 20,
    val systemPrompt: String
) {
    companion object {
        val supportedModels = setOf("deepseek-v4-flash", "deepseek-v4-pro")
        const val defaultModel = "deepseek-v4-flash"
    }
}
