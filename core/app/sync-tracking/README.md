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

## Reconciliation by scenario

The reconciler's decision table is the spec — but four concrete scenarios cover the load-bearing cases. Each shows local FS / tracking.db / remote before and after one `ts sync` pass.

```
LEGEND      ●  present       ──── ts sync ────►  one reconcile pass
            ∅  absent        ◄──  newly arrived (no engine action yet)

Three columns per scenario:

  ┌─ LOCAL ─────┐    ┌─ TRACKING.DB ─────────┐    ┌─ REMOTE ────┐
  │  files on   │    │  the engine's state   │    │  what the   │
  │  sync_root  │    │  model + state machine│    │  provider   │
  └─────────────┘    └───────────────────────┘    └─────────────┘


══════════════════════════════════════════════════════════════════════
 A.  first sync from empty local (the populated-remote case)
══════════════════════════════════════════════════════════════════════

  before:
  ┌─ LOCAL ─────┐    ┌─ TRACKING.DB ─────────┐    ┌─ REMOTE ────┐
  │ (empty)     │    │ (empty)               │    │ a.txt    ●  │
  │             │    │                       │    │ b.txt    ●  │
  │             │    │                       │    │ c.txt    ●  │
  └─────────────┘    └───────────────────────┘    └─────────────┘

  reconcile:
    /a..c   track=∅ + local=∅ + remote=●   →  DownloadRemote × 3

                       ──── ts sync ────►

  after:
  ┌─ LOCAL ─────┐    ┌─ TRACKING.DB ─────────┐    ┌─ REMOTE ────┐
  │ a.txt    ●  │    │ /a.txt  TrackedSynced │    │ a.txt    ●  │
  │ b.txt    ●  │    │ /b.txt  TrackedSynced │    │ b.txt    ●  │
  │ c.txt    ●  │    │ /c.txt  TrackedSynced │    │ c.txt    ●  │
  └─────────────┘    └───────────────────────┘    └─────────────┘


══════════════════════════════════════════════════════════════════════
 B.  partially-filled local — recovery from a crashed first sync
══════════════════════════════════════════════════════════════════════

  Crash before any bytes hit disk: 5 PendingDownload rows already
  persisted; local is still empty.

  before:
  ┌─ LOCAL ─────┐    ┌─ TRACKING.DB ─────────┐    ┌─ REMOTE ────┐
  │ (empty)     │    │ /.safe/f-0 PendingDl  │    │ /.safe/     │
  │             │    │ /.safe/f-1 PendingDl  │    │  f-0     ●  │
  │             │    │ /.safe/f-2 PendingDl  │    │  f-1     ●  │
  │             │    │ /.safe/f-3 PendingDl  │    │  ...        │
  │             │    │ /.safe/f-4 PendingDl  │    │  f-29    ●  │
  │             │    │  (localHash = null)   │    │             │
  └─────────────┘    └───────────────────────┘    └─────────────┘

  reconcile (the lemma in action):
    f-0..4   track=PendingDl + local=∅ + remote=●
             snapshot.localHash == null  → NOT localGone
             PendingDownload short-circuit fires → DownloadRemote
    f-5..29  track=∅ + local=∅ + remote=●  → DownloadRemote

  ✓ 30 DownloadRemote planned   ✗ 0 PropagateRemoteDelete
  (legacy state.db-as-authoritative would emit 28 del-remote actions
   from the same phantom-row shape — the bug this engine was built for)

                       ──── ts sync ────►

  after:
  ┌─ LOCAL ─────┐    ┌─ TRACKING.DB ─────────┐    ┌─ REMOTE ────┐
  │ /.safe/     │    │ /.safe/f-0..29        │    │ /.safe/     │
  │  f-0..29 ●  │    │   TrackedSynced × 30  │    │  f-0..29 ●  │
  └─────────────┘    └───────────────────────┘    └─────────────┘


══════════════════════════════════════════════════════════════════════
 C.  populated local + populated remote — adopt-on-content-match
══════════════════════════════════════════════════════════════════════

  Pre-existing files on both sides. Some match exactly; some don't.

  before:
  ┌─ LOCAL ─────┐    ┌─ TRACKING.DB ─────────┐    ┌─ REMOTE ────┐
  │ a.txt "abc" │    │ (empty)               │    │ a.txt "abc" │
  │ b.txt "L"   │    │                       │    │ b.txt "R"   │
  │ c.txt "z"   │    │                       │    │ (no c)      │
  └─────────────┘    └───────────────────────┘    └─────────────┘

  reconcile (spec Amendment 2):
    /a.txt   track=∅ + local=● + remote=● + hash match    → adopt
    /b.txt   track=∅ + local=● + remote=● + hash differ   → collision
    /c.txt   track=∅ + local=● + remote=∅                 → NoOp

                       ──── ts sync ────►

  after:
  ┌─ LOCAL ─────┐    ┌─ TRACKING.DB ─────────┐    ┌─ REMOTE ────┐
  │ a.txt "abc" │    │ /a.txt  TrackedSynced │    │ a.txt "abc" │
  │ b.txt "L"   │    │ (no /b.txt row)       │    │ b.txt "R"   │
  │ c.txt "z"   │    │ (no /c.txt row)       │    │             │
  └─────────────┘    └───────────────────────┘    └─────────────┘

  console:
    ! /b.txt: untracked path exists on both sides with different content
    Resolve with: unidrive ts claim /b.txt
    (/c.txt is invisible — untracked pure-local files are never deleted
     and never auto-uploaded; the user runs `ts claim /c.txt` to opt in.)


══════════════════════════════════════════════════════════════════════
 D.  populated + all-synced, then bulk-rm — BatchGuard intervenes
══════════════════════════════════════════════════════════════════════

  Steady state. User rm's the whole sync_root by accident
  (or remounts over the wrong volume).

  before:
  ┌─ LOCAL ─────┐    ┌─ TRACKING.DB ─────────┐    ┌─ REMOTE ────┐
  │ (empty)  ◄──│    │ /a.txt  TrackedSynced │    │ a.txt    ●  │
  │             │    │ /b.txt  TrackedSynced │    │ b.txt    ●  │
  │             │    │ /c.txt  TrackedSynced │    │ c.txt    ●  │
  └─────────────┘    └───────────────────────┘    └─────────────┘

  reconcile:
    /a..c   tracked + local=∅ + remote=●  → PropagateLocalDelete × 3

  BatchGuard.inspect(plan, trackedTotal=3) with defaults 0.5 / 50:
    ratio    = 3 / 3 = 1.00 > 0.50   → ratio tripped
    absolute = 3 ≤ 50                → absolute not tripped
    Verdict: Deny — drop all delete actions; non-delete actions still apply.

                       ──── ts sync ────►

  after:
  ┌─ LOCAL ─────┐    ┌─ TRACKING.DB ─────────┐    ┌─ REMOTE ────┐
  │ (empty)     │    │ /a.txt  TrackedSynced │    │ a.txt    ●  │
  │             │    │ /b.txt  TrackedSynced │    │ b.txt    ●  │
  │             │    │ /c.txt  TrackedSynced │    │ c.txt    ●  │
  └─────────────┘    └───────────────────────┘    └─────────────┘

  console:
    BatchGuard tripped: 3 delete(s) requested (tracked total: 3,
    ratio: 1.00). Ratio exceeds 0.50. No deletes applied this pass.

  (Defense-in-depth. The lemma already rules out untracked-path deletion;
   the BatchGuard backstops the "tracked-but-provider-lied" residual.
   Re-running `ts sync` reproduces this identically — the guard is
   per-pass, recomputed from the plan. Restore the missing files or
   pass `--max-delete-ratio=1.0` to bypass.)
```

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

