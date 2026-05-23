# IPC Transport Dispatcher Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all IPC socket I/O off `Dispatchers.IO` onto a dedicated 4-thread transport pool owned by `IpcServer`, offload verb handlers back to `Dispatchers.IO` via `withContext`, and make `WRITE_TIMEOUT_NS` env-tunable — so IPC writes can't be starved by `SyncEngine` HTTP downloads saturating `Dispatchers.IO`.

**Architecture:** One file changes meaningfully: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`. The constructor grows three optional parameters (`transportDispatcher`, `handlerDispatcher`, `writeTimeoutMs`) with defaults that match today's behavior. `start()` allocates a `ThreadPoolExecutor` (4 threads, named `ipc-io-N`) when no transport was injected; `close()` shuts it down with a 5s `awaitTermination` budget. The three existing `scope.launch(Dispatchers.IO)` sites become `scope.launch(transport)`. `dispatchRequest` wraps the handler call in `withContext(handlerDispatcher)`. A new `docs/env-vars.md` documents `UNIDRIVE_IPC_WRITE_TIMEOUT_MS` (already created in the spec commit). Four new tests live alongside the existing `IpcServerTest`.

**Tech Stack:** Kotlin (JVM 21), kotlinx.coroutines 1.8.x, `java.nio.channels.ServerSocketChannel`/`SocketChannel` (Unix domain), `java.util.concurrent.Executors`/`ThreadPoolExecutor`, JUnit 5 via `kotlin.test`. Build via Gradle composite — `cd core && ./gradlew :app:sync:test -q` is the local feedback loop.

**Spec:** `docs/superpowers/specs/ipc-transport-dispatcher-isolation-design.md`

---

## Pre-flight

- [ ] **Step 0: Verify clean working tree and confirm branch**

Run:
```bash
cd /home/gernot/dev/git/unidrive
git status --short
git branch --show-current
```
Expected: empty output from `git status --short`; current branch is `main` (or, if the executing agent chose to branch, `fix/ipc-transport-dispatcher`). If branching, run `git checkout -b fix/ipc-transport-dispatcher` once; subsequent commits stay on that branch. This plan assumes the agent will create that branch — small fix, doesn't need a worktree.

- [ ] **Step 0a: Confirm the existing IPC test suite is green before any change**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcServerTest" --tests "org.krost.unidrive.sync.IpcServerPermissionsTest" --tests "org.krost.unidrive.sync.IpcProgressReporterTest" -q > /tmp/ipc-baseline.log 2>&1 || (grep -E "FAILED|ERROR|Exception" -C 5 /tmp/ipc-baseline.log && exit 1)
```
Expected: command exits 0 (suite green). If anything fails, STOP and surface the failure to the user — the baseline is broken and you can't tell which subsequent changes regressed what. Do not commit anything until baseline is green.

---

## File Structure

This implementation touches exactly two source files and adds one test file.

| File | Role | Status |
|---|---|---|
| `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` | The fix itself — constructor params, dispatcher wiring, handler offload, env-var read. | Modify |
| `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcDispatcherIsolationTest.kt` | New test class housing the four invariant tests (T1 lives in the existing suite by virtue of not changing). | Create |
| `docs/env-vars.md` | Already created in the spec commit (`9e0c1e6`). No change. | (Exists) |

Tests for the new behavior live in a new test class rather than being grafted onto `IpcServerTest` for two reasons: (a) they share a different harness shape — most of them spin up a server with a *custom* `handlerDispatcher` so they can deterministically saturate it, whereas `IpcServerTest` always uses the defaults; (b) keeping them grouped makes the contract obvious to future readers — open the file, see four tests, see what invariant each one protects.

---

## Task 1: Confirm the bug with a failing red test

**Why this task exists:** Per TDD discipline, before changing `IpcServer.kt` we want a test that demonstrably fails today and will pass after the fix. The natural shape is the §3.4 T4 "contract test" — the most important of the four, and the one whose failure means the fix didn't take. Writing it first locks in the invariant. The other three new tests (T2/T3 plus an env-var test) land in later tasks because they exercise machinery (`ipc-io-N` thread names, `handlerDispatcher` injection) that doesn't exist yet.

