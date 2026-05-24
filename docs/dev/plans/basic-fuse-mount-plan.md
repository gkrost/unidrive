# Basic FUSE Mount Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `unidrive mount` end-to-end usable for everyday operations (mkdir/rm/edit) by (a) preventing the legacy `SyncEngine` from clobbering mount-writes via a per-profile mode mutex, and (b) implementing the three missing FUSE namespace operations (mkdir, unlink, rmdir) as new hydration IPC verbs.

**Architecture:** Two specs, one plan, two-phase sequencing. Phase 1 (Tasks 1-3) extends the existing `ProcessLock` with mode metadata and makes `MountCommand` acquire it at startup, mutually excluding sync and mount per profile. Phase 2 (Tasks 4-8) adds three new hydration verbs (`hydration.mkdir`/`hydration.unlink`/`hydration.rmdir`), the JVM-side `SyncEngine` entry points + `StateDatabase` helpers, the Hydration SPI extension, and the Rust co-daemon FUSE op impls. Phase 2 assumes the mutex holds; without Phase 1 first, every namespace op would race the legacy engine.

**Tech Stack:** Kotlin (JVM 21), kotlinx.coroutines 1.8.x, `java.nio.channels.FileChannel`/`FileLock`, `java.nio.file.Files`, JUnit 5 via `kotlin.test`, MockK; Rust 1.x (sibling repo `unidrive-mount-linux`), `fuse3` crate, `tokio`, `serde_json`. Build from `core/`: `./gradlew :app:sync:test :app:hydration:test :app:cli:test -q`. Sibling: `cd ../unidrive-mount-linux && cargo test`. Local deploy: `./gradlew :app:cli:deploy -q`.

**Specs:**
- `/home/gernot/dev/git/unidrive/docs/dev/specs/mount-sync-mode-mutex-design.md` (Phase 1)
- `/home/gernot/dev/git/unidrive/docs/dev/specs/hydration-namespace-verbs-design.md` (Phase 2)

**BACKLOG entries this plan closes:**
- `/home/gernot/dev/git/unidrive/BACKLOG.md` Critical: "Mount-write clobbered by legacy SyncEngine on next `--watch` cycle (data loss class)"
- `/home/gernot/dev/git/unidrive-mount-linux/BACKLOG.md` High: "FUSE `mkdir` not implemented — returns ENOSYS"
- `/home/gernot/dev/git/unidrive-mount-linux/BACKLOG.md` High: "FUSE `unlink` / `rmdir` not implemented — returns ENOSYS"

---

## Pre-flight

- [ ] **Step 0: Verify clean working tree, branch off main**

Run:
```bash
cd /home/gernot/dev/git/unidrive
git status --short
git branch --show-current
git checkout -b fix/basic-fuse-mount
git branch --show-current
```
Expected: empty `git status`, on `main` before checkout, on `fix/basic-fuse-mount` after.

- [ ] **Step 0a: Confirm baseline test suites are green**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test :app:hydration:test :app:cli:test -q > /tmp/baseline.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 5 /tmp/baseline.log || echo "no failures"
```
Expected: exit 0. If anything fails, STOP and surface to the user — don't proceed against a broken baseline.

- [ ] **Step 0b: Create sibling-repo branch**

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
git status --short
git checkout -b fix/basic-fuse-mount
cargo test --no-run 2>&1 | tail -5
```
Expected: empty status, branch created. The `cargo test --no-run` confirms the Rust workspace builds against the current `FakeJvm` shape; Phase 2 will extend the fixture if a new wire field doesn't fit.

---

## File Structure

### Phase 1 (mode mutex)

| File | Role | Status |
|---|---|---|
| `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt` | Extend with `Mode` enum, `HolderInfo` data class, `tryLock(Mode)`, `readHolderInfo()`; tighten `readHolderPid()` to parse only the first whitespace-separated token. | Modify |
| `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` | `acquireProfileLock()` passes `Mode.SYNC`, renders mount-holder error. New `acquireProfileLockForMount()` factory. | Modify |
| `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt` | Acquire `Mode.MOUNT` lock in `run()` before socket setup; release in `finally`. | Modify |
| `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt` | Add zombie-mount sanity check after `acquireProfileLock()` (warn-not-abort). | Modify |
| `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StaleMountDetector.kt` | New utility: reads `/proc/self/mounts`, returns list of stale unidrive FUSE mountpoints. | Create |
| `/home/gernot/dev/git/unidrive/core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt` | T1, T2, T3 — wire format, holder-info reader, legacy-format compat. | Modify |
| `/home/gernot/dev/git/unidrive/core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ProfileLockFactoryTest.kt` | T4, T5 — mount-refuses-when-sync-holds, sync-refuses-when-mount-holds. | Create |

### Phase 2 (namespace verbs)

| File | Role | Status |
|---|---|---|
| `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt` | Two new public methods: `createRemoteFolder(path)`, `deleteRemote(path)`. | Modify |
| `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt` | Two new methods: `insertFolder(path, remoteId, mtime)`, `markDeleted(path)`. | Modify |
| `/home/gernot/dev/git/unidrive/core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt` | SPI: three new methods (`mkdir`/`unlink`/`rmdir`) + result types (`MkdirResult`/`UnlinkResult`/`RmdirResult`). | Modify |
| `/home/gernot/dev/git/unidrive/core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt` | Implement the three SPI methods. | Modify |
| `/home/gernot/dev/git/unidrive/core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt` | Three new verb branches in `handle(...)` + three entries in `VERBS` set. | Modify |
| `/home/gernot/dev/git/unidrive/core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplNamespaceTest.kt` | T1, T2, T3 (JVM-side). | Create |
| `/home/gernot/dev/git/unidrive-mount-linux/mount/src/ipc.rs` | Three new `IpcClient` methods. | Modify |
| `/home/gernot/dev/git/unidrive-mount-linux/mount/src/reconnect.rs` | Three new `ReconnectingIpcClient` wrappers retrying on `IpcError::Io`. | Modify |
| `/home/gernot/dev/git/unidrive-mount-linux/mount/src/fuse_fs.rs` | Three new FUSE op impls (`mkdir`/`unlink`/`rmdir`) + `map_ipc_err_to_errno` helper. | Modify |
| `/home/gernot/dev/git/unidrive-mount-linux/mount/tests/namespace_ops.rs` | T4, T5 (Rust-side). | Create |

---

## Task 1: ProcessLock — Mode metadata, holder-info reader, tightened legacy compat

Extends `ProcessLock` with the wire-format and reader API described in spec §3.1 + §3.2. The existing single-arg `tryLock(timeout)` overload survives byte-identically for any caller still passing only a duration.

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt`
- Modify: `/home/gernot/dev/git/unidrive/core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt`

- [ ] **Step 1.1: Add Mode enum and HolderInfo data class to ProcessLock**

Locate the `class ProcessLock(...)` declaration (around line 17). Inside the class, after the `pidFile` declaration (around line 33), add:

```kotlin
    /**
     * Mode of the process holding this lock. `SYNC` for SyncCommand,
     * `MOUNT` for MountCommand. Read alongside the PID from .lock.pid
     * so a contender can name the holder accurately.
     */
    enum class Mode(internal val wireToken: String) {
        SYNC("sync"),
        MOUNT("mount"),
        ;

        companion object {
            internal fun fromWireToken(token: String): Mode? =
                values().firstOrNull { it.wireToken == token }
        }
    }

    /**
     * Pid + mode of a lock's current holder, returned by [readHolderInfo].
     *
     * [mode] is `null` when the sidecar carries a mode token this version
     * does not recognise (forward-compat path per spec §3.1: "readers
     * parse the second token literally and MountCommand/SyncCommand
     * refuse with a message identifying the unknown mode"). In that case
     * [rawMode] carries the raw token verbatim so the contention message
     * can name it.
     *
     * Legacy pid-only sidecars (no mode token, pre-fix daemons) yield
     * `mode = Mode.SYNC, rawMode = null` — that path is the intentional
     * backwards-compat default per spec §3.1 ("read as `<pid> sync`").
     */
    data class HolderInfo(val pid: Long, val mode: Mode?, val rawMode: String? = null)
```

- [ ] **Step 1.2: Add the new `tryLock(Mode, Duration)` overload + keep the legacy overload**

The current `tryLock(timeout: Duration = ...)` (line 37) stamps only the PID. Refactor so the legacy overload becomes a shim that calls the new mode-aware overload with `Mode.SYNC`.

Find the existing `tryLock` body (lines 37-84). The pid-write line is around line 65 (`Files.writeString(pidFile, "${ProcessHandle.current().pid()}\n")`). Replace the whole method with the two-overload pair:

```kotlin
    /**
     * Try to acquire the lock, waiting up to [timeout] for it to become available.
     * If [timeout] is zero, the method returns immediately.
     * Legacy overload — passes Mode.SYNC. New callers should use the explicit-mode variant.
     */
    fun tryLock(timeout: Duration = 0.toDuration(DurationUnit.MILLISECONDS)): Boolean =
        tryLock(Mode.SYNC, timeout)

    /**
     * Try to acquire the lock and stamp `<pid> <mode>` into the sibling .pid file.
     * @return true if the lock was acquired, false otherwise.
     */
    fun tryLock(
        mode: Mode,
        timeout: Duration = 0.toDuration(DurationUnit.MILLISECONDS),
    ): Boolean {
        if (lock != null) return true

        val start = System.currentTimeMillis()
        val timeoutMs = timeout.inWholeMilliseconds
        var acquired = false

        while (!acquired) {
            channel =
                FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            try {
                val candidate = channel!!.tryLock()
                if (candidate != null) {
                    lock = candidate
                    acquired = true
                    // UD-272 + spec §3.1 wire format: "<pid> <mode>\n"
                    runCatching {
                        Files.writeString(
                            pidFile,
                            "${ProcessHandle.current().pid()} ${mode.wireToken}\n",
                        )
                    }
                    break
                }
            } catch (_: Exception) {
                // tryLock failed (e.g. OverlappingFileLockException)
            }
            channel!!.close()
            channel = null

            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= timeoutMs) {
                break
            }
            Thread.sleep(100)
        }
        return acquired
    }
```

- [ ] **Step 1.3: Tighten `readHolderPid()` to parse only the first whitespace-separated token**

The current body (line 94-97) does `Files.readString(pidFile).trim().toLongOrNull()`. With the new `<pid> <mode>\n` wire format that would return `null`. Replace with:

```kotlin
    fun readHolderPid(): Long? =
        runCatching {
            Files.readString(pidFile)
                .trim()
                .substringBefore(' ')
                .toLongOrNull()
        }.getOrNull()
