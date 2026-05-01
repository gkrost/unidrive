package org.krost.unidrive.auth

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.krost.unidrive.io.setPosixPermissionsIfSupported
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * UD-344: shared credential-file load / save / delete with the
 * UD-312 atomic-write invariant baked in.
 *
 * Pre-UD-344 OneDrive, HiDrive and Internxt each implemented the same
 * "load from $dir/$file → parse → discard on parse error" + "write
 * → chmod 600" flow. OneDrive added UD-312 (atomic-move + shape
 * guard) on top. HiDrive and Internxt copied the basic flow **without
 * UD-312** — both had a real (small) crash-window race the UD-312
 * OneDrive comment explicitly identifies.
 *
 * Lifted here so:
 *   - HiDrive + Internxt inherit UD-312 atomic-write —
 *     partial-write crashes no longer leave corrupted credential files.
 *   - OneDrive's existing `hasPlausibleAccessTokenShape()` shape
 *     guard wires through the optional [validate] hook.
 *
 * Construction is configuration only; no I/O happens until [load],
 * [save] or [delete] is called.
 */
public class CredentialStore<T>(
    private val dir: Path,
    private val fileName: String,
    private val serializer: KSerializer<T>,
    private val validate: (T) -> Boolean = { true },
) {
    private val log = org.slf4j.LoggerFactory.getLogger(CredentialStore::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val file: Path get() = dir.resolve(fileName)
    private val tmpFile: Path get() = dir.resolve("$fileName.tmp")

    /**
     * Load and parse the credential file. Returns `null` if:
     *   - the file does not exist, or
     *   - parsing throws (treated as corrupt JSON; logged and swallowed
     *     so the caller falls through to a fresh authentication path), or
     *   - [validate] returns `false` (UD-312 lineage — caller-supplied
     *     shape guard for "syntactically parseable but obviously bad",
     *     e.g. empty access_token).
     */
    public fun load(): T? {
        if (!Files.exists(file)) return null

        return try {
            val parsed = json.decodeFromString(serializer, Files.readString(file))
            if (!validate(parsed)) {
                log.warn(
                    "Credential file at {} failed the validate() guard — discarding so caller refreshes",
                    file,
                )
                return null
            }
            parsed
        } catch (e: Exception) {
            log.warn("Failed to load credentials from {}: {}", file, e.message)
            null
        }
    }

    /**
     * Persist [value] atomically.
     *
     * UD-312 invariant: write to a sibling `.tmp` file then
     * `Files.move(... ATOMIC_MOVE)` so any concurrent reader (another
     * coroutine on the same JVM, or the MCP / tray reading the same
     * file) sees either the old contents or the new contents — never
     * a half-written truncate-and-rewrite. Falls back to a non-atomic
     * `REPLACE_EXISTING` move on filesystems that reject `ATOMIC_MOVE`
     * (some Windows network shares); the race window shrinks but doesn't
     * fully close — that's a documented limitation.
     *
     * Side-effects: chmod the directory `rwx------` and the file
     * `rw-------` on POSIX filesystems via
     * [setPosixPermissionsIfSupported]; no-op on Windows / FAT.
     */
    public fun save(value: T) {
        Files.createDirectories(dir)
        setPosixPermissionsIfSupported(dir, ownerRwx = true)
        Files.writeString(tmpFile, json.encodeToString(serializer, value))
        try {
            Files.move(
                tmpFile,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            log.warn("Filesystem rejected ATOMIC_MOVE at {} — falling back to non-atomic replace", file)
            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING)
        }
        setPosixPermissionsIfSupported(file, ownerRwx = false)
    }

    /** Delete the credential file if it exists. No-op otherwise. */
    public fun delete() {
        Files.deleteIfExists(file)
    }
}
