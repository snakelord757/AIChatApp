package formatting

object CliPromptMarkerNormalizer {
    fun normalizeGeneratedText(text: String): String {
        if (text.isEmpty()) return text

        var insideFence = false
        return text.lineSequence()
            .map { line ->
                val trimmed = line.trimStart()
                val leading = line.take(line.length - trimmed.length)
                val fenceMarker = trimmed.startsWith("```")
                val normalizedLine = if (!insideFence && trimmed.startsWith("> ")) {
                    leading + "Note: " + trimmed.removePrefix("> ").trimStart()
                } else {
                    line
                }
                if (fenceMarker) {
                    insideFence = !insideFence
                }
                normalizedLine
            }
            .joinToString("\n")
    }
}
