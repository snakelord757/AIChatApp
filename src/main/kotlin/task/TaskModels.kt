package task

import chat.TokenUsage
import java.time.Instant
import java.util.UUID

enum class TaskStage {
    PLANNING,
    EXECUTION,
    VALIDATION,
    COMPLETION;

    val isFinal: Boolean
        get() = this == COMPLETION
}

enum class TaskLifecycleStatus {
    ACTIVE,
    PAUSED,
    DONE,
    FAILED
}

data class StageResult(
    val stage: TaskStage,
    val success: Boolean,
    val summary: String,
    val output: String,
    val issues: List<String> = emptyList(),
    val requestedChanges: List<String> = emptyList(),
    val retryReason: String? = null,
    val tokenUsage: TokenUsage = TokenUsage.ZERO
)

data class StageState(
    val stage: TaskStage,
    val attempts: Int = 0,
    val result: StageResult? = null
)

data class TaskState(
    val id: String = UUID.randomUUID().toString(),
    val userTask: String,
    val lifecycleStatus: TaskLifecycleStatus = TaskLifecycleStatus.ACTIVE,
    val currentStage: TaskStage = TaskStage.PLANNING,
    val stages: List<StageState> = listOf(StageState(TaskStage.PLANNING)),
    val results: List<StageResult> = emptyList(),
    val clarifications: List<String> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
    val pauseReason: String? = null
)

data class TaskContinuation(
    val task: TaskState,
    val userMessage: String? = null
)

data class StageInput(
    val userTask: String,
    val previousResult: StageResult?,
    val results: List<StageResult>,
    val workingContext: String,
    val clarifications: List<String>
)
