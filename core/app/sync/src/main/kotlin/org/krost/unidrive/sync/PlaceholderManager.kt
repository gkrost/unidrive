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
    return resolved
}

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
