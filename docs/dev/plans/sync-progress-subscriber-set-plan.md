# Sync-Progress Subscriber Set Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gate IPC broadcast fanout (`IpcProgressReporter.emit(...)`) on a new `sync.subscribe` subscriber set so that request-reply-only clients (the FUSE co-daemon) never receive unsolicited sync-progress bytes that desync their `read_line` framing. Move `flushStateDump` from accept-time to a post-reply hook inside the `sync.subscribe` handler, eliminating the same problem for state-dump bytes. Fix the pre-existing JSON-injection bug in `flushStateDump` inline.

**Architecture:** Mechanism β from the spec (§3.2.3): `IpcServer` gains a one-shot per-connection `scheduleAfterReply` slot; `dispatchRequest` invokes the scheduled action only on the successful-reply path, strictly after writing the reply to the socket. The `sync.subscribe` handler uses this to defer state-dump-then-register until the reply is on the wire. Broadcast loop's subscriber-set predicate (`entry.id in syncSubscribers`) is the only gate for fanout; it's false until the post-reply hook completes. Pre-existing string-interpolation JSON in `flushStateDump` is converted to use the existing `escapeJson` helper inline since the same function body is being moved/renamed.

**Tech Stack:** Kotlin (JVM 21), kotlinx.coroutines 1.8.x, `java.util.concurrent.ConcurrentHashMap.newKeySet`, `java.nio.channels.SocketChannel` (Unix domain), `kotlinx.serialization.json` (test-side parsing only), JUnit 5 via `kotlin.test`. Build from `core/`: `./gradlew :app:sync:test -q`. Local deploy: `./gradlew :app:cli:deploy -q`.

**Spec:** `docs/dev/specs/sync-progress-subscriber-set-design.md` (commit `264d6c4`)

**Live evidence to reproduce against:** `~/.local/share/unidrive/unidrive.log` between 21:29 and 21:33 on 2026-05-23.

---

## Pre-flight

- [ ] **Step 0: Verify clean working tree, branch off main**

Run:
```bash
cd /home/gernot/dev/git/unidrive
git status --short
git branch --show-current
git checkout -b fix/sync-subscriber-set
git branch --show-current
```
Expected: empty `git status`, on `main` before the checkout, on `fix/sync-subscriber-set` after.

- [ ] **Step 0a: Confirm baseline test suite is green**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test -q > /tmp/sub-baseline.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 5 /tmp/sub-baseline.log || echo "no failures"
```
Expected: exit 0. If anything fails, STOP and surface to the user before any change.

---

## File Structure

| File | Role | Status |
|---|---|---|
| `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` | Subscriber-set field, public `registerSyncSubscriber`/`unregisterSyncSubscriber`, `scheduleAfterReply`, `pendingPostReply` map; rename `flushStateDump(entry)` → public `flushStateDumpTo(connectionId)`; broadcast-loop filter; remove accept-time `flushStateDump` call; `dispatchRequest` defensive-clear-at-top + post-reply-hook-on-success; JSON-escape co-fix in the state-dump body. Plus test-only `internal val syncSubscribersSnapshot` accessor (consumed by T4). | Modify |
| `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt` | Register `sync.subscribe` verb handler (schedules post-reply action). Register external close-listener that calls `unregisterSyncSubscriber`. | Modify |
| `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcSyncSubscriberSetTest.kt` | New file. Four tests T1–T4 per spec §3.5. | Create |
| `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt` | Update five tests per spec §3.5: prepend `sync.subscribe` round-trip, or rename-and-rewrite for the obsolete `late joiner receives state dump` case. | Modify |
| `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcProgressReporterTest.kt` | Update all five tests: prepend `sync.subscribe` round-trip after connect. | Modify |

Six tasks total: Task 1 lands the failing contract test (T1) against unmodified code to lock the invariant; Task 2 lands the structural fix on `IpcServer.kt`; Task 3 wires the new verb in `SyncCommand.kt`; Task 4 updates existing tests; Task 5 lands T2/T3/T4; Task 6 closes out (full suite green, BACKLOG move, deploy, live smoke).

---

## Task 1: Failing contract test (T1) on unmodified code

**Why this task exists:** The spec's contract test is "a non-subscribed client receives zero sync-progress bytes." On today's code, that test must FAIL — `server.emit(...)` fans to all clients regardless of subscription. Writing it first locks the invariant the structural fix will satisfy.

**Files:**
- Create: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcSyncSubscriberSetTest.kt`

- [ ] **Step 1.1: Create the test file with T1**

Create `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcSyncSubscriberSetTest.kt`:

```kotlin
package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Protects the invariants from
 * `docs/dev/specs/sync-progress-subscriber-set-design.md`.
 *
 * If any of these tests are deleted or loosened, the corresponding
 * invariant silently regresses and the Phase 2 co-daemon Broken-pipe
 * cycle (BACKLOG entry 1de0cb3, refined in 013da46) returns.
 */
class IpcSyncSubscriberSetTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-sub-test")
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

    private suspend fun readAvailableBytes(
        client: SocketChannel,
        windowMs: Long,
    ): ByteArray {
        val buf = ByteBuffer.allocate(8192)
        val acc = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            buf.clear()
            val n = client.read(buf)
            if (n > 0) {
                buf.flip()
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                acc.write(bytes)
            }
            delay(20)
        }
        return acc.toByteArray()
    }

    /**
     * The Phase 2 contract test. A connected client that did NOT issue
     * `sync.subscribe` must receive zero bytes from `server.emit(...)`
     * fanout. Without the fix this fails — the broadcast loop fans to
     * all clients regardless. With the fix it passes deterministically.
     */
    @Test
    fun non_subscriber_receives_no_sync_progress_bytes() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val s = IpcServer(socketPath)
                server = s
                s.start(serverScope)

                val client = connectClient()
                // Wait briefly so accept loop has registered the client.
                delay(100)

                // Fire several broadcast events.
                repeat(5) { i ->
                    s.emit("""{"event":"test","seq":$i}""")
                }

                // Read with a 500ms window. Without the fix, we receive at
                // least one of the five lines. With the fix, zero bytes.
                val received = readAvailableBytes(client, windowMs = 500)
                assertEquals(
                    0, received.size,
                    "Non-subscriber received ${received.size} bytes of " +
                        "unsolicited broadcast traffic: ${String(received).take(200)}",
                )
                client.close()
            } finally {
                serverScope.cancel()
            }
        }
}
```

