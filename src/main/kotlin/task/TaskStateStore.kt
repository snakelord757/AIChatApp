package task

import agent.JsonTools
import chat.TokenUsage
import formatting.CliPromptMarkerNormalizer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

class TaskStateStore(
    private val path: Path
) {
    fun read(): TaskState? {
        if (!Files.exists(path)) return null
        return try {
            val content = Files.readString(path, StandardCharsets.UTF_8)
            val userTask = field(content, "userTask") ?: return null
            val id = field(content, "id") ?: return null
            val lifecycle = field(content, "lifecycleStatus")
                ?.let { runCatching { TaskLifecycleStatus.valueOf(it) }.getOrNull() }
                ?: TaskLifecycleStatus.ACTIVE
            val stage = field(content, "currentStage")
                ?.let { runCatching { TaskStage.valueOf(it) }.getOrNull() }
                ?: TaskStage.PLANNING
            val pauseReason = field(content, "pauseReason")
            val results = decodeResults(content)
            val createdAt = field(content, "createdAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
            val updatedAt = field(content, "updatedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: createdAt
            val state = TaskState(
                id = id,
                userTask = userTask,
                lifecycleStatus = lifecycle,
                currentStage = stage,
                stages = if (results.isEmpty()) listOf(StageState(stage)) else results.map { StageState(it.stage, result = it) } + StageState(stage),
                results = results,
                createdAt = createdAt,
                updatedAt = updatedAt,
                pauseReason = pauseReason
            )
            state
        } catch (exception: Exception) {
            archiveInvalidState()
            null
        }
    }

    fun readResumable(): TaskState? {
        val state = read() ?: return null
        if (state.lifecycleStatus == TaskLifecycleStatus.DONE ||
            state.lifecycleStatus == TaskLifecycleStatus.FAILED ||
            state.isStaleFailedPause()
        ) {
            archiveInvalidState()
            return null
        }
        return state
    }

    fun write(state: TaskState) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, encode(state), StandardCharsets.UTF_8)
    }

    fun clear() {
        runCatching { Files.deleteIfExists(path) }
    }

    private fun archiveInvalidState() {
        val fileName = path.fileName?.toString() ?: "task-state.json"
        val archivePath = path.resolveSibling("$fileName.invalid-${System.currentTimeMillis()}")
        runCatching {
            Files.move(path, archivePath, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private fun TaskState.isStaleFailedPause(): Boolean {
        if (lifecycleStatus != TaskLifecycleStatus.PAUSED) return false
        if (results.none { !it.success && it.isToolFailure() }) return false
        val reason = pauseReason.orEmpty().lowercase()
        return reason.contains("pause requested") ||
            reason.contains("mcp") ||
            reason.contains("tool") ||
            reason.contains("ошиб")
    }

    private fun StageResult.isToolFailure(): Boolean {
        val text = listOf(summary, output, retryReason.orEmpty())
            .plus(issues)
            .plus(requestedChanges)
            .joinToString(" ")
            .lowercase()
        return text.contains("tool execution pipeline failed") ||
            text.contains("mcp tool") ||
            text.contains("amiiboid") ||
            text.contains("tool step failed")
    }

    private fun encode(state: TaskState): String = buildString {
        appendLine("{")
        appendLine("""  "id": "${JsonTools.escape(state.id)}",""")
        appendLine("""  "userTask": "${JsonTools.escape(state.userTask)}",""")
        appendLine("""  "lifecycleStatus": "${state.lifecycleStatus}",""")
        appendLine("""  "currentStage": "${state.currentStage}",""")
        appendLine("""  "createdAt": "${state.createdAt}",""")
        appendLine("""  "updatedAt": "${state.updatedAt}",""")
        appendLine("""  "pauseReason": ${state.pauseReason?.let { "\"${JsonTools.escape(it)}\"" } ?: "null"},""")
        appendLine("""  "results": [""")
        state.results.forEachIndexed { index, result ->
            append(encodeResult(result).prependIndent("    "))
            if (index < state.results.lastIndex) append(",")
            appendLine()
        }
        appendLine("""  ]""")
        appendLine("}")
    }

    private fun encodeResult(result: StageResult): String = buildString {
        append("{")
        append(""""stage":"${result.stage}",""")
        append(""""success":${result.success},""")
        append(""""summary":"${JsonTools.escape(result.summary)}",""")
        append(""""output":"${JsonTools.escape(result.output)}",""")
        append(""""issues":${encodeArray(result.issues)},""")
        append(""""requestedChanges":${encodeArray(result.requestedChanges)},""")
        append(""""retryReason":${result.retryReason?.let { "\"${JsonTools.escape(it)}\"" } ?: "null"},""")
        append(""""toolExecutionPlanJson":${result.toolExecutionPlanJson?.let { "\"${JsonTools.escape(it)}\"" } ?: "null"},""")
        append(""""tokenUsage":{"inputTokens":${result.tokenUsage.inputTokens},"outputTokens":${result.tokenUsage.outputTokens},"reasoningTokens":${result.tokenUsage.reasoningTokens}}""")
        append("}")
    }

    private fun encodeArray(values: List<String>): String =
        values.joinToString(",", "[", "]") { """"${JsonTools.escape(it)}"""" }

    private fun decodeResults(json: String): List<StageResult> {
        val arrayStart = json.indexOf("\"results\"").takeIf { it >= 0 }?.let { json.indexOf('[', it) } ?: return emptyList()
        val arrayEnd = matchingBracket(json, arrayStart, '[', ']') ?: return emptyList()
        val array = json.substring(arrayStart + 1, arrayEnd)
        return splitObjects(array).mapNotNull(::decodeResult)
    }

    private fun decodeResult(json: String): StageResult? {
        val stage = field(json, "stage")?.let { runCatching { TaskStage.valueOf(it) }.getOrNull() } ?: return null
        return StageResult(
            stage = stage,
            success = booleanField(json, "success") ?: true,
            summary = normalizeGenerated(field(json, "summary").orEmpty()),
            output = normalizeGenerated(field(json, "output").orEmpty()),
            issues = arrayField(json, "issues").map(::normalizeGenerated),
            requestedChanges = arrayField(json, "requestedChanges").map(::normalizeGenerated),
            retryReason = field(json, "retryReason")?.let(::normalizeGenerated),
            tokenUsage = TokenUsage(
                inputTokens = longField(json, "inputTokens"),
                outputTokens = longField(json, "outputTokens"),
                reasoningTokens = longField(json, "reasoningTokens")
            ),
            toolExecutionPlanJson = field(json, "toolExecutionPlanJson")
        )
    }

    private fun normalizeGenerated(value: String): String =
        CliPromptMarkerNormalizer.normalizeGeneratedText(value)

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

    private fun booleanField(json: String, key: String): Boolean? {
        val index = valueStart(json, key) ?: return null
        return when {
            json.startsWith("true", index, ignoreCase = true) -> true
            json.startsWith("false", index, ignoreCase = true) -> false
            else -> null
        }
    }

    private fun longField(json: String, key: String): Long {
        val start = valueStart(json, key) ?: return 0
        var end = start
        while (end < json.length && json[end].isDigit()) end++
        return json.substring(start, end).toLongOrNull() ?: 0
    }

    private fun arrayField(json: String, key: String): List<String> {
        val start = valueStart(json, key)?.takeIf { it < json.length && json[it] == '[' } ?: return emptyList()
        val end = matchingBracket(json, start, '[', ']') ?: return emptyList()
        val result = mutableListOf<String>()
        var index = start + 1
        while (index < end) {
            if (json[index] == '"') {
                readJsonString(json, index)?.let { (value, next) ->
                    result += value
                    index = next
                }
            }
            index++
        }
        return result
    }

    private fun valueStart(json: String, key: String): Int? {
        val keyIndex = json.indexOf("\"$key\"")
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex)
        if (colon < 0) return null
        var index = colon + 1
        while (index < json.length && json[index].isWhitespace()) index++
        return index
    }

    private fun field(json: String, key: String): String? {
        val keyIndex = json.indexOf("\"$key\"")
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex)
        if (colon < 0) return null
        var index = colon + 1
        while (index < json.length && json[index].isWhitespace()) index++
        if (json.startsWith("null", index)) return null
        if (index >= json.length || json[index] != '"') return null
        val value = StringBuilder()
        index++
        while (index < json.length) {
            val char = json[index]
            if (char == '"') return value.toString()
            if (char == '\\' && index + 1 < json.length) {
                index++
                value.append(
                    when (val escaped = json[index]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> escaped
                    }
                )
            } else {
                value.append(char)
            }
            index++
        }
        return null
    }

    private fun readJsonString(json: String, quoteIndex: Int): Pair<String, Int>? {
        if (quoteIndex >= json.length || json[quoteIndex] != '"') return null
        val value = StringBuilder()
        var index = quoteIndex + 1
        while (index < json.length) {
            val char = json[index]
            when {
                char == '"' -> return value.toString() to index + 1
                char == '\\' && index + 1 < json.length -> {
                    index++
                    value.append(
                        when (val escaped = json[index]) {
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
