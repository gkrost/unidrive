package org.krost.unidrive.cli

import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #487: arguments outside the ANSI code page reach `main` as `?` on Windows. [WindowsArgv] reads them back from the
 * UTF-16 command line and accepts them only when they provably are what the JVM degraded. Non-ASCII characters are built
 * from code points so the source stays ASCII.
 */
class WindowsArgvTest {
    private val cp1252 = Charset.forName("windows-1252")

    private fun cps(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)

    // "Awon iwe" with A-grave, o-dot-below (U+1ECD, not in Cp1252), i-grave and e-acute (both in Cp1252)
    private val yoruba = cps(0xC0, 'w'.code, 0x1ECD, 'n'.code, ' '.code, 0xEC, 'w'.code, 0xE9)
    private val yorubaAsReceived = cps(0xC0, 'w'.code, '?'.code, 'n'.code, ' '.code, 0xEC, 'w'.code, 0xE9)

    @Test
    fun `the trailing arguments replace their degraded forms`() {
        val full = listOf("java.exe", "-Xmx512m", "-jar", "unidrive.jar", "ls", "--live", "/_INBOX/$yoruba")
        val received = arrayOf("ls", "--live", "/_INBOX/$yorubaAsReceived")
        assertContentEquals(arrayOf("ls", "--live", "/_INBOX/$yoruba"), WindowsArgv.pickTrailing(full, received, cp1252))
    }

    @Test
    fun `characters outside the BMP arrive as one question mark per UTF-16 unit`() {
        val cat = cps(0x1F431)
        val full = listOf("java.exe", "-jar", "u.jar", "a$cat")
        assertContentEquals(arrayOf("a$cat"), WindowsArgv.pickTrailing(full, arrayOf("a??"), cp1252))
    }

    @Test
    fun `a best-fit look-alike counts as a degraded character`() {
        val lStroke = cps('x'.code, 0x141) // L with stroke is not in Cp1252; Windows' best fit gives 'L'
        assertContentEquals(arrayOf(lStroke), WindowsArgv.pickTrailing(listOf("j", lStroke), arrayOf("xL"), cp1252))
    }

    @Test
    fun `a character the code page has must match exactly`() {
        // 'o' is in Cp1252: if the command line says 'o' where the JVM delivered '?', they are not the same argument
        val full = listOf("java.exe", "-jar", "u.jar", "/_INBOX/Awon")
        assertNull(WindowsArgv.pickTrailing(full, arrayOf("/_INBOX/Aw?n"), cp1252))
    }

    @Test
    fun `different lengths or too few entries keep the received arguments`() {
        assertNull(WindowsArgv.pickTrailing(listOf("java.exe", "ab"), arrayOf("a?b"), cp1252))
        assertNull(WindowsArgv.pickTrailing(listOf("x"), arrayOf("a", "b"), cp1252))
    }

    // ── #528: an argument made ONLY of unrepresentable characters arrives all-ASCII (`?`) ──

    @Test
    fun `an argument made only of emoji characters is recovered from its all-question-mark form`() {
        val folder = cps(0x1F4C1) + "folder" // U+1F4C1 is a surrogate pair → arrives as two '?'
        val full = listOf("java.exe", "-jar", "u.jar", "ls", "--live", "/dir/$folder")
        val received = arrayOf("ls", "--live", "/dir/" + "?".repeat(2) + "folder")

        assertContentEquals(arrayOf("ls", "--live", "/dir/$folder"), WindowsArgv.pickTrailing(full, received, cp1252))
    }

    @Test
    fun `an argument made only of CJK characters is recovered`() {
        val name = cps(0x65E5, 0x672C) // 日本, each outside Cp1252, one '?' each
        val full = listOf("java.exe", "-jar", "u.jar", "ls", "--live", "/zz$name")
        val received = arrayOf("ls", "--live", "/zz??")

        assertContentEquals(arrayOf("ls", "--live", "/zz$name"), WindowsArgv.pickTrailing(full, received, cp1252))
    }

    @Test
    fun `ASCII arguments are returned as they are`() {
        val args = arrayOf("ls", "--live", "/plain")
        assertContentEquals(args, WindowsArgv.recover(args))
    }

    /** The whole round trip in a child JVM: Windows only, and only on a runtime where the FFM API is final. */
    @Test
    fun `a child JVM gets its non-ASCII argument back`() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        if (Runtime.version().feature() < 22) return
        val arg = "/_INBOX/" + yoruba + cps(0x0429, 0x4E2D, 0x1F431)
        val java = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
        val process =
            ProcessBuilder(
                java,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                WindowsArgvProbe::class.java.name,
                arg,
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.US_ASCII).readText()
        process.waitFor(60, TimeUnit.SECONDS)
        val expected = arg.codePoints().toArray().joinToString(" ") { "%X".format(it) }
        assertEquals(expected, output.lines().last { it.isNotBlank() }.trim(), "child output: $output")
    }
}

/** The child of the round-trip test: prints the recovered first argument as hexadecimal code points (ASCII only). */
object WindowsArgvProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println(WindowsArgv.recover(args)[0].codePoints().toArray().joinToString(" ") { "%X".format(it) })
    }
}
