package task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskStateMachineTest {
    @Test
    fun `prompt validation starts the stage flow`() {
        val valid = StageResult(TaskStage.PROMPT_VALIDATION, success = true, summary = "valid", output = "Prompt accepted.")
        val invalid = StageResult(TaskStage.PROMPT_VALIDATION, success = false, summary = "invalid", output = "Clarify task.")

        assertEquals(TaskStage.PLANNING, TaskStateMachine.nextStage(TaskStage.PROMPT_VALIDATION, valid))
        assertEquals(TaskStage.COMPLETION, TaskStateMachine.nextStage(TaskStage.PROMPT_VALIDATION, invalid))
    }

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
