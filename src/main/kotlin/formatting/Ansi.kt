package formatting

object Ansi {
    val isEnabled: Boolean = detectAnsiSupport()

    val RESET = "\u001B[0m"
    val BOLD = "\u001B[1m"
    val DIM = "\u001B[2m"
    val RED = "\u001B[31m"
    val GREEN = "\u001B[32m"
    val YELLOW = "\u001B[33m"
    val BLUE = "\u001B[34m"
    val MAGENTA = "\u001B[35m"
    val CYAN = "\u001B[36m"
    val GRAY = "\u001B[90m"

    fun style(text: String, vararg styles: String): String {
        if (!isEnabled) return text
        return styles.joinToString(separator = "") + text + RESET
    }

    private fun detectAnsiSupport(): Boolean {
        if (System.getProperty("aichat.ansi") == "true") return true
        if (System.getProperty("aichat.ansi") == "false") return false
        if (System.getenv("AICHAT_ANSI") == "true") return true
        if (System.getenv("AICHAT_ANSI") == "false") return false

        val osName = System.getProperty("os.name").lowercase()
        if (!osName.contains("windows")) return true

        return System.getenv("WT_SESSION").isNullOrBlank().not()
            || System.getenv("ANSICON").isNullOrBlank().not()
            || System.getenv("ConEmuANSI") == "ON"
            || System.getenv("TERM_PROGRAM").isNullOrBlank().not()
    }
}
