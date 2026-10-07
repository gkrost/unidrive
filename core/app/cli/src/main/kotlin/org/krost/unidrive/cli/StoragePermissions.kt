package org.krost.unidrive.cli

import org.krost.unidrive.io.OwnerOnly
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Owner-only permissions ([OwnerOnly]) for what a profile keeps on disk, applied at every engine
 * start (`daemon run`, `sync`), so folders and files an earlier version created with inherited
 * permissions are tightened as well. Restricted folders are the unit: what is created in them later
 * inherits (Windows) or is unreachable for others (POSIX). The IPC socket folder is handled by
 * `IpcServer.defaultSocketPath`.
 */
internal object StoragePermissions {
    private val log = LoggerFactory.getLogger(StoragePermissions::class.java)

    /** The credential files a profile folder can hold (the ones `logout` removes). */
    val CREDENTIAL_FILES = listOf("token.json", "credentials.json")

    /**
     * The credential files of [profileDir] first, then the folder itself (it also holds state.db and
     * the journals). Throws when either cannot be restricted: the engine does not start on credentials
     * it cannot keep to the current user. A profile folder that does not exist yet is left alone (the
     * credential store restricts it when it creates it).
     */
    fun restrictProfile(profileDir: Path) {
        for (name in CREDENTIAL_FILES) {
            val file = profileDir.resolve(name)
            if (Files.isRegularFile(file)) timed(file) { OwnerOnly.requireFile(file) }
        }
        if (Files.isDirectory(profileDir)) timed(profileDir) { OwnerOnly.requireDirectory(profileDir) }
    }

    /**
     * Creates [cacheProfileRoot] (the hydration cache's `<cache>/unidrive/hydration/<profile>`) and
     * restricts it, then [logDir] when it exists. Best effort: a failure is logged once at WARN.
     */
    fun restrictCacheAndLogs(
        cacheProfileRoot: Path,
        logDir: Path?,
    ) {
        timed(cacheProfileRoot) {
            try {
                Files.createDirectories(cacheProfileRoot)
                OwnerOnly.restrictDirectory(cacheProfileRoot)
            } catch (e: Exception) {
                OwnerOnly.Outcome.Failed(e.message ?: e.javaClass.name)
            }
        }
        if (logDir != null && Files.isDirectory(logDir)) timed(logDir) { OwnerOnly.restrictDirectory(logDir) }
    }

    /** The folder logback writes unidrive.log to (see logback.xml: LOCALAPPDATA, else ~/.local/share). */
    fun defaultLogDir(): Path =
        Paths.get(System.getenv("LOCALAPPDATA") ?: Paths.get(System.getProperty("user.home"), ".local", "share").toString(), "unidrive")

    // Changing a folder that already holds many files takes a while on Windows (the entries of every
    // file in it are re-derived), so a change is logged with its duration.
    private fun timed(
        path: Path,
        restrict: () -> OwnerOnly.Outcome,
    ) {
        val started = System.nanoTime()
        val outcome = restrict()
        val ms = (System.nanoTime() - started) / 1_000_000
        when {
            outcome == OwnerOnly.Outcome.Changed -> log.info("Restricted {} to the current user ({} ms)", path, ms)
            !outcome.restricted -> log.warn("Could not restrict {} to the current user: {}", path, outcome)
        }
    }
}
