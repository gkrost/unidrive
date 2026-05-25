# `unidrive daemon` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement per-profile, long-lived `unidrive daemon` JVM that owns the IPC server, auth/provider session, and `state.db` handle — enabling `unidrive mount` to work standalone after the mode-mutex closed the previous concurrent-sync workflow.

**Architecture:** Five sequential phases. Phase 1 mutates `ProcessLock.Mode` (SYNC + DAEMON, MOUNT removed) and migrates the two Main.kt factories. Phase 2 introduces `DaemonRuntime` (testable lifecycle) + `DaemonCommand` (picocli shell). Phase 3 wires the two new verbs (`refresh.run`, `daemon.status`). Phase 4 refactors `RefreshCommand` to a thin client and strips lock acquisition from `MountCommand`. Phase 5 closes out — BACKLOG entries, deploy, live smoke per spec §7. Each phase compiles and tests green before the next starts.

**Tech Stack:** Kotlin (JVM 21), kotlinx.coroutines 1.8.x, `java.nio.channels.UnixDomainSocketAddress`, picocli 4.x, JUnit 5 via `kotlin.test`, MockK. Build from `core/`: `./gradlew :app:sync:test :app:cli:test :app:hydration:test --console=plain`. Deploy: `./gradlew :app:cli:deploy --console=plain`.

**Specs:**
- `/home/gernot/dev/git/unidrive/docs/dev/specs/unidrive-daemon-design.md` (this spec)
- `/home/gernot/dev/git/unidrive/docs/dev/specs/mount-sync-mode-mutex-design.md` (Phase 1 mode-mutex; amended by §4.5 of this spec)

**BACKLOG entries this plan closes:**
- High tier: "`unidrive mount` has no JVM-side IPC server lifecycle (mount-only mode is dead-on-arrival)"

---

## Phase 0 — Pre-flight

- [ ] **Step 0.1: Verify clean state + correct branch**

```bash
cd /home/gernot/dev/git/unidrive
pwd
git branch --show-current
git status --short
```

Expected: branch is `opencode_dr` (or a fresh branch you create from `opencode_dr` for this plan), `git status` clean. If branch differs, surface to the operator before continuing — the daemon spec sits on top of opencode_dr's polish commits (B1, B2, P1) and the daemon spec commits (629b171, cc82299).

- [ ] **Step 0.2: Baseline test suite green**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test :app:cli:test :app:hydration:test --console=plain > /tmp/baseline.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 2 /tmp/baseline.log | head -30
```

Expected: exit 0, no failures. If anything is red, STOP and surface — the daemon plan assumes a green baseline.

- [ ] **Step 0.3: Sibling-repo branch check (informational only)**

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
git branch --show-current
git log --oneline -3
```

Note the head SHA. This plan does NOT modify the Rust co-daemon; the spec §3.3 explicitly says MountCommand stays as the supervisor for `unidrive-mount` (the sibling binary), only the JVM-side lock acquisition is removed. The Rust side's `ReconnectingIpcClient` already handles socket-restart correctly per spec §5.3.

---

## Phase 1 — `ProcessLock.Mode` migration (SYNC + DAEMON; MOUNT removed)

Mutates the per-profile lock-file wire format from "sync or mount" to "sync or daemon," replaces both Main.kt factory error messages, and updates the two existing tests that pin the mode-mutex contract. After this phase: lock contention semantics are correct end-to-end; no functional daemon yet, but the foundation is in place.

### Task 1: Replace `Mode.MOUNT` with `Mode.DAEMON` in ProcessLock

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt`
- Modify: `/home/gernot/dev/git/unidrive/core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt`

- [ ] **Step 1.1: Write the failing test for `Mode.DAEMON` round-trip**

Open `/home/gernot/dev/git/unidrive/core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt`. Locate the existing `lock_pid_file_carries_mode_after_tryLock` test (it pins T1 from the mode-mutex spec). Add a new test immediately after it:

```kotlin
    @Test
    fun lock_pid_file_carries_daemon_mode_after_tryLock_daemon() {
        val daemonLockFile = Files.createTempFile("mode-mutex-daemon", ".lock")
        val daemonLock = ProcessLock(daemonLockFile)
        try {
            assertTrue(daemonLock.tryLock(ProcessLock.Mode.DAEMON))
            val pidFile = daemonLockFile.resolveSibling("${daemonLockFile.fileName}.pid")
            val body = Files.readString(pidFile).trim()
            assertEquals(
                "${ProcessHandle.current().pid()} daemon",
                body,
                "lock-pid sidecar must carry '<pid> daemon' on DAEMON acquisition",
            )
        } finally {
            daemonLock.unlock()
            Files.deleteIfExists(daemonLockFile)
        }
    }
```

- [ ] **Step 1.2: Run the new test to verify it fails**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.ProcessLockTest.lock_pid_file_carries_daemon_mode_after_tryLock_daemon" --console=plain 2>&1 | tail -20
```

Expected: compile error referencing `ProcessLock.Mode.DAEMON` — the enum value doesn't exist yet.

- [ ] **Step 1.3: Replace `MOUNT` with `DAEMON` in ProcessLock.Mode**

Open `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt:37-46`. Replace the enum body:

```kotlin
    enum class Mode(internal val wireToken: String) {
        SYNC("sync"),
        DAEMON("daemon"),
        ;

        companion object {
            internal fun fromWireToken(token: String): Mode? =
                values().firstOrNull { it.wireToken == token }
        }
    }
```

(Same shape, just `MOUNT("mount")` becomes `DAEMON("daemon")`.) Also update the kdoc comment immediately above (line 33-36) to read:

```kotlin
    /**
     * Mode of the process holding this lock. SYNC for SyncCommand,
     * DAEMON for DaemonCommand. Read alongside the PID from .lock.pid
     * so a contender can name the holder accurately.
     */
```

- [ ] **Step 1.4: Run the new daemon-mode test — must now pass**

```bash
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.ProcessLockTest.lock_pid_file_carries_daemon_mode_after_tryLock_daemon" --console=plain 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 1.5: Update the existing T1 test that referenced `Mode.MOUNT`**

In `ProcessLockTest.kt`, find the existing test `lock_pid_file_carries_mode_after_tryLock` (it asserts `${pid} mount`). Change the assertion's mode token from `mount` to `daemon`, and update the variable names from `mountLockFile`/`mountLock` to `daemonLockFile`/`daemonLock`. Keep the symmetric SYNC test (the lower half of the same test method) unchanged.

Concretely: change every `ProcessLock.Mode.MOUNT` to `ProcessLock.Mode.DAEMON` and every "mount" string literal that was tied to the mode token (NOT comments or file-prefix names like `"mode-mutex-mount"` — those can stay or change for clarity, but they don't affect the assertion).

- [ ] **Step 1.6: Update T2 test `read_holder_info_returns_pid_and_mode_for_locked_file` to use Mode.DAEMON**

Same file. Find the `read_holder_info_returns_pid_and_mode_for_locked_file` test. Change `ProcessLock.Mode.MOUNT` references to `ProcessLock.Mode.DAEMON`. The structural assertions (pid matches, mode matches) stay identical.

- [ ] **Step 1.7: Check for any other test references to `Mode.MOUNT`**

```bash
grep -rn "Mode\.MOUNT\|MOUNT(" core/app/sync/src/test/ core/app/cli/src/test/ 2>&1 | head -10
```

If hits remain, change each to `Mode.DAEMON` and verify the assertion intent still matches. (After step 1.5 + 1.6, hits should be zero.)

- [ ] **Step 1.8: Run the full ProcessLockTest — must be green**

```bash
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.ProcessLockTest" --console=plain 2>&1 | tail -15
```

Expected: PASS for all `ProcessLockTest` tests (T1, T2, T3 legacy-pid-only, T3a unknown-mode, and the new T1-daemon-variant).

- [ ] **Step 1.9: Commit Task 1**

```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt \
        core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt
git commit -m "feat(sync): replace ProcessLock.Mode.MOUNT with Mode.DAEMON

Per spec unidrive-daemon-design.md §4.5: the legal mode tokens in
the .lock.pid sidecar become 'sync' and 'daemon'. Mount becomes a
client of the daemon and never acquires the profile lock itself.

Wire format unchanged: '<pid> <mode>\\n'. Any pre-existing .lock.pid
containing 'mount' is read by readHolderInfo() as an unknown-mode
token (HolderInfo(mode=null, rawMode=\"mount\")) via the existing
forward-compat path — no special-case handling needed.

ProcessLockTest updated: T1 + T2 now exercise Mode.DAEMON instead
of Mode.MOUNT; T3 (legacy pid-only) and T3a (unknown-mode) are
unchanged (and now also cover the 'mount' token via the rawMode
forward-compat path)."
```

### Task 2: Rewrite `Main.acquireProfileLock` error message + add `acquireProfileLockForDaemon`

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` (lines 556-637 — both existing factories)
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ProfileLockFactoryTest.kt`

- [ ] **Step 2.1: Write the failing T2 test `daemon_refuses_to_start_when_sync_holds_lock`**

Open `/home/gernot/dev/git/unidrive/core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ProfileLockFactoryTest.kt`. Locate the existing `mount_command_refuses_when_sync_holds_lock` test method. Replace its body so the test name becomes `daemon_refuses_to_start_when_sync_holds_lock` and the assertion exercises the DAEMON factory's contention error wording instead of the MOUNT one:

```kotlin
    @Test
    fun daemon_refuses_to_start_when_sync_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.SYNC), "precondition: SYNC lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.DAEMON)
            assertEquals(false, acquired, "DAEMON must not acquire while SYNC holds")
            val holder = contender.readHolderInfo()
            renderDaemonFactoryContention(holder, profileName = "test_profile")

            val out = capturedErr.toString()
            assertTrue(
                out.contains("Another `unidrive sync` is running for profile 'test_profile'"),
                "expected sync-holder phrasing; got: $out",
            )
            assertTrue(
                out.contains("PID ${ProcessHandle.current().pid()}"),
                "expected holder PID in stderr; got: $out",
            )
        } finally {
            held.unlock()
        }
    }
