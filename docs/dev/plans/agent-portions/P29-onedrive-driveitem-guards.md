# P29 OneDrive items: malware facet, package facet, quota sentinel and the hash fallback

| | |
|---|---|
| Issues | gkrost/unidrive#153 and rows 2, 4, 6 of gkrost/unidrive#839 (rows 1, 3, 5, 7 of #839 are not in this portion: row 1 see P30, row 3 see P26, rows 5 and 7 are decisions, see Open decisions) |
| Labels | enhancement, sev:low, area:onedrive, kind:correctness |
| Size | M |
| Priority | rank 29 of 53 (correctness group), wave 2 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P28, P30, P26, P02 (all edit `GraphApiService` / `OneDriveProvider`); same file family (take them one after the other, order in README): P28, P26, P02, P30 |
| Status check | verified on main a0c6c4d3, 2026-10-14: none of the four is done |

## Problem
- Row 6, hash fallback [code]: `OneDriveProvider.toCloudItem` (about line 646) and `toSharedCloudItem` (about line 705) set `hash = quickXorHash ?: sha256Hash` while `hashAlgorithm()` declares QuickXor. A SHA-256 string would be compared as a QuickXor value (`HashVerifier` in `engine-core`), a false mismatch or a false "ok". Personal OneDrive supplies quickXor; Business or some drives may not.
- Row 4, quota sentinel [code]: `GraphApiService.getQuota` (about line 114) returns `QuotaInfo(0, 0, 0)` when the drive has no quota object, while the provider declares `Capability.QuotaExact`; `QuotaInfo` has no unknown state, so a client shows "0 B of 0 B". The engine's rule is "unknown stays unknown".
- Row 2, package facet [code]: `DriveItem` (`model/DriveItem.kt`) has no `package` field, so a OneNote notebook looks like a file and a download of it fails or returns a stub.
- #153, malware facet [code]: no `malware` anywhere in the OneDrive module; a flagged item is downloaded or fails with a generic error. Guard sites: `GraphApiService.downloadFile` (after `getItemById`) and `OneDriveProvider.downloadResolved`. `PermanentDownloadFailureException` exists (`ProviderException.kt`) and makes the engine quarantine the row.
- [unverified] How Graph reports a flagged item on download (403 with a code, or the facet only). The issue gives no acceptance text.

## Scope
- Change: `model/DriveItem.kt` (optional `malware`, `package` objects, ignore unknown keys as today), `OneDriveProvider.toCloudItem` / `toSharedCloudItem` (hash), `OneDriveProvider.downloadResolved` and `GraphApiService.downloadFile` (guards), `GraphApiService.getQuota` and the SPI result.
  1. Hash: an item whose QuickXor is absent gets `hash = null` (the engine then compares size and mtime); the SHA-256 is not stored under a QuickXor declaration. Alternative (decision below): carry the algorithm.
  2. Quota: a drive without a quota object reports "unknown" (add a nullable/unknown representation to `QuotaInfo` only if `QuotaInfo` is not a wire type; otherwise throw an unsupported result as `getQuota` callers already handle) and `daemon.status.quota` shows `error`/null, never zeros.
  3. Package: an item with a `package` facet is listed as a cloud-only item that is not downloadable (`PermanentDownloadFailureException` with the reason), never as an empty file.
  4. Malware: an item with a `malware` facet is skipped on download with the same typed exception.
- Must not change: QuickXor preference, the upload paths, `QuotaExact` for drives that do report a quota, how `daemon.status.quota` is shaped for them.
- Out of scope: Business/national clouds (#839 row 1), `$select`, ranged download.

## Acceptance criteria
1. `OneDriveHashAlignmentTest`: an item with only `sha256Hash` yields `hash == null` (or the algorithm-carrying value from decision 1); an item with both uses QuickXor (existing case).
2. A MockEngine test (pattern of `DeltaDiagnosticTest` / `DeltaFromLatestTest`): a delta page with one `package` item and one `malware` item deserializes (no page failure) and the items are marked non-downloadable.
3. `DownloadContentTypeGuardTest`-style test: `downloadFile` of a `malware` item throws `PermanentDownloadFailureException` before any content request is made (MockEngine records the calls).
4. A drive response without `quota` gives an unknown quota result; the daemon's quota snapshot (`DaemonRuntime.QuotaSnapshot`) reports `error` instead of zeros (test in `DaemonStatusHealthTest` or `DaemonRuntimeTest`).
5. Existing tests of the touched classes stay green.
Commands: `cd core && ./gradlew :providers:onedrive:test --tests '*OneDriveHashAlignmentTest' --tests '*DeltaFromLatestTest' --tests '*DeltaDiagnosticTest' --tests '*DownloadContentTypeGuardTest' && ./gradlew :app:engine-core:test --tests '*HashVerifierTest'`.

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
1. Hash fallback: store null (proposal), or carry the algorithm per item (`CloudItem` change, touches every provider)?
2. #839 row 5 (skip one undeserialisable item instead of failing the delta page) advances the cursor past an item that then stays invisible until it changes; keep failing, or skip and mark `complete=false`? Not in this portion until decided.
3. #839 row 7 (`$select`, epoch fallback, `Prefer` header) is low value and needs live checks; not packaged.

## How to verify live
No live check needed. Optional on a throwaway OneDrive: a OneNote notebook in the drive lists as cloud-only and does not download.
