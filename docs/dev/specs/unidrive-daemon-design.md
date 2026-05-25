# `unidrive daemon` — per-profile long-lived IPC server

**Status:** draft (2026-05-25)
**Branches affected:** `unidrive` main repo (`core/app/cli/`, `core/app/sync/` minor), `unidrive-mount-linux` sibling (error-message wording only)
**BACKLOG entry this spec resolves:** "`unidrive mount` has no JVM-side IPC server lifecycle (mount-only mode is dead-on-arrival)" — High tier in `BACKLOG.md`.
**BACKLOG entries this spec defers:**
- "Convert `unidrive sync` into a client of the `unidrive daemon` process (daemon-decomposition phase 2)" — Medium tier.
- "Auto-spawn `unidrive daemon` on first client connection (daemon-decomposition phase 3)" — Medium tier.
- Mode-mutex spec `mount-sync-mode-mutex-design.md` becomes deletable scaffolding once phase 2 lands; this spec amends it but does not retire it.

## 1. Problem

The existing `unidrive mount` command was never functional standalone. `MountCommand.run()` spawns the Rust co-daemon (`unidrive-mount` from `../unidrive-mount-linux`) and supervises it, but it never instantiates an `IpcServer`. The Rust co-daemon connects to `/run/user/<uid>/unidrive-<profile>.sock` expecting a JVM-side handler to be listening — there is none.

Pre-mode-mutex, the workflow happened to work when an operator ran `unidrive sync -w` concurrently in another terminal: `SyncCommand` creates an `IpcServer` (at `SyncCommand.kt:308`) which the co-daemon then connected to. Phase 1 of the mode-mutex work (spec `mount-sync-mode-mutex-design.md`) correctly forbids concurrent sync + mount per profile, closing the only path that ever made standalone mount work. Live smoke on 2026-05-24 surfaced the gap: `failed to connect IPC at /run/user/1000/unidrive-posteo_onedrive.sock: io: Connection refused (os error 111)`.

This spec introduces `unidrive daemon` — a per-profile, long-lived JVM that owns the IPC server, auth/provider session, and `state.db` handle. `mount` becomes a thin client of the daemon. `sync` stays unchanged in this phase; conversion to a daemon client is the phase-2 follow-up filed in BACKLOG.

## 2. Goals and non-goals

### Goals

- **G1:** `unidrive mount` works end-to-end without requiring a concurrent `sync` process.
- **G2:** One daemon process per profile. Multiple profiles run multiple daemons.
- **G3:** Daemon is strictly reactive — it only acts when an IPC verb arrives. No background reconcile loops, no scheduled enumeration, no auto-bootstrap.
- **G4:** State-of-the-cloud population is the operator's choice via a new IPC verb `refresh.run`, exposed through a thin `unidrive refresh` client (replacing today's standalone refresh JVM).
- **G5:** Lifecycle is explicit and operator-visible: `unidrive daemon run|status|stop` subcommands. Foreground run; operator backgrounds via shell or wrapper.
- **G6:** Mode-mutex extends to two-way exclusion: `sync` ⇄ `daemon` per profile. Mount becomes a daemon client, not a lock holder. The existing `Mode.MOUNT` enum value is removed.
- **G7:** Fail-fast on startup: daemon authenticates BEFORE binding the socket; on auth failure, no socket is ever created.
- **G8:** Graceful shutdown with 10s deadline (matches existing `MountCommand.SIGTERM_GRACE_MS` convention). After deadline, in-flight RPCs are abandoned and the daemon exits.

### Non-goals

