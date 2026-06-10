package formatting

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset

class ConsolePrintStream(
    output: OutputStream,
    private val charset: Charset
) : PrintStream(output, true, charset) {
    override fun print(value: String?) {
        super.print(sanitize(value))
    }

    override fun println(value: String?) {
        super.println(sanitize(value))
    }

    override fun append(csq: CharSequence?): PrintStream {
        print(csq?.toString())
        return this
    }

    override fun append(csq: CharSequence?, start: Int, end: Int): PrintStream {
        print(csq?.subSequence(start, end)?.toString())
        return this
    }

    override fun append(c: Char): PrintStream {
        print(c.toString())
        return this
    }

    private fun sanitize(value: String?): String? {
        return value?.let { ConsoleTextSanitizer.sanitize(it, charset) }
    }
}
