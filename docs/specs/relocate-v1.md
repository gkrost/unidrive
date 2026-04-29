# Spec — `unidrive relocate` v1

**Feature:** One-shot provider relocation (Pro)
**Target release:** 2026-06-05 (per sprint-plan §5 Option A; spec text below still references 2026-05-30)
**Owner:** Gernot (solo dev)
**Status:** Draft, lean scope
**Depends on:** existing provider SPI, `core/app/sync/CloudRelocator`, `core/app/cli/RelocateCommand`, `core/app/xtra`

> **Update 2026-04-28 ([ADR-0012](../adr/0012-linux-mvp-protocol-removal.md)):**
> the spec's references to "protocol v1" / `protocol/schema/v1/` /
> `protocol/fixtures/` are obsolete — the directory was removed when
> Linux became the explicit MVP target. P0-13 still ships the four
> `relocate_*` event ops; they're emitted via `IpcProgressReporter`
> over UDS like every other sync event, with shape pinned by inline
> unit tests in the relocate module rather than a JSON-Schema file.
> AC-8 reads "every frame parses cleanly and matches the
> `IpcProgressReporter` event shape" instead of "validates against its
> schema in `protocol/schema/v1/`." The state-file format ([§10](#10-architecture-notes))
> is documented in this spec; no separate schema file lands.

---

## 0. Current State (what already exists)

A sizeable portion of relocate is already shipped and tested. Writing this spec without auditing the repo would have led to building things twice. What exists today:

| Component | File | Status |
|---|---|---|
| CLI subcommand | `core/app/cli/.../RelocateCommand.kt` | Exists; flags `--from/-f`, `--to/-t`, `--source-path`, `--target-path`, `--delete-source`, `--buffer-mb`, `--force`; authenticates both providers up front. |
| Migration engine | `core/app/sync/.../CloudRelocator.kt` | Exists; `preFlightCheck()` returns totals, `migrate()` returns a `Flow<MigrateEvent>`. |
| In-process events | `CloudRelocator.MigrateEvent` sealed class | Exists: `Started`, `FileProgressEvent` (with skipped counters), `Completed`, `Error`. |
| Skip-if-match behavior | `CloudRelocator.migrate(...)` | Exists; hash-match or size-match skip. Overridable with `--force`. |
| Per-file error continuation | `CloudRelocator.migrate(...)` | Exists; single-file failure does not abort the run. |
| Test suite | `CloudRelocatorTest.kt`, `RelocateCommandTest.kt` | 23 tests, green. |
| Platform storage convention | `ui/.../Platform.kt` | Uses `APPDATA\unidrive` (Windows), `XDG_DATA_HOME/unidrive` (Linux). New state files follow this convention. |
| Trash / delete-safety pattern | `sync/Trash.kt` | Moves files to `$XDG_DATA_HOME/Trash/`. Use for `--delete-source` staging. |

**What this spec is actually about:** closing the gap between the current prototype and a launchable v1. The v1 bar is resumability, protocol-level integration, license enforcement, OAuth token-refresh survival during multi-hour transfers, and validated scale — not greenfield.

## 1. Problem Statement

Cloud sync users have no safe way to leave a provider. When a vendor disappoints — Internxt silently revoked WebDAV access on lifetime plans in February 2026 — users face (a) manual re-upload, (b) stitching rclone commands together, or (c) losing their lifetime investment. UniDrive already ships a basic `relocate` command that handles the happy path; it does not yet survive interruption, produce a trustable audit trail, or integrate with the protocol v1 IPC. v1 closes those gaps so the command can be the feature the brief promises: single, auditable, resumable migration at 300 GB / 300 k-file scale.

## 2. Goals

1. A relocate run that is killed (SIGKILL, crash, laptop-lid, network loss, OAuth expiry) can be resumed with zero data loss and zero redundant work.
2. Every run produces an on-disk audit trail sufficient to reconstruct what happened per file — byte count, hash, timestamp, final state — without the UniDrive process running.
3. Protocol v1 IPC exposes relocate start/progress/complete/error so a future UI or MCP consumer can observe relocate without parsing stdout.
4. Validated against a real HiDrive account at ≥ 300 GB / ≥ 300 k files before launch. This is the scale at which the Internxt Linux client breaks — matching or exceeding it is the brief's implicit claim.
5. License enforcement gates the Pro feature, offline, with no phone-home.