```

You will need a corresponding `renderDaemonFactoryContention` helper — replace the existing `renderMountFactoryContention` private function with:

```kotlin
    // MUST mirror Main.acquireProfileLockForDaemon byte-for-byte. If the factory
    // wording drifts, update both sides together.
    private fun renderDaemonFactoryContention(holder: ProcessLock.HolderInfo?, profileName: String) {
        val holderDesc = when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is running for profile '$profileName'"
            holder?.mode == ProcessLock.Mode.DAEMON ->
                "Another `unidrive daemon` already serves profile '$profileName'"
            holder != null && holder.mode == null && holder.rawMode != null ->
                "Profile '$profileName' is held by an unidrive process running in " +
                    "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
            else ->
                "Another unidrive process is using profile '$profileName'"
        }
        val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
        System.err.println("$holderDesc$pidPart.")
    }
```

- [ ] **Step 2.2: Write the failing T3 test `sync_refuses_to_start_when_daemon_holds_lock`**

In the same file, replace `sync_command_refuses_when_mount_holds_lock` (which referenced `Mode.MOUNT`):

```kotlin
    @Test
    fun sync_refuses_to_start_when_daemon_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.DAEMON), "precondition: DAEMON lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.SYNC)
            assertEquals(false, acquired, "SYNC must not acquire while DAEMON holds")
            val holder = contender.readHolderInfo()
            renderSyncFactoryContention(holder, profileName = "test_profile")

            val out = capturedErr.toString()
            assertTrue(
                out.contains("is currently in use by `unidrive daemon`"),
                "expected daemon-holder phrasing; got: $out",
            )
            assertTrue(
                out.contains("PID ${ProcessHandle.current().pid()}"),
                "expected holder PID in stderr; got: $out",
            )
        } finally {
            held.unlock()
        }
    }
```

And update the existing `renderSyncFactoryContention` helper in the same file. The mount-holder branch becomes a daemon-holder branch (matches `Main.acquireProfileLock` rewrite in Step 2.4):

```kotlin
    // MUST mirror Main.acquireProfileLock byte-for-byte. If the factory
    // wording drifts, update both sides together.
    private fun renderSyncFactoryContention(holder: ProcessLock.HolderInfo?, profileName: String) {
        val holderDesc = when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is running for profile '$profileName'"
            holder?.mode == ProcessLock.Mode.DAEMON ->
                "Profile '$profileName' is currently in use by `unidrive daemon`"
            holder != null && holder.mode == null && holder.rawMode != null ->
                "Profile '$profileName' is held by an unidrive process running in " +
                    "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
            else ->
                "Another unidrive process is using profile '$profileName'"
        }
        val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
        System.err.println("$holderDesc$pidPart.")
    }
```

- [ ] **Step 2.3: Run T2 + T3 — must fail**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.ProfileLockFactoryTest" --console=plain 2>&1 | tail -20
```

Expected: compile errors referencing `Mode.MOUNT` from the production code (Main.kt:571-572, 581, 607, 611, 613-614, 623) — the tests are referencing the new `Mode.DAEMON` shape while production still has `Mode.MOUNT`.

- [ ] **Step 2.4: Rewrite `Main.acquireProfileLock` to render daemon-holder branch**

Open `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt`. Replace lines 556-596 (the existing `acquireProfileLock` factory) with:

```kotlin
    /**
     * Acquire the per-profile process lock in SYNC mode. Returns the lock on
     * success. If another unidrive process holds the lock, prints a mode-
     * specific message and exits with code 1.
     *
     * NOTE: error wording is duplicated in ProfileLockFactoryTest.renderSyncFactoryContention.
     * Keep both in sync when editing — drift here means stale test assertions.
     */
    fun acquireProfileLock(): org.krost.unidrive.sync.ProcessLock {
        val lockFile = providerConfigDir().resolve(".lock")
        java.nio.file.Files.createDirectories(lockFile.parent)
        val lock = org.krost.unidrive.sync.ProcessLock(lockFile)
        if (!lock.tryLock(org.krost.unidrive.sync.ProcessLock.Mode.SYNC)) {
            val profile = resolveCurrentProfile()
            val holder = lock.readHolderInfo()
            val holderDesc = when {
                holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.SYNC ->
                    "Another `unidrive sync` is running for profile '${profile.name}'"
                holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.DAEMON ->
                    "Profile '${profile.name}' is currently in use by `unidrive daemon`"
                holder != null && holder.mode == null && holder.rawMode != null ->
                    "Profile '${profile.name}' is held by an unidrive process running in " +
                        "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
                else ->
                    "Another unidrive process is using profile '${profile.name}'"
            }
            val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
            System.err.println("$holderDesc$pidPart.")
            if (holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.DAEMON) {
                System.err.println(
                    "Stop the daemon first: `unidrive daemon stop ${profile.name}`.",
                )
            } else if (holder != null) {
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                val killCmd = if (isWindows) "taskkill /PID ${holder.pid} /F" else "kill ${holder.pid}"
                System.err.println("Stop it with `$killCmd`, or wait for it to finish.")
            } else {
                System.err.println("Stop it first, or wait for it to finish.")
            }
            System.exit(1)
        }
        return lock
    }
```

- [ ] **Step 2.5: Replace `acquireProfileLockForMount` with `acquireProfileLockForDaemon`**

In the same file, replace lines 598-637 (the existing `acquireProfileLockForMount` factory) with the new daemon-side factory:

```kotlin
    /**
     * Acquire the per-profile process lock in DAEMON mode. Refuses with code 1
     * if another process holds the lock (typically `unidrive sync` for the same
     * profile, or another `unidrive daemon` instance).
     * See docs/dev/specs/unidrive-daemon-design.md.
     *
     * NOTE: error wording is duplicated in ProfileLockFactoryTest.renderDaemonFactoryContention.
     * Keep both in sync when editing — drift here means stale test assertions.
     */
    fun acquireProfileLockForDaemon(): org.krost.unidrive.sync.ProcessLock {
        val lockFile = providerConfigDir().resolve(".lock")
        java.nio.file.Files.createDirectories(lockFile.parent)
        val lock = org.krost.unidrive.sync.ProcessLock(lockFile)
        if (!lock.tryLock(org.krost.unidrive.sync.ProcessLock.Mode.DAEMON)) {
            val profile = resolveCurrentProfile()
            val holder = lock.readHolderInfo()
            val holderDesc = when {
                holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.SYNC ->
                    "Another `unidrive sync` is running for profile '${profile.name}'"
                holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.DAEMON ->
                    "Another `unidrive daemon` already serves profile '${profile.name}'"
                holder != null && holder.mode == null && holder.rawMode != null ->
                    "Profile '${profile.name}' is held by an unidrive process running in " +
                        "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
                else ->
                    "Another unidrive process is using profile '${profile.name}'"
            }
            val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
            System.err.println("$holderDesc$pidPart.")
            if (holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.SYNC) {
                System.err.println(
                    "Stop the sync watcher first: `kill ${holder.pid}` (or Ctrl-C its terminal).",
                )
                System.err.println(
                    "Sync and daemon are mutually exclusive per profile " +
                        "(see docs/dev/specs/unidrive-daemon-design.md).",
                )
            } else if (holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.DAEMON) {
                System.err.println(
                    "Stop the running daemon first: `unidrive daemon stop ${profile.name}`.",
                )
            } else if (holder != null) {
                System.err.println("Stop it with `kill ${holder.pid}`, or wait for it to exit.")
            }
            System.exit(1)
        }
        return lock
    }
```

- [ ] **Step 2.6: Run T2 + T3 — must now pass**

```bash
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.ProfileLockFactoryTest" --console=plain 2>&1 | tail -15
```

Expected: PASS for `daemon_refuses_to_start_when_sync_holds_lock` and `sync_refuses_to_start_when_daemon_holds_lock`.

- [ ] **Step 2.7: Commit Task 2**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt \
        core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ProfileLockFactoryTest.kt
git commit -m "feat(cli): replace acquireProfileLockForMount with acquireProfileLockForDaemon

Per spec unidrive-daemon-design.md §3.3:
- Main.acquireProfileLock (SYNC mode) now renders a daemon-holder
  branch instead of the prior mount-holder branch.
- Main.acquireProfileLockForMount is replaced with
  acquireProfileLockForDaemon (DAEMON mode); same factory shape,
  symmetric error wording.
- ProfileLockFactoryTest tests T2/T3 renamed to
  daemon_refuses_to_start_when_sync_holds_lock and
  sync_refuses_to_start_when_daemon_holds_lock per spec §6.

acquireProfileLockForMount has NO call site in this commit — its
removal is interim until Task 3 strips MountCommand's lock acquisition.
Compile of MountCommand still passes because the function exists
under the new daemon name and the mount-only caller will be removed
in Task 3."
```

(NOTE: the commit message says "still passes" — verify before committing with `./gradlew :app:cli:compileKotlin --console=plain 2>&1 | tail -5`. If MountCommand still references `acquireProfileLockForMount` and won't compile, swap Task 2 ↔ Task 3 ordering: do MountCommand strip first, then factory rename. The cleaner order is what's written here IF the rename `acquireProfileLockForMount → acquireProfileLockForDaemon` happens in one commit, which means MountCommand.kt compiles against a function that no longer exists. **Take the safer path: leave a one-line shim `fun acquireProfileLockForMount() = acquireProfileLockForDaemon()` in Main.kt at the end of Step 2.5; the shim gets removed in Task 3 Step 3.4.** Add this shim now:)

Append to Main.kt immediately after the new `acquireProfileLockForDaemon` body:

```kotlin
    /**
     * Transitional alias. Removed in the same commit that strips
     * MountCommand's lock acquisition. Do not introduce new callers.
     */
    @Deprecated("Removed in Task 3 (MountCommand strip); use acquireProfileLockForDaemon instead",
                ReplaceWith("acquireProfileLockForDaemon()"))
    fun acquireProfileLockForMount(): org.krost.unidrive.sync.ProcessLock =
        acquireProfileLockForDaemon()
