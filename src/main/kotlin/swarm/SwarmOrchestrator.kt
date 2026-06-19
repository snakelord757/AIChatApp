package swarm

import chat.ChatMessage
import chat.Role
import chat.TokenUsage
import task.StageChatClient
import task.StageInput
import task.TaskDomainClassifier

class ConsensusPolicy(
    private val minRounds: Int = 1
) {
    fun hasConsensus(dialogue: SwarmDialogue): Boolean {
        val completedRounds = dialogue.rounds.count { it.messages.map(SwarmMessage::role).toSet().size == SwarmRole.entries.size }
        if (completedRounds < minRounds.coerceAtLeast(1)) return false
        val latestByRole = dialogue.messages.groupBy { it.role }.mapValues { it.value.last() }
        if (latestByRole.size < SwarmRole.entries.size) return false
        if (latestByRole[SwarmRole.RISK_MANAGER]?.stance == "block") return false
        if (latestByRole[SwarmRole.REQUIREMENTS_ANALYST]?.stance == "block") return false
        if (latestByRole[SwarmRole.STRATEGIST]?.stance == "block") return false
        if (latestByRole.values.any { it.stance != "approve" }) return false
        if (latestByRole.values.any { it.requiredChanges.any(String::isNotBlank) }) return false
        return latestByRole.values.none { it.invariantConcerns.any(String::isNotBlank) && it.stance == "block" }
    }
}

class SwarmOrchestrator(
    private val agents: List<SwarmAgent>,
    private val synthesizerClient: StageChatClient,
    private val consensusPolicy: ConsensusPolicy = ConsensusPolicy(),
    private val maxRounds: Int = 3,
    private val initialDialogue: SwarmDialogue = SwarmDialogue(),
    private val onDialogueUpdated: (SwarmDialogue) -> Unit = {}
) {
    fun run(input: StageInput): SwarmRunResult {
        var dialogue = initialDialogue
        var usage = dialogue.messages.fold(TokenUsage.ZERO) { total, message -> total + message.tokenUsage }
        val rounds = maxRounds.coerceAtLeast(1)
        for (roundIndex in 1..rounds) {
            val existingRoles = dialogue.rounds
                .firstOrNull { it.index == roundIndex }
                ?.messages
                ?.map { it.role }
                ?.toSet()
                .orEmpty()
            for (agent in agents.filterNot { it.role in existingRoles }) {
                throwIfInterrupted()
                val message = agent.respond(input, roundIndex, dialogue)
                usage += message.tokenUsage
                dialogue = dialogue.withMessage(message)
                onDialogueUpdated(dialogue)
            }
            throwIfInterrupted()
            if (consensusPolicy.hasConsensus(dialogue)) break
        }
        throwIfInterrupted()
        val synthesis = synthesizerClient.send(synthesisMessages(input, dialogue, consensusPolicy.hasConsensus(dialogue)))
        usage += synthesis.usage
        val finalJson = if (TaskDomainClassifier.isProgrammingTask(input.userTask)) {
            synthesis.content
        } else {
            NonCodeSwarmGuard.sanitizeStageJson(synthesis.content)
        }
        return SwarmRunResult(dialogue, finalJson, usage)
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Planning swarm paused.")
        }
    }

    private fun synthesisMessages(input: StageInput, dialogue: SwarmDialogue, consensus: Boolean): List<ChatMessage> =
        listOf(
            ChatMessage(
                Role.SYSTEM,
                """
                You are the planning swarm orchestrator.
                You are not a TaskStage. Synthesize the PLANNING swarm discussion into StageResult-compatible JSON.
                Return only JSON with success, summary, output, issues, requestedChanges, and retryReason.
                The output must be a concise neutral execution plan, not a discussion log.
                If the request is impossible, contradictory, or blocked by invariants, return success false.
                If the user's task is not explicitly about software, do not synthesize software modules, data classes, functions, interfaces, engines, registries, APIs, implementation languages, code examples, internal stage agents, handoff, checkpoints, or architectural constraints.
                For travel, personal advice, business, writing, or research tasks, keep the final plan in that domain.
                Do not use Markdown blockquotes or lines starting with the CLI prompt marker.
                """.trimIndent()
            ),
            ChatMessage(
                Role.USER,
                buildString {
                    appendLine("Consensus reached: $consensus")
                    appendLine()
                    appendLine("Task:")
                    appendLine(input.userTask)
                    appendLine()
                    appendLine("Working context:")
                    appendLine(input.workingContext.ifBlank { "none" })
                    appendLine()
                    appendLine("Swarm dialogue:")
                    appendLine(dialogue.toEventText())
                }
            )
        )
}
