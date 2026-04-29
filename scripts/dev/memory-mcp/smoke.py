"""smoke.py — offline exercise of the memory-mcp CRUD primitives.

Runs against a tempdir (NEVER the user's real memory dir). Exercises
list / read / add / update / remove / index_sanity and asserts each
expected invariant. Exit 0 on success, 1 on failure.

Usage:
    python scripts/dev/memory-mcp/smoke.py
"""

from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        os.environ["UNIDRIVE_MEMORY_DIR"] = str(tmp_path)

        # Import AFTER setting the env var so memory_dir() picks it up on
        # first call. The module reads the env on every call anyway, but
        # being explicit avoids surprise.
        sys.path.insert(0, str(Path(__file__).parent))
        import server  # noqa: E402

        assert server.memory_dir() == tmp_path, (
            f"memory_dir override not honoured: {server.memory_dir()}"
        )

        # Pre-seed an empty MEMORY.md so the index exists.
        (tmp_path / "MEMORY.md").write_text("# Memory Index\n\n", encoding="utf-8")

        # --- add ---------------------------------------------------------
        r = server._add_memory(
            {
                "type": "feedback",
                "name": "test_alpha",
                "description": "alpha description",
                "body": "Body of alpha.\n\nSecond paragraph.\n",
            }
        )
        assert (tmp_path / "test_alpha.md").exists(), r
        assert "test_alpha.md" in r["index_line"], r

        server._add_memory(
            {
                "type": "project",
                "name": "test_beta",
                "description": "beta description",
                "body": "Beta body.\n",
            }
        )
        server._add_memory(
            {
                "type": "user",
                "name": "test_gamma",
                "description": "gamma description",
                "body": "Gamma body.\n",
            }
        )

        # Duplicate add must raise.
        try:
            server._add_memory(
                {
                    "type": "feedback",
                    "name": "test_alpha",
                    "description": "x",
                    "body": "x",
                }
            )
            return fail("duplicate add did not raise")
        except FileExistsError:
            pass

        # Invalid type must raise.
        try:
            server._add_memory(
                {
                    "type": "bogus",
                    "name": "test_delta",
                    "description": "x",
                    "body": "x",
                }
            )
            return fail("invalid type did not raise")
        except ValueError:
            pass

        # --- list --------------------------------------------------------
        listing = server._list_memories()
        names = [m["name"] for m in listing]
        assert names == ["test_gamma", "test_alpha", "test_beta"], (
            f"list ordering wrong (expected user, feedback, project): {names}"
        )
        alpha = next(m for m in listing if m["name"] == "test_alpha")
        assert alpha["description"] == "alpha description"
        assert alpha["type"] == "feedback"
        assert alpha["preview"].startswith("Body of alpha"), alpha

        # --- read --------------------------------------------------------
        read = server._read_memory("test_alpha")
        assert read["frontmatter"]["type"] == "feedback"
        assert read["frontmatter"]["description"] == "alpha description"
        assert "Second paragraph." in read["body"]

        # --- update (description only) -----------------------------------
        upd = server._update_memory(
            {"name": "test_alpha", "description": "alpha REVISED"}
        )
        assert upd["changed_fields"] == ["description"], upd
        read2 = server._read_memory("test_alpha")
        assert read2["frontmatter"]["description"] == "alpha REVISED"
        # Index line must have resynced.
        idx_text = (tmp_path / "MEMORY.md").read_text(encoding="utf-8")
        assert "alpha REVISED" in idx_text
        assert "alpha description" not in idx_text

        # --- update (body only) ------------------------------------------
        upd2 = server._update_memory(
            {"name": "test_alpha", "body": "new body content\n"}
        )
        assert upd2["changed_fields"] == ["body"], upd2
        # No-op update ----------------------------------------------------
        upd3 = server._update_memory({"name": "test_alpha"})
        assert upd3["changed_fields"] == [], upd3

        # --- index_sanity (clean state) ----------------------------------
        sanity = server._index_sanity()
        assert sanity["orphan_files"] == [], sanity
        assert sanity["orphan_index_lines"] == [], sanity
        assert sanity["description_drift"] == [], sanity

        # --- inject drift ------------------------------------------------
        # 1) orphan file: write a .md without an index entry
        (tmp_path / "test_orphan.md").write_text(
            "---\nname: test_orphan\ndescription: orphan desc\ntype: reference\n---\n\nbody\n",
            encoding="utf-8",
        )
        # 2) orphan index line: append a line pointing at a missing file
        with open(tmp_path / "MEMORY.md", "a", encoding="utf-8") as f:
            f.write("- [Missing](does_not_exist.md) \u2014 no such file\n")
        # 3) description drift: change frontmatter directly, bypassing update()
        beta_path = tmp_path / "test_beta.md"
        beta_text = beta_path.read_text(encoding="utf-8")
        beta_text = beta_text.replace(
            "description: beta description", "description: beta DRIFTED"
        )
        beta_path.write_text(beta_text, encoding="utf-8")

        sanity2 = server._index_sanity()
        assert "test_orphan.md" in sanity2["orphan_files"], sanity2
        assert any(
            "does_not_exist.md" in line for line in sanity2["orphan_index_lines"]
        ), sanity2
        drift_files = [d["file"] for d in sanity2["description_drift"]]
        assert "test_beta.md" in drift_files, sanity2

        # --- remove ------------------------------------------------------
        rm = server._remove_memory({"name": "test_alpha"})
        assert not (tmp_path / "test_alpha.md").exists()
        assert rm["removed_index_line"] is not None
        assert "test_alpha.md" in rm["removed_index_line"]
        idx_after = (tmp_path / "MEMORY.md").read_text(encoding="utf-8")
        assert "test_alpha.md" not in idx_after

        # Remove missing must raise.
        try:
            server._remove_memory({"name": "test_alpha"})
            return fail("remove of missing memory did not raise")
        except FileNotFoundError:
            pass

        print("OK — all assertions passed")
        return 0


def fail(msg: str) -> int:
    print(f"FAIL: {msg}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
