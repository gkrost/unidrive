# P33 Dropbox: resumable upload sessions and a live integration test

| | |
|---|---|
| Issues | gkrost/unidrive#867 |
| Labels | enhancement |
| Size | M (the live test needs the owner's app key and account) |
| Priority | rank 33 of 53 (efficiency group), wave 1 |
| Status | ready (live test needs the owner) |
| Depends on | nothing |
| Not in parallel with | P34 (`DropboxProvider`) |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open |

## Problem
- Files above 128 MiB go up in an upload session (`start`, 16 MiB `append_v2` steps, `finish`). [code] `DropboxProvider.uploadInSession` (about line 265) keeps the `session_id` only in memory; there is no store and no `incorrect_offset` / `correct_offset` handling, so an interrupted upload starts over. Cyberduck keeps the session id and offset so an upload can resume.
- The provider is tested against a scripted fake only (`FakeDropbox`, `DropboxWriteTest`, the session test at about line 100). No live test exists; `LiveGraphIntegrationTest` is the pattern (gated by `UNIDRIVE_TEST_ACCESS_TOKEN` with `assumeTrue`).
- Two things to check live (issue): whether a deleted folder reports its children's deletions or only its own; whether `move_v2` accepts a case-only rename directly (the provider goes through a temporary name now).

## Scope
- Change: a Dropbox session store in the profile folder (pattern: OneDrive `UploadSessionStore`) holding `{local path, size, mtime, session_id, committed offset}` per upload; on retry continue from the committed offset; on `incorrect_offset` take the offset Dropbox reports; sessions live 7 days, drop older ones (also on startup).
- Second step (needs the owner): a live test gated on `UNIDRIVE_INTEGRATION_TESTS` plus a Dropbox test token: delta from null and incremental, simple and session upload, a guarded upload conflict, a case-only rename, delete, the long-poll hint.
- Must not change: files below the session threshold, the upload path's conflict guard (`currentRev`), token handling.
- Out of scope: sign-in (P34).

## Acceptance criteria
1. `DropboxWriteTest` with `FakeDropbox`: interrupt after an append, restart the provider, the retry sends the next `append_v2` at the committed offset and the final file equals the source.
2. An `incorrect_offset` answer carrying `correct_offset` moves the upload to that offset; a second mismatch aborts with a typed error.
3. A session older than 7 days is dropped and the upload starts a new one; a changed local file (size or mtime) invalidates the stored session.
4. The store file is owner-only and holds no token.
5. The live test class exists, is skipped without the environment, and its two open questions are answered in its comments when run by the owner.
Commands: `cd core && ./gradlew :providers:dropbox:test --tests '*DropboxWriteTest' --tests '*DropboxAuthTest'`.

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
1. Supply a Dropbox test app key and account for the live test (the agent cannot).

## How to verify live
Owner: with the test token, run the gated live class and attach the output of the two open questions to the issue.
