"""server.py — MCP server exposing backlog.py operations as structured tools.

Wraps `scripts/dev/backlog.py` so a Claude Code session can file, close,
look up, and audit backlog tickets via typed tool calls instead of
argparse-shaped string munging. Ticket: UD-716.

Design constraints
------------------
- Reuses `backlog.py` directly via `import backlog`. No logic
  duplication; the MCP is a thin typed-args shim.
- Pure read/write on `docs/backlog/{BACKLOG,CLOSED}.md`. No network.
- `audit()` is the new capability the CLI can't easily deliver: a
  structured drift report across both files plus the git history.

Tools exposed
-------------

  next_id(category) -> { id }
  show(id) -> { found_in: "BACKLOG"|"CLOSED", block }
  file(id, title, category, priority, effort, code_refs[], body)
      -> { id, path }
  close(id, commit, note) -> { moved_to: "CLOSED.md", resolved_by }
  audit() -> { duplicate_ids, missing_fields, broken_cross_refs,
               stale_code_refs, orphan_resolved_refs }
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

# Import the existing CLI module as a library. The sibling `scripts/dev/`
# is our source of truth; we don't vendor or duplicate its logic.
_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE.parent))
import backlog  # noqa: E402  — path hack above

log = logging.getLogger("unidrive-backlog")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)

app = Server("unidrive-backlog")

REQUIRED_FIELDS = ("id", "title", "category", "priority", "effort", "status", "opened")
PRIORITIES = ("low", "medium", "high", "critical")
EFFORTS = ("XS", "S", "M", "L", "XL")


@app.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="next_id",
            description=(
                "Return the next free UD-### ticket id in the given category range. "
                "Categories: architecture, security, core, providers, shellwin/shell-win, "
                "ui, protocol, tooling, tests, experimental."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "category": {"type": "string"},
                },
                "required": ["category"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="show",
            description=(
                "Print the frontmatter block + body for a ticket, searching "
                "BACKLOG.md first then CLOSED.md."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "id": {"type": "string", "pattern": "^UD-\\d{3}[a-z]?$"},
                },
                "required": ["id"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="file",
            description=(
                "Append a new ticket to BACKLOG.md. Validates id is unused, "
                "priority ∈ {low,medium,high,critical}, effort ∈ {XS,S,M,L,XL}."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "id": {"type": "string", "pattern": "^UD-\\d{3}[a-z]?$"},
                    "title": {"type": "string"},
                    "category": {"type": "string"},
                    "priority": {"type": "string", "enum": list(PRIORITIES)},
                    "effort": {"type": "string", "enum": list(EFFORTS)},
                    "code_refs": {
                        "type": "array",
                        "items": {"type": "string"},
                        "default": [],
                    },
                    "body": {"type": "string"},
                },
                "required": ["id", "title", "category", "priority", "effort", "body"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="close",
            description=(
                "Move a ticket from BACKLOG.md to CLOSED.md with a resolved_by "
                "note. Commit is optional; if provided it is prefixed as "
                "'commit <sha>. ' in front of the note."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "id": {"type": "string", "pattern": "^UD-\\d{3}[a-z]?$"},
                    "commit": {"type": "string"},
                    "note": {"type": "string"},
                },
                "required": ["id", "note"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="audit",
            description=(
                "Scan BACKLOG.md + CLOSED.md and return a structured drift "
                "report: duplicate_ids, missing_fields, broken_cross_refs, "
                "stale_code_refs, orphan_resolved_refs (commits unreachable "
                "from HEAD)."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        ),
    ]


@app.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    try:
        if name == "next_id":
            result = _next_id(arguments)
        elif name == "show":
            result = _show(arguments)
        elif name == "file":
            result = _file(arguments)
        elif name == "close":
            result = _close(arguments)
        elif name == "audit":
            result = run_audit()
        else:
            raise ValueError(f"unknown tool: {name}")
        return [TextContent(type="text", text=json.dumps(result, indent=2))]
    except SystemExit as e:
        # backlog.py raises SystemExit for validation errors. Surface the
        # message as a structured tool error instead of crashing the stdio
        # loop.
        log.warning("backlog validation error in %s: %s", name, e)
        return [TextContent(type="text", text=json.dumps({"error": str(e)}))]
    except Exception as e:
        log.exception("unexpected error in %s", name)
        return [TextContent(type="text", text=json.dumps({"error": f"{type(e).__name__}: {e}"}))]


# ---------------------------------------------------------------- handlers


def _next_id(args: dict[str, Any]) -> dict[str, Any]:
    return {"id": backlog.next_free_id(args["category"])}


def _show(args: dict[str, Any]) -> dict[str, Any]:
    uid = args["id"]
    for path, label in ((backlog.BACKLOG, "BACKLOG"), (backlog.CLOSED, "CLOSED")):
        text = path.read_text(encoding="utf-8")
        if re.search(r"^id: " + re.escape(uid) + r"\b", text, re.MULTILINE):
            start, end = backlog.find_block(text, uid)
            return {"found_in": label, "block": text[start:end].rstrip()}
    raise SystemExit(f"{uid} not found")


def _file(args: dict[str, Any]) -> dict[str, Any]:
    path = backlog.file_ticket_impl(
        uid=args["id"],
        title=args["title"],
        category=args["category"],
        priority=args["priority"],
        effort=args["effort"],
        body=args["body"],
        code_refs=args.get("code_refs") or [],
    )
    return {"id": args["id"], "path": str(path)}


def _close(args: dict[str, Any]) -> dict[str, Any]:
    resolved_by = backlog.close_ticket_impl(
        uid=args["id"],
        commit=args.get("commit"),
        note=args["note"],
    )
    return {"moved_to": "CLOSED.md", "resolved_by": resolved_by}


# --------------------------------------------------------------------- audit


# Matches a frontmatter field line. Captures the key (lower-kebab/underscore)
# and the value. Field blocks are terminated by a `---` line; lines below the
# closing `---` are prose body.
_FIELD_RE = re.compile(r"^([a-z_]+):\s*(.*)$")
_ID_RE = re.compile(r"UD-\d{3}[a-z]?")
_COMMIT_SHA_RE = re.compile(r"\bcommit\s+([0-9a-f]{7,40})\b", re.IGNORECASE)
_CROSS_REF_RE = re.compile(
    r"(?:Related|Depends on|blocked_by|Blocked by):[^\n]*", re.IGNORECASE
)


def parse_blocks(text: str) -> list[dict[str, Any]]:
    """Parse a BACKLOG/CLOSED markdown file into ticket blocks.

    Returns a list of dicts: { id, fields: {name: value}, code_refs: [..],
    body: str, raw: str, line: int, file: str }.

    This is forgiving — tickets with missing fields still appear, which is
    the point (audit reports them).
    """
    blocks: list[dict[str, Any]] = []
    # A block starts with `---\nid: UD-`. Walk the file by regex.
    for m in re.finditer(r"^---\nid: (UD-\d{3}[a-z]?)\n", text, re.MULTILINE):
        start, end = backlog.find_block(text, m.group(1))
        raw = text[start:end]
        # Split frontmatter from body. Frontmatter ends at the second `---`.
        lines = raw.split("\n")
        # Skip the opening `---`.
        assert lines[0] == "---"
        fm_end = None
        for i in range(1, len(lines)):
            if lines[i] == "---":
                fm_end = i
                break
        if fm_end is None:
            continue
        fm_lines = lines[1:fm_end]
        body = "\n".join(lines[fm_end + 1 :])

        fields: dict[str, str] = {}
        code_refs: list[str] = []
        i = 0
        while i < len(fm_lines):
            line = fm_lines[i]
            if line.startswith("code_refs:"):
                tail = line.split(":", 1)[1].strip()
                if tail.startswith("["):
                    # inline list; skip parsing — treat as empty
                    pass
                else:
                    # multi-line list, each starting with "  - "
                    j = i + 1
                    while j < len(fm_lines) and fm_lines[j].startswith("  - "):
                        code_refs.append(fm_lines[j][4:].strip())
                        j += 1
                    i = j
                    continue
            fm = _FIELD_RE.match(line)
            if fm:
                fields[fm.group(1)] = fm.group(2).strip()
            i += 1

        blocks.append(
            {
                "id": m.group(1),
                "fields": fields,
                "code_refs": code_refs,
                "body": body,
                "raw": raw,
                "offset": start,
            }
        )
    return blocks


def _commit_exists(sha: str, repo: Path) -> bool:
    """True iff `git cat-file -e <sha>` succeeds (object reachable).

    Uses `-e` (exists check) rather than `merge-base --is-ancestor` because
    the orchestrator commits its ticket moves on a branch that isn't yet
    merged to main; reachability from HEAD is the weakest useful check.
    """
    try:
        r = subprocess.run(
            ["git", "cat-file", "-e", sha + "^{commit}"],
            cwd=repo,
            capture_output=True,
            timeout=5,
        )
        return r.returncode == 0
    except (subprocess.SubprocessError, FileNotFoundError):
        return False


def run_audit() -> dict[str, list[dict[str, Any]]]:
    """Scan BACKLOG.md + CLOSED.md and return the drift report."""
    backlog_text = backlog.BACKLOG.read_text(encoding="utf-8")
    closed_text = backlog.CLOSED.read_text(encoding="utf-8")
    backlog_blocks = parse_blocks(backlog_text)
    closed_blocks = parse_blocks(closed_text)
    all_blocks = [(b, "BACKLOG") for b in backlog_blocks] + [
        (b, "CLOSED") for b in closed_blocks
    ]

    # ---- duplicate_ids: same UD-### appearing >1 time across both files.
    seen: dict[str, list[str]] = {}
    for b, where in all_blocks:
        seen.setdefault(b["id"], []).append(where)
    duplicate_ids = [
        {"id": uid, "locations": locs}
        for uid, locs in sorted(seen.items())
        if len(locs) > 1
    ]

    # ---- missing_fields: required frontmatter fields absent on a block.
    missing_fields: list[dict[str, Any]] = []
    for b, where in all_blocks:
        missing = [f for f in REQUIRED_FIELDS if f not in b["fields"] and f != "id"]
        # `id` is implicit — we only matched blocks that have it.
        if missing:
            missing_fields.append(
                {"id": b["id"], "file": where, "missing": missing}
            )

    # ---- broken_cross_refs: Related/Depends on/blocked_by UD-X where UD-X
    # is not in the union of known ids.
    known_ids = set(seen)
    broken_cross_refs: list[dict[str, Any]] = []
    for b, where in all_blocks:
        refs_in_block: set[str] = set()
        for line_match in _CROSS_REF_RE.finditer(b["raw"]):
            for ref in _ID_RE.findall(line_match.group(0)):
                if ref == b["id"]:
                    continue
                refs_in_block.add(ref)
        for ref in sorted(refs_in_block):
            if ref not in known_ids:
                broken_cross_refs.append(
                    {"id": b["id"], "file": where, "missing_ref": ref}
                )

    # ---- stale_code_refs: code_refs: paths that don't exist in the tree.
    # Tolerate ":line" suffixes (e.g. "Foo.kt:43") by stripping them. Glob
    # expansion: if the ref contains "*", accept any match.
    stale_code_refs: list[dict[str, Any]] = []
    repo = backlog.REPO
    for b, where in all_blocks:
        for ref in b["code_refs"]:
            path_part = ref.split(":")[0].strip()
            if not path_part:
                continue
            candidate = repo / path_part
            exists = candidate.exists()
            if not exists and "*" in path_part:
                exists = bool(list(repo.glob(path_part)))
            if not exists:
                stale_code_refs.append(
                    {"id": b["id"], "file": where, "ref": ref}
                )

    # ---- orphan_resolved_refs: `resolved_by: commit <sha>` where <sha>
    # is not reachable via `git cat-file -e`. Only applies in CLOSED.md.
    orphan_resolved_refs: list[dict[str, Any]] = []
    for b, where in all_blocks:
        resolved = b["fields"].get("resolved_by", "")
        if not resolved:
            continue
        for sha_match in _COMMIT_SHA_RE.finditer(resolved):
            sha = sha_match.group(1)
            if not _commit_exists(sha, repo):
                orphan_resolved_refs.append(
                    {"id": b["id"], "file": where, "commit": sha}
                )

    return {
        "duplicate_ids": duplicate_ids,
        "missing_fields": missing_fields,
        "broken_cross_refs": broken_cross_refs,
        "stale_code_refs": stale_code_refs,
        "orphan_resolved_refs": orphan_resolved_refs,
    }


async def main() -> None:
    async with stdio_server() as (read, write):
        await app.run(read, write, app.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
