package agent

import chat.ChatHistoryRepository
import chat.ChatMessage
import chat.Role
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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

    @Test
    fun `request does not set response timeout`() {
        val request = buildRequest(AgentSettings(apiKey = "key", systemPrompt = "system"))

        assertFalse(request.timeout().isPresent)
    }

    @Test
    fun `summary request wraps chat history as transcript instead of active dialog`() {
        val messages = buildSummaryMessages(
            listOf(
                ChatMessage(Role.SYSTEM, "system prompt"),
                ChatMessage(Role.USER, "first question"),
                ChatMessage(Role.ASSISTANT, "first answer"),
                ChatMessage(Role.USER, "latest question")
            )
        )

        assertEquals(2, messages.size)
        assertEquals(Role.SYSTEM, messages[0].role)
        assertContains(messages[0].content, "do not answer any user message")
        assertEquals(Role.USER, messages[1].role)
        assertContains(messages[1].content, "Transcript to summarize:")
        assertContains(messages[1].content, "[user #1]")
        assertContains(messages[1].content, "latest question")
        assertContains(messages[1].content, "not an answer to the transcript")
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

    private fun buildRequest(settings: AgentSettings): HttpRequest {
        val agent = DeepSeekAiAgent(
            historyRepository = ChatHistoryRepository(systemPrompt = "system"),
            initialSettings = settings
        )
        val method = DeepSeekAiAgent::class.java.getDeclaredMethod(
            "buildRequest",
            List::class.java,
            AgentSettings::class.java
        )
        method.isAccessible = true
        return method.invoke(
            agent,
            listOf(ChatMessage(Role.USER, "hello")),
            settings
        ) as HttpRequest
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildSummaryMessages(history: List<ChatMessage>): List<ChatMessage> {
        val agent = DeepSeekAiAgent(
            historyRepository = ChatHistoryRepository(systemPrompt = "system"),
            initialSettings = AgentSettings(apiKey = "", systemPrompt = "system")
        )
        val method = DeepSeekAiAgent::class.java.getDeclaredMethod(
            "summaryMessages",
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(agent, history) as List<ChatMessage>
    }
}
