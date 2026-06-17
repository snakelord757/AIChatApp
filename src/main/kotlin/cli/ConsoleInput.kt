package cli

import formatting.ConsoleEncoding
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

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

    fun isInteractive(): Boolean = System.console() != null

    companion object {
        private fun createFallbackReader(): BufferedReader {
            val decoder = ConsoleEncoding.inputCharset()
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            return BufferedReader(InputStreamReader(System.`in`, decoder))
        }

        private fun isWindows(): Boolean {
            return System.getProperty("os.name").contains("windows", ignoreCase = true)
        }
    }
}
