# Lane: bugs + quick wins (gkrost/unidrive), for an agent

Prepared 2026-10-11 against `main` `ed40de96`. This edition replaces the 2026-10-09 one, whose tiers are merged, closed or carried over
below. It also supersedes `lane-engine-bugs.md` and `lane-engine-quickwins.md` (2026-10-05). A read-only triage of all open issues
verified every anchor below on `main`. Line numbers drift, so trust the symbol. Work the tiers **in order**: within a tier, the
"Sequencing" notes resolve shared files. One item = one branch = one **draft** PR; the owner merges.

## Rules (read before any code)
- **Claim:** comment on the item's issue ("lane: starting, branch …"), and check that it is still open and that no open PR touches the
  same files: `gh pr list --state open`.
- **Workspace:** never a worktree another session owns. `git fetch origin`, then
  `git worktree add ../wt-lane-<issue> -b fix/<issue>-<slug> origin/main`. One branch and one draft PR per item, against `main`, never
  stacked.
- **Test-first:** show the new test failing on `main`, then passing; both go in the PR body. Confirm your filter really ran
  (`build/test-results/**/*.xml`). A flake fix shows the race (a forced interleaving or a stress loop), not just one green run.
- **Gate:** `cd core && ./gradlew check` green before a PR leaves draft (6–15 min). Known flakes under parallel load are the Tier 2 items
  below: re-run the class once in isolation before investigating, and serialize gradle runs across worktrees. Do not wait on another
  session's gradle run; if the daemon is busy, use `--no-daemon`.
