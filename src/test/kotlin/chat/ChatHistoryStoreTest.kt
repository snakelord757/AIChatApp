package chat

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
