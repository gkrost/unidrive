package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnumerateRemoteIntoStateTest {
    private lateinit var syncRoot: Path
    private lateinit var dbDir: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: EnumerateFakeProvider
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-enum-root")
        dbDir = Files.createTempDirectory("ud-enum-db")
        cacheRoot = Files.createTempDirectory("ud-enum-cache")
        db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        provider = EnumerateFakeProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                reporter = ProgressReporter.Silent,
                cacheRoot = cacheRoot,
                cacheKey = "enum-test",
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `enumerate upserts remote entries into state_db without scanning sync_root or planning deletes`() =
        runTest {
            provider.putRemote("/a.txt", "AAA")
            provider.putRemote("/dir", isFolder = true)
            provider.putRemote("/dir/b.txt", "BBB")

            val result = engine.enumerateRemoteIntoState(reset = false)

            assertTrue(result.ok, "enumerate must succeed against an empty sync_root (no guard)")
            assertEquals(
                setOf("/a.txt", "/dir"),
                db.listDirectChildren("").map { it.path }.toSet(),
            )
            assertNotNull(db.getEntry("/dir/b.txt"))
            assertEquals(0, provider.deletedPaths.size, "enumerate must never call provider.delete")
            assertEquals(0, provider.uploadedPaths.size, "enumerate must never upload")
        }

    // ── Top-level fake provider — follows the ThrottledProviderTest /
    // CloudRelocatorTest per-test fake convention. Adds the hooks the
    // enumerate path needs: a settable remote, recorded cursors, and an
    // on-demand incomplete delta. Cursor model: each delta() advances the
    // returned cursor monotonically; the engine resumes by passing the
    // previously-returned cursor back in.
    class EnumerateFakeProvider : CloudProvider {
        override val id = "enum-fake"
        override val displayName = "Enumerate Fake"
        override var isAuthenticated = true

        override fun capabilities(): Set<org.krost.unidrive.Capability> =
            setOf(org.krost.unidrive.Capability.Delta)

        private val remote = linkedMapOf<String, CloudItem>()

        val deletedPaths = mutableListOf<String>()
        val uploadedPaths = mutableListOf<String>()

        // Cursors the engine passed into delta() across calls (null = full enum).
        val deltaCursorsSeen = mutableListOf<String?>()
        private var nextCursorSeq = 0
        private var nextDeltaIncomplete = false

        fun putRemote(path: String, content: String = "", isFolder: Boolean = false) {
            remote[path] =
                CloudItem(
                    id = "id-$path",
                    name = path.substringAfterLast("/"),
                    path = path,
                    size = if (isFolder) 0L else content.toByteArray().size.toLong(),
                    isFolder = isFolder,
                    modified = Instant.now(),
                    created = Instant.now(),
                    hash = if (isFolder) null else "h-$content",
                    mimeType = null,
                )
        }

        fun removeRemote(path: String) {
            remote.remove(path)
        }

        /** The NEXT delta() call returns complete=false (single-use). */
        fun markNextDeltaIncomplete() {
            nextDeltaIncomplete = true
        }

        override suspend fun authenticate() {}

        override suspend fun logout() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String) = remote.getValue(path)

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage {
            deltaCursorsSeen.add(cursor)
            val complete = !nextDeltaIncomplete
            // On an incomplete delta, return an empty page (a throttled subtree
            // skip) — the engine must NOT infer the omitted paths as deletions.
            val items = if (nextDeltaIncomplete) emptyList() else remote.values.toList()
            nextDeltaIncomplete = false
            nextCursorSeq++
            return DeltaPage(
                items = items,
                cursor = "cursor-$nextCursorSeq",
                hasMore = false,
                complete = complete,
            )
        }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("enumerate path must not download")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploadedPaths.add(remotePath)
            error("enumerate path must not upload")
        }

        override suspend fun delete(remotePath: String) {
            deletedPaths.add(remotePath)
        }

        override suspend fun createFolder(path: String) = error("enumerate path must not createFolder")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ) = error("enumerate path must not move")

        override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)
    }
}
