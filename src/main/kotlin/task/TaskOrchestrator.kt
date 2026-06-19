package task

import agent.AgentException
import chat.ChatHistoryRepository
import chat.Role
import chat.TokenUsage
import memory.MemoryRepository
import memory.TaskStatus
import java.time.Instant

class TaskOrchestrator(
    private val historyRepository: ChatHistoryRepository,
    private val memoryRepository: MemoryRepository?,
    private val stateStore: TaskStateStore,
    private val stageAgentFactory: StageAgentFactory,
    private val stageAuditStore: TaskStageAuditStore = TaskStageAuditStore.None,
    private val contextProvider: TaskContextProvider = TaskContextProvider.None
) {
    private val maxValidationFailures = 2
    private var activeTask: TaskState? = stateStore.read()

    @Volatile
    private var pauseRequested: Boolean = activeTask?.lifecycleStatus == TaskLifecycleStatus.PAUSED

    fun currentState(): TaskState? = activeTask

    fun runTask(userTask: String, events: TaskOrchestratorEvents = TaskOrchestratorEvents.None): OrchestratorResponse {
        if (activeTask?.lifecycleStatus == TaskLifecycleStatus.PAUSED || !pauseRequested) {
            pauseRequested = false
        }
        val initial = TaskState(userTask = userTask)
        activeTask = initial
        save(initial)
        historyRepository.addUser(userTask)
        memoryRepository?.reinforcePersonalSignals(userTask)
        memoryRepository?.setWorkingStatus(TaskStatus.PENDING)

        return runCatchingTask(initial, events)
    }

    fun resumePausedTask(
        userMessage: String? = null,
        events: TaskOrchestratorEvents = TaskOrchestratorEvents.None
    ): OrchestratorResponse {
        val paused = activeTask?.takeIf { it.lifecycleStatus == TaskLifecycleStatus.PAUSED }
            ?: return OrchestratorResponse("No paused task to resume.")
        pauseRequested = false
        val resumed = paused.copy(
            lifecycleStatus = TaskLifecycleStatus.ACTIVE,
            clarifications = if (userMessage.isNullOrBlank()) paused.clarifications else paused.clarifications + userMessage.trim(),
            currentStage = resumeStage(paused),
            updatedAt = Instant.now(),
            pauseReason = null
        )
        activeTask = resumed
        save(resumed)
        memoryRepository?.setWorkingStatus(TaskStatus.PENDING)
        return runCatchingTask(resumed, events)
    }

    fun requestPause(reason: String = "Paused by user."): TaskState {
        pauseRequested = true
        return pause(reason).state ?: activeTask ?: TaskState(userTask = "")
    }

    fun pauseActiveOnShutdown() {
        val state = activeTask ?: return
        if (state.lifecycleStatus == TaskLifecycleStatus.ACTIVE) {
            requestPause("Paused because CLI stopped.")
        }
    }

    private fun runCatchingTask(initial: TaskState, events: TaskOrchestratorEvents): OrchestratorResponse =
        try {
            executeLoop(initial, events)
        } catch (exception: TaskPausedException) {
            Thread.interrupted()
            val paused = pause(exception.message ?: "Task paused by user.")
            paused.state?.let(events::onPaused)
            paused
        } catch (exception: RuntimeException) {
            val failed = (activeTask ?: initial).copy(
                lifecycleStatus = TaskLifecycleStatus.FAILED,
                updatedAt = Instant.now()
            )
            activeTask = failed
            save(failed)
            throw exception
        }

    private fun executeLoop(initial: TaskState, events: TaskOrchestratorEvents): OrchestratorResponse {
        var state = initial
        while (state.lifecycleStatus == TaskLifecycleStatus.ACTIVE) {
            throwIfPauseRequested(state.currentStage)
            val emittedStageEvents = linkedSetOf<String>()
            val liveStageEventSink = swarm.SwarmEventSink { content ->
                if (emittedStageEvents.add(content)) {
                    events.onStageEvent(state.currentStage, content)
                    historyRepository.addEvent(content)
                }
            }
            val agent = stageAgentFactory.create(state.currentStage, liveStageEventSink)
            val attempt = state.results.count { it.stage == state.currentStage } + 1
            val beforeHistory = agent.history
            val input = stageInput(state)

            events.onStageStarted(state.currentStage)
            val result = try {
                executeStage(agent, input, state.currentStage)
            } catch (exception: RuntimeException) {
                emitStageEvents(agent.history.drop(beforeHistory.size), state.currentStage, events, emittedStageEvents)
                throw exception
            }
            emitStageEvents(agent.history.drop(beforeHistory.size), state.currentStage, events, emittedStageEvents)
            stageAuditStore.append(
                stageAuditEntry(
                    taskId = state.id,
                    stage = state.currentStage,
                    attempt = attempt,
                    input = input,
                    beforeHistory = beforeHistory,
                    afterHistory = agent.history,
                    result = result
                )
            )
            events.onStageCompleted(result)
            historyRepository.addEvent(stageHistoryEvent(result))

            if (result.stage == TaskStage.PLANNING && !result.success) {
                val completed = state.copy(
                    lifecycleStatus = TaskLifecycleStatus.DONE,
                    currentStage = TaskStage.COMPLETION,
                    results = state.results + result,
                    stages = state.stages + StageState(TaskStage.COMPLETION),
                    updatedAt = Instant.now()
                )
                activeTask = completed
                save(completed)
                memoryRepository?.setWorkingStatus(TaskStatus.DONE)
                historyRepository.addAssistant(result.output, result.tokenUsage)
                return OrchestratorResponse(result.output, result.tokenUsage)
            }

            if (shouldPauseForInvalidTask(state, result)) {
                val paused = pauseWithResult(
                    state = state,
                    result = result,
                    reason = result.output.ifBlank { "Task needs clarification before continuing." }
                )
                events.onPaused(paused)
                return OrchestratorResponse("Task paused: ${paused.pauseReason}", state = paused)
            }

            state = appendResult(state, result)
            if (pauseRequested) {
                val paused = pause("Pause requested after ${result.stage}.")
                paused.state?.let(events::onPaused)
                return paused
            }

            if (state.currentStage == TaskStage.COMPLETION && result.stage == TaskStage.COMPLETION) {
                val completed = state.copy(lifecycleStatus = TaskLifecycleStatus.DONE, updatedAt = Instant.now())
                activeTask = completed
                save(completed)
                memoryRepository?.setWorkingStatus(TaskStatus.DONE)
                historyRepository.addAssistant(result.output, result.tokenUsage)
                return OrchestratorResponse(result.output, result.tokenUsage)
            }
        }
        return OrchestratorResponse("Task is ${state.lifecycleStatus}.")
    }

    private fun executeStage(agent: StageAgent, input: StageInput, currentStage: TaskStage): StageResult =
        try {
            agent.execute(input)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TaskPausedException("Pause requested during $currentStage.", exception)
        } catch (exception: AgentException) {
            if (isInterruptedPause(exception)) {
                Thread.currentThread().interrupt()
                throw TaskPausedException("Pause requested during $currentStage.", exception)
            }
            throw exception
        }

    private fun emitStageEvents(
        messages: List<chat.ChatMessage>,
        stage: TaskStage,
        events: TaskOrchestratorEvents,
        emitted: MutableSet<String> = linkedSetOf()
    ) {
        messages.filter { it.role == Role.EVENT }.forEach { event ->
            if (emitted.add(event.content)) {
                events.onStageEvent(stage, event.content)
                historyRepository.addEvent(event.content)
            }
        }
    }

    private fun throwIfPauseRequested(currentStage: TaskStage) {
        if (pauseRequested || Thread.currentThread().isInterrupted) {
            throw TaskPausedException("Pause requested before $currentStage.")
        }
    }

    private fun isInterruptedPause(exception: AgentException): Boolean =
        pauseRequested ||
            Thread.currentThread().isInterrupted ||
            generateSequence(exception.cause) { it.cause }.any { it is InterruptedException }

    private fun appendResult(state: TaskState, result: StageResult): TaskState {
        val next = if (result.stage == TaskStage.COMPLETION) {
            TaskStage.COMPLETION
        } else {
            TaskStateMachine.nextStage(state.currentStage, result)
        }
        if (result.stage != TaskStage.COMPLETION) {
            TaskStateMachine.assertTransition(state.currentStage, next)
        }
        val updated = state.copy(
            currentStage = next,
            results = state.results + result,
            stages = state.stages + StageState(next),
            updatedAt = Instant.now()
        )
        activeTask = updated
        save(updated)
        return updated
    }

    private fun shouldPauseForInvalidTask(state: TaskState, result: StageResult): Boolean {
        if (result.stage != TaskStage.VALIDATION || result.success) return false
        val failures = state.results.count { it.stage == TaskStage.VALIDATION && !it.success } + 1
        return failures >= maxValidationFailures || needsUserClarification(result)
    }

    private fun needsUserClarification(result: StageResult): Boolean {
        val text = (listOf(result.summary, result.output, result.retryReason.orEmpty()) +
            result.issues +
            result.requestedChanges)
            .joinToString(" ")
            .lowercase()
        return listOf(
            "\u0443\u0442\u043e\u0447",
            "\u0443\u043a\u0430\u0436",
            "\u0437\u0430\u0434\u0430\u0447",
            "\u043e\u043f\u0438\u0441\u0430\u043d",
            "clarif",
            "specify",
            "missing task",
            "not specified"
        ).any(text::contains)
    }

    private fun resumeStage(state: TaskState): TaskStage =
        when (state.currentStage) {
            TaskStage.PLANNING -> TaskStage.PLANNING
            TaskStage.EXECUTION -> TaskStage.EXECUTION
            TaskStage.VALIDATION -> TaskStage.EXECUTION
            TaskStage.COMPLETION -> TaskStage.COMPLETION
        }

    private fun stageInput(state: TaskState): StageInput =
        StageInput(
            taskId = state.id,
            userTask = state.userTask,
            previousResult = state.results.lastOrNull(),
            results = state.results,
            workingContext = contextProvider.contextFor(state),
            clarifications = state.clarifications
        )

    private fun pause(reason: String): OrchestratorResponse {
        val state = activeTask ?: TaskState(userTask = "")
        val paused = state.copy(
            lifecycleStatus = TaskLifecycleStatus.PAUSED,
            updatedAt = Instant.now(),
            pauseReason = reason
        )
        activeTask = paused
        save(paused)
        memoryRepository?.setWorkingStatus(TaskStatus.PAUSED)
        return OrchestratorResponse("Task paused: $reason", state = paused)
    }

    private fun pauseWithResult(state: TaskState, result: StageResult, reason: String): TaskState {
        val paused = state.copy(
            lifecycleStatus = TaskLifecycleStatus.PAUSED,
            results = state.results + result,
            stages = state.stages + StageState(state.currentStage, result = result),
            updatedAt = Instant.now(),
            pauseReason = reason
        )
        activeTask = paused
        save(paused)
        memoryRepository?.setWorkingStatus(TaskStatus.PAUSED)
        return paused
    }

    private fun save(state: TaskState) {
        stateStore.write(state)
    }

    private fun stageHistoryEvent(result: StageResult): String = buildString {
        appendLine("Stage ${result.stage.name}: ${if (result.success) "success" else "needs changes"}")
        appendLine("Summary: ${result.summary}")
        val visibleOutput = result.visibleHistoryOutput()
        if (visibleOutput.isNotBlank()) {
            appendLine()
            appendLine(visibleOutput)
        }
        if (result.issues.isNotEmpty()) {
            appendLine()
            appendLine("Issues:")
            result.issues.forEach { appendLine("- $it") }
        }
        if (result.requestedChanges.isNotEmpty()) {
            appendLine()
            appendLine("Requested changes:")
            result.requestedChanges.forEach { appendLine("- $it") }
        }
    }.trim()

    private fun StageResult.visibleHistoryOutput(): String =
        when (stage) {
            TaskStage.EXECUTION, TaskStage.VALIDATION -> output.takeUnless { it.looksLikeRawJson() }.orEmpty()
            TaskStage.PLANNING, TaskStage.COMPLETION -> ""
        }

    private fun String.looksLikeRawJson(): Boolean {
        val trimmed = trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}") &&
            listOf("\"success\"", "\"summary\"", "\"output\"").all(trimmed::contains)
    }
}

data class OrchestratorResponse(
    val content: String,
    val usage: TokenUsage = TokenUsage.ZERO,
    val state: TaskState? = null
)

interface TaskOrchestratorEvents {
    fun onStageStarted(stage: TaskStage) {}
    fun onStageCompleted(result: StageResult) {}
    fun onStageEvent(stage: TaskStage, content: String) {}
    fun onPaused(state: TaskState) {}

    object None : TaskOrchestratorEvents
}

private class TaskPausedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