## Differential verification against the official Internxt client

When the Internxt official desktop client runs against the same account
on the same machine, its sync_root is the **oracle** for what the cloud
actually contains. `scripts/dev/verify-against-internxt-official.sh` is
a loop tool that snapshots both sync_roots, runs `ts sync --dry-run`,
parses the plan, and flags any action that contradicts the oracle.

Falsification table:

| plan action | path in oracle? | verdict |
|---|---|---|
| `del-remote /foo` | yes | **BUG** — would delete a file the cloud actually has |
| `del-local /foo`  | yes | **BUG** — would delete locally for a cloud-absence that isn't real |
| `download /foo`   | no  | SUSPICIOUS — delta saw a path the oracle didn't (timing window or stale view) |
| `upload /foo`     | yes (different content) | COLLISION — worth surfacing |
| any for `/foo` only in unidrive's root | n/a | pure-local untracked, expected (Amendment 2) |

Usage:

```bash
scripts/dev/verify-against-internxt-official.sh \
  --official-root='C:/Users/gerno/InternxtDrive - 0c06806b-...' \
  --unidrive-root='C:/Users/gerno/Internxt' \
  --profile=internxt \
  --interval=30 --max-iters=10
```

Exit codes: `0` = no falsifying actions across the run; `1` = at least
one BUG flagged; `2` = misconfiguration. Persistent flags across multiple
iterations are real bugs; one-off transient flags can be timing artifacts
(one client's delta hasn't caught up yet, or the other client is mid-
download). The loop interval is what lets the operator distinguish.

This tool complements the Gradle live-integration test
(`TrackingEngineInternxtLiveTest`): the JUnit test is the per-commit
assertion that the engine doesn't crash against real Internxt; the
script is the interactive convergence-watching tool for the harder
"does it produce the *right* plan" question. Per the "Verify Internxt
provider end-to-end" BACKLOG entry, BUG-category surfaces should be
filed as follow-up entries rather than fixed inline.

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
    FakeTrackingProvider.kt              # in-memory CloudProvider for tests
    TrackingReconcilerTest.kt            # case-table unit tests + BatchGuard
    TrackingEngineIntegrationTest.kt     # .safe/ regression + lemma + happy paths
    TrackingEngineInternxtLiveTest.kt    # lemma + downloads-only invariant against live Internxt
```
