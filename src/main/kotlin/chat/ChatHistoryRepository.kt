package chat

import agent.AgentSettings
import java.util.UUID

class ChatHistoryRepository(
    systemPrompt: String,
    restoredMessages: List<ChatMessage> = emptyList(),
    restoredSummary: ChatSummary? = null,
    restoredFacts: Map<String, String> = emptyMap(),
    restoredBranches: List<ChatBranch> = emptyList(),
    restoredActiveBranchId: String? = null,
    restoredCheckpoint: ChatCheckpoint? = null,
    private val onChanged: (ChatHistoryState) -> Unit = {}
) {
    private val messages = mutableListOf<ChatMessage>()
    private var summary: ChatSummary? = restoredSummary
    private val facts = linkedMapOf<String, String>()
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
        ((messages + branches.flatMap { it.messages }).distinct().mapNotNull { it.usage } + listOfNotNull(summary?.usage))
            .fold(TokenUsage.ZERO) { total, usage -> total + usage }

    fun shouldCreateSummary(interval: Int): Boolean {
        if (interval <= 0) return false
        if (activeBranchId != null) return false
        val summaryIndex = summary?.lastMessageIndex ?: firstDialogMessageIndex()
        return messages.countAfter(summaryIndex) > interval
    }

    fun summarySourceMessages(): List<ChatMessage> {
        val currentSummary = summary
        if (currentSummary == null) return messages.apiMessages()

        return buildList {
            addSystemPromptIfPresent()
            add(summaryMessage(currentSummary.content))
            addAll(messages.drop(currentSummary.lastMessageIndex + 1).apiMessages())
        }
    }

    fun apiContextMessages(): List<ChatMessage> {
        val currentSummary = summary ?: return messages.apiMessages()
        return buildList {
            addSystemPromptIfPresent()
            add(summaryMessage(currentSummary.content))
            addAll(messages.drop(currentSummary.lastMessageIndex + 1).apiMessages())
        }
    }

    fun apiContextMessages(settings: AgentSettings): List<ChatMessage> {
        return when (settings.contextStrategy) {
            ContextStrategy.SLIDING_WINDOW -> summarizedSlidingWindowMessages(settings.contextWindowMessages)
            ContextStrategy.STICKY_FACTS -> buildList {
                addSystemPromptIfPresent()
                summary?.let { add(summaryMessage(it.content)) }
                factsMessage()?.let(::add)
                addAll(lastDialogMessages(summaryTailMessages(), settings.contextWindowMessages))
            }
            ContextStrategy.BRANCHING -> branchContextMessages()
        }
    }

    fun facts(): Map<String, String> = facts.toMap()

    fun checkpoint() {
        checkpoint = ChatCheckpoint(activeMessages().toList())
        persist()
    }

    fun createBranch(name: String): Boolean {
        val branchName = name.trim()
        if (branchName.isBlank() || branches.any { it.name == branchName }) return false
        val sourceMessages = checkpoint?.messages ?: activeMessages().toList()
        val branch = ChatBranch(
            id = UUID.randomUUID().toString(),
            name = branchName,
            messages = sourceMessages
        )
        branches += branch
        activeBranchId = branch.id
        persist()
        return true
    }

    fun branchNames(): List<String> = branches.map { it.name }

    fun activeBranchName(): String? = branches.firstOrNull { it.id == activeBranchId }?.name

    fun switchBranch(name: String): Boolean {
        val branch = branches.firstOrNull { it.name == name.trim() } ?: return false
        activeBranchId = branch.id
        persist()
        return true
    }

    fun indexBeforeLatestUserMessage(): Int {
        val latestUserIndex = messages.indexOfLast { it.role == Role.USER }
        return if (latestUserIndex < 0) messages.lastIndex else latestUserIndex - 1
    }

    fun saveSummary(content: String, usage: TokenUsage?, lastMessageIndex: Int = messages.lastIndex) {
        summary = ChatSummary(
            content = content,
            lastMessageIndex = lastMessageIndex,
            usage = usage
        )
        messages += ChatMessage(Role.EVENT, summaryUsageMessage(usage))
        persist()
    }

    fun clear(systemPrompt: String) {
        messages.clear()
        messages += ChatMessage(Role.SYSTEM, systemPrompt)
        summary = null
        facts.clear()
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

    private fun MutableList<ChatMessage>.addSystemPromptIfPresent() {
        messages.firstOrNull { it.role == Role.SYSTEM }?.let(::add)
    }

    private fun summaryMessage(content: String): ChatMessage =
        ChatMessage(Role.SYSTEM, "Summary of the previous dialog:\n$content")

    private fun firstDialogMessageIndex(): Int =
        if (messages.firstOrNull()?.role == Role.SYSTEM) 0 else -1

    private fun List<ChatMessage>.countAfter(index: Int): Int =
        drop(index + 1).count { it.role == Role.USER || it.role == Role.ASSISTANT }

    private fun List<ChatMessage>.apiMessages(): List<ChatMessage> =
        filter { it.role == Role.SYSTEM || it.role == Role.USER || it.role == Role.ASSISTANT }

    private fun slidingWindowMessages(source: List<ChatMessage>, window: Int): List<ChatMessage> = buildList {
        source.firstOrNull { it.role == Role.SYSTEM }?.let(::add)
        addAll(lastDialogMessages(source, window))
    }

    private fun summarizedSlidingWindowMessages(window: Int): List<ChatMessage> {
        val currentSummary = summary
        if (currentSummary == null) return slidingWindowMessages(messages, window)

        return buildList {
            addSystemPromptIfPresent()
            add(summaryMessage(currentSummary.content))
            addAll(lastDialogMessages(summaryTailMessages(), window))
        }
    }

    private fun summaryTailMessages(): List<ChatMessage> {
        val currentSummary = summary ?: return messages
        return messages.drop(currentSummary.lastMessageIndex + 1)
    }

    private fun branchContextMessages(): List<ChatMessage> {
        val source = activeMessages()
        return source.apiMessages()
    }

    private fun factsMessage(): ChatMessage? {
        if (facts.isEmpty()) return null
        val content = facts.entries.joinToString(
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
                facts[marker.second] = value
            }
        }
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
        restoredBranches = restoredState.branches,
        restoredActiveBranchId = restoredState.activeBranchId,
        restoredCheckpoint = restoredState.checkpoint,
        onChanged = onChanged
    )
}
