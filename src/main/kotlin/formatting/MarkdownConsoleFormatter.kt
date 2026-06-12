package formatting

class MarkdownConsoleFormatter {
    fun format(text: String): String {
        val output = StringBuilder()
        val lines = text.lines()
        var inCodeBlock = false
        var index = 0

        while (index < lines.size) {
            val line = lines[index].replace(
                Regex("!\\[[^]]*]\\([^)]*\\)"),
                "[Images are not supported in the CLI]"
            )

            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                output.appendLine(Ansi.style(line, Ansi.GRAY))
                index++
                continue
            }

            if (inCodeBlock) {
                output.appendLine(Ansi.style(line, Ansi.GRAY))
                index++
                continue
            }

            if (isTableStart(lines, index)) {
                val tableLines = mutableListOf<String>()
                while (index < lines.size && isTableLine(lines[index])) {
                    tableLines += lines[index]
                    index++
                }
                output.appendLine(formatTable(tableLines))
                continue
            }

            val formattedLine = when {
                line.startsWith("### ") -> Ansi.style(line.removePrefix("### "), Ansi.BOLD, Ansi.CYAN)
                line.startsWith("## ") -> Ansi.style(line.removePrefix("## "), Ansi.BOLD, Ansi.CYAN)
                line.startsWith("# ") -> Ansi.style(line.removePrefix("# "), Ansi.BOLD, Ansi.MAGENTA)
                line.startsWith("> ") -> Ansi.style("| ${line.removePrefix("> ")}", Ansi.BLUE)
                line.startsWith("Warning:", ignoreCase = true) -> Ansi.style(line, Ansi.YELLOW)
                line.startsWith("Error:", ignoreCase = true) -> Ansi.style(line, Ansi.RED)
                line.startsWith("Success:", ignoreCase = true) -> Ansi.style(line, Ansi.GREEN)
                line.startsWith("Note:", ignoreCase = true) -> Ansi.style(line, Ansi.BLUE)
                line.startsWith("- ") || line.startsWith("* ") -> "  * ${formatInline(line.drop(2))}"
                line.matches(Regex("\\d+\\.\\s+.*")) -> "  ${formatInline(line)}"
                else -> formatInline(line)
            }
            output.appendLine(formattedLine)
            index++
        }

        return output.toString().trimEnd()
    }

    private fun formatInline(line: String): String {
        var result = line.replace(Regex("`([^`]+)`")) { match ->
            Ansi.style(match.groupValues[1], Ansi.GRAY)
        }
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*")) { match ->
            Ansi.style(match.groupValues[1], Ansi.BOLD)
        }
        return result
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        return isTableLine(lines[index]) && isSeparatorLine(lines[index + 1])
    }

    private fun isTableLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.contains("|") && trimmed.count { it == '|' } >= 2
    }

    private fun isSeparatorLine(line: String): Boolean {
        val trimmed = line.trim().trim('|').trim()
        if (trimmed.isBlank()) return false
        return trimmed.split("|").all { cell ->
            cell.trim().matches(Regex(":?-{3,}:?"))
        }
    }

    private fun formatTable(lines: List<String>): String {
        val rows = lines
            .filterNot(::isSeparatorLine)
            .map(::splitTableRow)
            .filter { it.isNotEmpty() }

        if (rows.isEmpty()) return lines.joinToString(System.lineSeparator())

        val columnCount = rows.maxOf { it.size }
        val normalizedRows = rows.map { row -> row + List(columnCount - row.size) { "" } }
        val widths = List(columnCount) { column ->
            normalizedRows.maxOf { row -> visibleLength(row[column]) }.coerceAtLeast(3)
        }

        return buildString {
            normalizedRows.forEachIndexed { rowIndex, row ->
                val cells = row.mapIndexed { columnIndex, cell ->
                    val padded = cell.padEnd(widths[columnIndex])
                    if (rowIndex == 0) Ansi.style(padded, Ansi.BOLD, Ansi.CYAN) else formatInline(padded)
                }
                appendLine(cells.joinToString(prefix = "| ", separator = " | ", postfix = " |"))
                if (rowIndex == 0 && normalizedRows.size > 1) {
                    val separator = widths.joinToString(prefix = "| ", separator = " | ", postfix = " |") {
                        "-".repeat(it)
                    }
                    appendLine(Ansi.style(separator, Ansi.GRAY))
                }
            }
        }.trimEnd()
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim().trim('|').split("|").map { it.trim() }

    private fun visibleLength(text: String): Int =
        text.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .length
}
