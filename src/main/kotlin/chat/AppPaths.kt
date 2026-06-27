package chat

import java.nio.file.Path
import java.nio.file.Paths

object AppPaths {
    fun applicationDirectory(): Path {
        System.getProperty("aichat.history.dir")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).toAbsolutePath().normalize()
        }

        if (isWindows()) {
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let {
                return Paths.get(it).resolve("AIChatApp").toAbsolutePath().normalize()
            }
            System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let {
                return Paths.get(it).resolve("AppData").resolve("Local").resolve("AIChatApp").toAbsolutePath().normalize()
            }
        }

        System.getenv("APP_HOME")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).toAbsolutePath().normalize()
        }

        System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).resolve(".aichat").toAbsolutePath().normalize()
        }

        return Paths.get("").toAbsolutePath().normalize()
    }

    fun historyPath(): Path = applicationDirectory().resolve("chat-history.json")

    fun memoryDirectory(): Path = applicationDirectory().resolve("memory")

    fun invariantsPath(): Path = applicationDirectory().resolve("invariants.md")

    fun taskStatePath(): Path = applicationDirectory().resolve("task-state.json")

    fun taskStageAuditPath(): Path = applicationDirectory().resolve("task-stage-audit.jsonl")

    fun scheduledTasksPath(): Path = applicationDirectory().resolve("scheduled-tasks.json")

    fun scheduledTaskStatePath(taskName: String, runId: String): Path =
        applicationDirectory().resolve("scheduled-tasks").resolve(safePathPart(taskName)).resolve("$runId-state.json")

    fun scheduledTaskStageAuditPath(taskName: String, runId: String): Path =
        applicationDirectory().resolve("scheduled-tasks").resolve(safePathPart(taskName)).resolve("$runId-audit.jsonl")

    fun planningSwarmSessionPath(): Path = applicationDirectory().resolve("planning-swarm-session.json")

    fun mcpServersPath(): Path = applicationDirectory().resolve("mcp-servers.json")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").contains("windows", ignoreCase = true)

    private fun safePathPart(value: String): String =
        value.replace(Regex("""[^A-Za-z0-9._-]+"""), "_").ifBlank { "task" }
}
