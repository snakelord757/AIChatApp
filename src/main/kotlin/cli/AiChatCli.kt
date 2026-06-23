package cli

import agent.DeepSeekAiAgent
import agent.MockAiAgent
import chat.ChatHistoryBusyException
import chat.ChatHistoryRepository
import chat.ChatHistoryStore
import config.LocalPropertiesConfig
import formatting.ConsoleEncoding
import formatting.ConsoleScreen
import chat.AppPaths
import invariants.InvariantRepository
import invariants.InvariantStore
import memory.MemoryRepository
import memory.MemoryStore
import task.DeepSeekStageChatClient
import task.DefaultStageAgentFactory
import task.StageChatClient
import task.StageChatResponse
import task.TaskStage
import task.TaskOrchestrator
import task.JsonlTaskStageAuditStore
import task.OrchestratorTaskContextProvider
import task.TaskStateStore
import swarm.JsonSwarmSessionStore
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

private const val CLI_NAME = "aichat"
private const val CLI_VERSION = "1.0.0"

fun main(args: Array<String>) {
    exitProcess(AiChatCli.run(args))
}

object AiChatCli {
    fun run(args: Array<String>): Int {
        return when (val command = CliArguments.parse(args)) {
            CliCommand.Chat -> {
                runInteractiveChat()
                0
            }
            CliCommand.Help -> {
                printHelp()
                0
            }
            CliCommand.Version -> {
                println("$CLI_NAME $CLI_VERSION")
                0
            }
            is CliCommand.Invalid -> {
                System.err.println(command.message)
                System.err.println()
                printHelp(System.err)
                2
            }
        }
    }

    private fun runInteractiveChat() {
        ConsoleEncoding.configureConsole()
        ConsoleScreen.clear()

        val renderer = ConsoleRenderer()
        val configResult = LocalPropertiesConfig.load()
        val historyStore = try {
            ChatHistoryStore.open()
        } catch (exception: ChatHistoryBusyException) {
            renderer.renderError(exception.message ?: "Cannot continue because the chat history file is locked by another process.")
            return
        }

        try {
            val restoredState = historyStore.readState()
            val restoredMessages = restoredState.messages
            val settings = when (configResult) {
                is LocalPropertiesConfig.Result.Success -> configResult.settings
                is LocalPropertiesConfig.Result.Failure -> configResult.fallbackSettings
            }
            val pricing = when (configResult) {
                is LocalPropertiesConfig.Result.Success -> configResult.pricing
                is LocalPropertiesConfig.Result.Failure -> configResult.pricing
            }
            val pricingWarning = when (configResult) {
                is LocalPropertiesConfig.Result.Success -> configResult.pricingWarning
                is LocalPropertiesConfig.Result.Failure -> configResult.pricingWarning
            }
            val settingsRef = AtomicReference(settings)

            val historyRepository = ChatHistoryRepository(
                systemPrompt = settings.systemPrompt,
                restoredState = restoredState,
                onChanged = historyStore::writeState
            )
            val memoryRepository = MemoryRepository(
                store = MemoryStore(AppPaths.memoryDirectory()),
                activeBranchKeyProvider = historyRepository::activeBranchIdOrMain
            )
            memoryRepository.ensureInitialized()
            val invariantRepository = InvariantRepository(InvariantStore(AppPaths.invariantsPath()))
            invariantRepository.ensureInitialized()

            val agent = when (configResult) {
                is LocalPropertiesConfig.Result.Success -> DeepSeekAiAgent(
                    historyRepository = historyRepository,
                    initialSettings = configResult.settings,
                    invariantRepository = invariantRepository,
                    memoryRepository = memoryRepository
                )
                is LocalPropertiesConfig.Result.Failure -> {
                    renderer.renderError(configResult.message)
                    renderer.renderSystem("No real key was found, so local demo mode is running without calling DeepSeek.")
                    MockAiAgent(
                        historyRepository = historyRepository,
                        initialSettings = configResult.fallbackSettings,
                        invariantRepository = invariantRepository,
                        memoryRepository = memoryRepository
                    )
                }
            }
            val stageClientFactory = {
                when (configResult) {
                    is LocalPropertiesConfig.Result.Success -> DeepSeekStageChatClient(settingsRef.get())
                    is LocalPropertiesConfig.Result.Failure -> DemoStageChatClient()
                }
            }
            val swarmStageClientFactory = { role: swarm.SwarmRole ->
                when (configResult) {
                    is LocalPropertiesConfig.Result.Success -> DeepSeekStageChatClient(settingsRef.get().copy(temperature = role.temperature))
                    is LocalPropertiesConfig.Result.Failure -> DemoStageChatClient(role.displayName)
                }
            }
            val taskOrchestrator = TaskOrchestrator(
                historyRepository = historyRepository,
                memoryRepository = memoryRepository,
                stateStore = TaskStateStore(AppPaths.taskStatePath()),
                stageAgentFactory = DefaultStageAgentFactory(
                    clientFactory = stageClientFactory,
                    settingsProvider = { settingsRef.get() },
                    swarmClientFactory = swarmStageClientFactory,
                    swarmSynthesizerClientFactory = stageClientFactory,
                    swarmSessionStore = JsonSwarmSessionStore(AppPaths.planningSwarmSessionPath())
                ),
                stageAuditStore = JsonlTaskStageAuditStore(AppPaths.taskStageAuditPath()),
                contextProvider = OrchestratorTaskContextProvider(
                    settingsProvider = { settingsRef.get() },
                    historyRepository = historyRepository,
                    invariantRepository = invariantRepository,
                    memoryRepository = memoryRepository
                )
            )

            ChatApplication(
                agent = agent,
                initialSettings = settings,
                historyRepository = historyRepository,
                invariantRepository = invariantRepository,
                memoryRepository = memoryRepository,
                taskOrchestrator = taskOrchestrator,
                onSettingsChanged = settingsRef::set,
                renderer = renderer,
                pricing = pricing,
                startupWarning = pricingWarning,
                showStartupWarning = restoredMessages.isEmpty()
            ).run()
        } finally {
            historyStore.close()
        }
    }
}

