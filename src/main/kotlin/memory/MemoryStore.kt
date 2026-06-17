package memory

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class MemoryStore(
    private val memoryDirectory: Path
) {
    fun permanentPath(): Path = memoryDirectory.resolve("permanent.md")

    fun personalPath(): Path = memoryDirectory.resolve("personal.md")

    fun workPath(branchIdOrName: String?): Path {
        val fileName = if (branchIdOrName.isNullOrBlank() || branchIdOrName == "main") {
            "main.md"
        } else {
            "${safeBranchFileName(branchIdOrName)}.md"
        }
        return memoryDirectory.resolve("work").resolve(fileName)
    }

    fun readPermanent(): String = readOrCreate(permanentPath(), permanentTemplate)

    fun readPersonal(): String = readOrCreate(personalPath(), personalTemplate)

    fun readWork(branchIdOrName: String?): String =
        readOrCreate(workPath(branchIdOrName), workTemplate(TaskStatus.DONE))

    fun writePersonal(content: String) {
        write(personalPath(), content)
    }

    fun writeWork(branchIdOrName: String?, content: String) {
        write(workPath(branchIdOrName), content)
    }

    fun workStatus(branchIdOrName: String?): TaskStatus =
        parseStatus(readWork(branchIdOrName)) ?: TaskStatus.DONE

    fun setWorkStatus(branchIdOrName: String?, status: TaskStatus) {
        val path = workPath(branchIdOrName)
        val content = readOrCreate(path, workTemplate(status))
        val updated = if (statusPattern.containsMatchIn(content)) {
            content.replace(statusPattern, "Status: ${status.name}")
        } else {
            content.replaceFirst("# Working Memory", "# Working Memory\n\nStatus: ${status.name}")
        }
        write(path, updated)
    }

    fun safeBranchFileName(value: String): String {
        val safe = value.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(80)
        return safe.ifBlank { "branch" }
    }

    fun isTemplateOnly(content: String): Boolean {
        val normalized = content.trim().replace("\r\n", "\n")
        return normalized.isBlank() ||
            normalized == permanentTemplate.trim() ||
            normalized == personalTemplate.trim() ||
            normalized == workTemplate(TaskStatus.DONE).trim() ||
            normalized == workTemplate(TaskStatus.PENDING).trim() ||
            normalized == workTemplate(TaskStatus.PAUSED).trim()
    }

    fun parseStatus(content: String): TaskStatus? {
        val value = statusPattern.find(content)?.groupValues?.getOrNull(1)
        return TaskStatus.parse(value)
    }

    private fun readOrCreate(path: Path, template: String): String {
        path.parent?.let(Files::createDirectories)
        if (!Files.exists(path)) {
            Files.writeString(path, template, StandardCharsets.UTF_8)
        }
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    private fun write(path: Path, content: String) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    companion object {
        val permanentTemplate = """
            # Permanent Memory

            Add global instructions and durable constraints here.
        """.trimIndent() + "\n"

        val personalTemplate = """
            # Personal Memory

            Add durable user preferences as Markdown bullet points.
        """.trimIndent() + "\n"

        fun workTemplate(status: TaskStatus): String = """
            # Working Memory

            Status: ${status.name}

            ## Current Task

            ## Notes
        """.trimIndent() + "\n"

        private val statusPattern = Regex("""(?im)^Status:\s*(PENDING|PAUSED|DONE)\s*$""")
    }
}
