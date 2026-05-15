# Closed items — append-only archive

> When closing a backlog item, **move** the full frontmatter block from [BACKLOG.md](BACKLOG.md) to the bottom of this file and add `status: closed`, `closed: YYYY-MM-DD`, and optional `resolved_by:`. Never edit entries above the latest. See [AGENT-SYNC.md](../AGENT-SYNC.md).

<!-- First closed entry will be appended below this marker. -->

---
id: UD-240i
title: Reconciler.detectMoves O(D×U) Files.isRegularFile syscall storm
category: core
priority: high
effort: S
status: closed
closed: 2026-05-04
resolved_by: commit 6c33d49. Already-fixed bookkeeping: commit 6c33d49 ('fix(UD-240i): bySize lookup in detectMoves — O(U) probes, was O(D×U)') replaced the per-(delete,upload) localFsProbe storm with a single pre-pass groupBy-bySize over uploads (Reconciler.kt:540-546). Total probe calls drops from D×U to U; the original 5M syscalls / ~4 min on 67k-upload first-sync (jstack 2026-05-02 17:48 PID 12764) is gone. Companion regression test in commit 9c0915e.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:301
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:309
opened: 2026-05-02
---
**`Reconciler.detectMoves` runs an O(D × U) `Files.isRegularFile` + `Files.size` syscall storm — D = DeleteRemote actions, U = Upload actions. On a real 67k-upload first-sync this stalls the engine for ~4 minutes of pure CPU and ~5 million native filesystem syscalls.**

## Captured live (2026-05-02 17:48, jstack PID 12764)

The running daemon — same workload that motivated UD-240g — was sampled mid-stall. Every jstack frame three seconds apart showed:

```
"main" runnable, cpu=334s elapsed=564s
  at sun.nio.fs.WindowsNativeDispatcher.GetFileAttributesEx0  (native)
  at java.nio.file.Files.isRegularFile
  at org.krost.unidrive.sync.Reconciler.detectMoves          (Reconciler.kt:309)
  at org.krost.unidrive.sync.Reconciler.reconcile            (Reconciler.kt:138)
  at org.krost.unidrive.sync.SyncEngine.doSyncOnce           (SyncEngine.kt:221)
```

Process at 98 % CPU, **0 B/s IO** — pure syscall thrash against the OS file-attribute cache (the local tree was just walked seconds ago by `LocalScanner`, so everything was hot).

## Root cause

