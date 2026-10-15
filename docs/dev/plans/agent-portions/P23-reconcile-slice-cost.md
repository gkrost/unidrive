# P23 Streaming reconcile loads the whole sync_entries table once per delta page

| | |
|---|---|
| Issues | gkrost/unidrive#316 |
| Labels | bug, sev:medium, area:sync, kind:efficiency, lane:sync-apply |
| Size | M |
| Priority | rank 23 of 54 (efficiency group), wave 3 |
| Status | ready after PR #877 |
| Depends on | PR #877 merged or closed (it edits `gatherStreamingChanges`, same consumer) |
| Not in parallel with | PR #877, P04 (`Reconciler.kt`), P37; same file family (take them one after the other, order in README): P37, P04, P39 |
| Status check | verified on main a0c6c4d3, 2026-10-14: open in code; the Internxt premise of the issue is stale |

## Problem
- [code] `Reconciler.resolveSlice` (about line 444) starts with `db.getAllEntries()` plus an `associateBy` (about line 458) for every slice; the caller is the consumer coroutine in `SyncEngine.gatherStreamingChanges` (about line 2152). Each load happens while holding the `StateDatabase` monitor.
- [code] Internxt returns ONE `DeltaPage` per gather (`assembleFullDelta` and `scopedDeltaPage` set `hasMore = false`), so Internxt pays one load per gather, not the roughly 2,000 the issue computes (that figure assumed pages of 50 items). The multi-page provider is OneDrive (`hasMore = nextLink != null`, about 200 items per page, 13 pages for 2,673 items in the capture run); a large OneDrive first sync of 500,000 items would load the table 2,500 times, each time bigger.
- The mount daemon does not use this path (it merges through `RemoteEnumeration`, chunked since PR #873); this is the mirror engine.
- The non-streaming path already bulk-loads once.

## Scope
- Change: `Reconciler.resolveSlice` takes the entry maps as a parameter (loaded once before the streaming loop) and the consumer refreshes them incrementally with the rows the executor upserts or deletes while it runs; or an indexed lookup per slice item instead of the full map (decision 1).
- Must not change: reconcile results (same actions for the same inputs), the non-streaming path, the 50-percent shrink gate and the reap guards.
- Out of scope: the provider-side memory (P15), the mount enumeration.

## Acceptance criteria
1. A counting seam (wrap `StateDatabase` or count calls of `getAllEntries`): a streamed gather of N pages causes at most one full load; test in `ReconcilerTest` next to the existing `resolveSlice ...` cases (about lines 2132-2170) and in `SyncEngineTest` streaming cases, with a real temp `StateDatabase` and `FakeCloudProvider` (a fake that returns several `DeltaPage`s).
2. Result equivalence: for a generated sequence of pages (new, changed, deleted, renamed items across page boundaries) the actions equal the old behaviour (golden comparison inside the test).
3. Staleness case: a row upserted by the executor between two slices is visible to the second slice.
4. Existing tests of `Reconciler` and `SyncEngine` pass unchanged.
5. A timing note in the PR body: 50,000 rows and 250 pages, before and after (informational).
Commands: `cd core && ./gradlew :app:sync:test --tests '*ReconcilerTest' --tests '*SyncEngineTest'`.

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
1. Loaded-once map with incremental refresh (fast, the staleness risk is the thing to test), or a per-item indexed lookup (no staleness, more queries)? Proposal: loaded once with refresh, plus a debug assertion mode in tests that compares with a fresh load.

## How to verify live
No live check needed; optional: a mirror first sync of a throwaway OneDrive with more than 5,000 items shows a flat scan time per page.
