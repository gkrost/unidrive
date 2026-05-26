# Sync ↔ FUSE-mount architecture: the gordian knot

**Status:** research / evidence map (read-only audit). No code changed.
**Question:** why does `unidrive -p <profile> refresh` against a *mounted* profile get
rejected by the empty-sync_root guard and the deletion safeguard, and what is the single
structural conflation that makes "keep a mounted view fresh from the remote" hard?

All `file:line` anchors below are against the canonical source tree (not the `.claude/worktrees/*` mirrors).

---

## 0. TL;DR

The daemon/mount is a **one-way remote→local-view consumer** (the mount serves cloud files
as placeholders read out of `state.db`, with bytes in a separate hydration cache). But the
only mechanism wired to populate `state.db` from the remote is `refresh.run`, which calls the
**legacy bidirectional `SyncEngine.syncOnce`**. That engine assumes `sync_root` (a real local
directory) is the authoritative local replica: it *always* walks `sync_root` (`LocalScanner.scan`),
treats every `state.db` row absent under `sync_root` as a **local deletion**, and (since the
mount never materialises files in `sync_root`) plans to **delete those files on the remote**.
The empty-sync_root guard and the `max_delete_*` safeguards then correctly refuse to run.

`refresh` is therefore the structurally wrong mechanism for a mount: it reconciles a directory
the mount doesn't use, and its deletion-planning is meaningless for a read-only view consumer.

---

## 1. Component / data-flow map (ASCII)

```
                          ┌─────────────────────────────────────────────────────────────┐
   ../unidrive-mount-linux│         unidrive daemon run <profile>  (per-profile JVM)      │
   (Rust co-daemon)       │         DaemonRuntime.start()  [DaemonRuntime.kt:55]          │
   FUSE kernel ↔ libfuse  │                                                               │
        │                 │   ONE SyncEngine instance, syncRoot = config.syncRoot         │
        │ getattr/readdir  │   [DaemonRuntime.kt:119]                                       │
        │ open/read/write  │        │                    │                                 │
        ▼ (UDS JSON-line)  │        ▼                    ▼                                 │
  ┌──────────────┐   IPC   │  HydrationImpl        RefreshRpcHandler                       │
  │ Hydration SPI│◀───────▶│  [HydrationImpl.kt]   [RefreshRpcHandler.kt]                  │
  │ verbs        │ socket  │        │                    │ engine.syncOnce(                │
  └──────────────┘         │        │                    │   skipTransfers=true,           │
        │                  │        │                    │   skipRemoteGather=false)       │
        │                  │        │                    │ [RefreshRpcHandler.kt:65]        │
        │  hydration.list   │       ▼                    ▼                                 │
        │  hydration.open_*  │  reads/writes        SyncEngine.doSyncOnce                   │
        │                  │  state.db            ┌──────────────────────────────────────┐ │
        ▼                  │  (namespace +        │ scanner.scan()  ── walks sync_root ──┐│ │
  hydration.list(prefix)   │   metadata)          │   [SyncEngine.kt:784] [LocalScanner]  ││ │
   → stateDb.listDirect-   │        │             │ gatherRemoteChanges() ── provider.   ││ │
     Children()            │        ▼             │   delta()  [SyncEngine.kt:739/1353]   ││ │
   [HydrationImpl.kt:185]  │   ┌─────────┐        │ reconciler.reconcile()                ││ │
                           │   │ state.db│◀───────│   [SyncEngine.kt:855]                  ││ │
  open_read → ensure-      │   │(SQLite) │  WRITE │ ── empty-sync_root guard [.kt:827]    ││ │
   Hydrated() downloads    │   └─────────┘        │ ── max_delete_* safeguards [.kt:959]  ││ │
   into HYDRATION CACHE    │        ▲             └──────────────────────────────────────┘│ │
   [SyncEngine.kt:199]     │        │  byte content lives HERE, not in sync_root            │
        │                  └────────┼───────────────────────────────────────────────────┘ │
        ▼                           │
  ~/.cache/unidrive/hydration/<profile>/<path>   ◀── resolveCachePath() [SyncEngine.kt:345]
  (the mount's REAL backing store)

  config.syncRoot  (e.g. /home/gernot/InternxtTest)  ◀── the LEGACY local replica
        ▲                                                 the MOUNT NEVER WRITES HERE
        └── created at SyncCommand.kt:462 (sync path only); walked by LocalScanner on every
            syncOnce; for a mounted profile it is EMPTY or near-empty → all rows look DELETED.

  ─────────────────────────────────────────────────────────────────────────────────────────
  SEPARATE, NOT WIRED INTO THE DAEMON/MOUNT AT ALL:
  TrackingEngine [TrackingEngine.kt] + tracking.db [TrackingSet.kt]  ── only reachable via
  the `unidrive ts ...` CLI (TrackingCli.kt). Different source-of-truth model (tracking set,
  not sync_root); structurally safe deletes; but the mount cannot see it.
```

