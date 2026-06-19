package swarm

import agent.AgentSettings
import chat.ChatHistoryRepository
import chat.ChatMessage
import chat.Role
import chat.TokenUsage
import task.DefaultStageAgentFactory
import task.StageAgent
import task.StageAgentFactory
import task.StageChatClient
import task.StageChatResponse
import task.StageInput
import task.StageResult
import task.TaskOrchestrator
import task.TaskLifecycleStatus
import task.TaskOrchestratorEvents
import task.TaskStage
import task.TaskStateStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanningSwarmStageAgentTest {
    @Test
    fun `planning swarm disabled creates prompted planning agent`() {
        val agent = DefaultStageAgentFactory(
            settingsProvider = { AgentSettings(apiKey = "", planningSwarmEnabled = false, systemPrompt = "system") }
        ) { ConstantClient() }.create(TaskStage.PLANNING)

        assertFalse(agent is PlanningSwarmStageAgent)
    }

    @Test
    fun `planning swarm enabled creates planning swarm stage agent`() {
        val agent = DefaultStageAgentFactory(
            settingsProvider = { AgentSettings(apiKey = "", planningSwarmEnabled = true, systemPrompt = "system") }
        ) { ConstantClient() }.create(TaskStage.PLANNING)

        assertIs<PlanningSwarmStageAgent>(agent)
    }

    @Test
    fun `different roles use configured temperatures`() {
        val usedTemperatures = mutableListOf<Double>()
        val agent = PlanningSwarmStageAgent(
            clientFactory = { role ->
                usedTemperatures += role.temperature
                RoleClient(role)
            },
            synthesizerClientFactory = { ConstantClient() }
        )

        agent.execute(input())

        assertEquals(SwarmRole.entries.map { it.temperature }, usedTemperatures)
    }

    @Test
    fun `final planning result remains stage result compatible`() {
        val agent = PlanningSwarmStageAgent(
            clientFactory = { role -> RoleClient(role) },
            synthesizerClientFactory = {
                ConstantClient(
                    """{"success":true,"summary":"swarm plan","output":"Execute the requested change safely.","issues":[],"requestedChanges":[],"retryReason":null}"""
                )
            }
        )

        val result = agent.execute(input())

        assertEquals(TaskStage.PLANNING, result.stage)
        assertTrue(result.success)
        assertEquals("swarm plan", result.summary)
        assertEquals("Execute the requested change safely.", result.output)
        assertEquals(emptyList(), result.issues)
        assertEquals(emptyList(), result.requestedChanges)
        assertEquals(null, result.retryReason)
    }

    @Test
    fun `swarm dialogue is added to chat history as event and excluded from api context`() {
        val directory = Files.createTempDirectory("aichat-swarm-history-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = TaskStateStore(directory.resolve("task-state.json")),
                stageAgentFactory = SwarmPlanningFactory()
            )

            orchestrator.runTask("ship swarm")

            val event = history.all().firstOrNull {
                it.role == Role.EVENT && it.content.startsWith("Stage PLANNING swarm dialogue:")
            }
            assertTrue(event != null)
            assertContains(event.content, "Round 1")
            assertContains(event.content, "[Strategist]")
            assertFalse(history.apiContextMessages().any { it.content.contains("swarm dialogue") })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `swarm agent messages are emitted live with role information`() {
        val directory = Files.createTempDirectory("aichat-swarm-live-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val liveEvents = mutableListOf<String>()
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = TaskStateStore(directory.resolve("task-state.json")),
                stageAgentFactory = SwarmPlanningFactory()
            )

            orchestrator.runTask(
                "ship live swarm",
                object : TaskOrchestratorEvents {
                    override fun onStageEvent(stage: TaskStage, content: String) {
                        liveEvents += content
                    }
                }
            )

            val liveMessages = liveEvents.filter { it.startsWith("Stage PLANNING swarm message:") }
            assertEquals(SwarmRole.entries.size, liveMessages.size)
            assertContains(liveMessages.first(), "Round 1")
            assertContains(liveMessages.first(), "[Strategist]")
            assertContains(liveMessages.last(), "[Risk Manager]")
            val storedMessages = history.all().filter {
                it.role == Role.EVENT && it.content.startsWith("Stage PLANNING swarm message:")
            }
            assertEquals(SwarmRole.entries.size, storedMessages.size)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `default factory emits each swarm message before next role starts`() {
        val directory = Files.createTempDirectory("aichat-swarm-live-order-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val liveEvents = mutableListOf<String>()
            val orchestrator = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = TaskStateStore(directory.resolve("task-state.json")),
                stageAgentFactory = DefaultStageAgentFactory(
                    settingsProvider = { AgentSettings(apiKey = "", planningSwarmEnabled = true, systemPrompt = "system") },
                    swarmClientFactory = { role ->
                        OrderingRoleClient(role, liveEvents)
                    },
                    swarmSynthesizerClientFactory = { ConstantClient() },
                    clientFactory = { ConstantClient() }
                )
            )

            orchestrator.runTask(
                "ship live ordered swarm",
                object : TaskOrchestratorEvents {
                    override fun onStageEvent(stage: TaskStage, content: String) {
                        liveEvents += content
                    }
                }
            )

            val liveMessages = liveEvents.filter { it.startsWith("Stage PLANNING swarm message:") }
            assertEquals(SwarmRole.entries.size, liveMessages.size)
            assertContains(liveMessages[0], "[Strategist]")
            assertContains(liveMessages[1], "[Requirements Analyst]")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `swarm dialogue resets for each new task`() {
        val events = mutableListOf<String>()
        val agent = PlanningSwarmStageAgent(
            clientFactory = { role -> RoleClient(role) },
            synthesizerClientFactory = { ConstantClient() },
            eventSink = SwarmEventSink(events::add)
        )

        agent.execute(input("first task"))
        agent.execute(input("second task"))

        val dialogueEvents = events.filter { it.startsWith("Stage PLANNING swarm dialogue:") }
        val messageEvents = events.filter { it.startsWith("Stage PLANNING swarm message:") }
        assertEquals(2, dialogueEvents.size)
        assertEquals(SwarmRole.entries.size * 2, messageEvents.size)
        assertEquals(1, Regex("Round 1").findAll(dialogueEvents[0]).count())
        assertEquals(1, Regex("Round 1").findAll(dialogueEvents[1]).count())
        assertFalse(dialogueEvents[1].contains("first task"))
    }

    @Test
    fun `role prompts require active participation and temperatures stay within supported range`() {
        SwarmRole.entries.forEach { role ->
            assertTrue(role.temperature in 0.0..2.0)
            assertContains(role.systemPrompt, "do not merely agree")
            assertContains(role.systemPrompt, "Contribute independent")
            assertContains(role.systemPrompt, "directly relevant to the user's current task domain")
        }
        assertContains(SwarmRole.SOLUTION_ARCHITECT.systemPrompt, "Do not invent software components")
        assertFalse(SwarmRole.SOLUTION_ARCHITECT.systemPrompt.contains("component boundaries"))
    }

    @Test
    fun `working context with invariants and memory is available to agents`() {
        val capturedPrompts = mutableListOf<String>()
        val agent = PlanningSwarmStageAgent(
            clientFactory = { role -> CapturingRoleClient(role, capturedPrompts) },
            synthesizerClientFactory = { ConstantClient() }
        )

        agent.execute(
            input(
                workingContext = """
                Assistant invariants:
                - Use Kotlin.

                Personal memory about the user:
                - Prefers concise Russian answers.
                """.trimIndent()
            )
        )

        assertTrue(capturedPrompts.isNotEmpty())
        capturedPrompts.forEach { prompt ->
            assertContains(prompt, "Assistant invariants:")
            assertContains(prompt, "Use Kotlin.")
            assertContains(prompt, "Personal memory about the user:")
            assertContains(prompt, "Do not infer that a non-programming task requires code")
            assertContains(prompt, "do not propose software modules")
            assertContains(prompt, "keep the plan in that domain unless the user explicitly asks for software implementation")
        }
    }

    @Test
    fun `non code swarm agent retries software implementation artifact response`() {
        var calls = 0
        val capturedPrompts = mutableListOf<List<ChatMessage>>()
        val client = object : StageChatClient {
            override fun send(messages: List<ChatMessage>): StageChatResponse {
                calls++
                capturedPrompts += messages
                return if (calls == 1) {
                    StageChatResponse(
                        """{"role":"Solution Architect","stance":"approve","summary":"Use architecture","proposal":"Create TravelContext data class and ScoringEngine interface.","concerns":[],"requiredChanges":[],"invariantConcerns":[]}"""
                    )
                } else {
                    StageChatResponse(
                        """{"role":"Solution Architect","stance":"approve","summary":"Structure the travel decision","proposal":"Group the plan into criteria, comparison, and final choice without software artifacts.","concerns":[],"requiredChanges":[],"invariantConcerns":[]}"""
                    )
                }
            }
        }

        val message = SwarmAgent(SwarmAgentConfig(SwarmRole.SOLUTION_ARCHITECT), client)
            .respond(input(userTask = "Составь план выбора страны для путешествия"), round = 1, dialogue = SwarmDialogue())

        assertEquals(2, calls)
        assertContains(capturedPrompts.last().last().content, "non-software task")
        assertFalse(message.proposal.contains("TravelContext"))
        assertFalse(message.proposal.contains("ScoringEngine"))
    }

    @Test
    fun `non code swarm agent sanitizes internal orchestration terms after failed retry`() {
        val client = object : StageChatClient {
            override fun send(messages: List<ChatMessage>): StageChatResponse =
                StageChatResponse(
                    """{"role":"Execution Coordinator","stance":"approve","summary":"Create checkpoints for ExecutionAgent","proposal":"Checkpoint A produces outputs for ExecutionAgent handoff. Add TravelContext and ScoringEngine.","concerns":[],"requiredChanges":[],"invariantConcerns":[]}"""
                )
        }

        val message = SwarmAgent(SwarmAgentConfig(SwarmRole.EXECUTION_COORDINATOR), client)
            .respond(input(userTask = "Составь план выбора страны для путешествия"), round = 1, dialogue = SwarmDialogue())

        val visible = message.toLiveEventText()
        assertFalse(visible.contains("ExecutionAgent"))
        assertFalse(visible.contains("Checkpoint"))
        assertFalse(visible.contains("handoff", ignoreCase = true))
        assertFalse(visible.contains("TravelContext"))
        assertFalse(visible.contains("ScoringEngine"))
    }

    @Test
    fun `non code synthesis sanitizes software artifacts from final stage json`() {
        val orchestrator = SwarmOrchestrator(
            agents = emptyList(),
            synthesizerClient = ConstantClient(
                """{"success":true,"summary":"Use TravelContext and ExecutionAgent","output":"Create TravelContext, ScoringEngine, and checkpoints for ExecutionAgent handoff.","issues":[],"requestedChanges":[],"retryReason":null}"""
            ),
            maxRounds = 1
        )

        val result = orchestrator.run(input(userTask = "Составь план выбора страны для путешествия"))

        assertFalse(result.finalJson.contains("TravelContext"))
        assertFalse(result.finalJson.contains("ScoringEngine"))
        assertFalse(result.finalJson.contains("ExecutionAgent"))
        assertFalse(result.finalJson.contains("handoff", ignoreCase = true))
    }

    @Test
    fun `non code paused swarm resets saved software implementation dialogue`() {
        val directory = Files.createTempDirectory("aichat-swarm-reset-software-dialogue-test")
        try {
            val sessionStore = JsonSwarmSessionStore(directory.resolve("planning-swarm-session.json"))
            sessionStore.write(
                SwarmSession(
                    taskId = "task-1",
                    dialogue = SwarmDialogue(
                        listOf(
                            SwarmRound(
                                1,
                                listOf(
                                    SwarmMessage(
                                        role = SwarmRole.SOLUTION_ARCHITECT,
                                        round = 1,
                                        stance = "approve",
                                        summary = "Software architecture",
                                        proposal = "Create TravelContext data class and ScoringEngine interface.",
                                        rawContent = "{}"
                                    )
                                )
                            )
                        )
                    )
                )
            )
            val events = mutableListOf<String>()
            val calledRoles = mutableListOf<SwarmRole>()
            val agent = PlanningSwarmStageAgent(
                clientFactory = { role -> RecordingRoleClient(role, calledRoles) },
                synthesizerClientFactory = { ConstantClient() },
                eventSink = SwarmEventSink(events::add),
                sessionStore = sessionStore
            )

            agent.execute(
                input(
                    userTask = "Составь план выбора страны для путешествия",
                    taskId = "task-1"
                )
            )

            assertTrue(events.any { it.startsWith("Stage PLANNING swarm session reset:") })
            assertEquals(SwarmRole.STRATEGIST, calledRoles.first())
            assertEquals(null, sessionStore.read("task-1"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `max rounds prevents infinite discussion`() {
        val calls = mutableListOf<SwarmRole>()
        val agent = PlanningSwarmStageAgent(
            clientFactory = { role -> BlockingRoleClient(role, calls) },
            synthesizerClientFactory = { ConstantClient("""{"success":false,"summary":"blocked","output":"Needs clarification.","issues":["No consensus"],"requestedChanges":[],"retryReason":"No consensus"}""") },
            maxRounds = 3
        )

        val result = agent.execute(input())

        assertEquals(15, calls.size)
        assertEquals(false, result.success)
        assertEquals(listOf("No consensus"), result.issues)
    }

    @Test
    fun `paused swarm dialogue is restored and resumed by a new orchestrator`() {
        val directory = Files.createTempDirectory("aichat-swarm-resume-test")
        try {
            val history = ChatHistoryRepository(systemPrompt = "system")
            val taskStore = TaskStateStore(directory.resolve("task-state.json"))
            val sessionStore = JsonSwarmSessionStore(directory.resolve("planning-swarm-session.json"))
            val firstRunFactory = ResumableSwarmFactory(
                sessionStore = sessionStore,
                roleClientFactory = { role ->
                    if (role == SwarmRole.REQUIREMENTS_ANALYST) InterruptingClient else RoleClient(role)
                }
            )
            val first = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = taskStore,
                stageAgentFactory = firstRunFactory
            )

            val paused = first.runTask("ship resumable swarm")

            assertContains(paused.content, "Task paused")
            assertEquals(TaskLifecycleStatus.PAUSED, taskStore.read()?.lifecycleStatus)
            val partialEvent = history.all().firstOrNull {
                it.role == Role.EVENT && it.content.startsWith("Stage PLANNING swarm dialogue:")
            }
            assertTrue(partialEvent != null)
            assertContains(partialEvent.content, "[Strategist]")
            val taskId = taskStore.read()?.id ?: error("Task id should be persisted.")
            val savedDialogue = sessionStore.read(taskId)
            assertEquals(listOf(SwarmRole.STRATEGIST), savedDialogue?.messages?.map { it.role })

            val resumedRoles = mutableListOf<SwarmRole>()
            val resumed = TaskOrchestrator(
                historyRepository = history,
                memoryRepository = null,
                stateStore = taskStore,
                stageAgentFactory = ResumableSwarmFactory(
                    sessionStore = sessionStore,
                    roleClientFactory = { role ->
                        RecordingRoleClient(role, resumedRoles)
                    }
                )
            )

            resumed.resumePausedTask()

            assertEquals(TaskLifecycleStatus.DONE, taskStore.read()?.lifecycleStatus)
            assertEquals(SwarmRole.REQUIREMENTS_ANALYST, resumedRoles.first())
            assertEquals(SwarmRole.entries.drop(1), resumedRoles)
            assertEquals(null, sessionStore.read(taskId))
            val swarmEvent = history.all().last { it.role == Role.EVENT && it.content.startsWith("Stage PLANNING swarm dialogue:") }
            assertContains(swarmEvent.content, "[Strategist]")
            assertContains(swarmEvent.content, "[Requirements Analyst]")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun input(
        userTask: String = "build feature",
        workingContext: String = "Assistant invariants:\n- Stay safe.",
        taskId: String? = null
    ): StageInput =
        StageInput(
            userTask = userTask,
            previousResult = null,
            results = emptyList(),
            workingContext = workingContext,
            clarifications = emptyList(),
            taskId = taskId
        )

    private class SwarmPlanningFactory : StageAgentFactory {
        override fun create(stage: TaskStage): StageAgent = create(stage, SwarmEventSink.None)

        override fun create(stage: TaskStage, eventSink: SwarmEventSink): StageAgent =
            if (stage == TaskStage.PLANNING) {
                PlanningSwarmStageAgent(
                    clientFactory = { role -> RoleClient(role) },
                    synthesizerClientFactory = { ConstantClient() },
                    eventSink = eventSink
                )
            } else {
                object : StageAgent {
                    override val stage: TaskStage = stage
                    override val history: List<ChatMessage> = emptyList()

                    override fun execute(input: StageInput): StageResult =
                        StageResult(stage, success = true, summary = "$stage done", output = "$stage output")
                }
            }
    }

    private class ResumableSwarmFactory(
        private val sessionStore: SwarmSessionStore,
        private val roleClientFactory: (SwarmRole) -> StageChatClient
    ) : StageAgentFactory {
        override fun create(stage: TaskStage): StageAgent = create(stage, SwarmEventSink.None)

        override fun create(stage: TaskStage, eventSink: SwarmEventSink): StageAgent =
            if (stage == TaskStage.PLANNING) {
                PlanningSwarmStageAgent(
                    clientFactory = roleClientFactory,
                    synthesizerClientFactory = { ConstantClient() },
                    eventSink = eventSink,
                    sessionStore = sessionStore
                )
            } else {
                object : StageAgent {
                    override val stage: TaskStage = stage
                    override val history: List<ChatMessage> = emptyList()

                    override fun execute(input: StageInput): StageResult =
                        StageResult(stage, success = true, summary = "$stage done", output = "$stage output")
                }
            }
    }

    private open class RoleClient(
        private val role: SwarmRole
    ) : StageChatClient {
        override fun send(messages: List<ChatMessage>): StageChatResponse =
            StageChatResponse(
                """{"role":"${role.displayName}","stance":"approve","summary":"${role.displayName} approves","proposal":"${role.displayName} proposal","concerns":[],"requiredChanges":[],"invariantConcerns":[]}""",
                TokenUsage(inputTokens = 1, outputTokens = 1)
            )
    }

    private class RecordingRoleClient(
        private val role: SwarmRole,
        private val calls: MutableList<SwarmRole>
    ) : RoleClient(role) {
        override fun send(messages: List<ChatMessage>): StageChatResponse {
            calls += role
            return super.send(messages)
        }
    }

    private class CapturingRoleClient(
        role: SwarmRole,
        private val capturedPrompts: MutableList<String>
    ) : RoleClient(role) {
        override fun send(messages: List<ChatMessage>): StageChatResponse {
            capturedPrompts += messages.last().content
            return super.send(messages)
        }
    }

    private class BlockingRoleClient(
        private val role: SwarmRole,
        private val calls: MutableList<SwarmRole>
    ) : StageChatClient {
        override fun send(messages: List<ChatMessage>): StageChatResponse {
            calls += role
            val stance = if (role == SwarmRole.RISK_MANAGER) "block" else "approve"
            return StageChatResponse(
                """{"role":"${role.displayName}","stance":"$stance","summary":"${role.displayName} $stance","proposal":"proposal","concerns":[],"requiredChanges":[],"invariantConcerns":[]}"""
            )
        }
    }

    private class OrderingRoleClient(
        private val role: SwarmRole,
        private val liveEvents: List<String>
    ) : StageChatClient {
        override fun send(messages: List<ChatMessage>): StageChatResponse {
            if (role == SwarmRole.REQUIREMENTS_ANALYST) {
                assertTrue(
                    liveEvents.any {
                        it.startsWith("Stage PLANNING swarm message:") && it.contains("[Strategist]")
                    },
                    "Strategist live event should be emitted before Requirements Analyst starts."
                )
            }
            return RoleClient(role).send(messages)
        }
    }

    private class ConstantClient(
        private val content: String = """{"success":true,"summary":"planned","output":"Plan for execution.","issues":[],"requestedChanges":[],"retryReason":null}"""
    ) : StageChatClient {
        override fun send(messages: List<ChatMessage>): StageChatResponse = StageChatResponse(content)
    }

    private object InterruptingClient : StageChatClient {
        override fun send(messages: List<ChatMessage>): StageChatResponse {
            throw InterruptedException("pause swarm")
        }
    }
}
