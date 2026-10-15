package org.krost.unidrive.localfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import org.krost.unidrive.io.withLocalIoRetry

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
        withContext(Dispatchers.IO) {
            Files.createDirectories(rootNorm)
            // #910: the account is a two-way mirror, so "authenticated" has to mean the root is
            // actually usable, not merely present — a profile pointed at an unreadable or
            // read-only directory must fail here with its token instead of surfacing later as
            // a mysterious `io` error on the first transfer.
            val token = RootAccess.validate(rootNorm)
            if (token != null) {
                throw ProviderException("localfs $token: $rootNorm is not usable as a sync root")
            }
        }
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
            withLocalIoRetry { copyDurably(src, destination) }
            Files.size(destination)
        }

    // ── writes ─────────────────────────────────────────────────────────────

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        // #291: a filesystem copy has no optimistic-concurrency token; the param is ignored.
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem =
        withContext(Dispatchers.IO) {
            val dest = toLocal(remotePath)
            dest.parent?.let { Files.createDirectories(it) }
            withLocalIoRetry { copyDurably(localPath, dest) }
            val size = Files.size(dest)
            onProgress?.invoke(size, size)
            toItem(dest)
        }

    /** Copies keeping the source mtime (the JDK stamps "now" on Linux otherwise) and flushes the target to disk. */
    private fun copyDurably(
        from: Path,
        to: Path,
    ) {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        FileChannel.open(to, StandardOpenOption.WRITE).use { it.force(true) }
    }

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        withContext(Dispatchers.IO) { deleteRecursivelyNoFollow(toLocal(remotePath)) }
    }

    /**
     * Recursive delete that never follows symlinks: a symlinked entry is removed as the link
     * itself, not descended into. Prevents a symlink *inside* a deleted directory from
     * redirecting the delete outside root_path (kotlin's File.deleteRecursively / FileTreeWalk
     * would follow it).
     */
    private fun deleteRecursivelyNoFollow(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(path).use { stream -> stream.forEach { deleteRecursivelyNoFollow(it) } }
        }
        Files.deleteIfExists(path)
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
            withLocalIoRetry { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING) }
            toItem(to)
        }

    // ── enumeration ──────────────────────────────────────────────────────────

    /**
     * Full-walk enumeration. localfs has no native change feed, so every call
     * returns the current inventory under [root]. The walk is tolerant (#910): a
     * directory that cannot be read (an NTFS `$RECYCLE.BIN` per-SID folder, a
     * `System Volume Information`) is skipped, logged (once per path), and the
     * page comes back `complete = false` — the engine must not infer deletions
     * for a part of the tree it never saw. The well-known Windows system folders
     * are skipped by policy even when readable, without flipping the page
     * incomplete: they are never meant to sync. The [cursor] is advisory (a
     * wall-clock stamp) and ignored on the next call. Folders are emitted
     * before/with their children via the natural pre-order walk.
     */
    override val deltaIsFullListing: Boolean get() = true

    /** The paths the last [delta] skipped, oldest first, capped at twenty. */
    var lastDeltaSkippedPaths: List<String> = emptyList()
        private set

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<CloudItem>()
            val skips = DeltaSkips()
            if (Files.exists(rootNorm)) {
                // walkFileTree instead of Files.walk: a stream throws out of forEach on the
                // first unreadable directory and aborts the whole listing; the visitor skips
                // the entry (visitFileFailed) and lets the rest through.
                Files.walkFileTree(
                    rootNorm,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            dir: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            val norm = dir.toAbsolutePath().normalize()
                            if (norm == rootNorm) return FileVisitResult.CONTINUE
                            val name = norm.fileName.toString()
                            if (systemFolderNames.any { it.equals(name, ignoreCase = true) }) {
                                skips.policySkip(norm)
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            // A symlink whose real path escapes the root is a policy skip (see
                            // withinRoot); an unreadable directory is an incomplete one.
                            if (!withinRoot(dir)) return FileVisitResult.SKIP_SUBTREE
                            try {
                                items.add(toItem(dir))
                            } catch (e: IOException) {
                                skips.unreadable(norm, e)
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            if (withinRoot(file)) {
                                try {
                                    items.add(toItem(file))
                                    if (items.size % 500 == 0) onPageProgress?.invoke(items.size)
                                } catch (e: IOException) {
                                    skips.unreadable(file.toAbsolutePath().normalize(), e)
                                }
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            e: IOException,
                        ): FileVisitResult {
                            skips.unreadable(file.toAbsolutePath().normalize(), e)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            }
            skips.summarize()
            lastDeltaSkippedPaths = skips.names
            onPageProgress?.invoke(items.size)
            DeltaPage(
                items = items,
                cursor = System.currentTimeMillis().toString(),
                hasMore = false,
                complete = !skips.hasUnreadable,
            )
        }

    /** Windows folders that exist on every drive and are never meant to be synced. */
    private val systemFolderNames = setOf("\$RECYCLE.BIN", "System Volume Information")

    private val skipNamesKept = 20

    /**
     * Skips of one walk. A policy skip (system folder, symlink escape) is deliberate; an
     * unreadable entry means part of the tree is unknown and the page must not read as
     * complete. Paths are recorded once each, capped so a hostile tree cannot grow the
     * list without bound.
     */
    private inner class DeltaSkips {
        private val seen = linkedSetOf<String>()
        var hasUnreadable = false
            private set

        val names: List<String> get() = seen.toList()

        fun policySkip(path: Path) {
            record(path)
            log.debug("localfs: skipping {} (system folder or outside the real root)", path)
        }

        fun unreadable(
            path: Path,
            e: IOException,
        ) {
            hasUnreadable = true
            if (record(path)) {
                log.warn("localfs: skipping unreadable path {} ({}); this enumeration is incomplete", path, e.message)
            }
        }

        fun summarize() {
            if (hasUnreadable) {
                log.warn(
                    "localfs: enumeration skipped {} unreadable path(s), first: {}; reporting the listing as incomplete",
                    seen.size,
                    seen.take(5),
                )
            }
        }

        private fun record(path: Path): Boolean =
            if (seen.size < skipNamesKept) seen.add(path.toString()) else false
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
