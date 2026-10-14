# P26 Ranged download in the SPI, and OneDrive resume of a broken download

| | |
|---|---|
| Issues | gkrost/unidrive#125 (with #839 row 3 and #846 item 2; the other items of #846 are P27) |
| Labels | enhancement, sev:medium, area:onedrive, kind:efficiency |
| Size | M |
| Priority | rank 26 of 53 (efficiency group), wave 3 |
| Status | ready (SPI shape: decision proposed) |
| Depends on | nothing; decide the SPI shape first (below) |
| Not in parallel with | P27 (`CloudProvider.kt`), P29 (`downloadFile`), P02, P25; same file family (take them one after the other, order in README): P28, P29, P02, P30, P27 |
| Status check | verified on main a0c6c4d3, 2026-10-14: no Range anywhere in the OneDrive module, the SPI `download` has no offset |

## Problem
- [code] `GraphApiService.downloadFile` (about lines 232-420) does `getItemById` for a fresh `@microsoft.graph.downloadUrl`, one plain GET, and writes with CREATE + TRUNCATE_EXISTING, so every retry (and every failed download of a large file) restarts at byte 0. `OneDriveProvider.downloadResolved` calls it. The SPI is `CloudProvider.download(remotePath, destination): Long` (plus `downloadWithHash`, `downloadById`, `downloadByIdWithHash`), whole file only.
- [code] The mount (`hydration.open_read`) blocks until the whole file is in the cache; #437 wants progress and cancel, and a later `hydration.read(offset, length)` needs the same primitive. Every planned provider can do ranges (Graph CDN URL, S3, SFTP, WebDAV, Drive, Dropbox).
- Internxt is separate: its files are AES-CTR encrypted per shard, so a resume needs a counter offset and the shard digest (see P25).
- [inferred] A broken 5 GB download is the case: the retry is a full re-download. Hash and truncation guards (`DownloadContentTypeGuardTest`, size check, QuickXor in `HashVerifier`) must still see the whole file.

## Scope
- Change:
  1. SPI: an additive default method, for example `suspend fun downloadRange(remotePath, destination, offset: Long): Long` that by default delegates to a full `download` when `offset == 0` and throws `UnsupportedOperationException` otherwise, plus a capability flag (`Capability.RangedDownload`) so callers know before asking. Decision 1 fixes the exact shape.
  2. `GraphApiService.downloadFile`: keep the partial file on a retryable failure (connection reset, timeout, 5xx), remember `(itemId, eTag, bytes written)`, and on retry send `Range: bytes=N-` against the same `downloadUrl` (re-resolve it when it expired, as `OneDriveDownloadReresolveTest` already covers); a `200` instead of `206`, a changed `Content-Length`, or a changed eTag restarts from zero.
  3. The final size and QuickXor checks run over the assembled file as today.
- Must not change: the whole-file `download` contract for other providers, the temp-file naming rules, quarantine on permanent failures, the hydration cache protocol.
- Out of scope: parallel segment download (#125's second half), hydration progress (#437), Internxt.

## Acceptance criteria
1. `CloudProviderDefaultsTest` (`:app:core`): a provider that does not override the new method behaves as before for offset 0 and refuses a non-zero offset with a typed result.
2. MockEngine test in the style of `OneDriveDownloadReresolveTest`: first attempt returns N bytes then drops; the second request carries `Range: bytes=N-`, and the final file equals the source and passes the size check.
3. A server that answers `200` to the ranged request: the download restarts from zero and completes correctly.
4. A changed eTag between attempts: no stale prefix is reused.
5. An expired `downloadUrl` mid-way: re-resolved once, the range continues.
6. `CapabilityContractTest` lists the new capability for OneDrive only.
Commands: `cd core && ./gradlew :app:core:test --tests '*CloudProviderDefaultsTest' --tests '*CapabilityContractTest' && ./gradlew :providers:onedrive:test --tests '*OneDriveDownloadReresolveTest' --tests '*DownloadContentTypeGuardTest'`.

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
1. SPI shape: `downloadRange(offset)` returning bytes written, or a `DownloadRequest(offset, length)` object that also serves a later `hydration.read`? Proposal: offset only now, length later by adding a defaulted parameter.
2. Does a resumed partial survive a daemon restart (needs a sidecar next to the `.hydrating-*` temp file) or only an in-process retry? Proposal: in-process first.

## How to verify live
Owner, throwaway OneDrive: download a 200 MB file and cut the network at about 50 percent; the log shows one `Range: bytes=` request and the file's QuickXor matches.