- **NG1:** Auto-spawning the daemon from clients. Operator runs `unidrive daemon run <profile>` explicitly. Filed as decomposition phase-3 in BACKLOG.
- **NG2:** Converting `unidrive sync` into a daemon client. Sync still owns its own `IpcServer` + reconcile loop. Filed as decomposition phase-2 in BACKLOG.
- **NG3:** Systemd-user-unit generation. Covered inside the phase-3 BACKLOG entry.
- **NG4:** Daemon multiplexing multiple profiles. One profile per daemon process; no shared state across daemons.
- **NG5:** Persisting the refresh cursor between runs. A new `refresh.run` re-enumerates the full cloud root. Persistent cursors are the tracking-set engine's concern, not this spec's.
- **NG6:** Auto-retry on auth failure during normal operation. Token refresh on 401 stays handled by the existing provider HTTP client; whole-session auth failure during normal operation surfaces as `provider_error` to the client, daemon stays up.
- **NG7:** UI changes (`unidrive-ui`). The UI already speaks IPC; it will connect to the daemon's socket transparently once the daemon exposes the same verbs the sync `IpcServer` does.
- **NG8:** Backwards-compat for standalone `unidrive refresh`. The pre-spec behaviour was a standalone JVM running the refresh path against the provider directly. Post-spec, `unidrive refresh` becomes a thin client that requires a running daemon. Operators who used `unidrive refresh` standalone (without a sync watcher) get a clear "daemon not running" error and the same hint pointing at `unidrive daemon run`. This is a deliberate UX change consistent with the daemon model — fragmenting the refresh path between "standalone JVM" and "daemon RPC" would defeat the single-source-of-truth invariant the daemon establishes. Trade-off acknowledged: scripts that called `unidrive refresh <profile>` in cron jobs without managing a daemon will break. Migration: wrap with `unidrive daemon run <profile> & sleep 2 && unidrive refresh <profile> && unidrive daemon stop <profile>` or move to systemd-user-unit when phase-3 ships.

## 3. Architecture

### 3.1 Component picture

```
                    ┌──────────────────────────────────────────────┐
                    │  unidrive daemon run posteo_onedrive         │
                    │  (long-lived JVM, one per profile)           │
                    │                                              │
                    │  ┌────────────────────────────────────────┐  │
                    │  │  ProcessLock (Mode.DAEMON)             │  │
                    │  └────────────────────────────────────────┘  │
                    │  ┌────────────────────────────────────────┐  │
                    │  │  Provider (OneDrive | Internxt)        │  │
                    │  │  authenticated at startup              │  │
                    │  └────────────────────────────────────────┘  │
                    │  ┌────────────────────────────────────────┐  │
                    │  │  StateDatabase (single connection,     │  │
                    │  │  rollback-journal mode (SQLite default),│ │
                    │  │  daemon-lifetime, @Synchronized API)   │  │
                    │  └────────────────────────────────────────┘  │
                    │  ┌────────────────────────────────────────┐  │
                    │  │  IpcServer @ /run/user/<uid>/          │  │
                    │  │    unidrive-<profile>.sock             │  │
                    │  │  Handlers:                             │  │
                    │  │    hydration.* (existing)              │  │
                    │  │    refresh.run (new)                   │  │
                    │  │    daemon.status (new, read-only)      │  │
                    │  │    sync.subscribe (re-used)            │  │
                    │  └────────────────────────────────────────┘  │
                    └──────────────────────────────────────────────┘
                                       ▲          ▲
                          ┌────────────┘          └─────────────┐
                          │ IPC                                 │ IPC
                ┌─────────┴───────────┐               ┌─────────┴───────────┐
                │ unidrive mount      │               │ unidrive refresh    │
                │ (Kotlin process     │               │ (Kotlin client)     │
                │  + Rust co-daemon)  │               │  issues refresh.run │
                │  uses hydration.*   │               │  + subscribes to    │
                │                     │               │  progress events    │
                └─────────────────────┘               └─────────────────────┘
```

### 3.2 Lifecycle state machine

```
   [ unidrive daemon run <profile> ]
              │
              ▼
   ┌──────────────────────────────┐
   │ Acquire ProcessLock(DAEMON)  │──── fails ───▶ Print contention error, exit 1
   └──────────────────────────────┘    (mirrors Mode.SYNC contention shape)
              │ acquired
              ▼
   ┌──────────────────────────────┐
   │ Open StateDatabase           │──── fails ───▶ Print error, release lock, exit 1
   └──────────────────────────────┘
              │ opened
              ▼
   ┌──────────────────────────────┐
   │ provider.authenticate()      │──── fails ───▶ Print auth error, release lock, exit 1
   └──────────────────────────────┘    (NO socket bound, NO .lock.pid lingering)
              │ authed
              ▼
   ┌──────────────────────────────┐
   │ IpcServer.start() — calls    │──── bind fails ▶ Release lock, exit 1
   │ reclaimStaleSocket internally│   (no retry — see F3)
   └──────────────────────────────┘
              │ bound
              ▼
   ┌──────────────────────────────┐
   │  SERVE (idle/serving RPCs)   │ ◀─── SIGTERM ──┐
   └──────────────────────────────┘                │
              │                                    │ initiate graceful shutdown
              │                                    │
              ▼                                    │
   ┌──────────────────────────────┐                │
   │ Refuse new connections;      │ ──── 10s ────▶ Force shutdown:
   │ wait for in-flight RPCs;     │    deadline    cancel coroutines,
   │ cancel refresh.run if any    │                drop connections
   └──────────────────────────────┘
              │
              ▼
   ┌──────────────────────────────┐
   │ Close IpcServer;             │
   │ close StateDatabase;         │
   │ release ProcessLock;         │
   │ remove socket file           │
   └──────────────────────────────┘
              │
              ▼
            exit 0
```

