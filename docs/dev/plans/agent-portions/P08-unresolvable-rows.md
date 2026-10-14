# P08 Rows the daemon lists but cannot open: not_found for files that exist in the cloud

| | |
|---|---|
| Issues | gkrost/unidrive#796 |
| Labels | bug (Internxt mount) |
| Size | M (diagnosis first) |
| Priority | rank 8 of 54 (correctness group), wave 3 |
| Status | ready (diagnosis first) |
| Depends on | nothing |
| Not in parallel with | P35, P17, P06 (`HydrationImpl` / `MountEngine`); same file family (take them one after the other, order in README): P18, P17, P35, P36, P03, P06, P02 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open; the cause is not proven |

## Problem
- [measured] (issue, 2026-10-12, Internxt, build 0450a3bd): 10 files under one folder plus 6 below it list and show as placeholders in the Windows mount but cannot be read ("cloud operation failed", reproducible). `ls --live` (provider query) lists them with their real mtimes (2026-05-18); `ls` (the daemon's state view, what the mount serves) lists the same files with ONE identical mtime `2026-10-08T07:45:09.842Z`; `hydration.open_read` answers `not_found` for them while sibling files read fine. `get` refuses while the daemon runs.
- [code] The path: `HydrationImpl.openForRead` -> `MountEngine.ensureHydrated` -> `Transfers.downloadByIdOrPath` (by the row's remote id when set, else by path) -> `InternxtProvider.downloadWithFileUuid` (`getFileMeta` 404) -> `HydrationImpl.downloadFailureOf` / `isNotFound` -> `HydrationError.NotFound`. A comment (about line 418) mentions a "#176 re-resolve", but no by-path retry after a failed id download exists in `MountEngine` or `Transfers`. Wanted item 2 of the issue (try the live provider by path before answering not_found) is therefore not implemented. `DoctorCommand` and `VerifyCommand` exist; neither reconciles a row with the live item at the same path.
- [inferred, unproven] Candidates named in the issue: rows created by an adoption or local copy on 2026-10-08 without the remote id of the already existing cloud file; or the path-collapse / duplicate-row family (a delta delivering a changed item without its parent). The uniform mtime looks like one recorded event.

## Scope
- Step 1 (diagnosis, in the PR body): from a copy of the affected `state.db` (the owner supplies it; the agent never touches a live profile) or a reproduction in a unit test, show which rows have a stale remote id or an adopted row, and why. Add a doctor/verify finding that NAMES such rows (a row whose remote id answers 404 while a live item exists at the same path).
- Step 2 (fix): when `ensureHydrated` fails with `not_found` for a row-id download, do ONE live lookup by path; if an item exists, heal the row (remote id, size, mtime) and continue the download; if not, answer `not_found` as today. The mount-side wording is the client's (another repository); give a distinct error token only if the corpus is extended (additive, fixture first).
- Must not change: the by-id fast path, row semantics for rows that resolve, mass deletions (no row is deleted by this change).
- Out of scope: running `refresh` on the live drive, the client message.

## Acceptance criteria
1. Unit test (`HydrationOpenReadNotFoundTest`, `HydrationTestEnv`, `MemProvider`): a row whose remote id is unknown to the provider while an item exists at the path: `open_read` heals the row and returns the file; the row now carries the live id and mtime.
2. A row whose path does not exist remotely still answers `not_found` and is not healed.
3. A provider error (503) on the by-path lookup is not turned into not_found.
4. A doctor/verify test names a stale-id row.
5. One live lookup per open at most (no loop; assert the request count).
Commands: `cd core && ./gradlew :app:hydration:test --tests '*HydrationOpenReadNotFoundTest' --tests '*HydrationImplTest' && ./gradlew :app:cli:test --tests '*DoctorCommandTest' --tests '*VerifyCommandTest'`.

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
1. Provide a sanitized copy of the affected `state.db` (or the rows) for the diagnosis; without it step 1 stays inferential.
2. Heal silently, or only report in doctor and leave the fix to `refresh`? Proposal: heal on open, report once at INFO.

## How to verify live
Owner: on the profile of the issue, open one of the listed files through the mount after the change; it reads, and `ls` now shows the remote mtime.
