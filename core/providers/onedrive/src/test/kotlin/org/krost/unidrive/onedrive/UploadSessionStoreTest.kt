package org.krost.unidrive.onedrive

import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class UploadSessionStoreTest {
    private lateinit var storeDir: java.nio.file.Path
    private lateinit var store: UploadSessionStore

    @BeforeTest
    fun setUp() {
        storeDir = Files.createTempDirectory("upload-session-store-test")
        store = UploadSessionStore(storeDir)
    }

    @Test
    fun `get returns null for unknown path`() {
        assertNull(store.get("/unknown.bin"))
    }

    @Test
    fun `put and get round-trip`() {
        val url = "https://upload.example.com/session/abc"
        val expires = Instant.now().plusSeconds(3600)
        store.put("/file.bin", url, expires, localSize = 1L, localMtimeMillis = 1L)
        assertEquals(url, store.get("/file.bin"))
    }

    @Test
    fun `get returns null for expired session`() {
        val url = "https://upload.example.com/session/expired"
        val expired = Instant.now().minusSeconds(1)
        store.put("/old.bin", url, expired, localSize = 1L, localMtimeMillis = 1L)
        assertNull(store.get("/old.bin"))
    }

    @Test
    fun `delete removes entry`() {
        val url = "https://upload.example.com/session/del"
        store.put("/del.bin", url, Instant.now().plusSeconds(3600), localSize = 1L, localMtimeMillis = 1L)
        store.delete("/del.bin")
        assertNull(store.get("/del.bin"))
    }

    @Test
    fun `pruneExpired removes only expired entries`() {
        store.put("/keep.bin", "https://example.com/keep", Instant.now().plusSeconds(3600), localSize = 1L, localMtimeMillis = 1L)
        store.put("/drop.bin", "https://example.com/drop", Instant.now().minusSeconds(1), localSize = 1L, localMtimeMillis = 1L)
        store.pruneExpired()
        assertNotNull(store.get("/keep.bin"))
        assertNull(store.get("/drop.bin"))
    }

    @Test
    fun `missing store file returns null gracefully`() {
        val emptyDir = Files.createTempDirectory("upload-session-empty")
        val emptyStore = UploadSessionStore(emptyDir)
        assertNull(emptyStore.get("/anything.bin"))
    }

    @Test
    fun `init prunes already-expired entries from an existing store file`() {
        store.put("/keep.bin", "https://example.com/keep", Instant.now().plusSeconds(3600), localSize = 1L, localMtimeMillis = 1L)
        store.put("/drop.bin", "https://example.com/drop", Instant.now().minusSeconds(1), localSize = 1L, localMtimeMillis = 1L)
        // New instance over the same dir runs its init-time prune on construction.
        val reopened = UploadSessionStore(storeDir)
        assertNotNull(reopened.get("/keep.bin"))
        assertNull(reopened.get("/drop.bin"))
    }

    @Test
    fun `put and get carry local file identity round-trip`() {
        val url = "https://upload.example.com/session/identity"
        val expires = Instant.now().plusSeconds(3600)
        store.put("/file.bin", url, expires, localSize = 12_345L, localMtimeMillis = 1_700_000_000_000L)
        val s = store.getSession("/file.bin")
        assertNotNull(s)
        assertEquals(url, s.uploadUrl)
        assertEquals(12_345L, s.localSize)
        assertEquals(1_700_000_000_000L, s.localMtimeMillis)
    }

    @Test
    fun `old-format entry without identity fields reads as absent`() {
        // Simulate a store file written by a prior version: no localSize/localMtimeMillis keys.
        val legacy =
            """{"/legacy.bin":{"uploadUrl":"https://example.com/legacy","expiresAt":"${Instant.now().plusSeconds(3600)}"}}"""
        Files.writeString(storeDir.resolve("upload_sessions.json"), legacy)
        val reopened = UploadSessionStore(storeDir)
        assertNull(reopened.getSession("/legacy.bin"), "an entry lacking identity fields must be treated as absent")
    }

    @Test
    fun `multiple sessions coexist`() {
        val expires = Instant.now().plusSeconds(3600)
        store.put("/a.bin", "https://example.com/a", expires, localSize = 1L, localMtimeMillis = 1L)
        store.put("/b.bin", "https://example.com/b", expires, localSize = 1L, localMtimeMillis = 1L)
        assertEquals("https://example.com/a", store.get("/a.bin"))
        assertEquals("https://example.com/b", store.get("/b.bin"))
        store.delete("/a.bin")
        assertNull(store.get("/a.bin"))
        assertEquals("https://example.com/b", store.get("/b.bin"))
    }
}
