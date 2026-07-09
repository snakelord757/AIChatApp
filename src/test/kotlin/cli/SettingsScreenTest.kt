package cli

import agent.AgentSettings
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
    fun `model context window can be updated`() {
        val result = captureStdout {
            SettingsScreen(
                renderer = ConsoleRenderer(),
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("set modelContextWindow 32768\nback\n")
                    )
                )
            ).open(AgentSettings(apiKey = "", systemPrompt = "system"))
        }

        assertEquals(32768L, result.modelContextWindowTokens)
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

    @Test
    fun `model can be selected from configured available models`() {
        val result = captureStdout {
            SettingsScreen(
                renderer = ConsoleRenderer(),
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("set model qwen2.5-coder\nset model missing-model\nback\n")
                    )
                )
            ).open(
                AgentSettings(
                    apiKey = "",
                    model = "llama3.1",
                    availableModels = listOf("llama3.1", "qwen2.5-coder"),
                    systemPrompt = "system"
                )
            )
        }

        assertEquals("qwen2.5-coder", result.model)
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
