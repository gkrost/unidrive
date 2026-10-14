# P21 HttpRetryBudget: make the concurrency limit real and let rising latency slow a provider down

| | |
|---|---|
| Issues | gkrost/unidrive#517 (the open remainder R7; the rest of its recommendations is delivered or sits in P24 and P16); a new issue for the latency part must be filed first |
| Labels | bug (R7), enhancement, area:internxt, kind:reliability, kind:efficiency |
| Size | M |
| Priority | rank 21 of 53 (first-sync group), wave 4 |
| Status | ready after PR #880 and the cursor follow-ups |
| Depends on | P09 merged (both edit `InternxtApiService.authenticatedGet`); PR #820 telemetry is already on main |
| Not in parallel with | P09, P28 (same `HttpRetryBudget` KDoc); same file family (take them one after the other, order in README): P09, P15, P16, P22, P24, P25, P05, P31, P32, P41, P10, P28 |
| Status check | verified on main a0c6c4d3, 2026-10-14: `currentConcurrency()` still has no caller outside tests |

## Problem
- [measured] Mean latency per minute of the account-wide `drive/{files,folders}/sync` requests in the capture run (`unidrive.log`, 17:22-17:27): 1.8 s (39 requests), 2.5 s, 3.4 s, 8.3 s at 17:25 (one `folders/sync` call took 83.6 s and was answered 200), 3.9 s; the 408 followed at 17:27. Later healthy pages took 3-5 s. The two streams (folders, files) keep one request in flight each, so two heavy queries run at once against one account.
- [inferred] Whether our own parallel heavy queries contribute to the slowdown is unknown (#517 F3: the two requests of a pair finished together in 13 of 13 cases, which fits one shared bottleneck). The 408 of #879 is deterministic and is not cured by this portion; this is about being a considerate client and about wasted time.
- [code] `HttpRetryBudget` (`:app:core`, `http/HttpRetryBudget.kt`): `maxConcurrency` seeds `concurrency`; `currentConcurrency()` has no caller outside tests; `awaitSlot` waits for the circuit breaker, the priority lane and the spacing only, never for a free slot. `driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 500, ...)` and `bridgeBudget(4)` in `InternxtApiService` therefore limit nothing, while the comment above them in `InternxtApiService` ("Drive REST: maxConcurrency=2, minSpacing=500ms") and `core/providers/internxt/README.md` (lines about 38 and 48: "2 concurrent with 500 ms spacing") present them as limits; steady-state spacing is 0 as well (UD-200). The same holds for `GraphApiService` (8), `DropboxApi` and `S3Api`.
- [code] The budget reacts to 429/503 only (`recordThrottle`) and recovers on clean time (`recordSuccess` after 5 minutes). Latency is not an input, although `RequestMetricsRecorder` (PR #820) already measures header time and total time per attempt.

## Scope
- Change:
  1. `HttpRetryBudget`: a real gate. Either `suspend fun <T> withSlot(priority, block)` or acquire/release backed by a resizable semaphore whose size follows `currentConcurrency()`; shrinking never revokes permits already held. `awaitSlot` stays for callers not yet converted, deprecated in the KDoc.
  2. `recordLatency(ms, ok)` with an EWMA against a baseline (a low percentile of a rolling window): above `k` times baseline for `n` consecutive samples halve the concurrency (floor 1), after `m` clean samples add 1 (the 429-storm logic stays as it is). Constants named, logged at INFO on every change.
  3. Callers: `InternxtApiService.authenticatedGet` (the cursor streams then alternate when the limit is 1); the other call sites of `awaitSlot` follow in the same change or are listed in the PR as a follow-up. Do not change what is retried or the Retry-After handling.
- Must not change: the 429/503 storm behaviour, priorities, `HttpDefaults`, upload paths (the bridge PUT keeps its own watchdog).
- Out of scope: per-provider override (P28), the 408 handling, multipart parallelism.

## Acceptance criteria
1. `HttpRetryBudgetMatrixTest` (`app/core`, injected clock) and `HttpRetryBudgetTest` (`providers/onedrive`) stay green unchanged.
2. New test (virtual time): 10 callers with a limit of 2 never have more than 2 in the block at once; after `recordThrottle` storms the limit is 1 and 1 holder finishes while the others wait.
3. Latency test: samples at 5 times the baseline reduce the limit to 1 within `n` samples; clean samples restore it step by step; a single slow sample changes nothing.
4. Internxt: `InternxtCursorListingTest` still completes with a limit of 1 and with 2; with limit 1 the requests of the two streams never overlap (record start/end in the fake `DriveMock`).
5. No deadlock when the limit shrinks to 1 while 2 permits are held (test).
6. The `InternxtApiService` comment and the README lines name what is really enforced after this change.
Commands: `cd core && ./gradlew :app:core:test --tests '*HttpRetryBudget*' && ./gradlew :providers:onedrive:test --tests '*HttpRetryBudgetTest' && ./gradlew :providers:internxt:test --tests '*InternxtCursorListingTest' --tests '*InternxtGetRetryTest'`.

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
1. Thresholds: `k` = 4, `n` = 5, `m` = 20, baseline = 20th percentile of the last 100 samples? Proposal only; the PR must show the values against the measured minute above.
2. Should the files stream yield to the folders stream when only one slot is left (folders gate path resolution)? Proposal: no, alternate fairly.

## How to verify live
Owner: first scan of the large account with the health line of P14; the INFO lines for concurrency changes appear when latency passes the threshold; the scan completes no later than on main (about 21-25 minutes of file stream).
