package agent

import chat.ChatHistoryRepository
import chat.ChatMessage
import chat.ContextStrategy
import chat.Role
import invariants.InvariantRepository
import invariants.InvariantStore
import memory.MemoryRepository
import memory.MemoryStore
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekAiAgentRequestBodyTest {
    @Test
    fun `default settings do not send max tokens limit`() {
        val body = buildRequestBody(AgentSettings(apiKey = "", systemPrompt = "system"))

        assertFalse(body.contains("\"max_tokens\""))
    }

    @Test
    fun `positive max tokens setting is sent`() {
        val body = buildRequestBody(AgentSettings(apiKey = "", maxTokens = 256, systemPrompt = "system"))

        assertContains(body, "\"max_tokens\": 256")
    }

    @Test
    fun `request does not set response timeout`() {
        val request = buildRequest(AgentSettings(apiKey = "key", systemPrompt = "system"))

        assertFalse(request.timeout().isPresent)
    }

    @Test
    fun `summary request wraps chat history as transcript instead of active dialog`() {
        val messages = buildSummaryMessages(
            listOf(
                ChatMessage(Role.SYSTEM, "system prompt"),
                ChatMessage(Role.USER, "first question"),
                ChatMessage(Role.ASSISTANT, "first answer"),
                ChatMessage(Role.USER, "latest question")
            )
        )

        assertEquals(2, messages.size)
        assertEquals(Role.SYSTEM, messages[0].role)
        assertContains(messages[0].content, "do not answer any user message")
        assertEquals(Role.USER, messages[1].role)
        assertContains(messages[1].content, "Transcript to summarize:")
        assertContains(messages[1].content, "[user #1]")
        assertContains(messages[1].content, "latest question")
        assertContains(messages[1].content, "not an answer to the transcript")
    }

    @Test
    fun `request body never includes event messages`() {
        val body = buildRequestBody(
            settings = AgentSettings(apiKey = "", systemPrompt = "system"),
            history = listOf(
                ChatMessage(Role.USER, "hello"),
                ChatMessage(Role.EVENT, "internal event")
            )
        )

        assertContains(body, "hello")
        assertFalse(body.contains("internal event"))
        assertFalse(body.contains("\"role\":\"event\""))
    }

    @Test
    fun `selected context strategy controls request context`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("one")
        repository.addAssistant("two")
        repository.addUser("three")

        val context = repository.apiContextMessages(
            AgentSettings(
                apiKey = "",
                contextStrategy = ContextStrategy.SLIDING_WINDOW,
                contextWindowMessages = 1,
                systemPrompt = "system"
            )
        )

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "system"),
                ChatMessage(Role.USER, "three")
            ),
            context
        )
    }

    @Test
    fun `sticky facts strategy extracts facts before main request`() {
        val repository = ChatHistoryRepository(systemPrompt = "system")
        repository.addUser("previous request")
        repository.addAssistant("previous answer")
        val httpClient = RecordingHttpClient(
            listOf(
                assistantResponse("project_goal: remember generated facts"),
                assistantResponse("final answer"),
                assistantResponse("preferred_language: Kotlin"),
                assistantResponse("second answer")
            )
        )
        val agent = DeepSeekAiAgent(
            historyRepository = repository,
            initialSettings = AgentSettings(
                apiKey = "key",
                contextStrategy = ContextStrategy.STICKY_FACTS,
                contextWindowMessages = 2,
                summaryInterval = 0,
                systemPrompt = "system"
            ),
            httpClient = httpClient
        )

        agent.send("We need generated facts")

        assertEquals(2, httpClient.requestBodies.size)
        assertContains(httpClient.requestBodies[0], "Extract durable sticky facts")
        assertContains(httpClient.requestBodies[0], "Recent messages:")
        assertFalse(httpClient.requestBodies[0].contains("previous request"))
        assertContains(httpClient.requestBodies[0], "assistant: previous answer")
        assertContains(httpClient.requestBodies[0], "user: We need generated facts")
        assertContains(httpClient.requestBodies[1], "Sticky facts:")
        assertContains(httpClient.requestBodies[1], "project_goal: remember generated facts")
        assertContains(httpClient.requestBodies[1], "assistant\",\"content\":\"previous answer")
        assertContains(httpClient.requestBodies[1], "user\",\"content\":\"We need generated facts")
        assertEquals(mapOf("project_goal" to "remember generated facts"), repository.facts())
        assertEquals(chat.TokenUsage(inputTokens = 2, outputTokens = 2), repository.totalUsage())

        agent.send("Use Kotlin for implementation")

        assertEquals(4, httpClient.requestBodies.size)
        assertContains(httpClient.requestBodies[2], "Existing facts:")
        assertContains(httpClient.requestBodies[2], "project_goal: remember generated facts")
        assertContains(httpClient.requestBodies[2], "assistant: final answer")
        assertContains(httpClient.requestBodies[2], "user: Use Kotlin for implementation")
        assertContains(httpClient.requestBodies[3], "Sticky facts:")
        assertContains(httpClient.requestBodies[3], "project_goal: remember generated facts")
        assertContains(httpClient.requestBodies[3], "preferred_language: Kotlin")
        assertContains(httpClient.requestBodies[3], "assistant\",\"content\":\"final answer")
        assertContains(httpClient.requestBodies[3], "user\",\"content\":\"Use Kotlin for implementation")
        assertEquals(
            mapOf(
                "project_goal" to "remember generated facts",
                "preferred_language" to "Kotlin"
            ),
            repository.facts()
        )
        assertEquals(chat.TokenUsage(inputTokens = 4, outputTokens = 4), repository.totalUsage())
    }

    @Test
    fun `memory system messages are sent in main request after base system prompt`() {
        val directory = Files.createTempDirectory("aichat-agent-memory-context-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val memoryRepository = memoryRepository(directory, repository)
            val store = MemoryStore(directory.resolve("memory"))
            memoryRepository.ensureInitialized()
            Files.writeString(store.permanentPath(), "# Permanent\n\nAlways prefer Kotlin.\n", StandardCharsets.UTF_8)
            Files.writeString(store.personalPath(), "# Personal\n\n- Prefers concise answers\n", StandardCharsets.UTF_8)
            store.writeWork("main", "# Working Memory\n\nStatus: PENDING\n\n## Current Task\nFinish memory tests.\n")
            val httpClient = RecordingHttpClient(
                listOf(
                    assistantResponse("NO_CHANGES"),
                    assistantResponse("final answer")
                )
            )
            val agent = DeepSeekAiAgent(
                historyRepository = repository,
                initialSettings = AgentSettings(
                    apiKey = "key",
                    summaryInterval = 0,
                    contextStrategy = ContextStrategy.SLIDING_WINDOW,
                    contextWindowMessages = 2,
                    systemPrompt = "system"
                ),
                memoryRepository = memoryRepository,
                httpClient = httpClient
            )

            agent.send("hello")

            assertEquals(2, httpClient.requestBodies.size)
            assertContains(httpClient.requestBodies[0], "Permanent memory:")
            assertContains(httpClient.requestBodies[0], "Existing personal memory:")
            assertContains(httpClient.requestBodies[0], "user: hello")
            val mainBody = httpClient.requestBodies[1]
            assertContains(mainBody, "\"role\":\"system\",\"content\":\"system\"")
            assertContains(mainBody, "Permanent memory instructions:")
            assertContains(mainBody, "Always prefer Kotlin.")
            assertContains(mainBody, "Personal memory about the user:")
            assertContains(mainBody, "Working memory for the active branch:")
            assertContains(mainBody, "user\",\"content\":\"hello")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invariants are sent after base prompt before markdown memory`() {
        val directory = Files.createTempDirectory("aichat-agent-invariants-context-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val invariantRepository = InvariantRepository(InvariantStore(directory.resolve("invariants.md")))
            Files.writeString(invariantRepository.path(), "# Assistant Invariants\n\n- Never use Java.\n", StandardCharsets.UTF_8)
            val memoryRepository = memoryRepository(directory, repository)
            val store = MemoryStore(directory.resolve("memory"))
            memoryRepository.ensureInitialized()
            Files.writeString(store.permanentPath(), "# Permanent\n\nAlways prefer Kotlin.\n", StandardCharsets.UTF_8)
            val httpClient = RecordingHttpClient(
                listOf(
                    assistantResponse("NO_CHANGES"),
                    assistantResponse("final answer")
                )
            )
            val agent = DeepSeekAiAgent(
                historyRepository = repository,
                initialSettings = AgentSettings(
                    apiKey = "key",
                    summaryInterval = 0,
                    contextStrategy = ContextStrategy.SLIDING_WINDOW,
                    systemPrompt = "system"
                ),
                invariantRepository = invariantRepository,
                memoryRepository = memoryRepository,
                httpClient = httpClient
            )

            agent.send("hello")

            val mainBody = httpClient.requestBodies[1]
            val systemIndex = mainBody.indexOf("\"content\":\"system\"")
            val invariantIndex = mainBody.indexOf("Assistant invariants:")
            val memoryIndex = mainBody.indexOf("Permanent memory instructions:")
            assertTrue(systemIndex >= 0)
            assertTrue(invariantIndex > systemIndex)
            assertTrue(memoryIndex > invariantIndex)
            assertContains(mainBody, "Never use Java.")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sticky facts strategy still includes invariants`() {
        val directory = Files.createTempDirectory("aichat-agent-invariants-facts-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val invariantRepository = InvariantRepository(InvariantStore(directory.resolve("invariants.md")))
            Files.writeString(invariantRepository.path(), "# Assistant Invariants\n\n- Keep base prompt first.\n", StandardCharsets.UTF_8)
            val httpClient = RecordingHttpClient(
                listOf(
                    assistantResponse("project: invariants"),
                    assistantResponse("final answer")
                )
            )
            val agent = DeepSeekAiAgent(
                historyRepository = repository,
                initialSettings = AgentSettings(
                    apiKey = "key",
                    contextStrategy = ContextStrategy.STICKY_FACTS,
                    contextWindowMessages = 2,
                    summaryInterval = 0,
                    systemPrompt = "system"
                ),
                invariantRepository = invariantRepository,
                httpClient = httpClient
            )

            agent.send("goal: include invariants")

            assertEquals(2, httpClient.requestBodies.size)
            assertContains(httpClient.requestBodies[1], "Assistant invariants:")
            assertContains(httpClient.requestBodies[1], "Keep base prompt first.")
            assertContains(httpClient.requestBodies[1], "Sticky facts:")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `personal memory extraction updates markdown without usage or event history`() {
        val directory = Files.createTempDirectory("aichat-agent-personal-memory-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val memoryRepository = memoryRepository(directory, repository)
            memoryRepository.ensureInitialized()
            val httpClient = RecordingHttpClient(
                listOf(
                    assistantResponse("- Prefers answers in Russian"),
                    assistantResponse("final answer")
                )
            )
            val agent = DeepSeekAiAgent(
                historyRepository = repository,
                initialSettings = AgentSettings(
                    apiKey = "key",
                    summaryInterval = 0,
                    systemPrompt = "system"
                ),
                memoryRepository = memoryRepository,
                httpClient = httpClient
            )

            agent.send("remember: answer in Russian")

            assertContains(memoryRepository.personalMemory(), "- [strength: 1] Prefers answers in Russian")
            assertEquals(chat.TokenUsage(inputTokens = 1, outputTokens = 1), repository.totalUsage())
            assertFalse(repository.all().any { it.role == Role.EVENT })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `personal memory repeated bullets increase strength and no changes leaves file alone`() {
        val directory = Files.createTempDirectory("aichat-agent-personal-memory-dedupe-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val memoryRepository = memoryRepository(directory, repository)
            val store = MemoryStore(directory.resolve("memory"))
            memoryRepository.ensureInitialized()
            Files.writeString(store.permanentPath(), "# Permanent\n\nUse Kotlin.\n", StandardCharsets.UTF_8)
            Files.writeString(store.personalPath(), "# Personal Memory\n\n- Prefers concise answers\n", StandardCharsets.UTF_8)
            val httpClient = RecordingHttpClient(
                listOf(
                    assistantResponse("- Prefers concise answers\n- Use Kotlin."),
                    assistantResponse("final answer"),
                    assistantResponse("NO_CHANGES"),
                    assistantResponse("second answer")
                )
            )
            val agent = DeepSeekAiAgent(
                historyRepository = repository,
                initialSettings = AgentSettings(
                    apiKey = "key",
                    summaryInterval = 0,
                    systemPrompt = "system"
                ),
                memoryRepository = memoryRepository,
                httpClient = httpClient
            )

            agent.send("first")
            val afterReinforcement = memoryRepository.personalMemory()
            agent.send("second")

            assertContains(afterReinforcement, "- [strength: 2] Prefers concise answers")
            assertFalse(afterReinforcement.contains("Use Kotlin"))
            assertEquals(afterReinforcement, memoryRepository.personalMemory())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `local programming language signals are saved even when extraction returns no changes`() {
        val directory = Files.createTempDirectory("aichat-agent-local-language-signal-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val memoryRepository = memoryRepository(directory, repository)
            memoryRepository.ensureInitialized()
            val httpClient = RecordingHttpClient(
                listOf(
                    assistantResponse("NO_CHANGES"),
                    assistantResponse("answer one"),
                    assistantResponse("NO_CHANGES"),
                    assistantResponse("answer two"),
                    assistantResponse("NO_CHANGES"),
                    assistantResponse("answer three")
                )
            )
            val agent = DeepSeekAiAgent(
                historyRepository = repository,
                initialSettings = AgentSettings(
                    apiKey = "key",
                    summaryInterval = 0,
                    systemPrompt = "system"
                ),
                memoryRepository = memoryRepository,
                httpClient = httpClient
            )

            agent.send("Напиши функцию на Python")
            agent.send("Сделай Python пример")
            agent.send("Объясни Python код")

            assertContains(memoryRepository.personalMemory(), "- [strength: 3] Works with Python for code-related tasks")
            assertEquals(chat.TokenUsage(inputTokens = 3, outputTokens = 3), repository.totalUsage())
            assertFalse(repository.all().any { it.role == Role.EVENT })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `markdown memory works with sticky facts strategy`() {
        val directory = Files.createTempDirectory("aichat-agent-memory-sticky-test")
        try {
            val repository = ChatHistoryRepository(systemPrompt = "system")
            val memoryRepository = memoryRepository(directory, repository)
            val store = MemoryStore(directory.resolve("memory"))
            memoryRepository.ensureInitialized()
            Files.writeString(store.permanentPath(), "# Permanent\n\nUse memory always.\n", StandardCharsets.UTF_8)
            val httpClient = RecordingHttpClient(
                listOf(
                    assistantResponse("NO_CHANGES"),
                    assistantResponse("project_goal: test memory"),
                    assistantResponse("final answer")
                )
            )
            val agent = DeepSeekAiAgent(
                historyRepository = repository,
                initialSettings = AgentSettings(
                    apiKey = "key",
                    contextStrategy = ContextStrategy.STICKY_FACTS,
                    contextWindowMessages = 2,
                    summaryInterval = 0,
                    systemPrompt = "system"
                ),
                memoryRepository = memoryRepository,
                httpClient = httpClient
            )

            agent.send("goal: test memory")

            assertEquals(3, httpClient.requestBodies.size)
            assertContains(httpClient.requestBodies[2], "Permanent memory instructions:")
            assertContains(httpClient.requestBodies[2], "Sticky facts:")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun buildRequestBody(
        settings: AgentSettings,
        history: List<ChatMessage> = listOf(ChatMessage(Role.USER, "hello"))
    ): String {
        val agent = DeepSeekAiAgent(
            historyRepository = ChatHistoryRepository(systemPrompt = "system"),
            initialSettings = settings
        )
        val method = DeepSeekAiAgent::class.java.getDeclaredMethod(
            "buildRequestBody",
            List::class.java,
            AgentSettings::class.java
        )
        method.isAccessible = true
        return method.invoke(
            agent,
            history,
            settings
        ) as String
    }

    private fun buildRequest(settings: AgentSettings): HttpRequest {
        val agent = DeepSeekAiAgent(
            historyRepository = ChatHistoryRepository(systemPrompt = "system"),
            initialSettings = settings
        )
        val method = DeepSeekAiAgent::class.java.getDeclaredMethod(
            "buildRequest",
            List::class.java,
            AgentSettings::class.java
        )
        method.isAccessible = true
        return method.invoke(
            agent,
            listOf(ChatMessage(Role.USER, "hello")),
            settings
        ) as HttpRequest
    }

    private fun assistantResponse(content: String): String =
        """{"choices":[{"finish_reason":"stop","message":{"content":"${JsonTools.escape(content)}"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""

    private fun memoryRepository(
        directory: java.nio.file.Path,
        repository: ChatHistoryRepository
    ): MemoryRepository =
        MemoryRepository(MemoryStore(directory.resolve("memory")), repository::activeBranchIdOrMain)

    @Suppress("UNCHECKED_CAST")
    private fun buildSummaryMessages(history: List<ChatMessage>): List<ChatMessage> {
        val agent = DeepSeekAiAgent(
            historyRepository = ChatHistoryRepository(systemPrompt = "system"),
            initialSettings = AgentSettings(apiKey = "", systemPrompt = "system")
        )
        val method = DeepSeekAiAgent::class.java.getDeclaredMethod(
            "summaryMessages",
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(agent, history) as List<ChatMessage>
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
            val body = request.bodyPublisher().orElseThrow().let { publisher ->
                val subscriber = BodySubscriber()
                publisher.subscribe(subscriber)
                subscriber.body()
            }
            requestBodies += body
            val response = responses.getOrElse(index) {
                """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""
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

    private class BodySubscriber : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        private val bytes = mutableListOf<Byte>()

        override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription) {
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: java.nio.ByteBuffer) {
            while (item.hasRemaining()) bytes += item.get()
        }

        override fun onError(throwable: Throwable) = throw throwable
        override fun onComplete() = Unit

        fun body(): String = bytes.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private class StringHttpResponse(
        private val request: HttpRequest,
        private val body: String
    ) : HttpResponse<String> {
        override fun statusCode(): Int = 200
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): java.net.http.HttpHeaders =
            java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body(): String = body
        override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