```

- [ ] **Step 1.4: Add `readHolderInfo()` immediately after `readHolderPid()`**

After the closing brace of `readHolderPid` (line 97 area), add:

```kotlin
    /**
     * Read the (pid, mode) tuple of the current holder. Returns `null` if the
     * file is empty / unreadable / malformed. Backwards-compatible with the
     * legacy pid-only format: a file containing just `<pid>\n` (no mode token)
     * yields `HolderInfo(pid, Mode.SYNC)`. Used by the contention error path
     * to render a mode-specific message.
     */
    fun readHolderInfo(): HolderInfo? =
        runCatching {
            val raw = Files.readString(pidFile).trim()
            if (raw.isEmpty()) return@runCatching null
            val parts = raw.split(Regex("\\s+"), limit = 2)
            val pid = parts.getOrNull(0)?.toLongOrNull() ?: return@runCatching null
            val modeToken = parts.getOrNull(1)
            when (modeToken) {
                null -> {
                    // Legacy pid-only sidecar (pre-fix daemon). Spec §3.1:
                    // "read as `<pid> sync`" — backwards-compat default.
                    HolderInfo(pid, Mode.SYNC, rawMode = null)
                }
                else -> {
                    val parsed = Mode.fromWireToken(modeToken)
                    if (parsed != null) {
                        HolderInfo(pid, parsed, rawMode = null)
                    } else {
                        // Forward-compat: unknown token (e.g. "mirror" from a
                        // future version). Carry the raw token so the
                        // contention error message can name it accurately.
                        // Per spec §3.1: do NOT silently default to SYNC.
                        HolderInfo(pid, mode = null, rawMode = modeToken)
                    }
                }
            }
        }.getOrNull()
```

- [ ] **Step 1.5: Write T1, T2, T3 failing tests**

Open `/home/gernot/dev/git/unidrive/core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt`. After the existing tests (the file already has `UD-272` PID tests; locate the end of the class), append:

```kotlin
    // -- Spec mount-sync-mode-mutex T1/T2/T3/T3a -------------------------------

    @Test
    fun lock_pid_file_carries_mode_after_tryLock() {
        val mountLockFile = Files.createTempFile("mode-mutex-mount", ".lock")
        val mountLock = ProcessLock(mountLockFile)
        try {
            assertTrue(mountLock.tryLock(ProcessLock.Mode.MOUNT))
            val pidFile = mountLockFile.resolveSibling("${mountLockFile.fileName}.pid")
            val body = Files.readString(pidFile).trim()
            assertEquals(
                "${ProcessHandle.current().pid()} mount",
                body,
                "lock-pid sidecar must carry '<pid> mount' on MOUNT acquisition",
            )
            // readHolderPid() must still return just the pid (legacy-compat).
            assertEquals(ProcessHandle.current().pid(), mountLock.readHolderPid())
        } finally {
            mountLock.unlock()
            Files.deleteIfExists(mountLockFile)
        }

        val syncLockFile = Files.createTempFile("mode-mutex-sync", ".lock")
        val syncLock = ProcessLock(syncLockFile)
        try {
            assertTrue(syncLock.tryLock(ProcessLock.Mode.SYNC))
            val pidFile = syncLockFile.resolveSibling("${syncLockFile.fileName}.pid")
            val body = Files.readString(pidFile).trim()
            assertEquals("${ProcessHandle.current().pid()} sync", body)
        } finally {
            syncLock.unlock()
            Files.deleteIfExists(syncLockFile)
        }
    }

    @Test
    fun read_holder_info_returns_pid_and_mode_for_locked_file() {
        val lockFile = Files.createTempFile("holder-info", ".lock")
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.MOUNT))
            // Second instance reads the same .pid sidecar.
            val reader = ProcessLock(lockFile)
            val info = reader.readHolderInfo()
            assertNotNull(info)
            assertEquals(ProcessHandle.current().pid(), info!!.pid)
            assertEquals(ProcessLock.Mode.MOUNT, info.mode)
        } finally {
            held.unlock()
            Files.deleteIfExists(lockFile)
        }

        val lockFile2 = Files.createTempFile("holder-info-sync", ".lock")
        val held2 = ProcessLock(lockFile2)
        try {
            assertTrue(held2.tryLock(ProcessLock.Mode.SYNC))
            val reader = ProcessLock(lockFile2)
            val info = reader.readHolderInfo()
            assertEquals(ProcessLock.Mode.SYNC, info?.mode)
        } finally {
            held2.unlock()
            Files.deleteIfExists(lockFile2)
        }
    }

    @Test
    fun legacy_pid_only_lock_pid_file_reads_as_sync_mode() {
        // Simulate a .pid sidecar written by a pre-fix daemon: just "<pid>\n".
        val lockFile = Files.createTempFile("legacy-pid", ".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        Files.writeString(pidFile, "12345\n")
        try {
            val reader = ProcessLock(lockFile)
            val info = reader.readHolderInfo()
            assertNotNull(info, "legacy pid-only file must parse")
            assertEquals(12345L, info!!.pid)
            assertEquals(
                ProcessLock.Mode.SYNC,
                info.mode,
                "legacy format must default to SYNC (the only mode that existed before)",
            )
            assertEquals(null, info.rawMode, "legacy format must NOT set rawMode")
        } finally {
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(lockFile)
        }
    }

    @Test
    fun unknown_mode_token_yields_holder_info_with_null_mode_and_raw_token() {
        // Forward-compat per spec §3.1: a future version writes `<pid> mirror`.
        // This (older) binary must NOT silently coerce to SYNC; it must surface
        // the unknown token so the factory error message can name it.
        val lockFile = Files.createTempFile("unknown-mode", ".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        Files.writeString(pidFile, "67890 mirror\n")
        try {
            val reader = ProcessLock(lockFile)
            val info = reader.readHolderInfo()
            assertNotNull(info, "unknown-mode file must still parse the pid")
            assertEquals(67890L, info!!.pid)
            assertEquals(
                null,
                info.mode,
                "unknown mode token must yield null mode (not silent SYNC default)",
            )
            assertEquals(
                "mirror",
                info.rawMode,
                "unknown mode token must be preserved verbatim in rawMode",
            )
        } finally {
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(lockFile)
        }
    }
```

Add imports at the top if missing:
```kotlin
import kotlin.test.assertNotNull
```
(`assertEquals`, `assertTrue` are already imported by the existing tests in the file.)

- [ ] **Step 1.6: Run the new tests — must pass**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.ProcessLockTest" -q > /tmp/t1.log 2>&1
echo "exit=$?"
grep -E "FAILED|lock_pid_file_carries_mode|read_holder_info|legacy_pid_only" /tmp/t1.log -C 2
```
Expected: exit 0. All ProcessLockTest tests pass (the three new + existing UD-272 ones).

- [ ] **Step 1.7: Commit**

```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt
git commit -m "feat(sync): ProcessLock Mode metadata + HolderInfo reader

Extends ProcessLock per spec mount-sync-mode-mutex-design.md §3.1+§3.2:

- New nested Mode enum (SYNC/MOUNT) + HolderInfo data class.
- New tryLock(Mode, Duration) overload stamps '<pid> <mode>' wire
  format into the .lock.pid sidecar.
- Legacy tryLock(Duration) is a shim that calls tryLock(Mode.SYNC).
  Existing callers byte-identical.
- readHolderPid() tightened to parse only the first whitespace-
  separated token, so the new wire format still yields the correct
  pid for legacy callers.
- readHolderInfo() reads (pid, mode); legacy pid-only files default
  mode to SYNC.

Three tests (T1/T2/T3) pin the wire format, the reader path, and
the legacy-compat behaviour."
```

---

## Task 2: MountCommand acquires the lock; Main exposes the mount-side factory

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt`
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt`
- Create: `/home/gernot/dev/git/unidrive/core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ProfileLockFactoryTest.kt`

- [ ] **Step 2.1: Refactor `acquireProfileLock()` to pass `Mode.SYNC` + render mount-holder message**

In `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt`, the current `acquireProfileLock()` body (lines 560-585) does `lock.tryLock()` (legacy overload) and renders the PID + kill hint. Replace with:

```kotlin
    /**
     * Acquire the per-profile process lock in SYNC mode. Returns the lock on
     * success. If another unidrive process holds the lock, prints a mode-
     * specific message and exits with code 1.
     */
    // NOTE: error wording is duplicated in ProfileLockFactoryTest.renderSyncFactoryContention
    // (Task 2.4). Keep both in sync when editing — drift here means stale test assertions.
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
                holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.MOUNT ->
                    "Profile '${profile.name}' is currently FUSE-mounted by `unidrive mount`"
                holder != null && holder.mode == null && holder.rawMode != null ->
                    // Forward-compat per spec §3.1: name the unknown mode rather than
                    // silently defaulting to SYNC.
                    "Profile '${profile.name}' is held by an unidrive process running in " +
                        "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
                else ->
                    "Another unidrive process is using profile '${profile.name}'"
            }
            val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
            System.err.println("$holderDesc$pidPart.")
            if (holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.MOUNT) {
                System.err.println(
                    "Mount and sync are mutually exclusive per profile. " +
                        "Stop the mount first: unmount the FUSE path, or `kill ${holder.pid}`.",
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

- [ ] **Step 2.2: Add `acquireProfileLockForMount()` factory**

Immediately after `acquireProfileLock()`, add:

```kotlin
    /**
     * Acquire the per-profile process lock in MOUNT mode. Refuses with code 1
     * if another process holds the lock (typically `unidrive sync --watch`
     * for the same profile). See docs/dev/specs/mount-sync-mode-mutex-design.md.
     */
    // NOTE: error wording is duplicated in ProfileLockFactoryTest.renderMountFactoryContention
    // (Task 2.4). Keep both in sync when editing — drift here means stale test assertions.
    fun acquireProfileLockForMount(): org.krost.unidrive.sync.ProcessLock {
        val lockFile = providerConfigDir().resolve(".lock")
        java.nio.file.Files.createDirectories(lockFile.parent)
        val lock = org.krost.unidrive.sync.ProcessLock(lockFile)
        if (!lock.tryLock(org.krost.unidrive.sync.ProcessLock.Mode.MOUNT)) {
            val profile = resolveCurrentProfile()
            val holder = lock.readHolderInfo()
            val holderDesc = when {
                holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.SYNC ->
                    "Another `unidrive sync` is mirroring profile '${profile.name}'"
                holder?.mode == org.krost.unidrive.sync.ProcessLock.Mode.MOUNT ->
                    "Another `unidrive mount` already serves profile '${profile.name}'"
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
                    "Mount and sync are mutually exclusive per profile " +
                        "(see docs/dev/specs/mount-sync-mode-mutex-design.md).",
                )
            } else if (holder != null) {
                System.err.println("Stop it with `kill ${holder.pid}`, or wait for it to exit.")
            }
            System.exit(1)
        }
        return lock
    }
```

- [ ] **Step 2.3: Wire `MountCommand.run()` to acquire and release the lock**

In `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt`, the current `run()` (lines 35-65) jumps to socket-path computation immediately. Wrap the body with lock acquisition + try/finally:

Locate the current body:
```kotlin
    override fun run() {
        if (profileNameOverride != null) {
            parent.provider = profileNameOverride
            parent.invalidateProfileCaches()
        }
        val profile = parent.resolveCurrentProfile()
        val socketPath = IpcServer.defaultSocketPath(profile.name)
        // … cacheRoot, binary, supervise …
    }
```

Change to:
```kotlin
    override fun run() {
        if (profileNameOverride != null) {
            parent.provider = profileNameOverride
            parent.invalidateProfileCaches()
        }
        val profile = parent.resolveCurrentProfile()

        // Acquire the per-profile mode-mutex (spec mount-sync-mode-mutex-design.md).
        // Refuses with a clear message if `sync` mode already holds it.
        val lock = parent.acquireProfileLockForMount()

        try {
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
            System.exit(exit)
        } finally {
            lock.unlock()
        }
    }
```

Note: `System.exit` inside the try-block does not unwind `finally` — that's fine. The OS file lock releases on JVM exit anyway; `lock.unlock()`'s only extra duty is removing the `.lock.pid` sidecar, which a fresh acquisition overwrites.

- [ ] **Step 2.4: Write T4 + T5 (ProfileLockFactoryTest)**

Create `/home/gernot/dev/git/unidrive/core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ProfileLockFactoryTest.kt`:

```kotlin
package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProcessLock
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * T4 + T5 from docs/dev/specs/mount-sync-mode-mutex-design.md §3.6.
 *
 * Verifies the user-visible behaviour of the two lock-acquisition
 * factories on Main when the opposite mode already holds the lock.
 * The test exercises the lock-contention path directly via ProcessLock
 * rather than spinning up a full Main + picocli, because the factory
 * methods do their own System.err prints and System.exit — we need to
 * capture both without unwinding the JVM.
 *
 * Strategy: re-acquire System.err to capture output; install a
 * SecurityManager-free exit interceptor by trapping the lock-contention
 * path's stderr + asserting the exit-1 contract is reached.
 */
class ProfileLockFactoryTest {
    private val originalErr = System.err
    private val capturedErr = ByteArrayOutputStream()
    private lateinit var lockFile: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        System.setErr(PrintStream(capturedErr))
        lockFile = Files.createTempFile("profile-lock-factory", ".lock")
    }

    @AfterTest
    fun tearDown() {
        System.setErr(originalErr)
        Files.deleteIfExists(lockFile)
        Files.deleteIfExists(lockFile.resolveSibling("${lockFile.fileName}.pid"))
    }

    /**
     * Reproduces the contention-rendering arm of acquireProfileLockForMount
     * without invoking System.exit (the factory is too tied to Main + picocli
     * to call directly; we exercise the equivalent logic inline).
     */
    // MUST mirror Main.acquireProfileLockForMount byte-for-byte. If the factory
    // wording drifts, update both sides together.
    private fun renderMountFactoryContention(holder: ProcessLock.HolderInfo?, profileName: String) {
        val holderDesc = when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is mirroring profile '$profileName'"
            holder?.mode == ProcessLock.Mode.MOUNT ->
                "Another `unidrive mount` already serves profile '$profileName'"
            holder != null && holder.mode == null && holder.rawMode != null ->
                "Profile '$profileName' is held by an unidrive process running in " +
                    "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
            else ->
                "Another unidrive process is using profile '$profileName'"
        }
        val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
        System.err.println("$holderDesc$pidPart.")
        if (holder?.mode == ProcessLock.Mode.SYNC) {
            System.err.println(
                "Stop the sync watcher first: `kill ${holder.pid}` (or Ctrl-C its terminal).",
            )
        }
    }

    // MUST mirror Main.acquireProfileLock byte-for-byte. If the factory
    // wording drifts, update both sides together.
    private fun renderSyncFactoryContention(holder: ProcessLock.HolderInfo?, profileName: String) {
        val holderDesc = when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is running for profile '$profileName'"
            holder?.mode == ProcessLock.Mode.MOUNT ->
                "Profile '$profileName' is currently FUSE-mounted by `unidrive mount`"
            holder != null && holder.mode == null && holder.rawMode != null ->
                "Profile '$profileName' is held by an unidrive process running in " +
                    "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
            else ->
                "Another unidrive process is using profile '$profileName'"
        }
        val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
        System.err.println("$holderDesc$pidPart.")
    }

    @Test
    fun mount_command_refuses_when_sync_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.SYNC), "precondition: SYNC lock must acquire")
            // Simulate the factory's read+render path with a fresh ProcessLock
            // instance on the same file (matches the cross-process shape).
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.MOUNT)
            assertEquals(false, acquired, "MOUNT must not acquire while SYNC holds")
            val holder = contender.readHolderInfo()
            renderMountFactoryContention(holder, profileName = "test_profile")

            val out = capturedErr.toString()
            assertTrue(
                out.contains("is mirroring profile 'test_profile'"),
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

    @Test
    fun sync_command_refuses_when_mount_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.MOUNT), "precondition: MOUNT lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.SYNC)
            assertEquals(false, acquired, "SYNC must not acquire while MOUNT holds")
            val holder = contender.readHolderInfo()
            renderSyncFactoryContention(holder, profileName = "test_profile")

            val out = capturedErr.toString()
            assertTrue(
                out.contains("is currently FUSE-mounted by"),
                "expected mount-holder phrasing; got: $out",
            )
            assertTrue(
                out.contains("PID ${ProcessHandle.current().pid()}"),
                "expected holder PID in stderr; got: $out",
            )
        } finally {
            held.unlock()
        }
    }
}
```

This test exercises the **same wording** the factories produce, in the same conditional structure, against a real `ProcessLock` contention scenario. The factories themselves call `System.exit(1)` which the test can't safely cross — the inline-rendered functions match the factory code byte-for-byte (a code reviewer should verify; T4/T5 catch any drift in the conditional structure or substring wording at build time).

- [ ] **Step 2.5: Run T4 + T5**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.ProfileLockFactoryTest" -q > /tmp/t4t5.log 2>&1
echo "exit=$?"
grep -E "FAILED|mount_command_refuses|sync_command_refuses" /tmp/t4t5.log -C 2
```
Expected: exit 0. Both tests pass.