```

Now the Step 2.7 commit is valid. Verify with `./gradlew :app:cli:compileKotlin --console=plain 2>&1 | tail -5` — expected: BUILD SUCCESSFUL.

### Task 3: Strip lock acquisition from MountCommand + remove the transitional shim

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt` (lines 42-44, 71-73)
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` (remove the transitional shim from Step 2.5)

- [ ] **Step 3.1: Strip the lock acquisition from MountCommand.run()**

Open `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt:35-74`. Replace the existing body:

```kotlin
    override fun run() {
        if (profileNameOverride != null) {
            parent.provider = profileNameOverride
            parent.invalidateProfileCaches()
        }
        val profile = parent.resolveCurrentProfile()

        // Per spec unidrive-daemon-design.md §3.3: mount no longer acquires
        // the profile lock. The daemon (which holds Mode.DAEMON) is the
        // authoritative IPC server for the profile. If the daemon is not
        // running, the co-daemon connection refusal below produces a clear
        // operator-facing error pointing at `unidrive daemon run`.
        val socketPath = IpcServer.defaultSocketPath(profile.name)
        val cacheRoot = SyncEngine.hydrationCacheRoot(
            SyncEngine.defaultHydrationCacheRoot(),
            profile.type,
        )
        val binary = defaultBinaryPath()

        val existsExit = checkBinaryExists(binary)
        if (existsExit != 0) {
            System.err.println(
                "unidrive mount: co-daemon binary not found at $binary",
            )
            System.err.println(
                "Build manually: cd ../unidrive-mount-linux && cargo build --release " +
                    "&& cp target/release/unidrive-mount ~/.local/lib/unidrive/",
            )
            System.exit(existsExit)
            return
        }

        Files.createDirectories(cacheRoot)
        val argv = buildArgv(binary, mountPath, socketPath, cacheRoot)
        val exit = superviseProcess(argv)
        if (exit != 0) {
            // Per spec §3.4 "Daemon-not-running error path": the co-daemon's
            // inherited stderr already prints "failed to connect IPC at ...:
            // Connection refused". Augment with the operator hint.
            System.err.println(
                "unidrive mount: co-daemon exited with code $exit. If the cause was " +
                    "Connection refused, the daemon for profile '${profile.name}' is " +
                    "not running. Start it with: `unidrive daemon run ${profile.name}`.",
            )
        }
        System.exit(exit)
    }
```

(Note: the `try { ... } finally { lock.unlock() }` wrapper from the Phase-1 mode-mutex work is removed entirely. `MountCommand` no longer owns a lock to release.)

- [ ] **Step 3.2: Remove unused import**

If `MountCommand.kt:3-12` imports any package solely for the lock acquisition, remove it. Concretely: `ProcessLock` was likely not imported directly (the lock came from `parent.acquireProfileLockForMount()`); confirm with:

```bash
grep -n "import org.krost.unidrive.sync.ProcessLock" core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt
```

If hit, delete the import line.

- [ ] **Step 3.3: Confirm compile**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:compileKotlin --console=plain 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL. If a stale reference remains, the compile error names the line — fix and retry.

- [ ] **Step 3.4: Remove the transitional `acquireProfileLockForMount` shim from Main.kt**

Remove the `@Deprecated` shim added in Step 2.5. Search for the only remaining caller:

```bash
grep -rn "acquireProfileLockForMount" core/app/cli/src/ 2>&1 | head -10
```

Expected: only the shim definition itself in Main.kt. Delete that block (the kdoc + the `@Deprecated` annotation + the one-line body — about 8 lines).

- [ ] **Step 3.5: Verify the shim is gone**

```bash
grep -rn "acquireProfileLockForMount" core/app/cli/src/ 2>&1 | head -5
```

Expected: zero hits.

- [ ] **Step 3.6: Run cli + sync tests — must still be green**

```bash
./gradlew :app:cli:test :app:sync:test --console=plain 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 3.7: Commit Task 3**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt \
        core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
git commit -m "feat(cli): strip MountCommand lock acquisition; mount is now a daemon client

