# IPC Transport Dispatcher Isolation — Design

**Status:** Proposed — design doc, not yet implemented
**Origin:** Phase 2 smoke-test finding, BACKLOG entry committed in `da193f7`
**Touches:** `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` (single file)

## 1. Problem

During the first live Phase 2 mount against the `posteo_onedrive` profile, the daemon dropped the FUSE co-daemon's IPC client mid-reply:

- `unidrive mount` came up cleanly. Verb registration was correct (fix in `e2e5366`).
- First `ls` on the FUSE mount succeeded — readdir + getattr against a freshly-populated `state.db`.
- `less geheim.txt` (a 52-byte file) failed with `EIO`.
- Daemon log:
  ```
  IPC: dropping dead client … Write timeout exceeded for IPC client
  ```
  concurrent with dozens of `Download: /Annika Pixel 4/Admin/Camera/...mp4 (69 MB)` lines.

### 1.1 Root cause

`IpcServer` runs all of its IO on the JVM-wide `Dispatchers.IO`:

- The accept loop and per-client reader (`IpcServer.kt:148, 163, 207`) launch with `Dispatchers.IO`.
- Verb handlers registered via `registerHandler` (`IpcServer.kt:84`) inherit the dispatcher of whatever called them — which, today, is the per-client read loop, also `Dispatchers.IO`.
- The non-blocking write busy-loop (`writeNonBlocking`, `IpcServer.kt:371-385`) has a 5-second hard deadline (`WRITE_TIMEOUT_NS = 5_000_000_000L`, `IpcServer.kt:389`).

`SyncEngine` saturates `Dispatchers.IO` with concurrent HTTP downloads. When the IO pool is full, an IPC write that returns `0` bytes (kernel buffer full or socket back-pressured) sits in `Thread.sleep(10)` until either bytes drain or the 5s deadline lapses. Under sync saturation, the deadline can lapse before the next dispatcher slice becomes available, and the broadcast loop kicks the client.

### 1.2 What this design does NOT address

- `SyncEngine`'s unbounded download concurrency. Filed separately (BACKLOG option c). Bounding sync IO is still worth doing, but it's a different invariant.
- The UD-265 deletion-safeguard trip on `internxt_gernot_krost_posteo`. Adjacent BACKLOG entry; separate fix path.

## 2. Goals & non-goals

**Goal G1:** IPC writes complete within their budget regardless of how saturated `Dispatchers.IO` becomes from sync, hydration handler bodies, or any other JVM-wide IO work.

**Goal G2:** Verb handler bodies (the things that actually do work — state.db queries, hydration coordination, cache lookups) continue to run on `Dispatchers.IO`, so handler work can scale with the rest of the JVM's IO.