### 3.3 Component responsibilities

- **`DaemonCommand` (new, `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonCommand.kt`)** — owns the lifecycle state machine in §3.2. Constructs Provider, StateDatabase, Hydration, IpcServer in that order. Registers handlers. Installs a SIGTERM shutdown hook. Runs `runBlocking { ... }` until socket closes. **Implementer note on testability:** the existing `SyncCommand` reads all its dependencies through `@ParentCommand parent: Main` (see `SyncCommand.kt:213, 230-255`), which prevents instantiation without picocli wiring. T1 requires the daemon's lifecycle to be exercisable from a JUnit test without spawning a JVM. To make T1 cleanly implementable, `DaemonCommand` should be structured as a picocli-thin shell that delegates to a separately-constructable `DaemonRuntime` class taking its dependencies (profile config, provider factory, lock file path, db path, socket path) as constructor arguments. The picocli class reads them off `parent: Main`, the test fixture constructs them directly. This avoids the test-harness friction the audit flagged as Issue 4 (2026-05-25 spec review); the implementation plan handles the exact split.
- **`DaemonStatusCommand` (new)** — reads `~/.config/unidrive/<profile>/.lock.pid`. If absent, prints "no daemon running for profile '<X>'" to stderr and exits 1. If present, prints `pid <N>, mode <X>` immediately from the file, THEN attempts to RPC the daemon for the richer fields (uptime, refresh-in-flight, clients connected). If the RPC fails (daemon mid-shutdown, socket gone), still prints the file-derived data plus a "daemon socket unreachable" note. Read-only; never acquires the lock.
- **`DaemonStopCommand` (new)** — reads `.lock.pid`. If absent, prints "no daemon running" + exit 0 (idempotent stop). If present and mode is `daemon`, sends `SIGTERM` to the PID, waits up to 12s (10s grace + 2s buffer), reports outcome. Does not acquire the lock itself. If mode is not `daemon` (e.g. `sync`), refuses with a clear error.
- **`RefreshCommand` (existing, refactored)** — becomes a thin client. Connects to the daemon socket; issues `sync.subscribe` first; then issues `refresh.run`; subscribes to progress events; prints them to stdout; exits when the daemon emits the `refresh.done` terminal event. If `Connection refused`, prints a clear error pointing at `unidrive daemon run`. The pre-existing standalone-JVM refresh path (calling `SyncCommand.run()` with `skipTransfers=true`) is removed from `RefreshCommand`; that code body moves into the daemon's `RefreshRpcHandler`.
- **`MountCommand` (existing, modified)** — removes the `parent.acquireProfileLockForMount()` call. Still constructs the socket path via `IpcServer.defaultSocketPath(profile.name)`. Still supervises the Rust co-daemon subprocess. On co-daemon exit with `Connection refused` stderr, prints a clear error pointing at `unidrive daemon run`. Otherwise unchanged.
- **`ProcessLock.Mode` (existing, modified at `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt`)** — `enum class Mode { SYNC, DAEMON }`. The `MOUNT` value is removed. The unknown-mode forward-compat path (already implemented at the `readHolderInfo()` reader per spec mount-sync-mode-mutex-design.md §3.1) handles any pre-existing `.lock.pid` files containing `mount` token gracefully: `HolderInfo(mode=null, rawMode="mount")`. The contention error names it verbatim ("Profile 'X' is held by an unidrive process running in unknown mode 'mount' — this binary may be older than the holder"), which is honest if a downgrade ever happens.
- **`Main.acquireProfileLock()` (existing, modified)** — keeps Mode.SYNC acquisition. The mount-holder branch becomes a daemon-holder branch (renders "currently in use by `unidrive daemon`"). The unknown-mode branch handles legacy `mount` token gracefully.
- **`Main.acquireProfileLockForMount()` (existing, REMOVED)** — no longer needed. Mount does not acquire the profile lock at all.
- **`Main.acquireProfileLockForDaemon()` (new)** — symmetric to `acquireProfileLock()` but stamps `Mode.DAEMON`. Used only by `DaemonCommand`.
- **`HydrationIpcHandler` (existing, unchanged)** — already supports all hydration verbs (read / open_write / list / subscribe / mkdir / unlink / rmdir). Daemon registers it on its IpcServer with the same wiring SyncCommand uses today.
- **`RefreshRpcHandler` (new)** — new class registered as the `refresh.run` handler. Wraps a call into the existing `SyncEngine` refresh path (`skipTransfers=true, skipRemoteGather=false` — the same flags `RefreshCommand` sets today via `SyncCommand` inheritance). Publishes progress events to subscribers via the `IpcServer`'s subscriber-set mechanism. Owns the in-flight flag for invariant I6 (at most one `refresh.run` at a time).
- **`StaleMountDetector` (existing)** — no change needed. The detector was added in Phase 1 to warn `SyncCommand` about leftover FUSE mounts from `kill -9`'d mounts (see `SyncCommand.kt:218`). The daemon adopts the same warn-but-do-not-abort behaviour: after acquiring the lock, call `StaleMountDetector.detectStaleFuseUnidriveMounts()`; if the returned list is non-empty, print a `WARNING:` line to stderr listing the stale mount paths plus a `fusermount3 -u <path>` cleanup hint, then continue startup. The daemon never aborts on stale mounts — they may have been left behind by a prior daemon's mount client that survived a `kill -9`, but the new daemon can still serve fresh clients.