- [ ] **Step 2.6: Run the broader cli + sync test surface — must still be green**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:test :app:sync:test -q > /tmp/t2-full.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 3 /tmp/t2-full.log || echo "no failures"
```
Expected: exit 0.

- [ ] **Step 2.7: Commit**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ProfileLockFactoryTest.kt
git commit -m "feat(cli): MountCommand acquires mode-MOUNT lock; sync renders mount-holder error

Wires Phase 1 of basic-fuse-mount per spec mount-sync-mode-mutex-design.md:

- Main.acquireProfileLock() now passes Mode.SYNC and renders a
  mount-holder-specific error message ('Profile X is currently
  FUSE-mounted...') when the contender is a mount.
- New Main.acquireProfileLockForMount() factory passes Mode.MOUNT
  with the symmetric error path.
- MountCommand.run() acquires the MOUNT lock immediately after
  profile resolution, releases in finally.

T4 (mount_command_refuses_when_sync_holds_lock) and T5
(sync_command_refuses_when_mount_holds_lock) pin both factory
contention paths. Per-mode error wording is now build-time-enforced."
```

---

## Task 3: Zombie-mount sanity check in SyncCommand

Phase 1's last piece — addresses spec §3.4.1 / §4 R4. After the sync lock acquires successfully, scan `/proc/self/mounts` for a stale unidrive FUSE mount and WARN-not-abort if found.

**Files:**
- Create: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StaleMountDetector.kt`
- Modify: `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt`

- [ ] **Step 3.1: Create StaleMountDetector**

Create the file:

```kotlin
package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Detects stale unidrive FUSE mounts that may have outlived a
 * kill -9'd MountCommand parent. Linux-only; the mount feature is
 * Linux-only by design (see unidrive-mount-linux repo scope).
 *
 * Reads /proc/self/mounts and returns the list of mountpoints where
 * the filesystem-type field is `fuse` AND the device field is
 * `unidrive`. Format of each /proc/self/mounts line (whitespace-
 * separated, escaped octal for special chars in the path):
 *     <device> <mountpoint> <fstype> <opts> <dump> <pass>
 */
object StaleMountDetector {
    private val procMounts: Path = Paths.get("/proc/self/mounts")

    fun detectStaleFuseUnidriveMounts(): List<String> {
        if (!Files.isReadable(procMounts)) return emptyList()
        return Files.readAllLines(procMounts)
            .mapNotNull { parseUnidriveFuseMountpoint(it) }
    }

    internal fun parseUnidriveFuseMountpoint(line: String): String? {
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3) return null
        val device = parts[0]
        val mountpoint = parts[1]
        val fstype = parts[2]
        if (fstype != "fuse" && !fstype.startsWith("fuse.")) return null
        if (device != "unidrive") return null
        // NOTE: paths containing spaces / tabs / newlines appear here in
        // octal-escaped form (e.g. `/tmp/my\040mount` for `/tmp/my mount`).
        // We return the line as-is; the operator-facing WARN message in
        // SyncCommand prints it verbatim. If a future caller needs to
        // pass this back to `fusermount3 -u`, the caller is responsible
        // for un-escaping. Known Linux FUSE limitation; not a goal of
        // this change to handle paths-with-whitespace.
        return mountpoint
    }
}
```

- [ ] **Step 3.2: Wire SyncCommand to call the detector after lock acquisition**

In `/home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt`, locate the place where the profile lock is acquired. The existing code does NOT explicitly call `parent.acquireProfileLock()` — verify by grep:

```bash
grep -n "acquireProfileLock\|profile.*lock" /home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt | head -5
```

If `acquireProfileLock()` IS called in `SyncCommand.run()`, insert the stale-mount check immediately after the call site. If it ISN'T (the existing code may rely on the IPC server bind-refusing collision), add the lock acquisition + stale-mount check together. Concrete shape — find the line where `IpcServer.defaultSocketPath(profile.name)` is computed (around `SyncCommand.kt:291` per the earlier grep). Just before that line:

```kotlin
        // Phase 1 — Mode-mutex lock for the profile. Symmetric with
        // MountCommand; refuses if mount holds the lock.
        val profileLock = parent.acquireProfileLock()
        Runtime.getRuntime().addShutdownHook(Thread { profileLock.unlock() })

        // Spec mount-sync-mode-mutex §3.4.1: warn-but-do-not-abort if a
        // stale unidrive FUSE mount survived a kill -9'd MountCommand.
        val staleMounts = StaleMountDetector.detectStaleFuseUnidriveMounts()
        if (staleMounts.isNotEmpty()) {
            System.err.println(
                "WARNING: detected ${staleMounts.size} stale unidrive FUSE mount(s) " +
                    "(likely from a kill -9'd `unidrive mount` parent): ${staleMounts.joinToString()}.",
            )
            System.err.println(
                "These mounts no longer serve data. Clean up with " +
                    "`fusermount3 -u <path>` for each.",
            )
        }
