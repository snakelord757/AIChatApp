package cli

import agent.AgentResponse
import agent.AgentSettings
import agent.AiAgent
import agent.SummaryEvents
import chat.ChatHistoryRepository
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ChatApplicationCommandTest {
    @Test
    fun `russian slash command aliases are rejected`() {
        val output = captureStdout {
            val agent = RecordingAgent()
            ChatApplication(
                agent = agent,
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = ChatHistoryRepository(systemPrompt = "system"),
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(BufferedReader(StringReader("/\u043f\u043e\u043c\u043e\u0449\u044c\n/exit\n")))
            ).run()

            assertEquals(emptyList(), agent.messages)
        }

        assertContains(output, "Unknown command. Enter /help for the command list.")
        assertContains(output, "Goodbye!")
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

    private class RecordingAgent : AiAgent {
        val messages = mutableListOf<String>()

        override fun send(userMessage: String, summaryEvents: SummaryEvents): AgentResponse {
            messages += userMessage
            return AgentResponse("ok")
        }

        override fun updateSettings(settings: AgentSettings) = Unit
    }
}
