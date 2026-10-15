# P20 CLI: inspect and safely drop stranded pending upload rows

| | |
|---|---|
| Issues | gkrost/unidrive#753 (private origin: unidrive-windows#253) |
| Labels | enhancement, sev:medium, area:cli, kind:reliability, lane:cli-trust |
| Size | M |
| Priority | rank 20 of 54 (first-sync group), wave 1 |
| Status | covered by PR #896; check its live state before starting more work |
| Depends on | nothing |
| Not in parallel with | P40 (`Main.kt` subcommand list), P18 (replay reads the same rows); same file family (take them one after the other, order in README): P40, P32, P39 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open (only `status --pending` as a count flag exists) |

## Problem
- Some pending local upload rows (`local:` remote ids) become permanently stuck when their cache content is empty or missing. Folder listings then hide those paths behind "still uploading", and the supported reset keeps the rows. Recovery today needs direct database edits or a broad profile reset.
- [code] No `pending` command exists. `StateDatabase.pendingUploadRows()` / `PendingUploadRow(path, lastSynced, lastErrorAt)` exist (`engine-core/.../StateDatabase.kt` about line 1445) but carry no local size, cache presence or cache size. `ProfileRemove.kt` already opens `state.db` read-only, detects a daemon holding the profile and refuses with `daemon_running` / `uploads_pending`: reuse that.
- Requested (issue): `unidrive pending list` (path, local size, age, cache file present and size); `unidrive pending drop <path-prefix | --zero-byte>` that previews the rows and asks for confirmation unless `--yes`; never remove a row with a remote id; never delete non-empty cache data, move it aside; the daemon must be stopped or the operation goes through an admin IPC verb; log the rows changed; safe to retry. Acceptance: after dropping stranded rows, copying the source folder back and syncing again works without losing intact local content.

## Scope
- Change: new `core/app/cli/.../PendingCommand.kt` (subcommands `list`, `drop`) registered in `Main.kt`; a richer read in `StateDatabase` (size, remote id check) and a guarded delete limited to `local:` rows; cache move-aside into a sibling folder next to the profile's cache (never into the sync root). `--json` on both (one object, refusal tokens as in `profile remove`: `daemon_running`, `state_unreadable`).
- Must not change: any row with a real remote id, the daemon's replay logic, `status --pending`.
- Decision for the first version: the command REFUSES while a daemon holds the profile (same detection as `profile remove`); an admin IPC verb is a later step.
- Out of scope: `--zero-byte` on the daemon-running path, GUI.

## Acceptance criteria
1. `PendingCommandTest` (new, pattern `ProfileRemoveTest`: temp config dir, real `state.db`, fake lock holder): `list` shows path, local size, age and cache state for a stranded row; JSON form equals the text facts.
2. `drop <prefix>` previews, asks (or `--yes`), deletes only `local:` rows under the prefix, leaves a row with a remote id, and is idempotent (second run changes nothing).
3. `drop --zero-byte` selects only rows whose local size is 0 and whose cache file is missing or empty.
4. A non-empty cache copy of a dropped row is moved aside, never deleted; the move target is reported.
5. With a daemon holding the lock the command exits 1 with `daemon_running` and changes nothing.
6. An unreadable `state.db` gives `state_unreadable`, no change.
Commands: `cd core && ./gradlew :app:cli:test --tests '*PendingCommandTest' --tests '*ProfileRemoveTest' && ./gradlew :app:sync:test --tests '*PendingUploadPathsTest'`.

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
1. Later: an admin IPC verb so a running daemon can drop rows (the issue allows either). Proposal: not in this portion.

## How to verify live
Use a throwaway profile: create a stranded row with a zero-byte cache file, run `pending list`, `pending drop --zero-byte --yes`, copy the folder back and sync. Do not use a real account.
