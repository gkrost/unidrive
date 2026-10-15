# P11 Enumeration retries: the reported next attempt, manual retries and the poller agree

| | |
|---|---|
| Issues | none filed yet: file one before starting ("enumeration: next_attempt_at_ms is lost after a manual retry"); related gkrost/unidrive-windows#439 |
| Labels | bug, kind:reliability, kind:observability |
| Size | S-M |
| Priority | rank 11 of 54 (first-sync group), wave 2 |
| Status | ready after the error-code portion |
| Depends on | P10 merged (same tracker/status files) |
| Not in parallel with | P12, P13; same file family (take them one after the other, order in README): P10, P12, P13 |
| Status check | verified on main a0c6c4d3, 2026-10-14: the behaviour below is present in the code |

## Problem
- [measured] Engine log of the capture run (`p3-final/trace/engine-logs/unidrive.log`), Internxt profile, five attempts. The poller logs "auto-poll: enumerate failed ... backing off to N ms" for attempts 1 (17:27:48, 240 s), 2 (17:33:35, 600 s) and 4 (17:44:50, 600 s). Attempts 3 (17:36:38, after the owner's Retry now) and 5 (17:49:56) have no such line: they were not poller ticks. Attempt 4 started 17:42:54, inside the 600 s plus or minus 10 percent window after 17:33:35; the next poller tick after attempt 4 was due about 17:55 (the client showed about 17:55:42). Attempt 5 ran about five minutes before that.
- [measured] `p3-final/daemon-status-internxt.json` (17:54:24): `"state":"failed","attempt":5,...` and NO `next_attempt_at_ms`, although the poller was still sleeping towards about 17:55:42. After a manual retry the client has no retry time to show.
- [code] `EnumerationTracker.begin()` sets `nextAttemptAtMs = null`. Only `EnumeratePoller` calls `onNextAttempt` (wired in `DaemonRuntime` to `enumerationTracker::nextAttemptAt`), so a manual `refresh.run` / `sync.enumerate` that fails leaves a `failed` status with no schedule, while the poller's sleep goes on unchanged.
- [code] Related behaviours: a poller tick that meets a running manual enumerate returns null from `runGuarded` and the loop sets `nextMs = intervalMs`, which drops an escalated backoff; manual attempts increment `attempt` and `enumerate_failure_streak` (`RemoteEnumeration.recordEnumerateFailureStreak`) but never the poller's `nextMs`; `providerReachable()` ends a back-off one plain interval after the failure, and if that time has passed the poll starts at once (`sleepInBackoff`) without updating the reported time before it begins.
- [unverified] Whether the owner clicked Retry now at 17:49:5x; the log shows only that attempt 5 was not a poller tick. The 17:55:42 figure is from the client; a local-time conversion error is not excluded, the UTC epoch values in the status JSON are consistent with 17:49:52 (attempt 5 start).

## Scope
- Change: `EnumeratePoller` (a second reporting path), `EnumerationTracker` (`failed()` / `nextAttemptAt`), `DaemonRuntime` wiring (one line), `RemoteEnumeration` (record who started the attempt only if needed).
  1. After any failed attempt, manual or polled, `next_attempt_at_ms` shows the time the poller really starts its next attempt (re-published when the tracker leaves RUNNING, or the poller republishes its pending time at the end of every attempt).
  2. A manual attempt that SUCCEEDS resets the poller's backoff (next poll one plain interval later); a manual attempt that FAILS does not shorten or lengthen the poller's schedule but is shown as such.
  3. A tick skipped as busy keeps the current backoff level instead of resetting to the plain interval.
  4. When `providerReachable()` starts a poll at once, the status says so (`next_attempt_at_ms` = now) before the attempt begins.
- Must not change: jitter of plus or minus 10 percent, backoff x4 capped at 600 s, the failure-streak seeding at start, the `attempt` counter meaning, the wire field names.
- Out of scope: how the client words times; whether a manual retry should skip the backoff (it does today and should).

## Acceptance criteria
1. Virtual-time test in `EnumeratePollerTest` (fixtures `RecordingEngine`, `EnumerateRpcHandler`, `pollTest`): poller fails (reports T1), a manual failing enumerate runs before T1, the status after it still reports T1; the poller then starts exactly at T1 (jitter injected as identity).
2. Manual success during a backoff: the next poll is one plain interval later, `next_attempt_at_ms` absent after success.
3. A busy-skipped tick after two failures keeps `nextMs` at the escalated value (assert the next sleep).
4. `providerReachable()` after the plain interval has passed: the reported time moves to now before `begin()`.
5. Tracker-level test in `EnumerationTrackerTest`: `failed()` after a `begin()` keeps a previously set next attempt only when the poller republished it (document the chosen mechanism in the class comment).
6. The IPC fixture for a failed enumeration with `next_attempt_at_ms` is unchanged; `IpcContractCorpusTest` green.
Commands: `cd core && ./gradlew :app:cli:test --tests '*EnumeratePollerTest' --tests '*EnumerateRpcHandlerTest' --tests '*IpcContractCorpusTest' && ./gradlew :app:engine-core:test --tests '*EnumerationTrackerTest'`.

## Constraints
- Create your own worktree from origin/main (`git fetch origin`, then `git worktree add ../wt-<slug> -b <branch> origin/main`); never work in a shared checkout or in a worktree another session owns.
- Never `git stash`. Merge main into the branch (no rebase, no force-push).
- One branch and one DRAFT pull request for this portion; the owner merges. Check `gh pr list --state open` for a PR on the same files first.
- Claim each issue with a comment ("starting, branch ...") before editing, and report the PR on the issue.
- Commit messages describe the change; engine commits carry no ticket id in the subject, the PR body names the issues. End every commit with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and the PR body with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Line endings follow `.gitattributes` (LF; only `*.bat`, `*.cmd`, `*.ps1` are CRLF): keep each file's endings. `core/gradlew.bat` always shows an EOL-only change in a Windows checkout: never commit it.
- detekt must pass against the per-module baselines (never regenerate a baseline; there is no ktlint in the build) and add no compiler warnings.
- Run only the touched test classes locally (`cd core && ./gradlew :app:<module>:test --tests '<class>'`); CI runs `./gradlew check` on the PR (read it with `gh pr checks`).
- Public repository: no private paths, account names or addresses in code, tests, commits or PRs. No live account, no deploy, no running `unidrive`/`java` process touched; use fakes, `localfs` or temp dirs.

## Open decisions for the owner
1. Should a failed manual retry count towards the poller's backoff level (so a user mashing Retry now does not hide an escalating failure)? Proposal: no; show it, do not escalate.

## How to verify live
No live check needed; the p3-final status JSON above is the failing case. Optional: on the Windows machine click Retry now during a backoff and confirm the flyout still shows a retry time.
