# Lane: engine bugs (gkrost/unidrive) — for a Sonnet-class agent

Prepared 2026-10-05 against `main` `0d239bf`. Every item was verified against the code (file:line below) by a read-only triage. Work them
**in order**; the order resolves shared files. Data safety first.

## Rules (read before any code)
- **Claim:** comment on the item's issue ("bug lane: starting, branch ...") before touching code. Re-check that it is still open and that
  no open PR touches the same files: `gh pr list --repo gkrost/unidrive --state open`.
- **Workspace:** never the main checkout. `git fetch origin`, then `git worktree add ../wt-bug-<n> -b fix/<n>-<slug> origin/main`. One
  branch and one **draft** PR per item, against `main`, never stacked. The owner merges; never merge yourself.
- **Test-first:** show the new test failing on `main`, then passing; put both in the PR body. Check that your test filter really ran (look
  at `build/test-results/**/*.xml`).
- **Gate:** `cd core && ./gradlew check` green before the PR leaves draft. It takes 6–12 min; `DaemonRuntimeTest` is a known flaky
  socket-timing test, so re-run it once before investigating. No tree-wide formatters.
- **Hands off (running work):**
  - **#560 engine split:** `core/app/hydration/**` (`HydrationImpl`), `core/app/engine-core/**` (except where an item says otherwise, after
    a claim on #560), the mount methods and the enumeration in `SyncEngine.kt` (PR #581 moves them), `RefreshRpcHandler`, `DaemonRuntime`,
    the `SyncCommand` hydration wiring, the `migrate` command.
  - **#578:** the Internxt upload path.
  - **The IPC contract corpus:** any new verb, field or error token needs a claim first.
  - **The owner's machine:** no deploy, no live account, no running `unidrive`/`java` process; fakes, `localfs` or temp dirs only.
- **Public repository:** no private paths, names, ids or e-mail addresses in code, tests, commits or the PR.
- **Style:** plain, factual KDoc and comments with issue numbers; new default parameters go before trailing lambdas.
- **Report:** a comment on the issue with the PR number, red/green, the gate result and anything left.

## B1 — #424 A remote folder rename turns the moved local files into empty placeholders (local bytes lost)
- **Evidence:**
  - the planner emits a `MoveLocal` per descendant (`Reconciler.kt:1248-1253` filters only candidates under the OLD prefix);
  - the folder move runs first (`:1290-1310`);
  - then `applyMoveLocal` (`SyncEngine.kt:4365-4369`) finds the source gone and calls `placeholder.createPlaceholder`, which deletes the
    real file at the new place and creates 0 bytes (`PlaceholderManager.kt:213-214`), outside the trash. An unsynced local edit is lost.
- **Fix:**
  - the planner drops descendant `MoveLocal`s whose `fromPath` is under a moved folder's `fromPath` and whose `path` is that folder's
    `path` plus the same relative part;
  - defence in depth in `applyMoveLocal`: source missing and destination present means update the row only, never `createPlaceholder`.
- **Tests:**
  - `SyncEngineTest`: sync `/d/a.txt` and `/d/sub/b.txt`, rename `/d`→`/e` remotely (same ids), sync. Both files keep their bytes and stay
    hydrated, and a second sync plans nothing.
  - A planner unit test: no descendant `MoveLocal`.
- **Size:** M. **Order:** after PR #581 has merged (it touches `SyncEngine.kt`); before B2 (same `Reconciler.kt`).

## B2 — #315 The case-collision guard fires only on the first scan; recovery uploads case twins blindly
- **Evidence:**
  - the collision checks key on `ChangeState.NEW` (`Reconciler.kt:232-262`);
  - the pending-upload recovery loops have no case check (`:322-338`, streaming twin `:582-594`);
  - on scan 2 both twins upload, and on a case-insensitive remote the second overwrites the first.
- **Fix:** in both recovery loops, skip with one warning when another alive row has the same `path.lowercase(Locale.ROOT)`. This keeps the
  first-scan policy. The provider case policy stays with #309; do not decide it here.