- [ ] **Step 1.2: Run the test and verify it FAILS on unmodified code**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcSyncSubscriberSetTest.non_subscriber_receives_no_sync_progress_bytes" -q > /tmp/red.log 2>&1
echo "exit=$?"
grep -E "FAILED|received [1-9]" /tmp/red.log -C 3
```
Expected: exit non-zero. The failure assertion should report "Non-subscriber received N bytes of unsolicited broadcast traffic: …" where N > 0. If somehow the test passes today, STOP — that would mean my root-cause analysis is wrong; surface to the user.

- [ ] **Step 1.3: Commit the failing test**

```bash
git add core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcSyncSubscriberSetTest.kt
git commit -m "test(ipc): failing contract test for sync-progress subscriber-set gating"
```

---

## Task 2: Structural fix in IpcServer.kt

The structural fix is one file. Lands in one commit (single coherent change) so the contract test goes red→green in one motion. Six sub-steps map to spec §3.2 sub-sections.

**Files:**
- Modify: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`

- [ ] **Step 2.1: Add subscriber-set field + pendingPostReply map**

Edit `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`. Locate the existing private field declarations near the top of the class (around line 68-83, just after the `ClientEntry` data class). Add these two lines:

```kotlin
    // Connections that issued `sync.subscribe`. Filtered by the broadcast loop
    // (§3.2.5) so request-reply-only clients (e.g. FUSE co-daemon) never
    // receive unsolicited sync-progress bytes. Cleanup is via the close-
    // listener registered externally by SyncCommand (§3.3 of the spec).
    private val syncSubscribers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // One-shot per-connection slot for an action that runs AFTER the current
    // request's reply is written to the socket. See scheduleAfterReply()
    // and the post-reply hook in dispatchRequest. Single in-flight request
    // per connection (the per-client reader processes lines sequentially),
    // so a per-connId slot is sufficient — no queue.
    private val pendingPostReply = java.util.concurrent.ConcurrentHashMap<String, suspend () -> Unit>()
```

- [ ] **Step 2.2: Add the three public methods**

In the same class, after the existing public `writeToConnection(...)` method (around line 116-131), add three new public methods:

```kotlin
    /**
     * Mark a connection as a sync-progress subscriber. After this call, the
     * connection receives sync-progress events from `emit(...)` until it
     * disconnects (cleanup via `unregisterSyncSubscriber`, called from a
     * connection-close listener registered externally by `SyncCommand` for
     * stylistic parity with the existing hydration close listeners).
     *
     * Symmetric with `HydrationIpcHandler.registerSubscriber` at the wire
     * level; the two subscriber sets are independent (a client may
     * subscribe to one, both, or neither).
     */
    fun registerSyncSubscriber(connectionId: String) {
        syncSubscribers.add(connectionId)
    }

    /**
     * Remove a connection from the sync-progress subscriber set. Idempotent;
     * called from the connection-close listener `SyncCommand` registers
     * and from the broadcast loop's dead-subscriber cleanup path.
     */
    fun unregisterSyncSubscriber(connectionId: String) {
        syncSubscribers.remove(connectionId)
    }

    /**
     * Schedule a one-shot action to run AFTER the current request's reply
     * is written to the socket. Used by `sync.subscribe` to push the state
     * dump and subscriber-set registration only after the {"ok":true} reply
     * is on the wire — so the subscriber's first observed line is always
     * the reply, never an event.
     *
     * Must be called from inside a verb handler running under
     * dispatchRequest's withContext(handlerDispatcher) block. Calling
     * outside a handler is a no-op (the action will never fire because
     * the next dispatchRequest call clears the slot at the top).
     *
     * The action fires only on the successful-reply path. If the handler
     * threw and dispatchRequest wrote an error envelope, the scheduled
     * action is discarded (R7 in the spec).
     */
    fun scheduleAfterReply(connectionId: String, action: suspend () -> Unit) {
        pendingPostReply[connectionId] = action
    }
```

Also add a test-only accessor at the same site:

```kotlin
    /**
     * Test-only accessor used by IpcSyncSubscriberSetTest to assert that
     * disconnected connections are removed from the subscriber set (T4 in
     * the spec). Same-module visibility is sufficient — `internal` works
     * for tests living in `:app:sync`.
     */
    internal val syncSubscribersSnapshot: Set<String>
        get() = syncSubscribers.toSet()
```

- [ ] **Step 2.3: Rename `flushStateDump(entry)` → public `flushStateDumpTo(connectionId)` + JSON-escape co-fix**

The current private helper at `IpcServer.kt:287-308` looks like:

```kotlin
    private suspend fun flushStateDump(entry: ClientEntry) {
        val state = syncState ?: return
        val ts = java.time.Instant.now().toString()
        val lines = mutableListOf<String>()
        lines.add(buildStateDumpLine("sync_started", state.profile, ts))
        if (state.phase != null) {
            lines.add(
                buildStateDumpLine(
                    "scan_progress",
                    state.profile,
                    ts,
                    """"phase":"${state.phase}","count":${state.scanCount}""",
                ),
            )
        }
        if (state.actionTotal > 0) {
            lines.add(
                buildStateDumpLine(
                    "action_count",
                    state.profile,
                    ts,
                    """"total":${state.actionTotal}""",
                ),
            )
        }
        if (state.actionIndex > 0 && state.lastAction != null) {
            lines.add(
                buildStateDumpLine(
                    "action_progress",
                    state.profile,
                    ts,
                    """"index":${state.actionIndex},"total":${state.actionTotal},"action":"${state.lastAction}","path":"${state.lastPath ?: ""}"""",
                ),
            )
        }
        for (line in lines) {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            try {
                entry.writeMutex.withLock {
                    writeNonBlocking(entry.channel, ByteBuffer.wrap(bytes))
                }
            } catch (e: IOException) {
                log.debug("IPC: failed to flush state dump to new client: {}", e.message)
                clients.remove(entry)
                runCatching { entry.channel.close() }
                return
            }
        }
    }
```

Replace it with:

