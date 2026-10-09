package org.krost.unidrive.sync

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Paths this code renders for the operator must use the OS's own separators end to
 * end: on Windows `C:\Users\me/Docs` (half backslashes from `user.home`, half
 * forward slashes from a string-concatenated join) is what `profile add` printed
 * and what the duplicate-root error echoed for any `~/…` sync root. Cloud-side
 * paths are POSIX-joined by design and are not covered here.
 */
class SyncConfigPathSeparatorTest {
    private val home = System.getenv("HOME") ?: System.getProperty("user.home")

    @Test
    fun `the duplicate-root error renders the expanded root with OS separators`() {
        val raw =
            SyncConfig.parseRaw(
                """
                [providers.a]
                type = "internxt"
                sync_root = "~/Docs"

                [providers.b]
                type = "onedrive"
                sync_root = "~/Docs"
                """.trimIndent(),
                "test.toml",
            )
        val error = SyncConfig.detectRootIsolationConflicts(raw)
        assertNotNull(error)
        assertTrue(
            error.contains(Paths.get(home, "Docs").toString()),
            "the message must echo the root as the OS writes it; got: $error",
        )
    }

    @Test
    fun `the default sync root never mixes separators`() {
        val rendered = SyncConfig.defaultSyncRoot("onedrive").toString()
        assertFalse(
            rendered.contains('/') && rendered.contains('\\'),
            "mixed separators in '$rendered'",
        )
        assertEquals(
            Paths.get(home, "OneDrive").toString(),
            rendered,
            "the default resolver applies the provider's syncRootDirName override",
        )
    }
}