## 3. Non-Goals (v1)

1. **Cross-encryption-key relocate.** If `xtra` is active on both sides, v1 passes ciphertext through unchanged (already the effective behavior — `CloudRelocator` reads and writes opaque bytes). Re-encrypting mid-flight is v2.
2. **Merge mode with conflict resolution.** Current `--force` is a blunt "transfer everything even if target has it." Sophisticated conflict policy (`LAST_WRITER_WINS`, `KEEP_BOTH`) is v2.
3. **Subtree filters with glob patterns.** `--source-path` / `--target-path` already exist; include/exclude patterns are v2.
4. **Tray UI controls for relocate.** ~~v1 is CLI-invoked.~~ v1 is CLI-only — the `ui/` tier was removed in [ADR-0013](../adr/0013-ui-removal.md). Any future tray re-import would consume `relocate_*` events over UDS read-only and leave start/pause/cancel as CLI verbs.
5. **Scheduled or continuous relocate.** One-shot, manual, explicit. "Migrate + keep in sync until cutover" is a separate feature.
6. **Multi-account orchestration in one invocation.** One source profile → one target profile per run.
7. **Permissions / ACL / sharing preservation.** Files + folder structure + (best-effort) mtime. Nothing else.

## 4. User Stories

**Primary persona:** technically competent Linux user, typically 300 GB – 2 TB of cloud data, has Pro license, has experienced or fears a vendor rug-pull.

- As a Pro user whose cloud provider has become unreliable, I want to kick off relocate and walk away — if my laptop sleeps, the network drops, or an OAuth token expires, I want to find the run either complete or cleanly resumable when I come back.
- As a Pro user moving 500 GB, I want the run to tell me in advance (a) how many files, (b) total bytes, (c) which target paths already exist and how the run will handle them — before I commit bandwidth.
- As a Pro user, I want a durable record on disk of what moved, so that if a destination corrupts a file six months later I can point at the audit and say "this file was verified on 2026-05-14, hash xxx."
- As a Pro user running a multi-hour relocate, I want OAuth tokens to be refreshed silently during the run — I should not see "403 Unauthorized" an hour in because the access token expired.
- As a Pro user, I want the command to exit non-zero with a specific, stable exit code per failure mode so I can script around it.

## 5. Requirements

### P0 — Must have for launch

