"""server.py — MCP server exposing running-JVM state so an agent can make
safe deploy decisions without scraping `tasklist` + `fsutil` + eyeballing
commandlines.

Runs on the user's side (NOT inside the Claude Desktop MSIX sandbox —
JVM process visibility is hidden from the sandbox). The user registers
it in their Claude Code MCP config (see README.md).

Tools exposed:

  list_unidrive_processes()
      Enumerate processes whose commandline mentions `unidrive`. Returns
      structured rows with pid / image / mem_mb / start_time_iso /
      commandline / jar_path / is_sync / is_ui / is_mcp classifiers.
      Read-only.

  jvm_state(pid, deployed_jar_path?)
      Given a pid, resolve its loaded jar via the list_unidrive_processes
      heuristic, stat its mtime, and fetch NTFS file IDs via
      `fsutil file queryFileID` for both the loaded path and the
      canonically-deployed path at
      %LOCALAPPDATA%\\unidrive\\<classifier>.jar (override with
      `deployed_jar_path`). Flips `needs_restart=True` iff the two
      file IDs differ (user has redeployed since the JVM started).

  safe_to_deploy(target_jar_path)
      For each unidrive process, compare its loaded-jar file ID against
      the target. Any match means the JVM is holding the file and
      overwriting it on Windows corrupts the running classloader
      (see memory `feedback_jar_hotswap`). Returns `safe=False` plus a
      concrete `taskkill /PID <pid> /F` recommendation list. Read-only —
      never kills anything itself. Per ticket non-goals, actual killing
      is handled by the caller.

POSIX note: on Linux/macOS the file-ID check is skipped (fsutil is a
Windows-only tool) and the tool returns `None` for the ID fields plus
a note in `recommendations`. The feature is designed around Windows
jar-locking; POSIX hot-swap doesn't have the same classloader failure
mode.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import platform
import re
import shlex
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psutil

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

log = logging.getLogger("unidrive-jvm-monitor")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)

app = Server("unidrive-jvm-monitor")

IS_WINDOWS = platform.system() == "Windows"


@app.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="list_unidrive_processes",
            description=(
                "Enumerate all running processes whose commandline contains "
                "'unidrive'. Classifies each as sync / ui / mcp and extracts "
                "the loaded jar path when determinable. Read-only."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        ),
        Tool(
            name="jvm_state",
            description=(
                "Report running state for a specific unidrive JVM: uptime, "
                "loaded jar mtime, and — on Windows — NTFS file IDs for the "
                "loaded and currently-deployed jar. `needs_restart=True` iff "
                "the two IDs differ (user has redeployed since the JVM "
                "started). On POSIX, file-ID fields are None."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "pid": {
                        "type": "integer",
                        "description": "Process ID of the unidrive JVM.",
                    },
                    "deployed_jar_path": {
                        "type": "string",
                        "description": (
                            "Override path to the 'currently deployed' jar. "
                            "Defaults to %LOCALAPPDATA%\\unidrive\\<classifier>.jar "
                            "based on the pid's classification."
                        ),
                    },
                },
                "required": ["pid"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="safe_to_deploy",
            description=(
                "Check whether it is safe to overwrite a jar at the given "
                "path. Returns safe=False plus concrete taskkill commands "
                "for every JVM currently holding the target. Read-only — "
                "does NOT kill any processes itself."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "target_jar_path": {
                        "type": "string",
                        "description": "Absolute path to the jar about to be deployed.",
                    },
                },
                "required": ["target_jar_path"],
                "additionalProperties": False,
            },
        ),
    ]


@app.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    try:
        if name == "list_unidrive_processes":
            result: Any = list_unidrive_processes()
        elif name == "jvm_state":
            result = jvm_state(
                pid=int(arguments["pid"]),
                deployed_jar_path=arguments.get("deployed_jar_path"),
            )
        elif name == "safe_to_deploy":
            result = safe_to_deploy(target_jar_path=arguments["target_jar_path"])
        else:
            raise ValueError(f"unknown tool: {name}")
        return [TextContent(type="text", text=json.dumps(result, indent=2))]
    except Exception as e:
        log.exception("unexpected error in %s", name)
        return [
            TextContent(
                type="text",
                text=json.dumps({"error": f"{type(e).__name__}: {e}"}),
            )
        ]


# --------------------------------------------------------------------------- #
# Core tool implementations — also importable by smoke.py
# --------------------------------------------------------------------------- #


def list_unidrive_processes() -> list[dict[str, Any]]:
    """Enumerate processes whose commandline mentions 'unidrive'."""
    rows: list[dict[str, Any]] = []
    for proc in psutil.process_iter(
        ["pid", "name", "cmdline", "create_time", "memory_info"]
    ):
        try:
            info = proc.info
            cmdline = info.get("cmdline") or []
            if not cmdline:
                continue
            joined = " ".join(cmdline)
            if "unidrive" not in joined.lower():
                continue
            mem = info.get("memory_info")
            mem_mb = round(mem.rss / (1024 * 1024), 1) if mem else None
            ct = info.get("create_time") or 0.0
            start_iso = (
                datetime.fromtimestamp(ct, tz=timezone.utc).isoformat()
                if ct
                else None
            )
            row = {
                "pid": info["pid"],
                "image": info.get("name"),
                "mem_mb": mem_mb,
                "start_time_iso": start_iso,
                "commandline": cmdline,
                "jar_path": _extract_jar_path(cmdline),
                "is_sync": _is_sync(joined),
                "is_ui": _is_ui(joined),
                "is_mcp": _is_mcp(joined),
            }
            rows.append(row)
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            continue
    return rows


def jvm_state(
    pid: int, deployed_jar_path: str | None = None
) -> dict[str, Any]:
    """Return running-state + stale-jar assessment for a specific pid."""
    try:
        proc = psutil.Process(pid)
    except psutil.NoSuchProcess:
        return {"error": f"no such process: {pid}"}
    except psutil.AccessDenied:
        return {"error": f"access denied for pid {pid}"}

    cmdline = proc.cmdline() or []
    loaded_jar = _extract_jar_path(cmdline)
    uptime_s = max(0.0, time.time() - proc.create_time())
    notes: list[str] = []

    loaded_mtime_iso: str | None = None
    if loaded_jar and os.path.exists(loaded_jar):
        st = os.stat(loaded_jar)
        loaded_mtime_iso = datetime.fromtimestamp(
            st.st_mtime, tz=timezone.utc
        ).isoformat()

    # Resolve deployed jar path (default = canonical LOCALAPPDATA location).
    if deployed_jar_path is None:
        joined = " ".join(cmdline)
        classifier = _classify_for_default_path(joined)
        deployed_jar_path = _default_deployed_path(classifier)

    loaded_id = _file_id(loaded_jar) if loaded_jar else None
    deployed_id = _file_id(deployed_jar_path) if deployed_jar_path else None

    if not IS_WINDOWS:
        notes.append(
            "POSIX host: file-ID check skipped (fsutil is Windows-only)."
        )

    needs_restart: bool | None
    if loaded_id is None or deployed_id is None:
        needs_restart = None
        if loaded_id is None and loaded_jar:
            notes.append(f"could not resolve file ID for loaded jar: {loaded_jar}")
        if deployed_id is None and deployed_jar_path:
            notes.append(
                f"could not resolve file ID for deployed jar: {deployed_jar_path}"
            )
    else:
        needs_restart = loaded_id != deployed_id

    return {
        "pid": pid,
        "uptime_s": round(uptime_s, 1),
        "loaded_jar_path": loaded_jar,
        "loaded_jar_mtime": loaded_mtime_iso,
        "loaded_jar_file_id": loaded_id,
        "deployed_jar_path": deployed_jar_path,
        "deployed_jar_file_id": deployed_id,
        "needs_restart": needs_restart,
        "notes": notes,
    }


def safe_to_deploy(target_jar_path: str) -> dict[str, Any]:
    """Is it safe to overwrite `target_jar_path` right now?"""
    target_id = _file_id(target_jar_path)
    holding: list[int] = []
    recommendations: list[str] = []

    if not IS_WINDOWS:
        recommendations.append(
            "POSIX host: file-ID check skipped. Windows is the target "
            "platform for this check (jar-locking classloader issue)."
        )

    if target_id is None and IS_WINDOWS:
        recommendations.append(
            f"could not resolve file ID for target: {target_jar_path}. "
            "If the file does not yet exist, that is fine — proceed with deploy."
        )

    for row in list_unidrive_processes():
        pid = row["pid"]
        loaded_jar = row.get("jar_path")
        if not loaded_jar:
            continue

        matched = False
        if target_id is not None:
            loaded_id = _file_id(loaded_jar)
            if loaded_id is not None and loaded_id == target_id:
                matched = True
        if not matched:
            # Path-based fallback: identical normalized path counts as a hold
            # even when we couldn't resolve an NTFS ID (POSIX or permissions).
            try:
                if os.path.normcase(os.path.abspath(loaded_jar)) == os.path.normcase(
                    os.path.abspath(target_jar_path)
                ):
                    matched = True
            except Exception:
                pass

        if matched:
            holding.append(pid)
            recommendations.append(
                f"taskkill /PID {pid} /F  # {row.get('image')} — "
                f"holding {loaded_jar}"
            )

    safe = len(holding) == 0 and not (
        IS_WINDOWS and target_id is None and os.path.exists(target_jar_path)
    )

    return {
        "safe": safe,
        "target_jar_path": target_jar_path,
        "target_file_id": target_id,
        "holding_pids": holding,
        "recommendations": recommendations,
    }


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #


_JAR_FLAG_RE = re.compile(r"^-jar$", re.IGNORECASE)


def _extract_jar_path(cmdline: list[str]) -> str | None:
    """Best-effort extraction of the loaded jar from a JVM commandline.

    Looks for `-jar <path>` first, then for a `.jar` token on the classpath
    (`-cp` / `-classpath`). Returns None if the commandline does not
    obviously name a jar — the ticket explicitly flags this as a fuzzy
    heuristic appropriate for unidrive's one-jar-per-JVM shape.
    """
    for i, tok in enumerate(cmdline):
        if _JAR_FLAG_RE.match(tok) and i + 1 < len(cmdline):
            return cmdline[i + 1]
    for i, tok in enumerate(cmdline):
        if tok in ("-cp", "-classpath") and i + 1 < len(cmdline):
            cp = cmdline[i + 1]
            # On Windows classpath separator is `;`, on POSIX `:`.
            sep = ";" if IS_WINDOWS else ":"
            for entry in cp.split(sep):
                if entry.lower().endswith(".jar") and "unidrive" in entry.lower():
                    return entry
    # Last resort: any .jar token naming unidrive.
    for tok in cmdline:
        if tok.lower().endswith(".jar") and "unidrive" in tok.lower():
            return tok
    return None


def _is_sync(cmdline_joined: str) -> bool:
    low = cmdline_joined.lower()
    return " sync" in f" {low}" or "syncservice" in low


def _is_ui(cmdline_joined: str) -> bool:
    low = cmdline_joined.lower()
    return "unidrive-ui" in low or "desktop" in low


def _is_mcp(cmdline_joined: str) -> bool:
    low = cmdline_joined.lower()
    return " mcp" in f" {low}" or "mcp-server" in low


def _classify_for_default_path(cmdline_joined: str) -> str:
    if _is_ui(cmdline_joined):
        return "unidrive-ui"
    if _is_mcp(cmdline_joined):
        return "unidrive-mcp"
    return "unidrive"


def _default_deployed_path(classifier: str) -> str | None:
    localapp = os.environ.get("LOCALAPPDATA")
    if not localapp:
        return None
    return str(Path(localapp) / "unidrive" / f"{classifier}.jar")


# Matches the tail of `fsutil file queryFileID` output. The ID is a
# 128-bit hex value like `0x0000000000000000000a0000000abcde`.
_FSUTIL_ID_RE = re.compile(r"(0x[0-9a-fA-F]+)")


def _file_id(path: str | None) -> str | None:
    """Return the NTFS file ID for `path`, or None if unavailable.

    Windows-only. Returns None on POSIX, on missing files, or when
    fsutil fails for any reason — callers interpret None as
    'indeterminate, note it in recommendations'.
    """
    if not path:
        return None
    if not IS_WINDOWS:
        return None
    try:
        if not os.path.exists(path):
            return None
    except OSError:
        return None
    try:
        out = subprocess.run(
            ["fsutil", "file", "queryFileID", path],
            shell=False,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        log.warning("fsutil failed for %s: %s", path, e)
        return None
    if out.returncode != 0:
        log.warning(
            "fsutil returncode=%s stderr=%s for %s",
            out.returncode,
            (out.stderr or "").strip(),
            path,
        )
        return None
    m = _FSUTIL_ID_RE.search(out.stdout or "")
    return m.group(1) if m else None


async def main() -> None:
    async with stdio_server() as (read, write):
        await app.run(read, write, app.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
