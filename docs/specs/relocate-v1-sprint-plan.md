# Sprint Plan — `unidrive relocate` v1

**Source:** [`relocate-v1.md`](relocate-v1.md)
**Window:** 2026-04-27 → 2026-05-29 (proposed launch); spec target was 2026-05-30 (Saturday — moved to nearest Friday). §5 Option A pushes to Fri **2026-06-05**.
**Owner:** Gernot, solo
**Generated:** 2026-04-24

> **Update 2026-04-28 ([ADR-0012](../adr/0012-linux-mvp-protocol-removal.md)):**
> W0 task 0.2 ("State file format design + `protocol/schema/v1/relocate-state-line.json`") drops the schema-file output; the state-file shape is documented in [`relocate-v1.md`](relocate-v1.md) §10 only. W3 task 3.1 ("Four protocol v1 schemas") becomes "Four `relocate_*` event ops emitted by `IpcProgressReporter`, with shape pinned by inline unit tests in the relocate module." Net schedule effect is roughly zero — the schema files weren't a critical-path bottleneck.

---

## 0. Calendar reality check (read this first)

**The spec said 25.5 working days fit in a 5-week window. They don't.**

German public holidays in May 2026 reduce the working calendar:

| Date | Day | Holiday |
|---|---|---|
| 2026-05-01 | Fri | Tag der Arbeit |
| 2026-05-14 | Thu | Christi Himmelfahrt |
| 2026-05-25 | Mon | Pfingstmontag |

That removes **3 working days**. Real availability:

| Week | Range | Days available | Notes |
|---|---|---|---|
| W0 (partial) | Mon Apr 27 – Thu Apr 30 | 4 | Fri Apr 30 capped before Labor Day weekend |
| W1 | Mon May 4 – Fri May 8 | 5 | |
| W2 | Mon May 11 – Fri May 15 | 4 | Ascension (Thu); spec assumes you work Fri (no Brückentag) |
| W3 | Mon May 18 – Fri May 22 | 5 | |
| W4 | Tue May 26 – Fri May 29 | 4 | Whit Monday |
| **Total** | | **22** | |

**Gap: 25.5 – 22 = 3.5 days.** This is what you came to find out by building the sprint plan. Three options at the bottom of this document.

---

## 1. Pre-work (this Friday or Monday morning)

Before any code lands, lock the three remaining open questions from the spec — none takes more than 15 minutes if you sit down with the spec:

- **OQ #5 — Worker concurrency defaults.** Proposed: OneDrive 4, HiDrive 8, others 4. Confirm.
- **OQ #6 — Progress event throttling.** Proposed: ≤ 1 Hz. Confirm.
- **OQ #8 — Circuit-breaker window/threshold defaults for `--delete-source`.** Proposed: window 200, threshold 5 %. Confirm or mark as "calibrate after P0-15b smoke run."

Output: a 5-line PR amending [`relocate-v1.md`](relocate-v1.md) Open Questions to "Resolved." Time: 0.5 days, parallel-able with W0.

---

## 2. Week-by-week plan

### W0 — Foundation (4 days)

| # | Task | Days | Output |
|---|---|---|---|
| 0.1 | Move `CloudRelocator` + `MigrateEvent` to new `core/app/relocate/` module, package `org.krost.unidrive.relocate` | 0.5 | Existing 23 tests still green; updated `settings.gradle.kts`; `RelocateCommand` import updated |
| 0.2 | State file format design + `protocol/schema/v1/relocate-state-line.json` | 0.5 | Schema reviewed against existing v1 envelope; example NDJSON file in `protocol/fixtures/` |
| 0.3 | State file writer (append + fsync per transition) | 1.5 | Unit tests for write/replay; SIGKILL test using a child process |
| 0.4 | `--dry-run` mode (collision analysis, plan output, no writes) | 1 | AC-1 green |
| **W0 total** | | **3.5** | **0.5 day slack** |

**End-of-week milestone:** dry-run on a real provider pair produces a clean plan; running without dry-run starts writing a state file and fsync-flushing every transition.

### W1 — Resilience (5 days)

