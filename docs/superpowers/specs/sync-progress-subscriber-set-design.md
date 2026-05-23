# Sync-Progress Subscriber Set — Design

**Status:** Proposed — design doc, not yet implemented
**Origin:** Phase 2 mount smoke-test finding, BACKLOG entry committed in `1de0cb3`, refined in `013da46`
**Touches:** `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`, `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt`, `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt`

## 1. Problem

The live Phase 2 mount smoke against `posteo_onedrive` (run with the structural IPC-transport fix from `309a5b3` loaded) produces `Input/output error (os error 5)` on `ls /tmp/onedrive-smoke/`, and the daemon log shows recurring `IPC: dropping dead client … Broken pipe` on `ipc-io-N` write threads at ~30s cadence. Zero `Write timeout exceeded` lines under the new build — the transport-dispatcher fix is structurally sound; this is a different failure further down the stack.

### 1.1 Root cause

`IpcServer`'s broadcast loop (`IpcServer.kt:206-227`) fans out every `emit(json)` call to **every** connected client, regardless of whether that client opted in to receive events:

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

`IpcProgressReporter.emit(...)` (`IpcProgressReporter.kt:23`) calls `server.emit(json)` for every sync-progress event — sync_started, scan_progress, reconcile_progress, action_count, action_progress, transfer_progress, sync_complete, sync_error, warning, poll_state. Under `--watch`, those emissions are continuous.

Separately, `IpcServer.start()`'s accept loop calls `flushStateDump(entry)` (`IpcServer.kt:257-308`) for **every** new connection — unconditionally writing the initial sync state's events to the fresh socket before the client has even sent a request.

The `unidrive-mount` co-daemon (Phase 2 FUSE binary, sibling repo) connects to issue verb requests only. Its `IpcClient` source defines a `subscribe()` method but **never calls it** (verified by `grep -rn "subscribe\|\.subscribe(" unidrive-mount-linux/mount/src/` — only the definition + two doc comments appear). The Rust code's comments document the intent: "The Phase-2 client does NOT consume that stream — Phase 3 work" (`ipc.rs:118-122`), and the reconnect wrapper deliberately omits subscribe (`reconnect.rs:192`).

But the JVM doesn't know that. The co-daemon connects → JVM `flushStateDump` writes state events to the socket → the SyncEngine's continuous `IpcProgressReporter.emit(...)` calls fan out to the co-daemon's socket → bytes pile in the kernel buffer because the co-daemon's reader is only active during the brief windows of a verb's `round_trip` → eventually one of three things happens:

- The co-daemon's `round_trip` reads a broadcast line as if it were the verb reply, gets `IpcError::Malformed` (the reconnect wrapper does NOT retry `Malformed` per `reconnect.rs:84-90` — only `Io`), surfaces EIO to FUSE.
- The socket buffer fills, JVM's next broadcast write returns `Broken pipe`, JVM drops the client.
- The co-daemon's reconnect wrapper kicks in on the next verb (whose write errors out because the JVM has already closed), the cycle starts over.

The 30s cadence in the log matches a periodic SyncEngine status emission interval.

### 1.2 Why this surfaced only now

Phase 1 had no FUSE client. Phase 2 added the co-daemon, which is the first request-reply-only client of the IPC surface. `unidrive status` (one-shot) and the older `unidrive-ui` (long-lived but presumed-subscriber semantics) both happen to either disconnect fast or actually consume what they receive — masking the protocol violation.

The hydration event surface (cache_added, hydration_failed, etc.) has **always** been subscriber-set-gated: `HydrationIpcHandler` (`HydrationIpcHandler.kt:210-213`) handles `hydration.subscribe` by inserting the connId into a private `subscribers` set, and the per-event fanout uses `IpcServer::writeToConnection` (`IpcServer.kt:116-131`) — targeted, per-connection writes that bypass the broadcast loop. So hydration events are correctly delivered only to opt-in clients.

