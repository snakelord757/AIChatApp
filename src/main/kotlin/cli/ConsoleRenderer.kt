package cli

import agent.AgentSettings
import chat.ChatMessage
import chat.Role
import chat.TokenUsage
import config.TokenPricing
import formatting.Ansi
import formatting.MarkdownConsoleFormatter
import memory.MemoryPaths
import mcp.McpConnectionState
import mcp.McpServerStatus
import mcp.McpToolCallResult
import mcp.McpTool
import scheduled.ScheduledTask
import task.StageResult
import task.TaskState
import task.TaskStage
import java.util.Locale

class ConsoleRenderer(
    private val formatter: MarkdownConsoleFormatter = MarkdownConsoleFormatter()
) {
    private var promptVisible: Boolean = false

    fun renderGreeting() {
        prepareOutput()
        println(Ansi.style("AIChatApp: model CLI chat", Ansi.BOLD, Ansi.CYAN))
        println("Enter a message or command. Available commands:")
        renderHelp()
    }

    fun renderHelp() {
        prepareOutput()
        println("${Ansi.style("/help", Ansi.CYAN)} - show help")
        println("${Ansi.style("/settings", Ansi.CYAN)} - open settings")
        println("${Ansi.style("/models", Ansi.CYAN)} - reload and show available models")
        println("${Ansi.style("/mcp", Ansi.CYAN)} - connect MCP servers and list tools")
        println("${Ansi.style("/summary", Ansi.CYAN)} - show token usage and cost for the full chat history")
        println("${Ansi.style("/facts", Ansi.CYAN)} - show sticky facts memory")
        println("${Ansi.style("/memory", Ansi.CYAN)} - show Markdown memory commands")
        println("${Ansi.style("/task", Ansi.CYAN)} - manage scheduled background tasks")
        println("${Ansi.style("/edit invariants", Ansi.CYAN)} - open assistant invariants")
        println("${Ansi.style("/pause", Ansi.CYAN)} - pause the active task")
        println("${Ansi.style("/resume", Ansi.CYAN)} - resume a paused task")
        println("${Ansi.style("/checkpoint", Ansi.CYAN)} - save a branching checkpoint")
        println("${Ansi.style("/branch create <name>", Ansi.CYAN)} - create and activate a branch")
        println("${Ansi.style("/branch list", Ansi.CYAN)} - list branches")
        println("${Ansi.style("/branch switch <name|main>", Ansi.CYAN)} - switch to a branch or main chat")
        println("${Ansi.style("/clear", Ansi.CYAN)} - clear the current session history")
        println("${Ansi.style("/exit", Ansi.CYAN)} - exit")
    }

    fun renderUser(text: String) {
        prepareOutput()
        println()
        println(Ansi.style("You:", Ansi.BOLD, Ansi.GREEN))
        println(text)
    }

    fun renderAssistant(text: String) {
        prepareOutput()
        println()
        println(Ansi.style("Assistant:", Ansi.BOLD, Ansi.MAGENTA))
        println(formatter.format(text))
        println()
    }

    fun renderHistory(messages: List<ChatMessage>, summarizeEvents: Boolean = false) {
        prepareOutput()
        var restoredEvents = 0
        messages.forEach { message ->
            when (message.role) {
                Role.USER -> renderUser(message.content)
                Role.ASSISTANT -> renderAssistant(message.content)
                Role.SYSTEM -> Unit
                Role.EVENT -> {
                    if (summarizeEvents) {
                        restoredEvents++
                    } else {
                        renderStageEvent(message.content)
                    }
                }
            }
        }
        if (restoredEvents > 0) {
            renderSystem("Restored $restoredEvents prior task/tool event(s).")
        }
    }

    fun renderStageEvent(content: String) {
        prepareOutput()
        if (content.startsWith("Stage PLANNING swarm message:")) {
            renderSwarmMessage(content)
            return
        }
        if (content.startsWith("Stage PLANNING swarm dialogue:")) {
            renderSwarmDialogue(content)
            return
        }
        if (content.startsWith("Tool ")) {
            renderToolPipelineEvent(content)
            return
        }
        val stageEvent = content.toStageEvent()
        if (stageEvent == null) {
            renderSystem(content)
            return
        }

        println()
        println(Ansi.style("Stage result: ${stageEvent.stage.name}", Ansi.BOLD, Ansi.CYAN))
        println("Status: ${stageEvent.status}")
        println("Summary: ${stageEvent.summary}")
        val visibleOutput = stageEvent.visibleOutput()
        if (visibleOutput.isNotBlank()) {
            println()
            println(formatter.format(visibleOutput))
        }
        if (stageEvent.issues.isNotEmpty()) {
            println()
            println("Issues:")
            stageEvent.issues.forEach { println("- $it") }
        }
        if (stageEvent.requestedChanges.isNotEmpty()) {
            println()
            println("Requested changes:")
            stageEvent.requestedChanges.forEach { println("- $it") }
        }
        println()
    }

    private fun renderToolPipelineEvent(content: String) {
        println()
        println(Ansi.style("Stage event: tool execution", Ansi.BOLD, Ansi.CYAN))
        println(formatter.format(content))
        println()
    }

    private fun renderSwarmMessage(content: String) {
        val lines = content.lineSequence().toList()
        println()
        println(Ansi.style("Stage event: PLANNING swarm message", Ansi.BOLD, Ansi.CYAN))
        lines.drop(1).forEach { line ->
            when {
                line.startsWith("Round ") -> println(Ansi.style(line, Ansi.CYAN))
                line.startsWith("[") -> println(Ansi.style(line, Ansi.BOLD))
                else -> println(formatter.format(line))
            }
        }
        println()
    }

    private fun renderSwarmDialogue(content: String) {
        val lines = content.lineSequence().toList()
        println()
        println(Ansi.style("Stage event: PLANNING swarm dialogue", Ansi.BOLD, Ansi.CYAN))
        lines.drop(1).forEach { line ->
            when {
                line.startsWith("Round ") -> println(Ansi.style(line, Ansi.CYAN))
                line.startsWith("[") -> println(Ansi.style(line, Ansi.BOLD))
                else -> println(formatter.format(line))
            }
        }
        println()
    }

    fun renderSystem(text: String) {
        prepareOutput()
        println(Ansi.style("System: $text", Ansi.BLUE))
    }

    fun renderWarning(text: String) {
        prepareOutput()
        println(Ansi.style("Warning: $text", Ansi.YELLOW, Ansi.BOLD))
    }

    fun renderError(text: String) {
        prepareOutput()
        println(Ansi.style("Error: $text", Ansi.RED, Ansi.BOLD))
    }

    fun renderSettings(settings: AgentSettings) {
        prepareOutput()
        println(Ansi.style("Agent Settings", Ansi.BOLD, Ansi.CYAN))
        println("Model: ${settings.model}")
        println("Available models: ${settings.availableModels.joinToString(", ")}")
        println("Thinking mode: ${if (settings.thinkingMode) "enabled" else "disabled"}")
        println("Temperature: ${settings.temperature}")
        println("Max tokens: ${if (settings.maxTokens > 0) settings.maxTokens else "unlimited"}")
        println("Model context window tokens: ${settings.modelContextWindowTokens}")
        println("Context strategy: ${settings.contextStrategy.displayName}")
        println("Context window messages: ${settings.contextWindowMessages}")
        println("Summary interval: ${if (settings.summaryInterval > 0) settings.summaryInterval else "disabled"}")
        println("Planning swarm: ${if (settings.planningSwarmEnabled) "enabled" else "disabled"}")
        println("RAG: ${if (settings.ragEnabled) "enabled" else "disabled"}")
        println("RAG indices: ${chat.AppPaths.indicesDirectory()}")
        println("RAG Ollama URL: ${settings.ragOllamaUrl}")
        println("RAG embedding model: ${settings.ragEmbeddingModel ?: "from index"}")
        println("RAG searchTopK: ${settings.ragSearchTopK}")
        println("RAG topK: ${settings.ragTopK}")
        println("Base URL: ${settings.baseUrl}")
        println("System prompt mode: ${if (settings.systemPromptOverridden) "custom direct chat" else "default staged"}")
        println("System prompt: ${settings.systemPrompt.take(120).replace('\n', ' ')}")
    }

    fun renderModels(selectedModel: String, models: List<String>) {
        prepareOutput()
        println()
        println(Ansi.style("Available Models", Ansi.BOLD, Ansi.CYAN))
        if (models.isEmpty()) {
            println("No models found.")
        } else {
            models.forEach { model ->
                val marker = if (model == selectedModel) "*" else " "
                println("$marker $model")
            }
        }
        println()
    }

    fun renderMcpServers(servers: List<McpServerStatus>) {
        prepareOutput()
        println()
        println(Ansi.style("MCP Servers", Ansi.BOLD, Ansi.CYAN))
        if (servers.isEmpty()) {
            println("No MCP servers configured.")
        } else {
            servers.forEach { server ->
                val status = when (server.state) {
                    McpConnectionState.CONFIGURED -> "configured"
                    McpConnectionState.CONNECTED -> "connected"
                    McpConnectionState.FAILED -> "failed"
                }
                val message = server.message?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                println("${server.name}: $status$message")
            }
        }
        println()
    }

    fun renderMcpTools(tools: List<McpTool>, includeServerName: Boolean) {
        prepareOutput()
        println()
        println(Ansi.style("MCP Tools", Ansi.BOLD, Ansi.CYAN))
        if (tools.isEmpty()) {
            println("No MCP tools found.")
        } else {
            tools.forEach { tool ->
                val prefix = if (includeServerName) "${tool.serverName}/" else ""
                println("- $prefix${tool.name}")
                tool.description?.takeIf { it.isNotBlank() }?.let { println("  $it") }
                tool.inputSchema?.takeIf { it.isNotBlank() }?.let { println("  inputSchema: $it") }
            }
        }
        println()
    }

    fun renderMcpToolResult(result: McpToolCallResult) {
        prepareOutput()
        println()
        println(Ansi.style("MCP Tool Result", Ansi.BOLD, Ansi.CYAN))
        println("${result.serverName}/${result.toolName}${if (result.isError) " (error)" else ""}")
        println(result.content)
        println()
    }

    fun renderUsage(usage: TokenUsage) {
        prepareOutput()
        println("Tokens: input=${usage.inputTokens}, output=${usage.outputTokens}, reasoning=${usage.reasoningTokens}, total=${usage.totalTokens}")
    }

    fun renderFinishReason(finishReason: String?) {
        prepareOutput()
        if (!finishReason.isNullOrBlank() && finishReason != "stop") {
            println("finish_reason: $finishReason")
        }
    }

    fun renderCost(usage: TokenUsage, pricing: TokenPricing?) {
        prepareOutput()
        println()
        if (pricing == null) {
            println("Cost: unavailable (token pricing is not configured)")
        } else {
            println("Cost: $${formatUsd(pricing.costUsd(usage))}")
        }
    }

    fun renderSummary(usage: TokenUsage, pricing: TokenPricing?) {
        prepareOutput()
        println()
        println(Ansi.style("Chat Summary", Ansi.BOLD, Ansi.CYAN))
        renderUsage(usage)
        renderCost(usage, pricing)
        println()
    }

    fun renderFacts(facts: Map<String, String>) {
        prepareOutput()
        println()
        println(Ansi.style("Sticky Facts", Ansi.BOLD, Ansi.CYAN))
        if (facts.isEmpty()) {
            println("No facts saved.")
        } else {
            facts.forEach { (key, value) -> println("$key: $value") }
        }
        println()
    }

    fun renderBranches(branches: List<String>, activeBranch: String?) {
        prepareOutput()
        println()
        println(Ansi.style("Branches", Ansi.BOLD, Ansi.CYAN))
        println("${if (activeBranch == null) "*" else " "} main")
        if (branches.isEmpty()) {
            println("No branches saved.")
        } else {
            branches.forEach { name ->
                val marker = if (name == activeBranch) "*" else " "
                println("$marker $name")
            }
        }
        println()
    }

    fun renderMemoryHelp() {
        prepareOutput()
        println()
        println(Ansi.style("Markdown Memory", Ansi.BOLD, Ansi.CYAN))
        println("/memory show permanent - show global Markdown instructions")
        println("/memory show personal - show personal Markdown memory")
        println("/memory show work - show working memory for the active branch")
        println("/memory status - show active working memory status")
        println("/memory done - set active working memory status to DONE")
        println("/memory pending - set active working memory status to PENDING")
        println("/memory path - show memory file paths")
        println("/memory reload - make sure memory files exist and read current disk contents on next request")
        println()
    }

    fun renderMemory(title: String, content: String) {
        prepareOutput()
        println()
        println(Ansi.style(title, Ansi.BOLD, Ansi.CYAN))
        println(content.ifBlank { "(empty)" })
        println()
    }

    fun renderMemoryPaths(paths: MemoryPaths) {
        prepareOutput()
        println()
        println(Ansi.style("Markdown Memory Paths", Ansi.BOLD, Ansi.CYAN))
        println("Permanent: ${paths.permanent}")
        println("Personal: ${paths.personal}")
        println("Working: ${paths.work}")
        println()
    }

    fun renderScheduledTasks(tasks: List<ScheduledTask>) {
        prepareOutput()
        println()
        println(Ansi.style("Scheduled Tasks", Ansi.BOLD, Ansi.CYAN))
        if (tasks.isEmpty()) {
            println("No scheduled tasks saved.")
        } else {
            tasks.forEach { task ->
                println("- ${task.name}: ${task.status}, every ${task.interval}")
                println("  Goal: ${task.taskGoal}")
                println("  Last run: ${task.lastRunAt ?: "never"}, records: ${task.records.size}")
            }
        }
        println()
    }

    fun renderTaskState(state: TaskState?) {
        prepareOutput()
        val description = if (state == null) {
            "No saved task."
        } else if (state.lifecycleStatus == task.TaskLifecycleStatus.DONE) {
            return
        } else {
            "Task ${state.lifecycleStatus} at ${state.currentStage}"
        }
        renderSystem(description)
    }

    fun renderStageStarted(stage: TaskStage) {
        prepareOutput()
        println()
        println(Ansi.style("Stage: ${stage.name}", Ansi.BOLD, Ansi.CYAN))
        println("Started.")
    }

    fun renderStageResult(result: StageResult) {
        prepareOutput()
        println()
        println(Ansi.style("Stage result: ${result.stage.name}", Ansi.BOLD, Ansi.CYAN))
        println("Status: ${if (result.success) "success" else "needs changes"}")
        println("Summary: ${result.summary}")
        val visibleOutput = result.visibleStageOutput()
        if (visibleOutput.isNotBlank()) {
            println()
            println(formatter.format(visibleOutput))
        }
        if (result.issues.isNotEmpty()) {
            println()
            println("Issues:")
            result.issues.forEach { println("- $it") }
        }
        if (result.requestedChanges.isNotEmpty()) {
            println()
            println("Requested changes:")
            result.requestedChanges.forEach { println("- $it") }
        }
        println()
    }

    private fun StageResult.visibleStageOutput(): String =
        when (stage) {
            TaskStage.EXECUTION, TaskStage.VALIDATION -> output.takeUnless { it.looksLikeRawJson() }.orEmpty()
            TaskStage.PLANNING, TaskStage.COMPLETION -> ""
        }

    private fun String.looksLikeRawJson(): Boolean {
        val trimmed = trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}") &&
            listOf("\"success\"", "\"summary\"", "\"output\"").all(trimmed::contains)
    }

    private fun String.toStageEvent(): StoredStageEvent? {
        if (!startsWith("Stage ")) return null
        val lines = lineSequence().toList()
        val firstLine = lines.firstOrNull().orEmpty()
        val stageName = firstLine.removePrefix("Stage ").substringBefore(':').trim()
        val stage = TaskStage.entries.firstOrNull { it.name == stageName } ?: return null
        val status = firstLine.substringAfter(':', missingDelimiterValue = "").trim()
        val summaryIndex = lines.indexOfFirst { it.startsWith("Summary:") }
        val summary = lines.getOrNull(summaryIndex)
            ?.substringAfter("Summary:")
            ?.trim()
            .orEmpty()
        val issuesIndex = lines.indexOf("Issues:")
        val requestedChangesIndex = lines.indexOf("Requested changes:")
        val outputStart = if (summaryIndex >= 0) summaryIndex + 1 else 1
        val outputEnd = listOf(issuesIndex, requestedChangesIndex)
            .filter { it >= 0 }
            .minOrNull()
            ?: lines.size
        val output = lines.subList(outputStart, outputEnd).joinToString("\n").trim()
        val issuesEnd = if (requestedChangesIndex >= 0) requestedChangesIndex else lines.size
        return StoredStageEvent(
            stage = stage,
            status = status,
            summary = summary,
            output = output,
            issues = lines.bulletsAfter(issuesIndex, issuesEnd),
            requestedChanges = lines.bulletsAfter(requestedChangesIndex, lines.size)
        )
    }

    private fun List<String>.bulletsAfter(headerIndex: Int, endIndex: Int): List<String> {
        if (headerIndex < 0) return emptyList()
        return subList(headerIndex + 1, endIndex)
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotBlank() }
    }

    private fun StoredStageEvent.visibleOutput(): String =
        when (stage) {
            TaskStage.EXECUTION, TaskStage.VALIDATION -> output.takeUnless { it.looksLikeRawJson() }.orEmpty()
            TaskStage.PLANNING, TaskStage.COMPLETION -> ""
        }

    fun prompt() {
        promptVisible = true
        print(Ansi.style("> ", Ansi.BOLD, Ansi.GREEN))
    }

    fun finishPromptInput(clearSubmittedLine: Boolean) {
        if (!promptVisible) return
        if (clearSubmittedLine && Ansi.isEnabled) {
            print("\u001B[1A\r\u001B[2K")
        }
        promptVisible = false
    }

    fun prepareBackgroundOutput() {
        clearPromptLine()
    }

    private fun formatUsd(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun prepareOutput() {
        if (promptVisible) {
            clearPromptLine()
        }
    }

    private fun clearPromptLine() {
        if (Ansi.isEnabled) {
            print("\r\u001B[2K")
        } else if (promptVisible) {
            println()
        }
        promptVisible = false
    }

    private data class StoredStageEvent(
        val stage: TaskStage,
        val status: String,
        val summary: String,
        val output: String,
        val issues: List<String>,
        val requestedChanges: List<String>
    )
}
