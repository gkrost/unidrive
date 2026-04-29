package org.krost.unidrive.webdav

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the MKCOL cache in WebDavApiService.
 *
 * The cache short-circuits ensureParentCollections when an ancestor is already
 * known to exist. Tests here pre-populate knownCollections directly and assert
 * that subsequent ensureParentCollections / mkcol calls skip the network.
 *
 * We use an unreachable URL on purpose: if the cache is consulted as designed,
 * no HTTP is attempted; any regression that bypasses the cache will hang on
 * the 30 s connect timeout and be caught.
 */
class WebDavApiServiceMkcolCacheTest {
    private fun service() =
        WebDavApiService(
            WebDavConfig(
                baseUrl = "https://dav.invalid.example.test/webdav",
                username = "alice",
                password = "secret",
                tokenPath = Files.createTempDirectory("webdav-mkcol-cache-test"),
            ),
        )

    // -- normalizeCollectionKey -------------------------------------------------

    @Test
    fun `normalizeCollectionKey strips leading and trailing slashes`() {
        val svc = service()
        assertEquals("/a/b/c", svc.normalizeCollectionKey("/a/b/c"))
        assertEquals("/a/b/c", svc.normalizeCollectionKey("a/b/c"))
        assertEquals("/a/b/c", svc.normalizeCollectionKey("/a/b/c/"))
        assertEquals("/a/b/c", svc.normalizeCollectionKey("a/b/c/"))
    }

    @Test
    fun `normalizeCollectionKey treats equivalent inputs as one key`() {
        val svc = service()
        val set = mutableSetOf<String>()
        set.add(svc.normalizeCollectionKey("/Pictures/Holiday"))
        set.add(svc.normalizeCollectionKey("Pictures/Holiday"))
        set.add(svc.normalizeCollectionKey("/Pictures/Holiday/"))
        assertEquals(1, set.size, "all three equivalent forms must collapse to one key")
    }

    // -- cache short-circuit ----------------------------------------------------

    @Test
    fun `ensureParentCollections returns immediately when deepest parent is cached`() =
        runTest {
            val svc = service()
            svc.knownCollections.add("/Pictures/Holiday/2024")
            // Uploading /Pictures/Holiday/2024/img.jpg — the deepest parent
            // /Pictures/Holiday/2024 is cached, so no MKCOL walk, no network.
            svc.ensureParentCollections("/Pictures/Holiday/2024/img.jpg")
            // No exception = success. knownCollections is unchanged.
            assertEquals(1, svc.knownCollections.size)
        }

    @Test
    fun `ensureParentCollections is a no-op for root-level files`() =
        runTest {
            val svc = service()
            // "/file.txt" has no parent directories to ensure
            svc.ensureParentCollections("/file.txt")
            assertTrue(svc.knownCollections.isEmpty())
        }

    @Test
    fun `mkcol returns without HTTP when path is cached`() =
        runTest {
            val svc = service()
            svc.knownCollections.add("/Pictures")
            // Unreachable URL would hang on connect timeout if not cached.
            // Completing quickly is the assertion.
            svc.mkcol("/Pictures")
            // Cache unchanged
            assertEquals(1, svc.knownCollections.size)
            assertTrue("/Pictures" in svc.knownCollections)
        }

    @Test
    fun `cache is independent per service instance`() {
        val a = service()
        val b = service()
        a.knownCollections.add("/shared")
        assertTrue("/shared" in a.knownCollections)
        assertTrue(b.knownCollections.isEmpty(), "second instance must not see first's cache")
    }
}