The sync-progress event surface is the asymmetric leg: it never grew a subscriber set, kept using the all-clients broadcast loop. Two comments in the codebase already document this is the intended end-state: `IpcServer.kt:120` ("the connections that ran `hydration.subscribe`, instead of fanning out via the shared broadcast channel"), and `SyncCommand.kt:466` ("Fan hydration events out only to connections that ran hydration.subscribe"). The fix is making sync-progress symmetric with hydration.

### 1.3 What this design does NOT address

- The 4 MiB `read_line` cap on the co-daemon side (`mount/src/ipc.rs:191`). After this fix lands, sync-progress events stop hitting the co-daemon at all, so the cap can't be reached via this path. Whether the JVM should still cap outbound `hydration.list` replies on the request-reply path is a separate question that doesn't bite once the broadcast is fixed.
- The co-daemon's `IpcError::Malformed`-without-retry behavior. After this fix, the co-daemon won't see any Malformed lines from this source. The retry logic itself is fine for the case it's designed to handle (transient socket I/O).
- The `unidrive-ui` consumer behavior. The next UI deploy will need to call `sync.subscribe` once after connecting to keep receiving events. This is the one observable client-breaking change. See §4.

## 2. Goals & non-goals

**Goal G1:** A connected IPC client receives sync-progress events if and only if it has issued a `sync.subscribe` verb.

**Goal G2:** A connected IPC client receives the initial `flushStateDump` state-replay if and only if it has just issued `sync.subscribe` — not at accept time.

**Goal G3:** The co-daemon's FUSE mount can perform `ls` / `getattr` / `open` / `read` against `/tmp/onedrive-smoke/` under a busy SyncEngine without the `Broken pipe` cycle.

**Goal G4:** Symmetry with the existing `hydration.subscribe` surface. The two verbs have the same shape (handler returns `{"ok": true}`, connId added to subscriber set, close-listener removes it).

**Non-goal NG1:** Removing the broadcast loop, `Channel<String>`, or `emit(...)` method. They remain — they just filter their fanout to subscribers. The plumbing is sound; only the audience is wrong.

**Non-goal NG2:** Touching `HydrationIpcHandler`'s subscriber logic. That surface already works correctly. The fix mirrors its model, not replaces it.

**Non-goal NG3:** Adding a `sync.unsubscribe` verb. Disconnection IS the unsubscribe — close-listener handles it. Symmetric with hydration (which also has no explicit unsubscribe). Add only when a real use case appears.

**Non-goal NG4:** Backwards-compatibility shim for old clients that connect-and-listen without subscribing. Today's `unidrive-ui` is the suspected one; it'll need a one-line update on its next deploy. CLAUDE.md "Avoid backwards-compatibility hacks" — the right move is to update consumers, not to shim. The systray UI is the user's own code anyway.

## 3. Design

### 3.1 Wire contract

New verb `sync.subscribe`. Request: `{"verb":"sync.subscribe"}`. Reply: `{"ok":true}`. Side effect: server adds the issuing connection's `connId` to a `syncSubscribers` set. After the reply lands, the server pushes a one-time state dump (the events `flushStateDump` synthesises today), then delivers every subsequent `IpcProgressReporter.emit(...)` payload to this connection until it disconnects.

Symmetric with `hydration.subscribe` (`HydrationIpcHandler.kt:210-213`): same one-line handler shape, same connId-set semantics, same auto-cleanup on disconnect via a connection-close listener.

### 3.2 IpcServer changes

Add a private subscriber set:

```kotlin
private val syncSubscribers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
```

Add a public registration method (used by `SyncCommand` to wire the verb handler):

```kotlin
/**
 * Mark a connection as a sync-progress subscriber. After this call, the
 * connection receives sync-progress events from `emit(...)` until it
 * disconnects (cleanup is via the existing connection-close listener
 * registration). Symmetric with `HydrationIpcHandler.registerSubscriber`
 * but for the sync-progress surface; the two subscriber sets are
 * independent (a client may subscribe to one, both, or neither).
 */
fun registerSyncSubscriber(connectionId: String) {
    syncSubscribers.add(connectionId)
}
```

Register a connection-close listener internally to remove from the set:

```kotlin
init {
    closeListeners.add { connId -> syncSubscribers.remove(connId) }
}
```