| ID | Requirement | Status today |
|---|---|---|
| P0-1 | CLI: `unidrive relocate --from <profile> --to <profile>` runs enumerate-plan-execute-verify. | ✅ built |
| P0-2 | Pre-flight check produces totals (file count, bytes) before any transfer. | ✅ built |
| P0-3 | Skip-if-match: don't retransmit files whose hash or size already matches at the target. | ✅ built |
| P0-4 | Per-file error continuation; single failures do not abort the run. | ✅ built |
| P0-5 | `--delete-source` removes source files only after successful target verification. **Per-file two-phase commit (copy → verify destination hash → delete source).** A circuit breaker monitors a sliding window of the last N transfers (default 200): if the failure rate in the window exceeds `--delete-failure-threshold` (default 5 %), the delete pipeline pauses and emits a `relocate_error` event; the user must re-arm with `unidrive relocate resume --id <id> --rearm-delete` to resume deletes. Single transient failures do not block the run; systemic corruption does. | 🟡 partial |
| P0-6 | **Dry-run mode.** New flag `--dry-run` (distinct from pre-flight): enumerates source AND target, produces a plan (file count, total bytes, files to transfer, files to skip, destination collisions), emits zero bytes of transfer, writes no state file. | ❌ to build |
| P0-7 | **State file.** On every non-dry run, append NDJSON lines to `$configDir/relocate/<run-id>.ndjson` capturing run parameters (first line) and per-file transitions (subsequent lines: pending → in_flight → done/failed, with bytes and hash at done). Append-only. Fsync after every transition. | ❌ to build |
| P0-8 | **Resume.** `unidrive relocate resume --id <run-id>` re-reads the state file, projects the latest per-file status, skips files already `done`, re-attempts `in_flight` and `failed`. Running the same `--from/--to` combination detects an in-progress run and prompts to resume or start fresh. | ❌ to build |
| P0-9 | **OAuth token refresh during long runs.** OneDrive, HiDrive, and Internxt providers must refresh access tokens silently mid-run. A 401 mid-transfer triggers one refresh + retry; persistent 401 fails only the current file, not the run. | 🟡 providers have refresh; not audited under multi-hour load |
| P0-10 | **Graceful shutdown.** SIGINT / SIGTERM: finish the currently transferring file if it has less than 30 s remaining estimated, else checkpoint the current position in the state file and exit with code 3 (partial). SIGKILL is handled implicitly because the state file is append + fsync. | ❌ to build |
| P0-11 | **Stable exit codes.** 0 success; 3 partial success (some files failed verification, or graceful interruption); 4 unlicensed; 5 destination not empty and `--force` not set; 6 authentication failure on either side; 64+ CLI usage errors. Document in `--help`. | 🟡 needs audit and normalization |
| P0-12 | **License gate.** Non-dry-run relocate refuses to start without a valid Pro license. **`--dry-run` is exempt** — unlicensed users can produce a plan, see the byte/collision report, and decide whether to license. License is an Ed25519-signed file at `$configDir/license.dat`; verification is offline; if the license has expired mid-run (during a resume), the run completes but future non-dry-run invocations refuse. | ❌ to build |
| P0-13 | **Protocol v1 event ops.** New ops emitted over the existing IPC envelope: `relocate_started`, `relocate_progress` (throttled ≤ 1 Hz), `relocate_complete`, `relocate_error`. Schemas added under `protocol/schema/v1/` in the same PR as the implementation. Op names conform to the envelope's `^[a-z][a-z0-9_]*$` pattern. | ❌ to build |
| P0-14 | **`MigrateEvent` → protocol mapping.** Existing in-process `MigrateEvent` (Started / FileProgressEvent / Completed / Error) is adapted to the four new IPC ops. The adapter lives in the CLI module; `CloudRelocator` itself remains transport-agnostic. | ❌ to build |
| P0-15a | **Synthetic scale gate (CI-runnable).** localfs → localfs at ≥ 300 k files / ≥ 50 GB synthetic data, runs in CI nightly. Must complete without OOM, state file bounded (< 500 MB), peak RSS ≤ 1 GB, resume tested at midpoint. This is the release blocker. | ❌ to build |
| P0-15b | **Real-network smoke (release-time, not CI).** One overnight run at ~50 GB / ~50 k files against a real HiDrive account, validating OAuth refresh, real throttling, real error paths. Pass criterion: completes successfully or fails with diagnosable cause. | ❌ to build |
| P0-15c | **Marketing-grade scale run (post-launch, optional).** ≥ 300 GB / ≥ 300 k files real-network. If completed, becomes a quotable benchmark. Not a release gate. | deferred |
| P0-16 | **Provider pair coverage.** v1 supports every pair drawn from {OneDrive, Internxt, HiDrive, WebDAV, localfs}. SFTP and S3 providers are scaffolded but not required for v1. | 🟡 works in principle; matrix not systematically tested |

### P1 — Nice to have, fast follow

| ID | Requirement |
|---|---|
| P1-1 | Bandwidth caps: `--upload-limit=Xmbit`, `--download-limit=Xmbit`. Reuse `sync/TokenBucket.kt`. |
| P1-2 | `--format=json` on stdout emits a structured final summary (run-id, totals, per-file-status counts, duration). |
| P1-3 | Preserve modification times where the target API supports it (HiDrive, OneDrive, WebDAV: yes; Internxt: partial). |
| P1-4 | Configurable worker concurrency `--workers N`. Per-provider default (OneDrive 4, HiDrive 8, others 4). |
| P1-5 | `unidrive relocate list` shows past + in-progress runs with their state-file summaries. |
| P1-6 | `unidrive relocate cancel --id <run-id>` terminates and marks the run aborted in the state file. |

### P2 — Future, noted to prevent architectural foot-guns

| ID | Requirement |
|---|---|
| P2-1 | Merge mode with `ConflictPolicy` (`LAST_WRITER_WINS`, `KEEP_BOTH`, reuse from `sync/model`). |
| P2-2 | Subtree glob filters `--include-glob` / `--exclude-glob`. |
| P2-3 | Re-encrypt in flight (source plaintext → target encrypted, or key rotation between providers). |
| ~~P2-4~~ | ~~Tray UI control surface for relocate~~ — moot until/unless a UI tier returns ([ADR-0013](../adr/0013-ui-removal.md)). |
| P2-5 | IPC command-type op to trigger relocate from a frontend (not just CLI) — would land on the existing UDS surface in `IpcServer.kt`. |
| P2-6 | SFTP / S3 first-class relocate providers with their own conformance tests. |
| P2-7 | Scheduled relocate: migrate then keep target in sync with source until manual cutover. |

