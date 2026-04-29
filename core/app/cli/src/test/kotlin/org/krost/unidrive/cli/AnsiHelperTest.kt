package org.krost.unidrive.cli

import kotlin.test.*

class AnsiHelperTest {
    @BeforeTest
    fun reset() {
        AnsiHelper.consolePresent = { true }
        AnsiHelper.envLookup = { null }
    }

    @AfterTest
    fun restore() {
        AnsiHelper.consolePresent = { System.console() != null }
        AnsiHelper.envLookup = { System.getenv(it) }
    }

    @Test
    fun `NO_COLOR set disables ANSI`() {
        AnsiHelper.envLookup = { key -> if (key == "NO_COLOR") "1" else "xterm-256color" }
        assertFalse(AnsiHelper.isAnsiSupported())
        assertEquals("hello", AnsiHelper.bold("hello"))
    }

    @Test
    fun `NO_COLOR empty string disables ANSI`() {
        AnsiHelper.envLookup = { key -> if (key == "NO_COLOR") "" else "xterm-256color" }
        assertFalse(AnsiHelper.isAnsiSupported())
    }

    @Test
    fun `TERM dumb disables ANSI`() {
        AnsiHelper.envLookup = { key -> if (key == "TERM") "dumb" else null }
        assertFalse(AnsiHelper.isAnsiSupported())
        assertEquals("hello", AnsiHelper.green("hello"))
    }

    @Test
    fun `ANSI supported when console present and TERM set`() {
        AnsiHelper.envLookup = { key -> if (key == "TERM") "xterm-256color" else null }
        assertTrue(AnsiHelper.isAnsiSupported())
        assertTrue(AnsiHelper.bold("hello").contains("\u001b["))
    }

    @Test
    fun `no console disables ANSI`() {
        AnsiHelper.consolePresent = { false }
        AnsiHelper.envLookup = { key -> if (key == "TERM") "xterm-256color" else null }
        assertFalse(AnsiHelper.isAnsiSupported())
    }

    @Test
    fun `color functions return raw text when disabled`() {
        AnsiHelper.envLookup = { key -> if (key == "NO_COLOR") "" else null }
        assertEquals("test", AnsiHelper.bold("test"))
        assertEquals("test", AnsiHelper.dim("test"))
        assertEquals("test", AnsiHelper.green("test"))
        assertEquals("test", AnsiHelper.yellow("test"))
        assertEquals("test", AnsiHelper.red("test"))
        assertEquals("", AnsiHelper.reset())
    }
}
