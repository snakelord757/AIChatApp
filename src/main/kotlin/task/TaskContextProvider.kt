package task

import agent.AgentSettings
import chat.ChatHistoryRepository
import invariants.InvariantRepository
import memory.MemoryRepository
import mcp.McpTool

interface TaskContextProvider {
    fun contextFor(state: TaskState): String

    object None : TaskContextProvider {
        override fun contextFor(state: TaskState): String = ""
    }
}

class OrchestratorTaskContextProvider(
    private val settingsProvider: () -> AgentSettings,
    private val historyRepository: ChatHistoryRepository,
    private val invariantRepository: InvariantRepository? = null,
    private val memoryRepository: MemoryRepository?,
    private val mcpToolsProvider: () -> List<McpTool> = { emptyList() }
) : TaskContextProvider {
    override fun contextFor(state: TaskState): String {
        val settings = settingsProvider()
        val extraSystemMessages = invariantsFor(state.currentStage) +
            memoryRepository?.contextMessages().orEmpty() +
            mcpToolsFor(state.currentStage)
        val contextMessages = extraSystemMessages.filterForTask(state)
        val chatContext = historyRepository.apiContextMessages(settings, contextMessages)
            .joinToString(separator = "\n\n") { message ->
                "[${message.role.apiName}]\n${message.content}"
            }
        return chatContext
    }

    private fun invariantsFor(stage: TaskStage): List<chat.ChatMessage> =
        if (stage == TaskStage.PLANNING || stage == TaskStage.VALIDATION) {
            invariantRepository?.contextMessages().orEmpty()
        } else {
            emptyList()
        }

    private fun mcpToolsFor(stage: TaskStage): List<chat.ChatMessage> {
        if (stage != TaskStage.PLANNING && stage != TaskStage.EXECUTION) return emptyList()
        val tools = runCatching { mcpToolsProvider() }.getOrElse { emptyList() }
        if (tools.isEmpty()) return emptyList()
        return listOf(
            chat.ChatMessage(
                chat.Role.SYSTEM,
                buildString {
                    appendLine("MCP tools available to scheduled and orchestrated task execution:")
                    appendLine("When one of these tools is relevant, Planning must include a top-level toolExecutionPlan with exact server/tool names and JSON arguments.")
                    appendLine("Do not only mention tools in prose; toolExecutionPlan is the executable contract.")
                    appendLine("Execution can call these tools through the MCP tool gateway; do not invent tool results.")
                    tools.forEach { tool ->
                        append("- ${tool.serverName}/${tool.name}")
                        tool.description?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                        tool.inputSchema?.takeIf { it.isNotBlank() }?.let { append(" inputSchema=$it") }
                        appendLine()
                    }
                }.trimEnd()
            )
        )
    }

    private fun List<chat.ChatMessage>.filterForTask(state: TaskState): List<chat.ChatMessage> {
        if (TaskDomainClassifier.isProgrammingTask(state.userTask)) return this
        if (state.currentStage != TaskStage.PLANNING && state.currentStage != TaskStage.VALIDATION) return this

        return mapNotNull { message ->
            if (message.content.startsWith("MCP tools available to scheduled and orchestrated task execution:")) {
                return@mapNotNull message
            }
            val filteredContent = message.content
                .lineSequence()
                .filterNot(TaskDomainClassifier::isCodeContextLine)
                .joinToString("\n")
                .trim()
            filteredContent
                .takeIf { TaskDomainClassifier.hasSubstantiveContext(it) }
                ?.let { message.copy(content = it) }
        }
    }
}

internal object TaskDomainClassifier {
    private val programmingTaskPatterns = listOf(
        Regex("""\b(code|coding|program|programming|software|app|application|api|sdk|cli|backend|frontend|database|sql|bug|debug|compile|build|test|refactor|class|function|method|interface|module|repository|kotlin|python|java|javascript|typescript|rust|swift|golang|c\+\+|c#)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(html|css|json|xml|yaml|gradle|maven|npm|node|android|ios)\b""", RegexOption.IGNORE_CASE),
        Regex("""(код|программ|приложен|апи|бэкенд|фронтенд|база данных|баг|отлад|скомпили|сборк|тест|рефактор|класс|функц|метод|интерфейс|модул|репозитор|котлин|питон|джава|яваскрипт|тайпскрипт|алгоритм)""", RegexOption.IGNORE_CASE)
    )

    private val codeContextPatterns = listOf(
        Regex("""\b(code|code-related|coding|programming|software|api|sdk|cli|class|function|method|interface|module|repository|algorithm|complexity|stability|memory usage)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(kotlin|python|java|javascript|typescript|rust|swift|golang|c\+\+|c#|sql|html|css|json|xml|yaml|gradle|maven|npm|node|android|ios)\b""", RegexOption.IGNORE_CASE),
        Regex("""(код|кодовых|программ|апи|класс|функц|метод|интерфейс|модул|репозитор|алгоритм|сложност|памят|котлин|питон|джава|яваскрипт|тайпскрипт|python|kotlin|java)""", RegexOption.IGNORE_CASE)
    )

    private val boilerplateLines = setOf(
        "Assistant invariants:",
        "These rules are non-negotiable. Before proposing or executing a solution, check it against every invariant.",
        "If the user's request conflicts with an invariant, refuse the conflicting part and propose the nearest compliant alternative.",
        "Personal memory about the user:",
        "Items may include [strength: N]. Higher strength means the preference or constraint has appeared more often and should be applied more readily when relevant.",
        "# Personal Memory",
        "# Assistant Invariants"
    )

    fun isProgrammingTask(task: String): Boolean =
        programmingTaskPatterns.any { it.containsMatchIn(task) }

    fun isCodeContextLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        return codeContextPatterns.any { it.containsMatchIn(trimmed) }
    }

    fun hasSubstantiveContext(content: String): Boolean =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .any { line -> line !in boilerplateLines && !line.matches(Regex("""^#+\s*.+$""")) }
}
