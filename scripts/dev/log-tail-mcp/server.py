"""server.py — MCP server that streams anomaly events from unidrive.log.

Push-model counterpart to the `unidrive-log-anomalies` skill (pull-model).
Runs on the user's native machine (NOT inside the Claude Desktop MSIX
sandbox — the sandbox virtualises %LOCALAPPDATA% and would hide the real
log). See memory project_msix_sandbox for the why.

Tools exposed:

  current_state()
      One-shot summary of total_lines / warn / error / throttle_429 /
      download_failed / fatal_5_5 / jwt_malformed / tls_handshake, plus
      top-10 retryAfterSeconds distribution. Byte-equivalent to
      `scripts/dev/log-watch.sh --summary`. Never blocks.

  watch(since_ts_ms=None, filters=None)
      Collect anomaly events for a short window (~30 s). Returns
      accumulated events as a single JSON payload. The MCP stdio
      protocol does not currently support push-streaming across a
      tool call; callers emulate a stream by re-invoking `watch` when
      the previous window ends.

      Implementation: 2-second poll of os.stat mtime/size → seek to
      last read offset → read new bytes → apply filter regex (default
      WARN|ERROR|Exception|throttled|Download failed|handshake|JWT) →
      classify category → emit event.

      Rate limit: after 10 events in 5 s, suppress per-line events for
      30 s and emit one `{"category": "rollup", "count": N}`.

  baseline_update()
      Snapshot current_state() and persist as the new reference to
      ~/AppData/Local/unidrive/unidrive-log-baseline.json (Windows) or
      ~/.local/share/unidrive/unidrive-log-baseline.json (Linux).
      Returns {prev_baseline, new_baseline, stored_at}.

  tail(lines)
      Last N raw log lines, unfiltered. Escape hatch when filters
      suppress something the agent needs to see.

Log path resolution:
  1. $UNIDRIVE_LOG
  2. Windows: %LOCALAPPDATA%\\unidrive\\unidrive.log
  3. Linux:   $HOME/.local/share/unidrive/unidrive.log
Matches core/app/cli/src/main/resources/logback.xml.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import sys
import time
from collections import deque
from pathlib import Path
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

log = logging.getLogger("unidrive-log-tail")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)

app = Server("unidrive-log-tail")

# Default anomaly filter — same regex as scripts/dev/log-watch.sh.
DEFAULT_FILTER = re.compile(r"WARN|ERROR|FATAL|Exception|throttled|Download failed|handshake|JWT")
NOISE_RE = re.compile(
    r"o\.k\.u\.onedrive\.GraphApiService - Delta: |IPC: dropping dead client|IPC: client connected"
)

# Classification substrings; first match wins, else "other".
CATEGORY_TESTS: list[tuple[str, str]] = [
    ("throttle_429", " 429 "),
    ("throttle_429", "Got 429"),
    ("download_failed", "Download failed"),
    ("fatal_5_5", "attempt 5/5"),
    ("jwt_malformed", "Malformed JWT"),
    ("jwt_malformed", "JWT is not well formed"),
    ("tls_handshake", "TLS handshake"),
    ("tls_handshake", "terminated the handshake"),
]

# Rate-limit tuning.
RL_WINDOW_SEC = 5.0
RL_THRESHOLD = 10
RL_SUPPRESS_SEC = 30.0

# Poll cadence and watch window.
POLL_INTERVAL_SEC = 2.0
WATCH_WINDOW_SEC = 30.0
MAX_LINE_CHARS = 500

# Cursor shared across watch() invocations for resume semantics.
_cursor_offset: int | None = None
_cursor_inode: int | None = None
_cursor_line_number: int = 0


def _log_path() -> Path:
    env = os.environ.get("UNIDRIVE_LOG")
    if env:
        return Path(env)
    if sys.platform.startswith("win"):
        local = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        return Path(local) / "unidrive" / "unidrive.log"
    return Path.home() / ".local" / "share" / "unidrive" / "unidrive.log"


def _baseline_path() -> Path:
    if sys.platform.startswith("win"):
        local = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        return Path(local) / "unidrive" / "unidrive-log-baseline.json"
    return Path.home() / ".local" / "share" / "unidrive" / "unidrive-log-baseline.json"


def _classify(line: str) -> str:
    for cat, needle in CATEGORY_TESTS:
        if needle in line:
            return cat
    return "other"


def _level(line: str) -> str:
    if " ERROR " in line or " FATAL " in line:
        return "ERROR"
    if " WARN " in line:
        return "WARN"
    return "INFO"


def _extract_ts(line: str) -> str:
    # logback pattern starts with ISO-ish timestamp "YYYY-MM-DD HH:MM:SS,SSS"
    m = re.match(r"(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})[,.](\d{3})", line)
    if m:
        return f"{m.group(1)}T{m.group(2)}.{m.group(3)}Z"
    return time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())


def _file_inode(path: Path) -> int | None:
    try:
        st = path.stat()
        # On Windows, st_ino is populated by CPython via GetFileInformationByHandle; good enough for truncation detection.
        return st.st_ino
    except OSError:
        return None


def _current_state() -> dict[str, Any]:
    path = _log_path()
    if not path.exists():
        return {
            "log_path": str(path),
            "exists": False,
            "total_lines": 0,
            "warn": 0,
            "error": 0,
            "throttle_429": 0,
            "download_failed": 0,
            "fatal_5_5": 0,
            "jwt_malformed": 0,
            "tls_handshake": 0,
            "retry_distribution": [],
        }
    total = 0
    warn = 0
    err = 0
    throttle = 0
    dlfail = 0
    fatal55 = 0
    jwt = 0
    tls = 0
    retry_counts: dict[str, int] = {}
    retry_re = re.compile(r'retryAfterSeconds":(\d+)')
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            total += 1
            if " WARN " in line:
                warn += 1
            if " ERROR " in line:
                err += 1
            if "Got 429" in line:
                throttle += 1
            if "Download failed" in line:
                dlfail += 1
            if "attempt 5/5" in line:
                fatal55 += 1
            if "JWT is not well formed" in line:
                jwt += 1
            if "terminated the handshake" in line:
                tls += 1
            m = retry_re.search(line)
            if m:
                k = m.group(1)
                retry_counts[k] = retry_counts.get(k, 0) + 1
    top = sorted(retry_counts.items(), key=lambda kv: (-kv[1], kv[0]))[:10]
    return {
        "log_path": str(path),
        "exists": True,
        "total_lines": total,
        "warn": warn,
        "error": err,
        "throttle_429": throttle,
        "download_failed": dlfail,
        "fatal_5_5": fatal55,
        "jwt_malformed": jwt,
        "tls_handshake": tls,
        "retry_distribution": [{"retry_after_seconds": int(k), "count": v} for k, v in top],
    }


def _tail(n: int) -> list[str]:
    path = _log_path()
    if not path.exists():
        return []
    # Simple: read entire file and slice. For 10+ MB logs this is fine on
    # modern disks and avoids the seek-and-scan edge cases.
    with path.open("r", encoding="utf-8", errors="replace") as f:
        lines = f.readlines()
    return [ln.rstrip("\n") for ln in lines[-n:]]


def _baseline_update() -> dict[str, Any]:
    state = _current_state()
    bpath = _baseline_path()
    bpath.parent.mkdir(parents=True, exist_ok=True)
    prev: dict[str, Any] | None = None
    if bpath.exists():
        try:
            prev = json.loads(bpath.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            prev = None
    stored_at = int(time.time() * 1000)
    payload = {"stored_at_ms": stored_at, "state": state}
    bpath.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return {
        "prev_baseline": prev,
        "new_baseline": payload,
        "stored_at_ms": stored_at,
        "path": str(bpath),
    }


def _read_baseline_counts() -> dict[str, int]:
    bpath = _baseline_path()
    if not bpath.exists():
        return {}
    try:
        data = json.loads(bpath.read_text(encoding="utf-8"))
        st = data.get("state", {})
        return {
            "total_lines": int(st.get("total_lines", 0)),
            "warn": int(st.get("warn", 0)),
            "error": int(st.get("error", 0)),
        }
    except (json.JSONDecodeError, OSError, ValueError):
        return {}


def _init_cursor_if_needed(path: Path) -> None:
    global _cursor_offset, _cursor_inode, _cursor_line_number
    if _cursor_offset is not None and _cursor_inode == _file_inode(path):
        return
    # Fresh or rotated: start from current EOF and count existing lines so
    # context_line_number stays meaningful.
    if path.exists():
        with path.open("rb") as f:
            f.seek(0, os.SEEK_END)
            _cursor_offset = f.tell()
        # Count existing lines for context_line_number.
        n = 0
        with path.open("r", encoding="utf-8", errors="replace") as f:
            for _ in f:
                n += 1
        _cursor_line_number = n
        _cursor_inode = _file_inode(path)
    else:
        _cursor_offset = 0
        _cursor_line_number = 0
        _cursor_inode = None


def _read_new_lines(path: Path) -> list[str]:
    """Read lines appended since last poll. Handles truncation/rotation."""
    global _cursor_offset, _cursor_inode, _cursor_line_number
    if not path.exists():
        return []
    inode = _file_inode(path)
    try:
        size = path.stat().st_size
    except OSError:
        return []
    if _cursor_inode is not None and inode != _cursor_inode:
        # Rotation / replacement — reset to start of new file.
        _cursor_offset = 0
        _cursor_inode = inode
        _cursor_line_number = 0
    if _cursor_offset is None:
        _cursor_offset = 0
    if size < _cursor_offset:
        # Truncation.
        _cursor_offset = 0
    if size == _cursor_offset:
        return []
    with path.open("rb") as f:
        f.seek(_cursor_offset)
        data = f.read(size - _cursor_offset)
        _cursor_offset = f.tell()
    _cursor_inode = inode
    text = data.decode("utf-8", errors="replace")
    # Keep trailing partial line for next read? Simple approach: if data
    # doesn't end in newline, rewind the cursor by the partial length so we
    # re-read it next time once the logger flushes the rest.
    if not text.endswith("\n"):
        last_nl = text.rfind("\n")
        if last_nl == -1:
            # No complete line yet.
            _cursor_offset -= len(data)
            return []
        remainder_bytes = len(data) - (last_nl + 1)
        _cursor_offset -= remainder_bytes
        text = text[: last_nl + 1]
    lines = text.splitlines()
    return lines


def _make_event(line: str, line_number: int, baseline_count: int) -> dict[str, Any]:
    truncated = line if len(line) <= MAX_LINE_CHARS else line[:MAX_LINE_CHARS] + "…"
    return {
        "ts": _extract_ts(line),
        "level": _level(line),
        "category": _classify(line),
        "line": truncated,
        "context_line_number": line_number,
        "since_baseline_count": baseline_count,
    }


async def _watch(args: dict[str, Any]) -> dict[str, Any]:
    """Collect anomaly events for one window; caller re-invokes to stream."""
    filters_list = args.get("filters")
    if filters_list:
        filter_re = re.compile("|".join(filters_list))
    else:
        filter_re = DEFAULT_FILTER

    window_sec = float(args.get("_window_sec", WATCH_WINDOW_SEC))
    deadline = time.monotonic() + window_sec

    path = _log_path()
    _init_cursor_if_needed(path)

    baseline = _read_baseline_counts()
    baseline_total = int(baseline.get("total_lines", 0))

    events: list[dict[str, Any]] = []
    recent_ts: deque[float] = deque()
    suppressed_until: float = 0.0
    suppressed_count = 0
    global _cursor_line_number

    while time.monotonic() < deadline:
        new_lines = _read_new_lines(path)
        for raw in new_lines:
            _cursor_line_number += 1
            if NOISE_RE.search(raw):
                continue
            if not filter_re.search(raw):
                continue
            now = time.monotonic()
            if now < suppressed_until:
                suppressed_count += 1
                continue
            if suppressed_count > 0:
                # Just exited suppression — flush a rollup summary.
                events.append({
                    "category": "rollup",
                    "count": suppressed_count,
                    "ts": _extract_ts(raw),
                })
                suppressed_count = 0
            # Update rolling window.
            recent_ts.append(now)
            while recent_ts and recent_ts[0] < now - RL_WINDOW_SEC:
                recent_ts.popleft()
            if len(recent_ts) > RL_THRESHOLD:
                # Enter suppression.
                suppressed_until = now + RL_SUPPRESS_SEC
                events.append({
                    "category": "rollup",
                    "count": len(recent_ts),
                    "ts": _extract_ts(raw),
                    "note": f"rate-limited; suppressing individual events for {int(RL_SUPPRESS_SEC)}s",
                })
                recent_ts.clear()
                suppressed_count = 0
                continue
            since_baseline = max(0, (_cursor_line_number - baseline_total))
            events.append(_make_event(raw, _cursor_line_number, since_baseline))
        await asyncio.sleep(POLL_INTERVAL_SEC)

    if suppressed_count > 0:
        events.append({
            "category": "rollup",
            "count": suppressed_count,
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime()),
        })

    return {
        "window_sec": window_sec,
        "events": events,
        "cursor_line_number": _cursor_line_number,
        "cursor_offset": _cursor_offset,
    }


@app.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="current_state",
            description=(
                "One-shot summary of the unidrive log (byte-equivalent to "
                "`scripts/dev/log-watch.sh --summary`): total lines, WARN/ERROR "
                "counts, throttle_429, download_failed, fatal_5_5, jwt_malformed, "
                "tls_handshake, and the top-10 retryAfterSeconds distribution. "
                "Never blocks; safe to call any time."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        ),
        Tool(
            name="watch",
            description=(
                "Collect anomaly events from the tail of the unidrive log for a "
                "short window (~30 s), then return them. The MCP stdio protocol "
                "does not support true push-streaming across a single call, so "
                "the caller emulates a stream by re-invoking `watch` back-to-back. "
                "Default filter regex: WARN|ERROR|Exception|throttled|Download "
                "failed|handshake|JWT. Pass `filters` (list of regex strings, "
                "OR-combined) to override. Rate limit: >10 events in 5 s triggers "
                "a rollup and 30 s suppression."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "since_ts_ms": {
                        "type": "integer",
                        "description": "Advisory — the watch cursor persists across calls so this is usually ignored.",
                    },
                    "filters": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Optional regex list; overrides the default anomaly filter.",
                    },
                    "_window_sec": {
                        "type": "number",
                        "description": "Test-only: override the collection window length (default 30s).",
                    },
                },
                "additionalProperties": False,
            },
        ),
        Tool(
            name="baseline_update",
            description=(
                "Snapshot current_state() as the new baseline. Persists to "
                "~/AppData/Local/unidrive/unidrive-log-baseline.json on Windows "
                "or ~/.local/share/unidrive/unidrive-log-baseline.json on Linux. "
                "Returns {prev_baseline, new_baseline, stored_at_ms}."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        ),
        Tool(
            name="tail",
            description=(
                "Return the last N raw log lines, unfiltered. Escape hatch when "
                "the anomaly filter suppresses something the agent needs to see."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "lines": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 5000,
                        "default": 50,
                    },
                },
                "additionalProperties": False,
            },
        ),
    ]


@app.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    try:
        if name == "current_state":
            result: Any = _current_state()
        elif name == "watch":
            result = await _watch(arguments)
        elif name == "baseline_update":
            result = _baseline_update()
        elif name == "tail":
            n = int(arguments.get("lines", 50))
            result = {"lines": _tail(n)}
        else:
            raise ValueError(f"unknown tool: {name}")
        return [TextContent(type="text", text=json.dumps(result, indent=2))]
    except FileNotFoundError as e:
        return [TextContent(type="text", text=json.dumps({"error": str(e)}))]
    except Exception as e:
        log.exception("unexpected error in %s", name)
        return [TextContent(type="text", text=json.dumps({"error": f"{type(e).__name__}: {e}"}))]


async def main() -> None:
    async with stdio_server() as (read, write):
        await app.run(read, write, app.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
