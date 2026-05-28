# Hydration & streaming-reconcile correctness review

Cross-validated findings from two independent read-only research agents over the
hydration (`HydrationImpl`, `SyncEngine.ensureHydrated`) and streaming-reconcile
(`Reconciler`, `StreamingReconcileBuffer`, the gather loop in `SyncEngine`) paths,
plus `StateDatabase` NFC coverage and the provider download contract.

Both agents reached the same conclusions independently on every load-bearing point
below. Line numbers are as of the PR #212 tip; treat them as anchors, re-verify
before editing. Scope: the `SyncEngine` / `state.db` engine only (not `TrackingEngine`).

**Status: shipped.** Every confirmed finding and the load-bearing adjacent issue (A1)
landed across #212–#218; the bot flagged and we fixed two P1s on the way (over-broad
deferred-delete suppression in #214, a concurrent same-path-download overwrite in
#217). The two deliberately deferred items are listed at the end. The body below is
kept as the point-in-time analysis that drove the work.

## Verdict summary

| Finding | Verdict | Severity | Status |
|---|---|---|---|
| C1 stale `remoteSize` → false EIO when a remote changed size since the last enum | confirmed | High | shipped #215 (with C7; #212's ineffective re-read removed first) |
| C2 `deleteEntry` no NFC | confirmed (was real) | Med | shipped #212 |
| C3 `markUnhydrated` no NFC | confirmed (was real) | Med | shipped #212 |
| C4 `renamePrefix` no NFC | confirmed (was real) | Med–High | shipped #212 |
| C5 prefix/children/descendant/count no NFC | confirmed (was real) | Med | shipped #212 |
| C6 `drainDeferred` not filtered vs `safePaths` | confirmed — exploitable, local data loss | Med–High | shipped #214 |
| C7 `ensureHydrated` never updates `remoteSize` (root cause of C1) | confirmed | High | shipped #215 |
| C8 "stale `entryByPath`" → dup actions | refined — misdiagnosed cause, real bug underneath | Med–High | shipped #216 (upload dedup) + #217 (content-aware download dedup + per-path serialization) |

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

- **A1 — NFC fix is incomplete (high-value follow-up). Shipped #218.** #212 normalized
  only the `StateDatabase` lookups. Mount **write** ops (`unlink`/`rename`/`mkdir`/`rmdir`)
  still passed raw NFD into `provider.delete`/`createFolder`/`renameRemote` **and**
  `resolveCachePath`. An NFD `deleteRemote` of an NFC-stored cloud file can 404 →
  `isAlreadyGone` swallows it → a still-present cloud file gets tombstoned; NFD cache
  lookups miss. Fixed at the single chokepoint: `HydrationIpcHandler` NFC-normalizes the
  logical path fields (`path`/`old_path`/`new_path`) as they enter, so cache resolution
  + provider calls + StateDatabase all see NFC (`cache_path` left as-is — a literal local
  path). (mount-linux ML#29 makes this defensive, but the JVM fix stands alone.)
- **`ensureHydrated` warm path serves stale same-size content silently. DEFERRED
  (post-MVP).** The warm-cache trust check is size-only; a same-byte-size remote edit is
  served from the old cache forever. Pre-existing; not closed by the C7 fix (still
  size-only). Low priority — spec note, not yet implemented.
- **Verified correct (no action):** #212's `getEntryByRemotePath` NFC + remote_path
  on write; the `safeResolveLocal` ASCII fast-path (only non-ASCII leaves have a
  decomposed variant); DeleteRemote×Upload is *not* exploitable (localState is
  constant per path, so DeleteRemote needs DELETED while a safe-now transfer needs
  NEW/MODIFIED — mutually exclusive; only DeleteLocal×DownloadContent, both allowing
  UNCHANGED, is the C6 case).

## Fix sequence (one concern per PR, per-invariant tests) — all shipped

1. **#212** — NFC coverage + ASCII fast-path; the ineffective post-hydration re-read
   removed (it needed C7 to mean anything).
2. **C6 → #214** — `drainDeferred` filtered against remote-content landings (not all
   `safePaths`, after the bot's P1: a per-page `Upload` must not suppress a real
   delete/modify conflict). First `StreamingReconcileBuffer` unit coverage.
3. **C1/C7 → #215** — OneDrive (vs `Content-Length`) and Internxt (vs declared plaintext
   size) reject short downloads and return the authoritative size; `ensureHydrated`
   persists it as `remoteSize`. The pre-existing incomplete-hydration guard stays green,
   now satisfied at the download site. (Internxt's provider-level short-read test is
   deferred — the provider builds its api/crypto internally, no DI seam yet; covered
   generically by the hydration EIO test.)
4. **C8 → #216 then #217** — #216 deduped the streaming-executor dispatch (+ concurrent
   `executedPaths`). #216's path-only claim also deduped downloads, which could drop a
   newer same-path version; #217 made the download claim content-aware AND serialized
   same-path transfers per path (the bot's P1: concurrent same-path downloads could
   overwrite the newer with a slower older one).
5. **NFC completeness (A1) → #218** — `HydrationIpcHandler` NFC-normalizes co-daemon
   logical path fields at the boundary.

No DB migration was required for any item (`--reset` remains the escape hatch).

### Deferred (not shipped)
- Internxt provider-level short-read test — needs a DI seam in `InternxtProvider`.
- `ensureHydrated` warm-path same-size staleness (size-only trust check) — post-MVP.
