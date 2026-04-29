package org.krost.unidrive.sync

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class CloudRelocatorTest {
    private lateinit var source: FakeProvider
    private lateinit var target: FakeProvider

    @BeforeTest
    fun setUp() {
        source = FakeProvider("source")
        target = FakeProvider("target")
    }

    // -- preFlightCheck ---------------------------------------------------------

    @Test
    fun `preFlightCheck returns zero for empty source`() =
        runTest {
            val relocator = CloudRelocator(source, target)
            val (size, count) = relocator.preFlightCheck("/")
            assertEquals(0L, size)
            assertEquals(0, count)
        }

    @Test
    fun `preFlightCheck sums file sizes and counts`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/a.txt", size = 100),
                    cloudItem("/b.txt", size = 200),
                )
            val relocator = CloudRelocator(source, target)
            val (size, count) = relocator.preFlightCheck("/")
            assertEquals(300L, size)
            assertEquals(2, count)
        }

    @Test
    fun `preFlightCheck recurses into folders`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/docs", isFolder = true),
                    cloudItem("/top.txt", size = 50),
                )
            source.children["/docs"] =
                listOf(
                    cloudItem("/docs/readme.md", size = 150),
                )
            val relocator = CloudRelocator(source, target)
            val (size, count) = relocator.preFlightCheck("/")
            assertEquals(200L, size)
            assertEquals(2, count)
        }

    @Test
    fun `preFlightCheck ignores folders in count`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/folder", isFolder = true),
                )
            source.children["/folder"] = emptyList()
            val relocator = CloudRelocator(source, target)
            val (_, count) = relocator.preFlightCheck("/")
            assertEquals(0, count)
        }

    // -- migrate: copies files from source to target ----------------------------

    @Test
    fun `migrate copies files from source to target`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/file.txt", size = 10),
                )
            source.fileContents["/file.txt"] = "hello".toByteArray()

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            assertTrue(
                target.uploadedPaths.contains("/file.txt"),
                "target should have received /file.txt",
            )
            val completed = events.filterIsInstance<MigrateEvent.Completed>()
            assertEquals(1, completed.size)
            assertEquals(1, completed[0].doneFiles)
            assertEquals(0, completed[0].errorCount)
        }

    @Test
    fun `migrate creates folders on target`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/sub", isFolder = true),
                )
            source.children["/sub"] =
                listOf(
                    cloudItem("/sub/data.bin", size = 5),
                )
            source.fileContents["/sub/data.bin"] = ByteArray(5)

            val relocator = CloudRelocator(source, target)
            relocator.migrate("/", "/").toList()

            assertTrue(target.createdFolders.contains("/sub"))
            assertTrue(target.uploadedPaths.contains("/sub/data.bin"))
        }

    @Test
    fun `migrate emits Started event with correct totals`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/a.txt", size = 100),
                    cloudItem("/b.txt", size = 200),
                )
            source.fileContents["/a.txt"] = ByteArray(100)
            source.fileContents["/b.txt"] = ByteArray(200)

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val started = events.filterIsInstance<MigrateEvent.Started>()
            assertEquals(1, started.size)
            assertEquals(2, started[0].totalFiles)
            assertEquals(300L, started[0].totalSize)
        }

    @Test
    fun `migrate emits FileProgressEvent with non-zero totals`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/a.txt", size = 100),
                    cloudItem("/b.txt", size = 200),
                )
            source.fileContents["/a.txt"] = ByteArray(100)
            source.fileContents["/b.txt"] = ByteArray(200)

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val progress = events.filterIsInstance<MigrateEvent.FileProgressEvent>()
            assertTrue(progress.isNotEmpty(), "should emit at least one FileProgressEvent")
            for (event in progress) {
                assertEquals(2, event.totalFiles, "totalFiles must be threaded from preFlightCheck")
                assertEquals(300L, event.totalSize, "totalSize must be threaded from preFlightCheck")
            }
            // doneSize grows monotonically toward totalSize
            assertEquals(300L, progress.last().doneSize)
            assertEquals(2, progress.last().doneFiles)
        }

    @Test
    fun `migrate maps source subtree to target subtree`() =
        runTest {
            source.children["/src"] =
                listOf(
                    cloudItem("/src/f.txt", size = 10),
                )
            source.fileContents["/src/f.txt"] = ByteArray(10)

            val relocator = CloudRelocator(source, target)
            relocator.migrate("/src", "/dst").toList()

            assertTrue(
                target.uploadedPaths.contains("/dst/f.txt"),
                "file should be uploaded under target subtree",
            )
        }

    // -- skip-if-exists ---------------------------------------------------------

    @Test
    fun `migrate skips file when target has matching size`() =
        runTest {
            source.children["/"] =
                listOf(cloudItem("/a.txt", size = 100))
            source.fileContents["/a.txt"] = ByteArray(100)
            target.children["/"] =
                listOf(cloudItem("/a.txt", size = 100))

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            assertTrue(
                "/a.txt" !in target.uploadedPaths,
                "target should not receive upload for equivalent file",
            )
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertEquals(1, completed.skippedFiles)
            assertEquals(100L, completed.skippedSize)
            // doneFiles includes skipped
            assertEquals(1, completed.doneFiles)
        }

    @Test
    fun `migrate skips file when hashes match even if sizes differ`() =
        runTest {
            source.children["/"] =
                listOf(cloudItem("/a.txt", size = 100))
            source.fileContents["/a.txt"] = ByteArray(100)
            // Different size, same hash — hash wins
            target.children["/"] =
                listOf(cloudItem("/a.txt", size = 200))

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            assertTrue("/a.txt" !in target.uploadedPaths)
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertEquals(1, completed.skippedFiles)
        }

    @Test
    fun `migrate transfers file when size differs and hashes absent`() =
        runTest {
            source.children["/"] =
                listOf(cloudItem("/a.txt", size = 100, hash = null))
            source.fileContents["/a.txt"] = ByteArray(100)
            target.children["/"] =
                listOf(cloudItem("/a.txt", size = 200, hash = null))

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            assertTrue("/a.txt" in target.uploadedPaths)
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertEquals(0, completed.skippedFiles)
            assertEquals(1, completed.doneFiles)
        }

    @Test
    fun `migrate transfers everything when skipExisting is false`() =
        runTest {
            source.children["/"] =
                listOf(cloudItem("/a.txt", size = 100))
            source.fileContents["/a.txt"] = ByteArray(100)
            target.children["/"] =
                listOf(cloudItem("/a.txt", size = 100))

            val relocator = CloudRelocator(source, target, skipExisting = false)
            val events = relocator.migrate("/", "/").toList()

            assertTrue("/a.txt" in target.uploadedPaths, "force flag should transfer despite match")
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertEquals(0, completed.skippedFiles)
        }

    @Test
    fun `migrate skips and transfers in mixed set`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/same.txt", size = 50),
                    cloudItem("/different.txt", size = 50, hash = null),
                    cloudItem("/new.txt", size = 50),
                )
            source.fileContents["/same.txt"] = ByteArray(50)
            source.fileContents["/different.txt"] = ByteArray(50)
            source.fileContents["/new.txt"] = ByteArray(50)
            target.children["/"] =
                listOf(
                    cloudItem("/same.txt", size = 50),
                    cloudItem("/different.txt", size = 99, hash = null),
                )

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            assertTrue("/same.txt" !in target.uploadedPaths)
            assertTrue("/different.txt" in target.uploadedPaths)
            assertTrue("/new.txt" in target.uploadedPaths)
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertEquals(1, completed.skippedFiles)
            assertEquals(50L, completed.skippedSize)
            // 2 transferred + 1 skipped
            assertEquals(3, completed.doneFiles)
            assertEquals(150L, completed.doneSize)
        }

    // -- error handling ---------------------------------------------------------

    @Test
    fun `migrate emits Error event on download failure`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/fail.txt", size = 10),
                )
            source.downloadShouldFail = true

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val errors = events.filterIsInstance<MigrateEvent.Error>()
            assertTrue(errors.isNotEmpty(), "should emit at least one Error event")
            val completed = events.filterIsInstance<MigrateEvent.Completed>()
            assertTrue(completed[0].errorCount > 0)
        }

    @Test
    fun `migrate continues after single file failure`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/good.txt", size = 10),
                    cloudItem("/bad.txt", size = 10),
                )
            source.fileContents["/good.txt"] = ByteArray(10)
            source.failPaths.add("/bad.txt")

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val completed = events.filterIsInstance<MigrateEvent.Completed>()
            assertEquals(1, completed[0].doneFiles, "good file should still be migrated")
            assertEquals(1, completed[0].errorCount, "bad file should be counted as error")
        }

    @Test
    fun `migrate emits Completed with duration`() =
        runTest {
            source.children["/"] = emptyList()

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val completed = events.filterIsInstance<MigrateEvent.Completed>()
            assertEquals(1, completed.size)
            assertTrue(completed[0].durationMs >= 0)
        }

    // -- UD-273: single-scan when totals supplied ------------------------------

    @Test
    fun `migrate with pre-computed totals scans source exactly once`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/sub", isFolder = true),
                    cloudItem("/top.txt", size = 10),
                )
            source.children["/sub"] =
                listOf(
                    cloudItem("/sub/a.txt", size = 20),
                )
            source.fileContents["/top.txt"] = ByteArray(10)
            source.fileContents["/sub/a.txt"] = ByteArray(20)

            val relocator = CloudRelocator(source, target)
            relocator.migrate("/", "/", knownTotalSize = 30L, knownTotalFiles = 2).toList()

            // 2 source folders → with known totals, preFlightCheck is skipped.
            // walk calls source.listChildren exactly once per folder.
            assertEquals(2, source.listChildrenCount, "source scanned once (no redundant preFlightCheck)")
        }

    @Test
    fun `migrate with pre-computed totals emits Started with those exact values`() =
        runTest {
            source.children["/"] = listOf(cloudItem("/a.txt", size = 10))
            source.fileContents["/a.txt"] = ByteArray(10)

            val relocator = CloudRelocator(source, target)
            val events =
                relocator.migrate("/", "/", knownTotalSize = 999L, knownTotalFiles = 42).toList()

            val started = events.filterIsInstance<MigrateEvent.Started>().first()
            assertEquals(999L, started.totalSize)
            assertEquals(42, started.totalFiles)
        }

    // -- helpers ----------------------------------------------------------------

    private fun cloudItem(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        hash: String? = "hash-$path",
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = hash,
        mimeType = "application/octet-stream",
    )

    class FakeProvider(
        override val id: String,
    ) : CloudProvider {
        override val displayName = id
        override val isAuthenticated = true

        override fun capabilities(): Set<org.krost.unidrive.Capability> = setOf(org.krost.unidrive.Capability.Delta)

        val children = mutableMapOf<String, List<CloudItem>>()
        val fileContents = mutableMapOf<String, ByteArray>()
        val uploadedPaths = mutableListOf<String>()
        val createdFolders = mutableListOf<String>()
        var downloadShouldFail = false
        val failPaths = mutableSetOf<String>()
        var listChildrenCount = 0

        override suspend fun authenticate() {}

        override suspend fun logout() {}

        override suspend fun listChildren(path: String): List<CloudItem> {
            listChildrenCount++
            return children[path] ?: emptyList()
        }

        override suspend fun getMetadata(path: String): CloudItem = throw UnsupportedOperationException("not needed for relocator tests")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            if (downloadShouldFail || remotePath in failPaths) {
                throw RuntimeException("Simulated download failure for $remotePath")
            }
            val content = fileContents[remotePath] ?: ByteArray(0)
            Files.createDirectories(destination.parent)
            Files.write(destination, content)
            return content.size.toLong()
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploadedPaths.add(remotePath)
            val size = Files.size(localPath)
            return CloudItem(
                id = "id-$remotePath",
                name = remotePath.substringAfterLast("/"),
                path = remotePath,
                size = size,
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = "uploaded",
                mimeType = null,
            )
        }

        override suspend fun delete(remotePath: String) {}

        override suspend fun createFolder(path: String): CloudItem {
            createdFolders.add(path)
            return CloudItem(
                id = "id-$path",
                name = path.substringAfterLast("/"),
                path = path,
                size = 0,
                isFolder = true,
                modified = Instant.now(),
                created = Instant.now(),
                hash = null,
                mimeType = null,
            )
        }

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ) = createFolder(toPath)

        override suspend fun delta(cursor: String?) = DeltaPage(items = emptyList(), cursor = "c", hasMore = false)

        override suspend fun quota() = QuotaInfo(total = 10_000_000, used = 0, remaining = 10_000_000)
    }
}