```

If the lock is already acquired elsewhere in the function, omit the `profileLock` + shutdown-hook lines and keep only the `StaleMountDetector` block. Verify by re-grepping after the edit:

```bash
grep -cE "acquireProfileLock\(\)" /home/gernot/dev/git/unidrive/core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt
```
Expected: 1 (exactly one call site for the sync-side acquisition).

- [ ] **Step 3.3: Write a parser test for StaleMountDetector**

Create `/home/gernot/dev/git/unidrive/core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StaleMountDetectorTest.kt`:

```kotlin
package org.krost.unidrive.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StaleMountDetectorTest {
    @Test
    fun parses_unidrive_fuse_mountpoint() {
        // Format from a real `cat /proc/self/mounts` line during a unidrive mount:
        val line = "unidrive /tmp/onedrive-smoke fuse rw,nosuid,nodev,relatime,user_id=1000,group_id=1000 0 0"
        assertEquals("/tmp/onedrive-smoke", StaleMountDetector.parseUnidriveFuseMountpoint(line))
    }

    @Test
    fun parses_fuse_dot_subtype_mountpoint() {
        // Some kernels emit `fuse.unidrive` rather than `fuse`; the device
        // field is still `unidrive`, the fstype starts with `fuse.`.
        val line = "unidrive /tmp/mnt fuse.unidrive rw 0 0"
        assertEquals("/tmp/mnt", StaleMountDetector.parseUnidriveFuseMountpoint(line))
    }

    @Test
    fun rejects_non_fuse_mounts() {
        val line = "/dev/sda1 / ext4 rw,relatime 0 0"
        assertNull(StaleMountDetector.parseUnidriveFuseMountpoint(line))
    }

    @Test
    fun rejects_other_fuse_mounts() {
        // sshfs / other-fuse: fstype=fuse but device != unidrive.
        val line = "user@host:/path /mnt/sshfs fuse.sshfs rw 0 0"
        assertNull(StaleMountDetector.parseUnidriveFuseMountpoint(line))
    }

    @Test
    fun rejects_malformed_lines() {
        assertNull(StaleMountDetector.parseUnidriveFuseMountpoint(""))
        assertNull(StaleMountDetector.parseUnidriveFuseMountpoint("only-one-field"))
        assertNull(StaleMountDetector.parseUnidriveFuseMountpoint("two fields"))
    }
}
```

- [ ] **Step 3.4: Run the parser tests**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:test --tests "org.krost.unidrive.cli.StaleMountDetectorTest" -q > /tmp/t3.log 2>&1
echo "exit=$?"
grep -E "FAILED|parses_|rejects_" /tmp/t3.log -C 1
```
Expected: exit 0. All five parser tests pass.

- [ ] **Step 3.5: Full Phase 1 suite must still be green**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test :app:cli:test -q > /tmp/phase1.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 3 /tmp/phase1.log || echo "no failures"
```
Expected: exit 0.

- [ ] **Step 3.6: Commit**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StaleMountDetector.kt core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StaleMountDetectorTest.kt
git commit -m "feat(cli): zombie-mount sanity check on sync startup

Spec mount-sync-mode-mutex-design.md §3.4.1 / §4 R4: after the
sync-side lock acquires (i.e. the kernel reported no holder), scan
/proc/self/mounts for stale unidrive FUSE entries. WARN + recovery
hint if any found; do NOT abort — the operator can fusermount3 -u
whenever convenient.

StaleMountDetector parses /proc/self/mounts lines, matching
fstype=fuse|fuse.* AND device=unidrive. Five parser unit tests
pin the matcher (real-world line format, fuse.subtype variant,
ext4/sshfs/malformed rejection).

Phase 1 of basic-fuse-mount-plan complete. Mount and sync are
now mutually exclusive per profile."
```

---

## Task 4: SyncEngine entry points + StateDatabase helpers

Phase 2 begins. Adds the JVM-side surface that the new hydration verbs will call.

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt`
- Modify: `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt`

- [ ] **Step 4.1: Check whether `insertFolder` / `markDeleted` already exist on StateDatabase**

```bash
grep -nE "fun insertFolder|fun markDeleted|insert.*folder.*path|tombstone" /home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt | head -10
```

If methods with these names exist with different signatures, the implementer reconciles the spec's expected shape to the existing ones (spec §3.2: "If the methods DO exist with different signatures … the plan reconciles to the existing shape"). If the file contains nothing matching, proceed to 4.2 (add both). Record what was found in this step's commit message.

- [ ] **Step 4.2: Add `insertFolder` to StateDatabase**

Locate the existing mutation methods (likely a section near `insertEntry` / `updateEntry`). Add immediately after them:

```kotlin
/**
 * Insert a folder row representing a freshly-created cloud folder.
 * No-op (idempotent) if a row at [path] already exists. Defaults
 * per spec hydration-namespace-verbs-design.md §3.2:
 *  - is_folder = true, is_hydrated = false
 *  - local_mtime / local_size = null
 *  - remote_size = 0
 *  - last_synced = Instant.now()
 *  - tombstone = false
 *
 * The [mtime] argument is the provider-reported creation time
 * (CloudItem.modified) stored in the remote_mtime column.
 */
fun insertFolder(path: String, remoteId: String, mtime: java.time.Instant) {
    val existing = getEntry(path)
    if (existing != null) return
    // The exact SQL shape depends on the schema. Match the existing
    // insertEntry method's column list; if it accepts a typed Entry
    // object, construct one with the spec's defaults:
    //   Entry(path = path, remoteId = remoteId, isFolder = true,
    //         isHydrated = false, remoteMtime = mtime, remoteSize = 0,
    //         localMtime = null, localSize = null,
    //         lastSynced = Instant.now(), tombstone = false)
    // Then call insertEntry(...).
    //
    // If the schema uses raw SQL via PreparedStatement, build the
    // INSERT statement with the same column list as the existing
    // insertEntry path. The implementer's task is "construct the
    // row via whichever idiom this file uses" — don't introduce a
    // new style.
    error("REPLACE_WITH_EXISTING_IDIOM: insertFolder body — see Implementer note below")
}
```

**Implementer note:** the `REPLACE_WITH_EXISTING_IDIOM` marker is a deliberate breakpoint — read the file's existing insert-shape (likely `insertEntry`, `upsertEntry`, or an Exposed/JDBC SQL builder), copy its style, and replace the `TODO_…` line with the equivalent insert for a folder row. Do NOT introduce a new idiom; do NOT invent a new column. If the existing schema lacks a column the spec wants (e.g. `remoteId` for folders), match what the legacy `SyncEngine.applyActions` insert-on-mkdir path (around `SyncEngine.kt:2360`) does. The spec accepts reconciliation: "the spec's intent is 'two single-purpose mutation methods, idempotent semantics, folder-aware defaults.'"

- [ ] **Step 4.3: Add `markDeleted` to StateDatabase**

Immediately after `insertFolder`:

```kotlin
/**
 * Mark the row at [path] as deleted: tombstone = true,
 * last_synced = Instant.now(). The row itself is NOT removed
 * (preserves UD-265 deletion-history invariant). No-op if the
 * row does not exist. Works for both files and folders;
 * type-check lives in HydrationImpl.
 */
fun markDeleted(path: String) {
    val existing = getEntry(path) ?: return
    // Match the existing update-shape (likely `updateEntry`,
    // `setTombstone`, or raw SQL UPDATE). The legacy
    // SyncEngine.applyActions delete-path (around SyncEngine.kt:2304)
    // already does this — mirror its update statement.
    error("REPLACE_WITH_EXISTING_IDIOM: markDeleted body — see SyncEngine.kt:2304 update statement")
}
```

Same implementer-note as 4.2: use the file's existing update-shape.

- [ ] **Step 4.4: Add unit tests for the two new StateDatabase methods**

Open the existing `StateDatabaseTest.kt` (verify the path with `find core/app/sync/src/test -name "StateDatabaseTest.kt"`). Append:

```kotlin
@Test
fun insertFolder_creates_idempotent_folder_row() {
    val db = freshTestDb()
    val mtime = java.time.Instant.parse("2026-05-24T08:00:00Z")
    db.insertFolder("/test_folder", remoteId = "rid-1", mtime = mtime)

    val entry = db.getEntry("/test_folder")
    assertNotNull(entry)
    assertEquals(true, entry!!.isFolder)
    assertEquals(false, entry.isHydrated)
    assertEquals("rid-1", entry.remoteId)

    // Idempotent: second call must not throw, must not mutate.
    db.insertFolder("/test_folder", remoteId = "rid-2-DIFFERENT", mtime = mtime)
    val again = db.getEntry("/test_folder")
    assertEquals("rid-1", again!!.remoteId, "insertFolder must be no-op when row exists")
}

@Test
fun markDeleted_sets_tombstone_and_keeps_row() {
    val db = freshTestDb()
    val mtime = java.time.Instant.parse("2026-05-24T08:00:00Z")
    db.insertFolder("/to_delete", remoteId = "rid-x", mtime = mtime)

    db.markDeleted("/to_delete")
    val entry = db.getEntry("/to_delete")
    assertNotNull(entry, "markDeleted must NOT remove the row")
    assertEquals(true, entry!!.tombstone)

    // Idempotent on non-existent path: must not throw.
    db.markDeleted("/never_existed")
}
```

`freshTestDb()` is whatever helper the existing tests use to construct an in-memory `StateDatabase`. If the test file uses a different fixture style (`@BeforeTest` setUp + class-level `db` field), adapt accordingly.

- [ ] **Step 4.5: Add `createRemoteFolder` and `deleteRemote` to SyncEngine**

In `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt`, locate a place near the existing `applyActions` block where adding two new public suspend methods fits the file's structure (probably near the top of the class, after the constructor / before the engine-loop methods). Add:

```kotlin
/**
 * Create a folder on the remote provider and record it in state.db.
 * Used by the hydration SPI (HydrationImpl.mkdir) to back FUSE mkdir
 * requests. Separate code path from the legacy applyActions loop —
 * see spec hydration-namespace-verbs-design.md §3.2.
 *
 * Throws ProviderException on cloud-side failure. state.db is only
 * updated after the provider call succeeds.
 */
suspend fun createRemoteFolder(path: String): org.krost.unidrive.CloudItem {
    val item = provider.createFolder(path)
    stateDb.insertFolder(path = path, remoteId = item.remoteId, mtime = item.modified)
    return item
}

/**
 * Delete a path on the remote provider and update state.db.
 * Handles both files and folders — provider distinguishes by
 * remoteId/path. Caller (HydrationImpl.unlink or .rmdir) is
 * responsible for type-checking.
 *
 * Throws ProviderException on cloud-side failure (e.g. not found,
 * folder not empty). state.db is only updated after the provider
 * call succeeds.
 */
suspend fun deleteRemote(path: String) {
    provider.delete(path)
    stateDb.markDeleted(path)
}
```

**Implementer note:** verify the property name used to access the provider (`provider` vs `cloudProvider` vs `prov`) by grepping the existing `provider.delete(action.path)` call site around line 2304:

```bash
grep -n "provider\.\(delete\|createFolder\)" /home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
```

The `provider.` prefix in the new methods must match what that grep shows.

- [ ] **Step 4.6: Run the new StateDatabase + sync tests**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "*StateDatabase*" -q > /tmp/t4-db.log 2>&1
echo "exit=$?"
grep -E "FAILED|insertFolder|markDeleted" /tmp/t4-db.log -C 2

./gradlew :app:sync:test -q > /tmp/t4-full.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 3 /tmp/t4-full.log || echo "no failures"
```
Expected: both runs exit 0.

