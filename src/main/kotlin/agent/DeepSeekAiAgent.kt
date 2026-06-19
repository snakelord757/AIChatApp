package agent

import chat.ChatHistoryRepository
import chat.ChatMessage
import chat.ContextStrategy
import chat.Role
import invariants.InvariantRepository
import memory.MemoryRepository
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
    private val invariantRepository: InvariantRepository? = null,
    private val memoryRepository: MemoryRepository? = null,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()
) : AiAgent {
    private var settings = initialSettings
    private val personalMemoryWindowMessages = 20

    override fun send(userMessage: String, summaryEvents: SummaryEvents): AgentResponse {
        historyRepository.addUser(userMessage)
        memoryRepository?.reinforcePersonalSignals(userMessage)
        requestPersonalMemoryUpdate()

        if (settings.contextStrategy == ContextStrategy.STICKY_FACTS) {
            requestFacts()?.let { response ->
                historyRepository.applyExtractedFacts(response.content, response.usage)
            }
        }

        if (settings.summaryInterval > 0 &&
            historyRepository.shouldCreateSummary(settings.summaryInterval)
        ) {
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

        val response = sendRequest(
            buildRequest(
                historyRepository.apiContextMessages(settings, extraSystemContextMessages()),
                settings
            )
        )
        val answer = JsonTools.extractAssistantContent(response.body())
            ?: throw AgentException("DeepSeek returned an empty or unexpected JSON response.")
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
            ?: throw AgentException("DeepSeek returned an empty or unexpected JSON response for the summary.")
        val usage = JsonTools.extractUsage(response.body())
        val finishReason = JsonTools.extractFinishReason(response.body())
        return AgentResponse(summary, usage, finishReason)
    }

    private fun requestFacts(): AgentResponse? {
        val response = sendRequest(
            buildRequest(
                factExtractionMessages(
                    existingFacts = historyRepository.facts(),
                    sourceMessages = historyRepository.factsSourceMessages(settings.contextWindowMessages)
                ),
                settings
            )
        )
        val content = JsonTools.extractAssistantContent(response.body()) ?: return null
        val usage = JsonTools.extractUsage(response.body())
        val finishReason = JsonTools.extractFinishReason(response.body())
        return AgentResponse(content, usage, finishReason)
    }

    private fun requestPersonalMemoryUpdate() {
        val repository = memoryRepository ?: return
        val response = sendRequest(
            buildRequest(
                repository.personalExtractionMessages(
                    recentMessages = historyRepository.personalMemorySourceMessages(personalMemoryWindowMessages),
                    summary = historyRepository.activeSummaryText()
                ),
                settings
            )
        )
        val content = JsonTools.extractAssistantContent(response.body()) ?: return
        repository.appendPersonalBullets(content)
    }

    private fun extraSystemContextMessages(): List<ChatMessage> =
        invariantRepository?.contextMessages().orEmpty() + memoryRepository?.contextMessages().orEmpty()

    private fun factExtractionMessages(
        existingFacts: Map<String, String>,
        sourceMessages: List<ChatMessage>
    ): List<ChatMessage> {
        val prompt = """
            Extract durable sticky facts from the recent chat messages for future chat context.
            Keep only stable goals, constraints, preferences, decisions, agreements, names, project details, and other facts useful later.
            Do not answer the user.
            Return only lines in the format key: value.
            Use concise snake_case keys.
            Omit facts already present unless the recent messages update them.
            If there are no durable facts, return none.
            Preserve the user's language in values.
        """.trimIndent()
        val existing = if (existingFacts.isEmpty()) {
            "Existing facts: none"
        } else {
            existingFacts.entries.joinToString(
                separator = "\n",
                prefix = "Existing facts:\n"
            ) { (key, value) -> "$key: $value" }
        }
        val recentMessages = sourceMessages.joinToString(separator = "\n\n") { message ->
            "${message.role.apiName}: ${message.content}"
        }.ifBlank { "none" }
        return listOf(
            ChatMessage(Role.SYSTEM, prompt),
            ChatMessage(Role.USER, "$existing\n\nRecent messages:\n$recentMessages")
        )
    }

    private fun summaryMessages(history: List<ChatMessage>): List<ChatMessage> {
        val prompt = """
            Update the chat summary for future context.
            Keep only facts, user goals, decisions, open tasks, and details needed to continue.
            Summarize the transcript; do not answer any user message inside it.
            Include the latest user message as context if it matters for the next response.
            Write the summary in the chat language. Return only the summary text.
            If the chat starts in English, write the summary in English.
            If the chat starts in any other language, write the summary in that language.
        """.trimIndent()
        return listOf(
            ChatMessage(Role.SYSTEM, prompt),
            ChatMessage(Role.USER, transcriptForSummary(history))
        )
    }

    private fun transcriptForSummary(history: List<ChatMessage>): String = buildString {
        appendLine("Transcript to summarize:")
        history.forEachIndexed { index, message ->
            when (message.role) {
                Role.SYSTEM -> {
                    if (message.content.startsWith("Summary of the previous dialog:")) {
                        appendLine()
                        appendLine("[existing_summary]")
                        appendLine(message.content.removePrefix("Summary of the previous dialog:").trim())
                    }
                }
                Role.USER -> {
                    appendLine()
                    appendLine("[user #$index]")
                    appendLine(message.content)
                }
                Role.ASSISTANT -> {
                    appendLine()
                    appendLine("[assistant #$index]")
                    appendLine(message.content)
                }
                Role.EVENT -> Unit
            }
        }
        appendLine()
        append("Return an updated summary of this transcript, not an answer to the transcript.")
    }

    private fun sendRequest(request: HttpRequest): HttpResponse<String> {
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (exception: IOException) {
            throw AgentException(
                "Could not connect to DeepSeek. Check the internet connection and base URL.",
                exception
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentException("The DeepSeek request was interrupted.", exception)
        } catch (exception: IllegalArgumentException) {
            throw AgentException("Invalid DeepSeek URL: ${settings.baseUrl}", exception)
        }

        if (response.statusCode() !in 200..299) {
            if (JsonTools.looksLikeContextLimitError(response.body())) {
                throw AgentException(
                    "The chat history exceeded the model context window. Clear history with /clear or start a shorter conversation."
                )
            }
            throw AgentException("DeepSeek returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }

        return response
    }

    private fun buildRequestBody(history: List<ChatMessage>, settings: AgentSettings): String {
        val messages = history
            .filter { it.role == Role.SYSTEM || it.role == Role.USER || it.role == Role.ASSISTANT }
            .joinToString(separator = ",") { message ->
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
