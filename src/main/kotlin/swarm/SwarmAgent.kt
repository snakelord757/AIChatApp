package swarm

import chat.ChatMessage
import chat.Role
import chat.TokenUsage
import task.StageChatClient
import task.StageInput
import task.TaskDomainClassifier

class SwarmAgent(
    private val config: SwarmAgentConfig,
    private val client: StageChatClient
) {
    val role: SwarmRole = config.role

    fun respond(input: StageInput, round: Int, dialogue: SwarmDialogue): SwarmMessage {
        val messages = listOf(
            ChatMessage(Role.SYSTEM, role.systemPrompt),
            ChatMessage(Role.USER, prompt(input, round, dialogue))
        )
        val response = client.send(messages)
        val parsed = parseResponse(response.content, response.usage, round)
        if (TaskDomainClassifier.isProgrammingTask(input.userTask) || !NonCodeSwarmGuard.containsSoftwareArtifacts(parsed)) {
            return parsed
        }

        val correctedResponse = client.send(
            messages + ChatMessage(Role.ASSISTANT, response.content) + ChatMessage(
                Role.USER,
                """
                Your previous response incorrectly turned a non-software task into software implementation.
                Rewrite the same role contribution as domain-level planning only.
                Do not mention software modules, data classes, functions, interfaces, engines, registries, APIs, JSON state, implementation languages, code examples, internal stage agents, handoff, checkpoints, or architectural constraints.
                Return JSON in the required shape only.
                """.trimIndent()
            )
        )
        val corrected = parseResponse(correctedResponse.content, response.usage + correctedResponse.usage, round)
        return if (NonCodeSwarmGuard.containsSoftwareArtifacts(corrected)) {
            NonCodeSwarmGuard.sanitize(corrected)
        } else {
            corrected
        }
    }

    private fun prompt(input: StageInput, round: Int, dialogue: SwarmDialogue): String = buildString {
        appendLine("Round: $round")
        appendLine("Return JSON exactly like:")
        appendLine("""{"role":"${role.displayName}","stance":"approve","summary":"...","proposal":"...","concerns":[],"requiredChanges":[],"invariantConcerns":[]}""")
        appendLine("Allowed stances: approve, revise, block.")
        appendLine("Participate actively from your role. Do not answer with mere agreement.")
        appendLine("Your proposal must add role-specific substance: a refinement, constraint, acceptance criterion, structural decision, execution step, risk, or mitigation.")
        appendLine("Use approve only when your role has no required changes left after adding its own contribution.")
        appendLine("Use revise when another role should inspect, refine, or challenge a material point.")
        appendLine("Round 1 can reach consensus if every role gives substantive approve. Later rounds resolve revise or block feedback.")
        appendLine("Apply workingContext only when it is directly relevant to this task domain.")
        appendLine("Do not infer that a non-programming task requires code, Kotlin modules, classes, APIs, algorithms, or implementation architecture merely because memory mentions programming languages or invariants mention code.")
        appendLine("If the task is not explicitly about software, do not propose software modules, data classes, functions, interfaces, engines, registries, APIs, implementation languages, code examples, internal stage agents, handoff, checkpoints, or architectural constraints.")
        appendLine("For travel, personal advice, business, writing, or research tasks, keep the plan in that domain unless the user explicitly asks for software implementation.")
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
        appendLine("Previous stage result:")
        appendLine(input.previousResult?.output ?: "none")
        appendLine()
        appendLine("Prior stage outputs:")
        appendLine(input.results.joinToString("\n\n") { "[${it.stage}]\nsummary: ${it.summary}\noutput:\n${it.output}" }.ifBlank { "none" })
        appendLine()
        appendLine("Swarm dialogue so far:")
        appendLine(dialogue.toEventText().ifBlank { "none" })
    }

    private fun parseResponse(content: String, usage: TokenUsage, round: Int): SwarmMessage {
        val json = content.stripJsonFence()
        return SwarmMessage(
            role = role,
            round = round,
            stance = json.extractString("stance")?.lowercase()?.takeIf { it in setOf("approve", "revise", "block") } ?: "revise",
            summary = json.extractString("summary") ?: json.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
            proposal = json.extractString("proposal") ?: content,
            concerns = json.extractArray("concerns"),
            requiredChanges = json.extractArray("requiredChanges"),
            invariantConcerns = json.extractArray("invariantConcerns"),
            rawContent = content,
            tokenUsage = usage
        )
    }
}

internal fun String.stripJsonFence(): String =
    trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

internal fun String.extractString(key: String): String? =
    valueStart(key)
        ?.takeIf { it < length && this[it] == '"' }
        ?.let { readJsonString(it)?.value }

internal fun String.extractBoolean(key: String): Boolean? {
    val index = valueStart(key) ?: return null
    return when {
        startsWith("true", index, ignoreCase = true) -> true
        startsWith("false", index, ignoreCase = true) -> false
        else -> null
    }
}

internal fun String.extractArray(key: String): List<String> {
    var index = valueStart(key) ?: return emptyList()
    if (index >= length || this[index] != '[') return emptyList()
    index++
    val values = mutableListOf<String>()
    while (index < length) {
        index = skipWhitespace(index)
        when {
            index >= length -> return values
            this[index] == ']' -> return values
            this[index] == ',' -> index++
            this[index] == '"' -> {
                val read = readJsonString(index) ?: return values
                values += read.value
                index = read.nextIndex
            }
            else -> index++
        }
    }
    return values
}

private fun String.valueStart(key: String): Int? {
    var searchFrom = 0
    while (searchFrom < length) {
        val keyStart = indexOf("\"$key\"", startIndex = searchFrom)
        if (keyStart < 0) return null
        var index = keyStart + key.length + 2
        index = skipWhitespace(index)
        if (index < length && this[index] == ':') return skipWhitespace(index + 1)
        searchFrom = keyStart + 1
    }
    return null
}

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
