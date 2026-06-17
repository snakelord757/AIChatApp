package chat

import java.nio.file.Path
import java.nio.file.Paths

object AppPaths {
    fun applicationDirectory(): Path {
        System.getProperty("aichat.history.dir")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).toAbsolutePath().normalize()
        }

        System.getenv("APP_HOME")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).toAbsolutePath().normalize()
        }

        if (isWindows()) {
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let {
                return Paths.get(it).resolve("AIChatApp").toAbsolutePath().normalize()
            }
        }

        System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).resolve(".aichat").toAbsolutePath().normalize()
        }

        return Paths.get("").toAbsolutePath().normalize()
    }

    fun historyPath(): Path = applicationDirectory().resolve("chat-history.json")

    fun memoryDirectory(): Path = applicationDirectory().resolve("memory")

    fun taskStatePath(): Path = applicationDirectory().resolve("task-state.json")

    fun taskStageAuditPath(): Path = applicationDirectory().resolve("task-stage-audit.jsonl")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").contains("windows", ignoreCase = true)
}
