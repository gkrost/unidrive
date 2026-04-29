# unidrive-daemon-ipc MCP

Read-only observation into a running unidrive sync daemon. Wraps the
AF_UNIX broadcast socket exposed by
[`IpcServer`](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt)
so an agent can snapshot a running sync without writing raw socket code.

**Ticket:** [UD-725](../../../docs/backlog/CLOSED.md).

## What you get

- `sync_state(profile, collect_ms=500)` — connect, receive the state
  dump + any events that land within `collect_ms`, disconnect, return a
  structured snapshot (`phase`, `current_file`, `action_index`,
  `action_total`, `recent_events[]`).
- `sync_log_tail(profile, lines=50)` — tail of `unidrive.log`. Source
  is the **on-disk log file**, not an in-memory buffer (the daemon
  doesn't expose one today). The response includes `"source": "log"` so
  the caller knows which.

## What is NOT here

- `sync_stop(drain=true)` — deliberately unimplemented. The daemon does
  not yet observe a stop-sentinel file and the existing IPC protocols
  (the AF_UNIX broadcast + the Win32 named pipe for CFAPI commands)
  have no `stop` verb. Revisit once [UD-712](../../../docs/backlog/BACKLOG.md)
  ships a graceful-stop path.
- Any write operation. Starting a sync, toggling flags, forcing a pass —
  use the CLI. This MCP is observation-first by design.

## Setup (one-time)

1. Install dependencies:
   ```powershell
   cd scripts\dev\daemon-ipc-mcp
   py -m venv .venv
   .\.venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

2. Register with Claude Code. Add to
   `~/.claude/claude_desktop_config.json`:
   ```json
   {
     "mcpServers": {
       "unidrive-daemon-ipc": {
         "command": "C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\daemon-ipc-mcp\\.venv\\Scripts\\python.exe",
         "args": ["C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\daemon-ipc-mcp\\server.py"]
       }
     }
   }
   ```

## Socket path resolution

Mirrors `IpcServer.defaultSocketPath`:

- Windows: `%TEMP%\unidrive-ipc\unidrive-<profile>.sock`
- Linux (uid 1000): `/run/user/1000/unidrive-<profile>.sock`
- Fallback: `/tmp/unidrive-ipc/unidrive-<profile>.sock` (only the common
  case; the JVM uses a random `mkdtemp` when `/run/user/$UID` is absent,
  which this MCP cannot discover).

Profile names longer than the macOS/Linux `sun_path` limit are hashed
the same way the JVM does (SHA-1 first 4 bytes, hex-encoded).

## Usage from inside an agent session

```
sync_state(profile="onedrive-test")
→ { "socket_path": "C:\\Users\\...\\Temp\\unidrive-ipc\\unidrive-onedrive-test.sock",
    "events_collected": 7,
    "phase": "download",
    "action_index": 21033,
    "action_total": 66855,
    "current_file": "/Pictures/2024/IMG_4242.heic",
    "recent_events": [ ... ] }

sync_log_tail(profile="onedrive-test", lines=20)
→ { "source": "log",
    "log_path": "C:\\Users\\...\\LocalAppData\\unidrive\\unidrive.log",
    "line_count": 20,
    "lines": [ "...", "...", ... ] }
```

## Known limitations

- **AF_UNIX on Windows.** Requires Windows 10 build 17063+ and Python
  3.9+. The JVM side uses this unconditionally; matching it in Python
  is the same constraint.
- **No live streaming.** MCP's `call_tool` returns after the handler
  completes. `sync_state` blocks for `collect_ms` (default 500 ms) and
  returns a batch. For continuous monitoring, the caller re-invokes.
- **Log tail is shared across profiles.** The logback config writes one
  file per host; the `profile` parameter is advisory.
