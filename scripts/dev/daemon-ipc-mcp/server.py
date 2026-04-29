"""server.py — MCP server exposing read-only observation into a running
unidrive sync daemon.

Wraps the daemon's existing AF_UNIX broadcast socket (on Linux) or its
Windows-side equivalent (TEMP/unidrive-ipc/unidrive-<profile>.sock — the
JVM uses AF_UNIX on both platforms per IpcServer.defaultSocketPath). The
broadcast stream is append-only: clients connect, receive a state dump,
then see live events as they land.

Tools exposed (see UD-725):

  sync_state(profile) -> { phase, current_file?, queue_depth,
                           bytes_transferred_session, recent_events: [...] }
      Connects to the profile's IPC socket, reads the state dump frame
      + any immediately-available events (up to 500 ms), returns a
      snapshot, disconnects. Safe to call at any frequency.

  sync_log_tail(profile, lines=50) -> [line]
      The running daemon does NOT currently expose an in-memory log
      buffer via IPC. Falls back to tailing the logback-configured
      unidrive.log. Scope-labelled "log" not "in-memory buffer" so the
      caller knows which.

sync_stop(drain=true) is deliberately NOT implemented: the daemon does
not yet observe a stop sentinel file, and the IPC protocol has no stop
command. UD-712 tracks the graceful-stop work; revisit this MCP when
that ships.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
import sys
from pathlib import Path
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

log = logging.getLogger("unidrive-daemon-ipc")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)

app = Server("unidrive-daemon-ipc")


# ---------- socket resolution ------------------------------------------------

MAX_SOCKET_PATH_LENGTH = 90


def _socket_dir() -> Path:
    """Mirror IpcServer.defaultSocketPath dir selection."""
    if os.name == "nt":
        tmp = Path(os.environ.get("TEMP") or os.environ.get("TMP") or "C:/temp")
        return tmp / "unidrive-ipc"
    # Linux / macOS: /run/user/$UID if present, else tempdir
    uid = os.getuid() if hasattr(os, "getuid") else 0
    run_dir = Path(f"/run/user/{uid}")
    if run_dir.is_dir():
        return run_dir
    # Fallback — the JVM creates a fresh tempdir each run; we can't know
    # its random suffix, so match the common case only.
    return Path("/tmp/unidrive-ipc")


def _hashed_socket_name(profile: str) -> str:
    import hashlib
    h = hashlib.sha1(profile.encode("utf-8")).digest()[:4].hex()
    return f"unidrive-{h}.sock"


def _resolve_socket(profile: str) -> Path:
    base = f"unidrive-{profile}.sock"
    parent = _socket_dir()
    candidate = parent / base
    if len(str(candidate)) > MAX_SOCKET_PATH_LENGTH or len(base) > MAX_SOCKET_PATH_LENGTH:
        candidate = parent / _hashed_socket_name(profile)
    return candidate


# ---------- log path (matches logback.xml) -----------------------------------


def _log_path() -> Path:
    env = os.environ.get("UNIDRIVE_LOG")
    if env:
        return Path(env)
    if os.name == "nt":
        return Path(os.environ["LOCALAPPDATA"]) / "unidrive" / "unidrive.log"
    return Path.home() / ".local" / "share" / "unidrive" / "unidrive.log"


# ---------- tool list --------------------------------------------------------


@app.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="sync_state",
            description=(
                "Read-only snapshot of the running unidrive sync daemon for "
                "the given profile. Connects to the IPC socket, reads the "
                "state dump + up to 500 ms of live events, returns structured "
                "JSON. Fails cleanly if the daemon is not running."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "profile": {"type": "string"},
                    "collect_ms": {
                        "type": "integer", "minimum": 50, "maximum": 5000,
                        "default": 500,
                        "description": "How long to collect live events after the state dump.",
                    },
                },
                "required": ["profile"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="sync_log_tail",
            description=(
                "Tail the last N lines of unidrive.log. Source is the "
                "logback-configured file, NOT an in-memory ring buffer "
                "(the daemon does not expose one today). Scope-labelled "
                "'log' so the caller knows which."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "profile": {
                        "type": "string",
                        "description": "Advisory — the log file is shared across profiles.",
                    },
                    "lines": {
                        "type": "integer", "minimum": 1, "maximum": 1000,
                        "default": 50,
                    },
                },
                "required": ["profile"],
                "additionalProperties": False,
            },
        ),
    ]


@app.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    try:
        if name == "sync_state":
            result = _sync_state(arguments)
        elif name == "sync_log_tail":
            result = _sync_log_tail(arguments)
        else:
            raise ValueError(f"unknown tool: {name}")
        return [TextContent(type="text", text=json.dumps(result, indent=2))]
    except Exception as e:
        log.exception("unexpected error in %s", name)
        return [TextContent(type="text",
                            text=json.dumps({"error": f"{type(e).__name__}: {e}"}))]


# ---------- sync_state --------------------------------------------------------


def _sync_state(args: dict[str, Any]) -> dict[str, Any]:
    profile = args["profile"]
    collect_ms = int(args.get("collect_ms", 500))
    sock_path = _resolve_socket(profile)

    if not sock_path.exists():
        return {
            "error": f"no IPC socket at {sock_path}",
            "hint": "is the daemon running for this profile?",
        }

    # AF_UNIX works on Windows 10+ via the JVM's ServerSocketChannel. Python
    # on Windows didn't support AF_UNIX until 3.9; guard just in case.
    if not hasattr(socket, "AF_UNIX"):
        return {"error": "Python build lacks AF_UNIX support"}

    events: list[dict[str, Any]] = []
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.connect(str(sock_path))
            s.settimeout(collect_ms / 1000.0)
            buf = b""
            import time
            deadline = time.monotonic() + (collect_ms / 1000.0)
            while time.monotonic() < deadline:
                try:
                    chunk = s.recv(8192)
                except socket.timeout:
                    break
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    if not line.strip():
                        continue
                    try:
                        events.append(json.loads(line.decode("utf-8")))
                    except ValueError:
                        events.append({"_raw": line.decode("utf-8", "replace")})
    except ConnectionRefusedError:
        return {"error": "daemon socket exists but nothing listening (stale?)"}
    except OSError as e:
        return {"error": f"connect failed: {e}"}

    # Derive a structured snapshot from the observed events.
    last_action = None
    last_path = None
    total = None
    index = None
    phase = None
    scan_count = None
    for e in events:
        ev = e.get("event")
        if ev == "action_progress":
            last_action = e.get("action")
            last_path = e.get("path")
            index = e.get("index")
            total = e.get("total")
        elif ev == "action_count":
            total = e.get("total")
        elif ev == "scan_progress":
            phase = e.get("phase")
            scan_count = e.get("count")

    return {
        "socket_path": str(sock_path),
        "events_collected": len(events),
        "phase": phase,
        "scan_count": scan_count,
        "action_index": index,
        "action_total": total,
        "last_action": last_action,
        "current_file": last_path,
        "recent_events": events[-20:],  # last 20 for context
    }


# ---------- sync_log_tail -----------------------------------------------------


def _sync_log_tail(args: dict[str, Any]) -> dict[str, Any]:
    lines = int(args.get("lines", 50))
    path = _log_path()
    if not path.exists():
        return {"error": f"log file not found at {path}",
                "source": "log", "lines": []}

    # Efficient tail without reading the whole file.
    try:
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            block = 64 * 1024
            data = b""
            while size > 0 and data.count(b"\n") <= lines + 1:
                read = min(block, size)
                size -= read
                f.seek(size)
                data = f.read(read) + data
        decoded = data.decode("utf-8", "replace")
    except OSError as e:
        return {"error": f"read failed: {e}", "source": "log", "lines": []}

    tail = decoded.splitlines()[-lines:]
    return {
        "source": "log",  # not "in-memory buffer"
        "log_path": str(path),
        "line_count": len(tail),
        "lines": tail,
    }


async def main() -> None:
    async with stdio_server() as (read, write):
        await app.run(read, write, app.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
