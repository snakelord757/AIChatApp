package agent

import chat.ChatMessage
import chat.ChatHistoryRepository
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class DeepSeekAiAgent(
    private val historyRepository: ChatHistoryRepository,
    initialSettings: AgentSettings,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()
) : AiAgent {
    private var settings = initialSettings

    override fun send(userMessage: String): AgentResponse {
        historyRepository.addUser(userMessage)
        val history = historyRepository.all()

        val request = buildRequest(history, settings)

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (exception: IOException) {
            throw AgentException("Не удалось подключиться к DeepSeek. Проверьте интернет и базовый URL.", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentException("Запрос к DeepSeek был прерван.", exception)
        } catch (exception: IllegalArgumentException) {
            throw AgentException("Некорректный URL DeepSeek: ${settings.baseUrl}", exception)
        }

        if (response.statusCode() !in 200..299) {
            throw AgentException("DeepSeek вернул HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }

        val answer = JsonTools.extractAssistantContent(response.body())
            ?: throw AgentException("DeepSeek вернул пустой или неожиданный JSON-ответ.")
        val usage = JsonTools.extractUsage(response.body())
        historyRepository.addAssistant(answer, usage)
        return AgentResponse(answer, usage)
    }

    override fun updateSettings(settings: AgentSettings) {
        this.settings = settings
    }

    private fun endpoint(baseUrl: String): URI {
        val normalized = baseUrl.trim().trimEnd('/')
        return URI.create("$normalized/chat/completions")
    }

    private fun buildRequest(history: List<ChatMessage>, settings: AgentSettings): HttpRequest =
        HttpRequest.newBuilder(endpoint(settings.baseUrl))
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(history, settings)))
            .build()

    private fun buildRequestBody(history: List<ChatMessage>, settings: AgentSettings): String {
        val messages = history.joinToString(separator = ",") { message ->
            """{"role":"${JsonTools.escape(message.role.apiName)}","content":"${JsonTools.escape(message.content)}"}"""
        }

        val thinkingType = if (settings.thinkingMode) "enabled" else "disabled"
        val fields = mutableListOf(
            """"model": "${JsonTools.escape(settings.model)}"""",
            """"messages": [$messages]""",
            """"thinking": {"type": "$thinkingType"}"""
        )
        if (settings.thinkingMode) {
            fields += """"reasoning_effort": "high""""
        } else {
            fields += """"temperature": ${settings.temperature}"""
        }
        if (settings.maxTokens > 0) {
            fields += """"max_tokens": ${settings.maxTokens}"""
        }

        return fields.joinToString(
            separator = ",\n  ",
            prefix = "{\n  ",
            postfix = "\n}"
        )
    }
}