```kotlin
    /**
     * Replay the current SyncState as initial state-dump events to a
     * single subscriber connection. Called from the `sync.subscribe`
     * post-reply hook (see scheduleAfterReply) so that subscribers see
     * context for the in-progress sync, but non-subscribers (e.g. the
     * FUSE co-daemon, request-reply clients) never receive these
     * unsolicited bytes on their socket.
     *
     * If `syncState` is null (no sync in progress at subscribe time),
     * this is a no-op. If the connection has closed between subscribe
     * and the post-reply hook firing, logs a WARN and returns —
     * surfacing the rare timing window for future operator debugging.
     *
     * Public for the same module-boundary reason as `registerSyncSubscriber`
     * (called from `SyncCommand` in `:app:cli`; `IpcServer` is in `:app:sync`).
     *
     * On partial-write failure, the subscriber receives a truncated state
     * dump and then nothing further from this method. The connection's
     * subsequent dead-client cleanup (via the broadcast loop's dropping
     * path or the per-client reader's IOException path) handles the
     * unhealthy connection. Subscribers should validate they receive
     * expected event shapes rather than assume the dump completed.
     */
    suspend fun flushStateDumpTo(connectionId: String) {
        val state = syncState ?: return
        val entry = clients.firstOrNull { it.id == connectionId } ?: run {
            log.warn(
                "IPC: flushStateDumpTo called for unknown connection id={}; " +
                    "client likely closed between sync.subscribe parse and post-reply hook",
                connectionId,
            )
            return
        }
        val ts = java.time.Instant.now().toString()
        val lines = mutableListOf<String>()
        lines.add(buildStateDumpLine("sync_started", state.profile, ts))
        if (state.phase != null) {
            lines.add(
                buildStateDumpLine(
                    "scan_progress",
                    state.profile,
                    ts,
                    """"phase":${escapeJson(state.phase ?: "")},"count":${state.scanCount}""",
                ),
            )
        }
        if (state.actionTotal > 0) {
            lines.add(
                buildStateDumpLine(
                    "action_count",
                    state.profile,
                    ts,
                    """"total":${state.actionTotal}""",
                ),
            )
        }
        if (state.actionIndex > 0 && state.lastAction != null) {
            lines.add(
                buildStateDumpLine(
                    "action_progress",
                    state.profile,
                    ts,
                    """"index":${state.actionIndex},"total":${state.actionTotal},"action":${escapeJson(state.lastAction ?: "")},"path":${escapeJson(state.lastPath ?: "")}""",
                ),
            )
        }
        for (line in lines) {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            try {
                entry.writeMutex.withLock {
                    writeNonBlocking(entry.channel, ByteBuffer.wrap(bytes))
                }
            } catch (e: IOException) {
                log.debug("IPC: failed to flush state dump to subscriber: {}", e.message)
                clients.remove(entry)
                runCatching { entry.channel.close() }
                return
            }
        }
    }
```

Then update `buildStateDumpLine` (currently at `IpcServer.kt:310-324`) to escape its dynamic string fields. Current body:

```kotlin
    private fun buildStateDumpLine(
        event: String,
        profile: String,
        timestamp: String,
        extra: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("""{"event":"$event","profile":"$profile"""")
        if (extra != null) {
            sb.append(",")
            sb.append(extra)
        }
        sb.append(""","timestamp":"$timestamp"}""")
        return sb.toString()
    }
```

Replace with:

```kotlin
    private fun buildStateDumpLine(
        event: String,
        profile: String,
        timestamp: String,
        extra: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("""{"event":${escapeJson(event)},"profile":${escapeJson(profile)}""")
        if (extra != null) {
            sb.append(",")
            sb.append(extra)
        }
        sb.append(""","timestamp":${escapeJson(timestamp)}}""")
        return sb.toString()
    }
```

- [ ] **Step 2.4: Remove `flushStateDump(entry)` call from accept loop**

In `IpcServer.kt:185`, the accept loop currently calls `flushStateDump(entry)`. Delete that line entirely. The surrounding context is:

```kotlin
                        sc.configureBlocking(false)
                        val connId = java.util.UUID.randomUUID().toString()
                        val entry = ClientEntry(sc, connId)
                        clients.add(entry)
                        log.debug("IPC: client connected id={} (total={})", connId, clients.size)
                        flushStateDump(entry)                          // ← DELETE THIS LINE
                        scope.launch(transport) {
```

After deletion:

```kotlin
                        sc.configureBlocking(false)
                        val connId = java.util.UUID.randomUUID().toString()
                        val entry = ClientEntry(sc, connId)
                        clients.add(entry)
                        log.debug("IPC: client connected id={} (total={})", connId, clients.size)
                        scope.launch(transport) {
```

- [ ] **Step 2.5: Filter the broadcast loop on the subscriber set**

In the broadcast loop (currently at `IpcServer.kt:229-250`), the inner iteration is:

```kotlin
                    for (entry in clients) {
                        try {
                            entry.writeMutex.withLock {
                                writeNonBlocking(entry.channel, ByteBuffer.wrap(line))
                            }
                        } catch (e: IOException) {
                            log.debug("IPC: dropping dead client id={}: {}", entry.id, e.message)
                            dead.add(entry)
                        }
                    }
```

Replace with:

```kotlin
                    for (entry in clients) {
                        if (entry.id !in syncSubscribers) continue
                        try {
                            entry.writeMutex.withLock {
                                writeNonBlocking(entry.channel, ByteBuffer.wrap(line))
                            }
                        } catch (e: IOException) {
                            log.debug("IPC: dropping dead sync-subscriber id={}: {}", entry.id, e.message)
                            dead.add(entry)
                        }
                    }
```

Two changes: the `if (entry.id !in syncSubscribers) continue` gate, and the log key shift from "dropping dead client" to "dropping dead sync-subscriber" for grep disambiguation.

- [ ] **Step 2.6: Extend `dispatchRequest` with defensive clear + success-gated post-reply hook**

The current `dispatchRequest` body (at `IpcServer.kt:356-377`) is:

```kotlin
    private suspend fun dispatchRequest(client: SocketChannel, connId: String, line: String) {
        val verb = parseVerb(line) ?: run {
            log.warn("IPC: request without 'verb' field, dropping: {}", line.take(80))
            return
        }
        val handler = handlers[verb] ?: run {
            log.warn("IPC: no handler for verb '{}'", verb)
            return
        }
        val reply = try {
            kotlinx.coroutines.withContext(handlerDispatcher) { handler(connId, line) }
        } catch (e: Exception) {
            log.error("IPC: handler '$verb' threw", e)
            """{"error":"handler_threw","verb":"$verb","message":${escapeJson(e.message ?: "")}}"""
        }
        val entry = clients.firstOrNull { it.channel === client } ?: return
        runCatching {
            entry.writeMutex.withLock {
                writeNonBlocking(entry.channel, ByteBuffer.wrap((reply + "\n").toByteArray(Charsets.UTF_8)))
            }
        }
    }
```

Replace with:

```kotlin
    private suspend fun dispatchRequest(client: SocketChannel, connId: String, line: String) {
        // R8: defensive clear at the top — any stale entry from a prior request
        // that errored on this connection is removed before this request runs.
        // No-op on the happy path (the entry was already consumed at the end
        // of the prior successful request).
        pendingPostReply.remove(connId)

        val verb = parseVerb(line) ?: run {
            log.warn("IPC: request without 'verb' field, dropping: {}", line.take(80))
            return
        }
        val handler = handlers[verb] ?: run {
            log.warn("IPC: no handler for verb '{}'", verb)
            return
        }
        var handlerThrew = false
        val reply = try {
            kotlinx.coroutines.withContext(handlerDispatcher) { handler(connId, line) }
        } catch (e: Exception) {
            handlerThrew = true
            log.error("IPC: handler '$verb' threw", e)
            """{"error":"handler_threw","verb":"$verb","message":${escapeJson(e.message ?: "")}}"""
        }
        val entry = clients.firstOrNull { it.channel === client } ?: return
        runCatching {
            entry.writeMutex.withLock {
                writeNonBlocking(entry.channel, ByteBuffer.wrap((reply + "\n").toByteArray(Charsets.UTF_8)))
            }
        }
        // R7: post-reply hook fires ONLY on the successful-reply path.
        // If the handler threw, the scheduled action (if any) is discarded.
        val pending = pendingPostReply.remove(connId)
        if (!handlerThrew && pending != null) {
            runCatching {
                kotlinx.coroutines.withContext(handlerDispatcher) { pending() }
            }.onFailure { e ->
                log.warn("IPC: post-reply hook for connId={} threw: {}", connId, e.message)
            }
        }
    }
```