[Reconciler.kt:301-345](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt#L301) — file-move detection:

```kotlin
for (del in deletes) {              // outer: ~75 DeleteRemote actions
    val entry = entryByPath[del.path] ?: continue
    if (entry.isFolder || entry.remoteId == null) continue

    for (up in uploads) {            // inner: ~67,000 Upload actions
        ...
        val localPath = resolveLocal(up.path)
        if (!Files.isRegularFile(localPath)) continue   // ← line 309
        if (Files.size(localPath) == entry.remoteSize) {
            ...
            break
        }
    }
}
```

For each `(delete, upload)` pair we issue at least one `Files.isRegularFile` (and `Files.size` on hits). Worst case: D × U syscalls. With D ≈ 75 and U ≈ 67,000 → ~5 M Windows file-attribute syscalls at ~50 µs each = ~4 min wall-clock. UD-240g's bulk-load fix does **not** address this — the storm is in syscalls, not DB calls — so even with UD-240g landed the user still sees the same multi-minute silent hang on this workload (the heartbeat from UD-240g would fire from the main path-walk loop *before* `detectMoves`, but `detectMoves` itself remains heartbeat-free and silent).

## Proposal

Pre-pass uploads **once**, building a `Map<sizeBytes, MutableList<SyncAction.Upload>>` keyed by file size (only entries that pass `Files.isRegularFile`). Then for each delete look up `bySize[entry.remoteSize]` in O(1) and pick the first un-matched candidate.

```kotlin
// One pass, U syscalls total
val uploadsBySize: Map<Long, List<SyncAction.Upload>> = uploads
    .mapNotNull { up ->
        val p = resolveLocal(up.path)
        val info = probeLocalFile(p) ?: return@mapNotNull null  // null on non-regular
        Triple(up, p, info.size)
    }
    .groupBy({ it.third }, { it.first })

for (del in deletes) {                         // D iterations
    val entry = entryByPath[del.path] ?: continue
    val candidates = uploadsBySize[entry.remoteSize] ?: continue   // O(1)
    val match = candidates.firstOrNull { it.path !in matchedUploads } ?: continue
    // ... emit MoveRemote ...
}
```

Total syscalls drop from D × U (~5,000,000) to U (~67,000) — purely linear. Wall-clock at ~50 µs/syscall: **3.4 s** instead of ~4 min on the same workload.

## Acceptance

- `Reconciler.detectMoves` issues at most one `isRegularFile` + at most one `size` per Upload action, regardless of how many DeleteRemote actions exist.
- New `ReconcilerTest` injects a syscall-counting `localFsProbe` and asserts the bound holds for D = 100 deletes × U = 1000 uploads with no matches: **probe calls ≤ 1000**, not 100,000.
- New correctness test asserts that a real (delete + same-sized upload) pair still produces a `MoveRemote` action — no behaviour regression.
- Existing `Reconciler` tests, including the case-collision and folder-move paths, still pass.
- Wall-time on the user's 2026-05-02 67k+19k workload drops from ~4 min in `detectMoves` to single-digit seconds.

## Why filed as 240i

Sibling of UD-240g and UD-240h; same first-sync-pain class, same workload, but the bottleneck is filesystem syscalls, not DB calls. Stacks on the UD-240g branch because both touch `Reconciler.detectMoves` (UD-240g changed its signature to take `entryByPath`); UD-240i builds on that signature.

## Surfaced

2026-05-02 17:48 — running daemon was sampled live with `jstack` while the operator was deciding whether to wait or kill. Three samples 3 s apart all showed the identical `Reconciler.detectMoves(Reconciler.kt:309)` frame. The CPU vs IO split (98 % CPU / 0 B/s IO) confirmed it was a syscall loop against the OS attribute cache, not a SQLite read.

---
id: UD-240
title: Feedback for long-running (>0.5s) CLI/UI actions — always-on IPC, progress state file, heartbeat, inline CLI progress
category: core
priority: medium
effort: M
status: closed
closed: 2026-05-04
resolved_by: Closed-as-decomposed-and-superseded after comprehensive sub-item audit 2026-05-04. Of the 6 sub-item proposals (240a-f) in the umbrella body: 240a shipped via UD-746 (commit 4a69fc7 — always-on IPC for one-shot sync). 240d shipped under UD-735+UD-742+UD-757 then was reversed by UD-408 (user explicitly preferred 1:1 line scrollback over the original \r-repaint+truncate ergonomics). 240e is structurally moot — the ui/ tree was retired by ADR-0013 (no DaemonDiscovery.kt to fall back). 240b (progress.json), 240c (heartbeat / idle-alive), 240f (still-working nudges) were never filed; UD-746's always-on IPC absorbs most of their original UX motivation, so they're deferrable rather than blocking. The umbrella's sibling perf-class sub-tickets that DID get filed: UD-240g (closed), UD-240h (open — real perf gap, addressed in same session as this close), UD-240i (closed in same hygiene pass — bySize pre-pass fix), UD-240j (closed), UD-240k (closed). The umbrella's stated 'Six sub-tickets filed' acceptance is moot; the underlying UX motivation has been delivered through the parallel UD-7xx and UD-225/UD-901 chains. UD-240h tracked separately.
opened: 2026-04-19
chunk: ipc-ui
---
**Improve feedback for long-running (>0.5s) unidrive actions across both CLI and UI. Research done 2026-04-19; concrete recommendations below.**

## Observed failure mode that motivated this ticket

2026-04-19 session: daemon PID 10792 ran `unidrive -c <config> -p inxt_user sync` (one-shot, no `--watch`) for ~50 min. The UI tray reported "no daemon" the whole time, which read as broken. Root cause is structural, not a UI bug per se.

## Root cause

[`SyncCommand.kt:153-157`](core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:153) only starts `IpcServer` + `NamedPipeServer` when `watch = true`. [`DaemonDiscovery.kt:34-49`](ui/src/main/kotlin/org/krost/unidrive/ui/DaemonDiscovery.kt:34) is pure file-glob on `unidrive-*.sock`. **One-shot syncs never advertise -> UI is blind to them by design.** Compounding: on Internxt-scale backfills (hours), one-shot means "long-running invisible".

## State inventory (where truth lives today)

| Carrier | Scope | Readable by | Liveness |
|---|---|---|---|
| `SyncState` (in `DaemonDiscovery`) | in-memory | UI only | only during `--watch` |
| `state.db` | persistent | anyone | between syncs (not live) |
| `failures.jsonl` | persistent, append | anyone | record, not liveness signal |
| `unidrive.log` | persistent, 10 MB rotate | anyone | volatile tail |
| IPC NDJSON (AF_UNIX / NamedPipe) | volatile stream | UI only | only during `--watch` |

## Proposed concrete wins (each a separate sub-ticket candidate)

### 240a — Always-on IPC (XS-S)

Drop the `if (watch)` guard on `IpcServer`/`NamedPipeServer` creation in `SyncCommand.kt`. Socket advertises for every `sync` run, one-shot or continuous. **UI discovers one-shot daemons automatically; no other change needed on the UI side.** Cheapest + highest-leverage fix.

### 240b — Progress state file (S)

Daemon writes `<profile>/progress.json` atomically every ~500 ms containing phase / items-processed / items-total / transfer-bps / eta / last-action. Survives UI restarts. Lets `unidrive status` (and the stale-UI tooltip) report real progress without requiring a live IPC connection. Also captures progress of already-running daemons after UI restart.

### 240c — Heartbeat + idle-alive event (XS)

Even when engine is quiescent, emit `{event:"idle", since: <ISO8601>, cycles_idle: N}` every 10 s. Distinguishes "alive, nothing to do" from "dead". Kills 90% of the "is the tray ok?" uncertainty.

### 240d — CLI inline progress reporter (XS)

Add a `--progress` flag (default on when stdout is a TTY, off when piped) that prints one compact line re-painted with `\r` every 500 ms:
```
[scan] 12,345 items  |  [downloads] 47/200 (23%)  |  2.3 MB/s  |  ETA 4m
```
`CliProgressReporter` already receives all the events; just needs a throttled painter.

### 240e — Daemon discovery fallback (S)

When UI sees no socket **but** `<profile>/progress.json` mtime is < 60 s old, show "syncing (legacy non-IPC)" in the tray instead of "no daemon". Graceful handling of old daemons or the transition window before 240a lands everywhere.

### 240f — Action-level "still working" nudges (S)

For specific operations that can exceed 500 ms without emitting progress (large file upload, folder delete, graph listChildren pagination), emit a `{event:"still_working", operation:"uploadLargeFile", elapsed_ms:1200, path:"..."}` nudge every ~1 s. Symmetric coverage so nothing is silent.

## Ordering recommendation

**240a first** (server-side fix is cheap, unblocks UI discovery immediately). Then **240d** (CLI inline progress — independent, user-visible win). Then **240b + 240c** together (progress.json + heartbeat — they co-design nicely). **240e + 240f** last.

## Non-goals

- GUI progress bar rework — out of scope; the tray already has progress rendering.
- Prometheus metrics / Grafana dashboard — that's a separate observability track.
- Replacing NDJSON with gRPC / Protobuf — tested previously; not warranted.

## Related

- UD-212 (closed) — MDC profile tag propagation; any new progress fields must respect the profile context.
- UD-221 (closed) — tray submenu work; same UI module that shows the per-profile status.
- UD-222 (closed) — "adopt writes zeros" incident; heartbeat + progress.json would have made it 10 min of triage instead of 2 hours.
- UD-316 (closed 2026-04-19) — `status --audit` computes a snapshot; progress.json would let `status` (no flag) do the same for in-flight syncs.

## Acceptance for the umbrella

- Six sub-tickets filed (240a-f), each scoped to its own commit.
- Tray correctly shows a running one-shot sync within 10 s of start, with phase + progress%.
- Piped CLI output preserves current quiet behaviour; interactive CLI gets the new `\r`-painted progress line.
- No regression in existing `--watch` daemon path.

## Priority

**Medium, M effort** as an umbrella. **240a alone is XS and worth landing tomorrow** regardless of the rest. 240d is next easiest standalone win.

---
id: UD-240h
title: LocalScanner pending-upload upserts — 67k single-row writes inside file walk
category: core
priority: medium
effort: S
status: closed
closed: 2026-05-04
resolved_by: commit 4496cfb. LocalScanner.scan now wraps Files.walkFileTree in a single db.beginBatch/commitBatch so the 67k UD-901 pending-upload pre-writes from a first-sync coalesce into one SQLite commit (was N transactions). finally{} rolls back on partial-walk failure to restore autoCommit, so the engine's later Pass-1 beginBatch isn't silently piggybacked. Two regression tests cover the happy path + cross-run autoCommit invariant. Estimated 50-100x wall-clock speedup on first-sync local scan dominated by the per-commit fsync going from N to 1.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:72
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt
opened: 2026-05-02
---
**`LocalScanner.scan` issues one synchronous `db.upsertEntry` per NEW local file inside `Files.walkFileTree`. On a 67k-file first-sync that's 67k single-row INSERTs interleaved with the disk walk, plus all the SQLite per-statement overhead.**

## Where

[LocalScanner.kt:72-86](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:72) — inside `visitFile`:

```kotlin
if (entry == null) {
    changes[relativePath] = ChangeState.NEW
    val sparseLeftover = isSparseLeftover(file, attrs.size())
    db.upsertEntry(SyncEntry(...))   // <-- one synchronous SQLite write per NEW file
}
```

This is UD-901's pending-upload row write — correct semantically, but the I/O pattern is one transaction per file. The walk holds no lock, so each upsert acquires-commits-releases the SQLite write lock individually. With 67k NEW files the cumulative wall time is dominated by this storm, not the actual file-tree walk.

## Adjacent context (UD-240g sibling)

UD-240g found and fixed the silent-reconcile-with-86k-SELECTs problem in `Reconciler`. The LocalScanner-during-walk pattern is the same shape (synchronous DB-per-item inside a hot loop), just on the write side. Doing both makes the first-sync path's DB cost basically negligible.

## Proposal

Wrap the walk in `db.batch { ... }`:

```kotlin
db.batch {
    Files.walkFileTree(syncRoot, object : SimpleFileVisitor<Path>() { ... })
}
```

`StateDatabase.batch` already exists (used by `SyncEngine.updateRemoteEntries`) — it runs the block inside a single transaction, deferring commit to the end. With WAL mode the in-walk upserts become a single fsync at commit time instead of 67k individual fsyncs.

## Acceptance

- `LocalScanner.scan` runs entirely inside one `db.batch { ... }`. The block must include the post-walk deletion-detection loop ([LocalScanner.kt:142](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:142)) so that whole pass is one transaction.
- On a 67k-NEW first-sync, the local-scan wall time drops by ≥ 5× on Windows (where SQLite per-statement fsync is the worst). Measured via the `last_scan_secs_local` metric SyncEngine already records.
- Existing `LocalScannerTest` cases still pass.
- A new test asserts that an exception thrown from `visitFile` rolls back the in-progress upserts (no half-written rows in `state.db`) — `db.batch` already handles this via JDBC transaction semantics, but pin the contract.

## Why filed as 240h

Sibling of UD-240g: same first-sync-pain class, same symptom (long silent stretch), same root cause shape (per-row DB call inside a hot loop). Different file, separable commit. Numbered `h` to make the relationship obvious in `git log` and BACKLOG / CLOSED searches.

## Surfaced

2026-05-02 session, while diagnosing UD-240g. Code reading made the pattern visible; not yet validated by direct profiling. The acceptance criterion above (`5×` wall-clock reduction) is the measurable test.

---
id: UD-236
title: CLI: split `sync` into `refresh` + `apply` + `sync` — git-style three-verb model (Direction B chosen)
category: core
priority: medium
effort: M
status: closed
closed: 2026-05-04
resolved_by: commit 7ceb6c9. Three-verb split implemented via SyncEngine.syncOnce flags (skipTransfers / skipRemoteGather), RefreshCommand + ApplyCommand as open-class subclasses of SyncCommand, and a StatusCommand --pending preview. Backwards-compatible — existing sync invocations preserved. Realistic effort came in at S not L because the existing UD-901 (remoteId=null = upload pending) and UD-225 (isHydrated=false = download pending) DB row markers ARE the persisted pending-actions state — Reconciler's recovery loops surface them as DownloadContent/Upload actions, so apply just runs reconcile with empty remoteChanges. No pending_actions table needed. Three test cases cover refresh-defers-Pass-2, apply-drains-pending, apply-no-op-when-empty. consequences.md docs section deferred — docs/user-guide/ was retired in maintainer commit 902f467; picocli --help on the new commands documents the verbs in the meantime.
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
  - docs/user-guide/consequences.md
opened: 2026-04-19
chunk: core
---
**UX feedback 2026-04-19.** Gernot's paraphrase during the Internxt-auth session: *"sync state of files (from) cloud provider; the action is idempotent to the local files, that changes when download is triggered"* — matches the pre-UD-222 mental model (placeholder stubs until `get`). Post-UD-222 the code actually moves bytes by default because remote-new non-folders emit `DownloadContent` in Pass 2; the user-facing mental model and the implementation drifted apart.

**Direction chosen 2026-04-19: B — git-style three-verb split.** Gernot: *"it's more git'ish style, i like that"*.

## Target shape

```
unidrive -p X refresh     # Gather + Reconcile only; update state.db with remote changes. Moves ZERO bytes.
unidrive -p X apply       # Apply pending DB actions (download, upload) without re-gathering.
unidrive -p X sync        # refresh + apply (the current combined path — kept as-is so existing scripts keep working).
```

Parallels `git fetch` → `git merge` → `git pull`. Matches unidrive's internal three-phase architecture: Gather → Reconcile → Apply.

## Implementation sketch

- Extract the Gather+Reconcile portion of `SyncCommand.call()` into a `RefreshCommand`. It runs the engine up to the point actions are generated, persists `pending_cursor` + any inserted/updated DB entries, and prints the action summary (e.g. *"12 download, 3 upload, 1 conflict pending — run `apply` to execute"*).
- Extract the Apply portion into `ApplyCommand`. It reads the persisted pending actions from `state.db` (new table or field per action — needs a design bit), executes Pass 1 + Pass 2, clears pending on success.
- `SyncCommand` becomes a thin composition: `refresh` then (if refresh produced actions) `apply`. Flags `--download-only` / `--upload-only` propagate to the apply phase.
- `status --pending` (companion; was direction C's idea, fits here too) prints what a hypothetical `apply` would do — cheap, reads the same pending table.

## Persistence for pending actions

Today `SyncAction` is in-memory only, generated per `sync` run. To make `apply` work as a separate command, the action list needs to survive between processes. Options:
(a) A new `pending_actions` SQLite table in `state.db` with rows per action (path + type + remote-ref).
(b) A serialised JSON file alongside `state.db` (`pending.json`).
(a) is cleaner; (b) is simpler. Lean (a) for rollback-ability + atomic commits.

## Breaking changes

None for existing scripts that invoke `sync`. Anything new goes under `refresh` / `apply`. Old `--download-only` / `--upload-only` semantics preserved on `sync`; propagated to `apply` as well.

## Acceptance

1. `unidrive -p X refresh` updates `state.db` with the current remote delta and prints a summary of pending actions. No bytes moved.
2. `unidrive -p X apply` executes the pending actions from (1); `state.db` after is identical to what `unidrive -p X sync` would have produced.
3. `unidrive -p X sync` with no changes behaves identically to today's `sync` (no regressions in MCP round-trip tests).
4. `unidrive -p X status --pending` (new) prints the action plan without executing.
5. `docs/user-guide/consequences.md` gets a section "The three verbs" explaining when to use which.
6. Existing `SyncCommand`-level integration tests pass unchanged. New tests cover the split flow.

## Related

- UD-222 (closed) — the change that drove sync to move bytes by default; this ticket is the UX consequence.
- UD-220 (open) — `consequences.md` user doc; home for the "three verbs" section.
- UD-713 (open) — first-sync ETA; with `refresh` as a separate command, the ETA question becomes cleanly scoped to enumeration.
- UD-237 (open, sibling) — `-p TYPE` auto-resolve polish; both tickets raised at the same verify-the-Internxt-auth moment.

## Priority

**Medium, M effort.** Breaks nothing existing, matches git's proven ergonomics, explicit three-phase visibility makes first-sync ETA + offline-quota queries cleaner. Not v0.1.0 critical but pulls a meaningful weight of UX clarity across several open tickets.

---
id: UD-213
title: BenchmarkCommand multi-profile cache invalidation (retroactive)
category: core
priority: medium
effort: S
status: closed
closed: 2026-05-13
resolved_by: commit 407c664. Retroactive ID for already-shipped profile-cache fix; previously squatted on UD-211 in source/EXTENSIONS.md. Drift caught 2026-05-13 during UD-211 watcher impl docs-sweep.
opened: 2026-05-13
---
**Source:** Codex review on PR #12 caught that `Main.resolveCurrentProfile()` memoises the first resolved profile in a private `_profile` cache (and `_vaultData` likewise). `BenchmarkCommand`'s multi-profile loop mutated `main.provider` per iteration but did not invalidate the cache, so iterations 2..N read the cached first profile while printing later profile names — multi-profile benchmark results would have been silently misattributed.

## Fix shape

- `Main.invalidateProfileCaches()` clears `_profile` and `_vaultData`.
- `CliServicesImpl.withProfile` calls it both when entering the inner profile and when restoring the saved one.
- `BenchmarkCommand` calls it after every `main.provider` mutation (3 sites).

## Resolution

Resolved by commit `407c664` (`fix(P1): invalidate profile cache when BenchmarkCommand switches profiles`). The commit subject did not reference a UD ticket at the time, so this ID is being allocated retroactively for archive integrity.

## Drift note (2026-05-13)

Prior to this ticket being filed, the source code and `docs/EXTENSIONS.md` annotated the profile-cache work as "UD-211". UD-211 in BACKLOG.md was already allocated to the LocalWatcher debounce ticket (opened 2026-05-04). The collision was caught during the watcher implementation's docs-sweep. Resolution: this ticket (UD-213) takes the profile-cache concern; UD-211 stays with the watcher.

## Cross-refs

- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:91-103` — `invalidateProfileCaches()`
- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ext/internal/CliServicesImpl.kt:24-92` — `withProfile` cache-bust
- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/BenchmarkCommand.kt` — three mutation sites
- `docs/EXTENSIONS.md` "Known limitations" / multi-profile note

---
id: UD-211
title: Watcher: debounce + atomic-edit detection for vim/JetBrains-style rename-on-save
category: core
priority: medium
effort: M
status: closed
closed: 2026-05-13
resolved_by: commit eb8f8a4. Uniform trailing-edge debounce coalescer; default 2000 ms, UNIDRIVE_WATCHER_DEBOUNCE_MS overrides. New LocalWatcherDebounceTest pins six invariants including vim atomic-save coalescing. LocalWatcherTest updated to use 50 ms debounce.
opened: 2026-05-04
---
**Source:** drive-desktop research (agent `a876235`, 2026-05-04). drive-desktop's watcher debounces events at 2s and post-event-verifies existence to handle Windows' atomic-edit pattern (delete + create with different `internalId`). The same problem exists on Linux: editors like vim, Emacs, JetBrains IDEs save by writing a temp file and then `rename(2)`-ing over the original — inotify emits `IN_MOVED_FROM` + `IN_MOVED_TO` (or `IN_DELETE` + `IN_CREATE` depending on the editor's strategy), not a single `IN_MODIFY`.

Without debounce + atomic-edit detection, unidrive's local watcher can:
- Emit a DELETE upload action for the original file (cloud loses the file).
- Followed by a CREATE upload action for the new file (cloud gets it back, but with a new UUID and lost version history).

Net effect: every `:w` in vim creates a "delete + create" cycle in the cloud, churning version history and (on providers without true atomic semantics) creating a window where the file is missing.

## Code refs

- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/watcher/` — wherever the inotify integration lives
- Look for existing `WatchService` or `java.nio.file.WatchService` usage; may be using the JDK polling watcher fallback on some platforms
- Cross-platform abstraction: macOS uses `FSEvents`, Linux uses `inotify`, Windows uses `ReadDirectoryChangesW` — same logical surface

## Proposed shape

Two-phase coalescer:

```kotlin
class AtomicEditCoalescer(
    private val window: Duration = Duration.ofSeconds(2),
) {
    private val pendingByPath = ConcurrentHashMap<Path, MutableList<WatchEvent>>()

    fun onEvent(event: WatchEvent) {
        val path = canonicalize(event.path)
        val list = pendingByPath.computeIfAbsent(path) { mutableListOf() }
        synchronized(list) { list += event }
        scheduleFlush(path, window)
    }

    private fun scheduleFlush(path: Path, after: Duration) { /* coroutine delay */ }

    private fun flush(path: Path) {
        val events = pendingByPath.remove(path) ?: return
        // Heuristics:
        // 1. (DELETE, CREATE) within window + path exists → MODIFY
        // 2. (CREATE) only + path didn't exist before → CREATE
        // 3. (DELETE) only + path doesn't exist → DELETE
        // 4. (MOVED_FROM tmpA, MOVED_TO origPath) → MODIFY of origPath
        // 5. Multiple MODIFY → single MODIFY
        emit(coalesced)
    }
}
```

Post-flush existence verification: before emitting DELETE, `Files.exists(path)` — if it does exist (race re-create or atomic rename completed), emit MODIFY instead.

## Acceptance

- Integration test: simulated vim atomic save (write `.foo.swp`, rename to `foo`) emits **one MODIFY** event for `foo`, not DELETE + CREATE.
- Integration test: real `:w` in vim against a watched file (test harness can drive vim via `expect` or simply reproduce the syscall sequence) — one MODIFY.
- Real DELETE (no rename, no recreate) emits one DELETE.
- Real CREATE of a fresh file emits one CREATE.
- Rapid 5-event burst (multiple writes within 2s) coalesces to one MODIFY.
- Window is configurable via env (`UNIDRIVE_WATCHER_DEBOUNCE_MS`); default 2000ms.
- Test on at least Linux + macOS; Windows path covered by inheritance from the abstraction.

## Notes

- 2s debounce is conservative. drive-desktop uses 2000ms; faster would be desktop-feel but riskier on slow editors. Make it configurable.
- This ticket touches the watcher layer, **not** the sync engine. Reconciler/Executor are agnostic — they receive coalesced events.
- Pairs with UD-210 (re-stat-during-upload). UD-210 catches mutations *during* upload; this ticket catches editor-save patterns *before* they become bogus actions. Different defences, both needed.
- Out of scope: debounce-based event compression for very high-volume directories (e.g. logs that rotate every second). Default behaviour: emit each MODIFY past the debounce; high-volume tuning is a separate ticket.

## Cross-refs

- drive-desktop `src/node-win/watcher/on-event.ts` — direct shape source.
- B3 — sibling defence (mid-upload mutation).

---
id: UD-818
title: Replace 5x duplicate ProviderFactory required-fields tests with parameterised TestFactory driven by ProviderRegistry + credentialPrompts() (post UD-006/spi-contract)
category: tests
priority: medium
effort: S
status: closed
closed: 2026-05-13
resolved_by: commit eade837. Parametric replacement in :app:cli driven by ProviderRegistry.all() + credentialPrompts(); contract strengthened to assert providerId + message.contains(key). s3/sftp/webdav per-module duplicates deleted; localfs/rclone untouched (no credentialPrompts SPI yet).
code_refs:
  - core/providers/s3/src/test/kotlin/org/krost/unidrive/s3/S3ProviderFactoryTest.kt
  - core/providers/sftp/src/test/kotlin/org/krost/unidrive/sftp/SftpProviderFactoryTest.kt
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavProviderFactoryTest.kt
  - core/providers/localfs/src/test/kotlin/org/krost/unidrive/localfs/LocalFsProviderFactoryTest.kt
  - core/providers/rclone/src/test/kotlin/org/krost/unidrive/rclone/RcloneProviderFactoryTest.kt
opened: 2026-05-02
---
## Problem

5 provider-factory test files follow the same shape:

| File | Pattern |
|---|---|
| `S3ProviderFactoryTest.kt` | `fullProps()` helper → for each required field: test it missing → test it blank → assert `ConfigurationException` |
| `SftpProviderFactoryTest.kt` | same |
| `WebDavProviderFactoryTest.kt` | same |
| `LocalFsProviderFactoryTest.kt` | same |
| `RcloneProviderFactoryTest.kt` | same |

Each test class is ~5-15 lines of unique-per-provider data wrapped in identical assertion scaffolding.

## Proposed action

Two options:

**A) Parametric base class.** A `ProviderFactoryRequiredFieldsTestBase<F : ProviderFactory>` in `:app:core/src/testFixtures/` that takes `(factory, requiredKeys, fullProps)` and drives the same tests. Each provider's test class extends it with a 5-line constructor.

**B) JUnit 5 `@TestFactory` style.** A single test in `:app:core/src/test/` that iterates over `ProviderRegistry.all()`, queries each factory's `credentialPrompts()` (now the SPI capability — see UD-006 / refactor-provider-spi-contract), filters to required prompts, and runs the missing-field assertions.

