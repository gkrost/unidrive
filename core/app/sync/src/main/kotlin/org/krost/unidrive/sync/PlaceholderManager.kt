package org.krost.unidrive.sync

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import org.krost.unidrive.engine.localNameIssue
import org.krost.unidrive.sync.model.SyncEntry

/**
 * Resolves a remote path (as received from cloud providers) against the sync root,
 * normalizing the result and rejecting any path that escapes the sync root via `..`
 * segments. Throws [SecurityException] on traversal attempts.
 *
 * This is the single enforcement point for the "all local paths stay inside syncRoot"
 * invariant. All code that maps a remote path to a local [Path] must call this.
 */
fun safeResolveLocal(
    syncRoot: Path,
    remotePath: String,
): Path {
    val normalizedRoot = syncRoot.toAbsolutePath().normalize()
    val resolved = normalizedRoot.resolve(remotePath.removePrefix("/")).normalize()
    if (!resolved.startsWith(normalizedRoot)) {
        throw SecurityException(
            "Refusing path traversal: remotePath='$remotePath' resolves to '$resolved' " +
                "which escapes syncRoot='$normalizedRoot'",
        )
    }
    // #171: the logical path is NFC, but a pre-existing on-disk file may be a
    // different Unicode form (NFD, e.g. a macOS-origin name) on a byte-preserving
    // filesystem. If the exact NFC path is absent, find the parent's child whose NFC
    // form matches so FS ops (upload read, isDirectory checks) reach the real bytes
    // instead of failing on the NFC spelling. The common case (NFC on disk) hits the
    // exact path and never scans. Returns the NFC path unchanged when no match exists
    // (a genuinely-new file: a later create/download writes the canonical NFC name).
    if (!Files.exists(resolved)) {
        val parent = resolved.parent
        val leaf = resolved.fileName?.toString()
        // Only a leaf with non-ASCII chars can have a differing Unicode form (NFC vs
        // NFD); a pure-ASCII name has no decomposed variant, so skip the O(n) parent
        // scan for it — that keeps a bulk download of ASCII-named files from going
        // O(n²) (every not-yet-created target would otherwise scan the growing dir).
        // A null leaf behaves like an empty one below: no non-ASCII chars, so the
        // NFC lookup is skipped for it.
        val nonAsciiLeafName = leaf.orEmpty().any { it.code > 0x7F }
        if (parent != null && nonAsciiLeafName && Files.isDirectory(parent)) {
            val leafNfc = PathNormalizer.nfc(leaf.orEmpty())
            val match =
                runCatching {
                    Files.newDirectoryStream(parent).use { ds ->
                        ds.firstOrNull { PathNormalizer.nfc(it.fileName.toString()) == leafNfc }
                    }
                }.getOrNull()
            if (match != null) return match
        }
    }
    return resolved
}

/**
 * #526: [safeResolveLocal] for row-driven passes (the pending-upload recovery, the delta's
 * resurrection arm). A name the filesystem cannot represent — a trailing space, creatable
 * through an extended-length path or a Linux tool — makes [Path.resolve] throw
 * [java.nio.file.InvalidPathException] on Windows, which aborted the whole sync from the
 * recovery loop. The row-driven passes skip such rows (null) instead; the scanner and the
 * plan function never see them (the walk's localNameIssue guard filters them first).
 */
internal fun safeResolveLocalOrNull(
    syncRoot: Path,
    remotePath: String,
): Path? =
    if (localNameIssue(remotePath) != null) {
        null
    } else {
        runCatching { safeResolveLocal(syncRoot, remotePath) }.getOrNull()
    }


// The shapes a placeholder or download artifact in the sync root can have — anything else
// is real user content, which the recovery download must never overwrite:
//  - a fresh placeholder / interrupted-before-first-byte download: a 0-byte stub
//    (createPlaceholder, or applyDownload killed before writing);
//  - a partial download: applyDownload writes the sync-root path directly, so a kill
//    mid-download leaves a prefix of the remote bytes; recovery finishing it is what
//    main's UD-225 loop already does, and uploading the prefix would truncate the remote;
//  - a freed placeholder: a sparse remoteSize of zeros stamped with the remote modified
//    time (dehydrate). A tool that touches the mtime afterwards (the touch-happy
//    property handlers from #396) must not turn it back into an upload of the stub, so
//    the zeros themselves are checked.
// [shorterIsPartialDownload] says whether a file shorter than the remote size counts as that
// partial download. LocalScanner passes false: every provider downloads to a temp name and renames
// it, so a short file at the real path is far more likely a user's short edit than a kill-truncated
// download, and keeping a stray prefix as a conflict copy loses nothing while overwriting an edit does.
internal fun looksLikePlaceholder(
    local: Path,
    entry: SyncEntry,
    shorterIsPartialDownload: Boolean = true,
): Boolean {
    val size = Files.size(local)
    if (size == 0L) return true
    if (shorterIsPartialDownload && size < entry.remoteSize) return true
    if (size == entry.remoteSize && entry.remoteSize > 0L) {
        val remoteModified = entry.remoteModified
        if (remoteModified != null &&
            Files.getLastModifiedTime(local).toMillis() == remoteModified.toEpochMilli()
        ) {
            return true
        }
        return isAllZero(local)
    }
    return false
}

internal fun isAllZero(path: Path): Boolean =
    runCatching {
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            var allZero = true
            while (allZero) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    if (buf[i] != 0.toByte()) {
                        allZero = false
                        break
                    }
                }
            }
            allZero
        }
    }.getOrDefault(false)

