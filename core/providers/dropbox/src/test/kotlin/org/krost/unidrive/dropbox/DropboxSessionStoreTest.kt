package org.krost.unidrive.dropbox

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #867: the session store's own rules — the identity binding (size, mtime), the 7-day life, and that
 * the file sits inside the (owner-only) profile folder and carries no token.
 */
class DropboxSessionStoreTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("dropbox-session-store")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a stored session comes back with its offset and is replaced by a newer one`() {
        val store = DropboxSessionStore(dir)
        store.put("/big.bin", "s1", 4_000, 10, 1_000)
        assertEquals("s1", store.get("/big.bin", 10, 1_000)?.sessionId)
        assertEquals(4_000, store.get("/big.bin", 10, 1_000)?.committedOffset)

        store.put("/big.bin", "s2", 0, 10, 1_000)
        assertEquals("s2", store.get("/big.bin", 10, 1_000)?.sessionId)
    }

    @Test
    fun `a changed local file invalidates the stored session`() {
        val store = DropboxSessionStore(dir)
        store.put("/big.bin", "s1", 4_000, 10, 1_000)
        assertNull(store.get("/big.bin", 12, 1_000), "a different size")
        assertNull(store.get("/big.bin", 10, 2_000), "a different mtime")
    }

    @Test
    fun `a session older than seven days is dropped and pruning removes it from the file`() {
        val store = DropboxSessionStore(dir)
        store.put("/big.bin", "s1", 4_000, 10, 1_000)
        // Age the record past the TTL by writing it back with an old creation instant.
        val file = dir.resolve("upload_sessions.json")
        file.writeText(file.readText().replace(Regex("\"createdAt\":\"[^\"]+\""), "\"createdAt\":\"2020-01-01T00:00:00Z\""))

        assertNull(store.get("/big.bin", 10, 1_000), "an expired session is not handed out")
        assertEquals("{}", Files.readString(file).trim(), "pruning removed it from the store")

        store.put("/big.bin", "s1", 4_000, 10, 1_000)
        file.writeText(file.readText().replace(Regex("\"createdAt\":\"[^\"]+\""), "\"createdAt\":\"2020-01-01T00:00:00Z\""))
        val fresh = DropboxSessionStore(dir)
        assertNull(fresh.get("/big.bin", 10, 1_000), "a fresh store prunes on construction")
    }

    @Test
    fun `the store lives in the profile folder and carries no token`() {
        val store = DropboxSessionStore(dir)
        store.put("/big.bin", "s1", 4_000, 10, 1_000)
        val file = dir.resolve("upload_sessions.json")
        assertTrue(Files.exists(file), "the store file sits in the profile folder (tightened owner-only at daemon start)")
        val text = Files.readString(file)
        assertTrue("token" !in text.lowercase() && "secret" !in text.lowercase(), "no token in the store: $text")
    }
}
