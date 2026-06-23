package task

import agent.AgentException
import agent.AgentSettings
import agent.JsonTools
import agent.ResponseLimitClassifier
import chat.ChatMessage
import chat.Role
import chat.TokenUsage
import formatting.CliPromptMarkerNormalizer
import mcp.McpJson
import mcp.McpTool
import mcp.McpToolArgumentSanitizer
import mcp.McpToolGateway
import mcp.asObject
import mcp.asString
import swarm.PlanningSwarmStageAgent
import swarm.SwarmEventSink
import swarm.SwarmRole
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

interface StageAgent {
    val stage: TaskStage
    val history: List<ChatMessage>
    fun execute(input: StageInput): StageResult
}

interface StageAgentFactory {
    fun create(stage: TaskStage): StageAgent
    fun create(stage: TaskStage, eventSink: SwarmEventSink): StageAgent = create(stage)
}

interface StageChatClient {
    fun send(messages: List<ChatMessage>): StageChatResponse
}

data class StageChatResponse(
    val content: String,
    val usage: TokenUsage = TokenUsage.ZERO,
    val finishReason: String? = null,
    val events: List<String> = emptyList()
)

class PromptedStageAgent(
    override val stage: TaskStage,
    private val systemPrompt: String,
    private val client: StageChatClient
) : StageAgent {
    private val messages = mutableListOf<ChatMessage>()

    override val history: List<ChatMessage>
        get() = messages.toList()

    override fun execute(input: StageInput): StageResult {
        if (messages.isEmpty()) {
            messages += ChatMessage(Role.SYSTEM, systemPrompt)
        }
        messages += ChatMessage(Role.USER, stagePrompt(input))
        val response = client.send(messages)
        response.events.forEach { messages += ChatMessage(Role.EVENT, it) }
        messages += ChatMessage(Role.ASSISTANT, response.content, response.usage)
        return parseStageResult(response)
    }

    private fun stagePrompt(input: StageInput): String = buildString {
        appendLine("Return ONLY valid JSON in this shape:")
        appendLine("""{"success":true,"summary":"short summary","output":"human-readable result","issues":[],"requestedChanges":[],"retryReason":null}""")
        appendLine("The output field must be readable text for the user or next stage, not escaped diagnostic context.")
        appendLine("Do not use Markdown blockquotes or lines starting with >; that marker is reserved for the CLI input prompt.")
        appendLine("Follow your stage contract exactly. Do not perform work assigned to another stage.")
        appendLine()
        appendLine("Task:")
        appendLine(input.userTask)
        appendLine()
        appendLine("Working context allowed by orchestrator:")
        appendLine(input.workingContext.ifBlank { "none" })
        appendLine()
        appendLine("Clarifications:")
        appendLine(input.clarifications.joinToString("\n").ifBlank { "none" })
        appendLine()
        appendLine("Previous result:")
        appendLine(input.previousResult?.output ?: "none")
        appendLine()
        appendLine("Prior stage outputs:")
        appendLine(input.results.joinToString("\n\n") { result ->
            "[${result.stage}]\nsummary: ${result.summary}\noutput:\n${result.output}"
        }.ifBlank { "none" })
        appendLine()
        appendLine("All stage summaries:")
        appendLine(input.results.joinToString("\n") { "${it.stage}: ${it.summary}" }.ifBlank { "none" })
    }

    private fun parseStageResult(response: StageChatResponse): StageResult {
        val content = response.content.stripJsonFence()
        val success = extractBoolean(content, "success") ?: true
        val output = CliPromptMarkerNormalizer.normalizeGeneratedText(extractString(content, "output") ?: content)
        val summary = extractString(content, "summary")
            ?.let(CliPromptMarkerNormalizer::normalizeGeneratedText)
            ?: output.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
            ?: stage.name
        return StageResult(
            stage = stage,
            success = success,
            summary = summary,
            output = output,
            issues = extractArray(content, "issues").map(CliPromptMarkerNormalizer::normalizeGeneratedText),
            requestedChanges = extractArray(content, "requestedChanges").map(CliPromptMarkerNormalizer::normalizeGeneratedText),
            retryReason = extractString(content, "retryReason")?.let(CliPromptMarkerNormalizer::normalizeGeneratedText),
            tokenUsage = response.usage
        )
    }

    private fun extractArray(json: String, key: String): List<String> {
        var index = valueStart(json, key) ?: return emptyList()
        if (index >= json.length || json[index] != '[') return emptyList()
        index++
        val values = mutableListOf<String>()
        while (index < json.length) {
            index = json.skipWhitespace(index)
            when {
                index >= json.length -> return values
                json[index] == ']' -> return values
                json[index] == ',' -> index++
                json[index] == '"' -> {
                    val read = json.readJsonString(index) ?: return values
                    values += read.value
                    index = read.nextIndex
                }
                else -> index++
            }
        }
        return values
    }

    private fun extractString(json: String, key: String): String? =
        valueStart(json, key)
            ?.takeIf { it < json.length && json[it] == '"' }
            ?.let { json.readJsonString(it)?.value }

    private fun extractBoolean(json: String, key: String): Boolean? {
        val index = valueStart(json, key) ?: return null
        return when {
            json.startsWith("true", index, ignoreCase = true) -> true
            json.startsWith("false", index, ignoreCase = true) -> false
            else -> null
        }
    }

    private fun valueStart(json: String, key: String): Int? {
        var searchFrom = 0
        while (searchFrom < json.length) {
            val keyStart = json.indexOf("\"$key\"", startIndex = searchFrom)
            if (keyStart < 0) return null
            var index = keyStart + key.length + 2
            index = json.skipWhitespace(index)
            if (index < json.length && json[index] == ':') {
                return json.skipWhitespace(index + 1)
            }
            searchFrom = keyStart + 1
        }
        return null
    }

    private fun String.stripJsonFence(): String =
        trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

    private fun String.readJsonString(quoteIndex: Int): JsonString? {
        if (quoteIndex >= length || this[quoteIndex] != '"') return null
        val result = StringBuilder()
        var index = quoteIndex + 1
        while (index < length) {
            val char = this[index]
            when {
                char == '"' -> return JsonString(result.toString(), index + 1)
                char == '\\' && index + 1 < length -> {
                    index++
                    result.append(
                        when (val escaped = this[index]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'b' -> '\b'
                            'f' -> '\u000C'
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'u' -> {
                                val hex = substringOrNull(index + 1, index + 5)
                                if (hex != null) {
                                    index += 4
                                    hex.toIntOrNull(16)?.toChar() ?: 'u'
                                } else {
                                    'u'
                                }
                            }
                            else -> escaped
                        }
                    )
                }
                else -> result.append(char)
            }
            index++
        }
        return null
    }

    private fun String.skipWhitespace(startIndex: Int): Int {
        var index = startIndex
        while (index < length && this[index].isWhitespace()) index++
        return index
    }

    private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? =
        if (startIndex >= 0 && endIndex <= length && startIndex <= endIndex) substring(startIndex, endIndex) else null

    private data class JsonString(
        val value: String,
        val nextIndex: Int
    )
}

