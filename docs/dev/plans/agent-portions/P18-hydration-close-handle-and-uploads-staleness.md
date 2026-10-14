# P18 close_handle across connections, and a staleness signal for the uploads reading

| | |
|---|---|
| Issues | gkrost/unidrive#438, gkrost/unidrive#747 |
| Labels | enhancement, sev:medium (#438), sev:low (#747), area:mount, kind:reliability, kind:observability |
| Size | M (two small changes in one file, two commits) |
| Priority | rank 18 of 53 (first-sync group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P17, P36, P08, P35 (all edit `HydrationImpl.kt`); do the two issues one after the other; same file family (take them one after the other, order in README): P17, P08, P35, P36, P03, P06, P02 |
| Status check | verified on main a0c6c4d3, 2026-10-14: both still open as described |

## Problem
- #438 [code]: `HydrationImpl.closeHandle(connectionId, handleId)` (about line 1134) removes the handle only from `openSets[connectionId]`; `openSets` is `connectionId -> handleId -> path` (about line 174). `open_read` registers on the connection that served it. A client that pools connections (the Windows client's `IpcClient` hands out any idle connection) can close on another connection: a silent no-op, and `dehydrate` answers busy for the rest of the session (the busy check reads `openSets.values`). Only a connection close clears the entry.
- #438 [code]: the spec `docs/dev/specs/ipc-write-protocol.md` section 2 states "Handles are scoped to the connection" (about line 61); the change alters that invariant.
- #438 [inferred]: handle ids are chosen by the client (`wh-N` style), so a global lookup can hit another client's handle with the same id. Prefer the caller's own connection, else accept a match only when it is unique.
- #747 [code]: `daemon.status.uploads` is read from `state.db` at most once per second (`UPLOAD_SNAPSHOT_TTL_MS`, `uploadHealthSnapshot` about line 1546) and the last reading is served when the database is busy. If every re-read fails, `refreshUploadSnapshot` logs `upload health unavailable: ...` (about line 1527) once per status request, and the last good counts are served for ever with only `oldest_pending_age_ms` moving. `UploadSnapshot.takenAtMs` exists (about line 1453) but is not on the wire. A front-end cannot tell fresh from frozen.

## Scope
- Change:
  1. `closeHandle`: look the handle up on the caller's connection first, then on the others (unique match only), remove it, and succeed (no error) when it is not found anywhere. Update `ipc-write-protocol.md` section 2 and the IPC corpus fixture of `hydration.close_handle` if its reply changes (it should not).
  2. `uploads.as_of_ms` (additive, from `takenAtMs`) in `StatusHealthJson.kt` (`UploadHealth.toJson`) and spec section 4.3; rate-limit the WARN to once per minute per cause. Fixture in `ipc-contract/daemon.status.ndjson` first.
- Must not change: handle lifetimes, the busy rule of `dehydrate`, `protocol_version`, the existing `uploads` fields.
- Out of scope: the cache size reading (P17 area), download cancel (P36).

## Acceptance criteria
1. `HydrationOpenSetConcurrencyTest` (it already drives several connections): open on connection A, close from B, then `dehydrate` succeeds; closing twice and closing an unknown id both reply ok.
2. With two clients using the same handle id on different connections, a close from one does not release the other's handle.
3. `HydrationIpcHandlerTest`: the `hydration.close_handle` verb works across connections.
4. `HydrationUploadHealthTest` (and `DaemonStatusHealthTest` in `app/cli`): after a failing re-read the reply still carries the last counts plus an `as_of_ms` that does not advance; after a good read it advances; the WARN appears once per interval.
5. `IpcContractCorpusTest` green with the new fixture field; spec edited.
Commands: `cd core && ./gradlew :app:hydration:test --tests '*HydrationOpenSetConcurrencyTest' --tests '*HydrationIpcHandlerTest' --tests '*HydrationUploadHealthTest' && ./gradlew :app:cli:test --tests '*DaemonStatusHealthTest' --tests '*IpcContractCorpusTest'`.

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
1. #747: expose `as_of_ms`, or omit `uploads` after N failed re-reads (the issue offers both)? Proposal: `as_of_ms`, and let the client decide.

## How to verify live
No live check needed.