---

## 2. The two engines

| Property | Legacy `SyncEngine` (`core/app/sync/.../SyncEngine.kt`) | `TrackingEngine` (`core/app/sync-tracking/.../TrackingEngine.kt`) |
|---|---|---|
| Source of truth for "what's local" | `sync_root` directory walk; `state.db` row + on-disk file. A `state.db` row with **no file under sync_root** = local deletion. | The **tracking set** (`tracking.db`). A path the set doesn't track is invisible to deletion logic. `sync_root` is observed, not authoritative. |
| Local scan | `scanner.scan()` walks `sync_root` always (`SyncEngine.kt:784`, streaming variant `:697`). `LocalScanner.kt:154-162`: every DB path absent on disk → `ChangeState.DELETED`. | `scanLocal()` under `sync_root` (`TrackingEngine.kt:60`), but unioned with remote ∪ trackingSet (`:64`); adoption + collision logic, not "absent ⇒ delete". |
| Deletion safety | Post-hoc guards: empty-sync_root guard (`:827`), `max_delete_percentage` (`:965`), `max_delete_absolute` (`:981`), per-subtree (`:998`), top-level-never-hydrated guard (`:880/2833`). All bypassable with `--force-delete`. | `BatchGuard.inspect` — if it trips, **drop ALL delete actions, keep uploads/downloads** (`TrackingEngine.kt:109`, doc §5). Incomplete-enum suppression (`:112`). Crash-safe Pending rows. Deletes are safe **by construction** (the `.safe/` lemma). |
| Change detection | Local mtime/size vs `state.db`; remote via `provider.delta()` cursor (`:1354`). `detectMissingAfterFullSync` (`:2097`) turns "DB row not in full delta" into a `deleted=true` CloudItem. | Per-path `LocalObservation` × `RemoteObservation` × tracked state; content-hash rename heuristic. |
| Persistence | `state.db` (`StateDatabase`, `core/app/sync/.../StateDatabase.kt`). | `tracking.db` (`SqliteTrackingSet`, separate file — `TrackingSet.kt:11`, `:61`). |
| Wired into daemon/mount? | **YES** — both `HydrationImpl` and `RefreshRpcHandler` share one instance (`DaemonRuntime.kt:119-152`). | **NO** — only `unidrive ts` (`TrackingCli.kt`). `grep` confirms zero references in `core/app/cli` or `core/app/hydration` main sources. |

**Key asymmetry:** the *safe* engine exists but is not on the mount path; the mount path is the
*dangerous* engine whose entire safety model is built around `sync_root`-as-replica.

---

## 3. Persistence stores

