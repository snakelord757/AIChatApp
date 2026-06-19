package swarm

import agent.JsonTools
import chat.TokenUsage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class JsonSwarmSessionStore(
    private val path: Path
) : SwarmSessionStore {
    override fun read(taskId: String): SwarmDialogue? {
        if (!Files.exists(path)) return null
        val content = Files.readString(path, StandardCharsets.UTF_8)
        if (content.field("taskId") != taskId) return null
        return decodeDialogue(content)
    }

    override fun write(session: SwarmSession) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, encode(session), StandardCharsets.UTF_8)
    }

    override fun clear(taskId: String) {
        if (!Files.exists(path)) return
        val content = Files.readString(path, StandardCharsets.UTF_8)
        if (content.field("taskId") == taskId) {
            Files.deleteIfExists(path)
        }
    }

    private fun encode(session: SwarmSession): String = buildString {
        appendLine("{")
        appendLine("""  "taskId": "${JsonTools.escape(session.taskId)}",""")
        appendLine("""  "rounds": [""")
        session.dialogue.rounds.forEachIndexed { roundIndex, round ->
            appendLine("""    {"index":${round.index},"messages":[""")
            round.messages.forEachIndexed { messageIndex, message ->
                append(encodeMessage(message).prependIndent("      "))
                if (messageIndex < round.messages.lastIndex) append(",")
                appendLine()
            }
            append("    ]}")
            if (roundIndex < session.dialogue.rounds.lastIndex) append(",")
            appendLine()
        }
        appendLine("""  ]""")
        appendLine("}")
    }

    private fun encodeMessage(message: SwarmMessage): String = buildString {
        append("{")
        append(""""role":"${message.role.name}",""")
        append(""""round":${message.round},""")
        append(""""stance":"${JsonTools.escape(message.stance)}",""")
        append(""""summary":"${JsonTools.escape(message.summary)}",""")
        append(""""proposal":"${JsonTools.escape(message.proposal)}",""")
        append(""""concerns":${encodeArray(message.concerns)},""")
        append(""""requiredChanges":${encodeArray(message.requiredChanges)},""")
        append(""""invariantConcerns":${encodeArray(message.invariantConcerns)},""")
        append(""""rawContent":"${JsonTools.escape(message.rawContent)}",""")
        append(""""tokenUsage":{"inputTokens":${message.tokenUsage.inputTokens},"outputTokens":${message.tokenUsage.outputTokens},"reasoningTokens":${message.tokenUsage.reasoningTokens}}""")
        append("}")
    }

    private fun encodeArray(values: List<String>): String =
        values.joinToString(",", "[", "]") { """"${JsonTools.escape(it)}"""" }

    private fun decodeDialogue(json: String): SwarmDialogue {
        val roundsStart = json.indexOf("\"rounds\"").takeIf { it >= 0 }?.let { json.indexOf('[', it) } ?: return SwarmDialogue()
        val roundsEnd = matchingBracket(json, roundsStart, '[', ']') ?: return SwarmDialogue()
        val rounds = splitObjects(json.substring(roundsStart + 1, roundsEnd)).mapNotNull(::decodeRound)
        return SwarmDialogue(rounds)
    }

    private fun decodeRound(json: String): SwarmRound? {
        val index = json.longField("index").toInt().takeIf { it > 0 } ?: return null
        val messagesStart = json.indexOf("\"messages\"").takeIf { it >= 0 }?.let { json.indexOf('[', it) } ?: return SwarmRound(index, emptyList())
        val messagesEnd = matchingBracket(json, messagesStart, '[', ']') ?: return SwarmRound(index, emptyList())
        return SwarmRound(index, splitObjects(json.substring(messagesStart + 1, messagesEnd)).mapNotNull(::decodeMessage))
    }

    private fun decodeMessage(json: String): SwarmMessage? {
        val role = json.field("role")?.let { runCatching { SwarmRole.valueOf(it) }.getOrNull() } ?: return null
        return SwarmMessage(
            role = role,
            round = json.longField("round").toInt().takeIf { it > 0 } ?: 1,
            stance = json.field("stance").orEmpty(),
            summary = json.field("summary").orEmpty(),
            proposal = json.field("proposal").orEmpty(),
            concerns = json.arrayField("concerns"),
            requiredChanges = json.arrayField("requiredChanges"),
            invariantConcerns = json.arrayField("invariantConcerns"),
            rawContent = json.field("rawContent").orEmpty(),
            tokenUsage = TokenUsage(
                inputTokens = json.longField("inputTokens"),
                outputTokens = json.longField("outputTokens"),
                reasoningTokens = json.longField("reasoningTokens")
            )
        )
    }

    private fun splitObjects(value: String): List<String> {
        val objects = mutableListOf<String>()
        var index = 0
        while (index < value.length) {
            val start = value.indexOf('{', index)
            if (start < 0) break
            val end = matchingBracket(value, start, '{', '}') ?: break
            objects += value.substring(start, end + 1)
            index = end + 1
        }
        return objects
    }

    private fun matchingBracket(value: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until value.length) {
            val char = value[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == open -> depth++
                !inString && char == close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun String.longField(key: String): Long {
        val start = valueStart(key) ?: return 0
        var end = start
        while (end < length && this[end].isDigit()) end++
        return substring(start, end).toLongOrNull() ?: 0
    }

    private fun String.arrayField(key: String): List<String> {
        val start = valueStart(key)?.takeIf { it < length && this[it] == '[' } ?: return emptyList()
        val end = matchingBracket(this, start, '[', ']') ?: return emptyList()
        val result = mutableListOf<String>()
        var index = start + 1
        while (index < end) {
            if (this[index] == '"') {
                readJsonString(index)?.let { (value, next) ->
                    result += value
                    index = next
                }
            }
            index++
        }
        return result
    }

    private fun String.field(key: String): String? {
        val index = valueStart(key) ?: return null
        if (startsWith("null", index)) return null
        return readJsonString(index)?.first
    }

    private fun String.valueStart(key: String): Int? {
        val keyIndex = indexOf("\"$key\"")
        if (keyIndex < 0) return null
        val colon = indexOf(':', keyIndex)
        if (colon < 0) return null
        var index = colon + 1
        while (index < length && this[index].isWhitespace()) index++
        return index
    }

    private fun String.readJsonString(quoteIndex: Int): Pair<String, Int>? {
        if (quoteIndex >= length || this[quoteIndex] != '"') return null
        val value = StringBuilder()
        var index = quoteIndex + 1
        while (index < length) {
            val char = this[index]
            when {
                char == '"' -> return value.toString() to index + 1
                char == '\\' && index + 1 < length -> {
                    index++
                    value.append(
                        when (val escaped = this[index]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            '"' -> '"'
                            '\\' -> '\\'
                            else -> escaped
                        }
                    )
                }
                else -> value.append(char)
            }
            index++
        }
        return null
    }
}