Recommend **B** (TestFactory pattern) — it leverages the new SPI capability `credentialPrompts()` introduced in this session's refactor, so the test stays current as new providers are added without requiring a new test-class subclass per provider. Adding a 9th provider gets free coverage.

## Acceptance criteria

- [ ] Decision A vs B documented.
- [ ] Per-provider factory test classes shrink dramatically OR are deleted.
- [ ] Coverage of "required field missing/blank → ConfigurationException" still in place for all providers (verify by running coverage report).
- [ ] No new provider can be added without automatically getting required-field coverage.

## Why tests-range

It's a test-architecture refactor.

## Out of scope

Other test patterns (the wizard tests, integration tests, capability-matrix tests). This is just the missing-field assertion family.

---
id: UD-803
title: GroundTruthRunner: cleanup deletes JSONL report before user can read it
category: tests
priority: low
effort: S
status: closed
closed: 2026-05-13
resolved_by: commit a73ac16. Report now written to <localBase>-reports/report.jsonl sibling; cleanup walk untouched. Adds GroundTruthCleanupTest regression guard.
code_refs:
  - core/app/e2e-360/src/main/kotlin/org/krost/unidrive/e2e/scenarios/GroundTruthRunner.kt:304
opened: 2026-05-13
---
Found by Codex review on PR #12 (intake of e2e-360).

With the default cleanup_local_after_run = true, GroundTruthRunner
writes the JSONL report to localBase/report.jsonl just before the
cleanup walks the same directory and deletes every child except
localBase itself. Successful groundtruth runs therefore remove their
own JSONL report, leaving only the console summary and no artifact
for later inspection.

Fix options:
- Exclude report.jsonl from the cleanup walk (filter).
- Move report.jsonl to a sibling 'reports/' directory that the
  cleanup doesn't touch.
- Write the report after cleanup completes.

Acceptance: a successful groundtruth run with the default cleanup
setting leaves report.jsonl intact at a documented, predictable path.

This is a pre-existing bug in the source migrated from unidrive-closed
(PR #12). Filed separately per the dissolution's strict-scope decision.

---
id: UD-802
title: GroundTruthRunner: bare provider types skip config.toml, hit fatal config-missing
category: tests
priority: medium
effort: S
status: closed
closed: 2026-05-13
resolved_by: commit be020a9. Helper buildBareProviderConfig synthesizes [providers.<type>] from env vars (s3/sftp/webdav/rclone) or type+sync_root only (onedrive/hidrive/internxt). Missing env vars now fail fast in Phase 1 with the var name in the message. 10-case test exercises emission, optional fields, env-missing, OAuth-only, and backslash quoting.
code_refs:
  - core/app/e2e-360/src/main/kotlin/org/krost/unidrive/e2e/scenarios/GroundTruthRunner.kt:78
opened: 2026-05-13
---
Found by Codex review on PR #12 (intake of e2e-360).

When ctx.provider is a bare type (s3 / sftp / webdav / onedrive / etc.),
GroundTruthRunner.kt:76-78 sets configContent to '' and skips writing
config.toml. The child unidrive invocation then calls
Main.resolveCurrentProfile(), which treats a missing config / empty
providers as a fatal 'config missing' error before any env-based
provider setup can run.

As a result groundtruth -p s3/sftp/webdav/... cannot reach the sync
phase even when credentials are available in the environment.

Acceptance: groundtruth with a bare provider type writes a minimal
config.toml (or the runner uses some other mechanism that bypasses
the config-missing exit) and reaches the sync phase. Either way, add
one passing test that exercises the bare-provider path.

This is a pre-existing bug in the source migrated from unidrive-closed.
The intake (PR #12) is in-scope for the move only; this fix is filed
separately per the dissolution spec's strict-scope decision (Decision 1).

---
id: UD-807
title: Re-enable UD-205 folderContents dedup test with virtual time
category: tests
priority: low
effort: S
status: closed
closed: 2026-05-13
resolved_by: commit 5d9f49c. Rewritten to use runTest + testScheduler.advanceUntilIdle. 5/5 stable; runs in 20 ms vs 500+ ms flaky budget. Added assertion before gate open catches dedup-gate-leak regressions earlier.
code_refs:
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:350
opened: 2026-05-13
---
Disabled in PR #12 because of timing flakes on Windows CI runners.

The test spawns 20 Dispatchers.Default coroutines and spin-waits with
delay(10) for up to 500ms. That budget is too tight on slow GitHub
Actions Windows VMs and the dedup invariant (only one loader callback
invocation across 20 concurrent calls) intermittently fails to be
observed.

Fix: rewrite using kotlinx-coroutines-test runTest +
TestCoroutineScheduler so the dedup wiring is exercised in virtual
time, not wall-clock. The production InFlightDedup primitive (UD-205)
is unchanged; this is a test-only refactor.

Acceptance:
- Test re-enabled (drop @Ignore on InternxtApiServiceTest:350).
- Runs deterministically on both Ubuntu and Windows CI.
- Still validates the invariant: one loader invocation across N
  concurrent load() calls for the same key.

---
id: UD-768
title: scripts/dev/log-watch.sh defaults to Windows AppData path on Linux MVP
category: tooling
priority: low
effort: XS
status: closed
closed: 2026-05-14
resolved_by: Duplicate of UD-700 (same bug: log-watch.sh defaults to Windows path). UD-700 has the correct Linux target — `${XDG_DATA_HOME:-$HOME/.local/share}/unidrive/unidrive.log` — verified against `core/app/cli/src/main/resources/logback.xml:3` which writes to `${LOCALAPPDATA:-${user.home}/.local/share}/unidrive`. UD-768 suggested `/.local/state` which would not match the daemon. Closed as wontfix-duplicate; UD-700 carries the fix.
code_refs:
  - scripts/dev/log-watch.sh:22
opened: 2026-05-02
---
## Problem

`scripts/dev/log-watch.sh:22` defaults the live-log path to a
**Windows AppData path** on a project whose MVP is Linux:

```
LOG="${UNIDRIVE_LOG:-$HOME/AppData/Local/unidrive/unidrive.log}"
```

On Linux (the MVP per ADR-0012), `$HOME/AppData/Local/...` does
not exist; the daemon writes to either `$XDG_STATE_HOME/unidrive/`
or `$HOME/.local/state/unidrive/` depending on env. The script
therefore prints "no such file" until the user remembers to set
`UNIDRIVE_LOG`.

This default is also the seed of broader doc drift: any reader
who looks at `log-watch.sh` to understand "where does unidrive
log on this platform?" gets a misleading answer.

## Proposed action

Two acceptable paths:

**A) Pick the right Linux default** and document the env-var
override:

```bash
LOG="${UNIDRIVE_LOG:-${XDG_STATE_HOME:-$HOME/.local/state}/unidrive/unidrive.log}"
```

This matches the daemon's actual write path (verify against
the daemon's logging config before committing).

**B) Refuse to default at all** if `UNIDRIVE_LOG` is unset on
Linux, exit with a one-line error pointing at the env var. More
explicit, less convenience.

