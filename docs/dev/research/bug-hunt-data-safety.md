# Adversarial data-safety bug hunt — unidrive JVM core (read-only audit)

Date: 2026-05-26 · Branch: `main` @ `7a5f245` · Scope: data-integrity, silent-failure,
concurrency on the highest-stakes flows. READ-ONLY; nothing changed.

Method: end-to-end flow tracing with `file:line` evidence, de-duped against `BACKLOG.md`
+ `CLOSED.md`. Every finding below was confirmed against the actual source (quoted lines),
not inferred.

## Ranked findings

| # | Severity | Title | Evidence |
|---|---|---|---|
| 1 | **High** | OneDrive chunked-upload resume keys session on `remotePath` only → content-A-prefix + content-B-suffix corruption when the local file changes between an interrupted upload and its resume | `UploadSessionStore.kt:25-28,44-64`; `GraphApiService.kt:559-562,608-644` |
| 2 | **High** | Legacy `applyDeleteRemote` swallows *every* `ProviderException` as "skipped" then flips the row to `TRASHED` → a transient provider error during sync-driven remote-delete strands an undeleted remote file and the delete is never retried | `SyncEngine.kt:2491-2493,2508-2516` |
| 3 | **Medium** | Tracking-set adopt-on-content-match degrades to **adopt-on-size-match silently** for Internxt (no content hash) → two same-size, different-content files at one path are adopted as identical with no collision reported | `TrackingReconciler.kt:155-169,149-153`; `CloudProvider.kt:237`; `InternxtProvider.kt` (`toCloudItem` `hash = null`) |
| 4 | **Medium** | OneDrive delta collapses soft-`removed` (permission-revoked, still extant elsewhere) into `deleted=true`, identical to a hard delete → a still-valid local copy gets reaped/del-local on permission change | `OneDriveProvider.kt:361`, contrast the explicit hard-vs-soft split in `logDeletionStateSummary` `OneDriveProvider.kt:221-246` |
| 5 | **Low** | `uploadFromCache` records `localMtime/localSize` from the cache file *after* upload returns → a concurrent write in that window stamps an un-uploaded mtime as the synced watermark, so the edit is never re-uploaded (lost update) | `SyncEngine.kt:290-313` |
| 6 | **Low** | `enumerateRemoteIntoState` deletes the hydration cache file *inside* the DB transaction → if a later `markDeleted` in the same batch throws and rolls back, the cache bytes are gone but the row is restored to EXISTS/hydrated | `SyncEngine.kt:477-493` |

Counts: **High 2 · Medium 2 · Low 2** (6 new). Plus 1 confirmation of an already-filed
Critical (see appendix).

---

## Detail

### 1 — High — OneDrive chunked upload resumes by path without verifying file identity

**Invariant violated:** a resumed upload must append bytes belonging to the *same content*
that produced the already-committed prefix.

`UploadSessionStore` persists `{uploadUrl, expiresAt}` keyed by `remotePath` only — no size,
mtime, or content hash:

```
// UploadSessionStore.kt:25-28
private data class StoredSession(
    val uploadUrl: String,
    val expiresAt: String, // ISO-8601 Instant
)
// :62  map[remotePath] = StoredSession(uploadUrl, expiresAt.toString())
```

`resolveUploadSession` probes the stored URL and resumes at the server's committed offset
(`nextExpectedRanges`), with no comparison against the current local file:

```
// GraphApiService.kt:632-644
val committedOffset = parsedSession.nextExpectedRanges?.firstOrNull()
    ?.substringBefore("-")?.toLongOrNull() ?: 0L
...
return stored to committedOffset
```

`uploadLargeFileOnce` then `raf.seek(offset)` into the *current* local file and uploads from
there (`GraphApiService.kt:562,570-578`).

**Failure scenario:** a >4 MiB file `/big.bin` (content A) uploads via the chunked path, gets
interrupted at offset 5 MiB (session persisted). The local file is replaced/edited (content B,
same-or-larger size) before the next attempt. The resume returns the old session at offset 5 MiB
and uploads bytes 5 MiB+ of **B** on top of the committed 5 MiB of **A**. Assembled remote =
`A[0..5MiB) + B[5MiB..)` — a silently corrupted file. Only invalidated by session *expiry*, never
by local-content change. A *smaller* replacement is caught (`offset < fileSize` is immediately
false → `result==null` → throws), but same/larger is not. The <4 MiB simple-PUT path re-reads
all bytes fresh and is safe.

Internxt guards this exact class via its upload tombstone matching `mtime + size +
existingRemoteId + TTL` (`InternxtProvider.kt:488-505`); OneDrive has no equivalent.