open class PlaceholderManager(
    protected val syncRoot: Path,
) {
    fun resolveLocal(remotePath: String): Path = safeResolveLocal(syncRoot, remotePath)

    // UD-222: placeholders never pre-allocate bytes. A stub is a 0-byte file + isHydrated=false in
    // the DB; real content arrives via provider.download. Previously setLength(size) was used as
    // a "sparse placeholder", but NTFS does not auto-sparse setLength — it fully allocates zero
    // bytes — so a 346 GB OneDrive turned into 346 GB of NUL-byte files on disk (UD-712 run).
    // UD-209a (2026-05-01): even on Linux, RandomAccessFile.setLength(N) is JDK-implementation-
    // dependent — JDK 21 jbrsdk emits ftruncate(0)+write(zeros, N) rather than ftruncate(N), so
    // setLength-based placeholders fully allocate the file there too. Apps opening the stub see
    // NUL bytes either way, indistinguishable from corruption. True placeholders belong to CfApi
    // (UD-401/402/403).
    open fun createPlaceholder(
        remotePath: String,
        size: Long,
        modified: Instant?,
    ) {
        val local = resolveLocal(remotePath)
        // A parent component may exist as a file when a folder and a same-named file share a path
        // (Internxt allows this since files and folders have separate API namespaces). Replace any
        // such file component with a directory so createDirectories succeeds.
        var check = local.parent
        while (check != syncRoot) {
            if (Files.isRegularFile(check)) {
                Files.deleteIfExists(check)
                Files.createDirectories(check)
                break
            }
            check = check.parent
        }
        Files.createDirectories(local.parent)

        Files.deleteIfExists(local)
        Files.createFile(local)

        if (modified != null) {
            Files.setLastModifiedTime(local, FileTime.from(modified))
        }
    }

    fun createFolder(
        remotePath: String,
        modified: Instant?,
    ) {
        val local = resolveLocal(remotePath)
        Files.createDirectories(local)
        if (modified != null) {
            Files.setLastModifiedTime(local, FileTime.from(modified))
        }
    }

    // UD-222: no-op on file size. Bump mtime only. See createPlaceholder for the rationale —
    // metadata bumps used to setLength(size), which on NTFS fully allocates NUL bytes.
    open fun updatePlaceholderMetadata(
        remotePath: String,
        size: Long,
        modified: Instant?,
    ) {
        val local = resolveLocal(remotePath)
        if (!Files.exists(local)) return
        if (modified != null) {
            Files.setLastModifiedTime(local, FileTime.from(modified))
        }
    }

    // UD-209a: produce a sparse file via FileChannel.truncate(0) + write-1-byte-at-N-1
    // instead of RandomAccessFile.setLength(0) + setLength(N). On JDK 21 jbrsdk on Linux,
    // the latter pattern emits ftruncate(0) + write(zeros, N) — a real page-allocating write —
    // not the ftruncate(N) hole-punch that JDK 25 emits. Strace evidence captured 2026-05-01.
    // The new pattern allocates only the trailing page (blocks=8 for any size that rounds up
    // to a single 4 KiB page), matching the isSparse(blocks * 512 < expectedSize) contract.
    open fun dehydrate(
        remotePath: String,
        remoteSize: Long,
        remoteModified: Instant?,
    ) {
        val local = resolveLocal(remotePath)
        FileChannel.open(local, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            if (remoteSize > 0L) {
                ch.position(remoteSize - 1L)
                ch.write(ByteBuffer.wrap(byteArrayOf(0)))
            }
        }
        if (remoteModified != null) {
            Files.setLastModifiedTime(local, FileTime.from(remoteModified))
        }
    }

    fun restoreMtime(
        remotePath: String,
        modified: Instant?,
    ) {
        if (modified == null) return
        val local = resolveLocal(remotePath)
        if (Files.exists(local)) {
            Files.setLastModifiedTime(local, FileTime.from(modified))
        }
    }

    open fun deleteLocal(remotePath: String) {
        val local = resolveLocal(remotePath)
        if (Files.isDirectory(local)) {
            Files.walkFileTree(
                local,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: java.io.IOException?,
                    ): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } else {
            Files.deleteIfExists(local)
        }
        cleanEmptyParents(local.parent)
    }

    fun isLocallyModified(
        remotePath: String,
        lastSyncedMtime: Long?,
    ): Boolean {
        val local = resolveLocal(remotePath)
        if (!Files.exists(local)) return false
        if (lastSyncedMtime == null) return true
        return Files.getLastModifiedTime(local).toMillis() != lastSyncedMtime
    }

    fun localExists(remotePath: String): Boolean = Files.exists(resolveLocal(remotePath))

    fun localMtime(remotePath: String): Long? {
        val local = resolveLocal(remotePath)
        return if (Files.exists(local)) Files.getLastModifiedTime(local).toMillis() else null
    }

    fun localSize(remotePath: String): Long? {
        val local = resolveLocal(remotePath)
        return if (Files.exists(local)) Files.size(local) else null
    }

    open fun isSparse(
        path: Path,
        expectedSize: Long,
    ): Boolean {
        if (!Files.isRegularFile(path) || expectedSize == 0L) return false
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win")) return false
        return try {
            val proc =
                ProcessBuilder("stat", "--format=%b", path.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start()
            val output =
                proc.inputStream
                    .bufferedReader()
                    .readLine()
                    ?.trim() ?: return false
            proc.waitFor()
            val blocks = output.toLongOrNull() ?: return false
            blocks * 512 < expectedSize
        } catch (_: Exception) {
            false
        }
    }

    protected fun cleanEmptyParents(dir: Path) {
        var current = dir
        while (current != syncRoot && Files.isDirectory(current)) {
            val isEmpty = Files.list(current).use { it.findFirst().isEmpty }
            if (isEmpty) {
                Files.deleteIfExists(current)
                current = current.parent
            } else {
                break
            }
        }
    }
}
