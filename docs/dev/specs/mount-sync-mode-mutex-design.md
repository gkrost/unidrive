# Mount/Sync Mode Mutex — Design

**Status:** Proposed — design doc, not yet implemented.
**Origin:** Critical-tier BACKLOG entry "Mount-write clobbered by legacy SyncEngine on next `--watch` cycle (data loss class)" committed in `976ca75`.
**Touches:**
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt` (extend lock body with mode metadata).
- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` (`acquireProfileLock` extension or sibling for mount).
- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt` (acquire lock at startup, release on exit).
- `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt` (cover the new mode-carrying behavior).

## 1. Problem

### 1.1 Observed failure

Live evidence (2026-05-24, log lines `08:32:19` and `08:33:10` against `posteo_onedrive`): operator edited `/tmp/onedrive-smoke/geheim.txt` through the FUSE mount (52 → 66 bytes). The co-daemon's dirty-release fired `hydration.open_write`; the JVM uploaded the 66-byte version to OneDrive at `08:32:19`. **One minute later at `08:33:10`** the legacy `SyncEngine` ran its `--watch` cycle and re-uploaded `/geheim.txt` with the old 52-byte content from `~/Onedrive/`, overwriting the cloud copy.

Confirmed by `~/.local/share/unidrive/unidrive.log`: `Upload (simple): /geheim.txt (66 bytes)` followed by `Upload: /geheim.txt (52 bytes)` from the engine's main loop. **Every user edit through the mount that is not also reflected in `~/Onedrive/` before the next sync tick is silently destroyed.**

### 1.2 Root cause

The FUSE mount and the local sync root `~/Onedrive/` are two independent producers of OneDrive uploads with no coordination:

- The mount writes user-edited content through `HydrationImpl.openForWrite` → `syncEngine.uploadFromCache` → provider PUT.
- The legacy engine treats `~/Onedrive/<path>` as authoritative for `<path>` and re-uploads it whenever the local mtime advances or on every full-sync cycle.

The two writers race; the engine's `--watch` cycle runs every minute, so the mount's writeback is structurally undone on the next tick, every time.

### 1.3 Existing partial defense

`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt` already implements an inter-process advisory lock (UD-272 history). `Main.acquireProfileLock()` (`Main.kt:560-585`) acquires it at the start of every `SyncCommand.run()` so two `unidrive sync` processes can't run against the same profile simultaneously. The lock file lives at `providerConfigDir()/.lock` with a sibling `.lock.pid` carrying the holder's PID for diagnostic messages.

`MountCommand.run()` does NOT acquire this lock today. That's the structural omission this design closes.

### 1.4 Mid-term context

The user's mid-term plan retires the legacy `SyncEngine` once the tracking-set engine (`core/app/sync-tracking/`, Critical-tier BACKLOG) reaches MVP. The tracking-set engine will inherit the same "mount or sync mirror, not both simultaneously" invariant — different implementation, same operator-facing rule. This design's invariants survive that migration unchanged.

### 1.5 What this design does NOT address

- **Implementing the hydration namespace verbs** (`mkdir`/`unlink`/`rmdir`). Filed as a separate spec; it ASSUMES this mutex holds for the duration of the mount session.
- **The delta-loop phantom-delete finding** (BACKLOG `976ca75`, recurring `Full sync: DB entry … not in delta, marking deleted` for paths the engine just uploaded). Adjacent but independent; not on the path to "basic FUSE stuff working."
- **Migrating existing `~/Onedrive/` data into the mount's cache or vice versa.** First-writer-wins: whichever mode launches first holds the lock; existing data stays untouched while the other mode refuses to start.

## 2. Goals & non-goals

**Goal G1:** A profile can be in `sync` mode (legacy `SyncEngine` mirror under `~/Onedrive/`) OR `mount` mode (FUSE on-demand view), never both simultaneously.

**Goal G2:** The second mode launched fails fast with an actionable error naming the holder's mode + PID + the command to stop it.

**Goal G3:** First-writer-wins semantics. Existing `~/Onedrive/` data is preserved if the operator chooses to launch `sync --watch` next session; mount-cache data is preserved if the operator chooses to launch `mount` next session.

**Goal G4:** No CLI verb changes, no config schema changes. Operator launches whichever mode they want; the second one refuses with a clear message.

**Goal G5:** Survives JVM crashes — a daemon that exits uncleanly does not leave the lock dangling for a future process.

**Non-goal NG1:** Auto-failover from one mode to the other. If `sync --watch` is running and the operator wants to switch to `mount`, they explicitly stop the sync watcher (e.g. `Ctrl-C` in its terminal) and re-launch as `mount`.

**Non-goal NG2:** Coexistence of mount and sync — that path is explicitly closed by this design.

**Non-goal NG3:** Profile-level config flag declaring permanent mode preference. The tracking-set migration will rethink profile-level config; adding flags here that the new engine will rework is premature.

**Non-goal NG4:** Granular lock scopes (e.g. read-only mount + read-only sync). YAGNI. The invariant is "one producer of writes per profile."

## 3. Design

### 3.1 Lock file body — mode metadata

The existing `ProcessLock` writes the holder PID to a sibling `.lock.pid` file. This design extends the metadata to also carry the holder's **mode**: either `sync` or `mount`. The lock-file path itself is unchanged; only the sidecar content changes.

**Wire format of `.lock.pid` (single line, two fields, space-separated):**

```
<pid> <mode>
```

Examples:
- `12345 sync` — sync watcher running, PID 12345.
- `67890 mount` — FUSE mount running, PID 67890.

Backwards compatibility: existing `.lock.pid` files (PID only, no mode) are read as `<pid> sync`. Forward compatibility: if a new mode is added later (e.g. `mirror`), readers parse the second token literally and `MountCommand`/`SyncCommand` refuse with a message identifying the unknown mode. No version field; the format is two fields forever.

### 3.2 `ProcessLock` API extension

Two additions to `ProcessLock`:

```kotlin
/**
 * Mode of the process holding this lock. `sync` for SyncCommand,
 * `mount` for MountCommand. Read alongside the PID from .lock.pid
 * so a contender can name the holder accurately ("another mount
 * is running" vs "another sync is running").
 */
