package agent

import chat.ContextStrategy

data class AgentSettings(
    val apiKey: String,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-v4-flash",
    val thinkingMode: Boolean = false,
    val temperature: Double = 0.7,
    val maxTokens: Int = 0,
    val summaryInterval: Int = 20,
    val contextStrategy: ContextStrategy = ContextStrategy.SLIDING_WINDOW,
    val contextWindowMessages: Int = 20,
    val allowClarifyingQuestions: Boolean = false,
    val systemPrompt: String
) {
    companion object {
        val supportedModels = setOf("deepseek-v4-flash", "deepseek-v4-pro")
        const val defaultModel = "deepseek-v4-flash"
    }
}
