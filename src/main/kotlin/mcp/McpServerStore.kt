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
                val workingDirectory = objectValue["workingDirectory"]?.asString()?.takeIf { it.isNotBlank() }
                val environment = objectValue["environment"]?.asObject().orEmpty()
                    .mapNotNull { (key, value) -> value.asString()?.let { key to it } }
                    .toMap()
                val transport = objectValue["transport"]?.asString()
                    ?.let { runCatching { McpTransport.valueOf(it.uppercase()) }.getOrNull() }
                    ?: if (objectValue["url"]?.asString()?.isNotBlank() == true) McpTransport.HTTP else McpTransport.STDIO
                val url = objectValue["url"]?.asString()?.takeIf { it.isNotBlank() }
                when (transport) {
                    McpTransport.STDIO -> if (command.isEmpty()) null else McpServerConfig(name, command, workingDirectory, environment)
                    McpTransport.HTTP -> url?.let {
                        McpServerConfig(
                            name = name,
                            command = emptyList(),
                            transport = McpTransport.HTTP,
                            url = it
                        )
                    }
                }
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    fun save(configs: List<McpServerConfig>) {
        path.parent?.let { Files.createDirectories(it) }
        val servers = configs.sortedBy { it.name.lowercase() }.map { config ->
            JsonValue.ObjectValue(
                linkedMapOf<String, JsonValue>(
                    "name" to JsonValue.StringValue(config.name),
                    "transport" to JsonValue.StringValue(config.transport.name.lowercase())
                ).also { values ->
                    when (config.transport) {
                        McpTransport.STDIO -> {
                            values["command"] = JsonValue.ArrayValue(config.command.map { JsonValue.StringValue(it) })
                            config.workingDirectory?.takeIf { it.isNotBlank() }?.let {
                                values["workingDirectory"] = JsonValue.StringValue(it)
                            }
                            if (config.environment.isNotEmpty()) {
                                values["environment"] = JsonValue.ObjectValue(
                                    config.environment.toSortedMap().mapValues { JsonValue.StringValue(it.value) }
                                )
                            }
                        }
                        McpTransport.HTTP -> {
                            config.url?.takeIf { it.isNotBlank() }?.let {
                                values["url"] = JsonValue.StringValue(it)
                            }
                        }
                    }
                }
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
                val workingDirectory = objectValue["workingDirectory"]?.asString()
                val environment = objectValue["environment"]?.asObject().orEmpty()
                    .mapNotNull { (key, value) -> value.asString()?.let { key to it } }
                    .toMap()
                val transport = objectValue["transport"]?.asString() ?: "stdio"
                val url = objectValue["url"]?.asString()
                append("    {\"name\":\"")
                append(McpJson.escape(name))
                append("\",\"transport\":\"")
                append(McpJson.escape(transport))
                append("\"")
                if (transport.equals("http", ignoreCase = true)) {
                    append(",\"url\":\"")
                    append(McpJson.escape(url.orEmpty()))
                    append("\"")
                } else {
                    append(",\"command\":[")
                    append(command.joinToString(",") { "\"${McpJson.escape(it)}\"" })
                    append("]")
                    workingDirectory?.let {
                        append(",\"workingDirectory\":\"")
                        append(McpJson.escape(it))
                        append("\"")
                    }
                    if (environment.isNotEmpty()) {
                        append(",\"environment\":{")
                        append(environment.toSortedMap().entries.joinToString(",") { (key, value) ->
                            "\"${McpJson.escape(key)}\":\"${McpJson.escape(value)}\""
                        })
                        append("}")
                    }
                }
                append("}")
                if (index != servers.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
    }
}