enum class Mode { SYNC, MOUNT }

/**
 * Acquire the lock and stamp `<pid> <mode>` into the sibling .pid file.
 * Existing call sites that don't care about mode (none today after
 * MountCommand wiring) pass Mode.SYNC.
 */
fun tryLock(mode: Mode, timeout: Duration = Duration.ZERO): Boolean

/**
 * Returns the holder's (pid, mode) tuple if the lock is currently
 * held, or null if no `.pid` sidecar exists. Used by the contention
 * error path to render a mode-specific message.
 */
fun readHolderInfo(): HolderInfo?

data class HolderInfo(val pid: Long, val mode: Mode)
```

The existing `tryLock(timeout: Duration)` overload becomes a thin shim that calls `tryLock(Mode.SYNC, timeout)` for backwards compatibility with any test or call site that hasn't migrated. The new code paths use the explicit-mode overload.

`readHolderPid()` (existing, returns `Long?`) stays callable; `readHolderInfo()` is the new richer variant. Both read from the same `.lock.pid` file.

### 3.3 `MountCommand` lock acquisition

`MountCommand.run()` (`MountCommand.kt:35-65`) currently jumps straight to `resolveCurrentProfile()` then `socketPath` then `superviseProcess`. The fix inserts lock acquisition between profile resolution and socket-path computation:

```kotlin
override fun run() {
    if (profileNameOverride != null) {
        parent.provider = profileNameOverride
        parent.invalidateProfileCaches()
    }
    val profile = parent.resolveCurrentProfile()

    // Acquire the per-profile mode-mutex. Refuses with a clear message
    // if `sync` mode is already holding it for this profile.
    val lock = parent.acquireProfileLockForMount()

    try {
        val socketPath = IpcServer.defaultSocketPath(profile.name)
        // … existing body (cacheRoot, binary, supervise) …
        val exit = superviseProcess(argv)
        System.exit(exit)
    } finally {
        lock.unlock()
    }
}
```

The `try/finally` ensures the lock releases even if `superviseProcess` throws or `System.exit` is not reached on an error path. (Note: `System.exit` from inside the `try` block bypasses the `finally`. That's fine — JVM exit releases the OS file lock; the only thing `finally` would have done extra is delete the `.lock.pid` sidecar, which a fresh acquisition cleans up anyway.)

### 3.4 `Main.acquireProfileLockForMount` factory

Symmetric with the existing `acquireProfileLock()` at `Main.kt:560-585`. Same lock file path, same diagnostic-message shape, only the mode and error wording differ:

```kotlin
fun acquireProfileLockForMount(): ProcessLock {
    val lockFile = providerConfigDir().resolve(".lock")
    java.nio.file.Files.createDirectories(lockFile.parent)
    val lock = ProcessLock(lockFile)
    if (!lock.tryLock(ProcessLock.Mode.MOUNT)) {
        val profile = resolveCurrentProfile()
        val holder = lock.readHolderInfo()
        val holderDesc = when (holder?.mode) {
            ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is mirroring profile '${profile.name}'"
            ProcessLock.Mode.MOUNT ->
                "Another `unidrive mount` already serves profile '${profile.name}'"
            null ->
                "Another unidrive process is using profile '${profile.name}'"
        }
        System.err.println("$holderDesc${holder?.let { " (PID ${it.pid})" } ?: ""}.")
        if (holder?.mode == ProcessLock.Mode.SYNC) {
            System.err.println(
                "Stop the sync watcher first: `kill ${holder.pid}` (or Ctrl-C its terminal).",
            )
            System.err.println(
                "Mount and sync are mutually exclusive per profile — see docs/dev/specs/mount-sync-mode-mutex-design.md.",
            )
        } else if (holder != null) {
            System.err.println("Stop it with `kill ${holder.pid}`, or wait for it to exit.")
        }
        System.exit(1)
    }
    return lock
}
```

`acquireProfileLock()` (the existing sync-mode factory) is updated symmetrically to (a) pass `Mode.SYNC` and (b) render the mount-holder case in its error path:

```kotlin
fun acquireProfileLock(): ProcessLock {
    val lockFile = providerConfigDir().resolve(".lock")
    Files.createDirectories(lockFile.parent)
    val lock = ProcessLock(lockFile)
    if (!lock.tryLock(ProcessLock.Mode.SYNC)) {
        val profile = resolveCurrentProfile()
        val holder = lock.readHolderInfo()
        val holderDesc = when (holder?.mode) {
            ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is running for profile '${profile.name}'"
            ProcessLock.Mode.MOUNT ->
                "Profile '${profile.name}' is currently FUSE-mounted by `unidrive mount`"
            null ->
                "Another unidrive process is using profile '${profile.name}'"
        }
        // … existing PID + kill-hint rendering, augmented with the holder-mode line …
        System.exit(1)
    }
    return lock
}
```

The diagnostic message is the operator's only insight into why the second mode refused; it's worth the extra few lines.

### 3.5 Crash recovery

The lock relies on the kernel's advisory file-lock semantics: when the holding JVM exits (cleanly OR via SIGKILL / OOM / segfault), the kernel releases the `FileLock`. The next `ProcessLock.tryLock(...)` succeeds. The stale `.lock.pid` sidecar from a crashed prior process is overwritten at acquisition (per `ProcessLock.kt:65-67`'s existing `Files.writeString(pidFile, …)`).

This means **no manual cleanup is required after a crash** — the next launch of either mode works. The only failure mode is a JVM that's still alive but stuck (e.g. wedged on a hung I/O call): the lock stays held, and the operator must kill the JVM explicitly. That's the intended behavior — the diagnostic message gives them the PID and kill command.

### 3.6 Testing

Four named tests pin the invariants. Three are unit tests against `ProcessLock` directly (extend `ProcessLockTest.kt`); one is an integration test against `MountCommand`'s startup behavior (new `MountCommandLockTest.kt`).

**T1 — `lock_pid_file_carries_mode_after_tryLock`.** Acquire with `Mode.MOUNT`, read `.lock.pid` content, assert it parses as `<pid> mount`. Acquire with `Mode.SYNC`, repeat with `<pid> sync`. Pins §3.1 wire format.

**T2 — `read_holder_info_returns_pid_and_mode_for_locked_file`.** Hold a `Mode.MOUNT` lock, call `readHolderInfo()` from a second `ProcessLock` instance on the same file, assert `HolderInfo(pid=…, mode=MOUNT)`. Symmetric repeat for `Mode.SYNC`. Pins §3.2 reader path.

**T3 — `legacy_pid_only_lock_pid_file_reads_as_sync_mode`.** Write a `.lock.pid` containing just `12345\n` (legacy format, no mode token). Call `readHolderInfo()`. Assert it returns `HolderInfo(pid=12345, mode=SYNC)`. Pins §3.1 backwards compatibility.

**T4 — `mount_command_refuses_when_sync_holds_lock`.** Acquire `Mode.SYNC` on a temp lock file via `ProcessLock`. Invoke `MountCommand.run()` (or its lock-acquisition step in isolation) against the same profile. Assert it exits with code 1 and the stderr message contains "is mirroring profile" + the holder PID. Pins §3.3+§3.4 user-visible behavior.

(A symmetric "sync refuses when mount holds lock" test would be nice-to-have but is structurally identical to T4 — it would exercise the same code path through the opposite-mode call site, and the existing `acquireProfileLock` test surface in `ProcessLockTest` already covers sync-vs-sync contention. Skip per YAGNI.)

### 3.7 Live smoke test (manual, post-merge)

Two-phase smoke against the actual `posteo_onedrive` profile, mirroring the original failure scenario:

**Phase A — sync-blocks-mount:**

1. Ensure `unidrive -p posteo_onedrive sync --watch` is running (PID known via `pgrep -af "sync --watch"`).
2. In a separate terminal, try to mount: `unidrive -p posteo_onedrive mount /tmp/onedrive-smoke-mutex`.
3. **Expected:** mount exits with code 1, stderr says `Another \`unidrive sync\` is mirroring profile 'posteo_onedrive' (PID <n>). Stop the sync watcher first: \`kill <n>\` (or Ctrl-C its terminal).`
4. `/tmp/onedrive-smoke-mutex` is NOT mounted (no entry in `mount | grep onedrive-smoke-mutex`).

