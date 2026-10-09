package org.krost.unidrive.engine

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The OS file-name rules both front-ends refuse a name with (#230, #526). Moved here from
 * :app:sync's PlaceholderManagerTest by #560 U6 (the mount resolves them through engine-core now).
 */
class LocalNamesTest {
    private val w = listOf(true, false)

    @Test
    fun `localNameIssue allows normal names including spaces and dot segments`() {
        for (windows in w) {
            assertNull(localNameIssue("/Documents/my report.txt", windows = windows))
            assertNull(localNameIssue("/a/b/c.pdf", windows = windows))
            assertNull(localNameIssue("/", windows = windows))
            // `.` / `..` are path navigation, not all-dots names.
            assertNull(localNameIssue("/a/./b", windows = windows))
            assertNull(localNameIssue("/a/../b", windows = windows))
        }
    }

    @Test
    fun `localNameIssue rejects Windows-invalid names only on Windows`() {
        val bad = listOf("/x/....", "/x/foo.", "/x/foo ", "/x/CON", "/x/nul.txt", "/x/a:b", "/x/a?b", "/x/a<b")
        for (p in bad) {
            assertNotNull(localNameIssue(p, windows = true), "expected '$p' rejected on Windows")
            assertNull(localNameIssue(p, windows = false), "expected '$p' allowed on POSIX")
        }
    }

    @Test
    fun `localNameIssue rejects an embedded NUL on every platform`() {
        val withNul = "/x/a" + Char(0) + "b"
        assertNotNull(localNameIssue(withNul, windows = true))
        assertNotNull(localNameIssue(withNul, windows = false))
    }
}
