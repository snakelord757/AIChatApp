package mcp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class ProcessMcpClient(
    private val requestTimeoutMillis: Long = 60_000,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()
) : McpClient {
    private val configs = linkedMapOf<String, McpServerConfig>()
    private val statuses = linkedMapOf<String, McpServerStatus>()
    private val sessions = linkedMapOf<String, McpSession>()
    private val requestIds = AtomicLong(1)

    override fun configure(configs: List<McpServerConfig>) {
        val currentNames = configs.map { it.name }.toSet()
        this.configs.keys.filter { it !in currentNames }.toList().forEach { staleName ->
            close(staleName)
            this.configs.remove(staleName)
            statuses.remove(staleName)
        }
        configs.forEach { config ->
            this.configs[config.name] = config
            statuses.putIfAbsent(config.name, McpServerStatus(config.name, McpConnectionState.CONFIGURED))
        }
    }

    override fun connect(config: McpServerConfig): McpServerStatus {
        close(config.name)
        configs[config.name] = config
        return try {
            val session = when (config.transport) {
                McpTransport.STDIO -> {
                    val builder = ProcessBuilder(McpProcessCommand.resolve(config.command))
                    config.workingDirectory?.takeIf { it.isNotBlank() }?.let {
                        builder.directory(Paths.get(it).toFile())
                    }
                    builder.environment().putAll(config.environment)
                    StdioSession(config, builder.start())
                }
                McpTransport.HTTP -> HttpSession(config)
            }
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

    override fun callTool(serverName: String, toolName: String, argumentsJson: String): McpToolCallResult {
        val session = sessions[serverName] ?: run {
            val config = configs[serverName] ?: error("Unknown MCP server: $serverName")
            val status = connect(config)
            if (status.state != McpConnectionState.CONNECTED) error(status.message ?: "Could not connect MCP server: $serverName")
            sessions[serverName] ?: error("Could not connect MCP server: $serverName")
        }
        val arguments = argumentsJson.trim().takeIf { it.isNotBlank() }?.let { McpJson.parse(it) }
            ?: JsonValue.ObjectValue(emptyMap())
        if (arguments !is JsonValue.ObjectValue) {
            error("MCP tool arguments must be a JSON object.")
        }
        val response = session.request(
            "tools/call",
            linkedMapOf(
                "name" to JsonValue.StringValue(toolName),
                "arguments" to arguments
            )
        )
        val result = response.asObject()?.get("result")?.asObject() ?: error("tools/call response did not include result")
        return McpToolCallResult(
            serverName = serverName,
            toolName = toolName,
            content = result.contentText(),
            isError = (result["isError"] as? JsonValue.Bool)?.value == true
        )
    }

    private fun Map<String, JsonValue>.contentText(): String {
        val content = this["content"]?.asArray().orEmpty()
        val text = content.mapNotNull { item ->
            val objectValue = item.asObject() ?: return@mapNotNull null
            objectValue["text"]?.asString()
                ?: objectValue["data"]?.let(McpJson::stringify)
        }
        return text.joinToString("\n").ifBlank { McpJson.stringify(JsonValue.ObjectValue(this)) }
    }

    override fun close() {
        sessions.keys.toList().forEach(::close)
    }

    private fun initialize(session: McpSession) {
        session.request(
            "initialize",
            linkedMapOf(
                "protocolVersion" to JsonValue.StringValue("2025-06-18"),
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

    private interface McpSession : AutoCloseable {
        fun request(method: String, params: Map<String, JsonValue>): JsonValue
        fun notify(method: String, params: Map<String, JsonValue>)
    }

    private inner class StdioSession(
        private val config: McpServerConfig,
        private val process: Process
    ) : McpSession {
        private val input = BufferedInputStream(process.inputStream)
        private val output = BufferedOutputStream(process.outputStream)
        private val stderrDrainer = Thread {
            process.errorStream.drain()
        }.also {
            it.name = "mcp-${config.name}-stderr"
            it.isDaemon = true
            it.start()
        }

        override fun request(method: String, params: Map<String, JsonValue>): JsonValue {
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

        override fun notify(method: String, params: Map<String, JsonValue>) {
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

    private inner class HttpSession(
        private val config: McpServerConfig
    ) : McpSession {
        private val endpoint = URI.create(config.url ?: error("HTTP MCP server ${config.name} is missing url"))
        private var sessionId: String? = null

        override fun request(method: String, params: Map<String, JsonValue>): JsonValue {
            val id = requestIds.getAndIncrement()
            val message = JsonValue.ObjectValue(
                linkedMapOf(
                    "jsonrpc" to JsonValue.StringValue("2.0"),
                    "id" to JsonValue.Number(id.toString()),
                    "method" to JsonValue.StringValue(method),
                    "params" to JsonValue.ObjectValue(params)
                )
            )
            val response = sendPost(message)
            response.headers().firstValue("Mcp-Session-Id").orElse(null)?.takeIf { it.isNotBlank() }?.let {
                sessionId = it
            }
            val body = response.body().trim()
            if (body.isBlank()) error("MCP ${config.name} returned an empty response for $method")
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            val messages = if (contentType.contains("text/event-stream", ignoreCase = true)) {
                parseSseMessages(body)
            } else {
                listOf(McpJson.parse(body))
            }
            return messages.firstOrNull { messageValue ->
                val objectValue = messageValue.asObject() ?: return@firstOrNull false
                val messageId = (objectValue["id"] as? JsonValue.Number)?.raw?.toLongOrNull()
                    ?: objectValue["id"]?.asString()?.toLongOrNull()
                messageId == id
            }?.also { messageValue ->
                val objectValue = messageValue.asObject().orEmpty()
                objectValue["error"]?.let { errorValue -> error("MCP ${config.name} error: ${McpJson.stringify(errorValue)}") }
            } ?: error("MCP ${config.name} response did not include id $id")
        }

        override fun notify(method: String, params: Map<String, JsonValue>) {
            try {
                sendPost(
                    JsonValue.ObjectValue(
                        linkedMapOf(
                            "jsonrpc" to JsonValue.StringValue("2.0"),
                            "method" to JsonValue.StringValue(method),
                            "params" to JsonValue.ObjectValue(params)
                        )
                    )
                )
            } catch (exception: IOException) {
                if (!exception.isHttpHeaderParserNoBytes()) throw exception
            }
        }

        override fun close() {
            val currentSessionId = sessionId ?: return
            val request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(requestTimeoutMillis))
                .header("Mcp-Session-Id", currentSessionId)
                .header("MCP-Protocol-Version", "2025-06-18")
                .DELETE()
                .build()
            runCatching { httpClient.send(request, HttpResponse.BodyHandlers.discarding()) }
        }

        private fun sendPost(message: JsonValue): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(requestTimeoutMillis))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2025-06-18")
                .POST(HttpRequest.BodyPublishers.ofString(McpJson.stringify(message), StandardCharsets.UTF_8))
            sessionId?.let { builder.header("Mcp-Session-Id", it) }
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() !in 200..299) {
                error("MCP ${config.name} HTTP ${response.statusCode()}: ${response.body().take(500)}")
            }
            return response
        }

        private fun parseSseMessages(body: String): List<JsonValue> {
            val messages = mutableListOf<JsonValue>()
            val data = StringBuilder()
            fun flushData() {
                if (data.isBlank()) return
                messages += McpJson.parse(data.toString().trim())
                data.clear()
            }
            body.lineSequence().forEach { rawLine ->
                val line = rawLine.trimEnd('\r')
                when {
                    line.isBlank() -> flushData()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                }
            }
            flushData()
            return messages
        }

        private fun IOException.isHttpHeaderParserNoBytes(): Boolean =
            sequenceOf(message, cause?.message)
                .filterNotNull()
                .any { it.contains("header parser received no bytes", ignoreCase = true) }
    }
}
