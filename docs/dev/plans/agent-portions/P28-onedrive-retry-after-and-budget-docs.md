# P28 One Retry-After parser for every provider, and a truthful HttpRetryBudget KDoc

| | |
|---|---|
| Issues | gkrost/unidrive#806 (#131 is obsolete, see the index; #172 is not packaged) |
| Labels | enhancement, sev:low, area:onedrive, kind:reliability |
| Size | S |
| Priority | rank 28 of 53 (reliability group), wave 2 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P21 (same KDoc and file), P29 (`GraphApiService`); same file family (take them one after the other, order in README): P29, P26, P02, P30, P41, P10, P21 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open, and wider than the issue says |

## Problem
- [code] Retry-After is read as whole seconds only at three OneDrive sites in `GraphApiService` (`response.headers["Retry-After"]?.toLongOrNull()` about lines 424, 944, 1147-1149; the comment says "Graph sends integer seconds"), and in the two newer providers: `DropboxApi.backoffMs` (about line 266) and `S3Api.backoffMs` (about line 437). A value in the HTTP-date form (`Wed, 21 Oct 2026 07:28:00 GMT`) gives null and the code falls back to its own exponential backoff, which can be shorter than the server asked for (RFC 9110 section 10.2.3 allows both forms).
- [code] Internxt has the correct parser, `InternxtApiService.parseRetryAfterHeader` (about line 1503: delta-seconds or RFC 1123 date, negative and past dates give null) but it is `internal` to that module.
- [code] The `HttpRetryBudget` KDoc (`core/app/core/.../http/HttpRetryBudget.kt`) links `docs/dev/lessons/http-retry-policy.md`, which does not exist (`docs/dev/lessons/` holds other files), names WebDAV, rclone, SFTP and HiDrive clients that are not in the tree (only internxt, onedrive, dropbox, s3, localfs exist), says the policy is for "a single GraphApiService", and its matrix cites `budget.maxRetries` / `budget.maxRetryAfter`, which are not members. It also lists 408 as "retry"; Internxt does not retry a 408 (see P09).
- Honest scope: Microsoft documents seconds for Graph and SharePoint; no live response with a date was seen. This is hardening.

## Scope
- Change: one `parseRetryAfter(header, nowMs)` next to `HttpRetryBudget` in `:app:core` (seconds or HTTP date, trimmed, non-negative, a past date gives null, garbage gives null); use it at the three Graph sites, in `DropboxApi.backoffMs`, `S3Api.backoffMs` and in Internxt (delete the private copy, keep its callers' behaviour); correct the KDoc (drop the missing link or add the lessons file, list the clients that exist, say each provider owns its budget instance, fix the 408 row to what the code does).
- Must not change: behaviour for integer seconds, the caps (`MAX_SINGLE_BACKOFF_MS` and the other per-provider caps), the JSON-body `retryAfterSeconds` handling, the retry counts.
- Out of scope: folding the hand-written retry loops into the budget (#702 item 5, #172), per-provider overrides (#131), the concurrency gate (P21).

## Acceptance criteria
1. Unit test of the shared parser in `core/app/core/src/test/.../http/` (beside `HttpRetryBudgetMatrixTest`): `"7"`, `" 7 "`, `"0"`, `"-1"`, a future HTTP date, a past date, garbage, empty, null.
2. One OneDrive test per site (429 with `Retry-After: <date 5 s ahead>`) asserting a wait of about 5 s on virtual time, in `GraphWriteThrottleTest` (it already uses the `engine=` MockEngine constructor and virtual `currentTime`).
3. One test each for Dropbox (`DropboxWriteTest` / `FakeDropbox`) and S3 (`S3ProviderTest` / `FakeS3`) with a date-form header.
4. `InternxtApiServiceTest` keeps passing against the shared parser (no behaviour change).
5. Optional: a test that every `docs/...` path named in the `HttpRetryBudget` KDoc exists.
Commands: `cd core && ./gradlew :app:core:test --tests '*RetryAfter*' --tests '*HttpRetryBudgetMatrixTest' && ./gradlew :providers:onedrive:test --tests '*GraphWriteThrottleTest' && ./gradlew :providers:dropbox:test --tests '*DropboxWriteTest' && ./gradlew :providers:s3:test --tests '*S3ProviderTest' && ./gradlew :providers:internxt:test --tests '*InternxtApiServiceTest'`.

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
None. If the lessons file is wanted instead of dropping the link, the PR may add it (two paragraphs).

## How to verify live
No live check needed.
