package org.krost.unidrive.localfs

import org.krost.unidrive.*
import org.krost.unidrive.sync.computeSnapshotDelta
import org.slf4j.LoggerFactory
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * CloudProvider backed by a local directory tree.
 *
 * Useful for local-to-local sync (backup to second drive, Docker testing,
 * relocate between two directories) and as a reference implementation with
 * zero network dependencies.
 *
 * Delta strategy: full recursive walk of [LocalFsConfig.rootPath], compare
 * mtime + size against previous snapshot stored as Base64-encoded JSON cursor
 * (same pattern as SFTP and S3).
 *
 * Path containment (CWE-22): every path operation resolves against rootPath
 * and verifies the result starts with rootPath. Traversal outside rootPath
 * throws [IllegalArgumentException].
 */
class LocalFsProvider(
    val config: LocalFsConfig,
) : CloudProvider {
    private val log = LoggerFactory.getLogger(LocalFsProvider::class.java)

    override val id = "localfs"
    override val displayName = "Local Filesystem"

    override var isAuthenticated: Boolean = false
        private set
    override val canAuthenticate: Boolean get() = true

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.Delta,
            Capability.Share, // Returns file:// URIs — limited but real.
            Capability.QuotaExact,
            Capability.VerifyItem,
        )

    override suspend fun authenticate() {
        if (!Files.exists(config.rootPath)) {
            Files.createDirectories(config.rootPath)
            log.info("Created root directory: {}", config.rootPath)
        }
        isAuthenticated = true
    }

    override suspend fun logout() {
        isAuthenticated = false
    }

    override fun close() {}

    // -- Path containment (CWE-22) ------------------------------------------------

    /**
     * Resolve a relative remote path against [config.rootPath] and verify
     * the result is contained within rootPath. Rejects `..` traversal.
     *
     * UD-304: SyncEngine uses forward-slash canonical paths with a leading '/'
     * (e.g. "/docs/notes.md"). Java's Path.resolve treats absolute paths as
     * replacing the base — so we strip leading path separators before resolve.
     * `.normalize()` still catches `..` traversal after this.
     */
    internal fun safePath(relativePath: String): Path {
        val rel = relativePath.trimStart('/', '\\')
        val resolved = config.rootPath.resolve(rel).normalize()
        require(resolved.startsWith(config.rootPath)) {
            "Path traversal rejected: '$relativePath' resolves outside root"
        }
        return resolved
    }

    // -- File operations ----------------------------------------------------------

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val source = safePath(remotePath)
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        return Files.size(source)
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val target = safePath(remotePath)
        Files.createDirectories(target.parent)
        Files.copy(localPath, target, StandardCopyOption.REPLACE_EXISTING)
        return fileToCloudItem(target, remotePath)
    }

    override suspend fun delete(remotePath: String) {
        val target = safePath(remotePath)
        if (Files.isDirectory(target)) {
            Files.walk(target).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } else {
            Files.deleteIfExists(target)
        }
    }

    override suspend fun createFolder(path: String): CloudItem {
        val target = safePath(path)
        Files.createDirectories(target)
        return fileToCloudItem(target, path)
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val source = safePath(fromPath)
        val dest = safePath(toPath)
        Files.createDirectories(dest.parent)
        Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING)
        return fileToCloudItem(dest, toPath)
    }

    // -- Metadata -----------------------------------------------------------------

    override suspend fun listChildren(path: String): List<CloudItem> {
        val dir = safePath(path)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .map { child ->
                    val rel = config.rootPath.relativize(child).joinToString("/")
                    fileToCloudItem(child, rel)
                }.toList()
        }
    }

    override suspend fun getMetadata(path: String): CloudItem {
        val target = safePath(path)
        if (!Files.exists(target)) {
            throw IllegalStateException("Local file not found: $path")
        }
        return fileToCloudItem(target, path)
    }

    // -- Delta (snapshot-based) ---------------------------------------------------

    override suspend fun delta(cursor: String?): DeltaPage {
        val currentEntries = walkRoot()
        val snapshotEntries = buildSnapshotEntries(currentEntries)
        val itemsByPath =
            currentEntries.associate { (rel, attrs) ->
                rel to attrsToCloudItem(rel, attrs)
            }
        return computeSnapshotDelta(
            currentEntries = snapshotEntries,
            currentItemsByPath = itemsByPath,
            prevCursor = cursor,
            entrySerializer = LocalFsSnapshotEntry.serializer(),
            hasChanged = { prev, curr ->
                prev.size != curr.size || prev.mtimeMillis != curr.mtimeMillis
            },
            deletedItem = { path, entry ->
                CloudItem(
                    id = path,
                    name = path.substringAfterLast("/"),
                    path = path,
                    size = 0,
                    isFolder = entry.isFolder,
                    modified = null,
                    created = null,
                    hash = null,
                    mimeType = null,
                    deleted = true,
                )
            },
        )
    }

    // -- Quota --------------------------------------------------------------------

    override suspend fun quota(): QuotaInfo {
        val store: FileStore = Files.getFileStore(config.rootPath)
        val total = store.totalSpace
        val usable = store.usableSpace
        return QuotaInfo(total = total, used = total - usable, remaining = usable)
    }

    // -- Share --------------------------------------------------------------------

    override suspend fun share(
        path: String,
        expiryHours: Int,
        password: String?,
    ): CapabilityResult<String> {
        val target = safePath(path)
        return if (Files.exists(target)) {
            CapabilityResult.Success("file://${target.toAbsolutePath()}")
        } else {
            // Capability is present — file is just missing. Use Unsupported with a
            // clear reason so callers get a meaningful message.
            CapabilityResult.Unsupported(
                Capability.Share,
                "No such file: $path",
            )
        }
    }

    override suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> {
        // localfs uses the path as the ID — Files.exists() is the exact truth.
        return CapabilityResult.Success(Files.exists(safePath(remoteId)))
    }

    // -- Helpers ------------------------------------------------------------------

    private fun walkRoot(): List<Pair<String, BasicFileAttributes>> {
        if (!Files.exists(config.rootPath)) return emptyList()
        return Files.walk(config.rootPath).use { stream ->
            stream
                .filter { it != config.rootPath }
                .map { path ->
                    val rel = config.rootPath.relativize(path).joinToString("/")
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                    rel to attrs
                }.toList()
        }
    }

    private fun buildSnapshotEntries(entries: List<Pair<String, BasicFileAttributes>>): Map<String, LocalFsSnapshotEntry> =
        entries.associate { (rel, attrs) ->
            rel to
                LocalFsSnapshotEntry(
                    size = attrs.size(),
                    mtimeMillis = attrs.lastModifiedTime().toMillis(),
                    isFolder = attrs.isDirectory,
                )
        }

    private fun fileToCloudItem(
        file: Path,
        relativePath: String,
    ): CloudItem {
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
        return attrsToCloudItem(relativePath, attrs)
    }

    private fun attrsToCloudItem(
        relativePath: String,
        attrs: BasicFileAttributes,
    ): CloudItem {
        val name = relativePath.substringAfterLast("/").ifEmpty { relativePath }
        return CloudItem(
            id = relativePath,
            name = name,
            path = relativePath.ifEmpty { "/" },
            size = attrs.size(),
            isFolder = attrs.isDirectory,
            modified = attrs.lastModifiedTime().toInstant(),
            created = attrs.creationTime().toInstant(),
            hash = null,
            mimeType = if (attrs.isDirectory) null else "application/octet-stream",
        )
    }
}
