package formatting

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleTextSanitizerTest {
    @Test
    fun `replaces unsupported punctuation without touching ansi formatting`() {
        val input = "\u001B[1m\u041f\u0440\u0438\u0432\u0435\u0442 \u2014 \u0442\u0435\u0441\u0442 \uD83D\uDC4B\u001B[0m"

        assertEquals(
            "\u001B[1m\u041f\u0440\u0438\u0432\u0435\u0442 - \u0442\u0435\u0441\u0442 \u001B[0m",
            ConsoleTextSanitizer.sanitize(input, Charset.forName("cp866"))
        )
    }

    @Test
    fun `keeps utf8 output unchanged`() {
        val input = "\u041f\u0440\u0438\u0432\u0435\u0442 \u2014 \u0442\u0435\u0441\u0442 \uD83D\uDC4B"

        assertEquals(input, ConsoleTextSanitizer.sanitize(input, Charsets.UTF_8))
    }

    @Test
    fun `sanitized legacy console output does not contain replacement question marks`() {
        val input = "\u041f\u0440\u0438\u0432\u0435\u0442! \uD83D\uDC4B \u042f \u2014 \u043f\u043e\u043c\u043e\u0449\u043d\u0438\u043a."

        assertEquals(
            "\u041f\u0440\u0438\u0432\u0435\u0442!  \u042f - \u043f\u043e\u043c\u043e\u0449\u043d\u0438\u043a.",
            ConsoleTextSanitizer.sanitize(input, Charset.forName("cp866"))
        )
    }
}
