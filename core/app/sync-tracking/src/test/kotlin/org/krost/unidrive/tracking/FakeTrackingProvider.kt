package org.krost.unidrive.tracking

import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * In-memory `CloudProvider` for the tracking-set integration test.
 * Intentionally minimal — only the surface the engine actually uses
 * (delta, download, upload, delete, getMetadata, capabilities).
 *
 * Tests seed `files["/path"] = bytes` to make a remote exist. The
 * delta page enumerates the current `files` map. Hashes are SHA-256
 * of the bytes, so a faithful download → local-hash match → adopt
 * round-trip works exactly as the real provider would.
 */
class FakeTrackingProvider : CloudProvider {
    override val id = "fake"
    override val displayName = "Fake"
    override var isAuthenticated: Boolean = true

    val files: MutableMap<String, ByteArray> = mutableMapOf()
    val uploadedPaths: MutableList<String> = mutableListOf()
    val deletedPaths: MutableList<String> = mutableListOf()

    override fun capabilities(): Set<Capability> =
        setOf(Capability.Delta, Capability.VerifyItem)

    override suspend fun authenticate() {}

    override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

    override suspend fun getMetadata(path: String): CloudItem {
        val bytes = files[path] ?: throw NoSuchElementException("no remote at $path")
        return itemFor(path, bytes)
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val bytes = files[remotePath] ?: throw NoSuchElementException("no remote at $remotePath")
        Files.createDirectories(destination.parent)
        Files.write(destination, bytes)
        return bytes.size.toLong()
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val bytes = Files.readAllBytes(localPath)
        files[remotePath] = bytes
        uploadedPaths += remotePath
        return itemFor(remotePath, bytes)
    }

    override suspend fun delete(remotePath: String) {
        files.remove(remotePath)
        deletedPaths += remotePath
    }

    override suspend fun createFolder(path: String): CloudItem =
        CloudItem(
            id = "folder-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = 0,
            isFolder = true,
            modified = Instant.now(),
            created = Instant.now(),
            hash = null,
            mimeType = null,
        )

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val bytes = files.remove(fromPath) ?: throw NoSuchElementException("no remote at $fromPath")
        files[toPath] = bytes
        return itemFor(toPath, bytes)
    }

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage {
        val items = files.entries.map { (path, bytes) -> itemFor(path, bytes) }
        return DeltaPage(items = items, cursor = "fake-cursor", hasMore = false)
    }

    override suspend fun quota(): QuotaInfo = QuotaInfo(total = 1_000_000, used = 0, remaining = 1_000_000)

    override suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> =
        CapabilityResult.Success(files.values.any { sha256Hex(it) == remoteId.removePrefix("hash-") })

    private fun itemFor(
        path: String,
        bytes: ByteArray,
    ): CloudItem {
        val hash = sha256Hex(bytes)
        return CloudItem(
            id = "hash-$hash",
            name = path.substringAfterLast('/'),
            path = path,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = Instant.parse("2026-05-21T00:00:00Z"),
            created = Instant.parse("2026-05-21T00:00:00Z"),
            hash = hash,
            mimeType = "application/octet-stream",
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256")
        return d.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
