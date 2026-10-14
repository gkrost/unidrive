# P05 Internxt: a 409 on create of identical content adopts instead of keeping both

| | |
|---|---|
| Issues | gkrost/unidrive#594, gkrost/unidrive#610 |
| Labels | bug, sev:medium, area:internxt, kind:correctness |
| Size | M (defect 2 of #610 alone is S) |
| Priority | rank 5 of 53 (correctness group), wave 4 |
| Status | blocked on owner decision |
| Depends on | P07 merged for the tombstone-write part (optional) |
| Not in parallel with | P24, P09 (`InternxtProvider.kt`), P06; same file family (take them one after the other, order in README): P09, P15, P16, P22, P21, P24, P25, P31, P32 |
| Status check | verified on main a0c6c4d3, 2026-10-14: both open; the cause of the double create is not established |

## Problem
- #594 [measured] (issue, 2026-10-06, fresh profile): one 63 MiB file copied into the mount once leaves two objects of identical size: the file and `... (conflict 2026-10-06).msi`. Log: `UD-366: 409 on POST /files for /<name> despite existingRemoteId=null`, then `UD-366: collision on /<name> - remote fileId=... size=66088960 differs from local fileId=... size=66088960`, then two `#401: upsert ... evicted alive row` lines. The sizes match and the message says "differs": the identity check cannot prove identity.
- #610 [measured] (issue, 2026-10-07, bulk move while retries were pending): 15 files written, moved into a subfolder; one ends as `sub/f11.txt` plus `sub/f11 (conflict 2026-10-07).txt`, same 8 bytes, same 409 message. First attempts of tiny files had failed with an `IOException` naming only a path under `upload-tombstones`.
- [code] The 409 fallback is `InternxtProvider.uploadUnclassified` (about lines 1146-1250). It adopts only if `remoteFile.fileId == bucketEntry.id && size equal` (about line 1184-1192); `bucketEntry.id` is the id of THIS upload's bridge object, so two separate uploads of identical bytes never match (new key and IV each time). Otherwise it uploads a conflict copy (`conflictName`, about line 2912) and the engine never merges them afterwards.
- [code] #610 defect 2: `UploadTombstoneStore.write` (about line 70) does `Files.move(tmp, final, ATOMIC_MOVE, REPLACE_EXISTING)` with no retry, so a Windows sharing violation (antivirus, indexer) fails the attempt; a path-only message is an `AccessDeniedException`.
- [inferred] #610 defect 1: an old-path retry of a renamed row is abandoned ("row or cache copy is gone", `HydrationImpl.runUploadWithRetries`), so the create at the new path is a separate submission (replay, recovery scan or the ghost probe in `HydrationImpl.rename`); `rekeyUploadSlot` exists. Needs a trace of who submitted it. #583 (`MountEngine.unchangedCloudVersionOrNull`) already skips identical rewrites.
- Both share one missing rule (backlog audit D8): after a 409 on a create whose remote item has the same content, adopt it.

## Scope
- Change: `InternxtProvider` 409 branch: prove identity with a rule from decision 1, then adopt the remote item (`replaceFile` path already used for the fileId match) and record the right remote id; `UploadTombstoneStore.write`: retry the move a few times on `AccessDeniedException` / sharing violation (use the helper of P07 when merged, else a local loop); `HydrationImpl.rename` / replay only after a trace shows the submission path.
- Must not change: keep-both for a real difference (different bytes), the conflict-name scheme, `existingRemoteId` handling.
- Out of scope: why the double create happens (needs a log trace; add a debug line naming the submitter if cheap).

## Acceptance criteria
1. Shared test (`InternxtCreateCollisionTest`, helpers `newProviderRooted` / `installMockClientOnProvider`): a 409 on create whose remote item has equal size and the proof of decision 1 creates NO conflict copy and leaves one remote item; a differing item still produces the copy.
2. A rename that overtakes a retry (`HydrationImplRenameUploadRaceTest`, its `UploadingProvider` or `MemProvider`): fake provider fails the first attempt of some creates, files are renamed into a subfolder, no conflict copy, exactly one item per name.
3. `UploadTombstoneStore` test: a move that fails twice with an access-denied error and then succeeds writes the tombstone; a permanent failure still throws.
4. Existing `InternxtUploadRefusalTest` and `InternxtCreateCollisionTest` pass.
Commands: `cd core && ./gradlew :providers:internxt:test --tests '*InternxtCreateCollisionTest' --tests '*UploadTombstone*' --tests '*InternxtUploadRefusalTest' && ./gradlew :app:hydration:test --tests '*HydrationImplRenameUploadRaceTest'`.

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
1. What proves identity after a 409: download and hash the remote (63 MB cost), or equal size plus remote modification time equal to the local mtime (create sets it from `localMtime`)? Proposal: the cheap heuristic first, hash only when it is ambiguous, and keep both when neither holds. The worklist calls this "needs a hash-comparison design".

## How to verify live
Owner, isolated Internxt test account: copy one 60 MB file into the mount once, and move 15 small files into a new subfolder while uploads retry; no `(conflict ...)` copies appear.
