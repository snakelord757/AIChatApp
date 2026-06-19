package task

import agent.AgentException
import agent.AgentSettings
import agent.JsonTools
import agent.ResponseLimitClassifier
import chat.ChatMessage
import chat.Role
import chat.TokenUsage
import formatting.CliPromptMarkerNormalizer
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
}

interface StageChatClient {
    fun send(messages: List<ChatMessage>): StageChatResponse
}

data class StageChatResponse(
    val content: String,
    val usage: TokenUsage = TokenUsage.ZERO,
    val finishReason: String? = null
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
    private val clientFactory: () -> StageChatClient
) : StageAgentFactory {
    override fun create(stage: TaskStage): StageAgent =
        PromptedStageAgent(stage, systemPrompt(stage), clientFactory())

    private fun systemPrompt(stage: TaskStage): String = when (stage) {
        TaskStage.PLANNING -> """
            You are PlanningAgent.
            Your only job is to create an execution plan for the user's prompt.
            Check the assistant invariants in working context before producing output.
            If the prompt conflicts with an invariant, return success false, explain only the conflicting part in output, and propose the nearest compliant alternative.
            Do not use Markdown blockquotes or lines starting with >; that marker is reserved for the CLI input prompt.
            Do NOT answer the user's prompt directly.
            Do NOT provide final deliverables, final code, final prose, or the finished solution.
            Do NOT address ExecutionAgent directly and do NOT write command-like prompts such as "ExecutionAgent, do ...".
            If the user asks for code, your plan may mention that ExecutionAgent must produce code, but you must not write that code.
            The output field must contain only a concise neutral plan with required deliverables and constraints.
            The summary field must describe the plan, not the final answer.
        """.trimIndent()
        TaskStage.EXECUTION -> """
            You are ExecutionAgent.
            Your job is to execute the PlanningAgent output and produce the actual draft answer or deliverable requested by the user.
            Do not use Markdown blockquotes or lines starting with >; that marker is reserved for the CLI input prompt.
            Treat the PLANNING result as instructions for your work.
            Do not merely repeat the plan.
            If the user requested code, this is the stage that writes the code.
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
            Do not solve the task from scratch.
            Do not use PlanningAgent output as the final answer.
            If validation succeeded, base the final answer primarily on ExecutionAgent output.
            The output field must contain only the final answer to show the user.
        """.trimIndent()
    }
}

class DeepSeekStageChatClient(
    private var settings: AgentSettings,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
) : StageChatClient {
    override fun send(messages: List<ChatMessage>): StageChatResponse {
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
        val content = JsonTools.extractAssistantContent(response.body())
            ?: throw AgentException("DeepSeek returned an empty or unexpected JSON response.")
        val usage = JsonTools.extractUsage(response.body())
        ResponseLimitClassifier.classify(JsonTools.extractFinishReason(response.body()), settings, usage)
        return StageChatResponse(content, usage, JsonTools.extractFinishReason(response.body()))
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
}
