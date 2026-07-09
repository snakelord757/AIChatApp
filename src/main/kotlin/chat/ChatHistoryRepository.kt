package chat

import agent.AgentSettings
import formatting.CliPromptMarkerNormalizer
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
    restoredLastModelInputTokens: Long = 0,
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
    private var lastModelInputTokens: Long = restoredLastModelInputTokens

    private companion object {
        const val TOKEN_CHARS_APPROXIMATION = 4
        const val MESSAGE_OVERHEAD_TOKENS = 8
        const val DEFAULT_RESPONSE_RESERVE_TOKENS = 4_096L
        const val MIN_REQUEST_CONTEXT_TOKENS = 1_024L
        const val STICKY_FACTS_THRESHOLD_PERCENT = 95L
    }

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

    @Synchronized
    fun all(): List<ChatMessage> = activeMessages().toList()

    @Synchronized
    fun state(): ChatHistoryState = ChatHistoryState(
        messages = messages.toList(),
        summary = summary,
        facts = facts.toMap(),
        factsUsage = factsUsage,
        lastFactsUsage = lastFactsUsage,
        branches = branches.toList(),
        activeBranchId = activeBranchId,
        checkpoint = checkpoint,
        lastModelInputTokens = lastModelInputTokens
    )

    @Synchronized
    fun addUser(content: String) {
        updateFacts(content)
        appendMessage(ChatMessage(Role.USER, content))
        persist()
    }

    @Synchronized
    fun addAssistant(content: String, usage: TokenUsage? = null) {
        appendMessage(ChatMessage(Role.ASSISTANT, CliPromptMarkerNormalizer.normalizeGeneratedText(content), usage))
        persist()
    }

    @Synchronized
    fun addEvent(content: String) {
        appendMessage(ChatMessage(Role.EVENT, CliPromptMarkerNormalizer.normalizeGeneratedText(content)))
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
        return apiContextMessages(settings, emptyList())
    }

    fun apiContextMessages(
        settings: AgentSettings,
        memoryMessages: List<ChatMessage>,
        includeDerivedContext: Boolean = true
    ): List<ChatMessage> {
        val prefix = contextPrefix(memoryMessages, includeDerivedContext)
        return fitToModelContextWindow(
            fixedMessages = prefix,
            tailMessages = lastDialogMessages(activeSummaryTailMessages()),
            contextWindowTokens = settings.modelContextWindowTokens,
            responseReserveTokens = settings.responseReserveTokens()
        )
    }

    fun facts(): Map<String, String> = activeFacts()

    fun lastFactsUsage(): TokenUsage? = activeLastFactsUsage()

    fun shouldCompressWithStickyFacts(
        settings: AgentSettings,
        memoryMessages: List<ChatMessage>,
        includeDerivedContext: Boolean = true
    ): Boolean {
        val threshold = settings.modelContextWindowTokens * STICKY_FACTS_THRESHOLD_PERCENT / 100
        val recordedInputTokens = activeLastModelInputTokens()
        if (recordedInputTokens > 0L) {
            val source = activeMessages()
            val lastAssistantIndex = source.indexOfLast { it.role == Role.ASSISTANT }
            val tokensAddedSinceLastResponse = source
                .drop(lastAssistantIndex.coerceAtLeast(0))
                .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
                .sumOf { it.estimatedTokens() }
            return recordedInputTokens + tokensAddedSinceLastResponse >= threshold
        }

        val fixedMessages = contextPrefix(memoryMessages, includeDerivedContext)
        val tailMessages = lastDialogMessages(activeSummaryTailMessages())
        return fixedMessages.sumOf { it.estimatedTokens() } +
            tailMessages.sumOf { it.estimatedTokens() } >= threshold
    }

    @Synchronized
    fun recordModelInputTokens(inputTokens: Long) {
        setActiveLastModelInputTokens(inputTokens.coerceAtLeast(0))
        persist()
    }

    fun factsSourceMessages(settings: AgentSettings): List<ChatMessage> =
        fitToModelContextWindow(
            fixedMessages = emptyList(),
            tailMessages = lastDialogMessages(activeSummaryTailMessages()),
            contextWindowTokens = settings.modelContextWindowTokens,
            responseReserveTokens = settings.responseReserveTokens()
        )

    fun personalMemorySourceMessages(window: Int): List<ChatMessage> =
        lastDialogMessages(activeSummaryTailMessages()).takeLast(window.coerceAtLeast(0))

    fun activeSummaryText(): String? = activeSummary()?.content

    fun activeBranchIdOrMain(): String = activeBranchId ?: "main"

    fun activeBranchDisplayName(): String = activeBranchName() ?: "main"

    fun applyExtractedFacts(content: String, usage: TokenUsage? = null, recordEvent: Boolean = true) {
        usage?.let {
            addActiveFactsUsage(it)
            setActiveLastFactsUsage(it)
            if (recordEvent) {
                appendMessage(ChatMessage(Role.EVENT, factsUsageMessage(it)))
            }
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
            activeLastFactsUsage(),
            activeLastModelInputTokens()
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
            lastFactsUsage = checkpoint?.lastFactsUsage ?: activeLastFactsUsage(),
            lastModelInputTokens = checkpoint?.lastModelInputTokens ?: activeLastModelInputTokens()
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
        lastModelInputTokens = 0
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

    private fun activeLastModelInputTokens(): Long {
        val branchId = activeBranchId ?: return lastModelInputTokens
        return branches.firstOrNull { it.id == branchId }?.lastModelInputTokens ?: 0
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

    private fun setActiveLastModelInputTokens(inputTokens: Long) {
        val branchId = activeBranchId
        if (branchId == null) {
            lastModelInputTokens = inputTokens
            return
        }
        val index = branches.indexOfFirst { it.id == branchId }
        if (index < 0) {
            activeBranchId = null
            lastModelInputTokens = inputTokens
            return
        }
        branches[index] = branches[index].copy(lastModelInputTokens = inputTokens)
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

    private fun contextPrefix(memoryMessages: List<ChatMessage>, includeDerivedContext: Boolean): List<ChatMessage> =
        buildList {
            add(activeSystemPrompt())
            addAll(memoryMessages.apiMessages())
            if (includeDerivedContext) {
                activeSummaryMessage()?.let(::add)
                factsMessage()?.let(::add)
            }
        }

    private fun lastDialogMessages(source: List<ChatMessage>): List<ChatMessage> =
        source.filter { it.role == Role.USER || it.role == Role.ASSISTANT }

    private fun fitToModelContextWindow(
        fixedMessages: List<ChatMessage>,
        tailMessages: List<ChatMessage>,
        contextWindowTokens: Long,
        responseReserveTokens: Long
    ): List<ChatMessage> {
        val requestBudget = requestContextBudget(contextWindowTokens, responseReserveTokens)
        val selectedTail = ArrayDeque<ChatMessage>()
        var usedTokens = fixedMessages.sumOf { it.estimatedTokens() }
        for (message in tailMessages.asReversed()) {
            val messageTokens = message.estimatedTokens()
            if (usedTokens + messageTokens <= requestBudget || selectedTail.isEmpty()) {
                selectedTail.addFirst(message)
                usedTokens += messageTokens
            } else {
                break
            }
        }
        return fixedMessages + selectedTail
    }

    private fun requestContextBudget(contextWindowTokens: Long, responseReserveTokens: Long): Long =
        (contextWindowTokens - responseReserveTokens).coerceAtLeast(MIN_REQUEST_CONTEXT_TOKENS)

    private fun AgentSettings.responseReserveTokens(): Long =
        if (maxTokens > 0) maxTokens.toLong() else DEFAULT_RESPONSE_RESERVE_TOKENS

    private fun ChatMessage.estimatedTokens(): Long =
        ((content.length + TOKEN_CHARS_APPROXIMATION - 1) / TOKEN_CHARS_APPROXIMATION + MESSAGE_OVERHEAD_TOKENS).toLong()

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
        restoredLastModelInputTokens = restoredState.lastModelInputTokens,
        onChanged = onChanged
    )
}
