# P02 OneDrive mount edits: a provider-neutral write token instead of the content hash

| | |
|---|---|
| Issues | gkrost/unidrive#511 |
| Labels | bug, sev:medium, area:onedrive, area:mount, kind:correctness |
| Size | M (OneDrive only; Internxt, Dropbox and S3 keep their present token) |
| Priority | rank 2 of 53 (correctness group), wave 2 |
| Status | blocked on owner decision |
| Depends on | nothing merged first; the owner decision on the column name below |
| Not in parallel with | P29 (`OneDriveProvider`), P20 (`StateDatabase`); anything editing `HydrationImpl.list` / `openForWrite`; same file family (take them one after the other, order in README): P18, P17, P08, P35, P36, P03, P06, P28, P29, P26, P30 |
| Status check | verified on main a0c6c4d3, 2026-10-14: PARTLY fixed by the stopgap 60e610c2 (PR #548); the real token is still missing |

## Problem
- [code] The mount's base token is `SyncEntry.remoteHash`. For OneDrive that is a content hash (QuickXor), not Graph's eTag. Stopgap on main: `MountEngine.uploadMountWriteFromCache` passes `ifMatchETag = if (provider.id == "onedrive") null else baseToken`, and `OneDriveProvider.upload` then reads the current eTag itself (`guardETag = ifMatchETag ?: itemAt(..).eTag`, about line 256). So there is no 412 and no keep-both copy any more, but also NO protection against a concurrent remote edit between the client's open and the upload: the stale-view window the guard exists for.
- [code] `hydration.list` still sends `etag = e.remoteHash` (`HydrationImpl`, about line 1695), and `HydrationImpl.openForWrite` (about line 512) compares `entry.remoteHash` with the client's `baseEtag`. The Windows client round-trips that value (`UploadQueue.cs`, other repository).
- [code] No `remote_version` / `remote_etag` column in `sync_entries` (`StateDatabase.kt`, `remote_hash` only), `CloudItem` has no version field, `DriveItem.eTag` is parsed but never mapped. Dropbox (`currentRev`) and S3 (`requireUnchanged`) improvise around the hash. Internxt is not affected: its `remoteHash` IS its version token.
- [code] `BACKLOG.md` has a design-constraint bullet saying the guard "is never forwarded to provider.upload"; it is stale for Internxt and needs rewording with this change.
- [unverified] The live behaviour with an isolated OneDrive account (edit through the mount, look for a "name 1.ext" copy) was never run.

## Scope
- Change:
  1. `StateDatabase` migration: nullable `remote_version TEXT` on `sync_entries` (column name: see decision), `SyncEntry`, the row mapping and the upsert; `CloudItem` gets an optional `version: String?`.
  2. `OneDriveProvider.toCloudItem` / `toSharedCloudItem` map `DriveItem.eTag` into it; `RemoteGather.updateRemoteEntries` and the upload-landed paths in `MountEngine` store it.
  3. `HydrationImpl.list` returns the version as `etag` when the row has one, else `remoteHash` (unchanged for other providers); `openForWrite` compares the same value; `MountEngine.uploadMountWriteFromCache` passes the version to OneDrive as `ifMatchETag`, and a 412 is handled by the existing keep-both path, now correctly.
  4. Fix the stale comment in `HydrationImpl` (about lines 505-516) and the BACKLOG bullet.
- Must not change: the schema for existing rows (null means "fall back to remoteHash"), Internxt's token, the wire name `etag`, `protocol_version`.
- Out of scope: the client change (gkrost/unidrive-windows round-trips whatever `etag` it gets), Dropbox and S3 cleanup, a live test.

## Acceptance criteria
1. `StateDatabaseTest`: an old database opens, the column is added, old rows read null; a round trip stores and returns the version.
2. `OneDriveProvider` test (MockEngine, `engine=` constructor idiom of `GraphWriteThrottleTest`): a listed item's eTag lands in `CloudItem.version`.
3. `HydrationImplTest` (the base_etag section, `lastUploadIfMatch` helper, fixture `MemProvider`): for a row with a version, `list` returns it as `etag`, a write with a stale `baseEtag` is refused, a write with the current one reaches the provider as `ifMatchETag`; a row without a version behaves as before.
4. `MountEngineOperationsTest`: with provider id `onedrive` the base token reaches `upload` (the `if (provider.id == "onedrive") null` stopgap is gone); a 412 from a fake provider produces one keep-both copy and no loop.
5. No change for providers that never set a version (all existing tests of `HydrationImplTest` and `MountEngineOperationsTest` unchanged).
Commands: `cd core && ./gradlew :app:engine-core:test --tests '*StateDatabaseTest' && ./gradlew :app:hydration:test --tests '*HydrationImplTest' --tests '*MountEngineOperationsTest' && ./gradlew :providers:onedrive:test --tests '*OneDrive*'`.

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
1. Column and field name: `remote_version` (the issue) or `remote_etag` (BACKLOG constraint wording)? Proposal: `remote_version`, documented as "the provider's write token".
2. Is a conditional write for OneDrive mount edits required before release, or is the present "fetch the eTag at upload time" enough? (The portion restores the guard; say no and it stays a safety improvement only.)

## How to verify live
Owner, isolated OneDrive account only: edit a file through the mount twice; no "name 1.ext" copy appears. Then edit the same file in the web UI between the client's open and its save: the engine reports a conflict (keep-both), the web version is not overwritten.
