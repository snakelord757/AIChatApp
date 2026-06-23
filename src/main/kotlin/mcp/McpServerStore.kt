package mcp

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class McpServerStore(private val path: Path) {
    fun load(): List<McpServerConfig> {
        if (!path.exists()) return emptyList()
        return try {
            val root = McpJson.parse(Files.readString(path, StandardCharsets.UTF_8))
            root.asObject()?.get("servers")?.asArray().orEmpty().mapNotNull { item ->
                val objectValue = item.asObject() ?: return@mapNotNull null
                val name = objectValue["name"]?.asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val command = objectValue["command"]?.asArray()
                    ?.mapNotNull { it.asString() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                if (command.isEmpty()) null else McpServerConfig(name, command)
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    fun save(configs: List<McpServerConfig>) {
        path.parent?.let { Files.createDirectories(it) }
        val servers = configs.sortedBy { it.name.lowercase() }.map { config ->
            JsonValue.ObjectValue(
                linkedMapOf(
                    "name" to JsonValue.StringValue(config.name),
                    "command" to JsonValue.ArrayValue(config.command.map { JsonValue.StringValue(it) })
                )
            )
        }
        val body = McpJson.stringify(JsonValue.ObjectValue(linkedMapOf("servers" to JsonValue.ArrayValue(servers))))
        Files.writeString(path, pretty(body), StandardCharsets.UTF_8)
    }

    private fun pretty(compact: String): String {
        val root = McpJson.parse(compact).asObject() ?: return compact
        val servers = root["servers"]?.asArray().orEmpty()
        return buildString {
            appendLine("{")
            appendLine("  \"servers\": [")
            servers.forEachIndexed { index, item ->
                val objectValue = item.asObject().orEmpty()
                val name = objectValue["name"]?.asString().orEmpty()
                val command = objectValue["command"]?.asArray().orEmpty().mapNotNull { it.asString() }
                append("    {\"name\":\"")
                append(McpJson.escape(name))
                append("\",\"command\":[")
                append(command.joinToString(",") { "\"${McpJson.escape(it)}\"" })
                append("]}")
                if (index != servers.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
    }
}
