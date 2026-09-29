package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncScopeTest {
    @Test
    fun `empty scope contains everything`() {
        assertTrue(SyncScope.contains("/anything", emptyList()))
    }

    @Test
    fun `scope matches its root and descendants only on a path boundary`() {
        val scope = listOf("/_INBOX")
        assertTrue(SyncScope.contains("/_INBOX", scope))
        assertTrue(SyncScope.contains("/_INBOX/sub/file.bin", scope))
        assertFalse(SyncScope.contains("/_INBOXX", scope))
        assertFalse(SyncScope.contains("/_INBOX-old/a.txt", scope))
        assertFalse(SyncScope.contains("/Project Notes/x", scope))
    }

    @Test
    fun `scope with several roots admits any of them`() {
        val scope = listOf("/_INBOX", "/gernot_ssh")
        assertTrue(SyncScope.contains("/gernot_ssh/keys/id", scope))
        assertTrue(SyncScope.contains("/_INBOX/a", scope))
        assertFalse(SyncScope.contains("/gernot_ssh2/x", scope))
    }

    @Test
    fun `ancestors cover every root and exclude the root itself`() {
        assertEquals(setOf("/a", "/a/b", "/x"), SyncScope.ancestors(listOf("/a/b/c", "/x/y")))
        assertEquals(emptySet(), SyncScope.ancestors(listOf("/top")))
    }

    @Test
    fun `normalizePath cleans separators and maps root to null`() {
        assertEquals("/Project Notes", SyncScope.normalizePath("\\Project Notes"))
        assertEquals("/internal/sub", SyncScope.normalizePath("//internal\\\\sub/"))
        assertEquals("/internal/sub", SyncScope.normalizePath("//internal\\\\sub/"))
        assertNull(SyncScope.normalizePath(null))
        assertNull(SyncScope.normalizePath(""))
        assertNull(SyncScope.normalizePath("/"))
    }

    @Test
    fun `normalize drops empty entries duplicates and nested roots`() {
        assertEquals(
            listOf("/a", "/b"),
            SyncScope.normalize(listOf("/a/x", "b/", "", "/a", "\\a\\y", "/b")),
        )
    }

    @Test
    fun `normalize treats a root entry as unscoped`() {
        assertEquals(emptyList(), SyncScope.normalize(listOf("/a", "/")))
        assertEquals(emptyList(), SyncScope.normalize(emptyList()))
    }

    private fun tree(vararg files: String): java.nio.file.Path {
        val root = java.nio.file.Files.createTempDirectory("scope-local")
        for (f in files) {
            val target = root.resolve(f)
            if (f.endsWith("/")) {
                java.nio.file.Files.createDirectories(target)
            } else {
                java.nio.file.Files.createDirectories(target.parent)
                java.nio.file.Files.writeString(target, "x")
            }
        }
        return root
    }

    @Test
    fun `outOfScopeLocal reports top-level entries outside the scope`() {
        val root = tree("_INBOX/x.txt", "other/y.txt", "file.txt", ".unidrive-trash/z")
        val outside = SyncScope.outOfScopeLocal(root, listOf("/_INBOX")) { it.startsWith("/.unidrive") }
        assertEquals(listOf("/file.txt", "/other"), outside)
    }

    @Test
    fun `outOfScopeLocal enters folders leading to a nested root`() {
        val root = tree("a/b/x.txt", "a/c/y.txt", "z/w.txt")
        assertEquals(listOf("/a/c", "/z"), SyncScope.outOfScopeLocal(root, listOf("/a/b")))
    }

    @Test
    fun `outOfScopeLocal is empty when unscoped or the root is missing`() {
        val root = tree("a/x.txt")
        assertEquals(emptyList(), SyncScope.outOfScopeLocal(root, emptyList()))
        assertEquals(emptyList(), SyncScope.outOfScopeLocal(root.resolve("missing"), listOf("/a")))
    }
}