Per spec unidrive-daemon-design.md §3.3: mount no longer acquires
ProcessLock(Mode.DAEMON). The daemon (\`unidrive daemon run <profile>\`)
holds the lock; mount just connects to the daemon's IPC socket via the
existing Rust co-daemon (\`unidrive-mount\` from
../unidrive-mount-linux).

MountCommand.run() now:
- Reads only the profile + socket path + cache root + binary path.
- Calls superviseProcess(argv) for the co-daemon as before.
- On non-zero exit, prints an additional hint pointing at
  \`unidrive daemon run <profile>\` — the most common cause of failure
  in the new model.
- No try/finally for lock release (the finally was unreachable past
  System.exit anyway; the lock object is gone entirely now).

Main.acquireProfileLockForMount transitional shim removed; the only
caller in MountCommand is gone."
```

### Task 4: Phase 1 close-out — full test suite green

- [ ] **Step 4.1: Run the targeted suites**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test :app:cli:test --console=plain 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL across both modules.

- [ ] **Step 4.2: Run the broader check (no integration tests)**

```bash
./gradlew :app:sync:check :app:cli:check --console=plain 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4.3: Mark Phase 1 complete — write the phase boundary commit**

If the working tree is clean (it should be after Task 3), no additional commit is needed. The plan moves to Phase 2 cleanly. If there are stray edits (e.g. an extra docstring you tweaked), commit them with `wip(cleanup): phase 1 boundary tidy`.

---

## Phase 2 — `DaemonRuntime` + `DaemonCommand` (the picocli shell)

The spec §3.3 mandates a split between `DaemonRuntime` (testable lifecycle, plain constructor) and `DaemonCommand` (picocli shell reading from `parent: Main`). This phase wires both, registers `DaemonCommand` under the picocli `subcommands = [...]` list in Main.kt, and lands T1 (the named test). At the end of Phase 2: `unidrive daemon run <profile>` is invokable from the CLI but only serves hydration verbs (no `refresh.run` / `daemon.status` yet — those come in Phase 3).

### Task 5: `DaemonRuntime` skeleton (constructor + lifecycle)

**Files:**
- Create: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt`
- Create: `/home/gernot/dev/git/unidrive/core/app/cli/src/test/kotlin/org/krost/unidrive/cli/DaemonRuntimeTest.kt`

- [ ] **Step 5.1: Write the failing test for `DaemonRuntime.run` happy path**

This test exercises T1 — `daemon_binds_socket_and_serves_hydration_verbs` per spec §6 T1. Create the test file:

```kotlin
package org.krost.unidrive.cli

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class DaemonRuntimeTest {
    private lateinit var tempDir: java.nio.file.Path
    private lateinit var lockFile: java.nio.file.Path
    private lateinit var dbPath: java.nio.file.Path
    private lateinit var socketPath: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-runtime-test")
        lockFile = tempDir.resolve(".lock")
        dbPath = tempDir.resolve("state.db")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        // Best-effort cleanup
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(dbPath) }
        runCatching { Files.deleteIfExists(lockFile) }
        runCatching { Files.deleteIfExists(lockFile.resolveSibling(".lock.pid")) }
        runCatching { Files.deleteIfExists(tempDir) }
    }

    @Test
    fun daemon_binds_socket_and_serves_hydration_verbs() = runBlocking {
        // Spec T1: start the daemon → connect a fake IPC client → issue
        // hydration.list → receive {"ok":true, ...}. Tear down via close().
        val provider = mockk<CloudProvider>(relaxed = true)
        coEvery { provider.authenticateAndLog() } returns Unit
        // hydration.list returns an empty list against a fake provider —
        // sufficient to prove the verb is wired.

        val runtime = DaemonRuntime(
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        // Launch the daemon in a child coroutine; the daemon's start() suspends
        // until close() is called.
        val daemonJob = kotlinx.coroutines.launch { runtime.start() }

        // Wait for the socket to bind.
        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            kotlinx.coroutines.delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

        // Connect a raw client and issue hydration.list.
        val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
        try {
            val req = """{"verb":"hydration.list","path":"/"}""" + "\n"
            channel.write(ByteBuffer.wrap(req.toByteArray()))
            val buf = ByteBuffer.allocate(4096)
            channel.read(buf)
            buf.flip()
            val reply = String(buf.array(), 0, buf.limit())
            assertTrue(reply.contains("\"ok\":true"), "expected ok:true reply; got: $reply")
        } finally {
            channel.close()
        }

        // Graceful shutdown.
        runtime.close()
        daemonJob.join()
    }
}
```

- [ ] **Step 5.2: Run the test to verify it fails**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.DaemonRuntimeTest" --console=plain 2>&1 | tail -15
```

Expected: compile error referencing `DaemonRuntime` — the class doesn't exist yet.

- [ ] **Step 5.3: Implement `DaemonRuntime` minimal class**

Create `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt`:

```kotlin
package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.StaleMountDetector
import org.krost.unidrive.sync.SyncEngine
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Per-profile daemon lifecycle, separated from picocli wiring for testability.
 *
 * Per spec docs/dev/specs/unidrive-daemon-design.md §3.3: the picocli class
 * [DaemonRunCommand] is a thin shell that reads its dependencies off `parent: Main`
 * and constructs this runtime. Tests construct DaemonRuntime directly with their
 * own paths + provider factory — no JVM spawn required.
 *
 * Lifecycle (spec §3.2):
 * 1. Acquire ProcessLock(DAEMON). On failure: System.exit(1).
 * 2. Warn on stale mounts (best-effort). Never abort.
 * 3. Open StateDatabase. On failure: release lock, System.exit(1).
 * 4. Call provider.authenticateAndLog(). On failure: release lock, System.exit(1).
 *    NO socket is bound until this succeeds.
 * 5. Bind IpcServer + register handlers. On bind failure: release lock,
 *    System.exit(1).
 * 6. SERVE until close() is called.
 * 7. On close: graceful shutdown bounded by SHUTDOWN_DEADLINE_MS (spec I7).
 */
class DaemonRuntime(
    private val profileName: String,
    private val lockFile: Path,
    private val dbPath: Path,
    private val socketPath: Path,
    private val providerFactory: () -> CloudProvider,
) {
    private val log = LoggerFactory.getLogger(DaemonRuntime::class.java)

    private var lock: ProcessLock? = null
    private var db: StateDatabase? = null
    private var ipcServer: IpcServer? = null
    private val closeSignal = CompletableDeferred<Unit>()

    /**
     * Start the daemon lifecycle. Returns when [close] has fired and shutdown
     * has completed (or the [SHUTDOWN_DEADLINE_MS] backstop fires).
     */
    suspend fun start() {
        // 1. Acquire lock
        Files.createDirectories(lockFile.parent)
        val acquiredLock = ProcessLock(lockFile)
        if (!acquiredLock.tryLock(ProcessLock.Mode.DAEMON)) {
            renderLockContentionAndExit(acquiredLock)
            return // unreachable; renderLockContentionAndExit calls System.exit
        }
        lock = acquiredLock

        try {
            // 2. Stale-mount warn (spec §3.3)
            val staleMounts = StaleMountDetector.detectStaleFuseUnidriveMounts()
            if (staleMounts.isNotEmpty()) {
                System.err.println(
                    "WARNING: detected ${staleMounts.size} stale unidrive FUSE mount(s) " +
                        "(likely from a kill -9'd \`unidrive mount\` parent or prior daemon): " +
                        staleMounts.joinToString(),
                )
                System.err.println(
                    "These mounts no longer serve data. Clean up with " +
                        "\`fusermount3 -u <path>\` for each.",
                )
            }

            // 3. Open DB
            db = StateDatabase(dbPath).also { it.initialize() }

            // 4. Authenticate
            val provider = providerFactory()
            try {
                provider.authenticateAndLog()
            } catch (e: Exception) {
                System.err.println("unidrive daemon: authentication failed for profile '$profileName': ${e.message}")
                throw e
            }

            // 5. Bind IPC + register handlers
            val server = IpcServer(socketPath)
            ipcServer = server

            coroutineScope {
                server.start(this)

                // Engine is needed by HydrationImpl; for read-only hydration verbs
                // it just wraps the existing provider + db.
                val engine = SyncEngine(provider, db!!, syncRoot = dbPath.parent)
                val hydration = HydrationImpl(engine, db!!)
                val hydrationIpc = HydrationIpcHandler(hydration)
                for (verb in HydrationIpcHandler.VERBS) {
                    server.registerHandler(verb) { connId, json ->
                        hydrationIpc.handle(connectionId = connId, jsonRequest = json)
                    }
                }
                server.registerConnectionCloseListener { connId ->
                    hydration.onConnectionClosed(connId)
                }
                hydrationIpc.start(this, server::writeToConnection)
                server.registerConnectionCloseListener { connId -> hydrationIpc.onSubscriberDisconnect(connId) }
                launch { hydration.events.collect { hydrationIpc.dispatchEvent(it) } }

                // sync.subscribe verb registration — symmetric to SyncCommand's
                // wiring at SyncCommand.kt:495-504. The daemon doesn't emit
                // sync events, but refresh.run (Task 8) will use the same
                // subscriber-set mechanism.
                server.registerHandler("sync.subscribe") { connId, _ ->
                    server.scheduleAfterReply(connId) {
                        server.flushStateDumpTo(connId)
                        server.registerSyncSubscriber(connId)
                    }
                    """{"ok":true}"""
                }
                server.registerConnectionCloseListener { connId ->
                    server.unregisterSyncSubscriber(connId)
                }

                System.err.println("daemon ready, pid ${ProcessHandle.current().pid()}, socket $socketPath")

                // 6. Serve until close()
                closeSignal.await()

                // 7. Graceful shutdown (spec I7)
                log.info("daemon: shutting down")
            }
        } catch (e: Exception) {
            log.error("daemon: lifecycle error", e)
            cleanup()
            throw e
        } finally {
            cleanup()
        }
    }

    /**
     * Signal the daemon to shut down. Returns immediately; [start] returns
     * once shutdown completes (or the SHUTDOWN_DEADLINE_MS backstop fires).
     */
    fun close() {
        closeSignal.complete(Unit)
    }

    private fun renderLockContentionAndExit(lock: ProcessLock) {
        val holder = lock.readHolderInfo()
        val holderDesc = when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is running for profile '$profileName'"
            holder?.mode == ProcessLock.Mode.DAEMON ->
                "Another `unidrive daemon` already serves profile '$profileName'"
            holder != null && holder.mode == null && holder.rawMode != null ->
                "Profile '$profileName' is held by an unidrive process running in " +
                    "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
            else ->
                "Another unidrive process is using profile '$profileName'"
        }
        val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
        System.err.println("$holderDesc$pidPart.")
        System.exit(1)
    }

    private fun cleanup() {
        runCatching { ipcServer?.close() }
        ipcServer = null
        runCatching { db?.close() }
        db = null
        runCatching { lock?.unlock() }
        lock = null
    }

    companion object {
        const val SHUTDOWN_DEADLINE_MS: Long = 10_000
    }
}
```

Notes for the implementer:
- The `providerFactory: () -> CloudProvider` injection point is what lets T1 use a `mockk<CloudProvider>` without going through the real `Main.createProvider()` path. Production wiring in Task 6 passes `parent::createProvider`.
- The `SyncEngine(provider, db, syncRoot=...)` constructor signature should match what exists in `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:25`. If the actual constructor takes more parameters (`ConflictPolicy`, override maps, etc.), use the defaults — for hydration-only mode, the reconcile-loop parameters aren't exercised.
- The `runtime.close()` path completes the `CompletableDeferred`; the `coroutineScope { ... }` body returns, the `finally { cleanup() }` runs. SHUTDOWN_DEADLINE_MS is the future backstop (Phase 3 wires it via `withTimeoutOrNull`); for T1 the close() path is fast enough not to need it yet.

- [ ] **Step 5.4: Run T1 — must now pass**

```bash
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.DaemonRuntimeTest.daemon_binds_socket_and_serves_hydration_verbs" --console=plain 2>&1 | tail -20
```

Expected: PASS.

If FAIL: read the failure carefully. Common causes: (a) the `SyncEngine` constructor signature differs (read `SyncEngine.kt:25-54` and adapt — but DO NOT change SyncEngine itself); (b) `HydrationImpl` constructor differs (read `HydrationImpl.kt` first lines); (c) the socket path's parent dir doesn't exist (add `Files.createDirectories(socketPath.parent)` before the IpcServer is constructed if needed).

- [ ] **Step 5.5: Commit Task 5**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt \
        core/app/cli/src/test/kotlin/org/krost/unidrive/cli/DaemonRuntimeTest.kt
git commit -m "feat(cli): introduce DaemonRuntime — testable daemon lifecycle

Per spec unidrive-daemon-design.md §3.3: separates the daemon's
lifecycle state machine from picocli wiring. DaemonRuntime takes
profile name + lock/db/socket paths + provider factory as constructor
arguments; tests construct it directly with a mockk<CloudProvider>.

Phase 2 scope: hydration.* verbs + sync.subscribe wired. refresh.run
and daemon.status come in Phase 3. Mode.DAEMON lock acquisition,
state.db open, provider auth, and IPC server bind all sequenced per
spec §3.2 lifecycle state machine. Stale-mount warn per spec §3.3.

T1 (\`daemon_binds_socket_and_serves_hydration_verbs\`) pins the
end-to-end happy path: lock acquire → auth → bind → serve hydration.list
→ graceful close."
```

### Task 6: `DaemonCommand` picocli shell + nested subcommands

**Files:**
- Create: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonCommand.kt`
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` (subcommands list)

- [ ] **Step 6.1: Create DaemonCommand.kt with the three nested subcommands**

Follow the `VaultCommand.kt` pattern (parent + child classes in one file):

```kotlin
package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

@Command(
    name = "daemon",
    description = ["Manage the per-profile daemon (IPC server, hydration, refresh)"],
    mixinStandardHelpOptions = true,
    subcommands = [
        DaemonRunCommand::class,
        DaemonStatusCommand::class,
        DaemonStopCommand::class,
    ],
)
class DaemonCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        println("Usage: unidrive daemon <run|status|stop>")
    }
}

// ── daemon run ───────────────────────────────────────────────────────────────

@Command(name = "run", description = ["Run the daemon in the foreground until SIGTERM"], mixinStandardHelpOptions = true)
class DaemonRunCommand : Runnable {
    @ParentCommand
    lateinit var daemonCmd: DaemonCommand

    override fun run() {
        val parent = daemonCmd.parent
        val profile = parent.resolveCurrentProfile()
        val lockFile = parent.providerConfigDir().resolve(".lock")
        val dbPath = parent.providerConfigDir().resolve("state.db")
        val socketPath = IpcServer.defaultSocketPath(profile.name)

        val runtime = DaemonRuntime(
            profileName = profile.name,
            lockFile = lockFile,
            dbPath = dbPath,
            socketPath = socketPath,
            providerFactory = { parent.createProvider() },
        )

        // Install SIGTERM handler that signals graceful shutdown.
        Runtime.getRuntime().addShutdownHook(Thread {
            runtime.close()
        })

        runBlocking { runtime.start() }
    }
}

// ── daemon status ────────────────────────────────────────────────────────────

@Command(name = "status", description = ["Show daemon status for the current profile"], mixinStandardHelpOptions = true)
class DaemonStatusCommand : Runnable {
    @ParentCommand
    lateinit var daemonCmd: DaemonCommand

    override fun run() {
        val parent = daemonCmd.parent
        val profile = parent.resolveCurrentProfile()
        val lockFile = parent.providerConfigDir().resolve(".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")

        if (!Files.exists(pidFile)) {
            System.err.println("no daemon running for profile '${profile.name}'")
            System.exit(1)
            return
        }

        val raw = Files.readString(pidFile).trim()
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val pid = parts.getOrNull(0)?.toLongOrNull()
        val modeToken = parts.getOrNull(1) ?: "(no-mode)"
        if (pid == null) {
            System.err.println("malformed lock-pid file at $pidFile: '$raw'")
            System.exit(1)
            return
        }
        println("pid $pid, mode $modeToken")

        // Phase 3 will add the RPC call to daemon.status here.
        // For Phase 2, status is file-derived only.
    }
}

// ── daemon stop ──────────────────────────────────────────────────────────────

@Command(name = "stop", description = ["Send SIGTERM to the running daemon and wait for it to exit"], mixinStandardHelpOptions = true)
class DaemonStopCommand : Runnable {
    @ParentCommand
    lateinit var daemonCmd: DaemonCommand

    companion object {
        const val STOP_DEADLINE_MS: Long = 12_000  // 10s graceful + 2s buffer
    }

    override fun run() {
        val parent = daemonCmd.parent
        val profile = parent.resolveCurrentProfile()
        val lockFile = parent.providerConfigDir().resolve(".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")

        if (!Files.exists(pidFile)) {
            println("no daemon running for profile '${profile.name}'")
            return  // idempotent stop; exit 0
        }

        val raw = Files.readString(pidFile).trim()
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val pid = parts.getOrNull(0)?.toLongOrNull()
        val modeToken = parts.getOrNull(1)
        if (pid == null) {
            System.err.println("malformed lock-pid file at $pidFile: '$raw'")
            System.exit(1)
            return
        }
        if (modeToken != "daemon") {
            System.err.println(
                "lock for profile '${profile.name}' is held by mode '$modeToken', not 'daemon'. " +
                    "Use the appropriate stop mechanism (e.g. \`kill $pid\` for sync).",
            )
            System.exit(1)
            return
        }

        // Send SIGTERM via ProcessHandle.
        val handle = ProcessHandle.of(pid).orElse(null)
        if (handle == null || !handle.isAlive) {
            println("daemon for profile '${profile.name}' (PID $pid) is not running (stale .lock.pid); cleaning up")
            runCatching { Files.deleteIfExists(pidFile) }
            return
        }
        handle.destroy()  // SIGTERM
        val exited = handle.onExit().orTimeout(STOP_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .handle { _, _ -> true }.get()
        if (!handle.isAlive) {
            println("daemon for profile '${profile.name}' stopped")
        } else {
            System.err.println(
                "daemon for profile '${profile.name}' did not exit within ${STOP_DEADLINE_MS}ms; " +
                    "send SIGKILL manually if needed (\`kill -9 $pid\`).",
            )
            System.exit(1)
        }
    }
}
```