- [ ] **Step 2.7: Re-run the T1 contract test — must now PASS**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcSyncSubscriberSetTest.non_subscriber_receives_no_sync_progress_bytes" -q > /tmp/green.log 2>&1
echo "exit=$?"
grep -E "FAILED|PASSED|non_subscriber" /tmp/green.log -C 2
```
Expected: exit 0. The contract test now passes because the non-subscribing client is excluded from the broadcast loop's iteration.

If it still fails: check that all three of (a) the `if (entry.id !in syncSubscribers) continue` gate is in place, (b) `syncSubscribers` field is correctly initialised, (c) you didn't accidentally subscribe the test's client somewhere.

- [ ] **Step 2.8: Confirm the broader sync test suite still compiles and runs**

The existing IPC tests (`IpcServerTest`, `IpcProgressReporterTest`, etc.) will fail at this point — that's expected and addressed by Task 4. We only want to verify nothing else regressed AND that the IPC-transport-dispatcher tests still pass:

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcDispatcherIsolationTest" --tests "org.krost.unidrive.sync.IpcServerPermissionsTest" -q > /tmp/sub-step28.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 3 /tmp/sub-step28.log || echo "no failures"
```
Expected: exit 0. These two test classes don't rely on broadcast/state-dump behavior. If they fail, the structural change broke something other than the broadcast surface.

- [ ] **Step 2.9: Commit the structural fix**

```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt
git commit -m "$(cat <<'EOF'
fix(ipc): gate broadcast on sync-subscribe; post-reply state dump

Phase 2 mount smoke against posteo_onedrive surfaced recurring
'Broken pipe' on ipc-io-N writes — the FUSE co-daemon connects to
issue verb requests but the JVM broadcast loop was fanning every
IpcProgressReporter.emit() to ALL connected clients, desyncing the
co-daemon's read_line framing on the next round_trip.

Structural fix per docs/dev/specs/sync-progress-subscriber-set-design.md:

- New private syncSubscribers set (ConcurrentHashMap.newKeySet).
  Broadcast loop iterates only members; log key shifts from
  'dropping dead client' to 'dropping dead sync-subscriber'.
- New public registerSyncSubscriber / unregisterSyncSubscriber for
  SyncCommand to wire (cross-module visibility).
- New scheduleAfterReply + pendingPostReply map. dispatchRequest:
  defensive clear at top (R8), success-gated hook at bottom (R7).
  Ensures sync.subscribe's {"ok":true} reply lands first; state-dump
  and registration happen only after the reply is on the wire; the
  broadcast loop's set-membership predicate is the only gate.
- flushStateDump(entry) renamed to public flushStateDumpTo(connId),
  with explicit unknown-connection WARN. Removed from accept loop.
- buildStateDumpLine + state-dump extras now route every dynamic
  string field through escapeJson(). Closes pre-existing JSON-
  injection bug for paths/actions containing `"` or `\`.
- Test-only `internal val syncSubscribersSnapshot` accessor (T4).

Contract test in IpcSyncSubscriberSetTest now passes. Existing
IpcServerTest and IpcProgressReporterTest will fail — they assume
the old all-clients fanout. Task 4 of the plan updates each.
EOF
)"
```

---

## Task 3: SyncCommand verb registration + close-listener

**Files:**
- Modify: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt`

- [ ] **Step 3.1: Register the sync.subscribe verb handler and close-listener**

Open `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt`. Locate the existing hydration handler registration block (around `:455-470` — search for `hydrationIpc.start`). Immediately after the existing block:

```kotlin
                // Wire Phase-1 hydration SPI as IpcServer handlers.
                val hydration = HydrationImpl(engine, db)
                val hydrationIpc = HydrationIpcHandler(hydration)
                for (verb in HydrationIpcHandler.VERBS) {
                    ipcServer.registerHandler(verb) { connId, json ->
                        hydrationIpc.handle(connectionId = connId, jsonRequest = json)
                    }
                }
                ipcServer.registerConnectionCloseListener { connId ->
                    hydration.onConnectionClosed(connId)
                }
                // Fan hydration events out only to connections that ran hydration.subscribe,
                // with a bounded queue + drop-oldest+sentinel backpressure per subscriber.
                hydrationIpc.start(this, ipcServer::writeToConnection)
                ipcServer.registerConnectionCloseListener { connId -> hydrationIpc.onSubscriberDisconnect(connId) }
                launch { hydration.events.collect { hydrationIpc.dispatchEvent(it) } }
```

Add immediately after the last `launch { … }` line above:

```kotlin
                // Register sync.subscribe verb (spec docs/dev/specs/sync-progress-subscriber-set-design.md).
                // Symmetric with hydration.subscribe at the wire level: client issues the
                // verb, reads {"ok":true} as the first line, then receives the state dump
                // followed by live events. The post-reply hook (mechanism β) defers the
                // state dump + subscriber-set registration until after dispatchRequest has
                // written the reply, so subscribers never see events interleaved with the
                // reply.
                ipcServer.registerHandler("sync.subscribe") { connId, _ ->
                    ipcServer.scheduleAfterReply(connId) {
                        ipcServer.flushStateDumpTo(connId)
                        ipcServer.registerSyncSubscriber(connId)
                    }
                    """{"ok":true}"""
                }
                ipcServer.registerConnectionCloseListener { connId ->
                    ipcServer.unregisterSyncSubscriber(connId)
                }
```

- [ ] **Step 3.2: Build to confirm compilation**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:compileKotlin -q > /tmp/sub-step32.log 2>&1
echo "exit=$?"
tail -20 /tmp/sub-step32.log
```
Expected: exit 0. If a type error fires, the most likely cause is `flushStateDumpTo` not having been bumped to `public` in Task 2; re-check Step 2.3.

- [ ] **Step 3.3: Commit the wiring**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt
git commit -m "feat(sync): register sync.subscribe verb + subscriber-cleanup close-listener

Wires the new IpcServer.scheduleAfterReply + registerSyncSubscriber
into SyncCommand alongside the existing hydration wiring. Three-line
verb registration; one-line close-listener. External-registration
style matches the hydration close-listener three lines above.

Closes the call-site half of the Phase 2 co-daemon Broken-pipe fix.
Existing IPC tests still fail until Task 4 updates them."
```

