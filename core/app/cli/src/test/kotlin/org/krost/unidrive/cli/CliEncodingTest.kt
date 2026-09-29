package org.krost.unidrive.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliEncodingTest {
    // The umlauts + ß from the #391 report; box-drawing is what the legacy
    // console code pages could not encode at all.
    private val name = "/_INBOX/Köln – Karten Stadt Köln – ä ö ü ß"

    @Test
    fun `redirected output without a configured charset is UTF-8`() {
        assertEquals(
            StandardCharsets.UTF_8,
            CliEncoding.charsetFor(attachedToConsole = false, configured = null, current = Charset.defaultCharset()),
        )
    }

    @Test
    fun `an attached console keeps the JVM's console charset`() {
        val console = Charset.forName("windows-1252")
        assertEquals(
            console,
            CliEncoding.charsetFor(attachedToConsole = true, configured = null, current = console),
        )
    }

    @Test
    fun `the launcher's configured property wins`() {
        assertEquals(
            StandardCharsets.UTF_8,
            CliEncoding.charsetFor(attachedToConsole = true, configured = "UTF-8", current = Charset.defaultCharset()),
        )
    }

    /**
     * The #391 acceptance print: a plan line with non-ASCII names through the
     * progress reporter, captured into a buffer with the chosen charset, must
     * decode byte-exact.
     */
    @Test
    fun `plan lines round-trip umlauts through the chosen charset`() {
        val buffer = ByteArrayOutputStream()
        val chosen = CliEncoding.charsetFor(attachedToConsole = false, configured = null, current = Charset.defaultCharset())
        val originalOut = System.out
        System.setOut(PrintStream(buffer, true, chosen))
        try {
            val reporter = CliProgressReporter(verbose = false, dryRun = true)
            reporter.onActionCount(1558, preFilterTotal = 1558, filterReason = null)
            reporter.onActionProgress(5, 1558, "mkdir", name)
        } finally {
            System.setOut(originalOut)
        }
        val captured = buffer.toString(StandardCharsets.UTF_8)
        assertTrue(
            captured.contains("[5/1558] mkdir $name"),
            "plan line must round-trip non-ASCII names exactly; was: $captured",
        )
    }
}
