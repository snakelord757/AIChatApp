package formatting

import java.io.PrintStream
import java.nio.charset.StandardCharsets

object ConsoleEncoding {
    fun configureUtf8() {
        System.setOut(PrintStream(System.out, true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(System.err, true, StandardCharsets.UTF_8))
    }
}
