# P36 Mount: a running download stops when the client disconnects or cancels

| | |
|---|---|
| Issues | gkrost/unidrive#437 (first half: cancel; the download progress events are a follow-up issue to file after P26 fixes the SPI shape) |
| Labels | enhancement, sev:medium, area:mount, kind:reliability, windows |
| Size | M |
| Priority | rank 36 of 54 (UX group), wave 4 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P18, P17, P08, P35 (`HydrationImpl`), P26; same file family (take them one after the other, order in README): P18, P17, P08, P35, P03, P06, P02 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open as described |

## Problem
- [code] `hydration.open_read` blocks until the whole file is in the cache. The SPI download is whole-file with no progress callback (`CloudProvider.download(remotePath, destination): Long`); `HydrationEvent` carries progress for uploads only; `hydration.cancel` cancels uploads only (`Hydration.cancelUpload`). `IpcServer.dispatchRequest` runs the handler inline in the connection reader (`withContext(handlerDispatcher) { handler(...) }`), so a client disconnect cancels nothing: the download keeps running and holds the per-path hydrate lock; `onConnectionClosed` only drops `openSets`.
- The Windows platform kills a fetch callback after 60 s; the client has a 900 s call limit as compensation (audit #579 item 2). A client that gives up cannot stop the work, and a retry of the same path waits behind the abandoned download.
- `MountEngine` built in `DaemonRuntime` has `verifyIntegrity` off, so only a size guard protects the cache.

## Scope
- Change: `IpcServer` (when a connection closes, cancel the coroutines of requests it has in flight; keep replies already started), `HydrationImpl.openForRead` / `MountEngine.ensureHydrated` (the download job is cancellable and releases the per-path lock and the `.hydrating-*` temp file on cancel), a new READ-or-FULL class verb `hydration.cancel_read` (path) or an extension of `hydration.cancel` to downloads (decision 1), spec `ipc-write-protocol.md`, corpus fixtures first.
- Must not change: a download that other waiters for the same path still need (cancel only when the last waiter is gone), upload cancel semantics, the cache's size guard.
- Out of scope: progress events and a ranged `hydration.read` (SPI work, P26), `verifyIntegrity` in the daemon.

## Acceptance criteria
1. `HydrationImplTest` / new `HydrationDownloadCancelTest` with a gated `MemProvider` download: closing the connection mid-download cancels it, the lock is released, the temp file is gone and a second `open_read` starts a fresh download.
2. Two waiters on one path: the first disconnects, the download continues for the second.
3. `hydration.cancel_read` (or the extended verb) cancels by path; replies `ok` also when nothing runs.
4. `IpcServerTest`: a request in flight is cancelled on disconnect; no exception reaches the log above DEBUG.
5. Corpus fixture and spec updated; `IpcContractCorpusTest` green.
Commands: `cd core && ./gradlew :app:hydration:test --tests '*HydrationImplTest' --tests '*HydrationDownloadCancelTest' && ./gradlew :app:sync:test --tests '*IpcServerTest' && ./gradlew :app:cli:test --tests '*IpcContractCorpusTest'`.

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
1. A new verb or an extension of `hydration.cancel` (path semantics already exist for uploads)? Proposal: extend, with a `kind` field (`upload`, `download`, default `upload`).
2. Which scope class may cancel a download (READ, as `open_read` is READ-class)? Proposal: the connection that asked, or FULL.

## How to verify live
Owner: start a hydration of a 1 GB file, kill the client process; the engine log shows the download cancelled and a new open starts a fresh one.