| Store | Path | Written by | Read by | Coupling |
|---|---|---|---|---|
| `state.db` | `~/.config/unidrive/<profile>/state.db` (`DaemonCommand.kt:50`) | `SyncEngine` (reconcile, hydrate, namespace verbs), `RefreshRpcHandler` (via `syncOnce`). | The **mount**: `hydration.list` → `stateDb.listDirectChildren` (`HydrationImpl.kt:185`); `getattr`-equivalents `lastSynced`/`openForRead` → `stateDb.getEntry` (`HydrationImpl.kt:177,64`). | The mount's *entire namespace + metadata view* is `state.db`. The only remote→`state.db` population path is `SyncEngine.gatherRemoteChanges` + `updateRemoteEntries` (`SyncEngine.kt:861`) inside `syncOnce`. |
| `tracking.db` | `~/.config/unidrive/<profile>/tracking.db` (`TrackingCli.kt:99`) | `TrackingEngine`/`TrackingSet`. | `unidrive ts` only. | **Decoupled** from `state.db` and from the mount. `status`/`status --all` read only `state.db` and render a tracking-set profile as empty (BACKLOG line 30). |
| Hydration cache | `~/.cache/unidrive/hydration/<cacheKey>/<path>`, `cacheKey = profile.name` (`SyncEngine.resolveCachePath` `:345`; daemon sets `cacheKey = profileName` `DaemonRuntime.kt:119`) | `SyncEngine.ensureHydrated` / `uploadFromCache` (`:199`, `:248`). | The mount's read/write byte path (`open_read` returns `cache_path`; the Rust co-daemon reads/writes that file). | Holds **bytes only**; namespace/metadata is in `state.db`. **Disjoint from `sync_root`.** |

**What the mount reads to serve getattr/readdir:** purely `state.db` rows
(`listDirectChildren` / `getEntry`). The hydration cache supplies bytes on `open_read`; it never
backs `readdir`. `sync_root` is read by neither.

---

## 4. The daemon + refresh path (exact trace)

1. `unidrive refresh <profile>` (`RefreshCommand.kt`) is a **thin IPC client**: it requires a
   running daemon (spec NG8), connects to the UDS, sends `sync.subscribe` then `refresh.run`
   (`RefreshCommand.kt:72-81`).
2. Daemon registered `refresh.run` → `RefreshRpcHandler.handle` (`DaemonRuntime.kt:153`).
3. `RefreshRpcHandler.handle` launches `engine.syncOnce(skipTransfers = true, skipRemoteGather = false)`
   (`RefreshRpcHandler.kt:65`). With `reset=true` it first `db.resetAll()` (`:63`).
4. `SyncEngine.syncOnce` → `doSyncOnce` (`SyncEngine.kt:455/504`). With `skipRemoteGather=false`,
   `skipTransfers=true`:
   - **`sync_root` drift guard** (`:556-566`): throws if stored `sync_root` ≠ current.
   - **Local scan** runs regardless (`:784` non-streaming / `:697` streaming) — `scanner.scan()`
     walks `sync_root` (`LocalScanner.kt`). There is **no `skipLocalScan` seam**; `skipTransfers`
     only skips Pass 2 byte transfers, `skipRemoteGather` only skips `provider.delta()`.
   - **Remote gather** runs (`gatherRemoteChanges`, `:739/1353`) via the delta cursor.
   - **empty-sync_root guard** (`:827`): `if (!forceDelete && hydratedEntryCount > 10 && isSyncRootEffectivelyEmpty())`
     throws `IllegalStateException("Local sync_root '$syncRoot' is empty, but state DB knows N
     previously-hydrated entries ...")`. ← **internxt_test symptom verbatim.**
   - `reconciler.reconcile` (`:855`). For each hydrated `state.db` row whose file is absent
     under `sync_root`, `LocalScanner` set `ChangeState.DELETED`; with the remote still present
     (`UNCHANGED`), `Reconciler.kt:615` emits **`SyncAction.DeleteRemote`**.
   - **Deletion safeguards** (`:959-1019`): `max_delete_percentage` (`:965`), `max_delete_absolute`
     (`:981`), per-subtree (`:998`). ← **posteo_onedrive symptom**: "46 of 59 files (77%) would be
     deleted ... max_delete_percentage=50%".

So `refresh.run` runs a **full bidirectional reconcile minus byte transfers**, against a
`sync_root` the mount never populates. The guards are doing exactly their job; they are firing on
a category error, not a bug.

**Why internxt_test trips the *empty* guard but posteo_onedrive trips the *percentage* guard:**
internxt_test's `sync_root` (`/home/gernot/InternxtTest`) is literally empty → `isSyncRootEffectivelyEmpty()`
true → first guard. posteo_onedrive's `sync_root` apparently has *some* files (13 of 59 present),
so the empty guard passes but 46 deletes against 59 entries = 77% trips the percentage guard.
Same root cause, two different guards depending on how empty the unused `sync_root` happens to be.

