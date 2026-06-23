package mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessMcpClientHttpTest {
    @Test
    fun `streamable http server initializes stores session and lists tools`() {
        val seenSessionHeaders = mutableListOf<String?>()
        val seenProtocolHeaders = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val request = McpJson.parse(requestBody).asObject().orEmpty()
            val method = request["method"]?.asString()
            val id = request["id"]
            seenSessionHeaders += exchange.requestHeaders.getFirst("Mcp-Session-Id")
            seenProtocolHeaders += exchange.requestHeaders.getFirst("MCP-Protocol-Version")
            when (method) {
                "initialize" -> exchange.respondJson(
                    status = 200,
                    body = response(id, """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"fake","version":"1.0.0"}}"""),
                    headers = mapOf("Mcp-Session-Id" to "session-123")
                )
                "notifications/initialized" -> exchange.sendResponseHeaders(202, -1)
                "tools/list" -> exchange.respondJson(
                    status = 200,
                    body = response(id, """{"tools":[{"name":"ping","description":"Ping","inputSchema":{"type":"object"}}]}""")
                )
                else -> exchange.respondJson(
                    status = 200,
                    body = response(id, """{}""")
                )
            }
        }
        server.start()
        val client = ProcessMcpClient()

        try {
            val config = McpServerConfig(
                name = "remote",
                command = emptyList(),
                transport = McpTransport.HTTP,
                url = "http://127.0.0.1:${server.address.port}/mcp"
            )

            val status = client.connect(config)
            val tools = client.listTools("remote")

            assertEquals(McpConnectionState.CONNECTED, status.state)
            assertEquals(listOf(McpTool("remote", "ping", "Ping", "{\"type\":\"object\"}")), tools)
            assertEquals(listOf(null, "session-123", "session-123"), seenSessionHeaders)
            assertEquals(listOf<String?>("2025-06-18", "2025-06-18", "2025-06-18"), seenProtocolHeaders)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun `streamable http initialized notification can close connection without failing connect`() {
        val httpClient = NotificationClosingHttpClient()
        val client = ProcessMcpClient(httpClient = httpClient)
        val config = McpServerConfig(
            name = "remote",
            command = emptyList(),
            transport = McpTransport.HTTP,
            url = "http://127.0.0.1:8080/mcp"
        )

        val status = client.connect(config)
        val tools = client.listTools("remote")

        assertEquals(McpConnectionState.CONNECTED, status.state)
        assertEquals(listOf(McpTool("remote", "ping", "Ping", "{\"type\":\"object\"}")), tools)
    }

    private fun HttpExchange.respondJson(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private class NotificationClosingHttpClient : HttpClient() {
        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>
        ): HttpResponse<T> {
            val body = request.bodyPublisher().orElseThrow().let { publisher ->
                val subscriber = BodySubscriber()
                publisher.subscribe(subscriber)
                subscriber.body()
            }
            val method = McpJson.parse(body).asObject().orEmpty()["method"]?.asString()
            val id = McpJson.parse(body).asObject().orEmpty()["id"]
            if (method == "notifications/initialized") {
                throw IOException("HTTP/1.1 header parser received no bytes")
            }
            val responseBody = when (method) {
                "initialize" -> response(id, """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"fake","version":"1.0.0"}}""")
                "tools/list" -> response(id, """{"tools":[{"name":"ping","description":"Ping","inputSchema":{"type":"object"}}]}""")
                else -> response(id, "{}")
            }
            @Suppress("UNCHECKED_CAST")
            return StringHttpResponse(request, responseBody, mapOf("Mcp-Session-Id" to listOf("session-123"))) as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

        override fun cookieHandler(): Optional<java.net.CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<java.net.ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<java.net.Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()
    }

    private class BodySubscriber : Flow.Subscriber<ByteBuffer> {
        private val bytes = mutableListOf<Byte>()

        override fun onSubscribe(subscription: Flow.Subscription) {
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: ByteBuffer) {
            while (item.hasRemaining()) bytes += item.get()
        }

        override fun onError(throwable: Throwable) = throw throwable
        override fun onComplete() = Unit
        fun body(): String = bytes.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private class StringHttpResponse(
        private val request: HttpRequest,
        private val body: String,
        private val headers: Map<String, List<String>> = emptyMap()
    ) : HttpResponse<String> {
        override fun statusCode(): Int = 200
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(headers) { _, _ -> true }
        override fun body(): String = body
        override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    private companion object {
        fun response(id: JsonValue?, resultJson: String): String =
            """{"jsonrpc":"2.0","id":${id?.let { McpJson.stringify(it) } ?: "null"},"result":$resultJson}"""
    }
}
