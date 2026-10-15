# P04 Case-insensitive backends: a provider case policy and no cross-device overwrite

| | |
|---|---|
| Issues | gkrost/unidrive#309 (first half; the case-only rename as a move is a follow-up issue to file) |
| Labels | bug, sev:medium, area:sync, kind:correctness, windows |
| Size | M |
| Priority | rank 4 of 54 (correctness group), wave 3 |
| Status | blocked on owner decision |
| Depends on | the decision below |
| Not in parallel with | P23, P03, P37 (`Reconciler.kt`), PR #877; same file family (take them one after the other, order in README): P23, P37, P39 |
| Status check | verified on main a0c6c4d3, 2026-10-14: partly fixed; the policy and the two-device case are open |

## Problem
- [measured] (issue comment 2026-10-03, OneDrive personal, engine b7e5dc7): (a) a local case-only rename (`case1.txt` to `CASE1.txt`) ended in a keep-both copy `case1 1.txt`, a renamed local file, a conflict copy and a remote delete; the name the user chose existed nowhere. (b) Two devices, `case4.txt` unsynced on A and `CASE4.txt` uploaded by B: A planned a download AND an upload for the same NTFS file, then a later sync deleted B's remote file; B's content survived only in a local conflict copy.
- Done on main [code]: remote twins are folded by `RemoteGather.gatherKey` / `foldGatherKeys`, on Windows only and only for remote collisions (PR #472); `Reconciler.reconcile` (about lines 255-290) and `finalizeStreaming` (about line 546) refuse local NEW paths that case-collide with a tracked row or with each other (Conflict, nothing transferred), the recovery loops too (PR #788); prefix queries are code-point correct (PR #492).
- Open [code]: no case-sensitivity capability exists (`Capability.kt` lists no such entry); `gatherKey` decides by the OS, not by the provider; the `Reconciler` guard is unconditional, so on a case-SENSITIVE provider it also refuses real sibling pairs; a local case-only rename is not a move keyed on the remote id (by reading: `DeleteRemote(old)` plus a refused `Conflict(new)`, a re-upload next pass; [inferred], no test); local-NEW against remote-NEW with no tracked row has no cross-check, so the two-device case of (b) still plans Download and Upload. `TrackingEngine.kt` keys are case-sensitive but that engine is frozen (#343). Dropbox does its own case-only rename through a temporary name; OneDrive has `OneDriveServerNames.sameName`.

## Scope
- Change:
  1. `Capability`-style declaration `CaseSensitivity` (sensitive / insensitive-preserving) on `CloudProvider` with a default; OneDrive and Dropbox insensitive, Internxt and S3 sensitive (verify against each API before setting), localfs from the platform.
  2. `RemoteGather.gatherKey` and the `Reconciler` guards use the provider policy AND the local file system's case behaviour (collide only when either side folds).
  3. Local NEW vs remote NEW cross-check: when the folded keys match and no row is tracked, plan neither Download nor Upload; report a Conflict (nothing transferred).
- Must not change: the refusal behaviour for real collisions, NFC handling, the tracked-row model.
- Out of scope: the case-only rename as a move (file as a follow-up: needs `moveById` semantics per provider), the frozen tracking engine.

## Acceptance criteria
1. `ReconcilerTest` (`case collision detected` cases) and `RefusedCaseCollisionTest`: a case-sensitive provider on a case-sensitive local file system accepts `a.txt` and `A.txt` as two files; an insensitive provider refuses the second.
2. Two-device scenario with `FakeCloudProvider` (policy insensitive): local NEW `case4.txt` and remote NEW `CASE4.txt` produce one Conflict and no transfer; nothing is deleted on either side on the next pass.
3. `RemoteEnumerationTest`: remote twins fold by policy, not by `os.name`.
4. `CapabilityContractTest` lists the declaration for every provider.
5. Linux-only parts use `assumeTrue` where a case-sensitive file system is required.
Commands: `cd core && ./gradlew :app:sync:test --tests '*ReconcilerTest' --tests '*RefusedCaseCollisionTest' && ./gradlew :app:engine-core:test --tests '*RemoteEnumerationTest' && ./gradlew :app:core:test --tests '*CapabilityContractTest'`.

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
1. The provider case policy ("fold for identity on OneDrive, preserve display case"; the lane document keeps this with #309): approve the declaration per provider.
2. Conflict on local NEW against remote NEW, or adopt when the content hashes match (#116 adopt-on-name-match)? Proposal: conflict first.

## How to verify live
Owner, throwaway OneDrive: repeat the two-device case of the issue; no remote file is deleted.
