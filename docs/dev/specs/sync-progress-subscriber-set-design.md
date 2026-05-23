# Sync-Progress Subscriber Set — Design

**Status:** Proposed — design doc, not yet implemented. Revised after deep-review pass surfaced eight findings against the first draft (`479d5d0` + `3944481`); this revision is end-to-end consistent against all eight.
**Origin:** Phase 2 mount smoke-test finding, BACKLOG entry committed in `1de0cb3`, refined in `013da46`.
**Touches:**
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` (broadcast filter, new public methods, dispatchRequest extension for post-reply hook).
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt` (no change — see §3.4).
- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt` (verb registration, external close-listener).
- Existing IPC test suites that today connect-and-listen without subscribing (`IpcServerTest`, `IpcProgressReporterTest` — see §3.5 and §5).

## 1. Problem

### 1.1 Observed failure

Live Phase 2 mount smoke against `posteo_onedrive` (under the IPC-transport-dispatcher fix from `309a5b3`): `ls /tmp/onedrive-smoke/` returns `Input/output error (os error 5)`. Daemon log `~/.local/share/unidrive/unidrive.log` shows recurring `IPC: dropping dead client … Broken pipe` on `ipc-io-N` threads at ~30s cadence between 21:29 and 21:33 on 2026-05-23. Client ids `f9548023-…`, `b3fff86a-…`, `9113361c-…`, `c56cf5bc-…` cycle: the FUSE co-daemon reconnects, the server logs another Broken-pipe shortly after, repeat.

Zero `Write timeout exceeded` lines under the new build — the transport-dispatcher fix is structurally sound; this is a different failure further down the stack.

### 1.2 Root cause

`IpcServer`'s broadcast loop (`IpcServer.kt:206-227`) fans out every `emit(json)` call to **every** connected client, regardless of opt-in:

```kotlin
broadcastJob =
    scope.launch(transport) {
        for (json in channel) {
            val line = (json + "\n").toByteArray(Charsets.UTF_8)
            val dead = mutableListOf<ClientEntry>()
            for (entry in clients) {              // ← iterates ALL clients
                try {
                    entry.writeMutex.withLock {
                        writeNonBlocking(entry.channel, ByteBuffer.wrap(line))
                    }
                } catch (e: IOException) {
                    log.debug("IPC: dropping dead client id={}: {}", entry.id, e.message)
                    dead.add(entry)
                }
            }
            // …
        }
    }
