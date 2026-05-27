# Third-review bug-hunt: independent verification report

**Date:** 2026-05-26
**Method:** Independent re-derivation from source; findings from prior docs (`bug-hunt-data-safety.md`, `bug-hunt-patterns.md`) treated as claims to test, not facts.
**Scope:** `unidrive` JVM @ `origin/main` 7a5f245, `unidrive-mount-linux` Rust @ `origin/main` 74eefc6.

---

## Status update (2026-05-27)

Resolution since this report was written (origin/main now at 4243293):

| Finding | Sev | Status |
|---------|-----|--------|
| **A** — `openSets` inner-map race | HIGH | ✅ **Fixed** — PR #89 (705b6b9) |
| **B1** — OneDrive chunked-upload resume corruption | HIGH | ✅ **Fixed** — PR #90 (c05e5c1) bound the resumable session to local file identity |
| **B2** — `applyDeleteRemote` catch trashes row on transient error | HIGH | ⚠️ **Still live** — `SyncEngine.kt:2550` catch is not `isAlreadyGone`-gated; next fix unit |
| **NEW-1** — `_events` `MutableSharedFlow` head-of-line blocking | HIGH | ⚠️ **Still live** — `HydrationImpl.kt:41` still `emit`, not `tryEmit`; now reachable (Phase-3 subscribe-on-mount merged) |
| B3 / B4 / B8 | MEDIUM | Open (filed to BACKLOG via #88) |
| B5 / B6 / B7 / NEW-2 / NEW-3 / NEW-4 | LOW | Open (filed) |

Remaining HIGH work: **B2** and **NEW-1**.

---

## (A) Disputed finding: `openSets` inner-map race — VERDICT: CONFIRMED HIGH (live, coordinator correct)

**Claim:** `HydrationImpl.openSets` (`HydrationImpl.kt:48-49`) is `ConcurrentHashMap<String, MutableMap<String, String>>` with a plain inner `HashMap`. One agent said NON-LIVE ("IpcServer dispatches per-connection lines sequentially"); the other said LIVE.

**Evidence trace:**

1. Inner map is a plain `HashMap` — `openSets.computeIfAbsent(connectionId) { mutableMapOf() }` returns a `MutableMap<String, String>` (Kotlin's `LinkedHashMap` delegation, `:49`). The mutation `[handleId] = path` at `:83`, `:103`, `:124`, `:317`, `:358` writes to this map **after** the atomic `computeIfAbsent` returns — the put is outside the lambda, so `ConcurrentHashMap`'s atomicity does NOT protect the inner map.

2. `dehydrate` at `:161` iterates ALL connections' inner maps:
   ```kotlin
   val anyOpen = openSets.values.any { perConn -> perConn.containsValue(path) }
   ```
   `HashMap.containsValue` iterates the internal table. Concurrent `put` (add node) or `remove` (unlink node) on the same `HashMap` instance while `containsValue` visits the table can throw `ConcurrentModificationException` or produce a torn read (miss an entry) per the `HashMap` contract.

3. Two connections' handlers CAN run concurrently. The reader loop at `IpcServer.kt:269-275` dispatches lines per-connection sequentially, but each connection has its OWN `scope.launch(transport)` coroutine (`:248`). `dispatchRequest` (`:456`) calls `withContext(handlerDispatcher)` where `handlerDispatcher = Dispatchers.IO` (`:93`). Dispatchers.IO has 64 threads. Two separate connections' handlers can execute on two IO threads simultaneously.

**Reachability trigger:**
- Connection A: the FUSE mount, calls `openForRead(path)` → handler thread writes to connection A's inner map via `computeIfAbsent { ... }[handleId] = path` (on IO thread 1)
- Connection B: any IPC client (CLI one-shot, test harness, or a second process), calls `hydration.dehydrate(path)` → handler iterates ALL inner maps via `openSets.values.any { ... }` (on IO thread 2)
- Connection A's `HashMap` is mutated on thread 1 while being iterated on thread 2 → **CME or lost entry**
- If `containsValue` misses the open handle, `dehydrate` returns `DehydrateResult.Ok` and deletes the cache file → **local-edit data loss** for a file open for write

**Coordinator verdict stands: CONFIRMED HIGH.**

The non-live argument's error: it assumed per-connection serialisation prevents cross-connection races. It does not — `dehydrate` reads across all connections.

---

## (B) Findings verdict table

### B1 — OneDrive chunked-upload RESUME CORRUPTION — CONFIRMED HIGH

`UploadSessionStore.kt:25-28`: `StoredSession(uploadUrl, expiresAt)` — stored by `remotePath` only. No `localSize`, `localMtime`, or `hash`.
`GraphApiService.kt:632-644`: `resolveUploadSession` probes the stored URL, reads `nextExpectedRanges` → committed offset. No comparison against current local file.
`GraphApiService.kt:562-578`: `uploadLargeFileOnce` does `raf.seek(offset)` into the CURRENT local file and uploads new bytes.

**Reachability:** upload interrupted at offset N → session persisted. Local file changed to different content, same-or-larger size. Resume reuses session at offset N, seeks to N in new file, uploads `B[N..]` on top of `A[0..N)`. Assembled remote = `A[0..N) + B[N..)` — **silently corrupted**. Smaller replacement correctly fails (offset > fileSize → null result → throw). The <4 MiB simple-PUT path is safe (no session).

**Fix direction already specified:** store `localSize` (and mtime/hash) in `StoredSession`; discard + recreate on mismatch.

### B2 — `applyDeleteRemote` catch trashes row regardless of error — CONFIRMED HIGH

`SyncEngine.kt:2489-2510`:
```kotlin
try {
    provider.delete(action.path)
} catch (e: ProviderException) {
    log.debug("DeleteRemote skipped for ${action.path}: ${e.message}")
    auditResult = "skipped:${e.message}"
} // no rethrow for ProviderException
// then unconditionally at :2508-2510:
val remoteId = priorEntry?.remoteId
if (remoteId != null) db.setStatusTrashed(remoteId)
```

**Evidence:** The catch is NOT shape-gated. A transient `ProviderException` (503 wrapped, 429, connection reset) goes to `"skipped"`, the row is set `TRASHED` — so the next sync never re-emits `DeleteRemote`. The remote file survives forever with no retry. The `auditLog` records `"skipped:..."` at DEBUG level only.

**Contrast with the hardened hydration path** at `SyncEngine.kt:394-405` which re-throws everything except the two `isAlreadyGone` patterns.

**Fix direction:** narrow the catch to `isAlreadyGone(e)`; re-throw everything else (same gate as the hydration path).

### B3 — Tracking adoption degrades to size-only for Internxt — CONFIRMED MEDIUM

`TrackingReconciler.kt:165-168`:
```kotlin
val lh = local.hash; val rh = remote.hash
if (lh != null && rh != null) return lh == rh
return local.size != null && remote.size != null && local.size == remote.size
```
`CloudProvider.kt:237`: `fun hashAlgorithm(): HashAlgorithm? = null` (default, Internxt never overrides).
`InternxtProvider.kt:1740,1780,1808,1927,2017,2035`: all `toCloudItem` return `hash = null`.

**Evidence:** `remote.hash` is always null for Internxt, so `contentMatches` falls to size-equality. Two same-size different-content files at the same path are silently adopted (NoOp) with no collision report. The inline comment ("Loose-match here is safe because the alternative is ReportCollision") is misleading — size match IS the adoption path, not the collision path.

**Reachability:** `unidrive ts sync` (tracking-set engine, not the default) with Internxt over a non-empty sync_root where a local file and a remote file at the same path happen to have the same byte count.

**Backlog dedup:** BACKLOG Critical tracks "create-collision upload-result recording" — this is adjacent but distinct (adoption on first-scan, not upload collision). Genuinely new.

### B4 — OneDrive delta conflates soft `removed` with hard delete — CONFIRMED MEDIUM

`OneDriveProvider.kt:361`: `deleted = removed != null || this.deleted != null`
`logDeletionStateSummary` at `:221-246` separates the two for DEBUG logging but `toCloudItem` collapses both.

**Evidence:** `removed.state="removed"` (permission revocation, item still exists) produces `deleted=true`. For a shared-item user (`includeShared=true`), an unshared item is reaped from the mount view (`markDeleted` + cache eviction) on a complete enumeration.

**Reachability:** personal OneDrive only → unreachable. Work/school + `includeShared` → real. Severity Medium fits.

### B5 — `uploadFromCache` watermark TOCTOU — REFINED LOW

`SyncEngine.kt:303-313`: mtime/size read after `provider.upload` returns.

**Refined finding:** The TOCTOU window is real but:
- On the **normal synchronous path** (`open_write` line 119-124): FUSE serializes RELEASE per-fd. No concurrent write on the same cache file while the upload handler runs.
- On the **async recovery path** (`recoveryUploadScope.launch` line 104-116): upload runs in the background. A concurrent write landing between upload-completion-and-mtime-read would stamp a newer mtime. But the watermark is used as "last synced — only re-upload if mtime exceeds this," so a newer mtime from a concurrent write means the edit WILL be re-uploaded (the watermark is conservative). This is the safe direction — it loses efficiency (re-uploaded once more), not data.
- The original finding's "lost update" scenario requires the watermark to exceed the true mtime, which can't happen from a concurrent write (the write produces a newer mtime, not an older one).

**Verdict:** Not a data-loss bug. Risk is a redundant re-upload on the recovery path. Already Low.

### B6 — Cache eviction inside DB transaction — REFINED LOW

`SyncEngine.kt:477-491`: `Files.deleteIfExists(resolveCachePath(path))` inside `db.batch{}`.

**Refined finding:** The inconsistency is real (cache bytes gone, row restored to EXISTS if `markDeleted` throws). But:
- `markDeleted` only writes a status update SQL. The only throw scenario is catastrophic SQLite failure (disk full, corruption) — in those scenarios the cache eviction is noise.
- `runCatching` keeps the eviction from aborting the batch. So the rollback-on-throw path requires `markDeleted` itself to throw, which only happens on I/O error from the SQLite layer.

**Verdict:** True inconsistency, but near-zero probability of observable effect. Fix direction (collect paths, commit DB, then evict) is correct but Low priority.

### B7 — Unknown path errno split — CONFIRMED LOW

`HydrationImpl.kt:65` (`openForRead`): `"Unknown path: $path"` (free text) → Rust `ipc_error_to_errno` → **EIO**
`HydrationImpl.kt:89` (`openForWrite`): `"Unknown path: $path"` → **EIO**
`HydrationImpl.kt:308` (`openWriteBegin`): `"unknown_path"` (bare token) → Rust `namespace_err_to_errno` → **ENOENT**

**Rust mapping** at `fuse_fs.rs:174-196`:
- `ipc_error_to_errno` (`:174`): `ServerError` → EIO (catch-all)
- `namespace_err_to_errno` (`:184`): `"unknown_path"` match → ENOENT

**Evidence:** Three open paths all mean "path not found" but disagree on wire word. `openForRead`/`openForWrite` append the path to the message, so even if routed through `namespace_err_to_errno`, the string `"Unknown path: /foo"` would never match the `"unknown_path"` arm.

**Practical impact:** Low — kernel runs `lookup` first, so a missing path rarely reaches `open`. But wire contract is inconsistent.

### B8 — Lookup byte-exact path match + no NFC normalization — CONFIRMED MEDIUM

`fuse_fs.rs:254-257`: `child_path = format!("{parent}/{name}")` from raw kernel bytes, then `e.path == child_path` byte-exact.
JVM: grep for `Normalizer` returns zero hits in the entire tree.

**Evidence:** No NFC/NFD normalization on either side. A file created on macOS (NFD) and synced to the cloud, then listed on Linux (NFC kernel), matches byte-exact against the JVM's stored path. If the provider returns NFC names and the kernel presents NFD (or vice versa), the equality fails → ENOENT on an existing file.

**Dedup:** JVM BACKLOG already has a "Path normalization (NFC) across sync" item. This is the FUSE half of that gap, not an independent finding.

### B9 — `uploadFromCache` watermark (duplicate of B5)

Covered in B5 above. No separate finding.

---

## (C) NEW findings — independent hunt

### NEW-1 — HIGH: `HydrationImpl._events` `MutableSharedFlow` causes head-of-line blocking on hydration operations when a subscriber is slow (will bite in Phase 3)

**File:** `HydrationImpl.kt:40-41`
```kotlin
private val _events = MutableSharedFlow<HydrationEvent>(extraBufferCapacity = 64)
```

**Mechanism:** `MutableSharedFlow.emit(value)` suspends when the buffer is full and at least one subscriber is not ready. The buffer capacity is `replay(0) + extraBufferCapacity(64) = 64`. With zero subscribers (today's state), `emit` never suspends — items are buffered only when there are subscribers. This is safe today.

**Phase-3 trigger:** The `subscribe-on-mount` PR (#18) has merged. When the mount subscribes (`hydration.subscribe`), the `HydrationIpcHandler` subscriber coroutine collects events and writes them to the socket. Each hydration handler (`openForRead`, `openForWrite`, `hydrate`, `dehydrate`, `mkdir`, `create`, `rename`) emits 1-2 events per call via `_events.emit(...)` at lines `:72, :78, :106, :109, :113, :122, :123, :135, :148, :151, :154, :168, :172, :206, :208, :224`.

If the mount subscriber is slow (socket write blocked, kernel buffer full), the flow buffer fills (64 events), and **every hydration handler call blocks on `_events.emit(...)`**. This creates head-of-line blocking: one slow subscriber stalls ALL hydration operations on ALL connections.

**Failure scenario:** `find /mount -type f` opens 65+ files concurrently. `openForRead` for each emits `Hydrating` event. After 64, the 65th `_events.emit(Hydrating(...))` at line 72 suspends. The handler never returns. The FUSE `open` never completes. The kernel tries to wait for all outstanding opens — **the mount hangs**. Unrecoverable without killing the co-daemon.

**Severity: HIGH** — causes complete mount stall on sustained concurrent hydration. Only blocked by Phase-3 subscribe-on-mount, which just merged.

**Fix direction:** Replace `emit` with `tryEmit` for progress events (drop when subscriber can't keep up — the mount re-reads `state.db` via `list` for accurate state). Or separate the progress flow from the view-invalidation flow (only the latter needs delivery guarantees). Or use an unbounded channel per subscriber.

### NEW-2 — MEDIUM: `IpcServer` `channel` capacity of 256 creates silent event drop window on sync progress events

**File:** `IpcServer.kt:77`
```kotlin
private val channel = Channel<String>(capacity = 256)
```
`emit` at `:124-126`:
```kotlin
fun emit(json: String) {
    if (!channel.isClosedForSend) {
        runCatching { channel.trySend(json) }
    }
}
```

**Mechanism:** Sync progress events (`sync_started`, `scan_progress`, `action_count`, `sync_complete`, etc.) are pushed through a `Channel<String>` with capacity 256. `trySend` drops the event if the channel is full (returns `false`, wrapped in `runCatching` → swallowed).

**Failure scenario:** A long sync (195k-file delta) generates many progress events. If the broadcast consumer is slower than the producer (e.g., a subscriber slow on socket writes, or multiple subscribers consuming at different rates), the channel fills and subsequent events are **silently dropped**. The subscriber sees an incomplete progress stream — `sync_started` but no `sync_complete`, or progress jumps from 10% to 90%.

Not a data-loss bug (sync state is authoritative; progress events are advisory), but breaks the operator experience. The `runCatching` wrap makes this completely silent — an operator watching `unidrive sync --watch` sees stale/wrong progress without any warning.

**Severity: MEDIUM** — observability, not data safety. But dropped `sync_complete` / `sync_error` events could confuse automation that waits for the terminal event.

**Fix direction:** Log a warning when `trySend` returns false. Or increase the channel capacity. Or add a dropped-event counter to the server status.

### NEW-3 — MEDIUM: Hydration `_events.emit` on `openForWrite` failure path can double-emit `Failed` or emit after terminal state

**File:** `HydrationImpl.kt:119-137`
```kotlin
return try {
    _events.emit(HydrationEvent.Hydrating(path))     // :122
    syncEngine.uploadFromCache(path, cachePath)
    val bytes = java.nio.file.Files.size(cachePath)
    _events.emit(HydrationEvent.Hydrated(path, bytes)) // :123 (should be :124 based on actual file)
    openSets.computeIfAbsent(connectionId) { mutableMapOf() }[handleId] = path  // :124
    OpenResult.Ok(cachePath)
} catch (e: Exception) {
    runCatching { stateDb.markUploadFailed(path, java.time.Instant.now()) }
    val err = HydrationError.Generic(e.message ?: "upload failed")
    _events.emit(HydrationEvent.Failed(path, err))    // :135
    OpenResult.Failed(err)
}
```

**Issue:** If `openSets.computeIfAbsent(...)[handleId] = path` at line 124 throws (e.g., OOM, ConcurrentModificationException from the race in finding A), the catch handler at line 126 catches the `Exception`, emits `Failed(path, err)` at line 135, and returns `Failed`. The previous `Hydrating` event (line 122) was already emitted. The subscriber sees `Hydrating` followed by `Failed` — correct.

But if `Files.size(cachePath)` at line 123 throws (file deleted between upload and size check), `Hydrating` was emitted, then `Failed` is emitted. Also correct.

**Edge case I found:** If the `_events.emit(Hydrated(...))` at line 123 itself throws (e.g., `CancellationException` from a cancelled scope), the `Hydrating` event was emitted, `Hydrated` was NOT emitted, `Failed` is NOT emitted (because `CancellationException` is not a non-fatal, so it might propagate differently). Actually, Kotlin coroutine cancellation throws `CancellationException` which is a subclass of `IllegalStateException`... no, `CancellationException` is a subclass of `IllegalStateException` in some implementations, but in kotlinx.coroutines it's a non-retryable exception.

Wait, `CancellationException` IS caught by `catch (e: Exception)` — it extends `IllegalStateException` which extends `RuntimeException` which extends `Exception`. So it's caught, `Failed` is emitted, and `OpenResult.Failed(err)` is returned with a misleading error message "upload failed" when actually it was a cancellation. Not a data-safety issue, just a confusing error.

This is too edge-casey to file. Let me drop it.

**NEW-4 — LOW: `StateDatabase.batch{}` holds `@Synchronized` across network-bound operations in `enumerateRemoteIntoState`**

`SyncEngine.kt:477`: `db.batch { updateRemoteEntries(remoteChanges) ... }` runs inside `@Synchronized` (per `StateDatabase.kt:301`).
`updateRemoteEntries` at `:2685` iterates ALL remote changes and calls `db.upsertEntry` for each.

For a 195k-file full enumeration with `reset=true`, the batch holds the `@Synchronized` lock for potentially seconds. During this time, `hydration.list` (which also calls `stateDb.listDirectChildren` → `@Synchronized`) blocks. The mount shows stale data until the batch completes, and `ls` on the mount point stalls.

Not a data-safety bug. Purely a performance concern. The mount's `getattr`/`readdir` operations block on the `StateDatabase` lock during enumeration.

**Severity: LOW** — temporary mount stall during full enumeration.

---

## (D) Checked-and-clean / false positives

**1. OAuth 401 refresh-and-retry** (`GraphApiService.kt:799-811, 855-869`): Both use a `refreshed` flag, force-refresh on first 401, retry once. The `tokenProvider(forceRefresh: Boolean)` can be called concurrently from multiple handlers — but OAuth token refresh is safe under concurrent calls (the last refresh wins; both tokens work until the first expires). **Checked OK.**

**2. Internxt E2EE IV-pinning** (`InternxtProvider.kt:560-573, 2088-2089`): The tombstone pins `indexBytes`/`iv` across upload retries. Re-encryption with the same key/IV and the same (re-read from disk) plaintext produces the same ciphertext → no IV reuse leak. The design constraint explicitly forbids holding stale `indexBytes` across a re-encrypt (`:2088`). **Checked OK.**

**3. IPC `pendingPostReply` thread safety** (`IpcServer.kt:96`): type is `ConcurrentHashMap<String, suspend ...>` — thread-safe by construction. My initial concern was wrong. **False positive rejected.**

**4. Internxt content hash verification:** Download decrypt at `:260-300` computes SHA-256 of ciphertext and verifies against bridge hash before decryption. The `hashAlgorithm() = null` and `hash = null` in `toCloudItem` are correct — Internxt doesn't expose content hashes at the CloudItem level, but integrity is verified at the ciphertext level. **Not a bug.**

**5. `ReconnectingIpcClient` retry of `create`/`rename`/`unlink`** (B2 in patterns doc, `reconnect.rs:212-285`): Verified. The retry-on-Io pattern blindly retries non-idempotent mutating verbs. The module docstring explicitly excludes `subscribe` for this reason but does not exclude the mutating verbs. This finding is **CONFIRMED** by the patterns doc and my verification. **Already filed** in the patterns doc — I just verify it's correct and not a false positive.

**6. OneDrive mount startup deadlock** (BACKLOG OPEN Critical): The BACKLOG entry is correct. The symptom — "co-daemon hangs before mount() on onedrive, works on internxt" — is reproducible and the root cause is not yet identified. The `"missing_prefix"` probe failure confirms a connection-prefix handshake exists. The startup code needs tracing. **Already filed as BACKLOG Critical; no new information to add.**

**7. `HydrationIpcHandler` VERBS lockstep test:** The patterns doc correctly notes that `HydrationIpcHandler.VERBS` (14 verbs) match the Rust `ipc.rs` verb set (14 methods). I verified: `open_read, open_write, open_write_begin, close_handle, hydrate, dehydrate, subscribe, last_synced, list, mkdir, unlink, rmdir, create, rename`. All 14 match. **Checked OK.**

---

## Summary

| # | Severity | Title | Verdict |
|---|----------|-------|---------|
| A | **HIGH** | `openSets` inner-map race (dehydrate iterates across connections) | **CONFIRMED** |
| B1 | **HIGH** | OneDrive chunked-upload resume corruption (path-only key) | **CONFIRMED** |
| B2 | **HIGH** | `applyDeleteRemote` catch trashes row regardless of error kind | **CONFIRMED** |
| B3 | MEDIUM | Tracking adoption size-only for Internxt (no hash) | **CONFIRMED** |
| B4 | MEDIUM | OneDrive delta conflates soft-removed with hard delete | **CONFIRMED** |
| B5 | LOW | `uploadFromCache` watermark TOCTOU | **REFINED** — not data-loss |
| B6 | LOW | Cache eviction inside DB transaction | **REFINED** — near-zero probability |
| B7 | LOW | Unknown path errno split (open_read → EIO vs open_write_begin → ENOENT) | **CONFIRMED** |
| B8 | MEDIUM | Lookup byte-exact path, no NFC normalization | **CONFIRMED** (FUSE half of filed gap) |
| **NEW-1** | **HIGH** | `_events` MutableSharedFlow head-of-line blocking (Phase-3 threat) | **NEW** |
| **NEW-2** | MEDIUM | `IpcServer` channel capacity 256 silent event drop | **NEW** |
| **NEW-3** | LOW | StateDatabase lock contention during full enumeration | **NEW** |
