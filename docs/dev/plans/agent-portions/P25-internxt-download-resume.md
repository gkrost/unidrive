# P25 Internxt: resume an interrupted download with a Range request

| | |
|---|---|
| Issues | gkrost/unidrive#804 |
| Labels | enhancement, sev:medium, area:internxt, kind:efficiency |
| Size | M (S for the live check) |
| Priority | rank 25 of 54 (efficiency group), wave 4 |
| Status | blocked on a live Range check |
| Depends on | the live check below; nothing merged |
| Not in parallel with | P26, P24, P09 (`InternxtApiService`); same file family (take them one after the other, order in README): P09, P15, P16, P22, P21, P24, P05, P31, P32 |
| Status check | verified on main a0c6c4d3, 2026-10-14: no Range / 206 handling anywhere in `core` |

## Problem
- [code] `InternxtApiService.downloadFileStreaming` (about lines 914-1017) issues one plain GET of the whole signed shard, decrypts into `<name>.unidrive-tmp` and, on any exception, deletes that file; the partial bytes are thrown away. `InternxtProvider.downloadWithFileUuid` (about line 369) wraps `getBridgeFileInfo` plus a fresh `Cipher` in `retryShardCommit` (3 attempts), so each attempt re-downloads from byte 0. The shard SHA-256 is verified over the whole ciphertext (about line 960). Hydration deletes `.hydrating-*` in `finally` and never resumes (`MountEngine` about line 296).
- A drop at 90 percent of a multi-GB file costs the whole transfer again on every attempt; the signed shard URL expires after about 15 minutes. The upload twin (#571, 1.36 GB restarting from zero) is fixed in part (#572 watchdog; #578 parts are P24).
- AES-256-CTR is seekable (counter = IV + offset / 16), so decrypting from an offset is possible; the digest needs either its state carried or the written prefix re-hashed.
- [unverified] Whether the storage host of the signed URL answers `Range` with `206`. This decides whether the issue is feasible at all.

## Scope
- Step 0, live check (owner, S): one `curl -r 0-99` against a fresh signed shard URL of a small file; paste status and `Content-Range` on the issue. If the host does not honour Range, close the issue with the evidence and drop this portion.
- If it does: keep the `.unidrive-tmp` partial on a retryable failure (reset, timeout, 5xx), remember `(bridgeFileId, index/IV, ciphertext bytes written, digest state or "re-hash prefix")`; on the retry send `Range: bytes=<n>-` with a CTR cipher positioned at `n` (`InternxtCrypto.createContentDecryptCipher` gets an offset); a `200` instead of `206`, a changed `Content-Length` or a changed `index` restarts from zero; the final length and SHA-256 checks stay. Delete the partial on a permanent failure.
- Must not change: the integrity checks, the upload path, the quarantine of truncated objects.
- Out of scope: parallel ranged downloads, hydration progress (P36).

## Acceptance criteria
1. `InternxtMultiShardDownloadTest` / `InternxtApiServiceTest` (MockEngine): the first attempt returns N bytes then drops; the second request carries `Range: bytes=N-`; the final file equals the source and passes the SHA-256 check.
2. A server answering `200` to the ranged request: restart from zero, correct file.
3. A changed `index` between attempts: no stale prefix is reused.
4. CTR offset test in `InternxtCryptoTest`: decrypting from a non-block-aligned offset equals the tail of a full decrypt.
5. `InternxtRemoteIncompleteDownloadTest` unchanged (a truncated object is still refused at the headers).
Commands: `cd core && ./gradlew :providers:internxt:test --tests '*InternxtMultiShardDownloadTest' --tests '*InternxtApiServiceTest' --tests '*InternxtCryptoTest' --tests '*InternxtRemoteIncompleteDownloadTest'`.

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
1. Run the Range live check (step 0) and post the result.
2. Digest strategy: carry the digest state across attempts (in memory only), or re-hash the written prefix? Proposal: re-hash the prefix (simple, restart-safe).

## How to verify live
Owner, isolated account: download a 300 MB file and cut the network at about 60 percent; the log shows one ranged request and the file's SHA-256 matches.
