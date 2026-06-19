package cli

import agent.AgentSettings
import chat.ContextStrategy
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsScreenTest {
    @Test
    fun `summary interval zero is accepted and negative values are rejected`() {
        val result = captureStdout {
            SettingsScreen(
                renderer = ConsoleRenderer(),
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("set summaryInterval 0\nset summaryInterval -1\nback\n")
                    )
                )
            ).open(AgentSettings(apiKey = "", summaryInterval = 20, systemPrompt = "system"))
        }

        assertEquals(0, result.summaryInterval)
    }

    @Test
    fun `context strategy and window can be updated`() {
        val result = captureStdout {
            SettingsScreen(
                renderer = ConsoleRenderer(),
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("set contextStrategy facts\nset contextWindow 7\nback\n")
                    )
                )
            ).open(AgentSettings(apiKey = "", systemPrompt = "system"))
        }

        assertEquals(ContextStrategy.STICKY_FACTS, result.contextStrategy)
        assertEquals(7, result.contextWindowMessages)
    }

    @Test
    fun `planning swarm setting is shown and can be disabled`() {
        val result = captureStdout {
            SettingsScreen(
                renderer = ConsoleRenderer(),
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("show\nset planningSwarmEnabled false\nback\n")
                    )
                )
            ).open(AgentSettings(apiKey = "", planningSwarmEnabled = true, systemPrompt = "system"))
        }

        assertEquals(false, result.planningSwarmEnabled)
    }

    private fun captureStdout(block: () -> AgentSettings): AgentSettings {
        val originalOut = System.out
        val stream = ByteArrayOutputStream()
        System.setOut(PrintStream(stream, true, Charsets.UTF_8))
        return try {
            block()
        } finally {
            System.setOut(originalOut)
        }
    }
}
