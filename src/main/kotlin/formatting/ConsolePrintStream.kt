package formatting

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset

class ConsolePrintStream(
    output: OutputStream,
    charset: Charset
) : PrintStream(output, true, charset)
