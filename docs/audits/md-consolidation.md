# Markdown consolidation audit

Analysis + plan only. Nothing here is executed; a follow-up runs the approved plan.
Goal: fewer files, fewer lines, more compressed text — without losing load-bearing
information. Every disposition is re-verified against code / merged PRs / issue state,
not against the prose being audited.

Inventory: **59 `.md` files, ~18,990 lines** (`find . -name '*.md' -not -path '*/build/*'`).

Sanctioned doc surface (`AGENTS.md` §"Doc surface is bounded"): `AGENTS.md`, `README.md`,
`BACKLOG.md`, `CLOSED.md`, `BOOSTERS.md`, `docs/adr/`, `docs/audits/`, `docs/dev/specs/`,
`docs/dev/plans/`, per-module READMEs. **Outside the sanction:** `docs/dev/research/`,
`docs/dev/findings/`, `docs/dev/lessons/`, `docs/dev/ops/` — each flagged below.

## Disposition table

Disposition key: KEEP · COMPRESS→~N · MERGE→file · DELETE (superseded) · RELOCATE→dir.
"shipped" = feature is on `origin/main` and cited in `CLOSED.md`; verified against code.

| file | lines | purpose | disposition | evidence |
|---|---:|---|---|---|
| AGENTS.md | 93 | rulebook | KEEP (1 edit §11) | sanction list must add research/findings/lessons/ops decision |
| README.md | 111 | marketing front | KEEP | end-user landing; no drift found |
| BACKLOG.md | 129 | issue index + prose tail | KEEP | already migrated to a pointer index (PR #174) |
| CLOSED.md | 104 | done-work ledger | COMPRESS→~70 | append-only by rule, but pre-slim bullets are paragraph-length working notes; tighten without dropping spec/plan citations |
| BOOSTERS.md | 190 | provider-API adoption map | KEEP | live planning doc; sanctioned |
| CLAUDE.md | 1 | pointer | KEEP | one line |
| .claude/skills/*/SKILL.md (×2) | 145 | agent skills | KEEP | tooling, out of doc-surface scope |
| core/app/core/README.md | 36 | module doc | KEEP | sanctioned per-module |
| core/app/sync-tracking/README.md | 325 | module doc | COMPRESS→~150 | sanctioned but bloated; biggest per-module README, much is design narrative duplicating the tracking-set ADR/spec intent |
| core/providers/internxt/README.md | 89 | module doc | KEEP | sanctioned |
| core/providers/onedrive/README.md | 53 | module doc | KEEP | sanctioned |
| dist/README.md | 43 | installer notes | KEEP | sanctioned-adjacent (ships with dist) |
| docs/adr/capability-contract.md | 30 | ADR | KEEP | load-bearing decision |
| docs/adr/core-app-contract.md | 59 | ADR | KEEP | referenced by AGENTS.md |
| docs/adr/linux-only.md | 39 | ADR (slim-phase) | KEEP | superseded-by note already in multi-platform; AGENTS.md §47 keeps them intentionally |
| docs/adr/multi-platform.md | 40 | ADR | KEEP | the platform-scope decision |
| docs/adr/shipping-surface.md | 37 | ADR (slim-phase) | KEEP | ditto linux-only |
| docs/audits/internxt-api-vs-spi.md | 533 | reverse-eng audit | COMPRESS→~250 | sanctioned; large + dated header + UD-IDs in body (AGENTS §12 violation) |
| docs/audits/internxt-boosters-analysis.md | 388 | booster mining | COMPRESS→~200 | overlaps BOOSTERS.md; keep findings, drop narrative |
| docs/audits/internxt-notifications-feasibility.md | 65 | feasibility | KEEP | small; strip UD-ID/date in compress pass |
| docs/audits/internxt-phantom-investigation.md | 154 | investigation | KEEP (de-ID) | regression landed (CLOSED "delta path-collapse"); keep as the why-record, strip date/UD-ID |
| docs/audits/internxt-websocket-feasibility.md | 205 | feasibility | KEEP (de-ID) | feeds the notifications-WS CLOSED work; de-ID only |
| docs/dev/findings/tracking-set-internxt.md | 181 | live-verify record | RELOCATE→docs/audits/ + COMPRESS→~80 | outside sanction; CLOSED cites it as the verification record — keep but in a sanctioned dir, trim the run-log prose |
| docs/dev/findings/tracking-set-onedrive.md | 232 | live-verify record | RELOCATE→docs/audits/ + COMPRESS→~80 | same as above |
| docs/dev/lessons/json-special-chars-on-windows-fs.md | 94 | lesson | RELOCATE→docs/audits/ or sanction `lessons/` | outside sanction; load-bearing gotcha, no home in §11 |
| docs/dev/lessons/validator-pin-to-sha-not-branch.md | 65 | lesson | RELOCATE/sanction | outside sanction; **contains a dead cross-ref** (see Dead ends) |
| docs/dev/ops/security-posture.md | 144 | GH-config audit | MERGE→docs/audits/ + COMPRESS→~90 | outside sanction; overlaps SECURITY.md framing; advisory audit belongs in audits/ |
| SECURITY.md | 37 | vuln-report policy | KEEP | GitHub-standard top-level file |
| docs/env-vars.md | 40 | env reference | KEEP | referenced by CLOSED §90; small, load-bearing |
| docs/dev/plans/basic-fuse-mount-plan.md | 2196 | per-feature plan | DELETE | shipped — CLOSED "Mount-write clobbered…" cites it; mode-mutex + namespace verbs on main (#75–#78) |
| docs/dev/plans/unidrive-daemon-plan.md | 2137 | per-feature plan | DELETE | shipped — CLOSED §57 cites Tasks 1-13 + Phase 5 smoke done |
| docs/dev/plans/hydration-spi-phase-1.md | 1633 | per-feature plan | DELETE | shipped — CLOSED §50 "Hydration SPI (Phase 1)"; module `app:hydration` on main (#58) |
| docs/dev/plans/sync-progress-subscriber-set-plan.md | 1418 | per-feature plan | DELETE | shipped — CLOSED §92 cites plan + 6 commits + live smoke (#92, #96) |
| docs/dev/plans/ipc-transport-dispatcher-isolation-plan.md | 841 | per-feature plan | DELETE | shipped — CLOSED §90/§91 cite plan + 5 commits |
| docs/dev/plans/sparse-hydration-roadmap-phase-2.md | 629 | per-feature plan | DELETE | shipped — Rust co-daemon repo exists; phase-2 mount on main (#87/#91) |
| docs/dev/plans/setattr-truncate-plan.md | 420 | per-feature plan | DELETE | shipped — fix-sequence §12 "setattr… retired to CLOSED"; #69 merged |
| docs/dev/plans/mount-view-refresh-phase-1.md | 325 | per-feature plan | DELETE | shipped — CLOSED §93/§94; #86/#87/#91 merged |
| docs/dev/plans/sync-engine-stability-default-ignore-list-plan.md | 282 | per-feature plan | DELETE | shipped — CLOSED drained "default-ignore-list scanLocal"; #76/#78/#82 |
| docs/dev/plans/critical-high-fix-sequence.md | 99 | live fix-sequence | KEEP | Units 4-8 still open (#108/#115/#116/#117/#118/#119); curated test-drive procedure not in any issue |
| docs/dev/research/bug-hunt-data-safety.md | 246 | bug hunt → issues | DELETE | findings migrated: #97/#98(fixed), #102/#103/#104, #132?, namespace-R5; superseded by issues |
| docs/dev/research/bug-hunt-patterns.md | 217 | bug hunt → issues | DELETE | findings migrated: #89-family, Rust-repo items, #138/#139, #171/#122; superseded |
| docs/dev/research/bug-hunt-third-pass-opencode.md | 268 | bug hunt | DELETE | **byte-identical to third-review minus a 17-line table** (verified `diff`) |
| docs/dev/research/bug-hunt-third-review.md | 284 | bug hunt verify | DELETE | all findings now issues #88-#119; A/B1/B2/#101 fixed, rest filed; status table itself is stale (see Contradiction) |
| docs/dev/research/journald-log-sink.md | 526 | research/design | COMPRESS→~120 or DELETE | PR #120 merged "journald as primary structured log sink" — design realized; keep only the decision if not already in an ADR |
| docs/dev/research/live-sync-prior-art.md | 348 | research brief | COMPRESS→~120 | outside sanction; informs open #144 auto-poll; keep the prior-art table, drop prose |
| docs/dev/research/sync-mount-architecture-knot.md | 325 | research brief | COMPRESS→~100 | the knot is resolved (mount-view-refresh + mode-mutex shipped); keep as the why-record, heavily trimmed |
| docs/dev/research/virtual-file-layer-linux-1.md | 117 | research brief | MERGE→virtual-file-layer-linux-2 | two investigator briefs on one question; merge to one |
| docs/dev/research/virtual-file-layer-linux-2.md | 249 | research brief | MERGE (target) + COMPRESS→~150 | FUSE layer shipped (BACKLOG VFS entry "FUSE mount already delivers this"); keep Windows-CloudFiles part |
| docs/dev/specs/fuse-transparency-coverage.md | 82 | spec | KEEP | referenced by 2 open BACKLOG deferred entries + #138/#139/#140 |
| docs/dev/specs/hydration-namespace-verbs-design.md | 572 | spec | COMPRESS→~250 | shipped (#63/#64/#65) but cited by CLOSED; keep as the contract, trim task-plan residue |
| docs/dev/specs/ipc-transport-dispatcher-isolation-design.md | 225 | spec | KEEP | shipped; the contract record (CLOSED §90 cites it) |
| docs/dev/specs/mount-sync-mode-mutex-design.md | 307 | spec | COMPRESS→~150 | shipped; cited by CLOSED §75; trim |
| docs/dev/specs/mount-view-refresh-design.md | 112 | spec | KEEP | shipped contract |
| docs/dev/specs/setattr-truncate-design.md | 77 | spec | KEEP | shipped contract |
| docs/dev/specs/sparse-hydration-roadmap-design.md | 391 | spec | COMPRESS→~200 | the roadmap anchor (cited 5×); keep §Event flow + phase contracts, trim shipped phase detail |
| docs/dev/specs/sync-engine-stability-design.md | 132 | spec | KEEP | shipped contract |
| docs/dev/specs/sync-progress-subscriber-set-design.md | 450 | spec | COMPRESS→~200 | shipped; cited by CLOSED §92; trim |
| docs/dev/specs/unidrive-daemon-design.md | 421 | spec | COMPRESS→~250 | the daemon contract (cited 12×); keep, trim the future-work prose |

## Contradictions (doc vs code; code is truth)

1. **`bug-hunt-third-review.md` §"Status update" says B2 (`applyDeleteRemote`) is "Still
   live — `SyncEngine.kt:2550` catch is not `isAlreadyGone`-gated."** CODE: the catch is now
   gated — `SyncEngine.kt:2557` `if (!isAlreadyGone(e)) { … }`; tests at `SyncEngineTest.kt:3197`
   (`applyDeleteRemote transient-error gate`). Issue **#102 is CLOSED** (PR #95). **The doc is
   stale; the code/issue is correct.** (Resolved by deleting the doc.)
2. **Same status table says NEW-1 (`_events`) is "Still live — still `emit`, not `tryEmit`."**
   CODE: `HydrationImpl.kt:41` is indeed still `MutableSharedFlow` + `.emit()` (the prescribed
   `tryEmit` fix was NOT applied), yet issue **#101 is CLOSED** via PR #71 — which fixed only the
   *recovery-handle* mount-hang symptom (`HydrationImpl.kt:104` backgrounds recovery `open_write`
   and returns `Ok` immediately) rather than the general head-of-line mechanism. So the doc and the
   code agree on the mechanism, but **issue #101 is closed on a narrower fix than its own title
   describes** — the steady-state slow-subscriber HOL-block path for non-recovery handles is not
   structurally prevented. Worth re-opening or filing a follow-up; flagged, not in scope to fix here.
3. **`bug-hunt-patterns.md` finding A and `third-review` finding A claim `openSets` inner map is a
   plain `HashMap` (race).** CODE: now `ConcurrentHashMap()` at `HydrationImpl.kt:105` (and the
   per-conn maps); issue **#98 CLOSED** (PR #89). Doc stale; resolved by deletion.

## Dead ends

1. **Broken cross-reference:** `docs/dev/lessons/validator-pin-to-sha-not-branch.md:62` links
   `docs/dev/lessons/one-truth-sync-discipline.md` — **file does not exist** (only two files in
   `lessons/`). Fix on relocate/compress: drop the dead bullet or restore the target.
2. **Plans carry agentic-worker checkbox scaffolding** ("REQUIRED SUB-SKILL: use
   superpowers:…", `- [ ]` task lists) for features already shipped — instructions that no longer
   apply. Resolved by deleting the shipped plans.
3. **UD-### / dates in doc bodies** violate AGENTS.md §12 ("No IDs, dates, or version numbers …
   in document content"): `docs/audits/internxt-{notifications,phantom,websocket}-*.md` headers
   (`UD-370`, `UD-376`, `UD-306`, `2026-05-0x`), the research bug-hunt date headers, and the
   findings docs (`off main at 7f71589`, `2026-05-…`). De-ID during the compress passes.

## Aged / superseded fragments (verified)

- **All four `docs/dev/research/bug-hunt-*.md`** — migrated to issues **#88, #97–#119, #121–#173**.
  Fixed-and-closed: #97, #98, #100, #101, #102, #106, #114. The docs add nothing the issues lack
  except the verification narrative, which is itself stale (see Contradictions 1–3).
- **8 of 10 `docs/dev/plans/*.md`** — shipped; CLOSED.md cites the plan path for each
  (daemon §57, mount §75, hydration §50, subscriber-set §92, ipc-transport §90/§91, view-refresh
  §93/§94, setattr fix-seq §12, ignore-list drained). The closing-citation requirement (AGENTS §11,
  "its closing BACKLOG entry cites the spec path") is satisfied by the **spec**, not the plan — so
  the plan is disposable once the spec is kept.
- **`docs/dev/research/journald-log-sink.md`** — PR #120 merged the design; the proposal is realized.
- **`docs/dev/research/sync-mount-architecture-knot.md`** — the "knot" (sync ↔ mount mutual
  clobber + stale view) was resolved by the mode-mutex (#75) and mount-view-refresh (#86/#87/#91).
- **`docs/dev/research/virtual-file-layer-linux-{1,2}.md`** — the Linux FUSE answer shipped; BACKLOG
  VFS entry now reads "the FUSE mount already delivers this." Only the Windows-CloudFiles tier survives.
- **`BACKLOG.md` issue-number annotations** (e.g. "FIXED in #90") drift as issues close — but
  BACKLOG is now a thin index over GitHub Issues, so the issue state is authoritative; no doc fix needed.

## Proposed end-state

Target tree (sanctioned dirs only; `research/findings/lessons/ops` collapsed):

```
AGENTS.md  README.md  BACKLOG.md  CLOSED.md  BOOSTERS.md  CLAUDE.md  SECURITY.md
docs/env-vars.md
docs/adr/         (5 — unchanged)
docs/audits/      (5 internxt + security-posture + 2 tracking-set live-verify   = 8)
docs/dev/specs/   (11 — kept, several compressed)
docs/dev/plans/   (1 — critical-high-fix-sequence only)
core/**/README.md (5 — unchanged, sync-tracking compressed)
.claude/skills/   (2 — out of scope)
```

Estimated totals:
- **Files: 59 → ~38** (delete 8 plans + 4 bug-hunts + merge 1 VFS brief = −13; relocate 4 out of
  unsanctioned dirs into `docs/audits/`, net file count unchanged by relocation; `journ​ald` either
  −1 or compressed).
- **Lines: ~18,990 → ~10,500** (plans −8,955 of 9,980; bug-hunts −1,015; the rest from compress
  passes on specs/audits/CLOSED/research nets roughly −2,500 against ~+1,000 retained).

**AGENTS.md §11 edit required.** The sanction list omits `research/`, `findings/`, `lessons/`,
`ops/`. After this audit those dirs are emptied/relocated into `docs/audits/`. Either (a) add one
clause sanctioning a single catch-all (recommend folding live-verify + lessons + security-posture
under `docs/audits/` and stating audits hold "reverse-engineering, live-verification, and advisory
notes"), or (b) leave §11 as-is if every survivor lands in an already-named dir (the relocations
above do exactly that — so the **minimal** edit is none, provided the follow-up relocates rather
than sanctions new dirs). Pick (b) for least churn; note it in the execution plan.

## Execution plan (ordered, low-risk first)

Safe deletions/merges first; judgment-heavy compressions last.

1. **[HIGH]** Delete the 8 shipped plans (keep `critical-high-fix-sequence.md`). Pure removal;
   each is cited-as-shipped in CLOSED.md + a merged PR. `−8,955` lines. *Confidence: HIGH —
   verified per-plan against CLOSED citations and code symbols on main.*
2. **[HIGH]** Delete `bug-hunt-third-pass-opencode.md` (byte-identical-minus-table duplicate of
   third-review). `−268`. *HIGH.*
3. **[HIGH]** Delete the remaining 3 bug-hunt docs (`data-safety`, `patterns`, `third-review`) —
   all findings are issues #88–#119; do NOT carry the stale status table forward. `−747`. *HIGH.*
4. **[LOW]** Fix the dead cross-ref in `validator-pin-to-sha-not-branch.md:62` (drop the
   `one-truth-sync-discipline.md` bullet) as part of its relocation. *HIGH (mechanical).*
5. **[LOW]** Relocate `findings/*` (×2), `lessons/*` (×2), `ops/security-posture.md` into
   `docs/audits/`; remove the now-empty `research/findings/lessons/ops` dirs where applicable.
   No AGENTS §11 edit needed if all land under sanctioned `docs/audits/`. *HIGH.*
6. **[LOW]** Merge `virtual-file-layer-linux-1.md` into `-2.md`; keep the Windows-CloudFiles
   half, drop the shipped-Linux half. `−~200`. *MEDIUM — needs a read to dedup the two briefs.*
7. **[MEDIUM]** Decide `journald-log-sink.md`: if PR #120's decision is captured anywhere
   load-bearing (ADR or code comment), DELETE; else COMPRESS→~120 (keep the decision). *MEDIUM —
   confirm the decision has a home before deleting.*
8. **[MEDIUM]** Compress the surviving research briefs (`live-sync-prior-art`,
   `sync-mount-architecture-knot`) to why-records. *MEDIUM — judgment on what stays load-bearing.*
9. **[MEDIUM]** Compress the large shipped specs (`unidrive-daemon-design`,
   `sparse-hydration-roadmap-design`, `sync-progress-subscriber-set-design`,
   `hydration-namespace-verbs-design`, `mount-sync-mode-mutex-design`) — keep the contract +
   §Event-flow / invariants, drop the task-plan residue. These are the closing-citation anchors,
   so they MUST survive in some form. *MEDIUM — re-verify each is still cited before trimming.*
10. **[MEDIUM]** De-ID + compress the audits (`internxt-api-vs-spi`, `internxt-boosters-analysis`,
    the 3 feasibility/investigation notes) and `sync-tracking/README.md`. Strip UD-IDs/dates per
    AGENTS §12. *MEDIUM.*
11. **[LOW]** Compress `CLOSED.md` pre-slim bullets to one line each, preserving every spec/plan
    path citation (those are the grep anchors AGENTS §11 depends on). *MEDIUM — append-only rule
    means don't drop entries, only tighten prose.*
12. **[follow-up, not docs]** File or re-open the #101 narrowness flag (Contradiction 2):
    steady-state non-recovery slow-subscriber HOL-block is unfixed though the issue is closed.
    *Flag for the maintainer — out of scope for a docs PR.*
