# Closed items — append-only archive

> When closing a backlog item, **move** the full frontmatter block from [BACKLOG.md](BACKLOG.md) to the bottom of this file and add `status: closed`, `closed: YYYY-MM-DD`, and optional `resolved_by:`. Never edit entries above the latest. See [AGENT-SYNC.md](../AGENT-SYNC.md).

<!-- First closed entry will be appended below this marker. -->

---
id: UD-229
title: Research — Microsoft Graph API feature table: what unidrive uses vs ignores
category: docs
priority: low
effort: M
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive
status: closed
closed: 2026-05-04
resolved_by: commit 50e0e91. Doc was largely already-written from a prior pass; refreshed all stale GraphApiService.kt:NNN line refs (file grew ~140 lines), fixed throttle-budget constants (5 min / 15 min, not 2 min / 10 min as previously stated), added See-also block linking ADR-0005 + SPECS.md per ticket's companion-cross-reference ask. Two tables (A: 10 used + secondary list, B: 10 v0.2.0+ candidates with S/M/L tiers + deferred list) intact.
opened: 2026-04-19
---
Produce two tables in `docs/providers/onedrive-graph-coverage.md`:

**Table A — Top 10 Graph features unidrive DOES use** — e.g. `/me/drive/root/delta` (delta enum), `/me/drive/items/{id}/content` (download), upload session, webhook subscriptions, `/createLink` sharing, etc. Per row: endpoint, why unidrive calls it, file:line of the caller, known quirks (UD-307-style ZWJ rejections, CDN 429 patterns, Retry-After header habits).

**Table B — Top 10 Graph features unidrive DOES NOT use** — candidates worth evaluating for v0.2.0+. Ideas:
  * **Drive-level search** (`/me/drive/search(q='...')`) — would let `unidrive search` skip full enumeration.
  * **Item preview** (`/items/{id}/preview`) — tray hover thumbnails / MCP previews.
  * **OneNote scope** — currently invisible to unidrive.
  * **Shared items `/me/drive/sharedWithMe`** — partially wired via UD-301 deltaWithShared, but explicit listings would help UX.
  * **Sensitivity labels / retention** — compliance features relevant for enterprise pitches.
  * **Thumbnail sizes** — cheaper than download for tray previews.
  * **Item versioning** `/items/{id}/versions` — unidrive has its own `.unidrive-versions/` scheme; overlap.
  * **Long-running operations** (`/async-monitor`) — we probably want these for large moves.
  * **Audit logs** (enterprise tenants) — relevant for UD-113 audit trail.
  * **Batch requests** (`/$batch`) — up to 20 ops in one HTTP call, reduces throttle pressure.

Each row: endpoint, what it does, cost (RPS impact, throttle class), unidrive-side integration cost (S/M/L), priority.

Companion cross-reference to ADR-0005 (Provider capability contract).

---
id: UD-205
title: Peer-review — atomicity across sync transfer phases (Gemma, needs-verification)
category: core
priority: high
effort: M
status: closed
closed: 2026-05-04
resolved_by: commit 2dac299. Verified 2026-05-04 (auto mode): the 'interruption recovery' risk this ticket flagged as the real concern (after dismissing the 'concurrent dirty reads' framing) is fully addressed by the UD-225a/b + UD-901a/b/c fix-stack that landed in subsequent sessions. Reconciler.kt now carries comprehensive recovery loops: UD-225a rehydrates download orphans (commit f9f1791), UD-225b routes through downloadById (commit 2dac299), UD-901a/b/c synthesise CreateRemoteFolder for ancestor cascades and recover pending-upload rows. SyncCornerCaseTest.kt provides 'sparse placeholder from interrupted sync is replaced by real download' coverage; ReconcilerTest.kt includes 'UD-901 interrupted upload retries on next reconcile'. The structural observation that Pass-2 transfers run outside the Pass-1 db.beginBatch/commitBatch boundary is still true (SyncEngine.kt:361 vs :442) but no longer matters — the recovery loops mean a SIGKILL during Pass-2 leaves recoverable state, not silent divergence. The ticket's acceptance criterion ('SIGKILL-during-transfer test, decide whether cross-phase tx or transfer-state marker is warranted') is met by SyncCornerCaseTest's interrupted-sync coverage; the 'cross-phase tx' option is correctly NOT pursued because per-row recovery is more robust than rolling back a multi-file transfer batch on partial failure.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt
adr_refs: []
opened: 2026-04-17
---
Peer-review (local Gemma) claim: `SyncEngine` wraps its metadata actions in a `db.beginBatch()` / `commitBatch()` but runs concurrent transfers (Pass 2) **outside** that batch. **Spot-check (2026-04-17):** structurally confirmed at `SyncEngine.kt:172-218` (sequential batch) vs `:220-277` (`coroutineScope { launch { ... applyDownload/applyUpload ... } }` outside batch). However the peer's "dirty reads / phantom updates" framing overreaches: SQLite single-row writes are atomic and `ProcessLock` prevents overlapping sync runs, so concurrent row updates on *different* paths are safe. The real risk is **interruption recovery** — if Pass-2 is killed mid-transfer, state DB and local files diverge. Acceptance: audit resume logic (`Reconciler` + placeholder re-entry) against a SIGKILL-during-transfer test; decide whether a cross-phase transaction or a transfer-state marker column is warranted.

---
id: UD-208
title: Peer-review — SyncAction refactor to type + payload split (Gemma, deferred)
category: core
priority: low
effort: M
status: closed
closed: 2026-05-04
resolved_by: Verified 2026-05-04 (auto mode): closed as still-deferred / wontfix. The ticket body itself filed this with reopen-criterion: 'reopen if SyncEngine action-dispatch becomes a maintenance bottleneck.' That bottleneck has not emerged in the 2 weeks since opening; no commit since 2026-04-17 mentions the dispatch as a pain point. The when-block in SyncEngine.kt:380 is small (5 active cases + else fallthrough) — the surrounding ~50 lines that look long are the catch-block error handling (UD-253, UD-248), a separate concern that wouldn't be helped by a SyncActionType+Payload split. The 'don't refactor speculatively' rule the ticket cites still applies; closing with no code change is the right call. Reopen if a future feature genuinely needs the dispatch shape changed.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync
adr_refs: []
opened: 2026-04-17
---
Peer-review structural suggestion: split `SyncAction` (sealed class today) into `SyncActionType` enum + `SyncActionPayload` data classes to shrink the `when` block in `SyncEngine`. **Deferred** because refactor-for-structure without a concrete bug-motivation violates the "don't refactor speculatively" guidance; reopen if `SyncEngine`'s action-dispatch becomes a maintenance bottleneck.

---
id: UD-302
title: deltaWithShared gap closure (S3, SFTP, Rclone, LocalFS, WebDAV, HiDrive, Internxt)
category: providers
priority: medium
effort: L
status: closed
closed: 2026-05-04
resolved_by: commit 529a0d3. Verified 2026-05-04 (auto mode): the ticket body's claim 'all non-OneDrive providers silently return empty' is FALSE as of current code. CloudProvider.deltaWithShared (lines 108-109) has a default impl returning CapabilityResult.Unsupported(Capability.DeltaShared, 'Provider does not support delta-with-shared enumeration'). All 6 non-OneDrive providers (Internxt, LocalFS, Rclone, S3, SFTP, WebDAV) inherit the default Unsupported — that's exactly the UD-301 declare-Unsupported-per-provider pattern this ticket prescribed as the alternative. OneDriveProvider declares Capability.DeltaShared in its capabilities() set (line 36) and overrides deltaWithShared. The ticket pre-dates the SPI contract refactor (commit 529a0d3, merged from refactor/provider-spi-contract); the refactor implicitly satisfied this ticket's acceptance. No code change needed.
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
  - core/providers
adr_refs: [ADR-0005]
opened: 2026-04-17
chunk: sg5
---
All non-OneDrive providers silently return empty. Either implement a meaningful `deltaWithShared()` or declare `Unsupported` per UD-301.

