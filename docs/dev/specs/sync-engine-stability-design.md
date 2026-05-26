# Sync-Engine Stability — Design Spec

**Version:** 2
**Status:** Draft — for implementation planning
**Revision history:** v2 — addressed implementation-review findings: SP1 exclude-layer consolidation (the hardcoded trash/versions live at `SyncEngine.kt:119`, not `SyncConfig`); SP2a references the existing `StreamingReconcileBuffer` prior art + batches the probe fallback; SP2b front-loads empirical verification of the Internxt `/files` lag (the ≥60 min figure is one observation, not a measured bound); SP3 carries the full `sync`-flag parity list + an explicit reconcile-guard / scheduler / subscription-renewal coalescing policy.
**Supersedes/relates:** `unidrive-daemon-design.md` (amends the G3 "strictly reactive" contract), `sync-progress-subscriber-set-design.md` (reuses its progress channel), `mount-sync-mode-mutex-design.md` (retires its scaffolding).

## Goal

Make the unidrive sync engine trustworthy on the two test accounts: remote changes (deletions, renames, creates) reflect on the FUSE mount **automatically**, remote deletions are detected **correctly per provider**, and desktop/OS junk is **never synced** to the cloud. Validated end-to-end on the two test accounts.

## Scope & accounts

**In scope** (create/delete freely):
- `internxt_test` — 19notte78@gmail.com
- `posteo_onedrive` — gernot.krost@gmail.com

**Out of scope:**
- The **primary** Internxt profile (`internxt_gernot_krost_posteo`, gernot.krost@posteo.com). Remote deletions on it are **forbidden**. Its legacy `SyncEngine` UD-265 delete-safeguard is currently tripped (a pass wanted to delete 150 files); **that the safeguard holds is the correct, intended behavior** — it protects real data. This effort does **not** force-unblock it (no `--force-delete`), does not raise its `maxDeleteAbsolute`, and does not migrate it. The "UD-265 unblock" backlog framing is reinterpreted here as "the safeguard correctly forbids deletion on the primary" and is closed for this effort.
- The **tracking-set engine** migration (a separate Critical effort). This spec hardens the **legacy `SyncEngine` + the daemon**; it does not replace them.
- The `--poll-interval` band-aid (the cheaper alternative to Sub-project 3) — explicitly rejected in favor of the durable reconcile-in-daemon approach.

## Architecture — three sequenced sub-projects

Each sub-project is independently shippable and testable. Dependency: **SP2 is a prerequisite of SP3** — a continuous reconcile loop running on broken deletion detection is worse than today's reactive model (it would loop on the OneDrive delta bug or mis-delete). SP1 is independent and ships first as a low-risk warm-up.

```
SP1 (ignore list) ──┐
                    ├──> ship independently
SP2 (delete-detect) ──> SP3 (reconcile-in-daemon)
```

---

### Sub-project 1 — Default ignore list

**Problem.** The default `SyncConfig.effectiveExcludePatterns(profile)` contains only `/.unidrive-trash/**` and `/.unidrive-versions/**`. Desktop environments and editors write transient junk into directories — KDE's `.directory.lock`, `.DS_Store`, `Thumbs.db`, `desktop.ini`, office lock files `~$*`, partial-download `*.part`, editor swap `*.swp`, temp `*.tmp` — and unidrive pushes all of it to the cloud (the observed `.directory.lock` upload storm).

**Design.** Extend the **default** exclude set with a curated junk list. Keep the existing per-profile config override (`general.exclude` / per-profile) intact and additive — user patterns extend, never replace, the defaults unless an explicit opt-out is provided. The default set:

| Pattern | Source |
|---|---|
| `**/.directory.lock` | KDE Dolphin |
| `**/.DS_Store`, `**/._*` | macOS |
| `**/Thumbs.db`, `**/desktop.ini`, `**/ehthumbs.db` | Windows |
| `**/~$*` | MS Office lock files |
| `**/*.part`, `**/*.crdownload` | partial downloads |
| `**/*.swp`, `**/*.swx`, `**/.*.sw?` | vim swap |
| `**/*.tmp`, `**/*~` | generic temp / backup |

