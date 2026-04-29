"""smoke.py — offline shape-check for daemon-ipc-mcp.

Verifies:
  - socket path resolution produces sane output on this platform
  - _sync_log_tail handles a missing log file cleanly
  - _sync_state handles a missing socket cleanly
  - _sync_state against a live local AF_UNIX server reads events
"""
from __future__ import annotations

import json
import os
import socket
import sys
import tempfile
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from server import _resolve_socket, _sync_log_tail, _sync_state, _log_path  # noqa: E402


def test_socket_path() -> None:
    p = _resolve_socket("onedrive-test")
    assert "unidrive-" in str(p)
    assert str(p).endswith(".sock")
    print(f"  socket path ok: {p}")


def test_socket_path_long_profile_is_hashed() -> None:
    long = "x" * 120
    p = _resolve_socket(long)
    assert long not in str(p), "expected long profile name to be hashed out"
    print(f"  long-profile hash ok: {p.name}")


def test_log_tail_missing_file() -> None:
    os.environ["UNIDRIVE_LOG"] = "/nonexistent/unidrive.log"
    result = _sync_log_tail({"profile": "x", "lines": 10})
    assert "error" in result
    assert result["lines"] == []
    del os.environ["UNIDRIVE_LOG"]
    print("  missing-log-file handled cleanly")


def test_log_tail_reads_file() -> None:
    with tempfile.TemporaryDirectory() as td:
        logp = Path(td) / "unidrive.log"
        logp.write_text("\n".join(f"line {i}" for i in range(100)) + "\n")
        os.environ["UNIDRIVE_LOG"] = str(logp)
        try:
            result = _sync_log_tail({"profile": "x", "lines": 5})
        finally:
            del os.environ["UNIDRIVE_LOG"]
        assert result["source"] == "log"
        assert result["line_count"] == 5
        assert result["lines"][-1] == "line 99"
    print("  log tail reads correct trailing lines")


def test_sync_state_missing_socket() -> None:
    result = _sync_state({"profile": "does-not-exist", "collect_ms": 100})
    assert "error" in result
    assert "no IPC socket" in result["error"]
    print("  missing-socket handled cleanly")


def test_sync_state_reads_events() -> None:
    if not hasattr(socket, "AF_UNIX"):
        print("  skipping live read test — no AF_UNIX on this platform")
        return

    with tempfile.TemporaryDirectory() as td:
        sock_path = Path(td) / "fake.sock"

        def serve() -> None:
            srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            srv.bind(str(sock_path))
            srv.listen(1)
            client, _ = srv.accept()
            frames = [
                {"event": "sync_started", "profile": "smoke"},
                {"event": "scan_progress", "phase": "local", "count": 42},
                {"event": "action_count", "total": 100},
                {"event": "action_progress", "index": 7, "total": 100,
                 "action": "download", "path": "/x/y.txt"},
            ]
            for f in frames:
                client.sendall((json.dumps(f) + "\n").encode("utf-8"))
            # linger, then close
            import time
            time.sleep(0.1)
            client.close()
            srv.close()

        t = threading.Thread(target=serve, daemon=True)
        t.start()

        # Wait for socket to exist
        import time
        for _ in range(50):
            if sock_path.exists():
                break
            time.sleep(0.02)

        # Patch the resolver for this one call
        from server import _resolve_socket as _orig_resolve
        import server as srv_mod
        srv_mod._resolve_socket = lambda profile: sock_path  # type: ignore
        try:
            result = _sync_state({"profile": "smoke", "collect_ms": 300})
        finally:
            srv_mod._resolve_socket = _orig_resolve  # type: ignore

        t.join(timeout=1)

    assert result.get("events_collected") == 4, result
    assert result.get("phase") == "local"
    assert result.get("current_file") == "/x/y.txt"
    assert result.get("action_total") == 100
    print(f"  live read ok — parsed {result['events_collected']} events")


def main() -> None:
    test_socket_path()
    test_socket_path_long_profile_is_hashed()
    test_log_tail_missing_file()
    test_log_tail_reads_file()
    test_sync_state_missing_socket()
    test_sync_state_reads_events()
    print("OK")


if __name__ == "__main__":
    main()
