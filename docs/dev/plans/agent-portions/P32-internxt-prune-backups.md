# P32 Internxt: `unidrive prune --backups` for keep_overwritten archives

| | |
|---|---|
| Issues | gkrost/unidrive#155 |
| Labels | enhancement, sev:low, area:internxt, area:cli, kind:efficiency, lane:deferred |
| Size | M |
| Priority | rank 32 of 54 (efficiency group), wave 4 |
| Status | deferred by the owner, start when asked |
| Depends on | PR #880 merged (same `InternxtProvider.kt`) |
| Not in parallel with | P09, P05, P40 (`Main.kt`); same file family (take them one after the other, order in README): P09, P15, P16, P22, P21, P24, P25, P05, P31, P20, P40, P39 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open; no prune command exists |

## Problem
- `keep_overwritten = true` (profile flag, default off) renames the prior cloud content to `<plainName>.unidrive-prev-<UTC stamp>` before every replace (`InternxtProvider.tryKeepOverwrittenRename`, about line 633; name from `keepOverwrittenArchiveName`, about line 2894: `yyyy-MM-dd'T'HH-mm-ss` in UTC, plus `-2`, `-3` on a same-second collision). Internxt has no server-side versioning, so these archives are the only recovery, they double the storage per edit, and nothing removes them.
- [code] `core/providers/internxt/README.md` (line about 100) promises "the upcoming `unidrive prune --backups` command". No `PruneCommand` exists in the CLI; `VersionsCommand.pruneAll` is the local versions manager, a different thing. Issue text: `unidrive prune --backups [--older-than 30d] [--profile NAME]`, glob on `.unidrive-prev-*` in `plainName`.

## Scope
- Change: new `core/app/cli/.../PruneCommand.kt` registered in `Main.kt`; the provider side lists archive items (a live listing of the account through the cursor/sync listing, filtering the name pattern, never trusting the name alone: the item must be a sibling of a live file with the stripped name or be old enough) and deletes them through the provider's `delete` (Internxt trashes); `--dry-run` default prints what would go; `--yes` to act; `--older-than` accepts `30d`, `12h`; `--json`.
- Must not change: `keep_overwritten` itself, the archive name format, the sync state of live files.
- Out of scope: pruning on a schedule, other providers.

## Acceptance criteria
1. A CLI test in the style of `AuthLifecycleTest` with a fake provider exposing `a.txt`, `a.txt.unidrive-prev-2026-01-01T00-00-00`, `a.txt.unidrive-prev-2026-01-01T00-00-00-2` and a user file named `b.unidrive-prev-x`: dry run lists the two archives, `--older-than` filters by the stamp, `--yes` deletes exactly those; the user file with a non-matching stamp is left alone.
2. An Internxt MockEngine test (`InternxtCreateCollisionTest` helpers) shows the delete requests for archives only.
3. A running daemon holding the profile: the command refuses (`daemon_running`) or routes through the daemon, per decision 2.
4. The README line is rewritten to the real command.
Commands: `cd core && ./gradlew :app:cli:test --tests '*PruneCommandTest' && ./gradlew :providers:internxt:test --tests '*InternxtCreateCollisionTest'`.

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
1. "Older than" from the UTC stamp in the name, or from the item's cloud modification time? Proposal: the stamp (it records when the overwrite happened).
2. Trash or permanent delete (`--purge`)? Proposal: trash, as every provider `delete` does.
3. Mirror versus mount profile behaviour while the daemon runs.

## How to verify live
Owner, isolated account with `keep_overwritten = true`: overwrite a file twice, run `prune --backups --older-than 0d` as a dry run, then with `--yes`: only the two archives go to the trash.
