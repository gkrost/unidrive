# `:app:sync-tracking` — structural-safety sync engine

A sync engine built around a tracking-set predicate over deletion.
Untracked paths — paths the client has never crossed the sync boundary
for — are invisible to deletion logic, making the phantom-row
delete-cascade class of bug structurally impossible.

**Experimental.** Wired into the CLI as `unidrive ts {sync,claim,unclaim,status}`
but not yet verified end-to-end against real Internxt or OneDrive
providers. Use `--dry-run` first. `unidrive sync` (the legacy engine)
stays the default until provider-integration parity lands.

## Why it exists

The existing `SyncEngine` / `Reconciler` treat the state DB as a record
of truth. **Any** `sync_root` absence is read as a user-delete signal —
including phantom rows left behind by an interrupted first-sync. The
`.safe/` incident was the headline case: 28 phantom folder rows from
a crashed first-sync produced 28 `del-remote` actions on the next pass,
against files the user had never seen locally.

This engine starts from a different invariant:

> **A path is managed only after the client has actually crossed the
> sync boundary for it (downloaded or uploaded) at least once.**
> Untracked paths are invisible to deletion logic.

The bug class becomes structurally impossible: an empty tracking set
contains zero candidates for deletion, so no amount of phantom DB rows
or filesystem accidents can synthesize a delete. See the lemma test in
`TrackingEngineIntegrationTest.kt`.

## How to use

```bash
# Show what one pass would do, no IO. ALWAYS start here.
unidrive ts sync -p <profile> --dry-run

# Run for real (still experimental).
unidrive ts sync -p <profile>

# After the engine reports a collision, claim the local copy as
# authoritative. (Or edit the local file to match and rerun.)
unidrive ts claim /Documents/the-conflict.txt

# Remove a path from the tracking set without touching files.
unidrive ts unclaim /Documents/the-conflict.txt

# See state counts.
unidrive ts status
```

The tracking set lives in a separate SQLite file at
`<config-dir>/<profile>/tracking.db`. The existing `state.db` is never
touched — both engines can run on the same profile, and falling back
is just "use `unidrive sync` instead of `unidrive ts sync`".

## How this engine differs from `:app:sync`

| Behaviour | `:app:sync` (legacy) | `:app:sync-tracking` |
|---|---|---|
| First-sync source of truth | DB rows + sync_root walk | Tracking set is empty; untracked paths invisible to deletion |
| Adopt-on-content-match | No (engine assumes DB authoritative) | Yes (spec Amendment 2); first-scan over non-empty `sync_root` |
| Drop a new file into sync_root | Uploaded on next pass | **Not uploaded** — explicit `ts claim` required |
| Crash mid first-sync | Phantom rows can become deletes | Pending* rows re-derive to original intent on resume |
| Batch-level delete safeguard | Percentage + absolute + per-subtree | `BatchGuard` ratio + absolute; deletes dropped if tripped, uploads still proceed |
| Identity | path (with rename heuristics) | `(provider_id, remote_file_id)` once known, content-hash for rename (spec Amendment 1) |

## What is intentionally NOT implemented yet

These are the explicit scope cuts; each is a follow-up worth its own
BACKLOG entry once `:app:sync-tracking` has provider-integration parity:

- **Move / rename detection.** A rename today shows up as one delete + one
  download. Real implementation requires the inode/file-id matching
  layer hinted at by `LocalObservation.inode` and `RemoteObservation.remoteFileId`.
- **Conflict resolution policy.** Collisions are reported with
  `ReconcileAction.ReportCollision`; the user must `ts claim` a winner.
  No `KEEP_BOTH` / `LOCAL_WINS` / `REMOTE_WINS` automation yet.
- **Files-on-demand / placeholders.** Every reconciliation downloads
  fully or not at all. Placeholder support is platform-tier work per
  [docs/adr/multi-platform.md](../../../docs/adr/multi-platform.md).
- **Pinning rules.** No `pin_rules` table equivalent.
- **Delta-cursor persistence.** Every pass is a full remote
  enumeration. Real-provider work (Internxt, OneDrive) will need a
  per-profile cursor that survives across runs.
- **Real-provider end-to-end verification.** The integration tests use
  `FakeTrackingProvider`; the engine has not been exercised end-to-end
  against live Internxt / OneDrive yet. That is the obvious next ticket.
- **Concurrent IO during apply.** Single-threaded apply loop. Real
  parallelism is where this kind of engine grows new bugs — keeping it
  single-threaded keeps the structural-safety story unambiguous.
- **Progress reporting / IPC.** The engine emits a final `PassReport`;
  no `ProgressReporter` equivalent. The existing engine's IPC server
  is untouched.
- **Migration from existing `state.db`.** The first `ts sync` pass on a
  profile with an existing `state.db` does NOT read `state.db`; it
  starts with an empty `tracking.db` and adopts on content-match.

## A/B testing against the legacy engine

The two engines run on independent state, so the easiest A/B is:

1. Pick a profile with a known-bad history (e.g. a reproduction of the
   `.safe/` incident shape).
2. Run `unidrive ts sync --dry-run` and capture the proposed plan.
3. Run `unidrive sync --dry-run` and capture its plan.
4. Diff the two. Anything `unidrive sync` would delete that `unidrive
   ts sync` skips is a candidate for the structural-safety win.

When you're confident the tracking-set plan is sound, run `ts sync` for
real. The existing `state.db` is untouched, so falling back to
`unidrive sync` is a no-op revert.

## Module layout

```
core/app/sync-tracking/
  build.gradle.kts
  README.md (this file)
  src/main/kotlin/org/krost/unidrive/tracking/
    TrackingModel.kt        # TrackState, TrackingRecord, observations, ReconcileAction
    TrackingReconciler.kt   # pure per-path reconciliation
    TrackingSet.kt          # interface + SqliteTrackingSet impl (tracking.db)
    BatchGuard.kt           # max delete ratio (spec Amendment 3)
    TrackingEngine.kt       # orchestrator
    TrackingCli.kt          # picocli @Command tree + CliExtension entry
  src/main/resources/META-INF/services/org.krost.unidrive.cli.ext.CliExtension
  src/test/kotlin/org/krost/unidrive/tracking/
    FakeTrackingProvider.kt           # in-memory CloudProvider for tests
    TrackingReconcilerTest.kt         # case-table unit tests + BatchGuard
    TrackingEngineIntegrationTest.kt  # .safe/ regression + lemma + happy paths
```
