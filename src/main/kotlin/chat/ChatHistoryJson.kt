package chat

internal object ChatHistoryJson {
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

    fun decodeMessages(json: String): List<ChatMessage> {
        val parser = Parser(json)
        return parser.parseMessages()
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
