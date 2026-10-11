package org.krost.unidrive.sync

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

/**
 * #737: decides whether a regular file found where [PlaceholderManager.createPlaceholder] needs a
 * parent folder may be replaced without a copy. [relativePath] is the file's sync-root-relative path
 * (`/x`). Answer true only with proof that the file's bytes are in the cloud; when in doubt, false:
 * the file is then kept as a conflict copy.
 */
fun interface FileInTheWayGuard {
    fun bytesProvablyInCloud(
        relativePath: String,
        file: Path,
    ): Boolean
}

/**
 * #737: a file is in the way of a placeholder's parent folder, its bytes are not proven to be in the
 * cloud, and it could not be kept as a conflict copy. The placeholder is refused and the file is left
 * exactly as it was.
 */
class FileInTheWayException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * #737: turns a regular file that sits where a parent folder must go into that folder without
 * losing its bytes. Before this, [PlaceholderManager.createPlaceholder] deleted the file, so a file
 * that was never uploaded, or had an edit not yet uploaded, was lost.
 */
internal object FileInTheWay {
    private val log = LoggerFactory.getLogger(FileInTheWay::class.java)

    // Free-name counter limit for one conflict copy (" (2)" … " (100)" within one minute).
    private const val MAX_COPY_NAME_ATTEMPTS = 100

    /**
     * The file is deleted only when [guard] proves its bytes are in the cloud. Otherwise it is
     * renamed with [move], in the same directory, to a keep-both style conflict copy
     * ([ConflictCopyNames]), and [onKeptAside] is told. If that rename fails, nothing was changed and
     * [FileInTheWayException] refuses the placeholder. Then [file] becomes a directory.
     */
    fun clear(
        root: Path,
        file: Path,
        guard: FileInTheWayGuard,
        onKeptAside: ((relativePath: String, copyRelativePath: String) -> Unit)?,
        move: (source: Path, target: Path) -> Unit,
    ) {
        val relativePath = relativeTo(root, file)
        val proven =
            runCatching { guard.bytesProvablyInCloud(relativePath, file) }
                .getOrElse { e ->
                    log.warn("Cannot tell whether {} is in the cloud ({}: {}); keeping a copy", relativePath, e.javaClass.simpleName, e.message)
                    false
                }
        if (proven) {
            log.info("Replacing {} with the remote's folder: its bytes are in the cloud", relativePath)
            Files.delete(file)
        } else {
            val copy = keepAside(file, relativePath, move)
            val copyRelativePath = relativeTo(root, copy)
            log.warn("{} was a file where the remote has a folder; its bytes are kept as {}", relativePath, copyRelativePath)
            runCatching { onKeptAside?.invoke(relativePath, copyRelativePath) }
                .onFailure { e ->
                    log.warn("Could not record the conflict copy {}: {}: {}", copyRelativePath, e.javaClass.simpleName, e.message)
                }
        }
        Files.createDirectories(file)
    }

    private fun keepAside(
        file: Path,
        relativePath: String,
        move: (source: Path, target: Path) -> Unit,
    ): Path {
        val now = Instant.now()
        val leaf = file.fileName.toString()
        var lastClash: FileAlreadyExistsException? = null
        for (n in 1..MAX_COPY_NAME_ATTEMPTS) {
            val target = file.resolveSibling(ConflictCopyNames.localCopyName(leaf, now, n))
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue
            // Nothing was changed if the rename failed: refuse, unless the name was just taken.
            val failure = runCatching { move(file, target) }.exceptionOrNull() ?: return target
            if (failure !is FileAlreadyExistsException) throw refused(relativePath, failure)
            lastClash = failure
        }
        throw FileInTheWayException(
            "Refusing to create a folder over $relativePath: no free conflict-copy name; the file is left as it was",
            lastClash,
        )
    }

    private fun refused(
        relativePath: String,
        cause: Throwable,
    ) = FileInTheWayException(
        "Refusing to create a folder over $relativePath: it could not be kept as a conflict copy " +
            "(${cause.javaClass.simpleName}: ${cause.message}); the file is left as it was",
        cause,
    )

    private fun relativeTo(
        root: Path,
        path: Path,
    ): String = "/" + root.relativize(path).toString().replace('\\', '/')
}
