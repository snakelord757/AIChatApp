package memory

import chat.ChatMessage
import chat.Role

class MemoryRepository(
    private val store: MemoryStore,
    private val activeBranchKeyProvider: () -> String?
) {
    fun ensureInitialized() {
        store.readPermanent()
        store.readPersonal()
        store.readWork(activeBranchKey())
    }

    fun contextMessages(): List<ChatMessage> = buildList {
        block("Permanent memory instructions:", store.readPermanent())?.let(::add)
        personalBlock(store.readPersonal())?.let(::add)
        workingBlock()?.let(::add)
    }

    fun permanentMemory(): String = store.readPermanent()

    fun personalMemory(): String = store.readPersonal()

    fun workingMemory(): String = store.readWork(activeBranchKey())

    fun workingStatus(): TaskStatus = store.workStatus(activeBranchKey())

    fun setWorkingStatus(status: TaskStatus) {
        store.setWorkStatus(activeBranchKey(), status)
    }

    fun reinforcePersonalSignals(userMessage: String): Boolean {
        val bullets = mutableListOf<String>()
        userMessage.lineSequence()
            .map { it.trim() }
            .forEach { line ->
                val value = when {
                    line.startsWith("remember:", ignoreCase = true) -> line.substringAfter(':').trim()
                    line.startsWith("preference:", ignoreCase = true) -> line.substringAfter(':').trim()
                    else -> null
                }
                value?.takeIf { it.isNotBlank() }?.let { bullets += "- $it" }
            }

        val lower = userMessage.lowercase()
        programmingLanguageSignals.forEach { signal ->
            if (signal.patterns.any { it.containsMatchIn(lower) }) {
                bullets += "- Works with ${signal.displayName} for code-related tasks"
            }
        }
        if (detailedExplanationPatterns.any { it.containsMatchIn(lower) }) {
            bullets += "- Prefers detailed explanations"
        }

        return appendPersonalBullets(bullets.joinToString("\n"))
    }

    fun appendPersonalBullets(candidateContent: String): Boolean {
        if (candidateContent.trim().equals("NO_CHANGES", ignoreCase = true)) return false
        val candidates = candidateContent.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .mapNotNull(::parsePersonalItem)
            .filterNot { looksSensitive(it.text) }
            .distinct()
            .toList()
        if (candidates.isEmpty()) return false

        val permanent = store.readPermanent()
        val personal = store.readPersonal()
        val existing = parsePersonalItems(personal).associateBy { normalizeConstraint(it.text) }.toMutableMap()
        var changed = false

        candidates.forEach { candidate ->
            if (permanent.contains(candidate.text, ignoreCase = true)) return@forEach
            val key = normalizeConstraint(candidate.text)
            val current = existing[key]
            if (current == null) {
                existing[key] = candidate.copy(strength = candidate.strength.coerceAtLeast(1))
                changed = true
            } else {
                existing[key] = current.copy(strength = (current.strength + candidate.strength).coerceAtMost(99))
                changed = true
            }
        }
        if (!changed) return false

        val renderedItems = existing.values
            .sortedWith(compareByDescending<PersonalMemoryItem> { it.strength }.thenBy { it.text.lowercase() })
            .joinToString("\n") { "- [strength: ${it.strength}] ${it.text}" }
        store.writePersonal("# Personal Memory\n\n$renderedItems\n")
        return true
    }

    fun paths(): MemoryPaths =
        MemoryPaths(
            permanent = store.permanentPath(),
            personal = store.personalPath(),
            work = store.workPath(activeBranchKey())
        )

    fun personalExtractionMessages(
        recentMessages: List<ChatMessage>,
        summary: String?
    ): List<ChatMessage> {
        val recent = recentMessages.joinToString(separator = "\n\n") { message ->
            "${message.role.apiName}: ${message.content}"
        }.ifBlank { "none" }
        val summaryBlock = summary?.takeIf { it.isNotBlank() }?.let {
            "\n\nCurrent summary:\n$it"
        }.orEmpty()
        val userPrompt = personalMemoryPrompt
            .replace("{{PERMANENT_MEMORY}}", permanentMemory().ifBlank { "none" })
            .replace("{{PERSONAL_MEMORY}}", personalMemory().ifBlank { "none" })
            .replace("{{RECENT_MESSAGES}}", recent + summaryBlock)
        return listOf(
            ChatMessage(Role.SYSTEM, "You maintain internal Markdown memory. Do not answer the user."),
            ChatMessage(Role.USER, userPrompt)
        )
    }

    private fun workingBlock(): ChatMessage? {
        val content = store.readWork(activeBranchKey())
        val status = store.parseStatus(content) ?: TaskStatus.DONE
        if (store.isTemplateOnly(content)) return null
        if (status != TaskStatus.PENDING && content.sectionsAfterStatusBlank()) return null
        return ChatMessage(Role.SYSTEM, "Working memory for the active branch:\n${content.trim()}")
    }

    private fun block(prefix: String, content: String): ChatMessage? {
        if (store.isTemplateOnly(content)) return null
        return ChatMessage(Role.SYSTEM, "$prefix\n${content.trim()}")
    }

    private fun personalBlock(content: String): ChatMessage? {
        if (store.isTemplateOnly(content)) return null
        return ChatMessage(
            Role.SYSTEM,
            """
            Personal memory about the user:
            Items may include [strength: N]. Higher strength means the preference or constraint has appeared more often and should be applied more readily when relevant.
            ${content.trim()}
            """.trimIndent()
        )
    }

    private fun activeBranchKey(): String? = activeBranchKeyProvider() ?: "main"

    private fun String.sectionsAfterStatusBlank(): Boolean =
        lineSequence()
            .filterNot { it.trim().startsWith("#") }
            .filterNot { it.trim().startsWith("Status:", ignoreCase = true) }
            .none { it.isNotBlank() }

    private fun looksSensitive(value: String): Boolean {
        val lower = value.lowercase()
        return listOf(
            "api key",
            "apikey",
            "password",
            "token",
            "secret",
            "credential",
            "private key"
        ).any(lower::contains)
    }

    private fun parsePersonalItems(content: String): List<PersonalMemoryItem> =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .mapNotNull(::parsePersonalItem)
            .filterNot { looksSensitive(it.text) }
            .toList()

    private fun parsePersonalItem(line: String): PersonalMemoryItem? {
        val match = personalItemPattern.matchEntire(line) ?: return null
        val strength = match.groupValues.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()
            ?.coerceIn(1, 99)
            ?: 1
        val text = match.groupValues.getOrNull(2)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.trimEnd('.')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return PersonalMemoryItem(text, strength)
    }

    private fun normalizeConstraint(value: String): String =
        value.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    companion object {
        private val personalItemPattern = Regex("""^-\s+(?:\[strength:\s*(\d+)]\s*)?(.+)$""", RegexOption.IGNORE_CASE)

        private val detailedExplanationPatterns = listOf(
            Regex("""\bexplain in detail\b"""),
            Regex("""\bdetailed explanation\b"""),
            Regex("""\u043f\u043e\u0434\u0440\u043e\u0431\u043d\u043e"""),
            Regex("""\u0434\u0435\u0442\u0430\u043b\u044c\u043d\u043e""")
        )

        private val programmingLanguageSignals = listOf(
            ProgrammingLanguageSignal("Python", listOf(Regex("""\bpython\b"""), Regex("""\u043f\u0438\u0442\u043e\u043d"""))),
            ProgrammingLanguageSignal("Kotlin", listOf(Regex("""\bkotlin\b"""))),
            ProgrammingLanguageSignal("Java", listOf(Regex("""\bjava\b"""))),
            ProgrammingLanguageSignal("JavaScript", listOf(Regex("""\bjavascript\b"""), Regex("""\bjs\b"""))),
            ProgrammingLanguageSignal("TypeScript", listOf(Regex("""\btypescript\b"""), Regex("""\bts\b"""))),
            ProgrammingLanguageSignal("C#", listOf(Regex("""\bc#\b"""), Regex("""\bcsharp\b"""))),
            ProgrammingLanguageSignal("C++", listOf(Regex("""\bc\+\+\b"""), Regex("""\bcpp\b"""))),
            ProgrammingLanguageSignal("Go", listOf(Regex("""\bgolang\b"""), Regex("""\bgo\b"""))),
            ProgrammingLanguageSignal("Rust", listOf(Regex("""\brust\b"""))),
            ProgrammingLanguageSignal("Swift", listOf(Regex("""\bswift\b"""))),
            ProgrammingLanguageSignal("SQL", listOf(Regex("""\bsql\b""")))
        )

        private val personalMemoryPrompt = """
            You update the assistant's personal Markdown memory about the user.

            Your task:
            Extract only durable behavioral preferences and interaction patterns that will likely help in future conversations.

            You must focus on:
            - how the user prefers answers to be structured;
            - preferred language, tone, detail level, and pacing;
            - stable engineering or workflow preferences;
            - repeated constraints the user wants respected;
            - durable collaboration habits;
            - explicit "remember this" instructions.

            You must NOT store:
            - temporary task details;
            - one-off chat facts;
            - secrets, API keys, passwords, tokens, credentials;
            - private personal data unless the user explicitly asked to remember it;
            - medical, legal, financial, political, religious, or sensitive identity information unless explicitly requested and clearly useful;
            - facts already present in permanent memory;
            - guesses or weak inferences.

            Permanent memory is authoritative and should not be duplicated.
            Existing personal memory is authoritative unless the recent chat clearly updates or reinforces it.
            Existing items may include [strength: N]. Higher strength means a more repeated and reliable preference.

            Return format:
            - If there is nothing new to save, return exactly:
            NO_CHANGES

            - Otherwise return only Markdown bullet lines.
            - Each bullet must be one concise durable memory item.
            - Return a bullet even when recent chat strongly reinforces an existing personal-memory item; local code will increase its strength.
            - You may use "- [strength: N] ..." only when the recent chat repeats the same constraint multiple times; otherwise use "- ...".
            - Preserve the user's language where practical.
            - Do not include headings.
            - Do not explain your reasoning.
            - Do not answer the user.

            Permanent memory:
            {{PERMANENT_MEMORY}}

            Existing personal memory:
            {{PERSONAL_MEMORY}}

            Recent chat:
            {{RECENT_MESSAGES}}
        """.trimIndent()
    }
}

private data class PersonalMemoryItem(
    val text: String,
    val strength: Int
)

private data class ProgrammingLanguageSignal(
    val displayName: String,
    val patterns: List<Regex>
)

data class MemoryPaths(
    val permanent: java.nio.file.Path,
    val personal: java.nio.file.Path,
    val work: java.nio.file.Path
)
