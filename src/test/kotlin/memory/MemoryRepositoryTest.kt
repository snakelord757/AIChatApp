package memory

import chat.ChatMessage
import chat.Role
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MemoryRepositoryTest {
    @Test
    fun `template files do not add system context`() {
        val directory = Files.createTempDirectory("aichat-memory-repository-template-test")
        try {
            val repository = MemoryRepository(MemoryStore(directory.resolve("memory"))) { "main" }

            repository.ensureInitialized()

            assertEquals(emptyList(), repository.contextMessages())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `markdown memory becomes ordered system blocks`() {
        val directory = Files.createTempDirectory("aichat-memory-repository-context-test")
        try {
            val store = MemoryStore(directory.resolve("memory"))
            val repository = MemoryRepository(store) { "main" }
            repository.ensureInitialized()
            Files.writeString(store.permanentPath(), "# Permanent\n\nAlways be precise.\n", StandardCharsets.UTF_8)
            Files.writeString(store.personalPath(), "# Personal\n\n- prefers concise answers\n", StandardCharsets.UTF_8)
            store.writeWork("main", "# Working Memory\n\nStatus: PENDING\n\n## Current Task\nShip memory.\n")

            val context = repository.contextMessages()

            assertEquals(Role.SYSTEM, context[0].role)
            assertContains(context[0].content, "Permanent memory instructions:")
            assertContains(context[1].content, "Personal memory about the user:")
            assertContains(context[2].content, "Working memory for the active branch:")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `working memory is separate for main and branch`() {
        val directory = Files.createTempDirectory("aichat-memory-repository-branch-test")
        try {
            var branch = "main"
            val store = MemoryStore(directory.resolve("memory"))
            val repository = MemoryRepository(store) { branch }

            store.writeWork("main", "# Working Memory\n\nStatus: PENDING\n\n## Current Task\nMain work.\n")
            branch = "branch-1"
            store.writeWork("branch-1", "# Working Memory\n\nStatus: PENDING\n\n## Current Task\nBranch work.\n")

            assertContains(repository.workingMemory(), "Branch work.")
            branch = "main"
            assertContains(repository.workingMemory(), "Main work.")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `working status changes locally`() {
        val directory = Files.createTempDirectory("aichat-memory-repository-status-test")
        try {
            val repository = MemoryRepository(MemoryStore(directory.resolve("memory"))) { "main" }

            repository.setWorkingStatus(TaskStatus.PENDING)
            assertEquals(TaskStatus.PENDING, repository.workingStatus())
            repository.setWorkingStatus(TaskStatus.DONE)
            assertEquals(TaskStatus.DONE, repository.workingStatus())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `personal bullets are deduplicated against personal and permanent memory`() {
        val directory = Files.createTempDirectory("aichat-memory-repository-bullets-test")
        try {
            val store = MemoryStore(directory.resolve("memory"))
            val repository = MemoryRepository(store) { "main" }
            repository.ensureInitialized()
            Files.writeString(store.permanentPath(), "# Permanent\n\nNever store duplicate permanent rule.\n", StandardCharsets.UTF_8)
            Files.writeString(store.personalPath(), "# Personal Memory\n\n- Existing preference\n", StandardCharsets.UTF_8)

            val changed = repository.appendPersonalBullets(
                """
                - Existing preference
                - Never store duplicate permanent rule.
                - New durable preference
                - password is secret
                """.trimIndent()
            )

            val personal = repository.personalMemory()
            assertEquals(true, changed)
            assertContains(personal, "- [strength: 2] Existing preference")
            assertContains(personal, "- [strength: 1] New durable preference")
            assertFalse(personal.contains("password is secret"))
            assertFalse(personal.contains("- Never store duplicate permanent rule."))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `repeated personal constraints increase strength`() {
        val directory = Files.createTempDirectory("aichat-memory-repository-strength-test")
        try {
            val store = MemoryStore(directory.resolve("memory"))
            val repository = MemoryRepository(store) { "main" }
            repository.ensureInitialized()

            repository.appendPersonalBullets("- Prefers Kotlin for code-related tasks")
            repository.appendPersonalBullets("- Prefers Kotlin for code-related tasks")
            repository.appendPersonalBullets("- [strength: 2] Prefers detailed explanations")

            val personal = repository.personalMemory()
            assertContains(personal, "- [strength: 2] Prefers Kotlin for code-related tasks")
            assertContains(personal, "- [strength: 2] Prefers detailed explanations")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `local signals reinforce repeated programming language usage`() {
        val directory = Files.createTempDirectory("aichat-memory-repository-local-signals-test")
        try {
            val repository = MemoryRepository(MemoryStore(directory.resolve("memory"))) { "main" }
            repository.ensureInitialized()

            repository.reinforcePersonalSignals("Напиши функцию на языке Python")
            repository.reinforcePersonalSignals("Сделай пример Python для чтения файла")
            repository.reinforcePersonalSignals("Объясни Python-код детально")

            val personal = repository.personalMemory()
            assertContains(personal, "- [strength: 3] Works with Python for code-related tasks")
            assertContains(personal, "- [strength: 1] Prefers detailed explanations")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `personal extraction prompt includes existing memories and recent messages`() {
        val directory = Files.createTempDirectory("aichat-memory-repository-prompt-test")
        try {
            val store = MemoryStore(directory.resolve("memory"))
            val repository = MemoryRepository(store) { "main" }
            repository.ensureInitialized()
            Files.writeString(store.permanentPath(), "# Permanent\n\nPermanent rule.\n", StandardCharsets.UTF_8)
            Files.writeString(store.personalPath(), "# Personal\n\n- Existing preference\n", StandardCharsets.UTF_8)

            val messages = repository.personalExtractionMessages(
                recentMessages = listOf(ChatMessage(Role.USER, "remember: keep it short")),
                summary = "Old summary"
            )

            assertContains(messages[1].content, "Permanent rule.")
            assertContains(messages[1].content, "- Existing preference")
            assertContains(messages[1].content, "Higher strength means")
            assertContains(messages[1].content, "user: remember: keep it short")
            assertContains(messages[1].content, "Old summary")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
