# Lane: bugs + quick wins (gkrost/unidrive) — for an agent

Prepared against `main` at the #662 merge. Every item was re-verified against the code (function-level
anchors below) by a read-only triage; the open-bug sweep verdicts live in
`docs/dev/plans/Agent_open-bug-sweep.md`. Work the tiers **in order**; within a tier the order resolves
shared files. One item = one branch = one **draft** PR; the owner merges.

## Rules (read before any code)
- **Claim:** comment on the item's issue ("lane: starting, branch …") and re-check it is still open and
  no open PR touches the same files: `gh pr list --state open`.
- **Workspace:** never a worktree another session owns. `git fetch origin`, then
  `git worktree add ../wt-lane-<issue> -b fix/<issue>-<slug> origin/main`. One branch, one draft PR per
  item, against `main`, never stacked.
- **Test-first:** show the new test failing on `main`, then passing; both go in the PR body. Confirm your
  filter really ran (`build/test-results/**/*.xml`).
- **Gate:** `cd core && ./gradlew check` green before a PR leaves draft (6–15 min). Known flakes under
  parallel load: `DaemonRuntimeTest` (socket timing), `RelocateCommandTest.deleteSourceRecursive`,
  `IpcContractCorpusTest` (shared `%TEMP%` sockets) — re-run the class once in isolation before
  investigating; serialize gradle runs across worktrees.