### 3.4 Data flow examples

**Fresh-machine mount workflow:**

```
operator:  unidrive daemon run posteo_onedrive            # terminal A, foreground
operator:  unidrive refresh posteo_onedrive               # terminal B, populates state.db
operator:  unidrive mount posteo_onedrive /tmp/onedrive   # terminal B, FUSE serves
operator:  ls /tmp/onedrive                                # sees cloud tree (refresh populated)
```

**Just-mount (write-only) workflow:**

```
operator:  unidrive daemon run posteo_onedrive            # terminal A
operator:  unidrive mount posteo_onedrive /tmp/onedrive   # terminal B
operator:  ls /tmp/onedrive                                # empty (no refresh ran)
operator:  mkdir /tmp/onedrive/new_folder                 # works → folder created on cloud
operator:  echo hello > /tmp/onedrive/new_file.txt        # works → uploaded
```

**Daemon-not-running error path:**

```
operator:  unidrive mount posteo_onedrive /tmp/onedrive
co-daemon: [Rust] failed to connect IPC at /run/user/1000/unidrive-posteo_onedrive.sock:
              io: Connection refused (os error 111)
mount:     unidrive mount: daemon for profile 'posteo_onedrive' is not running.
mount:     Start it first: `unidrive daemon run posteo_onedrive` (in another terminal).
exit 1.
```

## 4. Wire contract

### 4.1 Existing verbs (unchanged, inherited from current IpcServer)

- `hydration.read`, `hydration.open_write`, `hydration.list`, `hydration.subscribe`
- `hydration.mkdir`, `hydration.unlink`, `hydration.rmdir` (Phase 2 namespace verbs)

All reply shapes per the established pattern: `{"ok": true, ...}` on success, `{"ok": false, "error": "<wire-string>", "message": "..."}` on failure.

### 4.2 New verb: `refresh.run`

**Request:**

```json
{"verb": "refresh.run"}
```

No parameters in this spec. The verb runs against the daemon's bound profile (one daemon per profile, so the target is unambiguous). Future extensibility: a `path` field could narrow enumeration to a subtree — out of scope; file as follow-up if requested.

**Synchronous reply (returned immediately, before enumeration starts):**

```json
{"ok": true, "job_id": "<uuid>"}
```

If a `refresh.run` is already in flight:

```json
{"ok": false, "error": "busy", "message": "refresh already running (job_id=<existing>)"}
```

The daemon serialises `refresh.run` jobs — at most one in flight at a time (invariant I6). This matches the underlying `SyncEngine` refresh path which is single-shot.

**Progress events (streamed post-reply via the existing subscriber-set mechanism, mechanism β):**