## 6. Acceptance Criteria (P0 only, gap-focused)

Tests for AC-A/B/C/D (already-shipped behavior) exist in `CloudRelocatorTest`. New ACs target the gap items.

### AC-1 (P0-6): Dry-run produces plan, writes nothing
- **Given** a source profile with 100 files / 1 GB and a target profile
- **When** `unidrive relocate --from src --to dst --dry-run` runs
- **Then** stdout shows file count, total bytes, files-to-transfer, files-to-skip, and any target collisions
- **And** no file is written at the target
- **And** no state file is created
- **And** exit code is 0

### AC-2 (P0-7): State file is durable
- **Given** an in-progress relocate
- **When** any file transitions from `pending` to `in_flight` to `done`
- **Then** each transition is appended to `$configDir/relocate/<run-id>.ndjson` and fsynced before the next transition begins
- **And** killing the process with SIGKILL at any point leaves the state file with a consistent, replayable sequence of transitions

### AC-3 (P0-8): Resume after kill
- **Given** a relocate of 10 GB / 10 000 files that has processed 4 GB / 4 000 files and was killed with SIGKILL
- **When** the user runs `unidrive relocate resume --id <id>`
- **Then** no file already marked `done` is re-uploaded
- **And** exactly the remaining 6 GB transfers
- **And** final target state is equivalent to an uninterrupted run
- **And** exit code is 0

### AC-4 (P0-8): Resume auto-detect on repeat invocation
- **Given** an in-progress run for `--from A --to B` that was killed
- **When** the user runs the same `--from A --to B` command (without `resume`)
- **Then** the CLI prompts: "Resume run <id> (yes) or start fresh (no)?" (default: yes)
- **And** a non-TTY invocation errors out with exit code 64 and a hint to use `resume --id`

### AC-5 (P0-9): OAuth refresh survives multi-hour run
- **Given** a relocate estimated to run longer than the shortest OAuth access-token lifetime on the provider pair (OneDrive ~1 h)
- **When** the run exceeds that window mid-transfer
- **Then** the provider silently refreshes its access token and continues
- **And** no file is marked failed due to a single 401
- **And** the state file shows no spurious `failed` → `retried` transitions attributable to token expiry

### AC-6 (P0-10): Graceful SIGINT
- **Given** an in-progress relocate with a file currently transferring
- **When** the user sends SIGINT
- **Then** if the current file has < 30 s remaining, it completes and is marked `done`
- **And** otherwise the state file is checkpointed at the current position
- **And** the process exits within 5 s with code 3

### AC-7a (P0-12): Unlicensed non-dry-run is refused
- **Given** a build with no `license.dat` or an invalid signature
- **When** the user runs `unidrive relocate --from src --to dst` (without `--dry-run`)
- **Then** the command exits with code 4 before any provider is contacted
- **And** stdout prints a single-line message with the upgrade URL

### AC-7b (P0-12): Unlicensed dry-run is allowed
- **Given** the same unlicensed build
- **When** the user runs `unidrive relocate --from src --to dst --dry-run`
- **Then** the plan is computed and printed normally
- **And** no bytes transfer at the destination
- **And** the message includes a single-line note: "Run without --dry-run requires a Pro license: <upgrade URL>"
- **And** exit code is 0

### AC-8 (P0-13): Protocol events well-formed
- **Given** a successful relocate
- **When** the event stream is captured
- **Then** at least one `relocate_started` frame, N `relocate_progress` frames (1 ≤ rate ≤ 1 per second), and exactly one `relocate_complete` frame are emitted
- **And** every frame validates against its schema in `protocol/schema/v1/`
- **And** the envelope `op` field matches `^[a-z][a-z0-9_]*$`

### AC-9a (P0-15a): Synthetic scale gate
- **Given** 300 k synthetic files / 50 GB on localfs source and localfs target, generated in `core/docker/test-matrix.sh`
- **When** relocate runs end-to-end
- **Then** peak RSS stays ≤ 1 GB, state file stays ≤ 500 MB, the run completes without OOM or unbounded memory growth
- **And** resuming from a SIGKILL at the midpoint completes the remainder
- **And** total wall-clock time ≤ 30 min on a developer-class machine (no network bottleneck)

