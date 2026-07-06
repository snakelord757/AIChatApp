package scheduled

import agent.AgentSettings
import chat.ChatMessage
import chat.Role
import invariants.InvariantRepository
import memory.MemoryRepository
import task.ModelProviderStageChatClient
import task.StageChatClient

interface ScheduledTaskSummaryAgent {
    fun summarize(tasks: List<ScheduledTask>): String
}

class ModelProviderScheduledTaskSummaryAgent(
    private val settingsProvider: () -> AgentSettings,
    private val memoryRepository: MemoryRepository?,
    private val invariantRepository: InvariantRepository?
) : ScheduledTaskSummaryAgent {
    override fun summarize(tasks: List<ScheduledTask>): String =
        SummaryClient(
            ModelProviderStageChatClient(
                settings = settingsProvider(),
                structuredStageResponse = false
            )
        ).summarize(tasks, memoryRepository, invariantRepository)
}

typealias DeepSeekScheduledTaskSummaryAgent = ModelProviderScheduledTaskSummaryAgent

class DemoScheduledTaskSummaryAgent : ScheduledTaskSummaryAgent {
    override fun summarize(tasks: List<ScheduledTask>): String {
        val records = tasks.sumOf { it.records.size }
        val errors = tasks.sumOf { task -> task.records.count { it.status == ScheduledTaskRecordStatus.ERROR } }
        return "Scheduled tasks: ${tasks.size}. Records: $records. Errors: $errors."
    }
}

private class SummaryClient(
    private val client: StageChatClient
) {
    fun summarize(
        tasks: List<ScheduledTask>,
        memoryRepository: MemoryRepository?,
        invariantRepository: InvariantRepository?
    ): String {
        val records = tasks.joinToString("\n\n") { task ->
            buildString {
                appendLine("Task: ${task.name}")
                appendLine("Goal: ${task.taskGoal}")
                appendLine("Status: ${task.status}")
                task.records.takeLast(20).forEach { record ->
                    appendLine("- ${record.finishedAt} ${record.status}: ${(record.result ?: record.error).orEmpty().take(600)}")
                }
            }
        }.ifBlank { "No scheduled task records." }
        return client.send(
            listOf(
                ChatMessage(
                    Role.SYSTEM,
                    """
                    Create a compact aggregate summary of scheduled background task records.
                    Preserve important changes, facts, problems, and conclusions.
                    Compress repeated information and do not dump raw logs.
                    Return only the concise summary.
                    """.trimIndent()
                ),
                ChatMessage(
                    Role.USER,
                    """
                    Permanent memory:
                    ${memoryRepository?.permanentMemory().orEmpty().ifBlank { "none" }}

                    Personal memory:
                    ${memoryRepository?.personalMemory().orEmpty().ifBlank { "none" }}

                    Invariants:
                    ${invariantRepository?.invariants().orEmpty().ifBlank { "none" }}

                    Scheduled task records:
                    $records
                    """.trimIndent()
                )
            )
        ).content
    }
}
