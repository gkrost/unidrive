# P06 Upload into a folder trashed in the cloud: a typed token and a safe subtree tombstone

| | |
|---|---|
| Issues | gkrost/unidrive#580 |
| Labels | bug, sev:medium, area:internxt, area:mount, kind:reliability |
| Size | M |
| Priority | rank 6 of 54 (correctness group), wave 4 |
| Status | blocked on owner decision |
| Depends on | nothing merged first; the two decisions below |
| Not in parallel with | P17, P08, P05; same file family (take them one after the other, order in README): P18, P17, P08, P35, P36, P03, P02 |
| Status check | verified on main a0c6c4d3, 2026-10-14: partly fixed (the retry half, PR #599; the resurrection half, PR #663) |

## Problem
- Live case 2026-10-05 (issue): a folder was trashed in the web UI; Internxt reports trashed items late (#132), so `state.db` kept the folder and children as `EXISTS`; a new file written into it failed with `Folder not found` on every attempt, retried 3 times per round, re-submitted by the client until "waiting for a restart", and replayed at each restart.
- Done on main [code]: a walk that proves the parent absent raises `PermanentUploadFailureException` (`InternxtProvider.resolveUploadParent`, PR #599 34c133e3); `HydrationImpl` marks `upload_refused`, no retry ladder, no replay, the bytes stay in the cache (ask 3). The lagging delta can no longer resurrect a trashed child under a re-created folder (#601, PR #663 5a4a1921, `RemoteGather.staleDeltaItem`).
- Open [code]: (a) no `parent_gone` wire token (`HydrationError.kt` has none; the client sees a generic refusal; the engine signals exist: `Failed` event with `retryScheduled=false`, row `upload_refused` + `last_error_at`); (b) no id-confirmed subtree tombstone: `markDescendantsDeleted` is called only from `MountEngine`'s own rmdir (about line 873, `StateDatabase` about line 1026), never from a gather or an upload failure, so the trashed subtree stays `EXISTS` until the lagging delta; (c) residual TODO in `InternxtProvider.resolveFolder` (about line 2495): a stale `FolderUuidCache` entry can short-circuit the walk with a dead uuid (self-heals on restart).
- Why (b) is delicate (issue comment 2026-10-06): the walk that proves a parent absent is the walk a degraded gateway breaks (#387); a tombstone keyed on a FAILED listing would mass-delete mount-view state in an outage. The trash lag (#132) needs the id lookup before tombstoning, so an undone trash does not destroy rows of a live subtree.

## Scope
- Change:
  1. `HydrationError` gets a `parent_gone` token (additive), mapped from the permanent refusal that came from a proven-absent parent; hydration event corpus fixture first (`IpcContractCorpusTest`); the client consumer is another repository (gkrost/unidrive-windows#172, #32), so the token must be optional for old clients.
  2. After such a refusal the engine confirms by id (`getMetadata` by id or a trash lookup on the folder's remote id) and, only if the lookup SUCCEEDS and says trashed or deleted, calls `markDescendantsDeleted` for that folder's subtree and invalidates the view. A failed lookup changes nothing.
  3. `resolveFolder`: drop the `FolderUuidCache` entry when the uuid answers 404.
- Must not change: the retry-stop behaviour already on main, the bytes staying in the cache, `protocol_version`.
- Out of scope: the cure for the trash lag itself (#132).

## Acceptance criteria
1. Fake provider whose path walk no longer finds a tracked parent: one attempt, the typed failure, `parent_gone` on the event, no replay (extend `InternxtUploadRefusalTest` for the provider half and `HydrationImplTest` with `MemProvider` for the event).
2. Id lookup says trashed: the subtree rows become `DELETED` once, the next `view.invalidated` drops them; the user's cached bytes remain and the file shows as failed with the reason.
3. Id lookup fails (503/timeout): no row changes (the degraded-gateway case).
4. Id lookup says the folder is live (trash undone): nothing is tombstoned.
5. A stale `FolderUuidCache` uuid answering 404 is evicted and the next attempt walks.
6. Corpus fixture added; `IpcContractCorpusTest` green.
Commands: `cd core && ./gradlew :providers:internxt:test --tests '*InternxtUploadRefusalTest' --tests '*FolderUuidCacheTest' && ./gradlew :app:hydration:test --tests '*HydrationImplTest' && ./gradlew :app:cli:test --tests '*IpcContractCorpusTest'`.

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
1. The `parent_gone` wire token needs a corpus claim and a client design (the 2026-10-06 triage called both design decisions): approve the token name and that it is additive.
2. Approve the tombstone rule "only after a successful id lookup", including what the user sees for the cached file.

## How to verify live
Owner, isolated Internxt test account: trash a folder in the web UI, write a file into it through the mount: one failed attempt, a clear reason, the folder disappears from the mount after the next poll, the file stays recoverable from the cache.
