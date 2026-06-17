package task

import agent.JsonTools
import chat.ChatMessage
import chat.Role
import chat.TokenUsage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

interface TaskStageAuditStore {
    fun append(entry: TaskStageAuditEntry)

    object None : TaskStageAuditStore {
        override fun append(entry: TaskStageAuditEntry) = Unit
    }
}

data class TaskStageAuditEntry(
    val taskId: String,
    val stage: TaskStage,
    val attempt: Int,
    val createdAt: Instant,
    val promptMessages: List<ChatMessage>,
    val rawResponse: String,
    val result: StageResult
)

class JsonlTaskStageAuditStore(
    private val path: Path
) : TaskStageAuditStore {
    override fun append(entry: TaskStageAuditEntry) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            encode(entry) + "\n",
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND
        )
    }

    private fun encode(entry: TaskStageAuditEntry): String = buildString {
        append("{")
        field("taskId", entry.taskId)
        append(",")
        field("stage", entry.stage.name)
        append(",")
        append(""""attempt":${entry.attempt},""")
        field("createdAt", entry.createdAt.toString())
        append(",")
        append(""""promptMessages":[""")
        append(entry.promptMessages.joinToString(",") { encodeMessage(it) })
        append("],")
        field("rawResponse", entry.rawResponse)
        append(",")
        append(""""result":""")
        append(encodeResult(entry.result))
        append("}")
    }

    private fun encodeMessage(message: ChatMessage): String = buildString {
        append("{")
        field("role", message.role.apiName)
        append(",")
        field("content", message.content)
        message.usage?.let {
            append(",")
            append(""""usage":""")
            append(encodeUsage(it))
        }
        append("}")
    }

    private fun encodeResult(result: StageResult): String = buildString {
        append("{")
        field("stage", result.stage.name)
        append(",")
        append(""""success":${result.success},""")
        field("summary", result.summary)
        append(",")
        field("output", result.output)
        append(",")
        append(""""issues":""")
        append(encodeStringArray(result.issues))
        append(",")
        append(""""requestedChanges":""")
        append(encodeStringArray(result.requestedChanges))
        append(",")
        if (result.retryReason == null) {
            append(""""retryReason":null,""")
        } else {
            field("retryReason", result.retryReason)
            append(",")
        }
        append(""""tokenUsage":""")
        append(encodeUsage(result.tokenUsage))
        append("}")
    }

    private fun encodeUsage(usage: TokenUsage): String =
        """{"inputTokens":${usage.inputTokens},"outputTokens":${usage.outputTokens},"reasoningTokens":${usage.reasoningTokens},"totalTokens":${usage.totalTokens}}"""

    private fun encodeStringArray(values: List<String>): String =
        values.joinToString(",", "[", "]") { """"${JsonTools.escape(it)}"""" }

    private fun StringBuilder.field(key: String, value: String) {
        append(""""$key":"${JsonTools.escape(value)}"""")
    }
}

fun stageAuditEntry(
    taskId: String,
    stage: TaskStage,
    attempt: Int,
    input: StageInput,
    beforeHistory: List<ChatMessage>,
    afterHistory: List<ChatMessage>,
    result: StageResult
): TaskStageAuditEntry {
    val newMessages = afterHistory.drop(beforeHistory.size)
    val rawResponse = newMessages.lastOrNull { it.role == Role.ASSISTANT }?.content.orEmpty()
    val promptMessages = newMessages.takeWhile { it.role != Role.ASSISTANT }.ifEmpty {
        listOf(ChatMessage(Role.USER, fallbackPrompt(input)))
    }
    return TaskStageAuditEntry(
        taskId = taskId,
        stage = stage,
        attempt = attempt,
        createdAt = Instant.now(),
        promptMessages = promptMessages,
        rawResponse = rawResponse,
        result = result
    )
}

private fun fallbackPrompt(input: StageInput): String = buildString {
    appendLine("Task:")
    appendLine(input.userTask)
    appendLine()
    appendLine("Previous result:")
    appendLine(input.previousResult?.output ?: "none")
    appendLine()
    appendLine("All stage summaries:")
    appendLine(input.results.joinToString("\n") { "${it.stage}: ${it.summary}" }.ifBlank { "none" })
}
