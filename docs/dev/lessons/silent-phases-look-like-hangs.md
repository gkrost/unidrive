# Silent phases look exactly like a hang

Learned 2026-05-02, after a 67k-local + 19k-remote first-sync against
Internxt that the operator cancelled because the daemon "stopped
working" mid-sync. The daemon hadn't stopped — it was running through
a phase that emitted zero log lines and zero CLI / IPC heartbeats for
many seconds. See [UD-240g](../../backlog/CLOSED.md) (or BACKLOG until
closed) for the resulting fix.

## The failure mode

1. A long-running engine phase exists between two log-emitting / progress-emitting
   phases. In this case: between
   `gatherRemoteChanges` (last log line: `Delta: N items, hasMore=false`)
   and `reporter.onActionCount(actions.size)` (next CLI line:
   `Reconciled: N actions`).
2. The phase in the middle (`Reconciler.reconcile` here) does real work
   — ~86k SQLite `getEntry()` lookups + 2 full-table scans on a 67k+19k
   first-sync — but emits nothing to the log file and nothing to the
   reporter.
3. The CLI progress reporter's `printInline("\r…")` repaints in place
   (`UD-735`). The cursor sits frozen on the *previous* phase's last
   line (`Scanning local files… 67,458 items · 0:07`).
4. The user reads the frozen cursor + silent log as "stuck". They
   cancel. The whole pass is wasted.

## Why it bites

- **The CLI progress line lies by omission.** It shows the most recent
  thing the reporter heard about. If a later phase doesn't emit, the
  display tells the user the last phase is still running. There is no
  visible difference between "scan finished, reconcile is grinding"
  and "scan deadlocked".
- **Silence ≠ idle.** A daemon doing 86k synchronous fsyncs is fully
  CPU- and I/O-busy with no UI proof of life. Operating-system level
  observation (CPU%, syscall rate) would distinguish it from a hang;
  the log file alone doesn't.
- **Add-then-forget pattern.** Each new long-running pass tends to
  re-introduce the gap: the original `Reconciler` was fast on the
  workloads its tests covered; the silent-phase failure mode emerges
  only at scale, only on first-sync, only on a workload the test
  suite doesn't synthesise.

## How to apply

When you add or audit a phase that runs inside `SyncEngine.doSyncOnce`
(or any equivalent long-lived orchestration loop), require ALL THREE of:

1. **Bookend log lines.** `log.info("X started: …")` at entry,
   `log.info("X complete: N in {}ms")` at exit. The duration line
   makes regressions visible in normal log review.
2. **Reporter heartbeat.** Fire `reporter.on<Phase>Progress(processed,
   total)` from inside the hot loop, throttled by [`ScanHeartbeat`](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ScanHeartbeat.kt)
   (5k items / 10s, whichever first) so the CLI / IPC clients show
   movement. Add a default `{}` body on the interface method so
   reporters that don't render the phase don't need updating; override
   in `CliProgressReporter`, `IpcProgressReporter`, and the
   `CompositeReporter` in `SyncCommand` (the composite delegates and
   will silently no-op the new method otherwise).
3. **No silent O(N) DB calls inside the hot loop.** If you find
   yourself calling `db.getEntry(path)` once per item, bulk-load
   `db.getAllEntries().associateBy { it.path }` once at the top and
   route lookups through the in-memory map — `LocalScanner.kt:35`
   already follows this pattern, [`Reconciler.kt`](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt)
   followed it post-UD-240g.

## Defensive checklist when adding a new phase

- [ ] Phase-entry `log.info` line names the phase and its inputs (sizes).
- [ ] Phase-exit `log.info` line names the phase, its result count, and elapsed ms.
- [ ] Reporter event fired at entry (`processed=0, total=…`), at exit (`processed=total`),
      and on a `ScanHeartbeat` tick from inside the loop.
- [ ] No `db.getX(path)` inside an O(N) loop where N can exceed ~1k. Bulk-load instead.
- [ ] If the new reporter method has a default `{}` body, `SyncCommand.CompositeReporter`
      gets an explicit `override fun … = delegates.forEach { it.… }` — defaults
      survive the composite's `forEach`-over-delegates pattern, but skipping the
      composite override means the new event silently no-ops in the live CLI path.

## See also

- [verify-before-narrative.md](verify-before-narrative.md) — the
  diagnosis story for "the daemon hung" was wrong; the reporter just
  stopped speaking. Same shape as UD-219.
- UD-240 (umbrella) — the long-standing "feedback for long-running
  CLI/UI actions" ticket. Sub-tickets a-f propose six wins; UD-240g
  fixed the reconcile-phase slice; UD-240h is the LocalScanner sibling.
