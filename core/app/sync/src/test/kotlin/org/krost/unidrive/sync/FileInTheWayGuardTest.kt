package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
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
 * #737: a file in the way of a remote folder is replaced without a copy only when its state row
 * proves its bytes are in the cloud ([CloudCopyProof], installed by the sync engine). Every other
 * file is kept as a conflict copy, and a file that cannot be kept is left alone.
 */
class FileInTheWayGuardTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    private val bytes = "bytes the cloud has too".toByteArray()
    private val synced = Instant.parse("2026-03-28T12:00:00Z")

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-737-guard-root")
        db = StateDatabase(Files.createTempDirectory("ud-737-guard-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    /** A placeholder manager wired into a sync engine, as in production: the engine installs its guard. */
    private fun wiredManager(manager: PlaceholderManager = PlaceholderManager(syncRoot)): PlaceholderManager {
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            placeholder = manager,
        )
        return manager
    }

    /** A synced, hydrated `/x` whose row records exactly the bytes on disk. */
    private fun syncedFile(
        remoteHash: String?,
        localHash: String? = null,
    ): Path {
        val x = syncRoot.resolve("x")
        Files.write(x, bytes)
        Files.setLastModifiedTime(x, FileTime.from(synced))
        db.upsertEntry(
            SyncEntry(
                path = "/x",
                remoteId = "id-/x",
                remoteHash = remoteHash,
                remoteSize = bytes.size.toLong(),
                remoteModified = synced,
                localMtime = synced.toEpochMilli(),
                localSize = bytes.size.toLong(),
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = synced,
                localHash = localHash,
            ),
        )
        return x
    }

    private fun sha256(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun conflictCopies(): List<Path> =
        Files.list(syncRoot).use { s -> s.filter { it.fileName.toString().startsWith("x.conflict-local-") }.toList() }

    @Test
    fun `a synced unchanged file whose cloud copy matches by hash is replaced without a copy`() {
        provider.hashAlgorithmOverride = HashAlgorithm.Sha256Hex
        val x = syncedFile(remoteHash = sha256(bytes))

        wiredManager().createPlaceholder("/x/child", size = 10, modified = synced)

        assertTrue(Files.isDirectory(x))
        assertTrue(Files.isRegularFile(x.resolve("child")))
        assertEquals(emptyList(), conflictCopies(), "bytes provably in the cloud need no copy")
    }

    @Test
    fun `a synced unchanged file of a hashless provider is replaced when the recorded local hash matches`() {
        val x = syncedFile(remoteHash = null, localHash = sha256(bytes))

        wiredManager().createPlaceholder("/x/child", size = 10, modified = synced)

        assertTrue(Files.isDirectory(x))
        assertEquals(emptyList(), conflictCopies())
    }

    @Test
    fun `an edit that kept size and mtime is not proven and is kept as a copy`() {
        provider.hashAlgorithmOverride = HashAlgorithm.Sha256Hex
        val x = syncedFile(remoteHash = "0".repeat(64))

        wiredManager().createPlaceholder("/x/child", size = 10, modified = synced)

        assertTrue(Files.isDirectory(x))
        assertContentEquals(bytes, Files.readAllBytes(conflictCopies().single()))
    }

    @Test
    fun `a file without a remote hash or a recorded local hash is kept as a copy`() {
        val x = syncedFile(remoteHash = null, localHash = null)

        wiredManager().createPlaceholder("/x/child", size = 10, modified = synced)

        assertTrue(Files.isDirectory(x))
        assertContentEquals(bytes, Files.readAllBytes(conflictCopies().single()))
    }

    @Test
    fun `a file this pass reports deleted in the cloud is not proven even when its hash matches`() {
        val x = syncedFile(remoteHash = sha256(bytes))
        val deleted =
            CloudItem(
                id = "id-/x",
                name = "x",
                path = "/x",
                size = 0,
                isFolder = false,
                modified = synced,
                created = synced,
                hash = null,
                mimeType = null,
                deleted = true,
            )
        val folder = deleted.copy(id = "folder-/x", isFolder = true, deleted = false)
        val proof = CloudCopyProof(db, HashAlgorithm.Sha256Hex) { mapOf("/x" to deleted) }
        assertTrue(CloudCopyProof(db, HashAlgorithm.Sha256Hex) { emptyMap() }.bytesProvablyInCloud("/x", x))
        assertFalse(proof.bytesProvablyInCloud("/x", x), "the pass reports the file deleted")
        assertFalse(
            CloudCopyProof(db, HashAlgorithm.Sha256Hex) { mapOf("/x" to folder) }.bytesProvablyInCloud("/x", x),
            "the pass reports a folder at the file's path",
        )
    }

    @Test
    fun `an unsynced file is kept as a copy and recorded as a conflict`() {
        val x = syncRoot.resolve("x")
        Files.write(x, bytes)
        val log = ConflictLog(Files.createTempDirectory("ud-737-log").resolve("conflicts.jsonl"), Files.createTempDirectory("ud-737-bk"))
        val manager = PlaceholderManager(syncRoot)
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            conflictLog = log,
            placeholder = manager,
        )

        manager.createPlaceholder("/x/child", size = 10, modified = synced)

        assertContentEquals(bytes, Files.readAllBytes(conflictCopies().single()))
        val entries = log.recent()
        assertEquals(listOf("/x"), entries.map { it.path }, "the kept copy is recorded as a conflict")
        assertEquals("KEEP_BOTH", entries.single().policy)
    }

    @Test
    fun `a file that cannot be kept aside is left untouched and the placeholder is refused`() {
        val x = syncRoot.resolve("x")
        Files.write(x, bytes)
        val failing =
            object : PlaceholderManager(syncRoot) {
                override fun moveAside(
                    source: Path,
                    target: Path,
                ) {
                    throw IOException("simulated: the file is locked")
                }
            }

        assertFailsWith<FileInTheWayException> {
            wiredManager(failing).createPlaceholder("/x/child", size = 10, modified = synced)
        }

        assertTrue(Files.isRegularFile(x), "the file stays where it was")
        assertContentEquals(bytes, Files.readAllBytes(x))
        assertFalse(Files.exists(syncRoot.resolve("x").resolve("child")))
        assertEquals(emptyList(), conflictCopies())
    }

    @Test
    fun `a second copy in the same minute takes a free name instead of replacing the first`() {
        // The current minute's name and the next one's, so the test cannot straddle a minute boundary.
        val now = Instant.now()
        val existing = syncRoot.resolve(ConflictCopyNames.localCopyName("x", now))
        Files.write(existing, "an older copy".toByteArray())
        Files.write(syncRoot.resolve(ConflictCopyNames.localCopyName("x", now.plusSeconds(60))), "an older copy".toByteArray())
        val x = syncRoot.resolve("x")
        Files.write(x, bytes)

        PlaceholderManager(syncRoot).createPlaceholder("/x/child", size = 10, modified = synced)

        assertContentEquals("an older copy".toByteArray(), Files.readAllBytes(existing))
        val copies = conflictCopies()
        assertEquals(3, copies.size, "the older copies and the new one all exist: $copies")
        assertTrue(copies.any { Files.readAllBytes(it).contentEquals(bytes) })
    }
}
