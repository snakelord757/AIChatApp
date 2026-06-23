package mcp

internal sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Number(val raw: String) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class ObjectValue(val values: Map<String, JsonValue>) : JsonValue
}

internal object McpJson {
    fun parse(json: String): JsonValue = Parser(json).parse()

    fun stringify(value: JsonValue): String = when (value) {
        JsonValue.Null -> "null"
        is JsonValue.Bool -> value.value.toString()
        is JsonValue.Number -> value.raw
        is JsonValue.StringValue -> "\"${escape(value.value)}\""
        is JsonValue.ArrayValue -> value.values.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        is JsonValue.ObjectValue -> value.values.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "\"${escape(key)}\":${stringify(item)}"
        }
    }

    fun escape(value: String): String = buildString {
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }

    private class Parser(private val json: String) {
        private var index = 0

        fun parse(): JsonValue {
            val value = parseValue()
            skipWhitespace()
            if (index != json.length) error("Unexpected trailing JSON content")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            return when (peek()) {
                '"' -> JsonValue.StringValue(parseString())
                '{' -> parseObject()
                '[' -> parseArray()
                't' -> {
                    expectLiteral("true")
                    JsonValue.Bool(true)
                }
                'f' -> {
                    expectLiteral("false")
                    JsonValue.Bool(false)
                }
                'n' -> {
                    expectLiteral("null")
                    JsonValue.Null
                }
                '-', in '0'..'9' -> JsonValue.Number(parseNumber())
                else -> error("Expected JSON value")
            }
        }

        private fun parseObject(): JsonValue.ObjectValue {
            val values = linkedMapOf<String, JsonValue>()
            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.ObjectValue(values)
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                values[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    '}' -> {
                        index++
                        return JsonValue.ObjectValue(values)
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): JsonValue.ArrayValue {
            val values = mutableListOf<JsonValue>()
            expect('[')
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.ArrayValue(values)
            }
            while (true) {
                values += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    ']' -> {
                        index++
                        return JsonValue.ArrayValue(values)
                    }
                    else -> error("Expected ',' or ']'")
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
                    val value = json.substring(index, end).toIntOrNull(16) ?: error("Invalid unicode escape")
                    index = end
                    value.toChar()
                }
                else -> error("Invalid escape")
            }
        }

        private fun parseNumber(): String {
            val start = index
            if (peek() == '-') index++
            while (peek()?.isDigit() == true) index++
            if (peek() == '.') {
                index++
                while (peek()?.isDigit() == true) index++
            }
            if (peek() in setOf('e', 'E')) {
                index++
                if (peek() in setOf('+', '-')) index++
                while (peek()?.isDigit() == true) index++
            }
            return json.substring(start, index)
        }

        private fun expectLiteral(value: String) {
            if (!json.regionMatches(index, value, 0, value.length)) error("Expected $value")
            index += value.length
        }

        private fun expect(char: Char) {
            skipWhitespace()
            if (peek() != char) error("Expected '$char'")
            index++
        }

        private fun skipWhitespace() {
            while (peek()?.isWhitespace() == true) index++
        }

        private fun peek(): Char? = json.getOrNull(index)
    }
}

internal fun JsonValue.asObject(): Map<String, JsonValue>? = (this as? JsonValue.ObjectValue)?.values
internal fun JsonValue.asArray(): List<JsonValue>? = (this as? JsonValue.ArrayValue)?.values
internal fun JsonValue.asString(): String? = (this as? JsonValue.StringValue)?.value