| # | Task | Days | Output |
|---|---|---|---|
| 1.1 | `relocate resume --id <run-id>` — read state, project status, skip done files | 1.5 | AC-3 green |
| 1.2 | Auto-detect in-progress run on identical `--from/--to` invocation; TTY prompt vs non-TTY exit-64 | 0.5 | AC-4 green |
| 1.3 | OAuth token-refresh audit harness (mocked 401-after-N) | 1 | One test per provider (OneDrive, HiDrive, Internxt); all transparently refresh |
| 1.4 | OAuth multi-hour soak (manual, leave-running on real OneDrive) | 1 | AC-5 green; documented evidence in commit message |
| 1.5 | SIGINT / SIGTERM graceful shutdown | 1 | AC-6 green |
| **W1 total** | | **5** | **0 slack** |

**End-of-week milestone:** kill a live run with SIGKILL, resume it, walk away for 4 hours, come back to a healthy run.

### W2 — Trust stack (4 days, post-Ascension)

| # | Task | Days | Output |
|---|---|---|---|
| 2.1 | Ed25519 license verifier (BC or JDK 15+ built-in) | 1 | Unit tests for valid/invalid/expired licenses; embedded public key in CLI binary |
| 2.2 | License gate wiring + `--dry-run` exemption | 1 | AC-7a, AC-7b green |
| 2.3 | `--delete-source` per-file two-phase commit + sliding-window circuit breaker + `--rearm-delete` | 1 | Unit tests for breaker tripping at threshold; integration test for re-arming |
| 2.4 | Exit code normalization + `--help` documentation | 0.5 | All exit codes match P0-11; documented in --help and in landing-page copy stub |
| 2.5 | Buffer (or pull from W3) | 0.5 | |
| **W2 total** | | **4** | **0.5 absorbed buffer** |

**End-of-week milestone:** unlicensed dry-run prints a plan; unlicensed full-run refuses with exit 4 and the upgrade URL.

### W3 — Protocol & matrix (5 days)

| # | Task | Days | Output |
|---|---|---|---|
| 3.1 | Four protocol v1 schemas (`relocate_started`, `_progress`, `_complete`, `_error`) | 1 | Schema files in `protocol/schema/v1/`, fixtures in `protocol/fixtures/` |
| 3.2 | `MigrateEvent` → IPC adapter in CLI module | 1 | AC-8 green (event stream validates against schemas) |
| 3.3 | Cross-provider matrix conformance suite (see §3 below for size) | 3 | P0-16 green to the chosen size |
| **W3 total** | | **5** | **0 slack** |

**End-of-week milestone:** every supported provider pair runs a basic relocate cleanly in CI nightly; the IPC event stream validates against schemas.

### W4 — Validate & launch (4 days, post-Pfingsten)

| # | Task | Days | Output |
|---|---|---|---|
| 4.1 | Synthetic scale gate (localfs → localfs, 300 k synthetic files / 50 GB) | 1 | AC-9a green; CI runs nightly |
| 4.2 | Real-network smoke against HiDrive (~50 GB overnight) | 1 | AC-9b green; result captured in launch announcement evidence |
| 4.3 | Packaging — GraalVM native image, APT repo, release signing | 1.5 | Installable artifact; signature verifiable from a fresh machine |
| 4.4 | Landing page `/relocate`, release notes, final `--help` polish | 0.5 | Page live; release notes ready to publish |
| **W4 total** | | **4** | **0 slack** |

**End-of-week milestone (Fri May 29):** launchable artifact, signed; landing page live; PR pitch pack ready (see §5).

---

## 3. Dependency graph (what blocks what)

The flat list in the spec hides this. The real graph:

```
[0.1 Module move] → everything (do first; 30-min blocker for everything else)

[0.2 Schema] → [0.3 State writer] → [1.1 Resume] → [1.2 Auto-detect]

[0.4 Dry-run]   ─────┐
                     ├─→ [4.1 Synthetic scale gate]
[0.3 State writer] ──┘

[1.3 OAuth audit] → [1.4 OAuth soak] → [4.2 HiDrive smoke]

[2.1 Verifier] → [2.2 License gate] → AC-7a, AC-7b

[3.1 Schemas] → [3.2 Adapter] → [3.3 Matrix tests]
                                    │
                                    └→ depends also on [2.2 License gate]
                                       so matrix tests don't trip on license refusal

[4.3 Packaging] independent until W4 — could move earlier if needed
```

