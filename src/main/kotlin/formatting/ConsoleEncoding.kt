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
            ?: console?.charset()
            ?: Charset.defaultCharset()
    }

    private fun detectInputCharset(): Charset {
        val console = System.console()
        return configuredCharset()
            ?: if (console == null) Charsets.UTF_8 else null
            ?: charsetFromProperty("sun.stdin.encoding")
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
}
