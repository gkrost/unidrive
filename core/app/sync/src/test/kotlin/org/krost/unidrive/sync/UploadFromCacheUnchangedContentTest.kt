package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*

/**
 * #583: a file written through the mount whose bytes equal the version the row records (an Explorer copy over
 * the same file keeps the content and the modified time) was uploaded again in full; on the owner's line a
 * 1.36 GB zip took ten minutes. The engine already holds what proves the equality: the SHA-256 of the bytes
 * last exchanged (`local_hash`, hashless providers) or the provider's content hash (`remote_hash`). The mount
 * write path now adopts the copy without a provider write when the bytes match AND the cloud copy is still the
 * version the row records; every other case uploads exactly as before.
 */
class UploadFromCacheUnchangedContentTest {
    private lateinit var syncRoot: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider

    private val recordedModified = Instant.parse("2026-10-03T18:40:29Z")

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-583-root")
        cacheRoot = Files.createTempDirectory("ud-583-cache")
        db = StateDatabase(Files.createTempDirectory("ud-583-db").resolve("state.db"))
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            cacheRoot = cacheRoot,
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cloudItem(
        path: String,
        size: Long,
        modified: Instant = recordedModified,
        hash: String? = null,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = false,
        modified = modified,
        created = Instant.parse("2026-10-03T18:40:00Z"),
        hash = hash,
        mimeType = null,
    )

    /** A row that is in the cloud and in step with it: what a finished upload or a download leaves behind. */
    private fun syncedRow(
        path: String,
        recordedContent: ByteArray,
        localHash: String? = sha256(recordedContent),
        remoteHash: String? = null,
        remoteId: String? = "id-$path",
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = remoteHash,
        remoteSize = recordedContent.size.toLong(),
        remoteModified = recordedModified,
        localMtime = 1_000L,
        localSize = recordedContent.size.toLong(),
        isFolder = false,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.parse("2026-10-03T18:41:00Z"),
        localHash = localHash,
        lastErrorAt = Instant.parse("2026-10-05T12:00:00Z"),
        cacheBacked = true,
    )

    private fun cacheFile(name: String, content: ByteArray): Path = cacheRoot.resolve(name).also { Files.write(it, content) }

    @Test
    fun `identical bytes and an unchanged cloud copy are adopted without a provider write`() =
        runTest {
            val content = ByteArray(4096) { ((it * 31) and 0xff).toByte() }
            db.upsertEntry(syncedRow("/same.bin", content))
            provider.deltaItems = listOf(cloudItem("/same.bin", content.size.toLong()))
            val cache = cacheFile("same.bin", content)

            engine().uploadFromCache("/same.bin", cache)

            assertTrue(provider.uploadedPaths.isEmpty(), "no PUT for bytes the cloud already holds; got ${provider.uploadedPaths}")
            val row = db.getEntry("/same.bin")
            assertNotNull(row)
            assertEquals("id-/same.bin", row.remoteId, "the row keeps its cloud identity")
            assertEquals(content.size.toLong(), row.remoteSize)
            assertEquals(sha256(content), row.localHash, "the recorded hash stays")
            assertNull(row.lastErrorAt, "an adopted copy is a success: an earlier failure marker is cleared")
        }

    @Test
    fun `same size but different bytes are uploaded`() =
        runTest {
            val old = ByteArray(2048) { 1 }
            val edited = ByteArray(2048) { 2 }
            db.upsertEntry(syncedRow("/edit.bin", old))
            provider.deltaItems = listOf(cloudItem("/edit.bin", old.size.toLong()))
            val cache = cacheFile("edit.bin", edited)

            engine().uploadFromCache("/edit.bin", cache)

            assertTrue(provider.uploadedPaths.contains("/edit.bin"), "an edit of the same length is still an edit")
            assertEquals(sha256(edited), db.getEntry("/edit.bin")?.localHash)
        }

    @Test
    fun `identical bytes but a cloud copy that changed since the baseline are not skipped`() =
        runTest {
            val content = ByteArray(1024) { 7 }
            db.upsertEntry(syncedRow("/moved-on.bin", content))
            // the cloud holds a newer version (modified differs): the skip must not hide it, the normal upload path decides
            provider.deltaItems = listOf(cloudItem("/moved-on.bin", content.size.toLong(), modified = Instant.parse("2026-10-05T09:00:00Z")))
            val cache = cacheFile("moved-on.bin", content)

            engine().uploadFromCache("/moved-on.bin", cache)

            assertTrue(provider.uploadedPaths.contains("/moved-on.bin"), "the cloud copy is not the recorded version: take the upload path")
        }

    @Test
    fun `a cloud copy of another size is not skipped`() =
        runTest {
            val content = ByteArray(1024) { 7 }
            db.upsertEntry(syncedRow("/resized.bin", content))
            provider.deltaItems = listOf(cloudItem("/resized.bin", 99L))
            val cache = cacheFile("resized.bin", content)

            engine().uploadFromCache("/resized.bin", cache)

            assertTrue(provider.uploadedPaths.contains("/resized.bin"))
        }

    @Test
    fun `without a recorded hash the bytes cannot be proven equal and are uploaded`() =
        runTest {
            val content = ByteArray(1024) { 3 }
            db.upsertEntry(syncedRow("/nohash.bin", content, localHash = null))
            provider.deltaItems = listOf(cloudItem("/nohash.bin", content.size.toLong()))
            val cache = cacheFile("nohash.bin", content)

            engine().uploadFromCache("/nohash.bin", cache)

            assertTrue(provider.uploadedPaths.contains("/nohash.bin"))
        }

    @Test
    fun `a file that never reached the cloud is uploaded`() =
        runTest {
            val content = ByteArray(1024) { 5 }
            db.upsertEntry(syncedRow("/pending.bin", content, remoteId = "local:abc"))
            provider.deltaItems = emptyList()
            val cache = cacheFile("pending.bin", content)

            engine().uploadFromCache("/pending.bin", cache)

            assertTrue(provider.uploadedPaths.contains("/pending.bin"), "a pending row has no cloud version to compare with")
        }

    @Test
    fun `a content-hash provider compares the provider hash and skips identical bytes`() =
        runTest {
            val content = ByteArray(3000) { ((it * 13) and 0xff).toByte() }
            provider.hashAlgorithmOverride = HashAlgorithm.Sha256Hex
            val hash = sha256(content)
            db.upsertEntry(syncedRow("/hashed.bin", content, localHash = null, remoteHash = hash))
            provider.deltaItems = listOf(cloudItem("/hashed.bin", content.size.toLong(), hash = hash))
            val cache = cacheFile("hashed.bin", content)

            engine().uploadFromCache("/hashed.bin", cache)

            assertTrue(provider.uploadedPaths.isEmpty(), "the provider's own content hash proves the equality")
        }

    @Test
    fun `an unreachable cloud lookup falls back to the normal upload`() =
        runTest {
            val content = ByteArray(1024) { 9 }
            db.upsertEntry(syncedRow("/lookup.bin", content))
            provider.deltaItems = emptyList() // getMetadata finds nothing and throws
            val cache = cacheFile("lookup.bin", content)

            engine().uploadFromCache("/lookup.bin", cache)

            assertTrue(provider.uploadedPaths.contains("/lookup.bin"), "when the cloud copy cannot be checked the skip is not taken")
        }
}
