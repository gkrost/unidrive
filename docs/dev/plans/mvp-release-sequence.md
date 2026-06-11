# MVP release sequence — OneDrive + Internxt must work flawlessly

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` to execute each unit task-by-task. One unit → one branch → one PR → merge → next. Strictly sequential; never two open PRs at once.

**Goal:** Bring both providers (OneDrive, Internxt) to a flawless sync + mount state across the JVM core (`unidrive`), the Rust FUSE co-daemon (`unidrive-mount-linux`), and the Windows **read-only** CfAPI tier (`unidrive-windows`), then validate each surface against `docs/dev/specs/mvp-acceptance-criteria.md` (#342) and cut a release.

**Approach:** This supersedes and extends `critical-high-fix-sequence.md` (its Units 1–8). Ordering is dependency- and data-risk-first, then provider correctness, then mount POSIX correctness, then CLI trust, then validation + release. Cross-repo and shared-file edits force strict serialization — which is also the user's hard constraint (no parallel PRs).

**Repos / engine:** `unidrive` (Kotlin/JVM — legacy `SyncEngine` is the MVP engine; tracking-set frozen for MVP, decision #343), `unidrive-mount-linux` (Rust FUSE co-daemon), `unidrive-windows` (C#/.NET CfAPI client — read-only tier in MVP, #290), `unidrive-dist` (packaging; Windows channel = unidrive-dist#12). Test accounts: `internxt_test`, `posteo_onedrive`. The **primary** Internxt profile (`internxt_gernot_krost_posteo`) is read-mostly — **remote deletions forbidden; never a destructive test target.**

---

## Ground-truth snapshot (verified 2026-05-27)

Both repos clean on `main`, up to date with origin. Open issues: **67** (`unidrive`) + **15** (`unidrive-mount-linux`).

### Already landed — do NOT re-sequence (verified in code, not just commit titles)

- **Unit 1** create-collision keep-both (no blind PUT-overwrite) — `OneDriveProvider` `conflictBehavior=rename` + `InternxtCreateCollisionTest`. ✅
- **Unit 2** tracking-set cursor-resume pending-delete — `TrackingCursorResumePendingDeleteTest` + guard. ✅
- **Unit 4 (topo-sort half)** apply-phase parent-before-child ordering — commit `cbe79a1`. ✅ (XDG half still open = #115)
- **Unit 6.1** "not in delta, marking deleted" churn — **#119 CLOSED** (accepted/resolved; log line at `SyncEngine.kt:2207` is intentional debug).
- **Unit 6.2** OneDrive `downloadUrl` re-resolve on stale/HTML — #176, #181. ✅
- **ML #27 fix is PRESENT in code** — `HydrationImpl.kt:99-112` backgrounds `recovery-` uploads on `recoveryUploadScope`, returning immediately instead of blocking before `mount()`. → treat as **VERIFY-FIRST** (live smoke to confirm + close), not a dev effort.
- daemon (`run/status/stop` + IPC), mount-view-refresh Phase 1+2 (`sync.enumerate`, `--poll-interval`), hydration namespace verbs (`mkdir`/`unlink`/`rmdir`), setattr/truncate, default-ignore surfaces (a)+(b) — all landed (see specs corpus).

---

## Scope criterion

Include a unit if a normal user doing normal sync or mount with OneDrive or Internxt would otherwise hit: **wrong data, data loss/overwrite, a hang/crash, an unmountable/unusable mount, a silent wrong-sync, or a guard that wrongly fires/passes on a routine op.** Plus the release-gate items (artifact + acceptance smoke).

Defer: pure efficiency optimizations (unless they cause wholesale abort), daemon-decomposition phases 2/3 (#141/#142), pure observability/UX/CLI polish, test-coverage-only tasks, build hygiene.

## Scope decisions (locked)

- **Tracking-set engine is OUT of the MVP** (decision #343, reversing the earlier lock). The legacy `SyncEngine` is the only engine the MVP certifies; ts receives data-safety fixes only and graduates post-MVP behind its own epic. #108's resolution falls back to the plan's documented alternative — a one-shot, operator-supervised force-delete on the legacy engine (**no destructive op on the primary profile without explicit operator go-ahead**). Phase H moves to Deferred; #118-ts follows it.
- **Windows read-only tier is IN the MVP** (decision #290): daemon + CLI sync + read-only CfAPI mount (`unidrive-windows` Phases 0–1 incl. 1.4/1.5 verification, unidrive-windows#3/#4). Writeback / shell UX / MSIX are post-MVP (unidrive-windows#5/#6/#7).
- **Acceptance is mechanical:** every surface gates on `docs/dev/specs/mvp-acceptance-criteria.md` (#342), instrumented by `unidrive verify` (#341). Soaks overlap with interactive work on the next surface by design.
- **Include everything incl. webhooks.** Phase E (mount POSIX correctness), Phase F (NFC + mount freshness), and C5 (#111 OneDrive webhooks) are all in MVP.
- Still deferred: pure efficiency optimizations, daemon-decomposition phases 2/3 (#141/#142), pure observability/UX/CLI polish, test-coverage-only and build-hygiene tasks (except #175 if it blocks the suite), and mount perf/hygiene (ML #31/#32/#33/#36-#43).

---

## The sequential PR chain

Each unit = one branch (`fix/...`), one PR, merged before the next starts. Every unit's acceptance includes a **live test-drive on both test accounts** (provider-specific units note the single relevant account). Verify every subagent commit with `git log`/`git branch --contains` before merging.

### Phase D0 — Data-safety batch (deep-review criticals; before everything else)
The production-readiness review (#287–#340) surfaced data-loss paths *beneath* the existing phases. One PR each, in this order (shared `SyncEngine.kt`/provider surfaces force serialization):
- **D0.1 · #289** (Critical) — temp-file + fsync + `ATOMIC_MOVE` for every download path (both providers, hydration cache); never restore a placeholder over real bytes.
- **D0.2 · #287** (Critical) — OneDrive delta tombstones: deletion facets short-circuit the vault/root filters; surface tombstones by id.
- **D0.3 · #291** (High) — If-Match plumbing engine→upload; ≤4 MiB PUT precondition; 412 → collision.
- **D0.4 · #296** (High) — detectMoves: require basename (+hash when available) before pairing delete+upload.
- **D0.5 · #294** (High) — Internxt `getMetadata`: exact-name match across the listing before any base-name fallback (wrong-sibling delete/download/move).
- **D0.6 · #297** (High) — unhydrated-row local edits: divergence check before the recovery download.
- **D0.7 · #298** (High) — dry-run contractually side-effect-free (no `updateRemoteEntries`, no cursor promotion, no scanner pre-writes).
- **D0.8 · #301** (High) — dehydrate/reap refuse cache eviction while an upload slot is live or the row is dirty.

*(#288/#292/#299/#302 are ts-engine — frozen per #343, handled in the post-MVP ts epic.)*

### Phase P — Portability floor (parallel with D0; no shared files)
- **P1 · #344** — `windows-latest` CI: `./gradlew check` + daemon start→stop AF_UNIX round-trip (advisory; guards closed #239, exercises #327).
- **P2 · #345** — IPC `protocol_version` + golden NDJSON contract corpus (companions: unidrive-windows#8, unidrive-mount-linux#70).

### Phase 0 — Verify gate (no/tiny code)
- **V1 · ML #27** (Critical) — live OneDrive mount smoke (`unidrive -p posteo_onedrive mount`): confirm mount completes in ~5s. Code shows the claimed fix present. If green → close #27 (+ its mount BACKLOG line). If it still hangs → promote to a real fix as unit A0 (strace the co-daemon startup; surfaces `mount/src/{main,ipc,reconnect}.rs` + JVM `HydrationIpcHandler` subscribe/list).

### Phase A — Mount/IPC correctness (cross-repo)
- **A1 · ML #28** (High, correctness) — `ReconnectingIpcClient` blind-replays non-idempotent verbs (`rename`/`create`/`unlink`/`mkdir`/`rmdir`/`open_write`) on `IpcError::Io` → spurious `ENOENT` on an `mv` that already succeeded. Surface: `mount/src/reconnect.rs` (split read vs mutate) **+ JVM `HydrationIpcHandler` request-id dedupe** (cross-repo). Test-drive: mid-op disconnect, assert no spurious errno on a succeeded mutation.

### Phase B — Sync apply-phase storms (legacy, both providers)
- **B1 · #115** (High, correctness) — XDG-user-dir locale aliasing (`Bilder`↔`Pictures`…) → duplicate trees / 409 / "Folder not found" (~3 000 spurious mkdirs observed). Surface: `SyncEngine.kt` apply phase. Test-drive: German `Bilder/` vs cloud `Pictures/` → 0 dupes, 0 409.
- **B2 · #116** (High, correctness) — `--fast-bootstrap` emits `mkdir-remote` for local folders already on cloud → N×409. Reuses B1's adopt-on-name-match. Surface: `SyncEngine.kt` apply phase. Test-drive: `sync --reset --fast-bootstrap` → 0 409, N quiet adoptions.
  - *Shares `SyncEngine.kt` apply phase with B1 → serialize B1 then B2.*

### Phase C — OneDrive provider correctness (posteo_onedrive smoke only)
- **C1 · #110** (High, correctness) — 410 Gone resync handling on the **legacy `SyncEngine`** delta loop (`resyncChanges*`). NOTE: the *tracking-set* 410 self-heal already shipped (CLOSED.md:101, commit `09e9891`); #110 is the still-open legacy-engine variant — reuse the tracking-engine recovery shape, don't reinvent.
- **C2 · #112** (High; labeled efficiency but it's change-detection correctness) — use OneDrive `file.hashes` in the local change detector to stop re-upload churn.
- **C3 · #113** (High, correctness) — `If-Match` precondition on `createUploadSession` (catches concurrent mid-flight edit before the session URL is handed out). Surface: `GraphApiService.createUploadSession` (verified). NOTE: If-Match on `moveItem` + `uploadSimple` conflictBehavior=replace parity already shipped (CLOSED.md:82); #113 is the uncovered session-create path only.
- **C4 · #183** (Medium, correctness) — OneDrive minimal soft-removed tombstone leaves an orphan DB row (no path to resolve) → state pollution.
- **C5 · #111** (High, reliability) — OneDrive webhook lifecycle + validation endpoint. **In MVP** (push freshness below the poll interval). Surface: OneDrive provider + a daemon-side validation HTTP endpoint; coordinate with the auto-poll path.

### Phase D — Sync data-safety guards & state consistency (legacy)
- **D1 · #137** (Medium, correctness) — pre-reconcile empty-`sync_root` guard must distinguish rehydrate intent from a wrong/misconfigured `sync_root`. Data-safety guard. Surface: `SyncEngine.kt` pre-reconcile.
- **D2 · #135** (Medium, correctness) — socket-path name hashing diverges from hydration-cache-root naming for long profile names → mount breaks for long names.
- **D3 · #149** (Low, reliability) — hydration-cache file deleted inside the reap `db.batch{}` (filesystem op inside a DB transaction) → atomicity hazard.
- **D4 · #147** (Low, efficiency/reliability) — `StateDatabase.batch{}` holds the `@Synchronized` lock across a full enumeration → `mount ls` stalls during `enumerateRemoteIntoState(reset)`.
- **D5 · #145** (Medium, correctness) — `unidrive ls` (live) and the FUSE mount (`state.db`) disagree during the stale window → operator distrust.
- **D6 · #160** (Low, correctness) — `--download-only` hydrated-but-locally-missing rows silently loop instead of re-downloading.
  - *D1/D3/D5/D6 touch `SyncEngine.kt`; D4/D5 touch `StateDatabase.kt` → serialize within phase.*

### Phase E — Mount POSIX correctness (namespace verbs) — *borderline-include for a transparency mount*
- **E1 · #122** (Medium) — hydration unknown path maps to two different errnos (EIO vs ENOENT) depending on the verb.
- **E2 · #139** (Medium) — `mkdir` parent-missing maps to ENOENT instead of EIO (namespace-verbs R3).
- **E3 · #138** (Medium) — typed `FolderNotEmpty` provider exception for `HydrationImpl.rmdir` (R2).
- **E4 · #140** (Medium) — JVM-side cache-file eviction on `unlink`/`rmdir` (R5).
- **E5 · #123** (Medium) — bulk directory creation aborts wholesale on any failure (and is slow: one round-trip/dir). The wholesale-abort is the blocker; batching is the bonus.

### Phase F — Cross-repo NFC + mount freshness — *borderline*
- **F1 · #171 + ML #29** (correctness, cross-repo pair) — NFC normalization: sync ingests canonical NFC; mount `lookup` normalizes before match (NFD names currently → ENOENT). Surfaces: `SyncEngine.kt` + `mount/src` lookup. Single coordinated change.
- **F2 · ML #30 (+ #144/#145 finish)** (correctness/reliability) — co-daemon issues `hydration.subscribe` on mount; JVM reverts mount-detection to `hasSubscribers()` and emits `view.invalidated` on the subscribe stream after enumerate mutates `state.db` → co-daemon `notify_inval_entry` (mount-view-refresh §6 / Phase 3). Closes the residual mount-staleness story.

### Phase G — CLI trust (status)
- **G1 · #117** (High, correctness) — `status` and `status --all` enumerate different profile sources (FS-scan vs `config.toml`) → orphan-profile divergence. Pick FS-scan as truth, flag orphans.
- **G2 · #118** (High, observability) — `status` doesn't reflect tracking-set profiles (reads legacy `state.db`). Renders `tracking.db` → **sequenced inside Phase H** (after the ts engine is hardened), not here.

### Phase H — Tracking-set engine — **moved post-MVP** (decision #343)
H1–H4 (#104, #134, ts live-tests #133/#161/#162/#167/#168/#169, #118-ts, #108-via-migration) leave the MVP chain and form the post-MVP ts epic, joined by the deep-review ts data-safety set (#288 #292 #299 #302). What remains in-MVP from old H4: resolve #108 on the **legacy** engine via the one-shot, operator-supervised `--force-delete` — **no destructive op on the primary profile without explicit operator go-ahead.**

### Phase W — Windows read-only surface (after D0 + provider phases; interactive on the Windows dev box)
- **W1** — engine-side Windows blockers: #306 (daemon stop transport), #309 (case folding), #328 (credential ACL/DPAPI), the state-dir-fragmentation inventory recorded in #290 — plus whatever the soak surfaces.
- **W2 · unidrive-windows#3/#4** — verify CfAPI Phases 1.4 (dehydrate; blocked on #301) and 1.5 (live refresh; needs F2's `view.invalidated`).
- **W3 · unidrive-windows#7 + unidrive-dist#12 (MVP half)** — minimal install story (zip/script, prerequisites, login-start, recovery-aware uninstall).
- **Gate: unidrive-windows#9** per the acceptance-criteria spec; the Linux box runs its core soak in the background throughout.

### Phase I — Release + acceptance gate
- **I1 · #175** — fix `Task 'testClasses' not found` if it blocks running the test suite/CI (verify first; may be environmental).
- **I2 · #107** (High) — cut first `unidrive-mount-linux` release tarball + wire `dist/install.sh` to download + SHA256-verify. Ship-blocker, no data risk.
- **I3 · #109** (High) — reach the 5+5+2 live-integration smoke target (5 OneDrive, 5 Internxt, 2 sync).
- **I4 · #341** — `unidrive verify` convergence audit lands early enough to instrument every soak.
- **I5 — Surface gates** per `docs/dev/specs/mvp-acceptance-criteria.md` (#342): core+Linux (soaking since Phase D0 on the Linux box) and Windows read-only (unidrive-windows#9). **Final acceptance = all three checklists walked clean.**

---

## Deferred (post-MVP)

**Tracking-set epic (#343):** Phase H entire (#99 umbrella, #104 #134 #133 #161 #162 #167 #168 #169 #118-ts, #108-via-migration) + deep-review ts set (#288 #292 #299 #302). **Windows beyond read-only:** writeback (unidrive-windows#5), shell UX (unidrive-windows#6), MSIX/Authenticode/winget (unidrive-dist#12 post-MVP half). Efficiency: #124 #125 #126 #127 #128 #129 #130 #131 #153 #154 #155 #148. Daemon phases: #141 #142. Observability/UX/CLI: #143 #150 #151 #152 #156 #157 #159 #164 #165 #166 #170. Test/build: #146 #158 #163. Internxt inherent: #132 (≥60 min /files reap lag — document, cross-check folder-contents endpoint, not fully fixable client-side). Mount perf/hygiene: ML #31 #32 #36 #37 #38 #39 #40 #41 #42 #43. (ML #33 thumbnail bulk-hydration moved into Phase E consideration — re-tier if it causes egress blowups.) Deep-review medium/low findings (#307–#339) tier into the existing buckets; data-loss-relevant ones are already in Phase D0.

## Shared-file serialization map (why parallel is unsafe)

| File | Units |
|---|---|
| `SyncEngine.kt` (apply/guard/reap) | D0.1, D0.3–D0.4, D0.6–D0.8, B1, B2, D1, D3, D5, D6 |
| `StateDatabase.kt` | D4, D5 |
| `GraphApiService` / OneDrive provider | D0.1, D0.2, D0.3, C1, C2, C3, C4 |
| `HydrationImpl.kt` | E2, E3, E4, E5 |
| `HydrationIpcHandler` (JVM IPC) | A1, F2 |
| `mount/src/reconnect.rs` | V1(if hang), A1 |
| status command | G1, G2 |

## Execution protocol

1. Strictly one PR in flight. Dispatch a fresh subagent per unit (its own branch off latest `main`); it writes a bite-sized TDD plan, implements, runs the unit + regression tests, opens the PR.
2. I review the diff + `git log` (subagents can fabricate SHAs/reports — always verify), require the both-account smoke result, then merge, then `git pull` main before the next unit.
3. Re-base/re-verify the next unit on merged `main` (shared-file units especially).
