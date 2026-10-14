# P30 OneDrive: work/school drives other than /me/drive (SharePoint libraries)

| | |
|---|---|
| Issues | gkrost/unidrive#588 (the discovery command is the last step; the Business-cloud authority of #839 row 1 stays out) |
| Labels | enhancement, sev:medium, area:onedrive |
| Size | M (L with the discovery command: split it into a second PR) |
| Priority | rank 30 of 54 (efficiency group), wave 4 |
| Status | blocked on owner decision (is Business in scope) |
| Depends on | P29 and P26 merged (same `GraphApiService`) |
| Not in parallel with | P29, P26, P02, P28; same file family (take them one after the other, order in README): P28, P29, P26, P02 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open as described |

## Problem
- A work or school account can sign in (`authority_url`), but only its personal drive is reachable: `GraphApiService` hard-codes `/me/drive` (17 occurrences on 16 lines; the issue's 22 is stale). There is no `drive_id` setting. SharePoint document libraries and other drives the account sees (`/drives/{id}`, `/sites/{site-id}/drives`) cannot be synced or mounted.
- [code] More than the URL base depends on it: the `createSubscription` resource is `/me/drive/root`; `OneDriveProvider.toCloudItem` strips the literal `/drive/root:` from `parentReference.path` (about lines 180, 677) and `isRootItem` compares `/drive/root`, but for another drive Graph returns `/drives/{id}/root:` paths [Graph behaviour inferred]; `GraphEndpoints.classifier` literals know `drives` but not `sites`. Config: `RawProvider` has `authority_url` only (mapped at `Main.kt` about line 417 and `OneDriveProviderFactory.create` about line 78).
- Related: #130 (per-tenant concurrency), #839 row 1 (`/common` authority and national clouds), #111 (subscription resource).

## Scope
- Change: optional per-profile `drive_id` (default `/me/drive`), a base path built once from the profile and used at every site in `GraphApiService` (delta, listings, upload sessions, subscriptions, quota), the path mapping in `OneDriveProvider` for `/drives/{id}/root:`, `SyncConfig.kt` + `Main.kt` + `OneDriveProviderFactory.create` + `OneDriveConfig` for the key, `GraphEndpoints.classifier` (templated segments for `drives` and `sites`), `docs/config-schema`.
- Second PR: `unidrive` discovery command listing the drives the account reaches (`/me/drives`, followed sites' drives) as JSON.
- Must not change: behaviour with no `drive_id`, token storage, scopes.
- Out of scope: Business/national-cloud authority detection (#839 row 1), webhooks.

## Acceptance criteria
1. `ListChildrenPaginationTest`, `DeltaFromLatestTest` and the `GraphWriteThrottleTest` `engine=` idiom run once more with `drive_id = "b!abc"`: every request path starts with `/drives/b!abc/` and none with `/me/drive`.
2. Path mapping: items whose `parentReference.path` is `/drives/b!abc/root:/Docs` get the path `/Docs`; the root item is recognised; a personal drive behaves as before.
3. Subscriptions and upload sessions use the same base.
4. A config with an invalid `drive_id` is refused at profile load with a clear message (a new factory test next to `OneDriveInteractiveAuthContractTest`).
5. A `grep` test (or detekt-free unit test) asserts no `"/me/drive"` literal outside the base-path builder in `GraphApiService`.
Commands: `cd core && ./gradlew :providers:onedrive:test --tests '*ListChildrenPaginationTest' --tests '*DeltaFromLatestTest' --tests '*GraphWriteThrottleTest'`.

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
1. Is Business/SharePoint in scope for the first release at all (#839 row 1 says "high, if Business is in scope")?
2. The key name (`drive_id` is the issue's).

## How to verify live
Owner with a work/school test tenant (none exists on the development machine): list a document library's root and download one file.
