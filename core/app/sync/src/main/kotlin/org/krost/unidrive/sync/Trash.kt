package org.krost.unidrive.sync

import org.slf4j.LoggerFactory
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * FreeDesktop.org Trash spec implementation.
 * Moves files to $XDG_DATA_HOME/Trash/ with .trashinfo metadata.
 * Falls back to permanent deletion if trash is unavailable.
 */
object Trash {
    private val log = LoggerFactory.getLogger(Trash::class.java)
    private val TRASH_DATE_FORMAT =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneId.systemDefault())

    /**
     * Move a file or directory to the FreeDesktop Trash.
     * Returns true if trashed, false if permanently deleted (fallback).
     */
    fun trash(path: Path): Boolean {
        if (!Files.exists(path)) return true

        val trashDir =
            resolveTrashDir() ?: run {
                log.debug("No trash directory available, deleting permanently: {}", path)
                deletePermanently(path)
                return false
            }

        val filesDir = trashDir.resolve("files")
        val infoDir = trashDir.resolve("info")
        Files.createDirectories(filesDir)
        Files.createDirectories(infoDir)

        // Generate unique trash name (append .2, .3 etc. on collision)
        val baseName = path.fileName.toString()
        var trashName = baseName
        var counter = 2
        while (Files.exists(filesDir.resolve(trashName)) || Files.exists(infoDir.resolve("$trashName.trashinfo"))) {
            val dot = baseName.lastIndexOf('.')
            trashName =
                if (dot > 0) {
                    "${baseName.substring(0, dot)}.$counter${baseName.substring(dot)}"
                } else {
                    "$baseName.$counter"
                }
            counter++
        }

        // Write .trashinfo metadata
        val trashInfo =
            """
            |[Trash Info]
            |Path=${path.toAbsolutePath()}
            |DeletionDate=${TRASH_DATE_FORMAT.format(Instant.now())}
            """.trimMargin() + "\n"

        try {
            Files.writeString(
                infoDir.resolve("$trashName.trashinfo"),
                trashInfo,
                StandardOpenOption.CREATE_NEW,
            )
            Files.move(path, filesDir.resolve(trashName))
            return true
        } catch (e: Exception) {
            log.warn("Failed to trash {}, deleting permanently: {}", path, e.message)
            // Clean up partial trashinfo if move failed
            Files.deleteIfExists(infoDir.resolve("$trashName.trashinfo"))
            deletePermanently(path)
            return false
        }
    }

    internal fun resolveTrashDir(): Path? {
        val xdgDataHome = System.getenv("XDG_DATA_HOME")
        val trashDir =
            if (xdgDataHome != null) {
                Paths.get(xdgDataHome, "Trash")
            } else {
                val home = System.getenv("HOME") ?: System.getProperty("user.home") ?: return null
                Paths.get(home, ".local", "share", "Trash")
            }
        return trashDir
    }

    private fun deletePermanently(path: Path) {
        if (Files.isDirectory(path)) {
            Files.walkFileTree(
                path,
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
            Files.deleteIfExists(path)
        }
    }
}
