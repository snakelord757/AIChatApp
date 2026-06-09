package cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CliArgumentsTest {
    @Test
    fun `no arguments starts chat`() {
        assertEquals(CliCommand.Chat, CliArguments.parse(emptyArray()))
    }

    @Test
    fun `chat command starts chat`() {
        assertEquals(CliCommand.Chat, CliArguments.parse(arrayOf("chat")))
    }

    @Test
    fun `help aliases show help`() {
        assertEquals(CliCommand.Help, CliArguments.parse(arrayOf("--help")))
        assertEquals(CliCommand.Help, CliArguments.parse(arrayOf("-h")))
        assertEquals(CliCommand.Help, CliArguments.parse(arrayOf("help")))
    }

    @Test
    fun `version aliases show version`() {
        assertEquals(CliCommand.Version, CliArguments.parse(arrayOf("--version")))
        assertEquals(CliCommand.Version, CliArguments.parse(arrayOf("-V")))
        assertEquals(CliCommand.Version, CliArguments.parse(arrayOf("version")))
    }

    @Test
    fun `unknown arguments are invalid`() {
        assertIs<CliCommand.Invalid>(CliArguments.parse(arrayOf("--missing")))
    }
}