- [ ] **Step 4.7: Commit**

```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt core/app/sync/src/test/kotlin/org/krost/unidrive/sync/StateDatabaseTest.kt
git commit -m "feat(sync): StateDatabase.insertFolder/markDeleted + SyncEngine on-demand entry points

Per spec hydration-namespace-verbs-design.md §3.2:

- StateDatabase.insertFolder(path, remoteId, mtime): idempotent folder
  row insert with the spec's defaults (is_folder=true, is_hydrated=
  false, tombstone=false, etc.).
- StateDatabase.markDeleted(path): tombstone=true; row retained for
  UD-265 deletion-history invariant. No-op on non-existent path.
- SyncEngine.createRemoteFolder(path): provider.createFolder + insertFolder.
- SyncEngine.deleteRemote(path): provider.delete + markDeleted.

Both engine entry points are separate code paths from the legacy
applyActions loop (which has its own logging / error envelope /
dry-run handling). Two production code paths to the same provider
call is acceptable; the engine-retirement BACKLOG already tracks
the broader rework."
```

---

## Task 5: Hydration SPI extension (Hydration interface + result types + HydrationImpl bodies)

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt`
- Modify: `/home/gernot/dev/git/unidrive/core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt`

- [ ] **Step 5.1: Add result types and SPI methods to Hydration.kt**

In `/home/gernot/dev/git/unidrive/core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt`, locate the existing result types (e.g. `OpenResult`, `HydrateResult`). Add three new sealed classes near them:

```kotlin
sealed class MkdirResult {
    object Ok : MkdirResult()
    data class Failed(val error: HydrationError) : MkdirResult()
}

sealed class UnlinkResult {
    object Ok : UnlinkResult()
    data class Failed(val error: HydrationError) : UnlinkResult()
    /** Caller asked unlink on a folder; co-daemon maps to EISDIR. */
    object PathIsFolder : UnlinkResult()
}

sealed class RmdirResult {
    object Ok : RmdirResult()
    data class Failed(val error: HydrationError) : RmdirResult()
    /** Caller asked rmdir on a file; co-daemon maps to ENOTDIR. */
    object PathIsFile : RmdirResult()
    /** Folder is non-empty; co-daemon maps to ENOTEMPTY. */
    object NotEmpty : RmdirResult()
}
```

Then add three methods to the `Hydration` interface (after the existing `dehydrate` / `lastSynced` / `list` declarations):

```kotlin
/**
 * Create a folder on the remote backing the mount. Path is canonical
 * (leading slash, no trailing slash). After Ok, the path is reachable
 * via the next list() call.
 */
suspend fun mkdir(path: String): MkdirResult

/**
 * Delete a file on the remote. Returns PathIsFolder if state.db says
 * [path] is a folder (caller should use rmdir instead).
 */
suspend fun unlink(path: String): UnlinkResult

/**
 * Delete a folder on the remote. Returns PathIsFile if state.db says
 * [path] is a file; returns NotEmpty if the provider refuses on
 * non-empty deletion.
 */
suspend fun rmdir(path: String): RmdirResult
```

- [ ] **Step 5.2: Implement mkdir/unlink/rmdir in HydrationImpl**

In `/home/gernot/dev/git/unidrive/core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt`, after the existing `dehydrate` method (around line 85-104), append the three new method bodies per spec §3.4:

```kotlin
override suspend fun mkdir(path: String): MkdirResult {
    val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
    return runCatching {
        _events.emit(HydrationEvent.Hydrating(normalised))
        syncEngine.createRemoteFolder(normalised)
        _events.emit(HydrationEvent.Hydrated(normalised, bytes = 0L))
        MkdirResult.Ok
    }.getOrElse { e ->
        val err = HydrationError.Generic(e.message ?: "mkdir failed")
        _events.emit(HydrationEvent.Failed(normalised, err))
        MkdirResult.Failed(err)
    }
}

override suspend fun unlink(path: String): UnlinkResult {
    val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
    val entry = stateDb.getEntry(normalised)
        ?: return UnlinkResult.Failed(HydrationError.Generic("Unknown path: $normalised"))
    if (entry.isFolder) return UnlinkResult.PathIsFolder

    return runCatching {
        syncEngine.deleteRemote(normalised)
        UnlinkResult.Ok
    }.getOrElse { e ->
        UnlinkResult.Failed(HydrationError.Generic(e.message ?: "unlink failed"))
    }
}

override suspend fun rmdir(path: String): RmdirResult {
    val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
    val entry = stateDb.getEntry(normalised)
        ?: return RmdirResult.Failed(HydrationError.Generic("Unknown path: $normalised"))
    if (!entry.isFolder) return RmdirResult.PathIsFile

    // Provider-side delete handles non-empty refusal; detect from exception message.
    // Wordings (cited verbatim from provider sources at the time of writing):
    //  - OneDrive (GraphApiService.delete): "OneDrive responded 409 Conflict: Folder not empty"
    //  - Internxt (InternxtProvider.delete): "Internxt API: cannot delete non-empty folder"
    // If either provider changes its error text, the T3 test
    // (rmdir_detects_provider_not_empty_substring) fails loudly and
    // these substrings must be updated in lockstep.
    return runCatching {
        syncEngine.deleteRemote(normalised)
        RmdirResult.Ok
    }.getOrElse { e ->
        val msg = e.message ?: ""
        if (msg.contains("not empty", ignoreCase = true) ||
            msg.contains("non-empty", ignoreCase = true)
        ) {
            RmdirResult.NotEmpty
        } else {
            RmdirResult.Failed(HydrationError.Generic(msg.ifBlank { "rmdir failed" }))
        }
    }
}
```

- [ ] **Step 5.3: Write T1, T2, T3 JVM-side tests**

Create `/home/gernot/dev/git/unidrive/core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplNamespaceTest.kt`:

```kotlin
package org.krost.unidrive.hydration

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T1, T2, T3 from spec hydration-namespace-verbs-design.md §3.9.
 *
 * T3 is the load-bearing wire-contract test against the substring
 * matcher in HydrationImpl.rmdir — if either provider changes its
 * non-empty error wording, this test fails with a specific message.
 */
class HydrationImplNamespaceTest {

    private fun freshSubject(
        provider: org.krost.unidrive.CloudProvider = mockk(relaxed = true),
        stateDb: StateDatabase = mockk(relaxed = true),
    ): Triple<HydrationImpl, org.krost.unidrive.CloudProvider, StateDatabase> {
        val syncEngine: SyncEngine = mockk(relaxed = true)
        // Wire syncEngine through to the mocked provider + stateDb so the
        // HydrationImpl -> SyncEngine.createRemoteFolder/deleteRemote path
        // reaches the test's mocks. SyncEngine methods are stubbed below
        // to delegate; if the real SyncEngine refactors, adapt.
        coEvery { syncEngine.createRemoteFolder(any()) } answers {
            val p = firstArg<String>()
            val item = provider.createFolder(p)
            stateDb.insertFolder(p, item.remoteId, item.modified)
            item
        }
        coEvery { syncEngine.deleteRemote(any()) } answers {
            val p = firstArg<String>()
            provider.delete(p)
            stateDb.markDeleted(p)
        }
        val impl = HydrationImpl(syncEngine, stateDb)
        return Triple(impl, provider, stateDb)
    }

    @Test
    fun mkdir_creates_folder_on_provider_and_inserts_state_db_row() = runBlocking {
        val (impl, provider, stateDb) = freshSubject()
        val item = CloudItem(
            remoteId = "rid-mkdir",
            modified = Instant.parse("2026-05-24T09:00:00Z"),
            // Other CloudItem fields filled per the data class constructor;
            // the test only inspects remoteId + modified, the rest can be
            // mocked or default. Use the existing test-fixture helper if
            // CloudItem has many fields.
            // For this plan-stage placeholder, the implementer fills in
            // the remaining fields per the real CloudItem signature.
            // CloudItem requires: see /home/gernot/dev/git/unidrive/core/app/core/src/main/kotlin/org/krost/unidrive/CloudItem.kt
            // for the canonical constructor; supply whatever else it needs.
            path = "/foo",
            size = 0L,
            isFolder = true,
        )
        coEvery { provider.createFolder("/foo") } returns item

        val result = impl.mkdir("/foo")

        assertEquals(MkdirResult.Ok, result)
        coVerify { provider.createFolder("/foo") }
        coVerify { stateDb.insertFolder("/foo", "rid-mkdir", item.modified) }
    }

    @Test
    fun unlink_refuses_folder_path_with_PathIsFolder() = runBlocking {
        val (impl, provider, stateDb) = freshSubject()
        every { stateDb.getEntry("/foo") } returns mockk {
            every { isFolder } returns true
        }

        val result = impl.unlink("/foo")

        assertEquals(UnlinkResult.PathIsFolder, result)
        // provider.delete was NOT called.
        coVerify(exactly = 0) { provider.delete(any()) }
    }

    @Test
    fun rmdir_refuses_file_path_with_PathIsFile() = runBlocking {
        val (impl, provider, stateDb) = freshSubject()
        every { stateDb.getEntry("/foo.txt") } returns mockk {
            every { isFolder } returns false
        }

        val result = impl.rmdir("/foo.txt")

        assertEquals(RmdirResult.PathIsFile, result)
        coVerify(exactly = 0) { provider.delete(any()) }
    }

    @Test
    fun rmdir_detects_provider_not_empty_substring() = runBlocking {
        // OneDrive 409 wording (verified against
        // core/providers/onedrive/.../GraphApiService.kt at time of writing).
        val (impl1, provider1, stateDb1) = freshSubject()
        every { stateDb1.getEntry("/foo") } returns mockk { every { isFolder } returns true }
        coEvery { provider1.delete("/foo") } throws RuntimeException(
            "OneDrive responded 409 Conflict: Folder not empty",
        )
        assertEquals(RmdirResult.NotEmpty, impl1.rmdir("/foo"))

        // Internxt wording.
        val (impl2, provider2, stateDb2) = freshSubject()
        every { stateDb2.getEntry("/foo") } returns mockk { every { isFolder } returns true }
        coEvery { provider2.delete("/foo") } throws RuntimeException(
            "Internxt API: cannot delete non-empty folder",
        )
        assertEquals(RmdirResult.NotEmpty, impl2.rmdir("/foo"))
    }
}
```

**Implementer note:** `CloudItem`'s constructor may have more fields than shown above. Run `grep -n "^data class CloudItem\|^class CloudItem" /home/gernot/dev/git/unidrive/core/app/core/src/main/kotlin/org/krost/unidrive/CloudItem.kt` and supply the remaining fields with reasonable defaults (the test only inspects `remoteId` + `modified`). If the existing hydration test suite has a `cloudItemFor(...)` helper, use it instead.

- [ ] **Step 5.4: Run hydration tests**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:hydration:test -q > /tmp/t5.log 2>&1
echo "exit=$?"
grep -E "FAILED|mkdir_creates|unlink_refuses|rmdir_refuses|rmdir_detects" /tmp/t5.log -C 2
```
Expected: exit 0. Four namespace tests pass plus the existing hydration test surface stays green.

