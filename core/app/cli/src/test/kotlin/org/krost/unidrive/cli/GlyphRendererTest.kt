package org.krost.unidrive.cli

import java.nio.charset.Charset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-258 / UD-259 — verify the three-tier glyph fallback decision tree.
 *
 * We never let this test depend on the JVM's actual `Charset.defaultCharset()`
 * or process env — [GlyphRenderer] exposes internal hooks for exactly this
 * reason. Every case sets the hooks explicitly so the assertion is
 * deterministic across Linux/Windows/CI.
 *
 * UD-259 added:
 *   - Three predicates ([GlyphRenderer.isBoxDrawingSafe],
 *     [GlyphRenderer.isDingbatsSafe], [GlyphRenderer.isEmojiSafe]) gated on
 *     `os.name` + `WT_SESSION` in addition to the encoding check.
 *   - VS15 (U+FE0E) suffix on every Misc-Symbols glyph so the cell-width
 *     math stays honest in monospace tables.
 */
@Suppress("DEPRECATION")
class GlyphRendererTest {
    @BeforeTest
    fun reset() {
        GlyphRenderer.envLookup = { null }
        GlyphRenderer.propLookup = { null }
        GlyphRenderer.charsetLookup = { Charsets.UTF_8 }
    }

    @AfterTest
    fun restore() {
        GlyphRenderer.envLookup = { System.getenv(it) }
        GlyphRenderer.propLookup = { System.getProperty(it) }
        GlyphRenderer.charsetLookup = { Charset.defaultCharset() }
    }

    // ── UD-258 — UNIDRIVE_ASCII override ─────────────────────────────────────

    @Test
    fun `UNIDRIVE_ASCII=1 forces ASCII even when stdout is UTF-8`() {
        GlyphRenderer.envLookup = { if (it == "UNIDRIVE_ASCII") "1" else null }
        GlyphRenderer.propLookup = { if (it == "stdout.encoding") "UTF-8" else null }
        assertFalse(GlyphRenderer.isBoxDrawingSafe())
        assertFalse(GlyphRenderer.isDingbatsSafe())
        assertFalse(GlyphRenderer.isEmojiSafe())
        assertEquals("[OK]", GlyphRenderer.tick())
        assertEquals("[X]", GlyphRenderer.cross())
        assertEquals("[CLD]", GlyphRenderer.cloud())
        assertEquals("[CFG]", GlyphRenderer.gear())
        assertEquals("[!]", GlyphRenderer.lightning())
        assertEquals("+--", GlyphRenderer.treeBranch())
        assertEquals("+--", GlyphRenderer.treeLast())
        assertEquals("-", GlyphRenderer.boxHorizontal())
    }