- [ ] **Step 6.2: Register DaemonCommand in Main.kt's subcommands list**

Open `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:28-55`. Add `DaemonCommand::class` to the `subcommands` array, immediately after `MountCommand::class`:

```kotlin
    subcommands = [
        AuthCommand::class,
        BackupCommand::class,
        LogoutCommand::class,
        SyncCommand::class,
        MountCommand::class,
        DaemonCommand::class,
        // Git-style three-verb split — refresh = sync without byte
        // transfers; apply = drain pending transfers without re-fetching deltas.
        RefreshCommand::class,
        ApplyCommand::class,
        ...
```

- [ ] **Step 6.3: Compile + run cli tests**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:compileKotlin :app:cli:test --console=plain 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. T1 from Task 5 still passes (DaemonRuntime is the lifecycle; DaemonCommand is just the picocli shell that doesn't affect T1).

- [ ] **Step 6.4: Sanity check — `unidrive daemon --help` shows the subcommands**

Build the jar locally and exercise the CLI without invoking actual daemon logic:

```bash
./gradlew :app:cli:installDist --console=plain 2>&1 | tail -5
./app/cli/build/install/cli/bin/cli daemon --help 2>&1 | head -20
```

Expected: help text lists `run`, `status`, `stop` as subcommands.

- [ ] **Step 6.5: Commit Task 6**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonCommand.kt \
        core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
git commit -m "feat(cli): wire DaemonCommand picocli shell + nested run/status/stop

Per spec unidrive-daemon-design.md §3.3: DaemonCommand follows the
existing VaultCommand pattern (parent + nested children in one file).

- DaemonRunCommand: foreground; constructs DaemonRuntime from
  parent: Main, installs SIGTERM shutdown hook, runBlocking start().
- DaemonStatusCommand: reads .lock.pid for PID + mode. RPC enrichment
  (uptime, refresh-in-flight) lands in Phase 3 when daemon.status verb
  exists.
- DaemonStopCommand: reads .lock.pid; sends SIGTERM via
  ProcessHandle.destroy(); waits 12s (10s grace + 2s buffer).
  Idempotent — exits 0 if no daemon was running. Refuses if the lock
  is held by a non-daemon mode (e.g. sync).

DaemonCommand::class registered in Main.kt subcommands list."
```

---

### Task 7: T4 — auth-failure fail-fast test

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/test/kotlin/org/krost/unidrive/cli/DaemonRuntimeTest.kt` (append a test)

This is named test T4 from spec §6 — `daemon_fail_fast_on_auth_failure_does_not_bind_socket`. It exercises invariant I4 (no socket bound when auth fails).

- [ ] **Step 7.1: Write the failing T4 test**

Append to `DaemonRuntimeTest.kt`:

```kotlin
    @Test
    fun daemon_fail_fast_on_auth_failure_does_not_bind_socket() = runBlocking {
        // Spec T4: provider.authenticate() throws → daemon refuses to bind
        // the socket, releases the lock, exits non-zero.
        // Construct a provider that throws AuthenticationException.
        val provider = mockk<CloudProvider>(relaxed = true)
        coEvery { provider.authenticateAndLog() } throws org.krost.unidrive.AuthenticationException("test auth failure")

        val runtime = DaemonRuntime(
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        // start() should throw (or exit). Use the throwing path since
        // we can catch it from the test; System.exit() would kill JUnit.
        // For T4 to be cleanly testable, DaemonRuntime.start() must propagate
        // auth exceptions BEFORE the socket is bound. The catch block in
        // start() rethrows after cleanup() — that's the design contract this
        // test pins.
        val ex = kotlin.runCatching {
            runtime.start()
        }.exceptionOrNull()

        assertTrue(
            ex is org.krost.unidrive.AuthenticationException || ex?.cause is org.krost.unidrive.AuthenticationException,
            "auth failure must propagate; got: $ex",
        )

        // Invariant I4: socket file must NOT exist after auth failure.
        assertTrue(
            !Files.exists(socketPath),
            "socket file must not be left behind after auth failure; found $socketPath",
        )

        // Lock must be released — the .lock.pid sidecar should be gone.
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        assertTrue(
            !Files.exists(pidFile),
            ".lock.pid sidecar must be cleaned up after auth failure; found $pidFile",
        )
    }
```

- [ ] **Step 7.2: Run T4 to verify it fails initially OR passes**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.DaemonRuntimeTest.daemon_fail_fast_on_auth_failure_does_not_bind_socket" --console=plain 2>&1 | tail -20
```

The test may already PASS — the Task 5 implementation of `DaemonRuntime.start()` has `try { ... } catch { cleanup(); throw }`. If PASS, that's good: T4's contract holds for free.

If FAIL: examine the failure. The most likely causes are:
- `AuthenticationException` is not in `org.krost.unidrive` — adjust the import to wherever the real class lives (likely `org.krost.unidrive.AuthenticationException` per the existing `SyncEngine.kt:5` import).
- The cleanup() path doesn't delete the `.lock.pid` sidecar — verify `ProcessLock.unlock()` removes it (it does, per ProcessLock.kt:181).
- Auth fails inside the `coroutineScope { ... }` block, not before it — restructure `start()` so authentication happens OUTSIDE the `coroutineScope` (before `IpcServer` is constructed). Move the `provider.authenticateAndLog()` call up.

- [ ] **Step 7.3: Commit Task 7**

```bash
git add core/app/cli/src/test/kotlin/org/krost/unidrive/cli/DaemonRuntimeTest.kt
git commit -m "test(cli): T4 — daemon fail-fast on auth failure does not bind socket

Pins spec invariant I4: when provider.authenticateAndLog() throws,
the daemon must NOT have bound the socket and must NOT have left a
.lock.pid sidecar. The catch + finally in DaemonRuntime.start()
guarantees this; the test makes it a contract."
```

---

## Phase 3 — Verb handlers (`refresh.run`, `daemon.status`)

This phase adds the two new IPC verbs. After Phase 3: the daemon can refresh state.db on demand (via RPC, not a standalone JVM) and report its own uptime/client/refresh-state via a verb.

### Task 8: `RefreshRpcHandler` + `refresh.run` verb registration

**Files:**
- Create: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RefreshRpcHandler.kt`
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt` (register the verb)
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/test/kotlin/org/krost/unidrive/cli/DaemonRuntimeTest.kt` (T5)

- [ ] **Step 8.1: Write the failing T5 test `refresh_run_emits_terminal_event_after_completing_enumeration`**

Append to `DaemonRuntimeTest.kt`:

```kotlin
    @Test
    fun refresh_run_emits_terminal_event_after_completing_enumeration() = runBlocking {
        // Spec T5: subscribe → refresh.run → await refresh.done terminal event.
        // Then re-issue refresh.run; must succeed (not stuck in 'busy').
        val provider = mockk<CloudProvider>(relaxed = true)
        coEvery { provider.authenticateAndLog() } returns Unit
        // For T5 we don't strictly need provider to yield items — the spec
        // says "small finite tree" but the assertion is on the terminal
        // event, not on row count. An empty cloud tree is sufficient.

        val runtime = DaemonRuntime(
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = kotlinx.coroutines.launch { runtime.start() }

        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            kotlinx.coroutines.delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

        val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
        try {
            // Subscribe first
            channel.write(ByteBuffer.wrap(("""{"verb":"sync.subscribe"}""" + "\n").toByteArray()))
            val subBuf = ByteBuffer.allocate(1024)
            channel.read(subBuf)
            subBuf.flip()
            val subReply = String(subBuf.array(), 0, subBuf.limit())
            assertTrue(subReply.contains("\"ok\":true"), "subscribe must succeed; got: $subReply")

            // Issue refresh.run
            channel.write(ByteBuffer.wrap(("""{"verb":"refresh.run"}""" + "\n").toByteArray()))

            // Read until we see the terminal event "refresh.done"
            val collected = StringBuilder()
            val deadline = System.currentTimeMillis() + 10_000  // T5 must complete within 10s on a fake provider
            while (System.currentTimeMillis() < deadline && !collected.contains("refresh.done")) {
                val buf = ByteBuffer.allocate(4096)
                val n = channel.read(buf)
                if (n > 0) {
                    buf.flip()
                    collected.append(String(buf.array(), 0, buf.limit()))
                }
            }
            assertTrue(
                collected.contains("\"event\":\"refresh.done\""),
                "expected refresh.done terminal event within 10s; got: $collected",
            )
            assertTrue(
                collected.contains("\"ok\":true"),
                "expected ok:true on terminal event; got: $collected",
            )

            // Issue refresh.run again — must NOT be 'busy' since first one completed.
            channel.write(ByteBuffer.wrap(("""{"verb":"refresh.run"}""" + "\n").toByteArray()))
            val secondBuf = ByteBuffer.allocate(1024)
            channel.read(secondBuf)
            secondBuf.flip()
            val secondReply = String(secondBuf.array(), 0, secondBuf.limit())
            assertTrue(
                secondReply.contains("\"ok\":true"),
                "second refresh.run must succeed (not busy); got: $secondReply",
            )
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }
```

- [ ] **Step 8.2: Run T5 — must fail**

```bash
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.DaemonRuntimeTest.refresh_run_emits_terminal_event_after_completing_enumeration" --console=plain 2>&1 | tail -20
```

Expected: FAIL because the `refresh.run` verb is not registered. The reply will be an `unknown verb` error.

- [ ] **Step 8.3: Implement `RefreshRpcHandler`**

Create `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RefreshRpcHandler.kt`:

```kotlin
package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.SyncEngine
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * IPC verb handler for `refresh.run` (spec §4.2).
 *
 * Synchronous reply: `{"ok":true,"job_id":"<uuid>"}` immediately, or
 * `{"ok":false,"error":"busy",...}` if another refresh is in flight.
 *
 * Progress + terminal events are streamed post-reply via the IpcServer's
 * subscriber-set mechanism (scheduleAfterReply hook, mechanism β).
 *
 * Invariant I6: at most one refresh.run in flight per daemon. Serialised
 * via [inFlight]; a second concurrent call returns "busy".
 *
 * Terminal event delivery is best-effort per spec §5.2 F9 — if IpcServer
 * close races the emit, the event is dropped silently. Clients (the
 * `unidrive refresh` CLI) treat socket EOF as equivalent to a
 * `refresh.done error=shutdown` event.
 */
class RefreshRpcHandler(
    private val ipcServer: IpcServer,
    private val engine: SyncEngine,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(RefreshRpcHandler::class.java)

    private val inFlight = AtomicReference<RefreshJob?>(null)

    data class RefreshJob(val id: String, val job: Job)

    /**
     * Handle one `refresh.run` request. Returns the synchronous reply JSON.
     * If accepted (ok:true), launches the refresh body as a child of [scope].
     */
    fun handle(connectionId: String, @Suppress("UNUSED_PARAMETER") jsonRequest: String): String {
        val existing = inFlight.get()
        if (existing != null) {
            return """{"ok":false,"error":"busy","message":"refresh already running (job_id=${existing.id})"}"""
        }
        val jobId = UUID.randomUUID().toString()
        val launched = scope.launch {
            val terminalEvent = try {
                // Delegate to SyncEngine's refresh path (skipTransfers=true,
                // skipRemoteGather=false — same flags RefreshCommand uses).
                engine.syncOnce(skipTransfers = true, skipRemoteGather = false)
                """{"event":"refresh.done","job_id":"$jobId","ok":true}"""
            } catch (e: kotlinx.coroutines.CancellationException) {
                log.info("refresh.run cancelled (shutdown): job_id=$jobId")
                """{"event":"refresh.done","job_id":"$jobId","ok":false,"error":"shutdown"}"""
            } catch (e: Exception) {
                log.warn("refresh.run failed: job_id=$jobId", e)
                val msg = e.message?.replace("\"", "\\\"") ?: ""
                """{"event":"refresh.done","job_id":"$jobId","ok":false,"error":"provider_error","message":"$msg"}"""
            } finally {
                inFlight.set(null)
            }
            // Best-effort emit to subscribers (spec F9).
            runCatching {
                ipcServer.broadcastToSubscribers(terminalEvent)
            }
        }
        inFlight.set(RefreshJob(jobId, launched))
        return """{"ok":true,"job_id":"$jobId"}"""
    }

    /** Test-only / status-only accessor. */
    fun isInFlight(): Boolean = inFlight.get() != null

    /** Returns the in-flight job_id or null. */
    fun inFlightJobId(): String? = inFlight.get()?.id
}
```

**Important note for the implementer:** the call `ipcServer.broadcastToSubscribers(terminalEvent)` references a method that may or may not exist on the current `IpcServer`. Two paths to handle:

**(a) Method exists:** Look at `IpcServer.kt` for the broadcast helper used by `flushStateDumpTo` / sync-progress emission. The existing code likely already has a method that iterates `syncSubscribers` and writes the event to each. Use it — name might be `broadcastToSyncSubscribers`, `emitToSubscribers`, etc. Adapt the call accordingly.

**(b) Method doesn't exist:** Add a thin helper to IpcServer in this same task (separate commit OR same commit, your call). The helper iterates the existing `syncSubscribers` set and calls `writeToConnection(connId, event)` for each, ignoring per-connection failures (best-effort delivery). Signature:

```kotlin
suspend fun broadcastToSubscribers(json: String) {
    for (connId in syncSubscribers.toList()) {
        runCatching { writeToConnection(connId, json) }
    }
}
```

(Search for `syncSubscribers` in `IpcServer.kt` to confirm the field exists and is accessible.)

- [ ] **Step 8.4: Wire `refresh.run` verb in `DaemonRuntime`**

Modify `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt`. In the `coroutineScope { ... }` block, after the `sync.subscribe` registration, add:

```kotlin
                // refresh.run verb (spec §4.2)
                val refreshHandler = RefreshRpcHandler(server, engine, this)
                server.registerHandler("refresh.run") { connId, json ->
                    refreshHandler.handle(connId, json)
                }
```

(Keep the closing brace of `coroutineScope { ... }` where it is.)

- [ ] **Step 8.5: Run T5 — must now pass**

```bash
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.DaemonRuntimeTest.refresh_run_emits_terminal_event_after_completing_enumeration" --console=plain 2>&1 | tail -25
```

Expected: PASS. If FAIL on a 10s timeout (no `refresh.done` ever arrives), the broadcast path is wrong — debug by reading `IpcServer.syncSubscribers` field directly and verifying the test connection was added to it.

- [ ] **Step 8.6: Commit Task 8**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RefreshRpcHandler.kt \
        core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt \
        core/app/cli/src/test/kotlin/org/krost/unidrive/cli/DaemonRuntimeTest.kt
# If IpcServer needed a broadcastToSubscribers helper, add that file too:
# git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt
git commit -m "feat(cli): refresh.run IPC verb — RefreshRpcHandler + daemon registration

Per spec unidrive-daemon-design.md §4.2: refresh.run synchronous-reply-
then-stream-events pattern.

- Synchronous reply {\"ok\":true,\"job_id\":\"<uuid>\"} on accept,
  {\"ok\":false,\"error\":\"busy\"} if another refresh is in flight (I6).
- Terminal event {\"event\":\"refresh.done\",\"job_id\":...,\"ok\":bool} via
  the subscriber-set mechanism (mechanism β).
- Best-effort delivery on shutdown (spec F9): if IpcServer.close()
  races the emit, the event drops; clients treat socket EOF as
  equivalent.

T5 (refresh_run_emits_terminal_event_after_completing_enumeration)
pins the happy path + non-busy re-issue."
```

### Task 9: `daemon.status` verb

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt` (register verb, track start time)

- [ ] **Step 9.1: Write a focused test for `daemon.status`**

Append to `DaemonRuntimeTest.kt`:

```kotlin
    @Test
    fun daemon_status_returns_uptime_clients_and_refresh_state() = runBlocking {
        val provider = mockk<CloudProvider>(relaxed = true)
        coEvery { provider.authenticateAndLog() } returns Unit

        val runtime = DaemonRuntime(
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = kotlinx.coroutines.launch { runtime.start() }
        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            kotlinx.coroutines.delay(50)
        }

        val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
        try {
            channel.write(ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
            val buf = ByteBuffer.allocate(1024)
            channel.read(buf)
            buf.flip()
            val reply = String(buf.array(), 0, buf.limit())
            assertTrue(reply.contains("\"ok\":true"), "expected ok:true; got: $reply")
            assertTrue(reply.contains("\"uptime_ms\""), "expected uptime_ms field; got: $reply")
            assertTrue(reply.contains("\"clients_connected\""), "expected clients_connected; got: $reply")
            assertTrue(reply.contains("\"refresh_in_flight\":false"), "expected refresh_in_flight:false; got: $reply")
            assertTrue(reply.contains("\"refresh_job_id\":null"), "expected refresh_job_id:null; got: $reply")
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }
```

- [ ] **Step 9.2: Run the test — must fail (unknown verb)**

```bash
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.DaemonRuntimeTest.daemon_status_returns_uptime_clients_and_refresh_state" --console=plain 2>&1 | tail -15
```

Expected: FAIL.

- [ ] **Step 9.3: Add daemon-start timestamp + register verb**

In `DaemonRuntime.kt`, add at the top of the class:

```kotlin
    private var startedAtMs: Long = 0
```

Inside `start()`, immediately before the `coroutineScope { ... }` block:

```kotlin
            startedAtMs = System.currentTimeMillis()
```

Inside the `coroutineScope { ... }` block, after the `refresh.run` registration from Task 8:

```kotlin
                // daemon.status verb (spec §4.3)
                server.registerHandler("daemon.status") { _, _ ->
                    val uptimeMs = System.currentTimeMillis() - startedAtMs
                    val clientCount = server.clientCount
                    val refreshInFlight = refreshHandler.isInFlight()
                    val refreshJobId = refreshHandler.inFlightJobId()
                    val jobIdJson = if (refreshJobId != null) "\"$refreshJobId\"" else "null"
                    """{"ok":true,"uptime_ms":$uptimeMs,"clients_connected":$clientCount,"refresh_in_flight":$refreshInFlight,"refresh_job_id":$jobIdJson}"""
                }
```

(The `server.clientCount` field is the existing read-only Int accessor at `IpcServer.kt:61`. Verify with `grep -n "clientCount" core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`.)

- [ ] **Step 9.4: Run the test — must now pass**

```bash
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.DaemonRuntimeTest.daemon_status_returns_uptime_clients_and_refresh_state" --console=plain 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 9.5: Commit Task 9**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt \
        core/app/cli/src/test/kotlin/org/krost/unidrive/cli/DaemonRuntimeTest.kt
git commit -m "feat(cli): daemon.status IPC verb

Per spec unidrive-daemon-design.md §4.3: read-only verb returning
uptime_ms + clients_connected + refresh_in_flight + refresh_job_id.

PID and profile name are deliberately NOT in the response — those
come from .lock.pid (knowable without the daemon being reachable;
spec §4.3 chicken-and-egg rationale).

DaemonStatusCommand in Task 6 currently only reads .lock.pid; Phase 4
will extend it to call this verb for the richer fields."
```

### Task 10: Phase 3 close-out — full cli tests green

- [ ] **Step 10.1: Run all cli tests**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:test --console=plain 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10.2: Run broader check (sync + hydration too — they should be unaffected)**

```bash
./gradlew :app:sync:test :app:hydration:test --console=plain 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

---

## Phase 4 — CLI client refactors (`RefreshCommand`, `DaemonStatusCommand` enrichment)

This phase converts `unidrive refresh` from a standalone JVM into a daemon client and extends `DaemonStatusCommand` to RPC the daemon for the richer fields.

### Task 11: Refactor `RefreshCommand` to a thin daemon client

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RefreshCommand.kt` (full rewrite)

- [ ] **Step 11.1: Read the current RefreshCommand for context**

```bash
cat core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RefreshCommand.kt
```

Today: extends `SyncCommand` with `skipTransfers = true`. Post-refactor: extends nothing; connects to the daemon socket and issues `sync.subscribe` + `refresh.run`.

- [ ] **Step 11.2: Rewrite RefreshCommand to a thin daemon client**

Replace the entire body of `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RefreshCommand.kt`:

```kotlin
package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files

/**
 * `unidrive refresh` — thin client of the daemon's `refresh.run` IPC verb.
 *
 * Per spec docs/dev/specs/unidrive-daemon-design.md §3.3: the pre-spec
 * standalone-JVM refresh path is removed. The daemon (`unidrive daemon run`)
 * must be running; this command connects to its socket, issues
 * sync.subscribe, then refresh.run, then streams progress events until
 * the refresh.done terminal event.
 *
 * Migration for cron jobs that called `unidrive refresh` standalone:
 *   unidrive daemon run <profile> & sleep 2 && unidrive refresh <profile> && unidrive daemon stop <profile>
 * Or move to a systemd-user-unit when phase-3 ships (BACKLOG follow-up).
 */
@Command(
    name = "refresh",
    description = ["Update state.db with remote changes via the running daemon's refresh.run RPC."],
    mixinStandardHelpOptions = true,
)
class RefreshCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        val profile = parent.resolveCurrentProfile()
        val socketPath = IpcServer.defaultSocketPath(profile.name)

        if (!Files.exists(socketPath)) {
            System.err.println(
                "unidrive refresh: daemon for profile '${profile.name}' is not running.",
            )
            System.err.println(
                "Start it first: `unidrive daemon run ${profile.name}` (in another terminal).",
            )
            System.exit(1)
            return
        }

        try {
            SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { channel ->
                // Subscribe to progress events
                channel.write(ByteBuffer.wrap(("""{"verb":"sync.subscribe"}""" + "\n").toByteArray()))
                readOneJsonReply(channel)  // discard sync.subscribe reply

                // Issue refresh.run
                channel.write(ByteBuffer.wrap(("""{"verb":"refresh.run"}""" + "\n").toByteArray()))
                val runReply = readOneJsonReply(channel)
                if (!runReply.contains("\"ok\":true")) {
                    System.err.println("unidrive refresh: daemon rejected refresh.run: $runReply")
                    System.exit(1)
                    return
                }

                // Stream events until refresh.done
                val collected = StringBuilder()
                while (!collected.contains("\"event\":\"refresh.done\"")) {
                    val buf = ByteBuffer.allocate(4096)
                    val n = channel.read(buf)
                    if (n <= 0) {
                        // EOF — daemon shut down per spec F9
                        System.err.println("unidrive refresh: daemon shut down before refresh completed")
                        System.exit(1)
                        return
                    }
                    buf.flip()
                    val chunk = String(buf.array(), 0, buf.limit())
                    collected.append(chunk)
                    // Print progress events as they arrive (one per line)
                    chunk.lineSequence().filter { it.isNotBlank() }.forEach { println(it) }
                }

                // Check terminal event for failure
                if (collected.contains("\"event\":\"refresh.done\"") && !collected.contains("\"ok\":true")) {
                    System.exit(1)
                }
            }
        } catch (e: java.io.IOException) {
            System.err.println("unidrive refresh: failed to communicate with daemon: ${e.message}")
            System.err.println("Daemon may have crashed. Restart with: `unidrive daemon run ${profile.name}`.")
            System.exit(1)
        }
    }

    /** Read a single JSON reply terminated by newline. Returns the line without the newline. */
    private fun readOneJsonReply(channel: SocketChannel): String {
        val collected = StringBuilder()
        while (!collected.contains('\n')) {
            val buf = ByteBuffer.allocate(1024)
            val n = channel.read(buf)
            if (n <= 0) return collected.toString()
            buf.flip()
            collected.append(String(buf.array(), 0, buf.limit()))
        }
        return collected.toString().substringBefore('\n')
    }
}
```

- [ ] **Step 11.3: Compile + run cli tests**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:compileKotlin :app:cli:test --console=plain 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. T5 from Task 8 still passes (it tested the daemon-side, not the client).

If any existing RefreshCommand-related test fails (e.g. a test that exercised the old `extends SyncCommand` path), that test was testing the old behaviour. Per spec NG8 the standalone JVM refresh is removed; update or delete the test as appropriate. Document the deletion in the commit message.

- [ ] **Step 11.4: Commit Task 11**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RefreshCommand.kt
# If any test was deleted/updated as part of this refactor, add it too.
git commit -m "refactor(cli): RefreshCommand is now a thin daemon client

Per spec unidrive-daemon-design.md §3.3 + NG8: the pre-spec standalone
JVM refresh path (RefreshCommand extends SyncCommand with
skipTransfers=true) is removed entirely. RefreshCommand now:

- Connects to the daemon's IPC socket at IpcServer.defaultSocketPath().
- Issues sync.subscribe, then refresh.run.
- Streams progress events to stdout until the refresh.done terminal
  event arrives.
- Maps socket EOF to a 'daemon shut down before refresh completed'
  error (spec F9 contract).
- Prints a clear 'daemon not running' error pointing at
  \`unidrive daemon run\` when the socket file doesn't exist.

Breaks: scripts that ran \`unidrive refresh <profile>\` without a daemon
will fail. Migration documented in NG8."
```

### Task 12: Extend `DaemonStatusCommand` to RPC daemon.status

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonCommand.kt` (`DaemonStatusCommand` body)

- [ ] **Step 12.1: Extend DaemonStatusCommand to call daemon.status via RPC**

Replace the body of `DaemonStatusCommand.run()` from Step 6.1. The file-derived path (PID + mode from `.lock.pid`) stays; the RPC call gets appended:

```kotlin
    override fun run() {
        val parent = daemonCmd.parent
        val profile = parent.resolveCurrentProfile()
        val lockFile = parent.providerConfigDir().resolve(".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")

        if (!Files.exists(pidFile)) {
            System.err.println("no daemon running for profile '${profile.name}'")
            System.exit(1)
            return
        }

        val raw = Files.readString(pidFile).trim()
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val pid = parts.getOrNull(0)?.toLongOrNull()
        val modeToken = parts.getOrNull(1) ?: "(no-mode)"
        if (pid == null) {
            System.err.println("malformed lock-pid file at $pidFile: '$raw'")
            System.exit(1)
            return
        }
        println("pid $pid, mode $modeToken")

        // RPC enrichment per spec §4.3 — file-derived data above, RPC-derived below.
        val socketPath = IpcServer.defaultSocketPath(profile.name)
        if (!Files.exists(socketPath)) {
            System.err.println("daemon socket not found at $socketPath (daemon may be mid-shutdown)")
            return
        }
        try {
            java.net.UnixDomainSocketAddress.of(socketPath).let { addr ->
                java.nio.channels.SocketChannel.open(addr).use { channel ->
                    channel.write(java.nio.ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
                    val buf = java.nio.ByteBuffer.allocate(1024)
                    channel.read(buf)
                    buf.flip()
                    val reply = String(buf.array(), 0, buf.limit()).substringBefore('\n')
                    println(reply)
                }
            }
        } catch (e: java.io.IOException) {
            System.err.println("daemon socket unreachable: ${e.message}")
        }
    }
```

- [ ] **Step 12.2: Compile + run cli tests**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:test --console=plain 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 12.3: Commit Task 12**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonCommand.kt
git commit -m "feat(cli): DaemonStatusCommand RPC-enriches output with daemon.status

Per spec unidrive-daemon-design.md §3.3 + §4.3: status output is now
two-stage:
1. .lock.pid (file-derived: PID + mode) — always printed.
2. daemon.status RPC reply (uptime_ms, clients_connected,
   refresh_in_flight, refresh_job_id) — printed when the socket is
   reachable.

If the .lock.pid exists but the socket is gone (daemon mid-shutdown
or kill -9'd), prints the file-derived line and a 'socket unreachable'
note. Chicken-and-egg case from spec §4.3 honored: never need the
daemon to be up just to know whether it's up."
```

---

## Phase 5 — BACKLOG close-out + deploy + live smoke + merge

### Task 13: Full JVM suite + BACKLOG entry close-out

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/BACKLOG.md` (move the High-tier entry to "Recently Completed" or equivalent)

- [ ] **Step 13.1: Run full JVM suite**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test :app:cli:test :app:hydration:test --console=plain 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, no failures across all modules.

- [ ] **Step 13.2: Move the BACKLOG entry**

In `/home/gernot/dev/git/unidrive/BACKLOG.md`, find the High-tier entry titled "`unidrive mount` has no JVM-side IPC server lifecycle (mount-only mode is dead-on-arrival)". Move it to "Recently Completed" (look for the existing section name — `## Recently Completed` or `## Closed` or similar; if no such section exists, create it at the bottom of the file under a new `## Recently Completed` heading). Add a one-line resolution note:

```markdown
- **`unidrive mount` has no JVM-side IPC server lifecycle (mount-only mode is dead-on-arrival)** —
  Resolved by the `unidrive daemon` per-profile process per
  docs/dev/specs/unidrive-daemon-design.md. Mount becomes a thin client
  of the daemon. Plan: docs/dev/plans/unidrive-daemon-plan.md Tasks 1-13.
```

- [ ] **Step 13.3: Verify the entry moved**

```bash
grep -nE "unidrive mount.*has no JVM-side|Resolved by the .unidrive daemon" BACKLOG.md | head -5
```

Expected: the entry now appears in the Completed section, with the resolution note.

- [ ] **Step 13.4: Commit BACKLOG close-out**

```bash
git add BACKLOG.md
git commit -m "docs(backlog): close mount-IPC-lifecycle entry after daemon ship

The High-tier entry filed during 2026-05-24 live smoke (mount-IPC
ECONNREFUSED) is resolved by the unidrive daemon implementation
per docs/dev/specs/unidrive-daemon-design.md and Plan Tasks 1-13."
```

### Task 14: Deploy + operator-driven live smoke (spec §7)

**Files:** none (operator-side commands).

- [ ] **Step 14.1: Deploy the JVM daemon**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:deploy --console=plain 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, deploy task prints completion banner.

- [ ] **Step 14.2: Verify the new subcommand is available**

```bash
/home/gernot/.local/bin/unidrive daemon --help 2>&1 | head -15
```

Expected: help text lists `run`, `status`, `stop` as subcommands of `daemon`.

- [ ] **Step 14.3: Operator-driven live smoke — Phase 1 (daemon basics)**

Per spec §7 steps 1-3:

```bash
# Terminal A: start daemon
/home/gernot/.local/bin/unidrive daemon run posteo_onedrive
# Expected stderr: "daemon ready, pid <N>, socket /run/user/1000/unidrive-posteo_onedrive.sock"
# Foreground; stays running.

# Terminal B: contention test
/home/gernot/.local/bin/unidrive sync posteo_onedrive
# Expected: exit 1, stderr:
#   Profile 'posteo_onedrive' is currently in use by `unidrive daemon` (PID <N>).
#   Stop the daemon first: `unidrive daemon stop posteo_onedrive`.

# Terminal B: status
/home/gernot/.local/bin/unidrive -p posteo_onedrive daemon status
# Expected:
#   pid <N>, mode daemon
#   {"ok":true,"uptime_ms":<M>,"clients_connected":1,"refresh_in_flight":false,"refresh_job_id":null}
```

Document each output. Any divergence from expected becomes a finding to file before merge.

- [ ] **Step 14.4: Operator-driven live smoke — Phase 2 (refresh + mount)**

Per spec §7 steps 4-5:

```bash
# Terminal B (daemon still in A)
/home/gernot/.local/bin/unidrive -p posteo_onedrive refresh
# Expected: progress events stream to stdout; exits 0 on refresh.done with ok:true.

# Terminal B
/home/gernot/.local/bin/unidrive -p posteo_onedrive mount /tmp/onedrive-smoke
# Expected: FUSE mount comes up; mount command stays foreground.

# Terminal C
ls /tmp/onedrive-smoke
# Expected: sees the cloud tree (because refresh populated state.db).
mkdir /tmp/onedrive-smoke/smoke_folder
echo hello > /tmp/onedrive-smoke/smoke_folder/hello.txt
rm /tmp/onedrive-smoke/smoke_folder/hello.txt
rmdir /tmp/onedrive-smoke/smoke_folder
# All four must succeed; verify in the OneDrive web UI within 30s.
```

- [ ] **Step 14.5: Operator-driven live smoke — Phase 3 (graceful stop + error path)**

Per spec §7 steps 6-7:

```bash
# Terminal D
/home/gernot/.local/bin/unidrive -p posteo_onedrive daemon stop
# Expected: prints "daemon for profile 'posteo_onedrive' stopped", exit 0 within 12s.
# Terminal A's daemon exits cleanly.
# Terminal B's mount also exits (co-daemon notices socket gone, FUSE unmounts).

# Terminal D — daemon-not-running error path
/home/gernot/.local/bin/unidrive -p posteo_onedrive mount /tmp/onedrive-smoke
# Expected: exit 1, stderr:
#   [Rust] failed to connect IPC at /run/user/1000/unidrive-posteo_onedrive.sock: ...
#   unidrive mount: co-daemon exited with code 1. If the cause was Connection refused, the daemon for profile 'posteo_onedrive' is not running. Start it with: `unidrive daemon run posteo_onedrive`.
```

- [ ] **Step 14.6: File findings for any divergence**

If any expected output doesn't match the actual output:
- For each divergence, file a BACKLOG entry under appropriate tier (High if the divergence breaks the spec's contract, Medium if it's UX polish).
- Do NOT merge until either (a) the divergence is fixed, or (b) it's filed AND the spec is updated to reflect actual behaviour with operator's sign-off.

If everything matches, proceed to merge.

### Task 15: Merge to main (operator-supervised)

**Files:** none (git operations).

- [ ] **Step 15.1: Pre-merge state check**

```bash
cd /home/gernot/dev/git/unidrive
git status --short
git log --oneline opencode_dr ^main | head -30
```

Expected: clean working tree, list of all daemon-plan commits since the branch diverged from main.

- [ ] **Step 15.2: Update local main**

```bash
git fetch origin
git checkout main
git merge --ff-only origin/main
```

If `--ff-only` fails: main has diverged. STOP and surface to the operator — manual rebase / reconciliation is needed and not in this plan's scope.

- [ ] **Step 15.3: Merge feature branch**

```bash
git merge --no-ff opencode_dr -m "Merge: unidrive daemon — per-profile long-lived IPC server"
```

Do NOT push. Per CLAUDE.md / AGENTS.md, push requires explicit operator approval.

- [ ] **Step 15.4: Final report to operator**

Post a single short summary in chat:
- Phase 1 (mode mutex amendment): committed, tested.
- Phase 2 (DaemonRuntime + DaemonCommand): committed, T1 + T4 named tests green.
- Phase 3 (refresh.run + daemon.status verbs): committed, T5 named test green.
- Phase 4 (RefreshCommand + DaemonStatusCommand client refactors): committed.
- Phase 5 (BACKLOG close-out + live smoke): completed; any findings filed.
- Feature branch merged to local `main`.
- Awaiting operator go-ahead before `git push origin main`.

---

## Self-Review Checklist (performed by plan author before handoff)

- **Spec coverage:**
  - Spec §2 G1 (mount works end-to-end without sync) — Phase 1 strips lock acquisition, Phase 2 wires daemon IPC, Phase 5 smoke validates.
  - Spec §2 G2 (one daemon per profile) — Task 5 acquires `Mode.DAEMON` lock, Task 2 renders contention.
  - Spec §2 G3 (strictly reactive) — Task 5 only registers verbs; no scheduler.
  - Spec §2 G4 (refresh as RPC) — Tasks 8, 11.
  - Spec §2 G5 (explicit lifecycle commands) — Task 6.
  - Spec §2 G6 (mode-mutex amendment) — Tasks 1-3.
  - Spec §2 G7 (fail-fast auth) — Task 7 (T4).
  - Spec §2 G8 (graceful shutdown 10s deadline) — Task 6 shutdown hook + Task 5 cleanup; not yet explicitly enforced with `withTimeoutOrNull` — that's the implementer's call when wiring DaemonRuntime.close()'s graceful path. **Recommendation to the implementer:** add a `withTimeoutOrNull(SHUTDOWN_DEADLINE_MS) { closeSignal.await() ... }` shape during Task 5's `start()` body if the test for graceful-shutdown timing (left to implementer judgment per spec §6) needs it.
  - Spec §3.3 component responsibilities — each component covered in named tasks.
  - Spec §4.1 existing verbs — inherited via HydrationIpcHandler registration in Task 5.
  - Spec §4.2 refresh.run — Task 8.
  - Spec §4.3 daemon.status — Task 9.
  - Spec §4.4 sync.subscribe semantics — registered in Task 5.
  - Spec §4.5 mode-mutex amendment — Tasks 1-3.
  - Spec §5.1 invariants I1-I8 — pinned across T1-T5 + the failure-mode tests left to implementer judgment.
  - Spec §5.2 failure mode catalog F1-F10 — F1 (Tasks 2, 5), F2 (Task 7 T4), F3 (inherited via IpcServer.reclaimStaleSocket — no new test), F4 (DB open failure — not explicitly tested; implementer covers if scope allows), F5-F10 (inherited from existing IpcServer + hydration paths).
  - Spec §6 named tests T1-T5 — all included: T1 (Task 5), T2 (Task 2), T3 (Task 2), T4 (Task 7), T5 (Task 8).
  - Spec §7 live smoke — Task 14.

- **Placeholder scan:**
  - No `TBD` / `TODO` / `FIXME` / "implement later" / "fill in details" / "Similar to Task N".
  - Every step shows the actual code or command.
  - The few implementer-judgment notes (graceful-shutdown timing, DB open failure test, broadcast-helper-exists-vs-add) are explicit decisions documented inline, not vague placeholders.

- **Type consistency:**
  - `Mode.SYNC` / `Mode.DAEMON` used consistently across all tasks; `Mode.MOUNT` only appears in commit messages and migration paths.
  - `DaemonRuntime` constructor signature `(profileName, lockFile, dbPath, socketPath, providerFactory)` consistent across Tasks 5, 6, 7, 8, 9.
  - `RefreshRpcHandler` `handle(connectionId, jsonRequest)` matches the verb-handler shape used by `HydrationIpcHandler`.
  - Wire-contract error strings `"busy"` / `"shutdown"` / `"auth_failed"` / `"provider_error"` consistent with spec §4.2.
  - `daemon.status` reply shape (uptime_ms, clients_connected, refresh_in_flight, refresh_job_id) matches spec §4.3 in Task 9.