After the synchronous reply, the daemon writes JSON-Lines event records to any client that subscribed via `sync.subscribe` before issuing `refresh.run`. Event shapes match the existing `IpcServer.SyncState` data class wire format (already used by `SyncCommand` for its progress emission). Terminal event:

```json
{"event": "refresh.done", "job_id": "<uuid>", "ok": true}
```

Or on failure:

```json
{"event": "refresh.done", "job_id": "<uuid>", "ok": false, "error": "<wire-string>"}
```

**Wire-contract error strings for `refresh.run`:**

- `"busy"` — another `refresh.run` is in flight (synchronous reply path only).
- `"shutdown"` — daemon received SIGTERM and cancelled the refresh (terminal event path).
- `"auth_failed"` — provider auth lapsed mid-job (rare; provider HTTP client normally refreshes tokens transparently).
- `"provider_error"` — generic provider failure (network, 5xx, etc.); the human-readable detail is in `message`.

The `unidrive refresh` CLI client treats any non-`ok=true` terminal as exit 1 with the `message` field printed to stderr.

### 4.3 New verb: `daemon.status`

**Request:**

```json
{"verb": "daemon.status"}
```

**Reply (always synchronous, `ok: true`):**

```json
{
  "ok": true,
  "uptime_ms": 3600000,
  "clients_connected": 2,
  "refresh_in_flight": false,
  "refresh_job_id": null
}
```

If a refresh is running:

```json
{
  "ok": true,
  "uptime_ms": 3600000,
  "clients_connected": 2,
  "refresh_in_flight": true,
  "refresh_job_id": "9e08915a-..."
}
```

Read-only verb; takes no parameters; never returns `ok: false` (a daemon that can reply to verbs is by definition functional enough to answer this one). PID and profile name are deliberately NOT in the response — those come from `.lock.pid` and are knowable without the daemon being reachable (avoiding the chicken-and-egg case where "is the daemon up?" requires the daemon to be up). The verb adds value only for the data that cannot be derived from the file system: uptime, connected-clients count, refresh-in-flight state.

### 4.4 `sync.subscribe` (existing, unchanged semantics)

The verb stays registered on the daemon's `IpcServer` with the same `scheduleAfterReply` post-reply hook mechanism (β) the `SyncCommand` uses today. On the daemon side, the only events emitted are refresh-progress events (the daemon has no reconcile loop). On the `SyncCommand` side today, the same verb emits sync-progress events. Both implementations honor the same subscriber-set contract; the event source differs by which IPC server the client connects to.

This is intentional: a single subscription mechanism, multiple event sources. When phase-2 (sync-as-daemon-client) lands, the daemon will emit both refresh AND sync events on the same subscriber stream; `sync.subscribe` semantics don't need to change again.

### 4.5 Mode-mutex amendment

The `.lock.pid` sidecar wire format stays `<pid> <mode>\n`. The legal `<mode>` values become:

- `sync` — held by `unidrive sync` (existing).
- `daemon` — held by `unidrive daemon run` (new).
- ~~`mount`~~ — **removed.** Any pre-existing `.lock.pid` containing `mount` is treated by `readHolderInfo()` as an unknown mode token (existing forward-compat path), producing `HolderInfo(mode=null, rawMode="mount")`. The contention error names the unknown mode verbatim.

Backwards-compat for legacy pid-only sidecars stays unchanged: `<pid>\n` (no mode token) reads as `Mode.SYNC`.

### 4.6 Path normalisation

Unchanged from existing IPC contract. The new verbs follow the same conventions as the existing ones (`trimEnd('/')` per spec `hydration-namespace-verbs-design.md` §3.1, paths are absolute starting with `/`, empty path or `/` means root).

## 5. Invariants and failure modes

### 5.1 Invariants