**Phase B — mount-blocks-sync:**

1. Stop the sync watcher (`Ctrl-C` in its terminal).
2. `mkdir -p /tmp/onedrive-smoke-mutex && unidrive -p posteo_onedrive mount /tmp/onedrive-smoke-mutex` — should succeed (no contender for the lock).
3. In a separate terminal: `unidrive -p posteo_onedrive sync --watch`.
4. **Expected:** sync exits with code 1, stderr says `Profile 'posteo_onedrive' is currently FUSE-mounted by \`unidrive mount\` (PID <n>).`
5. Cleanup: `fusermount3 -u /tmp/onedrive-smoke-mutex && rmdir /tmp/onedrive-smoke-mutex`.

**Verification of the original data-loss scenario:**

After this fix lands, the original repro (mount → edit `geheim.txt` → wait for `sync --watch` to clobber) is **structurally impossible** because the operator cannot have both modes running simultaneously. The clobber observation from log lines `08:32:19` / `08:33:10` on 2026-05-24 cannot recur.

## 4. Risks and open questions

**R1 — Operator workflow change.** Users who got used to having `sync --watch` running continuously and occasionally mounting the FUSE view will need to choose one mode per session. Mitigation: the error message explicitly tells them how to switch (kill the sync watcher). The mount/sync trade-off is fundamentally one of "mirror vs. on-demand view"; trying to support both simultaneously is what produced the data-loss class. The user explicitly chose first-writer-wins semantics.