---

## Task 4: Update existing tests to call sync.subscribe

The structural change breaks tests that connect-and-listen without subscribing. Per spec §3.5, every broken test gets `sync.subscribe` prepended (preserving original intent) except `late joiner receives state dump`, which is renamed and rewritten because its premise (state-dump fires on accept) is gone by design.

**Files:**
- Modify: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt`
- Modify: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcProgressReporterTest.kt`

- [ ] **Step 4.1: Add a `subscribeSync(client)` helper to `IpcServerTest`**

Open `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt`. After the existing `readFromClient` private helper (around line 49-71), add:

```kotlin
    /**
     * Issue `sync.subscribe` on `client` and drain the {"ok":true} reply
     * line plus any subsequent state-dump lines. Returns after the
     * post-reply state-dump has completed and the connection is registered
     * in IpcServer.syncSubscribers. Used by tests that previously relied
     * on accept-time fanout but must now opt in explicitly.
     *
     * Caveat: this method drains EVERYTHING the server has already pushed
     * (reply + state-dump). Subsequent reads see only events emitted
     * AFTER this call returns.
     */
    private suspend fun subscribeSync(client: SocketChannel) {
        val req = """{"verb":"sync.subscribe"}""" + "\n"
        val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
        while (w.hasRemaining()) client.write(w)
        // Drain at least the {"ok":true} reply; allow up to 250ms for
        // any state-dump lines that follow.
        readFromClient(client, timeoutMs = 250, minLines = 1)
    }
```

- [ ] **Step 4.2: Update `broadcast sends message to connected client`**

Find the test at `IpcServerTest.kt:82`. The current body (after the `serverScope` setup) looks roughly like:

```kotlin
                server = IpcServer(socketPath)
                server!!.start(serverScope)
                val client = connectClient()
                delay(100)
                server!!.emit("""{"event":"test","data":"hello"}""")
                val received = readFromClient(client, timeoutMs = 2000)
                assertTrue(received.contains("test"), "Expected broadcast to reach client: $received")
                client.close()
```

Change the body to insert `subscribeSync(client)` after the `delay(100)`:

```kotlin
                server = IpcServer(socketPath)
                server!!.start(serverScope)
                val client = connectClient()
                delay(100)
                subscribeSync(client)
                server!!.emit("""{"event":"test","data":"hello"}""")
                val received = readFromClient(client, timeoutMs = 2000)
                assertTrue(received.contains("test"), "Expected broadcast to reach subscribed client: $received")
                client.close()
```

- [ ] **Step 4.3: Update `dead client is removed without crash`**

The test at `IpcServerTest.kt:104` does NOT need a subscribe — its assertion is about server-side cleanup after a client closes mid-broadcast, not about message receipt. **No change needed.** Verify by re-reading the test body and confirming it doesn't read from the client; it just emits and then closes the client.

If the test asserts the client receives an event before closing, prepend `subscribeSync(client)` after the connect.

- [ ] **Step 4.4: Update `stale socket file is reclaimed`**

The test at `IpcServerTest.kt:151` emits after the reclaim path. If the emit reaches a connected client and the test asserts on that, prepend `subscribeSync(client)`. If it only asserts the server didn't crash, no change needed. Re-read the body and decide; the rule is: any `readFromClient` looking for `"event"` content implies a prior `subscribeSync(client)`.

- [ ] **Step 4.5: Rename-and-rewrite `late joiner receives state dump` → `subscriber receives state dump after sync subscribe`**

The original test at `IpcServerTest.kt:182` is structured as: pre-populate state, connect a fresh client, read lines, assert state-dump events arrive. Post-fix, the state-dump no longer fires at connect — it fires at `sync.subscribe`. Rewrite (NOT just patch):

```kotlin
    @Test
    fun `subscriber receives state dump after sync subscribe`() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                server = IpcServer(socketPath)
                server!!.start(serverScope)
                server!!.updateState(
                    IpcServer.SyncState(
                        profile = "test_profile",
                        phase = "reconcile",
                        scanCount = 42,
                        actionTotal = 10,
                        actionIndex = 3,
                        lastAction = "Upload",
                        lastPath = "/foo.txt",
                    ),
                )
                val client = connectClient()
                delay(100)

                // BEFORE sync.subscribe: no state-dump bytes should arrive.
                val preSubscribe = readFromClient(client, timeoutMs = 300, minLines = 1)
                // readFromClient returns whatever it accumulated; for the
                // pre-subscribe assertion we check that it received zero bytes.
                // (Implementation note: readFromClient returns "" when the
                // window times out without any data.)
                assertEquals("", preSubscribe, "Expected zero pre-subscribe bytes; got: $preSubscribe")

                // Issue sync.subscribe and drain the reply + state-dump.
                val req = """{"verb":"sync.subscribe"}""" + "\n"
                val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                while (w.hasRemaining()) client.write(w)
                val received = readFromClient(client, timeoutMs = 2000, minLines = 5)

                // Lines expected: {"ok":true}, sync_started, scan_progress,
                // action_count, action_progress.
                val lines = received.split("\n").filter { it.isNotEmpty() }
                assertTrue(lines.size >= 5, "Expected ≥5 lines, got ${lines.size}: $received")

                assertTrue(lines[0].contains("\"ok\":true"), "Line 1 must be the subscribe reply: ${lines[0]}")
                assertTrue(lines[1].contains("sync_started"), "Line 2 must be sync_started: ${lines[1]}")
                assertTrue(lines[2].contains("scan_progress"), "Line 3 must be scan_progress: ${lines[2]}")
                assertTrue(lines[3].contains("action_count"), "Line 4 must be action_count: ${lines[3]}")
                assertTrue(lines[4].contains("action_progress"), "Line 5 must be action_progress: ${lines[4]}")

                client.close()
            } finally {
                serverScope.cancel()
            }
        }
```

- [ ] **Step 4.6: Update `state dump events include profile and timestamp fields`**

The test at `IpcServerTest.kt:230` connects a client and reads state-dump lines, asserting each has `profile` and `timestamp`. Same shape as Step 4.5, but the assertion is field-presence rather than line-order. Update to issue `subscribeSync(client)` after connect, then re-read. The existing per-line assertions stay; just prepend the subscribe.

Concrete shape: replace the body's `val received = readFromClient(...)` block with:

```kotlin
                server = IpcServer(socketPath)
                server!!.start(serverScope)
                server!!.updateState(
                    IpcServer.SyncState(
                        profile = "test_profile",
                        phase = "reconcile",
                        scanCount = 5,
                    ),
                )
                val client = connectClient()
                delay(100)
                // Issue sync.subscribe; drain reply + state-dump in one read.
                val req = """{"verb":"sync.subscribe"}""" + "\n"
                val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                while (w.hasRemaining()) client.write(w)
                val received = readFromClient(client, timeoutMs = 2000, minLines = 3)
                // … existing field-presence assertions follow unchanged …
```

