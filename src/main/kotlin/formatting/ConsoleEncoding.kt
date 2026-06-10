package formatting

import java.io.PrintStream
import java.nio.charset.Charset

object ConsoleEncoding {
    private val consoleCharset: Charset by lazy { detectConsoleCharset() }

    fun configureConsole() {
        System.setOut(PrintStream(System.out, true, consoleCharset))
        System.setErr(PrintStream(System.err, true, consoleCharset))
    }

    fun inputCharset(): Charset {
        return consoleCharset
    }

    private fun detectConsoleCharset(): Charset {
        return configuredCharset()
            ?: charsetFromProperty("sun.stdout.encoding")
            ?: System.console()?.charset()
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

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("windows", ignoreCase = true)
    }
}
