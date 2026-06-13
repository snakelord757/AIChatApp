package chat

import agent.AgentSettings
import java.util.UUID

class ChatHistoryRepository(
    systemPrompt: String,
    restoredMessages: List<ChatMessage> = emptyList(),
    restoredSummary: ChatSummary? = null,
    restoredFacts: Map<String, String> = emptyMap(),
    restoredFactsUsage: TokenUsage = TokenUsage.ZERO,
    restoredLastFactsUsage: TokenUsage? = null,
    restoredBranches: List<ChatBranch> = emptyList(),
    restoredActiveBranchId: String? = null,
    restoredCheckpoint: ChatCheckpoint? = null,
    private val onChanged: (ChatHistoryState) -> Unit = {}
) {
    private val messages = mutableListOf<ChatMessage>()
    private var summary: ChatSummary? = restoredSummary
    private val facts = linkedMapOf<String, String>()
    private var factsUsage: TokenUsage = restoredFactsUsage
    private var lastFactsUsage: TokenUsage? = restoredLastFactsUsage
    private val branches = mutableListOf<ChatBranch>()
    private var activeBranchId: String? = restoredActiveBranchId
    private var checkpoint: ChatCheckpoint? = restoredCheckpoint

    init {
        if (restoredMessages.isEmpty()) {
            messages += ChatMessage(Role.SYSTEM, systemPrompt)
        } else {
            messages += restoredMessages
        }
        facts.putAll(restoredFacts)
        branches += restoredBranches
        if (activeBranchId != null && branches.none { it.id == activeBranchId }) {
            activeBranchId = null
        }
    }

    fun all(): List<ChatMessage> = activeMessages().toList()

    fun state(): ChatHistoryState = ChatHistoryState(
        messages = messages.toList(),
        summary = summary,
        facts = facts.toMap(),
        factsUsage = factsUsage,
        lastFactsUsage = lastFactsUsage,
        branches = branches.toList(),
        activeBranchId = activeBranchId,
        checkpoint = checkpoint
    )

    fun addUser(content: String) {
        updateFacts(content)
        appendMessage(ChatMessage(Role.USER, content))
        persist()
    }

    fun addAssistant(content: String, usage: TokenUsage? = null) {
        appendMessage(ChatMessage(Role.ASSISTANT, content, usage))
        persist()
    }

    fun totalUsage(): TokenUsage =
        ((messages + branches.flatMap { it.messages }).distinct().mapNotNull { it.usage } +
            listOfNotNull(summary?.usage) +
            branches.mapNotNull { it.summary?.usage } +
            listOf(factsUsage) +
            branches.map { it.factsUsage })
            .fold(TokenUsage.ZERO) { total, usage -> total + usage }

    fun shouldCreateSummary(interval: Int): Boolean {
        if (interval <= 0) return false
        val source = activeMessages()
        val summaryIndex = activeSummary()?.lastMessageIndex ?: firstDialogMessageIndex(source)
        return source.countAfter(summaryIndex) > interval
    }

    fun summarySourceMessages(): List<ChatMessage> {
        val source = activeMessages()
        val currentSummary = activeSummary()
        if (currentSummary == null) return source.apiMessages()

        return buildList {
            add(activeSystemPrompt())
            add(summaryMessage(currentSummary.content))
            addAll(source.drop(currentSummary.lastMessageIndex + 1).apiMessages())
        }
    }

    fun apiContextMessages(): List<ChatMessage> {
        val source = activeMessages()
        val currentSummary = activeSummary() ?: return source.apiMessages()
        return buildList {
            add(activeSystemPrompt())
            add(summaryMessage(currentSummary.content))
            addAll(source.drop(currentSummary.lastMessageIndex + 1).apiMessages())
        }
    }

    fun apiContextMessages(settings: AgentSettings): List<ChatMessage> {
        return when (settings.contextStrategy) {
            ContextStrategy.SLIDING_WINDOW -> summarizedSlidingWindowMessages(settings.contextWindowMessages, activeMessages())
            ContextStrategy.STICKY_FACTS -> buildList {
                add(activeSystemPrompt())
                activeSummaryMessage()?.let(::add)
                factsMessage()?.let(::add)
                addAll(lastDialogMessages(activeSummaryTailMessages(), settings.contextWindowMessages))
            }
        }
    }

    fun facts(): Map<String, String> = activeFacts()

    fun lastFactsUsage(): TokenUsage? = activeLastFactsUsage()

    fun factsSourceMessages(window: Int): List<ChatMessage> =
        lastDialogMessages(activeSummaryTailMessages(), window)

    fun applyExtractedFacts(content: String, usage: TokenUsage? = null) {
        usage?.let {
            addActiveFactsUsage(it)
            setActiveLastFactsUsage(it)
            appendMessage(ChatMessage(Role.EVENT, factsUsageMessage(it)))
        }
        if (updateFactsFromLines(content)) {
            persist()
        } else if (usage != null) {
            persist()
        }
    }

    fun checkpoint() {
        checkpoint = ChatCheckpoint(
            activeMessages().toList(),
            activeSummary(),
            activeFacts(),
            activeFactsUsage(),
            activeLastFactsUsage()
        )
        persist()
    }

    fun createBranch(name: String): Boolean {
        val branchName = name.trim()
        if (branchName.isBlank() || branches.any { it.name == branchName }) return false
        val sourceMessages = checkpoint?.messages ?: activeMessages().toList()
        val branch = ChatBranch(
            id = UUID.randomUUID().toString(),
            name = branchName,
            messages = sourceMessages,
            summary = checkpoint?.summary ?: activeSummary(),
            facts = checkpoint?.facts ?: activeFacts(),
            factsUsage = checkpoint?.factsUsage ?: activeFactsUsage(),
            lastFactsUsage = checkpoint?.lastFactsUsage ?: activeLastFactsUsage()
        )
        branches += branch
        activeBranchId = branch.id
        persist()
        return true
    }

    fun branchNames(): List<String> = branches.map { it.name }

    fun activeBranchName(): String? = branches.firstOrNull { it.id == activeBranchId }?.name

    fun switchToMain() {
        activeBranchId = null
        persist()
    }

    fun switchBranch(name: String): Boolean {
        val normalized = name.trim()
        if (normalized.equals("main", ignoreCase = true)) {
            switchToMain()
            return true
        }
        val branch = branches.firstOrNull { it.name == normalized } ?: return false
        activeBranchId = branch.id
        persist()
        return true
    }

    fun indexBeforeLatestUserMessage(): Int {
        val source = activeMessages()
        val latestUserIndex = source.indexOfLast { it.role == Role.USER }
        return if (latestUserIndex < 0) source.lastIndex else latestUserIndex - 1
    }

    fun saveSummary(content: String, usage: TokenUsage?, lastMessageIndex: Int = activeMessages().lastIndex) {
        val newSummary = ChatSummary(
            content = content,
            lastMessageIndex = lastMessageIndex,
            usage = usage
        )
        setActiveSummary(newSummary)
        appendMessage(ChatMessage(Role.EVENT, summaryUsageMessage(usage)))
        persist()
    }

    fun clear(systemPrompt: String) {
        messages.clear()
        messages += ChatMessage(Role.SYSTEM, systemPrompt)
        summary = null
        facts.clear()
        factsUsage = TokenUsage.ZERO
        lastFactsUsage = null
        branches.clear()
        activeBranchId = null
        checkpoint = null
        onChanged(ChatHistoryState())
    }

    fun updateSystemPrompt(systemPrompt: String) {
        if (messages.firstOrNull()?.role == Role.SYSTEM) {
            messages[0] = ChatMessage(Role.SYSTEM, systemPrompt)
        } else {
            messages.add(0, ChatMessage(Role.SYSTEM, systemPrompt))
        }
        branches.replaceAll { branch ->
            branch.copy(messages = branch.messages.withSystemPrompt(systemPrompt))
        }
        checkpoint = checkpoint?.copy(messages = checkpoint?.messages.orEmpty().withSystemPrompt(systemPrompt))
        persist()
    }

    private fun persist() {
        onChanged(state())
    }

    private fun activeMessages(): List<ChatMessage> {
        val branchId = activeBranchId ?: return messages
        return branches.firstOrNull { it.id == branchId }?.messages ?: messages
    }

    private fun activeSummary(): ChatSummary? {
        val branchId = activeBranchId ?: return summary
        return branches.firstOrNull { it.id == branchId }?.summary
    }

    private fun activeFacts(): Map<String, String> {
        val branchId = activeBranchId ?: return facts.toMap()
        return branches.firstOrNull { it.id == branchId }?.facts.orEmpty()
    }

    private fun activeFactsUsage(): TokenUsage {
        val branchId = activeBranchId ?: return factsUsage
        return branches.firstOrNull { it.id == branchId }?.factsUsage ?: TokenUsage.ZERO
    }

    private fun activeLastFactsUsage(): TokenUsage? {
        val branchId = activeBranchId ?: return lastFactsUsage
        return branches.firstOrNull { it.id == branchId }?.lastFactsUsage
    }

    private fun setActiveSummary(newSummary: ChatSummary?) {
        val branchId = activeBranchId
        if (branchId == null) {
            summary = newSummary
            return
        }
        val index = branches.indexOfFirst { it.id == branchId }
        if (index < 0) {
            activeBranchId = null
            summary = newSummary
            return
        }
        branches[index] = branches[index].copy(summary = newSummary)
    }

    private fun setActiveFacts(newFacts: Map<String, String>) {
        val branchId = activeBranchId
        if (branchId == null) {
            facts.clear()
            facts.putAll(newFacts)
            return
        }
        val index = branches.indexOfFirst { it.id == branchId }
        if (index < 0) {
            activeBranchId = null
            facts.clear()
            facts.putAll(newFacts)
            return
        }
        branches[index] = branches[index].copy(facts = newFacts)
    }

    private fun addActiveFactsUsage(usage: TokenUsage) {
        val branchId = activeBranchId
        if (branchId == null) {
            factsUsage += usage
            return
        }
        val index = branches.indexOfFirst { it.id == branchId }
        if (index < 0) {
            activeBranchId = null
            factsUsage += usage
            return
        }
        branches[index] = branches[index].copy(factsUsage = branches[index].factsUsage + usage)
    }

    private fun setActiveLastFactsUsage(usage: TokenUsage?) {
        val branchId = activeBranchId
        if (branchId == null) {
            lastFactsUsage = usage
            return
        }
        val index = branches.indexOfFirst { it.id == branchId }
        if (index < 0) {
            activeBranchId = null
            lastFactsUsage = usage
            return
        }
        branches[index] = branches[index].copy(lastFactsUsage = usage)
    }

    private fun appendMessage(message: ChatMessage) {
        val branchId = activeBranchId
        if (branchId == null) {
            messages += message
            return
        }

        val index = branches.indexOfFirst { it.id == branchId }
        if (index < 0) {
            activeBranchId = null
            messages += message
            return
        }
        branches[index] = branches[index].copy(messages = branches[index].messages + message)
    }

    private fun summaryMessage(content: String): ChatMessage =
        ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\n$content")

    private fun firstDialogMessageIndex(source: List<ChatMessage>): Int =
        if (source.firstOrNull()?.role == Role.SYSTEM) 0 else -1

    private fun List<ChatMessage>.countAfter(index: Int): Int =
        drop(index + 1).count { it.role == Role.USER || it.role == Role.ASSISTANT }

    private fun List<ChatMessage>.apiMessages(): List<ChatMessage> =
        filter { it.role == Role.SYSTEM || it.role == Role.USER || it.role == Role.ASSISTANT }

    private fun slidingWindowMessages(source: List<ChatMessage>, window: Int): List<ChatMessage> = buildList {
        source.firstOrNull { it.role == Role.SYSTEM }?.let(::add)
        addAll(lastDialogMessages(source, window))
    }

    private fun summarizedSlidingWindowMessages(window: Int, source: List<ChatMessage>): List<ChatMessage> {
        val currentSummary = activeSummary() ?: return slidingWindowMessages(source, window)

        return buildList {
            add(activeSystemPrompt())
            add(summaryMessage(currentSummary.content))
            addAll(lastDialogMessages(source.drop(currentSummary.lastMessageIndex + 1), window))
        }
    }

    private fun summaryTailMessages(): List<ChatMessage> {
        val currentSummary = summary ?: return messages
        return messages.drop(currentSummary.lastMessageIndex + 1)
    }

    private fun activeSummaryTailMessages(): List<ChatMessage> =
        if (activeBranchId == null) {
            summaryTailMessages()
        } else {
            val branchSummary = activeSummary()
            val source = activeMessages()
            if (branchSummary == null) source else source.drop(branchSummary.lastMessageIndex + 1)
        }

    private fun activeSummaryMessage(): ChatMessage? =
        activeSummary()?.let { summaryMessage(it.content) }

    private fun activeSystemPrompt(): ChatMessage =
        activeMessages().firstOrNull { it.role == Role.SYSTEM }
            ?: messages.firstOrNull { it.role == Role.SYSTEM }
            ?: ChatMessage(Role.SYSTEM, "")

    private fun factsMessage(): ChatMessage? {
        val currentFacts = activeFacts()
        if (currentFacts.isEmpty()) return null
        val content = currentFacts.entries.joinToString(
            separator = "\n",
            prefix = "Sticky facts:\n"
        ) { (key, value) -> "- $key: $value" }
        return ChatMessage(Role.SYSTEM, content)
    }

    private fun lastDialogMessages(source: List<ChatMessage>, window: Int): List<ChatMessage> {
        val limit = window.coerceAtLeast(0)
        if (limit == 0) return emptyList()
        return source
            .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .takeLast(limit)
    }

    private fun updateFacts(content: String) {
        val currentFacts = linkedMapOf<String, String>()
        currentFacts.putAll(activeFacts())
        val markers = listOf(
            "goal:" to "goal",
            "\u0446\u0435\u043b\u044c:" to "goal",
            "constraint:" to "constraints",
            "\u043e\u0433\u0440\u0430\u043d\u0438\u0447\u0435\u043d\u0438\u0435:" to "constraints",
            "preference:" to "preferences",
            "\u043f\u0440\u0435\u0434\u043f\u043e\u0447\u0438\u0442\u0430\u044e:" to "preferences",
            "decision:" to "decisions",
            "\u0440\u0435\u0448\u0438\u043b\u0438:" to "decisions",
            "agreement:" to "agreements",
            "\u0434\u043e\u0433\u043e\u0432\u043e\u0440\u0438\u043b\u0438\u0441\u044c:" to "agreements"
        )

        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            val lower = trimmed.lowercase()
            val marker = markers.firstOrNull { (prefix) -> lower.startsWith(prefix) } ?: return@forEach
            val value = trimmed.substring(marker.first.length).trim()
            if (value.isNotBlank()) {
                currentFacts[marker.second] = value
            }
        }
        setActiveFacts(currentFacts)
    }

    private fun updateFactsFromLines(content: String): Boolean {
        val currentFacts = linkedMapOf<String, String>()
        currentFacts.putAll(activeFacts())
        var changed = false
        content.lineSequence().forEach { line ->
            val trimmed = line.trim().trimStart('-', '*').trim()
            if (trimmed.isBlank() || trimmed.equals("none", ignoreCase = true)) return@forEach

            val separator = trimmed.indexOf(':')
            if (separator <= 0) return@forEach

            val key = trimmed.substring(0, separator).trim().lowercase()
                .replace(Regex("\\s+"), "_")
            val value = trimmed.substring(separator + 1).trim()
            if (key.isBlank() || value.isBlank()) return@forEach
            if (currentFacts[key] != value) {
                currentFacts[key] = value
                changed = true
            }
        }
        if (changed) {
            setActiveFacts(currentFacts)
        }
        return changed
    }

    private fun List<ChatMessage>.withSystemPrompt(systemPrompt: String): List<ChatMessage> {
        if (isEmpty()) return listOf(ChatMessage(Role.SYSTEM, systemPrompt))
        if (first().role == Role.SYSTEM) return listOf(ChatMessage(Role.SYSTEM, systemPrompt)) + drop(1)
        return listOf(ChatMessage(Role.SYSTEM, systemPrompt)) + this
    }
}

fun ChatHistoryRepository(
    systemPrompt: String,
    restoredState: ChatHistoryState,
    onChanged: (ChatHistoryState) -> Unit = {}
): ChatHistoryRepository {
    return ChatHistoryRepository(
        systemPrompt = systemPrompt,
        restoredMessages = restoredState.messages,
        restoredSummary = restoredState.summary,
        restoredFacts = restoredState.facts,
        restoredFactsUsage = restoredState.factsUsage,
        restoredLastFactsUsage = restoredState.lastFactsUsage,
        restoredBranches = restoredState.branches,
        restoredActiveBranchId = restoredState.activeBranchId,
        restoredCheckpoint = restoredState.checkpoint,
        onChanged = onChanged
    )
}