**Fix direction:** store `localSize` (and ideally mtime/hash) in `StoredSession`; in
`resolveUploadSession`, discard + recreate the session when the current file's size/mtime
differs from the stored values (mirror Internxt's `resumeOk` gate).

### 2 — High — `applyDeleteRemote` treats all `ProviderException` as already-gone and trashes the row anyway

**Invariant violated:** a sync-driven remote delete that did *not* succeed must not be recorded
as done, and must be retried.

```
// SyncEngine.kt:2489-2493
try {
    provider.delete(action.path)
} catch (e: ProviderException) {
    log.debug("DeleteRemote skipped for ${action.path}: ${e.message}")
    auditResult = "skipped:${e.message}"
}
// ... then unconditionally:
// :2508-2510
val remoteId = priorEntry?.remoteId
if (remoteId != null) db.setStatusTrashed(remoteId)
```

This catch is **not** type/shape-gated. Contrast the hardened hydration path `deleteRemote`
(`SyncEngine.kt:394-405`) which re-throws everything except the two typed not-found prefixes via
`isAlreadyGone` (`:430-433`). The legacy apply path catches *any* `ProviderException` —
transient 5xx, throttle-wrapped, auth-adjacent, "Cannot upload to root", a path-resolution miss
on an unrelated parent — and then flips the row to `TRASHED`.

**Failure scenario:** user deletes a file locally; sync plans `DeleteRemote`; `provider.delete`
hits a transient `ProviderException` (e.g. 503 wrapped). The error is logged at DEBUG only, the
row is set `TRASHED` (no longer `EXISTS`), so the next sync's reconcile never re-emits the delete.
The remote file survives forever while the engine believes it is trashed — a silent
delete-propagation failure with lost retry. The `auditResult="skipped:…"` is the only trace and
it is DEBUG-level.

**Fix direction:** narrow the catch to `isAlreadyGone(e)` (the same gate the hydration path uses);
re-throw everything else so the row stays `EXISTS` and the delete is retried next pass.

### 3 — Medium — tracking-set adoption silently degrades to size-match for Internxt

**Invariant violated:** "adopt-on-*exact-content*-match, else LOUD collision" (BACKLOG Critical
entry's own contract).

```
// TrackingReconciler.kt:165-168
val lh = local.hash; val rh = remote.hash
if (lh != null && rh != null) return lh == rh
return local.size != null && remote.size != null && local.size == remote.size
```

`shouldAdopt` (`:149-153`) returns true when this is true. For Internxt, `hashAlgorithm()` is the
default `null` (`CloudProvider.kt:237`, never overridden) and every `toCloudItem` sets
`hash = null` (`InternxtProvider.kt:1740,1780,…`). So `remote.hash` is always null on the primary
E2EE provider, and `contentMatches` falls to **size-equality**.

**Failure scenario:** first `ts sync` over a non-empty `sync_root` against Internxt: a local file
and a remote file at the same path with the same byte count but different content are adopted as
identical (`trackingSet.adopt`), `continue`, never uploaded/downloaded, never `ReportCollision`.
The two sides diverge silently. The inline comment claims "Loose-match here is safe because the
alternative is `ReportCollision`" — but a size match returns `true` (NoOp/adopt), so the safe
alternative is exactly what is bypassed. The BACKLOG envisions a `--auto-match (size or name)`
*opt-in* for hashless providers; today size-match is the silent default for Internxt.

**Fix direction:** for adoption specifically, require a real content signal (hash) — or gate the
size-only fallback behind an explicit `--auto-match` flag and otherwise emit `ReportCollision`.
Internxt's bucket-entry `fileId` is a content address; surfacing it as `CloudItem.hash` would make
adoption truly content-exact.

### 4 — Medium — OneDrive delta conflates soft-`removed` with hard delete

**Invariant violated:** a permission-revocation (item still exists elsewhere) must not be treated
as a deletion of the user's local copy.

```
// OneDriveProvider.kt:361
deleted = removed != null || this.deleted != null,
```

`logDeletionStateSummary` (`:221-246`) carefully separates `removed.state == "removed"`
(permission lost / out of view) from `"deleted"`/`deleted` facet (recycle-bin) — but only for a
DEBUG log line. `toCloudItem` collapses both to `deleted=true`.

**Failure scenario:** a shared item is un-shared, or an item leaves the user's accessible scope.
Graph emits `removed.state="removed"`. The engine sees `deleted=true` → mount-view
`enumerateRemoteIntoState` reaps it (`markDeleted` + cache eviction, `SyncEngine.kt:486-491`) on a
complete enum, and bidirectional sync would plan a `DeleteLocal`. The user's still-valid local
copy is removed. Rare on a personal-only drive; real once shared items / `includeShared` are in
play.

**Fix direction:** map only `removed.state == "deleted"` / `deleted` facet to `deleted=true`;
treat `state == "removed"` as out-of-scope (drop from the change set) rather than a delete.

### 5 — Low — `uploadFromCache` reads the watermark after upload (TOCTOU lost update)

```
// SyncEngine.kt:303-313 (after provider.upload returns)
val mtime = Files.getLastModifiedTime(cachePath).toMillis()
val size  = Files.size(cachePath)
...
localMtime = mtime, localSize = size, isHydrated = true, lastSynced = Instant.now(),
```

The uploaded bytes were captured by the provider during `upload`, but the watermark is read from
the cache file *after* the call returns. A write that lands between upload completion and this read
stamps a newer mtime than what was actually uploaded. The co-daemon crash-recovery scanner replays
`open_write` only for cache files whose mtime *exceeds* the watermark — so that interleaved edit is
recorded as "synced" and never re-uploaded. Narrow window; needs a concurrent write mid-RELEASE.

**Fix direction:** snapshot mtime/size *before* the upload and record those (or re-check that the
file is unchanged after upload, else re-queue).

### 6 — Low — cache-file delete inside the reap transaction

```
// SyncEngine.kt:477-491
db.batch {
    updateRemoteEntries(remoteChanges)
    if (complete) for ((path, item) in remoteChanges) {
        if (!item.deleted) continue
        db.markDeleted(path)
        runCatching { Files.deleteIfExists(resolveCachePath(path)) } // side-effect inside txn
        reaped++
    }
}
```

The cache-file deletion is a non-transactional side effect executed inside the SQLite batch. If a
later `markDeleted` in the same batch throws, `batch{}` rolls back the DB (`StateDatabase.kt:309-311`)
but the cache bytes are already gone — the row is restored to EXISTS/hydrated pointing at an
absent cache file. `runCatching` keeps it from aborting, so the practical blast radius is small
(these are deletes), but it's a state/disk inconsistency window.

**Fix direction:** collect the paths to evict, commit the DB batch, then delete cache files after
a successful commit.

---

## Checked but OK / already-filed (appendix)

- **`EnumerateRpcHandler` / `RefreshRpcHandler` result-status handling** — both correctly branch on
  `EnumerateResult.ok` and surface `provider_error`/`shutdown` (`EnumerateRpcHandler.kt:52-69`,
  `RefreshRpcHandler.kt:80-87`). The "ignored return value" class flagged in the brief was **not**
  found to recur here. In-flight `AtomicReference` guards use the defensive
  compareAndSet-before-launch shape (`EnumerateRpcHandler.kt:42-46,96`); `RefreshRpcHandler` uses the
  simpler get-then-set but the launched body cannot run before `handle()` returns under a real
  dispatcher (already documented in BACKLOG-60 as a non-bug). OK.
- **`HydrationImpl.openSets` inner-map race (TODO at `:44-49`)** — *not* currently exploitable: the
  IpcServer per-connection reader dispatches request lines **sequentially**, awaiting each
  `dispatchRequest` (incl. its `withContext(handlerDispatcher)`) before reading the next
  (`IpcServer.kt:269-275,456-495`). No two coroutines mutate one connection's inner map
  concurrently. The TODO's hazard requires concurrent same-connection dispatch, which does not
  happen. Latent, not live.
- **`deleteItem` / `OneDriveProvider.delete` 404-idempotency** — correct: both treat 404/NotFound
  as success (`GraphApiService.kt:418`, `OneDriveProvider.kt:159`).
- **Internxt `delete` / `createFolder` 409 recovery / `createFolders` `UNCHECKED_CAST`** — all
  result slots are filled before the cast (`InternxtProvider.kt:945-998`); no null leak. OK.
- **Deletion safeguards (UD-265/UD-298)** — three independent axes (absolute cap default 50,
  whole-inventory %, per-subtree %), forceDelete-gated, warn-in-dry-run (`SyncEngine.kt:1002-1060`).
  Robust; small accounts covered by the absolute cap.
- **TrackingEngine incomplete-enum / batch-guard delete suppression** — both filter out
  `PropagateLocalDelete`/`PropagateRemoteDelete` (`TrackingEngine.kt:112-138`). Pending-delete
  crash-recovery re-derives from live observations (`TrackingReconciler.kt:102-141`). OK (these are
  the CLOSED'd regression-tested invariants).
- **ALREADY FILED (Critical, BACKLOG "Tracking-set sync engine")** — *engine records the
  conflict-copy's id/hash against the original path on a keep-both create-collision.* Confirmed live
  in BOTH the legacy `uploadFromCache` path and the tracking engine: Internxt `upload` returns the
  conflict-copy `CloudItem` as `finalItem` (`InternxtProvider.kt:843-878`), which
  `SyncEngine.uploadFromCache` then upserts against the original path (`:306-329`) and
  `TrackingEngine.applyUpload` adopts against the original path (`:245-248`). The BACKLOG Critical
  entry + the UD-366 CLOSED note both scope the "engine owns upload-result recording" half to the
  tracking-set work — so this is **already filed**, surfaced here only to confirm it also affects
  the legacy hydration write-back path (not just the tracking engine).
- **Label-vs-disposition (`DELETED` vs `TRASHED`) on local `rm`** — already filed (BACKLOG Low).
- **Cache-file un-eviction on remote-detected delete** — already filed (BACKLOG Medium,
  namespace-verbs R5).
