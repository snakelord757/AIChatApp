package agent

import chat.ChatHistoryRepository
import chat.ChatMessage
import chat.Role
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

    override fun send(userMessage: String, summaryEvents: SummaryEvents): AgentResponse {
        historyRepository.addUser(userMessage)

        if (historyRepository.shouldCreateSummary(settings.summaryInterval)) {
            summaryEvents.onSummaryStarted()
            val summaryCutoffIndex = historyRepository.indexBeforeLatestUserMessage()
            val summaryResponse = requestSummary(historyRepository.summarySourceMessages())
            summaryEvents.onSummaryUsage(summaryResponse.usage)
            historyRepository.saveSummary(
                content = summaryResponse.content,
                usage = summaryResponse.usage,
                lastMessageIndex = summaryCutoffIndex
            )
        }

        val response = sendRequest(buildRequest(historyRepository.apiContextMessages(), settings))
        val answer = JsonTools.extractAssistantContent(response.body())
            ?: throw AgentException("DeepSeek вернул пустой или неожиданный JSON-ответ.")
        val usage = JsonTools.extractUsage(response.body())
        val finishReason = JsonTools.extractFinishReason(response.body())
        val limitReason = ResponseLimitClassifier.classify(
            finishReason = finishReason,
            settings = settings,
            usage = usage
        )
        historyRepository.addAssistant(answer, usage)
        return AgentResponse(answer, usage, finishReason, limitReason)
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

    private fun requestSummary(history: List<ChatMessage>): AgentResponse {
        val response = sendRequest(buildRequest(summaryMessages(history), settings))
        val summary = JsonTools.extractAssistantContent(response.body())
            ?: throw AgentException("DeepSeek вернул пустой или неожиданный JSON-ответ для summary.")
        val usage = JsonTools.extractUsage(response.body())
        val finishReason = JsonTools.extractFinishReason(response.body())
        return AgentResponse(summary, usage, finishReason)
    }

    private fun summaryMessages(history: List<ChatMessage>): List<ChatMessage> {
        val prompt = """
            Update the chat summary for future context.
            Keep only facts, user goals, decisions, open tasks, and details needed to continue.
            Include the latest user message if it matters for the next response.
            Write the summary in the chat language. Return only the summary text.
        """.trimIndent()
        return listOf(ChatMessage(Role.SYSTEM, prompt)) + history.filterNot { it.role == Role.SYSTEM }
    }

    private fun sendRequest(request: HttpRequest): HttpResponse<String> {
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (exception: IOException) {
            throw AgentException(
                "Не удалось подключиться к DeepSeek. Проверьте интернет и базовый URL.",
                exception
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentException("Запрос к DeepSeek был прерван.", exception)
        } catch (exception: IllegalArgumentException) {
            throw AgentException("Некорректный URL DeepSeek: ${settings.baseUrl}", exception)
        }

        if (response.statusCode() !in 200..299) {
            if (JsonTools.looksLikeContextLimitError(response.body())) {
                throw AgentException(
                    "История чата превысила контекстное окно модели. Очистите историю командой /clear или начните новый диалог с более коротким контекстом."
                )
            }
            throw AgentException("DeepSeek вернул HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }

        return response
    }

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