**Goal G3:** A single mount client cannot DoS other IPC consumers (`unidrive status`, future `unidrive watch`, the user's CLI) by holding the IPC transport.

**Goal G4:** The write timeout becomes operator-tunable without a code change, so emergencies (laptop sleep, kernel-side throttling, slow targets) don't require a rebuild.

**Non-goal NG1:** Splitting `IpcServer.kt` into multiple files. Considered (Approach B in brainstorm); rejected as YAGNI under CLAUDE.md "minimal changes".

**Non-goal NG2:** Changing the wire protocol, the verb-dispatch shape, or any public API of `IpcServer`. The only new public constructor parameter is an optional dispatcher (for test injection).

**Non-goal NG3:** Removing the 5s timeout. The timeout protects against truly stuck clients (slow consumer, dead socket); we only want it not to fire spuriously under sync load.

## 3. Design

### 3.1 Topology

```
                       Dispatchers.IO  (JVM-wide, elastic)
                       ─────────────────────────────────
                       SyncEngine downloads ──┐
                       Hydration handlers ────┤  (handler bodies offloaded here)
                       Cache scan ───────────┤
                       Anything else IO ─────┘

                                              ▲
                                              │ withContext(Dispatchers.IO)
                                              │ around handler(connId, line)
                                              │
                       Transport dispatcher  (4 threads, owned by IpcServer)
                       ───────────────────────────────────────────────────
                       Accept loop
                       Per-client reader  (parses framing, sees \n)
                       dispatchRequest    (writes reply line)
                       Broadcast loop     (fans out emit()'d JSON)
                       writeToConnection  (subscriber pipeline)
                       flushStateDump     (initial state to fresh client)
```

The transport pool is dedicated to socket I/O and framing. Handler invocation explicitly bounces to `Dispatchers.IO`. Under sync saturation, the transport pool always has spare capacity to write a reply — the 5s budget becomes structurally defensible.

### 3.2 IpcServer changes

#### 3.2.1 Constructor signature

```kotlin
class IpcServer(
    private val socketPath: Path,
    private val transportDispatcher: CoroutineDispatcher? = null,
    private val handlerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val writeTimeoutMs: Long = readWriteTimeoutFromEnv(),
)
```

- `transportDispatcher`: nullable. If null, `start()` allocates `Executors.newFixedThreadPool(4, namedFactory("ipc-io"))` and owns its lifecycle (shut down in `close()`). If non-null, the caller owns it (tests inject `StandardTestDispatcher` or `UnconfinedTestDispatcher`).
- `handlerDispatcher`: defaults to `Dispatchers.IO`. Exists primarily for tests, so handler latency can be controlled deterministically.
- `writeTimeoutMs`: per-instance, captured at construction. Default comes from `readWriteTimeoutFromEnv()` (see §3.2.4).

Existing callers (`SyncCommand`, `IpcProgressReporter`, tests) construct with just `IpcServer(socketPath)` and get the defaults — no breaking change.

#### 3.2.2 Thread pool lifecycle

```kotlin
private var ownedTransport: java.util.concurrent.ExecutorService? = null

fun start(scope: CoroutineScope) {
    // … existing reclaim + bind …
    val transport = transportDispatcher ?: run {
        val es = Executors.newFixedThreadPool(4, ipcIoThreadFactory())
        ownedTransport = es
        es.asCoroutineDispatcher()
    }
    // … all scope.launch(Dispatchers.IO) → scope.launch(transport) …
}

fun close() {
    // … existing socket teardown …
    ownedTransport?.shutdown()
    ownedTransport?.awaitTermination(5, TimeUnit.SECONDS)
    ownedTransport = null
}
```

Thread factory names threads `ipc-io-1`..`ipc-io-4` so grep'ing the daemon log can attribute stacks. `awaitTermination(5s)` is best-effort; if it expires we don't `shutdownNow()` — outstanding writes finishing late is fine, leaked threads in tests are a separate problem we'd notice via JVM thread-count metrics. (Open question: do we need `shutdownNow()` after the await? Decision: no — `close()` already closes the server channel and client sockets, so any thread still in `writeNonBlocking` will exit via `IOException` on the closed channel within one `Thread.sleep(10)` iteration.)

#### 3.2.3 Handler offload

```kotlin
private suspend fun dispatchRequest(client: SocketChannel, connId: String, line: String) {
    val verb = parseVerb(line) ?: return  // existing logging
    val handler = handlers[verb] ?: return  // existing logging
    val reply = try {
        withContext(handlerDispatcher) { handler(connId, line) }
    } catch (e: Exception) {
        // existing error envelope
    }
    // existing reply write under writeMutex
}
```

The `withContext(handlerDispatcher)` switch is the one piece of new behavior that handler authors need to know about. **Implication:** any handler that today relies on already being on the read-loop's dispatcher (none do, today, but it's worth stating) would need to be re-checked. Mitigation: covered by §3.4 testing — every existing handler-using test must still pass.

#### 3.2.4 Configurable write timeout

```kotlin
private companion object {
    fun readWriteTimeoutFromEnv(): Long {
        val raw = System.getenv("UNIDRIVE_IPC_WRITE_TIMEOUT_MS") ?: return 5_000L
        return raw.toLongOrNull()?.takeIf { it in 100L..600_000L } ?: 5_000L
    }
}

private val writeTimeoutNs = writeTimeoutMs * 1_000_000L

private fun writeNonBlocking(client: SocketChannel, buf: ByteBuffer) {
    val deadline = System.nanoTime() + writeTimeoutNs
    // … unchanged loop …
}
```

- Range guard `100ms..600s` prevents pathological values (`0`, negative, multi-hour).
- Bad strings silently fall back to 5s — this is a tuning knob, not a contract.
- Log the effective value once at `start()` so operators can confirm the env var took effect.

### 3.3 Migration / call-site survey

The accept loop, the per-client reader sub-launch, and the broadcast loop are the three `scope.launch(Dispatchers.IO)` sites in `IpcServer.kt:148, 163, 207`. All three become `scope.launch(transport)`. No call site outside `IpcServer.kt` references its dispatcher choice — searched the tree for `Dispatchers.IO` near `IpcServer`/`IpcClient` usage; none found.

`IpcProgressReporter.kt`, `SyncCommand.kt`, `MountCommand.kt`, and the hydration verb registrations are unaffected; they call `IpcServer.registerHandler(...)` / `IpcServer.emit(...)` and don't care which dispatcher executes the handler.

### 3.4 Testing

Four tests are required. The first three are existing-behavior smoke (the green ones still go green); the fourth is the new invariant the structural fix delivers.

