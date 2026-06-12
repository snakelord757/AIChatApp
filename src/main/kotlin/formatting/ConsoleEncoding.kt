package formatting

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
            ?: windowsUtf8Charset()
            ?: console?.charset()
            ?: Charset.defaultCharset()
    }

    private fun detectInputCharset(): Charset {
        val console = System.console()
        return configuredCharset()
            ?: if (console == null) Charsets.UTF_8 else null
            ?: charsetFromProperty("sun.stdin.encoding")
            ?: windowsUtf8Charset()
            ?: console?.charset()
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

    private fun windowsUtf8Charset(): Charset? {
        if (!isWindows()) return null
        if (!System.getenv("WT_SESSION").isNullOrBlank()) return Charsets.UTF_8
        return if (isUtf8CodePage()) Charsets.UTF_8 else null
    }

    private fun isUtf8CodePage(): Boolean {
        return runCatching {
            ProcessBuilder("cmd.exe", "/c", "chcp")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .use { reader -> reader.readText() }
                .contains("65001")
        }.getOrDefault(false)
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("windows", ignoreCase = true)
    }
}