---

## 5. sync_root vs hydration cache vs mount — wiring confirmed

- `config.syncRoot` is created **only on the sync path** (`SyncCommand.kt:462`,
  `Files.createDirectories(config.syncRoot)`). `DaemonRuntime` passes it into `SyncEngine`
  (`DaemonRuntime.kt:57,119`) but the mount never writes a byte there.
- The mount's backing store is the hydration cache, keyed per-account by `profile.name`
  (`resolveCachePath` `:345`; `cacheKey = profileName` `DaemonRuntime.kt:119`), matching the Rust
  co-daemon's `--cache` root. **Claim confirmed: a mounted profile's `sync_root` is unused by the mount.**
- The empty-sync_root guard is at `SyncEngine.kt:827`; `isSyncRootEffectivelyEmpty()` at `:2959`.
  The `sync_root`-drift guard is at `:556`.
- **Existing seams** (none is a clean cut, but they are the nearest precedents):
  - `skipTransfers` (`syncOnce` `:470`) — skip Pass 2 byte transfers only. *Local scan still runs.*
  - `skipRemoteGather` (`:476`) — skip `provider.delta()` only. *Local scan still runs.*
  - `fastBootstrap` / `--fast-bootstrap` (`:63`, `SyncConfig.fast_bootstrap` `:247`) — adopt the
    remote cursor without enumerating on first sync; suppresses `detectMissingAfterFullSync`
    (`gatherRemoteChanges` `:1365`). Closest in spirit to "don't infer deletions" but only first-sync.
  - `syncDirection = DOWNLOAD` (`SyncConfig.kt:264`, filter `SyncEngine.kt:900`) — filters the
    *action list* to download-side ops; but the local scan **still runs** and `DeleteLocal` is still
    emitted, and the safeguards still count `DeleteLocal` (`:960`). It does **not** stop the empty-
    sync_root guard (that fires before the direction filter, at `:827`).
  - **No `skipLocalScan` exists.** That is the missing seam.

---

## 6. The knot

**One structural conflation, in two sentences:** the mount is a *remote→local-view consumer*
whose entire view is `state.db`, but the only thing that refreshes `state.db` from the remote is
a *bidirectional reconcile engine* (`SyncEngine.syncOnce`) that treats a real `sync_root`
directory as the authoritative local replica. Because the mount stores nothing in `sync_root`
(bytes live in the disjoint hydration cache), the reconciler reads the empty/near-empty
`sync_root`, infers that every cloud-backed `state.db` row was *locally deleted by the user*, and
plans to propagate those deletions to the cloud — at which point the safeguards (correctly)
abort, so the mount can never be refreshed from the remote without either `--force-delete`
(catastrophic) or a structural change.

The deeper conflation is that **one `SyncEngine` instance, one `state.db`, and one `sync_root`
serve two incompatible roles**: (a) the mount's read-only "what does the cloud contain" namespace
projection, and (b) the legacy two-way mirror's "what should I make the cloud match." Role (a)
needs a pure *enumerate-remote→state.db* operation that never looks at `sync_root` and never
plans a delete-remote. Role (b) needs exactly the `sync_root` scan and the delete-planning that
breaks role (a). They share a code path, a DB, and a guard set, so you cannot make (a) work
without bypassing the safety machinery that (b) depends on — and bypassing it via `--force-delete`
re-arms the data-loss cannon the guards exist to prevent. This is also why the existing partial
defenses don't help: the mount-sync mutex (`mount-sync-mode-mutex-design.md`) only stops the two
*writers* racing; it does nothing for the *reader-refresh* case, because refresh **is** the sync
engine, just with transfers skipped.

### The clean cut

