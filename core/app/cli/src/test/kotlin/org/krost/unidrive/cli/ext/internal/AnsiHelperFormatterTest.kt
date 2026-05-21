package org.krost.unidrive.cli.ext.internal

import org.junit.After
import org.junit.Test
import org.krost.unidrive.cli.AnsiHelper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnsiHelperFormatterTest {
    private val originalEnv = AnsiHelper.envLookup
    private val originalConsole = AnsiHelper.consolePresent

    @After
    fun tearDown() {
        AnsiHelper.envLookup = originalEnv
        AnsiHelper.consolePresent = originalConsole
    }

    @Test
    fun `bold wraps text when ANSI supported`() {
        AnsiHelper.envLookup = {
            if (it == "NO_COLOR") {
                null
            } else if (it == "TERM") {
                "xterm"
            } else {
                null
            }
        }
        AnsiHelper.consolePresent = { true }
        val f = AnsiHelperFormatter()
        assertEquals("\u001b[1mhello\u001b[0m", f.bold("hello"))
    }

    @Test
    fun `bold passes through when NO_COLOR set`() {
        AnsiHelper.envLookup = { if (it == "NO_COLOR") "1" else null }
        AnsiHelper.consolePresent = { true }
        val f = AnsiHelperFormatter()
        assertEquals("hello", f.bold("hello"))
    }

    @Test
    fun `dim and underline delegate through same ANSI gate`() {
        AnsiHelper.envLookup = {
            if (it == "NO_COLOR") {
                null
            } else if (it == "TERM") {
                "xterm"
            } else {
                null
            }
        }
        AnsiHelper.consolePresent = { true }
        val f = AnsiHelperFormatter()
        assertTrue(f.dim("x").contains("\u001b["))
        assertTrue(f.underline("x").contains("\u001b["))
    }
}
