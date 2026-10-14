# Agent-ready work portions for gkrost/unidrive (2026-10-14)

Every open issue of this repository is either inside one portion (`P<nn>-<slug>.md`, one unit of work for one fresh agent: one branch, one
DRAFT pull request, the owner merges) or listed under "Not packaged" with the reason. A portion states facts, anchors and a definition of
done; it does not need other documents. Anchors were checked by reading `origin/main` on 2026-10-14 (read at `8cb7b248`, re-checked at `a0c6c4d3`; the one newer commit, #876, touches
only the logout/auth files and the README). Symbols and files are named; line numbers are hints and drift. Claims in a portion are tagged `[measured]` (a trace or database copy of the 2026-10-14 capture
runs), `[code]` (read in the tree), `[inferred]` or `[unverified]`. Nothing was built or run: no gradle task, no test, no provider API call.

Planning sources read first: `lane-bugs-quickwins.md` and `lane-desktop-ui.md` (tracked, in this folder's parent), and the untracked
plans of the owner's checkout (`agent-correctness-worklist.md`, `Agent_open-bug-sweep.md`, `Agent_internxt-cursor-sync-windows-gaps.md`,
`Agent_server-side-cursor-sync.md`, the two first-startup captures and the Internxt-vs-UniDrive comparison). The untracked files are not on
main; a portion therefore never depends on them. Where they are stale, see "Stale planning documents".

## In flight (not portions)

