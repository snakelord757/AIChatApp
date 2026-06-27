package scheduled

import agent.JsonTools
import mcp.JsonValue
import mcp.McpJson
import mcp.asArray
import mcp.asObject
import mcp.asString
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ScheduledTaskStore(
    private val path: Path
) {
    @Synchronized
    fun readAll(): List<ScheduledTask> {
        if (!Files.exists(path)) return emptyList()
        val content = Files.readString(path, StandardCharsets.UTF_8).trim()
        if (content.isBlank()) return emptyList()
        val root = runCatching { McpJson.parse(content).asObject() }.getOrNull() ?: return emptyList()
        return root["tasks"]?.asArray().orEmpty().mapNotNull(::decodeTask)
    }

    @Synchronized
    fun upsert(task: ScheduledTask) {
        val tasks = readAll().filterNot { it.name == task.name } + task
        writeAll(tasks.sortedBy { it.name.lowercase() })
    }

    @Synchronized
    fun update(name: String, transform: (ScheduledTask) -> ScheduledTask) {
        writeAll(readAll().map { if (it.name == name) transform(it) else it })
    }

    @Synchronized
    fun clear() {
        writeAll(emptyList())
    }

    @Synchronized
    fun writeAll(tasks: List<ScheduledTask>) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, encode(tasks), StandardCharsets.UTF_8)
    }

    private fun encode(tasks: List<ScheduledTask>): String = buildString {
        appendLine("{")
        appendLine("""  "tasks": [""")
        tasks.forEachIndexed { index, task ->
            append(encodeTask(task).prependIndent("    "))
            if (index < tasks.lastIndex) append(",")
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun encodeTask(task: ScheduledTask): String = buildString {
        appendLine("{")
        appendLine("""  "name": "${JsonTools.escape(task.name)}",""")
        appendLine("""  "originalPrompt": "${JsonTools.escape(task.originalPrompt)}",""")
        appendLine("""  "taskGoal": "${JsonTools.escape(task.taskGoal)}",""")
        appendLine("""  "interval": {"time": ${task.interval.time}, "timeUnit": "${task.interval.timeUnit}"},""")
        appendLine("""  "status": "${task.status}",""")
        appendLine("""  "createdAt": "${task.createdAt}",""")
        appendLine("""  "lastRunAt": ${task.lastRunAt?.let { "\"$it\"" } ?: "null"},""")
        appendLine("""  "records": [""")
        task.records.forEachIndexed { index, record ->
            append(encodeRecord(record).prependIndent("    "))
            if (index < task.records.lastIndex) append(",")
            appendLine()
        }
        appendLine("  ]")
        append("}")
    }

    private fun encodeRecord(record: ScheduledTaskRecord): String = buildString {
        append("{")
        append(""""runId":"${JsonTools.escape(record.runId)}",""")
        append(""""status":"${record.status}",""")
        append(""""startedAt":"${record.startedAt}",""")
        append(""""finishedAt":"${record.finishedAt}",""")
        append(""""result":${record.result?.let { "\"${JsonTools.escape(it)}\"" } ?: "null"},""")
        append(""""error":${record.error?.let { "\"${JsonTools.escape(it)}\"" } ?: "null"}""")
        append("}")
    }

    private fun decodeTask(value: JsonValue): ScheduledTask? {
        val obj = value.asObject() ?: return null
        val interval = obj["interval"]?.asObject() ?: return null
        return ScheduledTask(
            name = obj["name"]?.asString()?.takeIf { it.isNotBlank() } ?: return null,
            originalPrompt = obj["originalPrompt"]?.asString().orEmpty(),
            taskGoal = obj["taskGoal"]?.asString()?.takeIf { it.isNotBlank() } ?: return null,
            interval = ScheduledTaskInterval(
                time = interval["time"]?.numberLong() ?: return null,
                timeUnit = interval["timeUnit"]?.asString()
                    ?.let { runCatching { ScheduledTaskTimeUnit.valueOf(it) }.getOrNull() }
                    ?: return null
            ),
            status = obj["status"]?.asString()
                ?.let { runCatching { ScheduledTaskStatus.valueOf(it) }.getOrNull() }
                ?: ScheduledTaskStatus.STOPPED,
            createdAt = obj["createdAt"]?.asString()?.parseInstantOrNull() ?: Instant.now(),
            lastRunAt = obj["lastRunAt"]?.asString()?.parseInstantOrNull(),
            records = obj["records"]?.asArray().orEmpty().mapNotNull(::decodeRecord)
        )
    }

    private fun decodeRecord(value: JsonValue): ScheduledTaskRecord? {
        val obj = value.asObject() ?: return null
        return ScheduledTaskRecord(
            runId = obj["runId"]?.asString()?.takeIf { it.isNotBlank() } ?: return null,
            status = obj["status"]?.asString()
                ?.let { runCatching { ScheduledTaskRecordStatus.valueOf(it) }.getOrNull() }
                ?: return null,
            startedAt = obj["startedAt"]?.asString()?.parseInstantOrNull() ?: return null,
            finishedAt = obj["finishedAt"]?.asString()?.parseInstantOrNull() ?: return null,
            result = obj["result"]?.asString(),
            error = obj["error"]?.asString()
        )
    }

    private fun JsonValue.numberLong(): Long? = (this as? JsonValue.Number)?.raw?.toLongOrNull()

    private fun String.parseInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
}
