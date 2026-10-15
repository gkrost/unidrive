package org.krost.unidrive.engine

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The last line of the read-only rule: whichever part of the engine starts a write, an upload, a delete, a move, a
 * rename or a folder, it does not reach the provider when it names a path in the trash namespace or an item that was
 * placed there. Reading is not a write and passes.
 */
class ReadOnlyTrashProviderTest {
    private fun item(
        id: String,
        path: String,
        trashed: Boolean = false,
    ) = CloudItem(id, path.substringAfterLast('/'), path, 1, false, null, null, null, null, trashed = trashed)

    private val inner = RecordingProvider()
    private val trash = TrashNamespace(ignoreCase = false)
    private val provider = ReadOnlyTrashProvider(inner, trash)

    private val local: Path = Files.createTempFile("trash-readonly", ".bin")

    private fun enterTrash() {
        trash.place(listOf(item("f-in-trash", "/Old/a.txt", trashed = true), item("live", "/Live.txt"))) { emptyList() }
    }

    @Test
    fun `every write to a path in the namespace is refused before it reaches the provider`() =
        runTest {
            enterTrash()

            assertFailsWith<TrashReadOnlyException> { provider.upload(local, "/Papierkorb/Old/a.txt") }
            assertFailsWith<TrashReadOnlyException> { provider.upload(local, "/Papierkorb/Old/new.txt", existingRemoteId = null) }
            assertFailsWith<TrashReadOnlyException> { provider.delete("/Papierkorb/Old/a.txt") }
            assertFailsWith<TrashReadOnlyException> { provider.delete("/Papierkorb") }
            assertFailsWith<TrashReadOnlyException> { provider.deleteById("f-in-trash", "/Papierkorb/Old/a.txt") }
            assertFailsWith<TrashReadOnlyException> { provider.createFolder("/Papierkorb/Old/sub") }
            assertFailsWith<TrashReadOnlyException> { provider.createFolders(listOf("/Fine", "/Papierkorb/x")) }
            assertFailsWith<TrashReadOnlyException> { provider.move("/Papierkorb/Old/a.txt", "/Elsewhere/a.txt") }
            assertFailsWith<TrashReadOnlyException> { provider.move("/Live.txt", "/Papierkorb/Live.txt") }
            assertFailsWith<TrashReadOnlyException> { provider.moveById("f-in-trash", "/Papierkorb/Old/a.txt", "/Papierkorb/Old/b.txt") }

            assertEquals(emptyList(), inner.calls, "nothing reached the provider")
        }

    @Test
    fun `an item that was placed in the namespace is refused by id whatever path the call carries`() =
        runTest {
            enterTrash()

            // A row that has not been moved yet still addresses the item by its old place: the path is no help, the id is.
            assertFailsWith<TrashReadOnlyException> { provider.upload(local, "/Old/a.txt", existingRemoteId = "f-in-trash") }
            assertFailsWith<TrashReadOnlyException> { provider.deleteById("f-in-trash", "/Old/a.txt") }
            assertFailsWith<TrashReadOnlyException> { provider.moveById("f-in-trash", "/Old/a.txt", "/New/a.txt") }
            assertFailsWith<TrashReadOnlyException> { provider.deleteById(TrashNamespace.ROOT_ID, "/anything") }

            assertEquals(emptyList(), inner.calls)
        }

    @Test
    fun `writes outside the namespace pass through untouched`() =
        runTest {
            enterTrash()

            provider.upload(local, "/Live.txt", existingRemoteId = "live")
            provider.delete("/Live.txt")
            provider.deleteById("live", "/Live.txt")
            provider.createFolder("/Fine")
            provider.createFolders(listOf("/A", "/B"))
            provider.move("/Live.txt", "/Moved.txt")
            provider.moveById("live", "/Live.txt", "/Moved.txt")

            assertEquals(
                listOf(
                    "upload /Live.txt live",
                    "delete /Live.txt",
                    "deleteById live /Live.txt",
                    "createFolder /Fine",
                    "createFolder /A",
                    "createFolder /B",
                    "move /Live.txt /Moved.txt",
                    "moveById live /Live.txt /Moved.txt",
                ),
                inner.calls,
            )
        }

    @Test
    fun `reading from the namespace is not a write`() =
        runTest {
            enterTrash()
            val target = Files.createTempFile("trash-readonly-read", ".bin")

            provider.listChildren("/Papierkorb/Old")
            provider.getMetadata("/Papierkorb/Old/a.txt")
            provider.download("/Papierkorb/Old/a.txt", target)
            provider.downloadById("f-in-trash", "/Papierkorb/Old/a.txt", target)

            assertEquals(
                listOf(
                    "listChildren /Papierkorb/Old",
                    "getMetadata /Papierkorb/Old/a.txt",
                    "download /Papierkorb/Old/a.txt",
                    "downloadById f-in-trash /Papierkorb/Old/a.txt",
                ),
                inner.calls,
            )
        }

    @Test
    fun `while nothing is in the trash the name is free and nothing is refused`() =
        runTest {
            provider.createFolder("/Papierkorb")
            provider.upload(local, "/Papierkorb/x.txt")

            assertEquals(listOf("createFolder /Papierkorb", "upload /Papierkorb/x.txt null"), inner.calls)
        }

    private class RecordingProvider : CloudProvider {
        val calls = mutableListOf<String>()

        override val id = "recording"
        override val displayName = "recording"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        private fun folder(path: String) = CloudItem("id-$path", path.substringAfterLast('/'), path, 0, true, null, null, null, null)

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList<CloudItem>().also { calls += "listChildren $path" }

        override suspend fun getMetadata(path: String): CloudItem = folder(path).also { calls += "getMetadata $path" }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = 0L.also { calls += "download $remotePath" }

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long = 0L.also { calls += "downloadById $remoteId $remotePath" }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = folder(remotePath).also { calls += "upload $remotePath $existingRemoteId" }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            calls += "delete $remotePath"
        }

        override suspend fun deleteById(
            remoteId: String,
            remotePath: String,
        ) {
            calls += "deleteById $remoteId $remotePath"
        }

        override suspend fun createFolder(path: String): CloudItem = folder(path).also { calls += "createFolder $path" }

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = folder(toPath).also { calls += "move $fromPath $toPath" }

        override suspend fun moveById(
            remoteId: String,
            fromPath: String,
            toPath: String,
        ): CloudItem = folder(toPath).also { calls += "moveById $remoteId $fromPath $toPath" }

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(emptyList(), "c", false)

        override suspend fun quota(): QuotaInfo = error("not used")
    }
}
