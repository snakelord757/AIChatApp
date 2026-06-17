package cli

import chat.ChatMessage
import chat.Role
import task.StageResult
import task.TaskStage
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ConsoleRendererTest {
    @Test
    fun `history renders visible dialog messages only`() {
        val output = captureStdout {
            ConsoleRenderer().renderHistory(
                listOf(
                    ChatMessage(Role.SYSTEM, "hidden system prompt"),
                    ChatMessage(Role.USER, "previous user message"),
                    ChatMessage(Role.ASSISTANT, "previous assistant message")
                )
            )
        }

        assertContains(output, "previous user message")
        assertContains(output, "previous assistant message")
        assertFalse(output.contains("hidden system prompt"))
    }

    @Test
    fun `stage renderer hides internal planning output`() {
        val output = captureStdout {
            ConsoleRenderer().renderStageResult(
                StageResult(
                    stage = TaskStage.PLANNING,
                    success = true,
                    summary = "Plan prepared.",
                    output = "ExecutionAgent, produce the user-facing answer."
                )
            )
        }

        assertContains(output, "Plan prepared.")
        assertFalse(output.contains("ExecutionAgent"))
    }

    @Test
    fun `history renderer hides stored internal stage output`() {
        val output = captureStdout {
            ConsoleRenderer().renderHistory(
                listOf(
                    ChatMessage(
                        Role.EVENT,
                        "Stage PLANNING: success\nSummary: Plan prepared.\n\nExecutionAgent, produce the user-facing answer."
                    )
                )
            )
        }

        assertContains(output, "Stage result: PLANNING")
        assertContains(output, "Status: success")
        assertContains(output, "Summary: Plan prepared.")
        assertFalse(output.contains("ExecutionAgent"))
        assertFalse(output.contains("System: Stage PLANNING"))
    }

    @Test
    fun `history renderer restores stage event in live stage format`() {
        val output = captureStdout {
            ConsoleRenderer().renderHistory(
                listOf(
                    ChatMessage(
                        Role.EVENT,
                        """
                        Stage VALIDATION: needs changes
                        Summary: Validation found gaps.

                        Short validation note.

                        Issues:
                        - Missing test
                        Requested changes:
                        - Add regression test
                        """.trimIndent()
                    )
                )
            )
        }

        assertContains(output, "Stage result: VALIDATION")
        assertContains(output, "Status: needs changes")
        assertContains(output, "Summary: Validation found gaps.")
        assertContains(output, "Short validation note.")
        assertContains(output, "Issues:")
        assertContains(output, "- Missing test")
        assertContains(output, "Requested changes:")
        assertContains(output, "- Add regression test")
        assertFalse(output.contains("System: Stage VALIDATION"))
    }

    @Test
    fun `history renderer keeps non-stage events as system messages`() {
        val output = captureStdout {
            ConsoleRenderer().renderHistory(
                listOf(ChatMessage(Role.EVENT, "Chat summarization completed."))
            )
        }

        assertContains(output, "System: Chat summarization completed.")
    }

    @Test
    fun `prompt is cleared before normal output`() {
        val output = captureStdout {
            val renderer = ConsoleRenderer()
            renderer.prompt()
            renderer.renderSystem("Message after prompt.")
        }

        assertContains(output, "Message after prompt.")
        assertFalse(output.contains("> System: Message after prompt."))
    }

    @Test
    fun `submitted prompt line is cleared before rendering the user message`() {
        val output = captureStdout {
            val renderer = ConsoleRenderer()
            renderer.prompt()
            renderer.finishPromptInput(clearSubmittedLine = true)
            renderer.renderUser("hello")
        }

        assertContains(output, "\u001B[1A\r\u001B[2K")
        assertContains(output, "You:")
        assertContains(output, "hello")
        assertFalse(output.contains("> hello"))
    }

    @Test
    fun `prompt is cleared before background output and redrawn after it`() {
        val output = captureStdout {
            val renderer = ConsoleRenderer()
            renderer.prompt()
            renderer.prepareBackgroundOutput()
            renderer.renderStageStarted(TaskStage.EXECUTION)
            renderer.prompt()
        }

        assertContains(output, "Stage: EXECUTION")
        assertFalse(output.contains("> \nStage: EXECUTION"))
        assertContains(output, "> ")
    }

    @Test
    fun `stage renderer hides raw json output`() {
        val output = captureStdout {
            ConsoleRenderer().renderStageResult(
                StageResult(
                    stage = TaskStage.VALIDATION,
                    success = true,
                    summary = "Validation passed.",
                    output = """{"success":true,"summary":"Validation passed.","output":"","issues":[],"requestedChanges":[]}"""
                )
            )
        }

        assertContains(output, "Validation passed.")
        assertFalse(output.contains("\"success\""))
    }

    private fun captureStdout(block: () -> Unit): String {
        val originalOut = System.out
        val stream = ByteArrayOutputStream()
        System.setOut(PrintStream(stream, true, Charsets.UTF_8))
        return try {
            block()
            stream.toString(Charsets.UTF_8)
        } finally {
            System.setOut(originalOut)
        }
    }
}
