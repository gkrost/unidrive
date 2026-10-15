# P48 Review residuals of the owner-only work: the cache containment helper and the dry-run permission side effect

| | |
|---|---|
| Issues | gkrost/unidrive#626 (items 1 and 3; items 2, 4 and 5 are resolved on main) |
| Labels | bug, sev:low, area:sync, area:mount, kind:reliability, kind:test |
| Size | S |
| Priority | rank 48 of 54 (tests group), wave 1 |
| Status | ready (check the harden branch first) |
| Depends on | check the remote branch `harden/validate-client-paths` first (it changes the cache-path containment; its status decides whether item 3 may start) |
| Not in parallel with | any change of `CacheFolder.kt` / `CachePaths.kt`, `SyncCommand.kt` |
| Status check | verified on main a0c6c4d3, 2026-10-14: items 1 and 3 open |

## Problem
- Item 1 [code]: `sync --dry-run` tightens storage permissions: `StoragePermissions.restrictProfile` runs for every `sync` invocation, dry run included (`SyncCommand.kt` about line 261, the `dryRun` field about line 70), before any dry-run gate. A profile folder created by an elevated run (owner Administrators) makes a later non-elevated `--dry-run` fail with an IOException where it used to work. Whether this is deliberate is the owner's question (lane document "needs the owner").
- Item 3 [code]: two near-duplicate containment helpers: `CacheFolder.isInsideCacheFolder` (`core/app/hydration/.../CacheFolder.kt`, #622) and `CachePaths.isInside` (`core/app/engine-core/.../engine/CachePaths.kt`, #621) implement the same algorithm (absolute + normalize, deepest-existing-ancestor real-path walk, strict-inside). `:app:hydration` already depends on `engine-core`, so one can delegate to the other. Lane document Q7: rewire, do not change the semantics.
- Resolved on main: item 2 (one-shot sync now restricts the log dir, `SyncCommand` about line 354; a mirror profile has no hydration cache), item 4 (`InternxtShardPutWatchdogTest` reworked, PR #758), item 5 (`IpcSocketDirTest` pins the bound-socket case, PR #627).

## Scope
- Change: item 3: `CacheFolder.isInsideCacheFolder` delegates to `CachePaths.isInside` (same module edge already exists); keep both public names; item 1: gate `restrictProfile` on `!dryRun` if decision 1 says so, else document it in the command help and `docs`.
- Must not change: the containment semantics (symlink/junction escapes, strict-inside), the hydration cache layout, the branch `harden/validate-client-paths` content.
- Out of scope: new containment rules.

## Acceptance criteria
1. `CachePathsTest` and `CacheFolderTest` both pass unchanged; one added case proves the two helpers agree on a table of paths (inside, equal, sibling prefix, `..` escape, symlink escape where the OS allows).
2. Item 1 per decision: either `StoragePermissionsTest` / `SyncCommandTest` show that a dry run does not touch permissions, or the help text and docs state that it does.
3. No other file changes behaviour.
Commands: `cd core && ./gradlew :app:hydration:test --tests '*CacheFolderTest' && ./gradlew :app:engine-core:test --tests '*CachePathsTest' && ./gradlew :app:cli:test --tests '*StoragePermissionsTest' --tests '*SyncCommandTest'`.

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
1. Item 1: is it deliberate that `sync --dry-run` tightens permissions? Proposal: gate on `!dryRun`.

## How to verify live
No live check needed.
