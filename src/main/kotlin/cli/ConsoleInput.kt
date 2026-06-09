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
        if (console != null) {
            return console.readLine()
        }

        return fallbackReader.readLine()
    }

    companion object {
        private fun createFallbackReader(): BufferedReader {
            val decoder = ConsoleEncoding.inputCharset()
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            return BufferedReader(InputStreamReader(System.`in`, decoder))
        }
    }
}