**R2 — `unidrive status` and other read-only commands.** `unidrive status` queries the daemon's IPC socket but does not run the engine. Today it does NOT call `acquireProfileLock()` — it's a read-only client of the running daemon. After this fix, that behavior is unchanged: status doesn't acquire the lock, so it works fine regardless of which mode holds it. Verified by grep: `acquireProfileLock` is called only from `SyncCommand.run()`.

**R3 — Lock file location.** `providerConfigDir()/.lock` is the existing path (`Main.kt:561`). This sits under `~/.config/unidrive/<profile>/` on Linux. Per CLAUDE.md `~/docker/` and operator config, the location is fine. The systemd-user-unit setup also runs as the same UID, so the lock file is readable/writable by the same set of processes that can read the profile config.

**R4 — Co-daemon process supervision.** `MountCommand.superviseProcess` forks the Rust co-daemon and waits for its exit. If the JVM `MountCommand` is killed (e.g. `kill -9 <pid>`) while the co-daemon is alive, the kernel releases the file lock (JVM exit) but the co-daemon keeps running, still attached to the FUSE mount via `/dev/fuse`. The next `unidrive -p X sync --watch` would then acquire the lock and start syncing into `~/Onedrive/` while the mount is still serving from `/tmp/...`. **This is a problem.** Mitigation options:

