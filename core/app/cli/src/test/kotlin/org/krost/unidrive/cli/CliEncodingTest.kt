package org.krost.unidrive.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun `a configured value the JVM rejected falls back to the current charset`() {
        val current = Charset.forName("windows-1252")
        // Space makes this an illegal charset NAME (IllegalCharsetNameException,
        // before UnsupportedCharsetException even gets a chance).
        assertEquals(current, CliEncoding.charsetFor(attachedToConsole = false, configured = "NOT A CHARSET", current))
    }

    @Test
    fun `a flag the JVM was not started with is not reported as explicit`() {
        // The property map defines stdout.encoding on every JVM (JDK 19+), so
        // only the command-line check can tell "user asked" from "JVM guessed";
        // this test worker is not started with such a flag.
        assertNull(CliEncoding.explicitProperty("stdout.encoding.explicitly.unset"))
    }

    /**
     * The bare `java -jar` shape from #391: the test JVM's output is redirected
     * and no encoding flag was passed, yet the JVM still defines
     * stdout.encoding itself (Cp1252 on Windows). [CliEncoding.apply] must
     * ignore that JVM-guessed value and rebind to UTF-8.
     */
    @Test
    fun `apply rebinds redirected output to UTF-8 even though the JVM predefines the property`() {
        // Guard the premise: if the test JVM ever starts carrying an explicit
        // encoding flag, this test no longer exercises the bare `java -jar`
        // shape and must say so instead of passing vacuously.
        assertNull(CliEncoding.explicitProperty("stdout.encoding"))
        val originalOut = System.out
        val originalErr = System.err
        try {
            CliEncoding.apply()
            assertEquals(StandardCharsets.UTF_8, System.out.charset())
            assertEquals(StandardCharsets.UTF_8, System.err.charset())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
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
            val reporter = CliProgressReporter(dryRun = true)
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
