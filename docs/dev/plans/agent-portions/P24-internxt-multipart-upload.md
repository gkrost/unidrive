# P24 Internxt: upload large files as parallel parts

| | |
|---|---|
| Issues | gkrost/unidrive#578 (first half: parts with per-part retry and a concurrency key; per-part resume in the tombstone, the daemon flag and the status field are the second half, to be filed after this merges) |
| Labels | enhancement, sev:medium, area:internxt, kind:efficiency |
| Size | M-L (first half of an L; the second half is filed after this merges) |
| Priority | rank 24 of 54 (efficiency group), wave 4 |
| Status | ready after the concurrency gate |
| Depends on | P21 merged (a real concurrency gate), PR #880 merged |
| Not in parallel with | P05 (`uploadUnclassified`), P25, P09; same file family (take them one after the other, order in README): P09, P15, P16, P22, P21, P25, P05, P31, P32 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open; owner decision 2026-10-05 "queued after #560" (#560 is delivered) |

## Problem
- [measured] (issue, #571) a 1.36 GB upload moved at about 2 MB/s on a link of 50 MiB/s; JFR showed one TCP stream to the object store that never exceeded 3 MB/s (hypothesis: send window against 65 ms RTT). More streams are the lever. rclone's Internxt backend uploads files above 100 MiB as 30 MiB chunks, 4 in parallel, each chunk AES-256-CTR encrypted at its byte offset (parts are independent); the official client (2.7.0) uploads multipart above 100 MB in 15 MB parts, 10 in parallel, with whole-file retry and no resume (comparison report).
- [code] `InternxtConfig` defines `MULTIPART_MIN_SIZE_BYTES` (100 MB), `MULTIPART_CHUNK_SIZE_BYTES` (50 MB), `MAX_PARALLEL_PARTS` (6), unused; `InternxtApiService.startUpload` sends `files/start?multiparts=1` only (about line 1100); a stale comment names the wrong ticket (`InternxtProvider` about line 687, "pending UD-307"; UD-307 is the OneDrive emoji issue). No `upload_concurrency` / `upload_chunk_mb` / `upload_cutoff_mb` keys exist. `UploadTombstoneStore` keeps no per-part state.
- The shard PUT already runs under a write-progress watchdog (#572); nested retries (3 provider x 3 hydration) restart a whole-file PUT from byte 0 (audit #579 item 1).
- [unverified] The exact wire shape of `start?multiparts=N` (one URL per part) and `finish` with `UploadId` and the part list: take it from rclone's `backend/internxt` and the official `inxt-js`, and check against the public server source before coding.

## Scope
- Change: `InternxtApiService` (start with N parts, part PUT, finish with the part list), `InternxtProvider.uploadUnclassified` (cut the encrypted staging file into parts at CTR offsets, run up to `upload_concurrency` parts under the budget of P21, each part with its own retry and its own small watchdog window), `InternxtConfig` / `InternxtProviderFactory` (profile keys `upload_concurrency` default 4 (1 = today, max 16), `upload_chunk_mb` default 32 (minimum 5, parts at most 10,000), `upload_cutoff_mb` default 100), `docs/config-schema`, the stale comment.
- Memory bound: concurrency x chunk buffers; the staged `.enc` stays on disk.
- Must not change: files at or below the cutoff (single PUT, byte-identical behaviour with concurrency 1), encryption format and `indexBytes` pinning, the tombstone fields that exist.
- Out of scope (second half): persisting completed part numbers and ETags in the tombstone, `daemon run --upload-concurrency`, the `daemon.status` field, OneDrive chunking.

## Acceptance criteria
1. Fake bridge with N part URLs (`InternxtApiServiceTest`, MockEngine; `LoopbackServer` for the PUT watchdog): parts upload in parallel up to the limit; one part fails twice and succeeds without restarting the others; a finish call with an incomplete part list is never sent.
2. Concurrency 1 equals the old behaviour byte for byte (same requests).
3. Boundary sizes: exactly the cutoff, one byte above, a size that makes a short last part.
4. The assembled object decrypts to the source (`InternxtCryptoTest` CTR offset case; round trip in `InternxtMultiShardDownloadTest` style).
5. Key validation: out-of-range values refuse the profile with a clear message.
6. `InternxtShardPutWatchdogTest` passes unchanged.
Commands: `cd core && ./gradlew :providers:internxt:test --tests '*InternxtApiServiceTest' --tests '*InternxtShardPutWatchdogTest' --tests '*InternxtCryptoTest' --tests '*InternxtMultiShardDownloadTest' --tests '*InternxtUploadWritebackTest'`.

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
1. Defaults (4 parts of 32 MB above 100 MB) and whether OneDrive gets the same keys later.
2. Acceptance on the live stack needs the owner's OK for a real upload (same before/after JFR NetworkUtilization query as #571).

## How to verify live
Owner, isolated account: upload a 1 GB file with concurrency 1 and 4 and compare throughput; kill the daemon in the middle (no resume in this half: the upload restarts, which is the second half's job).