| What | Issue | State |
|---|---|---|
| [PR #880](https://github.com/gkrost/unidrive/pull/880) (draft) | #879 Internxt first scan resumes a cursor the server times out on | in progress; its follow-ups are [P09](P09-internxt-cursor-fallback-followups.md) |
| [PR #877](https://github.com/gkrost/unidrive/pull/877) | #321 120 s reap grace (the sweep also defers rows written after the gather started) | in progress; nothing further after it merges |

Do not start a portion that edits the same files before the PR is merged: `InternxtCursorListing.kt`, `InternxtProvider.kt`,
`InternxtApiService.kt` (#880) and `RemoteGather.kt`, `SyncEngine.kt`, `FakeCloudProvider.kt` (#877).

## Index

Priority is the rank (1 = first) in the order data safety and correctness, first-sync and reliability, efficiency, UX, tests, docs,
release; the wave says when it can start (see "Order of work"). "Status" is `ready`, `blocked on owner decision` or a dependency; a
portion marked blocked still carries a proposed default so the owner can answer in one line. Rows without an issue number need a new
issue to be filed first (the portion says so).

| Portion | Issues | Priority | Size | Depends on | Status |
|---|---|---|---|---|---|
| [P01](P01-launcher-stdin.md) Windows launcher drops standard input: profile set ignore_rules clears the rules | #859 | 1 / wave 1 | S | - | ready |
| [P02](P02-onedrive-write-token.md) OneDrive mount edits: a provider-neutral write token instead of the content hash | #511 | 2 / wave 2 | M | owner decision (column name) | blocked on owner decision |
| [P03](P03-hydration-nfc-twin-guard.md) Mount writes: do not let an NFD twin overwrite the NFC file's cloud content | #491 | 3 / wave 4 | M | owner decision (rule) | blocked on owner decision |
| [P04](P04-case-policy-capability.md) Case-insensitive backends: a provider case policy and no cross-device overwrite | #309 | 4 / wave 3 | M | owner decision (case policy) | blocked on owner decision |
| [P05](P05-internxt-create-collision-adopt.md) Internxt: a 409 on create of identical content adopts instead of keeping both | #594, #610 | 5 / wave 4 | M | owner decision (identity proof); [P07](P07-local-io-retry.md) optional | blocked on owner decision |
| [P06](P06-parent-gone-and-subtree-tombstone.md) Upload into a folder trashed in the cloud: a typed token and a safe subtree tombstone | #580 | 6 / wave 4 | M | owner decisions (wire token, tombstone rule) | blocked on owner decision |
| [P07](P07-local-io-retry.md) Windows: a file held open by another process fails the transfer and waits a whole poll cycle | #802 | 7 / wave 1 | M | - | ready |
| [P08](P08-unresolvable-rows.md) Rows the daemon lists but cannot open: not_found for files that exist in the cloud | #796 | 8 / wave 3 | M | state.db copy for step 1 | ready (diagnosis first) |
| [P09](P09-internxt-cursor-fallback-followups.md) Internxt cursor listing: what the #879 fallback leaves open | #879 | 9 / wave 2 | M | PR #880 merged | ready after PR #880 is merged |
| [P10](P10-enumeration-error-code.md) daemon.status last_error: readable text and a machine-readable last_error_code | #878 | 10 / wave 1 | S | - | ready |
| [P11](P11-enumerate-retry-scheduling.md) Enumeration retries: the reported next attempt, manual retries and the poller agree | new issue to file | 11 / wave 2 | S-M | [P10](P10-enumeration-error-code.md) | ready after the error-code portion |
| [P12](P12-enumeration-per-stream-state.md) daemon.status.enumeration: report the folders and files streams separately | new issue to file | 12 / wave 3 | M | PR #880, [P10](P10-enumeration-error-code.md) | ready after PR #880 and the error-code portion |
| [P13](P13-enumeration-progress-contract.md) daemon.status.enumeration: expected total and ETA for a scan | new issue to file | 13 / wave 3 | M | [P10](P10-enumeration-error-code.md) ([P12](P12-enumeration-per-stream-state.md) for per-stream totals) | ready after the error-code portion |
| [P14](P14-heap-visibility-and-probe.md) Heap of a full enumeration: a health line and a repeatable probe | new issue to file | 14 / wave 1 | S-M | - | ready |
| [P15](P15-internxt-stage-and-release.md) Internxt full enumeration: stage pages and release them instead of holding the account | #638 | 15 / wave 3 | M | PR #880, [P14](P14-heap-visibility-and-probe.md) | ready after PR #880 and the heap probe |
| [P16](P16-internxt-scoped-walk-followups.md) Internxt folder walk: what the resume work left open | #393, #693 | 16 / wave 3 | M | PR #880 | ready after PR #880 |
| [P17](P17-remote-incomplete-memory.md) A truncated cloud object: remember the refusal per object instead of asking again on every read | #696 | 17 / wave 2 | M | [P18](P18-hydration-close-handle-and-uploads-staleness.md) | ready after the close-handle portion |
| [P18](P18-hydration-close-handle-and-uploads-staleness.md) close_handle across connections, and a staleness signal for the uploads reading | #438, #747 | 18 / wave 1 | M | - | ready |
| [P19](P19-hot-reauth.md) Sign in again while the daemon holds the profile | #813 | 19 / wave 3 | M | PR #876 (merged); pick A or B | ready (pick A or B on the issue first) |
| [P20](P20-pending-rows-cli.md) CLI: inspect and safely drop stranded pending upload rows | #753 | 20 / wave 1 | M | - | ready |
| [P21](P21-http-concurrency-latency.md) HttpRetryBudget: make the concurrency limit real and let rising latency slow a provider down | #517 | 21 / wave 4 | M | PR #880, [P09](P09-internxt-cursor-fallback-followups.md) | ready after PR #880 and the cursor follow-ups |
| [P22](P22-incremental-poll-through-sync.md) Internxt incremental poll through the keyset endpoints | #312 | 22 / wave 3 | M | PR #880 | ready after PR #880 |
| [P23](P23-reconcile-slice-cost.md) Streaming reconcile loads the whole sync_entries table once per delta page | #316 | 23 / wave 3 | M | PR #877 | ready after PR #877 |
| [P24](P24-internxt-multipart-upload.md) Internxt: upload large files as parallel parts | #578 | 24 / wave 4 | M-L | [P21](P21-http-concurrency-latency.md), PR #880 | ready after the concurrency gate |
| [P25](P25-internxt-download-resume.md) Internxt: resume an interrupted download with a Range request | #804 | 25 / wave 4 | M | live Range check by the owner | blocked on a live Range check |
| [P26](P26-spi-ranged-download.md) Ranged download in the SPI, and OneDrive resume of a broken download | #125 | 26 / wave 3 | M | SPI shape decided | ready (SPI shape: decision proposed) |
| [P27](P27-spi-gaps-sha1-mtime-conditional.md) SPI gaps for the next providers: Sha1 hash, mtime precision, conditional-write capability | #846 | 27 / wave 3 | M | - | ready (shape: decision proposed) |
| [P28](P28-onedrive-retry-after-and-budget-docs.md) One Retry-After parser for every provider, and a truthful HttpRetryBudget KDoc | #806 | 28 / wave 2 | S | - | ready |
| [P29](P29-onedrive-driveitem-guards.md) OneDrive items: malware facet, package facet, quota sentinel and the hash fallback | #153, #839 | 29 / wave 2 | M | - | ready |
| [P30](P30-onedrive-drive-id.md) OneDrive: work/school drives other than /me/drive (SharePoint libraries) | #588 | 30 / wave 4 | M | [P29](P29-onedrive-driveitem-guards.md), [P26](P26-spi-ranged-download.md); owner decision (Business in scope) | blocked on owner decision (is Business in scope) |
| [P31](P31-internxt-strict-names.md) Internxt strict compatibility mode: never create a name Internxt's own clients reject | #577 | 31 / wave 4 | M | - | ready (key name: decision proposed) |
| [P32](P32-internxt-prune-backups.md) Internxt: `unidrive prune --backups` for keep_overwritten archives | #155 | 32 / wave 4 | M | PR #880; owner starts it | deferred by the owner, start when asked |
| [P33](P33-dropbox-upload-sessions.md) Dropbox: resumable upload sessions and a live integration test | #867 | 33 / wave 1 | M | - | ready (live test needs the owner) |
| [P34](P34-dropbox-auth-begin-complete.md) Dropbox sign-in for front-ends: auth begin / auth complete with the no-redirect code flow | #866 | 34 / wave 1 | M | - | ready |
| [P35](P35-browse-during-first-scan.md) Mount: list a folder live while the first scan has not written any rows | #515 | 35 / wave 4 | M | [P08](P08-unresolvable-rows.md) (same files) | ready |
| [P36](P36-hydration-download-cancel.md) Mount: a running download stops when the client disconnects or cancels | #437 | 36 / wave 4 | M | - | ready |
| [P37](P37-ignore-mirror-upload-path.md) Ignore files for the mirror engine: LocalScanner and Reconciler upload planning | #660 | 37 / wave 2 | M | PR #877 | ready after PR #877 |
| [P38](P38-history-query-and-verification.md) Transfer history for the Activity view: sort, per-column filters and a verification field | #860 | 38 / wave 3 | M | - | ready (asks 3 and 4 are decisions) |
| [P39](P39-scope-command-and-nfc-roots.md) sync_path scope: NFC roots, a `scope` command, status coverage | #395 | 39 / wave 2 | M | - | ready |
| [P40](P40-cli-rm-command.md) CLI: `unidrive rm` to delete a remote file or folder | #269 | 40 / wave 4 | M | owner decision (mirror profiles) | blocked on owner decision |
| [P41](P41-request-log-lines.md) Request log lines: the server's request id on every response, and the profile in the message | #644 | 41 / wave 1 | S | - | ready |
| [P42](P42-engine-log-policy.md) Engine logs: a quiet default, a per-profile durable log, a WARN side file, the stop reason | #664 | 42 / wave 4 | M | [P41](P41-request-log-lines.md); owner starts it | blocked on owner decision (owner starts it when asked) |
| [P43](P43-mount-binary-search-path.md) Linux mount: find the co-daemon under /usr/lib as well as under the user's home | #799 | 43 / wave 1 | S | - | ready |
| [P44](P44-small-test-gaps.md) Two missing tests: ts-CLI authentication order, and Internxt key-derivation known answers | #168, #336 | 44 / wave 1 | S | - | ready |
| [P45](P45-localfs-engine-tests.md) Engine tests on the localfs provider: mirror drift, exact round trip, seeded Unicode fuzz, hazard names | #589 | 45 / wave 2 | M | - | ready |
| [P46](P46-s3-minio-suite.md) S3: a hermetic MinIO suite and opt-in vendor runs | #869 | 46 / wave 4 | M | owner decision (new test dependency) | blocked on owner decision (new test dependency, Docker on CI) |
| [P47](P47-tests-seams-instead-of-reflection.md) Tests: constructor seams instead of writing final fields by reflection (OneDrive, engine-core, hydration) | #507 | 47 / wave 2 | M | - (Internxt half after the Internxt portions) | ready |
| [P48](P48-cache-containment-helper-dedupe.md) Review residuals of the owner-only work: the cache containment helper and the dry-run permission side effect | #626 | 48 / wave 1 | S | check branch harden/validate-client-paths | ready (check the harden branch first) |
| [P49](P49-dist-install-tests.md) dist: test the stale-jar NOTE of install.sh, and limit the mount unit's pre-start unmount to a dead endpoint | #745, #746 | 49 / wave 1 | S | - | ready |
| [P50](P50-docs-drift-leftovers.md) Docs drift: what is left of the status audit | #412 | 50 / wave 1 | S | - (item 4: owner statement) | ready |
| [P51](P51-runtime-image-cds.md) Packaged runtime image: a class-data-sharing archive, measured first | #688 | 51 / wave 3 | S for the code, M for the measurement | - | ready (measure first) |
| [P52](P52-kotlin-warnings-gate.md) A compiler-warning gate for the Kotlin modules (audit #628 finding 4) | #628 | 52 / wave 4 | S-M | owner decision | blocked on owner decision |
| [P53](P53-reproducible-jar-and-sbom.md) Release engineering: a reproducible shadowJar and an SBOM | #339 | 53 / wave 4 | S for the flags, M with the build-time fix and the SBOM | owner decisions (build instant, SBOM plugin) | ready for the flags, blocked on owner decision for the SBOM |

## Not packaged

Every remaining open issue appears here exactly once (55 issues).

### Close candidates: already fixed on main, obsolete or superseded (16)
- #131 obsolete: `HttpRetryBudget` is an instance class with every constant a constructor parameter; OneDrive, Internxt, Dropbox and S3 pass their own budgets. A per-profile knob would be a different feature (see #130).
- #150 fixed by #721 (61a2fe93): redeploy warning, co-daemon `RUST_LOG` default, profile MDC on hydration uploads.
- #206 superseded: the poll runs by default (`daemon_poll_seconds` 60), the `mode` model was replaced by `docs/adr/independent-profiles.md`; a "last refreshed" line in `status` would be an S leftover if wanted.
- #238 answered: `daemon.shutdown` and stop over IPC (#445), stale lock reclaim (#856), `profile remove --stop` (#795), logout against a running daemon (#876). A verb that deletes the sync root is deliberately not offered.
- #364 obsolete: a finding about the third-party Internxt Desktop app, not engine code (#372 says so); the engine counterpart #303 is closed. Its stale `BACKLOG.md` line is removed in [P50](P50-docs-drift-leftovers.md).
- #372 gate satisfied: all seven listed items (#268, #297, #298, #301, #303, #295, #322) are closed.
- #495 review done: its fixes landed with the #488 squash; the optional "v2 layout" gaps are scripts only (S).
- #542 adopted: detekt #544, #550, ratchet #712 (the spike advised an opt-in task; the owner chose `check`).
- #560 delivered: ADR `independent-profiles.md` and units U1-U7b; close together with #607.
- #586 engine part fixed by #810 (8c0e5fb8, `credential_health`); the client part is in gkrost/unidrive-windows.
- #607 delivered: #794 (explicit wiring order), #701, #703, #705; `cache_backed` is kept on purpose.
- #624 answered: OpenJDK 25 with `-Xms64m` wins, GraalVM not recommended (comment 2026-10-09); follow-ups #686 and #691 merged.
- #805 fixed by #829 (a6c6f87e): local OneDrive name rules, the typed refusal is consumed by the mirror engine, `status --pending`.
- #831 fixed: the named assertion is gone, the test was reworked in #829 and #828.
- #832 fixed by #834 (a367bdab); one optional residual flush budget in the sibling test `a full queue drops ...`.
- #864 fixed by #876 (a0c6c4d3): `logout --json`, `auth.logout`, keep-state default.

### Epic, umbrella, audit or snapshot with no work of its own (12)
- #99 post-MVP umbrella of the frozen tracking-set engine (only #168 and #270 are open children; #168 is in [P44](P44-small-test-gaps.md)).
- #343 decision record (belongs in `docs/adr/`); #360 MVP master tracking; #442 lanes plan; #443 Lane B queue; #458 session snapshot; #534 quick-win lane plan (executed).
- #579 timeout audit (its children #578, #437, #696, #517 are packaged: P24, P36, P17, P21); #702 tech-debt audit (phases 1-2 merged; phase 3 items are in P47, P51, P52 and others); #808 backlog audit (none of its close actions was executed; this index repeats the useful part).
- #650 epic (all first-wave children merged); #651 epic (matcher, layering and the mount are merged; the mirror is [P37](P37-ignore-mirror-upload-path.md); IG-5 `ignore.rules` and IG-6 `unidrive ignore check` have no issue yet).

### Needs an owner decision before any work (11)
- #111 OneDrive webhook lifecycle: a desktop daemon would need a public HTTPS `notificationUrl`; who hosts it, and should the daemon receive webhooks at all?
- #172 retry budget per operation: one sentence, no acceptance; define "operation" and the wall-clock bound (the hand-written retry loops are #702 item 5).
- #253 remove implicit default provider/profile: only `status` is done (#649); one configured profile auto-select, keep `[general] default_profile`, the migration message are open (`profile default`, #852, suggests keeping it).
- #327 AF_UNIX accept loop on Windows: the advisory `check-windows` job runs the daemon start/stop tests; is that enough, or make it required / add a named step (S)?
- #403 deterministic names for duplicate remote items: main suppresses and reports the loser; decorated `(dup-<id8>)` names or a dedupe command need a product decision.
- #514 site cutover to GitHub Pages: DNS, the private infra repository, the CSP downgrade and the Pages setting are the owner's; the in-repo `site/` folder is M once decided.
- #538 CodeQL `java-kotlin` stays commented out (the extractor must support Kotlin 2.4.x); wait, pin or drop is a decision; no SpotBugs triage ticket exists.
- #841 WebDAV, #842 SFTP: re-adding a removed provider needs the owner's go per provider (`docs/adr/shipping-surface.md`); both need the SPI work of [P27](P27-spi-gaps-sha1-mtime-conditional.md) first and are L.
- #843 FTP/FTPS: the issue's own question (native, rclone bridge or out of scope). #845 Google Drive: the restricted-scope / CASA question comes before any code.

### Deferred or without acceptance text (7)
- #124 OneDrive `$batch`, #126 conditional GET (premise weak: DriveItem has no `cTag` field), #127 `$select` slim delta (the listed field set would break the engine; delta `$select` unverified), #129 `createLink` dedupe (what counts as matching is undefined), #154 early 507 (Graph behaviour unverified): Graph efficiency stubs, `lane:deferred`, Post-MVP.
- #564 mode switch for an existing profile: deferred P3 by the owner (2026-10-05).
- #591 CLI extensions: deferred ("extensions are not shipped"); item 1 (plugin directory or `-cp` launchers) decides whether the extension point is usable at all.

### Research, measurement or data work (4)
- #130 per-tenant concurrency calibration: needs a Business and a GCC tenant to measure.
- #132 Internxt trash lag in `/files`: needs a measurement on the endpoints now in use (`/files/sync`), then documentation; a cross-check against folder contents is the larger option.
- #340 competitive landscape note: a research document; commit it as a doc only if the owner wants it.
- #556 census of 19 truncated Internxt objects: data recovery from verified local copies (owner), then `unidrive verify --remote-objects`; the engine side is [P17](P17-remote-incomplete-memory.md).

### Other repository (1), tripwire (1), blocked on an event (2), in progress (1)
- #202 co-daemon connect race: the engine half is fixed (#445 bind last, #723 message); the first-connect retry is in unidrive-mount-linux.
- #270 tripwire: add an engine-level backoff test only if `HttpRetryBudget` is ever wired into the tracking-set engine; no action now.
- #107 first co-daemon release tarball plus `install.sh` download and SHA-256 verify: blocked on a release of the sibling repository (empty today).
- #612 drop the protocol-1 fallback: after the first release that ships the protocol-2 engine and all its clients (the tag `v0.0.1` contains protocol 2; the clients' release is the open part).
- #321 in progress by PR #877 (see above).

## Order of work

Waves say when a portion may start; a wave starts when the portions it depends on are merged (the index column "Depends on"). Within a
wave, portions of the same file family (table below) run one after the other, in the order shown.

| Wave | Portions |
|---|---|
| 0 (in flight) | PR #880 (#879), PR #877 (#321) |
| 1 (start now) | P01, P07, P10, P14, P18, P20, P33, P34, P41, P43, P44, P48, P49, P50 |
| 2 | P09 (after #880), P11 (after P10), P17 (after P18), P28, P29, P37 (after #877), P39, P45, P47; P02 once decided |
| 3 | P08, P12, P13, P15, P16, P19 (after P34), P22, P23 (after #877), P26, P27, P38, P51; P04 once decided |
| 4 | P21, P24, P25, P30, P31, P32, P35, P36, P42, P46, P52, P53; decision-blocked: P03, P05, P06, P40 |

File families that must not run in parallel (take the portions in the order written):

| Family | Files | Order |
|---|---|---|
| Internxt provider core | `InternxtProvider.kt`, `InternxtApiService.kt`, `InternxtCursorListing.kt` | PR #880, P09, P15, P16, P22, P21, P24, P25, P05, P31, P32 |
| Enumeration status | `EnumerationTracker.kt`, `EnumerationStatus.kt`, `EnumerationStatusJson.kt`, `ProviderException.kt` | P10, P11, P12, P13 |
| Hydration core | `HydrationImpl.kt`, `MountEngine.kt` | P18, P17, P08, P35, P36, P03, P06, P02 |
| OneDrive / Graph | `GraphApiService.kt`, `OneDriveProvider.kt`, `DriveItem.kt` | P28, P29, P26, P02, P30 |
| Reconciler and sync | `Reconciler.kt`, `SyncEngine.kt`, `LocalScanner.kt`, `SyncScope.kt` | PR #877, P23, P37, P04, P39 |
| CLI command list | `Main.kt` (new subcommands) | P20, P40, P32, P39 |
| Auth CLI | `AuthLifecycle.kt`, `LifecycleJson.kt` | P34, P19 |
| SPI | `CloudProvider.kt`, `Capability.kt`, `HashAlgorithm.kt` | P26, P27 |
| HTTP plugins and budget | `RequestIdPlugin.kt`, `HttpRetryBudget.kt`, provider API classes | P41, P10, P28, P21 |
| Build and launchers | `core/app/cli/build.gradle.kts`, `dist/launcher/*` | P01 (templates only), P51, P52, P53 |

P14, P43, P44, P45, P46, P47, P48, P49 and P50 are file-disjoint from the rest (P47 touches many test files but no production file apart
from defaulted constructor parameters).

## Stale planning documents

- `lane-bugs-quickwins.md` (tracked, 2026-10-11): its Tiers 1-3 are merged. Merged since, from its "still open" list: #700 by #856, #740 by #857, #741 by #835, #742 by #847, #748 by #786, #394 by #781. What it still carries is covered here: #395 NFC part in P39, #747 in P18, #696 in P17, Q2 (#412) in P50, Q7 (#626 item 3) in P48, Q8 (#688) in P51. Its "Hands off" list is outdated; the desktop-UI epics are mostly merged.
- `lane-desktop-ui.md` (tracked): N1-N4, M2 (pause #801, #862) and M4 (#816 by #848, #817 by #852) are merged; M1 is P37; M3 is P19; #664 is P42; the rest of the #651 epic has no issue yet.
- Untracked, not on main: `agent-correctness-worklist.md` (#741 and #740 are merged; #577 and #153 are P31 and P29; its "assigned, do not duplicate" list is now P02 #511, P03 #491, P05 #594, P04 #309, P22 #312; #439 and #448 are closed; #805 is fixed); `Agent_open-bug-sweep.md` (2026-10-07, superseded by this index); `Agent_internxt-cursor-sync-windows-gaps.md` and `Agent_server-side-cursor-sync.md` (the comparison report below corrects two statements: the status filter is sent only when a stream starts at the epoch, and the official client does have a two-minute rewind; the look-alike letter of windows#213 stays because the 2.7.0 live-root sweep still matches "internxt").
- "Assigned to gkrost" on many issues is the account the agents run under; it is not a lock. Claim by comment.

## From the comparison report

`docs/dev/plans/Agent_internxt-vs-unidrive-comparison.md` (read from the owner's checkout; it describes the measured official client 2.7.0 built from `feat/2-7-0-release`, not the git tag `v2.7.0`). Its ranked adopt list:

| # | Adopt | Where |
|---|---|---|
| 1 | Status-free `updatedAt` continuation for a stalled sync stream | PR #880 (in progress); open edges and the return to the `EXISTS` filter in [P09](P09-internxt-cursor-fallback-followups.md) |
| 2 | Persist and forget each page; heap doubles after a resume | [P15](P15-internxt-stage-and-release.md) (#638), measured by [P14](P14-heap-visibility-and-probe.md) |
| 3 | Incremental poll through `/sync` from the rewound `updatedAt` | [P22](P22-incremental-poll-through-sync.md) (#312) |
| 4 | Multipart upload with parallel parts | [P24](P24-internxt-multipart-upload.md) (#578; no PR is in flight on main) |
| 5 | Server `x-request-id` on every response line | [P41](P41-request-log-lines.md) (#644) |
| 6 | Thumbnails after create and replace | new, not packaged: a feature with no issue; it touches the upload path and needs the owner's scope decision first |
| 7 | Periodic health line (heap, GC, queue depth, enumeration state) | [P14](P14-heap-visibility-and-probe.md) |
| 8 | WARN-and-above side log with its own retention | [P42](P42-engine-log-policy.md) (#664 first half) |
| 9 | "Check the index against the cloud" action | new, not packaged: client-led (gkrost/unidrive-windows); the engine half would be a read-only drift report beside `verify` and `doctor`, to design when the client asks |
| 10 | Explorer verb "Copy share link" | client repository only |

Also from the report, folded in: the 408 is missing from `TRANSIENT_STATUSES` although the shared policy table says retry ([P09](P09-internxt-cursor-fallback-followups.md), [P28](P28-onedrive-retry-after-and-budget-docs.md)); `HttpRetryBudget.maxConcurrency` limits nothing, so the README's "2 concurrent" overstates ([P21](P21-http-concurrency-latency.md)).

## What could not be verified

- No build, test or live call was run; every acceptance command is unrun. Test classes that do not exist yet are marked "new" in their portion.
- Server-side facts stay `[unverified]` until the owner runs the live checks named in P09 (status-less page body), P25 (Range on the signed URL), P46 (Scaleway checks) and P14 (heap histogram).
- The capture numbers come from the run folders of 2026-10-14 (engine log, status JSON, process sampler CSV, a copy of the Internxt `state.db`); nothing from the live profile folders was read.
- Whether the owner clicked Retry now at 17:49:5x (attempt 5) is inferred from the missing poller log line, see P11.
