package org.krost.unidrive.localfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * A no-auth, local-filesystem [CloudProvider]. Treats one local directory
 * ([root], the profile's `root_path`) as the "cloud": every remote path maps to
 * a file under [root].
 *
 * Purpose: offline development and testing. There is no network and no
 * credentials, and [authenticate] always succeeds — so a daemon configured with
 * a `localfs` profile passes `DaemonRuntime`'s auth-before-bind gate and binds
 * its IPC socket without any cloud round-trip. This is the missing piece that
 * `ProviderRegistry.defaultProvider()` and the `SyncConfig` docs already
 * reference ("localfs … never fails the auth step") but the greenfield rewrite
 * never carried over.
 *
 * Remote paths are POSIX-style with a leading slash (`/dir/file.txt`); `/` is the
 * root. All path mapping is anti-traversal: a resolved path that escapes [root]
 * is rejected.
 */
class LocalFsProvider(root: Path) : CloudProvider {
    private val log = LoggerFactory.getLogger(LocalFsProvider::class.java)
    private val rootNorm: Path = root.toAbsolutePath().normalize()

    override val id: String = "localfs"
    override val displayName: String = "Local Filesystem"
    override var isAuthenticated: Boolean = false
    override val canAuthenticate: Boolean get() = true

    override fun capabilities(): Set<Capability> =
        setOf(Capability.Delta, Capability.QuotaExact, Capability.VerifyItem)

    override suspend fun authenticate() {
        withContext(Dispatchers.IO) { Files.createDirectories(rootNorm) }
        isAuthenticated = true
        log.debug("localfs authenticated, root={}", rootNorm)
    }

    // ── path mapping (anti-traversal) ───────────────────────────────────────

    private fun toLocal(remotePath: String): Path {
        // Strip only path separators, never whitespace: a remote filename may legally
        // start or end with spaces (e.g. "/dir/report "), and trimming them here would
        // silently redirect reads/writes to the wrong file.
        val rel = remotePath.trim('/')
        val resolved = if (rel.isEmpty()) rootNorm else rootNorm.resolve(rel).normalize()
        require(resolved == rootNorm || resolved.startsWith(rootNorm)) {
            "path escapes localfs root: $remotePath"
        }
        // Symlink-aware guard: a symlink *under* the root can still point outside it, and the
        // java.nio operations below follow symlinks — so the lexical check alone would let a
        // read/write/delete escape through a symlinked directory. Resolve the real path of the
        // deepest existing ancestor and require it to stay within the real root.
        if (Files.exists(rootNorm)) {
            val realRoot = rootNorm.toRealPath()
            var probe = resolved
            while (!Files.exists(probe) && probe.parent != null) probe = probe.parent
            val realProbe = probe.toRealPath()
            require(realProbe == realRoot || realProbe.startsWith(realRoot)) {
                "path escapes localfs root via symlink: $remotePath"
            }
        }
        return resolved
    }

    private fun toRemote(local: Path): String {
        val rel = rootNorm.relativize(local.toAbsolutePath().normalize()).toString().replace('\\', '/')
        return if (rel.isEmpty()) "/" else "/$rel"
    }

    private fun toItem(local: Path): CloudItem {
        val norm = local.toAbsolutePath().normalize()
        val isDir = Files.isDirectory(norm)
        val attrs = Files.readAttributes(norm, BasicFileAttributes::class.java)
        val remote = toRemote(norm)
        val parent = norm.parent
        val parentRemote = if (parent != null && parent.startsWith(rootNorm)) toRemote(parent) else null
        return CloudItem(
            id = remote,
            name = if (remote == "/") "" else norm.fileName.toString(),
            path = remote,
            size = if (isDir) 0L else attrs.size(),
            isFolder = isDir,
            modified = attrs.lastModifiedTime().toInstant(),
            created = attrs.creationTime().toInstant(),
            hash = null,
            mimeType = null,
            parentId = parentRemote,
        )
    }

    /**
     * True when an existing [local] entry's real path stays within the real root — the
     * symlink-safe filter for enumeration (listChildren / delta), mirroring [toLocal]'s
     * guard for direct operations. False if the path can't be resolved.
     */
    private fun withinRoot(local: Path): Boolean =
        runCatching {
            val realRoot = rootNorm.toRealPath()
            val real = local.toAbsolutePath().normalize().toRealPath()
            real == realRoot || real.startsWith(realRoot)
        }.getOrDefault(false)

    // ── reads ──────────────────────────────────────────────────────────────

    override suspend fun listChildren(path: String): List<CloudItem> =
        withContext(Dispatchers.IO) {
            val dir = toLocal(path)
            if (!Files.isDirectory(dir)) return@withContext emptyList<CloudItem>()
            Files.newDirectoryStream(dir).use { stream ->
                stream.filter { withinRoot(it) }.map { toItem(it) }
            }
        }

    override suspend fun getMetadata(path: String): CloudItem =
        withContext(Dispatchers.IO) {
            val p = toLocal(path)
            if (!Files.exists(p)) throw FileNotFoundException("localfs: no such item: $path")
            toItem(p)
        }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long =
        withContext(Dispatchers.IO) {
            val src = toLocal(remotePath)
            destination.parent?.let { Files.createDirectories(it) }
            Files.copy(src, destination, StandardCopyOption.REPLACE_EXISTING)
            Files.size(destination)
        }

    // ── writes ─────────────────────────────────────────────────────────────

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem =
        withContext(Dispatchers.IO) {
            val dest = toLocal(remotePath)
            dest.parent?.let { Files.createDirectories(it) }
            Files.copy(localPath, dest, StandardCopyOption.REPLACE_EXISTING)
            val size = Files.size(dest)
            onProgress?.invoke(size, size)
            toItem(dest)
        }

    override suspend fun delete(remotePath: String) {
        withContext(Dispatchers.IO) { toLocal(remotePath).toFile().deleteRecursively() }
    }

    override suspend fun createFolder(path: String): CloudItem =
        withContext(Dispatchers.IO) {
            val dir = toLocal(path)
            Files.createDirectories(dir)
            toItem(dir)
        }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem =
        withContext(Dispatchers.IO) {
            val from = toLocal(fromPath)
            val to = toLocal(toPath)
            to.parent?.let { Files.createDirectories(it) }
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            toItem(to)
        }

    // ── enumeration ──────────────────────────────────────────────────────────

    /**
     * Full-walk enumeration. localfs has no native change feed, so every call
     * returns the complete current inventory under [root]. `complete = true`
     * keeps the engine's absence-implies-deletion sweep enabled (the full set
     * is authoritative); the [cursor] is advisory (a wall-clock stamp) and
     * ignored on the next call. Folders are emitted before/with their children
     * via the natural pre-order walk.
     */
    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<CloudItem>()
            if (Files.exists(rootNorm)) {
                // Files.walk doesn't follow symlinks but still yields the link entry itself;
                // skip any entry whose real path escapes the root (see withinRoot).
                Files.walk(rootNorm).use { stream ->
                    stream.forEach { path ->
                        val norm = path.toAbsolutePath().normalize()
                        if (norm != rootNorm && withinRoot(path)) {
                            items.add(toItem(path))
                            if (items.size % 500 == 0) onPageProgress?.invoke(items.size)
                        }
                    }
                }
            }
            onPageProgress?.invoke(items.size)
            DeltaPage(items = items, cursor = System.currentTimeMillis().toString(), hasMore = false, complete = true)
        }

    override suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> =
        CapabilityResult.Success(withContext(Dispatchers.IO) { Files.exists(toLocal(remoteId)) })

    override suspend fun quota(): QuotaInfo =
        withContext(Dispatchers.IO) {
            var probe: Path = rootNorm
            while (!Files.exists(probe) && probe.parent != null) probe = probe.parent
            val store = Files.getFileStore(probe)
            val total = store.totalSpace
            val usable = store.usableSpace
            QuotaInfo(total = total, used = (total - usable).coerceAtLeast(0), remaining = usable)
        }
}