- **I1: One daemon per profile.** Enforced by `ProcessLock.Mode.DAEMON` acquisition before any other startup work. If the lock is held, the second daemon refuses to start with a clear contention message.
- **I2: Sync ⇄ Daemon mutual exclusion per profile.** Same lock file, two-way mutex. `Mode.MOUNT` is removed.
- **I3: Socket existence implies serving daemon.** If `/run/user/<uid>/unidrive-<profile>.sock` exists, a daemon process is bound to it. Stale sockets are detected and cleaned by `IpcServer.reclaimStaleSocket()` (at `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt:341`) on next startup.
- **I4: `.lock.pid` and bound socket are atomic.** Either both exist (daemon running) or neither (daemon stopped). The sequence is: acquire lock + write `.lock.pid` → authenticate → bind socket. On clean shutdown: close socket → release lock + remove `.lock.pid`. On `kill -9`: the kernel releases the file lock, but `.lock.pid` and the socket file may remain. Stale-pid detection (existing in `acquireProfileLock`) and `reclaimStaleSocket` handle this on the next start.
- **I5: Auth state is binary at startup.** Authenticated or refuses to bind. Token expiry mid-run does NOT shut down the daemon — the provider HTTP client handles refresh on 401; only an unrecoverable auth failure surfaces as `provider_error` to clients.
- **I6: At most one `refresh.run` in flight.** The daemon serialises `refresh.run` calls via an internal mutex/atomic flag. A second concurrent call gets `{"ok": false, "error": "busy"}`.
- **I7: Graceful-shutdown bounded.** SIGTERM → refuse new connections → wait for in-flight hydration RPCs (each capped at the existing IPC write/read timeout) → cancel in-flight `refresh.run` → hard deadline 10s → `exitProcess(143)` by the supervisor or self-exit. State.db is closed cleanly in all paths via `try/finally`.
- **I8: State.db transactions are inherited from existing code.** The daemon does NOT introduce new long-lived transactions. Each hydration RPC delegates to one or more `StateDatabase` methods; `refresh.run` delegates to existing `SyncEngine` refresh code paths, which already have battle-tested batching. The codebase runs SQLite in its default **rollback-journal** mode with `autoCommit = true` (`StateDatabase.initialize()` at `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:52-63` — no `PRAGMA journal_mode=WAL` is issued), and every public `StateDatabase` method carries `@Synchronized`. Power-outage durability: each `@Synchronized` method (and the explicit `beginBatch` / `commitBatch` / `rollbackBatch` triplet at lines 318-336) is a single transaction; SQLite's rollback journal makes each committed transaction crash-consistent. Concurrent-access cost: every refresh-side write serialises on the same monitor as every hydration-side read. During a multi-minute `refresh.run`, mount-side `hydration.list` / `hydration.read` calls block waiting for the monitor between each refresh batch — bounded by the per-batch duration (tens of ms typical), not the full refresh wall-clock. This is acceptable for the basic mount UX but is a known property that the implementer should be aware of when writing T5 and when sizing the refresh batch interval. A future WAL-mode migration (separate spec; would unlock concurrent readers) is out of scope here.

### 5.2 Failure mode catalog

| # | Failure | Detection | Daemon response | Client-visible effect |
|---|---|---|---|---|
| F1 | Lock contention (sync or another daemon running) | `tryLock(Mode.DAEMON)` returns false | Print mode-specific contention error, exit 1 | n/a (daemon never started) |
| F2 | Auth failure at startup | Exception from `provider.authenticateAndLog()` | Release lock, exit 1 with stderr message | Mount/refresh clients see "Connection refused"; operator hint points at `unidrive daemon run` |
| F3 | Socket bind fails post-reclaim (permission denied, FS full, AF_UNIX unsupported) | `IOException` from `IpcServer.start()` after `reclaimStaleSocket` ran | Release lock, exit 1 with explicit "could not bind socket: <cause>" stderr | Operator sees the exact bind failure cause; no retry, no ambiguity |
| F4 | State.db corrupted / locked | SQLException during open | Release lock, exit 1; do NOT delete state.db | Operator instruction: "state.db at <path> could not be opened: ..." |
| F5 | Hydration RPC fails mid-call (provider 500) | `provider_error` from underlying call | Reply `{"ok": false, "error": "provider_error", "message": "..."}` | Mount: EIO from the FUSE op; operator may retry |
| F6 | `refresh.run` fails mid-job (network, 401-loop, etc.) | Exception caught at handler boundary | Emit `{"event": "refresh.done", "ok": false, "error": "..."}` to subscribers; mark job cleared; daemon stays up | `unidrive refresh` CLI prints error and exits 1; daemon ready for retry |
| F7 | Client disconnects mid-RPC | EOF on socket read/write | Drop the connection, mark its subscriptions cleaned (existing `IpcServer` behaviour) | n/a (client already gone) |
| F8 | `kill -9` of daemon | Kernel releases file lock | n/a (process gone) | `.lock.pid` + socket file remain stale; next daemon start sees stale-pid and `reclaimStaleSocket` cleans both |
| F9 | SIGTERM during `refresh.run` | Shutdown hook sets cancel flag; child coroutine receives `CancellationException` | Refresh coroutine cancels at the next suspension point, attempts to emit `{"event": "refresh.done", "ok": false, "error": "shutdown"}` to subscribers, daemon proceeds to close the IpcServer and exits within 10s. Terminal-event delivery is **best-effort**: `IpcServer.close()` at `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt:319-339` cancels the broadcast job BEFORE closing client channels, so if the refresh coroutine's terminal-event emit loses the race against `close()`, the event is dropped silently (RENDEZVOUS channel semantics). Clients MUST treat socket EOF as equivalent to a `refresh.done error=shutdown` event for shutdown-correctness; the `unidrive refresh` CLI implements this by mapping EOF → exit 1 with stderr "daemon shut down before refresh completed." The 10s hard deadline (I7) is the backstop — if cancellation does not propagate (blocking OkHttp without `Dispatchers.IO` wrapping; SQLite transaction commit in flight that's already past its cancellation point), the supervisor calls `exitProcess(143)` regardless. State.db rollback-journal recovery protects committed work. Implementation note: audit OneDrive + Internxt HTTP clients during implementation for `withContext(Dispatchers.IO)` coverage; file a follow-up if gaps surface. | `unidrive refresh` CLI sees terminal event with `error: shutdown` OR sees socket EOF (handles both paths identically); exits 1 |
| F10 | SIGTERM with no in-flight RPCs | Shutdown hook fires | Close socket immediately, release lock, exit 0 | `daemon stop` returns success |

