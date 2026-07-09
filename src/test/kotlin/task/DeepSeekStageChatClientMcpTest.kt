package task

import agent.AgentSettings
import agent.JsonTools
import chat.ChatMessage
import chat.Role
import mcp.McpTool
import mcp.McpToolCallResult
import mcp.McpToolGateway
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DeepSeekStageChatClientMcpTest {
    @Test
    fun `stage client emits mcp events when model requests tool call`() {
        val httpClient = RecordingHttpClient(
            listOf(
                assistantResponse("""{"mcpToolCall":{"server":"amiibo","tool":"search_amiibo","arguments":{"name":"Mario"}}}"""),
                assistantResponse("""{"success":true,"summary":"done","output":"Mario result","issues":[],"requestedChanges":[],"retryReason":null}""")
            )
        )
        val gateway = RecordingMcpGateway()
        val client = DeepSeekStageChatClient(
            settings = AgentSettings(apiKey = "key", summaryInterval = 0, systemPrompt = "system"),
            mcpToolGateway = gateway,
            httpClient = httpClient
        )

        val response = client.send(listOf(ChatMessage(Role.USER, "Find Mario amiibo")))

        assertContains(response.content, "Mario result")
        assertEquals("amiibo", gateway.calls.single().first)
        assertEquals("search_amiibo", gateway.calls.single().second)
        assertContains(gateway.calls.single().third, "\"name\":\"Mario\"")
        assertTrueEvent(response.events, "Calling MCP tool amiibo/search_amiibo")
        assertTrueEvent(response.events, "MCP tool amiibo/search_amiibo completed")
        assertContains(httpClient.requestBodies.first(), "\"response_format\"")
        assertContains(httpClient.requestBodies.first(), "\"type\": \"json_schema\"")
        assertContains(httpClient.requestBodies.first(), "MCP tools are available")
        assertContains(httpClient.requestBodies[1], "MCP tool result for amiibo/search_amiibo")
    }

    @Test
    fun `stage client can omit structured response format for non stage helper calls`() {
        val httpClient = RecordingHttpClient(
            listOf(assistantResponse("""{"task":"Summarize","interval":{"time":1,"timeUnit":"HOURS"}}"""))
        )
        val client = DeepSeekStageChatClient(
            settings = AgentSettings(apiKey = "key", summaryInterval = 0, systemPrompt = "system"),
            structuredStageResponse = false,
            httpClient = httpClient
        )

        client.send(listOf(ChatMessage(Role.USER, "Parse schedule")))

        kotlin.test.assertFalse(httpClient.requestBodies.single().contains("\"response_format\""))
    }

    @Test
    fun `stage client sends model context window to provider options`() {
        val httpClient = RecordingHttpClient(
            listOf(assistantResponse("""{"success":true,"summary":"done","output":"Done","issues":[],"requestedChanges":[],"retryReason":null}"""))
        )
        val client = DeepSeekStageChatClient(
            settings = AgentSettings(
                apiKey = "key",
                modelContextWindowTokens = 32_768,
                systemPrompt = "system"
            ),
            httpClient = httpClient
        )

        client.send(listOf(ChatMessage(Role.USER, "Run stage")))

        assertContains(httpClient.requestBodies.single(), "\"options\": {\"num_ctx\": 32768}")
    }

    @Test
    fun `stage client executes multiple requested mcp tool calls`() {
        val httpClient = RecordingHttpClient(
            listOf(
                assistantResponse(
                    """
                    {"mcpToolCalls":[
                      {"server":"amiibo","tool":"search_amiibo","arguments":{"name":"Mario"}},
                      {"server":"files","tool":"read_file","arguments":{"path":"README.md"}}
                    ]}
                    """.trimIndent()
                ),
                assistantResponse("""{"success":true,"summary":"done","output":"Combined result","issues":[],"requestedChanges":[],"retryReason":null}""")
            )
        )
        val gateway = RecordingMcpGateway()
        val client = DeepSeekStageChatClient(
            settings = AgentSettings(apiKey = "key", summaryInterval = 0, systemPrompt = "system"),
            mcpToolGateway = gateway,
            httpClient = httpClient
        )

        val response = client.send(listOf(ChatMessage(Role.USER, "Use several MCP sources")))

        assertContains(response.content, "Combined result")
        assertEquals(
            listOf("amiibo/search_amiibo", "files/read_file"),
            gateway.calls.map { "${it.first}/${it.second}" }
        )
        assertTrueEvent(response.events, "Calling MCP tool amiibo/search_amiibo")
        assertTrueEvent(response.events, "Calling MCP tool files/read_file")
        assertContains(httpClient.requestBodies[1], "MCP tool result for amiibo/search_amiibo")
        assertContains(httpClient.requestBodies[1], "MCP tool result for files/read_file")
    }

    private fun assertTrueEvent(events: List<String>, text: String) {
        kotlin.test.assertTrue(events.any { it.contains(text) }, "Expected event containing: $text")
    }

    private fun assistantResponse(content: String): String =
        """{"choices":[{"finish_reason":"stop","message":{"content":"${JsonTools.escape(content)}"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""

    private class RecordingMcpGateway : McpToolGateway {
        val calls = mutableListOf<Triple<String, String, String>>()

        override fun availableTools(): List<McpTool> =
            listOf(
                McpTool("amiibo", "search_amiibo", "Search amiibo", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"),
                McpTool("files", "read_file", "Read file", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")
            )

        override fun callTool(serverName: String, toolName: String, argumentsJson: String): McpToolCallResult {
            calls += Triple(serverName, toolName, argumentsJson)
            return McpToolCallResult(serverName, toolName, "$serverName/$toolName tool-output")
        }
    }

    private class RecordingHttpClient(
        private val responses: List<String>
    ) : HttpClient() {
        val requestBodies = mutableListOf<String>()
        private var index = 0

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>
        ): HttpResponse<T> {
            val subscriber = BodySubscriber()
            request.bodyPublisher().orElseThrow().subscribe(subscriber)
            requestBodies += subscriber.body()
            val response = responses.getOrElse(index) {
                """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
            }
            index++
            @Suppress("UNCHECKED_CAST")
            return StringHttpResponse(request, response) as HttpResponse<T>
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

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
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

        fun body(): String = bytes.toByteArray().decodeToString()
    }

    private class StringHttpResponse(
        private val request: HttpRequest,
        private val body: String
    ) : HttpResponse<String> {
        override fun statusCode(): Int = 200
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body(): String = body
        override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
