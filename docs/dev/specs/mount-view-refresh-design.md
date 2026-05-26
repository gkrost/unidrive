# Mount-view refresh — design

**Status:** design. Supersedes the narrow BACKLOG row "mount-safe refresh: `refresh.run` reuses the full sync_root deletion reconcile" (PR #84) — that row is the symptom; this is the architectural cut.

**Evidence base:** `docs/dev/research/sync-mount-architecture-knot.md` (code map, constraints A–H, `file:line` index) and `docs/dev/research/live-sync-prior-art.md` (how rclone / OneDrive / Dropbox / Nextcloud / DriveFS do it).

---

## 1. Problem

The FUSE mount is a **one-way remote→view consumer**: its entire namespace/metadata view is served from `state.db` (`HydrationImpl.list → StateDatabase.listDirectChildren`), with bytes in a disjoint hydration cache (`~/.cache/unidrive/hydration/<profile>/`). It never writes to `sync_root`.

But the only path that refreshes `state.db` from the remote is `refresh.run → SyncEngine.syncOnce(skipTransfers=true, skipRemoteGather=false)` — the **legacy bidirectional reconcile**, which *always* walks `sync_root` (`LocalScanner.scan`). For a mount, `sync_root` is empty/near-empty, so every cloud-backed row reads as a **local deletion** → a plan to delete those files on the remote → the empty-sync_root guard (`SyncEngine.kt:827`) and `max_delete_*` safeguards (`:959`) correctly abort.

Result: a mounted profile **cannot be refreshed from the remote** without `--force-delete` (catastrophic) or a structural change. The mount view goes stale (remote renames/deletes invisible until a refresh that can't run).

## 2. The cut (one sentence)

Introduce a **remote-enumerate-only** operation that pulls the remote namespace into `state.db` and **never** scans `sync_root`, never plans or executes a local→remote delete, and never reaches the deletion guards — making "refresh the mount view" structurally distinct from "reconcile a two-way replica."

This is the dominant industry pattern (prior-art doc): *keep-the-view-fresh* (one-way remote→metadata-DB enumeration via delta cursor) is a separate, separately-guarded path from *reconcile-a-replica*.

## 3. Semantics of `enumerateRemoteIntoState`

A new engine entry point: `SyncEngine.enumerateRemoteIntoState(reset: Boolean): EnumerateResult`. It:

1. **Reuses** `gatherRemoteChanges()` (`SyncEngine.kt:1353`) to pull the provider delta (cursor-resumed unless `reset`), and `updateRemoteEntries` (`:860/2685`) + `entryFromCloudItem` (`:2667`) to upsert `state.db` rows — so `hydration.list`/`getEntry` see well-formed rows (`isFolder`, `remoteSize`, `remoteId`, `remoteModified`).
2. **Never** calls `scanner.scan()` / walks `sync_root` → `localChanges` is empty → no `ChangeState.DELETED` from absent local files → `Reconciler` never emits `DeleteRemote`.
3. **Never** emits or executes `DeleteRemote` / `Upload` / `MoveRemote` (no local→remote propagation) and runs **no Pass-1/Pass-2 apply loop**.
4. **Never** evaluates the empty-sync_root guard (`:827`), the `sync_root`-drift guard (`:556`), or the `max_delete_*` safeguards (`:959`) — there are no local-inferred deletes to guard against. It must **not** set `forceDelete=true` (that disables remote-side safety the legacy path needs and is semantically wrong).
5. **Remote-observed deletions → `state.db` status flip only.** Reuse `detectMissingAfterFullSync` (`:2097`) to find "DB row absent from a complete delta," but route its result to `StateDatabase.markDeleted` (`:602`) + **hydration-cache eviction** (`Files.deleteIfExists(resolveCachePath(path))`) — **not** to a provider `delete()`. (Closes the BACKLOG cache-eviction-on-remote-delete item too.)
6. **Promotes the cursor** like the no-transfer success path (`promotePendingCursor` `:2070`) so successive enumerations are incremental. With `reset=true`, call `db.resetAll()` (`:72`) first (full re-enumeration).

### 3.1 Safety invariant (mandatory, own named test)

**Remote-observed deletions are reaped into `state.db` only after a COMPLETE enumeration** (`pending_cursor_complete`). A partial/throttled delta omits unchanged paths; reaping on an incomplete enumeration would silently empty the mount view. Mirror `TrackingEngine`'s incomplete-enum suppression (`TrackingEngine.kt:112`). This is constraint C and the single riskiest invariant — it gets its own test, separate from the happy path (CLAUDE.md orthogonal-invariant rule).

### 3.2 View-side corroboration guard (robustness, Phase 2)

Internxt's `/files` status lags a trash by ≥60 min and flaps. So even on a complete enumeration, a **bulk disappearance is suspicious**: if a single enumeration would flip more than `max(50 absolute, 20% of rows)` to deleted, **warn and defer** the reaping until a second enumeration corroborates. This mirrors ownCloud `aboutToRemoveAllFiles` / rclone `--max-delete`. It is a *view* guard (marking rows TRASHED + evicting cache ≠ deleting user data), so it re-arms none of the UD-265 two-way data-loss class. Phase 2.

## 4. Auto-selection: when does `refresh` pick the enumerate path?

`refresh` (and the poll loop) must pick `enumerateRemoteIntoState` over the legacy reconcile **when the profile is being served as a mount** — detected at runtime via **a connected mount client** (`server.clientCount` / the `hydration.subscribe` connection registry, `DaemonRuntime.kt:160`). This is alternative **E** as a *selection trigger only*: "mount client connected" selects the structurally-safe path; it does **not** mean "compute local deletes and ignore the guard."

- **Avoid** a static `mount_mode` config flag (alternative B): mount-ness is runtime, not config, state — a stale flag would silently disable the safeguards for a later real-`sync_root` use, re-arming the data-loss class.
- **Optional operator override:** a `sync_direction = "passive"` profile value (alternative D's naming) may force the enumerate path even with no live mount client (e.g. a monitoring-only profile). Phase 2; not required for the mount case.
- `skipLocalScan` (alternative C) is an **internal implementation detail** of the enumerate path, never a public knob.

### 4.1 IPC contract

New verb **`sync.enumerate`** (JSON-line, daemon-internal), parallel to `refresh.run`:
- Request: `{"verb":"sync.enumerate","reset":false}`
- Terminal event: `{"event":"enumerate.done","job_id":"…","ok":true,"upserted":N,"reaped":M,"complete":true}` or `{"ok":false,"error":"provider_error","message":"…"}`.
- Guarded by the same in-flight `AtomicReference` pattern as `RefreshRpcHandler` (one enumeration per profile at a time; concurrent request → `busy`).

`refresh.run` stays for the legacy real-`sync_root` use. `RefreshRpcHandler` routes: **mount client connected → `sync.enumerate`; else → legacy `syncOnce`.** (Keeps `unidrive refresh` working for both; the operator need not learn a new verb.)

## 5. Auto-poll (`--poll-interval`)

Add `--poll-interval <duration>` to `unidrive daemon run` (default `0` = off; recommended `60s`). When > 0, the daemon fires `sync.enumerate` on a timer **with ±10% jitter**, serialized by the in-flight guard, and **honours provider `Retry-After`** (on 429 from a provider, pause that profile's poll and back off). On each enumeration that changes `state.db`, the daemon **invalidates the FUSE co-daemon's dir/attr cache** for affected paths (see §6) so the next `ls` re-reads the DB.

**Spec amendment required:** `unidrive-daemon-design.md` G3 ("strictly reactive") gets a documented `--poll-interval` exception (BACKLOG-58 already files this). The verb (`sync.enumerate`) itself is reactive; only the optional in-process timer is the exception.

Defaults rationale (prior-art doc): 60s balances freshness vs API cost on a 195k-file account (a no-change incremental delta is one cheap call); jitter avoids thundering-herd across profiles; Internxt has no push so polling is the only signal (and its deletion-visibility lag is inherent — documented, not fixable client-side).

## 6. FUSE cache invalidation (cross-repo, `unidrive-mount-linux`)

The Rust co-daemon caches `getattr`/`readdir` results (`CachedAttr`). After an enumeration mutates `state.db`, the co-daemon must drop stale entries or the kernel keeps serving them. Two options:
- **(a)** Push via the existing `hydration.subscribe` NDJSON event stream: emit a `view.invalidated` event (path or subtree) that the co-daemon consumes to drop its cache entry / call `notify_inval_entry`. Preferred (event-driven, no polling on the Rust side).
- **(b)** Co-daemon sets a short attr/entry TTL so `ls` re-reads within seconds. Cheaper interim.

Phase 3 (sibling repo). Phase 1 can ship with the JVM side correct and rely on the co-daemon's existing re-read behaviour / a short TTL; the event-push is the clean finish. **This is a `unidrive-mount-linux` BACKLOG entry**, filed when Phase 1 lands.

## 7. Constraints any implementation must satisfy (from the knot doc §6–7)

- **A.** `RefreshRpcHandler` routes to the enumerate path when a mount client is connected.
- **B.** Reuse `db.batch { updateRemoteEntries(...) }` + `entryFromCloudItem` so rows are well-formed.
- **C.** Remote-deletion reaping only on a **complete** enumeration (§3.1) — own named test.
- **D.** Promote `pending_cursor → delta_cursor` (`promotePendingCursor`); `reset` calls `resetAll()` first. Make a deliberate decision on the NG5 "always re-enumerate" wording vs the persisted cursor (BACKLOG-82) — this design chooses **incremental by default, `--reset` for full**.
- **E.** Guards bypassed **by construction** (no local-inferred deletes), never via `--force-delete`.
- **F.** Daemon already holds `Mode.DAEMON`; no new lock.
- **G.** Skip the `sync_root`-drift guard; a mount profile may leave `sync_root` empty/unset.
- **H.** Write-back path (`HydrationImpl.uploadFromCache`) untouched; this cut is read-direction only.
- **Engine emits structured outcomes; no human strings in the engine** (core/app contract).
- **Don't grow the daemon to host a UI tier** (AGENTS.md) — enumerate logic lives in `core/app/sync`, surfaced via the IPC verb.

## 8. Non-goals

- Two-way sync for mounts (mounts write via `open_write`/`uploadFromCache`; not via this path).
- Replacing the legacy `SyncEngine` reconcile for real-`sync_root` profiles (`refresh.run` stays).
- Migrating the daemon/mount onto `TrackingEngine` (separate, larger effort; this cut is within the legacy engine the daemon already uses).
- Provider push/webhooks (OneDrive supports them; a future efficiency add — polling is the baseline).

## 9. Test plan (high level; per-task detail in the Phase-1 plan)

- `enumerateRemoteIntoState` upserts remote adds/mods into `state.db` without scanning `sync_root` (fake provider + empty sync_root + populated remote → rows appear, **no** `DeleteRemote`, **no** guard exception).
- **Complete-enum-only-reaping** (the §3.1 invariant): incomplete delta omitting a tracked path does **not** mark it deleted; a complete enumeration that genuinely drops it does.
- Cursor promotion: second enumeration resumes from the persisted cursor; `reset` re-enumerates from scratch.
- Cache eviction: a remote-deleted hydrated path evicts its cache file.
- `RefreshRpcHandler` routing: mount-client-connected → enumerate path (no guard exception on an empty sync_root); no mount client → legacy `syncOnce` (unchanged).
- Live: on `internxt_test` + `posteo_onedrive` mounts, `unidrive refresh` updates the view with **no** deletion/empty-sync_root guard, sync_root untouched.

## 10. Phasing

- **Phase 1 (this plan):** `enumerateRemoteIntoState` + complete-enum suppression + cache eviction + `sync.enumerate` verb + `RefreshRpcHandler` auto-routing. Makes `unidrive refresh` work on mounts. → `docs/dev/plans/mount-view-refresh-phase-1.md`.
- **Phase 2:** `--poll-interval` daemon timer (+ G3 spec amendment) + the corroboration guard (§3.2) + optional `passive` config.
- **Phase 3:** FUSE cache invalidation in `unidrive-mount-linux` (event-push). Sibling-repo BACKLOG entry.
