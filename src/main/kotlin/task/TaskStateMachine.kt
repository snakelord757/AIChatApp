package task

class InvalidTaskTransitionException(message: String) : IllegalStateException(message)

object TaskStateMachine {
    fun nextStage(current: TaskStage, result: StageResult): TaskStage {
        if (current.isFinal) {
            throw InvalidTaskTransitionException("COMPLETION is final.")
        }
        if (result.stage != current) {
            throw InvalidTaskTransitionException("Result stage ${result.stage} does not match current stage $current.")
        }
        return when (current) {
            TaskStage.PROMPT_VALIDATION -> if (result.success) TaskStage.PLANNING else TaskStage.COMPLETION
            TaskStage.PLANNING -> TaskStage.EXECUTION
            TaskStage.EXECUTION -> TaskStage.VALIDATION
            TaskStage.VALIDATION -> if (result.success) TaskStage.COMPLETION else TaskStage.EXECUTION
            TaskStage.COMPLETION -> throw InvalidTaskTransitionException("COMPLETION is final.")
        }
    }

    fun assertTransition(from: TaskStage, to: TaskStage) {
        val allowed = when (from) {
            TaskStage.PROMPT_VALIDATION -> setOf(TaskStage.PLANNING, TaskStage.COMPLETION)
            TaskStage.PLANNING -> setOf(TaskStage.EXECUTION)
            TaskStage.EXECUTION -> setOf(TaskStage.VALIDATION)
            TaskStage.VALIDATION -> setOf(TaskStage.COMPLETION, TaskStage.EXECUTION)
            TaskStage.COMPLETION -> emptySet()
        }
        if (to !in allowed) {
            throw InvalidTaskTransitionException("Transition $from -> $to is not allowed.")
        }
    }
}
