# Lane: desktop-UI data and verbs (gkrost/unidrive) — for an agent

Prepared against `main` at `b3554149` (after #670, the U6 cutover). Scope: the open children of epic **#650**
(engine data and verbs for desktop front-ends) and of epic **#651** (ignore files), label `lane:desktop-ui`.
#655 (quota snapshot) is done (#669). Every anchor below was re-read on `main`; line numbers drift, so trust the
symbol. Work the tiers **in order**; within a tier the items are file-disjoint and may run in parallel. One item
= one branch = one **draft** PR; the owner merges.

## Rules (read before any code)
- **Claim:** comment on the item's issue ("lane: starting, branch …"). Re-check that it is still open and that no
  open PR touches the same files: `gh pr list --state open`.
- **Workspace:** never a worktree another session owns. `git fetch origin`, then
  `git worktree add ../wt-ui-<issue> -b feat/<issue>-<slug> origin/main`. One branch and one draft PR per item,
  against `main`, never stacked.
- **Test-first:** show the new test failing on `main`, then passing; both go in the PR body.
- **Wire rules (epic #650):**
  - additive changes only; `protocol_version` unchanged;
  - every new verb or event gets a fixture in `core/app/cli/src/test/resources/ipc-contract/` **first** (`IpcContractCorpusTest`);
  - a new read verb is added to the READ class in `IpcAuth.VERB_CLASSES`; the corpus test keeps the table complete;
  - **unknown stays unknown:** a missing value is absent or `null`, never `0` or "healthy".
- **Gate:** CI's `./gradlew check` green on the PR before it leaves draft. Locally only the touched test classes, never a
  full `./gradlew check` unless the owner asks (AGENTS.md). Known flakes: `DaemonRuntimeTest`, `IpcContractCorpusTest`;
  re-run the failed job once.
- **Hands off (in flight or owned elsewhere at prep time):**
  - **#607 U8** (bridge and schema-leftover removal, another lane) owns `MountWiring`'s bridges, `SyncRootBridge`,
    the test-scope module dependency and the `state.db` leftovers. Tier 3 waits for it or coordinates on the
    issue first.
  - **#695** (open PR): `RemoteGather` / `InternxtProvider` enumeration retry.
  - **#586** (credential health in `daemon.status`) shares the status line with U4. Either fold it into U4
    (say so on both issues) or land it after U4. Never two PRs on that line at once.
- **The owner's machine:** no deploy, no live account, no running `unidrive`/`java` process touched. Fakes,
  `localfs` or temp dirs only. A repro signals only the PID it started.
- **Public repository:** no private paths, names or addresses in code, tests, commits or PRs. No issue
  IDs or dates in commit subjects.
- **Report:** a comment on the issue with the PR number, red/green evidence, the gate result and anything
  left.

## Tier 1 — start now (file-disjoint)

### U1 — #657 Observer-safe subscription: a READ-scope subscribe must not enumerate
- **Anchor:** `DaemonRuntime.run`, the mount-mode handler loop: `if (verb == "hydration.subscribe" && reply.contains("\"ok\":true"))`
  calls `server.scheduleAfterReply(connId) { enumerateHandler.runGuarded(...) }` with no scope check.
  The scope lives in `IpcServer.authSessions[connId].scope` (private).
- **Fix:** add `IpcServer.scopeOf(connId): String?`. Schedule the enumerate only when the scope is `full`, or when
  auth is off (protocol-1 fallback, until #612). The reply itself is unchanged.
- **Tests:**
  - a read-scope connection subscribes, and no enumerate job starts (assert on the enumerate handler / `refresh_job_id`);
  - a full-scope subscribe still enumerates once;
  - an unauthenticated (auth-off) daemon behaves as today.
- **Do not:** add a request flag, or change `hydration.open_read` (it stays READ; front-ends must simply not call it).
- **Size:** S.

### U2 — #656 Structured account lifecycle (CLI only, in up to three PRs)
- **Anchors:** `ProfileCommand` requires `System.console()` for `add` (the `add` run, and a second site in the
  same file); `profile list` prints a table; `remove` rewrites the config and deletes the profile folder.
- **PR a:** `profile list --json`, `profile set <name> <key> <value>` for the documented keys only (unknown keys
  refused; "restart the daemon" in the reply), and a free-text `label` key. The profile name stays the
  immutable id.
- **PR b:** non-interactive `profile add --type --name --mode [--label] --json`. Credentials never come from
  arguments:
  - `auth begin`/`auth complete` for the device-code flow;
  - `auth login --email … --password-stdin [--totp …]` for Internxt.
  
  New profile folders and credential files go through the same owner-only permission helpers as the console path.
- **PR c:** `profile remove` refuses while a daemon holds the profile (machine-readable `error` token), unless
  `--stop`. It also removes the IPC token files and the socket. The JSON result says what was kept.
- **`profile default <name> [--json]` / `profile default --clear`:** rewrites only `[general] default_profile` and
  starts no daemon. Reply `{"ok":true,"default_profile":<name|null>,"previous":<name|null>,"message":...}`; refusals use
  `{"ok":false,"error":<token>,"message":...}` with the tokens `profile_not_found`, `invalid_profile_name`,
  `missing_value`, `invalid_value`, `config_not_found`, `config_invalid`, `write_failed`. Exit code 0 / 1.
- **Tests:** each command with no console (CI-style); no secret in argv, logs or JSON; existing interactive tests
  unchanged.
- **Must not:** auto-spawn a daemon (`DaemonAutospawn` is for `mount`/`refresh`). A front-end calls these
  while the daemon may be stopped on purpose.
- **Size:** M.

### U3 — #659 Ignore matcher with a git-conformance oracle (library only)
- **Today:** `Reconciler.matchesGlob` / `matchesGlobRegex` is a custom glob with no negation and no nested files.
  Leave every call site alone in this item.
- **Do:** a new matcher (own package) implementing git's pattern rules:
  - anchoring, trailing `/`, `**` forms, `!` with git's re-include limit, escapes, trailing spaces;
  - `explain(path)` → winning rule and source line.
  
  Pick an own implementation or JGit's ignore classes; **the oracle decides**, not preference.
- **Oracle:** a corpus of rule sets plus path lists, compared with `git check-ignore --stdin -z -v -n --no-index` in a
  temp repo. CI pins one git version; skip locally with a clear message when git is absent.
  - Include `core.ignorecase=true` runs and NFC/NFD names.
  - Record every intentional deviation in a doc.
- **Acceptance:** zero unexplained differences; a 1 M-path benchmark with directory pruning, with the time stated
  in the PR.
- **Size:** M.

## Tier 2 — after U1 (same file region: the `daemon.status` reply in `DaemonRuntime`)

### U4 — #658 Upload and cache health, last provider contact, in `daemon.status`
- **Anchors:**
  - the status reply in `DaemonRuntime` (the single `{"ok":true,"protocol_version":…}` string);
  - `HydrationImpl.uploadSlots` (in-flight and queued uploads);
  - the `local:` rows awaiting upload in `state.db`;
  - the cache files counted by `HydrationImpl.evictCache` / `listCacheFiles`;
  - `EnumeratePoller.providerReachable()`, the hook for "last successful provider contact".
- **Add (additive):**
  - `uploads {pending, in_flight, failed, oldest_pending_age_ms}`;
  - `cache {bytes, budget_bytes}`;
  - `provider {last_contact_ms}`.
  
  Pending counts **include** the start-up replay's own uploads (`engine-replay-*`) and **exclude** excluded
  (keep-local) rows.
- **Tests:** fixture + contract test; a stalled upload shows a growing `oldest_pending_age_ms`; a provider outage
  keeps `last_contact_ms` (it doesn't reset it).
- **Size:** S–M.

## Tier 3 — history and metrics (after #607 merges, or after agreeing file boundaries on #607)

### U5 — #652 Durable transfer history in the mount daemon
- **Anchors:**
  - `MountWiring.auditLog: AuditSink?` and `interface AuditSink` (engine-core). `MountEngine` already emits to it.
  - The daemon builds the engine without a sink (no `auditLog` in `DaemonRuntime`). `AuditEntry` (sync) has no timing or attempts.
- **Do:**
  - a per-profile `telemetry.db` (its own file, not `state.db`), with a bounded writer queue, retention by age and size, owner-only permissions, and an explicit disk-full behaviour;
  - an `AuditSink` implementation the daemon passes in;
  - logical-transfer rows written at the `Completed` points in `HydrationImpl` (`launchSerializedUpload` / `runUploadWithRetries`), so start-up replays and identical-bytes skips (`result=skipped`) are recorded too;
  - fields `queued_at`, `started_at` (transfer permit acquired) and `finished_at`. Queue wait and transfer time are never one duration;
  - a crash leaves an `interrupted` row, never a success.
- **Tests:** a row per direction; a failed attempt followed by a success; 50k small uploads in a burst do not slow the
  path measurably (number in the PR); retention prunes; no URLs or secrets in messages.
- **Size:** M.

### U6 — #653 History read verbs with stable paging and a reconnect cursor (after U5)
- READ-class `history.list` (filters, sort, cursor, limit) and a tail stream with a `lost` marker on overflow, as
  `hydration.subscribe` has. Fixtures first. **Size:** S–M.

### U7 — #654 Request-attempt metrics (after U5's writer exists)
- **Anchor:** `RequestIdPlugin` (`createClientPlugin("UnidriveRequestId")`) measures response-header time
  only.
- **Do:** an attempt table in `telemetry.db`: endpoint class (templated, no ids), status class or transport-error class,
  header time, total time (body included), bytes, retry number, throttle flag. Add per-minute roll-ups and a READ
  `metrics.query`.
- **Tests:** a large body has header time < total time; a 429 and a reset are distinct classes; overhead < 1 %.
- **Size:** M.

## Tier 4 — later in this lane
- **#660** discovery, layering, caching and the overridable `.git` default (after U3). Before any call site moves,
  settle on the issue:
  - (a) an ignored file in a **mount root** must not become a "leftover" at the next remount;
  - (b) ignore stops uploads only; cloud items stay visible.
- **#664** engine logs (quiet default, per-profile log with the stop reason): independent of the tiers above. Start
  it whenever the owner asks.

## Parallel plan
```
A: U1 ─▶ U4            (DaemonRuntime status/subscribe region)
B: U2 a ─▶ b ─▶ c      (ProfileCommand / AuthCommand)
C: U3 ─▶ #660          (new ignore package)
D: [#607 merged] ─▶ U5 ─▶ U6, U7   (MountWiring / HydrationImpl / telemetry.db)
```

## Status and the next wave

The tiers above are the original brief. This section is the current state; an agent picking an item starts here.

### Done
| Item | PR |
|---|---|
| U1 #657 observer-safe subscribe | #699 |
| U2 #656 PR a (`profile list --json`, `profile set`, `label`) and PR b (non-interactive `profile add`, `auth begin` / `complete` / `login --password-stdin`) | #724, #725 |
| U3 #659 ignore matcher with the git oracle (clean-room; 0 differences on 1444 verdicts) | #710. The issue is still open: close it, #660 carries the rest |
| U4 #658 upload, cache and provider health in `daemon.status` | #722 |
| #607 U8 (a), (b)+(d), (e) | #701, #703, #705 |

Re-checked on `main`: `DaemonRuntime.run` still has its late-bound references (`hydrationForShutdown`, `hydrationIpcRef`,
`hydrationRef`, `hydration`, `pollerRef`). `ProfileRemoveCommand` still asks on the console and has no daemon check. No
`telemetry.db`, no history or pause verb exists. The new `sync.ignore.IgnoreMatcher` has no call site yet; every exclude still goes through
`Reconciler.matchesGlob` (`DoctorCommand`, `LegacyInventory`, `StatusCommand`, `VerifyCommand`, `RemoteOperationGuard`).

### Next, in this order
The rules at the top apply unchanged: claim on the issue, one branch and one draft PR per item, test-first, CI as the gate, no full local
`./gradlew check`.

**N1: #607 (c), the late-bound references in `DaemonRuntime.run`.**
- **Do:** turn the nullable `var … = null` references that are filled further down into an explicit construction order, or into an explicit
  event dependency where a cycle is real (the poller comment says "Late-bound: the poller is built further down").
- **No behaviour change:** the IPC replies, the shutdown order and the log lines stay the same.
- **Tests:** the existing `DaemonRuntimeTest` stays green. A request that arrives before the hydration exists gets an error reply,
  never a `NullPointerException`. Pin today's reply in a test first; if there is no defined reply today, define one and say so in the PR.
- **Owns `DaemonRuntime.kt`** while it runs: no other PR of this lane edits that file.
- **Size:** M. It gates N3's wiring and #586.

**N2: #656 PR c, a safe `profile remove`.**
- **Do:**
  - refuse while a daemon holds the profile, with the machine-readable error token `daemon_running`;
  - detect that read-only (the profile's process lock and a socket that answers); never autospawn;
  - `--stop` asks the running daemon to stop over IPC first, and waits a bounded time;
  - `--yes --json` for front-ends, so no console is needed;
  - remove the IPC token files and the socket with the profile;
  - the JSON result says what was removed and what was kept: the config section, the state folder, the credentials, and **never** the
    local sync root or mount root.
- **Tests:** no console (CI style); the refusal while a fake daemon holds the lock; no secret in the output.
- **Size:** S–M. It unblocks the client's account removal (gkrost/unidrive-windows#264).

**N3: U5 #652, the history store, in two PRs.**
- The "claimed" comment of 2026-10-08 on #652 is stale: no branch and no session behind it. Say so when you claim.
- **PR 1 (now, in parallel with N1):** the store only, in its own package, with no edit to `DaemonRuntime` or `HydrationImpl`:
  - a per-profile `telemetry.db` with a bounded writer queue and owner-only permissions;
  - retention of 90 days or 200 MB, whichever comes first, configurable (the owner's decision on #652);
  - an explicit disk-full behaviour. Proposed: drop history rows and never block a transfer. Confirm it on the issue before building;
  - an `AuditSink` implementation with `queued_at`, `started_at` and `finished_at`;
  - an `interrupted` row after a crash, never a success.
  
  Tests: retention, crash recovery, and the 50k-row burst number.
- **PR 2 (after N1 merges):** the daemon passes the sink. Rows are written at the `Completed` points in `HydrationImpl`, including the
  start-up replay and identical-bytes skips.
- **Size:** M + S.

**N4: #660, discovery, layering and the call sites.**
- The two questions the tier-4 note asked to settle are **answered by the owner's D3** (recorded on #651):
  - ignore stops uploads only, and the cloud item stays visible;
  - `.git` is excluded by default but overridable, and `.gitignore` is opt-in per account;
  - an ignored file stays in place in a mount root and never becomes a leftover.
- **PR 1:** discovery, layering and caching with `explain(path)`, with no call site moved.
- **PR 2:** the upload path, one call site at a time. A front-end needs no new verb for that yet.
- **Hands off:** check the open PR #788 (refusing case collisions during upload recovery) before you touch `Reconciler`.
- **Size:** L.

**Later:**
- U6 #653 and U7 #654, after N3.
- #586 (credential health), after N1: it edits the same status reply.
- #778 and #789 (pause): postponed by the owner (gkrost/unidrive-windows#358). Not before the client's cards, Start action and first-run
  wizard.
- #664: when the owner asks.

### Parallel plan, next wave
```
A: N1 (DaemonRuntime) ─▶ N3 PR 2 ─▶ U6, U7 ; #586
B: N2 (ProfileCommand)
C: N3 PR 1 (new telemetry package)
D: N4 PR 1 (ignore package) ─▶ PR 2 (upload path, after #788)
```

## Status after the history wave
Re-read against the open issues and PRs; the section above is the previous wave.

### Done since
| Item | PR |
|---|---|
| N1 #607 (c) the explicit wiring order in `DaemonRuntime.run` | #794 |
| N2 #656 PR c, safe `profile remove` (`daemon_running`, `--stop`, `--yes --json`) | #795; #656 closed |
| N3 #652 history store and daemon wiring | #797, #812; #652 closed |
| U6 #653 `history.list` / `history.subscribe` | #818 |
| U7 #654 `metrics.query` | #820 |
| #586 credential health in `daemon.status` | #810 |
| N4 #660 PR 2a: the mount holds ignored paths back, `ignore.explain`, the status field | #819 |
| deflaking the timing tests | #834 |

### Owner decisions from the Windows client questionnaire (gkrost/unidrive-windows#258)
- **Re-authentication while the daemon runs is solved here (#813):** `auth` hands the new credentials to the running daemon over IPC instead of refusing with `profile_in_use`. The daemon is not stopped. #743 is closed in favour of #813.
- **Pause is un-postponed (#778):** manual pause/resume and `paused` in `daemon.status` start now. The timed pause #789 comes after.
- **The clients stay mount-only for v1:** no front-end needs mirror verbs.

### Next, in this order
**M1: #660 PR 2b, ignore for the mirror (`LocalScanner` / `Reconciler` upload planning).**
- In flight in worktree `wt-ui-660c`, branch `feat/660-ignore-mirror`; no PR yet.
- **Size:** M.

**M2: #778 pause and resume.**
- **Do:**
  - `daemon.pause` / `daemon.resume` (admin scope);
  - `paused` plus a reason in `daemon.status`;
  - a paused daemon starts no transfer and finishes nothing half-way unsafely;
  - reads and hydration of already-local bytes keep working.
  
  Say in the PR what a paused daemon does with an `open` that needs a download: that is a product question, so ask it on the issue before building.
- **Tests:** contract fixtures for both verbs and the status field, and a pause during an upload burst.
- **Size:** M. Then #789 (`until`).

**M3: #813 hot re-auth.**
- **Do:**
  - `auth begin` / `complete` / `login` on a held profile hand the new token to the daemon (an admin verb, or the token file plus a reload signal; pick one and say why);
  - the daemon swaps credentials without a restart;
  - `credential_health` turns `ok`.
- **Tests:** a fake daemon holding the lock, and no secret in any argv or log.
- **Size:** M. The client's Sign in works unchanged after it.

**M4: #816 and #817, account lifecycle follow-ups.**
- #816: `profile remove` refuses while a **stopped** profile's state still has pending uploads; a `failed` count is reported apart from pending.
- #817: a non-interactive way to set or clear the default profile.
- **Size:** S each.

**Later:**
- #664 (logs), when the owner asks.
- #651 epic remainder after M1.

### Parallel plan, this wave
```
A: M1 #660 PR 2b (Reconciler)
B: M2 #778 pause (DaemonRuntime / status) ─▶ #789
C: M3 #813 hot re-auth (auth CLI + daemon)  — coordinate with B on DaemonRuntime
D: M4 #816, #817 (ProfileCommand)
```