Introduce a **remote-enumerate-only path** that maps the existing `gatherRemoteChanges()` directly
into `state.db` (via `updateRemoteEntries` + a delete-detection step that is allowed to mark rows
TRASHED/DELETED in `state.db`) and **never**:
1. calls `scanner.scan()` / walks `sync_root`,
2. produces `localChanges` (so `localState` is never `DELETED` for an unmaterialised row),
3. emits or executes `DeleteRemote` / `Upload` / `MoveRemote` (no local→remote propagation),
4. evaluates the empty-sync_root guard or the `max_delete_*` safeguards (there are no
   local-inferred deletes to guard against; remote-observed deletes are authoritative).

This is essentially "what `refresh` *should* mean for a view consumer": pull the remote namespace
into `state.db`, reconcile **remote-against-state.db only**, mark remote-side adds/mods/dels, leave
the local side entirely alone. `provider.delta()` + the cursor machinery already produce exactly
the data needed (`gatherRemoteChanges` `:1353`); `detectMissingAfterFullSync` (`:2097`) already
knows how to turn "DB row not in full delta" into a remote deletion — that step is *safe* here
because it only flips `state.db` rows, it does not touch the cloud or the local FS.

**Every call site / guard / assumption the cut must satisfy:**

- **A. `RefreshRpcHandler.kt:65`** must call the new enumerate-only entry instead of
  `engine.syncOnce(skipTransfers=true, skipRemoteGather=false)` **when the profile is being served
  as a mount** (or unconditionally, if `refresh` is redefined to mean view-refresh — see §7).
- **B. `state.db` writers must stay consistent.** The enumerate-only path must use the same
  `db.batch { updateRemoteEntries(...) }` (`:860`) and the same `entryFromCloudItem` mapping
  (`:2667`) so `hydration.list`/`getEntry` see well-formed rows (correct `isFolder`, `remoteSize`,
  `remoteId`, `remoteModified`).
- **C. Remote-deletion handling for `state.db` only.** Reuse `detectMissingAfterFullSync` (`:2097`)
  but route its `deleted=true` items to a `state.db` status flip (mark TRASHED/DELETED + evict
  hydration-cache file — BACKLOG line 54 already files the eviction-on-remote-delete follow-up),
  NOT to a `DeleteRemote` provider call. **Must only run on a *complete* full enumeration**
  (`pending_cursor_complete`), mirroring `TrackingEngine`'s incomplete-enum suppression
  (`TrackingEngine.kt:112`) — otherwise a partial delta wrongly reaps live rows. This is the
  riskiest single invariant of the cut.
- **D. Cursor semantics.** Enumerate-only must promote `pending_cursor` → `delta_cursor` exactly
  like the no-transfer success path (`promotePendingCursor` `:2070`), so successive refreshes are
  incremental. Note the existing delta-resume UX surprise (BACKLOG line 82) and `--reset` (which
  must keep calling `db.resetAll()` first).
- **E. Guards bypassed *by construction, not by `--force-delete`*.** The cut must not reach the
  empty-sync_root guard (`:827`) or the `max_delete_*` block (`:959`) at all, because there are no
  local-inferred deletes. It must NOT set `forceDelete=true` (that would also disable the
  remote-side safety the legacy path needs and is semantically wrong).
- **F. Mode-mutex unaffected.** The daemon already holds `Mode.DAEMON`; enumerate-only is still a
  daemon-internal `state.db` write, no new lock.
- **G. `sync_root`-drift guard (`:556`).** Enumerate-only must skip it (it presupposes a meaningful
  `sync_root`), or the mount-profile config can legitimately leave `sync_root` empty/unset.
- **H. Write-back path untouched.** `HydrationImpl.uploadFromCache` (mount writes) must keep working
  unchanged; the cut is read-direction only. The mutex already prevents a concurrent legacy `sync`
  from clobbering mount writes.

A natural shape: add `private suspend fun enumerateRemoteIntoState(reset: Boolean)` to `SyncEngine`
(reusing `gatherRemoteChanges` + `updateRemoteEntries` + a state-only delete pass), expose it via a
new IPC verb, and point `RefreshRpcHandler` at it for mount profiles. This is **alternative A**
below, which the evidence shows is the cleanest cut.

---

## 7. Design constraints any fix must respect

From `AGENTS.md`, `unidrive-daemon-design.md`, `mount-sync-mode-mutex-design.md`, and BACKLOG:

- **Daemon is strictly reactive (G3).** No background reconcile loop, no scheduled enumeration,
  no auto-bootstrap (`unidrive-daemon-design.md` §2 G3). An enumerate-only path is fine *as an IPC
  verb*; a daemon-internal poll loop needs the spec amendment already filed (BACKLOG line 58,
  `--poll-interval`).
- **No persistent refresh cursor is a daemon *non-goal* (NG5 / §5.3)** — but the underlying
  `SyncEngine` *does* persist `delta_cursor`. The spec NG5 wording ("`refresh.run` always
  re-enumerates from scratch") is already contradicted by the live delta-resume behaviour
  (BACKLOG line 82). The cut should make a deliberate decision here, not inherit the ambiguity.
- **"Don't grow the daemon to host a UI tier" / engine emits structured outcomes, clients render**
  (`AGENTS.md` "What not to do"; `core-app-contract.md`). The enumerate-only logic belongs in
  `core/app/sync` (engine), surfaced via an IPC verb; no human strings in the engine.
- **Hydration cache is keyed per-account** (`cacheKey = profile.name`; namespace-verbs spec; BACKLOG
  line 49 flags the socket-hash vs cache-name divergence as a latent landmine — don't make it worse).
- **Mount ⇄ sync mutual exclusion** (`mount-sync-mode-mutex-design.md`; daemon G6 extends it to
  daemon ⇄ sync). The cut must not require running legacy `sync` against a mounted profile.
- **Legacy `SyncEngine` is on a retirement path** (`AGENTS.md` "What lives where"; mode-mutex §1.4):
  the tracking-set engine inherits "mount or mirror, not both." A fix should not deepen the mount's
  dependence on the legacy engine's `sync_root` model in a way that fights that migration.
- **`--force-delete` is the existing escape hatch but is a footgun** (BACKLOG line 20 is the exact
  internxt deletion-safeguard incident). A fix that tells operators to run `refresh --force-delete`
  on a mount is wrong — that is the data-loss path, not a workaround.
- **Structural-safety invariants earn their keep** (`AGENTS.md`; CLAUDE.md orthogonal-invariant
  rule). The "complete-enumeration-only deletes" invariant (constraint C above) needs its own named
  test, separate from the happy-path enumerate test.

---

## 8. Evaluation of the 5 proposed alternatives

| Alt | Summary | Verdict | Evidence / reasoning |
|---|---|---|---|
| **A** | New IPC verb `sync.enumerate`: delta-enumerate → `state.db`, skip local scan/reconcile/apply; daemon polls it for mounts; `refresh.run` untouched. | **Cleanest cut. Feasible. Recommended.** | Maps 1:1 onto the §6 clean cut. Reuses `gatherRemoteChanges` (`:1353`) + `updateRemoteEntries` (`:861`) + `detectMissingAfterFullSync` (`:2097`, state-only). Never calls `scanner.scan`, never emits `DeleteRemote`, never reaches the guards (`:827`,`:959`) — *by construction*, not by `--force-delete`. Honours G3 (verb, not loop; the "daemon polls" clause needs the BACKLOG-58 `--poll-interval` amendment, but the verb itself is reactive). Engine-side logic, IPC-surfaced → respects core/app contract. **Risk:** the complete-enumeration-only delete invariant (constraint C); must suppress remote-delete reaping on partial/incomplete delta (copy `TrackingEngine.kt:112`). Leaves `refresh.run`/legacy `sync` semantics intact for the real-`sync_root` use case. |
| **B** | Config `mount_mode=true` per profile → auto-selects enumerate-only, skips sync_root validation + safeguards. | **Feasible but fights the architecture; couples config to runtime topology.** | "Is this profile mounted" is a *runtime* fact (is a co-daemon connected?), not a *static config* fact. A profile can be sync'd today and mounted tomorrow; a stale `mount_mode=true` would silently disable the safeguards for a profile someone later uses with a real `sync_root` — re-arming the exact data-loss class BACKLOG-20 documents. Also duplicates intent already encoded by the lock mode / connected mount clients. Prefer the verb (A) + auto-detect (E) over a config flag. If kept, it must *only* select the enumerate-only path, never `forceDelete`. |
| **C** | `skipLocalScan: Boolean` on `syncOnce` (reconciler sees zero local deletions → safeguards don't trip; daemon sets it for profiles with active mount clients). | **Partially feasible, but a leaky cut — does too much and too little.** | A `skipLocalScan` seam *is* the missing knob (today only `skipTransfers`/`skipRemoteGather` exist, neither skips the scan — `SyncEngine.kt:697/784`). With no local scan, `localChanges` is empty, so `Reconciler.kt:615` never fires `DeleteRemote` and the safeguards see `deleteCount==0`. **But:** (i) the empty-sync_root guard at `:827` runs *before* reconcile and would still need a separate bypass; (ii) `syncOnce` would still run the full reconcile + Pass-1 apply machinery (mkdirs, placeholder ops, even `MoveLocal`) that a pure view-refresh shouldn't; (iii) it keeps the dangerous and the safe behaviour in one method gated by a boolean — easy for a future edit to mis-wire (the very conflation we're trying to cut). Acceptable as an *implementation detail inside* A's enumerate-only path, not as the public contract. |
| **D** | Generalised `sync_direction="passive"` / `mode="passive"`: only receives remote state, never mutates local FS. | **Conceptually closest to the *intent*, but the current direction filter is post-scan and doesn't address the guards.** | `SyncDirection` already exists (`SyncConfig.kt:264`) and `DOWNLOAD` filters the action list (`:900`). A `PASSIVE` value could be the principled home for "remote→view only." **However**, the existing direction filter runs *after* `scanner.scan()` and *after* the empty-sync_root guard (`:827`) — so naively adding `PASSIVE` to the enum still trips the guard and still pays for the local scan. To make `PASSIVE` correct you must *also* short-circuit the scan and the guards, i.e. you end up implementing A/C underneath. Good *naming* for the operator-facing knob; not a standalone mechanism. Note `PASSIVE` also implies "never mutate local FS" which for a mount is already true (writes go to the cache via the SPI, not the engine's apply loop) — so the name slightly overpromises. |
| **E** | Auto-detect from daemon context: if the profile's daemon has connected mount clients, treat local deletions as "don't care." | **Right *trigger*, wrong *implementation* if "don't care" means bypassing the safeguard.** | The daemon already tracks connected clients (`server.clientCount`, `DaemonRuntime.kt:160`; `hydration.subscribe` connections). Using "a mount client is connected" to *select the enumerate-only path* (alt A) is exactly right and avoids the stale-config hazard of B. But "treat local deletions as don't care" must mean **don't compute local deletions at all** (don't scan sync_root), NOT "compute them and ignore the safeguard" — the latter still reaches `:827`/`:959` and is one refactor away from re-enabling cloud deletes. Also fragile alone: a mount that disconnects mid-refresh, or a refresh issued in the gap before the co-daemon connects, would flip back to the dangerous path. Best used as the *auto-selection signal feeding A*, with the enumerate-only path being the thing that is structurally safe regardless of client state. |

**Recommendation:** **A** is the clean architectural cut — a dedicated enumerate-remote→`state.db`
verb/method in `SyncEngine` that never scans `sync_root`, never plans local→remote deletes, and
never reaches the guards. Use **E**'s "mount client connected" as the *auto-selection trigger* for
when `refresh` (or a daemon poll) should pick A over the legacy reconcile, and borrow **D**'s
`passive` naming for the operator-facing config if a static override is also wanted. Treat **C**
(`skipLocalScan`) as an internal helper of A, not a public knob, and avoid **B** (static
`mount_mode` config) because mount-ness is runtime, not config, state.

The one invariant that must have its own named test (CLAUDE.md orthogonal-decomposition):
*remote-observed deletions are only reaped into `state.db` after a **complete** enumeration* —
otherwise a partial/throttled delta silently empties the mount view (the failure shape already
seen live, BACKLOG line 58's deletion-staleness corollary, and the `TrackingEngine.kt:112`
suppression is the proven pattern to copy).

---

## 9. Evidence index (file:line)

- Daemon wiring (one engine, both roles): `core/app/cli/.../DaemonRuntime.kt:119` (engine, syncRoot, cacheKey), `:120-121` (HydrationImpl), `:152-155` (RefreshRpcHandler).
- refresh client → RPC: `core/app/cli/.../RefreshCommand.kt:72-81`; handler `core/app/cli/.../RefreshRpcHandler.kt:65` (`engine.syncOnce(skipTransfers=true, skipRemoteGather=false)`), `:63` (`db.resetAll()` on reset).
- syncOnce / doSyncOnce + flags: `core/app/sync/.../SyncEngine.kt:455` (`syncOnce`), `:470` (`skipTransfers`), `:476` (`skipRemoteGather`), `:504` (`doSyncOnce`).
- sync_root drift guard: `SyncEngine.kt:556-566`.
- local scan (always runs): `SyncEngine.kt:784` (non-streaming), `:697` (streaming); `core/app/sync/.../LocalScanner.kt:154-162` (absent ⇒ `ChangeState.DELETED`).
- remote gather (delta cursor): `SyncEngine.kt:739`, `:1353` (`gatherRemoteChanges`); full-enum delete inference `:2097` (`detectMissingAfterFullSync`).
- **empty-sync_root guard:** `SyncEngine.kt:827`; helper `isSyncRootEffectivelyEmpty()` `:2959`.
- reconcile call: `SyncEngine.kt:855`; verdict table `core/app/sync/.../Reconciler.kt:545-660`; **local-absent+remote-present ⇒ `DeleteRemote`** `Reconciler.kt:615`; remote-deleted ⇒ `DeleteLocal` `:598`.
- updateRemoteEntries (remote→state.db): `SyncEngine.kt:860-862`, `:2685`; `entryFromCloudItem` `:2667`.
- **deletion safeguards:** `SyncEngine.kt:959` (block), `:965` (`max_delete_percentage`), `:981` (`max_delete_absolute`), `:998` (per-subtree); direction filter `:884-911`.
- cursor promotion: `SyncEngine.kt:2070` (`promotePendingCursor`).
- hydration cache path: `SyncEngine.kt:345` (`resolveCachePath`), `:199` (`ensureHydrated`), `:248` (`uploadFromCache`).
- mount reads state.db: `core/app/hydration/.../HydrationImpl.kt:185` (`list`→`listDirectChildren`), `:177` (`lastSynced`→`getEntry`), `:64` (`openForRead`→`getEntry`); IPC dispatch `core/app/hydration/.../HydrationIpcHandler.kt:252` (`hydration.list`).
- sync_root creation (sync path only): `core/app/cli/.../SyncCommand.kt:462`.
- existing seams: `--fast-bootstrap` `SyncEngine.kt:63`, `SyncConfig.kt:247`, gather `:1365`; `SyncDirection` `SyncConfig.kt:264`.
- TrackingEngine (safe, not wired to mount): `core/app/sync-tracking/.../TrackingEngine.kt:43` (ctor), `:60-64` (scan+union), `:109-120` (BatchGuard / incomplete-enum suppression); `tracking.db` separate `core/app/sync-tracking/.../TrackingSet.kt:11,61`; CLI-only entry `TrackingCli.kt:99`.
- StateDatabase: `core/app/sync/.../StateDatabase.kt:72` (`resetAll`), `:412` (`getEntry`), `:457` (`getHydratedEntryCount`), `:539` (`listDirectChildren`), `:602` (`markDeleted`).
- Specs/constraints: `docs/dev/specs/unidrive-daemon-design.md` (G3 §2, NG5/§5.3, §4.2 refresh.run, §3.3 RefreshRpcHandler note); `docs/dev/specs/mount-sync-mode-mutex-design.md` (§1.2 clobber incident, §1.4 retirement); `AGENTS.md` ("don't grow the daemon to host a UI tier", retirement path); BACKLOG.md lines 20 (deletion-safeguard incident), 54 (cache eviction on remote delete), 58/59 (mount-view staleness / two-truths), 82 (delta-resume UX).