### 5.3 Out of scope (recorded so the reviewer can confirm)

- **No daemon-side rate limiting on `refresh.run`.** Operator can issue back-to-back refreshes; second one gets `busy` error while the first runs. Acceptable until evidence shows otherwise.
- **No persistent refresh cursor.** A `refresh.run` always re-enumerates from scratch. Persistent cursors are the tracking-set engine's concern.
- **No client-side reconnect logic added.** The Rust co-daemon already has `ReconnectingIpcClient` in `unidrive-mount-linux/mount/src/reconnect.rs` for the existing connection. Daemon restart while mount is running is handled by that pre-existing client-side machinery.
- **No daemon health-check broadcast beyond `daemon.status`.** Clients wanting liveness probes use `daemon.status` polling. No heartbeat.

## 6. Test surface (named)

Five spec-named tests pin the contract changes. Other tests (graceful-shutdown timing, client error rendering, `busy` rejection, `daemon.status` shape, `reclaimStaleSocket` behaviour) are required for shipping but left to implementer judgment for exact naming and module placement.

### T1 — `daemon_binds_socket_and_serves_hydration_verbs`

**Module:** `core/app/cli/` (new test class `DaemonCommandTest`).
**Coverage:** Start the daemon (via a test harness that wires picocli without re-spawning a JVM); connect a fake IPC client; issue `hydration.list` against a fake provider; receive `{"ok": true, ...}`. Tear down via SIGTERM.
**What it pins:** Daemon startup → auth → bind → register handlers → serve → graceful shutdown happy path.

### T2 — `daemon_refuses_to_start_when_sync_holds_lock`

**Module:** `core/app/cli/` (extension to existing `ProfileLockFactoryTest`).
**Coverage:** Acquire `ProcessLock(Mode.SYNC)` from the test; attempt to acquire `ProcessLock(Mode.DAEMON)` from a second `ProcessLock` instance on the same lock file; assert it returns false; assert the rendered contention error names `unidrive sync`.
**What it pins:** Sync ⇒ daemon exclusion + error rendering (G6).

### T3 — `sync_refuses_to_start_when_daemon_holds_lock`

**Module:** same as T2.
**Coverage:** Symmetric to T2. Acquire `ProcessLock(Mode.DAEMON)`; attempt to acquire `Mode.SYNC`; assert refused with daemon-holder error.
**What it pins:** Daemon ⇒ sync exclusion. Symmetric naming so removing T2 OR T3 in a future refactor can't silently weaken the contract (CLAUDE.md orthogonal-invariant-decomposition rule).

### T4 — `daemon_fail_fast_on_auth_failure_does_not_bind_socket`