object CliArguments {
    fun parse(args: Array<String>): CliCommand {
        if (args.isEmpty()) return CliCommand.Chat

        return when {
            args.size == 1 && args[0] in setOf("chat", "run") -> CliCommand.Chat
            args.size == 1 && args[0] in setOf("-h", "--help", "help") -> CliCommand.Help
            args.size == 1 && args[0] in setOf("-V", "--version", "version") -> CliCommand.Version
            else -> CliCommand.Invalid("Unknown argument: ${args.joinToString(" ")}")
        }
    }
}

sealed interface CliCommand {
    data object Chat : CliCommand
    data object Help : CliCommand
    data object Version : CliCommand
    data class Invalid(val message: String) : CliCommand
}

private fun printHelp(out: java.io.PrintStream = System.out) {
    out.println(
        """
        AIChatApp command line interface

        Usage:
          $CLI_NAME [chat]
          $CLI_NAME --help
          $CLI_NAME --version

        Commands:
          chat          Start the interactive AI chat session.

        Options:
          -h, --help    Show this help message.
          -V, --version Show the CLI version.

        In chat mode, use /help, /settings, /mcp, /summary, /facts, /memory, /edit invariants, /pause, /resume, /clear, or /exit inside the session.
        """.trimIndent()
    )
}

private class DemoStageChatClient(
    private val swarmRole: String? = null
) : StageChatClient {
    override fun send(messages: List<chat.ChatMessage>): StageChatResponse {
        if (swarmRole != null) {
            return StageChatResponse(
                """
                {"role":"$swarmRole","stance":"approve","summary":"$swarmRole demo response","proposal":"Contribute to a safe executable plan.","concerns":[],"requiredChanges":[],"invariantConcerns":[]}
                """.trimIndent()
            )
        }
        val stage = when {
            messages.firstOrNull()?.content?.contains("planning swarm orchestrator", ignoreCase = true) == true -> TaskStage.PLANNING
            messages.firstOrNull()?.content?.contains("PlanningAgent") == true -> TaskStage.PLANNING
            messages.firstOrNull()?.content?.contains("ExecutionAgent") == true -> TaskStage.EXECUTION
            messages.firstOrNull()?.content?.contains("ValidationAgent") == true -> TaskStage.VALIDATION
            messages.firstOrNull()?.content?.contains("CompletionAgent") == true -> TaskStage.COMPLETION
            else -> TaskStage.EXECUTION
        }
        val output = when (stage) {
            TaskStage.PLANNING -> "Demo plan: understand the request, execute it, validate the result, then summarize the outcome."
            TaskStage.EXECUTION -> "Demo execution completed. Add a real DEEPSEEK_API_KEY to local.properties for model-backed execution."
            TaskStage.VALIDATION -> "Demo validation passed."
            TaskStage.COMPLETION -> "Demo task completed. Add DEEPSEEK_API_KEY to local.properties to receive real staged answers."
        }
        return StageChatResponse(
            """
            {"success":true,"summary":"${stage.name} completed","output":"$output","issues":[],"requestedChanges":[],"retryReason":null}
            """.trimIndent()
        )
    }
}