class DefaultStageAgentFactory(
    private val settingsProvider: () -> AgentSettings? = { null },
    private val swarmClientFactory: ((SwarmRole) -> StageChatClient)? = null,
    private val swarmSynthesizerClientFactory: (() -> StageChatClient)? = null,
    private val swarmEventSink: SwarmEventSink = SwarmEventSink.None,
    private val swarmSessionStore: swarm.SwarmSessionStore = swarm.SwarmSessionStore.None,
    private val mcpToolsProvider: () -> List<McpTool> = { emptyList() },
    private val stageClientFactory: ((TaskStage) -> StageChatClient)? = null,
    private val clientFactory: () -> StageChatClient
) : StageAgentFactory {
    override fun create(stage: TaskStage): StageAgent = create(stage, swarmEventSink)

    override fun create(stage: TaskStage, eventSink: SwarmEventSink): StageAgent =
        if (stage == TaskStage.PLANNING && settingsProvider()?.planningSwarmEnabled == true) {
            PlanningSwarmStageAgent(
                clientFactory = swarmClientFactory ?: { clientFactory() },
                synthesizerClientFactory = swarmSynthesizerClientFactory ?: clientFactory,
                eventSink = eventSink,
                sessionStore = swarmSessionStore
            )
        } else {
            PromptedStageAgent(stage, systemPrompt(stage), stageClientFactory?.invoke(stage) ?: clientFactory())
        }

    private fun systemPrompt(stage: TaskStage): String = when (stage) {
        TaskStage.PLANNING -> """
            You are PlanningAgent.
            Your only job is to create an execution plan for the user's prompt.
            Check the assistant invariants in working context before producing output.
            Apply working context only when it is directly relevant to the user's current task domain.
            Do not convert non-code requests into programming tasks because memory mentions programming languages or invariants mention code.
            For travel, personal advice, business, writing, or research tasks, keep the plan in that domain unless the user explicitly asks for software implementation.
            If the user explicitly asks to ignore, bypass, remove, or violate an invariant, return success false.
            If the user explicitly requires a named option that an invariant forbids, return success false instead of silently replacing that named requirement.
            For broad requests such as "use several languages" or "cover popular tools", do not fail only because some possible options are disallowed; plan a compliant subset or the single allowed option when the user did not explicitly name and require a forbidden option.
            Return success false when no meaningful compliant version of the task can be planned, when the user explicitly forbids the compliant adaptation, or when the request directly requires a forbidden action.
            When you adapt the task to invariants, keep success true and state the adaptation in the plan for ExecutionAgent.
            Do not instruct later stages to tell the user about invariant limitations unless the user's prompt explicitly requested something that violates an invariant and no quiet compliant adaptation is possible.
            Do not use Markdown blockquotes or lines starting with >; that marker is reserved for the CLI input prompt.
            Do NOT answer the user's prompt directly.
            Do NOT provide final deliverables, final code, final prose, or the finished solution.
            Do NOT address ExecutionAgent directly and do NOT write command-like prompts such as "ExecutionAgent, do ...".
            If the user asks for code, your plan may mention that ExecutionAgent must produce code, but you must not write that code.
            If available MCP tools can help answer the user accurately, include the exact server/tool name and suggested JSON arguments for ExecutionAgent in the plan.
            Use only argument fields declared by the tool inputSchema. If inputSchema.properties is empty, suggested JSON arguments must be {}.
            Do not request or call MCP tools yourself in PlanningAgent; only decide whether ExecutionAgent should use them.
            If no MCP tool is relevant, say that no MCP tool is needed.
            The output field must contain only a concise neutral plan with required deliverables and constraints.
            The summary field must describe the plan, not the final answer.
            ${planningMcpToolCatalog()}
        """.trimIndent()
        TaskStage.EXECUTION -> """
            You are ExecutionAgent.
            Your job is to execute the PlanningAgent output and produce the actual draft answer or deliverable requested by the user.
            Do not use Markdown blockquotes or lines starting with >; that marker is reserved for the CLI input prompt.
            Treat the PLANNING result as instructions for your work.
            Do not merely repeat the plan.
            If the user requested code, this is the stage that writes the code.
            If the plan identifies a relevant MCP tool, use it before drafting the answer.
            If another available MCP tool is clearly needed and relevant, you may use it.
            When requesting an MCP tool call, use only argument fields declared by the tool inputSchema. If inputSchema.properties is empty, pass {}.
            The output field must contain the completed draft deliverable for ValidationAgent.
        """.trimIndent()
        TaskStage.VALIDATION -> """
            You are ValidationAgent.
            Your job is to validate the ExecutionAgent output against the original user prompt and the PlanningAgent plan.
            Check the assistant invariants in working context before producing output.
            If the execution output violates an invariant, return success false and put the violation in issues and requestedChanges.
            Do not use Markdown blockquotes or lines starting with >; that marker is reserved for the CLI input prompt.
            Return ONLY validation metadata, never the solution itself.
            Do NOT produce a new final answer, Do NOT improve the answer, and Do NOT copy or quote the execution output.
            The summary field must be one short sentence about the validation result.
            The output field must contain ONLY a tiny validation note: maximum 2 short lines and maximum 300 characters total.
            The output field must not contain Markdown headings, tables, code blocks, long explanations, examples, or user-facing final prose.
            If the execution is acceptable, return success true and use output like "Validation passed. Requirements are covered."
            If problems remain, return success false; put details in issues and requestedChanges, not in output.
            Each issues/requestedChanges item must be concise and describe only what failed or must change.
        """.trimIndent()
        TaskStage.COMPLETION -> """
            You are CompletionAgent.
            Your job is to compose the final user-facing answer from the ExecutionAgent output, using ValidationAgent feedback.
            Do not use Markdown blockquotes or lines starting with >; that marker is reserved for the CLI input prompt.
            Do not mention assistant invariants, internal constraints, or hidden policy-driven adaptations unless the final result is a refusal or the user explicitly asked about those constraints.
            Do not solve the task from scratch.
            Do not use PlanningAgent output as the final answer.
            If validation succeeded, base the final answer primarily on ExecutionAgent output.
            The output field must contain only the final answer to show the user.
        """.trimIndent()
    }

    private fun planningMcpToolCatalog(): String {
        val tools = runCatching { mcpToolsProvider() }.getOrElse { emptyList() }
        if (tools.isEmpty()) return "Available MCP tools: none."
        return buildString {
            appendLine("Available MCP tools for ExecutionAgent:")
            tools.forEach { tool ->
                append("- ${tool.serverName}/${tool.name}")
                tool.description?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                tool.inputSchema?.takeIf { it.isNotBlank() }?.let { append(" inputSchema=$it") }
                appendLine()
            }
        }.trimEnd()
    }
}