**Files:**
- Create: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcDispatcherIsolationTest.kt`

- [ ] **Step 1.1: Create the new test file with the contract test**

Create `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcDispatcherIsolationTest.kt`:

```kotlin
package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Protects the invariants from `docs/superpowers/specs/ipc-transport-dispatcher-isolation-design.md`.
 *
 * If any of these tests are deleted or loosened, the corresponding invariant
 * silently regresses and the Phase 2 IPC write-timeout bug returns under
 * Dispatchers.IO saturation.
 */
class IpcDispatcherIsolationTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-isolation-test")
        socketPath = socketDir.resolve("test.sock")
    }

    @AfterTest
    fun tearDown() {
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir)
    }

    private fun connectClient(): SocketChannel {
        val client = SocketChannel.open(StandardProtocolFamily.UNIX)
        client.connect(UnixDomainSocketAddress.of(socketPath))
        client.configureBlocking(false)
        return client
    }

    private suspend fun readOneLine(client: SocketChannel, timeoutMs: Long): String? {
        val buf = ByteBuffer.allocate(4096)
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            buf.clear()
            val n = client.read(buf)
            if (n > 0) {
                buf.flip()
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                sb.append(String(bytes, Charsets.UTF_8))
                val nl = sb.indexOf('\n')
                if (nl >= 0) return sb.substring(0, nl)
            }
            delay(20)
        }
        return null
    }

    private fun sendVerb(client: SocketChannel, verb: String) {
        val line = """{"verb":"$verb"}""" + "\n"
        val buf = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
        while (buf.hasRemaining()) client.write(buf)
    }

    /**
     * The contract test for the structural fix. Saturate the handler dispatcher
     * with N slow handlers, then verify a fast verb on a fresh client still
     * gets its reply within 1 second. Failure means handler saturation is
     * blocking IPC transport writes — i.e. the structural fix didn't take.
     */
    @Test
    fun fast_client_is_not_blocked_by_saturated_handler_dispatcher() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val slowEntered = AtomicInteger(0)
                val s = IpcServer(socketPath)
                server = s
                s.registerHandler("slow") { _, _ ->
                    slowEntered.incrementAndGet()
                    delay(10_000)
                    """{"event":"slow_done"}"""
                }
                s.registerHandler("ping") { _, _ -> """{"event":"pong"}""" }
                s.start(serverScope)

                // Saturate: 8 clients each holding a slow handler in flight.
                val slowClients = (1..8).map { connectClient() }
                for (c in slowClients) sendVerb(c, "slow")

                // Wait until all 8 handlers have actually entered the body.
                val deadline = System.currentTimeMillis() + 5_000
                while (slowEntered.get() < 8 && System.currentTimeMillis() < deadline) {
                    delay(20)
                }
                check(slowEntered.get() == 8) {
                    "Pre-condition failed: only ${slowEntered.get()}/8 slow handlers entered. " +
                        "Test cannot prove the invariant under partial saturation."
                }

                // Fast client should get its reply quickly despite the saturation.
                val fast = connectClient()
                val t0 = System.currentTimeMillis()
                sendVerb(fast, "ping")
                val line = readOneLine(fast, timeoutMs = 1_000)
                val elapsed = System.currentTimeMillis() - t0
                if (line == null || !line.contains("pong")) {
                    fail(
                        "Fast client did not receive 'pong' within 1000ms despite isolated " +
                            "transport. Saturated handlers blocked the transport — structural " +
                            "fix didn't take. Got: $line (elapsed=${elapsed}ms)",
                    )
                }
                assertTrue(elapsed < 1_000, "Fast client took ${elapsed}ms (expected <1000ms)")
                slowClients.forEach { runCatching { it.close() } }
                fast.close()
            } finally {
                serverScope.cancel()
            }
        }
}
```

- [ ] **Step 1.2: Run the test and confirm it fails on today's code**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcDispatcherIsolationTest" -q > /tmp/red.log 2>&1
echo "exit=$?"
grep -E "FAILED|fast_client" /tmp/red.log -C 3
```
Expected on today's code (no fix yet): exit non-zero. The failure mode is one of:
- `IpcServer` constructor signature accepts only `socketPath`, the new test compiles fine (it uses the default constructor) — but at runtime, `slow` handlers running on `Dispatchers.IO` *will* block other handlers too, depending on the IO pool size on the test JVM. The assertion may fail with "Fast client did not receive 'pong' within 1000ms" OR it may pass coincidentally if the JVM IO pool happens to have spare workers. The test must reliably fail, so step 1.3 instruments deterministic failure.

