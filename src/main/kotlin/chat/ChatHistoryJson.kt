package chat

internal object ChatHistoryJson {
    fun encodeMessages(messages: List<ChatMessage>): String {
        val body = messages.joinToString(separator = ",\n") { message ->
            """  {"role":"${escape(message.role.apiName)}","content":"${escape(message.content)}"}"""
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

            expect('{')
            skipWhitespace()
            if (peek() == '}') error("Message object must not be empty")

            while (true) {
                val name = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = parseString()
                when (name) {
                    "role" -> role = Role.entries.firstOrNull { it.apiName == value }
                    "content" -> content = value
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
                            content ?: error("Message content is missing")
                        )
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
