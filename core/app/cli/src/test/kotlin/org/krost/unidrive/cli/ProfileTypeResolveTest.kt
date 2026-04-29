package org.krost.unidrive.cli

import org.krost.unidrive.sync.SyncConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UD-237: when `-p <value>` does not match a profile name but DOES match a known
 * provider TYPE (case-insensitive), and exactly one profile of that type is
 * configured, [tryResolveByType] auto-selects it. Zero matches and >1 matches
 * both fall through to the existing error path; the caller adds a typed-list
 * hint when there are multiple.
 *
 * The CLI integration glue lives in [Main.resolveCurrentProfile]; we test the
 * pure helpers here so we don't need to trap System.exit or fork a subprocess.
 */
class ProfileTypeResolveTest {
    private fun parse(toml: String) = SyncConfig.parseRaw(toml)

    // ── exact name (case-sensitive) — current behaviour, unchanged ────────────

    @Test
    fun `exact profile name resolves directly via SyncConfig (no type fallback needed)`() {
        val raw =
            parse(
                """
                |[providers.krost]
                |type = "internxt"
                """.trimMargin(),
            )
        val info = SyncConfig.resolveProfile("krost", raw)
        assertEquals("krost", info.name)
        assertEquals("internxt", info.type)
    }

    @Test
    fun `profile name lookup is case-sensitive — capitalised query falls to the type fallback`() {
        val raw =
            parse(
                """
                |[providers.krost]
                |type = "internxt"
                """.trimMargin(),
            )
        // "Krost" is neither a profile name (case-sensitive miss) nor a known type:
        assertNull(tryResolveByType("Krost", raw), "'Krost' is not a known type")
    }

    // ── auto-resolve: exactly one profile of that type ────────────────────────

    @Test
    fun `auto-resolves -p TYPE when exactly one profile of that type exists`() {
        val raw =
            parse(
                """
                |[providers.krost]
                |type = "internxt"
                |
                |[providers.work]
                |type = "onedrive"
                """.trimMargin(),
            )
        val resolved = tryResolveByType("internxt", raw)
        assertEquals("krost", resolved?.name)
        assertEquals("internxt", resolved?.type)
    }

    @Test
    fun `auto-resolve is case-insensitive on the type query`() {
        val raw =
            parse(
                """
                |[providers.krost]
                |type = "internxt"
                """.trimMargin(),
            )
        val resolved = tryResolveByType("Internxt", raw)
        assertEquals("krost", resolved?.name)
    }

    @Test
    fun `auto-resolve infers type from profile name when type field is omitted`() {
        // SyncConfig.resolveProfile defaults the type to the profile name when the
        // [providers.<name>] section omits `type = "..."`. profilesOfType mirrors
        // that, so a profile literally named "sftp" counts as one of type "sftp".
        val raw =
            parse(
                """
                |[providers.sftp]
                |host = "example.com"
                """.trimMargin(),
            )
        val resolved = tryResolveByType("sftp", raw)
        assertEquals("sftp", resolved?.name)
    }

    // ── zero matches: type known, no profiles of that type ────────────────────

    @Test
    fun `tryResolveByType returns null for known type with zero profiles`() {
        val raw =
            parse(
                """
                |[providers.krost]
                |type = "internxt"
                """.trimMargin(),
            )
        val resolved = tryResolveByType("onedrive", raw)
        assertNull(resolved, "no onedrive profile configured → null, caller errors as today")
    }

    @Test
    fun `tryResolveByType returns null for unknown type string`() {
        val raw =
            parse(
                """
                |[providers.krost]
                |type = "internxt"
                """.trimMargin(),
            )
        assertNull(tryResolveByType("not-a-real-type", raw))
    }

    // ── multiple matches: hint helper enumerates them ─────────────────────────

    @Test
    fun `tryResolveByType returns null when multiple profiles of that type exist`() {
        val raw =
            parse(
                """
                |[providers.alpha]
                |type = "sftp"
                |
                |[providers.beta]
                |type = "sftp"
                |
                |[providers.gamma]
                |type = "sftp"
                """.trimMargin(),
            )
        val resolved = tryResolveByType("sftp", raw)
        assertNull(resolved, "ambiguous → null, caller errors with the type-list hint")
    }

    @Test
    fun `profilesOfType lists every name of the matched type for the multi-match hint`() {
        val raw =
            parse(
                """
                |[providers.alpha]
                |type = "sftp"
                |
                |[providers.beta]
                |type = "sftp"
                |
                |[providers.work]
                |type = "onedrive"
                """.trimMargin(),
            )
        val names = profilesOfType("sftp", raw)
        assertEquals(2, names.size)
        assertTrue("alpha" in names)
        assertTrue("beta" in names)
    }

    @Test
    fun `profilesOfType is case-insensitive on the query`() {
        val raw =
            parse(
                """
                |[providers.x]
                |type = "internxt"
                """.trimMargin(),
            )
        assertEquals(listOf("x"), profilesOfType("INTERNXT", raw))
        assertEquals(listOf("x"), profilesOfType("Internxt", raw))
    }
}
