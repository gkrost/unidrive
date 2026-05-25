package org.krost.unidrive.tracking

import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaCursorExpiredException
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * In-memory `CloudProvider` for the tracking-set integration test.
 * Intentionally minimal — only the surface the engine actually uses
 * (delta, download, upload, delete, getMetadata, capabilities).
 *
 * Tests seed `files["/path"] = bytes` to make a remote exist. The
 * delta page enumerates the current `files` map. Hashes are SHA-256
 * of the bytes, so a faithful download → local-hash match → adopt
 * round-trip works exactly as the real provider would.
 */
class FakeTrackingProvider : CloudProvider {
    override val id = "fake"
    override val displayName = "Fake"
    override var isAuthenticated: Boolean = true

    val files: MutableMap<String, ByteArray> = mutableMapOf()
    val uploadedPaths: MutableList<String> = mutableListOf()
    val deletedPaths: MutableList<String> = mutableListOf()

    /**
     * Test hook: the cursor passed to every [delta] invocation, in call
     * order. Lets tests pin that a subsequent pass resumes from the cursor
     * a prior pass ended on rather than from an empty/initial cursor.
     */
    val deltaCursors: MutableList<String?> = mutableListOf()

    /** Test hook: the cursor value the next [delta] page reports. */
    var nextCursor: String = "fake-cursor"

    /**
     * Test hook: flip to false to simulate a provider whose delta enumeration
     * couldn't gather the full inventory (transient API failure, throttling
     * mid-walk, etc.). The returned [DeltaPage] still carries [files] but
     * marks `complete = false` so the engine knows the view is partial.
     */
    var deltaComplete: Boolean = true

    /**
     * Test hook: cursor-aware incremental delta. A real provider returns the
     * FULL inventory only when `delta(null)` is called; once resumed from a
     * non-null cursor it returns just the items changed (or deleted) since
     * that cursor.
     *
     * When [incrementalAware] is true and [delta] is called with a NON-null
     * cursor, the page carries ONLY:
     *   - [incrementalChanges] — paths whose content changed since the cursor
     *     (these are also written into [files] so a subsequent download/adopt
     *     round-trips); and
     *   - [incrementalDeletes] — paths the delta explicitly marks deleted
     *     (emitted as `CloudItem(deleted = true)`; also removed from [files]).
     * Every other tracked path is simply OMITTED — exactly the shape that
     * tricked the pre-fix engine into reading "absent ⇒ remote-gone".
     *
     * When [incrementalAware] is false (default) the fake keeps its legacy
     * behaviour: every [delta] returns the full [files] inventory regardless
     * of cursor. This keeps the existing full-pass tests untouched.
     */
    var incrementalAware: Boolean = false

    /** Path→bytes to report as CHANGED on an incremental (non-null cursor) delta. */
    val incrementalChanges: MutableMap<String, ByteArray> = mutableMapOf()

    /** Paths to report as DELETED on an incremental (non-null cursor) delta. */
    val incrementalDeletes: MutableList<String> = mutableListOf()

    /**
     * UD-410 test hook: when [delta] is called with a cursor equal to this
     * value, throw [DeltaCursorExpiredException] (the 410-Gone signal). Models
     * a stored cursor that aged out / a re-keyed drive. The recovery pass
     * re-enters [delta] with `cursor = null`, which returns the full [files]
     * inventory as usual — so a tracked path genuinely removed during the
     * stale window is absent from the full enum (→ remote-gone) and an
     * unchanged path is present.
     */
    var expiredCursor: String? = null

    /**
     * UD-410 test hook: flip to true to make the post-410 FULL re-enumeration
     * itself fail (a `cursor == null` delta throws). Lets a test pin that a
     * failed recovery falls back to `complete = false` (deletes suppressed)
     * rather than throwing out of the engine.
     */
    var failFullReenumeration: Boolean = false

    override fun capabilities(): Set<Capability> =
        setOf(Capability.Delta, Capability.VerifyItem)

    override suspend fun authenticate() {}

    override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

    override suspend fun getMetadata(path: String): CloudItem {
        val bytes = files[path] ?: throw NoSuchElementException("no remote at $path")
        return itemFor(path, bytes)
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val bytes = files[remotePath] ?: throw NoSuchElementException("no remote at $remotePath")
        Files.createDirectories(destination.parent)
        Files.write(destination, bytes)
        return bytes.size.toLong()
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val bytes = Files.readAllBytes(localPath)
        files[remotePath] = bytes
        uploadedPaths += remotePath
        return itemFor(remotePath, bytes)
    }

    override suspend fun delete(remotePath: String) {
        files.remove(remotePath)
        deletedPaths += remotePath
    }

    override suspend fun createFolder(path: String): CloudItem =
        CloudItem(
            id = "folder-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = 0,
            isFolder = true,
            modified = Instant.now(),
            created = Instant.now(),
            hash = null,
            mimeType = null,
        )

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val bytes = files.remove(fromPath) ?: throw NoSuchElementException("no remote at $fromPath")
        files[toPath] = bytes
        return itemFor(toPath, bytes)
    }

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage {
        deltaCursors += cursor
        if (cursor != null && cursor == expiredCursor) {
            throw DeltaCursorExpiredException("simulated 410 Gone on cursor=$cursor")
        }
        if (cursor == null && failFullReenumeration) {
            throw org.krost.unidrive.ProviderException("simulated full re-enumeration failure")
        }
        val items =
            if (incrementalAware && cursor != null) {
                // Incremental delta: only changed + explicitly-deleted items.
                // Apply the seeded mutations to the backing store so a
                // follow-on download/adopt round-trips faithfully, then emit
                // exactly those items — every untouched tracked path is omitted.
                val deletedItems =
                    incrementalDeletes.map { path ->
                        val bytes = files.remove(path)
                        itemFor(path, bytes ?: ByteArray(0)).copy(deleted = true)
                    }
                val changedItems =
                    incrementalChanges.map { (path, bytes) ->
                        files[path] = bytes
                        itemFor(path, bytes)
                    }
                deletedItems + changedItems
            } else {
                files.entries.map { (path, bytes) -> itemFor(path, bytes) }
            }
        return DeltaPage(items = items, cursor = nextCursor, hasMore = false, complete = deltaComplete)
    }

    override suspend fun quota(): QuotaInfo = QuotaInfo(total = 1_000_000, used = 0, remaining = 1_000_000)

    override suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> =
        CapabilityResult.Success(files.values.any { sha256Hex(it) == remoteId.removePrefix("hash-") })

    private fun itemFor(
        path: String,
        bytes: ByteArray,
    ): CloudItem {
        val hash = sha256Hex(bytes)
        return CloudItem(
            id = "hash-$hash",
            name = path.substringAfterLast('/'),
            path = path,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = Instant.parse("2026-05-21T00:00:00Z"),
            created = Instant.parse("2026-05-21T00:00:00Z"),
            hash = hash,
            mimeType = "application/octet-stream",
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256")
        return d.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
