package chat

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal object MojibakeRepair {
    private val windows1251: Charset = Charset.forName("windows-1251")
    private val markers = listOf("\u0420", "\u0421", "\u0432\u0402")
    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    fun repair(value: String): String {
        if (!looksLikeMojibake(value)) return value

        val repaired = runCatching {
            String(value.toByteArray(windows1251), StandardCharsets.UTF_8)
        }.getOrNull() ?: return value

        return if (score(repaired) > score(value)) repaired else value
    }

    private fun looksLikeMojibake(value: String): Boolean {
        return markers.any(value::contains)
    }

    private fun score(value: String): Int {
        val cyrillicScore = cyrillic.findAll(value).count() * 2
        val mojibakePenalty = markers.sumOf { marker ->
            value.windowed(marker.length).count { it == marker }
        } * 3
        val replacementPenalty = value.count { it == '\uFFFD' || it == '?' }
        return cyrillicScore - mojibakePenalty - replacementPenalty
    }
}
