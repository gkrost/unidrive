# P09 Internxt cursor listing: what the #879 fallback leaves open

| | |
|---|---|
| Issues | gkrost/unidrive#879 (the main fix is in progress by draft PR #880, branch `fix/879-cursor-fallback`; it is NOT part of this portion) |
| Labels | bug, area:internxt, kind:reliability |
| Size | M |
| Priority | rank 9 of 53 (first-sync group), wave 2 |
| Status | ready after PR #880 is merged |
| Depends on | PR #880 merged (same code: `InternxtCursorListing.kt`, `InternxtProvider.cursorFullDelta`, `InternxtApiService`) |
| Not in parallel with | P12, P21; same file family (take them one after the other, order in README): P15, P16, P22, P21, P24, P25, P05, P31, P32 |
| Status check | verified on main a0c6c4d3, 2026-10-14: #879 open, main still resumes the identical cursor; PR #880 open (draft) |

## Problem
- [measured] Capture run 2026-10-14 (engine 2ed1bab3, one account: about 316,000 files and about 71,000 live folders; the official client's database holds 540,534 folder rows, 71,396 EXISTS, 47,241 TRASHED, 421,897 DELETED): `GET /drive/folders/sync` after the cursor `{updatedAt 2026-07-28T19:37:56Z, uuid 329d6f9d-1ab9-43f0-8c14-5d361f61da82, status EXISTS}` answered HTTP 408 after 115.1-116.1 s five times (17:27:48, 17:33:35, 17:38:34, 17:44:50, 17:51:52; request ids `r3bD05qt5ElpZEoPMprm2`, `aHLunvO8UVWsztWzobR6y`). The files requests of the same minutes took 3-5 s. The official client 2.7.0 failed on the same cursor at 16:16:34 and passed at its next tick with no status filter.
- [measured] The same stretch stopped the cursor listing on 2026-10-03 (gkrost/unidrive#517 comment): `folders/sync` page 58 (about 57,000 folders) answered Cloudflare 524 after 125 s four times; the wall is a property of the account's data, not of one day's load.
- [measured] State when the run was stopped (copy of `state.db` in the capture folder): 251,000 staged rows (62,000 folders, 189,000 files), 0 rows in `sync_entries`, `enumerate_failure_streak` 5. The wall sits where a long run of TRASHED/DELETED folders follows the last live ones ([inferred]: the status filter has to step over about 344,000 tombstone rows after that cursor in the 2026-10-06 stale database of the official client).
- [code] On main a 408 is in neither `TRANSIENT_STATUSES` nor `GATEWAY_ORIGIN_ERRORS` (`InternxtApiService`) and `isServerSideListingFailure(408)` is false, so `cursorFullDelta` rethrows with the marker intact and the poller resumes the same cursor. PR #880 leaves the cursor after the second identical 408/5xx (continues from `updatedAt` minus 2 min, no status filter, non-live rows dropped locally).
- [code] Official-client claims: the installed 2.7.0 is built from drive-desktop `feat/2-7-0-release` (cursor sync); the git tag `v2.7.0` is an older offset-based sync. Read the former.
- What PR #880 leaves open (its own "could not verify" list plus the code of `leaveCursorAfter`):
  1. No captured body of a status-less page: "each row carries `status`" rests on the public server source ([unverified] live).
  2. A failure on a first page (no cursor), a failure of the unfiltered stream itself and an undecodable cursor never fall back.
  3. 5xx is in the trigger: two 5xx on one cursor also leave the status filter after a transient outage.
  4. The files stream can in principle hit the same wall; only the folders stream did.
  5. Cost: before the switch about 8 minutes (115 s, backoff 240 s, 115 s); after it the unfiltered folders stream pages about 478 times (about 1 s each) to find about 9,000 live folders. The comparison report proposes returning to the `EXISTS` filter once a page is mostly live.
  6. Nothing tells a client that a stream left its cursor or why the scan is not finished (see P12).
  7. `HttpRetryBudget` KDoc lists 408 as "retry with exp + jitter"; `InternxtApiService` never retries a 408.

## Scope
- Change: `InternxtCursorListing.kt` (`pageStreamWithFallback`, `leaveCursorAfter`), `InternxtProvider.cursorFullDelta`, `InternxtApiService.authenticatedGet` / `LISTING_SOCKET_TIMEOUT_MS` (330 s), the KDoc of `HttpRetryBudget`, `core/providers/internxt/README.md`.
- Must not change: the strategy order (cursor, then walk), the `EXISTS` filter of a healthy listing, the marker fields PR #880 added (extend only), the incremental delta.
- Out of scope: the per-stream wire shape (P12), the poller (P11).

## Acceptance criteria
1. Decision 1 implemented: a first-page 408 and an undecodable cursor either fall back as decided or end the attempt with the marker intact, never an endless same-request loop. Tests in `InternxtCursorFallbackTest` (added by PR #880; its fake server follows the public server source).
2. Decision 2 implemented: with "408 only" two 503 on one cursor do not leave the filter; with "408 and 5xx" they do. One test per variant.
3. A gather in which a stream was cut short by a guard (looping cursor, double empty page) after the switch returns `DeltaPage(complete=false)` and reaps nothing: test through `InternxtCursorListingTest`.
4. A timed-out listing page logs elapsed ms, the limit in force and the stream; a per-page deadline replaces the flat 330 s if decision 3 says so. Test with the existing `InternxtFolderContentTimeoutTest` pattern.
5. `RequestAttempt` rows of the 408 carry the endpoint class of `drive/folders/sync` and outcome HTTP 4xx (check `InternxtRequestMetricsTest`); add the assertion if missing.
6. The `HttpRetryBudget` KDoc and the provider README say what Internxt does with a 408 on a cursor.
7. Green: `cd core && ./gradlew :providers:internxt:test --tests '*InternxtCursorFallbackTest' --tests '*InternxtCursorListingTest' --tests '*InternxtRequestMetricsTest'` and `:providers:internxt:detekt`.

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
1. First page and undecodable cursor: fall back to an unfiltered restart from the epoch (re-walks the stream, rows deduplicated by uuid), or stay failing? Default proposal: restart unfiltered.
2. Trigger: 408 only, or 408 and 5xx (PR #880 chooses both)? Default proposal: keep both, but only after a 5xx that is not a gateway 524 on a cursor with a recorded failure.
3. Per-page deadline: keep 330 s, or about 150 s (above the server's 115 s) so a silent hang costs less? Needs a value.
4. Make the second try immediate in the same attempt instead of waiting a 240 s poller backoff?

## How to verify live
Owner only (no agent calls the API): run the build with PR #880 and this portion on a fresh Internxt profile with the capture logging; expect one INFO "leaving the cursor" line and a finished scan. Capture one status-less page (`folders/sync?updatedAt=...&limit=5`) with the owner's own token and confirm each row has a string `status`. Report the cursor and request ids to Internxt.