- [ ] **Step 1.3: If the test passes coincidentally on today's code, tighten the saturation**

If step 1.2 reports the test passing on unmodified code, that means the JVM IO pool on the test machine has more than 8 slack workers and the saturation didn't bite. Add more pressure: change `(1..8)` to `(1..32)` in the test. Re-run step 1.2. If still passing on unmodified code, escalate to `(1..64)` and re-run. The number that reliably fails today is the number that locks the invariant. (`kotlinx.coroutines` 1.8 caps `Dispatchers.IO` at `64` workers by default on most JVMs, so 64 always saturates.) Document the chosen N in a code comment on the slow-client list:

```kotlin
// N=NN chosen because the default Dispatchers.IO worker cap on this JVM is 64;
// saturating beyond the cap is what makes this test deterministic regardless
// of the fix's state. Lower N first to confirm the IO pool truly leaks.
val slowClients = (1..NN).map { connectClient() }
```

Do not proceed to Task 2 until step 1.2 reliably reports the test as FAILED on unmodified code.

- [ ] **Step 1.4: Commit the failing test**

Run:
```bash
git add core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcDispatcherIsolationTest.kt
git commit -m "test(ipc): failing contract test for transport-handler dispatcher isolation"
```

---

## Task 2: Add the transport dispatcher and offload handlers

Two changes land in a single commit because they're a structural pair — adding the transport pool without the handler offload would be incomplete, and vice versa. This is the green half of the contract test.