---
id: UD-005
title: ADR-0007 cites retired shell-win-v0.3.0 sub-tag example after ADR-0011 retired the tier
category: architecture
priority: low
effort: XS
status: closed
closed: 2026-05-04
resolved_by: commit 6b835f8. Already-fixed bookkeeping: ADR-0007 example list was rewritten on 2026-05-03 by the UD-771 doc-discoverability sweep (commit 6b835f8). The shell-win-v0.3.0 reference was replaced with mcp-v0.1.0 plus an explicit pointer to ADR-0011. No further work needed.
code_refs:
  - docs/adr/0007-release-versioning.md:16
opened: 2026-05-02
---
## Problem

`docs/adr/0007-release-versioning.md:16` defines component sub-tag
versioning with two examples:

> Component sub-tags are permitted as additional tags on the same
> commit when a tier needs an independently-citable build
> (e.g., `core-v0.1.0`, `shell-win-v0.3.0`). They are *not*
> primary and must match the monorepo tag's commit.

The `shell-win-v0.3.0` example references a **retired tier**:
[ADR-0011](adr/0011-shell-win-removal.md) removed `shell-win` as
a tier of the project. The example therefore advertises a
versioning surface that does not exist.

This is a small but real ADR-vs-ADR coherence drift. ADR-0007
should reflect the post-amendment state of the project's tier
list (per ADR-0011/0012/0013 — the v0.1.0 surface).

## Why this is filed under `architecture` and not folded into UD-771

UD-771 sweeps doc-discoverability and small textual drift in
non-ADR files (README links, EXTENSIONS.md misref, consequences.md
CF-API mention). ADRs are normative architecture decisions; an
ADR amendment deserves its own commit and its own audit trail in
`git log` so the reasoning ("we updated the example because the
referenced tier was retired") is searchable independently of doc
hygiene noise.

## Proposed action

Two acceptable paths:

**A) Replace the example with a surviving tier.** Pick something
like `mcp-v0.1.0` or just remove the second example (one is enough
to make the point):

> e.g., `core-v0.1.0`.

**B) Add an "as of v0.1.0" qualifier** with a backreference to
ADR-0011 explaining that the originally cited `shell-win-v0.3.0`
was illustrative for a tier that has since been retired. Keeps
the historical breadcrumb visible.

Recommend **A** for line economy; ADR-0011 already explains the
retirement and is one click away.

## Acceptance criteria

- [ ] `grep -n "shell-win" docs/adr/0007-release-versioning.md`
      returns nothing (or only a backreference to ADR-0011 if
      Option B chosen).
- [ ] ADR-0007 is consistent with ADR-0011/0012/0013 about
      which tiers exist.
- [ ] No code change required.

## Out of scope

Broader ADR-0007 review (release cadence, conventional-commit
enforcement, etc.) — this ticket is **only** the
`shell-win-v0.3.0` line. Don't scope-creep.

---
id: UD-205a
title: applyKeepBoth uploads to original remote path — Internxt 409s on exclusive-create
category: core
priority: high
effort: M
status: closed
closed: 2026-05-04
resolved_by: commit 490cca5. Already-fixed bookkeeping: UD-366 (commit 490cca5) added existingRemoteId to CloudProvider.upload and threaded it through every call site, including applyKeepBoth's NEW/NEW + remote-exists branch and applyConflict's COPY-MERGE arm. Internxt's replaceFile() (PUT /files/{uuid}) now overwrites in place; the 409 stranding pattern from the 2026-05-03 11:42 repro no longer applies.
opened: 2026-05-03
---
**`SyncEngine.applyKeepBoth` assumes `provider.upload(localPath, action.path)` overwrites whatever sits at `action.path` on remote. That's true for OneDrive (Graph PUT to drive-item path), S3 (PUT object), and WebDAV (PUT). It is NOT true for Internxt: [`InternxtApiService.createFile`](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:113) is exclusive-create and returns 409 Conflict — `{"message":"File already exists"}` — when the parent folder already contains a file with the same `plainName`. Result: every NEW/NEW conflict and every MODIFIED/MODIFIED conflict on Internxt fails permanently with KEEP_BOTH policy.**

## Repro (live, 2026-05-03 11:42)

```
sync: profile=inxt_gernot_krost_posteo type=internxt sync_root=C:\Users\gerno\InternxtDrive mode=upload
Reconciling... 243,003 / 243,003 items · 0:18
Reconciled: 106470 actions
[3/106470] mkdir-remote /Sample/02.11.…
11:42:30.826 WARN  o.k.u.sync.SyncEngine - Conflict on /AfA - …/Eigene Dokumente/Businessplan_Krost.docx: local=NEW, remote=NEW
11:42:34.339 WARN  o.k.u.http.RequestId - ← req=e745cc14 409 Conflict (673ms)
11:42:34.339 WARN  o.k.u.sync.SyncEngine - Action failed for /AfA - …/Eigene Dokumente/Businessplan_Krost.docx (1 consecutive):
                   InternxtApiException: API error: 409 Conflict - {"message":"File already exists","error":"Conflict","statusCode":409}
        at o.k.u.internxt.InternxtApiService.checkResponse(InternxtApiService.kt:473)
        at o.k.u.internxt.InternxtApiService$createFile$2.invokeSuspend(InternxtApiService.kt:141)
```

## Root cause

[`SyncEngine.applyKeepBoth` lines 983-1012](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:983) for the NEW/NEW + remote-exists branch:

```kotlin
if (action.remoteItem != null && !action.remoteItem.deleted) {
    val conflictPath = "$base$conflictSuffix"                          // .conflict-remote-TS.ext
    val conflictLocal = placeholder.resolveLocal(conflictPath)
    withEchoSuppression(conflictPath) {
        provider.download(action.remoteItem.path, conflictLocal)       // ✅ remote → local-renamed
    }
    if (action.localState == ChangeState.NEW || action.localState == ChangeState.MODIFIED) {
        if (Files.exists(localPath)) {
            val result =
                provider.upload(localPath, action.path) { … }          // ❌ local → remote ORIGINAL path
            …
            db.upsertEntry(SyncEntry(path = action.path, remoteId = result.id, …))
        }
    }
}
```

The MODIFIED/MODIFIED arm at lines ~1019-1027 has the same `provider.upload(localPath, action.path)` shape and the same hidden assumption.

The contract gap: providers' `upload()` semantics around path collision are not defined in the [`CloudProvider`](core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt) interface. Three of four providers happen to overwrite; Internxt rejects.

## Why this hadn't bitten before

Reconciler short-circuits the common NEW/NEW case at [Reconciler.kt:295-302](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:295) when `Files.size(localPath) == remoteItem.size` — emits `CreatePlaceholder` (adopt remote), no Conflict. So this only fires when sizes differ, which on a well-mannered first sync is rare. The operator's 2026-05-03 sync hits it because their state.db has 107k legacy rows from prior partial syncs and the local tree has been edited since those partials landed — sizes diverged.

`KEEP_BOTH` is also the default policy ([SyncEngine.kt:26](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:26)), so any operator on Internxt with conflicts will hit this.

## No tests cover the actual file-op semantics

Searched `core/app/sync/src/test/kotlin/`: only [`ConflictLogTest`](core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ConflictLogTest.kt) and tests that *trigger* `Conflict` actions. Nothing exercises `applyKeepBoth`'s actual download+upload sequence against any provider, real or fake. The bug is purely structural — code review would have caught the missing overwrite-vs-create contract.

## Acceptance

Pick one of:

- **(a) Asymmetric: upload local to renamed remote path.** Smallest patch. After `provider.download(remote → local-conflict-remote)`, `provider.upload(local → remote-conflict-local)`. Both sides keep their original-path file untouched; both sides gain a renamed copy of the other side's bytes. DB tracks the conflict-local upload's remoteId. Same template applies to MODIFIED/MODIFIED. Asymmetric naming (no single "winner" path) but unblocks Internxt without provider work.
- **(b) Symmetric via Internxt rename.** Confirm `PATCH /files/$uuid` with `plainName` field renames in-place (Internxt API doc check). If yes: server-side rename remote bytes to `.conflict-remote-TS.ext`, then upload local to the now-free original path. Add `provider.rename(remoteId, newName)` to the `CloudProvider` contract; Internxt impl uses PATCH; other providers fall back to a download-rename-upload three-step. Symmetric naming on both sides.
- **(c) Hash-match short-circuit + 409 graceful adopt.** Two-prong: in `Reconciler` NEW/NEW size-mismatch, fetch remote hash and compare local content hash; if equal, adopt remote (no Conflict emitted) — handles case where size encoding differs but bytes match. In `applyKeepBoth`, on 409 from upload, fall back to registering existing remote in DB and marking local MODIFIED for next round. Wider scope; biggest correctness improvement.

