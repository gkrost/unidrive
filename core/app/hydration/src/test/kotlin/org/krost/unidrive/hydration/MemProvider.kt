package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** An in-memory remote: a flat folder whose full listing is the delta. */
internal class MemProvider : CloudProvider {
    override val id = "mem"
    override val displayName = "Mem"
    override var isAuthenticated = true
    val remote = linkedMapOf<String, ByteArray>()
    val items = linkedMapOf<String, CloudItem>()
    val deleted = mutableListOf<String>()
    val downloads = mutableListOf<String>()
    private var version = 0

    // When set, upload() suspends here before storing anything: holds a background upload in its slot.
    @Volatile var uploadGate: CompletableDeferred<Unit>? = null

    /** A file that already exists on the remote with a fixed modified time (what an enumeration reports). */
    fun seed(
        path: String,
        bytes: ByteArray,
        modified: Instant = Instant.parse("2026-03-28T12:00:00Z"),
    ) {
        remote[path] = bytes
        items[path] =
            CloudItem(
                id = "id-$path",
                name = path.substringAfterLast("/"),
                path = path,
                size = bytes.size.toLong(),
                isFolder = false,
                modified = modified,
                created = modified,
                hash = "h-$path",
                mimeType = null,
            )
    }

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

    override suspend fun authenticate() {}

    override suspend fun listChildren(path: String): List<CloudItem> = items.values.toList()

    override suspend fun getMetadata(path: String): CloudItem = items[path] ?: error("not found: $path")

    override suspend fun download(remotePath: String, destination: Path): Long {
        downloads += remotePath
        Files.createDirectories(destination.parent)
        Files.write(destination, remote.getValue(remotePath))
        return remote.getValue(remotePath).size.toLong()
    }

    override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = download(remotePath, destination)

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        uploadGate?.await()
        val bytes = Files.readAllBytes(localPath)
        remote[remotePath] = bytes
        version++
        val at = Instant.parse("2026-03-28T12:00:00Z").plusSeconds(version.toLong())
        return CloudItem(
            id = "id-$remotePath",
            name = remotePath.substringAfterLast("/"),
            path = remotePath,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = at,
            created = at,
            hash = "v$version",
            mimeType = null,
        ).also { items[remotePath] = it }
    }

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        deleted += remotePath
        remote.remove(remotePath)
        items.remove(remotePath)
    }

    override suspend fun createFolder(path: String): CloudItem = error("not used")

    override suspend fun move(fromPath: String, toPath: String): CloudItem {
        val bytes = remote.remove(fromPath) ?: error("not found: $fromPath")
        val item = items.remove(fromPath) ?: error("not found: $fromPath")
        remote[toPath] = bytes
        version++
        return item
            .copy(
                id = "id-$toPath",
                name = toPath.substringAfterLast("/"),
                path = toPath,
            ).also { items[toPath] = it }
    }

    override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
        DeltaPage(items = items.values.toList(), cursor = "cursor", hasMore = false)

    override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
}
