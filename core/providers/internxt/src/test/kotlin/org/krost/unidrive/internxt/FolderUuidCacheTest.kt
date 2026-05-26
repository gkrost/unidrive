package org.krost.unidrive.internxt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * UD-357: unit coverage for the (parentUuid, sanitizedName) -> uuid cache
 * that masks Internxt's read-after-write inconsistency window between
 * `POST /drive/folders` and `GET /drive/folders/{parentUuid}`.
 *
 * The cache is wired into [InternxtProvider.createFolder] (populates on
 * create-success) and [InternxtProvider.resolveFolder] (consults before
 * the listing call, populates on list-and-find). Unit-tested here at the
 * data-structure layer; the provider integration is mechanical.
 */
class FolderUuidCacheTest {
    @Test
    fun `get returns null on miss`() {
        val cache = FolderUuidCache()
        assertNull(cache.get("any-parent", "any-name"))
    }

    @Test
    fun `put then get returns the uuid`() {
        val cache = FolderUuidCache()
        cache.put("parent-1", "Project Notes", "uuid-A")
        assertEquals("uuid-A", cache.get("parent-1", "Project Notes"))
    }

    @Test
    fun `entries are keyed by both parent and name`() {
        // Two folders named "shared" under different parents must NOT collide.
        val cache = FolderUuidCache()
        cache.put("parent-A", "shared", "uuid-A-shared")
        cache.put("parent-B", "shared", "uuid-B-shared")
        assertEquals("uuid-A-shared", cache.get("parent-A", "shared"))
        assertEquals("uuid-B-shared", cache.get("parent-B", "shared"))
    }

    @Test
    fun `entries are keyed by name within the same parent`() {
        // Two folders under the same parent with different names must not collide.
        val cache = FolderUuidCache()
        cache.put("parent-1", "Subfolder1", "uuid-eigene")
        cache.put("parent-1", "Subfolder2", "uuid-fremde")
        assertEquals("uuid-eigene", cache.get("parent-1", "Subfolder1"))
        assertEquals("uuid-fremde", cache.get("parent-1", "Subfolder2"))
    }

    @Test
    fun `put on existing key overwrites`() {
        val cache = FolderUuidCache()
        cache.put("parent-1", "name", "uuid-old")
        cache.put("parent-1", "name", "uuid-new")
        assertEquals("uuid-new", cache.get("parent-1", "name"))
    }

    @Test
    fun `non-ASCII names are first-class keys`() {
        // The user's repro had "Project Notes" and "SubfolderS -
        // SubfolderL". Pin Unicode passthrough.
        val cache = FolderUuidCache()
        cache.put("root", "Notes", "uuid-1")
        cache.put("root", "SubfolderS", "uuid-2")
        assertEquals("uuid-1", cache.get("root", "Notes"))
        assertEquals("uuid-2", cache.get("root", "SubfolderS"))
    }

    @Test
    fun `invalidate drops a single stale entry leaving others intact`() {
        // Guards: folderCache.invalidate(parentUuid, name) must remove exactly the
        // targeted entry so a subsequent get returns null (self-heal on observed 404).
        // Other entries under the same parent must not be affected.
        val cache = FolderUuidCache()
        cache.put("parent-1", "stale-folder", "uuid-stale")
        cache.put("parent-1", "live-folder", "uuid-live")

        cache.invalidate("parent-1", "stale-folder")

        assertNull(cache.get("parent-1", "stale-folder"), "invalidated entry must return null")
        assertEquals("uuid-live", cache.get("parent-1", "live-folder"), "sibling entry must be unaffected")
    }

    @Test
    fun `size and clear support the test infrastructure`() {
        val cache = FolderUuidCache()
        assertEquals(0, cache.size())
        cache.put("p", "a", "u1")
        cache.put("p", "b", "u2")
        assertEquals(2, cache.size())
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("p", "a"))
    }
}
