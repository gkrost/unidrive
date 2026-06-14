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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnumerateCorroborationGuardTest {
    private lateinit var syncRoot: Path
    private lateinit var dbDir: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: CorroborationFakeProvider
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-corr-root")
        dbDir = Files.createTempDirectory("ud-corr-db")
        cacheRoot = Files.createTempDirectory("ud-corr-cache")
        db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        provider = CorroborationFakeProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                reporter = ProgressReporter.Silent,
                cacheRoot = cacheRoot,
                cacheKey = "corr-test",
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(n: Int) {
        repeat(n) { provider.putRemote("/f$it.txt", "v$it") }
        val r = engine.enumerateRemoteIntoState(reset = false)
        assertTrue(r.complete)
        assertEquals(n, db.getEntryCount())
    }

    @Test
    fun `bulk disappearance on a single complete enumeration is deferred not reaped on first pass`() =
        runTest {
            seed(100)
            // Drop 60 of 100 (> max(50, 20% of 100=20) → bulk). A single complete
            // enumeration must NOT reap; it warns and defers.
            for (i in 0 until 60) provider.removeRemote("/f$i.txt")
            val r = engine.enumerateRemoteIntoState(reset = true)

            assertTrue(r.complete)
            assertEquals(0, r.reaped, "bulk disappearance must defer reaping on the FIRST complete enumeration")
            assertNotNull(db.getEntry("/f0.txt"), "deferred path must still be alive after the first pass")
            assertEquals(100, db.getEntryCount(), "no rows reaped on the deferred pass")
        }

    @Test
    fun `second consecutive complete enumeration still-missing reaps the deferred bulk`() =
        runTest {
            seed(100)
            for (i in 0 until 60) provider.removeRemote("/f$i.txt")
            engine.enumerateRemoteIntoState(reset = true)
            assertEquals(100, db.getEntryCount())

            // Second complete enumeration: the same 60 paths are still missing →
            // corroborated → reaped.
            val r = engine.enumerateRemoteIntoState(reset = true)

            assertTrue(r.complete)
            assertEquals(60, r.reaped, "a bulk disappearance corroborated by a second complete enumeration must reap")
            assertNull(db.getEntry("/f0.txt"))
            assertEquals(40, db.getEntryCount())
        }

    @Test
    fun `sub-threshold disappearance reaps immediately without corroboration`() =
        runTest {
            seed(100)
            // Drop 10 of 100 (< max(50, 20)=50 → sub-threshold). Reaps on the first pass.
            for (i in 0 until 10) provider.removeRemote("/f$i.txt")
            val r = engine.enumerateRemoteIntoState(reset = true)

            assertTrue(r.complete)
            assertEquals(10, r.reaped, "a sub-threshold drop must reap on the first complete enumeration (unchanged)")
            assertNull(db.getEntry("/f0.txt"))
            assertEquals(90, db.getEntryCount())
        }

    @Test
    fun `path that reappears on the second enumeration is not reaped`() =
        runTest {
            seed(100)
            for (i in 0 until 60) provider.removeRemote("/f$i.txt")
            engine.enumerateRemoteIntoState(reset = true)
            assertEquals(100, db.getEntryCount(), "deferred on first pass")

            // /f0.txt comes back before the second enumeration; the other 59 are still gone.
            // /f0.txt was missing on pass 1 but present on pass 2 → not corroborated → not reaped.
            // The remaining 59 are sub-threshold (< 50? no, 59 > 50) — still bulk, corroborated
            // for the 59 that stayed missing.
            provider.putRemote("/f0.txt", "back")
            val r = engine.enumerateRemoteIntoState(reset = true)

            assertTrue(r.complete)
            assertNotNull(db.getEntry("/f0.txt"), "a path that reappeared must never be reaped")
            assertNull(db.getEntry("/f1.txt"), "a path missing on both passes is corroborated → reaped")
            assertEquals(59, r.reaped)
        }

    class CorroborationFakeProvider : CloudProvider {
        override val id = "corr-fake"
        override val displayName = "Corroboration Fake"
        override var isAuthenticated = true

        override fun capabilities(): Set<org.krost.unidrive.Capability> =
            setOf(org.krost.unidrive.Capability.Delta)

        private val remote = linkedMapOf<String, CloudItem>()
        private var nextCursorSeq = 0

        fun putRemote(path: String, content: String = "") {
            remote[path] =
                CloudItem(
                    id = "id-$path",
                    name = path.substringAfterLast("/"),
                    path = path,
                    size = content.toByteArray().size.toLong(),
                    isFolder = false,
                    modified = Instant.now(),
                    created = Instant.now(),
                    hash = "h-$content",
                    mimeType = null,
                )
        }

        fun removeRemote(path: String) {
            remote.remove(path)
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
            nextCursorSeq++
            return DeltaPage(
                items = remote.values.toList(),
                cursor = "cursor-$nextCursorSeq",
                hasMore = false,
                complete = true,
            )
        }

        override suspend fun download(remotePath: String, destination: Path): Long = error("no download")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("no upload")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("no delete")

        override suspend fun createFolder(path: String) = error("no createFolder")

        override suspend fun move(fromPath: String, toPath: String) = error("no move")

        override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)
    }
}
