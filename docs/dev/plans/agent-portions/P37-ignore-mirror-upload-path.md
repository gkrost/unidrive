# P37 Ignore files for the mirror engine: LocalScanner and Reconciler upload planning

| | |
|---|---|
| Issues | gkrost/unidrive#660 (the mirror part, PR 2b; epic #651 is not packaged) |
| Labels | enhancement, area:sync, lane:desktop-ui |
| Size | M |
| Priority | rank 37 of 54 (UX group), wave 2 |
| Status | ready after PR #877 |
| Depends on | PR #877 merged or closed (sync engine files); the matcher and the mount call site are on main |
| Not in parallel with | P23, P04, P39 (`Reconciler.kt`), PR #877; same file family (take them one after the other, order in README): P23, P04, P39 |
| Status check | verified on main a0c6c4d3, 2026-10-14: PR 1 (#811) and PR 2a (#819, the mount) merged; PR 2b not started (the local branch `feat/660-ignore-mirror` has no commit of its own and no worktree is registered; the lane document's `wt-ui-660c` is gone) |

## Problem
- Delivered: matcher with the git oracle (#710), discovery/layering/cache/`explain` (`sync/ignore/IgnoreTree`, #811, spec `docs/dev/specs/ignore-layering-design.md`), the mount call site (`RemoteOperationGuard.isExcludedPath`, `daemon.status.ignore.held_back`, READ verb `ignore.explain`, #819), profile keys `honor_gitignore` / `ignore_rules` (#855).
- [code] The mirror engine still uses the old custom glob: `LocalScanner.isExcluded` (about line 90) and many uses of `Reconciler.matchesGlob` (about lines 206, 306, 352, 476, 589, 617, 1041; the function itself at about 1479), no `IgnoreTree` / `UploadIgnore` in either. So `.unidrive.ignore` files do nothing for a mirror profile, and `node_modules`-style trees keep being uploaded.
- Owner decisions (issue #651, 2026-10-09): ignoring means no new transfer for that path; it never deletes anything in the cloud, and deleting an ignored local file never deletes remotely; cloud items stay visible; `.git` is excluded by default but overridable; `.gitignore` is opt-in per account; an ignored file stays in place and is never a leftover.
- Open questions from the issue comment of 2026-10-12: how `exclude_patterns` (old glob syntax, matched on remote paths without the ancestor rule) maps onto the account layer; fail closed or open for an unreadable rule file; whether a cloud-only rule file holds its folder back until opened.

## Scope
- Change: `LocalScanner` (one `IgnoreTree` per sync root, `revalidate()` at scan start, `explainEntry` in the walk to prune ignored directories, ignored paths yield no upload), the upload decisions in `Reconciler` (new-local uploads and the pending-upload recovery loops), `SyncConfig` (account layer wiring), keeping `matchesGlob` as its own layer for `exclude_patterns` until decision 1.
- Must not change: remote listings, downloads and deletes (untouched by ignore); profiles without ignore files behave exactly as today; the mount call site.
- Out of scope: IG-5 `ignore.rules` verb with a generation counter and IG-6 `unidrive ignore check <path>` (later children of epic #651), the Windows leftovers matcher (client repository).

## Acceptance criteria
1. `LocalScannerTest`: an ignored directory is pruned (not walked) and produces no upload; un-ignoring (removing the rule file) makes its paths eligible at the next scan and deletes nothing anywhere.
2. `ReconcilerTest`: a new local file matching `.unidrive.ignore` is not uploaded; a synced path that becomes ignored stays in the cloud and locally; the recovery loops skip ignored rows (not delete them).
3. Profiles with only `exclude_patterns` produce the same plan as before (golden comparison over the existing exclude test cases).
4. `!` re-include, nested files and `.git` override work for the mirror as for the mount (`UploadIgnoreTest`-style cases against `LocalScanner`).
5. A mirror dry run lists ignored paths as skipped with the winning rule (`explain`).
Commands: `cd core && ./gradlew :app:sync:test --tests '*LocalScannerTest' --tests '*ReconcilerTest' --tests '*UploadIgnoreTest' --tests '*IgnoreTreeTest'`.

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
1. Translate `exclude_patterns` into the account layer, or keep `matchesGlob` as a separate layer? Proposal: keep it separate in this portion.
2. Unreadable rule file: fail closed (hold the folder back, as the mount does) for the mirror too? Proposal: yes, same as the mount.

## How to verify live
Owner, throwaway localfs mirror profile: a tree with `node_modules/` and a root `.unidrive.ignore` listing it; sync; the folder is absent from the remote; remove the rule, sync; it uploads.
