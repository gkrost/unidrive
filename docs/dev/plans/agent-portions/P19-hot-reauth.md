# P19 Sign in again while the daemon holds the profile

| | |
|---|---|
| Issues | gkrost/unidrive#813 |
| Labels | enhancement (desktop-UI lane) |
| Size | M |
| Priority | rank 19 of 54 (first-sync group), wave 3 |
| Status | ready (pick A or B on the issue first) |
| Depends on | PR #876 is merged (it adds the admin verb `auth.logout`, a template) |
| Not in parallel with | P34 (`AuthLifecycle.kt`), P01 (stdin password), P42 (`DaemonRuntime.kt`); same file family (take them one after the other, order in README): P34 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open |

## Problem
- `auth login` / `auth complete` refuse with `profile_in_use` while a daemon holds the profile (`LifecycleJson.withProfileLock`, about lines 118-141). A front-end learns that an account needs signing in again FROM the running daemon (`daemon.status.credential_health` `expired` or `missing`, PR #810), so today the only way is to stop the daemon first, which a mount must not need.
- Owner decision 2026-10-13 (client questionnaire gkrost/unidrive-windows#258, recorded on the issue): re-authentication is solved in the engine; `auth` hands the new credentials to the running daemon over IPC; the daemon is not stopped; this answers #743. The client's Sign in then works unchanged.
- [code] There is no `auth.login` / `auth.reload` verb. The only auth verb is `auth.logout` (admin class, `AuthLogoutRpcHandler`, fixture `ipc-contract/auth.logout.ndjson`, spec daemon design section 4.10): a ready template including the callback that marks the engine provider unauthenticated.
- [unverified] Whether each provider instance re-reads its credentials in place after a new token file appears.

## Scope
- The issue offers two shapes; pick one on the issue before coding and say why. Proposal B: `auth begin/complete/login` run beside the daemon, write the credential file atomically (owner-only, as today), then call a new admin verb `auth.reload`; the daemon reloads its provider credentials in place, re-runs the credential check and `daemon.status` turns `ok` at once. A (the secret in an IPC body, `auth.login`) is possible but puts secrets on the wire and into more code paths.
- Change: `AuthLifecycle.kt`, `LifecycleJson.kt` (a held profile is no longer a refusal for the auth verbs; other lifecycle verbs keep it), `DaemonRuntime.kt` plus a handler like `AuthLogoutRpcHandler` (registered before the socket binds), `IpcAuth.VERB_CLASSES` (admin), `CredentialHealthMonitor` refresh, spec section 4.10, corpus fixture first.
- Must not change: where credentials are stored, owner-only permissions, `logout`, the client contract of `auth begin/complete/login` replies.
- Out of scope: provider-specific sign-in flows (Dropbox: P34).

## Acceptance criteria
1. A fake daemon holding the lock (pattern `AuthLogoutRpcHandlerTest`, `LogoutCommandJsonTest`): `auth login` succeeds, stores the credentials and the daemon's next `daemon.status` reports `authenticated: true` and `credential_health.state: ok`.
2. A wrong password leaves the old credentials untouched and the daemon unauthenticated state unchanged.
3. No secret in any argv, log line or reply (assert on captured output).
4. The reload does not interrupt a running upload or enumeration (test with a gated fake provider).
5. A daemon too old for the verb is reported with a clear token (`daemon_too_old`), not `profile_in_use`.
6. `IpcContractCorpusTest` and the verb-class table test green.
Commands: `cd core && ./gradlew :app:cli:test --tests '*AuthLifecycleTest' --tests '*DaemonCredentialHealthTest' --tests '*AuthLogoutRpcHandlerTest' --tests '*IpcContractCorpusTest' && ./gradlew :app:sync:test --tests '*IpcAuthTest'`.

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
1. A (IPC body) or B (file plus `auth.reload`)? Proposal B.

## How to verify live
Owner, throwaway profile: expire the token, watch `credential_health` turn `expired`, run `auth login` from the client's Sign in while the mount runs; the mount keeps running and turns healthy.
