package agent

import chat.ChatHistoryRepository
import chat.ChatMessage
import chat.Role
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DeepSeekAiAgentRequestBodyTest {
    @Test
    fun `default settings do not send max tokens limit`() {
        val body = buildRequestBody(AgentSettings(apiKey = "", systemPrompt = "system"))

        assertFalse(body.contains("\"max_tokens\""))
    }

    @Test
    fun `positive max tokens setting is sent`() {
        val body = buildRequestBody(AgentSettings(apiKey = "", maxTokens = 256, systemPrompt = "system"))

        assertContains(body, "\"max_tokens\": 256")
    }

    private fun buildRequestBody(settings: AgentSettings): String {
        val agent = DeepSeekAiAgent(
            historyRepository = ChatHistoryRepository(systemPrompt = "system"),
            initialSettings = settings
        )
        val method = DeepSeekAiAgent::class.java.getDeclaredMethod(
            "buildRequestBody",
            List::class.java,
            AgentSettings::class.java
        )
        method.isAccessible = true
        return method.invoke(
            agent,
            listOf(ChatMessage(Role.USER, "hello")),
            settings
        ) as String
    }
}
