package cli

import agent.AgentException
import scheduled.ScheduleParsingAgentClient
import scheduled.ScheduledTaskManager
import scheduled.ScheduledTaskSummaryAgent

class TaskScreen(
    private val renderer: ConsoleRenderer,
    private val manager: ScheduledTaskManager,
    private val parser: ScheduleParsingAgentClient,
    private val summaryAgent: ScheduledTaskSummaryAgent?,
    private val input: ConsoleInput = ConsoleInput()
) {
    fun open() {
        renderHelp()

        while (true) {
            print("task> ")
            val command = input.readLine()?.trimTaskInput() ?: return
            if (command.isBlank()) {
                renderer.renderSystem("Enter a task command.")
                continue
            }

            val parts = command.split(Regex("\\s+"), limit = 3)
            when (parts.first().lowercase()) {
                "help" -> renderHelp()
                "list" -> renderer.renderScheduledTasks(manager.allTasks())
                "schedule" -> handleSchedule(parts)
                "stop" -> handleStop(parts)
                "clear" -> {
                    manager.clear()
                    renderer.renderSystem("All scheduled tasks stopped and cleared.")
                }
                "summary" -> handleSummary()
                "back", "exit" -> {
                    renderer.renderSystem("Returning to chat.")
                    return
                }
                else -> renderer.renderError("Unknown task command. Enter help.")
            }
        }
    }

    private fun handleSchedule(parts: List<String>) {
        if (parts.size < 3) {
            renderer.renderError("Usage: schedule <task-name> <natural-language-task>")
            return
        }
        try {
            renderer.renderSystem("Parsing schedule request...")
            val parsed = parser.parse(parts[2])
            val task = manager.schedule(parts[1], parts[2], parsed)
            renderer.renderSystem("Scheduled task '${task.name}' started: ${task.taskGoal} every ${task.interval}.")
        } catch (exception: IllegalArgumentException) {
            renderer.renderError(exception.message ?: "Invalid scheduled task.")
        } catch (exception: AgentException) {
            renderer.renderError(exception.message ?: "Could not parse schedule request.")
        } catch (exception: RuntimeException) {
            renderer.renderError("Could not schedule task: ${exception.message ?: exception::class.simpleName}")
        }
    }

    private fun handleStop(parts: List<String>) {
        val name = parts.getOrNull(1)
        if (parts.size != 2 || name.isNullOrBlank()) {
            renderer.renderError("Usage: stop <task-name>")
            return
        }
        if (manager.stop(name)) {
            renderer.renderSystem("Scheduled task stopped: $name")
        } else {
            renderer.renderError("Scheduled task not found: $name")
        }
    }

    private fun handleSummary() {
        val agent = summaryAgent
        if (agent == null) {
            renderer.renderError("Scheduled task summary agent is unavailable in this session.")
            return
        }
        try {
            renderer.renderSystem("Summarizing scheduled task records...")
            renderer.renderAssistant(agent.summarize(manager.allTasks()))
        } catch (exception: AgentException) {
            renderer.renderError(exception.message ?: "Could not summarize scheduled tasks.")
        } catch (exception: RuntimeException) {
            renderer.renderError("Could not summarize scheduled tasks: ${exception.message ?: exception::class.simpleName}")
        }
    }

    private fun renderHelp() {
        println("Task commands:")
        println("help - show task help")
        println("list - list scheduled background tasks")
        println("schedule <task-name> <natural-language-task> - create and start a scheduled task")
        println("stop <task-name> - stop a scheduled task without deleting records")
        println("clear - stop all scheduled tasks and clear saved records")
        println("summary - summarize saved scheduled task records")
        println("back - return to chat")
    }

    private fun String.trimTaskInput(): String = trim { it.isWhitespace() || it == '\uFEFF' }
}