```

`IpcProgressReporter.emit(...)` (`IpcProgressReporter.kt:23`) is the sole production caller of `server.emit(json)` — grep-verified in §4 R2. Under `--watch`, it fires continuously for sync_started, scan_progress, reconcile_progress, action_count, action_progress, transfer_progress, sync_complete, sync_error, warning, poll_state.

Separately, `IpcServer.start()`'s accept loop calls `flushStateDump(entry)` (`IpcServer.kt:257-308`) for **every** new connection — writing the current sync state's startup events to the fresh socket before the client has sent anything.

The `unidrive-mount` co-daemon connects to issue verb requests only. Its `IpcClient` defines a `subscribe()` method (`mount/src/ipc.rs:122`) but **never calls it** — verified by `grep -rn "subscribe\|\.subscribe(" unidrive-mount-linux/mount/src/` returning only the definition and two doc comments. Two pieces of in-tree documentation confirm the intent: `mount/src/ipc.rs:118-122` ("The Phase-2 client does NOT consume that stream — Phase 3 work") and `mount/src/reconnect.rs:192` ("Deliberately NO `subscribe` method").

The mechanism:
1. Co-daemon connects → JVM's accept loop calls `flushStateDump(entry)` → state-dump bytes land in the socket buffer the co-daemon never reads.
2. `SyncEngine` calls `IpcProgressReporter.emit(...)` → `server.emit(json)` → broadcast loop fans the JSON to every client, including the co-daemon's socket.
3. Co-daemon's `round_trip` (`mount/src/ipc.rs:174-197`) issues a verb, reads `read_line(...)`, gets a state-dump or broadcast line instead of the verb reply, returns `IpcError::Malformed`. The reconnect wrapper retries only `IpcError::Io` (`mount/src/reconnect.rs:84-90`), so `Malformed` propagates up.
4. Eventually the kernel socket buffer fills; the JVM's next broadcast write returns `Broken pipe`; the JVM drops the client. Co-daemon reconnects; cycle repeats.

### 1.3 Existing partial symmetry

The **hydration event surface** is already subscriber-set-gated correctly: `HydrationIpcHandler.kt:210-213` handles `hydration.subscribe` by inserting the connId into a private `subscribers` set; events are delivered via per-subscriber `Channel<HydrationEvent>` + writer coroutines + bounded backpressure with drop-oldest+sentinel (`HydrationIpcHandler.kt:81-92`); fanout uses `IpcServer::writeToConnection` for targeted writes. Two in-tree comments document that sync-progress was supposed to follow the same model: `IpcServer.kt:120` and `SyncCommand.kt:466`. The legacy broadcast-to-all-clients loop is the leg that was skipped.

### 1.4 What this design does NOT address

- **The 4 MiB `read_line` cap on the co-daemon side** (`mount/src/ipc.rs:191`). After this fix, sync-progress bytes stop reaching the co-daemon, so the cap can't be tripped via this path. Whether the JVM should cap outbound replies on the request-reply path is a separate question, deferred.
- **The co-daemon's `IpcError::Malformed` non-retry behavior.** After this fix, the co-daemon won't see Malformed lines from this source. The retry policy itself is fine for the case it's designed to handle.
- **The pre-existing JSON escaping bug in `flushStateDump`** is fixed inline as part of this change (see §3.2.4) because the rename to `flushStateDumpTo` touches the same body. Documented as a co-fix, not a separate scope.

## 2. Goals & non-goals

**Goal G1:** A connected IPC client receives sync-progress events if and only if it has issued a `sync.subscribe` verb and received the `{"ok":true}` reply.

**Goal G2:** A connected IPC client receives the initial state-dump events if and only if it has just issued `sync.subscribe`. Non-subscribers never receive state-dump bytes on their socket.

**Goal G3:** The co-daemon's FUSE mount performs `ls` / `getattr` / `open` / `read` against `/tmp/onedrive-smoke/` under a busy SyncEngine without any `Broken pipe` log lines during the session.

**Goal G4 (opt-in symmetry, internal-topology asymmetry acknowledged):** The wire-level contract for `sync.subscribe` matches `hydration.subscribe`: client issues the verb, reads `{"ok":true}` as the first line, then receives the event stream. **Internal delivery topology intentionally differs:** sync events go through the shared `Channel<String>` broadcast loop with a subscriber-set filter, while hydration uses per-subscriber channels + drop-oldest backpressure. Rationale: sync-progress has one producer and low fanout cardinality at this stage; per-subscriber-channel apparatus is over-engineering for the current load profile. The migration path if pressure changes is documented in §4 R6.

**Goal G5:** Pre-existing `flushStateDump` JSON-injection vulnerability (raw interpolation of `lastPath` / `lastAction` into JSON string literals) is closed inline. Subscribers receive parseable JSON regardless of cloud-side filename content.

**Non-goal NG1:** Removing the broadcast loop, `Channel<String>`, or `emit(...)` method. They remain — they just filter their fanout to subscribers. The plumbing is sound; only the audience was wrong.

**Non-goal NG2:** Touching `HydrationIpcHandler`'s subscriber logic. That surface works correctly. The fix mirrors its *wire contract*, not its internal topology.

**Non-goal NG3:** Adding a `sync.unsubscribe` verb. Disconnection IS the unsubscribe — close-listener handles it. Symmetric with hydration (no explicit unsubscribe). Add only when a real use case appears.

**Non-goal NG4:** Backwards-compatibility shim for old clients that connect-and-listen without subscribing. `unidrive-ui.jar` (Apr 2 09:58 build) is the suspected one; it'll need a one-line `sync.subscribe` call on its next deploy. CLAUDE.md "Avoid backwards-compatibility hacks" applies.

## 3. Design

### 3.1 Wire contract

**Verb:** `sync.subscribe`. **Request:** `{"verb":"sync.subscribe"}`. **Reply:** `{"ok":true}`.

**Observable byte sequence on the subscriber's socket after the request is sent:**

```
{"ok":true}\n
{"event":"sync_started",…}\n         ← state-dump lines (if syncState non-null at subscribe time)
{"event":"scan_progress",…}\n        ← state-dump continued
{"event":"action_count",…}\n         ← state-dump continued
{"event":"action_progress",…}\n      ← state-dump continued
{"event":"…",…}\n                    ← live events from emit() begin here, in chronological order
…
```

**The reply is always the first line.** State-dump lines (if any) follow, then live events. This matches `hydration.subscribe`'s observable shape: a `round_trip`-style client reads the reply, then switches modes to consume the event stream.

**No event interleaving with the reply.** The implementation (§3.2.3, mechanism β) guarantees the reply is written to the socket before either the state dump or any live event reaches the subscriber.

**State-dump line count is bounded:** at most one of each of `sync_started`, `scan_progress`, `action_count`, `action_progress` — synthesised from the current `SyncState`. The existing `flushStateDump` body (`IpcServer.kt:257-308`) is the basis — see §3.2.4 for the JSON-escaping fix.

**Idempotent double-subscribe:** if a client sends `sync.subscribe` twice on the same connection, the second invocation re-runs the state dump (the subscriber gets a fresh snapshot) and is a no-op on the subscriber-set membership (it's a `Set.add`). Harmless. Documented but not gated.

### 3.2 IpcServer changes

#### 3.2.1 Subscriber-set field

```kotlin
private val syncSubscribers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
```

Membership is set-semantics; thread-safe (`ConcurrentHashMap.newKeySet`); no separate lock needed. The broadcast loop reads `syncSubscribers.contains(entry.id)` under no lock — `ConcurrentHashMap` documents `contains` is consistent with concurrent `add`/`remove`.

#### 3.2.2 New public methods on `IpcServer`

All three methods are `public` (not `internal`). The composite Gradle build places `IpcServer` in `:app:sync` and the caller (`SyncCommand`) in `:app:cli`; Kotlin `internal` visibility does not cross module boundaries in this layout. The existing `writeToConnection` is `public` for the same reason.

```kotlin
/**
 * Mark a connection as a sync-progress subscriber. After this call, the
 * connection receives sync-progress events from `emit(...)` until it
 * disconnects (cleanup via `unregisterSyncSubscriber`, called from a
 * connection-close listener registered externally by `SyncCommand` for
 * stylistic parity with the existing hydration close listeners).
 *
 * Symmetric with `HydrationIpcHandler.registerSubscriber` at the wire
 * level; the two subscriber sets are independent (a client may subscribe
 * to one, both, or neither).
 */
