package cli

import agent.AgentResponse
import agent.AgentSettings
import agent.AiAgent
import agent.SummaryEvents
import chat.ChatHistoryRepository
import chat.ContextStrategy
import chat.TokenUsage
import invariants.InvariantRepository
import invariants.InvariantStore
import memory.MemoryRepository
import memory.MemoryStore
import memory.TaskStatus
import mcp.McpClient
import mcp.McpConnectionState
import mcp.McpServerConfig
import mcp.McpServerStatus
import mcp.McpServerStore
import mcp.McpTool
import task.StageAgent
import task.StageAgentFactory
import task.StageInput
import task.StageResult
import task.TaskOrchestrator
import task.TaskStage
import task.TaskStateStore
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatApplicationCommandTest {
    @Test
    fun `input prompt marker is stripped before sending message`() {
        val agent = RecordingAgent()

        captureStdout {
            ChatApplication(
                agent = agent,
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = ChatHistoryRepository(systemPrompt = "system"),
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(BufferedReader(StringReader("> hello\n/exit\n")))
            ).run()
        }

        assertEquals(listOf("hello"), agent.messages)
    }

    @Test
    fun `input prompt marker is stripped when it is glued to message`() {
        val agent = RecordingAgent()

        captureStdout {
            ChatApplication(
                agent = agent,
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = ChatHistoryRepository(systemPrompt = "system"),
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(
                    BufferedReader(
                        StringReader(">hello\n>\u00A0world\n\uFF1E/fullwidth\n/exit\n")
                    )
                )
            ).run()
        }

        assertEquals(listOf("hello", "world"), agent.messages)
    }

    @Test
    fun `input prompt marker is stripped before command handling`() {
        val agent = RecordingAgent()

        val output = captureStdout {
            ChatApplication(
                agent = agent,
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = ChatHistoryRepository(systemPrompt = "system"),
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(BufferedReader(StringReader("> /exit\n")))
            ).run()
        }

        assertEquals(emptyList(), agent.messages)
        assertContains(output, "Goodbye!")
    }

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

    @Test
    fun `memory commands show and update markdown memory`() {
        val directory = Files.createTempDirectory("aichat-memory-command-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val store = MemoryStore(directory.resolve("memory"))
            val memoryRepository = MemoryRepository(store, repository::activeBranchIdOrMain)
            memoryRepository.ensureInitialized()
            Files.writeString(store.permanentPath(), "# Permanent\n\nAlways test memory.\n", StandardCharsets.UTF_8)

            val output = captureStdout {
                ChatApplication(
                    agent = RecordingAgent(),
                    initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                    historyRepository = repository,
                    memoryRepository = memoryRepository,
                    renderer = ConsoleRenderer(),
                    pricing = null,
                    showStartupWarning = false,
                    input = ConsoleInput(
                        BufferedReader(
                            StringReader("/memory\n/memory show permanent\n/memory pending\n/memory status\n/memory done\n/exit\n")
                        )
                    )
                ).run()
            }

            assertContains(output, "Markdown Memory")
            assertContains(output, "Always test memory.")
            assertContains(output, "Working memory status: PENDING")
            assertContains(output, "Working memory status: DONE")
            assertEquals(TaskStatus.DONE, memoryRepository.workingStatus())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `edit invariants opens file and does not add chat message`() {
        val directory = Files.createTempDirectory("aichat-edit-invariants-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val invariantRepository = InvariantRepository(InvariantStore(directory.resolve("invariants.md")))
            val opener = RecordingFileOpener()

            val output = captureStdout {
                ChatApplication(
                    agent = RecordingAgent(),
                    initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                    historyRepository = repository,
                    invariantRepository = invariantRepository,
                    fileOpener = opener,
                    renderer = ConsoleRenderer(),
                    pricing = null,
                    showStartupWarning = false,
                    input = ConsoleInput(BufferedReader(StringReader("/edit invariants\n/exit\n")))
                ).run()
            }

            val expectedPath = directory.resolve("invariants.md").toAbsolutePath().normalize()
            assertEquals(listOf(expectedPath), opener.opened)
            assertTrue(Files.exists(expectedPath))
            assertContains(output, "Invariants file: $expectedPath")
            assertEquals(listOf(chat.ChatMessage(chat.Role.SYSTEM, "system")), repository.all())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `edit invalid command shows usage`() {
        val output = captureStdout {
            ChatApplication(
                agent = RecordingAgent(),
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = ChatHistoryRepository(systemPrompt = "system"),
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(BufferedReader(StringReader("/edit\n/edit unknown\n/exit\n")))
            ).run()
        }

        assertEquals(2, Regex("Usage: /edit invariants").findAll(output).count())
    }

    @Test
    fun `clear does not modify invariants file`() {
        val directory = Files.createTempDirectory("aichat-clear-invariants-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val invariantRepository = InvariantRepository(InvariantStore(directory.resolve("invariants.md")))
            invariantRepository.ensureInitialized()
            Files.writeString(invariantRepository.path(), "# Assistant Invariants\n\n- Preserve me.\n", StandardCharsets.UTF_8)

            captureStdout {
                ChatApplication(
                    agent = RecordingAgent(),
                    initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                    historyRepository = repository,
                    invariantRepository = invariantRepository,
                    fileOpener = RecordingFileOpener(),
                    renderer = ConsoleRenderer(),
                    pricing = null,
                    showStartupWarning = false,
                    input = ConsoleInput(BufferedReader(StringReader("hello\n/clear\n/exit\n")))
                ).run()
            }

            assertEquals("# Assistant Invariants\n\n- Preserve me.\n", Files.readString(invariantRepository.path(), StandardCharsets.UTF_8))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `help includes pause command`() {
        val output = captureStdout {
            ChatApplication(
                agent = RecordingAgent(),
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = ChatHistoryRepository(systemPrompt = "system"),
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(BufferedReader(StringReader("/help\n/exit\n")))
            ).run()
        }

        assertContains(output, "/pause")
    }

    @Test
    fun `help includes mcp command`() {
        val output = captureStdout {
            ChatApplication(
                agent = RecordingAgent(),
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = ChatHistoryRepository(systemPrompt = "system"),
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                input = ConsoleInput(BufferedReader(StringReader("/help\n/exit\n")))
            ).run()
        }

        assertContains(output, "/mcp")
    }

    @Test
    fun `mcp opens context and commands are not sent to agent`() {
        val directory = Files.createTempDirectory("aichat-mcp-command-test")
        val agent = RecordingAgent()
        val client = FakeMcpClient()

        val output = captureStdout {
            ChatApplication(
                agent = agent,
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = ChatHistoryRepository(systemPrompt = "system"),
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                mcpScreenFactory = { renderer, input ->
                    McpScreen(renderer, McpServerStore(directory.resolve("mcp-servers.json")), client, input)
                },
                input = ConsoleInput(
                    BufferedReader(StringReader("/mcp\nconnect local node server.js\ntools local\nback\n/exit\n"))
                )
            ).run()
        }

        assertEquals(emptyList(), agent.messages)
        assertContains(output, "mcp> ")
        assertContains(output, "local: connected")
        assertContains(output, "Returned to chat.")
    }

    @Test
    fun `mcp context exits with back and returns to chat`() {
        val directory = Files.createTempDirectory("aichat-mcp-back-test")
        val agent = RecordingAgent()

        captureStdout {
            ChatApplication(
                agent = agent,
                initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                historyRepository = ChatHistoryRepository(systemPrompt = "system"),
                renderer = ConsoleRenderer(),
                pricing = null,
                showStartupWarning = false,
                mcpScreenFactory = { renderer, input ->
                    McpScreen(renderer, McpServerStore(directory.resolve("mcp-servers.json")), FakeMcpClient(), input)
                },
                input = ConsoleInput(BufferedReader(StringReader("/mcp\nback\nhello\n/exit\n")))
            ).run()
        }

        assertEquals(listOf("hello"), agent.messages)
    }

    @Test
    fun `pause command marks working memory paused`() {
        val directory = Files.createTempDirectory("aichat-pause-command-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val memoryRepository = MemoryRepository(MemoryStore(directory.resolve("memory")), repository::activeBranchIdOrMain)
            memoryRepository.ensureInitialized()

            val output = captureStdout {
                ChatApplication(
                    agent = RecordingAgent(),
                    initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                    historyRepository = repository,
                    memoryRepository = memoryRepository,
                    renderer = ConsoleRenderer(),
                    pricing = null,
                    showStartupWarning = false,
                    input = ConsoleInput(BufferedReader(StringReader("/pause\n/exit\n")))
                ).run()
            }

            assertContains(output, "Task paused.")
            assertEquals(TaskStatus.PAUSED, memoryRepository.workingStatus())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pause command can stop a running orchestrated task immediately`() {
        val directory = Files.createTempDirectory("aichat-running-pause-command-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val memoryRepository = MemoryRepository(MemoryStore(directory.resolve("memory")), repository::activeBranchIdOrMain)
            memoryRepository.ensureInitialized()
            val stageStarted = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val orchestrator = TaskOrchestrator(
                historyRepository = repository,
                memoryRepository = memoryRepository,
                stateStore = TaskStateStore(directory.resolve("task-state.json")),
                stageAgentFactory = BlockingStageFactory(stageStarted, interrupted)
            )

            val output = captureStdout {
                ChatApplication(
                    agent = RecordingAgent(),
                    initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                    historyRepository = repository,
                    memoryRepository = memoryRepository,
                    taskOrchestrator = orchestrator,
                    renderer = ConsoleRenderer(),
                    pricing = null,
                    showStartupWarning = false,
                    input = ConsoleInput(WaitingPauseReader(stageStarted))
                ).run()
            }

            assertContains(output, "Task started. You can type /pause")
            assertContains(output, "Pause requested.")
            assertContains(output, "Stopping the current stage")
            assertTrue(
                stageStarted.count == 1L || interrupted.count == 0L,
                "Pause should either stop before the stage starts or interrupt the running stage."
            )
            assertEquals(TaskStatus.PAUSED, memoryRepository.workingStatus())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `working memory status is pending during request and done after successful response`() {
        val directory = Files.createTempDirectory("aichat-memory-status-command-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val memoryRepository = MemoryRepository(MemoryStore(directory.resolve("memory")), repository::activeBranchIdOrMain)
            val agent = StatusAssertingAgent(memoryRepository)

            captureStdout {
                ChatApplication(
                    agent = agent,
                    initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                    historyRepository = repository,
                    memoryRepository = memoryRepository,
                    renderer = ConsoleRenderer(),
                    pricing = null,
                    showStartupWarning = false,
                    input = ConsoleInput(BufferedReader(StringReader("hello\n/exit\n")))
                ).run()
            }

            assertEquals(TaskStatus.PENDING, agent.statusDuringSend)
            assertEquals(TaskStatus.DONE, memoryRepository.workingStatus())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `working memory stays pending after failed response`() {
        val directory = Files.createTempDirectory("aichat-memory-failed-status-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val memoryRepository = MemoryRepository(MemoryStore(directory.resolve("memory")), repository::activeBranchIdOrMain)

            captureStdout {
                ChatApplication(
                    agent = FailingAgent(),
                    initialSettings = AgentSettings(apiKey = "", systemPrompt = "system"),
                    historyRepository = repository,
                    memoryRepository = memoryRepository,
                    renderer = ConsoleRenderer(),
                    pricing = null,
                    showStartupWarning = false,
                    input = ConsoleInput(BufferedReader(StringReader("hello\n/exit\n")))
                ).run()
            }

            assertEquals(TaskStatus.PENDING, memoryRepository.workingStatus())
        } finally {
            directory.toFile().deleteRecursively()
        }
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

    private class StatusAssertingAgent(
        private val memoryRepository: MemoryRepository
    ) : AiAgent {
        var statusDuringSend: TaskStatus? = null

        override fun send(userMessage: String, summaryEvents: SummaryEvents): AgentResponse {
            statusDuringSend = memoryRepository.workingStatus()
            return AgentResponse("ok")
        }

        override fun updateSettings(settings: AgentSettings) = Unit
    }

    private class FailingAgent : AiAgent {
        override fun send(userMessage: String, summaryEvents: SummaryEvents): AgentResponse {
            throw agent.AgentException("boom")
        }

        override fun updateSettings(settings: AgentSettings) = Unit
    }

    private class RecordingFileOpener : FileOpener {
        val opened = mutableListOf<Path>()

        override fun open(path: Path) {
            opened.add(path.toAbsolutePath().normalize())
        }
    }

    private class FakeMcpClient : McpClient {
        private val configs = linkedMapOf<String, McpServerConfig>()
        private val statuses = linkedMapOf<String, McpServerStatus>()

        override fun configure(configs: List<McpServerConfig>) {
            configs.forEach {
                this.configs[it.name] = it
                statuses.putIfAbsent(it.name, McpServerStatus(it.name, McpConnectionState.CONFIGURED))
            }
        }

        override fun connect(config: McpServerConfig): McpServerStatus {
            configs[config.name] = config
            return McpServerStatus(config.name, McpConnectionState.CONNECTED).also { statuses[config.name] = it }
        }

        override fun disconnect(serverName: String) {
            configs.remove(serverName)
            statuses.remove(serverName)
        }

        override fun clear() {
            configs.clear()
            statuses.clear()
        }

        override fun listServers(): List<McpServerStatus> =
            configs.keys.sorted().map { statuses[it] ?: McpServerStatus(it, McpConnectionState.CONFIGURED) }

        override fun listTools(serverName: String): List<McpTool> = listOf(McpTool(serverName, "demo_tool"))

        override fun close() = Unit
    }

    private class WaitingPauseReader(
        private val stageStarted: CountDownLatch
    ) : BufferedReader(StringReader("")) {
        private val index = AtomicInteger(0)

        override fun readLine(): String? =
            when (index.getAndIncrement()) {
                0 -> "long task"
                1 -> {
                    stageStarted.await(2, TimeUnit.SECONDS)
                    "/pause"
                }
                2 -> "/exit"
                else -> null
            }
    }

    private class BlockingStageFactory(
        private val stageStarted: CountDownLatch,
        private val interrupted: CountDownLatch
    ) : StageAgentFactory {
        override fun create(stage: TaskStage): StageAgent = object : StageAgent {
            override val stage: TaskStage = stage
            override val history = emptyList<chat.ChatMessage>()

            override fun execute(input: StageInput): StageResult {
                stageStarted.countDown()
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(5))
                } catch (exception: InterruptedException) {
                    interrupted.countDown()
                    Thread.currentThread().interrupt()
                    throw exception
                }
                return StageResult(stage, success = true, summary = "$stage done", output = "$stage output")
            }
        }
    }
}