**Critical path:** 0.1 → 0.3 → 1.1 → 1.3 → 1.4 → 2.2 → 3.2 → 3.3 → 4.2 → 4.3. Anything on this chain that slips a day slips the launch a day.

**Off-critical-path tasks** that can absorb slack or move:
- 0.4 dry-run (parallelizable with state writer once schema is locked)
- 2.3 delete-source breaker (independent until shipping)
- 4.1 synthetic scale gate (can move to W3 if W4 looks tight)
- 4.4 landing page (writable evenings/weekends — see §6)

---

## 4. Visible risks from the dependency graph

These are the ones you cannot see by reading the spec linearly.

### R1 — Provider OAuth registrations (HIGH)
Tasks 1.3 and 4.2 assume registered OAuth apps for OneDrive (Azure App), HiDrive (Strato dev portal), Internxt (their developer flow). Each can take days of approval. **Action this week:** verify all three OAuth apps exist and have the scopes you need; if any are missing, start the registration today, not in W1.

### R2 — Test data on HiDrive (MEDIUM)
Task 4.2 needs ~50 GB of test data on a real HiDrive source account. At 50 Mbit/s that's ~2.5 hours of upload, but realistically with throttling and the rest of life, plan a full evening. **Action W3:** start uploading test data on Wednesday W3 so it's ready for W4 Tuesday.

### R3 — Cross-provider matrix combinatorics (HIGH)
3 days for a 5×5 matrix = 25 pairs ≈ 30 minutes per pair. That assumes everything works first try. The Internxt WebDAV combination is documented-broken (rclone forum thread, Feb 2026). Realistic outcomes:
- Best case: 23/25 pairs green, 2 known-issues. 3 days holds.
- Likely case: 18-20/25 green, 5-7 need investigation. 3 days insufficient.
- Worst case: a systemic issue (e.g., Internxt API quirk) breaks 10 pairs. Multi-day debug.

**Mitigation built-in:** size the matrix to actual launch needs, not theoretical coverage. See §5 cut-point #B.

### R4 — Calendar drift on the OAuth soak (MEDIUM)
Task 1.4 is a multi-hour passive run. It blocks 1 day of W1, but the soak time itself is wall-clock not work-clock. **Action W1:** start the OneDrive soak Wednesday morning so it runs overnight; coding continues Thursday.

