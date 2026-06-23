package mcp

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

internal object McpProcessCommand {
    fun resolve(command: List<String>): List<String> {
        if (command.isEmpty()) return command
        val executable = command.first()
        val resolvedExecutable = when (executable.lowercase()) {
            "java", "java.exe" -> javaExecutable()?.toString() ?: executable
            else -> executable
        }
        return listOf(resolvedExecutable) + command.drop(1)
    }

    internal fun javaExecutable(
        javaHomeProperty: String? = System.getProperty("java.home"),
        javaHomeEnvironment: String? = System.getenv("JAVA_HOME"),
        osName: String = System.getProperty("os.name"),
        exists: (Path) -> Boolean = { it.exists() }
    ): Path? =
        javaHomeCandidates(javaHomeProperty, javaHomeEnvironment)
            .map { Paths.get(it).resolve("bin").resolve(javaExecutableName(osName)).toAbsolutePath().normalize() }
            .firstOrNull(exists)

    private fun javaHomeCandidates(javaHomeProperty: String?, javaHomeEnvironment: String?): List<String> =
        listOf(javaHomeProperty, javaHomeEnvironment)
            .mapNotNull { it?.trim()?.trim('"')?.takeIf(String::isNotBlank) }
            .distinct()

    private fun javaExecutableName(osName: String): String =
        if (osName.contains("windows", ignoreCase = true)) "java.exe" else "java"
}