- [ ] **Step 4.7: Update `concurrent broadcast and RPC reply produce valid NDJSON lines`**

The test at `IpcServerTest.kt:460` opens a persistent listener client and asserts ≥50 valid NDJSON lines arrive while concurrent RPC traffic happens. Prepend `subscribeSync(client)` for the persistent listener:

```kotlin
                val listener = connectClient()
                delay(100)
                subscribeSync(listener)   // ← INSERT THIS LINE
                // … existing emit-loop + RPC-traffic + assert-NDJSON-integrity body …
```

The NDJSON-integrity assertion (every line parses as JSON, no mid-line corruption) is preserved; this test is load-bearing for `writeMutex` correctness and must keep passing.

- [ ] **Step 4.8: Update all five `IpcProgressReporterTest` tests**

Open `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcProgressReporterTest.kt`. Each of the five test methods (`onScanProgress`, `onActionProgress`, `onSyncComplete`, `emitSyncError`, `emitSyncStarted`, around lines 107, 129, 154, 187, 210) connects a client, then invokes the reporter, then reads lines. Add a `sync.subscribe` round-trip after the connect.

First, add a private helper near the top of the class (after `parseJson` at line 104):

```kotlin
    private suspend fun subscribeSync(client: SocketChannel) {
        val req = """{"verb":"sync.subscribe"}""" + "\n"
        val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
        while (w.hasRemaining()) client.write(w)
        // Drain reply + any state-dump (state may be empty if reporter
        // hasn't fired yet — that's fine; readLines tolerates a short read).
        readLines(client, timeoutMs = 250, expectedLines = 1)
    }
```

(The exact `readLines` signature is what already exists in this test file at line 80; the helper above assumes its first parameter is the client. If the existing helper has a different signature, adapt the call to drain at least one line within 250ms.)

In each of the five test methods, locate the `val client = connectClient()` call (or equivalent — `awaitClientCount` followed by client access). Immediately after the client connects (and after any `awaitClientCount` synchronisation), insert:

```kotlin
            subscribeSync(client)
```

Each test's downstream assertion logic (parsing the emitted JSON event for the expected fields) is unchanged.

- [ ] **Step 4.9: Run the full `:app:sync` test suite — must be green**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test -q > /tmp/sub-step49.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 5 /tmp/sub-step49.log || echo "no failures"
tail -20 /tmp/sub-step49.log
```
Expected: exit 0, no failures. If any of the updated tests still fail, the most likely cause is:
- Forgot to prepend `subscribeSync(client)` in one of the affected tests.
- The state pre-population in the rewritten `subscriber receives state dump after sync subscribe` test doesn't match what `flushStateDumpTo` actually emits (re-read `flushStateDumpTo` in Task 2.3 — `scan_progress` only emits when `state.phase != null`, etc.).
- Re-check the in-test `readFromClient` semantics — if it returns "" on timeout rather than throwing, the pre-subscribe assertion `assertEquals("", …)` is correct.

- [ ] **Step 4.10: Commit the test updates**

```bash
git add core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcProgressReporterTest.kt
git commit -m "$(cat <<'EOF'
test(ipc): update existing tests to call sync.subscribe per spec §3.5

Five tests in IpcServerTest and all five in IpcProgressReporterTest
previously relied on the broadcast-to-all-clients fanout. After the
subscriber-set fix, they must opt in via sync.subscribe.

- subscribeSync(client) helper added to both test files.
- broadcast sends message / stale socket reclaimed: prepend
  subscribeSync after connect.
- late joiner receives state dump: renamed and rewritten as
  `subscriber receives state dump after sync subscribe`. Original
  invariant (state dump on accept) is gone by design. New test
  asserts the wire ordering per spec §3.1: reply line first,
  then sync_started, scan_progress, action_count, action_progress
  in order.
- state dump events include profile and timestamp fields: prepend
  subscribeSync; field-presence assertions unchanged.
- concurrent broadcast and RPC NDJSON: prepend subscribeSync for
  the persistent listener. NDJSON-integrity assertion preserved.
- All five IpcProgressReporterTest tests: prepend subscribeSync.

dead client is removed without crash: NO change — asserts
server-side cleanup, doesn't read events.
EOF
)"
```

---

## Task 5: Land T2, T3, T4 in IpcSyncSubscriberSetTest

T1 is in place from Task 1. This task adds the three remaining tests from spec §3.5.

**Files:**
- Modify: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcSyncSubscriberSetTest.kt`

- [ ] **Step 5.1: Add T2 — wire-ordering test**

Append to the `IpcSyncSubscriberSetTest` class:

```kotlin
    /**
     * T2 from spec §3.5: subscriber receives reply first, then state
     * dump in defined order, then live events. Pins the wire contract
     * from spec §3.1.
     */
    @Test
    fun subscriber_receives_reply_first_then_state_dump_then_live_events() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val s = IpcServer(socketPath)
                server = s
                s.start(serverScope)
                s.updateState(
                    IpcServer.SyncState(
                        profile = "p",
                        phase = "reconcile",
                        scanCount = 42,
                        actionTotal = 10,
                        actionIndex = 3,
                        lastAction = "Upload",
                        lastPath = "/foo.txt",
                    ),
                )

                // Register sync.subscribe handler — production code lives in
                // SyncCommand, but the test only exercises IpcServer mechanics.
                s.registerHandler("sync.subscribe") { connId, _ ->
                    s.scheduleAfterReply(connId) {
                        s.flushStateDumpTo(connId)
                        s.registerSyncSubscriber(connId)
                    }
                    """{"ok":true}"""
                }

                val client = connectClient()
                delay(100)

                // Send sync.subscribe.
                val req = """{"verb":"sync.subscribe"}""" + "\n"
                val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                while (w.hasRemaining()) client.write(w)

                // Read 5 lines: reply + 4 state-dump events.
                val first5 = drainLines(client, count = 5, timeoutMs = 2_000)
                assertTrue(first5[0].contains("\"ok\":true"),
                    "Line 1 must be the subscribe reply, got: ${first5[0]}")
                assertTrue(first5[1].contains("sync_started"),
                    "Line 2 must be sync_started, got: ${first5[1]}")
                assertTrue(first5[2].contains("scan_progress"),
                    "Line 3 must be scan_progress, got: ${first5[2]}")
                assertTrue(first5[3].contains("action_count"),
                    "Line 4 must be action_count, got: ${first5[3]}")
                assertTrue(first5[4].contains("action_progress"),
                    "Line 5 must be action_progress, got: ${first5[4]}")

                // Emit a live event; must arrive AFTER the state dump.
                s.emit("""{"event":"live","seq":1}""")
                val live = drainLines(client, count = 1, timeoutMs = 1_000)
                assertTrue(live[0].contains("\"event\":\"live\""),
                    "Live event must follow state dump, got: ${live[0]}")
                client.close()
            } finally {
                serverScope.cancel()
            }
        }

    /**
     * Read exactly `count` newline-terminated lines from `client` within
     * `timeoutMs`. Returns the lines (with newlines stripped). Fails the
     * test if fewer than `count` lines arrive within the deadline.
     */
    private suspend fun drainLines(
        client: SocketChannel,
        count: Int,
        timeoutMs: Long,
    ): List<String> {
        val buf = ByteBuffer.allocate(8192)
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            buf.clear()
            val n = client.read(buf)
            if (n > 0) {
                buf.flip()
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                sb.append(String(bytes, Charsets.UTF_8))
                val lines = sb.toString().split("\n").filter { it.isNotEmpty() }
                if (lines.size >= count) return lines.take(count)
            }
            delay(20)
        }
        val partial = sb.toString().split("\n").filter { it.isNotEmpty() }
        kotlin.test.fail(
            "Expected $count lines within ${timeoutMs}ms; got ${partial.size}: $partial",
        )
    }
```

