package chat

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals

class MojibakeRepairTest {
    @Test
    fun `repairs utf8 text decoded as windows 1251`() {
        val original = "\u041e \u0447\u0451\u043c \u044f \u0441\u043f\u0440\u0430\u0448\u0438\u0432\u0430\u043b? \u041e\u0442\u0432\u0435\u0442 \u2014 \u0442\u0435\u0441\u0442."
        val mojibake = String(original.toByteArray(StandardCharsets.UTF_8), Charset.forName("windows-1251"))

        assertEquals(original, MojibakeRepair.repair(mojibake))
    }

    @Test
    fun `leaves normal text unchanged`() {
        val normal = "\u041f\u0440\u0438\u0432\u0435\u0442 -- normal text"

        assertEquals(normal, MojibakeRepair.repair(normal))
    }
}