**T1 — existing IPC integration suite still passes.** No change to `IpcServerTest`, `IpcClientTest`, `IpcProgressReporterTest`. If any break, the fix has changed observable semantics and that's a bug.

**T2 — handler-dispatcher offload is observable.** A new test registers a verb whose handler returns `Thread.currentThread().name`. Drive the verb over a real `IpcServer` with the default constructor and assert the returned name matches the JVM IO pool naming pattern (`DefaultDispatcher-worker-N` or whichever pattern `kotlinx.coroutines` uses on this JVM) and does **not** start with `ipc-io-`. Records the invariant: handlers run off the transport pool.

**T3 — transport pool is named and bounded.** After `start()`, assert that `Thread.getAllStackTraces().keys` contains at least one thread whose name starts with `ipc-io-` and that no more than 4 such threads exist. After `close()`, assert all `ipc-io-` threads exit within 5 seconds (`awaitTermination` budget).

**T4 — the actual race window.** This is the test that justifies the entire change. Construct an `IpcServer` with a handler that does `delay(10_000)` while holding a slot on the `handlerDispatcher`. Spin up `N=8` concurrent clients all firing that verb. While they're all parked, a 9th client fires a fast verb (`ping`-shaped). Assert the 9th client gets its reply within 1 second — i.e. handler saturation cannot block IPC transport writes. **Failure of this test means the structural fix didn't take.** This test is the contract per CLAUDE.md's "orthogonal invariant decomposition" — its name and presence are what protect the invariant going forward.

Test names (proposed):
- `transport_dispatcher_uses_ipc_io_pool_not_dispatchers_io`
- `handler_invocation_is_offloaded_to_handler_dispatcher`
- `transport_pool_is_capped_at_four_threads_and_shuts_down_on_close`
- `fast_client_is_not_blocked_by_saturated_handler_dispatcher` ← the contract test

### 3.5 Live smoke test (manual, post-merge)

The original finding was a live-smoke EIO. The fix has to be validated the same way:

1. Build a snapshot JAR with the change.
2. Restart the `posteo_onedrive` daemon under the new build.
3. `unidrive mount /tmp/onedrive` against it.
4. While the SyncEngine is mid-download (logs show `Download: ... (69 MB)`), run `less /tmp/onedrive/geheim.txt`.
5. Expect: file content prints. Daemon log shows no `Write timeout exceeded` entries during the read.
6. If the timeout still fires: the fix is incomplete — re-open the BACKLOG entry.

This step is documented in the spec so the plan-mode implementation doesn't claim "done" before the actual reproducer is exercised. AGENTS.md "Smoke test on the actual target" applies.

## 4. Risks and open questions

**R1 — Test T4 may be flaky on CI under load.** A 1-second wall-clock budget for the fast-client reply is generous on a developer laptop but could tighten on a shared CI runner. Mitigation: budget is 1s, expected response is ~10ms; 100x headroom should be enough. If it flakes, raise to 3s — still proves the invariant.

**R2 — Pool size of 4 may be wrong.** With `MAX_CLIENTS=10` and `writeMutex` serialising per-client, the worst case is 10 concurrent broadcasts (the broadcast loop iterates clients sequentially, so realistically 1). The accept loop + reader loops are mostly blocked on `read()` or `delay(20)`, not CPU-bound. 4 threads is a guess; we'll know better after a few weeks of production runs. The pool size could become configurable later if data demands it.

**R3 — `awaitTermination(5s)` could leak threads in pathological close paths.** If a thread is stuck in `writeNonBlocking` on a half-dead socket, `close()`'s socket teardown should kick it out via `IOException` within one `Thread.sleep(10)` tick. If somehow it doesn't, we'd see a "thread leak" warning in long-running test suites. Decision: tolerate this for now; add `shutdownNow()` after the await only if we observe leaks. (YAGNI.)

**R4 — `withContext(Dispatchers.IO)` per request adds dispatch overhead.** One context switch per IPC request. Measured cost on `kotlinx.coroutines` 1.8.x is ~µs-scale. The smallest hydration request (a `getattr` round-trip) is already in the hundreds-of-µs range, so the relative overhead is negligible. Not worth optimising.

## 5. Acceptance

- All four tests in §3.4 pass.
- Existing IPC test suites unchanged and green.
- Manual live smoke in §3.5 passes against the `posteo_onedrive` profile.
- BACKLOG entry for "IPC write-timeout drops Phase 2 mount clients during heavy concurrent sync load" moves to `CLOSED.md` in the same commit that lands the fix.
- New constant `UNIDRIVE_IPC_WRITE_TIMEOUT_MS` env var documented in the daemon's environment-variables reference (wherever those are tracked — to be confirmed in plan).
