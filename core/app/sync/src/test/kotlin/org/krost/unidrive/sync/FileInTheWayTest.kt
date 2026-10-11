package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #737: when the remote needs a folder where the sync root holds a regular file (the remote turned
 * `/x` into a folder, or Internxt holds a file and a folder of the same name), creating a placeholder
 * under that folder used to delete the file. A file whose bytes never reached the cloud was lost.
 * Its bytes must survive as a conflict copy next to the new folder.
 */
class FileInTheWayTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    private val localBytes = "bytes that exist only on this disk".toByteArray()
    private val remoteBytes = "the child the cloud has under the new folder".toByteArray()
    private val modified = Instant.parse("2026-03-28T12:00:00Z")

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-737-root")
        db = StateDatabase(Files.createTempDirectory("ud-737-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
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
        )

    /** Every regular file under the sync root whose content is exactly [bytes]. */
    private fun filesHolding(bytes: ByteArray): List<Path> =
        Files.walk(syncRoot).use { s ->
            s.filter { Files.isRegularFile(it) && Files.readAllBytes(it).contentEquals(bytes) }.toList()
        }

    @Test
    fun `an unsynced local file survives as a conflict copy when the remote needs a folder at its path`() =
        runTest {
            val x = syncRoot.resolve("x")
            Files.write(x, localBytes)
            provider.files["/x/child"] = remoteBytes
            provider.deltaItems =
                listOf(
                    CloudItem(
                        id = "id-/x/child",
                        name = "child",
                        path = "/x/child",
                        size = remoteBytes.size.toLong(),
                        isFolder = false,
                        modified = modified,
                        created = modified,
                        hash = null,
                        mimeType = null,
                    ),
                )
            // The local file never reaches the cloud in this pass: its only copy is on this disk.
            provider.uploadFailCount = 1_000

            val engine = engine()
            engine.syncOnce()

            val kept = filesHolding(localBytes)
            assertEquals(1, kept.size, "the local bytes must survive exactly once under $syncRoot, found $kept")
            assertTrue(
                kept.single().fileName.toString().startsWith("x.conflict-local-"),
                "the bytes are kept as a keep-both style conflict copy, got ${kept.single()}",
            )
            assertEquals(syncRoot, kept.single().parent, "the copy sits next to the new folder")
            assertTrue(Files.isDirectory(x), "the remote's folder takes the path")

            // The next pass fills in the child and still keeps the copy.
            provider.uploadFailCount = 0
            engine.syncOnce()

            assertContentEquals(remoteBytes, Files.readAllBytes(x.resolve("child")))
            assertEquals(1, filesHolding(localBytes).size, "the conflict copy is still there after the next pass")
        }

    @Test
    fun `createPlaceholder keeps a file that is in the way of its parent folder as a conflict copy`() {
        val report = syncRoot.resolve("a").resolve("report.txt")
        Files.createDirectories(report.parent)
        Files.write(report, localBytes)

        PlaceholderManager(syncRoot).createPlaceholder("/a/report.txt/child", size = 10, modified = modified)

        assertTrue(Files.isDirectory(report), "the parent folder replaces the file")
        assertTrue(Files.isRegularFile(report.resolve("child")), "the placeholder is created")
        val kept = filesHolding(localBytes)
        assertEquals(1, kept.size, "the file's bytes must survive, found $kept")
        val name = kept.single().fileName.toString()
        assertTrue(
            name.startsWith("report.conflict-local-") && name.endsWith(".txt"),
            "named the way keep-both names a local conflict copy, got $name",
        )
        assertEquals(report.parent, kept.single().parent)
    }
}
