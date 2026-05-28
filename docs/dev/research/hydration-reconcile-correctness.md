# Hydration & streaming-reconcile correctness review

Cross-validated findings from two independent read-only research agents over the
hydration (`HydrationImpl`, `SyncEngine.ensureHydrated`) and streaming-reconcile
(`Reconciler`, `StreamingReconcileBuffer`, the gather loop in `SyncEngine`) paths,
plus `StateDatabase` NFC coverage and the provider download contract.

Both agents reached the same conclusions independently on every load-bearing point
below. Line numbers are as of the PR #212 tip; treat them as anchors, re-verify
before editing. Scope: the `SyncEngine` / `state.db` engine only (not `TrackingEngine`).

## Verdict summary

| Finding | Verdict | Severity | Status |
|---|---|---|---|
| C1 stale `remoteSize` → false EIO when a remote changed size since the last enum | confirmed | High | open (PR #212's re-read was ineffective and was removed) |
| C2 `deleteEntry` no NFC | confirmed (was real) | Med | FIXED in #212 |
| C3 `markUnhydrated` no NFC | confirmed (was real) | Med | FIXED in #212 |
| C4 `renamePrefix` no NFC | confirmed (was real) | Med–High | FIXED in #212 |
| C5 prefix/children/descendant/count no NFC | confirmed (was real) | Med | FIXED in #212 |
| C6 `drainDeferred` not filtered vs `safePaths` | confirmed — exploitable, local data loss | Med–High | open |
| C7 `ensureHydrated` never updates `remoteSize` (root cause of C1) | confirmed | High | open |
| C8 "stale `entryByPath`" → dup actions | refined — misdiagnosed cause, real bug underneath | Med–High | open |

C2–C5 were genuine *functional* gaps (not merely defensive): `HydrationImpl.dehydrate`
/ `unlink` / `list` pass un-normalized co-daemon paths straight through, so an NFD op
silently no-ops against the NFC-stored row before #212.

## C6 — streaming `drainDeferred` can trash a just-downloaded file (data loss)

Reachable via delete-then-recreate of the same path with a new id, split across
delta pages:

1. Page 1: tombstone(old id) for `/foo`. `localState=UNCHANGED, remoteState=DELETED`
   → `DeleteLocal(/foo)` → **deferred** (`StreamingReconcileBuffer.isDeferred`).
2. Page N: create(new id) at `/foo`. The DB row still points at the old id (the
   remote-entry writeback runs post-gather), so `remoteState=MODIFIED/NEW` →
   `DownloadContent(/foo)` → **safe-now → downloaded mid-gather**; `safePaths += /foo`.
3. Scan-end: `drainDeferred()` returns the `DeleteLocal` with **no `safePaths` check**.
   `detectRemoteRenames` can't pair them (the new id never had a DB row); the single
   delete is below the bulk-deletion guards; `sortActions` runs DeleteLocal (Pass 1)
   which trashes the file just downloaded.

Both agents independently produced this scenario and the same one-line fix:

```kotlin
fun drainDeferred(): List<SyncAction> {
    val out = deferred.values.filterNot { it.path in safePaths }
    deferred.clear()
    return out
}
```

Invariant: *a path that received a safe-now (additive) verdict this gather must not
also be destroyed by a deferred delete.* No multi-page streaming test exists today
(nothing in the suite drives `hasMore=true`), so this whole region is uncovered.

## C1 / C7 — stale `remoteSize` after rehydrate, and the provider trap

`ensureHydrated` re-downloads on a cache miss but its upsert sets
`isHydrated/localMtime/localSize/lastSynced` and **omits `remoteSize`** (and
`remoteHash`/`remoteModified`). So when a remote changed size since the last
enumeration, the post-download mount guard compares the fresh cache bytes against a
stale `remoteSize` → EIO on a valid file. The sync path (`applyDownload`) already
refreshes these via `entryFromCloudItem`; only the mount path is broken.

**The trap (decisive):** the two providers return different things from `download()`:

- OneDrive returns the **declared** size (`item.size` from a fresh `getItemByPath`)
  but `downloadFile` streams to EOF with **no byte-count assertion** — it does not
  throw on a short read.
- Internxt returns the **actual bytes written** and likewise does not validate the
  decrypted length against the declared size.

So naively persisting `downloadByIdOrPath`'s return as `remoteSize` would make
`bytes == remoteSize` always true for Internxt and **silently destroy truncation
protection**. (The existing truncation test passes only because `remoteSize` stays
at the declared value.)

**Chosen fix (provider completeness):**

1. Both providers assert the written/streamed byte count equals the authoritative
   declared size after the stream completes, and throw on mismatch:
   - OneDrive `GraphApiService.downloadFile`: track bytes written; after the loop
     `if (written != item.size) throw`.
   - Internxt `download`/`downloadWithFileUuid`: `if (written != fileMeta.size) throw`.
   Return the declared size.
2. `ensureHydrated` captures the return and adds `remoteSize` (+ `remoteHash`,
   `remoteModified`) to its upsert.

Truncation can then no longer reach the mount guard (the provider threw); `remoteSize`
is the fresh authoritative value, so the guard compares fresh-vs-fresh and C1's
false-EIO disappears. This also closes `hydrate()` (which has no guard today) and
hardens the sync path — truncation detection lives at the download site, its correct
home. Caveat: a just-changed remote whose declared metadata briefly lags could throw
spuriously; bounded by the existing single re-resolve.

## C8 — streaming executor fires duplicate concurrent uploads

The literal "stale `entryByPath`" claim is **refuted** — `resolveSlice` fetches
`db.getAllEntries()` fresh every slice. The real bug: `resolveSlice` iterates
`pageRemote.keys + localChanges.keys`, so the full `localChanges` map is processed on
**every** page. A MODIFIED-local file absent from a page's remote delta yields
`Upload(path)` per page. The mid-gather executor `launch`es a dispatch per channel
item with **no `executedPaths` precheck** (that gate exists only in Pass 2), and the
`#210` skip covers only NEW. Result: for a MODIFIED file spanning K pages, K
concurrent `applyUpload(samePath)` fire — wasted round-trips and racing
replace-PUTs/DB-writebacks.

**Fix:** claim the path before dispatch (`if (!sentToExecutor.add(action.path))
continue` at the executor send site), and make `executedPaths` a concurrent set
(`ConcurrentHashMap.newKeySet()`) since it's mutated from fan-out coroutines.

## Adjacent issues

- **NFC fix is incomplete (high-value follow-up).** #212 normalized only the
  `StateDatabase` lookups. Mount **write** ops (`unlink`/`rename`/`mkdir`/`rmdir`)
  still pass raw NFD into `provider.delete`/`createFolder`/`renameRemote` **and**
  `resolveCachePath`. An NFD `deleteRemote` of an NFC-stored cloud file can 404 →
  `isAlreadyGone` swallows it → a still-present cloud file gets tombstoned; NFD cache
  lookups miss. Fix at one point: NFC-normalize the path at the IPC handler (or top of
  each `HydrationImpl` op). (mount-linux ML#29 would make this defensive, but the JVM
  fix must stand alone.)
- **`ensureHydrated` warm path serves stale same-size content silently.** The
  warm-cache trust check is size-only; a same-byte-size remote edit is served from
  the old cache forever. Pre-existing; not closed by the C7 fix (still size-only).
  Low priority for MVP — spec note.
- **Verified correct (no action):** #212's `getEntryByRemotePath` NFC + remote_path
  on write; the `safeResolveLocal` ASCII fast-path (only non-ASCII leaves have a
  decomposed variant); DeleteRemote×Upload is *not* exploitable (localState is
  constant per path, so DeleteRemote needs DELETED while a safe-now transfer needs
  NEW/MODIFIED — mutually exclusive; only DeleteLocal×DownloadContent, both allowing
  UNCHANGED, is the C6 case).

## Fix sequence (one concern per PR, per-invariant tests)

1. **#212** — NFC coverage + ASCII fast-path (re-read removed). *Merged separately.*
2. **C6** — `drainDeferred` `safePaths` filter + the first multi-page (`hasMore=true`)
   streaming test harness.
   - tests: `deferred_delete_is_suppressed_when_same_path_fired_safe_now`;
     `delete_then_recreate_same_path_keeps_recreated_file`.
3. **C1/C7** — provider completeness throw (OneDrive + Internxt) + `ensureHydrated`
   stores fresh `remoteSize`/`remoteHash`/`remoteModified`.
   - tests: `download_throws_on_short_read` (per provider);
     `ensureHydrated_refreshes_remoteSize_after_redownload`;
     `open_read_succeeds_when_remote_grew_since_last_enum`.
   - keep the existing incomplete-hydration test green (now satisfied by the throw);
     risk-register line: if it is loosened, truncation acceptance silently re-opens.
4. **C8** — executor dedup + concurrent `executedPaths`.
   - test: `modified_local_upload_dispatched_once_across_a_multi_page_gather`.
5. **NFC completeness** — normalize the path at the IPC boundary for mount write ops.
   - tests: `nfd_unlink_deletes_the_nfc_stored_remote`; `nfd_open_read_hits_the_nfc_cache_file`.

No DB migration is required for any item (`--reset` remains the escape hatch).