### AC-9b (P0-15b): Real-network smoke
- **Given** ~50 GB / ~50 k files on a real HiDrive account (credentials via secret, not committed)
- **When** relocate runs overnight against an empty target HiDrive folder
- **Then** the run completes within reasonable wall-clock time, OAuth tokens refresh transparently when they expire, and there is no spurious failure attributable to throttling
- **And** if the run fails, the failure is diagnosable from the state file alone

## 7. Success Metrics

### Leading (first 30 days post-launch)
- Pro conversions attributable to `/relocate` landing-page UTM: ≥ 50.
- Relocate runs initiated (opt-in telemetry counter, no payload): ≥ 100.
- Resume success rate across all runs that survive at least one checkpoint: ≥ 95 %.
- First-run failure rate (exit code ≥ 2 on a user's first invocation): ≤ 10 %.

### Lagging (first 90 days)
- One named trade-press mention of `relocate` by name (c't, Heise, Linux Magazin, Kuketz-Blog, Phoronix).
- At least one public user story of a UniDrive-driven migration off a specific provider (HN thread, Reddit post, blog).
- Net Pro-license growth during any vendor-drama news cycle exceeds non-news weeks by ≥ 50 %.

### Measurement
- Self-hosted Plausible (or similar; no Google Analytics) for landing-page UTM.
- Opt-in local telemetry counter: three integers (success / failure / resume). Uploaded only on user confirmation. Off by default.
- Manual 2-hour weekly media scan; do not automate before signal exists.

## 8. Open Questions (self-assigned, all decisions yours)

1. **State file location — `$configDir/relocate/` or `$XDG_STATE_HOME/unidrive/relocate/`?** `Platform.configDir` resolves to `XDG_DATA_HOME` on Linux, which is the current convention for UniDrive state (trash, logs). XDG purists would argue `XDG_STATE_HOME` is semantically correct for "continuable runtime state." Lean recommendation: stay with `Platform.configDir` for consistency. One decision, document it, move on.
2. **License file format.** Ed25519 is decided. Open: embed metadata (purchased_at, expires_at, edition, holder_email) in-file or reference a server endpoint? Lean: embed everything, sign it, revoke via public revocation list fetched best-effort at launch and cached. This matches the offline-first trust promise.
3. **Run-id format.** 8-char Crockford base32 (40 bits, 1 in 10^12 collision on sensible volumes) is recommended. User-typable after `resume --id`, no collision risk in the foreseeable future. Confirm before P0-7.
4. ~~**Dry-run behavior when license missing.**~~ **Resolved:** dry-run is exempt from the license check (see P0-12, AC-7a/b). Rationale: the plan-and-preview output is the value proposition, and gating the preview behind a license inverts the funnel.
5. **Worker concurrency default.** Currently serial. Four workers on OneDrive is safe; HiDrive WebDAV can handle more. Confirm profiles before P1-4.
6. **Protocol v1 `relocate_progress` throttling.** Strictly ≤ 1 Hz vs. event-per-file (up to thousands per second)? Lean: throttle to 1 Hz to match `sync` progress events; do not flood the IPC.
7. ~~**`--delete-source` safety bar.**~~ **Resolved:** per-file two-phase commit + 5 % sliding-window failure-rate circuit breaker (see revised P0-5). User can re-arm with `--rearm-delete` on `resume` after investigating the cause.
8. **Circuit-breaker window size and threshold.** Default proposed: window = 200 transfers, threshold = 5 %. These need real-data calibration. Until P0-15b runs, treat them as placeholders subject to one tuning pass after the smoke run.

## 9. Timeline

Target: **2026-05-30.** That is 5 calendar weeks from 2026-04-24.

Current baseline reduces risk significantly — the following is net new work on top of the existing `CloudRelocator` / `RelocateCommand`:

| Phase | Days | Covers |
|---|---|---|
| Resolve remaining open questions (5, 6, 8) | 0.5 | Final design calls before coding |
| Move `CloudRelocator` to `core/app/relocate/` | 0.5 | Architecture cleanup; carries `MigrateEvent` |
| State file + checkpoint + fsync | 3 | P0-7 |
| `relocate resume --id` + auto-detect in-progress run | 2 | P0-8, AC-3, AC-4 |
| `--dry-run` with collision analysis | 1 | P0-6, AC-1 |
| OAuth token-refresh audit + multi-hour soak harness | 2 | P0-9, AC-5 |
| SIGINT / SIGTERM graceful shutdown | 1 | P0-10, AC-6 |
| License gate (Ed25519 verifier + CLI wiring + dry-run exemption) | 2 | P0-12, AC-7a/b |
| Exit code normalization + documentation | 0.5 | P0-11 |
| `--delete-source` per-file commit + circuit breaker + `--rearm-delete` | 1 | P0-5 |
| Protocol v1 schemas (4 new ops) + `MigrateEvent` adapter | 2 | P0-13, P0-14, AC-8 |
| Cross-provider matrix conformance tests (5 × 5 = 25 pairs; CI nightly) | 3 | P0-16 |
| Synthetic scale gate (localfs, 300 k files / 50 GB, in CI) | 1 | P0-15a, AC-9a |
| Real-network smoke (HiDrive, ~50 GB overnight) | 1 | P0-15b, AC-9b |
| Packaging (GraalVM native image, APT repo, release signing) | 2 | Distribution readiness |
| Landing page `/relocate` + release notes + `--help` polish | 2 | Marketing prerequisite |
| Buffer | 1 | Real life |
| **Total** | **25.5 working days** | Fits 5 weeks if no major derail |

**If behind at the halfway mark:** cut all P1 entirely, defer scale run to 100 GB / 100 k files, ship with "tested at 100 GB" claim. Do not cut: state file, resume, OAuth refresh audit, license gate, protocol ops. Those four are the spine of the spec.

**Hard deadline constraint:** none externally. The 2026-05-30 target is tied to timing the launch for the Internxt PR hook. A one-week slip is acceptable; two weeks costs the news-cycle pairing and should force a conscious re-plan rather than a quiet slide.

## 10. Architecture Notes

Because you asked for lean, one page only.

- **Module placement:** **Move `CloudRelocator` to a new module `core/app/relocate/` with package `org.krost.unidrive.relocate`.** The earlier rationale for keeping it in `sync/` was wrong — `CloudRelocator.kt` only depends on `CloudItem` and `CloudProvider` from `core`, not on any sibling in `sync/`. The current location is convenience, not coupling. Move now while the surface area is small (one production class, one test class). Carry `MigrateEvent` along; it is the relocate event type, not a sync type. Plan: rename package, move files, update `RelocateCommand` import, update `settings.gradle.kts`, run the existing 23 tests. Half a day at most.
- **State file schema:** NDJSON, first line is a run-header `{run_id, started_at, from_profile, to_profile, source_path, target_path, flags, totals}`; subsequent lines are per-file transitions `{path, transition, size, hash, ts, error?}`. Append-only. Replay = fold transitions into a map keyed by path. Schema file `protocol/schema/v1/relocate-state-line.json` added in the same PR for machine-readability.
- **Protocol ops (new):** `relocate_started`, `relocate_progress`, `relocate_complete`, `relocate_error`. Each has its own schema under `protocol/schema/v1/`. Names match the envelope `op` regex. Schemas follow the existing `sync-started-event.json` / `action-progress-event.json` patterns.
- **`MigrateEvent` → IPC adapter:** the CLI module's `RelocateCommand` already consumes the `Flow<MigrateEvent>` — introduce a thin adapter there that also publishes protocol events to the IPC endpoint. Keep `CloudRelocator` transport-agnostic.
- **License verifier:** Ed25519 via BouncyCastle or JDK 15+ (JDK has Ed25519 since 15). The public key is embedded in the binary; the license file is `{header_json}\n{signature_base64}`. 15 lines of code.
- **OAuth refresh audit:** write a harness that mocks HTTP 401 responses after N requests and verifies the provider transparently refreshes. One test per provider (OneDrive, HiDrive, Internxt). The refresh path already exists in the provider code; the audit confirms it survives extended use.
- **Scale test:** run in `core/docker/test-matrix.sh`. Use real HiDrive account behind a secret — do not commit credentials. Measure RSS via `/proc/self/status` at 10 s intervals, write to CSV, generate a plot as part of the release artifact.

---

*End of spec.*
