package agent

object JsonTools {
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
                else -> {
                    if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
    }

    fun extractAssistantContent(json: String): String? {
        val choicesIndex = json.indexOf("\"choices\"")
        if (choicesIndex < 0) return null
        val messageIndex = json.indexOf("\"message\"", startIndex = choicesIndex)
        if (messageIndex < 0) return null
        val contentIndex = json.indexOf("\"content\"", startIndex = messageIndex)
        if (contentIndex < 0) return null
        val colonIndex = json.indexOf(':', startIndex = contentIndex)
        if (colonIndex < 0) return null
        val stringStart = json.indexOf('"', startIndex = colonIndex + 1)
        if (stringStart < 0) return null
        val decoded = readJsonString(json, stringStart) ?: return null
        return decoded.takeIf { it.isNotBlank() }
    }

    private fun readJsonString(json: String, quoteIndex: Int): String? {
        val result = StringBuilder()
        var index = quoteIndex + 1
        while (index < json.length) {
            val char = json[index]
            when (char) {
                '"' -> return result.toString()
                '\\' -> {
                    index++
                    if (index >= json.length) return null
                    when (val escaped = json[index]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val hex = json.substringOrNull(index + 1, index + 5) ?: return null
                            result.append(hex.toIntOrNull(16)?.toChar() ?: return null)
                            index += 4
                        }
                        else -> return null
                    }
                }
                else -> result.append(char)
            }
            index++
        }
        return null
    }

    private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? =
        if (startIndex >= 0 && endIndex <= length && startIndex <= endIndex) substring(startIndex, endIndex) else null
}