(Or equivalent — the existing `closeListeners` machinery already exists; we just hook into it from inside `IpcServer` itself rather than from an external caller.)

Change the broadcast loop's iteration to filter on the subscriber set:

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

The log message changes from "dropping dead client" to "dropping dead sync-subscriber" — useful for log-grep to tell the two surfaces apart.

Remove the `flushStateDump(entry)` call from the accept loop:

```diff
- flushStateDump(entry)
  scope.launch(transport) {
      // … per-client reader loop …
  }
```

Add a new method `flushStateDumpTo(connectionId: String)` that does what `flushStateDump` does today, but targeted at a single connection by ID and called from the `sync.subscribe` handler (not at accept time):

```kotlin
/**
 * Replay the current SyncState as initial state-dump events to a single
 * subscriber connection. Called from the `sync.subscribe` handler so
 * that subscribers see context for the in-progress sync, but
 * non-subscribers (e.g. the FUSE co-daemon, request-reply clients)
 * never receive these unsolicited bytes on their socket.
 */
suspend fun flushStateDumpTo(connectionId: String) {
    val state = syncState ?: return
    val entry = clients.firstOrNull { it.id == connectionId } ?: return
    // … same body as today's flushStateDump but operating on `entry` ...
}
```

(The existing private `flushStateDump(entry: ClientEntry)` can be renamed or its body refactored. The simplest landing is to keep one method, rename it to `flushStateDumpTo`, and accept a `connectionId` instead of an entry — match the existing `writeToConnection` shape.)

### 3.3 SyncCommand changes

Register the new verb handler immediately after the existing hydration handler registration (`SyncCommand.kt:455-470` area):

```kotlin
ipcServer.registerHandler("sync.subscribe") { connId, _ ->
    ipcServer.registerSyncSubscriber(connId)
    ipcServer.flushStateDumpTo(connId)
    """{"ok":true}"""
}
```

Three-line addition; the `flushStateDumpTo` call is where the state-dump replay now happens.

### 3.4 IpcProgressReporter changes

**None.** `IpcProgressReporter.emit(...)` still calls `server.emit(json.toString())` (`IpcProgressReporter.kt:23`). The broadcast loop change in §3.2 silently filters the fanout. The Reporter doesn't know or care.

### 3.5 Testing

Four tests pin the invariants. All live in a new test class `IpcSyncSubscriberSetTest.kt` alongside `IpcDispatcherIsolationTest`.

**T1 — A non-subscribed connection receives no sync-progress bytes.** The contract test. Connect a client. Issue `emit("foo")` on the server. Wait 200ms (real wall-clock — the broadcast loop iterates the channel within tens of ms). Assert: the client's socket read returns no data (`read` returns 0 from a non-blocking socket, or `select` times out). The client never called `sync.subscribe`, so it must receive nothing. **This is the test that protects against regression of the exact Phase 2 bug.**