**Files:**
- Modify: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`

- [ ] **Step 2.1: Add constructor parameters and lifecycle field**

Edit `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`. The current constructor is:

```kotlin
class IpcServer(
    private val socketPath: Path,
) {
```

Replace with:

```kotlin
class IpcServer(
    private val socketPath: Path,
    private val transportDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    private val handlerDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    writeTimeoutMs: Long = readWriteTimeoutFromEnv(),
) {
    // Captured at construction so a later companion-object change can't accidentally
    // re-read the env var mid-flight. Multiply once into nanos so writeNonBlocking
    // does no per-call arithmetic.
    private val writeTimeoutNs: Long = writeTimeoutMs * 1_000_000L
```

Also add this private field next to the other `private var ... Job?` declarations near the top of the class (around line 73):

```kotlin
    private var ownedTransport: java.util.concurrent.ExecutorService? = null
```

- [ ] **Step 2.2: Add the env-var reader and named thread factory to the companion object**

In the existing `companion object` at the bottom of the file (around line 387), add two private helpers. The companion currently looks like:

```kotlin
    companion object {
        private const val MAX_CLIENTS = 10
        private const val WRITE_TIMEOUT_NS = 5_000_000_000L // 5 seconds
        private const val MAX_SOCKET_PATH_LENGTH = 90
        private const val MAX_REQUEST_BYTES = 64 * 1024
        // ... socketBaseName, hashedSocketName, writeMetaFile ...
    }
```

Delete the `WRITE_TIMEOUT_NS` constant (it's now per-instance via `writeTimeoutNs`). Add immediately after `MAX_REQUEST_BYTES`:

```kotlin
        private const val TRANSPORT_POOL_SIZE = 4

        private fun readWriteTimeoutFromEnv(): Long {
            val raw = System.getenv("UNIDRIVE_IPC_WRITE_TIMEOUT_MS") ?: return 5_000L
            return raw.toLongOrNull()?.takeIf { it in 100L..600_000L } ?: 5_000L
        }

        private fun ipcIoThreadFactory(): java.util.concurrent.ThreadFactory {
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            return java.util.concurrent.ThreadFactory { r ->
                Thread(r, "ipc-io-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
```

Daemon threads so the JVM can shut down cleanly even if `close()` is somehow skipped.

- [ ] **Step 2.3: Wire the transport dispatcher into `start()` and switch all three launch sites**

In `start()` (currently around line 133), right after `serverChannel = server`, insert the dispatcher resolution:

```kotlin
        val transport: kotlinx.coroutines.CoroutineDispatcher = transportDispatcher ?: run {
            val es = java.util.concurrent.Executors.newFixedThreadPool(
                TRANSPORT_POOL_SIZE,
                ipcIoThreadFactory(),
            )
            ownedTransport = es
            kotlinx.coroutines.asCoroutineDispatcher(es)
        }
        log.info(
            "IPC: transport pool size={} write_timeout_ms={}",
            TRANSPORT_POOL_SIZE,
            writeTimeoutNs / 1_000_000L,
        )
```

Note: `kotlinx.coroutines.asCoroutineDispatcher(es)` is the namespaced form; the existing imports already pull in `kotlinx.coroutines.*` so `es.asCoroutineDispatcher()` works too. Pick whichever is clearer in the existing file's style — match what `Dispatchers.IO` reads like.

Then change the three launch sites in `start()`:

1. Accept loop (line ~148): `scope.launch(Dispatchers.IO) {` → `scope.launch(transport) {`
2. Per-client reader sub-launch (line ~163): `scope.launch(Dispatchers.IO) {` → `scope.launch(transport) {`
3. Broadcast loop (line ~207): `scope.launch(Dispatchers.IO) {` → `scope.launch(transport) {`

- [ ] **Step 2.4: Add handler offload in `dispatchRequest`**

Replace the current `dispatchRequest` body (around line 326) — specifically the line `val reply = try { handler(connId, line) }` — to wrap the handler call in `withContext(handlerDispatcher)`:

Before:
```kotlin
        val reply = try {
            handler(connId, line)
        } catch (e: Exception) {
```

After:
```kotlin
        val reply = try {
            kotlinx.coroutines.withContext(handlerDispatcher) { handler(connId, line) }
        } catch (e: Exception) {
```

- [ ] **Step 2.5: Update `writeNonBlocking` to use the instance `writeTimeoutNs`**

In `writeNonBlocking` (line ~371), replace:

```kotlin
    private fun writeNonBlocking(
        client: SocketChannel,
        buf: ByteBuffer,
    ) {
        val deadline = System.nanoTime() + WRITE_TIMEOUT_NS
```

with:

```kotlin
    private fun writeNonBlocking(
        client: SocketChannel,
        buf: ByteBuffer,
    ) {
        val deadline = System.nanoTime() + writeTimeoutNs
```

(Only the constant reference changes; the rest of the loop body is unchanged.)

- [ ] **Step 2.6: Shut the transport pool down in `close()`**

In `close()` (line ~230), the current body is:

```kotlin
    fun close() {
        channel.close()
        acceptJob?.cancel()
        broadcastJob?.cancel()
        runCatching { serverChannel?.close() }
        for (entry in clients) {
            runCatching { entry.channel.close() }
            closeListeners.forEach { it(entry.id) }
        }
        clients.clear()
        runCatching { Files.deleteIfExists(socketPath) }
    }
```

Add transport shutdown at the end (after `Files.deleteIfExists`):

```kotlin
        ownedTransport?.let { es ->
            es.shutdown()
            runCatching {
                es.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            ownedTransport = null
        }
```

We deliberately do NOT call `shutdownNow()` after the await. Per spec §R3, any thread still in `writeNonBlocking` will exit via `IOException` on the closed channel within one `Thread.sleep(10)` iteration — and `close()` has already closed every client channel above. If `awaitTermination` returns `false`, the JVM will eventually GC the executor when nothing references it; the threads are daemons so they don't block shutdown.

- [ ] **Step 2.7: Run the contract test and confirm it now passes**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcDispatcherIsolationTest.fast_client_is_not_blocked_by_saturated_handler_dispatcher" -q > /tmp/green.log 2>&1
echo "exit=$?"
grep -E "FAILED|PASSED|fast_client" /tmp/green.log -C 2
```
Expected: exit 0, test passes. The fast client returns `pong` within 1 second despite N saturated slow handlers.

If the test fails: do not loosen the assertion. Re-check that:
- All three `scope.launch(Dispatchers.IO)` sites in `start()` were switched to `scope.launch(transport)`.
- `withContext(handlerDispatcher)` actually wraps the `handler(connId, line)` call (a common mistake is to wrap the wrong scope).
- `dispatchRequest` is still being called from the per-client reader loop — i.e. you didn't accidentally re-route requests through a different code path.

- [ ] **Step 2.8: Run the full existing IPC suite — must still be green**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcServerTest" --tests "org.krost.unidrive.sync.IpcServerPermissionsTest" --tests "org.krost.unidrive.sync.IpcProgressReporterTest" -q > /tmp/regression.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 5 /tmp/regression.log || echo "no failures"
```
Expected: exit 0, no FAILED entries. The existing tests use the default constructor and the defaults preserve today's behavior — any regression here means the structural change altered observable semantics.

- [ ] **Step 2.9: Commit the fix**

Run:
```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt
git commit -m "$(cat <<'EOF'
fix(ipc): isolate transport on dedicated 4-thread pool, offload handlers

Phase 2 smoke against posteo_onedrive surfaced 'Write timeout exceeded
for IPC client' under SyncEngine saturation of Dispatchers.IO. The 5s
write deadline lapsed not because the client was slow but because the
IPC server was queued behind dozens of 69MB HTTP downloads on the same
dispatcher.

Structural fix:

- IpcServer allocates a dedicated 4-thread pool (named ipc-io-N, daemon
  threads) and runs the accept loop, per-client reader, and broadcast
  loop on it instead of Dispatchers.IO.
- dispatchRequest wraps handler(connId, line) in
  withContext(handlerDispatcher), defaulting to Dispatchers.IO so
  handler bodies still scale with the JVM-wide IO pool but can't
  starve the transport.
- WRITE_TIMEOUT_NS becomes per-instance (writeTimeoutNs), readable
  from UNIDRIVE_IPC_WRITE_TIMEOUT_MS env var (default 5000,
  range 100-600000).
- close() shuts the pool down with awaitTermination(5s).

Spec: docs/superpowers/specs/ipc-transport-dispatcher-isolation-design.md
Contract test: IpcDispatcherIsolationTest.fast_client_is_not_blocked_by_saturated_handler_dispatcher
EOF
)"
```

---

## Task 3: T2 — observable handler offload

The contract test (T4) proves the *behavior* of isolation. T2 proves the *mechanism* — that handlers actually run on a different thread than the transport. This catches a subtle regression class: if someone accidentally removed `withContext(handlerDispatcher)` but kept the pool isolation, T4 might still pass (handlers don't saturate the pool because they finish fast) while the structural invariant has silently regressed.

**Files:**
- Modify: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcDispatcherIsolationTest.kt`

- [ ] **Step 3.1: Add the handler-offload test**

Append this test to the `IpcDispatcherIsolationTest` class (keep it in the same file as the contract test):

```kotlin
    /**
     * Proves the handler offload mechanism: handler invocation runs on a
     * different thread than the transport. Without coupling to
     * kotlinx.coroutines internal naming.
     */
    @Test
    fun handler_invocation_is_offloaded_off_transport_pool() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val handlerThreadName = java.util.concurrent.atomic.AtomicReference<String?>()
                val s = IpcServer(socketPath)
                server = s
                s.registerHandler("probe") { _, _ ->
                    handlerThreadName.set(Thread.currentThread().name)
                    """{"event":"probe_done"}"""
                }
                s.start(serverScope)

                val client = connectClient()
                sendVerb(client, "probe")
                val reply = readOneLine(client, timeoutMs = 2_000)
                assertTrue(reply != null && reply.contains("probe_done"), "probe never replied: $reply")

                val name = handlerThreadName.get()
                    ?: fail("Handler did not record its thread name")
                assertTrue(
                    !name.startsWith("ipc-io-"),
                    "Handler ran on transport pool thread '$name'; expected off-pool. " +
                        "withContext(handlerDispatcher) likely missing from dispatchRequest.",
                )
                client.close()
            } finally {
                serverScope.cancel()
            }
        }
```

- [ ] **Step 3.2: Run the test**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcDispatcherIsolationTest.handler_invocation_is_offloaded_off_transport_pool" -q > /tmp/t2.log 2>&1
echo "exit=$?"
grep -E "FAILED|PASSED|handler_invocation" /tmp/t2.log -C 2
```
Expected: exit 0, test passes. The captured thread name does not start with `ipc-io-` (it'll be something from `Dispatchers.IO` — name varies, we don't assert the positive shape).

- [ ] **Step 3.3: Commit**

Run:
```bash
git add core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcDispatcherIsolationTest.kt
git commit -m "test(ipc): assert handler invocation runs off the ipc-io transport pool"
```

---

## Task 4: T3 — transport pool naming and bounded lifecycle

Proves the pool is sized as advertised (≤4 `ipc-io-` threads), named for log-grepping, and shuts down cleanly on `close()`.

**Files:**
- Modify: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcDispatcherIsolationTest.kt`

- [ ] **Step 4.1: Add the pool-lifecycle test**

Append to the same test class:

```kotlin
    /**
     * Proves the transport pool is named ipc-io-N, bounded at 4, and shuts
     * down cleanly on close(). The thread count assertion is on
     * `ipc-io-`-prefixed threads only — other test infrastructure (JUnit
     * runner, build daemon) won't pollute the count.
     */
    @Test
    fun transport_pool_is_capped_at_four_threads_and_shuts_down_on_close() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val s = IpcServer(socketPath)
                server = s
                // Touch verb so the pool sees real work, ensuring all threads spin up
                // (FixedThreadPool lazily creates threads as tasks arrive).
                s.registerHandler("ping") { _, _ -> """{"event":"pong"}""" }
                s.start(serverScope)

                // Force at least 4 concurrent in-flight tasks so the pool grows to its
                // configured size. Each connectClient + sendVerb keeps a reader busy
                // momentarily.
                val clients = (1..8).map { connectClient() }
                for (c in clients) sendVerb(c, "ping")
                // Drain the replies so the test doesn't block on dangling reads.
                for (c in clients) readOneLine(c, timeoutMs = 1_000)

                val ipcThreads = Thread.getAllStackTraces().keys
                    .filter { it.name.startsWith("ipc-io-") }
                assertTrue(ipcThreads.isNotEmpty(), "No ipc-io- threads found after activity")
                assertTrue(
                    ipcThreads.size <= 4,
                    "Expected ≤4 ipc-io- threads, found ${ipcThreads.size}: ${ipcThreads.map { it.name }}",
                )

                clients.forEach { runCatching { it.close() } }
                s.close()
                server = null  // prevent double-close in tearDown

                // After close(): wait up to the spec's 5s awaitTermination budget plus
                // a small slack, then assert no ipc-io- threads remain.
                val deadline = System.currentTimeMillis() + 6_000
                var remaining: List<Thread>
                do {
                    remaining = Thread.getAllStackTraces().keys
                        .filter { it.name.startsWith("ipc-io-") && it.isAlive }
                    if (remaining.isEmpty()) break
                    delay(100)
                } while (System.currentTimeMillis() < deadline)
                assertTrue(
                    remaining.isEmpty(),
                    "ipc-io- threads survived close()+6s: ${remaining.map { it.name }}",
                )
            } finally {
                serverScope.cancel()
            }
        }
```

- [ ] **Step 4.2: Run the test**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcDispatcherIsolationTest.transport_pool_is_capped_at_four_threads_and_shuts_down_on_close" -q > /tmp/t3.log 2>&1
echo "exit=$?"
grep -E "FAILED|PASSED|transport_pool" /tmp/t3.log -C 2
```
Expected: exit 0, test passes. Up to 4 `ipc-io-N` threads observed during activity; zero after `close()`.

- [ ] **Step 4.3: Commit**

Run:
```bash
git add core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcDispatcherIsolationTest.kt
git commit -m "test(ipc): assert transport pool is bounded at 4 and shuts down on close"
```

---

## Task 5: Env-var test

Proves `UNIDRIVE_IPC_WRITE_TIMEOUT_MS` is honored, falls back to 5000 on bad input, and clamps out-of-range values. We test the parsing helper directly rather than spinning up a server with a forced-timeout scenario — driving an actual 5s write timeout in a unit test is slow and flaky; the parsing logic is the contract we need to lock down.

**Files:**
- Modify: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` (visibility tweak only)
- Modify: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcDispatcherIsolationTest.kt`

- [ ] **Step 5.1: Expose `readWriteTimeoutFromEnv` to the test package**

In `IpcServer.kt` companion object, change:

```kotlin
        private fun readWriteTimeoutFromEnv(): Long {
```

to:

```kotlin
        internal fun readWriteTimeoutFromEnv(): Long {
```

`internal` makes it visible to the same Gradle module's test source set without leaking to other modules. (Kotlin convention; matches how `IpcServer` already exposes test-helper accessors like `clientCount`.)

- [ ] **Step 5.2: Add the env-var test**

Note on the test approach: `System.getenv()` is read-only on the JVM — you can't `setEnv("UNIDRIVE_IPC_WRITE_TIMEOUT_MS", "12345")` in a portable way. We test the parsing helper indirectly by extracting the regex-like behavior into a pure-function overload. Add to the companion object in `IpcServer.kt`:

```kotlin
        // Pure-function overload for testing the parse + clamp logic without
        // touching System.getenv. Production path delegates here.
        internal fun parseWriteTimeoutMs(raw: String?): Long {
            if (raw == null) return 5_000L
            return raw.toLongOrNull()?.takeIf { it in 100L..600_000L } ?: 5_000L
        }
```

And refactor `readWriteTimeoutFromEnv` to delegate:

```kotlin
        internal fun readWriteTimeoutFromEnv(): Long =
            parseWriteTimeoutMs(System.getenv("UNIDRIVE_IPC_WRITE_TIMEOUT_MS"))
```

Then append the test to `IpcDispatcherIsolationTest`:

```kotlin
    @Test
    fun write_timeout_env_var_parses_and_clamps() {
        // Null (env var unset) → default
        assertTrue(IpcServer.parseWriteTimeoutMs(null) == 5_000L)
        // Valid in-range value honored
        assertTrue(IpcServer.parseWriteTimeoutMs("30000") == 30_000L)
        assertTrue(IpcServer.parseWriteTimeoutMs("100") == 100L)
        assertTrue(IpcServer.parseWriteTimeoutMs("600000") == 600_000L)
        // Out-of-range clamps to default (we don't silently saturate)
        assertTrue(IpcServer.parseWriteTimeoutMs("0") == 5_000L)
        assertTrue(IpcServer.parseWriteTimeoutMs("99") == 5_000L)
        assertTrue(IpcServer.parseWriteTimeoutMs("600001") == 5_000L)
        assertTrue(IpcServer.parseWriteTimeoutMs("-1") == 5_000L)
        // Unparseable → default
        assertTrue(IpcServer.parseWriteTimeoutMs("") == 5_000L)
        assertTrue(IpcServer.parseWriteTimeoutMs("not-a-number") == 5_000L)
        assertTrue(IpcServer.parseWriteTimeoutMs("12.5") == 5_000L)
    }
```

- [ ] **Step 5.3: Run the test**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcDispatcherIsolationTest.write_timeout_env_var_parses_and_clamps" -q > /tmp/t5.log 2>&1
echo "exit=$?"
grep -E "FAILED|PASSED|write_timeout" /tmp/t5.log -C 2
```
Expected: exit 0, test passes.

- [ ] **Step 5.4: Commit**

Run:
```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcDispatcherIsolationTest.kt
git commit -m "test(ipc): assert UNIDRIVE_IPC_WRITE_TIMEOUT_MS parses and clamps"
```

---

## Task 6: Full-suite green + BACKLOG close-out

The fix is mechanically complete. Run the whole `:app:sync` test surface to catch any indirect regression, then close the BACKLOG entry.

- [ ] **Step 6.1: Run the full `:app:sync` test suite**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test -q > /tmp/full.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 5 /tmp/full.log || echo "no failures"
tail -20 /tmp/full.log
```
Expected: exit 0. If anything fails outside `IpcDispatcherIsolationTest`, the fix has indirect side effects — investigate before claiming done. The most likely class of regression is a test that timed its assertions to the old dispatcher topology (e.g. assumed broadcast events would arrive within X ms because they shared a thread with the sender). Such a test is fragile and worth fixing, but it's a separate scope; surface to user before changing test timings.

- [ ] **Step 6.2: Close the BACKLOG entry**

Read `BACKLOG.md`, find the line that begins:

```
| IPC write-timeout drops Phase 2 mount clients during heavy concurrent sync load |
```

Cut that row out of `BACKLOG.md` and append it to `CLOSED.md` (same row, no edits). Both edits go in the same commit. Pattern matches the existing close-out commits in the log (e.g. `7a6f8b6 docs(backlog): close Phase 2 JVM wiring entry`).

Run:
```bash
# Manual edits via your editor of choice; verify with:
git diff -- BACKLOG.md CLOSED.md
git add BACKLOG.md CLOSED.md
git commit -m "docs(backlog): close IPC write-timeout entry"
```

- [ ] **Step 6.3: Live smoke per spec §3.5 — required before claiming done**

The spec is explicit: the fix has to be validated the same way the bug was found. **Do not skip.** Per AGENTS.md "Smoke test on the actual target."

```bash
# 1. Build a snapshot of the daemon with the fix.
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:installDist -q

# 2. Restart the daemon for the posteo_onedrive profile under the new build.
#    (Exact command depends on how the user's daemon is supervised. If it's
#    the systemd-user-unit `unidrive-sync@posteo_onedrive.service`, restart
#    via `systemctl --user restart unidrive-sync@posteo_onedrive`. If the
#    user runs it from a terminal, kill and respawn.)
#
#    User-confirmable step: ask the user how to restart the daemon if not
#    obvious from the unit files and profile config.

# 3. Mount.
unidrive mount /tmp/onedrive-smoke

# 4. While SyncEngine is mid-download (logs show "Download: ... (NN MB)"),
#    read a small file.
less /tmp/onedrive-smoke/geheim.txt

# 5. Expect: file content prints. Daemon log shows zero
#    "Write timeout exceeded for IPC client" entries during the read.
journalctl --user -u unidrive-sync@posteo_onedrive.service -n 200 \
    | grep -E "Write timeout|dropping dead" || echo "no timeout events"

# 6. Unmount and clean up.
fusermount3 -u /tmp/onedrive-smoke
```

If step 5 reports any "Write timeout exceeded" line during the read: the fix is incomplete. Do not move the BACKLOG entry back — leave Task 6.2's commit in place (the entry was correctly closed structurally) — but file a new entry capturing whatever the residual symptom is.

- [ ] **Step 6.4: If on a fix branch, merge to main**

If the agent created `fix/ipc-transport-dispatcher` in step 0, merge it:

```bash
git checkout main
git merge --no-ff fix/ipc-transport-dispatcher -m "Merge fix/ipc-transport-dispatcher"
git branch -d fix/ipc-transport-dispatcher
```

If work was on `main` directly, skip this step.

---

## Out of scope for this plan

These are documented here so a future reader knows they were considered and intentionally deferred:

1. **Pool-size queue-depth instrumentation (spec §R2).** The spec specifies the trigger — `ThreadPoolExecutor.queue.size() > 0` sustained >100ms — but implementing the heartbeat sampler touches the daemon-heartbeat path, which is a separate code surface. File as a follow-up BACKLOG entry after this plan lands. The fix doesn't need it to be correct; it needs it to be self-tuning over time.
2. **Bounded SyncEngine download concurrency (BACKLOG option c).** Independent finding; lives in `SyncEngine`, not `IpcServer`.
3. **Documenting the other four production env vars** (`UNIDRIVE_CONFIG_DIR`, `UNIDRIVE_STRICT_CONFIG`, `UNIDRIVE_VAULT_PASS`, `UNIDRIVE_WATCHER_DEBOUNCE_MS`). Stubs in `docs/env-vars.md`; fill in when operator-facing behavior of each is next touched.

---

## Self-review pass (writer-side)

Checked against `docs/superpowers/specs/ipc-transport-dispatcher-isolation-design.md`:

- **§3.2.1 constructor signature**: Task 2.1.
- **§3.2.2 pool lifecycle**: Tasks 2.2 (factory), 2.3 (start), 2.6 (close).
- **§3.2.3 handler offload**: Task 2.4.
- **§3.2.4 configurable timeout**: Tasks 2.1, 2.2, 2.5 + Task 5 (parsing/clamp test).
- **§3.3 call-site survey**: Task 2.3 step 3 enumerates all three sites.
- **§3.4 four tests**: T4 in Task 1, T2 in Task 3, T3 in Task 4, plus the env-var test in Task 5. The "existing suite still green" T1 is Task 2.8 + Task 6.1.
- **§3.5 live smoke**: Task 6.3.
- **§4 R1 (T4 flakiness)**: Task 1.3 escalation logic handles this.
- **§4 R2 (pool-size monitoring)**: Out of scope above.
- **§5 acceptance**: All four criteria covered by Tasks 6.1 (suite), 6.2 (BACKLOG close-out), 6.3 (live smoke). The new env-var is already documented in `docs/env-vars.md` from the spec commit.

No TBDs, no "implement appropriate error handling" placeholders, no cross-task type drift (constructor params named identically in spec, plan steps, and test usages). Plan is self-contained.