- [ ] **Step 5.2: Add T3 — JSON-escaping test**

Append to the same class:

```kotlin
    /**
     * T3 from spec §3.5: state-dump JSON is parseable even when path/action
     * fields contain `"` or `\`. Pins the §3.2.4 escapeJson co-fix.
     */
    @Test
    fun state_dump_handles_special_chars_in_path_via_escape_json() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val s = IpcServer(socketPath)
                server = s
                s.start(serverScope)
                s.updateState(
                    IpcServer.SyncState(
                        profile = "p",
                        actionTotal = 1,
                        actionIndex = 1,
                        lastAction = "Upload",
                        // Path containing both `"` and `\`. In a Kotlin
                        // triple-quoted string, " and \ are literal.
                        lastPath = """/docs/"hello"\bs.txt""",
                    ),
                )
                s.registerHandler("sync.subscribe") { connId, _ ->
                    s.scheduleAfterReply(connId) {
                        s.flushStateDumpTo(connId)
                        s.registerSyncSubscriber(connId)
                    }
                    """{"ok":true}"""
                }
                val client = connectClient()
                delay(100)
                val req = """{"verb":"sync.subscribe"}""" + "\n"
                val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                while (w.hasRemaining()) client.write(w)

                // Read reply + sync_started + action_count + action_progress
                // (4 lines; no phase = no scan_progress line).
                val lines = drainLines(client, count = 4, timeoutMs = 2_000)
                // Every line must parse as valid JSON.
                val json = kotlinx.serialization.json.Json
                lines.forEach { line ->
                    runCatching { json.parseToJsonElement(line) }
                        .onFailure {
                            kotlin.test.fail(
                                "Line did not parse as JSON: $line\nCause: ${it.message}",
                            )
                        }
                }
                client.close()
            } finally {
                serverScope.cancel()
            }
        }
```

- [ ] **Step 5.3: Add T4 — subscriber-set cleanup on disconnect**

Append to the same class:

```kotlin
    /**
     * T4 from spec §3.5: when a subscriber disconnects, its connId is
     * removed from syncSubscribers within the close-listener cycle.
     * Pins the SyncCommand-side cleanup contract; this test exercises
     * IpcServer in isolation by registering a close-listener inline.
     */
    @Test
    fun subscriber_set_is_cleaned_up_on_disconnect() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val s = IpcServer(socketPath)
                server = s
                s.registerConnectionCloseListener { connId ->
                    s.unregisterSyncSubscriber(connId)
                }
                s.registerHandler("sync.subscribe") { connId, _ ->
                    s.scheduleAfterReply(connId) {
                        s.registerSyncSubscriber(connId)
                    }
                    """{"ok":true}"""
                }
                s.start(serverScope)

                val client = connectClient()
                delay(100)
                val req = """{"verb":"sync.subscribe"}""" + "\n"
                val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                while (w.hasRemaining()) client.write(w)
                drainLines(client, count = 1, timeoutMs = 2_000)  // reply

                // Wait briefly so the post-reply hook registers the subscriber.
                val regDeadline = System.currentTimeMillis() + 1_000
                while (s.syncSubscribersSnapshot.isEmpty() &&
                    System.currentTimeMillis() < regDeadline) {
                    delay(20)
                }
                assertTrue(
                    s.syncSubscribersSnapshot.isNotEmpty(),
                    "Subscriber should be registered within 1s of receiving reply",
                )

                // Now close the client and wait for the close listener.
                client.close()
                val cleanupDeadline = System.currentTimeMillis() + 1_000
                while (s.syncSubscribersSnapshot.isNotEmpty() &&
                    System.currentTimeMillis() < cleanupDeadline) {
                    delay(20)
                }
                assertEquals(
                    emptySet<String>(), s.syncSubscribersSnapshot,
                    "Subscriber set should be empty within 1s of disconnect",
                )
            } finally {
                serverScope.cancel()
            }
        }
```

- [ ] **Step 5.4: Run all four IpcSyncSubscriberSetTest tests**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test --tests "org.krost.unidrive.sync.IpcSyncSubscriberSetTest" -q > /tmp/sub-step54.log 2>&1
echo "exit=$?"
grep -E "FAILED|PASSED|non_subscriber|subscriber_receives|state_dump_handles|subscriber_set_is" /tmp/sub-step54.log -C 1
```
Expected: exit 0. All four tests pass.

- [ ] **Step 5.5: Commit T2/T3/T4**

```bash
git add core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcSyncSubscriberSetTest.kt
git commit -m "test(ipc): T2/T3/T4 wire-ordering, JSON-escape, and cleanup tests

Three tests from spec §3.5 land alongside the contract T1:

- subscriber_receives_reply_first_then_state_dump_then_live_events
  pins the wire ordering from §3.1: {\"ok\":true} on line 1,
  then sync_started + scan_progress + action_count + action_progress
  in order, then live events. Includes inline sync.subscribe handler
  registration to keep the test self-contained (SyncCommand is in
  a different module).

- state_dump_handles_special_chars_in_path_via_escape_json
  pins the §3.2.4 JSON-escape co-fix. Path contains both \" and \\\\;
  every state-dump line must parse as valid JSON.

- subscriber_set_is_cleaned_up_on_disconnect uses the test-only
  internal val syncSubscribersSnapshot accessor to assert that
  disconnect triggers unregisterSyncSubscriber via the close-listener.

Shared drainLines helper for line-counted reads with deadlines."
```

---

## Task 6: Full suite, BACKLOG close-out, deploy, live smoke