public fun registerSyncSubscriber(connectionId: String) {
    syncSubscribers.add(connectionId)
}

/**
 * Remove a connection from the sync-progress subscriber set. Idempotent;
 * called from the connection-close listener `SyncCommand` registers.
 * Public for the same module-boundary reason as `registerSyncSubscriber`.
 */
public fun unregisterSyncSubscriber(connectionId: String) {
    syncSubscribers.remove(connectionId)
}

/**
 * Replay the current SyncState as initial state-dump events to a single
 * subscriber connection. Called from the `sync.subscribe` post-reply hook
 * (see scheduleAfterReply in §3.2.3) so that subscribers see context for
 * the in-progress sync, but non-subscribers (e.g. the FUSE co-daemon,
 * request-reply clients) never receive these unsolicited bytes on their
 * socket.
 *
 * If `syncState` is null (no sync in progress at subscribe time), this
 * is a no-op. If the connection has closed between subscribe and the
 * post-reply hook firing, logs a WARN and returns — surfacing the rare
 * timing window for future operator debugging.
 *
 * Public for the same module-boundary reason as `registerSyncSubscriber`.
 */
public suspend fun flushStateDumpTo(connectionId: String) {
    val state = syncState ?: return
    val entry = clients.firstOrNull { it.id == connectionId } ?: run {
        log.warn(
            "IPC: flushStateDumpTo called for unknown connection id={}; " +
                "client likely closed between sync.subscribe parse and post-reply hook",
            connectionId,
        )
        return
    }
    // Body: same shape as today's flushStateDump(entry), but every dynamic
    // string field passes through escapeJson(). See §3.2.4 for the lines.
    // …
}
```

#### 3.2.3 Post-reply hook in `dispatchRequest` (mechanism β)

The current `dispatchRequest` (`IpcServer.kt:326-347`) writes the reply and returns. The fix adds a per-`connectionId` slot for a one-shot `suspend () -> Unit` action that, if present, runs **after** the reply has been written. The handler doesn't return the action — it registers it on `IpcServer` mid-flight via a thin scheduler API:

```kotlin
private val pendingPostReply = java.util.concurrent.ConcurrentHashMap<String, suspend () -> Unit>()

