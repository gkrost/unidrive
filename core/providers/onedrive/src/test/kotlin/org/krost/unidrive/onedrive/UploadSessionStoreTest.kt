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
        store.put("/file.bin", url, expires)
        assertEquals(url, store.get("/file.bin"))
    }

    @Test
    fun `get returns null for expired session`() {
        val url = "https://upload.example.com/session/expired"
        val expired = Instant.now().minusSeconds(1)
        store.put("/old.bin", url, expired)
        assertNull(store.get("/old.bin"))
    }

    @Test
    fun `delete removes entry`() {
        val url = "https://upload.example.com/session/del"
        store.put("/del.bin", url, Instant.now().plusSeconds(3600))
        store.delete("/del.bin")
        assertNull(store.get("/del.bin"))
    }

    @Test
    fun `pruneExpired removes only expired entries`() {
        store.put("/keep.bin", "https://example.com/keep", Instant.now().plusSeconds(3600))
        store.put("/drop.bin", "https://example.com/drop", Instant.now().minusSeconds(1))
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
    fun `multiple sessions coexist`() {
        val expires = Instant.now().plusSeconds(3600)
        store.put("/a.bin", "https://example.com/a", expires)
        store.put("/b.bin", "https://example.com/b", expires)
        assertEquals("https://example.com/a", store.get("/a.bin"))
        assertEquals("https://example.com/b", store.get("/b.bin"))
        store.delete("/a.bin")
        assertNull(store.get("/a.bin"))
        assertEquals("https://example.com/b", store.get("/b.bin"))
    }
}