Each option needs:
- New `applyKeepBothTest` covering NEW/NEW, MODIFIED/MODIFIED with a `FakeCloudProvider` that throws on path collision (mirrors Internxt's contract).
- Live verification on the operator's `inxt_gernot_krost_posteo` profile against the failing path.

## Related

- **UD-712** (closed) — OneDrive 409 nameAlreadyExists for ZWJ-compound emoji filenames; provider-side handling precedent.
- **UD-205** (open, needs-verification) — apply-side atomicity. Sister concern: even with the right action emitted, half-applied conflicts leave divergent state.
- **UD-901c** (closed) — `renamePrefix` PK collision; same pattern (apply-path implementation gap not visible in tests).
- **UD-222** (closed) — Pass-2 failure aggregation + cursor-promotion guard. The fact that cursor only promotes on zero failures means the 409'd file will be retried next sync — but every retry will fail identically until this is fixed.
- **`docs/dev/lessons/pending-row-recovery-invariants.md`** — same lesson axis: a code path's quiet contract assumption (here: `provider.upload` overwrites) breaks under data shapes the original author didn't plan for.

## Surfaced

2026-05-03 11:42, while the operator was end-to-end verifying the UD-901 fix-stack against their full ~68k local tree on Internxt. One file failed with the 409; the rest of the sync continued. State.db has 107k legacy rows so the surfacing is high-volume — every conflict-eligible file in the operator's `/AfA - …/` and similar trees is at risk.

---
id: UD-240g
title: Reconciler progress + perf — silence and 86k single-row SELECTs on first-sync
category: core
priority: high
effort: M
status: closed
closed: 2026-05-04
resolved_by: commit 2e65fee. Already-fixed bookkeeping: three commits together close UD-240g — 221ac88 (log breadcrumbs), 281f8bf (bulk DB load eliminating 86k single-row SELECTs), 2e65fee (reconcile-phase heartbeat via ScanHeartbeat). All acceptance bullets verified against current Reconciler.kt.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProgressReporter.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt
opened: 2026-05-02
---
**Reconcile phase emits no log line and no reporter event between local-scan completion and `onActionCount`. On a 67k-local + 19k-remote first-sync the daemon goes silent for many seconds while running ~86k single-row SELECTs against state.db, looking exactly like a hang.**

## Failure mode (2026-05-02 session)

User ran `unidrive -p inxt_user sync --upload-only` on a 67,458-file local tree against a 19,508-item Internxt drive (first sync, no `delta_cursor`). Sequence observed:

1. Daemon log up to **15:40:38.799 — `Delta: 19508 items, hasMore=false`** ([SyncEngine.kt:663](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:663))
2. CLI showed `Scanning local files... 67,458 items · 0:07` and froze there.
3. **No further log output, no CLI update.** User assumed hang and cancelled with Ctrl+C.

## Root cause

After `gatherRemoteChanges()` returns ([SyncEngine.kt:161](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:161)) and `scanner.scan` finishes ([SyncEngine.kt:184](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:184)), the next user-visible event is `reporter.onActionCount(actions.size)` ([SyncEngine.kt:258](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:258)) → `"Reconciled: N actions"`. Between these two points runs `Reconciler.reconcile(remote, local)` ([Reconciler.kt:15](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:15)), which:

- Calls `db.getEntry(path)` once per path in `(remoteChanges.keys + localChanges.keys)` ([Reconciler.kt:28](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:28)) → ~86,000 single-row SELECTs.
- Calls `db.getAllEntries()` twice (full table scans, [Reconciler.kt:100](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:100), [Reconciler.kt:127](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:127)).
- `detectMoves` and `detectRemoteRenames` make further per-action `db.getEntry`/`db.getEntryByRemoteId` lookups.
- Emits **zero log lines and zero `reporter.*` calls** for the duration.

Two separable problems hit the same code path:

1. **Silence** — no breadcrumb in the log file, no progress event for the CLI / UI / progress.json. From the operator's perspective the daemon is hung. This is the UD-240f failure class for the reconcile phase specifically.
2. **Latency** — 86k point-lookups + 2 full-table scans is O(N) on the DB but with a ~1ms per-row overhead on Windows SQLite this dominates wall time. Bulk-load + map lookup is the standard fix; [LocalScanner.kt:35](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:35) already does the same trick (`db.getAllEntries().associateBy { it.path }`).

## Acceptance

- `Reconciler.reconcile` emits at minimum:
  - `log.info("Reconcile started: {} remote, {} local", remote.size, local.size)` at entry
  - `log.info("Reconcile complete: {} actions in {}ms", actions.size, elapsed)` at exit
- `ProgressReporter` gets an `onReconcileProgress(processed: Int, total: Int)` event, fired at 5k-item / 10s heartbeat intervals (mirroring the UD-742 ScanHeartbeat helper) from inside the main reconcile loop. `CliProgressReporter` paints `Reconciling... N / M items · 0:XX` analogous to the scan phase.
- Bulk-load `db.getAllEntries().associateBy { it.path }` once at the top of `reconcile`; replace the per-path `db.getEntry(...)` with map lookups. Same for `db.getEntryByRemoteId` lookups in `detectRemoteRenames` (build a `remoteId -> SyncEntry` map). The `db.getAllEntries()` calls at lines 100/127 reuse the same map.
- On first-sync of the user's 67k+19k workload, time-from-`Delta: ... hasMore=false` to `Reconciled: N actions` drops to single-digit seconds, and the operator sees movement in both the log file and the CLI throughout.
- Existing `ReconcilerTest` cases still pass (this is a perf + observability change, not a behaviour change).

## Related

- **Parent:** UD-240 (umbrella for long-running-action feedback). This ticket implements the reconcile-phase slice of UD-240f.
- **Pattern source:** UD-352 / UD-742 / UD-757 — the scan-phase heartbeat already does exactly what reconcile needs. Lift `ScanHeartbeat` (or a sibling `ReconcileHeartbeat`) and reuse.
- **DB-bulk-load precedent:** UD-901 introduced the pending-upload row writes in `LocalScanner`; the same scanner already pre-loads the entries map ([LocalScanner.kt:35](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:35)). Apply the same pattern.
- UD-247 (open) — cross-provider benchmark harness will need a stable reconcile baseline; doing this fix first means the harness measures the post-fix shape.

## Priority

**High** because the silent-hang UX failure is misleading enough that users cancel mid-operation (as happened in the 2026-05-02 session). The breadcrumb log lines alone (S-effort slice) close the worst of the UX gap; the bulk-load is M effort and a clear win on top.

---
id: UD-240j
title: detectMoves folder-move emits duplicate MoveRemote — missing matchedFolderCreates dedup
category: core
priority: high
effort: S
status: closed
closed: 2026-05-04
resolved_by: commit bc5ffa1. Already-fixed bookkeeping: commit bc5ffa1 added the missing matchedFolderCreates set to Reconciler.detectMoves's folder-move scan, mirroring matchedFolderDeletes. The duplicate-MoveRemote pattern from the 10:41 repro can no longer occur.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:393
opened: 2026-05-03
---
**`Reconciler.detectMoves`'s folder-move detection has no `matchedFolderCreates` set — only `matchedFolderDeletes`. Two different `DeleteRemote` actions with the same basename can both match the same `CreateRemoteFolder`, producing duplicate `MoveRemote` actions targeting the same destination, both of which fail at execution time because the source paths don't actually exist on remote.**

## Captured live (2026-05-03 10:41)

```
[1/107776] move /Sample -> /userhome/Pictures/_Photos/Sample
10:41:05 WARN Action failed for /userhome/Pictures/_Photos/Sample (1 consecutive):
              ProviderException: Item not found: /Sample
[2/107776] move /userhome/Pictures/Sample -> /userhome/Pictures/_Photos/Sample
10:41:11 WARN Action failed for /userhome/Pictures/_Photos/Sample (2 consecutive):
              ProviderException: Item not found: /userhome/Pictures/Sample
```

Two `MoveRemote` actions, **identical destination** `/userhome/Pictures/_Photos/Sample`, different sources. That's structurally impossible for a real folder move — at most one source can become the destination. User flagged this in real time: *"It tries to move a non existing remote folder hence the move was made locally. Does not make sense at all."*

The user is right. The reconciler is hallucinating moves.

## Root cause

[Reconciler.kt:393-432](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt#L393):

```kotlin
val folderCreates = actions.filterIsInstance<SyncAction.CreateRemoteFolder>()
val matchedFolderDeletes = mutableSetOf<String>()
for (del in deletes) {
    val entry = entryByPath[del.path] ?: continue
    if (!entry.isFolder || entry.remoteId == null) continue

    val oldName = del.path.substringAfterLast("/")
    for (create in folderCreates) {
        if (create.path.substringAfterLast("/") != oldName) continue
        // ↑ matches by BASENAME ONLY. No check whether `create` was already
        //   consumed by a previous outer-loop iteration.
        actions.add(SyncAction.MoveRemote(path = create.path, fromPath = del.path, ...))
        actions.remove(del)
        actions.remove(create)
        ...
        matchedFolderDeletes.add(del.path)
        break
    }
}
```

`folderCreates` is captured as a **snapshot** of the `actions` list at the start. It never updates. Two outer iterations:

1. `del.path = "/Sample"`, basename = `"Sample"`. Inner loop finds `create.path = "/userhome/Pictures/_Photos/Sample"` (basename match). Emit `MoveRemote(/Sample → /…/_Photos/Sample)`. Remove `create` from `actions` (but not from `folderCreates`).
2. `del.path = "/userhome/Pictures/Sample"`, basename = `"Sample"`. Inner loop finds the SAME `create.path` in `folderCreates` (still there, snapshot is stale). basename match. Emit `MoveRemote(/userhome/Pictures/Sample → /…/_Photos/Sample)`. `actions.remove(create)` is a no-op (already gone).

Both `MoveRemote` actions land in `actions`. Pass 1 runs them sequentially — both fail because the source folders don't exist on remote.

## Why both source paths exist as DeleteRemote actions

The user's local sync history has accumulated stale DB rows. Several syncs over time, with different sync_root paths and partial successes, left rows at `/Sample` (legacy, top-level), `/userhome/Pictures/Sample` (current after sync_root change), and likely others. The current sync sees:

- `/Sample` not on disk locally → LocalScanner marks `ChangeState.DELETED` for the DB row
- `/userhome/Pictures/Sample` not on disk locally (user moved it) → same DELETED
- `/userhome/Pictures/_Photos/Sample` IS on disk locally, NEW → emits `CreateRemoteFolder`

`resolveAction` on each DELETED-with-DB-row path emits `DeleteRemote`. Reconcile's main loop is doing the right thing.

`detectMoves` is where it goes off the rails.

## Proposal

Add `matchedFolderCreates: MutableSet<String>` and skip already-consumed creates:

```kotlin
val folderCreates = actions.filterIsInstance<SyncAction.CreateRemoteFolder>()
val matchedFolderDeletes = mutableSetOf<String>()
val matchedFolderCreates = mutableSetOf<String>()  // ← UD-240j
for (del in deletes) {
    val entry = entryByPath[del.path] ?: continue
    if (!entry.isFolder || entry.remoteId == null) continue

    val oldName = del.path.substringAfterLast("/")
    for (create in folderCreates) {
        if (create.path in matchedFolderCreates) continue   // ← UD-240j
        if (create.path.substringAfterLast("/") != oldName) continue
        actions.add(SyncAction.MoveRemote(...))
        ...
        matchedFolderDeletes.add(del.path)
        matchedFolderCreates.add(create.path)              // ← UD-240j
        break
    }
}
```

The file-move loop directly below uses the symmetric pattern with `matchedUploads` already; folder-move was the missing parallel.

## Acceptance

- A `ReconcilerTest` case pre-seeds DB with two folder rows sharing a basename (`/Sample` and `/Pictures/Sample`, both `isFolder=true`, both with `remoteId`), and a local-scan that produces a single `CreateRemoteFolder(/Pictures/_Photos/Sample)`. Calling `reconcile` produces **at most one** `MoveRemote` action targeting `/Pictures/_Photos/Sample`.
- The other `DeleteRemote` survives untouched (the engine's apply path will then propagate it as a real delete-remote, OR — better — UD-205-style it stays a Conflict for the user to resolve; but that's out of scope for UD-240j).
- Existing folder-move tests still pass.
- Live verification: the user's full upload sync no longer emits the `[1/107776] move /Sample …` + `[2/107776] move /userhome/Pictures/Sample …` duplicate pair. At most one of them becomes a MoveRemote (the more-specific one if both basenames are equal — exact tie-break left to the iteration order; document this).

## Related

- **UD-240i** (closed): refactored the file-move detection to be O(U) with a bySize map. The file-move side already has `matchedUploads` dedup. UD-240i didn't touch the folder-move side, so the bug pre-dated UD-240i and persists.
- **UD-205** (open, "atomicity across sync transfer phases"): a deeper concern is that detectMoves is matching folder names *across totally different parent paths* (basename-only). A `/Sample` at root vs `/Pictures/Sample` halfway down the tree are not the same folder. Even with UD-240j's dedup, the algorithm can pick the WRONG match. A stricter heuristic — match only when the parent path is also a sibling-rename (one common ancestor change) — would reduce false positives further. Out of scope here; file as UD-240k if desired.
- **UD-901c** (closed): renamePrefix PK collision. UD-901c made renames survive when the destination already had pending rows; UD-240j keeps detectMoves from emitting impossible renames in the first place. Pair well.

## Surfaced

2026-05-03 10:41, after UD-901a/b/c + UD-357 + UD-405 + UD-406 deployed. The user re-ran a full upload-only sync and got two MoveRemote actions targeting the same destination path. They flagged it: "It tries to move a non existing remote folder hence the move was made locally. does not make sense at all." Confirmed by code-read of `Reconciler.detectMoves` folder-move loop — missing `matchedFolderCreates` dedup.

---
id: UD-240k
title: detectMoves folder-move basename-only match picks wrong source across tree distance
category: core
priority: high
effort: S
status: closed
closed: 2026-05-04
resolved_by: commit 2baafeb. Already-fixed bookkeeping: commit 2baafeb replaced the basename-only random-pick with parent-prefix-overlap scoring in Reconciler.detectMoves's folder-move branch. The 'Item not found: /Sample' symptom from the 10:51 repro can no longer arise from the wrong-source-picked path.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:401
opened: 2026-05-03
---
**`Reconciler.detectMoves`'s folder-move detection matches `DeleteRemote` ↔ `CreateRemoteFolder` purely by basename. When multiple deletes share a basename, it picks one effectively at random (set-iteration order). With stale legacy DB rows, "random" routinely hits the wrong source — producing a `MoveRemote` from a path that doesn't exist on remote, instead of from the close-relative path that's the actual user intent.**

## Captured live (2026-05-03 10:51, post-UD-240j)

```
[1/107801] move /Sample -> /userhome/Pictures/_Photos/Sample
10:51:59 WARN Action failed: ProviderException: Item not found: /Sample
```

UD-240j stopped the *duplicate* MoveRemote (only one emitted now, not two). But the surviving one picked `/Sample` (a top-level legacy folder that no longer exists on Internxt) as the source, instead of `/userhome/Pictures/Sample` (the actual moved folder, sibling-tree to the destination). The user wanted:

```
move /userhome/Pictures/Sample -> /userhome/Pictures/_Photos/Sample     ← real move
delete /Sample                                                              ← legacy garbage
```

But got:

```
move /Sample -> /userhome/Pictures/_Photos/Sample                       ← fictional move
delete /userhome/Pictures/Sample                                           ← unintentional, also fails
```

## Root cause

[Reconciler.kt:401-404](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt#L401):

```kotlin
val oldName = del.path.substringAfterLast("/")
for (create in folderCreates) {
    if (create.path in matchedFolderCreates) continue   // UD-240j dedup
    if (create.path.substringAfterLast("/") != oldName) continue
    // ↑ basename-only match. No structural similarity check.
    actions.add(SyncAction.MoveRemote(...))
    ...
}
```

A real folder move has **structural locality**: source and destination share at least one ancestor segment. A rename in place shares the entire parent (`/A/B/Old → /A/B/New`). A cross-parent move shares the grandparent (`/A/old/X → /A/new/X`). A move that crosses *the entire tree* (`/X → /a/b/c/d/X`) is plausible only when the user has actually moved their data tree-deep — extremely rare.

Pure basename matching cheerfully accepts any two paths in the universe, ignoring structural distance. With stale DB rows accumulating over time (legacy `/Sample`, current `/userhome/Pictures/Sample`, partial-rename leftovers, etc.), false positives are routine.

## Proposal

Score candidate matches by **common-ancestor segment count** between the parents of source and destination. Pick the highest score. Require score ≥ 1 — i.e. the source and destination must share at least one parent segment.

```kotlin
// For each CreateRemoteFolder, find the best-matching DeleteRemote.
// "Best" = same basename AND longest common parent-path. Score 0
// (no shared parent segments) → not a move.
for (create in folderCreates) {
    if (create.path in matchedFolderCreates) continue
    val newName = create.path.substringAfterLast("/")
    val createParent = parentSegments(create.path)
    val bestDel = deletes
        .asSequence()
        .filter { it.path !in matchedFolderDeletes }
        .filter { it.path.substringAfterLast("/") == newName }
        .filter {
            val e = entryByPath[it.path]
            e != null && e.isFolder && e.remoteId != null
        }
        .map { it to commonPrefixSegments(parentSegments(it.path), createParent) }
        .filter { (_, score) -> score >= 1 }
        .maxByOrNull { (_, score) -> score }
        ?.first ?: continue

    // emit MoveRemote(create.path, fromPath = bestDel.path)
    ...
}
```

The loop also flips inside-out: outer = creates, inner = candidate deletes. This reflects intent — a `CreateRemoteFolder` is the "thing the user just made"; we look back for the source that BEST explains it. The previous loop did it the other direction, biasing against the user's intent when multiple stale deletes were in play.

When score ≥ 1 isn't satisfied (no candidate shares any parent segment), no move is emitted. The standalone `DeleteRemote` propagates as a normal delete; if the source path doesn't actually exist on remote, the existing 404-skip path in `applyDeleteRemote` handles it gracefully.

## Acceptance

- New `ReconcilerTest` case: pre-seed DB with TWO folder rows sharing basename — one stale far-away (`/Sample`) and one close-relative (`/Pictures/Sample`). Local `CreateRemoteFolder(/Pictures/_Photos/Sample)`. Reconcile must emit `MoveRemote(fromPath=/Pictures/Sample, path=/Pictures/_Photos/Sample)` AND a separate `DeleteRemote(/Sample)` — NOT the cross-tree move.
- New test: cross-tree-only candidates (no parent overlap with the create at all). Expect NO `MoveRemote` emitted; both `DeleteRemote` and `CreateRemoteFolder` survive untouched.
- Existing folder-move tests still pass:
  - single legitimate move (rename in place, share full parent)
  - cross-parent move that DOES share at least one ancestor segment
  - UD-240j dedup test (two deletes sharing basename → at most one MoveRemote)

## Related

- **UD-240j** (closed): dedup matchedFolderCreates so each create is consumed at most once. Necessary precondition for UD-240k — without dedup, a smart pick still gets duplicated.
- **UD-205** (open): "atomicity across sync transfer phases." Sister concern: even with the right move detected, the half-moved-DB recovery problem persists. UD-901c addressed renamePrefix; UD-205 is the broader concern.
- **detectMoves' file-move side** (UD-240i refactored): files match by SIZE, not basename. The same structural-locality argument applies — `/mnt/nas/<HASH>` matching a JPG by size alone produced false-positive moves we've seen earlier in this session. A follow-up UD-240l should apply the same parent-overlap heuristic to the file-move loop. Out of scope for UD-240k; same pattern.

## Surfaced

2026-05-03 10:51, immediately after UD-240j deployed. The user's full upload sync still showed `move /Sample → /userhome/Pictures/_Photos/Sample` as action #1 — the wrong source despite UD-240j stopping the duplicate. User identified the problem in real time: detectMoves picked an impossible source over the obvious correct one.

---
id: UD-357
title: Internxt mkdir cascade fails — create returns UUID but resolveFolder re-lists and races eventual consistency
category: providers
priority: high
effort: S
status: closed
closed: 2026-05-04
resolved_by: commit 319e4dc. Already-fixed bookkeeping: commit 319e4dc added folderCache population to InternxtProvider.createFolder; UD-368's bulk path (commit da85b27) populates the same cache. resolveFolder's segment walk hits the cache before listing, masking Internxt's read-after-write eventual-consistency window. The 'Folder not found' cascade from the 09:40 repro can no longer occur.
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:228
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:377
opened: 2026-05-03
---
**`InternxtProvider.createFolder` returns the new folder's UUID via `api.createFolder(...)` but throws it away. The next call to `resolveFolder(...)` (which walks the path segment-by-segment via `api.getFolderContents`) hits Internxt's read-after-write inconsistency window: `POST /drive/folders` succeeds, but the immediately-following `GET /drive/folders/{parentUuid}` listing doesn't yet show the just-created folder. Multi-level mkdir cascades fail with "Folder not found: X in /X".**

## Captured live (2026-05-03 09:40, scan f4d637f9)

```
[1/106458] mkdir-remote /Project Notes        ← step 1: succeeds
09:40:43.065 WARN Action failed for /Project Notes/Subfolder1:
                  Folder not found: Project Notes in /Project Notes
09:40:46.328 WARN Action failed for /Project Notes/Subfolder1/VendorH:
                  Folder not found: Subfolder1 in /Project Notes/Subfolder1
```

Step 3's failure progresses past `/Project Notes` (proving step 1 DID create it on the server, ~3 s later when Internxt's listing caught up) but fails at `Subfolder1` because step 2 — running ~milliseconds after step 1's `POST` — couldn't see the just-created `/AfA` in the listing and threw before its own `POST` ran. The cascade leaves an entire subtree never created.

This is independent of UD-405 / UD-901a / UD-901b: those land the right *action set*; UD-357 ensures the *execution* doesn't trip on Internxt's own latency.

## Root cause

[`InternxtProvider.createFolder`](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt#L228) at line 237:

```kotlin
val folder =
    try {
        api.createFolder(parentUuid, name, encryptedName)   // ← returns InternxtFolder with .uuid
    } catch (e: InternxtApiException) { ... }
return folder.toCloudItem(parentPath)
```

The `InternxtFolder` returned by the API has the new folder's `uuid`. The provider uses it only to build the `CloudItem` return value, then drops it. Subsequent `resolveFolder` calls re-discover the folder via `api.getFolderContents(parentUuid)` — exactly the call that races Internxt's listing eventual consistency.

[`InternxtProvider.resolveFolder`](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt#L377) at line 383-388:

```kotlin
for (segment in segments) {
    val content = api.getFolderContents(currentUuid)        // ← always API call
    val child = content.children.find { sanitizeName(it.plainName ?: it.name ?: "") == segment }
        ?: throw ProviderException("Folder not found: $segment in $path")
    currentUuid = child.uuid
}
```

No memoisation. Each segment is a fresh round-trip.

## Proposal — process-local folder UUID cache

Tiny `FolderUuidCache` keyed by `(parentUuid, sanitizedName) -> uuid`. Populated on `createFolder` success. Consulted at the top of each `resolveFolder` segment-walk before hitting `getFolderContents`. Cache survives the lifetime of one `InternxtProvider` instance (≈ one daemon process), no eviction needed at this scale.

```kotlin
class FolderUuidCache {
    private val map = ConcurrentHashMap<Pair<String, String>, String>()
    fun put(parentUuid: String, sanitizedName: String, uuid: String) { ... }
    fun get(parentUuid: String, sanitizedName: String): String? = ...
}

// In createFolder, after api.createFolder succeeds:
folderCache.put(parentUuid, sanitizeName(name), folder.uuid)

// In resolveFolder, top of the segment-walk:
val cached = folderCache.get(currentUuid, segment)
val childUuid = cached ?: run {
    val content = api.getFolderContents(currentUuid)
    val child = content.children.find { ... } ?: throw ProviderException(...)
    folderCache.put(currentUuid, segment, child.uuid)  // ← also cache on read
    child.uuid
}
currentUuid = childUuid
```

Caching on read AS WELL as on write means subsequent multi-segment `resolveFolder` calls don't re-list the same parents — a free latency win on top of the bug fix.

## Acceptance

- New `FolderUuidCache.kt` (or a private nested class in `InternxtProvider`) with `put` / `get(parentUuid, name)` and a unit test covering put/get/miss.
- `InternxtProvider.createFolder` populates the cache with the freshly-created folder's UUID, keyed by `(parentUuid, sanitizeName(name))`.
- `InternxtProvider.resolveFolder` consults the cache before each `getFolderContents` call; on miss, populates the cache with the discovered child's UUID after listing.
- Live verification: the user's `--sync-path '/Project Notes'` workload — which previously failed with the cascade above — now creates the entire AfA tree and proceeds to upload files.
- No regression in existing `InternxtProvider` behaviour (covered by `InternxtNameSanitizationTest`, `InternxtAuthServiceTest`, `InternxtIntegrationTest`).
- Edge case: a folder created via this cache, then deleted out-of-band (web UI, another sync session), produces a stale cache entry. Subsequent operations using the stale UUID will fail at the next API call (404). Acceptable trade for in-process cache; if it bites in practice, file a follow-up to invalidate on 404.

## Related

- **UD-901b** (closes alongside): the action set is now correct. UD-357 ensures execution doesn't trip on Internxt's own latency window.
- **UD-317** (closed): handles the symmetric case — `createFolder` returning 409 when the folder already exists. Good shape to mirror; that path also calls `getFolderContents` to recover the existing UUID, and could populate the same cache for free.
- **UD-241 / UD-330** family: throttle and retry budget. The cache also reduces total API calls under load — fewer chances to trip a 429.

## Surfaced

2026-05-03 09:40, after UD-405 + UD-901a/b were filed but before they were implemented. The user's `--sync-path '/Project Notes'` produced the right action set (CreateRemoteFolder for the AfA root + every subfolder + files) but the second mkdir failed because step 1's create wasn't visible to step 2's `getFolderContents`. Diagnosed by the inconsistency between failure messages: step 2 fails at `Project Notes` segment (depth 1), step 3 fails at `Subfolder1` segment (depth 2 — past the now-visible `AfA`). Same root cause: Internxt's listing endpoint lags create by 1-3 s.

---
id: UD-901a
title: Reconciler UD-901 upload-recovery loop ignores syncPath filter
category: core
priority: high
effort: S
status: closed
closed: 2026-05-04
resolved_by: commit fb4735c. Already-fixed bookkeeping: commit fb4735c added pathInSyncScope filtering to both the UD-225 download-recovery and UD-901 upload-recovery loops in Reconciler.reconcile. The 107k-action explosion from the 00:30 --sync-path /internal repro can no longer occur.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:127
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:99
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:163
opened: 2026-05-03
---
**`Reconciler.reconcile`'s UD-901 upload-recovery loop iterates `db.getAllEntries()` and emits `SyncAction.Upload(...)` for every pending-upload row, regardless of the `syncPath` filter. A `--sync-path /internal` invocation that should have ~100 actions ended up with 107,988 actions — including pending uploads from `/Project Notes`, `/Album2`, and every other orphan in the DB.**

## Repro (live, 2026-05-03 00:30)

```
PS C:\> unidrive -p inxt_user sync --upload-only --sync-path /internal
sync: profile=inxt_user type=internxt sync_root=C:\Users\gerno\InternxtDrive sync_path=/internal mode=upload
Reconciling... 106 / 106 items · 0:00              ← scope filter worked: 106 in-scope changes
Reconciled: 107988 actions                          ← but 107k actions appeared
[111/107988] up …Album2/IMG_001.jpg   ← out of scope
```

Reconcile main loop saw 106 paths (correct — `/internal` scope). Recovery loop added ~107k. Same symptom in UD-405's repro: `--sync-path '\Project Notes'` produced 170k actions despite reconcile-main seeing 0 remote / 0 local.

## Root cause

[Reconciler.kt:127-136](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:127):

```kotlin
// UD-901: previously-interrupted uploads leave DB entries with remoteId=null and
// isHydrated=true …
for (entry in allDbEntries) {                      // ← iterates ALL DB entries
    if (entry.isFolder) continue
    if (entry.remoteId != null) continue
    if (!entry.isHydrated) continue
    if (entry.path in coveredPaths) continue
    if (excludePatterns.any { matchesGlob(entry.path, it) }) continue
    val localPath = safeResolveLocal(syncRoot, entry.path)
    if (!Files.isRegularFile(localPath)) continue
    actions.add(SyncAction.Upload(entry.path))
}
```

Compared to the main path-walk loop above (Reconciler.kt:92-120), which only considers `(remoteChanges.keys + localChanges.keys)` — a set already filtered by the `SyncEngine.doSyncOnce` syncPath gate. The recovery loop bypasses that gate. The `excludePatterns` filter is preserved but `syncPath` is not.

There's a sibling at [Reconciler.kt:99-118](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:99) (the UD-225 download-recovery loop) with the same shape and the same syncPath bypass — it should get the same fix.

## Acceptance

- Both recovery loops (UD-225 download + UD-901 upload) accept a `syncPath: String?` parameter (passed from `SyncEngine` alongside `entryByPath`/`entryByLcPath`/`entryByRemoteId`).
- When `syncPath != null`, each loop additionally filters out entries whose `entry.path` is neither the syncPath nor a descendant of it. Same predicate as the main filter in [SyncEngine.kt:163-168](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:163):
  ```kotlin
  entry.path == syncPath || entry.path.startsWith("$syncPath/")
  ```
- Live repro: with `--sync-path /internal` and 107k orphans in DB outside that prefix, reconcile produces ≤ ~110 actions (the 106 in-scope changes + a few cross-action housekeeping additions), not 107k.
- New `ReconcilerTest` case: pre-seed the DB with 5 pending-upload rows under different prefixes (`/internal/a.bin`, `/AfA - …/b.bin`, `/AnonA/c.bin`, …); call `reconcile(remote=empty, local=empty, syncPath="/internal")`; assert exactly one `Upload` action emitted (`/internal/a.bin`).
- Existing `ReconcilerTest` cases (none currently pass `syncPath`) still pass.

## Related

- **UD-405 (filed alongside)**: enables this bug to bite Windows users via silent backslash-path scope drop. Fix landed independently.
- **UD-901b (filed alongside)**: even when in-scope, UD-901 recovery emits Upload without parent CreateRemoteFolder. Fixing UD-901a stops the bleed *outside* scope; UD-901b stops the failure *inside* scope.
- **UD-225** (closed, the download-recovery sibling) — adopt the same syncPath fix for the download recovery loop in this commit.
- **UD-901** (the parent ticket that introduced pending-upload rows in `LocalScanner`) — closed; this is a follow-up invariant violation.

## Surfaced

2026-05-03 00:30 — user ran `--sync-path /internal` expecting ~100-action work, saw 107,988 actions. Daemon's reconcile log line `Reconciling... 106 / 106 items · 0:00` (the heartbeat from UD-240g) confirmed scope filter worked at the main loop; the gap to `Reconciled: 107988 actions` revealed the recovery-loop bypass.

---
id: UD-901b
title: Reconciler UD-901 upload-recovery emits Upload without parent CreateRemoteFolder — orphans never resolve
category: core
priority: high
effort: M
status: closed
closed: 2026-05-04
resolved_by: commit be95469. Already-fixed bookkeeping: commit be95469 added ancestor-walk + CreateRemoteFolder synthesis on top of the UD-901 recovery loops. Pending uploads in trees whose parent folders were never created on remote (e.g. /Project Notes from the 00:25 repro) now get mkdir actions emitted alongside the Upload, breaking the permanent-orphan loop.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:127
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:352
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:387
opened: 2026-05-03
---
**`Reconciler`'s UD-901 upload-recovery loop emits `SyncAction.Upload(entry.path)` but never `SyncAction.CreateRemoteFolder(...)` for the parent directory tree. When a pending-upload row's parent folder doesn't exist on remote (because the original sync that produced the row was cancelled/failed before its `CreateRemoteFolder` ran), Pass 2 fires the upload, the provider's `resolveFolder` walks the path and throws `Folder not found`. **The orphan stays orphaned: every subsequent sync re-emits the same Upload, fails identically, and the row never resolves itself.** No `--reset` short of wiping `state.db` will recover.**

## Repro (live, 2026-05-03 00:25)

```
2026-05-03 00:25:37.693 WARN  o.k.u.sync.SyncEngine - Upload failed for /Project Notes/.~lock.report.xlsx#:
                              ProviderException: Folder not found: Project Notes in /Project Notes
2026-05-03 00:25:37.702 WARN  Upload failed for /Project Notes/Subfolder1/business_plan.docx: …
2026-05-03 00:25:38.057 WARN  Upload failed for /Project Notes/Subfolder1/report.xlsx: …
… (dozens more, every file in the AfA tree)
```

Daemon log shows zero `POST /drive/folders` requests during the entire scan — i.e. no `CreateRemoteFolder` action existed for these paths in the action set. The pending-upload rows came from the UD-901 recovery loop ([Reconciler.kt:127](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:127)); that loop only emits `Upload`, not the prerequisite folder creates.

## Root cause

[Reconciler.kt:127-136](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:127):

```kotlin
for (entry in allDbEntries) {
    if (entry.isFolder) continue                    // ← folders explicitly skipped
    if (entry.remoteId != null) continue
    if (!entry.isHydrated) continue
    if (entry.path in coveredPaths) continue
    …
    actions.add(SyncAction.Upload(entry.path))      // ← only Upload; no CreateRemoteFolder
}
```

The loop's contract is "resurrect any pending upload the previous run didn't complete." It correctly identifies orphan files but treats the parent-folder existence as an unstated precondition. That precondition holds iff the previous run successfully landed the parent's `CreateRemoteFolder` before crashing — which is exactly the scenario UD-901 was trying to recover from. **The recovery is incomplete by design.**

Sibling: the UD-225 download-recovery loop at [Reconciler.kt:99-118](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:99) emits `DownloadContent` but never `CreatePlaceholder` for the parent placeholder. Same shape; same bug for download orphans on a fresh disk.

## Permanent failure mode

Once a UD-901 row exists for `/X/Y/Z/file.dat` and `/X/Y/Z/` doesn't exist on remote:

1. Sync runs. Reconciler.UD-901 emits `Upload(/X/Y/Z/file.dat)`. No `CreateRemoteFolder(/X/Y/Z)` because the loop doesn't think to.
2. Pass 1 runs (no folder creates). Pass 2 runs the Upload. Provider walks `resolveFolder("/X/Y/Z")`, fails. Engine logs WARN, increments `transferFailures`, continues.
3. Cursor is NOT promoted (UD-222 invariant — any transfer failure blocks cursor promotion).
4. Next sync re-runs. Same path. Same failure. The row stays in DB, untouched. The file stays on disk, never uploaded.

`failures.jsonl` accumulates duplicate entries for the same path; the user sees uploads "fail" forever despite the local file existing.

## Acceptance

- UD-901 recovery loop synthesises the `CreateRemoteFolder` action chain for any missing ancestors before emitting `Upload`. Walk `entry.path.split('/').dropLast(1)` from shallowest to deepest, emit `CreateRemoteFolder(prefix)` for each prefix not already covered (by an existing action OR by an existing remote folder verified via cached delta state).
- Pass 1's existing sequential ordering ensures the folder creates run before Pass 2's parallel Upload. (Already correct — see [SyncEngine.kt:352-355](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:352): `sequentialActions = actions.filter { it !is DownloadContent && it !is Upload }` — `CreateRemoteFolder` is in Pass 1.)
- The action-priority sort in `Reconciler.sortActions` already places `CreateRemoteFolder` at priority 0 (before Upload at 2), so within Pass 1's sequential apply, parents will be created before children deeper in the same prefix.
- Sibling fix in the UD-225 download-recovery loop: emit `CreatePlaceholder(parent, isFolder=true)` for missing local parent dirs.
- New `ReconcilerTest` case: pre-seed DB with one pending-upload row at `/X/Y/Z/file.dat`, no remote items, no local changes; assert reconcile emits 3 `CreateRemoteFolder` (`/X`, `/X/Y`, `/X/Y/Z`) ahead of the `Upload`. Idempotency: second call with the same DB state still produces the same action set if the remote tree didn't get updated meanwhile.
- Live verification: with the user's existing pending rows under `/Project Notes/*`, after the fix lands and the daemon re-runs, the AfA tree is created on Internxt and the files upload.

## Mitigation for affected users until the fix lands

- **Manual recovery**: pre-create the missing folders on Internxt's web UI (or via API), then re-run `unidrive sync --upload-only`. Pending uploads will succeed once their parent folder exists.
- **Last-resort**: `unidrive sync --upload-only --reset` — wipes `state.db`, re-walks local files, re-emits `CreateRemoteFolder + Upload` from the main reconcile path (which DOES emit folder creates). Loses any cached delta state.

## Related

- **UD-405** (filed alongside): the silent backslash-path scope drop is what most often EXPOSES this bug — users invoke a scoped sync, get 100k+ orphan resurrections, see this failure cascade for paths they didn't ask about.
- **UD-901a** (filed alongside): the syncPath-bypass that surfaces orphans the user didn't ask about. Fixing 901a contains the bleed; fixing 901b is the actual repair.
- **UD-901** (parent, closed): introduced the pending-upload rows in `LocalScanner.scan` so the engine could survive interrupted uploads. The recovery contract was incomplete — landed without considering "what if the parent never made it to remote either."
- **UD-225** (closed sibling): the download-recovery loop has the same shape. Apply the parallel fix here.
- **UD-222** (closed): the "no NUL stub" invariant; same code area, related concerns.

## Surfaced

2026-05-03 00:25 user-session diagnostic. Confirmed by code reading + log inspection (no `POST /drive/folders` for any failing-upload path during the scan). Fix is mechanical given the action priority sort already favours folder creates ahead of uploads in Pass 1.

---
id: UD-901c
title: renamePrefix PK collision when LocalScanner pre-wrote pending row at move destination
category: core
priority: high
effort: S
status: closed
closed: 2026-05-04
resolved_by: commit 915bdb1. Already-fixed bookkeeping: commit 915bdb1 reworked StateDatabase.renamePrefix to DELETE colliding destination rows inside the same batch as the UPDATE, eliminating the SQLITE_CONSTRAINT_PRIMARYKEY failure when a UD-901 pending-upload row pre-exists at the move destination.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:232
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:876
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:72
opened: 2026-05-03
---
**`StateDatabase.renamePrefix` UPDATEs `sync_entries.path` to relocate every row under an old prefix to a new prefix. SQLite enforces PK uniqueness on `path`. When a row already exists at the destination path (because `LocalScanner` just wrote a UD-901 pending-upload placeholder there for the same file/folder the user moved locally), the UPDATE fails with `SQLITE_CONSTRAINT_PRIMARYKEY`. The `MoveRemote` action fails, Pass 1 logs `WARN`, sync continues — but the rename never lands and the DB is left half-moved (source rows still at the old prefix, pending rows still at the new prefix).**

## Captured live (2026-05-03 10:13, scan after AfA fix succeeded)

```
[1/107775] move ...78/Pictures/Sample -> /userhome/Pictures/_Photos/Sample
10:13:20 WARN Action failed for /userhome/Pictures/_Photos/Sample (1 consecutive):
              SQLiteException: [SQLITE_CONSTRAINT_PRIMARYKEY]
              A PRIMARY KEY constraint failed (UNIQUE constraint failed: sync_entries.path)
  at StateDatabase.renamePrefix(StateDatabase.kt:245)
  at SyncEngine.applyMoveRemote(SyncEngine.kt:876)
```

The MoveRemote came from `Reconciler.detectMoves` matching `DeleteRemote(/Pictures/Sample)` + `CreateRemoteFolder(/Pictures/_Photos/Sample)` by basename — i.e., the user moved the `Sample` folder locally, LocalScanner saw the absence at the old path (DELETED) and presence at the new path (NEW), and reconcile correctly detected it as a move.

## Root cause — the two-writers-one-row pattern

UD-901 (closed) added a synchronous `db.upsertEntry(...)` write inside `LocalScanner.scan` for every NEW local file ([LocalScanner.kt:72](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:72)) — the pending-upload placeholder row that survives daemon restarts.

When the user moves a folder `/Pictures/Sample` → `/Pictures/_Photos/Sample`, the local-scan pass emits two changes:

1. `/Pictures/Sample/<file>` for every existing tracked file (DELETED — gone from disk)
2. `/Pictures/_Photos/Sample/<file>` for every same file at the new location (NEW)

LocalScanner writes UD-901 pending rows for category 2 — at the NEW path. Those rows have `remoteId=null` and `isHydrated=true`.

Reconciler's `detectMoves` then matches `DeleteRemote(old)` + `CreateRemoteFolder(new)` and emits `MoveRemote`.

`SyncEngine.applyMoveRemote` ([SyncEngine.kt:876](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:876)) calls `db.renamePrefix(action.fromPath, action.path)` to rename all source rows to the destination prefix. The SQL ([StateDatabase.kt:240](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:240)):

```sql
UPDATE sync_entries SET path = ? || substr(path, ?) WHERE path LIKE ? ESCAPE '\'
```

Hits the PK constraint because the destination rows are already there.

## Failure mode

- `applyMoveRemote` throws `SQLiteException`. The action is logged `Action failed`. Sync continues.
- The remote-side move already succeeded (`api.move(...)` ran at line ~860 before the rename DB call). So **the remote tree IS correct**.
- The DB is left half-moved: source rows at old prefix (now pointing at a remote that doesn't have those files anymore), pending rows at new prefix (with `remoteId=null`).
- Next sync: source rows look like remote-deleted-but-DB-says-they-exist; reconciler likely emits redundant DeleteRemote. New rows trigger UD-901 recovery → re-upload. Same MoveRemote fires again. **Permanent failure mode** until `--reset` or manual SQL.
- `failures.jsonl` accumulates duplicate entries.

## Proposal

In `StateDatabase.renamePrefix`, **delete any pre-existing destination rows before the UPDATE**, inside a single transaction:

```kotlin
@Synchronized
fun renamePrefix(oldPrefix: String, newPrefix: String) {
    val old = if (oldPrefix.endsWith('/')) oldPrefix else "$oldPrefix/"
    val new = if (newPrefix.endsWith('/')) newPrefix else "$newPrefix/"
    conn.autoCommit = false
    try {
        // UD-901c: clear any rows at the destination prefix first. They are
        // either UD-901 pending placeholders just written by LocalScanner
        // (for the same files the source rows track) or stale from an
        // earlier interrupted move; either way, the source rows about to
        // land here carry the canonical remoteId/hash/modified.
        conn.prepareStatement(
            "DELETE FROM sync_entries WHERE path = ? OR path LIKE ? ESCAPE '\\'",
        ).use { stmt ->
            stmt.setString(1, newPrefix.removeSuffix("/"))
            stmt.setString(2, "${escapeLike(new)}%")
            stmt.executeUpdate()
        }
        conn.prepareStatement(
            "UPDATE sync_entries SET path = ? || substr(path, ?) WHERE path LIKE ? ESCAPE '\\'",
        ).use { stmt ->
            stmt.setString(1, new)
            stmt.setInt(2, old.length + 1)
            stmt.setString(3, "${escapeLike(old)}%")
            stmt.executeUpdate()
        }
        conn.commit()
    } catch (e: Exception) {
        conn.rollback()
        throw e
    } finally {
        conn.autoCommit = true
    }
}
```

Source rows' UPDATE then lands cleanly because their target paths are now empty. The atomicity ensures we never observe a half-deleted state if the JVM crashes mid-operation.

## Acceptance

- `StateDatabase.renamePrefix` no longer throws `SQLITE_CONSTRAINT_PRIMARYKEY` when rows pre-exist at the destination.
- Source rows successfully relocate to the destination, retaining their `remoteId` / `remoteHash` / `remoteModified` / `lastSynced` (the historical metadata we want to keep).
- Pre-existing destination rows are discarded (they were either UD-901 pending placeholders or stale half-moved leftovers — both meaningless after the rename).
- New `StateDatabaseTest` case: pre-seed DB with rows at BOTH `/old/file.bin` (with remoteId="src") and `/new/file.bin` (with remoteId=null), call `renamePrefix("/old", "/new")`, assert exactly one row at `/new/file.bin` with `remoteId="src"`.
- New test: rollback semantics — if the UPDATE phase throws, the prior DELETE is undone (table unchanged from before the call).
- Existing tests pass.
- Live verification: the user's `move /userhome/Pictures/Sample -> /_Photos/Sample` action runs to completion; subsequent sync is clean.

## Related

- **UD-901** (closed parent): introduced the LocalScanner pending-upload writes that this collision depends on. UD-901c is the third invariant violation in that family — UD-901a (scope filter), UD-901b (parent CreateRemoteFolder), UD-901c (rename PK conflict).
- **UD-205** (open, "atomicity across sync transfer phases" peer review): the half-moved-DB failure mode here is a concrete instance of that ticket's framing. Fixing UD-901c reduces UD-205's surface area but doesn't close it.
- **UD-203** (closed, "DB-vs-remote drift after failed action"): related class of "what happens when applyXxx throws after side-effects have already landed elsewhere." Same shape — but UD-901c is preventable, where UD-203 was about better recovery.
- **UD-240h** (open, sibling): proposes wrapping the LocalScanner walk in `db.batch { }` for perf. Independent of UD-901c, but the batch-write semantics could help if the rename's DELETE+UPDATE pair is also adopted as a sub-batch elsewhere.

## Surfaced

2026-05-03 10:13, immediately after UD-405 + UD-901a + UD-901b + UD-357 fixes deployed at 10:00. The user's first AfA-tree sync succeeded (8 files uploaded in 298.8 s, the four-fix stack working as intended). The next full sync surfaced this PK collision in the move-detection path. Different bug, different code area, same UD-901-pending-row root cause class.