Recommend **A**: matches the daemon, removes the cliff for new
contributors trying out the script.

## Acceptance criteria

- [ ] `log-watch.sh` default resolves to a path the daemon
      actually writes to on Linux.
- [ ] If macOS / Windows fallbacks are kept, they're behind an
      `os.name` check at the top of the script with a comment
      explaining ADR-0012.
- [ ] Smoke: `UNIDRIVE_LOG=/tmp/test.log scripts/dev/log-watch.sh
      --summary` works on Linux without further env setup.

## Why a separate ticket from UD-400

UD-400 sweeps `os.name` branches in **non-test Kotlin code**.
This is a shell script. Same spirit, different scope. Atomic
commit lets `git log` show the rationale without dragging the
Kotlin sweep along.

---
id: UD-700
title: log-watch.sh hardcodes Windows log path; fails on Linux/macOS by default
category: tooling
priority: low
effort: XS
status: closed
closed: 2026-05-14
resolved_by: commit 12946af. Replaces hardcoded Windows path with default_log_path() branching on uname -s; matches the daemon's logback.xml resolution ($XDG_DATA_HOME/.local/share on Linux, %LOCALAPPDATA% on Windows, ~/Library/Logs/ on macOS). Adds a one-line hint when the default doesn't exist and UNIDRIVE_LOG is unset. Smoke-tested on Linux: finds 8 rolled logs without env override. UD-768 was a duplicate and is already in CLOSED.md (wontfix-duplicate).
opened: 2026-05-04
---
`scripts/dev/log-watch.sh` hardcodes a Windows-only default log path and fails immediately on Linux/macOS.

## Repro (2026-05-04 session)

```
$ bash scripts/dev/log-watch.sh --summary
log not found: /home/gernot/AppData/Local/unidrive/unidrive.log
```

The actual log on Linux lives at `~/.local/share/unidrive/unidrive.log` (XDG `$XDG_DATA_HOME`).

## Root cause

`scripts/dev/log-watch.sh:22`:

```bash
LOG="${UNIDRIVE_LOG:-$HOME/AppData/Local/unidrive/unidrive.log}"
```

The default path is Windows-only. The escape hatch (`UNIDRIVE_LOG=… bash log-watch.sh …`) works but every Linux/macOS contributor — i.e. all of them — has to discover this and set it manually every time.

## Proposed fix

Detect platform and pick the right XDG-style path. The skill `unidrive-log-anomalies` invokes this script unconditionally at session start; right now it silently fails on Linux every session.

```bash
default_log_path() {
  case "$(uname -s)" in
    Darwin)         echo "$HOME/Library/Logs/unidrive/unidrive.log" ;;
    Linux)          echo "${XDG_DATA_HOME:-$HOME/.local/share}/unidrive/unidrive.log" ;;
    MINGW*|MSYS*|CYGWIN*) echo "$HOME/AppData/Local/unidrive/unidrive.log" ;;
    *)              echo "${XDG_DATA_HOME:-$HOME/.local/share}/unidrive/unidrive.log" ;;
  esac
}
LOG="${UNIDRIVE_LOG:-$(default_log_path)}"
```

Verify the macOS path against whatever the daemon actually writes (likely `~/Library/Logs/unidrive/`, but could be `~/Library/Application Support/unidrive/` if the logger config says so — check `core/app/cli/src/main/resources/logback.xml` or whichever logger config the JVM uses, and align).

## Acceptance

- `bash scripts/dev/log-watch.sh --summary` works out of the box on Linux without setting `UNIDRIVE_LOG`.
- macOS path is verified against the daemon's actual writer config.
- Windows path remains the default for `MINGW*`/`MSYS*`/`CYGWIN*`.
- `unidrive-log-anomalies` skill produces a real summary at session start on Linux instead of "log not found".
- Bonus: if no log file exists at the resolved default *and* `UNIDRIVE_LOG` is unset, print a one-line hint pointing to where the daemon would write it on this OS, instead of just `log not found:`.

## Notes

- Pure scripting fix, no Kotlin changes.
- Could also affect the JFR scripts (`scripts/dev/unidrive-jfr.sh` / `.ps1`) — worth a quick grep for sibling paths while in there.

---
id: UD-203
title: Capture x-request-id (and provider equivalents) in provider exception types for log correlation
category: core
priority: medium
effort: S
status: closed
closed: 2026-05-14
resolved_by: commit 80b3cf3. Implemented across 4 commits (53ff21e OneDrive base, b5a52e9 Internxt, 894dc06 S3, 80b3cf3 SyncEngine log surface + per-provider robustness docs). ProviderException base gained requestId; OneDrive reads request-id + client-request-id, Internxt reads x-request-id, S3 reads x-amz-request-id and x-amz-id-2. SyncEngine appends requestIdSuffix(e) to auth-fatal ERROR and per-action WARN lines. Tests: GraphRequestIdPropagationTest (4), InternxtRequestIdPropagationTest (3), S3RequestIdPropagationTest (5), RequestIdSuffixTest (5). HiDrive out of scope — no provider module in repo.
opened: 2026-05-04
---
**Source:** internxt/sdk research (agent `a4990ef`, 2026-05-04). The SDK's single best observability lever is `AxiosResponseError.xRequestId` — extracted from the `x-request-id` response header at error normalization time. unidrive's provider exception types should carry the same field so `unidrive.log` ERROR lines correlate with server-side support tickets.

## Code refs

- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiException.kt` (or wherever the type lives) — add `xRequestId: String?`
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt` — `parseError`-style helper, populate from response headers
- `core/providers/onedrive/...` — same pattern (already partially captures `request-id`?)
- `core/providers/hidrive/...`, `core/providers/s3/...` — extend pattern
- BACKLOG.md line 1100s — the existing error-parsing audit ticket already enumerates per-provider error fields; this extends it to capture the request-id specifically

## Header-name matrix per provider

| Provider | Response header | Notes |
|----------|-----------------|-------|
| Internxt | `x-request-id` | confirmed by SDK research |
| OneDrive | `request-id` and/or `client-request-id` | Microsoft Graph standard |
| S3 | `x-amz-request-id` + `x-amz-id-2` | both useful for AWS support |
| HiDrive | TBD — verify | |
| WebDAV | none standard — skip | |
| SFTP | n/a (not HTTP) | |

## Acceptance

- Provider-specific exception types each gain a `requestId: String?` (or provider-specific name) field.
- Error-construction sites populate it from response headers when available; null otherwise.
- Logging interceptor surfaces `requestId=<value>` on every ERROR log line that originated from a provider exception.
- Unit tests: forced 500 from each provider's mock, with synthetic header → exception carries the value, log line contains it.
- `docs/providers/<provider>-robustness.md` documents the header name being captured.

## Notes

