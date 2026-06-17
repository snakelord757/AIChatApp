package task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskStateMachineTest {
    @Test
    fun `planning cannot jump to validation`() {
        assertFailsWith<InvalidTaskTransitionException> {
            TaskStateMachine.assertTransition(TaskStage.PLANNING, TaskStage.VALIDATION)
        }
    }

    @Test
    fun `execution cannot be skipped`() {
        val planning = StageResult(TaskStage.PLANNING, success = true, summary = "plan", output = "plan")

        assertEquals(TaskStage.EXECUTION, TaskStateMachine.nextStage(TaskStage.PLANNING, planning))
        assertFailsWith<InvalidTaskTransitionException> {
            TaskStateMachine.assertTransition(TaskStage.PLANNING, TaskStage.COMPLETION)
        }
    }

    @Test
    fun `validation can return to execution`() {
        val validation = StageResult(
            stage = TaskStage.VALIDATION,
            success = false,
            summary = "problems",
            output = "fix it",
            issues = listOf("missing validation"),
            requestedChanges = listOf("retry")
        )

        assertEquals(TaskStage.EXECUTION, TaskStateMachine.nextStage(TaskStage.VALIDATION, validation))
    }

    @Test
    fun `completion is final`() {
        assertFailsWith<InvalidTaskTransitionException> {
            TaskStateMachine.nextStage(
                TaskStage.COMPLETION,
                StageResult(TaskStage.COMPLETION, success = true, summary = "done", output = "done")
            )
        }
    }
}
