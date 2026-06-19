package invariants

import chat.Role
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class InvariantRepositoryTest {
    @Test
    fun `template only invariants do not add system context`() {
        val directory = Files.createTempDirectory("aichat-invariant-template-test")
        try {
            val repository = InvariantRepository(InvariantStore(directory.resolve("invariants.md")))
            repository.ensureInitialized()

            assertEquals(emptyList(), repository.contextMessages())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `non-empty invariants produce exactly one system block`() {
        val directory = Files.createTempDirectory("aichat-invariant-context-test")
        try {
            val path = directory.resolve("invariants.md")
            Files.writeString(path, "# Assistant Invariants\n\n- Use Kotlin only.\n", StandardCharsets.UTF_8)
            val repository = InvariantRepository(InvariantStore(path))

            val messages = repository.contextMessages()

            assertEquals(1, messages.size)
            assertEquals(Role.SYSTEM, messages.single().role)
            assertContains(messages.single().content, "Assistant invariants:")
            assertContains(messages.single().content, "Use Kotlin only.")
            assertContains(messages.single().content, "refuse the conflicting part")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