**Boundaries + placement (review finding 1).** Today exclusions live in three uncoordinated layers: TOML global + per-profile via `SyncConfig.effectiveExcludePatterns(provider)`; CLI `--exclude` folded in at `SyncCommand.kt:424`; and **then** the engine hardcodes `/.unidrive-trash/**` + `/.unidrive-versions/**` at `SyncEngine.kt:119` — so those two are invisible to `doctor` and to `effectiveExcludePatterns`. This sub-project **consolidates**: add the junk list **and** the trash/versions patterns as a `SyncConfig.DEFAULT_EXCLUDE_PATTERNS` companion, fold them into `effectiveExcludePatterns` so a single set flows uniformly to `LocalScanner`, `Reconciler`, and `doctor`, and **remove** the hardcoded list from `SyncEngine.kt:119`. Reuse the existing `Reconciler.matchesGlob` matcher (already handles `**/`, `*`, `?`) — no new matcher. The CLI/TOML user layers stay additive on top of the defaults.

**Acceptance.** Creating any listed file in a sync_root or via the mount does not upload it (verified: it never appears in a provider listing); an explicit user-config pattern still works additively; a file NOT on the list still syncs.

---

### Sub-project 2 — Delete-detection correctness

A continuous reconcile loop (SP3) demands that "the remote no longer has X" be computed correctly. Two provider-specific defects block that.

**2a. OneDrive — "not in delta, marking deleted" churn loop.**
The full-sync codepath enumerates OneDrive's delta and concludes a path the engine *itself just uploaded* is gone, queues a deletion, then re-uploads it next cycle — steady-state churn that re-uploads the file every minute and pollutes the delta stream. Hypothesised root cause (ordered): (a) the delta page is read before OneDrive has indexed the just-completed `PUT` (Graph delta is eventually consistent vs the item-write endpoint); (b) cursor pagination mid-stream — the item lives on a later page the full-sync path doesn't drain before declaring "not in delta"; (c) local `mtime` racing the upload completion.

*Design:* first **confirm the root cause** by capturing one instance with TRACE-level Graph delta logging (delta-page contents + the upload `req=` timing). **Prior art (review finding 2):** `SyncEngine` already defers deletion-bearing actions to scan-end via `StreamingReconcileBuffer` (`SyncEngine.kt:94-98`), and `detectMissingAfterFullSync` (`SyncEngine.kt:2010`) only emits the "not in delta" verdict at scan-end — so the race is purely the *next* pass seeing a just-uploaded path before the delta has indexed it. The fix extends this with a **recently-written guard**: an in-memory set of paths the engine successfully uploaded this session, consulted by `detectMissingAfterFullSync` so such a path is not marked-deleted on the immediately-following pass; if still absent on a later full drain it may be reconsidered. A `getItemById` existence probe is the fallback if the guard proves insufficient — but it costs a provider round-trip **per candidate-deleted path**, so it must be **batched**, and the in-memory guard (no round-trip) is preferred. The fix must converge (no permanent re-upload) and must not suppress a genuine remote deletion beyond one extra pass.

**2b. Internxt — `/files` status lags a web-UI trash by ≥60 min.**
Deletion detection relies solely on the flat `/files` listing's `status` field, which lagged a confirmed web-UI trash by ≥60 min (the folder-contents endpoint reflected it immediately). So an Internxt remote-delete stays invisible to unidrive until that index flips.

