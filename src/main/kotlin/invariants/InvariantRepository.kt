package invariants

import chat.ChatMessage
import chat.Role
import java.nio.file.Path

class InvariantRepository(
    private val store: InvariantStore
) {
    fun ensureInitialized() {
        store.ensureInitialized()
    }

    fun invariants(): String = store.read()

    fun contextMessages(): List<ChatMessage> {
        val content = invariants()
        if (store.isTemplateOnly(content)) return emptyList()
        return listOf(
            ChatMessage(
                Role.SYSTEM,
                """
                Assistant invariants:
                These rules are non-negotiable. Before proposing or executing a solution, check it against every invariant.
                If the user's request conflicts with an invariant, refuse the conflicting part and propose the nearest compliant alternative.

                ${content.trim()}
                """.trimIndent()
            )
        )
    }

    fun path(): Path = store.path()

    fun appendInvariant(text: String): Boolean = store.append(text)

    fun removeInvariant(index: Int): Boolean = store.remove(index)
}
