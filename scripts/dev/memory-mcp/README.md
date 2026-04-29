# unidrive-memory MCP

Structured CRUD over Claude Code's per-project agent-memory directory
(typically under `~/.claude/projects/<repo-encoded-path>/memory/`).
Replaces four manual steps (look up frontmatter shape, write the file,
remember to append to `MEMORY.md`, get formatting right) with one
tool call. Also exposes `index_sanity` — a drift audit that flags
orphan files, stale index lines, and description mismatches.

**Design context:** UD-721.

## What this is for

- **Write path.** Adding / updating / removing memory files without
  hand-editing frontmatter or the `MEMORY.md` index.
- **Audit path.** `index_sanity()` catches the drift modes that
  accumulate silently today: a file was renamed but the index wasn't;
  a description was edited in-place but the index still shows the old
  text; a file was deleted but its index line lingers.

Complements the `anthropic-skills:consolidate-memory` skill: that skill
is the batch-cleanup reflective pass; this MCP is the per-operation
write path.

## Memory file format

```
---
name: <name>
description: <one-line description>
type: user | feedback | project | reference
---

<markdown body>
```

## Setup (one-time, on the user's machine)

1. Install dependencies into a dedicated venv:
   ```powershell
   cd scripts\dev\memory-mcp
   py -m venv .venv
   .\.venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

2. Sanity-check the CRUD logic end-to-end (uses a tempdir, never the
   real memory dir):
   ```powershell
   python .\smoke.py
   ```
   Expected output ends with `OK — all assertions passed`.

3. Register the MCP in your Claude Code config. Add to
   `~/.claude/claude_desktop_config.json` (or the equivalent Claude
   Code settings file):
   ```json
   {
     "mcpServers": {
       "unidrive-memory": {
         "command": "<repo-root>/scripts/dev/memory-mcp/.venv/Scripts/python.exe",
         "args": ["<repo-root>/scripts/dev/memory-mcp/server.py"]
       }
     }
   }
   ```
   Restart Claude Code. The tools will appear as
   `mcp__unidrive-memory__list`, `mcp__unidrive-memory__read`, etc.

### Memory-dir override

By default the server derives the memory dir from the current working
directory using Claude Code's path-encoding rules (the repo's absolute
path with `:`, `\`, `/` replaced by `-`, prefixed with `C--`, under
`~/.claude/projects/`). If you want to point at a different memory
dir — a sibling checkout, a test fixture, or an MSIX-sandbox-virtualised
path — set `UNIDRIVE_MEMORY_DIR`:

```json
{
  "mcpServers": {
    "unidrive-memory": {
      "command": "<repo-root>/.../python.exe",
      "args": ["<repo-root>/scripts/dev/memory-mcp/server.py"],
      "env": { "UNIDRIVE_MEMORY_DIR": "/path/to/alt/memory/dir" }
    }
  }
}
```

## Tools

| Tool            | Purpose                                                           |
| --------------- | ----------------------------------------------------------------- |
| `list`          | Enumerate memories with frontmatter + 120-char body preview.      |
| `read`          | Return one memory parsed into `{frontmatter, body}`.              |
| `add`           | Create a new memory + append index line. Refuses duplicates.     |
| `update`        | In-place edit of description / body. Resyncs index on desc edit. |
| `remove`        | Delete memory file + strip its index line.                        |
| `index_sanity`  | Audit: orphan files, orphan index lines, description drift.       |

### Example

```
list()
→ [ { "name": "user_profile", "filename": "user_profile.md",
      "type": "user", "description": "Gernot Krost, …",
      "preview": "…" }, … ]

add(type="feedback", name="test_foo",
    description="one-line summary",
    body="# Body\n\nMarkdown.\n")
→ { "path": "…\\test_foo.md",
    "index_line": "- [test_foo](test_foo.md) — one-line summary" }

index_sanity()
→ { "orphan_files": [],
    "orphan_index_lines": [],
    "description_drift": [] }
```

## Design notes

- **No YAML library.** Frontmatter is strict `key: value` lines between
  `---` fences. Hand-parsed. Keeps the dependency footprint to just
  `mcp`.
- **Atomic writes.** All writes go through `<name>.md.tmp` + `fsync` +
  `os.replace`. A mid-write crash leaves the old file intact rather
  than a half-written one.
- **Index never blindly rewritten.** `MEMORY.md` is treated as a set of
  lines; only the line matching a given filename is replaced / removed.
  Any header / trailing commentary in the file is preserved.
- **Type is immutable on update.** The ticket spec rejects `update()`
  changing `type`: if type changes the memory is fundamentally
  different — `remove` then `add` instead. `name` is also immutable —
  a rename is `remove` + `add`.

## What this deliberately does NOT do

- **No cross-project memory.** Scoped to this repo's memory dir. A
  second unidrive clone would need its own MCP instance.
- **No rename operation.** Do `remove` + `add` — if the name changes,
  so should the index line + filename + history you'd want to capture.
- **No soft-delete.** `remove` is a hard unlink. The ticket argues
  memory hygiene prefers pruning over retention.
- **No batch / migration tools.** `consolidate-memory` (skill) is the
  batch path; this MCP is per-operation.

See also: `docs/dev/TOOLS.md`,
`~/.claude/projects/.../memory/MEMORY.md`.