- **Data-safety items (Tier 1)** get a test that writes real bytes and checks they survive, not just that a row changed.
- **Hands off:**
  - **The desktop-UI lane** (`docs/dev/plans/lane-desktop-ui.md`, epics #650/#651): #652–#660, #656 PR c and the #607 (c) restructure
    of `DaemonRuntime`. Its next wave works in `DaemonRuntime.kt` and the IPC contract corpus. Do not restructure those files; a
    one-line change there needs a note on the UI item that owns the file.
  - **The Linux session:** the items listed under "Linux session" below.
  - **`harden/validate-client-paths`** (a remote branch, no PR): it changes the hydration cache-path containment (`MountEngine`,
    `SyncEngine.resolveCachePath`). Leave that containment alone.
  - Do not regenerate the detekt/ktlint baselines or the lockfiles (#712). Add a new finding to the baseline only when the PR says why.
- **The owner's machine:** no deploy, no live account, and no running `unidrive`/`java` process touched. Use fakes, `localfs` or temp
  dirs only. A repro signals only the PID it started.
- **Public repository:** no private paths, names or addresses in code, tests, commits or PRs. No issue IDs or dates in commit subjects.
  Security findings never go in a public issue (see `SECURITY.md`); seven security-labelled items are deliberately not in this lane.
- **Licence:** for git- or rsync-compatible behaviour, implement from the published documentation and black-box runs only. Never read
  or port GPL source.
- **Report:** a comment on the issue with the PR number, red/green evidence, the gate result and anything left.

## Tier 1: data safety (first, in this order)

### D1: #737 Mirror: a local file is deleted without a copy when the remote turns its path into a folder
- **Anchor:** `PlaceholderManager.createPlaceholder`, the parent walk: `if (Files.isRegularFile(check)) {` →
  `Files.deleteIfExists(check)` → `Files.createDirectories(check)`.
- **Fix:** move the file aside first, as a conflict copy named the way keep-both names them, and record it as a conflict.
  If the copy cannot be made, refuse the placeholder: never delete. An unchanged, already-synced file may be replaced only once its bytes
  are known to be in the cloud (the row's hash or size matches the remote's last state).
- **Tests:** an unsynced local `/x` plus a remote `/x/child`: `/x`'s bytes survive in the copy, and `/x/child` is created. A synced,
  unchanged `/x` is replaced without a copy only when the cloud still has it.
- **Size:** S.

### D2: #738 Keep-both: two conflicts on one file within a minute overwrite the first conflict copy
- **Anchor:** `SyncEngine.applyKeepBoth`: `.ofPattern("yyyyMMdd'T'HHmm")`, then
  `Files.move(localPath, conflictLocal, java.nio.file.StandardCopyOption.REPLACE_EXISTING)`.
- **Fix:** seconds in the stamp, plus a free-name counter (`… (2)`). Drop `REPLACE_EXISTING`: an existing name is a bug, not a target.
  Keep the remote copy's name scheme the same.
- **Tests:** two conflicts with a pinned clock in the same second: both copies exist with their own bytes.
- **Size:** S. **Sequencing:** before D4 (same file).

### D3: #739 A real all-zero file is taken for a sparse stub and its failed upload is never retried
- **Anchor:** `LocalScanner`: `val sparseLeftover = isSparseLeftover(file, attrs.size())` → `isHydrated = !sparseLeftover,`. The recovery
  loops then skip it (`Reconciler`, both `if (!entry.isPendingUpload) continue` sites).
- **Fix:** a path with no `state.db` row cannot be an engine placeholder (placeholders are 0-byte), so skip the probe when the row is
  absent. Keep #716's full-read confirmation for rows that exist.
- **Tests:** an all-zero 4 MiB file whose first upload fails is uploaded on the next pass, and a real stub row is still detected.
- **Size:** S.

### D4: #424 Remote folder rename: descendant MoveLocals recreate empty placeholders over hydrated files
- **Anchors:**
  - `Reconciler`, the rename planner, filters actions by `action.path.startsWith(oldPrefix)`. Descendant moves carry the new path, so
    they survive.
  - `SyncEngine.applyMoveLocal`: when the source is gone, it falls back to `placeholder.createPlaceholder(action.path, …)` over the file
    the folder move already put there.
  - From the code, a local edit not yet uploaded inside that folder is lost too, not only re-downloaded.
- **Fix:**
  - drop descendant MoveLocals whose `fromPath` lies under a moved folder;
  - defence in depth: source missing and destination present → update the row only, never recreate.
- **Tests:** rename `/d` → `/e` remotely with hydrated descendants and one unsynced local edit: bytes, hydration and the edit survive. A
  second sync plans nothing.
- **Size:** S/M. **Sequencing:** after D2 (`SyncEngine.kt`), and before C4 (`Reconciler.kt`).

### D5: #307 OneDrive `include_shared` reads only the first page of sharedWithMe
- **Anchors:**
  - `GraphApiService`, the sharedWithMe call, decodes one `DriveItemCollectionResponse` and returns `collection.value`, with no
    `@odata.nextLink` loop. Items past page 1 look deleted.
  - `OneDriveProvider` gives those items `hash = item.file?.hashes?.sha256Hash`, while the provider declares QuickXor.
- **Fix:**
  - follow `nextLink` the way `listChildren` does;
  - use the provider's own `quickXorHash ?: sha256Hash` order.
- **Tests:** a MockEngine with two pages: all items present, and the hash taken from QuickXor.
- **Size:** S. **Sequencing:** the first of the `GraphApiService` chain (D5 → C2 → C3).

## Tier 2: CI flakes (test-side, file-disjoint, parallel)

- **F1, #709 `DaemonRuntimeTest.refresh_run_emits_terminal_event_after_completing_enumeration`** (Windows leg, seen again at
  `ed40de96`).
  - The anchor is `readUntil(channel, "refresh.done", timeoutMs = 10_000)`.
  - Use the shared budgeted helper, and have each failure name its step and the elapsed time.
  - If a second `refresh.run` right after `refresh.done` reads as busy, that is an ordering bug: file it, don't widen the wait.
- **F2, #713 `CoroutineDebugTest`** (Linux).
  - The watcher runs on the shared `Dispatchers.Default`, and `install()` and the dump swallow their errors.
  - Assert `DebugProbes.isInstalled`, give the watcher its own thread, and expose a failure hook to the test.
- **F3, #711: a refused IPC client on Windows sometimes loses the `too_many_clients` line.**
  - `IpcServer.refuse` drains without blocking after `shutdownOutput()`, so late input still resets the connection.
  - Wait up to about 200 ms for the peer's EOF before `close()`. This is a product change: keep it to `refuse`.
- **F4, #726 `InternxtShardPutWatchdogTest`.**
  - The budgets are sub-second (`idleWindowMs = 1_000`, `socketTimeoutMs = 500`).
  - Scale them (an idle window of about 3 s, with the body sized to match) or inject a clock, and name the step that went over.
- **F5, #718 (test side only): a same-size cache edit within one millisecond looks unchanged.**
  - The tests' edit (`HydrationCacheBudgetTest`; `EnumerateRemoteIntoStateTest` races `Instant.now()` the same way) does not set the
    mtime. Set it explicitly in both tests.
  - The product side (ns precision) is an owner decision.
- **F6, `OwnerOnlyTest` long-path precondition (file the issue first).**
  - `while (nested.toString().length < 240)` plus a 20-character file name can land exactly on 260, and the test asserts `> 260`.
  - Loop until the final file path exceeds 260.

## Tier 3: correctness

### C1: #700 Daemon lock: a recycled pid counts as the daemon
- **Anchors:**
  - `isLockHolderAlive(pid) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)` (`DaemonCommand.kt`), and the same check in
    `ProcessLock`, `DoctorCommand` and `MigrateConvertCommand`;
  - `daemon stop` uses `destroy = { handle.destroy() }`.
- **Fix:** one shared `isLockHolder(pid, lockFile)`:
  - the process's `startInstant` must not be later than the lock file's mtime;
  - best effort: its command line contains `daemon run`.
  - Never `destroy()` a process that fails the check; report it instead.
- **Tests:** through the injected `isAlive` seam: a live pid that started after the lock is not the holder; stop does not destroy it.
- **Size:** M. **Sequencing:** before #656 PR c (UI lane), which builds on the same check.

### C2: #308 then C3: #330 OneDrive resumable uploads (one at a time, after D5)
- **#308:** `GraphApiService`'s session upload calls `sessionStore.delete(remotePath)` in a catch-all `catch (e: Exception)`, so a
  cancellation or a transient IO error restarts the upload from byte 0. Delete only on non-retryable results (4xx other than 408/429;
  404/410 are already handled). Rethrow cancellation and IO errors without touching the store. S.
- **#330:** a retry after the server already committed the whole chunk sends an empty, inverted range. Also, `skip` is computed from
  the current offset but applied to the original buffer (`val skip = (nextStart - currentOffset).toInt()` →
  `bytes.copyOfRange(skip, …)`). If `nextStart > endByte`, the chunk was accepted. Compute `skip` against the chunk's own start. S.
- **Tests:** MockEngine sequences for each. #521's per-request timeout question stays out (owner).

### C4: #315 The case-collision guard covers only NEW paths
- **Anchor:** the guard in `Reconciler` checks `state == ChangeState.NEW`. Both upload-recovery loops (`if (!entry.isPendingUpload)
  continue`) upload case twins without checking.
- **Fix:** skip and warn when another live row has the same case-folded path, in both loops.
- **Tests:** they need a case-sensitive file system, so they run on the Linux CI leg (`assumeTrue`).
- **Size:** S. **Sequencing:** after D4 (`Reconciler.kt`).

### C5: #394 `ls --live` lists trashed and removed children
- **Anchor:** `LsCommand`: `val children = provider.listChildren(normalized)`, printed as they come.
- **Fix:** `children.filterNot { it.deleted }` in `LsCommand` only. The provider stays as it is (`CloudRelocator` relies on it).
- **Tests:** a fake provider with a deleted child.
- **Size:** S.

### C6: #395 (NFC part) `sync_path` roots are not NFC-normalized
- **Anchor:** `SyncScope.normalizePath` fixes only slashes and leading/trailing `/`.
- **Fix:** apply the engine's NFC normalizer there.
- **Tests:** `SyncScopeTest`: an NFD root matches its NFC paths.
- **Size:** S. The rest of #395 (the `--full-tree` UX) is out.

### C7: #748, then #747, then #696 (the `daemon.status` health readings in `HydrationImpl`, in this order)
- **#748:** each `daemon.status` calls `requestCacheMeasurement()`, which walks the whole cache once its 10 s TTL has expired. That is
  the walk #736 removed. Serve #736's running total (`cacheBytes`) once the first walk has seeded it; `bytes` stays `null` before that.
  The test is a counting seam: N status requests under budget cause at most one walk. S.
- **#747:** the uploads reading can be served stale with no signal. Add `as_of_ms` (from the snapshot's `takenAtMs`) and rate-limit the
  WARN. It is an additive field: the fixture goes into the IPC contract corpus first. S.
- **#696** (items 1, 2 and 4): every read of a known-truncated object asks the provider again and logs a WARN. Add a per-daemon memo
  keyed by remote id with size and mtime, cleared on change, and WARN once. Item 3 (a `daemon.status` field) belongs to the UI lane. M.
- All three touch `HydrationImpl.kt`, so do them one at a time.

## Tier 4: quick wins

- **Q1, `docs/config-schema/config.example.toml`:**
  - remove the retired `sync_root_rescan_minutes` (#670);
  - add `mode = "mirror"` to the sample profile (`mode` is mandatory);
  - fix the header line "every key is optional";
  - replace the personal sample path with a neutral one.
- **Q2, #412 residual:** `.claude/skills/unidrive-log-anomalies/SKILL.md` still links `docs/backlog/BACKLOG.md`. Point it at the root
  `BACKLOG.md`. Then list on #412 what is left (most items were done by #446 and #590).
- **Q3, #741:** `isValidProfileName` (`ProfileConfigEditor.kt`) accepts Windows reserved device names (`con`, `nul`, `com1`, …). Refuse
  them case-insensitively on every platform, with a distinct error token.
- **Q4, #742:** every device-code failure becomes `auth_failed` (`AuthLifecycle`, `is CompleteAuthResult.Failure -> …`). Add an optional
  `reason` (`expired`, `unknown_handle`, `access_denied`), map it in the OneDrive factory, and cap the echoed provider text.
- **Q5, `--totp-stdin`** (a #725 review follow-up; file the issue first): `auth login --totp` takes the code in argv. Read it from the
  second stdin line when `--totp-stdin` is given. **Sequencing:** after Q4 (same file).
- **Q6, #740:** concurrent `profile add`/`set`/`remove` can lose an update. The interactive `add` and `remove` (`ProfileCommand`) write
  with a plain `Files.writeString`. Use one
  `config.toml.lock` and one shared atomic-replace helper (`writeConfigAtomically` exists in `ProfileSettings`, and a second copy is in
  `ProfileCreate`) for all three. M. **Sequencing:** after Q3; before #656 PR c.
- **Q7, #626 item 3:** `CacheFolder.isInsideCacheFolder` duplicates `CachePaths.isInside`. Delegate. This rewires the check, it does
  not change it; leave the containment semantics alone (see the hands-off note).
- **Q8, #688:** the jlink image has no CDS archive. Measure the start-up time on Windows first. Add `--generate-cds-archive` only with
  the numbers and the size change in the PR. The owner decides whether it is worth the size.

## Sequencing (shared files)
- `SyncEngine.kt`: D2 → D4.
- `Reconciler.kt`: D4 → C4 (and D3, if its fix lands in `Reconciler`).
- `GraphApiService.kt`: D5 → C2 (#308) → C3 (#330).
- `HydrationImpl.kt`: #748 → #747 → #696.
- Profile config: Q3 → Q6, then the UI lane's #656 PR c. `AuthLifecycle.kt`: Q4 → Q5.
- `DaemonCommand.kt` / `ProcessLock.kt`: C1 before #656 PR c.
- Tier 2 items are file-disjoint and may run in parallel with anything above.

## Close candidates (owner)
- **#305:** fixed by #715. `install.sh` removes the old jar before copying (a fresh inode).
- **#320:** fixed by #716. No `ProcessBuilder("stat"` is left.
- Both are claimed by the Linux session: let it confirm and close them.

## Needs the owner's decision (not in this lane)
- **#743:** support re-authenticating while the daemon runs (a narrower token lock, or an IPC verb)?
- **#448 (rest):** should excluded items be refused by `openForRead` with a typed error, or explicitly allowed? `ls`/`get` follow the
  same answer.
- **#511:** a `remote_version` write-token column (schema), or the interim fallback for non-Graph etags?
- **#321:** the 120 s upload grace lives in memory only. Persist the upload marks, or gate on "the upload finished before the
  enumeration started"?
- **#626 item 1:** is it deliberate that `sync --dry-run` tightens permissions?
- **#310:** OneDrive name rules: in the provider, or through the capability contract?
- **#718 (product):** store `localMtime` with ns precision (a `state.db` semantic change)?
- **#151:** the TRASHED-vs-DELETED spec proposed in the 2026-10-04 comment.
- **#594:** adopt instead of keep-both after an Internxt 409 (needs a hash-comparison design).
- **#521:** a per-request socket timeout, or a write-progress watchdog like Internxt's?

## Linux session
#746, #745, #202 (the co-daemon connect retry; the CLI half is done by #723), #693, #325, and the #718 tmpfs repro.

## Not in this lane (too large or needs investigation)
#610, #147, #316, #312, #309, the #560/#578 areas, #580 (claimed on its own branch), and the desktop-UI epics.
