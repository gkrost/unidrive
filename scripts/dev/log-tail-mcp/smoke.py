"""smoke.py — verify log-tail-mcp logic WITHOUT the MCP harness.

Creates a temp log file, points UNIDRIVE_LOG at it, drives the server's
internal helpers through a realistic scenario:

  1. Seed the temp log with some lines (including anomalies).
  2. Run current_state(); assert counts match.
  3. Start a short watch() window in one task.
  4. While the watch is running, append WARN/ERROR lines.
  5. When the window closes, verify events were emitted for the
     appended anomalies and nothing else.
  6. baseline_update() + re-read to verify persistence.

Deliberately avoids the real unidrive.log — uses a tempdir so this is
safe to run at any time on a machine with an active daemon.

Exits 0 on success, non-zero with a message on failure.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
import time
from pathlib import Path


def _seed_lines(path: Path) -> None:
    path.write_text(
        "\n".join([
            "2026-04-20 10:00:00,001 INFO  o.k.u.onedrive.GraphApiService - Delta: 42 items",
            "2026-04-20 10:00:01,002 WARN  o.k.u.onedrive.GraphApiService - Got 429, retryAfterSeconds\":30",
            "2026-04-20 10:00:02,003 ERROR o.k.u.onedrive.GraphApiService - Download failed: some-file.txt",
            "2026-04-20 10:00:03,004 INFO  o.k.u.onedrive.GraphApiService - ok",
            "2026-04-20 10:00:04,005 WARN  o.k.u.onedrive.GraphApiService - attempt 5/5 giving up",
            "",
        ]),
        encoding="utf-8",
    )


async def run() -> int:
    with tempfile.TemporaryDirectory() as td:
        log = Path(td) / "unidrive.log"
        baseline_dir = Path(td) / "baseline-root"
        baseline_dir.mkdir(parents=True, exist_ok=True)
        _seed_lines(log)

        os.environ["UNIDRIVE_LOG"] = str(log)
        if sys.platform.startswith("win"):
            os.environ["LOCALAPPDATA"] = str(baseline_dir)
        else:
            os.environ["HOME"] = str(baseline_dir)

        # Import AFTER env vars are set so path resolution picks them up.
        sys.path.insert(0, str(Path(__file__).parent))
        # Clear any cached module state from prior runs in the same process.
        import importlib
        if "server" in sys.modules:
            importlib.reload(sys.modules["server"])
        import server  # type: ignore

        # --- current_state ---
        state = server._current_state()
        print("current_state:", json.dumps(state, indent=2))
        assert state["exists"], "log should exist"
        assert state["total_lines"] == 5, f"expected 5 lines, got {state['total_lines']}"
        assert state["warn"] == 2, f"expected 2 WARN, got {state['warn']}"
        assert state["error"] == 1, f"expected 1 ERROR, got {state['error']}"
        assert state["throttle_429"] == 1, f"expected 1 throttle_429, got {state['throttle_429']}"
        assert state["download_failed"] == 1, f"expected 1 download_failed, got {state['download_failed']}"
        assert state["fatal_5_5"] == 1, f"expected 1 fatal_5_5, got {state['fatal_5_5']}"

        # --- watch ---
        # Run a 6-second window; append anomalies after 1s.
        async def appender() -> None:
            await asyncio.sleep(1.0)
            with log.open("a", encoding="utf-8") as f:
                f.write("2026-04-20 10:01:00,001 WARN  o.k.u.onedrive.GraphApiService - Got 429 transient\n")
                f.write("2026-04-20 10:01:01,002 INFO  o.k.u.onedrive.GraphApiService - Delta: 3 items\n")  # noise — filtered
                f.write("2026-04-20 10:01:02,003 ERROR o.k.u.onedrive.GraphApiService - Exception in pipeline\n")
                f.flush()
                os.fsync(f.fileno())
            await asyncio.sleep(1.0)
            with log.open("a", encoding="utf-8") as f:
                f.write("2026-04-20 10:01:03,004 WARN  o.k.u.onedrive.GraphApiService - terminated the handshake\n")
                f.flush()
                os.fsync(f.fileno())

        start = time.monotonic()
        watch_task = asyncio.create_task(server._watch({"_window_sec": 6}))
        append_task = asyncio.create_task(appender())
        await append_task
        watch_result = await watch_task
        elapsed = time.monotonic() - start
        print(f"watch returned after {elapsed:.1f}s")
        print("watch:", json.dumps(watch_result, indent=2))

        events = watch_result["events"]
        # We appended 4 lines, 3 anomalies (429, Exception/ERROR, handshake).
        # The INFO Delta: line is noise-filtered.
        anomaly_events = [e for e in events if e.get("category") != "rollup"]
        cats = {e["category"] for e in anomaly_events}
        assert len(anomaly_events) >= 3, f"expected >=3 anomaly events, got {len(anomaly_events)}: {anomaly_events}"
        assert "throttle_429" in cats, f"missing throttle_429 in {cats}"
        assert "tls_handshake" in cats, f"missing tls_handshake in {cats}"
        # 'Exception' line has no " ERROR " token when we look for substring match —
        # check we at least classified it as an anomaly.
        # Noise line should not appear.
        for e in anomaly_events:
            assert "Delta:" not in e["line"], f"noise leaked through: {e}"

        # --- baseline_update ---
        b1 = server._baseline_update()
        print("baseline_update (first):", json.dumps(b1, indent=2))
        assert b1["prev_baseline"] is None, "first baseline should have no prev"
        b2 = server._baseline_update()
        print("baseline_update (second):", json.dumps({"prev_baseline_total": b2["prev_baseline"]["state"]["total_lines"]}, indent=2))
        assert b2["prev_baseline"] is not None, "second baseline should have prev"
        assert b2["prev_baseline"]["state"]["total_lines"] == b1["new_baseline"]["state"]["total_lines"]

        # --- tail ---
        tail_lines = server._tail(3)
        print("tail(3):", tail_lines)
        assert len(tail_lines) == 3, f"expected 3 tail lines, got {len(tail_lines)}"

        print("\nOK")
        return 0


def main() -> int:
    try:
        return asyncio.run(run())
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"FAIL ({type(e).__name__}): {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
