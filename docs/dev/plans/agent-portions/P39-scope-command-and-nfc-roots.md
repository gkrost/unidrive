# P39 sync_path scope: NFC roots, a `scope` command, status coverage

| | |
|---|---|
| Issues | gkrost/unidrive#395 |
| Labels | enhancement, sev:low, area:sync, area:cli |
| Size | M (the NFC part alone is S and can be its own commit) |
| Priority | rank 39 of 54 (UX group), wave 2 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P37, P04 (`Reconciler.kt`/identity), P40 (`Main.kt`); same file family (take them one after the other, order in README): P23, P37, P04, P20, P40, P32 |
| Status check | verified on main a0c6c4d3, 2026-10-14: partly fixed (2 of 6 bullets) |

## Problem
- Done: the `--full-tree` refusal no longer prints the usage dump (PR #540); the "would stop tracking" message is silent for 0 rows (PR #551).
- Open [code]:
  1. `SyncScope.normalizePath` (`engine-core/.../sync/SyncScope.kt`, line 14) fixes slashes and the leading/trailing `/` only. A root typed with NFD characters does not match the NFC path the engine keys on. (Lane document C6: apply the NFC normalizer; the case part waits for the policy of P04.)
  2. No `unidrive scope show|set <paths>|clear` command exists: widening a scoped profile needs a `--full-tree` run because `effective_scope` history only grows (`SyncEngine` about lines 686-730, union only, cleared only by `--full-tree`); `status` explains it but there is no first-class way.
  3. `status` prints the scope only in the single-profile view (`StatusCommand.printScopeLine`, about line 420); the multi-profile table has no scope column.
  4. A per-run `--sync-path` outside the standing scope is tracked for that run and untracked by the next run without the flag, silently; it should log at INFO with the roots involved.

## Scope
- Change: (a) `SyncScope.normalizePath` through the engine's NFC normalizer (`PathNormalizer.nfc`); (b) `ScopeCommand` (`show`, `set`, `clear`) that edits the config, applies the scope transition (untrack / clear cursor) and prints exactly what the next run will do, with `--json`; (c) a scope column in the multi-profile `status`; (d) the drift INFO line.
- Must not change: matching of already-normalized roots, `--full-tree`, the transition rules of `RemoteGather.applyScopeTransition`.
- Out of scope: the case policy of roots (waits for #309), per-provider scoped enumeration.

## Acceptance criteria
1. `SyncScopeTest`: an NFD-typed root equals its NFC form and matches NFC paths; slash/trailing-slash cases unchanged.
2. `ScopeCommandTest` (new, pattern `StatusCommandTest`): `set /a /b` writes the config and prints the transition; `clear` leaves an unscoped profile; `show --json` lists roots and the effective history.
3. `StatusCommandTest`: the multi-profile table shows the scope of a scoped profile and none for others.
4. A run with `--sync-path /x` outside the standing scope logs one INFO line naming both.
5. Widening through `scope clear` needs no `--full-tree` flag (the command performs the documented transition).
Commands: `cd core && ./gradlew :app:engine-core:test --tests '*SyncScopeTest' && ./gradlew :app:cli:test --tests '*ScopeCommandTest' --tests '*StatusCommandTest' && ./gradlew :app:sync:test --tests '*SyncEngineTest'`.

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
1. Is `effective_scope` replaced by the current standing scope on `scope set`, or kept as history with a `scope clear-history` (issue comment)? Proposal: `set` replaces, with the transition printed.

## How to verify live
Throwaway profile (localfs): `scope set /a`, sync, `scope clear`, sync: the rest of the tree appears without a `--full-tree` flag.
