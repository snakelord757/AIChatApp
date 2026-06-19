package invariants

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class InvariantStore(
    private val invariantsPath: Path
) {
    fun path(): Path = invariantsPath

    fun ensureInitialized() {
        readOrCreate()
    }

    fun read(): String =
        if (Files.exists(invariantsPath)) Files.readString(invariantsPath, StandardCharsets.UTF_8) else ""

    fun append(text: String): Boolean {
        val value = text.trim()
        if (value.isBlank()) return false
        val current = readOrCreate()
        val separator = if (current.endsWith("\n")) "" else "\n"
        write(current + separator + "- $value\n")
        return true
    }

    fun remove(index: Int): Boolean {
        if (index < 0) return false
        val lines = read().lines().toMutableList()
        val bulletIndexes = lines.withIndex()
            .filter { it.value.trimStart().startsWith("- ") }
            .map { it.index }
        val lineIndex = bulletIndexes.getOrNull(index) ?: return false
        lines.removeAt(lineIndex)
        write(lines.joinToString("\n").trimEnd() + "\n")
        return true
    }

    fun isTemplateOnly(content: String): Boolean {
        val normalized = content.trim().replace("\r\n", "\n")
        return normalized.isBlank() || normalized == defaultTemplate.trim()
    }

    private fun readOrCreate(): String {
        invariantsPath.parent?.let(Files::createDirectories)
        if (!Files.exists(invariantsPath)) {
            Files.writeString(invariantsPath, defaultTemplate, StandardCharsets.UTF_8)
        }
        return Files.readString(invariantsPath, StandardCharsets.UTF_8)
    }

    private fun write(content: String) {
        invariantsPath.parent?.let(Files::createDirectories)
        Files.writeString(invariantsPath, content, StandardCharsets.UTF_8)
    }

    companion object {
        val defaultTemplate = """
            # Assistant Invariants

            Add non-negotiable architecture decisions, stack limits, technical decisions, and business rules here.
        """.trimIndent() + "\n"
    }
}