**Module:** `core/app/cli/`.
**Coverage:** Construct a `Provider` mock that throws `AuthenticationException` from `authenticateAndLog()`; run the daemon's startup sequence; assert (a) the daemon exited with non-zero, (b) the lock file was released (no `.lock.pid` left behind), (c) the socket file at the expected path does NOT exist.
**What it pins:** G7 contract + invariant I4. Failed auth must not leave a half-functional daemon.

### T5 — `refresh_run_emits_terminal_event_after_completing_enumeration`

**Module:** `core/app/cli/` or `core/app/sync/` — whichever has the easier path to the existing fake-provider fixture used by today's `RefreshCommand` tests.
**Coverage:** Start the daemon against a fake provider that yields a small, finite cloud tree (e.g. 3 folders + 5 files); subscribe via `sync.subscribe`; issue `refresh.run`; await the terminal `{"event": "refresh.done", "ok": true}` event; assert state.db contains expected rows; issue `refresh.run` again and assert it succeeds (not stuck in `busy`).
**What it pins:** I6 (refresh serialisation + clearing) + post-reply hook pattern + state.db population end-to-end.

### Tests deliberately NOT named in the spec (implementer's choice)

- Daemon graceful shutdown timing — wall-clock-sensitive; harness choice is implementer's call.
- Mount client error rendering when daemon down — coverage required; exact test name is implementer's call.
- `refresh.run` `busy` rejection on concurrent call — covered by T5's "issue refresh again" step OR a dedicated test, implementer's call.
- `daemon.status` verb returns expected shape — straightforward verb test, no spec-naming needed.
- `reclaimStaleSocket` integration — already covered by IpcServer's own test suite.

## 7. Live smoke (operator runs at close-out)

```
# 1. Start daemon (terminal A, foreground)
unidrive daemon run posteo_onedrive
# Expected: prints "daemon ready, pid <N>, socket /run/user/<uid>/unidrive-posteo_onedrive.sock"
# Stays foreground.

# 2. Daemon contention (terminal B)
unidrive sync posteo_onedrive
# Expected: exit 1, stderr:
#   Profile 'posteo_onedrive' is currently in use by `unidrive daemon` (PID <N>).
#   Stop the daemon first: `unidrive daemon stop posteo_onedrive`.

# 3. Status (terminal B, daemon still running in A)
unidrive daemon status posteo_onedrive
# Expected: prints PID + mode from .lock.pid AND uptime + clients + refresh state from RPC.

# 4. Refresh + mount (terminal B)
unidrive refresh posteo_onedrive
# Expected: progress events stream to stdout; exits 0 on completion.
unidrive mount posteo_onedrive /tmp/onedrive-smoke
# Expected: foreground; FUSE mount appears.

# 5. (terminal C) Browse, mkdir, unlink, rmdir
ls /tmp/onedrive-smoke
mkdir /tmp/onedrive-smoke/smoke_folder
echo hello > /tmp/onedrive-smoke/smoke_folder/hello.txt
rm /tmp/onedrive-smoke/smoke_folder/hello.txt
rmdir /tmp/onedrive-smoke/smoke_folder
# All must succeed; check webapp for the round-trip.

# 6. Graceful stop (terminal D)
unidrive daemon stop posteo_onedrive
# Expected: daemon exits cleanly in <10s, mount terminal A's mount exits (co-daemon
# notices socket gone, FUSE unmounts).

# 7. Daemon-not-running error path (terminal D)
unidrive mount posteo_onedrive /tmp/onedrive-smoke
# Expected: exit 1, stderr "daemon for profile 'posteo_onedrive' is not running.
# Start it first: unidrive daemon run posteo_onedrive"
```

If any step diverges from the expected output, file the divergence as a finding before merge.

## 8. Open questions deferred to plan / implementation

None at spec time. The plan will surface concrete questions about:
- Exact picocli wiring for the `daemon` subcommand group (matches existing `vault`, `profile` patterns).
- Whether `DaemonStatusCommand` + `DaemonStopCommand` are nested under `daemon` (`unidrive daemon status`) or top-level (`unidrive daemon-status`). Recommendation: nested per existing pattern.
- The exact `RefreshRpcHandler` coroutine structure (do we launch refresh as a child of the IpcServer's scope, or own a separate `SupervisorJob` for it). Implementation choice; pin via T5.
