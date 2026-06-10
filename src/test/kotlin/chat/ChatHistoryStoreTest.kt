package chat

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
}