- **Hands off (in flight at prep time):**
  - **#663** (open PR): `RemoteGather.updateRemoteEntries` + `RemoteEnumerationTest` — do not touch the
    delta upsert.
  - **#665** (open PR): `PlaceholderManager.localNameIssue`, `LocalScanner`'s length-rule consumer and
    their tests — do not touch the name-issue rules.
  - **#669** (open PR): `DaemonRuntime` quota members, `SyncConfig` quota keys,
    `QuotaCommand`, `ProviderMetadata.hasQuota`, localfs metadata.
  - **U6 cutover** (`feat/u6-mount-cutover`, issue #605): the mount/mirror isolation rewrite owns the
    engine cutover surfaces — coordinate before anything that rewrites `SyncEngine`/`MountEngine`
    structure. After **#661** (merged), #605 is unblocked; if its lane starts, pause B-tier items that
    touch the same files.
- **The owner's machine:** no deploy, no live account, no running `unidrive`/`java` process killed; fakes,
  `localfs` or temp dirs only. Windows-local verification is available (this is a Windows box).
- **Public repository:** no private paths, names or addresses in code, tests, commits or PRs. No issue
  IDs/dates in commit subjects.
- **Report:** a comment on the issue with the PR number, red/green evidence, the gate result and anything
  left.

## Tier 1 — start now (file-disjoint from the open PRs)

### Q1 — #625 Finish the startup-budget consolidation (test-only)
- **State:** `DaemonRuntimeTest.awaitSocket(daemonJob)` is already the fixed helper (30 s `withTimeout`,
  died-start check, from the U4 branch). Still on inline short budgets: ~6 sites in `DaemonRuntimeTest`
  (`2.5s`/`5s` assertTrue polls), `IpcContractCorpusTest` (2× `repeat(50)`), `LsCommandTest` (1×).
- **Fix:** convert the remaining sites to the helper (promote it to a shared test util in
  `org.krost.unidrive.cli`), or raise the budgets the same way. Assert no test sleeps the full budget on
  the happy path (the poll exits as soon as the socket exists).
- **Tests:** the suite itself; the second live occurrence is on record (check-windows run for #643).
- **Size:** S. **Label:** quick-win.

### Q2 — #623 Windows ACL assertions must accept equivalent SDDL principal aliases (test-only)
- **Evidence:** the issue's probe: the same owner-only DACL renders as the raw SID for a normal account
  but as the `LA` alias for the built-in Administrator. `OwnerOnlyTest` builds expected ACEs from
  `WindowsAclProbe.userSid`; `CredentialStoreTest`, `StoragePermissionsTest`, `IpcSocketDirTest` and
  `IpcServerPermissionsTest` compare spellings.
- **Fix:** normalize both sides before asserting — parse the SDDL with `RawSecurityDescriptor` and
  compare SIDs (or translate aliases via `GetSddlForm`), never raw strings. Test-only; run on this box.
- **Tests:** the five test classes above; add a case with an alias-principal DACL.
- **Size:** S. **Label:** quick-win. Run before B5 (same surface).

### Q3 — #314 Wire `decryptName` for plainName-null cloud items
- **Evidence:** `InternxtCrypto.decryptName` (`InternxtCrypto.kt:87`) has zero production callers; where a
  listing row's `plainName` is null the naming fallback surfaces the raw ciphertext name to the user.
- **Fix:** at the naming fallback (where `plainName ?: name` is chosen for `CloudItem`/rows), attempt
  `decryptName(name)` with the profile key first; on failure keep today's fallback. Check both the file
  and the folder naming sites (`InternxtProvider.kt` ~:2754/:2778/:2819 fill `parentId` right next to the
  name fields).
- **Tests:** round-trip fixture (encrypt a known name, null the plainName, assert the display name is the
  plaintext; a deliberately wrong key keeps the fallback, no throw).
- **Size:** S.

### Q4 — #528 Non-ANSI CLI arguments arrive as `?` (Windows)
- **Evidence:** `WindowsArgv.recover` (`WindowsArgv.kt:24-30`) papers over lossy args; the loss happens
  before the JVM sees them (the launcher's `cmd`/shell decoding).
- **Fix:** read the true wide argv via `GetCommandLineW` + `CommandLineToArgvW` (JNA is already a test
  dep of the shadow jar's packaging path — confirm what the runtime image pins before adding anything;
  if a native dep is unacceptable, a `jlink`-friendly `ProcessHandle.current().information()` route does
  not exist — document the chosen mechanism in the PR). Keep `recover` as the fallback.
- **Tests:** on this box: an argument of characters outside the ANSI code page survives to `Main` intact
  (fixture command echoes it back). Add the case to the existing `WindowsArgv` test.
- **Size:** M (mechanism decision). Windows-local verification required.

### Q5 — #135 One canonical profile disk name for socket path and cache root
- **Evidence:** `IpcServer.socketBaseName` (`IpcServer.kt:614-620`, SHA-1-truncated when the path would
  exceed `MAX_SOCKET_PATH_LENGTH`) vs `SyncEngine.hydrationCacheRoot` (verbatim provider-id directory, no
  truncation). Long profile names make the two disagree about what identifies the profile on disk; the
  `.meta` sidecar keeps IPC working, so nothing breaks today.
- **Fix:** one canonical function (e.g. in `:app:sync`, next to the socket helpers) used by both; the
  cache root adopts the hashed form only when the verbatim name would break a path consumer (mirror the
  socket rule). Keep the `.meta` sidecar for already-deployed hashed sockets.
- **Tests:** a profile name long enough to trigger socket hashing: cache root and socket name derive from
  the same canonical; a short name is unchanged (byte-identical behavior).
- **Size:** S. Low priority by design — defensive hardening.

## Tier 2 — after #663/#665/#669 merge (collision-ordered)

### B1 — #620 (sev:high) Logical hydration cache paths must resolve through links
- **Evidence:** `CachePaths.resolveInside` compares normalized paths lexically while `isInside` resolves
  through `toRealPath`; the hydration logical-path verbs use the former, so a symlink inside the cache
  leads `open_write_begin` outside and `prepareEmptyCache` opens the target with `TRUNCATE_EXISTING`
  (data loss outside the cache). Found reviewing #617.
- **Fix:** the logical-path check resolves the EXISTING prefix through `toRealPath` before the lexical
  containment test (the PR head's failing case is the regression test).
- **Tests:** the issue's exact scenario: tracked `/doc.txt`, outside file with bytes, cache symlink
  `doc.txt` → outside file; `openWriteBegin` must refuse.
- **Size:** S/M. **Note:** security-adjacent (path containment) — expect a closer owner review; do not
  broaden the change beyond the verbs the issue names.

### B2 — #448 Excluded items must not be served by hydration.list/ls/get
- **Evidence:** `hydration.list` already carries an `excluded` flag per entry (`HydrationImpl` list, from
  `mount.isExcludedPath`); `ls --live` and `get` do not consider exclusions at all.
- **Fix:** align the surfaces — `ls --live` filters non-EXISTS/excluded children the way the daemon view
  does; `get`/`hydrate` refuse excluded paths with the same error token the view flags. Keep the wire
  `excluded` flag additive (clients depend on it).
- **Tests:** a profile with an exclude pattern: the excluded item is absent from `ls` (live and daemon
  view agree), `get` refuses, `hydration.list` still carries the flag for the client.
- **Size:** M.

### B3 — #424 Remote folder rename plans descendant `MoveLocal`s that recreate empty placeholders
- **Evidence + fix:** the prior lane brief's B1 anchors still hold (`Reconciler.kt` planner filters only
  the old prefix; `applyMoveLocal` falls back to `createPlaceholder` when the source is gone). Drop
  descendant `MoveLocal`s under a moved folder; defence in depth: source-missing + destination-present
  means update the row only, never re-create.
- **Tests:** the issue's probe as a `SyncEngineTest`: rename `/d`→`/e` with hydrated descendants — bytes
  and hydration survive; a second sync plans nothing.
- **Size:** M.

### B4 — #610 + #609 The hydration lifecycle pair (same file, one PR each)
- **#610:** a rename landing while the path's upload is mid-retry leaves a conflict copy — the retry's
  path must be re-resolved against the row at attempt time (or the in-flight upload must own a path
  token that a rename invalidates, failing the attempt cleanly).
- **#609:** daemon shutdown must cancel/await the hydration upload scope BEFORE `StateDatabase.close()`
  — a defined order: cancel scope → await bounded (the #662 stall watchdog bounds attempts already) →
  close the DB. Tests with a slow fake upload closed mid-flight: no post-close write, the row consistent.
- **Size:** M each. Both touch `HydrationImpl` near the merged #662 watchdog — after Tier 1, not parallel
  to each other.

### B5 — #626 cluster (#618 + #637) Windows ACL/storage-restriction hardening
- One PR: child ACL grants (`OwnerOnly` must recurse or explicitly clear explicit grants), the >259-char
  path error-123 skip, and the test-side alias work rides on Q2. All Windows-local verifiable.
- **Size:** M. **Order:** after Q2.

## Not in this lane (with reasons)
- **#561 / #556** — cloud-data census (upstream damaged objects; detection/repair is its own design).
- **#303 / #322** — credential storage/keyring: the design constraint is drafted in `BACKLOG.md`; needs
  the owner's keyring decision, not an agent patch.
- **#517 / #521** — the watchdog rollout's remaining R-items (large, multi-surface, partially shipped).
- **#288 / #299 / #268 / #315 / #338** — tracking-set epic or legacy-engine items gated on the epic lane.
- **#240** — a deliberate decision owed by the owner (the autofix PRs are unsafe).
- **#325 / #305 / #326 / #506** — dist/release-gate/infra lane (owner's release tooling).
- **#491 / #309 / #310** — NFC/case-fold/Graph-name specialized lanes; each needs its own short brief.
- **#620 is IN (B1) but #618 rides B5** — both security-flavored: extra review, no drive-by refactors.
- **#443** — the meta queue this lane supersedes for the bug tier; keep for bookkeeping.
- **#132 / #580's remainder** — upstream trash lag; client-side mitigation is #663's guard (in review).
