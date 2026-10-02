package org.krost.unidrive.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #318: ensureHydrated serialises the warm-cache check + download per path, and
 * re-downloads into a staged temp file that is atomically swapped in, so a
 * re-hydration can neither double-download a path under concurrent cold opens
 * nor truncate a cache file another handle is reading.
 */
class EnsureHydratedConcurrencyTest {
    private lateinit var syncRoot: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val content = "hello hydration".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-318-root")
        cacheRoot = Files.createTempDirectory("ud-318-cache")
        db = StateDatabase(Files.createTempDirectory("ud-318-db").resolve("state.db"))
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                cacheRoot = cacheRoot,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun seedRemoteBackedRow(path: String) {
        db.upsertEntry(
            org.krost.unidrive.sync.model.SyncEntry(
                path = path,
                remoteId = "id-$path",
                remoteHash = "hash-$path",
                remoteSize = content.size.toLong(),
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
    }

    private fun downloadCount(): Int = provider.downloadByIdCalls.size + provider.downloadByPathCalls.size

    @Test
    fun `concurrent cold opens of the same path download once and serve the same bytes`() =
        runTest {
            provider.files["/f.txt"] = content
            seedRemoteBackedRow("/f.txt")

            val a = async { engine.ensureHydrated("/f.txt") }
            val b = async { engine.ensureHydrated("/f.txt") }

            assertEquals(a.await(), b.await(), "both opens are served the same cache path")
            assertEquals(1, downloadCount(), "the per-path mutex must collapse concurrent cold opens into one download")
            assertContentEquals(content, Files.readAllBytes(a.await()))
        }

    @Test
    fun `a failed re-download leaves the previous cache copy intact and stages nothing`() =
        runTest {
            provider.files["/f.txt"] = content
            seedRemoteBackedRow("/f.txt")
            val cachePath = engine.ensureHydrated("/f.txt")
            val goodBytes = Files.readAllBytes(cachePath)

            // Force the warm-cache size check to fail, then fail the download.
            db.upsertEntry(db.getEntry("/f.txt")!!.copy(remoteSize = content.size + 5L))
            provider.downloadFailCount = 1

            assertFailsWith<ProviderException> { engine.ensureHydrated("/f.txt") }

            assertContentEquals(
                goodBytes,
                Files.readAllBytes(cachePath),
                "a failed re-download must not clobber the cache copy other handles are reading",
            )
            assertFalse(
                Files.list(cachePath.parent).use { s -> s.anyMatch { it.fileName.toString().contains(".hydrating-") } },
                "no staging file may survive a failed hydration",
            )
        }

    @Test
    fun `a successful re-hydration leaves no staging files behind`() =
        runTest {
            provider.files["/f.txt"] = content
            seedRemoteBackedRow("/f.txt")
            val cachePath = engine.ensureHydrated("/f.txt")

            db.upsertEntry(db.getEntry("/f.txt")!!.copy(remoteSize = content.size + 5L))
            provider.files["/f.txt"] = "bigger content now".toByteArray()
            engine.ensureHydrated("/f.txt")

            assertContentEquals("bigger content now".toByteArray(), Files.readAllBytes(cachePath))
            assertTrue(
                Files.list(cachePath.parent).use { s -> s.noneMatch { it.fileName.toString().contains(".hydrating-") } },
                "the staged copy must be consumed by the atomic swap",
            )
        }
}