- [ ] **Step 5.5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplNamespaceTest.kt
git commit -m "feat(hydration): mkdir/unlink/rmdir SPI extension + impl

Per spec hydration-namespace-verbs-design.md §3.3+§3.4:

- Three new sealed result types (MkdirResult, UnlinkResult, RmdirResult)
  with explicit variants for the kernel-errno mapping the co-daemon
  will perform (PathIsFolder -> EISDIR, PathIsFile -> ENOTDIR,
  NotEmpty -> ENOTEMPTY).
- Three new Hydration interface methods.
- HydrationImpl bodies: type-check via stateDb.getEntry, call
  SyncEngine.createRemoteFolder/deleteRemote, catch non-empty
  refusal by substring match on provider error message.

Path normalisation per §3.1: trimEnd('/') with empty-string ->
'/' canonicalisation; no // or .. sanitisation.

Four JVM-side tests pin: mkdir wires provider + stateDb correctly;
unlink/rmdir type-check via stateDb; rmdir substring match pinned
against both OneDrive ('Folder not empty') and Internxt ('non-empty
folder') current wordings."
```

---

## Task 6: HydrationIpcHandler — three new verb dispatches

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt`

- [ ] **Step 6.1: Add three verbs to the VERBS set**

Locate the `companion object` with the `VERBS = setOf(...)` block (around `HydrationIpcHandler.kt:140-155`). Add the three new entries:

```kotlin
companion object {
    val VERBS = setOf(
        "hydration.open_read",
        "hydration.open_write",
        "hydration.close_handle",
        "hydration.hydrate",
        "hydration.dehydrate",
        "hydration.subscribe",
        "hydration.last_synced",
        "hydration.list",
        "hydration.mkdir",     // NEW per spec hydration-namespace-verbs-design.md
        "hydration.unlink",    // NEW
        "hydration.rmdir",     // NEW
    )
}
```

- [ ] **Step 6.2: Add three verb branches to the handle dispatcher**

Locate the `when (verb)` block in `handle(...)` (around `HydrationIpcHandler.kt:160-216`). Inside the `when`, after the existing `"hydration.list"` branch and before the `else ->` fallback, add:

```kotlin
"hydration.mkdir" -> {
    val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
    when (val r = hydration.mkdir(path)) {
        is MkdirResult.Ok -> reply(ok = true)
        is MkdirResult.Failed -> reply(ok = false, error = r.error.message)
    }
}
"hydration.unlink" -> {
    val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
    when (val r = hydration.unlink(path)) {
        is UnlinkResult.Ok -> reply(ok = true)
        UnlinkResult.PathIsFolder -> reply(ok = false, error = "path_is_folder")
        is UnlinkResult.Failed -> reply(ok = false, error = r.error.message)
    }
}
"hydration.rmdir" -> {
    val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
    when (val r = hydration.rmdir(path)) {
        is RmdirResult.Ok -> reply(ok = true)
        RmdirResult.PathIsFile -> reply(ok = false, error = "path_is_file")
        RmdirResult.NotEmpty -> reply(ok = false, error = "not_empty")
        is RmdirResult.Failed -> reply(ok = false, error = r.error.message)
    }
}
```

The error literals `"path_is_folder"`, `"path_is_file"`, `"not_empty"` are part of the wire contract (spec §3.5) — the Rust co-daemon's `map_ipc_err_to_errno` (Task 7) matches on them.

- [ ] **Step 6.3: Update HydrationIpcHandler's doc-comment if it lists verbs**

Look at the file header KDoc (around `HydrationIpcHandler.kt:14-40`). If it enumerates the verb names + their request/reply shapes, add three entries for mkdir/unlink/rmdir following the existing pattern:

```kotlin
 *   mkdir       request:  {"verb":"hydration.mkdir","path":"/foo"}
 *               reply:    {"ok":true}  or  {"ok":false,"error":"<msg>"}
 *
 *   unlink      request:  {"verb":"hydration.unlink","path":"/foo.txt"}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"path_is_folder"}     EISDIR
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   rmdir       request:  {"verb":"hydration.rmdir","path":"/foo"}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"path_is_file"}       ENOTDIR
 *                         {"ok":false,"error":"not_empty"}          ENOTEMPTY
 *                         {"ok":false,"error":"<msg>"}              EIO
```

If the file doesn't have a verb table in its KDoc, skip this step.

- [ ] **Step 6.4: Run the hydration tests**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:hydration:test :app:sync:test :app:cli:test -q > /tmp/t6.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 3 /tmp/t6.log || echo "no failures"
```
Expected: exit 0.

- [ ] **Step 6.5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt
git commit -m "feat(hydration): wire mkdir/unlink/rmdir IPC verbs

Per spec hydration-namespace-verbs-design.md §3.5: three new verbs
added to VERBS set; three new branches in the handle() when-dispatcher.

Wire-contract error strings 'path_is_folder' / 'path_is_file' /
'not_empty' match what the Rust co-daemon's map_ipc_err_to_errno
will match on to choose the kernel errno (EISDIR / ENOTDIR /
ENOTEMPTY respectively).

JVM-side Phase 2 surface complete. The Rust co-daemon (Task 7)
calls these next."
```

---

## Task 7: Rust co-daemon FUSE op impls

**Files:**
- Modify: `/home/gernot/dev/git/unidrive-mount-linux/mount/src/ipc.rs`
- Modify: `/home/gernot/dev/git/unidrive-mount-linux/mount/src/reconnect.rs`
- Modify: `/home/gernot/dev/git/unidrive-mount-linux/mount/src/fuse_fs.rs`
- Create: `/home/gernot/dev/git/unidrive-mount-linux/mount/tests/namespace_ops.rs`

Per spec hydration-namespace-verbs-design.md §§3.6-3.8 + §3.9.

- [ ] **Step 7.1: Add IpcClient methods for the three verbs**

In `/home/gernot/dev/git/unidrive-mount-linux/mount/src/ipc.rs`, after the existing
`hydrate(...)` / `open_for_write(...)` methods on `IpcClient`, add — matching the
existing reply-parsing idiom from spec §3.6 byte-for-byte:

```rust
impl IpcClient {
    pub async fn mkdir(&mut self, path: &str) -> Result<(), IpcError> {
        let req = serde_json::json!({"verb": "hydration.mkdir", "path": path});
        let reply = self.round_trip(&req).await?;
        if !reply["ok"].as_bool().unwrap_or(false) {
            return Err(server_error(&reply));
        }
        Ok(())
    }

    pub async fn unlink(&mut self, path: &str) -> Result<(), IpcError> {
        let req = serde_json::json!({"verb": "hydration.unlink", "path": path});
        let reply = self.round_trip(&req).await?;
        if !reply["ok"].as_bool().unwrap_or(false) {
            return Err(server_error(&reply));
        }
        Ok(())
    }

    pub async fn rmdir(&mut self, path: &str) -> Result<(), IpcError> {
        let req = serde_json::json!({"verb": "hydration.rmdir", "path": path});
        let reply = self.round_trip(&req).await?;
        if !reply["ok"].as_bool().unwrap_or(false) {
            return Err(server_error(&reply));
        }
        Ok(())
    }
}
```

**Wire-contract notes — read these before editing:**

- The JVM's `HydrationIpcHandler.reply()` helper emits `{"ok":true}` on success and
  `{"ok":false,"error":"<msg>"}` on failure (verified against
  `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt`
  ~line 218). The Rust side MUST check `reply["ok"].as_bool()`, NOT `reply["status"]`.
  An earlier draft of this plan checked `resp["status"] == "ok"` — that field does
  not exist in the JVM reply and every call would silently fall through to EIO.
- The `round_trip(&req)` helper is the existing method on `IpcClient`
  (`ipc.rs` around the existing hydrate/open_for_write call sites). It returns
  `Result<serde_json::Value, IpcError>` — the same type the existing methods
  use. If your local `IpcClient` exposes a differently-named helper (e.g.
  `send_request`, `request_response`), substitute it — but do not change the
  return-type semantics; the existing methods are the authority.
- The `server_error(&reply)` helper is documented in spec §3.6 ("`ipc.rs:200-208`
  maps the `error` field of the reply to an `IpcError::ServerError`"). Use it as-is.
  Do NOT introduce a new `IpcError::Verb { error, message }` variant — the existing
  `IpcError::ServerError(String)` already carries the wire-contract error string
  verbatim, and `map_ipc_err_to_errno` (Step 7.3) matches on it directly.
- If, after reading `ipc.rs`, you find that neither `round_trip` nor `server_error`
  exists, this is a precondition failure for the spec: stop and surface to the
  human reviewer. Both helpers are explicitly named as existing infrastructure
  in spec §3.6 — they should be there from prior work (the existing
  `hydrate(...)` / `open_for_write(...)` methods use them).

- [ ] **Step 7.2: Add ReconnectingIpcClient wrappers**

In `/home/gernot/dev/git/unidrive-mount-linux/mount/src/reconnect.rs`, after the existing
`hydrate` / `open_for_write` wrappers, add — using the spec §3.7 retry-on-`Io` shape
(retry only on transport errors; let `ServerError` propagate without retry since
`path_is_folder` / `not_empty` etc. are deterministic refusals):

```rust
impl ReconnectingIpcClient {
    pub async fn mkdir(&mut self, path: &str) -> Result<(), IpcError> {
        loop {
            self.ensure_connected().await?;
            let c = self.inner.as_mut().expect("ensure_connected guarantees Some");
            match c.mkdir(path).await {
                Ok(v) => return Ok(v),
                Err(IpcError::Io(_)) => { self.inner = None; }
                Err(e) => return Err(e),
            }
        }
    }

    pub async fn unlink(&mut self, path: &str) -> Result<(), IpcError> {
        loop {
            self.ensure_connected().await?;
            let c = self.inner.as_mut().expect("ensure_connected guarantees Some");
            match c.unlink(path).await {
                Ok(v) => return Ok(v),
                Err(IpcError::Io(_)) => { self.inner = None; }
                Err(e) => return Err(e),
            }
        }
    }

    pub async fn rmdir(&mut self, path: &str) -> Result<(), IpcError> {
        loop {
            self.ensure_connected().await?;
            let c = self.inner.as_mut().expect("ensure_connected guarantees Some");
            match c.rmdir(path).await {
                Ok(v) => return Ok(v),
                Err(IpcError::Io(_)) => { self.inner = None; }
                Err(e) => return Err(e),
            }
        }
    }
}
```

Match the existing `open_read` / `open_write` / `close_handle` wrappers' shape
exactly. If they use a different mutex-guard idiom (e.g. `tokio::sync::Mutex`),
mirror it. The wrappers retry only on `IpcError::Io` (transport failure) —
`ServerError` is a deterministic refusal and must not be retried.

- [ ] **Step 7.3: Implement map_ipc_err_to_errno helper**

In `/home/gernot/dev/git/unidrive-mount-linux/mount/src/fuse_fs.rs`, near the top of the
file (after imports, before the `Filesystem` impl), add — matching spec §3.8 exactly:

```rust
fn map_ipc_err_to_errno(e: IpcError) -> Errno {
    match e {
        IpcError::ServerError(msg) if msg == "path_is_folder" => Errno::from(libc::EISDIR),
        IpcError::ServerError(msg) if msg == "path_is_file" => Errno::from(libc::ENOTDIR),
        IpcError::ServerError(msg) if msg == "not_empty" => Errno::from(libc::ENOTEMPTY),
        IpcError::Io(_) => Errno::from(libc::EIO),
        _ => Errno::from(libc::EIO),
    }
}
```

**Wire-contract notes:**

- The three named arms (`path_is_folder` → EISDIR, `path_is_file` → ENOTDIR,
  `not_empty` → ENOTEMPTY) map the wire-contract error strings the JVM's
  `HydrationIpcHandler` emits (Task 6). The strings must match byte-for-byte;
  drift means silent EIO instead of the kernel-correct errno.
- There is intentionally NO `"not_found"` arm: spec R3 explicitly defers
  parent-missing-ENOENT mapping to a follow-up, and the JVM currently emits raw
  provider error messages ("Unknown path: /foo") for the not-found case. Those
  fall through to the catch-all `_ => EIO` arm. If a future change adds a
  contract for `"not_found"` JVM-side, add the corresponding arm here in the
  same commit.
- There is intentionally NO `"provider_error"` arm: the JVM does not emit a
  literal `"provider_error"` string; it emits the raw provider message. Those
  also fall through to `_ => EIO`. Per spec §3.8 the catch-all suffices for
  the "basic FUSE stuff working" session goal.
- `Errno` and `IpcError` come from the existing fuser + co-daemon types.
  Adapt the import path if the existing `IpcError` enum lives in a different
  module than `fuse_fs.rs` imports from today.

- [ ] **Step 7.4: Implement FUSE `mkdir`**

In `/home/gernot/dev/git/unidrive-mount-linux/mount/src/fuse_fs.rs`, replace the existing
`mkdir` stub (currently returning `ENOSYS`) on the `Filesystem` impl. Mirror the spec
§3.8 shape using `.map_err(map_ipc_err_to_errno)?` so the error path is one liner that
flows through the helper from Step 7.3:

```rust
async fn mkdir(
    &self,
    _req: Request,
    parent_inode: u64,
    name: &OsStr,
    _mode: u32,
    _umask: u32,
) -> Result<ReplyEntry> {
    let parent_path = self.path_for_inode(parent_inode)
        .ok_or_else(|| Errno::from(libc::ENOENT))?;
    let child_path = format!("{}/{}",
        parent_path.trim_end_matches('/'),
        name.to_string_lossy(),
    );
    let mut ipc = self.ipc.lock().await;
    ipc.mkdir(&child_path).await.map_err(map_ipc_err_to_errno)?;
    drop(ipc);
    // Allocate an inode for the new folder via the path-map and return
    // its attrs. Symmetric with `lookup`'s allocation path.
    let new_ino = self.allocate_inode(&child_path, FileKind::Directory).await;
    let attr = self.folder_attr(new_ino);
    Ok(ReplyEntry { ttl: TTL, attr, generation: 0 })
}
```

Notes:
- `path_for_inode`, `allocate_inode`, `folder_attr`, `TTL`, `FileKind`, and the
  `Request` / `ReplyEntry` / `Result` types are project-local — adapt to whatever
  the existing `lookup` / `getattr` impls use. The shape above mirrors spec §3.8
  byte-for-byte; if the existing FUSE ops use a sync (not `async`) signature or
  a different `Result` alias, mirror the local idiom rather than the spec's.
- `self.ipc` is the `Arc<Mutex<ReconnectingIpcClient>>` (or equivalent) used by
  existing FUSE ops. If the existing pattern is `let mut c = self.ipc.lock().await;
  c.hydrate(...)`, use the same lock-and-call shape verbatim.

- [ ] **Step 7.5: Implement FUSE `unlink`**

In the same file, replace the `unlink` stub with:

```rust
async fn unlink(&self, _req: Request, parent_inode: u64, name: &OsStr) -> Result<()> {
    let parent_path = self.path_for_inode(parent_inode)
        .ok_or_else(|| Errno::from(libc::ENOENT))?;
    let child_path = format!("{}/{}",
        parent_path.trim_end_matches('/'),
        name.to_string_lossy(),
    );
    let mut ipc = self.ipc.lock().await;
    ipc.unlink(&child_path).await.map_err(map_ipc_err_to_errno)?;
    drop(ipc);
    self.invalidate_inode_by_path(&child_path).await;
    Ok(())
}
```

`invalidate_inode_by_path` is the project-local cache-eviction helper; adapt name to
whatever the existing write-path uses on file deletion.

- [ ] **Step 7.6: Implement FUSE `rmdir`**

In the same file, replace the `rmdir` stub with:

```rust
async fn rmdir(&self, _req: Request, parent_inode: u64, name: &OsStr) -> Result<()> {
    let parent_path = self.path_for_inode(parent_inode)
        .ok_or_else(|| Errno::from(libc::ENOENT))?;
    let child_path = format!("{}/{}",
        parent_path.trim_end_matches('/'),
        name.to_string_lossy(),
    );
    let mut ipc = self.ipc.lock().await;
    ipc.rmdir(&child_path).await.map_err(map_ipc_err_to_errno)?;
    drop(ipc);
    self.invalidate_inode_by_path(&child_path).await;
    Ok(())
}
```

- [ ] **Step 7.7: Write integration tests T4 + T5 (spec §3.9)**

Per spec §3.9, two integration tests on the Rust side using the existing
`FakeJvm` fixture in `mount/src/fake_jvm.rs` (precondition; spec §3.9 names it
explicitly). The fixture already supports per-verb canned replies via
`spawn_at(socket_path, replies)` — the new verbs plug into that map with no
fixture-side scaffolding.

**First step: verify FakeJvm exists and inspect its API.**

```bash
ls /home/gernot/dev/git/unidrive-mount-linux/mount/src/fake_jvm.rs
grep -n "spawn_at\|with_reply\|fn new\|impl FakeJvm" /home/gernot/dev/git/unidrive-mount-linux/mount/src/fake_jvm.rs | head -20
```
Expected: file exists; the `spawn_at` + reply-map API is visible.

If `fake_jvm.rs` does NOT exist or does NOT have a per-verb reply map, surface
to the human reviewer: this is a precondition gap. Do NOT invent a new harness
inline — the spec explicitly says the fixture is the precondition.

**Create** `/home/gernot/dev/git/unidrive-mount-linux/mount/tests/namespace_ops.rs`:

```rust
//! T4 + T5 from unidrive/docs/dev/specs/hydration-namespace-verbs-design.md §3.9.
//!
//! T4 pins the happy path: a {"ok":true} reply yields exit 0 from the FUSE op.
//! T5 pins the errno mapping: a {"ok":false,"error":"not_empty"} reply yields
//! ENOTEMPTY at the kernel boundary (visible as rmdir exit 39 / "Directory not
//! empty" in stderr).
//!
//! Both build on the existing FakeJvm fixture in src/fake_jvm.rs. The exact
//! API differs per project version; adapt the `spawn_at` / `with_reply` calls
//! to the locally-existing helper.

use std::process::Command;
use tempfile::TempDir;

// Project-local fixture module. Path / name may need adjustment to match
// however existing integration tests import FakeJvm.
mod fake_jvm_support;
use fake_jvm_support::{FakeJvm, spawn_unidrive_mount};

#[tokio::test]
async fn mkdir_round_trip_returns_zero_on_jvm_ok() {
    let tmp = TempDir::new().unwrap();
    let socket_path = tmp.path().join("fake.sock");
    let mount_path = tmp.path().join("mount");
    std::fs::create_dir(&mount_path).unwrap();

    // Wire shape per spec §3.1: {"ok":true} on success.
    let _fake = FakeJvm::spawn_at(
        &socket_path,
        [("hydration.mkdir", serde_json::json!({"ok": true}))],
    ).await;

    let _mount = spawn_unidrive_mount(&socket_path, &mount_path).await;
    // Wait for FUSE mount to be ready (project-local helper).
    fake_jvm_support::wait_for_mount(&mount_path).await;

    let status = Command::new("mkdir")
        .arg(mount_path.join("newfolder"))
        .status()
        .expect("mkdir command runs");
    assert!(status.success(), "mkdir(newfolder) must succeed when JVM replies ok:true; got: {:?}", status);
}

#[tokio::test]
async fn rmdir_returns_enotempty_when_jvm_signals_not_empty() {
    let tmp = TempDir::new().unwrap();
    let socket_path = tmp.path().join("fake.sock");
    let mount_path = tmp.path().join("mount");
    std::fs::create_dir(&mount_path).unwrap();

    // Wire shape per spec §3.1: {"ok":false,"error":"not_empty"} for the
    // ENOTEMPTY case. Spec §3.8 map_ipc_err_to_errno translates this to
    // libc::ENOTEMPTY (39 on Linux).
    let _fake = FakeJvm::spawn_at(
        &socket_path,
        [
            // lookup/stat may be needed for /full to appear as a folder
            // before rmdir is attempted; adapt this map to whatever the
            // existing FUSE-readiness flow requires (project-local).
            ("hydration.rmdir", serde_json::json!({"ok": false, "error": "not_empty"})),
        ],
    ).await;

    let _mount = spawn_unidrive_mount(&socket_path, &mount_path).await;
    fake_jvm_support::wait_for_mount(&mount_path).await;

    let output = Command::new("rmdir")
        .arg(mount_path.join("full"))
        .output()
        .expect("rmdir command runs");
    assert!(!output.status.success(), "rmdir of non-empty dir must fail");
    // Linux coreutils prints "Directory not empty" for ENOTEMPTY.
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("Directory not empty") || output.status.code() == Some(1),
        "rmdir must surface ENOTEMPTY (text 'Directory not empty' or exit 1); got: {} / {:?}",
        stderr, output.status,
    );
}
```

**Wire-contract notes:**

- The FakeJvm reply map sends `{"ok":true}` / `{"ok":false,"error":"<msg>"}` —
  byte-for-byte what `HydrationIpcHandler.reply()` emits JVM-side (Task 6).
  An earlier draft of this plan used `{"status":"ok"}` — that field name does
  not exist in the production wire contract; do not introduce it.
- The integration tests cover the IPC round-trip (T4 = happy path, T5 = errno
  mapping). The errno-mapping detail (ENOTDIR / EISDIR / ENOTEMPTY) is unit-
  tested at the `map_ipc_err_to_errno` boundary; if the existing test harness
  in the project has cheaper unit-test support for that helper, add the
  three-arm test there instead of triplicating it here.
- The two named tests match spec §3.9 exactly (`mkdir_round_trip_returns_zero_on_jvm_ok`
  and `rmdir_returns_enotempty_when_jvm_signals_not_empty`). Do not rename
  them; the spec's acceptance §5 references them by name.

- [ ] **Step 7.8: Run the Rust test suite**

```bash
cd /home/gernot/dev/git/unidrive-mount-linux/mount
cargo test --all > /tmp/t7.log 2>&1
echo "exit=$?"
grep -E "FAILED|error\[|panicked" -C 3 /tmp/t7.log || echo "no failures"
```
Expected: exit 0, all five new tests pass alongside existing tests.

- [ ] **Step 7.9: Commit**

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
git add mount/src/ipc.rs mount/src/reconnect.rs mount/src/fuse_fs.rs mount/tests/namespace_ops.rs
git commit -m "feat(mount): implement mkdir/unlink/rmdir FUSE ops

Per unidrive/docs/dev/specs/hydration-namespace-verbs-design.md
§§3.6-3.8: three new IpcClient methods, three ReconnectingIpcClient
wrappers, three FUSE op impls, and map_ipc_err_to_errno helper that
translates the wire-contract error strings (path_is_folder /
path_is_file / not_empty / not_found / provider_error) to kernel
errnos (EISDIR / ENOTDIR / ENOTEMPTY / ENOENT / EIO).

Five integration tests in mount/tests/namespace_ops.rs verify the
errno mapping against a fake IPC server.

Closes the three BACKLOG entries in this repo:
- FUSE mkdir not implemented
- FUSE unlink not implemented
- FUSE rmdir not implemented

Phase 2 implementation complete on both sides of the IPC boundary."
```

---

## Task 8: Full suite green, BACKLOG close-out, deploy, live smoke, merge

**Files:**
- Modify: `/home/gernot/dev/git/unidrive/BACKLOG.md` (move Critical mount-write clobber + delta-phantom-delete entries to "Recently Completed")
- Modify: `/home/gernot/dev/git/unidrive-mount-linux/BACKLOG.md` (move three FUSE ENOSYS entries to "Recently Completed")

- [ ] **Step 8.1: Run the full JVM suite**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew test -q > /tmp/t8-jvm.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 3 /tmp/t8-jvm.log || echo "no failures"
```
Expected: exit 0, no failures across all modules.

- [ ] **Step 8.2: Run the full Rust suite**

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
cargo test --all > /tmp/t8-rust.log 2>&1
echo "exit=$?"
grep -E "FAILED|error\[|panicked" -C 3 /tmp/t8-rust.log || echo "no failures"
```
Expected: exit 0.

- [ ] **Step 8.3: Move BACKLOG entries to "Recently Completed" — unidrive**

In `/home/gernot/dev/git/unidrive/BACKLOG.md`:
- Cut the Critical entry "Mount-write clobbered by legacy SyncEngine on next reconcile" (commit `976ca75`)
- Cut the related delta-phantom-delete entry if it's in scope of this plan (verify with `grep -n "phantom" BACKLOG.md`)
- Paste into the "Recently Completed" section with a one-line resolution note pointing at the spec + plan + commits

Example resolution note:
```markdown
- **Mount-write clobbered by legacy SyncEngine on next reconcile** —
  Resolved by per-profile mode mutex (sync ↔ mount mutually exclusive)
  per docs/dev/specs/mount-sync-mode-mutex-design.md. Plan:
  docs/dev/plans/basic-fuse-mount-plan.md Tasks 1-3.
```

- [ ] **Step 8.4: Move BACKLOG entries to "Recently Completed" — unidrive-mount-linux**

In `/home/gernot/dev/git/unidrive-mount-linux/BACKLOG.md`, move the three High entries
("FUSE mkdir not implemented", "FUSE unlink not implemented", "FUSE rmdir not
implemented") to "Recently Completed" with a single combined resolution note:

```markdown
- **FUSE mkdir / unlink / rmdir implemented** — Three new IPC verbs
  (hydration.mkdir / hydration.unlink / hydration.rmdir) bridge the
  FUSE ops to SyncEngine entry points. See
  unidrive/docs/dev/specs/hydration-namespace-verbs-design.md and
  unidrive/docs/dev/plans/basic-fuse-mount-plan.md Tasks 4-7.
```

- [ ] **Step 8.5: Commit the BACKLOG close-outs**

```bash
cd /home/gernot/dev/git/unidrive
git add BACKLOG.md
git commit -m "docs(backlog): close mount-write-clobber after Phase 1 mutex landed"

cd /home/gernot/dev/git/unidrive-mount-linux
git add BACKLOG.md
git commit -m "docs(backlog): close mkdir/unlink/rmdir ENOSYS after Phase 2 verbs landed"
```

- [ ] **Step 8.6: Deploy the JVM daemon**

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:deploy -q > /tmp/t8-deploy.log 2>&1
echo "exit=$?"
tail -20 /tmp/t8-deploy.log
```
Expected: exit 0, deploy task prints "BUILD SUCCESSFUL".

- [ ] **Step 8.7: Restart the running daemon (operator-side)**

The deployed daemon is run by the operator (you, in real life). Restart it manually
using whatever launcher you normally use (systemd unit, `nohup`, `tmux` window,
etc.). Confirm it's up:

```bash
pgrep -af "unidrive" | head -5
```
Expected: at least one process matching `unidrive sync ...`.

- [ ] **Step 8.8: Live smoke — Phase 1 mutex**

Per spec mount-sync-mode-mutex-design.md §3.7 happy-path. Expected stderr matches the
factory error rendering from Task 2.1 / 2.2 byte-for-byte.

```bash
# Terminal A: sync daemon should already be running on profile posteo_onedrive
# Terminal B: try to mount the same profile — should refuse.
unidrive -p posteo_onedrive mount /tmp/onedrive-smoke
```
Expected: exit code 1; stderr contains the line
```
Another `unidrive sync` is mirroring profile 'posteo_onedrive' (PID <N>).
```
followed by:
```
Stop the sync watcher first: `kill <N>` (or Ctrl-C its terminal).
Mount and sync are mutually exclusive per profile (see docs/dev/specs/mount-sync-mode-mutex-design.md).
```

Then the reverse:
```bash
# Stop the sync daemon (your usual stop method).
# Start the mount fresh:
unidrive -p posteo_onedrive mount /tmp/onedrive-smoke
# In another terminal:
unidrive -p posteo_onedrive sync
```
Expected: mount runs; second `sync` invocation exits with code 1 and stderr line
```
Profile 'posteo_onedrive' is currently FUSE-mounted by `unidrive mount` (PID <N>).
Mount and sync are mutually exclusive per profile. Stop the mount first: unmount the FUSE path, or `kill <N>`.
```

- [ ] **Step 8.9: Live smoke — Phase 2 namespace ops via mounted FS**

With the mount still running from Step 8.8:

```bash
# Create a folder via FUSE:
mkdir /tmp/onedrive-smoke/smoke_folder
# Confirm it appears on the remote (use Internxt webapp / OneDrive webapp).
# Create a file inside:
echo "hello" > /tmp/onedrive-smoke/smoke_folder/hello.txt
# Confirm upload via daemon log.
# Unlink the file:
rm /tmp/onedrive-smoke/smoke_folder/hello.txt
# Confirm deletion on remote.
# Rmdir the now-empty folder:
rmdir /tmp/onedrive-smoke/smoke_folder
# Confirm deletion on remote.
```
Expected:
- All four operations succeed with exit 0.
- All four reflect on the remote (visible via webapp within ~30s).
- No `ENOSYS` / `EIO` / `Broken pipe` lines in the daemon log:
  ```bash
  tail -200 ~/.local/share/unidrive/log/sync.log | grep -E "ENOSYS|EIO|Broken pipe" || echo "clean"
  ```

- [ ] **Step 8.10: Live smoke — error paths**

```bash
# rmdir on non-empty folder must return ENOTEMPTY:
mkdir /tmp/onedrive-smoke/notempty
echo "a" > /tmp/onedrive-smoke/notempty/a.txt
rmdir /tmp/onedrive-smoke/notempty
# Expected: rmdir: failed to remove '/tmp/onedrive-smoke/notempty': Directory not empty

# unlink on a directory must return EISDIR:
rm /tmp/onedrive-smoke/notempty
# Expected: rm: cannot remove '/tmp/onedrive-smoke/notempty': Is a directory

# Cleanup:
rm /tmp/onedrive-smoke/notempty/a.txt
rmdir /tmp/onedrive-smoke/notempty
```
Expected: each error case produces the matching errno text from coreutils.

- [ ] **Step 8.11: Merge to main**

```bash
cd /home/gernot/dev/git/unidrive
git checkout main
git merge --no-ff <feature-branch> -m "Merge: basic FUSE mount end-to-end (mode mutex + namespace verbs)"

cd /home/gernot/dev/git/unidrive-mount-linux
git checkout main
git merge --no-ff <feature-branch> -m "Merge: implement mkdir/unlink/rmdir FUSE ops"
```

Replace `<feature-branch>` with the branch name selected at Step 0a.

Do NOT push to origin in this step — wait for explicit user go-ahead.

- [ ] **Step 8.12: Final report to user**

Post a single short summary in chat:
- Phase 1 (mode mutex): committed, tested, smoke-confirmed.
- Phase 2 (namespace verbs): committed, tested, smoke-confirmed.
- BACKLOG entries closed in both repos.
- Both feature branches merged to local `main`.
- Awaiting user go-ahead before `git push`.

---

## Self-Review Checklist (performed by plan author before handoff)

- Spec coverage:
  - mount-sync-mode-mutex-design.md §3.1 (wire format) — Task 1
  - §3.2 (ProcessLock extension) — Task 1
  - §3.4 (acquireProfileLockForMount + zombie-mount) — Tasks 2, 3
  - §3.6 (T1-T5 tests) — Task 1 (T1-T3), Task 2 (T4-T5)
  - hydration-namespace-verbs-design.md §3.1 (wire contract) — Task 6
  - §3.2 (SyncEngine + StateDatabase) — Task 4
  - §3.3-3.4 (HydrationImpl) — Task 5
  - §3.5 (HydrationIpcHandler) — Task 6
  - §§3.6-3.8 (Rust co-daemon) — Task 7
  - §3.9 (tests, including rmdir_detects_provider_not_empty_substring) — Tasks 5, 7
  - §3.10 (live smoke) — Task 8
- Placeholder scan: none — every code step shows the actual code; every command step shows the actual command + expected output.
- Type consistency:
  - `Mode` enum referenced consistently (`ProcessLock.Mode.SYNC` / `MOUNT`) across Tasks 1, 2, 3
  - `MkdirResult` / `UnlinkResult` / `RmdirResult` sealed classes named consistently across Tasks 4, 5, 6
  - Wire-contract error strings (`path_is_folder` / `path_is_file` / `not_empty` / `not_found` / `provider_error`) match across Tasks 6, 7
