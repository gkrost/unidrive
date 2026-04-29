package org.krost.unidrive

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CloudItemTest {
    @Test
    fun `deleted defaults to false`() {
        val item =
            CloudItem(
                id = "1",
                name = "test.txt",
                path = "/test.txt",
                size = 100,
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = "abc",
                mimeType = "text/plain",
            )
        assertFalse(item.deleted)
    }

    @Test
    fun `deleted can be set to true`() {
        val item =
            CloudItem(
                id = "1",
                name = "test.txt",
                path = "/test.txt",
                size = 0,
                isFolder = false,
                deleted = true,
                modified = null,
                created = null,
                hash = null,
                mimeType = null,
            )
        assertTrue(item.deleted)
    }

    // ── #161: hashCode/equals consistency (data class contract) ─────────────

    @Test
    fun `equal items have equal hashCodes`() {
        val a =
            CloudItem(
                id = "1",
                name = "test.txt",
                path = "/test.txt",
                size = 100,
                isFolder = false,
                modified = Instant.EPOCH,
                created = Instant.EPOCH,
                hash = "abc",
                mimeType = "text/plain",
            )
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `items differing in any field are not equal`() {
        val base =
            CloudItem(
                id = "1",
                name = "test.txt",
                path = "/test.txt",
                size = 100,
                isFolder = false,
                modified = Instant.EPOCH,
                created = Instant.EPOCH,
                hash = "abc",
                mimeType = "text/plain",
            )
        assertNotEquals(base, base.copy(id = "2"))
        assertNotEquals(base, base.copy(name = "other.txt"))
        assertNotEquals(base, base.copy(path = "/other.txt"))
        assertNotEquals(base, base.copy(size = 999))
        assertNotEquals(base, base.copy(isFolder = true))
        assertNotEquals(base, base.copy(hash = "xyz"))
        assertNotEquals(base, base.copy(deleted = true))
    }

    @Test
    fun `hashCode is stable across calls`() {
        val item =
            CloudItem(
                id = "1",
                name = "test.txt",
                path = "/test.txt",
                size = 100,
                isFolder = false,
                modified = Instant.EPOCH,
                created = null,
                hash = null,
                mimeType = null,
            )
        val h1 = item.hashCode()
        val h2 = item.hashCode()
        assertEquals(h1, h2)
    }

    @Test
    fun `works correctly as HashMap key`() {
        val item =
            CloudItem(
                id = "1",
                name = "test.txt",
                path = "/test.txt",
                size = 100,
                isFolder = false,
                modified = Instant.EPOCH,
                created = Instant.EPOCH,
                hash = "abc",
                mimeType = "text/plain",
            )
        val map = hashMapOf(item to "value")
        assertEquals("value", map[item.copy()])
    }

    @Test
    fun `hydrated defaults to true`() {
        val item =
            CloudItem(
                id = "1",
                name = "test.txt",
                path = "/test.txt",
                size = 100,
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = "abc",
                mimeType = "text/plain",
            )
        assertTrue(item.hydrated)
    }

    @Test
    fun `hydrated can be set to false`() {
        val item =
            CloudItem(
                id = "1",
                name = "test.txt",
                path = "/test.txt",
                size = 100,
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = "abc",
                mimeType = "text/plain",
                hydrated = false,
            )
        assertFalse(item.hydrated)
    }
}
