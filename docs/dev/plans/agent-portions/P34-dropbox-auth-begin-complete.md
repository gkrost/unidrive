# P34 Dropbox sign-in for front-ends: auth begin / auth complete with the no-redirect code flow

| | |
|---|---|
| Issues | gkrost/unidrive#866 |
| Labels | enhancement |
| Size | M |
| Priority | rank 34 of 54 (UX group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P19 (`AuthLifecycle.kt` / `LifecycleJson.kt`); same file family (take them one after the other, order in README): P19 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open |

## Problem
- `unidrive auth` signs in to Dropbox in one process (`DropboxAuth.signInWithBrowser()` opens the browser and waits on a loopback callback). Front-ends (the Windows wizard, MCP) use `auth begin` / `auth complete`, which run as SEPARATE processes; a loopback listener started by `auth begin` dies when that process exits. [code] `DropboxProviderFactory` does not override `supportsInteractiveAuth`, so it is false; the `completeInteractiveAuth(profileDir, handle)` path and `CompleteAuthResult` have no input channel for a code. Dropbox has no device-code flow.
- Options in the issue: (1) the "no redirect" code flow: `auth begin` returns the authorize URL (no `redirect_uri`) and keeps the PKCE verifier as a pending flow in the profile folder (like OneDrive's `PendingDeviceFlow`); Dropbox shows the user a code to paste; `auth complete` takes the code and exchanges it. The code is a one-time secret, so not an argument: `--code-stdin` (precedent `auth login --password-stdin`) or a new SPI input. (2) the daemon or a helper owns the loopback listener for the flow's lifetime (more moving parts). Recommended: option 1.

## Scope
- Change: `ProviderFactory.kt` / `InteractiveAuth.kt` (a way to hand a secret code to `completeInteractiveAuth`), `DropboxProviderFactory` (`supportsInteractiveAuth = true`), `DropboxAuth` (pending-flow store in the profile folder through `CredentialStore`, as OneDrive does; PKCE verifier, expiry), `cli/AuthLifecycle.kt` (`--code-stdin`), `docs` for the wizard contract.
- Must not change: the browser sign-in of `unidrive auth`, token storage and permissions, OneDrive's flow.
- Out of scope: the Windows wizard (file a client issue once the engine shape is set), Dropbox refresh logic.

## Acceptance criteria
1. `auth begin --profile <dropbox>` prints the authorize URL and a handle (JSON); the pending flow file is owner-only and expires.
2. `auth complete --handle <h> --code-stdin` stores `token.json`; a wrong or expired code stores nothing and returns a typed error; a second complete with the same handle is refused.
3. A MockEngine token endpoint (`DropboxAuthTest`, `FakeDropbox`) serves the exchange; the code, the verifier and tokens never appear in argv, logs or JSON output (assert on captured output).
4. `AuthLifecycleTest` (pattern: the OneDrive device flow cases and `OneDrivePendingFlowTest`) covers begin, complete, refusal paths.
5. OneDrive and Internxt auth tests unchanged.
Commands: `cd core && ./gradlew :providers:dropbox:test --tests '*DropboxAuthTest' && ./gradlew :app:cli:test --tests '*AuthLifecycleTest' && ./gradlew :providers:onedrive:test --tests '*OneDrivePendingFlowTest'`.

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
None; option 1 is the issue's recommendation. The test app key for any live check is the owner's.

## How to verify live
Owner with a Dropbox test app: run begin, open the URL, paste the code into `auth complete --code-stdin`, then `ls` works.
