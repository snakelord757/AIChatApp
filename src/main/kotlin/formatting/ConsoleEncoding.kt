package formatting

import java.io.PrintStream
import java.nio.charset.Charset

object ConsoleEncoding {
    fun configureConsole() {
        System.setOut(PrintStream(System.out, true, outputCharset()))
        System.setErr(PrintStream(System.err, true, errorCharset()))
    }

    fun inputCharset(): Charset {
        return System.console()?.charset()
            ?: charsetFromProperty("sun.stdin.encoding")
            ?: Charset.defaultCharset()
    }

    private fun outputCharset(): Charset {
        return System.console()?.charset()
            ?: charsetFromProperty("sun.stdout.encoding")
            ?: Charset.defaultCharset()
    }

    private fun errorCharset(): Charset {
        return System.console()?.charset()
            ?: charsetFromProperty("sun.stderr.encoding")
            ?: Charset.defaultCharset()
    }

    private fun charsetFromProperty(name: String): Charset? {
        val value = System.getProperty(name)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Charset.forName(value) }.getOrNull()
    }
}
