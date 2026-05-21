package org.krost.unidrive

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
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

    // UD-810 audit: deleted four data-class-contract tests
    // (`equal items have equal hashCodes`, `items differing in any field are
    // not equal`, `hashCode is stable across calls`, `works correctly as
    // HashMap key`). All four exercised Kotlin's generated equals/hashCode
    // on `data class CloudItem` — i.e., they tested the compiler, not a
    // domain invariant. If `data class` ever becomes a regular `class`, the
    // affected sync engine code paths (HashMap keys in `Reconciler`,
    // `Set<CloudItem>` in the delta walker) would fail loudly; no runtime
    // test would catch a regression the type-system wouldn't catch first.
    // The defaults tests (`deleted defaults to false`, `hydrated defaults
    // to true`) remain below — those pin business invariants. See CHANGELOG.

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
