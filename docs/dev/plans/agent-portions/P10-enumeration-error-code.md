# P10 daemon.status last_error: readable text and a machine-readable last_error_code

| | |
|---|---|
| Issues | gkrost/unidrive#878 (client side: gkrost/unidrive-windows#438) |
| Labels | bug |
| Size | S |
| Priority | rank 10 of 53 (first-sync group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P12, P13, P11 (all edit `EnumerationTracker` / `EnumerationStatus`); merge this one first; same file family (take them one after the other, order in README): P11, P12, P13, P41, P28, P21 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open |

## Problem
- [measured] `daemon.status` of the Internxt daemon after the 408 (capture run 2026-10-14, `p3-final/daemon-status-internxt.json`): `"last_error":"API error: 408 Request Timeout - {<name>:408,<name>:<name>,<name>:<name>}"`. The Windows tray flyout prints it as it is.
- [code] The provider message is `"API error: ${response.status} - ${truncateErrorBody(body)}"` (`InternxtApiService.checkResponse`, `GraphApiService` the same). `RemoteEnumeration.runEnumeration` passes `e.message` to `EnumerationTracker.failed(reason, authRejected)`, which runs `sanitizeError`: URL, address, every quoted string (`'..'`, `".."`, backticks) become `<name>` or similar, then paths. A JSON body is made of quoted strings, so it turns into punctuation. The privacy rule is sound; the body must not be sanitized, it must not be sent at all.
- [code] `ProviderException` (`:app:core`) carries no status. `InternxtApiException.statusCode` and `GraphApiException.statusCode` exist, but `engine-core` cannot see provider modules, so no code can reach the tracker today.
- [code] The spec says "the engine reports numbers and codes only; the client words them" (`docs/dev/specs/unidrive-daemon-design.md` section 4.3, table row `last_error`).

## Scope
- Change:
  - `ProviderException` gets an optional `httpStatus: Int? = null`; `InternxtApiException`, `GraphApiException` (and `DropboxApiException` and the S3 equivalent if they hold a status) pass their status up. Keep the existing `statusCode` properties.
  - `EnumerationTracker.failed(reason, authRejected, code)`, `EnumerationStatus.lastErrorCode`, `EnumerationStatusJson` (`last_error_code`), `RemoteEnumeration.runEnumeration` (derive the code in the `catch (e: ProviderException)`).
  - `EnumerationTracker.sanitizeError`: drop a trailing response body (" - {", " - [", " - <") before the quote rules run. The first line, URL/address/path rules and the 200-character cap stay.
  - Spec section 4.3: a `last_error_code` row. Code vocabulary: `http_<status>` for an API error, otherwise the lower-case `AttemptOutcome.ofFailure` class (`timeout`, `connect`, `dns`, `tls`, `reset`, `io`), `auth_rejected` for `AuthenticationException`; absent when unknown.
- Must not change: `protocol_version`; `last_error` stays one sanitized line (no field is removed); `authRejectedAtMs`.
- Out of scope: the client wording (gkrost/unidrive-windows#438), the retry timing.

## Acceptance criteria
1. `sanitizeError("API error: 408 Request Timeout - {\"statusCode\":408,\"message\":\"Request timed out\",\"requestId\":\"x\"}")` returns `API error: 408 Request Timeout`; an HTML body after " - " is dropped the same way.
2. Messages without a body (the cases in `EnumerationTrackerTest`, about lines 500-532) give the same results as before.
3. A failed enumeration whose provider throws `InternxtApiException(..., statusCode = 408)` reports `"last_error_code":"http_408"`; a transport timeout reports `timeout`; an unknown failure leaves the field out; `succeeded()` and `begin()` clear it.
4. The field is additive: a fixture with `last_error_code` is added to `core/app/cli/src/test/resources/ipc-contract/daemon.status.ndjson` (or the `events` corpus) before the code; `IpcContractCorpusTest` is green.
5. The log still carries the full message (the engine log is unchanged).
Tests: `EnumerationTrackerTest` (`app/engine-core`), `EnumerationStatusJsonTest` (`app/cli`), `RemoteEnumerationTest` (`app/engine-core`, test `a provider failure counts the streak and leaves the cursor alone`), `EnumerationStatusTest` (`app/sync`, its `ReportingProvider`), `IpcContractCorpusTest`.
Commands: `cd core && ./gradlew :app:engine-core:test --tests '*EnumerationTrackerTest' && ./gradlew :app:cli:test --tests '*EnumerationStatusJsonTest' --tests '*IpcContractCorpusTest' && ./gradlew :app:engine-core:test --tests '*RemoteEnumerationTest'`.

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
None needed to start. Code spelling (`http_408` versus `408`) is a proposal; the client reads it as an opaque token.

## How to verify live
No live check needed: a fake provider that answers 408 shows the shape. The owner can confirm the tray text once gkrost/unidrive-windows#438 consumes the code.
