package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Minimal fake CloudProvider for HydrationImpl tests.
 * Only implements the methods exercised by [SyncEngine.ensureHydrated]:
 * [capabilities], [authenticate], [downloadById], and the abstract stubs
 * that Kotlin requires for every non-default interface member.
 */
internal class MinimalFakeProvider(
    private val remoteFiles: MutableMap<String, ByteArray> = mutableMapOf(),
) : CloudProvider {
    override val id = "fake-hydration"
    override val displayName = "Fake (hydration test)"
    override var isAuthenticated = true

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

    override suspend fun authenticate() {}

    override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

    override suspend fun getMetadata(path: String): CloudItem =
        error("getMetadata not used in hydration tests")

    override suspend fun download(remotePath: String, destination: Path): Long {
        val content = remoteFiles[remotePath] ?: ByteArray(0)
        Files.createDirectories(destination.parent)
        Files.write(destination, content)
        return content.size.toLong()
    }

    override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long =
        download(remotePath, destination)

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem = error("upload not used in hydration cold-path tests")

    override suspend fun delete(remotePath: String) = error("delete not used")

    override suspend fun createFolder(path: String): CloudItem = error("createFolder not used")

    override suspend fun move(fromPath: String, toPath: String): CloudItem = error("move not used")

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

    override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)

    /** Seed remote content for a path. */
    fun seedContent(path: String, content: String) {
        remoteFiles[path] = content.toByteArray()
    }
}

/**
 * Test fixture: wires a real [HydrationImpl] against a real [SyncEngine]
 * (backed by [MinimalFakeProvider]) and a real [StateDatabase] (in-memory).
 *
 * Helper names align with the plan's symbolic names:
 * - [stateDb] — wraps insertUnhydratedEntry / isHydrated over the real StateDatabase API
 * - [syncEngine] — exposes seedRemoteContent
 * - [hydration] — the [HydrationImpl] under test
 */
class HydrationTestEnv {
    private val cacheRoot: Path = Files.createTempDirectory("unidrive-hydration-cache")
    private val dbPath: Path = Files.createTempDirectory("unidrive-hydration-db").resolve("state.db")
    private val fakeProvider = MinimalFakeProvider()

    val stateDb: StateDatabaseFacade
    val syncEngine: SyncEngineFacade
    val hydration: Hydration

    init {
        val db = StateDatabase(dbPath = dbPath, inMemory = true)
        db.initialize()

        val engine = SyncEngine(
            provider = fakeProvider,
            db = db,
            syncRoot = Files.createTempDirectory("unidrive-hydration-sync"),
            cacheRoot = cacheRoot,
        )

        stateDb = StateDatabaseFacade(db)
        syncEngine = SyncEngineFacade(fakeProvider)
        hydration = HydrationImpl(syncEngine = engine, stateDb = db)
    }

    inner class StateDatabaseFacade(private val db: StateDatabase) {
        fun insertUnhydratedEntry(path: String, remoteSize: Long) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = "id-$path",
                    remoteHash = "hash-$path",
                    remoteSize = remoteSize,
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

        fun isHydrated(path: String): Boolean =
            db.getEntry(path)?.isHydrated ?: false
    }

    inner class SyncEngineFacade internal constructor(private val fakeProvider: MinimalFakeProvider) {
        fun seedRemoteContent(path: String, content: String) {
            fakeProvider.seedContent(path, content)
        }
    }
}

class HydrationImplTest {
    @Test
    fun `open_read on unhydrated path triggers download and returns cache path`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/foo.txt", "hello")

        val result = env.hydration.openForRead("conn1", "h1", "/foo.txt")

        assertTrue(result is OpenResult.Ok)
        val cachePath = (result as OpenResult.Ok).cachePath
        assertEquals("hello", java.nio.file.Files.readString(cachePath))
        assertEquals(true, env.stateDb.isHydrated("/foo.txt"))
    }

    @Test
    fun `open_read on unknown path returns Failed with Generic error`() = runTest {
        val env = HydrationTestEnv()

        val result = env.hydration.openForRead("conn1", "h2", "/does-not-exist.txt")

        assertTrue(result is OpenResult.Failed)
        val error = (result as OpenResult.Failed).error
        assertTrue(error is HydrationError.Generic)
        assertTrue(error.message.contains("does-not-exist.txt"))
    }
}
