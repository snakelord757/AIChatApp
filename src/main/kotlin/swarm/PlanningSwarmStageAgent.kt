package swarm

import chat.ChatMessage
import chat.Role
import chat.TokenUsage
import formatting.CliPromptMarkerNormalizer
import task.StageAgent
import task.StageChatClient
import task.StageInput
import task.StageResult
import task.TaskDomainClassifier
import task.TaskStage

class PlanningSwarmStageAgent(
    private val clientFactory: (SwarmRole) -> StageChatClient,
    private val synthesizerClientFactory: () -> StageChatClient,
    private val eventSink: SwarmEventSink = SwarmEventSink.None,
    private val sessionStore: SwarmSessionStore = SwarmSessionStore.None,
    private val maxRounds: Int = 3
) : StageAgent {
    override val stage: TaskStage = TaskStage.PLANNING
    private val messages = mutableListOf<ChatMessage>()

    override val history: List<ChatMessage>
        get() = messages.toList()

    override fun execute(input: StageInput): StageResult {
        messages.clear()
        val taskId = input.taskId
        val initialDialogue = restoredDialogue(input, taskId)
        var currentDialogue = initialDialogue
        val agents = SwarmRole.entries.map { role ->
            SwarmAgent(SwarmAgentConfig(role = role, maxRounds = maxRounds), clientFactory(role))
        }
        messages += ChatMessage(Role.SYSTEM, "PlanningSwarmStageAgent")
        messages += ChatMessage(Role.USER, inputAuditMessage(input))
        return try {
            val run = SwarmOrchestrator(
                agents = agents,
                synthesizerClient = synthesizerClientFactory(),
                maxRounds = maxRounds,
                initialDialogue = initialDialogue,
                onDialogueUpdated = { dialogue ->
                    currentDialogue = dialogue
                    taskId?.let { sessionStore.write(SwarmSession(it, dialogue)) }
                    dialogue.messages.lastOrNull()?.let { message ->
                        val event = message.toLiveEventText()
                        messages += ChatMessage(Role.EVENT, event)
                        eventSink.emit(event)
                    }
                }
            ).run(input)
            val dialogueEvent = run.dialogue.toEventText()
            eventSink.emit(dialogueEvent)
            messages += ChatMessage(Role.EVENT, dialogueEvent)
            messages += ChatMessage(Role.ASSISTANT, run.finalJson, run.tokenUsage)
            taskId?.let(sessionStore::clear)
            parseStageResult(run.finalJson, run.tokenUsage)
        } catch (exception: InterruptedException) {
            if (currentDialogue.rounds.isNotEmpty()) {
                messages += ChatMessage(Role.EVENT, currentDialogue.toEventText())
            }
            throw exception
        }
    }

    private fun inputAuditMessage(input: StageInput): String = buildString {
        appendLine("Task:")
        appendLine(input.userTask)
        appendLine()
        appendLine("Working context allowed by orchestrator:")
        appendLine(input.workingContext.ifBlank { "none" })
    }

    private fun restoredDialogue(input: StageInput, taskId: String?): SwarmDialogue {
        val dialogue = taskId?.let(sessionStore::read) ?: return SwarmDialogue()
        if (!TaskDomainClassifier.isProgrammingTask(input.userTask) && NonCodeSwarmGuard.containsSoftwareArtifacts(dialogue)) {
            sessionStore.clear(taskId)
            val event = "Stage PLANNING swarm session reset:\nSaved dialogue contained software implementation artifacts for a non-software task, so planning restarts with filtered context."
            messages += ChatMessage(Role.EVENT, event)
            eventSink.emit(event)
            return SwarmDialogue()
        }
        return dialogue
    }

    private fun parseStageResult(content: String, usage: TokenUsage): StageResult {
        val json = content.stripJsonFence()
        val output = CliPromptMarkerNormalizer.normalizeGeneratedText(json.extractString("output") ?: content)
        val summary = json.extractString("summary")
            ?.let(CliPromptMarkerNormalizer::normalizeGeneratedText)
            ?: output.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
            ?: "Planning swarm completed"
        return StageResult(
            stage = TaskStage.PLANNING,
            success = json.extractBoolean("success") ?: true,
            summary = summary,
            output = output,
            issues = json.extractArray("issues").map(CliPromptMarkerNormalizer::normalizeGeneratedText),
            requestedChanges = json.extractArray("requestedChanges").map(CliPromptMarkerNormalizer::normalizeGeneratedText),
            retryReason = json.extractString("retryReason")?.let(CliPromptMarkerNormalizer::normalizeGeneratedText),
            tokenUsage = usage
        )
    }
}
