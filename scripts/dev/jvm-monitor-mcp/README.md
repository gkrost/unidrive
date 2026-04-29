# unidrive-jvm-monitor MCP

Surfaces running-JVM state so an agent can answer **"is it safe to
deploy this jar right now?"** with structured data instead of scraping
`tasklist` + `fsutil` + eyeballing commandlines.

Runs in the user's native environment (outside the Claude Desktop MSIX
sandbox — JVM visibility is hidden from the sandbox).

**Context:** [UD-719](../../../docs/backlog/BACKLOG.md), and the
`feedback_jar_hotswap` memory entry explaining why overwriting a jar on
a running JVM corrupts the Windows classloader.

## Tools

- **`list_unidrive_processes()`** — enumerate every process whose
  commandline mentions `unidrive`. Each row includes `pid`, `image`,
  `mem_mb`, `start_time_iso`, full `commandline`, a best-effort
  `jar_path`, and three classifier booleans `is_sync` / `is_ui` /
  `is_mcp`.
- **`jvm_state(pid, deployed_jar_path?)`** — for a specific JVM,
  return uptime, the loaded jar's mtime, and — on Windows — NTFS file
  IDs for the loaded and currently-deployed jars. Sets
  `needs_restart=true` iff the two IDs differ (the user redeployed
  since this JVM started, so it is running stale code). The default
  deployed path is `%LOCALAPPDATA%\unidrive\<classifier>.jar` based
  on the pid's classification; override via `deployed_jar_path`.
- **`safe_to_deploy(target_jar_path)`** — return `safe=false` plus a
  concrete `taskkill /PID <pid> /F` recommendation for every JVM
  holding the target. **Read-only** — never kills anything. Actually
  killing is explicitly out of scope per the ticket non-goals.

## How the Windows jar-locking check works

- NTFS file IDs come from `fsutil file queryFileID`, the same tool
  [`scripts/dev/msix-id.sh`](../msix-id.sh) uses to reconcile MSIX
  sandbox virtualisation. Two jars with the same NTFS ID **are** the
  same physical file; different IDs mean the on-disk copy has been
  replaced since the running JVM mapped its copy.
- On Linux/macOS the file-ID check is skipped (there is no `fsutil`)
  and the affected fields return `null`. The feature is meant to
  catch Windows jar-locking; POSIX hot-swap does not have the same
  classloader failure mode.

## Setup (one-time, on the user's machine)

1. Install dependencies into a dedicated venv:
   ```powershell
   cd scripts\dev\jvm-monitor-mcp
   py -m venv .venv
   .\.venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

2. Sanity-check the wiring without needing a running unidrive daemon:
   ```powershell
   python .\smoke.py
   ```
   Expected output ends with `OK`. The check verifies `psutil` imports,
   the `server` module imports, and each of the three tool functions
   returns the documented shape.

3. Register the MCP in your Claude Code config. Add to
   `~/.claude/claude_desktop_config.json` (or the equivalent Claude Code
   settings file):
   ```json
   {
     "mcpServers": {
       "unidrive-jvm-monitor": {
         "command": "C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\jvm-monitor-mcp\\.venv\\Scripts\\python.exe",
         "args": ["C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\jvm-monitor-mcp\\server.py"]
       }
     }
   }
   ```
   Restart Claude Code. The tools appear as
   `mcp__unidrive-jvm-monitor__list_unidrive_processes`,
   `mcp__unidrive-jvm-monitor__jvm_state`, and
   `mcp__unidrive-jvm-monitor__safe_to_deploy`.

## Usage from inside an agent session

```
list_unidrive_processes()
→ [ { "pid": 3240,
      "image": "java.exe",
      "mem_mb": 412.5,
      "start_time_iso": "2026-04-20T08:12:04+00:00",
      "commandline": ["java.exe", "-jar",
                      "<deploy-target>/unidrive.jar",
                      "sync"],
      "jar_path": "C:\\...\\unidrive.jar",
      "is_sync": true, "is_ui": false, "is_mcp": false } ]

jvm_state(pid=3240)
→ { "pid": 3240, "uptime_s": 7421.3,
    "loaded_jar_path": "C:\\...\\unidrive.jar",
    "loaded_jar_mtime": "2026-04-19T22:14:11+00:00",
    "loaded_jar_file_id": "0x0000000000000000000a000000001abc",
    "deployed_jar_path": "<deploy-target>/unidrive.jar",
    "deployed_jar_file_id": "0x0000000000000000000a000000002def",
    "needs_restart": true, "notes": [] }

safe_to_deploy("C:\\...\\unidrive.jar")
→ { "safe": false,
    "holding_pids": [3240],
    "recommendations": [
      "taskkill /PID 3240 /F  # java.exe — holding C:\\...\\unidrive.jar"
    ] }
```

Typical deploy flow now collapses to two MCP calls:

1. `safe_to_deploy(<target>)` — if `safe: false`, run the suggested
   `taskkill` commands manually.
2. Copy the new jar into place.
3. (Optional) Restart the JVM; call `jvm_state` afterwards to confirm
   `needs_restart=false`.

## What this deliberately does NOT do

- **No killing.** `safe_to_deploy` emits `taskkill` commands as
  strings; the caller runs them. Actually killing JVMs from inside the
  MCP is a ticket non-goal (too environment-specific: service vs
  user-session vs systemd).
- **No starting JVMs.** Same reasoning.
- **No POSIX parity.** Linux/macOS don't have the jar-locking
  classloader issue that motivated this tool. File-ID fields return
  `null` there, with a note in `recommendations`.
- **No handle-table inspection.** The "which jar is loaded?" heuristic
  parses the JVM's commandline (`-jar <path>` or a `unidrive*.jar`
  token on the classpath). unidrive is a one-jar-per-JVM shape, so
  this is good enough. If a future multi-jar deployment breaks the
  heuristic, fall back to `handle.exe` (Sysinternals).

## Known limitations / open work

- **`AccessDenied` for elevated JVMs.** psutil cannot read the
  commandline of a higher-integrity process. The row is silently
  skipped. Run the MCP as the same user that owns the JVM.
- **Classifier heuristics are substring-based.** `is_sync` /
  `is_ui` / `is_mcp` look for `sync` / `unidrive-ui` / `mcp` in the
  commandline. A pathological commandline could confuse them; revisit
  if a deployment ever needs finer classification.
- **MSIX sandbox.** The file IDs returned by this MCP are the user's
  native-side IDs. If the agent process tree is sandboxed under
  `wcifs.sys`, its view of the same path may have a different ID. Use
  [`scripts/dev/msix-id.sh`](../msix-id.sh) from inside the sandbox to
  reconcile.

See also: [`docs/dev/TOOLS.md`](../../../docs/dev/TOOLS.md),
`~/.claude/projects/.../memory/feedback_jar_hotswap.md`,
`~/.claude/projects/.../memory/project_msix_sandbox.md`.
