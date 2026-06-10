package formatting

import java.io.PrintStream
import java.nio.charset.Charset

object ConsoleEncoding {
    private val outputCharset: Charset by lazy { detectOutputCharset() }
    private val inputCharset: Charset by lazy { detectInputCharset() }

    fun configureConsole() {
        System.setOut(ConsolePrintStream(System.out, outputCharset))
        System.setErr(ConsolePrintStream(System.err, outputCharset))
    }

    fun inputCharset(): Charset {
        return inputCharset
    }

    private fun detectOutputCharset(): Charset {
        val console = System.console()
        return configuredCharset()
            ?: if (console == null) Charsets.UTF_8 else null
            ?: charsetFromProperty("sun.stdout.encoding")
            ?: console?.charset()
            ?: utf8WindowsTerminalCharset()
            ?: windowsCodePageCharset()
            ?: Charset.defaultCharset()
    }

    private fun detectInputCharset(): Charset {
        val console = System.console()
        return configuredCharset()
            ?: if (console == null) Charsets.UTF_8 else null
            ?: charsetFromProperty("sun.stdin.encoding")
            ?: console?.charset()
            ?: utf8WindowsTerminalCharset()
            ?: windowsCodePageCharset()
            ?: Charset.defaultCharset()
    }

    private fun configuredCharset(): Charset? {
        return charsetFromValue(System.getProperty("aichat.charset"))
            ?: charsetFromValue(System.getenv("AICHAT_CHARSET"))
    }

    private fun charsetFromProperty(name: String): Charset? {
        return charsetFromValue(System.getProperty(name))
    }

    private fun charsetFromValue(rawValue: String?): Charset? {
        val value = rawValue?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Charset.forName(value) }.getOrNull()
    }

    private fun windowsCodePageCharset(): Charset? {
        if (!isWindows()) return null

        val detected = runCatching {
            val output = ProcessBuilder("cmd.exe", "/c", "chcp")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
            when (val codePage = Regex("""\d+""").find(output)?.value) {
                "65001" -> Charsets.UTF_8
                null -> null
                else -> Charset.forName("cp$codePage")
            }
        }.getOrNull()

        return detected ?: charsetFromValue("cp866")
    }

    private fun utf8WindowsTerminalCharset(): Charset? {
        if (!isWindows()) return null
        return if (System.getenv("WT_SESSION").isNullOrBlank()) null else Charsets.UTF_8
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("windows", ignoreCase = true)
    }
}
