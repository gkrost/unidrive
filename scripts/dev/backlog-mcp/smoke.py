"""smoke.py — offline sanity check for backlog-mcp.

Exercises the three read-only tool handlers — `next_id`, `show`, and
`audit` — against the current repo state without mutating any file.
Run before registering the MCP to confirm imports and parsing work:

    python scripts/dev/backlog-mcp/smoke.py

Prints a one-line summary per tool and a non-zero exit code on
failure. The mutating tools (`file`, `close`) are not exercised here —
they're covered by the CLI's own test loop.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE))

# Defer the actual imports so failures surface inside main() with nicer output.


def main() -> int:
    try:
        import server  # noqa: F401 — triggers backlog + mcp imports
        from server import _next_id, _show, run_audit
    except Exception as e:
        print(f"FAIL (import): {type(e).__name__}: {e}")
        return 1

    # next_id against a well-known category
    try:
        nid = _next_id({"category": "tooling"})
        print(f"next_id(tooling): {nid['id']}")
    except SystemExit as e:
        print(f"FAIL (next_id): {e}")
        return 1

    # show a ticket that must exist — UD-716 is this feature's own ticket
    try:
        shown = _show({"id": "UD-716"})
        snippet = shown["block"].splitlines()[0:3]
        print(f"show(UD-716): found_in={shown['found_in']}, first_lines={snippet}")
    except SystemExit as e:
        print(f"FAIL (show): {e}")
        return 1

    # audit — don't assert a specific shape beyond the top-level keys; the
    # point is that it runs end-to-end without raising.
    try:
        report = run_audit()
        expected = {
            "duplicate_ids",
            "missing_fields",
            "broken_cross_refs",
            "stale_code_refs",
            "orphan_resolved_refs",
        }
        got = set(report)
        if got != expected:
            print(f"FAIL (audit): unexpected keys: missing={expected - got}, extra={got - expected}")
            return 1
        counts = {k: len(v) for k, v in report.items()}
        print(f"audit(): {json.dumps(counts)}")
    except Exception as e:
        print(f"FAIL (audit): {type(e).__name__}: {e}")
        return 1

    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
