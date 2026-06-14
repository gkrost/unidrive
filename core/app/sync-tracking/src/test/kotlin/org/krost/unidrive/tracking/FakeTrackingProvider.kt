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

    // ── JWT / OAuth-refresh fault-injection hooks ────────────────────────────
    //
    // A real provider's HTTP client owns token refresh: a 401 mid-enumeration
    // triggers a refresh-token round-trip inside `delta()` and the call retries
    // transparently, so the tracking engine never sees the blip. These hooks
    // model that boundary inside the fake's `delta()` — the only place the
    // engine touches the provider during enumeration.

    /** Test hook: the JWT is considered expired starting at this 0-based
     *  [delta] call index. -1 (default) means the token never expires. */
    var tokenExpiresAtDeltaCall: Int = -1

    /** Test hook: when true, an expired-token [delta] transparently
     *  refreshes (increments [refreshCount]) and the retried call succeeds —
     *  mirroring a provider whose HTTP layer recovers a 401 via refresh-token.
     *  When false, the expired-token [delta] surfaces [AuthenticationException]
     *  to the engine instead (models a refresh that itself failed). */
    var refreshOnTokenExpiry: Boolean = true

    /** Observability: how many transparent token refreshes fired. A
     *  test asserts this is exactly 1 to prove the refresh path was exercised
     *  rather than the token simply outliving the run. */
    var refreshCount: Int = 0
        private set

    /** Observability: how many times [authenticate] was invoked. */
    var authenticateCount: Int = 0
        private set

    // ── throttling / 429-storm fault-injection hooks ─────────────────────────
    //
    // The engine has no 429-retry of its own; the provider's HttpRetryBudget
    // absorbs the storm. What the engine DOES see is the storm's downstream
    // effect: a budget that exhausts its retries mid-walk yields a PARTIAL
    // inventory (`DeltaPage.complete = false`). These hooks reproduce that
    // signal so the engine's delete-suppression + convergence is exercised.

    /** Test hook: the next N [delta] calls return `complete = false`
     *  (throttle storm exhausted the retry budget → partial inventory), after
     *  which calls complete normally. Counts DOWN — each throttled call
     *  decrements it — so a test sets it relative to "the next pass," not the
     *  provider's lifetime call count. 0 (default) means no storm. */
    var throttleIncompletePasses: Int = 0

    /** Test hook: server-hinted Retry-After (ms) recorded on each
     *  throttled pass; surfaced via [observedRetryAfterMs] so a test can assert
     *  the backoff hint was honoured rather than discarded. */
    var retryAfterMs: Long = 2_000L

    /** Observability: the Retry-After hints recorded across throttled
     *  passes, in call order. Empty when no storm was injected. */
    val observedRetryAfterMs: MutableList<Long> = mutableListOf()

    /** Observability: total [delta] invocations, for assertions on
     *  how many passes the engine needed to converge. */
    var deltaCallCount: Int = 0
        private set

    override fun capabilities(): Set<Capability> =
        setOf(Capability.Delta, Capability.VerifyItem)

    override suspend fun authenticate() {
        authenticateCount++
    }

    /**
     * Test hook for the directory-reaping path: directory paths whose [delete]
     * should throw, to model a provider that fails a folder delete. The thrown
     * type is a generic provider error so the engine's best-effort catch is
     * exercised.
     */
    val failDeleteDirs: MutableSet<String> = mutableSetOf()

    /**
     * Test hook: directory paths that report one phantom child on the FIRST
     * [listChildren] call and then settle to their real (empty) contents —
     * modelling an eventually-consistent provider's read-after-write lag where a
     * just-trashed file lingers in the listing for one pass. A path is removed
     * from this set the first time it is listed.
     */
    val lagDirsOnce: MutableSet<String> = mutableSetOf()

    /**
     * Immediate children of [path], derived from the [files] map: files directly
     * under [path] plus one synthesized folder item per immediate subdirectory.
     * A normalised "/" matches the root. Lets the engine verify a directory is
     * empty before reaping it.
     */
    override suspend fun listChildren(path: String): List<CloudItem> {
        if (lagDirsOnce.remove(path)) {
            // First listing after a delete: pretend a stale child is still shown.
            return listOf(itemFor("$path/.__lagging__", ByteArray(0)))
        }
        val prefix = if (path == "/") "/" else "$path/"
        val out = mutableListOf<CloudItem>()
        val seenDirs = mutableSetOf<String>()
        for ((filePath, bytes) in files) {
            if (!filePath.startsWith(prefix)) continue
            val rest = filePath.substring(prefix.length)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            if (slash < 0) {
                // Direct file child.
                out += itemFor(filePath, bytes)
            } else {
                // Immediate subdirectory child; synthesize a folder item once.
                val childDir = prefix + rest.substring(0, slash)
                if (seenDirs.add(childDir)) {
                    out +=
                        CloudItem(
                            id = "folder-$childDir",
                            name = childDir.substringAfterLast('/'),
                            path = childDir,
                            size = 0,
                            isFolder = true,
                            modified = Instant.parse("2026-05-21T00:00:00Z"),
                            created = Instant.parse("2026-05-21T00:00:00Z"),
                            hash = null,
                            mimeType = null,
                        )
                }
            }
        }
        return out
    }

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
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val bytes = Files.readAllBytes(localPath)
        files[remotePath] = bytes
        uploadedPaths += remotePath
        return itemFor(remotePath, bytes)
    }

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        if (remotePath in failDeleteDirs) {
            throw org.krost.unidrive.ProviderException("simulated folder-delete failure for $remotePath")
        }
        if (files.containsKey(remotePath)) {
            // File delete.
            files.remove(remotePath)
        } else {
            // Directory delete: cascade-remove every file under the prefix,
            // modelling Internxt's folder-trash (which takes the whole subtree).
            val prefix = "$remotePath/"
            files.keys.filter { it.startsWith(prefix) }.forEach { files.remove(it) }
        }
        deletedPaths += remotePath
    }

    /** Remote folders created via createFolder/createFolders, for test assertions. */
    val createdFolders: MutableList<String> = mutableListOf()

    override suspend fun createFolder(path: String): CloudItem {
        createdFolders += path
        return CloudItem(
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
    }

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
        val callIndex = deltaCallCount
        deltaCallCount++
        deltaCursors += cursor

        // JWT/OAuth-refresh seam. When this call index reaches the configured
        // expiry point, the token is stale. A real provider's HTTP client
        // either (a) refreshes transparently and the call succeeds, or
        // (b) surfaces the auth failure when the refresh itself fails.
        if (tokenExpiresAtDeltaCall in 0..callIndex) {
            if (refreshOnTokenExpiry) {
                // Transparent refresh-token round-trip, exactly once: bump the
                // counter, clear the expiry so subsequent calls are clean, and
                // fall through to serve the page as if nothing happened.
                refreshCount++
                tokenExpiresAtDeltaCall = -1
            } else {
                throw org.krost.unidrive.AuthenticationException(
                    "simulated 401: token expired at delta call $callIndex and refresh failed",
                )
            }
        }

        if (cursor != null && cursor == expiredCursor) {
            throw DeltaCursorExpiredException("simulated 410 Gone on cursor=$cursor")
        }
        if (cursor == null && failFullReenumeration) {
            throw org.krost.unidrive.ProviderException("simulated full re-enumeration failure")
        }

        // throttle-storm seam. While the storm is active the provider's
        // retry budget is exhausted mid-walk, so it returns a PARTIAL inventory
        // (complete = false) — the exact signal the engine reacts to. Record the
        // server-hinted Retry-After so a test can assert the backoff was
        // observed, and count the storm DOWN so the engine eventually converges.
        val throttledThisPass = throttleIncompletePasses > 0
        if (throttledThisPass) {
            observedRetryAfterMs += retryAfterMs
            throttleIncompletePasses--
        }
        val passComplete = deltaComplete && !throttledThisPass

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
        return DeltaPage(items = items, cursor = nextCursor, hasMore = false, complete = passComplete)
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

/**
 * Variant of [FakeTrackingProvider] that models a hashless remote (e.g. Internxt):
 * every [CloudItem] emitted has `hash = null`, which is what the tracking engine
 * sees when the provider's API does not return a content hash.
 *
 * Used to pin the auto-match behaviour: with no [AutoMatchMode] set, a null-hash
 * both-sides entry must surface a [ReconcileAction.ReportCollision]; with
 * [AutoMatchMode.SIZE] or [AutoMatchMode.NAME] it must adopt.
 */
class HashlessFakeProvider : CloudProvider {
    val files: MutableMap<String, ByteArray> = mutableMapOf()
    override val id = "hashless-fake"
    override val displayName = "Hashless Fake"
    override var isAuthenticated: Boolean = true

    override fun capabilities(): Set<org.krost.unidrive.Capability> =
        setOf(org.krost.unidrive.Capability.Delta, org.krost.unidrive.Capability.VerifyItem)

    override suspend fun authenticate() {}

    override suspend fun listChildren(path: String): List<org.krost.unidrive.CloudItem> = emptyList()

    override suspend fun getMetadata(path: String): org.krost.unidrive.CloudItem {
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
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): org.krost.unidrive.CloudItem {
        val bytes = Files.readAllBytes(localPath)
        files[remotePath] = bytes
        return itemFor(remotePath, bytes)
    }

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        files.remove(remotePath)
    }

    override suspend fun createFolder(path: String): org.krost.unidrive.CloudItem =
        org.krost.unidrive.CloudItem(
            id = "folder-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = 0,
            isFolder = true,
            modified = java.time.Instant.now(),
            created = java.time.Instant.now(),
            hash = null,
            mimeType = null,
        )

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): org.krost.unidrive.CloudItem {
        val bytes = files.remove(fromPath) ?: throw NoSuchElementException("no remote at $fromPath")
        files[toPath] = bytes
        return itemFor(toPath, bytes)
    }

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): org.krost.unidrive.DeltaPage {
        val items =
            files.entries.map { (path, bytes) -> itemFor(path, bytes) }
        return org.krost.unidrive.DeltaPage(items = items, cursor = "hashless-cursor", hasMore = false)
    }

    override suspend fun quota(): org.krost.unidrive.QuotaInfo =
        org.krost.unidrive.QuotaInfo(total = 1_000_000, used = 0, remaining = 1_000_000)

    override suspend fun verifyItemExists(remoteId: String): org.krost.unidrive.CapabilityResult<Boolean> =
        org.krost.unidrive.CapabilityResult.Success(files.containsKey(remoteId))

    private fun itemFor(
        path: String,
        bytes: ByteArray,
    ): org.krost.unidrive.CloudItem =
        org.krost.unidrive.CloudItem(
            id = "id-${path.hashCode()}",
            name = path.substringAfterLast('/'),
            path = path,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = java.time.Instant.parse("2026-05-21T00:00:00Z"),
            created = java.time.Instant.parse("2026-05-21T00:00:00Z"),
            hash = null, // <-- the distinguishing property: no content hash
            mimeType = "application/octet-stream",
        )
}
