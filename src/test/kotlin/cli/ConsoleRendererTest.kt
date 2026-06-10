package cli

import chat.ChatMessage
import chat.Role
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
