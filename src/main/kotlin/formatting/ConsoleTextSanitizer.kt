package formatting

import java.nio.charset.Charset

object ConsoleTextSanitizer {
    fun sanitize(value: String, charset: Charset): String {
        val encoder = charset.newEncoder()
        val result = StringBuilder(value.length)
        var index = 0

        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            if (encoder.canEncode(text)) {
                result.append(text)
            } else {
                result.append(replacementFor(codePoint))
            }
            index += Character.charCount(codePoint)
        }

        return result.toString()
    }

    private fun replacementFor(codePoint: Int): String {
        return when (codePoint) {
            0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212 -> "-"
            0x2018, 0x2019, 0x201A, 0x201B -> "'"
            0x201C, 0x201D, 0x201E, 0x201F -> "\""
            0x2026 -> "..."
            0x2022, 0x25E6, 0x2043 -> "*"
            0x00A0 -> " "
            in 0x1F000..0x1FAFF -> ""
            else -> ""
        }
    }
}
