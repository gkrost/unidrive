# IPC Protocol — push-only UDS NDJSON

> Public contract for third-party tray/UI consumers of the unidrive
> daemon's progress stream. ADR-0013 deliberately keeps this surface
> open for community tools after the in-tree UI was cut.

Wire-format reference. Intent summary lives in
[SPECS.md §2.1](../SPECS.md#21-surface-a--daemon--consumer-push-only-event-stream);
topology in [ARCHITECTURE.md §IPC topology](../ARCHITECTURE.md#ipc-topology).

## At a glance

- **Transport:** Unix-domain socket (AF_UNIX, `SOCK_STREAM`).
- **Direction:** push-only, daemon-to-client broadcast. Not RPC.
- **Framing:** NDJSON, one JSON object per `\n`-terminated line, UTF-8.
- **Always present per frame:** `event`, `profile`, `timestamp` (ISO-8601 UTC).
- **State-dump on connect:** late joiners get a synthetic replay so
  they don't render "no data" mid-sync.

## Socket path

Computed by `IpcServer.defaultSocketPath` —
[`IpcServer.kt:264-304`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt#L264):

| OS | Directory | Filename |
|----|-----------|----------|
| Linux / macOS | `/run/user/$UID/` if present, else `0700` tempdir | `unidrive-<profile>.sock` |
| Windows | `%LOCALAPPDATA%\Temp\unidrive-ipc\` | `unidrive-<profile>.sock` |

If the basename would exceed 90 chars, it becomes
`unidrive-<sha1[0..4]>.sock` with a sibling `.meta` file containing
the profile name. Socket mode is `0600` on Linux.

## Lifecycle & limits

1. AF_UNIX `connect()`.
2. Read the **state-dump** prefix (zero or more lines, same shape as
   live frames, no sentinel separator).
3. Read live frames until close.

Limits — [`IpcServer.kt:233-235`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt#L233):

| Limit | Value | Effect when exceeded |
|-------|-------|----------------------|
| `MAX_CLIENTS` | 10 | New socket closed, WARN logged. |
| `WRITE_TIMEOUT_NS` | 5 s | Slow client dropped on next broadcast. |
| Broadcast channel | 256 slots | `trySend` — over-capacity emits dropped silently. |

## Frame schema

All emitters in
[`IpcProgressReporter.kt`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt);
replay path in [`IpcServer.kt:150-215`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt#L150).

```
{"event":"sync_started",     "profile":"...", "timestamp":"..."}
{"event":"scan_progress",    "profile":"...", "phase":"local",  "count":42}
{"event":"reconcile_progress","profile":"...","processed":120, "total":340}
{"event":"action_count",     "profile":"...", "total":1697}
{"event":"action_progress",  "profile":"...", "index":529,"total":1697,
                              "action":"upload","path":"/Photos/img.jpg"}
{"event":"transfer_progress","profile":"...", "path":"...",
                              "bytes_transferred":123456,"total_bytes":987654}
{"event":"sync_complete",    "profile":"...", "downloaded":10,"uploaded":3,
                              "conflicts":0,"duration_ms":4521}
{"event":"sync_error",       "profile":"...", "message":"..."}
{"event":"warning",          "profile":"...", "message":"..."}
{"event":"poll_state",       "profile":"...", "state":"IDLE",
                              "interval_s":300,"idle_cycles":12}
```

(`timestamp` omitted above for brevity; it's always present.)

Field semantics:

- **`sync_started`** — one per cycle, no extra fields.
- **`scan_progress.phase`** — implementation-defined string
  (`local`, `remote`, etc.). `count` is items seen so far.
- **`reconcile_progress`** — UD-240g heartbeat so clients can render
  a real progress bar mid-reconcile instead of "stuck on scan".
- **`action_count`** — fires once after reconcile, before any apply
  work. `total` is the planned `SyncAction` count.
- **`action_progress.action`** — free-form verb (`upload`, `download`,
  `move`, …). `path` is sync-root-relative.
- **`transfer_progress`** — per-file byte progress for large items.
- **`sync_complete`** — once per cycle. Per-action breakdowns
  (`actionCounts`, `failed`) reach the in-process
  `ProgressReporter` but are NOT serialized to this frame; see
  [`IpcProgressReporter.kt:112-136`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt#L112).
- **`sync_error` / `warning.message`** — newlines collapsed to
  spaces, truncated to 500 chars.
- **`poll_state.state`** — one of the `AdaptiveInterval` states
  (`ACTIVE`, `NORMAL`, `IDLE`). One emit per idle poll cycle.

## Late-joiner replay

Mid-sync clients see, synthesized from `SyncServer.syncState`, an
ordered prefix:

```
sync_started → scan_progress → action_count → action_progress
```

(each only if the corresponding field is non-null). Replay frames
carry the **current connect timestamp**, not the original emit time —
the daemon doesn't persist per-frame times. See
[`IpcServer.flushStateDump`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt#L150).

## Minimal Kotlin client

```kotlin
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path

val sock = Path.of("/run/user/${posix.getuid()}/unidrive-default.sock")
SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
    ch.connect(UnixDomainSocketAddress.of(sock))
    ch.socket().getInputStream().bufferedReader().forEachLine(::println)
}
```

Non-JVM clients: any language with AF_UNIX support works. The
canonical in-tree consumer is `unidrive log --watch`.

## See also

- [SPECS.md §2.1](../SPECS.md#21-surface-a--daemon--consumer-push-only-event-stream) — normative summary.
- [`IpcServer.kt`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt) — accept loop, broadcast, replay.
- [`IpcProgressReporter.kt`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt) — every emitter.
- ADR-0013 — UI removal context for keeping this surface stable.
