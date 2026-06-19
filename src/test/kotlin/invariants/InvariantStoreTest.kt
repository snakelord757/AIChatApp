package invariants

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvariantStoreTest {
    @Test
    fun `store creates invariants file with default template`() {
        val directory = Files.createTempDirectory("aichat-invariant-store-test")
        try {
            val store = InvariantStore(directory.resolve("invariants.md"))

            store.ensureInitialized()

            assertTrue(Files.exists(store.path()))
            assertEquals(InvariantStore.defaultTemplate, Files.readString(store.path(), StandardCharsets.UTF_8))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