*Design:* **first verify the dependency empirically (review finding 3)** — the ≥60 min figure is a single prior observation, not a measured bound, and the fix rests on Internxt's eventual-consistency behaviour. On `internxt_test`: trash a file via the web UI, then measure how long `/files` (`status=ALL` on incremental, `EXISTS` on initial — `InternxtProvider.kt:1017`/`:1105`) keeps reporting it present, and confirm `getFolderContents` (`/folders/content/{uuid}`) reflects the trash promptly. Only once confirmed, switch deletion detection to **cross-check the folder-contents endpoint** (what `unidrive ls` already uses) rather than relying on the lagging `/files` status. The engine already has the folder-contents path; the change makes the deletion-detection gather consult it. Keep `updatedAt`/incremental-cursor as the fast path; folder-contents cross-check is the authority for "is this still present."

**Acceptance** (both test accounts): a file trashed remotely is detected as gone within **one reconcile pass** (not ≥60 min); a just-written file is **not** spuriously re-uploaded (no churn loop); a genuine deletion is still propagated.

---

### Sub-project 3 — Reconcile-in-daemon (continuous remote-change reflection)

**Problem.** Per `unidrive-daemon-design.md` G3 ("strictly reactive — only acts when an IPC verb arrives") + NG5 ("no persistent refresh cursor"), the daemon never re-enumerates on its own. So remote changes are invisible on the mount until the operator runs `unidrive refresh`. `unidrive sync` still owns its own reconcile loop + IpcServer in a separate process (the transitional state the `mount-sync-mode-mutex` scaffolding exists to police).

**Design.** Move reconcile-loop ownership into the daemon:
- The daemon hosts the reconcile loop as a **scheduled in-process task**, mirroring `SubscriptionRenewalScheduler`'s pattern (a coroutine on the daemon scope firing at an interval, default e.g. 60s, configurable, 0 = off). Each fire runs Gather → Reconcile → apply (the existing `syncOnce` machinery), now in the daemon.
- `unidrive sync` becomes a **thin RPC client**: new `sync.run` / `sync.cancel` verbs; the CLI issues `sync.run` then subscribes to progress via the existing **subscriber-set** channel (`sync-progress-subscriber-set-design.md`) and renders to the terminal. All current `sync` flags become RPC parameters with **per-flag parity (review finding 4)** — the full set from `SyncCommand`: `--dry-run`, `--watch`, `--full-tree`/`--allow-full-tree`, `--download-only`, `--upload-only`, `--ignore-top-level-guard`, `--force-delete`, `--exclude`, `--fast-bootstrap`, `--propagate-deletes`, `--no-stream`, `--sync-path`. Exit semantics + stdout stay byte-for-byte equivalent; a parity test covers each flag.
- The **G3 contract is amended**: the daemon gains a scheduled-reconcile exception (spec update to `unidrive-daemon-design.md`). NG5's "no persistent cursor" is reconsidered — the scheduled loop reuses the existing per-pass delta drain; persistent-cursor is **not** required by this sub-project (each pass re-drains, same as `refresh.run` today).
- The `mount-sync-mode-mutex` / zombie-mount-detector scaffolding becomes **deletable** once the daemon serialises everything per-profile internally — remove it in the same effort (or a one-line follow-up).

