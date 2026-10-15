package org.krost.unidrive.localfs

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Root usability probes shared by the factory (`profile add` must refuse an unusable
 * `root_path` with a token the wizard can map) and the provider (`authenticate` must not
 * report an unreadable or read-only root as authenticated — the localfs account is a
 * two-way mirror, so both directions have to work) (#910).
 *
 * The probes are real operations, not attribute checks: `Files.isReadable`/`isWritable`
 * consult the read-only attribute on Windows and cannot see an ACL that denies a
 * `$RECYCLE.BIN`-style traversal, while opening a directory stream and creating a file
 * fail exactly the way the engine's own later operations would.
 */
internal object RootAccess {
    private val log = LoggerFactory.getLogger(RootAccess::class.java)

    /** Returns a refusal token when [root] is not usable as a sync root, null when it is. */
    fun validate(root: Path): String? {
        if (Files.exists(root) && !Files.isDirectory(root)) return "root_not_directory"
        try {
            Files.createDirectories(root)
        } catch (e: IOException) {
            log.warn("localfs root probe: cannot create {}: {}", root, e.message)
            return "root_not_creatable"
        }
        try {
            Files.newDirectoryStream(root).use { }
        } catch (e: IOException) {
            log.warn("localfs root probe: cannot read {}: {}", root, e.message)
            return "root_not_readable"
        }
        try {
            val probe = Files.createTempFile(root, ".unidrive-write-probe", null)
            Files.deleteIfExists(probe)
        } catch (e: IOException) {
            log.warn("localfs root probe: cannot write {}: {}", root, e.message)
            return "root_not_writable"
        }
        return null
    }
}
