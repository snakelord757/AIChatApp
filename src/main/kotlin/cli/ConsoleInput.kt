package cli

import formatting.ConsoleEncoding
import java.io.BufferedReader
import java.io.InputStreamReader

class ConsoleInput(
    private val fallbackReader: BufferedReader = createFallbackReader()
) {
    fun readLine(): String? {
        val console = System.console()
        if (console != null && !isWindows()) {
            return console.readLine()
        }

        return fallbackReader.readLine()
    }

    companion object {
        private fun createFallbackReader(): BufferedReader {
            return BufferedReader(InputStreamReader(System.`in`, ConsoleEncoding.inputCharset()))
        }

        private fun isWindows(): Boolean {
            return System.getProperty("os.name").contains("windows", ignoreCase = true)
        }
    }
}
