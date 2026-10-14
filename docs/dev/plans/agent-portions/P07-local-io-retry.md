# P07 Windows: a file held open by another process fails the transfer and waits a whole poll cycle

| | |
|---|---|
| Issues | gkrost/unidrive#802 |
| Labels | bug, sev:medium, area:sync, kind:reliability, windows |
| Size | M |
| Priority | rank 7 of 53 (correctness group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P29, P02 (`OneDriveProvider`), P05 (`UploadTombstoneStore`) |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open; the anchors moved |

## Problem
- [code] Nothing in `core/` retries local file I/O or recognises a Windows sharing violation (`ERROR_SHARING_VIOLATION` 32) or an access-denied error on a file that exists. The engine's own file touches are plain `java.nio` calls: the upload read in `OneDriveProvider` (`Files.readAllBytes`, about line 214), the Internxt encrypt-while-reading (`CipherInputStream(Files.newInputStream(..))`, about line 932), `PlaceholderManager` write/delete/rename (about lines 185, 201, 273-287), `LocalFsProvider` copy and move (about lines 175, 212).
- [code] On failure the sync action's generic `catch (e: Exception)` in `SyncEngine` logs one WARN, writes `failures.jsonl` and counts a failure; the next pass re-plans, so the retry interval is a whole poll cycle (60 s by default) for a lock that antivirus, the search indexer or a backup tool releases after a second. A failed download also runs `restoreToPlaceholder` over a file someone has open.
- [code] The scanner already knows the case (`LocalScanner`, about line 223: an unreadable file "locked by another process" counts as changed) and the localfs README says "does not retry host I/O errors".
- [unverified] How often this happens on a real Windows profile; the mechanism is certain, the frequency is not.
- Related: the upload tombstone write in Internxt fails the same way (#610 defect 2, see P05); it should use the helper built here.

## Scope
- Change: a small helper in `:app:core` (package `org.krost.unidrive.io`, for example `withLocalIoRetry`) that retries a local file operation a few times (3 attempts, 100, 300, 900 ms) only when the failure is a sharing violation or access-denied on a file that exists (`FileSystemException` whose reason or `AccessDeniedException` matches; Windows only, a no-op elsewhere); anything else is rethrown at once; never swallows `CancellationException`. Use it at the engine's own file touches above, not on provider network paths. When the attempts are used up, keep today's behaviour but put the cause in the message ("locked by another process" versus "gone").
- Must not change: error classification for other failures, `failures.jsonl` format, the poll cadence, provider network retry (`HttpRetryBudget`).
- Out of scope: opening files with explicit share flags (not portable through `java.nio`), shadow-copy reads, a generic engine retry budget (#172).

## Acceptance criteria
1. Unit test of the helper (new, `core/app/core/src/test`): an injected failing action succeeds on attempt 2, gives up at the cap, does not retry a non-sharing exception, sleeps not at all on success; delays use an injectable sleeper (no real sleeping in the test).
2. A Windows-only test (`assumeTrue` on the OS, pattern of `LauncherTemplateTest`) that holds an exclusive handle from a second thread, releases it after about 300 ms and checks that the engine's read or write succeeds (`PlaceholderManagerTest`, `LocalFsProviderTest`).
3. The final failure message names the sharing violation (assert in a `LocalScannerTest` or `PlaceholderManagerTest` case with a stub that always throws).
4. Cancellation during a retry sleep propagates.
5. Existing tests of the touched classes pass; no new `Thread.sleep` in tests.
Commands: `cd core && ./gradlew :app:core:test --tests '*LocalIoRetry*' && ./gradlew :app:sync:test --tests '*PlaceholderManagerTest' --tests '*LocalScannerTest' && ./gradlew :providers:localfs:test --tests '*LocalFsProviderTest'`.

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
None blocking. The attempt count and delays are proposals.

## How to verify live
Windows machine: open a synced file with an exclusive handle for two seconds while a sync pass uploads it; the upload succeeds in the same pass.