- This is the cheapest observability win in the research reports. ~30 LOC per provider plus the logging interceptor.
- Pairs with UD-330 — when retries fail and the budget gives up, the final ERROR log should include the last-attempt's `requestId`.
- Out of scope: client-side request-id generation (we'd send our own correlator). Useful but separate ticket.

## Cross-refs

- internxt/sdk research — `Top 5 ideas to steal` #3.
- BACKLOG ticket near line 1095-1130 (existing error-parsing audit) — this is the captured-fields extension for the request-id specifically.

---
id: UD-810
title: Test cleanup: :app:core misleading + data-class delete-candidates (UD-813 batch A)
category: tests
priority: low
effort: S
status: closed
closed: 2026-05-14
resolved_by: commit 246769d. Two misleading tests renamed to match their assertions (allByTier applies the canonical tier ordering; every discovered metadata has non-blank id and displayName); nine data-class-contract tests deleted (ProviderMetadataTest: 5; CloudItemTest: 4). CHANGELOG [Unreleased] / Removed records the deletions; TEST-AUDIT.md table flipped to 'Resolved by 246769d' for the four affected rows. Business-invariant defaults tests retained.
opened: 2026-05-13
---
## Source

UD-813 audit of `:app:core` tests (2026-05-13). See
[`docs/dev/TEST-AUDIT.md`](../../dev/TEST-AUDIT.md) for the full table.

This is the lower-risk batch — misleading test names and data-class
delete-candidates. Rewrites are mechanical: rename to match the body, or
delete with a one-line CHANGELOG note. No behaviour changes in production
code.

## Scope

`core/app/core/src/test/`:

- **`ProviderMetadataTest.allByTier returns sorted metadata`** — name promises
  sort verification; body iterates without checking sort order. Either
  rewrite to assert the canonical tier order
  (`["Local", "DE-hosted", "EU-hosted", "Self-hosted", "Global"]`) or rename
  to "every entry has non-blank tier" if sort is intentionally not pinned.
- **`ProviderMetadataTest.allMetadata returns list`** — body's `for (meta in all)`
  runs 0 iterations on empty list, comment "may be empty" excuses the
  vacuous pass. Either move the structural assertion to the `:app:cli`
  classpath-rich variant (see new `ProviderRegistryDiscoveryTest`) and
  delete here, or keep as a "per-entry structural invariant when present"
  test with the explicit name.
- **`ProviderMetadataTest.ProviderMetadata stores all required fields` /
  `optional fields` / `data class equality and copy`** — assert Kotlin
  data-class generation. Tests the compiler. Delete with a CHANGELOG note.
- **`CloudItemTest.equal items have equal hashCodes` /
  `items differing in any field are not equal` / `hashCode is stable across calls` /
  `works correctly as HashMap key`** — same delete-candidate class.

## Acceptance

- Rewritten or deleted per the table above.
- CHANGELOG `[Unreleased] / Removed` entry for each deleted test, naming
  the file and the reason ("delete-candidate per UD-813 audit").
- `TEST-AUDIT.md` table updated: each row's "Action" cell flips to the
  commit that resolved it.

---
id: UD-811
title: Test cleanup: :app:core retry-budget + dedup + request-id (UD-813 batch B)
category: tests
priority: medium
effort: M
status: closed
closed: 2026-05-14
resolved_by: commit 6556992. Two HttpRetryBudgetMatrixTest names corrected to match their assertions (storm-threshold + largest-Retry-After gating; isRetriableIoException classifier). Two RequestIdPluginTest cases strengthened (dropped 8-char impl-anchor; replaced unreachable builder-side capture with the engine-side header assertion that actually pins the contract). InFlightDedupTest portion landed earlier via 9a55b6c (PR #15 rider). The remaining @Ignore'd HttpRetryBudgetMatrixTest rows require a real HttpRetryBudget API surface and stay deferred to UD-207.
opened: 2026-05-13
---
## Source

UD-813 audit of `:app:core` tests (2026-05-13). See
[`docs/dev/TEST-AUDIT.md`](../../dev/TEST-AUDIT.md) for the full table.

This is the higher-touch batch — misleading retry-budget assertions and
the dedup tests that share UD-807's `Dispatchers.Default + delay(10)
spin-wait` flake class.

## Scope

`core/app/core/src/test/`:

- **`HttpRetryBudgetMatrixTest.429 honors Retry-After capped by maxRetryAfter`** —
  rename to reflect what the body actually checks: the storm-trigger plus
  max-Retry-After honored in `resumeAfterEpochMs`. The "capped by
  `maxRetryAfter`" feature does not exist in `HttpRetryBudget` (only in the
  KDoc matrix comment). Either remove the cap claim from the docstring
  matrix or implement the cap and add a test asserting it (separate
  decision; the current test is just misnamed).
- **`HttpRetryBudgetMatrixTest.network IOException retries with exponential backoff`** —
  rename to `isRetriableIoException distinguishes transient vs misconfig IO failures`.
  Body is a classifier-only contract; no backoff assertion is present.
- **`InFlightDedupTest.concurrent callers for same key share exactly one loader invocation`**
  and the sibling **`loader failure is rethrown ...`** test — rewrite to
  use `runTest` + `testScheduler.advanceUntilIdle()` per the UD-807 pattern
  (see `core/providers/internxt/src/test/kotlin/.../InternxtApiServiceTest.kt`
  for the template). The 100-coroutine `Dispatchers.Default` flavour is
  the same flake class as the now-fixed Internxt test; on slow Windows CI
  the spin-wait budget is too tight for all callers to install themselves
  before the gate releases.
- **`RequestIdPluginTest.every request carries X-Unidrive-Request-Id header`** —
  the `assertEquals(8, id.length)` pin reflects the current id format. If
  the format changes (e.g. to 12 chars or UUID), the test breaks without
  the invariant being violated. Replace with "non-blank + unique across N
  requests" only, leaving the length as an implementation detail.
- **`RequestIdPluginTest.request attribute exposes the id for caller introspection`** —
  drop the `if (capturedFromBuilder != null)` guard around the
  `assertEquals`. Today a regression that returns null silently passes;
  the test should fail loud.

## Acceptance

- Each test rewritten per the table above.
- `InFlightDedupTest` runs under `runTest` and finishes in < 50 ms instead
  of relying on wall-clock spin-wait budgets.
- `TEST-AUDIT.md` table updated with the resolving commit per row.

## Risk

Higher than UD-810 because the dedup rewrite touches multi-coroutine
ordering. Reference: UD-807's Internxt fix (commit 5d9f49c) — the same
pattern translates 1:1.

---
id: UD-214
title: Fix flaky IpcProgressReporterTest IPC race (re-enable @Ignore'd tests)
category: core
priority: medium
effort: M
status: closed
closed: 2026-05-14
resolved_by: commit a695944. Exposed IpcServer.clientCount as a synchronization seam; replaced delay()/yield() gates in IpcProgressReporterTest with awaitClientCount(server, 1). Both previously-@Ignore'd tests (onSyncComplete emits sync_complete event; emitSyncError sanitizes newlines) are now active and green. 10/10 stable on local Linux; CI on ubuntu+windows will exercise the 20-run criterion.
opened: 2026-05-14
---
## Problem

`IpcProgressReporterTest` uses `runTest { ... }` (virtual-time test dispatcher),
but `IpcServer.start()` launches its accept-loop and broadcast-loop on
`Dispatchers.IO` (real threads). The `delay(100)` calls in the tests advance
virtual time without forcing the real-thread accept/broadcast loops to make
progress, so on slower runners the client write can arrive before the broadcast
loop has drained the channel, and `readLines` times out without ever seeing the
line.

This is the same race class tracked as flaky in IpcProgressReporterTest itself
(internal note "#108"). Two tests are currently `@Ignore`d for this reason:

- `onSyncComplete emits sync_complete event`
- `emitSyncError sanitizes newlines` (disabled in this branch — was failing on
  ubuntu-latest CI run 25847766935 / job 75946814314)

The production sanitization invariant (`emitSyncError` collapses `\n` → space and
truncates at 500 chars) is still covered indirectly by the non-IPC
`all methods update server syncState` test, but we no longer have end-to-end
coverage that the sanitized payload actually reaches a client over the socket.

## Resolution sketch

Pick one (or hybrid):

1. **Plumb a real dispatcher into the server during tests.** Inject the
   coroutine dispatcher used by accept/broadcast loops; in tests, pass the
   `TestScope`'s dispatcher so `delay` and channel sends are deterministic.
2. **Replace `delay()` polling with real-time waits in tests.** After connect,
   poll `server.clientCount` (need to expose) until non-empty before emitting;
   after emit, wait on a `CompletableDeferred` that the broadcast loop completes
   on first successful write.
3. **Migrate `readLines` to a blocking-with-timeout NIO selector loop** so the
   test makes no virtual-time assumptions about real I/O scheduling.

## Acceptance

- Both currently-`@Ignore`d IPC tests are re-enabled.
- Both pass deterministically on Linux + macOS + Windows runners across at least
  20 consecutive CI runs.
- No `Thread.sleep` / `delay(N)` "hope it's enough" patterns in the test setup.
---
id: UD-766
title: Wire backlog-sync.kts into CI (build.yml)
category: tooling
priority: high
effort: XS
status: closed
closed: 2026-05-14
resolved_by: commit 270dcb4. New 'backlog' job in .github/workflows/build.yml runs the canonical orphan/stale/anchorless/abandoned checker on every push to main + every PR. Installs kotlinc 2.3.21 from JetBrains' GitHub release (~80 MB), invokes the existing scripts/ci/backlog-sync.sh wrapper. Catches 6 drift classes that previously surfaced only during PR review.
code_refs:
  - scripts/backlog-sync.kts
  - scripts/ci/backlog-sync.sh
  - .github/workflows/build.yml
opened: 2026-05-01
---
**Wire `scripts/backlog-sync.kts` into `.github/workflows/build.yml` as a CI step. Best leverage-to-effort ratio in the tree.**

The script and shell wrapper both already exist (`scripts/backlog-sync.kts`, 179 lines; `scripts/ci/backlog-sync.sh`, 6 lines), but no CI job invokes them — drift goes uncaught until someone runs `kotlinc -script` locally, and most contributors don't.

## What's already there

- `scripts/backlog-sync.kts` — canonical orphan/stale/anchorless/abandoned checker per AGENT-SYNC.md. Exit 0 on clean (or warnings only); exit 1 on hard errors (orphan code refs, stale closed items).
- `scripts/ci/backlog-sync.sh` — already exists as the CI wrapper. Three lines: `cd` to repo root, `exec kotlinc -script scripts/backlog-sync.kts`. Ready to call.
- `docs/AGENT-SYNC.md` — already documents that this script is the contract. The contract just isn't enforced.

## What gets caught the moment we wire it

In one shot:
- **Orphan code refs** — `// UD-xyz` in source with no matching block in `BACKLOG.md`/`CLOSED.md`.
- **Stale closed** — IDs in `CLOSED.md` still referenced in source.
- **Non-canonical statuses** — frontmatter `status:` outside `open|in-progress|blocked|closed`.
- **Anchorless open** (warning) — `code_refs:` pointing at non-existent files.
- **Abandoned** (warning) — `status: open`, no `code_refs`, opened > 30 d ago.
- **Source-vs-CLOSED drift** — entries that disagree between BACKLOG and CLOSED.

These are the failure modes that today only surface during PR review or session handover. Wiring catches them at push time.

## Acceptance

- New job `backlog` in `.github/workflows/build.yml` running on `ubuntu-latest` only (kotlinc-only — no JDK build needed):
  - Checkout
  - Set up JDK 21 (kotlinc needs it)
  - Install kotlinc (`curl` from GitHub releases, or use a marketplace action — pick whichever is cheaper)
  - `bash scripts/ci/backlog-sync.sh`
- Job runs on push to `main` and on PRs (same triggers as `core`).
- Job is **fast** — script reads docs + greps `core/` for `UD-###` patterns, no Gradle invocation. Should be < 30 s including kotlinc warmup.
- `concurrency:` group shared with the existing `core` group so PRs don't queue duplicates.
- Failure surfaces in the PR check list with a useful summary (the script's stderr is already shaped for humans).

## Out of scope

- Re-using the existing Gradle daemon — kotlinc is fine standalone for a 179-line script.
- Strict mode for warnings — keep "anchorless open" + "abandoned" as warnings only (script default). If we want to escalate later, that's a separate ticket.
- Running this in a pre-commit hook — separate ticket, related to UD-762's `check-docs.sh` salvage.

## Provenance

Discussed 2026-05-01 with maintainer. Highest leverage/effort ratio in the tree because: (a) script + wrapper already exist, (b) checks every PR + every push to `main`, (c) catches 6 distinct drift classes simultaneously.

---
id: UD-003
title: ADR-0014 consolidating ADR-0008/0011/0012/0013 — v0.1.0 surface
category: architecture
priority: high
effort: XS
status: closed
closed: 2026-05-14
resolved_by: commit b918c2a. ADR-0014 lands as a consolidator (not rewrite). Names the v0.1.0 shipping surface so a reader has a single citable answer instead of composing ADR-0008 + ADR-0011 + ADR-0012 + ADR-0013 mentally.
code_refs:
  - docs/adr/
opened: 2026-05-01
---
**Write ADR-0014 consolidating the v0.1.0 surface as it stands after ADR-0008 + 0011 + 0012 + 0013.**

A new contributor reading the ADR set today must mentally compose four documents to answer "what's actually shipping?":

- ADR-0008 (greenfield restart): "v0.1.0 = core-only, ui/ + shell-win/ at preview"
- ADR-0011 (remove shell-win): "actually, shell-win/ is gone"
- ADR-0012 (Linux-only MVP + protocol/ removal): "actually, protocol/ + named pipes are gone too"
- ADR-0013 (remove ui/): "actually, ui/ is also gone"

ADR-0008's stated trade-offs ("no tray, no Explorer integration") are now the actual shipped state, not a temporary acceptance. Each amendment ADR explicitly cross-references the others (see the `amends` / `amended_by` frontmatter chain). The chain is faithfully recorded but not summarised.

## Why a consolidator is the right shape (not a rewrite)

ADR-0008..0013 are **historical** — they record decisions and the context at the time. ADR rule of thumb: never rewrite history; instead supersede with a new ADR that captures the *current* decision surface. ADR-0014 is that consolidation.

It does **not** invalidate the four it consolidates. They stay accepted; ADR-0014 cites them as `consolidates: ADR-0008, ADR-0011, ADR-0012, ADR-0013` and is the **single answer** to "what's shipping in v0.1.0?"

## Acceptance

`docs/adr/0014-v0_1_0-surface.md`, ~half a page, frontmatter:

```yaml

---
id: UD-767
title: Add docs/ROADMAP.md + docs/NON-GOALS.md (half page each)
category: tooling
priority: high
effort: XS
status: closed
closed: 2026-05-14
resolved_by: commit d7d9c2c. docs/ROADMAP.md (milestone-oriented v0.1.0 / v0.2.0 / v0.3.0 / beyond) + docs/NON-GOALS.md (explicit list with why-not + how-to-move-back-into-scope). Linked from README.md Status section.
code_refs:
  - docs/ROADMAP.md
  - docs/NON-GOALS.md
  - README.md
opened: 2026-05-01
---
**Add `docs/ROADMAP.md` and `docs/NON-GOALS.md` — half a page each. Raises the project from "preview with strong opinions" to "preview with discoverable strategy."**

The current public has rigorous tactical docs (`SPECS.md`, `ARCHITECTURE.md`, `AGENT-SYNC.md`, `BACKLOG.md`, 13 ADRs, lessons-learned files) but no single document that answers a contributor's first two questions:

1. **"Where is this going?"** — answered today only by reading 13 ADRs + the BACKLOG.md (90+ tickets) + the wiki. That's a 30-minute reading task, and even after it the answer is implicit.
2. **"What is this *not* trying to be?"** — answered today only by ADR-0011/0012/0013 (each phrased as "we removed X"), which is hard to discover proactively.

Both gaps are common in open-source previews. Closing them is a half-day of writing that pays off every time someone asks "does it support Y?", "will it ever do Z?", "should I file this as a feature request?"

## What goes where

### `docs/ROADMAP.md` (~half a page)

**Audience:** prospective contributor, prospective user, prospective sponsor.

**Shape:** time-anchored milestones, not a feature list. Each milestone is one paragraph.

```markdown
# Roadmap

## v0.1.0 — first release (Linux MVP)
Quality-gated providers: localfs, s3, sftp. CLI + MCP + sync engine.
Linux-only. Outstanding gates: <link to BACKLOG.md milestone:v0.1.0>.

## v0.2.0 — preview providers graduate
OneDrive, WebDAV, HiDrive, Internxt, Rclone leave preview status. Each
needs: live-integration test in CI, capability-contract round-trip
(ADR-0005), parallelism budget tuned in `ProviderMetadata`. Likely Q3.

## v0.3.0 — release artefacts
Standalone installer (`dist/install.sh`, UD-761), GitHub Releases with
fat JAR, Scoop bucket / WinGet manifest if community appetite exists.
Webhook-driven sync exits experimental status (UD-???).

## Beyond v0.3.0 — not committed
- Shell-extension overlays (Linux: Nautilus + Dolphin; Windows: depends
  on appetite). See `BACKLOG_IDEAS_UI.md`.
- Companion projects: `unidrive-android` (in flight in adjacent repo),
  `unidrive-tray` (community).
- Provider expansion to Google Drive, Dropbox, Box (currently only via
  rclone gateway).
```

Cross-links into `BACKLOG.md`'s `milestone:v0.1.0` field. If we don't currently use the `milestone:` field consistently, the ROADMAP creation forces that audit.

### `docs/NON-GOALS.md` (~half a page)

**Audience:** anyone about to file a feature request that won't land.

**Shape:** explicit list with one-line "why not" for each. Doesn't need numbering.

```markdown
# Non-goals

unidrive-cli explicitly does NOT aim to:

- **Be a backup tool.** Sync ≠ backup. We sync deltas; we do not snapshot
  history-aware archives. Use restic/borg/duplicacy for that. (We do
  retain `unidrive backup add` for one-way replication, which is
  different from a backup tool.)
- **Run on Windows or macOS in v0.1.0.** ADR-0012 is the authority. Both
  are post-v0.3.0 candidates per `BACKLOG_IDEAS_UI.md`.
- **Ship a system-tray UI in core.** ADR-0013 moved that to companion
  projects; the daemon's UDS broadcast surface is the contract for
  third-party trays. See `BACKLOG_IDEAS_UI.md` W11/W12.
- **Implement provider-specific features that don't generalise.** The
  `CloudProvider` interface is deliberately minimal; provider quirks
  hide behind capabilities (ADR-0005). "OneDrive shared notebooks" or
  "Dropbox Paper documents" are out unless they map to a generalisable
  capability.
- **Be a sync conflict resolver.** We surface conflicts (`unidrive
  conflicts`) and offer two policies (`keep_both`, `last_writer_wins`),
  but we do not auto-merge document contents. Document-merge tooling is
  a different product.
- **Replace native cloud storage clients.** OneDrive's official client
  has features we won't replicate (cloud streams in Office, embedded
  Teams sharing, etc.). We're a **second-class citizen** for any single
  provider, but a **first-class citizen** for the multi-provider use
  case. That's the trade.
```

Each non-goal cites either an ADR, a BACKLOG_IDEAS_UI section, or a "why" sentence. The list isn't fixed — adding a non-goal as the project matures is fine, and gets a date footer (`Updated YYYY-MM-DD`).

## Why both, not just one

A roadmap without non-goals reads as "all the things, eventually, just be patient" — which is what every preview looks like and trains contributors to file maximalist requests. Non-goals constrain expectations bidirectionally: contributors know what won't land, users know what to look elsewhere for, sponsors know the scope they're actually backing.

## Acceptance

- `docs/ROADMAP.md` exists, ~half a page, links to BACKLOG.md milestone field + ADR-0014 (when filed under UD-003).
- `docs/NON-GOALS.md` exists, ~half a page, references ADR-0011/0012/0013 + ADR-0005 + `BACKLOG_IDEAS_UI.md`.
- `README.md` adds two links in the docs section pointing at both.
- Wiki Home page (built 2026-05-01) gets a "What's next" section with a one-liner pointer to ROADMAP.md.
- If `BACKLOG.md` items don't currently have `milestone:` set, populate it for the ones in v0.1.0 / v0.2.0 / v0.3.0 buckets — keeps ROADMAP.md and BACKLOG.md in sync via the milestone field.

## Out of scope

- Detailed feature specs for v0.2.0 or v0.3.0 — those ship as separate spec files in `docs/specs/` when the time comes.
- Marketing copy / pitch deck — different artefact, different audience.

## Provenance

Discussed 2026-05-01 with maintainer. Pairs with UD-003 (ADR-0014 surface consolidator) — together they make the project navigable for new contributors without reading 13 ADRs + 90 tickets.

---
id: UD-769
title: Drop windows-latest from CI core matrix (or demote to allowed-to-fail) per ADR-0011/0012
category: tooling
priority: medium
effort: XS
status: closed
closed: 2026-05-15
resolved_by: commit b8c0447. Path A: dropped windows-latest from matrix; collapsed core (matrix.os) to core (ubuntu-latest); dropped 4 if-guards; tightened README §Status.
code_refs:
  - .github/workflows/build.yml:39
opened: 2026-05-02
---
## Problem

`.github/workflows/build.yml:39`:

```yaml
strategy:
  fail-fast: false
  matrix:
    os: [ubuntu-latest, windows-latest]
```

[ADR-0011](adr/0011-shell-win-removal.md) +
[ADR-0012](adr/0012-linux-mvp-protocol-removal.md) make Linux the
MVP target. macOS and Windows are explicitly community-best-effort
(README §"Status", in slim form: "Linux-first").

Yet CI runs the full `core` job on `windows-latest` and `fail-fast:
false` means a Windows-only failure produces a red overall build
status even when Linux is green. This:

- **Lies about the support matrix.** Anyone reading the build
  badge and expanding the run sees "we test on Windows" — we
  don't, in any meaningful sense; we just smoke that the JVM
  modules compile.
- **Burns CI time** (Windows runners are 5-10× slower than
  ubuntu-latest for Gradle cold start; observed in
  run 25247884883 — Windows job ran 5+ minutes when Linux took
  3).
- **Adds noise to PRs.** Every PR sits with a half-failed
  status until the Windows run finishes, even though the
  failure is rarely a real defect.

## Proposed action

Two acceptable paths:

**A) Drop windows-latest from the matrix entirely.** Honest,
fast, matches ADR-0012. Loses the "compiles on Windows"
canary, which is real but cheap to restore later.

**B) Keep it as a separate, allowed-to-fail job** with an
`continue-on-error: true` or a `if: github.event_name ==
'schedule'` guard so PR builds don't block on Windows. Restore
the Windows runner only on the main branch / nightly schedule.

Recommend **A** for v0.1.0. Re-add via **B** if a future ADR
re-opens Windows as a tier (per ADR-0012 §"Re-opening criteria").

## Acceptance criteria

- [ ] `core` matrix does not include `windows-latest` for PR
      builds.
- [ ] If kept (Option B), windows-latest is `continue-on-error:
      true` AND only runs on `push: branches: [main]` or a
      `schedule:` trigger.
- [ ] README badge stays accurate (linux-first claim is honest).
- [ ] No regression on ubuntu-latest + gitleaks jobs.

## Why this is its own ticket

It's a one-line CI config change but with policy implications
(does the project still claim "best-effort Windows builds"?).
A discrete commit with a clear `git log` entry is worth more
than folding it into a generic "CI hygiene" sweep.

---
id: UD-770
title: SPECS.md §2.2 reports 15 MCP tools; code lists 23 (UD-216 admin verbs added; doc not updated)
category: tooling
priority: medium
effort: XS
status: closed
closed: 2026-05-15
resolved_by: commit 4d574e3. SPECS.md App-tier table row and §2.2 prose updated: 15 to 23 (15 user-facing + 8 UD-216 admin verbs). Pure doc reconciliation, no code change.
code_refs:
  - docs/SPECS.md:54
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:36-62
opened: 2026-05-02
---
## Problem

`docs/SPECS.md:54` claims the MCP server exposes 15 tools,
verified against `core/app/mcp/.../Main.kt:20-24`:

> | `app/mcp` | MCP server with 15 tools |
> [`core/app/mcp/`](../core/app/mcp) | 💻 **15 tools verified**
> (`core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:20-24`) |

The actual count in `Main.kt` (verified at filing time) is **23**:
the original 15 user-facing tools (status, sync, get, free, pin,
conflicts, ls, config, trash, versions, share, relocate,
watchEvents, quota, backup) **plus 8 admin verbs** added under
[UD-216](#ud-216): authBegin, authComplete, logout, profileList,
profileAdd, profileRemove, profileSet, identity.

`Main.kt:53` even has the comment `// UD-216: admin verbs for
end-to-end LLM-driven management.` admitting the count moved.

The SPECS.md row is also marked 💻 **15 tools verified**, which
is now a false attestation — the verification was correct at the
time it was made, but the doc didn't follow the code.

## Risk

- `SPECS.md` is the **normative intent catalog** per CLAUDE.md.
  The whole point of SPECS.md is to flag doc-vs-code drift with
  📄 / 💻 / ✅ / ⚠ labels. A wrong 💻 attestation is precisely
  the failure mode SPECS.md exists to prevent.
- LLM clients picking up unidrive's MCP server expecting "15
  tools" per the docs will be surprised by the 8 admin verbs;
  not breaking, but inelegant.

## Proposed action

1. Update `docs/SPECS.md:54` row:
   - Tool count: `15` → `23` (or whatever `Main.kt` lists at
     edit time — re-verify, do not trust this ticket).
   - Line range: `Main.kt:20-24` → the actual `listOf(...)`
     range. At filing time the `tools = listOf(...)` block
     spans `Main.kt:36-62`.
   - Verification label: stays 💻 (code-verified) once corrected.
2. Cross-check: does any other doc (README, ARCHITECTURE.md,
   docs/EXTENSIONS.md, docs/MCP* if such exists) cite "15
   tools"? Sweep and update.
3. Add a `// SPECS.md row N — keep tool count in sync` marker
   comment near the `listOf(...)` block in `Main.kt` so future
   adds prompt a SPECS.md edit. (Optional; only if the maintainer
   thinks comment-pinning beats a CI check.)

## Acceptance criteria

- [ ] `docs/SPECS.md:54` (or its current line) reports the
      actual tool count, with the actual line range.
- [ ] `grep -rn "15 tools" docs/` returns no stale claims.
- [ ] No code changes required (this is doc drift only).

## Why this is its own ticket

Tiny but high-trust: SPECS.md is the source of truth for
intent-vs-code accuracy. Drift here erodes confidence in every
other ✅ / ⚠ label across the catalog. Worth its own atomic
commit so a future reader can `git log -- docs/SPECS.md` and
see exactly when the count was reconciled.

---
id: UD-007
title: Default logout() on CloudProvider; remove 4-provider boilerplate (WebDAV, S3, Rclone, LocalFs)
category: architecture
priority: medium
effort: XS
status: closed
closed: 2026-05-15
resolved_by: commit 041d304. CloudProvider.logout() now has a default body that flips isAuthenticated. 4 boilerplate overrides (WebDAV/S3/Rclone/LocalFs) removed; OneDrive/Internxt converted to var with no-op setter; SFTP keeps its api.close() override. 8 test stubs val to var. Out of scope: ADR-0005 capability split for logout().
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt
  - core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProvider.kt
  - core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt
opened: 2026-05-02
---
## Problem

4 of 7 in-tree providers (WebDAV, S3, Rclone, LocalFs) implement `logout()` as the same boilerplate:

```kotlin
override suspend fun logout() { isAuthenticated = false }
```

The `CloudProvider` interface should provide this as the default. SFTP, OneDrive, and Internxt would override to add `api.close()` / `tokenManager.logout()` etc.

## Proposed action

1. In `CloudProvider.kt`, change `logout()` from abstract to default-implemented:

   ```kotlin
   /**
    * Forget any cached credentials / authenticated state. Default
    * implementation flips the in-memory flag; providers that own
    * external resources (token files, network connections) override
    * to release them.
    */
   suspend fun logout() {
       // Default no-op; subclasses override to release resources.
       // Note: subclasses that track an in-memory `isAuthenticated`
       // flag must reset it themselves in their override.
   }
   ```

2. Decide: should `isAuthenticated` be lifted into `CloudProvider`? If yes, the default `logout()` can flip it. If not, keep the default a no-op and let each provider's override handle its own state.

3. Delete the boilerplate `override suspend fun logout() { isAuthenticated = false }` in WebDAV, S3, Rclone, LocalFs.

4. Verify SFTP, OneDrive, Internxt overrides still do the right thing (they'll need to set `isAuthenticated = false` themselves now if it was previously inherited from the boilerplate; depends on §2 decision).

## Acceptance criteria

- [ ] `logout()` has a default implementation in `CloudProvider`.
- [ ] WebDAV, S3, Rclone, LocalFs no longer override `logout()`.
- [ ] SFTP, OneDrive, Internxt overrides still close their external
      resources.
- [ ] Existing `logout`-related tests still pass; no test weakened.

## Out of scope

Lifting `isAuthenticated` is a separate decision that can be made
inside this ticket OR deferred. Document the choice in the ticket
resolution note.

---
id: UD-009
title: Lift defaultTokenPath() into :app:core/io; eliminate 5-provider duplicate (SFTP/WebDAV/S3/OneDrive/Internxt)
category: architecture
priority: medium
effort: XS
status: closed
closed: 2026-05-15
resolved_by: commit bff6c1e. New :app:core/io/TokenPath.kt holds the canonical defaultTokenPath(providerId). 5 provider Configs migrated. SftpConcurrencyTest call sites updated. Behaviour byte-identical. XDG_CONFIG_HOME out of scope per ticket.
code_refs:
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProviderFactory.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ProviderFactory.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProviderFactory.kt
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProviderFactory.kt
opened: 2026-05-02
---
## Problem

5 providers (SFTP, WebDAV, S3, OneDrive, Internxt) each define an identical helper:

```kotlin
fun defaultTokenPath(): Path {
    val home = System.getenv("HOME") ?: System.getProperty("user.home")
    return Paths.get(home, ".config", "unidrive", "<provider-id>")
}
```

Only the last path segment varies by provider id.

## Proposed action

Lift to `:app:core` as a single helper:

```kotlin
// core/app/core/src/main/kotlin/org/krost/unidrive/io/TokenPath.kt
package org.krost.unidrive.io

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Default per-profile token storage directory:
 *   $HOME/.config/unidrive/<providerId>
 *
 * The caller passes its own `id` (typically `factory.id`) so this
 * function does not need to know which providers exist.
 */
fun defaultTokenPath(providerId: String): Path {
    val home = System.getenv("HOME") ?: System.getProperty("user.home")
    return Paths.get(home, ".config", "unidrive", providerId)
}
```

Replace the 5 duplicates with `org.krost.unidrive.io.defaultTokenPath(id)`.

## Acceptance criteria

- [ ] `core/app/core/src/main/kotlin/org/krost/unidrive/io/TokenPath.kt` exists.
- [ ] All 5 duplicates removed.
- [ ] Behaviour byte-identical: `defaultTokenPath("onedrive")` produces the same path string the old `OneDrive`-specific helper did.
- [ ] No test regressions.

## Why architecture-range

Cross-module helper lift. Same category as UD-006.

## Out of scope

Honouring `XDG_CONFIG_HOME` (currently the helper hardcodes `~/.config`; that's a separate ticket if/when XDG compliance becomes a goal).

---
id: UD-006
title: Lift formatSize/formatBytes (IEC byte formatting) into :app:core/io; eliminate 5+ duplicates
category: architecture
priority: high
effort: S
status: closed
closed: 2026-05-15
resolved_by: commit 362f564. Tight scope: ByteFormatter.kt holds canonical 'integer + space + IEC binary' (CliProgressReporter convention). TrashCommand + VersionsCommand migrated to it; CliProgressReporter.formatSize delegates. RelocateCommand/WebDavProvider/StatusAudit/BenchmarkCommand intentionally NOT migrated (different format families per ticket out-of-scope).
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:284
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/VersionsCommand.kt:53
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/TrashCommand.kt:49
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:374
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusAudit.kt:46
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
opened: 2026-05-02
---
## Problem

`formatSize` / `formatBytes` (IEC-binary byte formatting: `B`/`KiB`/`MiB`/`GiB`/`TiB`) is duplicated across 5 files:

| File | Line | Status |
|---|---|---|
| `core/app/cli/src/main/kotlin/.../CliProgressReporter.kt` | 284 (companion) | canonical |
| `core/app/cli/src/main/kotlin/.../VersionsCommand.kt` | 53 | duplicate |
| `core/app/cli/src/main/kotlin/.../TrashCommand.kt` | 49 | duplicate |
| `core/app/cli/src/main/kotlin/.../RelocateCommand.kt` | 374 | duplicate |
| `core/app/cli/src/main/kotlin/.../StatusAudit.kt` | 46 | duplicate |
| `core/providers/webdav/src/main/kotlin/.../WebDavProvider.kt` | (added by UD-???) | duplicate (this PR added a 6th, deliberately, per the SPI-contract spec §3.5) |

All implement the same algorithm. Highest-ROI cleanup: lift to `:app:core` and replace 5 (or 6, including the WebDAV one this PR introduced) call-sites.

## Proposed action

1. Create `core/app/core/src/main/kotlin/org/krost/unidrive/io/ByteFormatter.kt`:

   ```kotlin
   package org.krost.unidrive.io

   /**
    * Format a byte count using IEC binary prefixes (KiB, MiB, ...).
    * One decimal place above the 1 KiB threshold.
    */
   fun formatSize(bytes: Long): String {
       val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
       var size = bytes.toDouble()
       var unit = 0
       while (size >= 1024 && unit < units.lastIndex) {
           size /= 1024
           unit++
       }
       return "%.1f %s".format(size, units[unit])
   }
   ```

2. Replace each duplicate with `import org.krost.unidrive.io.formatSize`.

3. Verify byte-identical output for representative sizes (1, 1023, 1024, 999_999_999, 50 GiB) before deleting any duplicate.

## Why architecture-range

This is a cross-module helper lift; the tooling category is for build/CI scripts. Architecture range fits cross-module API surface.

## Acceptance criteria

- [ ] `core/app/core/src/main/kotlin/org/krost/unidrive/io/ByteFormatter.kt` exists.
- [ ] All 5 (or 6) duplicates replaced with `import org.krost.unidrive.io.formatSize`.
- [ ] Output byte-identical for the 5 fixture sizes above (verify via test).
- [ ] Build green; `./gradlew build test` reports the same baseline.

## Out of scope

Locale-aware formatting, decimal-prefix variants (KB vs KiB), or any
behaviour change to the algorithm itself.

---
id: UD-114
title: SECURITY.md drift: NDJSON validation claim vs ADR-0012 (NamedPipeServer + NdjsonValidator deleted)
category: security
priority: medium
effort: S
status: closed
closed: 2026-05-15
resolved_by: commit 214fa78. SECURITY.md drift fixed: NDJSON inbound-validation + per-connection rate-limit claims removed (mitigations deleted with NamedPipeServer.kt under ADR-0012). T1/D1/D2 STRIDE rows annotated 'moot post-ADR-0012' with re-opening conditions. v0.0.1 baseline UD-105/UD-106 entries struck through. No code change.
code_refs:
  - docs/SECURITY.md:81
  - docs/SECURITY.md:94
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt
opened: 2026-05-02
---
## Problem

`docs/SECURITY.md:81` and `docs/SECURITY.md:94` claim:

> **NDJSON frame validation** at IPC boundary
> (`IpcProgressReporter.kt`) — rejects non-conforming frames before
> dispatch.

> Schema validation rejects malformed structure
> (`IpcProgressReporter.kt`); JSON parse errors dropped.

This was true before [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)
retired the bidirectional named-pipe transport. With ADR-0012:

- `core/app/sync/.../NamedPipeServer.kt` — **deleted**.
- `core/app/sync/.../NdjsonValidator.kt` — **deleted**.
- The remaining `IpcProgressReporter.kt` is a **push-only UDS
  broadcaster**: the daemon writes NDJSON sync events out to a Unix
  socket; nothing reads frames *into* the daemon over IPC.

Verified at filing time:

```
$ find core -name "NamedPipeServer.kt" -o -name "NdjsonValidator.kt"
(no results)
```

The mitigation referenced in `SECURITY.md` therefore does not exist
in the current architecture. The threat model row T1 ("Tampering
with an NDJSON command frame in flight (local loopback)") is also
moot — there are no inbound IPC frames to tamper with.

## Risk

This is a **threat-model honesty** issue. A reader of `SECURITY.md`
believes the daemon validates incoming IPC frames. It doesn't,
because there are no incoming IPC frames. If a future change
re-introduces an IPC inbound surface (e.g. a CLI-to-daemon command
channel), the existing doc would falsely claim it's already
validated.

## Proposed action

Update `docs/SECURITY.md`:

1. **§"Current mitigations"** (line 81 area): remove the "NDJSON
   frame validation at IPC boundary" bullet. Replace with a
   short note describing the actual UDS-broadcast topology and
   why it doesn't need inbound validation (because it has no
   inbound surface).

2. **STRIDE table T1** (line 94 area): either drop the row
   entirely (no inbound IPC = no T1 threat surface) or rephrase
   to cover what *is* exposed today — a malicious local reader
   binding to `unidrive-<profile>.sock` and consuming frames
   intended for the legitimate observer. That has its own
   mitigations (`mode 0600` on the socket, `SO_PEERCRED` if we
   ever want peer identity).

3. Cross-reference [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)
   §"Removed surfaces" so the historical context is one click away.

## Acceptance criteria

- [ ] `grep -rn "NamedPipeServer\|NdjsonValidator" docs/SECURITY.md`
      returns nothing.
- [ ] `grep -n "IpcProgressReporter" docs/SECURITY.md` either
      returns nothing or the surrounding text accurately describes
      what the file does today (push-only UDS broadcaster).
- [ ] STRIDE T1 row either removed or rewritten for the actual
      threat surface.
- [ ] No code changes required — this is purely doc drift.

## Why this is its own ticket

It's a security-doc fix; bundling it with general doc cleanup
would bury it. Filing under `security` range keeps it on the
right reviewer's radar (anyone scanning the security tier in
`AGENT-SYNC.md`).

---
id: UD-012
title: Replace 8x "onedrive" historical default in SyncConfig.kt with ProviderRegistry-driven default (or document via ADR)
category: architecture
priority: medium
effort: S
status: closed
closed: 2026-05-15
resolved_by: commit 0f80d09. Added ProviderRegistry.defaultProvider() returning 'localfs' (in-tree, zero setup) or first SPI-discovered factory. 7 SyncConfig.kt 'onedrive' literals migrated. syncRootDirNameOverrides keeps 'onedrive' to 'OneDrive' for backwards compat (no ~/OneDrive to ~/Microsoft\ OneDrive break). UD-252 tests updated to assert against registry default.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:304
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:313
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:359
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:433
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:436
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:441
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:461
opened: 2026-05-02
---
## Problem

`SyncConfig.kt` hardcodes `"onedrive"` as the universal fallback default at 8 sites (verified 2026-05-02 against the post-refactor/provider-spi-contract tree):

| Line | Role |
|---|---|
| 304 | `providerId: String = "onedrive"` (default param of `RawProvider.defaults`-helper-style ctor) |
| 313 | `"onedrive" to "OneDrive"` — display-name override map |
| 359 | `fun defaults(providerId: String = "onedrive")` |
| 433 | `if (!Files.exists(configFile)) return "onedrive"` (resolveDefaultProfile waterfall) |
| 436 | `raw.general.default_profile?.takeIf { it.isNotBlank() } ?: "onedrive"` |
| 441 | `"onedrive"` (last fallback in resolveDefaultProfile) |
| 461 | `profileName: String = "onedrive"` (default param) |

Plus one help-text reference:
- `core/app/cli/src/main/kotlin/.../Main.kt:474` — `RCLONE_BINARY ... default: "rclone"` (refers to the rclone *binary* on PATH, not the provider id; mentioned for completeness).

The `SyncConfig` references are the "single-provider-era" artefact: the project had only OneDrive when this default was chosen. With 7 in-tree providers today, the choice is undocumented and inconsistent (display-name map at 313 only covers one provider; the rest get their default-display from elsewhere).

## Proposed action

Replace with a registry-driven default. Two acceptable shapes:

**A) `ProviderRegistry.defaultProvider()`** that returns either:
- the first SPI-discovered provider (deterministic by classpath order, usually `localfs` since it's pure-Java); or
- a config-driven choice (`general.default_provider_when_none` in `config.toml`).

Then `SyncConfig.kt` calls `ProviderRegistry.defaultProvider()` instead of literal `"onedrive"`. Display-name map at line 313 is removed in favour of `ProviderRegistry.getMetadata(id)?.displayName`.

**B) Keep "onedrive" but document why** in an ADR. If OneDrive is genuinely the canonical default for the project's target audience, that's a defensible position — but it should be a deliberate choice with `// allow: per ADR-XXXX` markers on each site.

Recommend **A**. The architecture has moved on; the literal hasn't. A registry-driven choice is also testable (parametric tests can swap the default per-test).

## Acceptance criteria

- [ ] Decision A vs B documented (ideally in a short ADR, e.g. ADR-0015 "default provider resolution").
- [ ] If A: `SyncConfig.kt` has zero `"onedrive"` literals; all 8 sites consult `ProviderRegistry.defaultProvider()` or `ProviderRegistry.getMetadata`.
- [ ] If B: each surviving literal has `// allow: per ADR-XXXX` marker (CI guard's allow-list mechanism).
- [ ] Either way, `bash scripts/ci/check-no-provider-string-dispatch.sh` passes.
- [ ] No regression in `resolveDefaultProfile` waterfall semantics; existing tests still green.

## Why architecture-range

It's a structural decision (what is the project's default provider?) with cross-module implications. UD-004 / UD-008 / UD-011 are all architecture-range; this fits.

## Out of scope

XDG-config-honouring resolution, multi-default support (different default per environment), profile-cycling. Those are independent later work.

## Status of the CI guard

`scripts/ci/check-no-provider-string-dispatch.sh` (added in
refactor/provider-spi-contract) currently flags these 8 sites. Until
this ticket is resolved, **the lines are tagged with `// allow:
UD-012` markers** so CI doesn't fail on pre-existing technical debt.
Removing those markers is part of this ticket's done-criterion.

---
id: UD-008
title: Abstract SnapshotDeltaProvider helper (or base class) — eliminate 5x deletedItem lambda duplication and shared delta() boilerplate
category: architecture
priority: high
effort: M
status: closed
closed: 2026-05-15
resolved_by: commit 82134bf. Option B: helper function (no abstract base class). New SnapshotEntry interface + defaultDeletedItem(path, entry, id=path) in :app:sync. All 5 snapshot entry classes implement SnapshotEntry; 5 provider delta() lambdas shrink from 11 lines to 1. S3 reuses helper via id=api.pathToKey(path) override.
code_refs:
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProvider.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt
  - core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProvider.kt
  - core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SnapshotDeltaEngine.kt
opened: 2026-05-02
---
## Problem

All 5 snapshot-based providers (SFTP, WebDAV, S3, Rclone, LocalFs) follow an identical `delta()` structure:

```kotlin
override suspend fun delta(cursor, onPageProgress): DeltaPage {
    val heartbeat = onPageProgress?.let { cb -> ScanHeartbeat(cb) }
    val currentEntries = api.listAll(onProgress = { count -> heartbeat?.tick(count) })
    val snapshotEntries = buildSnapshotEntries(currentEntries)
    val itemsByPath = currentEntries.associate { it.path to it.toCloudItem() }
    return computeSnapshotDelta(
        currentEntries = snapshotEntries,
        currentItemsByPath = itemsByPath,
        prevCursor = cursor,
        entrySerializer = XxxSnapshotEntry.serializer(),
        hasChanged = { prev, curr -> /* provider-specific */ },
        deletedItem = { path, entry ->
            CloudItem(id = path, name = path.substringAfterLast("/"), path = path,
                      size = 0, isFolder = entry.isFolder, modified = null,
                      created = null, hash = null, mimeType = null, deleted = true)
        },
    )
}
```

Per-provider variations:
- `listAll()` — API call shape
- `buildSnapshotEntries()` — entry-type mapping
- `toCloudItem()` — `CloudItem` mapping
- `hasChanged` — change-detection predicate

The `deletedItem` lambda is **byte-identical across all 5** providers.

## Proposed action

Two options:

**A) Abstract base class `SnapshotDeltaProvider<E>`** that captures the boilerplate. Concrete providers implement only the four variations:

```kotlin
abstract class SnapshotDeltaProvider<E>(
    private val entrySerializer: KSerializer<E>,
) : CloudProvider {
    protected abstract suspend fun listAll(heartbeat: ScanHeartbeat?): List<RawEntry<E>>
    protected abstract fun hasChanged(prev: E, curr: E): Boolean

    final override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?): DeltaPage {
        // shared boilerplate — calls listAll, builds maps, calls computeSnapshotDelta
    }
}
```

**B) Standalone helper function `snapshotDelta(...)`** that takes the four variation points as lambda parameters. Providers retain their flat structure but call into the helper:

```kotlin
override suspend fun delta(cursor, onPageProgress): DeltaPage =
    snapshotDelta(
        cursor = cursor,
        progress = onPageProgress,
        listAll = { hb -> api.listAll(...) },
        toEntry = ::buildSnapshotEntry,
        toCloudItem = RawEntry<E>::toCloudItem,
        hasChanged = ::hasChanged,
        entrySerializer = MySnapshotEntry.serializer(),
    )
```

Recommend **B** (helper function) over **A** (abstract base): `CloudProvider` is already an interface, and Kotlin doesn't love abstract-class diamond inheritance when interfaces gain abstract methods. Helper-function pattern keeps providers as plain classes.

Lift `deletedItem` into the helper; per-provider duplicates disappear.

## Acceptance criteria

- [ ] Decision A vs B documented in the ticket resolution.
- [ ] `deletedItem` lambda exists once (in the helper or the base class), zero duplicates across providers.
- [ ] All 5 snapshot providers' `delta()` methods shrink to ≤10 lines, mostly delegating.
- [ ] No test regressions; existing `Reconciler` / `delta` tests still pass.

## Why architecture-range

It's a structural change to how snapshot providers compose with the
sync engine. Architecture range; needs an ADR or short design doc
before any code moves (decision A vs B is the kind of thing future
readers will ask about).