- (a) `MountCommand` installs a shutdown hook that issues `SIGTERM` to the co-daemon child before releasing the lock. Today's `superviseProcess` already does signal forwarding on SIGTERM/SIGINT (per `MountCommand.kt:102+`). A JVM crash bypasses this.
- (b) The co-daemon could acquire its OWN copy of the lock file (Rust-side `flock(2)`) for the duration of the mount. JVM crash releases the JVM's lock, but the co-daemon's flock keeps the lock alive until the co-daemon exits.

Option (b) is structurally cleaner but requires co-daemon-side changes and a second cross-repo edit. Option (a) is JVM-only and addresses the common case (orderly shutdown). **Decision: ship (a) now, file (b) as a follow-up entry** so this design isn't blocked on the cross-repo change. The residual risk is a `kill -9 <jvm-pid>` leaving a zombie mount that races with a freshly-launched sync — the operator must `fusermount3 -u` the dead mount as a recovery step. Document the residual.

**R5 — Tracking-set engine migration.** When the tracking-set engine (`core/app/sync-tracking/`) lands as MVP and replaces the legacy `SyncEngine`, the new engine inherits the same "acquire `Mode.SYNC` lock at startup" contract. No design change required in the new engine; it just calls `acquireProfileLock()` like its predecessor. The lock infrastructure is engine-agnostic by design.

## 5. Acceptance

- Four tests in §3.6 pass:
  - `lock_pid_file_carries_mode_after_tryLock`
  - `read_holder_info_returns_pid_and_mode_for_locked_file`
  - `legacy_pid_only_lock_pid_file_reads_as_sync_mode`
  - `mount_command_refuses_when_sync_holds_lock`
- Existing `ProcessLockTest.kt` cases continue to pass (the legacy `tryLock(timeout)` overload preserves byte-identical behavior for callers that don't pass a mode).
- The full `:app:sync:test` + `:app:cli:test` suites are green.
- Manual smoke per §3.7 confirms both phases (sync-blocks-mount, mount-blocks-sync) produce the expected error messages and exit codes.
- BACKLOG entry "Mount-write clobbered by legacy SyncEngine on next `--watch` cycle (data loss class)" moves to `CLOSED.md` in the same commit set that lands the fix.
- Follow-up BACKLOG entry filed for R4 option (b): co-daemon-side `flock(2)` of the same lock file so a `kill -9 <jvm-pid>` while the mount is up doesn't leave the lock unclaimed.
