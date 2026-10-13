package org.krost.unidrive.cli

import org.krost.unidrive.io.OwnerOnly
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Serializes complete config read/check/write transactions across CLI threads and processes. */
internal object ConfigFileTransaction {
    private val processLock = Any()

    fun <T> withLock(
        configPath: Path,
        action: () -> T,
    ): T = synchronized(processLock) {
        val path = configPath.toAbsolutePath().normalize()
        val parent = path.parent ?: throw java.io.IOException("config path has no parent: $path")
        Files.createDirectories(parent)
        val lockPath = parent.resolve("${path.fileName}.lock")
        createLockFile(lockPath)

        FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
            OwnerOnly.requireFile(lockPath)
            channel.lock().use { action() }
        }
    }

    /** Replace a config through a sibling temp file; callers must hold [withLock] for read-modify-write edits. */
    fun writeAtomically(
        target: Path,
        text: String,
    ) {
        target.parent?.let(Files::createDirectories)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        try {
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun createLockFile(lockPath: Path) {
        if (Files.exists(lockPath)) return
        try {
            Files.createFile(
                lockPath,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        } catch (_: UnsupportedOperationException) {
            try {
                Files.createFile(lockPath)
            } catch (_: FileAlreadyExistsException) {
                // Another process created the persistent lock file.
            }
        } catch (_: FileAlreadyExistsException) {
            // Another process created the persistent lock file.
        }
    }
}