### R5 — Native-image build surprises (MEDIUM)
GraalVM native-image with Kotlin coroutines + reflection can be a multi-day rabbit hole if the project hasn't built natively before. **Action this week:** spike a 30-minute native-image build of the existing CLI binary today and confirm it works. If it doesn't, factor 1 extra day into W4 and cut something else, OR fall back to JAR+install-script (see cut-point #D).

---

## 5. Cut-points — closing the 3.5-day gap

The 3.5-day gap is real. Pick one or combine.

### Option A — Push launch to Fri Jun 5 (recommended)
**Cost:** 4 calendar days, 4 working days. Closes the gap with 0.5 days to spare.
**Risk:** Internxt PR cycle has a half-life; June 5 is still in-window. May 29 vs June 5 is a rounding error in press timing.
**Verdict:** This is the honest move. The spec was based on a flat day-count that didn't account for German holidays. Acknowledging it now beats discovering it in W3.

### Option B — Shrink cross-provider matrix from 5×5 to 3×3
**Cost:** 1.5 days saved. Coverage drops from 25 pairs to 9: {OneDrive, HiDrive, localfs} as both source and target. Drop Internxt-as-source/destination (already known broken on WebDAV) and WebDAV-as-generic (covered indirectly via HiDrive).
**Risk:** Internxt is positioned as a launch provider in the brief. Shipping without Internxt-pair tests means launching with "Internxt: experimental" in the docs.
**Verdict:** Reasonable if combined with Option A. Internxt's own client is broken anyway — "experimental" is honest, not embarrassing.

### Option C — Drop native-image, ship JAR + install script
**Cost:** 1.5 days saved (skip GraalVM packaging rabbit holes; just `gradle distZip` + a shell installer).
**Risk:** Slower startup, JRE dependency on user's machine, weaker UX. Minor for a CLI tool used occasionally; significant for daily-driver software.
**Verdict:** Acceptable for v1 if R5 spike fails. Plan the native-image as 1.0.1.

### Option D — Defer license gate to 1.0.1, run a 2-week beta with Pro features unlocked
**Cost:** 2 days saved. Time pressure released significantly.
**Risk:** Significant trust-stack hit. The license file is the proof-of-Pro-purchase mechanism; without it, "free for two weeks" creates a precedent and makes the eventual gate feel like a takeaway.
**Verdict:** Only if everything else has slipped and you need the launch window. This is the "miss the news cycle vs. miss the revenue model" trade.

### Option E — Work the missed holidays
**Cost:** 0 calendar days; closes the gap by working May 1, May 14, and May 25.
**Risk:** Predictably ends in resentment, missed bugs, and a sloppy launch.
**Verdict:** Don't.

### My recommendation
**A + B.** Push launch to Fri Jun 5. Ship with a 3×3 matrix and Internxt marked experimental in the README. This:
- Buys 4 days of breathing room over the spec's already-tight estimate.
- Lets you ship a proper native-image binary (signed).
- Keeps the license gate (the revenue model is non-negotiable).
- Honest about Internxt's broken state, which actually fits the brief's positioning ("we ship around Internxt's bugs, we don't pretend they don't exist").
- Lets you spend an extra day on the landing page / PR pitches.

Total: gap closed. New launch: **Fri Jun 5, 2026.**

---

## 6. Parallel non-coding work (evenings, weekends, or coffee breaks)

These don't compete with the engineering critical path; you can do them in chunks of an hour or two:

| Task | When | Effort |
|---|---|---|
| `/relocate` landing-page copy v0 | Anytime W0–W2 | 2 hours |
| README rewrite (drop "OneDrive + Internxt MVP" framing) | W0 evening | 1 hour |
| PR pitch pack (c't editorial, Kuketz blog, HN Show post) | W3 weekend | 4 hours |
| Test data prep on real HiDrive | W3 Wed evening | 1 hour active + overnight upload |
| OAuth app sanity check (R1 mitigation) | This Friday | 30 min |
| Native-image spike (R5 mitigation) | This Friday | 30 min |

Front-load the two 30-minute spikes — they de-risk the entire plan and cost you almost nothing this week.

---

## 7. Cut-line summary

If at the end of any week you are behind the cumulative day count below, trigger the cut decisions on the right.

| End of | Cumulative days planned | If behind, cut |
|---|---|---|
| W0 | 4 | Nothing yet — borrow from W2 buffer |
| W1 | 9 | Compress OAuth soak to 1 hr instead of overnight; document caveat |
| W2 | 13 | Drop the breaker rearm-flag; ship with run-level pessimism (revert to original spec). 0.5 day saved. |
| W3 | 18 | Trigger Option B (matrix shrink). 1.5 days saved. |
| W4 | 22 | Trigger Option C (drop native-image) and/or push launch to Fri Jun 12. |

---

## 8. What I'd actually do Monday morning

1. (5 min) Resolve OQ #5, #6, #8. Edit the spec.
2. (30 min) Spike a GraalVM native-image build of the current CLI. Pass/fail tells you whether to reserve W4 packaging time as 1.5 or 3 days.
3. (30 min) Verify all three OAuth registrations are in place.
4. (rest of day) Move `CloudRelocator` to `core/app/relocate/`. Run the 23 existing tests. Commit clean.

By end of day Monday you've de-risked the two highest-impact unknowns and shipped the architectural cleanup the spec calls for. W0 is then on track.

---

*End of sprint plan.*
