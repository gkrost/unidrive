# P12 daemon.status.enumeration: report the folders and files streams separately

| | |
|---|---|
| Issues | none filed yet: file one before starting ("daemon.status.enumeration: per-stream state"); related gkrost/unidrive#879, #878, gkrost/unidrive-windows#398 |
| Labels | enhancement, area:internxt, kind:observability (design proposal) |
| Size | M |
| Priority | rank 12 of 54 (first-sync group), wave 3 |
| Status | ready after PR #880 and the error-code portion |
| Depends on | PR #880 merged (P09 may run first or after, not at the same time); P10 merged (it adds `last_error_code`, reused per stream) |
| Not in parallel with | P13, P11 (all edit `EnumerationTracker`, `EnumerationStatus`, `EnumerationStatusJson`); same file family (take them one after the other, order in README): P10, P11, P13 |
| Status check | verified on main a0c6c4d3, 2026-10-14: no per-stream field exists |

## Problem
- [measured] When the capture run stopped, the Internxt `daemon.status.enumeration` read `{"state":"failed","first":true,"attempt":5,"listing":"cursor","items":251000,"last_error":...}`. The staged rows (run copy of `state.db`) were 62,000 folders and 189,000 files: the folders stream stood at 62,000 of about 71,400 live folders (87 percent) and was stuck on one cursor, the files stream at 189,000 of 315,744 files (60 percent) and healthy. The aggregate says only "failed, 251,000".
- [measured] The cursor markers in `unidrive.log` ("Resuming Internxt-style scan ... marker=...") show the files cursor advancing between attempts (updatedAt 2026-05-04 at 17:31, 2026-05-05 at 17:49) while the folders cursor stayed on 2026-07-28. Nothing reaches a client.
- [code] `ScanProgress(items, foldersDone, foldersKnown, foldersSkipped, listing)` (`:app:core`, `ScanContext.kt`) has no per-stream part; the cursor listing reports one total in `cursorFullDelta.report()`; `EnumerationTracker.onProgress` keeps one number; `EnumerationStatus` and `EnumerationStatusJson` have no stream object. A failure is raised from inside `pageCursorStream`/`pageStreamWithFallback` and cancels the sibling stream, so the provider has no point where it reports "folders failed, files stopped".
- A UI cannot say "files done, folders failed" or "files 189,000 and still going". This is a proposal: it needs a design look before code.

## Scope
- Proposed wire shape (additive, `protocol_version` unchanged), in `enumeration`:
  `"streams":[{"name":"folders","state":"failed","items":62000,"error_code":"http_408"},{"name":"files","state":"running","items":189000}]`, `state` one of `pending`, `running`, `done`, `failed`, `bypassed` (left its cursor, see PR #880). Absent for a listing that has no streams (folder walk, OneDrive delta). Unknown stays absent.
- Change: `ScanProgress` gets an optional list of stream reports; `InternxtProvider.cursorFullDelta` reports both streams after every page and in its failure path (catch, report, rethrow); `EnumerationTracker` keeps the last report and does not clear it on `failed()` (the attempt's reached state stays visible, as `items` does); `EnumerationStatus` / `EnumerationStatusJson`; spec section 4.3; corpus fixture first.
- Must not change: the aggregate `items`, `listing`, `rate_per_s`, `eta_*` fields and their meaning; the resume marker format (the report is derived from it, not stored in it).
- Out of scope: client wording; an expected total per stream (P13).

## Acceptance criteria
1. Fixture lines for a running two-stream scan and a failed one (folders `failed` with `error_code`, files `running`) are added to the IPC corpus first; `IpcContractCorpusTest` and `EnumerationStatusJsonTest` pass.
2. Provider test (`InternxtCursorListingTest`, its `DriveMock` fake drive): while both streams page, every progress report names both streams with their own counts, and the counts sum to `items`.
3. Provider test: a 408 on the folders cursor leaves the report `folders failed`, `files` with the count it had reached; the sibling stream is cancelled and not reported `done`.
4. Engine test (`EnumerationTrackerTest`): `failed()` keeps the streams; `begin()`/`succeeded()` clear them; a restart (resume from a marker with both positions) reports the staged counts per stream from the first poll.
5. A stream that finished (`done: true` in the marker) is reported `done` while the other continues.
6. Folder-walk and OneDrive enumerations produce no `streams` key.
Commands: `cd core && ./gradlew :providers:internxt:test --tests '*InternxtCursorListingTest' && ./gradlew :app:engine-core:test --tests '*EnumerationTrackerTest' && ./gradlew :app:cli:test --tests '*EnumerationStatusJsonTest' --tests '*IpcContractCorpusTest'`.

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
1. The vocabulary (`streams`, state names) and whether the client needs `bypassed` visible or only `running`/`done`/`failed`.
2. Counting: items per stream from the marker positions plus the staged rows, or from the in-memory maps (cheaper, but lost on a restart until the first page)?

## How to verify live
Owner: start the Internxt profile on the large account and read `daemon.status` every 10 s (`unidrive-win` log or `daemon.status` over IPC): both streams show their own counts; with a walled cursor the folders stream shows `failed`/`bypassed` while the files stream keeps counting.
