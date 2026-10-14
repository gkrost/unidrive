# P16 Internxt folder walk: what the resume work left open

| | |
|---|---|
| Issues | gkrost/unidrive#393, gkrost/unidrive#693 |
| Labels | #393 enhancement, sev:medium, area:internxt, area:sync, kind:reliability; #693 bug, sev:high, area:internxt, kind:reliability |
| Size | M |
| Priority | rank 16 of 54 (first-sync group), wave 3 |
| Status | ready after PR #880 |
| Depends on | PR #880 merged (P09 first: both edit `cursorFullDelta`) |
| Not in parallel with | P09, P15, P21; same file family (take them one after the other, order in README): P09, P15, P22, P21, P24, P25, P05, P31, P32 |
| Status check | verified on main a0c6c4d3, 2026-10-14: #693 is fixed in the main (PR #695, #519, #830) with residuals; #393 is partly done |

## Problem
- #693 (issue, 2026-10-09, 700 GiB, 137,554 entries): a single connect failure longer than about 6 s failed the whole first enumeration and the next attempt started from zero; three attempts lost 15, 4 and 4 minutes. On main: connect-level errors inside an enumeration wait up to 180 s (PR #695 0ec026d2, `EnumerationOutageRetry`), the cursor listing resumes from its cursors (PR #519), the folder walk resumes from its staged rows (PR #830 d48b1428, `InternxtTreeResume.kt`, `InternxtTreeResumeTest`).
- Residuals [code, partly inferred]: (1) a walk that takes over from a failed listing gets no `scanContext` (`InternxtProvider` about lines 2216, 1984, 1996), so it is not resumable; (2) after an outage longer than 180 s inside a cursor listing, status 0 counts as a server-side failure (`isServerSideListingFailure(0)`), sets the 30-minute `heavyListingsUnavailableUntil`, and the next gather walks from zero with a tree marker that replaces the cursor marker ([inferred] from about lines 2203-2222 and 1815); (3) the comment at about line 2241 still says "No resume marker is kept".
- #393 items, status on main: resume = done (#830); concurrency default: still `concurrency: Int = 4` in `collectScopedInventoryImpl`, measured 18-27 s at 4 and 14.5 s at 8 on a 127-listing subtree with no throttling (issue), and the budget that should back off under a 429 storm limits nothing today (see P21); folders with 1,000 or more entries: answered by evidence (a 29,186-file folder is served in one body, `folderContentsWithPerPageFallback`, test `a folder of 29186 files is listed in 30 cursor pages`) but never recorded on the issue; missing root: the message exists (about line 2846) and the CLI shows it cleanly, `daemon run` behaviour is unverified ([inferred] no crash loop: `RemoteEnumeration` turns a `ProviderException` into `EnumerateResult(ok=false)`); other providers: only Internxt reads `ScanContext.scopeRoots`; duplicate-name folders: the walk takes `firstOrNull` (about line 2845) while `resolveFolder` fails closed (#402).

## Scope
- Change: `InternxtProvider` (`scopedFullDelta` scan context hand-over, the comment, root resolution), `InternxtTreeResume.kt`.
  1. Give a fallback walk the scan context (and a marker that does not destroy a cursor marker it replaces); an outage inside a cursor listing keeps the cursor marker and does not open the 30-minute window.
  2. Duplicate-name scope roots: resolve deterministically (winner rule of the gather admission: folder, tracked id, smallest id) and WARN, or fail closed like `resolveFolder`.
  3. Missing configured root: a unit test that `daemon run` / the poller does not crash-loop; behaviour per decision 2.
  4. Fix the stale comment; record the large-folder evidence on #393 (PR body).
- Must not change: the walk's results for a healthy drive, the marker format of the cursor listing, `INCREMENTAL_WALK_AFTER`.
- Out of scope: concurrency default 8 (decision 1), scoped enumeration for other providers (Graph `/root:/path:/delta`).

## Acceptance criteria
1. `InternxtCursorListingTest`: an outage longer than the 180 s budget inside a cursor listing leaves the cursor marker and staged rows intact and opens no listing-unavailable window; the next gather resumes the cursor.
2. `InternxtTreeResumeTest`: a walk handed over from a failed listing persists and resumes.
3. `InternxtScopedInventoryTest`: two live folders with the scope root's name give the same winner on every run and one WARN.
4. A poller test (`EnumeratePollerTest` pattern): a missing root makes the enumeration fail with the clean message and the poller backs off; no exception escapes.
Commands: `cd core && ./gradlew :providers:internxt:test --tests '*InternxtCursorListingTest' --tests '*InternxtTreeResumeTest' --tests '*InternxtScopedInventoryTest' --tests '*InternxtScopedDeltaTest' && ./gradlew :app:cli:test --tests '*EnumeratePollerTest'`.

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
1. Default walk concurrency 4 or 8? Not before P21.
2. A missing scope root while the local directory exists: fail (today) or treat the remote side as empty (the issue's idea; risky for deletes)? Proposal: keep failing.

## How to verify live
Owner: first scan of a scoped profile with the network cut for 4 minutes in the middle: the scan resumes, no restart from item 0.
