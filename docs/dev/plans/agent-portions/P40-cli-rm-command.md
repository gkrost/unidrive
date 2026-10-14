# P40 CLI: `unidrive rm` to delete a remote file or folder

| | |
|---|---|
| Issues | gkrost/unidrive#269 |
| Labels | enhancement, sev:medium, area:cli, lane:cli-trust |
| Size | M |
| Priority | rank 40 of 54 (UX group), wave 4 |
| Status | blocked on owner decision |
| Depends on | the owner decision below |
| Not in parallel with | P20, P32, P39 (`Main.kt` subcommand list); same file family (take them one after the other, order in README): P20, P32, P39 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open |

## Problem
- There is no operator command to delete a specific remote file or folder. The CLI has `trash list/restore/purge` (already-trashed items only) but no verb that moves a live remote item to the trash. Removal today needs sync propagating a local delete (the premise #268, unsafe delete via sync, is fixed) or the provider's web UI, which makes scripted cleanup of test accounts impossible.
- [code] The capability exists at the SPI: `CloudProvider.delete(remotePath, ifMatchETag)` (`CloudProvider.kt` about line 138); implemented by `OneDriveProvider` (about 328), `InternxtProvider` (about 1422), `LocalFsProvider` (about 179), `S3Provider` (about 274), `DropboxProvider` (about 315). In mount mode the daemon already serves `hydration.unlink` / `hydration.rmdir` (`HydrationIpcHandler.VERBS`): the consistent route when a daemon runs, as `ls` and `get` route through the daemon.
- Requested shape (issue): `unidrive -p <profile> rm <remotePath>`; `-r/--recursive` for folders; confirmation for folders and non-empty targets, `--yes` for scripts; `--purge` if a hard delete is wanted and the provider supports it; trash versus hard delete matches what the provider's `delete()` already does (Internxt trashes).

## Scope
- Change: new `core/app/cli/.../RmCommand.kt` in `Main.kt`; routing: a running mount daemon gets `hydration.unlink` / `hydration.rmdir` over the authenticated IPC (pattern: `LsCommand`'s `IpcAuthClient` / `IpcEndpoint` use), otherwise the provider's `delete` directly; `--json` with refusal tokens (`not_found`, `folder_not_empty` without `-r`, `daemon_running` where the mirror engine owns the profile, `confirmation_required` without a console and without `--yes`).
- Must not change: the provider `delete` semantics, the mirror engine's own delete propagation, the `trash` command.
- Out of scope: wildcard patterns, deleting through a state-db-only path.

## Acceptance criteria
1. `RmCommandTest` (pattern `LsCommandTest`, real `LocalFsProvider` as the fake remote): a file is removed; a folder without `-r` is refused; with `-r --yes` it is removed with its children; a missing path gives `not_found`; the JSON form matches the text facts.
2. Without a console and without `--yes` the command refuses and changes nothing.
3. With a (fake) daemon holding a mount profile the command sends `hydration.unlink` / `hydration.rmdir` and not the provider call.
4. `--purge` on a provider that cannot hard-delete is refused with a clear message.
5. Nothing is logged that names more than the path the user typed.
Commands: `cd core && ./gradlew :app:cli:test --tests '*RmCommandTest' --tests '*LsCommandTest'`.

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
1. Behaviour on a MIRROR profile: a remote delete behind the engine's back leaves `state.db` stale (the next sync would re-upload the local file). Refuse on mirror profiles, delete local and remote together, or document? Proposal: refuse on mirror profiles in the first version.
2. `--purge`: offered at all?

## How to verify live
Owner, throwaway localfs or test-account profile: `rm -r` a test folder; `ls --live` no longer lists it.
