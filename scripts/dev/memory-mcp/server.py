"""server.py — MCP server exposing structured CRUD over the agent-memory files.

Runs on the user's side. The user registers it in their Claude Code MCP config
(see README.md). Tools exposed:

  list()
      Enumerate every memory file under the memory dir, returning parsed
      frontmatter (name, type, description) and a 120-char preview of the
      body. Ordered by type then name.

  read(name: str)
      Return one memory file, parsed into {frontmatter: {...}, body: str}.

  add(type, name, description, body)
      Atomically create a new <name>.md with correctly-shaped frontmatter,
      append the index line to MEMORY.md. Refuses duplicates; refuses
      invalid types.

  update(name, description?, body?)
      In-place edit preserving the original name / type. If description
      changes, the MEMORY.md index line is resynced.

  remove(name)
      Delete the memory file and strip its MEMORY.md index line.

  index_sanity()
      Scan the directory against MEMORY.md for orphan files (not indexed),
      orphan index lines (point at missing files), and description drift
      (index description != frontmatter description).

Memory dir resolution: `UNIDRIVE_MEMORY_DIR` env override, else
Claude Code's per-project memory dir derived from the current working
tree's path (Claude encodes the absolute repo path into a folder name
under `~/.claude/projects/`). Set `UNIDRIVE_MEMORY_DIR` explicitly when
the repo lives outside the auto-detected layout.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

log = logging.getLogger("unidrive-memory")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)

app = Server("unidrive-memory")

VALID_TYPES = ("user", "feedback", "project", "reference")
MEMORY_INDEX = "MEMORY.md"
NAME_RE = re.compile(r"^[a-zA-Z0-9_\-]+$")

# Index line pattern: "- [Title](file.md) — description"
# The separator is an em-dash (U+2014). Some hand-written entries may use "-"
# or " - " — we accept both but write em-dashes.
INDEX_LINE_RE = re.compile(
    r"^\s*-\s+\[(?P<title>[^\]]+)\]\((?P<file>[^)]+\.md)\)\s*(?:[\u2014\-]+)\s*(?P<desc>.*?)\s*$"
)


# ---------------------------------------------------------------------------
# Path resolution
# ---------------------------------------------------------------------------


def memory_dir() -> Path:
    """Resolve the Claude Code memory dir for this repo.

    Priority:
      1. `UNIDRIVE_MEMORY_DIR` env override (use when running outside the
         auto-detected layout, e.g. via Claude Desktop's MSIX sandbox).
      2. Auto-derived from the current working directory: Claude encodes
         the absolute repo path as `<drive-letter-or-empty>--Users-...`
         under `~/.claude/projects/`. The encoding replaces `:`, `\\`,
         `/` with `-`.
      3. Last-resort fallback: `<repo-root>/.claude/memory/` (only used
         when `~/.claude/projects/<encoded>` doesn't exist — useful for
         local-only operation without a Claude install).
    """
    override = os.environ.get("UNIDRIVE_MEMORY_DIR")
    if override:
        return Path(override).expanduser()

    cwd = Path.cwd().resolve()
    encoded = (
        str(cwd)
        .replace(":", "-")
        .replace("\\", "-")
        .replace("/", "-")
        .lstrip("-")
    )
    auto = Path.home() / ".claude" / "projects" / f"C--{encoded}" / "memory"
    if auto.parent.exists():
        return auto

    return cwd / ".claude" / "memory"


# ---------------------------------------------------------------------------
# Frontmatter parsing / serialisation
# ---------------------------------------------------------------------------


@dataclass
class Memory:
    frontmatter: dict[str, str]
    body: str


def parse_memory(text: str) -> Memory:
    """Parse a memory file. Frontmatter is simple `key: value` YAML,
    bounded by `---` fences. No nesting, no lists — the contract is strict.
    """
    if not text.startswith("---"):
        raise ValueError("missing leading --- frontmatter fence")
    # Split off leading fence.
    lines = text.splitlines(keepends=False)
    if not lines or lines[0].rstrip() != "---":
        raise ValueError("missing leading --- frontmatter fence")
    fm: dict[str, str] = {}
    end_idx = -1
    for i, line in enumerate(lines[1:], start=1):
        if line.rstrip() == "---":
            end_idx = i
            break
        if ":" not in line:
            raise ValueError(f"frontmatter line has no ':' separator: {line!r}")
        key, _, value = line.partition(":")
        fm[key.strip()] = value.strip()
    if end_idx == -1:
        raise ValueError("missing closing --- frontmatter fence")
    # Body is everything after the closing fence. Strip one leading blank
    # line (by convention) but preserve internal structure.
    body_lines = lines[end_idx + 1:]
    if body_lines and body_lines[0] == "":
        body_lines = body_lines[1:]
    body = "\n".join(body_lines)
    # Preserve trailing newline if the source had one.
    if text.endswith("\n") and not body.endswith("\n"):
        body += "\n"
    return Memory(frontmatter=fm, body=body)


def serialise_memory(fm: dict[str, str], body: str) -> str:
    """Render frontmatter + body back to disk format. Keys are emitted in a
    stable order: name, description, type, then any extras.
    """
    key_order = ["name", "description", "type"]
    ordered_keys = [k for k in key_order if k in fm] + [
        k for k in fm if k not in key_order
    ]
    lines = ["---"]
    for k in ordered_keys:
        lines.append(f"{k}: {fm[k]}")
    lines.append("---")
    lines.append("")
    out = "\n".join(lines) + "\n"
    if body:
        out += body
        if not out.endswith("\n"):
            out += "\n"
    return out


# ---------------------------------------------------------------------------
# Atomic write
# ---------------------------------------------------------------------------


def atomic_write(path: Path, content: str) -> None:
    """Write via `<path>.tmp` + fsync + rename. Avoids torn writes if the
    process dies mid-write."""
    tmp = path.with_suffix(path.suffix + ".tmp")
    # newline="" so the exact content bytes hit disk; we manage line endings
    # ourselves.
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(content)
        f.flush()
        try:
            os.fsync(f.fileno())
        except OSError:
            # Some filesystems (network mounts) reject fsync; the rename is
            # still the atomicity guarantee on Windows/Linux.
            pass
    os.replace(tmp, path)


# ---------------------------------------------------------------------------
# MEMORY.md index manipulation
# ---------------------------------------------------------------------------


def index_path() -> Path:
    return memory_dir() / MEMORY_INDEX


def format_index_line(title: str, filename: str, description: str) -> str:
    return f"- [{title}]({filename}) \u2014 {description}"


def read_index() -> list[str]:
    p = index_path()
    if not p.exists():
        return []
    return p.read_text(encoding="utf-8").splitlines()


def write_index(lines: list[str]) -> None:
    # Ensure trailing newline on the file as a whole.
    text = "\n".join(lines)
    if not text.endswith("\n"):
        text += "\n"
    atomic_write(index_path(), text)


def parse_index_line(line: str) -> dict[str, str] | None:
    m = INDEX_LINE_RE.match(line)
    if not m:
        return None
    return {
        "title": m.group("title"),
        "file": m.group("file"),
        "description": m.group("desc"),
        "raw": line,
    }


def find_index_line(filename: str) -> tuple[int, dict[str, str]] | None:
    for i, line in enumerate(read_index()):
        parsed = parse_index_line(line)
        if parsed and parsed["file"] == filename:
            return i, parsed
    return None


def append_index_line(title: str, filename: str, description: str) -> str:
    lines = read_index()
    new = format_index_line(title, filename, description)
    # Drop any trailing blank lines so we append contiguously, then re-add
    # a single trailing newline via write_index.
    while lines and lines[-1].strip() == "":
        lines.pop()
    lines.append(new)
    write_index(lines)
    return new


def replace_index_line(filename: str, new_line: str) -> str | None:
    lines = read_index()
    for i, line in enumerate(lines):
        parsed = parse_index_line(line)
        if parsed and parsed["file"] == filename:
            old = lines[i]
            lines[i] = new_line
            write_index(lines)
            return old
    return None


def remove_index_line(filename: str) -> str | None:
    lines = read_index()
    for i, line in enumerate(lines):
        parsed = parse_index_line(line)
        if parsed and parsed["file"] == filename:
            old = lines.pop(i)
            write_index(lines)
            return old
    return None


# ---------------------------------------------------------------------------
# Tool handlers
# ---------------------------------------------------------------------------


def _memory_path(name: str) -> Path:
    if not NAME_RE.match(name):
        raise ValueError(f"invalid name {name!r}: must be [a-zA-Z0-9_-]+")
    return memory_dir() / f"{name}.md"


def _list_memories() -> list[dict[str, Any]]:
    d = memory_dir()
    if not d.exists():
        return []
    out = []
    for p in sorted(d.iterdir()):
        if not p.is_file() or p.suffix != ".md" or p.name == MEMORY_INDEX:
            continue
        try:
            mem = parse_memory(p.read_text(encoding="utf-8"))
        except ValueError as e:
            out.append(
                {
                    "name": p.stem,
                    "type": "unknown",
                    "description": f"(unparseable frontmatter: {e})",
                    "preview": "",
                }
            )
            continue
        body_flat = mem.body.strip().replace("\n", " ")
        preview = body_flat[:120]
        out.append(
            {
                "name": mem.frontmatter.get("name", p.stem),
                "filename": p.name,
                "type": mem.frontmatter.get("type", "unknown"),
                "description": mem.frontmatter.get("description", ""),
                "preview": preview,
            }
        )
    type_order = {t: i for i, t in enumerate(VALID_TYPES)}
    out.sort(key=lambda x: (type_order.get(x["type"], 99), x["name"]))
    return out


def _read_memory(name: str) -> dict[str, Any]:
    p = _memory_path(name)
    if not p.exists():
        raise FileNotFoundError(f"no memory {name!r} at {p}")
    mem = parse_memory(p.read_text(encoding="utf-8"))
    return {"frontmatter": mem.frontmatter, "body": mem.body}


def _add_memory(args: dict[str, Any]) -> dict[str, Any]:
    mtype = args["type"]
    name = args["name"]
    description = args["description"]
    body = args["body"]
    if mtype not in VALID_TYPES:
        raise ValueError(f"invalid type {mtype!r}; must be one of {VALID_TYPES}")
    p = _memory_path(name)
    if p.exists():
        raise FileExistsError(f"memory {name!r} already exists at {p}")
    p.parent.mkdir(parents=True, exist_ok=True)
    fm = {"name": name, "description": description, "type": mtype}
    atomic_write(p, serialise_memory(fm, body))
    # Title for the index line: prefer the first non-blank body line if it's
    # a markdown heading, else fall back to the name. The existing MEMORY.md
    # entries use a human-readable title, not the filename stem — let the
    # caller pass that via `description` is wrong; we use `name` as the title
    # by default. If the caller wants a different title they can hand-edit.
    title = name
    index_line = append_index_line(title, p.name, description)
    return {"path": str(p), "index_line": index_line}


def _update_memory(args: dict[str, Any]) -> dict[str, Any]:
    name = args["name"]
    p = _memory_path(name)
    if not p.exists():
        raise FileNotFoundError(f"no memory {name!r} at {p}")
    mem = parse_memory(p.read_text(encoding="utf-8"))
    changed: list[str] = []
    new_desc = args.get("description")
    new_body = args.get("body")
    if new_desc is not None and new_desc != mem.frontmatter.get("description"):
        mem.frontmatter["description"] = new_desc
        changed.append("description")
    if new_body is not None and new_body != mem.body:
        mem.body = new_body
        changed.append("body")
    if not changed:
        return {"changed_fields": []}
    atomic_write(p, serialise_memory(mem.frontmatter, mem.body))
    if "description" in changed:
        # Resync the index line, keeping whatever title the user has
        # hand-edited rather than clobbering it with `name`.
        hit = find_index_line(p.name)
        if hit is not None:
            _i, parsed = hit
            new_line = format_index_line(parsed["title"], p.name, new_desc)
            replace_index_line(p.name, new_line)
    return {"changed_fields": changed}


def _remove_memory(args: dict[str, Any]) -> dict[str, Any]:
    name = args["name"]
    p = _memory_path(name)
    if not p.exists():
        raise FileNotFoundError(f"no memory {name!r} at {p}")
    removed_line = remove_index_line(p.name)
    p.unlink()
    return {
        "removed_path": str(p),
        "removed_index_line": removed_line,
    }


def _index_sanity() -> dict[str, Any]:
    d = memory_dir()
    on_disk: set[str] = set()
    on_disk_descriptions: dict[str, str] = {}
    if d.exists():
        for p in d.iterdir():
            if not p.is_file() or p.suffix != ".md" or p.name == MEMORY_INDEX:
                continue
            on_disk.add(p.name)
            try:
                mem = parse_memory(p.read_text(encoding="utf-8"))
                on_disk_descriptions[p.name] = mem.frontmatter.get("description", "")
            except ValueError:
                on_disk_descriptions[p.name] = ""
    indexed: dict[str, dict[str, str]] = {}
    orphan_index_lines: list[str] = []
    for line in read_index():
        parsed = parse_index_line(line)
        if not parsed:
            continue
        fname = parsed["file"]
        indexed[fname] = parsed
        if fname not in on_disk:
            orphan_index_lines.append(parsed["raw"])
    orphan_files = sorted(on_disk - set(indexed.keys()))
    description_drift = []
    for fname, parsed in indexed.items():
        if fname not in on_disk_descriptions:
            continue
        disk_desc = on_disk_descriptions[fname]
        idx_desc = parsed["description"]
        if disk_desc and idx_desc and disk_desc.strip() != idx_desc.strip():
            description_drift.append(
                {
                    "file": fname,
                    "index_description": idx_desc,
                    "frontmatter_description": disk_desc,
                }
            )
    return {
        "memory_dir": str(d),
        "orphan_files": orphan_files,
        "orphan_index_lines": orphan_index_lines,
        "description_drift": description_drift,
    }


# ---------------------------------------------------------------------------
# MCP wire-up
# ---------------------------------------------------------------------------


@app.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="list",
            description=(
                "Enumerate all memory files with parsed frontmatter and a "
                "120-char body preview. Ordered by type (user, feedback, "
                "project, reference) then name."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        ),
        Tool(
            name="read",
            description="Read one memory, parsed into {frontmatter, body}.",
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Memory stem (filename without .md)",
                    },
                },
                "required": ["name"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="add",
            description=(
                "Create a new memory. Writes <name>.md with frontmatter, "
                "appends an index line to MEMORY.md. Refuses duplicates."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "type": {"type": "string", "enum": list(VALID_TYPES)},
                    "name": {
                        "type": "string",
                        "description": "Filename stem; [a-zA-Z0-9_-]+",
                    },
                    "description": {"type": "string"},
                    "body": {"type": "string"},
                },
                "required": ["type", "name", "description", "body"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="update",
            description=(
                "In-place edit. Pass description and/or body. Type and name "
                "are immutable here — use remove + add if the type changes. "
                "Resyncs the MEMORY.md description if it changed."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "description": {"type": "string"},
                    "body": {"type": "string"},
                },
                "required": ["name"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="remove",
            description="Delete the memory file and its MEMORY.md index line.",
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                },
                "required": ["name"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="index_sanity",
            description=(
                "Scan the memory dir vs MEMORY.md for orphan files, orphan "
                "index lines, and description drift between frontmatter and "
                "the index."
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
        if name == "list":
            result: Any = _list_memories()
        elif name == "read":
            result = _read_memory(arguments["name"])
        elif name == "add":
            result = _add_memory(arguments)
        elif name == "update":
            result = _update_memory(arguments)
        elif name == "remove":
            result = _remove_memory(arguments)
        elif name == "index_sanity":
            result = _index_sanity()
        else:
            raise ValueError(f"unknown tool: {name}")
        return [TextContent(type="text", text=json.dumps(result, indent=2))]
    except (FileNotFoundError, FileExistsError, ValueError) as e:
        log.warning("%s: %s", name, e)
        return [
            TextContent(
                type="text",
                text=json.dumps({"error": f"{type(e).__name__}: {e}"}),
            )
        ]
    except Exception as e:
        log.exception("unexpected error in %s", name)
        return [
            TextContent(
                type="text",
                text=json.dumps({"error": f"{type(e).__name__}: {e}"}),
            )
        ]


async def main() -> None:
    async with stdio_server() as (read, write):
        await app.run(read, write, app.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
