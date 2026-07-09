package agent

import chat.ContextStrategy

data class AgentSettings(
    val apiKey: String,
    val baseUrl: String = "http://localhost:11434/v1",
    val model: String = defaultModel,
    val availableModels: List<String> = listOf(defaultModel),
    val thinkingMode: Boolean = false,
    val temperature: Double = 0.7,
    val maxTokens: Int = 0,
    val modelContextWindowTokens: Long = 1_000_000L,
    val summaryInterval: Int = 20,
    val contextStrategy: ContextStrategy = ContextStrategy.SLIDING_WINDOW,
    val contextWindowMessages: Int = 20,
    val allowClarifyingQuestions: Boolean = false,
    val planningSwarmEnabled: Boolean = false,
    val ragEnabled: Boolean = false,
    val ragOllamaUrl: String = "http://localhost:11434",
    val ragEmbeddingModel: String? = null,
    val ragSearchTopK: Int = 15,
    val ragTopK: Int = 5,
    val systemPrompt: String,
    val systemPromptOverridden: Boolean = false
) {
    fun shouldUseDirectChat(): Boolean = ragEnabled || systemPromptOverridden

    companion object {
        const val defaultModel = "local-model"
    }
}
