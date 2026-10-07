package org.krost.unidrive.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #361: a Windows path with backslashes in a double-quoted (TOML basic) string is an invalid
 * escape. These tests drive the real ktoml reader, no mock: the configs are parsed from text.
 * The backslash is built from its char code so the test source holds no escape sequences.
 */
class SyncConfigBackslashTest {
    private val bs = Char(92).toString()
    private val dq = '"'.toString()
    private val sq = "'"

    private fun config(syncRootValue: String): String =
        """
        |[general]
        |
        |[providers.my_inxt]
        |type = "localfs"
        |sync_root = $syncRootValue
        """.trimMargin()

    private val windowsPath = "C:${bs}Users${bs}me${bs}Drive"

    @Test
    fun `a backslash path in a double-quoted string fails with an error naming key line and fix`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                SyncConfig.parseRaw(config("$dq$windowsPath$dq"))
            }
        assertFalse(e is NumberFormatException, "must not leak the raw NumberFormatException: ${e.message}")
        val msg = e.message.orEmpty()
        assertTrue("config.toml" in msg, "must name the file: $msg")
        assertTrue("sync_root" in msg, "must name the key: $msg")
        assertTrue("line 5" in msg, "must name the line: $msg")
        assertTrue("${bs}U" in msg, "must show the offending escape: $msg")
        assertTrue("forward slashes" in msg, "must offer the forward-slash fix: $msg")
        assertTrue("literal string" in msg, "must offer the literal-string fix: $msg")
    }

    @Test
    fun `load names the config file by its path`() {
        val dir = Files.createTempDirectory("unidrive-config-backslash")
        try {
            val file = dir.resolve("config.toml")
            Files.writeString(file, config("$dq$windowsPath$dq"))
            val e = assertFailsWith<IllegalArgumentException> { SyncConfig.load(file) }
            assertTrue(file.toString() in e.message.orEmpty(), "must name the file path: ${e.message}")
            assertTrue("sync_root" in e.message.orEmpty(), "must name the key: ${e.message}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a single-quoted literal string keeps the backslashes`() {
        val raw = SyncConfig.parseRaw(config("$sq$windowsPath$sq"))
        assertEquals(windowsPath, raw.providers["my_inxt"]?.sync_root)
    }

    @Test
    fun `doubled backslashes in a double-quoted string read back as single backslashes`() {
        val doubled = windowsPath.replace(bs, bs + bs)
        val raw = SyncConfig.parseRaw(config("$dq$doubled$dq"))
        assertEquals(windowsPath, raw.providers["my_inxt"]?.sync_root)
    }

    @Test
    fun `forward slashes work on any platform`() {
        val raw = SyncConfig.parseRaw(config("${dq}C:/Users/me/Drive$dq"))
        assertEquals("C:/Users/me/Drive", raw.providers["my_inxt"]?.sync_root)
    }

    @Test
    fun `profile setup writes a Windows sync_root that reads back unchanged`() {
        // The writer funnels through escapeTomlValue, so profile creation cannot emit a config
        // that this reader rejects (the round-trip question raised in the issue).
        val section = generateProfileToml("localfs", "my_inxt", windowsPath, emptyMap(), ProfileMode.MIRROR)
        val raw = SyncConfig.parseRaw(section)
        assertEquals(windowsPath, raw.providers["my_inxt"]?.sync_root)
    }

    @Test
    fun `the escape scan flags only escapes TOML does not define`() {
        val valid = listOf("${bs}b${bs}t${bs}n${bs}f${bs}r${bs}$dq${bs}$bs", "${bs}u00e9", "${bs}U0001F600")
        for (v in valid) {
            assertEquals(null, findInvalidTomlEscape("k = $dq$v$dq"), "valid escape flagged: $v")
        }
        assertEquals(null, findInvalidTomlEscape("k = $sq$windowsPath$sq"), "a literal string has no escapes")
        assertEquals(null, findInvalidTomlEscape("k = ${dq}ok$dq # C:${bs}Users in a comment"))
        assertEquals("${bs}U", findInvalidTomlEscape("k = ${dq}C:${bs}Users$dq")?.sequence, "a short unicode escape is invalid")
        assertEquals("${bs}P", findInvalidTomlEscape("k = ${dq}C:${bs}Program Files$dq")?.sequence)
    }

    @Test
    fun `the escape scan names the key and line of an array element`() {
        val found = findInvalidTomlEscape("[general]\nexclude_patterns = [${dq}ok$dq, ${dq}D:${bs}Data$dq]\n")
        assertEquals(InvalidTomlEscape(lineNumber = 2, key = "exclude_patterns", sequence = "${bs}D"), found)
    }

    @Test
    fun `a valid config is not touched by the escape diagnosis`() {
        // The scan only runs after a decode failure; a failure with no bad escape keeps its cause.
        val e = assertFailsWith<Exception> { SyncConfig.parseRaw("[general]\npoll_interval = \"not-a-number\"\n") }
        assertFalse("invalid escape" in e.message.orEmpty(), "unrelated failure must not be blamed on escapes: ${e.message}")
    }
}