- [ ] **Step 6.1: Run the full `:app:sync` test suite**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:sync:test -q > /tmp/sub-step61.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 5 /tmp/sub-step61.log || echo "no failures"
tail -20 /tmp/sub-step61.log
```
Expected: exit 0. If anything in `IpcServerTest`, `IpcProgressReporterTest`, `IpcDispatcherIsolationTest`, or `IpcServerPermissionsTest` fails, surface to user — Task 4's catalogue missed something.

- [ ] **Step 6.2: Run the broader composite test surface to catch indirect regressions**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew test -q > /tmp/sub-step62.log 2>&1
echo "exit=$?"
grep -E "FAILED|ERROR|Exception" -C 5 /tmp/sub-step62.log || echo "no failures"
```
Expected: exit 0. The wider test surface should be unaffected — no other module reads `IpcServer`'s broadcast topology directly. If something fails (e.g., a CLI-level integration test that connects and listens), apply the same `sync.subscribe` prepend pattern, commit, then re-run.

- [ ] **Step 6.3: Close the BACKLOG entry**

Edit `BACKLOG.md`: find and remove the row beginning:

```
| Phase 2 co-daemon disconnects mid-IPC: `ls` on FUSE mount returns EIO, daemon logs `Broken pipe` on `ipc-io-N` write |
```

Append the same descriptive text (rewritten as a CLOSED entry in bullet form, matching the existing CLOSED.md pattern) to `CLOSED.md` at the end of the file. The bullet should describe the fix in one paragraph, citing the commits (Tasks 1–5), the spec, and the live-smoke result.

Also add a follow-up entry to `BACKLOG.md` under the Low or Cross-cutting section (per spec §5):

```
| `unidrive-ui` needs to call `sync.subscribe` after connecting | After the sync-progress subscriber-set fix landed (commit <task-2-sha>), legacy clients that connect-and-listen without subscribing receive no events. The systray UI binary at `~/.local/lib/unidrive-ui.jar` (Apr 2 09:58 build) is the suspected one — it'll show stale state until the next deploy. Fix is a one-line `sync.subscribe` round-trip after the connect in the UI's IPC client. Detection: when launched against a daemon running the new build, the systray shows "no recent sync activity" even mid-sync. |
```

Commit:

```bash
git add BACKLOG.md CLOSED.md
git diff --cached  # verify only the expected rows moved/added
git commit -m "docs(backlog): close co-daemon Broken-pipe entry; file unidrive-ui follow-up"
```

- [ ] **Step 6.4: Deploy the fix locally**

Run:
```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:deploy -q
ls -la ~/.local/lib/unidrive/unidrive-0.0.1.jar
```
Expected: deploy completes, the jar timestamp is current.

- [ ] **Step 6.5: Restart the posteo_onedrive sync daemon under the new build**

If a previous `unidrive -p posteo_onedrive sync --watch` is running in another terminal, the operator should `Ctrl-C` it and relaunch under the new build:

```bash
unidrive -p posteo_onedrive sync --watch
```

Confirm in the daemon log that startup banners include the current build commit:

```bash
grep -E "IPC: transport pool size" ~/.local/share/unidrive/unidrive.log | tail -3
```

- [ ] **Step 6.6: Live smoke — `ls` and `less` against a busy daemon**

In a separate terminal:

```bash
mkdir -p /tmp/onedrive-smoke-sub
unidrive -p posteo_onedrive mount /tmp/onedrive-smoke-sub
```

Wait for the mount to come up. Then, while the SyncEngine log shows ongoing `Download:` lines:

```bash
ls /tmp/onedrive-smoke-sub/
less /tmp/onedrive-smoke-sub/geheim.txt
```

Expected: `ls` lists real cloud entries; `less` prints the file content. Neither returns EIO.

In parallel, watch the daemon log for the symptom:

```bash
tail -F ~/.local/share/unidrive/unidrive.log | grep -E "Broken pipe|dropping dead|Write timeout"
```

Expected: zero new lines of `Broken pipe`, `dropping dead`, or `Write timeout` during the mount session. If any appear, the fix is incomplete — surface to user with the captured log evidence; do NOT move the BACKLOG entry back (Step 6.3 already filed it).

Cleanup:

```bash
fusermount3 -u /tmp/onedrive-smoke-sub
rmdir /tmp/onedrive-smoke-sub
```

- [ ] **Step 6.7: Merge to main**

```bash
git checkout main
git merge --no-ff fix/sync-subscriber-set -m "Merge fix/sync-subscriber-set"
git branch -d fix/sync-subscriber-set
git log --oneline -10
```

Expected: the merge commit and the per-task commits (1, 2, 3, 4, 5, 6.3) all appear in the log.

---

## Out of scope (deferred)

- `unidrive-ui.jar` update to call `sync.subscribe`. Filed as a follow-up BACKLOG entry in Step 6.3.
- 4 MiB `read_line` cap on the co-daemon side (`mount/src/ipc.rs:191`). Not reached via this path post-fix.
- Per-subscriber channels for sync events (spec R6 escape hatch). File only if a slow-subscriber scenario actually surfaces in production.

---

## Self-review pass (writer-side)

Checked against `docs/dev/specs/sync-progress-subscriber-set-design.md`:

- **§3.1 wire contract**: T2 in Task 5.1 asserts the exact byte sequence.
- **§3.2.1 subscriber-set field**: Task 2.1.
- **§3.2.2 public methods**: Task 2.2 (register/unregister/snapshot) + Task 2.3 (flushStateDumpTo).
- **§3.2.3 mechanism β**: Task 2.6 (defensive clear + success-gated hook).
- **§3.2.4 JSON-escape fix**: Task 2.3 (flushStateDumpTo body) + buildStateDumpLine rewrite in same step. T3 in Task 5.2 pins it.
- **§3.2.5 broadcast-loop filter**: Task 2.5.
- **§3.2.6 remove accept-time flushStateDump call**: Task 2.4.
- **§3.3 SyncCommand changes**: Task 3.
- **§3.4 IpcProgressReporter unchanged**: confirmed by Task 4 (only test-side changes touch the Reporter's callers).
- **§3.5 four new tests T1–T4**: Task 1 (T1) + Task 5 (T2, T3, T4).
- **§3.5 existing test updates**: Task 4 (catalogued each one).
- **§3.6 live smoke**: Task 6.6.
- **§4 R7 (handler exceptions)**: Task 2.6 includes the `handlerThrew` gate.
- **§4 R8 (stale pending entries)**: Task 2.6 includes the defensive clear at top.
- **§5 acceptance**: Task 6.1/6.2 (full suite), 6.3 (BACKLOG move + follow-up entry), 6.6 (live smoke).

No TBDs, no "implement appropriate error handling" placeholders. Type-consistency check: `registerSyncSubscriber`, `unregisterSyncSubscriber`, `scheduleAfterReply`, `flushStateDumpTo`, `syncSubscribersSnapshot` are named identically across the spec, the task code blocks, and the test usages. Plan is self-contained.