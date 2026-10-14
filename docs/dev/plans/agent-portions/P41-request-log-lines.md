# P41 Request log lines: the server's request id on every response, and the profile in the message

| | |
|---|---|
| Issues | gkrost/unidrive#644 (with adopt item 5 of the Internxt comparison report: the server `x-request-id` on every response line) |
| Labels | enhancement, area:internxt, kind:observability |
| Size | S |
| Priority | rank 41 of 54 (observability group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P42 (log format and levels), P21 (HTTP plugins); same file family (take them one after the other, order in README): P10, P28, P21 |
| Status check | verified on main a0c6c4d3, 2026-10-14: open; the premise of #644 holds only for threads without the profile MDC |

## Problem
- #644: the `org.krost.unidrive.http.RequestId` DEBUG lines (`-> req=<id> GET <url>`, `<- req=<id> 200 (172ms) up=0 dn=659`) carry the profile only through the logging MDC column, which coroutine worker threads lack (`[*]`), so a log shared by several daemons or one daemon with two accounts cannot be split per account.
- [measured] In the capture log of 2026-10-14 (two daemons, one shared `unidrive.log`) all 631 request lines carry their profile (`[internxt]` 529, `[onedrive]` 102), none `[*]`: the enumeration runs on a thread that has the MDC. The gap that remains is code paths without MDC (hydration downloads and other worker threads; hydration uploads got `MDCContext` in PR #721). So the ask narrows to: the profile must be in the message itself, independent of the thread.
- Comparison report adopt item 5: the official client logs the server `x-request-id` for every call; our response lines carry only a local id, so the 83.6 s `folders/sync` call at 17:25 could not be correlated with the server (the id appears only inside error bodies). [code] `RequestIdPlugin` logs `<- req={} {status} ({ms}ms){size}` and for status 400 and above at WARN; `ProviderException.requestId` already maps the server id per provider (Internxt `x-request-id`, Graph `request-id` / `client-request-id`, S3 `x-amz-request-id`).
- Install sites: `InternxtApiService.defaultHttpClient` (about line 116), `DropboxApi` (about 395), `GraphEndpoints` (about 43), `S3Api` (about 570).

## Scope
- Change: `RequestIdConfig` gets `profile: String?` and `serverRequestIdHeaders: List<String>`; the plugin writes `<- req=ab12 [profile] srv=<server id> 200 (172ms) ...` (profile and `srv=` only when known; the rest of the format and the order of existing fields stay, so parsers that end at `\d+ms\)` or read `up=`/`dn=` keep matching); the four install sites pass the profile name (the leaf of the provider's token path or a factory property) and their header names.
- Must not change: the correlation header sent, the redaction rule (no query string), WARN level for 4xx/5xx, the attempt metrics.
- Out of scope: log levels and files (P42).

## Acceptance criteria
1. `RequestIdPluginTest` (MockEngine): two clients with different profile names log their own name from a thread without MDC; a client without a profile logs the old format byte for byte.
2. A response carrying the server id header logs `srv=<id>` (Internxt `x-request-id`, Graph `request-id`); one without it logs none.
3. `formatSizeSegment` cases unchanged; the line still ends with the size segment.
4. `InternxtRequestIdPropagationTest` passes; no secret or query string in the new fields (assert with a URL that has a token in the query).
Commands: `cd core && ./gradlew :app:core:test --tests '*RequestIdPluginTest' && ./gradlew :providers:internxt:test --tests '*InternxtRequestIdPropagationTest' --tests '*InternxtRequestMetricsTest'`.

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
None.

## How to verify live
No live check needed; in the next capture run the `<-` lines of a slow call carry `srv=` and can be given to Internxt support.
