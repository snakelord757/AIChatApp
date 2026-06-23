package mcp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

class ProcessMcpClient(
    private val requestTimeoutMillis: Long = 60_000
) : McpClient {
    private val configs = linkedMapOf<String, McpServerConfig>()
    private val statuses = linkedMapOf<String, McpServerStatus>()
    private val sessions = linkedMapOf<String, Session>()
    private val requestIds = AtomicLong(1)

    override fun configure(configs: List<McpServerConfig>) {
        configs.forEach { config ->
            this.configs[config.name] = config
            statuses.putIfAbsent(config.name, McpServerStatus(config.name, McpConnectionState.CONFIGURED))
        }
    }

    override fun connect(config: McpServerConfig): McpServerStatus {
        close(config.name)
        configs[config.name] = config
        return try {
            val session = Session(config, ProcessBuilder(config.command).start())
            sessions[config.name] = session
            initialize(session)
            val status = McpServerStatus(config.name, McpConnectionState.CONNECTED)
            statuses[config.name] = status
            status
        } catch (exception: RuntimeException) {
            close(config.name)
            val status = McpServerStatus(
                config.name,
                McpConnectionState.FAILED,
                exception.message ?: exception::class.simpleName
            )
            statuses[config.name] = status
            status
        } catch (exception: java.io.IOException) {
            close(config.name)
            val status = McpServerStatus(
                config.name,
                McpConnectionState.FAILED,
                exception.message ?: exception::class.simpleName
            )
            statuses[config.name] = status
            status
        }
    }

    override fun disconnect(serverName: String) {
        close(serverName)
        configs.remove(serverName)
        statuses.remove(serverName)
    }

    override fun clear() {
        sessions.keys.toList().forEach(::close)
        configs.clear()
        statuses.clear()
    }

    override fun listServers(): List<McpServerStatus> =
        configs.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).map { name ->
            statuses[name] ?: McpServerStatus(name, McpConnectionState.CONFIGURED)
        }

    override fun listTools(serverName: String): List<McpTool> {
        val session = sessions[serverName] ?: run {
            val config = configs[serverName] ?: error("Unknown MCP server: $serverName")
            val status = connect(config)
            if (status.state != McpConnectionState.CONNECTED) error(status.message ?: "Could not connect MCP server: $serverName")
            sessions[serverName] ?: error("Could not connect MCP server: $serverName")
        }
        val response = session.request("tools/list", emptyMap())
        val result = response.asObject()?.get("result")?.asObject() ?: error("tools/list response did not include result")
        return result["tools"]?.asArray().orEmpty().mapNotNull { item ->
            val tool = item.asObject() ?: return@mapNotNull null
            val name = tool["name"]?.asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpTool(
                serverName = serverName,
                name = name,
                description = tool["description"]?.asString(),
                inputSchema = tool["inputSchema"]?.let(McpJson::stringify)
            )
        }
    }

    override fun close() {
        sessions.keys.toList().forEach(::close)
    }

    private fun initialize(session: Session) {
        session.request(
            "initialize",
            linkedMapOf(
                "protocolVersion" to JsonValue.StringValue("2024-11-05"),
                "capabilities" to JsonValue.ObjectValue(emptyMap()),
                "clientInfo" to JsonValue.ObjectValue(
                    linkedMapOf(
                        "name" to JsonValue.StringValue("AIChatApp"),
                        "version" to JsonValue.StringValue("1.0.0")
                    )
                )
            )
        )
        session.notify("notifications/initialized", emptyMap())
    }

    private fun close(name: String) {
        sessions.remove(name)?.close()
    }

    private inner class Session(
        private val config: McpServerConfig,
        private val process: Process
    ) : AutoCloseable {
        private val input = BufferedInputStream(process.inputStream)
        private val output = BufferedOutputStream(process.outputStream)
        private val stderrDrainer = Thread {
            process.errorStream.drain()
        }.also {
            it.name = "mcp-${config.name}-stderr"
            it.isDaemon = true
            it.start()
        }

        fun request(method: String, params: Map<String, JsonValue>): JsonValue {
            val id = requestIds.getAndIncrement()
            send(
                JsonValue.ObjectValue(
                    linkedMapOf(
                        "jsonrpc" to JsonValue.StringValue("2.0"),
                        "id" to JsonValue.Number(id.toString()),
                        "method" to JsonValue.StringValue(method),
                        "params" to JsonValue.ObjectValue(params)
                    )
                )
            )
            val deadline = System.currentTimeMillis() + requestTimeoutMillis
            while (System.currentTimeMillis() <= deadline) {
                val message = readMessage(deadline)
                val objectValue = message.asObject() ?: continue
                val messageId = (objectValue["id"] as? JsonValue.Number)?.raw?.toLongOrNull()
                if (messageId != id) continue
                objectValue["error"]?.let { errorValue -> error("MCP ${config.name} error: ${McpJson.stringify(errorValue)}") }
                return message
            }
            error("Timed out waiting for MCP server ${config.name}")
        }

        fun notify(method: String, params: Map<String, JsonValue>) {
            send(
                JsonValue.ObjectValue(
                    linkedMapOf(
                        "jsonrpc" to JsonValue.StringValue("2.0"),
                        "method" to JsonValue.StringValue(method),
                        "params" to JsonValue.ObjectValue(params)
                    )
                )
            )
        }

        private fun send(value: JsonValue) {
            val body = McpJson.stringify(value) + "\n"
            output.write(body.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }

        private fun readMessage(deadline: Long): JsonValue {
            return McpJson.parse(readUtf8Line(deadline))
        }

        private fun readUtf8Line(deadline: Long): String {
            val bytes = mutableListOf<Byte>()
            while (true) {
                val next = readByte(deadline)
                if (next < 0) error("MCP server ${config.name} closed the stream")
                if (next == '\n'.code) break
                if (next != '\r'.code) bytes += next.toByte()
            }
            return bytes.toByteArray().toString(StandardCharsets.UTF_8)
        }

        private fun readByte(deadline: Long): Int {
            while (input.available() <= 0) {
                if (!process.isAlive) error("MCP server ${config.name} exited")
                if (System.currentTimeMillis() > deadline) error("Timed out waiting for MCP server ${config.name}")
                Thread.sleep(10)
            }
            return input.read()
        }

        override fun close() {
            process.destroy()
        }

        private fun InputStream.drain() {
            val buffer = ByteArray(1024)
            while (read(buffer) >= 0) {
                // Discard stderr so a verbose MCP server cannot block on a full error pipe.
            }
        }
    }
}
