package cli

import agent.DeepSeekAiAgent
import agent.MockAiAgent
import chat.ChatHistoryRepository
import config.LocalPropertiesConfig
import formatting.ConsoleEncoding
import formatting.ConsoleScreen
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
        val historyRepository = ChatHistoryRepository(
            when (configResult) {
                is LocalPropertiesConfig.Result.Success -> configResult.settings.systemPrompt
                is LocalPropertiesConfig.Result.Failure -> configResult.fallbackSettings.systemPrompt
            }
        )

        val (settings, agent) = when (configResult) {
            is LocalPropertiesConfig.Result.Success -> configResult.settings to DeepSeekAiAgent(
                historyRepository = historyRepository,
                initialSettings = configResult.settings
            )
            is LocalPropertiesConfig.Result.Failure -> {
                renderer.renderError(configResult.message)
                renderer.renderSystem("Реальный ключ не найден, поэтому запущен локальный демонстрационный режим без обращения к DeepSeek.")
                configResult.fallbackSettings to MockAiAgent(
                    historyRepository = historyRepository,
                    initialSettings = configResult.fallbackSettings
                )
            }
        }

        ChatApplication(
            agent = agent,
            initialSettings = settings,
            historyRepository = historyRepository,
            renderer = renderer
        ).run()
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

        In chat mode, use /help, /settings, /clear, or /exit inside the session.
        """.trimIndent()
    )
}