**T2 — A subscribed connection receives sync-progress events.** Connect a client, send `{"verb":"sync.subscribe"}`, read the `{"ok":true}` reply, then issue `emit("foo")` on the server. Assert the client reads `"foo"` (with the broadcast loop's newline-appended framing) within 1s.

**T3 — `flushStateDump` does not fire at accept time.** Pre-populate `server.updateState(SyncState(profile="p", phase="reconcile", scanCount=100))`. Connect a client. Wait 200ms. Assert the client's socket has zero bytes available. Then issue `sync.subscribe` and assert the state-dump lines DO arrive after the `{"ok":true}` reply.

**T4 — Subscriber cleanup on disconnect.** Connect a client, call `sync.subscribe`, confirm an emit reaches it, close the client. Server-side: assert `syncSubscribers` no longer contains the connId (via a test-only accessor or by observing that a follow-up emit does not produce a broadcast write — measurable via the same `dead` list mechanism). Pins that the close-listener actually removes from the subscriber set.

Test names:
- `non_subscriber_receives_no_sync_progress_bytes`
- `subscriber_receives_sync_progress_after_subscribe_reply`
- `state_dump_does_not_fire_until_sync_subscribe`
- `subscriber_set_is_cleaned_up_on_disconnect`

### 3.6 Live smoke test (manual, post-merge)

Same shape as the IPC-transport fix's §3.5 smoke (per the corrected plan in `91955b7`):

1. `cd /home/gernot/dev/git/unidrive/core && ./gradlew :app:cli:deploy -q`
2. Restart the `posteo_onedrive` sync watcher: kill the running `java … -p posteo_onedrive sync --watch` if any, then `unidrive -p posteo_onedrive sync --watch`.
3. `mkdir -p /tmp/onedrive-smoke && unidrive -p posteo_onedrive mount /tmp/onedrive-smoke`
4. `ls /tmp/onedrive-smoke/` — **must succeed** and list real cloud entries.
5. `less /tmp/onedrive-smoke/geheim.txt` — must print the file content.
6. Tail the daemon log: `tail -F ~/.local/share/unidrive/unidrive.log | grep -E "Broken pipe|dropping dead"`. Expected: zero new lines during the mount session.
7. Cleanup: `fusermount3 -u /tmp/onedrive-smoke`.

## 4. Risks and open questions

**R1 — `unidrive-ui` breaks silently.** The systray UI binary at `~/.local/lib/unidrive-ui.jar` is from Apr 2 09:58 — pre-subscriber-set. If it connects expecting events to flow without a `sync.subscribe` call, the UI will show stale state forever after this lands. Mitigation: trivial UI-side fix (one-line `sync.subscribe` after connect). Detection: when the user next runs `unidrive-ui`, the systray will show "no recent sync activity" even mid-sync — obvious enough to spot quickly. Out of scope for this design; file a follow-up entry to update `unidrive-ui`. The user owns both repos and can do that in one commit when convenient.

**R2 — Other broadcast-listening consumers.** `grep -rn "server\.emit\|ipcServer\.emit" core/` returns exactly one production caller: `IpcProgressReporter.kt:23`. Verified before writing this design. No other internal callers exist, so the surface is fully accounted for on the JVM side.

**R3 — Race: emit between subscribe and state dump.** If `SyncEngine` calls `emit(json)` between the moment a subscriber is registered and the moment `flushStateDumpTo` finishes, the subscriber could receive (a) the new event, (b) the state dump, in that order — out of chronological order. Mitigation: the state-dump events are explicitly tagged with the original timestamp on the `SyncState` they reflect; consumers that care about ordering already need to use the `timestamp` field. Not worth synchronising the subscribe-then-dump as an atomic unit — the JSON event stream is, by design, an at-least-once-in-eventual-order stream. Documented but not gated.

**R4 — Double-subscribe.** A client that calls `sync.subscribe` twice on the same connection adds the connId once (it's a `Set.add`) but receives the state dump twice. Harmless idempotency on the membership side; mild noise on the state-dump side. Could be fixed by gating `flushStateDumpTo` on "first subscribe wins" but that complicates the handler for a non-real-world scenario. Document and ignore.

**R5 — Pool-size tightening on the transport pool from `309a5b3`.** The transport pool is 4 threads. The broadcast loop is a single thread on that pool. Adding subscribe handler work doesn't change that — `sync.subscribe` is a single-request verb on the existing `dispatchRequest` path. No change to pool sizing rationale.

## 5. Acceptance

- All four tests in §3.5 pass.
- Existing IPC test suites unchanged and green (`IpcServerTest`, `IpcServerPermissionsTest`, `IpcProgressReporterTest`, `IpcDispatcherIsolationTest`).
- Manual live smoke in §3.6 passes against the `posteo_onedrive` profile — `ls` succeeds, `less geheim.txt` prints, zero `Broken pipe`/`dropping dead` log lines during the session.
- BACKLOG entry "Phase 2 co-daemon disconnects mid-IPC: `ls` on FUSE mount returns EIO" moves to `CLOSED.md` in the same commit that lands the fix.
- Follow-up BACKLOG entry filed for `unidrive-ui` to call `sync.subscribe` on its next deploy (per §R1).