class DeepSeekStageChatClient(
    private var settings: AgentSettings,
    private val mcpToolGateway: McpToolGateway? = null,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
) : StageChatClient {
    override fun send(messages: List<ChatMessage>): StageChatResponse {
        val requestMessages = messages + mcpToolContextMessages()
        val response = sendRequest(requestMessages)
        val firstContent = JsonTools.extractAssistantContent(response.body())
            ?: throw AgentException("DeepSeek returned an empty or unexpected JSON response.")
        val firstUsage = JsonTools.extractUsage(response.body())
        val toolCall = parseMcpToolCall(firstContent)
        if (toolCall == null || mcpToolGateway == null) {
            ResponseLimitClassifier.classify(JsonTools.extractFinishReason(response.body()), settings, firstUsage)
            return StageChatResponse(firstContent, firstUsage, JsonTools.extractFinishReason(response.body()))
        }

        val events = mutableListOf<String>()
        val argumentsJson = sanitizedMcpArguments(toolCall)
        events += mcpToolStartedEvent(toolCall.serverName, toolCall.toolName, argumentsJson)
        val toolResult = try {
            mcpToolGateway.callTool(toolCall.serverName, toolCall.toolName, argumentsJson)
        } catch (exception: RuntimeException) {
            val message = exception.message ?: "Could not call MCP tool."
            events += mcpToolFailedEvent(toolCall.serverName, toolCall.toolName, message)
            throw AgentException("MCP tool ${toolCall.serverName}/${toolCall.toolName} failed: $message", exception)
        }
        events += mcpToolCompletedEvent(toolResult.serverName, toolResult.toolName, toolResult.content, toolResult.isError)
        val finalResponse = sendRequest(
            requestMessages + listOf(
                ChatMessage(Role.ASSISTANT, firstContent),
                ChatMessage(
                    Role.USER,
                    """
                    MCP tool result for ${toolResult.serverName}/${toolResult.toolName}:
                    ${toolResult.content}

                    Continue the current stage using this tool result. Return ONLY the valid JSON required by the stage contract. Do not request another MCP tool call.
                    """.trimIndent()
                )
            )
        )
        val content = JsonTools.extractAssistantContent(finalResponse.body())
            ?: throw AgentException("DeepSeek returned an empty or unexpected JSON response.")
        val usage = firstUsage + JsonTools.extractUsage(finalResponse.body())
        ResponseLimitClassifier.classify(JsonTools.extractFinishReason(finalResponse.body()), settings, usage)
        return StageChatResponse(content, usage, JsonTools.extractFinishReason(finalResponse.body()), events)
    }

    fun updateSettings(settings: AgentSettings) {
        this.settings = settings
    }

    private fun buildRequest(messages: List<ChatMessage>): HttpRequest =
        HttpRequest.newBuilder(endpoint(settings.baseUrl))
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(messages)))
            .build()

    private fun sendRequest(messages: List<ChatMessage>): HttpResponse<String> {
        val response = try {
            httpClient.send(buildRequest(messages), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (exception: IOException) {
            throw AgentException("Could not connect to DeepSeek. Check the internet connection and base URL.", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentException("The DeepSeek request was interrupted.", exception)
        }
        if (response.statusCode() !in 200..299) {
            throw AgentException("DeepSeek returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }
        return response
    }

    private fun endpoint(baseUrl: String): URI = URI.create("${baseUrl.trim().trimEnd('/')}/chat/completions")

    private fun buildRequestBody(messages: List<ChatMessage>): String {
        val payloadMessages = messages
            .filter { it.role == Role.SYSTEM || it.role == Role.USER || it.role == Role.ASSISTANT }
            .joinToString(",") { """{"role":"${JsonTools.escape(it.role.apiName)}","content":"${JsonTools.escape(it.content)}"}""" }
        val fields = mutableListOf(
            """"model": "${JsonTools.escape(settings.model)}"""",
            """"messages": [$payloadMessages]""",
            """"thinking": {"type": "${if (settings.thinkingMode) "enabled" else "disabled"}"}"""
        )
        if (settings.thinkingMode) fields += """"reasoning_effort": "high"""" else fields += """"temperature": ${settings.temperature}"""
        if (settings.maxTokens > 0) fields += """"max_tokens": ${settings.maxTokens}"""
        return fields.joinToString(",\n  ", "{\n  ", "\n}")
    }

    private fun mcpToolContextMessages(): List<ChatMessage> {
        val gateway = mcpToolGateway ?: return emptyList()
        val tools = runCatching { gateway.availableTools() }.getOrElse { emptyList() }
        if (tools.isEmpty()) return emptyList()
        return listOf(
            ChatMessage(
                Role.SYSTEM,
                """
                MCP tools are available. Use them when they can help complete the current stage.
                To request exactly one MCP tool call, respond only with compact JSON in this form:
                {"mcpToolCall":{"server":"serverName","tool":"toolName","arguments":{}}}
                Use only argument fields declared by the tool inputSchema. If inputSchema.properties is empty, arguments must be {}.
                Available MCP tools:
                ${tools.joinToString("\n", transform = ::toolDescription)}
                """.trimIndent()
            )
        )
    }

    private fun toolDescription(tool: McpTool): String =
        "- ${tool.serverName}/${tool.name}: ${tool.description.orEmpty()} inputSchema=${tool.inputSchema ?: "{}"}"

    private fun sanitizedMcpArguments(toolCall: McpRequestedToolCall): String {
        val tool = runCatching {
            mcpToolGateway?.availableTools()?.firstOrNull {
                it.serverName == toolCall.serverName && it.name == toolCall.toolName
            }
        }.getOrNull()
        return McpToolArgumentSanitizer.sanitize(toolCall.argumentsJson, tool)
    }

    private fun parseMcpToolCall(content: String): McpRequestedToolCall? {
        val root = runCatching { McpJson.parse(content.trim().withoutMarkdownFence()).asObject() }.getOrNull() ?: return null
        val call = root["mcpToolCall"]?.asObject() ?: return null
        val server = call["server"]?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val tool = call["tool"]?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val arguments = call["arguments"] ?: return null
        return McpRequestedToolCall(server, tool, McpJson.stringify(arguments))
    }

    private fun mcpToolStartedEvent(serverName: String, toolName: String, argumentsJson: String): String =
        "Calling MCP tool $serverName/$toolName with arguments: $argumentsJson"

    private fun mcpToolCompletedEvent(serverName: String, toolName: String, content: String, isError: Boolean): String =
        "MCP tool $serverName/$toolName completed${if (isError) " with error" else ""}. Result: ${content.take(500)}"

    private fun mcpToolFailedEvent(serverName: String, toolName: String, message: String): String =
        "MCP tool $serverName/$toolName failed: $message"

    private fun String.withoutMarkdownFence(): String {
        if (!startsWith("```")) return this
        return lines().drop(1).dropLast(1).joinToString("\n").trim()
    }

    private data class McpRequestedToolCall(
        val serverName: String,
        val toolName: String,
        val argumentsJson: String
    )
}
