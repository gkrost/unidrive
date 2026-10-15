# P54 HistoryReaderTest: the writer-visibility assertion stops racing the writer thread

| | |
|---|---|
| Issues | gkrost/unidrive#882 |
| Labels | - (none on the issue yet; `bug` fits) |
| Size | S |
| Priority | rank 54 of 54 (tests group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | [P38](P38-history-query-and-verification.md) (both touch the telemetry history tests; this one is wave 1 and P38 is wave 3 — merge this first) |
| Status check | verified on main a0c6c4d3, 2026-10-14: the assertion is unchanged since #818; it failed in CI the same day (evidence in the issue) |

## Problem
- [measured] `check-windows` failed on PR #877 (a sync-module PR that touches no telemetry code), 2026-10-14: `HistoryReaderTest > reading goes through its own connection and does not wait for the writer thread FAILED` with `expected:<5> but was:<1>`. The same commit passed on re-run (~23 min under runner contention) and on both Linux legs. Evidence and links: gkrost/unidrive#882.
- [code] `TransferHistory.enqueue` is `writer.offer(row)` on a bounded, drop-on-full, non-blocking queue (`core/app/engine-core/src/main/kotlin/org/krost/unidrive/telemetry/TransferHistory.kt`): nothing in the enqueue loop forces a commit.
- [code] The test (`core/app/engine-core/src/test/kotlin/org/krost/unidrive/telemetry/HistoryReaderTest.kt`, line 205) writes `/a`, flushes (committed), opens the reader, enqueues 2000 bulk rows, and then asserts `r.query(HistoryQuery(limit = 5)).rows.size == 5` while the writer still has a backlog. That exact 5 requires at least 4 of the 2000 rows to be already committed at query time. A writer thread that has not been scheduled yet (loaded runner) leaves exactly the one committed row visible: `was:<1>`, the observed signature. The test can only pass when the writer thread wins a scheduling race.

## Scope
- Change: only the assertion in that one test. Keep the test's intent — the reader answers from its own connection and does not wait for the writer thread:
  - While the backlog is pending, assert a lower bound: the query returns at least the committed row (`/a`, which sorts last on the page because every committed backlog row finished later), and the page holds at most the limit. Drop the exact `== 5`.
  - Keep the second assertion exact: after `history.flush()` the query with `HistoryQuery.MAX_LIMIT` sees everything (2001 rows, page capped at `MAX_LIMIT`).
  - A poll-with-deadline until 5 rows are visible is an acceptable alternative if a reviewer prefers proving backlog visibility inside the test, as long as the deadline failure message says the writer thread starved.
- Must not change: `TransferHistory`, `HistoryReader`, `HistoryQuery` (production code is sound; the test's expectation is wrong); the other tests of the class.
- Out of scope: the queue design (drop-on-full is deliberate — the hot path never waits for the disk).

## Acceptance criteria
1. The test passes with the writer thread never scheduled between the enqueue loop and the first query — the 2026-10-14 CI signature (`expected:<5> but was:<1>`) becomes impossible.
2. The committed row is still proven visible while the backlog is pending (the "does not wait" property stays tested, not deleted).
3. After `flush()`, the full row count is still asserted exactly.
Tests: `HistoryReaderTest` (`app/engine-core`).
Commands: `cd core && ./gradlew :app:engine-core:test --tests '*HistoryReaderTest'`.

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
None needed to start. Lower-bound versus poll-with-deadline is the implementer's choice inside acceptance criterion 1.

## How to verify live
No live check needed: the failure only reproduces under runner starvation. `for i in 1 2 3 4 5; do ./gradlew :app:engine-core:test --tests '*HistoryReaderTest'; done` on a loaded machine is the closest local rehearsal; the race-free assertion passes deterministically.
