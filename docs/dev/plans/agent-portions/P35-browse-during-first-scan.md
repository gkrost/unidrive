# P35 Mount: list a folder live while the first scan has not written any rows

| | |
|---|---|
| Issues | gkrost/unidrive#515 |
| Labels | enhancement, sev:medium, area:mount |
| Size | M (first slice: live listing without writing rows) |
| Priority | rank 35 of 53 (UX group), wave 4 |
| Status | ready |
| Depends on | nothing; sequence after P08 (same files) |
| Not in parallel with | P08, P18, P17, P03; same file family (take them one after the other, order in README): P18, P17, P08, P36, P03, P06, P02 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open as described |

## Problem
- [code] `HydrationImpl.list` (about line 1665) answers only from `stateDb.listDirectChildren`. The rows of a first scan arrive after the whole gather (`RemoteEnumeration.runEnumeration`, now merged in chunks since PR #873). `EnumerationStatus.first` is true until a scan completed.
- [measured] Capture run 2026-10-14, Internxt: after 30 minutes of listing `scan_staging` held 251,000 rows while `sync_entries` held 0 rows (copy of `state.db`), so the mounted folder showed nothing the engine had learned; the first scan of that account needs about 21-25 minutes when it works and never finished that day (#879). The Explorer panel tells the user the folder shows only what was known before.
- Opening a folder costs one `folders/content/<uuid>` call (about 0.3 s measured earlier, #387/#388); `ls --live` already lists that way. `MountEngine.remoteItemOrNull` (about line 927) shows the provider-access pattern.

## Scope
- Change: while `first` is true AND the folder has no rows in `state.db`, `HydrationImpl.list` lists it live from the provider (paged like `ls --live`), honours `sync_path` and the exclusion/ignore rules, and answers with those items marked as cloud-only. First slice: DO NOT write them into `state.db` (the scan writes the same rows later; no race with `updateRemoteEntries` and the reap); cache the answer in memory for a short TTL (a few seconds) so Explorer's repeated listing does not repeat the call.
- Failure of the live call is a failed list (the platform asks again), never an empty folder.
- Must not change: behaviour once the first scan has completed (`state.db` is then the truth), anything a scan writes, upload-queue entries for names the live listing also returns (those win).
- Out of scope: writing rows from the live listing (the issue's full proposal: races with the scan upsert and the reap need their own design), any change of the enumeration.

## Acceptance criteria
1. `HydrationImplTest` / `HydrationSmokeTest` with `MemProvider` (its `listChildren` ignores the path today: extend it to honour the path): first scan not completed, empty folder in `state.db`: `list` returns the provider's children; after the scan completes it returns `state.db` rows only (assert provider call count 0).
2. A provider error during the live call gives an error reply, not an empty list.
3. A folder with 1,001 entries is listed completely (paging).
4. Excluded and ignored names are marked as the present `list` marks them.
5. Repeated `list` of one folder within the TTL calls the provider once.
6. A pending upload for a name in the folder is shown as the upload, not as the cloud item.
Commands: `cd core && ./gradlew :app:hydration:test --tests '*HydrationImplTest' --tests '*HydrationSmokeTest'` and `:app:engine-core:test --tests '*EnumerationTrackerTest'` if the `first` flag plumbing is touched.

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
1. Is a live-only first slice acceptable, or must the rows be written (race rules needed)? Proposal: live only.
2. Rate: Explorer may list many folders on a cold state; cap concurrent live listings (proposal 2).

## How to verify live
Owner: start a first scan of the large Internxt profile and browse the root and two folders in Explorer while it runs: entries appear within seconds.
