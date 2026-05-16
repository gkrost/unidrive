#!/usr/bin/env python3
"""UD-717: backlog.py next-id must skip IDs referenced in prose.

Standalone smoke test (no pytest dep). Run from repo root:

    python3 scripts/dev/tests/test_backlog_next_id.py

Exits 0 on success, 1 on failure, with a printed diagnostic on stderr.
"""
from __future__ import annotations

import importlib.util
import sys
import tempfile
from pathlib import Path


def _load_backlog_module():
    """Import scripts/dev/backlog.py as a fresh module each call."""
    repo = Path(__file__).resolve().parents[3]
    spec = importlib.util.spec_from_file_location(
        "backlog_under_test", repo / "scripts" / "dev" / "backlog.py"
    )
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _make_fake_tree(tmp: Path) -> None:
    """Lay down a minimal repo-shaped tree with a known UD-XXX prose ref."""
    (tmp / "docs" / "backlog").mkdir(parents=True)
    (tmp / "docs" / "backlog" / "BACKLOG.md").write_text(
        "# Backlog\n\n---\nid: UD-200\ntitle: existing\ncategory: core\n"
        "priority: low\neffort: S\nstatus: open\nopened: 2026-01-01\n---\n"
        "Body.\n"
    )
    (tmp / "docs" / "backlog" / "CLOSED.md").write_text("# Closed\n")
    # Prose-only reference to UD-201 — must be picked up by referenced_ids().
    (tmp / "docs").mkdir(exist_ok=True)
    (tmp / "docs" / "ARCHITECTURE.md").write_text(
        "See [UD-201](backlog/CLOSED.md#ud-201) for the prior research.\n"
    )


def test_next_id_skips_prose_referenced_id():
    """Prose-only UD-201 must be skipped; next-id returns UD-202."""
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        _make_fake_tree(tmp)
        mod = _load_backlog_module()
        mod.REPO = tmp
        mod.BACKLOG = tmp / "docs" / "backlog" / "BACKLOG.md"
        mod.CLOSED = tmp / "docs" / "backlog" / "CLOSED.md"
        mod._referenced_cache = None  # clear cache from any prior call

        # Pre-condition: frontmatter has UD-200; prose has UD-201.
        # Old behaviour would return UD-201 (skipping only frontmatter).
        # New behaviour must return UD-202 (skipping both).
        got = mod.next_free_id("core")
        assert got == "UD-202", (
            f"expected UD-202 (UD-200 frontmatter + UD-201 prose both blocked), "
            f"got {got}"
        )


def test_file_ticket_warns_on_prose_referenced_id(capsys=None):
    """file_ticket_impl must warn (not refuse) when --id is prose-referenced."""
    import io
    import contextlib

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        _make_fake_tree(tmp)
        mod = _load_backlog_module()
        mod.REPO = tmp
        mod.BACKLOG = tmp / "docs" / "backlog" / "BACKLOG.md"
        mod.CLOSED = tmp / "docs" / "backlog" / "CLOSED.md"
        mod._referenced_cache = None

        # Filing UD-201 (prose-referenced, not in frontmatter) should
        # succeed but emit a warning to stderr.
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            mod.file_ticket_impl(
                uid="UD-201",
                title="hand-picked colliding id",
                category="core",
                priority="low",
                effort="S",
                body="Body.",
            )
        stderr = buf.getvalue()
        assert "UD-201" in stderr and "warning" in stderr.lower(), (
            f"expected warning on stderr mentioning UD-201 and 'warning', "
            f"got: {stderr!r}"
        )
        # And the ticket must actually land in BACKLOG.md.
        assert "id: UD-201" in mod.BACKLOG.read_text()


def main() -> int:
    failures: list[str] = []
    for fn in (test_next_id_skips_prose_referenced_id, test_file_ticket_warns_on_prose_referenced_id):
        try:
            fn()
            print(f"ok  {fn.__name__}")
        except AssertionError as e:
            print(f"FAIL {fn.__name__}: {e}", file=sys.stderr)
            failures.append(fn.__name__)
    if failures:
        print(f"\n{len(failures)} test(s) failed.", file=sys.stderr)
        return 1
    print(f"\nall {2} tests passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
