package chat

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatHistoryStoreTest {
    @Test
    fun `store writes and restores messages`() {
        val directory = Files.createTempDirectory("aichat-history-test")
        try {
            val path = directory.resolve("chat-history.json")
            ChatHistoryStore.open(path).use { store ->
                store.write(
                    listOf(
                        ChatMessage(Role.SYSTEM, "system"),
                        ChatMessage(Role.USER, "hello"),
                        ChatMessage(Role.ASSISTANT, "hi\nthere")
                    )
                )
            }

            ChatHistoryStore.open(path).use { store ->
                assertEquals(
                    listOf(
                        ChatMessage(Role.SYSTEM, "system"),
                        ChatMessage(Role.USER, "hello"),
                        ChatMessage(Role.ASSISTANT, "hi\nthere")
                    ),
                    store.read()
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store writes and restores assistant token usage`() {
        val directory = Files.createTempDirectory("aichat-history-usage-test")
        try {
            val path = directory.resolve("chat-history.json")
            val usage = TokenUsage(inputTokens = 10, outputTokens = 20, reasoningTokens = 30)
            ChatHistoryStore.open(path).use { store ->
                store.write(listOf(ChatMessage(Role.ASSISTANT, "hi", usage)))
            }

            ChatHistoryStore.open(path).use { store ->
                assertEquals(listOf(ChatMessage(Role.ASSISTANT, "hi", usage)), store.read())
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store writes and restores summary state`() {
        val directory = Files.createTempDirectory("aichat-history-summary-test")
        try {
            val path = directory.resolve("chat-history.json")
            val state = ChatHistoryState(
                messages = listOf(
                    ChatMessage(Role.SYSTEM, "system"),
                    ChatMessage(Role.USER, "hello")
                ),
                summary = ChatSummary(
                    content = "compressed",
                    lastMessageIndex = 1,
                    usage = TokenUsage(inputTokens = 10, outputTokens = 20, reasoningTokens = 30)
                )
            )
            ChatHistoryStore.open(path).use { store ->
                store.writeState(state)
            }

            ChatHistoryStore.open(path).use { store ->
                assertEquals(
                    state.copy(
                        messages = listOf(
                            ChatMessage(Role.SYSTEM, "system"),
                            ChatMessage(Role.USER, "hello"),
                            ChatMessage(
                                Role.EVENT,
                                "Chat summarization completed. Summary request tokens: input=10, output=20, reasoning=30, total=60"
                            )
                        )
                    ),
                    store.readState()
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store adds missing summary usage event for existing summary state`() {
        val directory = Files.createTempDirectory("aichat-history-summary-event-test")
        try {
            val path = directory.resolve("chat-history.json")
            val summary = ChatSummary(
                content = "compressed",
                lastMessageIndex = 1,
                usage = TokenUsage(inputTokens = 1, outputTokens = 2, reasoningTokens = 3)
            )
            Files.writeString(
                path,
                ChatHistoryJson.encodeState(
                    ChatHistoryState(
                        messages = listOf(
                            ChatMessage(Role.SYSTEM, "system"),
                            ChatMessage(Role.USER, "hello"),
                            ChatMessage(Role.USER, "tail")
                        ),
                        summary = summary
                    )
                ),
                StandardCharsets.UTF_8
            )

            ChatHistoryStore.open(path).use { store ->
                assertEquals(
                    ChatHistoryState(
                        messages = listOf(
                            ChatMessage(Role.SYSTEM, "system"),
                            ChatMessage(Role.USER, "hello"),
                            ChatMessage(
                                Role.EVENT,
                                "Chat summarization completed. Summary request tokens: input=1, output=2, reasoning=3, total=6"
                            ),
                            ChatMessage(Role.USER, "tail")
                        ),
                        summary = summary
                    ),
                    store.readState()
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store restores legacy message array as state without summary`() {
        val directory = Files.createTempDirectory("aichat-history-legacy-test")
        try {
            val path = directory.resolve("chat-history.json")
            val messages = listOf(ChatMessage(Role.USER, "hello"))
            ChatHistoryStore.open(path).use { store ->
                store.write(messages)
            }

            ChatHistoryStore.open(path).use { store ->
                assertEquals(ChatHistoryState(messages = messages), store.readState())
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store normalizes restored generated blockquotes without changing user input`() {
        val directory = Files.createTempDirectory("aichat-history-prompt-marker-test")
        try {
            val path = directory.resolve("chat-history.json")
            ChatHistoryStore.open(path).use { store ->
                store.writeState(
                    ChatHistoryState(
                        messages = listOf(
                            ChatMessage(Role.SYSTEM, "system"),
                            ChatMessage(Role.USER, "> user quote"),
                            ChatMessage(Role.ASSISTANT, "Answer\n> generated quote"),
                            ChatMessage(Role.EVENT, "Stage EXECUTION: success\nSummary: ok\n\n> detail")
                        )
                    )
                )
            }

            ChatHistoryStore.open(path).use { store ->
                assertEquals(
                    listOf(
                        ChatMessage(Role.SYSTEM, "system"),
                        ChatMessage(Role.USER, "> user quote"),
                        ChatMessage(Role.ASSISTANT, "Answer\nNote: generated quote"),
                        ChatMessage(Role.EVENT, "Stage EXECUTION: success\nSummary: ok\n\nNote: detail")
                    ),
                    store.readState().messages
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `json round trips new state fields and decodes legacy object`() {
        val state = ChatHistoryState(
            messages = listOf(ChatMessage(Role.SYSTEM, "system")),
            summary = ChatSummary("summary", lastMessageIndex = 0),
            facts = mapOf("goal" to "\u0446\u0435\u043b\u044c"),
            factsUsage = TokenUsage(inputTokens = 1, outputTokens = 2, reasoningTokens = 3),
            lastFactsUsage = TokenUsage(inputTokens = 2, outputTokens = 3, reasoningTokens = 4),
            lastModelInputTokens = 101,
            branches = listOf(
                ChatBranch(
                    id = "branch-1",
                    name = "\u0432\u0435\u0442\u043a\u0430",
                    messages = listOf(ChatMessage(Role.USER, "\u043f\u0440\u0438\u0432\u0435\u0442")),
                    summary = ChatSummary("branch summary", lastMessageIndex = 0),
                    facts = mapOf("branch_goal" to "\u0432\u0435\u0442\u043a\u0430"),
                    factsUsage = TokenUsage(inputTokens = 4, outputTokens = 5, reasoningTokens = 6),
                    lastFactsUsage = TokenUsage(inputTokens = 5, outputTokens = 6, reasoningTokens = 7),
                    lastModelInputTokens = 202
                )
            ),
            activeBranchId = "branch-1",
            checkpoint = ChatCheckpoint(
                messages = listOf(ChatMessage(Role.USER, "checkpoint")),
                summary = ChatSummary("checkpoint summary", lastMessageIndex = 0),
                facts = mapOf("checkpoint_goal" to "checkpoint"),
                factsUsage = TokenUsage(inputTokens = 7, outputTokens = 8, reasoningTokens = 9),
                lastFactsUsage = TokenUsage(inputTokens = 8, outputTokens = 9, reasoningTokens = 10),
                lastModelInputTokens = 303
            )
        )

        assertEquals(state, ChatHistoryJson.decodeState(ChatHistoryJson.encodeState(state)))
        assertEquals(
            ChatHistoryState(messages = listOf(ChatMessage(Role.USER, "old"))),
            ChatHistoryJson.decodeState("""{"messages":[{"role":"user","content":"old"}],"summary":null}""")
        )
    }

    @Test
    fun `store repairs mojibake in summary facts branch names and branch messages`() {
        val directory = Files.createTempDirectory("aichat-history-new-fields-repair-test")
        try {
            val path = directory.resolve("chat-history.json")
            fun mojibake(value: String): String =
                String(value.toByteArray(StandardCharsets.UTF_8), Charset.forName("windows-1251"))

            val state = ChatHistoryState(
                messages = listOf(ChatMessage(Role.SYSTEM, "system")),
                summary = ChatSummary(mojibake("\u0441\u0432\u043e\u0434\u043a\u0430"), lastMessageIndex = 0),
                facts = mapOf("goal" to mojibake("\u0446\u0435\u043b\u044c")),
                branches = listOf(
                    ChatBranch(
                        id = "branch-1",
                        name = mojibake("\u0432\u0435\u0442\u043a\u0430"),
                        messages = listOf(ChatMessage(Role.USER, mojibake("\u043f\u0440\u0438\u0432\u0435\u0442"))),
                        summary = ChatSummary(mojibake("\u0441\u0432\u043e\u0434\u043a\u0430 \u0432\u0435\u0442\u043a\u0438"), lastMessageIndex = 0),
                        facts = mapOf("branch_goal" to mojibake("\u0432\u0435\u0442\u043a\u0430"))
                    )
                )
            )
            Files.writeString(path, ChatHistoryJson.encodeState(state), StandardCharsets.UTF_8)

            ChatHistoryStore.open(path).use { store ->
                val restored = store.readState()
                assertEquals("\u0441\u0432\u043e\u0434\u043a\u0430", restored.summary?.content)
                assertEquals(mapOf("goal" to "\u0446\u0435\u043b\u044c"), restored.facts)
                assertEquals("\u0432\u0435\u0442\u043a\u0430", restored.branches.single().name)
                assertEquals("\u043f\u0440\u0438\u0432\u0435\u0442", restored.branches.single().messages.first { it.role == Role.USER }.content)
                assertEquals(true, restored.branches.single().messages.any { it.role == Role.EVENT })
                assertEquals("\u0441\u0432\u043e\u0434\u043a\u0430 \u0432\u0435\u0442\u043a\u0438", restored.branches.single().summary?.content)
                assertEquals(mapOf("branch_goal" to "\u0432\u0435\u0442\u043a\u0430"), restored.branches.single().facts)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store rejects second lock owner`() {
        val directory = Files.createTempDirectory("aichat-history-lock-test")
        try {
            val path = directory.resolve("chat-history.json")
            ChatHistoryStore.open(path).use {
                assertFailsWith<ChatHistoryBusyException> {
                    ChatHistoryStore.open(path)
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store repairs mojibake history on read`() {
        val directory = Files.createTempDirectory("aichat-history-repair-test")
        try {
            val path = directory.resolve("chat-history.json")
            val original = "\u0413\u043e\u0442\u043e\u0432 \u043f\u043e\u043c\u043e\u0447\u044c \u2014 \u0441\u043f\u0440\u043e\u0441\u0438\u0442\u0435."
            val mojibake = String(original.toByteArray(StandardCharsets.UTF_8), Charset.forName("windows-1251"))
            Files.writeString(
                path,
                ChatHistoryJson.encodeMessages(listOf(ChatMessage(Role.ASSISTANT, mojibake))),
                StandardCharsets.UTF_8
            )

            ChatHistoryStore.open(path).use { store ->
                assertEquals(listOf(ChatMessage(Role.ASSISTANT, original)), store.read())
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `default history path is not derived from java executable directory`() {
        val defaultPath = ChatHistoryStore.defaultHistoryPath().toAbsolutePath().normalize()
        val configuredPath = System.getProperty("aichat.history.dir")?.takeIf { it.isNotBlank() }
            ?: System.getenv("APP_HOME")?.takeIf { it.isNotBlank() }
        val userHome = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()

        val allowedBasePaths = listOfNotNull(
            configuredPath?.let { Paths.get(it).toAbsolutePath().normalize() },
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it).toAbsolutePath().normalize() },
            userHome
        )

        assertTrue(allowedBasePaths.any { defaultPath.startsWith(it) })
    }
}
