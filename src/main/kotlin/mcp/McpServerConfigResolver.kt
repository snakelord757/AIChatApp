package mcp

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

object McpServerConfigResolver {
    fun resolve(name: String, command: List<String>): McpServerConfig? {
        if (name.isBlank() || command.isEmpty()) return null
        command.singleOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { url ->
                return McpServerConfig(
                    name = name,
                    command = emptyList(),
                    transport = McpTransport.HTTP,
                    url = url
                )
            }
        if (command.size == 1) {
            val path = Paths.get(command.first()).toAbsolutePath().normalize()
            if (path.exists() && path.isDirectory()) {
                return fromProjectDirectory(name, path)
            }
        }
        return McpServerConfig(name, command)
    }

    private fun fromProjectDirectory(name: String, directory: Path): McpServerConfig? {
        val clientConfig = directory.resolve("examples").resolve("mcp-client-config.json")
        if (clientConfig.exists()) {
            runCatching { fromClientConfig(name, directory, clientConfig) }.getOrNull()?.let { return it }
        }

        val jar = directory.resolve("build").resolve("libs")
            .takeIf { it.exists() && it.isDirectory() }
            ?.let { libs ->
                Files.list(libs).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".jar") }
                        .sorted()
                        .findFirst()
                        .orElse(null)
                }
            }

        return jar?.let {
            McpServerConfig(
                name = name,
                command = listOf("java", "-jar", it.toAbsolutePath().normalize().toString()),
                workingDirectory = directory.toString()
            )
        }
    }

    private fun fromClientConfig(name: String, directory: Path, path: Path): McpServerConfig? {
        val root = McpJson.parse(Files.readString(path, StandardCharsets.UTF_8)).asObject() ?: return null
        val servers = root["mcpServers"]?.asObject() ?: return null
        val server = servers[name]?.asObject()
            ?: servers.values.firstOrNull()?.asObject()
            ?: return null
        val executable = server["command"]?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val args = server["args"]?.asArray().orEmpty().mapNotNull { it.asString() }
        val cwd = server["cwd"]?.asString()
            ?.takeIf { it.isNotBlank() }
            ?.let { resolvePath(directory, it) }
            ?: directory
        val env = server["env"]?.asObject().orEmpty()
            .mapNotNull { (key, value) -> value.asString()?.let { key to it } }
            .toMap()
        return McpServerConfig(
            name = name,
            command = listOf(executable) + args,
            workingDirectory = cwd.toString(),
            environment = env
        )
    }

    private fun resolvePath(base: Path, value: String): Path {
        val path = Paths.get(value)
        return if (path.isAbsolute) path.normalize() else base.resolve(path).normalize()
    }
}
