package agent

import chat.ChatHistoryRepository
import chat.ChatMessage
import chat.ContextStrategy
import chat.Role
import invariants.InvariantRepository
import memory.MemoryRepository
import mcp.JsonValue
import mcp.McpJson
import mcp.McpToolCallResult
import mcp.McpToolArgumentSanitizer
import mcp.McpToolGateway
import mcp.asArray
import mcp.asObject
import mcp.asString
import rag.RagSearchResponse
import rag.RagSearchService
import rag.SearchResult
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
    private val mcpToolGateway: McpToolGateway? = null,
    private val ragSearchService: RagSearchService? = null,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()
) : AiAgent {
    private var settings = initialSettings
    private val personalMemoryWindowMessages = 20

    override fun send(userMessage: String, summaryEvents: SummaryEvents): AgentResponse {
        if (settings.ragEnabled) {
            return sendWithRag(userMessage)
        }

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

        val requestContext = historyRepository.apiContextMessages(settings, extraSystemContextMessages())
        val response = sendRequest(buildRequest(requestContext, settings))
        val firstAnswer = JsonTools.extractAssistantContent(response.body())
            ?: throw AgentException("The model provider returned an empty or unexpected JSON response.")
        val firstUsage = JsonTools.extractUsage(response.body())
        val toolCalls = parseMcpToolCalls(firstAnswer)
        val finalResponse = if (toolCalls.isEmpty() || mcpToolGateway == null) {
            response
        } else {
            val toolResults = toolCalls.map { toolCall ->
                try {
                    val argumentsJson = sanitizedMcpArguments(toolCall)
                    summaryEvents.onMcpToolCallStarted(toolCall.serverName, toolCall.toolName, argumentsJson)
                    mcpToolGateway.callTool(toolCall.serverName, toolCall.toolName, argumentsJson)
                        .also(summaryEvents::onMcpToolCallCompleted)
                } catch (exception: RuntimeException) {
                    val message = exception.message ?: "Could not call MCP tool."
                    summaryEvents.onMcpToolCallFailed(toolCall.serverName, toolCall.toolName, message)
                    throw AgentException("MCP tool ${toolCall.serverName}/${toolCall.toolName} failed: $message", exception)
                }
            }
            sendRequest(
                buildRequest(
                    requestContext + listOf(
                        ChatMessage(Role.ASSISTANT, firstAnswer),
                        ChatMessage(Role.USER, mcpToolResultsPrompt(toolResults))
                    ),
                    settings
                )
            )
        }
        val answer = JsonTools.extractAssistantContent(finalResponse.body())
            ?: throw AgentException("The model provider returned an empty or unexpected JSON response.")
        val usage = firstUsage + if (finalResponse == response) chat.TokenUsage.ZERO else JsonTools.extractUsage(finalResponse.body())
        val finishReason = JsonTools.extractFinishReason(finalResponse.body())
        val limitReason = ResponseLimitClassifier.classify(
            finishReason = finishReason,
            settings = settings,
            usage = usage
        )
        historyRepository.addAssistant(answer, usage)
        return AgentResponse(answer, usage, finishReason, limitReason)
    }

    private fun sendWithRag(userMessage: String): AgentResponse {
        historyRepository.addUser(userMessage)
        val rag = ragSearchService ?: throw AgentException("RAG is enabled, but the RAG search service is unavailable.")
        val search = try {
            rag.search(userMessage, settings)
        } catch (exception: RuntimeException) {
            throw AgentException(exception.message ?: "RAG search failed.", exception)
        }
        if (search.indexCount == 0) {
            val answer = "$UNKNOWN_RAG_ANSWER\n\nSources:\n- none (no RAG indexes found in ${chat.AppPaths.indicesDirectory()})"
            historyRepository.addAssistant(answer, chat.TokenUsage.ZERO)
            return AgentResponse(answer, chat.TokenUsage.ZERO, "stop")
        }
        if (search.results.isEmpty()) {
            val answer = "$UNKNOWN_RAG_ANSWER\n\nSources:\n- none (RAG indexes loaded: ${search.indexCount}, chunks: ${search.chunkCount})"
            historyRepository.addAssistant(answer, chat.TokenUsage.ZERO)
            return AgentResponse(answer, chat.TokenUsage.ZERO, "stop")
        }

        val response = sendRequest(buildRequest(ragMessages(userMessage, search), settings))
        val answer = JsonTools.extractAssistantContent(response.body())
            ?: throw AgentException("The model provider returned an empty or unexpected JSON response.")
        val usage = JsonTools.extractUsage(response.body())
        val finishReason = JsonTools.extractFinishReason(response.body())
        val content = appendRagSources(answer, search.results)
        val limitReason = ResponseLimitClassifier.classify(
            finishReason = finishReason,
            settings = settings,
            usage = usage
        )
        historyRepository.addAssistant(content, usage)
        return AgentResponse(content, usage, finishReason, limitReason)
    }

    override fun updateSettings(settings: AgentSettings) {
        this.settings = settings
    }

    private fun endpoint(baseUrl: String): URI {
        val normalized = baseUrl.trim().trimEnd('/')
        return URI.create("$normalized/chat/completions")
    }

    private fun buildRequest(history: List<ChatMessage>, settings: AgentSettings): HttpRequest {
        val builder = HttpRequest.newBuilder(endpoint(settings.baseUrl))
            .header("Content-Type", "application/json")
        if (settings.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.apiKey}")
        }
        return builder
            .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(history, settings)))
            .build()
    }

    private fun requestSummary(history: List<ChatMessage>): AgentResponse {
        val response = sendRequest(buildRequest(summaryMessages(history), settings))
        val summary = JsonTools.extractAssistantContent(response.body())
            ?: throw AgentException("The model provider returned an empty or unexpected JSON response for the summary.")
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
        invariantRepository?.contextMessages().orEmpty() +
            memoryRepository?.contextMessages().orEmpty() +
            mcpToolContextMessages()

    private fun ragMessages(question: String, search: RagSearchResponse): List<ChatMessage> =
        listOf(
            ChatMessage(Role.SYSTEM, settings.systemPrompt),
            ChatMessage(
                Role.SYSTEM,
                """
                RAG mode is enabled. Answer using only the RAG context chunks provided by the application.
                Do not use MCP tools, task planning, memory, summaries, sticky facts, or external knowledge to fill gaps.
                If the chunks do not contain enough evidence, answer exactly: $UNKNOWN_RAG_ANSWER
                Keep the answer concise and preserve the user's language.
                """.trimIndent()
            ),
            ChatMessage(Role.USER, ragUserPrompt(question, search.results))
        )

    private fun ragUserPrompt(question: String, results: List<SearchResult>): String = buildString {
        appendLine("RAG context chunks:")
        results.forEachIndexed { index, result ->
            val metadata = result.chunk.metadata
            appendLine()
            appendLine("[${index + 1}] chunk_id=${metadata.chunkId}, score=${java.lang.String.format(java.util.Locale.US, "%.4f", result.score)}")
            appendLine("Title: ${metadata.title}")
            metadata.section?.let { appendLine("Section: $it") }
            appendLine("Source: ${metadata.source}")
            appendLine("Text:")
            appendLine(result.chunk.text)
        }
        appendLine()
        appendLine("Question:")
        appendLine(question)
    }

    private fun appendRagSources(answer: String, results: List<SearchResult>): String {
        val sources = results.joinToString(separator = "\n") { result ->
            val metadata = result.chunk.metadata
            val section = metadata.section?.let { ", section=$it" }.orEmpty()
            "- ${metadata.chunkId}: ${metadata.title}$section, source=${metadata.source}, score=${java.lang.String.format(java.util.Locale.US, "%.4f", result.score)}"
        }
        return "${answer.trim()}\n\nSources:\n$sources"
    }

    private fun mcpToolContextMessages(): List<ChatMessage> {
        val gateway = mcpToolGateway ?: return emptyList()
        val tools = runCatching { gateway.availableTools() }.getOrElse { emptyList() }
        if (tools.isEmpty()) return emptyList()
        val toolList = tools.joinToString("\n") { tool ->
            val schema = tool.inputSchema?.takeIf { it.isNotBlank() } ?: "{}"
            "- ${tool.serverName}/${tool.name}: ${tool.description.orEmpty()} inputSchema=$schema"
        }
        return listOf(
            ChatMessage(
                Role.SYSTEM,
                """
                MCP tools are available. Use them when they can answer the user's request better than guessing.
                To request one MCP tool call, respond only with compact JSON in this form:
                {"mcpToolCall":{"server":"serverName","tool":"toolName","arguments":{}}}
                To request multiple independent MCP tool calls, respond only with compact JSON in this form:
                {"mcpToolCalls":[{"server":"serverName","tool":"toolName","arguments":{}}]}
                Treat each inputSchema as a strict contract. Use only declared properties and satisfy required fields, JSON types, minLength, maxLength, pattern, minimum, and maximum constraints exactly.
                Available MCP tools:
                $toolList
                """.trimIndent()
            )
        )
    }

    private fun parseMcpToolCalls(content: String): List<McpRequestedToolCall> {
        val trimmed = content.trim().withoutMarkdownFence()
        val root = runCatching { McpJson.parse(trimmed).asObject() }.getOrNull() ?: return emptyList()
        val multiple = root["mcpToolCalls"]?.asArray().orEmpty()
            .mapNotNull { value -> parseMcpToolCall(value.asObject()) }
        if (multiple.isNotEmpty()) return multiple
        return listOfNotNull(parseMcpToolCall(root["mcpToolCall"]?.asObject()))
    }

    private fun parseMcpToolCall(call: Map<String, JsonValue>?): McpRequestedToolCall? {
        call ?: return null
        val server = call["server"]?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val tool = call["tool"]?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val arguments = call["arguments"] ?: return null
        return McpRequestedToolCall(server, tool, McpJson.stringify(arguments))
    }

    private fun sanitizedMcpArguments(toolCall: McpRequestedToolCall): String {
        val tool = runCatching {
            mcpToolGateway?.availableTools()?.firstOrNull {
                it.serverName == toolCall.serverName && it.name == toolCall.toolName
            }
        }.getOrNull()
        return McpToolArgumentSanitizer.sanitize(toolCall.argumentsJson, tool)
    }

    private data class McpRequestedToolCall(
        val serverName: String,
        val toolName: String,
        val argumentsJson: String
    )

    private companion object {
        const val UNKNOWN_RAG_ANSWER = "\u041d\u0435 \u0437\u043d\u0430\u044e"
    }

    private fun mcpToolResultsPrompt(toolResults: List<McpToolCallResult>): String = buildString {
        appendLine("MCP tool results:")
        toolResults.forEach { result ->
            appendLine()
            appendLine("MCP tool result for ${result.serverName}/${result.toolName}${if (result.isError) " (error)" else ""}:")
            appendLine(result.content)
        }
        appendLine()
        appendLine("Use these tool results to answer the user's original request. Do not request another MCP tool call.")
    }.trim()

    private fun String.withoutMarkdownFence(): String {
        if (!startsWith("```")) return this
        return lines()
            .drop(1)
            .dropLast(1)
            .joinToString("\n")
            .trim()
    }

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
                "Could not connect to the model provider. Check the internet connection and base URL.",
                exception
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentException("The model provider request was interrupted.", exception)
        } catch (exception: IllegalArgumentException) {
            throw AgentException("Invalid model provider URL: ${settings.baseUrl}", exception)
        }

        if (response.statusCode() !in 200..299) {
            if (JsonTools.looksLikeContextLimitError(response.body())) {
                throw AgentException(
                    "The chat history exceeded the model context window. Clear history with /clear or start a shorter conversation."
                )
            }
            throw AgentException("The model provider returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }

        return response
    }

    private fun buildRequestBody(history: List<ChatMessage>, settings: AgentSettings): String {
        val model = settings.model.takeIf { it.isNotBlank() }
            ?: throw AgentException("No model is selected. Run /models to load provider models, then choose one in /settings.")
        val messages = history
            .filter { it.role == Role.SYSTEM || it.role == Role.USER || it.role == Role.ASSISTANT }
            .joinToString(separator = ",") { message ->
                """{"role":"${JsonTools.escape(message.role.apiName)}","content":"${JsonTools.escape(message.content)}"}"""
            }

        val thinkingType = if (settings.thinkingMode) "enabled" else "disabled"
        val fields = mutableListOf(
            """"model": "${JsonTools.escape(model)}"""",
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

typealias ModelProviderAiAgent = DeepSeekAiAgent
