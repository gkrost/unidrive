# P22 Internxt incremental poll through the keyset endpoints

| | |
|---|---|
| Issues | gkrost/unidrive#312 (comparison report adopt item 3) |
| Labels | bug, sev:medium, area:internxt, kind:correctness, kind:efficiency |
| Size | M |
| Priority | rank 22 of 54 (first-sync group), wave 3 |
| Status | ready after PR #880 |
| Depends on | PR #880 merged (it makes `status` optional on the two sync calls and adds the guard for rows without a status); P15 merged or not started (same provider code) |
| Not in parallel with | P09, P15, P21; same file family (take them one after the other, order in README): P09, P15, P16, P21, P24, P25, P05, P31, P32 |
| Status check | verified on main a0c6c4d3, 2026-10-14: the incremental path is still offset-paged (`deltaImpl`: `sort=updatedAt`, `status=ALL`) |

## Problem
- [code] `InternxtProvider.deltaImpl` with a cursor calls `api.listFolders/listFiles(adjustedCursor, limit, offset, "ALL", "updatedAt")` through `speculativeFetchPages` (two pages in flight per stream, the second cancelled when the first is short): up to four requests per poll per profile, every 60 s (`EnumeratePoller`). The pin test is `an incremental delta does not use the cursor listing` in `InternxtCursorListingTest`.
- [code] #312: an offset over a listing sorted by `updatedAt` shifts when an already fetched row mutates during the gather; the row at the next page boundary is skipped, its `updatedAt` is below the promoted cursor, and the change (a TRASHED tombstone included) never syncs until the item changes again. The 120 s `rewindCursor` covers neither case. Fix direction in the issue: detect the mutation, or keyset pagination.
- [measured] The keyset endpoints are the stable alternative and are cheap: `/folders/sync` 0.9-2.2 s and `/files/sync` 2.5-5.7 s per 1,000 rows (#517 comments), and the official client's idle tick is two requests, one `files/sync` and one `folders/sync` page (comparison report, official log 16:40:33). The cost of UniDrive's own incremental poll on a large account is [unknown]: no first scan completed.
- [code] The official client asks `updatedAt = checkpoint minus 2 min` with no status filter so that tombstones arrive in-band; with PR #880 the engine has the same query shape (optional `status`, rows must carry a string `status`).

## Scope
- Change: `InternxtProvider.deltaImpl` (cursor != null branch), `InternxtApiService` (reuse `getFilesSync` / `getFoldersSync` with `status = null`), `InternxtCursorListing.kt` helpers (`pageCursorStream`, `parseFilesSyncPage(rowsCarryStatus = true)`); `docs` line in `core/providers/internxt/README.md`.
  1. An incremental delta pages `files/sync` and `folders/sync` from `rewindCursor(cursor)`, no status filter, following `nextCursor` to the end, one request in flight per stream; non-live rows become `deleted` items exactly as `status=ALL` rows do today.
  2. The cursor handed back is the max `updatedAt` seen, as `advanceCursor` does now; a stream cut short by a guard makes the page `complete=false`.
  3. A server without the endpoints (404/405/501 on the first page, `CursorListingUnavailable`) keeps the offset listing.
- Must not change: the stored cursor format, `INCREMENTAL_WALK_AFTER` and the walk fallback, the full enumeration, deleted-item semantics (`status` TRASHED or DELETED means deleted).
- Out of scope: an adaptive idle interval (the poller, P11), the streaming of pages (P15).

## Acceptance criteria
1. The test named above is replaced by one that asserts the incremental poll uses `files/sync` and `folders/sync` with no `status` parameter and `updatedAt` equal to the rewound cursor, one request per stream for a quiet account.
2. A row that mutates between two polls is returned by the next poll and never skipped: a `DriveMock` scenario that changes row order between pages (the case of #312) loses no item; the same scenario on the offset listing is kept as a regression note in the test comment.
3. A TRASHED and a DELETED row arrive as `deleted` items; a row without a `status` fails the poll loudly (`SyncRowWithoutStatus`) instead of being taken as live.
4. A server that answers 404 on the first page falls back to the offset listing and still returns the items.
5. `InternxtScopedDeltaTest` and the incremental tests of `InternxtApiServiceTest` (status ALL params, line about 376-411) are adapted deliberately, not deleted; `InternxtGetRetryTest` green.
6. A page list longer than one (more than 1,000 changes) follows `nextCursor`; a repeated cursor ends the stream incomplete (reuse the guards).
Commands: `cd core && ./gradlew :providers:internxt:test --tests '*InternxtCursorListingTest' --tests '*InternxtScopedDeltaTest' --tests '*InternxtApiServiceTest' --tests '*InternxtGetRetryTest'`.

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
1. Keep `status=ALL` semantic with the offset listing as the default until a live poll has been compared (feature flag), or switch at once? Proposal: switch, keep the offset listing only as the 404 fallback.

## How to verify live
Owner: with a completed first scan, watch two minutes of poll lines (`unidrive.log`, request lines for `drive/files/sync` and `drive/folders/sync`: one each per poll) and trash a file in the web UI: it disappears locally at the next poll after the server lists it (the trash lag of #132 is a server matter).
