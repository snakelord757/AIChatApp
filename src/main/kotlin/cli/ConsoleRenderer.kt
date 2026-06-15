package cli

import agent.AgentSettings
import chat.ChatMessage
import chat.Role
import chat.TokenUsage
import config.TokenPricing
import formatting.Ansi
import formatting.MarkdownConsoleFormatter
import memory.MemoryPaths
import java.util.Locale

class ConsoleRenderer(
    private val formatter: MarkdownConsoleFormatter = MarkdownConsoleFormatter()
) {
    fun renderGreeting() {
        println(Ansi.style("AIChatApp: DeepSeek CLI chat", Ansi.BOLD, Ansi.CYAN))
        println("Enter a message or command. Available commands:")
        renderHelp()
    }

    fun renderHelp() {
        println("${Ansi.style("/help", Ansi.CYAN)} - show help")
        println("${Ansi.style("/settings", Ansi.CYAN)} - open settings")
        println("${Ansi.style("/summary", Ansi.CYAN)} - show token usage and cost for the full chat history")
        println("${Ansi.style("/facts", Ansi.CYAN)} - show sticky facts memory")
        println("${Ansi.style("/memory", Ansi.CYAN)} - show Markdown memory commands")
        println("${Ansi.style("/checkpoint", Ansi.CYAN)} - save a branching checkpoint")
        println("${Ansi.style("/branch create <name>", Ansi.CYAN)} - create and activate a branch")
        println("${Ansi.style("/branch list", Ansi.CYAN)} - list branches")
        println("${Ansi.style("/branch switch <name|main>", Ansi.CYAN)} - switch to a branch or main chat")
        println("${Ansi.style("/clear", Ansi.CYAN)} - clear the current session history")
        println("${Ansi.style("/exit", Ansi.CYAN)} - exit")
    }

    fun renderUser(text: String) {
        println()
        println(Ansi.style("You:", Ansi.BOLD, Ansi.GREEN))
        println(text)
    }

    fun renderAssistant(text: String) {
        println()
        println(Ansi.style("Assistant:", Ansi.BOLD, Ansi.MAGENTA))
        println(formatter.format(text))
        println()
    }

    fun renderHistory(messages: List<ChatMessage>) {
        messages.forEach { message ->
            when (message.role) {
                Role.USER -> renderUser(message.content)
                Role.ASSISTANT -> renderAssistant(message.content)
                Role.SYSTEM -> Unit
                Role.EVENT -> renderSystem(message.content)
            }
        }
    }

    fun renderSystem(text: String) {
        println(Ansi.style("System: $text", Ansi.BLUE))
    }

    fun renderWarning(text: String) {
        println(Ansi.style("Warning: $text", Ansi.YELLOW, Ansi.BOLD))
    }

    fun renderError(text: String) {
        println(Ansi.style("Error: $text", Ansi.RED, Ansi.BOLD))
    }

    fun renderSettings(settings: AgentSettings) {
        println(Ansi.style("Agent Settings", Ansi.BOLD, Ansi.CYAN))
        println("Model: ${settings.model}")
        println("Thinking mode: ${if (settings.thinkingMode) "enabled" else "disabled"}")
        println("Temperature: ${settings.temperature}")
        println("Max tokens: ${if (settings.maxTokens > 0) settings.maxTokens else "unlimited"}")
        println("Context strategy: ${settings.contextStrategy.displayName}")
        println("Context window messages: ${settings.contextWindowMessages}")
        println("Summary interval: ${if (settings.summaryInterval > 0) settings.summaryInterval else "disabled"}")
        println("Base URL: ${settings.baseUrl}")
        println("System prompt: ${settings.systemPrompt.take(120).replace('\n', ' ')}")
    }

    fun renderUsage(usage: TokenUsage) {
        println("Tokens: input=${usage.inputTokens}, output=${usage.outputTokens}, reasoning=${usage.reasoningTokens}, total=${usage.totalTokens}")
    }

    fun renderFinishReason(finishReason: String?) {
        if (!finishReason.isNullOrBlank() && finishReason != "stop") {
            println("finish_reason: $finishReason")
        }
    }

    fun renderCost(usage: TokenUsage, pricing: TokenPricing?) {
        println()
        if (pricing == null) {
            println("Cost: unavailable (token pricing is not configured)")
        } else {
            println("Cost: $${formatUsd(pricing.costUsd(usage))}")
        }
    }

    fun renderSummary(usage: TokenUsage, pricing: TokenPricing?) {
        println()
        println(Ansi.style("Chat Summary", Ansi.BOLD, Ansi.CYAN))
        renderUsage(usage)
        renderCost(usage, pricing)
        println()
    }

    fun renderFacts(facts: Map<String, String>) {
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
        println()
        println(Ansi.style(title, Ansi.BOLD, Ansi.CYAN))
        println(content.ifBlank { "(empty)" })
        println()
    }

    fun renderMemoryPaths(paths: MemoryPaths) {
        println()
        println(Ansi.style("Markdown Memory Paths", Ansi.BOLD, Ansi.CYAN))
        println("Permanent: ${paths.permanent}")
        println("Personal: ${paths.personal}")
        println("Working: ${paths.work}")
        println()
    }

    fun prompt() {
        print(Ansi.style("> ", Ansi.BOLD, Ansi.GREEN))
    }

    private fun formatUsd(value: Double): String = String.format(Locale.US, "%.6f", value)
}