/**
 * Schedule a one-shot action to run AFTER the current request's reply is
 * written to the socket. Intended for verb handlers that need to push
 * additional bytes (e.g. state-dump on sync.subscribe) without those
 * bytes preceding the verb's reply on the wire.
 *
 * Must be called from inside a verb handler running under
 * dispatchRequest's withContext(handlerDispatcher) block. Calling outside
 * a handler is a no-op (the action will never fire). Subsequent calls
 * within the same request overwrite the prior action — one slot per
 * connection per in-flight request.
 *
 * The action fires only on the successful-reply path. If the handler
 * threw and dispatchRequest wrote an error envelope, the scheduled
 * action is discarded.
 *
 * Public for the same module-boundary reason as registerSyncSubscriber.
 */
public fun scheduleAfterReply(connectionId: String, action: suspend () -> Unit) {
    pendingPostReply[connectionId] = action
}
```

`dispatchRequest` extension (replaces the body at `IpcServer.kt:326-347`):

```kotlin
private suspend fun dispatchRequest(client: SocketChannel, connId: String, line: String) {
    // R8: defensive clear at the top — any stale entry from a prior request
    // that errored on this connection is removed before this request runs.
    pendingPostReply.remove(connId)

    val verb = parseVerb(line) ?: run { /* existing logging */ return }
    val handler = handlers[verb] ?: run { /* existing logging */ return }

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

    // R7: post-reply hook fires ONLY on the successful-reply path. If the
    // handler threw, the scheduled action (if any) is discarded.
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

**Order on the wire (formal):**

1. `dispatchRequest` writes the reply bytes under `entry.writeMutex.withLock { writeNonBlocking(...) }`. The mutex is released when the lock body returns.
2. `pendingPostReply.remove(connId)` retrieves the scheduled action (if any). On the error path it is discarded; on the success path it is invoked.
3. For `sync.subscribe`, the action is `{ flushStateDumpTo(connId); registerSyncSubscriber(connId) }`.
4. `flushStateDumpTo` writes state-dump lines, each under its own `entry.writeMutex.withLock` (same per-line mutex pattern as today's `flushStateDump`).
5. After `flushStateDumpTo` returns, `registerSyncSubscriber` adds the connId to `syncSubscribers`.
6. Only now is the broadcast loop allowed to see the new subscriber.

**Why this is race-free:** the broadcast loop's only gate to write to a connection is `entry.id in syncSubscribers`. That predicate is false until step 5. Steps 1–4 all happen before step 5. So the broadcast loop's first write to this subscriber is strictly after the reply and after the entire state dump. No interleaving possible.

**Why steps 4 and 5 don't race with each other:** they run sequentially on the same coroutine inside the post-reply hook. Step 5 (the set add) cannot start until step 4's last `withLock { ... }` has returned.

**Why steps 1 and 4 don't race with the broadcast loop:** the broadcast loop only attempts a write to this connection if `entry.id in syncSubscribers`, which is false throughout steps 1–4.

#### 3.2.4 `flushStateDump` JSON-escaping fix

The existing private `flushStateDump(entry: ClientEntry)` builds JSON via direct string interpolation (`IpcServer.kt:257-308`). The `action_progress` line at `:291` interpolates `state.lastAction` and `state.lastPath ?: ""` raw — a path containing `"`, `\`, or control chars produces malformed JSON. Same risk in the `scan_progress` line's `state.phase` (`:271`). The `state.profile` field is shaped by config and is less risky but is escaped for uniformity.

Fix: every dynamic string field passes through the existing `escapeJson(s: String): String` helper (`IpcServer.kt:399`). The helper already returns the value with surrounding quotes, so the interpolation pattern changes from `"path":"${state.lastPath ?: ""}"` to `"path":${escapeJson(state.lastPath ?: "")}`.

Concrete rewrites in `buildStateDumpLine` and `flushStateDumpTo`:

```kotlin
// Before (the buildStateDumpLine output):
"""{"event":"$event","profile":"$profile","timestamp":"$timestamp"}"""

// After:
"""{"event":${escapeJson(event)},"profile":${escapeJson(profile)},"timestamp":${escapeJson(timestamp)}}"""

// Before (scan_progress extra):
""""phase":"${state.phase}","count":${state.scanCount}"""

// After:
""""phase":${escapeJson(state.phase ?: "")},"count":${state.scanCount}"""

// Before (action_progress extra):
""""index":${state.actionIndex},"total":${state.actionTotal},"action":"${state.lastAction}","path":"${state.lastPath ?: ""}""""

// After:
""""index":${state.actionIndex},"total":${state.actionTotal},"action":${escapeJson(state.lastAction ?: "")},"path":${escapeJson(state.lastPath ?: "")}"""
```

Numeric fields (`scanCount`, `actionIndex`, `actionTotal`) are integers; no escaping needed.

#### 3.2.5 Broadcast-loop filter

The existing loop body (`IpcServer.kt:206-227`) iteration changes:

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

The log key changes from "dropping dead client" to "dropping dead sync-subscriber" — disambiguates this drop class from other failure shapes when grepping the daemon log.

When a subscriber is dropped due to write failure, it's also removed from `clients` (existing behavior), which triggers the close-listeners. `unregisterSyncSubscriber` (registered as a close-listener by `SyncCommand` — see §3.3) removes the connId from `syncSubscribers`. Same cleanup path as a graceful disconnect.

#### 3.2.6 Remove `flushStateDump(entry)` call from accept loop

In `IpcServer.start()`'s accept loop (around `:147-204`), the line `flushStateDump(entry)` (around `:162`) is **deleted**. State-dump bytes never go out at accept time after this change.

The private helper `flushStateDump(entry: ClientEntry)` is **renamed** to `flushStateDumpTo(connectionId: String)` and **promoted to public** per §3.2.2. Its body becomes the implementation behind the public method, with the lookup-by-id at the top and the JSON-escaping fix from §3.2.4 applied.

### 3.3 SyncCommand changes

Three additions in `SyncCommand.kt` immediately after the existing hydration handler registration block (around `:455-470`):

```kotlin
// Register the sync.subscribe verb. Symmetric with hydration.subscribe.
// The handler returns the reply synchronously; the state-dump replay and
// subscriber-set registration happen in a post-reply hook (mechanism β,
// see IpcServer.kt scheduleAfterReply) so that subscribers receive the
// {"ok":true} reply as the first line on their socket, followed by the
// state dump, followed by live events — never interleaved.
ipcServer.registerHandler("sync.subscribe") { connId, _ ->
    ipcServer.scheduleAfterReply(connId) {
        ipcServer.flushStateDumpTo(connId)
        ipcServer.registerSyncSubscriber(connId)
    }
    """{"ok":true}"""
}

// Cleanup on disconnect — symmetric with the hydration close listener
// registered three lines above. External registration keeps the
// SyncCommand wiring centralised; IpcServer doesn't self-register
// internal listeners for module-boundary state.
ipcServer.registerConnectionCloseListener { connId ->
    ipcServer.unregisterSyncSubscriber(connId)
}
```

### 3.4 IpcProgressReporter changes

**None.** `IpcProgressReporter.emit(...)` (`IpcProgressReporter.kt:23`) still calls `server.emit(json.toString())`. The broadcast loop's subscriber-set filter (§3.2.5) silently gates the fanout. The Reporter doesn't know or care; its call sites in `SyncEngine` don't change.

### 3.5 Testing

Four new tests live in `IpcSyncSubscriberSetTest.kt` alongside `IpcDispatcherIsolationTest`. Real-socket I/O via the existing `runBlocking(Dispatchers.IO) + SupervisorJob + serverScope.cancel()` pattern documented in `IpcServerTest.kt` (per the in-file comment: "runTest with real NIO operations will hang"). The IPC-transport-fix's `transportDispatcher` injection seam is NOT used here — virtual time would deadlock against the real-socket I/O the test exercises, and the assertions don't need it (the broadcast loop's behavior is observable end-to-end within a few hundred ms of wall-clock, comfortably under the 2-3s read deadlines the existing test harness uses).

#### T1 — `non_subscriber_receives_no_sync_progress_bytes` (contract test)

Connect a client. Do NOT issue `sync.subscribe`. Issue `server.emit("""{"event":"test"}""")` from the test. Read from the client with a 500ms deadline. Assert: zero bytes received. **This is the test that protects against regression of the exact Phase 2 bug.**

#### T2 — `subscriber_receives_reply_first_then_state_dump_then_live_events`

Pre-populate `server.updateState(SyncState(profile="p", phase="reconcile", scanCount=42, actionTotal=10, actionIndex=3, lastAction="Upload", lastPath="/foo.txt"))`. Connect a client. Send `{"verb":"sync.subscribe"}`. Read lines from the client with a 2s deadline. After reading the first 5 lines, issue `server.emit("""{"event":"live"}""")` from the test, read one more line. Assert the ordered sequence:

1. Line 1: `{"ok":true}` (parsed as JSON, `ok == true`).
2. Lines 2–5: state-dump events in order — `sync_started`, `scan_progress` (phase=reconcile, count=42), `action_count` (total=10), `action_progress` (index=3, total=10, action=Upload, path=/foo.txt). All four parse as valid JSON.
3. Line 6: the live event `{"event":"live"}`.

**This test pins the wire contract from §3.1.**

#### T3 — `state_dump_handles_special_chars_in_path_via_escape_json`

Pre-populate `server.updateState(SyncState(profile="p", actionTotal=1, actionIndex=1, lastAction="Upload", lastPath="""/docs/"hello"\backslash.txt"""))`. Connect a client. Send `{"verb":"sync.subscribe"}`. Read the reply line + state-dump lines. Assert: every line parses as valid JSON via `kotlinx.serialization.json.Json.parseToJsonElement(line)` without throwing. **Pins the §3.2.4 JSON-escaping fix.**

#### T4 — `subscriber_set_is_cleaned_up_on_disconnect`

Connect a client. Send `{"verb":"sync.subscribe"}`. Read the reply. `server.emit(...)`, confirm the client reads it. Close the client. Poll `server.syncSubscribersSnapshot` (test-only `internal val` accessor that returns `syncSubscribers.toSet()`) with a 500ms deadline; assert the disconnected connId is no longer present. The `internal` modifier works here because the test lives in the same `:app:sync` Gradle module as `IpcServer`.

#### Test names (locked)

- `non_subscriber_receives_no_sync_progress_bytes`
- `subscriber_receives_reply_first_then_state_dump_then_live_events`
- `state_dump_handles_special_chars_in_path_via_escape_json`
- `subscriber_set_is_cleaned_up_on_disconnect`

#### Existing test updates (required for §5 acceptance)

The fix breaks existing tests that connect-and-listen without subscribing. Enumerated:

`IpcServerTest.kt`:
- `broadcast sends message to connected client` (around `:82`) — prepend `sync.subscribe` round-trip after connect.
- `stale socket file is reclaimed` (around `:151`) — prepend `sync.subscribe` only if the test asserts event delivery; pure connect-then-close cases need no change.
- `late joiner receives state dump` (around `:182`) — **rename and rewrite** as `subscriber_receives_state_dump_after_sync_subscribe`. The original name no longer matches what the test exercises post-fix; the original invariant (state-dump fires on connect) is gone by design.
- `state dump events include profile and timestamp fields` (around `:230`) — prepend `sync.subscribe`; the field-content assertions are unchanged.
- `concurrent broadcast and RPC reply produce valid NDJSON lines` (around `:460`) — prepend `sync.subscribe` for the persistent listener. The NDJSON-integrity assertion is preserved; this test is load-bearing for the writeMutex correctness.

`IpcProgressReporterTest.kt`:
- All five tests (`onScanProgress`, `onActionProgress`, `onSyncComplete`, `emitSyncError`, `emitSyncStarted`): prepend `sync.subscribe` round-trip after connect. Field-content assertions unchanged.

The implementation plan catalogues each test's exact line numbers and produces the per-test diff. This spec section is the invariant: every broken test is updated, none are deleted, the original assertion intent is preserved or explicitly replaced.

### 3.6 Live smoke test (manual, post-merge)

Same shape as the IPC-transport fix's §3.5 smoke (per the corrected plan in `91955b7`):

1. `cd /home/gernot/dev/git/unidrive/core && ./gradlew :app:cli:deploy -q`
2. Kill any running `java … -p posteo_onedrive sync --watch` process. Relaunch in a terminal: `unidrive -p posteo_onedrive sync --watch`.
3. `mkdir -p /tmp/onedrive-smoke && unidrive -p posteo_onedrive mount /tmp/onedrive-smoke`
4. `ls /tmp/onedrive-smoke/` — **must succeed**, listing real cloud entries.
5. `less /tmp/onedrive-smoke/geheim.txt` — must print the file content.
6. `tail -F ~/.local/share/unidrive/unidrive.log | grep -E "Broken pipe|dropping dead"` in a parallel terminal. Expected: zero new lines during the mount session.
7. Cleanup: `fusermount3 -u /tmp/onedrive-smoke`.

## 4. Risks and open questions

**R1 — `unidrive-ui` breaks silently.** The systray UI binary at `~/.local/lib/unidrive-ui.jar` (Apr 2 09:58, pre-subscriber-set) connects expecting events to flow without `sync.subscribe`. After this fix, it'll show stale state forever. Mitigation: trivial UI-side fix (one-line `sync.subscribe` after connect). Detection: when the user next runs `unidrive-ui`, the systray will show "no recent sync activity" even mid-sync. Out of scope for this design; a follow-up BACKLOG entry to update `unidrive-ui` is filed at close-out (per §5).

**R2 — Other broadcast-listening consumers.** `grep -rn "server\.emit\|ipcServer\.emit" core/ --include="*.kt"` (production code, excluding tests) returns exactly one caller: `IpcProgressReporter.kt:23`. No other internal callers exist, so the JVM-side surface is fully accounted for. The grep was performed during the deep-review stage and is the substantive evidence behind G1.

**R3 — Race: event delivery vs reply ordering.** Naive ordering (subscriber set updated before reply written) admits a window where the broadcast loop delivers a fresh event before the reply. **Addressed by §3.2.3 mechanism β:** `dispatchRequest` writes the reply BEFORE invoking the post-reply hook; the post-reply hook does the state-dump-then-register sequence. Broadcast loop sees an empty `syncSubscribers` membership for this connId throughout the reply write and the state-dump write; only after the register step can it deliver. No locking required between the broadcast loop and the post-reply hook — the set predicate is the only gate.

**R4 — Double-subscribe on the same connection.** A second `sync.subscribe` produces a second state dump (subscriber gets a fresh snapshot) and is a no-op on subscriber-set membership. Mildly noisy but harmless. Documented in §3.1.

**R5 — Pool-size impact on the transport pool from `309a5b3`.** The transport pool is 4 threads. The broadcast loop is a single thread on that pool. Adding the `scheduleAfterReply` map + the post-reply hook in `dispatchRequest` doesn't change that — `sync.subscribe` is a single-request verb on the existing handlerDispatcher offload path. No change to pool sizing rationale.

**R6 — Internal-topology asymmetry (the soft-G4 escape hatch).** Hydration uses per-subscriber channels + drop-oldest backpressure; sync reuses the shared broadcast `Channel<String>` + filtered fanout. The asymmetry is acceptable for the current load profile (one producer = `SyncEngine`, low fanout cardinality). It would become a problem in two scenarios:

- A subscriber that consistently can't keep up. Today's `writeNonBlocking` has a per-write 5s deadline (configurable via `UNIDRIVE_IPC_WRITE_TIMEOUT_MS`); a chronically slow subscriber will get dropped repeatedly rather than back up the shared channel. Other subscribers are unaffected because each broadcast-loop iteration acquires `entry.writeMutex` per-client, not a shared lock.
- A future high-fanout scenario (e.g., five simultaneous UI clients on a high-emit-rate `SyncEngine`). The per-subscriber-channel migration is straightforward: replace the broadcast-loop body with per-subscriber `Channel<String>` and writer coroutines, modelled on `HydrationIpcHandler`. File as a follow-up entry if/when the load profile demands it.

**R7 — Handler exceptions and the post-reply hook.** If a verb handler calls `scheduleAfterReply` and then throws, the scheduled action should NOT fire — the verb effectively failed. **Addressed by §3.2.3:** `dispatchRequest` tracks `handlerThrew`, and the post-reply hook only runs if `!handlerThrew`. The pending entry is still removed from the map (via `remove`) to prevent R8.

**R8 — Stale entries in `pendingPostReply` from prior failed handlers.** If handler A on connId X calls `scheduleAfterReply` and throws (action discarded per R7), then handler B later runs on the same connId X without scheduling anything — under a naive implementation, B would inherit A's stale pending action. **Addressed by §3.2.3:** `dispatchRequest` calls `pendingPostReply.remove(connId)` at the **start** of every request as a defensive clear, before parsing the verb or invoking the handler. This is a no-op on the happy path (the map entry was already removed at the end of the prior request's success path) and prevents R8 on the error path.

## 5. Acceptance

- All four tests in §3.5 pass:
  - `non_subscriber_receives_no_sync_progress_bytes`
  - `subscriber_receives_reply_first_then_state_dump_then_live_events`
  - `state_dump_handles_special_chars_in_path_via_escape_json`
  - `subscriber_set_is_cleaned_up_on_disconnect`
- All existing tests in `IpcServerTest.kt` and `IpcProgressReporterTest.kt` updated per §3.5 (sync.subscribe prepend or rename-and-rewrite for `late joiner receives state dump`); the updated suite passes.
- `IpcDispatcherIsolationTest.kt` and `IpcServerPermissionsTest.kt` unchanged and still pass (they don't rely on broadcast or state-dump behavior).
- Manual live smoke per §3.6 passes against the `posteo_onedrive` profile: `ls` succeeds, `less geheim.txt` prints, zero `Broken pipe`/`dropping dead` log lines during the session.
- BACKLOG entry "Phase 2 co-daemon disconnects mid-IPC: `ls` on FUSE mount returns EIO" moves to `CLOSED.md` in the same commit set that lands the fix.
- Follow-up BACKLOG entry filed at close-out: "`unidrive-ui` needs to call `sync.subscribe` after connecting (next deploy)."
