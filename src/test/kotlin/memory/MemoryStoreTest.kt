package memory

import chat.AppPaths
import chat.ChatHistoryStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryStoreTest {
    @Test
    fun `store creates missing markdown files`() {
        val directory = Files.createTempDirectory("aichat-memory-store-test")
        try {
            val store = MemoryStore(directory.resolve("memory"))

            assertContains(store.readPermanent(), "# Permanent Memory")
            assertContains(store.readPersonal(), "# Personal Memory")
            assertContains(store.readWork("main"), "# Working Memory")

            assertTrue(Files.exists(directory.resolve("memory/permanent.md")))
            assertTrue(Files.exists(directory.resolve("memory/personal.md")))
            assertTrue(Files.exists(directory.resolve("memory/work/main.md")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `memory directory shares app directory logic with chat history`() {
        val directory = Files.createTempDirectory("aichat-memory-app-path-test")
        val oldProperty = System.getProperty("aichat.history.dir")
        try {
            System.setProperty("aichat.history.dir", directory.toString())

            assertEquals(directory.resolve("chat-history.json").toAbsolutePath().normalize(), ChatHistoryStore.defaultHistoryPath())
            assertEquals(directory.resolve("memory").toAbsolutePath().normalize(), AppPaths.memoryDirectory())
        } finally {
            if (oldProperty == null) {
                System.clearProperty("aichat.history.dir")
            } else {
                System.setProperty("aichat.history.dir", oldProperty)
            }
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store reads and writes utf8 markdown`() {
        val directory = Files.createTempDirectory("aichat-memory-utf8-test")
        try {
            val store = MemoryStore(directory.resolve("memory"))
            val text = "# Personal Memory\n\n- \u043e\u0442\u0432\u0435\u0447\u0430\u0442\u044c \u043a\u0440\u0430\u0442\u043a\u043e\n"

            store.writePersonal(text)

            assertEquals(text, Files.readString(store.personalPath(), StandardCharsets.UTF_8))
            assertEquals(text, store.readPersonal())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store parses and updates work status locally`() {
        val directory = Files.createTempDirectory("aichat-memory-status-test")
        try {
            val store = MemoryStore(directory.resolve("memory"))

            assertEquals(TaskStatus.DONE, store.workStatus("main"))
            store.setWorkStatus("main", TaskStatus.PENDING)
            assertEquals(TaskStatus.PENDING, store.workStatus("main"))
            store.setWorkStatus("main", TaskStatus.DONE)
            assertEquals(TaskStatus.DONE, store.workStatus("main"))
            assertEquals(null, store.parseStatus("# Working Memory\n\nStatus: BLOCKED\n"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store normalizes branch file names safely`() {
        val directory = Files.createTempDirectory("aichat-memory-safe-name-test")
        val store = MemoryStore(directory)

        try {
            assertEquals("feature-user-flow", store.safeBranchFileName("Feature/User Flow!?"))
            assertEquals("branch", store.safeBranchFileName("///"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
