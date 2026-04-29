"""smoke.py — offline-ish check for the jvm-monitor MCP.

Runs the same in-process tool functions the MCP exposes, validates the
return-shape, and confirms `psutil` sees at least one Python interpreter
process (itself). Does NOT require a running unidrive daemon — this way
a developer can sanity-check the wiring before any JVM is even started.

Exit 0 on success, non-zero on failure. Prints a short OK/FAIL trailer.

Usage:
    python smoke.py
"""

from __future__ import annotations

import json
import os
import platform
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))


def _fail(msg: str) -> None:
    print(f"FAIL: {msg}")
    sys.exit(1)


def main() -> None:
    try:
        import psutil  # noqa: F401
    except ImportError as e:
        _fail(f"psutil not importable: {e}")

    try:
        import server  # type: ignore
    except ImportError as e:
        _fail(f"server module not importable: {e}")

    # 1. psutil sees at least one python process (this one must qualify).
    import psutil

    py_names = {"python.exe", "python3", "python", "python3.exe", "py.exe"}
    seen_python = False
    for proc in psutil.process_iter(["name"]):
        name = (proc.info.get("name") or "").lower()
        if name in {n.lower() for n in py_names}:
            seen_python = True
            break
    if not seen_python:
        # Fall back to checking current process image name — we are python.
        cur_name = (psutil.Process(os.getpid()).name() or "").lower()
        if cur_name not in {n.lower() for n in py_names}:
            _fail(
                f"psutil.process_iter() yielded no python.exe / python3 "
                f"and current process image is {cur_name!r}"
            )
    print("ok: psutil sees at least one python process")

    # 2. list_unidrive_processes returns a list (possibly empty) and
    #    every row has the expected schema.
    rows = server.list_unidrive_processes()
    if not isinstance(rows, list):
        _fail(f"list_unidrive_processes returned {type(rows).__name__}, want list")
    required_keys = {
        "pid",
        "image",
        "mem_mb",
        "start_time_iso",
        "commandline",
        "jar_path",
        "is_sync",
        "is_ui",
        "is_mcp",
    }
    for row in rows:
        missing = required_keys - set(row.keys())
        if missing:
            _fail(f"row {row.get('pid')} missing keys: {missing}")
        for k in ("is_sync", "is_ui", "is_mcp"):
            if not isinstance(row[k], bool):
                _fail(f"row {row['pid']} field {k} is {type(row[k]).__name__}, want bool")
    print(f"ok: list_unidrive_processes returned {len(rows)} row(s), schema valid")

    # 3. safe_to_deploy on a path that (almost certainly) nobody is holding.
    #    We point at a bogus path; the tool must not crash and must return the
    #    full shape.
    bogus = str(HERE / "definitely-not-a-real-deployed.jar")
    result = server.safe_to_deploy(bogus)
    for k in ("safe", "target_jar_path", "target_file_id", "holding_pids", "recommendations"):
        if k not in result:
            _fail(f"safe_to_deploy missing key {k}")
    if not isinstance(result["holding_pids"], list):
        _fail("safe_to_deploy.holding_pids is not a list")
    if not isinstance(result["recommendations"], list):
        _fail("safe_to_deploy.recommendations is not a list")
    print(f"ok: safe_to_deploy shape valid (safe={result['safe']})")

    # 4. jvm_state on our own pid — the smoke process is not a JVM and has no
    #    jar, so this exercises the "no jar on commandline" code path without
    #    hitting psutil.NoSuchProcess. needs_restart must be None (indeterminate).
    state = server.jvm_state(pid=os.getpid())
    if "error" in state:
        _fail(f"jvm_state(self) errored: {state['error']}")
    for k in (
        "pid",
        "uptime_s",
        "loaded_jar_path",
        "loaded_jar_mtime",
        "loaded_jar_file_id",
        "deployed_jar_path",
        "deployed_jar_file_id",
        "needs_restart",
        "notes",
    ):
        if k not in state:
            _fail(f"jvm_state missing key {k}")
    print(
        f"ok: jvm_state(self) shape valid "
        f"(loaded_jar_path={state['loaded_jar_path']}, "
        f"needs_restart={state['needs_restart']})"
    )

    print(f"\nplatform={platform.system()}  python={sys.version.split()[0]}")
    print("OK")


if __name__ == "__main__":
    main()