**Result.** Remote deletions/renames/creates reflect on the mount within one reconcile interval, no manual `refresh`. Builds on SP2 (the loop's deletion detection must be correct).

**Boundaries.** The reconcile loop is a daemon-side scheduled task with one job (run `syncOnce` on schedule + on `sync.run`). The RPC surface (`sync.run`/`sync.cancel`) is a thin adapter over the existing engine. Progress streaming reuses the existing subscriber-set — no new channel.

---

## Data flow & IPC

```
unidrive sync [flags]  ──sync.run{params}──▶  daemon
        ▲                                      │ runs syncOnce (Gather→Reconcile→apply)
        └──── progress events ◀── subscriber-set ──┘
FUSE mount ──hydration.* verbs──▶ daemon (unchanged); sees a continuously-reconciled state.db
scheduled timer (interval) ──▶ daemon fires syncOnce in-process
```

## Error handling & adversarial design (red-team)

- **Daemon crash mid-reconcile** → `state.db` writes are transactional (existing); the next scheduled pass resumes. No partial-apply corruption (the existing apply-phase recovery loops cover unhydrated / no-remoteId rows).
- **Delta cursor expiry / 410 Gone** → existing self-heal (full re-enumeration) applies inside the scheduled loop.
- **Concurrent triggers — scheduled fire, `sync.run` RPC, and subscription-renewal (review findings 5 & 6)** → ONE per-profile reconcile in-flight guard governs ALL reconcile triggers (mirroring `RefreshRpcHandler`'s `AtomicReference`). Policy: (a) an in-flight reconcile makes a new trigger **coalesce** — `sync.run` returns/awaits `busy` rather than starting a second pass; a scheduled fire that finds one in-flight is **skipped** to the next interval; (b) an explicit `sync.run` **resets the scheduled timer** (next auto-fire is one interval later); (c) `SubscriptionRenewalScheduler` and the reconcile loop both run on the daemon scope and both can hit the provider's delta endpoint — they must NOT enumerate concurrently: gate renewal behind the same per-profile reconcile lock, or sequence it outside the reconcile critical section. Mount hydration verbs continue concurrently (open-set/cache, not the reconcile transaction) but must not race a delete-apply on the same path — reconcile takes the path lock the apply already uses.
- **Delete-detection false-positive** (SP2 regression) → the **UD-265 max-delete safeguard stays in force** as the backstop; a runaway delete batch still trips it rather than destroying data. (This is why the safeguard is never weakened.)
- **Reconcile loop on still-broken detection** → mitigated structurally by sequencing SP2 before SP3.
- **Ignore-list too broad** → conservative, well-known patterns only; per-profile override; `doctor` surfaces excluded-but-present files so a mistaken exclude is visible.
- **OneDrive delta eventual-consistency** (SP2a) → the recently-written guard prevents the self-inflicted delete; a genuine deletion still propagates within one extra pass.
- **Scheduled interval too aggressive** → default conservative (≥60s), configurable, 0=off; the reconcile is idempotent so overlapping fires are guarded (in-flight guard).

## Testing strategy

- **SP1:** unit tests for the default-exclude matcher (each pattern matches/▷ doesn't over-match); live smoke — create each junk file on both test accounts' mounts, assert it never reaches the provider.
- **SP2:** OneDrive — a test reproducing the upload-then-delta-miss cycle (TRACE-captured), asserting no re-upload churn + the recently-written guard; Internxt — a test that a folder-contents-visible trash is detected as gone while `/files` still lags. Live: trash a file via each provider's web UI, assert detection within one pass.
- **SP3:** unit tests for the scheduled loop (fires on interval; in-flight guard coalesces; `sync.run`/`sync.cancel` semantics); `sync` CLI parity tests (flags → RPC params, stdout/exit unchanged); live — delete/rename a file remotely on both test accounts, assert the mount reflects it within one interval with no manual refresh. The 5+5+2 live-smoke target is the backdrop.

## Out of scope (restated)

- Primary profile, UD-265 force-unblock, `maxDeleteAbsolute` changes.
- Tracking-set engine migration.
- `--poll-interval` (superseded by SP3).
- Persistent delta cursor in the daemon (each pass re-drains; revisit only if interval cost is shown to matter).

## Risks / open questions

- **OneDrive delta root cause (SP2a)** is hypothesised, not confirmed — the fix shape depends on the TRACE investigation; the plan must front-load that diagnosis before committing to the guard-vs-probe fix.
- **Reconcile ↔ mount serialisation (SP3)** is the highest-risk interaction; the path-lock discipline must be verified against the existing hydration write path.
- **`sync` CLI parity (SP3)** — migrating every flag to an RPC param without behavioural drift needs a parity test per flag.
