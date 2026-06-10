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

    override fun send(userMessage: String): String {
        historyRepository.addUser(userMessage)
        val history = historyRepository.all()

        val request = HttpRequest.newBuilder(endpoint(settings.baseUrl))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(history, settings)))
            .build()

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
        historyRepository.addAssistant(answer)
        return answer
    }

    override fun updateSettings(settings: AgentSettings) {
        this.settings = settings
    }

    private fun endpoint(baseUrl: String): URI {
        val normalized = baseUrl.trim().trimEnd('/')
        return URI.create("$normalized/chat/completions")
    }

    private fun buildRequestBody(history: List<ChatMessage>, settings: AgentSettings): String {
        val messages = history.joinToString(separator = ",") { message ->
            """{"role":"${JsonTools.escape(message.role.apiName)}","content":"${JsonTools.escape(message.content)}"}"""
        }

        return """
            {
              "model": "${JsonTools.escape(settings.model)}",
              "messages": [$messages],
              "temperature": ${settings.temperature},
              "max_tokens": ${settings.maxTokens}
            }
        """.trimIndent()
    }
}
