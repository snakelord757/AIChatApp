package chat

internal object ChatHistoryJson {
    fun encodeState(state: ChatHistoryState): String {
        val summaryJson = encodeNullableSummary(state.summary)
        return "{\n" +
            "  \"messages\": ${encodeMessages(state.messages)},\n" +
            "  \"summary\": $summaryJson,\n" +
            "  \"facts\": ${encodeFacts(state.facts)},\n" +
            "  \"factsUsage\": ${encodeUsage(state.factsUsage)},\n" +
            "  \"lastFactsUsage\": ${encodeNullableUsage(state.lastFactsUsage)},\n" +
            "  \"branches\": ${encodeBranches(state.branches)},\n" +
            "  \"activeBranchId\": ${state.activeBranchId?.let { "\"${escape(it)}\"" } ?: "null"},\n" +
            "  \"checkpoint\": ${state.checkpoint?.let(::encodeCheckpoint) ?: "null"},\n" +
            "  \"lastModelInputTokens\": ${state.lastModelInputTokens}\n" +
            "}"
    }

    fun encodeMessages(messages: List<ChatMessage>): String {
        val body = messages.joinToString(separator = ",\n") { message ->
            val usage = message.usage
            val usageJson = if (usage == null) {
                ""
            } else {
                ""","usage":{"inputTokens":${usage.inputTokens},"outputTokens":${usage.outputTokens},"reasoningTokens":${usage.reasoningTokens}}"""
            }
            """  {"role":"${escape(message.role.apiName)}","content":"${escape(message.content)}"$usageJson}"""
        }
        return "[\n$body\n]"
    }

    private fun encodeFacts(facts: Map<String, String>): String {
        val body = facts.entries.joinToString(separator = ",") { (key, value) ->
            """"${escape(key)}":"${escape(value)}""""
        }
        return "{$body}"
    }

    private fun encodeBranches(branches: List<ChatBranch>): String {
        val body = branches.joinToString(separator = ",\n") { branch ->
            """  {"id":"${escape(branch.id)}","name":"${escape(branch.name)}","messages":${encodeMessages(branch.messages)},"summary":${encodeNullableSummary(branch.summary)},"facts":${encodeFacts(branch.facts)},"factsUsage":${encodeUsage(branch.factsUsage)},"lastFactsUsage":${encodeNullableUsage(branch.lastFactsUsage)},"lastModelInputTokens":${branch.lastModelInputTokens}}"""
        }
        return "[\n$body\n]"
    }

    private fun encodeCheckpoint(checkpoint: ChatCheckpoint): String =
        """{"messages":${encodeMessages(checkpoint.messages)},"summary":${encodeNullableSummary(checkpoint.summary)},"facts":${encodeFacts(checkpoint.facts)},"factsUsage":${encodeUsage(checkpoint.factsUsage)},"lastFactsUsage":${encodeNullableUsage(checkpoint.lastFactsUsage)},"lastModelInputTokens":${checkpoint.lastModelInputTokens}}"""

    private fun encodeNullableSummary(summary: ChatSummary?): String {
        if (summary == null) return "null"

        val usage = summary.usage
        val usageJson = if (usage == null) {
            ""
        } else {
            ""","usage":{"inputTokens":${usage.inputTokens},"outputTokens":${usage.outputTokens},"reasoningTokens":${usage.reasoningTokens}}"""
        }
        return """{"content":"${escape(summary.content)}","lastMessageIndex":${summary.lastMessageIndex}$usageJson}"""
    }

    private fun encodeUsage(usage: TokenUsage): String =
        """{"inputTokens":${usage.inputTokens},"outputTokens":${usage.outputTokens},"reasoningTokens":${usage.reasoningTokens}}"""

    private fun encodeNullableUsage(usage: TokenUsage?): String =
        usage?.let(::encodeUsage) ?: "null"

    fun decodeMessages(json: String): List<ChatMessage> {
        val parser = Parser(json)
        return parser.parseMessages()
    }

    fun decodeState(json: String): ChatHistoryState {
        val parser = Parser(json)
        return parser.parseState()
    }

    private fun escape(value: String): String = buildString {
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
    }

    private class Parser(private val json: String) {
        private var index = 0

        fun parseMessages(): List<ChatMessage> {
            val messages = mutableListOf<ChatMessage>()
            skipWhitespace()
            expect('[')
            skipWhitespace()
            if (peek() == ']') {
                index++
                skipWhitespace()
                requireEnd()
                return emptyList()
            }

            while (true) {
                messages += parseMessage()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    ']' -> {
                        index++
                        skipWhitespace()
                        requireEnd()
                        return messages
                    }
                    else -> error("Expected ',' or ']'")
                }
            }
        }

        fun parseState(): ChatHistoryState {
            skipWhitespace()
            if (peek() == '[') return ChatHistoryState(parseMessages(), summary = null)

            var messages: List<ChatMessage> = emptyList()
            var summary: ChatSummary? = null
            var facts: Map<String, String> = emptyMap()
            var factsUsage = TokenUsage.ZERO
            var lastFactsUsage: TokenUsage? = null
            var branches: List<ChatBranch> = emptyList()
            var activeBranchId: String? = null
            var checkpoint: ChatCheckpoint? = null
            var lastModelInputTokens = 0L

            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                index++
                requireEnd()
                return ChatHistoryState()
            }

            while (true) {
                val name = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                when (name) {
                    "messages" -> messages = parseMessagesArrayOnly()
                    "summary" -> summary = parseNullableSummary()
                    "facts" -> facts = parseFacts()
                    "factsUsage" -> factsUsage = parseUsage()
                    "lastFactsUsage" -> lastFactsUsage = parseNullableUsage()
                    "branches" -> branches = parseBranches()
                    "activeBranchId" -> activeBranchId = parseNullableString()
                    "checkpoint" -> checkpoint = parseNullableCheckpoint()
                    "lastModelInputTokens" -> lastModelInputTokens = parseNumber()
                    else -> skipValue()
                }
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    '}' -> {
                        index++
                        skipWhitespace()
                        requireEnd()
                        return ChatHistoryState(
                            messages = messages,
                            summary = summary,
                            facts = facts,
                            factsUsage = factsUsage,
                            lastFactsUsage = lastFactsUsage,
                            branches = branches,
                            activeBranchId = activeBranchId,
                            checkpoint = checkpoint,
                            lastModelInputTokens = lastModelInputTokens
                        )
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseFacts(): Map<String, String> {
            val facts = linkedMapOf<String, String>()
            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                index++
                return emptyMap()
            }

            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                facts[key] = parseString()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    '}' -> {
                        index++
                        return facts
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseBranches(): List<ChatBranch> {
            val branches = mutableListOf<ChatBranch>()
            expect('[')
            skipWhitespace()
            if (peek() == ']') {
                index++
                return emptyList()
            }

            while (true) {
                branches += parseBranch()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    ']' -> {
                        index++
                        return branches
                    }
                    else -> error("Expected ',' or ']'")
                }
            }
        }

        private fun parseBranch(): ChatBranch {
            var id: String? = null
            var name: String? = null
            var messages: List<ChatMessage> = emptyList()
            var summary: ChatSummary? = null
            var facts: Map<String, String> = emptyMap()
            var factsUsage = TokenUsage.ZERO
            var lastFactsUsage: TokenUsage? = null
            var lastModelInputTokens = 0L

            expect('{')
            skipWhitespace()
            if (peek() == '}') error("Branch object must not be empty")

            while (true) {
                val field = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                when (field) {
                    "id" -> id = parseString()
                    "name" -> name = parseString()
                    "messages" -> messages = parseMessagesArrayOnly()
                    "summary" -> summary = parseNullableSummary()
                    "facts" -> facts = parseFacts()
                    "factsUsage" -> factsUsage = parseUsage()
                    "lastFactsUsage" -> lastFactsUsage = parseNullableUsage()
                    "lastModelInputTokens" -> lastModelInputTokens = parseNumber()
                    else -> skipValue()
                }
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    '}' -> {
                        index++
                        return ChatBranch(
                            id ?: error("Branch id is missing"),
                            name ?: error("Branch name is missing"),
                            messages,
                            summary,
                            facts,
                            factsUsage,
                            lastFactsUsage,
                            lastModelInputTokens
                        )
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseNullableString(): String? {
            if (consumeLiteral("null")) return null
            return parseString()
        }

        private fun parseNullableCheckpoint(): ChatCheckpoint? {
            if (consumeLiteral("null")) return null
            var messages: List<ChatMessage> = emptyList()
            var summary: ChatSummary? = null
            var facts: Map<String, String> = emptyMap()
            var factsUsage = TokenUsage.ZERO
            var lastFactsUsage: TokenUsage? = null
            var lastModelInputTokens = 0L

            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                index++
                return ChatCheckpoint(emptyList())
            }

            while (true) {
                val field = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                when (field) {
                    "messages" -> messages = parseMessagesArrayOnly()
                    "summary" -> summary = parseNullableSummary()
                    "facts" -> facts = parseFacts()
                    "factsUsage" -> factsUsage = parseUsage()
                    "lastFactsUsage" -> lastFactsUsage = parseNullableUsage()
                    "lastModelInputTokens" -> lastModelInputTokens = parseNumber()
                    else -> skipValue()
                }
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    '}' -> {
                        index++
                        return ChatCheckpoint(messages, summary, facts, factsUsage, lastFactsUsage, lastModelInputTokens)
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseMessagesArrayOnly(): List<ChatMessage> {
            val messages = mutableListOf<ChatMessage>()
            expect('[')
            skipWhitespace()
            if (peek() == ']') {
                index++
                return emptyList()
            }

            while (true) {
                messages += parseMessage()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    ']' -> {
                        index++
                        return messages
                    }
                    else -> error("Expected ',' or ']'")
                }
            }
        }

        private fun parseNullableSummary(): ChatSummary? {
            if (consumeLiteral("null")) return null

            var content: String? = null
            var lastMessageIndex: Int? = null
            var usage: TokenUsage? = null

            expect('{')
            skipWhitespace()
            if (peek() == '}') error("Summary object must not be empty")

            while (true) {
                val name = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                when (name) {
                    "content" -> content = parseString()
                    "lastMessageIndex" -> lastMessageIndex = parseNumber().toInt()
                    "usage" -> usage = parseUsage()
                    else -> skipValue()
                }
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    '}' -> {
                        index++
                        return ChatSummary(
                            content ?: error("Summary content is missing"),
                            lastMessageIndex ?: error("Summary lastMessageIndex is missing"),
                            usage
                        )
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseMessage(): ChatMessage {
            var role: Role? = null
            var content: String? = null
            var usage: TokenUsage? = null

            expect('{')
            skipWhitespace()
            if (peek() == '}') error("Message object must not be empty")

            while (true) {
                val name = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                when (name) {
                    "role" -> {
                        val value = parseString()
                        role = Role.entries.firstOrNull { it.apiName == value }
                    }
                    "content" -> content = parseString()
                    "usage" -> usage = parseUsage()
                    else -> skipValue()
                }
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    '}' -> {
                        index++
                        return ChatMessage(
                            role ?: error("Message role is missing or unsupported"),
                            content ?: error("Message content is missing"),
                            usage
                        )
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseUsage(): TokenUsage {
            var inputTokens = 0L
            var outputTokens = 0L
            var reasoningTokens = 0L

            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                index++
                return TokenUsage.ZERO
            }

            while (true) {
                val name = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = parseNumber()
                when (name) {
                    "inputTokens" -> inputTokens = value
                    "outputTokens" -> outputTokens = value
                    "reasoningTokens" -> reasoningTokens = value
                }
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    '}' -> {
                        index++
                        return TokenUsage(inputTokens, outputTokens, reasoningTokens)
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseNullableUsage(): TokenUsage? {
            if (consumeLiteral("null")) return null
            return parseUsage()
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < json.length) {
                when (val char = json[index++]) {
                    '"' -> return result.toString()
                    '\\' -> result.append(parseEscape())
                    else -> result.append(char)
                }
            }
            error("Unterminated string")
        }

        private fun parseNumber(): Long {
            val start = index
            if (peek() == '-') index++
            while (index < json.length && json[index].isDigit()) index++
            if (start == index) error("Expected number")
            return json.substring(start, index).toLongOrNull() ?: error("Invalid number")
        }

        private fun consumeLiteral(value: String): Boolean {
            if (!json.regionMatches(index, value, 0, value.length)) return false
            index += value.length
            skipWhitespace()
            return true
        }

        private fun skipValue() {
            when (peek()) {
                '"' -> parseString()
                '{' -> skipObject()
                '[' -> skipArray()
                else -> {
                    while (index < json.length && json[index] !in setOf(',', '}', ']')) index++
                }
            }
        }

        private fun skipObject() {
            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                index++
                return
            }
            while (true) {
                parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                skipValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    '}' -> {
                        index++
                        return
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun skipArray() {
            expect('[')
            skipWhitespace()
            if (peek() == ']') {
                index++
                return
            }
            while (true) {
                skipValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    ']' -> {
                        index++
                        return
                    }
                    else -> error("Expected ',' or ']'")
                }
            }
        }

        private fun parseEscape(): Char {
            if (index >= json.length) error("Unterminated escape")
            return when (val escaped = json[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    val end = index + 4
                    if (end > json.length) error("Invalid unicode escape")
                    val value = json.substring(index, end).toIntOrNull(16)
                        ?: error("Invalid unicode escape")
                    index = end
                    value.toChar()
                }
                else -> error("Invalid escape")
            }
        }

        private fun skipWhitespace() {
            while (index < json.length && json[index].isWhitespace()) index++
        }

        private fun expect(char: Char) {
            skipWhitespace()
            if (peek() != char) error("Expected '$char'")
            index++
            skipWhitespace()
        }

        private fun peek(): Char? = json.getOrNull(index)

        private fun requireEnd() {
            if (index != json.length) error("Unexpected trailing content")
        }
    }
}
