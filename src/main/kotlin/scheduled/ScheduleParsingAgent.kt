package scheduled

import agent.AgentException
import agent.AgentSettings
import chat.ChatMessage
import chat.Role
import mcp.McpJson
import mcp.asObject
import mcp.asString
import task.StageChatClient

class ScheduleParsingAgent(
    private val clientFactory: () -> StageChatClient
) {
    fun parse(request: String): ParsedScheduleRequest {
        val response = clientFactory().send(
            listOf(
                ChatMessage(
                    Role.SYSTEM,
                    """
                    You parse scheduled task requests.
                    Return strict JSON only, with no Markdown and no extra text.
                    Shape:
                    {"task":"actual task goal","interval":{"time":1,"timeUnit":"MINUTES"}}
                    time must be a positive integer.
                    timeUnit must be one of SECONDS, MINUTES, HOURS, DAYS.
                    Remove the schedule words from task and keep the executable goal.
                    """.trimIndent()
                ),
                ChatMessage(Role.USER, request)
            )
        )
        return parseJson(response.content)
    }

    fun parseJson(content: String): ParsedScheduleRequest {
        val root = runCatching { McpJson.parse(content.trim().withoutJsonFence()).asObject() }.getOrNull()
            ?: throw AgentException("Schedule parser returned invalid JSON.")
        val task = root["task"]?.asString()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw AgentException("Schedule parser JSON is missing non-empty task.")
        val interval = root["interval"]?.asObject()
            ?: throw AgentException("Schedule parser JSON is missing interval.")
        val time = (interval["time"] as? mcp.JsonValue.Number)?.raw?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: throw AgentException("Schedule parser JSON interval.time must be a positive integer.")
        val unit = interval["timeUnit"]?.asString()
            ?.let { runCatching { ScheduledTaskTimeUnit.valueOf(it) }.getOrNull() }
            ?: throw AgentException("Schedule parser JSON interval.timeUnit must be SECONDS, MINUTES, HOURS, or DAYS.")
        return ParsedScheduleRequest(task, ScheduledTaskInterval(time, unit))
    }

    private fun String.withoutJsonFence(): String {
        if (!startsWith("```")) return this
        return lines().drop(1).dropLast(1).joinToString("\n").trim()
    }
}

class DemoScheduleParsingAgent : ScheduleParsingAgentClient {
    override fun parse(request: String): ParsedScheduleRequest {
        val match = Regex("""(?i)\bevery\s+(\d+)\s+(second|seconds|minute|minutes|hour|hours|day|days)\b""")
            .find(request)
            ?: throw AgentException("Could not find schedule interval. Use wording like: every 30 minutes.")
        val unit = when (match.groupValues[2].lowercase()) {
            "second", "seconds" -> ScheduledTaskTimeUnit.SECONDS
            "minute", "minutes" -> ScheduledTaskTimeUnit.MINUTES
            "hour", "hours" -> ScheduledTaskTimeUnit.HOURS
            else -> ScheduledTaskTimeUnit.DAYS
        }
        val task = request.removeRange(match.range).trim().ifBlank {
            throw AgentException("Could not find scheduled task goal before the interval.")
        }
        return ParsedScheduleRequest(task, ScheduledTaskInterval(match.groupValues[1].toLong(), unit))
    }
}

interface ScheduleParsingAgentClient {
    fun parse(request: String): ParsedScheduleRequest
}

class DeepSeekScheduleParsingAgent(
    settingsProvider: () -> AgentSettings
) : ScheduleParsingAgentClient {
    private val agent = ScheduleParsingAgent { task.DeepSeekStageChatClient(settingsProvider()) }

    override fun parse(request: String): ParsedScheduleRequest = agent.parse(request)
}
