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
