package cli

import agent.AgentResponse
import agent.AgentSettings
import agent.AiAgent
import agent.SummaryEvents
import chat.ChatHistoryRepository
import chat.ContextStrategy
import chat.TokenUsage
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `branch create command creates branch and is not sent to agent`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        val agent = RecordingAgent()

        val output = captureStdout {
            ChatApplication(
                agent = agent,
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = repository,
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("\u200B/branch create feature branch\n/exit\n")
                    )
                )
            ).run()
        }

        assertEquals(emptyList(), agent.messages)
        assertEquals(listOf("feature branch"), repository.branchNames())
        assertEquals("feature branch", repository.activeBranchName())
        assertContains(output, "Branch created and activated: feature branch")
    }

    @Test
    fun `branch switch reloads only selected branch history`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("main base")
        repository.checkpoint()
        repository.createBranch("alpha")
        repository.addUser("alpha detail")
        repository.switchBranch("main")
        repository.addUser("main tail")

        val output = captureStdout {
            ChatApplication(
                agent = RecordingAgent(),
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = repository,
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("/branch switch alpha\n/exit\n")
                    )
                )
            ).run()
        }

        val afterSwitch = output.substringAfter("Switched to branch: alpha")
        assertContains(afterSwitch, "main base")
        assertContains(afterSwitch, "alpha detail")
        assertTrue(!afterSwitch.contains("main tail"))
    }

    @Test
    fun `enabling sticky facts strategy prints composed markers`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("goal: show facts")

        val output = captureStdout {
            ChatApplication(
                agent = RecordingAgent(),
                initialSettings = AgentSettings(
                    apiKey = "",
                    contextStrategy = ContextStrategy.SLIDING_WINDOW,
                    systemPrompt = "system"
                ),
                historyRepository = repository,
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("/settings\nset contextStrategy facts\nback\n/exit\n")
                    )
                )
            ).run()
        }

        assertContains(output, "Sticky Facts")
        assertContains(output, "goal: show facts")
    }

    @Test
    fun `sticky facts strategy prints markers after user message updates memory`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")

        val output = captureStdout {
            ChatApplication(
                agent = RepositoryUpdatingAgent(repository),
                initialSettings = AgentSettings(
                    apiKey = "",
                    contextStrategy = ContextStrategy.STICKY_FACTS,
                    systemPrompt = "system"
                ),
                historyRepository = repository,
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("goal: show facts after message\n/exit\n")
                    )
                )
            ).run()
        }

        assertContains(output, "Sticky Facts")
        assertContains(output, "goal: show facts after message")
    }

    @Test
    fun `sticky facts strategy prints markers even when memory is unchanged`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.applyExtractedFacts("goal: show facts repeatedly")

        val output = captureStdout {
            ChatApplication(
                agent = RecordingAgent(),
                initialSettings = AgentSettings(
                    apiKey = "",
                    contextStrategy = ContextStrategy.STICKY_FACTS,
                    systemPrompt = "system"
                ),
                historyRepository = repository,
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("first\nsecond\n/exit\n")
                    )
                )
            ).run()
        }

        assertTrue(Regex("Sticky Facts").findAll(output).count() >= 3)
        assertContains(output, "goal: show facts repeatedly")
    }

    @Test
    fun `sticky facts strategy prints facts request tokens after facts`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")

        val output = captureStdout {
            ChatApplication(
                agent = FactsUsageAgent(repository),
                initialSettings = AgentSettings(
                    apiKey = "",
                    contextStrategy = ContextStrategy.STICKY_FACTS,
                    systemPrompt = "system"
                ),
                historyRepository = repository,
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(
                    BufferedReader(
                        StringReader("remember\n/exit\n")
                    )
                )
            ).run()
        }

        val factsBlock = output.substringAfter("Sticky Facts")
        assertContains(factsBlock, "goal: token accounting")
        assertContains(factsBlock, "Tokens: input=5, output=2, reasoning=1, total=8")
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

    private class RepositoryUpdatingAgent(
        private val repository: ChatHistoryRepository
    ) : AiAgent {
        override fun send(userMessage: String, summaryEvents: SummaryEvents): AgentResponse {
            repository.addUser(userMessage)
            repository.addAssistant("ok")
            return AgentResponse("ok")
        }

        override fun updateSettings(settings: AgentSettings) = Unit
    }

    private class FactsUsageAgent(
        private val repository: ChatHistoryRepository
    ) : AiAgent {
        override fun send(userMessage: String, summaryEvents: SummaryEvents): AgentResponse {
            repository.addUser(userMessage)
            repository.applyExtractedFacts(
                content = "goal: token accounting",
                usage = TokenUsage(inputTokens = 5, outputTokens = 2, reasoningTokens = 1)
            )
            repository.addAssistant("ok")
            return AgentResponse("ok")
        }

        override fun updateSettings(settings: AgentSettings) = Unit
    }
}
