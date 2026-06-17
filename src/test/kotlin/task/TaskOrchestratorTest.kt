package task

import chat.ChatHistoryRepository
import agent.AgentSettings
import chat.ContextStrategy
import memory.MemoryRepository
import memory.MemoryStore
import memory.TaskStatus
import chat.Role
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskOrchestratorTest {
    @Test
    fun `task state store normalizes restored stage blockquotes`() {
        val directory = Files.createTempDirectory("aichat-task-state-prompt-marker-test")
        try {
            val path = directory.resolve("task-state.json")
            Files.writeString(
                path,
                """
                {
                  "id": "task-1",
                  "userTask": "> user task stays",
                  "lifecycleStatus": "DONE",
                  "currentStage": "COMPLETION",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z",
                  "pauseReason": null,
                  "results": [
                    {"stage":"EXECUTION","success":true,"summary":"> summary","output":"Answer\n> detail","issues":["> issue"],"requestedChanges":["> change"],"retryReason":"> retry","tokenUsage":{"inputTokens":1,"outputTokens":2,"reasoningTokens":3}}
                  ]
                }
                """.trimIndent(),
                StandardCharsets.UTF_8
            )

            val state = TaskStateStore(path).read()
            val result = state?.results?.single()

            assertEquals("> user task stays", state?.userTask)
            assertEquals("Note: summary", result?.summary)
            assertEquals("Answer\nNote: detail", result?.output)
            assertEquals(listOf("Note: issue"), result?.issues)
            assertEquals(listOf("Note: change"), result?.requestedChanges)
            assertEquals("Note: retry", result?.retryReason)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `orchestrator context includes markdown memory messages`() {
        val directory = Files.createTempDirectory("aichat-stage-memory-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val store = MemoryStore(directory.resolve("memory"))
            val memory = MemoryRepository(store, history::activeBranchIdOrMain)
            memory.ensureInitialized()
            Files.writeString(store.permanentPath(), "# Permanent Memory\n\nAlways prefer tests.\n", StandardCharsets.UTF_8)
            Files.writeString(store.personalPath(), "# Personal Memory\n\n- [strength: 3] Prefers concise Russian answers\n", StandardCharsets.UTF_8)
            store.writeWork("main", "# Working Memory\n\nStatus: PENDING\n\n## Current Task\nShip orchestration.\n")
            val factory = RecordingFactory()
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = memory,
                stateStore = TaskStateStore(directory.resolve("task-state.json")),
                stageAgentFactory = factory,
                contextProvider = OrchestratorTaskContextProvider(
                    settingsProvider = { AgentSettings(apiKey = "", systemPrompt = "system") },
                    historyRepository = history,
                    memoryRepository = memory
                )
            )

            orchestrator.runTask("build feature")

            val context = factory.inputs.first().workingContext
            assertContains(context, "Permanent memory instructions:")
            assertContains(context, "Always prefer tests.")
            assertContains(context, "Personal memory about the user:")
            assertContains(context, "Prefers concise Russian answers")
            assertContains(context, "Working memory for the active branch:")
            assertContains(context, "Ship orchestration.")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `context strategy is applied by orchestrator context provider only`() {
        val directory = Files.createTempDirectory("aichat-orchestrator-context-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            history.addUser("goal: keep facts")
            history.addAssistant("ack")
            history.applyExtractedFacts("project: context orchestration")
            val factory = RecordingFactory()
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = TaskStateStore(directory.resolve("task-state.json")),
                stageAgentFactory = factory,
                contextProvider = OrchestratorTaskContextProvider(
                    settingsProvider = {
                        AgentSettings(
                            apiKey = "",
                            systemPrompt = "system",
                            contextStrategy = ContextStrategy.STICKY_FACTS,
                            contextWindowMessages = 1
                        )
                    },
                    historyRepository = history,
                    memoryRepository = null
                )
            )

            orchestrator.runTask("use context")

            assertContains(factory.inputs.first().workingContext, "Sticky facts:")
            assertContains(factory.inputs.first().workingContext, "project: context orchestration")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stage result is passed to next stage`() {
        val directory = Files.createTempDirectory("aichat-stage-result-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val factory = RecordingFactory()
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = TaskStateStore(directory.resolve("task-state.json")),
                stageAgentFactory = factory
            )

            orchestrator.runTask("ship")

            assertEquals("PLANNING output", factory.inputs[1].previousResult?.output)
            assertEquals("EXECUTION output", factory.inputs[2].previousResult?.output)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stage audit is stored separately from public chat history`() {
        val directory = Files.createTempDirectory("aichat-stage-audit-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val auditPath = directory.resolve("task-stage-audit.jsonl")
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = TaskStateStore(directory.resolve("task-state.json")),
                stageAgentFactory = RecordingFactory(),
                stageAuditStore = JsonlTaskStageAuditStore(auditPath)
            )

            orchestrator.runTask("ship audit")

            val publicMessages = history.all()
            assertEquals(2, publicMessages.count { it.role == Role.USER || it.role == Role.ASSISTANT })
            assertEquals(1, publicMessages.count { it.role == Role.USER })
            assertEquals(1, publicMessages.count { it.role == Role.ASSISTANT })
            assertFalse(publicMessages.any { it.content.contains("PLANNING output") && it.role == Role.USER })

            val audit = Files.readString(auditPath)
            assertContains(audit, """"stage":"PLANNING"""")
            assertContains(audit, """"stage":"EXECUTION"""")
            assertContains(audit, """"stage":"VALIDATION"""")
            assertContains(audit, """"stage":"COMPLETION"""")
            assertContains(audit, """"rawResponse"""")
            assertContains(audit, """"promptMessages"""")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stage outputs are visible as chat events but excluded from agent context`() {
        val directory = Files.createTempDirectory("aichat-stage-events-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = TaskStateStore(directory.resolve("task-state.json")),
                stageAgentFactory = RecordingFactory()
            )

            orchestrator.runTask("ship visible stages")

            val events = history.all().filter { it.role == Role.EVENT }
            assertEquals(4, events.count { it.content.startsWith("Stage ") })
            assertContains(events.joinToString("\n") { it.content }, "Stage PLANNING: success")
            assertContains(events.joinToString("\n") { it.content }, "EXECUTION output")
            assertFalse(events.joinToString("\n") { it.content }.contains("PLANNING output"))

            val agentContext = history.apiContextMessages()
            assertFalse(agentContext.any { it.role == Role.EVENT })
            assertFalse(agentContext.any { it.content.contains("Stage PLANNING") })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }



    @Test
    fun `pause is persisted`() {
        val directory = Files.createTempDirectory("aichat-pause-store-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val memory = MemoryRepository(MemoryStore(directory.resolve("memory")), history::activeBranchIdOrMain)
            memory.ensureInitialized()
            val store = TaskStateStore(directory.resolve("task-state.json"))
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = memory,
                stateStore = store,
                stageAgentFactory = RecordingFactory()
            )

            orchestrator.requestPause("test pause")

            assertEquals(TaskLifecycleStatus.PAUSED, store.read()?.lifecycleStatus)
            assertEquals(TaskStatus.PAUSED, memory.workingStatus())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pause request interrupts running stage and keeps task paused`() {
        val directory = Files.createTempDirectory("aichat-interrupt-pause-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val memory = MemoryRepository(MemoryStore(directory.resolve("memory")), history::activeBranchIdOrMain)
            memory.ensureInitialized()
            val store = TaskStateStore(directory.resolve("task-state.json"))
            val stageStarted = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = memory,
                stateStore = store,
                stageAgentFactory = InterruptibleStageFactory(stageStarted, interrupted)
            )
            val response = AtomicReference<OrchestratorResponse>()
            val failure = AtomicReference<Throwable>()

            val worker = Thread {
                try {
                    response.set(orchestrator.runTask("long task"))
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                }
            }
            worker.start()

            assertTrue(stageStarted.await(1, TimeUnit.SECONDS), "Stage should start before the pause request.")
            orchestrator.requestPause("test pause")
            worker.interrupt()

            assertTrue(interrupted.await(1, TimeUnit.SECONDS), "Running stage should be interrupted by pause.")
            worker.join(TimeUnit.SECONDS.toMillis(1))

            assertFalse(worker.isAlive, "Worker should stop after the running stage is interrupted.")
            assertEquals(null, failure.get())
            assertContains(response.get()?.content.orEmpty(), "Task paused")
            assertEquals(TaskLifecycleStatus.PAUSED, store.read()?.lifecycleStatus)
            assertEquals(TaskStatus.PAUSED, memory.workingStatus())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validation retry loop is paused after repeated failures`() {
        val directory = Files.createTempDirectory("aichat-validation-loop-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val store = TaskStateStore(directory.resolve("task-state.json"))
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = store,
                stageAgentFactory = RepeatedValidationFailureFactory()
            )

            val response = orchestrator.runTask("ambiguous")

            assertContains(response.content, "Task paused")
            assertEquals(TaskLifecycleStatus.PAUSED, store.read()?.lifecycleStatus)
            assertEquals(2, store.read()?.results?.count { it.stage == TaskStage.VALIDATION && !it.success })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resume continues paused task without creating a new user prompt`() {
        val directory = Files.createTempDirectory("aichat-resume-paused-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val store = TaskStateStore(directory.resolve("task-state.json"))
            store.write(
                TaskState(
                    userTask = "original task",
                    lifecycleStatus = TaskLifecycleStatus.PAUSED,
                    currentStage = TaskStage.EXECUTION,
                    results = listOf(StageResult(TaskStage.PLANNING, true, "planned", "plan"))
                )
            )
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = store,
                stageAgentFactory = RecordingFactory()
            )

            orchestrator.resumePausedTask()

            assertFalse(history.all().any { it.role == Role.USER && it.content.contains("continue", ignoreCase = true) })
            assertEquals(TaskLifecycleStatus.DONE, store.read()?.lifecycleStatus)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }


    @Test
    fun `active task is paused on shutdown`() {
        val directory = Files.createTempDirectory("aichat-shutdown-pause-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val store = TaskStateStore(directory.resolve("task-state.json"))
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = store,
                stageAgentFactory = RecordingFactory()
            )

            orchestrator.runTask("done task")
            store.write(TaskState(userTask = "active task"))
            val restored = TaskOrchestrator(history, null, store, RecordingFactory())
            restored.pauseActiveOnShutdown()

            assertEquals(TaskLifecycleStatus.PAUSED, store.read()?.lifecycleStatus)
            assertContains(store.read()?.pauseReason.orEmpty(), "CLI stopped")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class RecordingFactory : StageAgentFactory {
        val inputs = mutableListOf<StageInput>()

        override fun create(stage: TaskStage): StageAgent = object : StageAgent {
            override val stage: TaskStage = stage
            override val history = emptyList<chat.ChatMessage>()

            override fun execute(input: StageInput): StageResult {
                inputs += input
                return StageResult(stage, success = true, summary = "$stage summary", output = "$stage output")
            }
        }
    }

    private class RepeatedValidationFailureFactory : StageAgentFactory {
        override fun create(stage: TaskStage): StageAgent = object : StageAgent {
            override val stage: TaskStage = stage
            override val history = emptyList<chat.ChatMessage>()

            override fun execute(input: StageInput): StageResult =
                when (stage) {
                    TaskStage.PLANNING -> StageResult(stage, success = true, summary = "plan", output = "plan")
                    TaskStage.EXECUTION -> StageResult(stage, success = true, summary = "execution", output = "execution")
                    TaskStage.VALIDATION -> StageResult(stage, success = false, summary = "invalid", output = "invalid", issues = listOf("failed"))
                    TaskStage.COMPLETION -> StageResult(stage, success = true, summary = "done", output = "done")
                }
        }
    }

    private class InterruptibleStageFactory(
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
                return StageResult(stage, success = true, summary = "$stage summary", output = "$stage output")
            }
        }
    }
}
