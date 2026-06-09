package formatting

import java.io.PrintStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object ConsoleEncoding {
    fun configureConsole() {
        enableWindowsUtf8Console()
        val charset = preferredCharset()
        System.setOut(PrintStream(System.out, true, charset))
        System.setErr(PrintStream(System.err, true, charset))
    }

    fun inputCharset(): Charset {
        if (isWindows()) return StandardCharsets.UTF_8

        return System.console()?.charset()
            ?: charsetFromProperty("sun.stdin.encoding")
            ?: Charset.defaultCharset()
    }

    private fun preferredCharset(): Charset {
        if (isWindows()) return StandardCharsets.UTF_8

        return System.console()?.charset()
            ?: charsetFromProperty("sun.stdout.encoding")
            ?: Charset.defaultCharset()
    }

    private fun charsetFromProperty(name: String): Charset? {
        val value = System.getProperty(name)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Charset.forName(value) }.getOrNull()
    }

    private fun enableWindowsUtf8Console() {
        if (!isWindows()) return

        runCatching {
            ProcessBuilder("cmd.exe", "/c", "chcp 65001 > nul")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor()
        }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("windows", ignoreCase = true)
    }
}
