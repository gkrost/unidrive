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
