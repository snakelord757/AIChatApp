package formatting

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains

class ConsoleScreenTest {
    @Test
    fun `clear emits ansi scrollback reset when ansi is enabled`() {
        val originalOut = System.out
        val stream = ByteArrayOutputStream()
        System.setOut(PrintStream(stream, true, Charsets.UTF_8))

        try {
            ConsoleScreen.clear()
        } finally {
            System.setOut(originalOut)
        }

        assertContains(stream.toString(Charsets.UTF_8), "\u001B[3J")
    }
}