## Out of scope

Refactoring OneDrive (it uses `/delta` natively, not snapshot mode)
or Internxt (its delta path is also custom and is being audited
separately under UD-354).

---
id: UD-338
title: Lift token-refresh mutex+NonCancellable pattern to shared :app:core/auth
category: providers
priority: high
effort: M
status: closed
closed: 2026-05-15
resolved_by: commit 40ec063. Scoped lift: RefreshableTokenLatch encapsulates exactly the mutex + NonCancellable + isAlreadyFresh-predicate pattern. OneDrive UD-310 forceRefresh + UD-111 RefreshFailure stay in TokenManager (intentional features, not duplication). HiDrive mention in ticket body was stale — no HiDrive provider in v0.1.0 tree.
opened: 2026-04-30
---
**From the 2026-04-30 provider-duplication survey (agent run after UD-748).**

Three near-identical `getValidToken` / `refreshToken` flows live in the
provider modules:

- `core/providers/onedrive/.../TokenManager.kt:88-149` — full version
  with UD-310 forceRefresh + UD-111 `lastRefreshFailure` recording.
- `core/providers/hidrive/.../TokenManager.kt:51-88` — UD-331
  NonCancellable wrap; missing UD-310 forceRefresh and UD-111 failure
  record.
- `core/providers/internxt/.../AuthService.kt:207-230` — UD-331
  NonCancellable wrap; missing UD-310 forceRefresh; has a
  `fetchRefreshedJwt` overridable seam for tests.

