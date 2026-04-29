# unidrive-log-tail MCP

Push-model anomaly stream from the running unidrive daemon's
`unidrive.log`. Complement to the pull-model `unidrive-log-anomalies`
skill — the skill works when the agent remembers to invoke it; this MCP
emits events the moment they land in the log, closing the
"silence = success" failure mode where a sync quietly drifts into a
retry storm between agent checks.

Runs on the user's native machine (NOT inside the Claude Desktop MSIX
sandbox — the sandbox virtualises `%LOCALAPPDATA%` and would hide the
real log file). See memory `project_msix_sandbox`.

## Tools

- `current_state()` — byte-equivalent to `scripts/dev/log-watch.sh --summary`.
  Returns total lines, WARN/ERROR counts, `throttle_429`,
  `download_failed`, `fatal_5_5`, `jwt_malformed`, `tls_handshake`, plus
  top-10 `retryAfterSeconds` distribution.
- `watch(since_ts_ms?, filters?)` — collects anomaly events for a ~30 s
  window, then returns them. The MCP stdio protocol does not currently
  support true push-streaming across a single tool call, so this call
  **blocks for the window duration and the caller re-invokes it back-to-back**
  to emulate a stream. The watch cursor persists across invocations so no
  events are missed and none are re-emitted. Default filter:
  `WARN|ERROR|Exception|throttled|Download failed|handshake|JWT`. Pass
  `filters=[...]` (list of regex strings, OR-combined) to override.
- `baseline_update()` — snapshots current counts as the new reference.
  Persists to `~/AppData/Local/unidrive/unidrive-log-baseline.json`
  (Windows) / `~/.local/share/unidrive/unidrive-log-baseline.json`
  (Linux).
- `tail(lines)` — last N raw log lines, unfiltered. Escape hatch.

### Streaming limitation

The `mcp` Python SDK currently returns tool results as a `list[TextContent]`
once the handler finishes. There is no mid-call emission primitive.
`watch` therefore collects events inside a fixed window
(`WATCH_WINDOW_SEC`, default 30 s) and returns the batch as a single JSON
payload. The agent treats consecutive `watch` calls as a conceptual
stream. The cursor (file offset + inode + line number) is kept in
process memory between calls, so:

- Lines appended **during** a `watch` window land in that window's
  payload within the 2-second poll interval.
- Lines appended **between** the return of one `watch` and the start of
  the next are picked up on the next call.
- File rotation (inode change) or truncation (size < cursor) resets the
  cursor cleanly.

### Rate limiting

Log floods would otherwise blow up the agent's context. Policy: after 10
events in 5 seconds, `watch` emits one `{"category": "rollup", "count": N}`
and suppresses per-line events for 30 seconds. When the suppression
window ends, a second rollup flushes the suppressed count.

## Log path resolution

1. `$UNIDRIVE_LOG` environment variable (wins if set).
2. Windows: `%LOCALAPPDATA%\unidrive\unidrive.log`.
3. Linux: `$HOME/.local/share/unidrive/unidrive.log`.

Matches the logback config at
[`core/app/cli/src/main/resources/logback.xml`](../../../core/app/cli/src/main/resources/logback.xml).

## Setup

1. Create a venv and install dependencies:
   ```powershell
   cd scripts\dev\log-tail-mcp
   py -m venv .venv
   .\.venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

2. Sanity-check without the MCP harness:
   ```powershell
   python .\smoke.py
   ```
   Creates a temp log, runs `current_state`, appends lines, runs `watch`
   for a few seconds, verifies events are emitted. Exits `0` on success.

3. Register the MCP in your Claude Code config (add to
   `~/.claude/claude_desktop_config.json` or equivalent):
   ```json
   {
     "mcpServers": {
       "unidrive-log-tail": {
         "command": "C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\log-tail-mcp\\.venv\\Scripts\\python.exe",
         "args": ["C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\log-tail-mcp\\server.py"]
       }
     }
   }
   ```
   Restart Claude Code. Tools appear as `mcp__unidrive-log-tail__current_state`,
   `mcp__unidrive-log-tail__watch`, `mcp__unidrive-log-tail__baseline_update`,
   `mcp__unidrive-log-tail__tail`.

## LogAnomalyEvent shape

```json
{
  "ts": "2026-04-20T12:34:56.789Z",
  "level": "WARN",
  "category": "throttle_429",
  "line": "<full log line, truncated to 500 chars if longer>",
  "context_line_number": 8772,
  "since_baseline_count": 2168
}
```

Categories (simple substring match, first hit wins):

- `throttle_429` — `" 429 "`, `"Got 429"`
- `download_failed` — `"Download failed"`
- `fatal_5_5` — `"attempt 5/5"`
- `jwt_malformed` — `"Malformed JWT"`, `"JWT is not well formed"`
- `tls_handshake` — `"TLS handshake"`, `"terminated the handshake"`
- `other` — filter matched but none of the above
- `rollup` — synthetic rate-limit summary

## What this deliberately does NOT do

- **No `watchdog` / OS-level file-notify.** A 2-second `os.stat` +
  seek-and-read loop is 20 LOC and works identically on every platform
  we care about.
- **No full log parsing.** Categorisation is a 7-element enum. If the
  agent needs more detail (throttle duration, file path, etc.), it
  calls `tail(N)`.
- **No multi-profile correlation.** One log file per MCP instance.
  Multi-profile logs are a v2 concern.

## Related

- [`scripts/dev/log-watch.sh`](../log-watch.sh) — the on-demand bash
  counterpart. `current_state()` is byte-equivalent to
  `log-watch.sh --summary`.
- [`.claude/skills/unidrive-log-anomalies/SKILL.md`](../../../.claude/skills/unidrive-log-anomalies/SKILL.md) —
  pull-model skill this MCP complements, not replaces.
- UD-717 (this ticket), UD-716 (sibling backlog MCP).