- **Tests:** `A.txt` and `a.txt`: sync 1 gives a Conflict (covered); sync 2 plans no `Upload` for either. Run both streaming and
  non-streaming.
- **Size:** S. **Order:** after B1.

## B3 — #308 OneDrive: the resumable upload session is deleted on any exception (cancellation and IO included)
- **Evidence:** `GraphApiService.kt:693-697` catches `Exception` and calls `sessionStore.delete(remotePath)`. A daemon stop mid-chunk, or an
  IO error, restarts the next attempt at byte 0. #521 builds on this.
- **Fix:**
  - rethrow `CancellationException` without deleting the session;
  - keep the session on IO and transient errors (5xx, 408, 429);
  - delete only on a permanent 4xx or a session-gone 404/410 `GraphApiException`.
- **Tests:** `MockEngine` (style of `ListChildrenPaginationTest`/`GraphWriteThrottleTest`). The second chunk throws: an `IOException` keeps
  the session, a `CancellationException` keeps it, a 400 removes it.
- **Size:** S.

## B4 — #330 OneDrive: a chunk retry after the server committed the whole chunk sends an invalid Content-Range
- **Evidence:** `GraphApiService.kt:921-931` trims the buffer to `nextStart`. With `nextStart == endByte + 1` the next PUT has an empty body
  and `Content-Range: bytes end+1-end/...` (`:861`); Graph answers 400, which is permanent (`:893-898`). With `nextStart > endByte + 1`,
  `copyOfRange` throws.
- **Fix:**
  - `nextStart > endByte` means the chunk is accepted: continue `uploadLargeFile`'s loop at `nextStart` (a small return-type change);
  - final chunk without `nextExpectedRanges`: fetch or complete the item.
- **Tests:** `MockEngine`: the chunk PUT answers 503, and the session GET says `nextExpectedRanges: ["<endByte+1>-"]`. No PUT is sent with
  start > end, and the upload completes. Add a last-chunk case.
- **Size:** M. **Order:** after B3 (same file).

## B5 — #307 OneDrive: `listSharedWithMe` ignores pagination; shared items get a SHA-256 hash
- **Evidence:**
  - `GraphApiService.kt:1195-1200` decodes one page only (no `@odata.nextLink` loop);
  - `OneDriveProvider.kt:369` uses `sha256Hash`, while `toCloudItem` prefers QuickXor (`:454`).
  - With `include_shared`, items beyond page 1 drop out of every delta.
- **Fix:** loop on `nextLink` as `listChildren` does; use `quickXorHash ?: sha256Hash`.
- **Tests:** `MockEngine` with two `sharedWithMe` pages: both are returned, and an item with both hashes gets QuickXor.
- **Size:** S. **Order:** after B3/B4 (same files, separate regions).

## B6 — #528 Windows CLI arguments made only of emoji/CJK arrive as `?`
- **Evidence:** `WindowsArgv.kt:25` returns early when every character is ASCII. A degraded character IS `?` (ASCII), so a fully degraded
  argument is never repaired, although `pickTrailing` (`:37-`) does the right comparison.
- **Fix:** keep the cheap path only when no argument contains `?`; otherwise run `pickTrailing` against the native command line.
- **Tests:** extract the logic behind the `os.name`/native call into an internal function, then cover: an emoji-only argument, a CJK-only
  argument, the existing mixed case, and an ASCII argument with a literal `?` (unchanged when the native line has `?` too).
- **Size:** S. Independent.

## For the owner (not agent tasks)
- **Close candidates (fixed on main; evidence in the triage):**
  - #490 (by #492);
  - #526, #531, #554 (by #557);
  - #298, #405 (dry-run purity ratchet empty);
  - #317 (by #522);
  - #532 (§1–5 by #546);
  - #152 (no UI module in this repo).
- **Narrow:** #323 (state.db half done by #482), #324 (only branch protection left).
- **Highest-priority design item, excluded here:** #268 (Tier 0). It needs a reproducing test first, and the proposed gate would block
  genuine new uploads on incremental passes; the code is in `SyncEngine.syncOnce`, which #581 is changing.