The skeleton is identical: `refreshMutex`, recheck-after-acquire,
`withContext(NonCancellable)` around the refresh + persist. The
divergence has already burnt time — UD-331 had to mirror UD-310 by
hand.

## Proposal

Extract a generic helper to `:app:core/auth/RefreshableCredentialStore.kt`:

```kotlin
class RefreshableCredentialStore<T>(
    private val isStale: (T) -> Boolean,
    private val refresh: suspend (T) -> T,   // network call
    private val persist: suspend (T) -> Unit,
)
```

with `currentValue: T?`, `forceRefresh: Boolean`, and a
`RefreshFailure` record matching UD-111's shape. HiDrive automatically
gets UD-310 forceRefresh + UD-111 failure recording for free.

## Acceptance

- All three providers consume the shared store; no duplicate refresh
  loops remain.
- HiDrive's silent `println("Token refresh failed")` (TokenManager.kt:82)
  becomes a UD-111-style failure record + log.warn.
- Tests pin the contract: stale-then-fresh path, refresh-failure
  recording, NonCancellable-survives-cancellation, force-refresh.

## Effort / agent-ability

**M effort**, agent-able partial — design contract (seam for
persistence, generic over credential type) needs human input first.

## Related

- **UD-310** (closed) — OneDrive forceRefresh.
- **UD-111** (closed/open?) — token-refresh failure telemetry.
- **UD-331** (closed) — NonCancellable wrap mirror across providers.
- **UD-336** (closed, sibling lift) — error-body helpers in same package.
