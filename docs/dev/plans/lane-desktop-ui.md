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
- **Gate:** `cd core && ./gradlew check` green before a PR leaves draft. Known flakes under parallel load:
  `DaemonRuntimeTest`, `IpcContractCorpusTest`. Re-run the class once in isolation; serialize gradle runs across
  worktrees.
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