    @Test
    fun `UNIDRIVE_ASCII=1 also forces ASCII on Windows Terminal`() {
        // Even when WT_SESSION is set, the env override wins.
        GlyphRenderer.envLookup = {
            when (it) {
                "UNIDRIVE_ASCII" -> "1"
                "WT_SESSION" -> "abc-123-uuid"
                else -> null
            }
        }
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Windows 11"
                else -> null
            }
        }
        assertFalse(GlyphRenderer.isBoxDrawingSafe())
        assertFalse(GlyphRenderer.isDingbatsSafe())
        assertFalse(GlyphRenderer.isEmojiSafe())
    }

    @Test
    fun `UNIDRIVE_ASCII=0 does not force ASCII`() {
        GlyphRenderer.envLookup = { if (it == "UNIDRIVE_ASCII") "0" else null }
        GlyphRenderer.propLookup = { if (it == "stdout.encoding") "UTF-8" else null }
        assertTrue(GlyphRenderer.isEmojiSafe())
    }

    @Test
    fun `UNIDRIVE_ASCII empty string does not force ASCII`() {
        GlyphRenderer.envLookup = { if (it == "UNIDRIVE_ASCII") "" else null }
        GlyphRenderer.propLookup = { if (it == "stdout.encoding") "UTF-8" else null }
        assertTrue(GlyphRenderer.isEmojiSafe())
    }

    // ── Encoding gate (Tier 1: box drawing) ─────────────────────────────────

    @Test
    fun `stdout encoding UTF-8 yields unicode glyphs`() {
        GlyphRenderer.propLookup = { if (it == "stdout.encoding") "UTF-8" else null }
        assertTrue(GlyphRenderer.isBoxDrawingSafe())
        assertTrue(GlyphRenderer.isDingbatsSafe())
        assertTrue(GlyphRenderer.isEmojiSafe())
        assertEquals("\u2714", GlyphRenderer.tick())
        assertEquals("\u2718", GlyphRenderer.cross())
        // Cloud carries VS15.
        assertEquals("\u2601\uFE0E", GlyphRenderer.cloud())
        assertEquals("\u251c\u2500", GlyphRenderer.treeBranch())
        assertEquals("\u2514\u2500", GlyphRenderer.treeLast())
    }

    @Test
    fun `stdout encoding UTF-16 also counts as unicode safe`() {
        GlyphRenderer.propLookup = { if (it == "stdout.encoding") "UTF-16LE" else null }
        assertTrue(GlyphRenderer.isBoxDrawingSafe())
        assertTrue(GlyphRenderer.isDingbatsSafe())
        assertTrue(GlyphRenderer.isEmojiSafe())
    }

    @Test
    fun `stdout encoding windows-1252 falls back to ASCII for all tiers`() {
        GlyphRenderer.propLookup = { if (it == "stdout.encoding") "windows-1252" else null }
        assertFalse(GlyphRenderer.isBoxDrawingSafe())
        assertFalse(GlyphRenderer.isDingbatsSafe())
        assertFalse(GlyphRenderer.isEmojiSafe())
        assertEquals("[OK]", GlyphRenderer.tick())
        assertEquals("[AUTH]", GlyphRenderer.authFailLabel())
        assertEquals("[ERR]", GlyphRenderer.errLabel())
        assertEquals("[--]", GlyphRenderer.inactiveLabel())
        assertEquals("[! expiring soon]", GlyphRenderer.warnLabel("expiring soon"))
    }

    @Test
    fun `stdout encoding cp1252 falls back to ASCII`() {
        // cp1252 is the active codepage alias PS 5.1 exposes for Windows-1252.
        GlyphRenderer.propLookup = { if (it == "stdout.encoding") "cp1252" else null }
        assertFalse(GlyphRenderer.isBoxDrawingSafe())
    }

    @Test
    fun `stdout encoding IBM437 falls back to ASCII`() {
        // Legacy OEM codepage on older Windows consoles.
        GlyphRenderer.propLookup = { if (it == "stdout.encoding") "IBM437" else null }
        assertFalse(GlyphRenderer.isBoxDrawingSafe())
    }

    @Test
    fun `no stdout encoding property falls back to default charset`() {
        GlyphRenderer.propLookup = { null }
        GlyphRenderer.charsetLookup = { Charsets.UTF_8 }
        assertTrue(GlyphRenderer.isBoxDrawingSafe())

        GlyphRenderer.charsetLookup = { Charsets.ISO_8859_1 }
        assertFalse(GlyphRenderer.isBoxDrawingSafe())
    }

    // ── UD-259 — Windows / WT_SESSION gating ────────────────────────────────

    @Test
    fun `Windows + WT_SESSION blank disables dingbats and emoji but keeps box drawing`() {
        // conhost.exe — UTF-8 reaches the byte stream but font fallback is
        // missing, so we must downgrade the higher tiers to ASCII.
        GlyphRenderer.envLookup = { if (it == "WT_SESSION") "" else null }
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Windows 11"
                else -> null
            }
        }
        assertTrue(GlyphRenderer.isBoxDrawingSafe())
        assertFalse(GlyphRenderer.isDingbatsSafe())
        assertFalse(GlyphRenderer.isEmojiSafe())
        assertEquals("[OK]", GlyphRenderer.tick())
        assertEquals("[CLD]", GlyphRenderer.cloud())
        // Box drawing still on.
        assertEquals("\u2500", GlyphRenderer.boxHorizontal())
        assertEquals("\u251c\u2500", GlyphRenderer.treeBranch())
    }

    @Test
    fun `Windows + WT_SESSION null disables dingbats and emoji`() {
        // env returns null instead of empty string — same effect.
        GlyphRenderer.envLookup = { null }
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Windows 10"
                else -> null
            }
        }
        assertTrue(GlyphRenderer.isBoxDrawingSafe())
        assertFalse(GlyphRenderer.isDingbatsSafe())
        assertFalse(GlyphRenderer.isEmojiSafe())
    }

    @Test
    fun `Windows + WT_SESSION set enables all three tiers`() {
        // Windows Terminal — full font fallback available.
        GlyphRenderer.envLookup = {
            if (it == "WT_SESSION") "8d4e2bc1-9a3f-4e56-b801-5e9e3a712def" else null
        }
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Windows 11"
                else -> null
            }
        }
        assertTrue(GlyphRenderer.isBoxDrawingSafe())
        assertTrue(GlyphRenderer.isDingbatsSafe())
        assertTrue(GlyphRenderer.isEmojiSafe())
        assertEquals("\u2714", GlyphRenderer.tick())
        assertEquals("\u2601\uFE0E", GlyphRenderer.cloud())
    }

    @Test
    fun `non-Windows OS keeps emoji on regardless of WT_SESSION`() {
        // Linux / macOS — every modern terminal does emoji fallback.
        GlyphRenderer.envLookup = { null }
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Linux"
                else -> null
            }
        }
        assertTrue(GlyphRenderer.isBoxDrawingSafe())
        assertTrue(GlyphRenderer.isDingbatsSafe())
        assertTrue(GlyphRenderer.isEmojiSafe())

        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Mac OS X"
                else -> null
            }
        }
        assertTrue(GlyphRenderer.isEmojiSafe())
    }

    // ── UD-259 — VS15 variation selector on Misc Symbols ─────────────────────

    @Test
    fun `cloud carries VS15 suffix when emoji-safe`() {
        // Force the emoji-safe path: UTF-8 + non-Windows.
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Linux"
                else -> null
            }
        }
        val cloud = GlyphRenderer.cloud()
        // 2 UTF-16 code units: U+2601 + U+FE0E.
        assertEquals(2, cloud.length)
        assertEquals('\u2601', cloud[0])
        assertEquals('\uFE0E', cloud[1])
    }

    @Test
    fun `warn carries VS15 suffix when emoji-safe`() {
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Linux"
                else -> null
            }
        }
        assertEquals("\u26A0\uFE0E", GlyphRenderer.warn())
    }

    @Test
    fun `gear and lightning carry VS15 suffix when emoji-safe`() {
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Linux"
                else -> null
            }
        }
        assertEquals("\u2699\uFE0E", GlyphRenderer.gear())
        assertEquals("\u26A1\uFE0E", GlyphRenderer.lightning())
    }

    @Test
    fun `warnLabel embeds the VS15-suffixed warn glyph when emoji-safe`() {
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Linux"
                else -> null
            }
        }
        assertEquals("[\u26A0\uFE0E 5d left]", GlyphRenderer.warnLabel("5d left"))
    }

    // ── Composite labels ────────────────────────────────────────────────────

    @Test
    fun `composite labels compose correctly for both modes`() {
        // Unicode (non-Windows so emoji tier is on)
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Linux"
                else -> null
            }
        }
        assertEquals("[\u2714 OK]", GlyphRenderer.okLabel())
        assertEquals("[\u2718 AUTH]", GlyphRenderer.authFailLabel())
        assertEquals("[\u2718 ERR]", GlyphRenderer.errLabel())
        assertEquals("[\u2013 \u2013]", GlyphRenderer.inactiveLabel())
        assertEquals("[\u26A0\uFE0E 5d left]", GlyphRenderer.warnLabel("5d left"))

        // ASCII (encoding fallback)
        GlyphRenderer.propLookup = { if (it == "stdout.encoding") "windows-1252" else null }
        assertEquals("[OK]", GlyphRenderer.okLabel())
        assertEquals("[AUTH]", GlyphRenderer.authFailLabel())
        assertEquals("[ERR]", GlyphRenderer.errLabel())
        assertEquals("[--]", GlyphRenderer.inactiveLabel())
        assertEquals("[! 5d left]", GlyphRenderer.warnLabel("5d left"))
    }

    // ── Backward compatibility ──────────────────────────────────────────────

    @Test
    fun `isUnicodeSafeStdout shim aliases the emoji tier`() {
        // Non-Windows UTF-8 → all tiers true.
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Linux"
                else -> null
            }
        }
        assertTrue(GlyphRenderer.isUnicodeSafeStdout())

        // Windows + no WT_SESSION → emoji tier false → shim false.
        GlyphRenderer.envLookup = { null }
        GlyphRenderer.propLookup = {
            when (it) {
                "stdout.encoding" -> "UTF-8"
                "os.name" -> "Windows 11"
                else -> null
            }
        }
        assertFalse(GlyphRenderer.isUnicodeSafeStdout())
    }
}
