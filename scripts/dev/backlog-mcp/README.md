# unidrive-backlog MCP

Structured-tool wrapper around [`scripts/dev/backlog.py`](../backlog.py)
so a Claude Code session can file, close, look up, and audit backlog
tickets with typed arguments instead of argparse string munging. Plus a
new `audit()` tool the CLI can't easily deliver. Ticket: UD-716.

## How it relates to `backlog.py`

- `server.py` imports `backlog` directly (sibling `scripts/dev/`
  directory is added to `sys.path`). Zero logic duplication — the MCP
  is a thin typed-args shim on top of `backlog.next_free_id`,
  `backlog.find_block`, `backlog.file_ticket_impl`,
  `backlog.close_ticket_impl`.
- The CLI (`python scripts/dev/backlog.py ...`) and the MCP produce
  byte-identical changes to `docs/backlog/{BACKLOG,CLOSED}.md`.
- `audit()` is new and MCP-only; it was cheap to deliver here because
  the block-parser is shared with `backlog.find_block`.

## Tools

- `next_id(category)` → `{ id }`. Categories: architecture, security,
  core, providers, shellwin/shell-win, ui, protocol, tooling, tests,
  experimental.
- `show(id)` → `{ found_in: "BACKLOG"|"CLOSED", block }`.
- `file(id, title, category, priority, effort, code_refs[], body)` →
  `{ id, path }`. Validates id is unused,
  priority ∈ {low,medium,high,critical}, effort ∈ {XS,S,M,L,XL}.
- `close(id, commit, note)` → `{ moved_to: "CLOSED.md", resolved_by }`.
- `audit()` → `{ duplicate_ids, missing_fields, broken_cross_refs,
  stale_code_refs, orphan_resolved_refs }`. Read-only drift report:
  - `duplicate_ids`: same UD-### appearing in both files or twice in
    one.
  - `missing_fields`: frontmatter blocks missing any of title,
    category, priority, effort, status, opened.
  - `broken_cross_refs`: `Related: UD-X` / `Depends on UD-X` /
    `blocked_by: UD-X` pointing at a ticket that isn't known.
  - `stale_code_refs`: `code_refs:` paths that don't exist in the
    working tree (glob-expanded; `:line` suffixes stripped).
  - `orphan_resolved_refs`: `resolved_by: commit <sha>` where
    `git cat-file -e <sha>` fails — usually an amend/rebase left the
    ticket pointing at a rewritten hash.

## Setup (one-time, on the user's machine)

1. Install dependencies into a dedicated venv:
   ```powershell
   cd scripts\dev\backlog-mcp
   py -m venv .venv
   .\.venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

2. Sanity-check the parse + audit flow end-to-end:
   ```powershell
   python .\smoke.py
   ```
   Expected output ends with `OK`. If a FAIL, read the tool name — it
   points at the broken handler.

3. Register the MCP in your Claude Code config. Add to
   `~/.claude/claude_desktop_config.json` (or the equivalent Claude
   Code settings file):
   ```json
   {
     "mcpServers": {
       "unidrive-backlog": {
         "command": "C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\backlog-mcp\\.venv\\Scripts\\python.exe",
         "args": ["C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\backlog-mcp\\server.py"]
       }
     }
   }
   ```
   Restart Claude Code. Tools appear as `mcp__unidrive-backlog__next_id`,
   `mcp__unidrive-backlog__show`, `mcp__unidrive-backlog__file`,
   `mcp__unidrive-backlog__close`, `mcp__unidrive-backlog__audit`.

## Usage from inside an agent session

```
next_id(category="tooling")
→ { "id": "UD-730" }

show(id="UD-716")
→ { "found_in": "BACKLOG",
    "block": "---\nid: UD-716\ntitle: ...\n---\n..." }

file(id="UD-730", title="...", category="tooling",
     priority="medium", effort="S",
     code_refs=["scripts/dev/backlog-mcp/"],
     body="## Context\n...")
→ { "id": "UD-730", "path": ".../docs/backlog/BACKLOG.md" }

close(id="UD-730", commit="abc1234",
      note="shipped in <sha>, verified via smoke.py")
→ { "moved_to": "CLOSED.md",
    "resolved_by": "commit abc1234. shipped in <sha>..." }

audit()
→ { "duplicate_ids": [],
    "missing_fields": [],
    "broken_cross_refs": [{"id": "UD-204", "file": "CLOSED", "missing_ref": "UD-999"}],
    "stale_code_refs": [...],
    "orphan_resolved_refs": [...] }
```

## What this deliberately does NOT do

- **No auto-commit.** `file()` and `close()` write to the markdown
  files; the agent is responsible for staging and committing the
  result. Keeps commit messages the agent's responsibility.
- **No GitHub issue sync.** This repo uses the markdown files as the
  source of truth; bridging to Issues is a different ticket.
- **No `backlog.py` CLI replacement.** The CLI stays — the MCP is a
  parallel interface for structured-tool consumers.

## Known limitations / open work

- **`audit()` commit reachability** uses `git cat-file -e <sha>`, not
  `git merge-base --is-ancestor`. A commit on any branch still
  counts as "reachable"; a rebased-away commit does not. This matches
  the typical failure mode (amend/force-push rewrote the hash) but
  misses the subtler "landed on a feature branch that got abandoned"
  case. Good enough for v1.
- **`stale_code_refs` is path-exists-only.** It does not check that
  the `:line` suffix still matches the current file content — the
  line-tolerance contract described in UD-716 is deferred to a
  follow-up.
- **Non-atomic writes.** `file()` and `close()` use the same
  read-modify-write pattern as the CLI; a concurrent `backlog.py` run
  could interleave. In practice both are agent-driven and serial.

See also: [`scripts/dev/oauth-mcp/README.md`](../oauth-mcp/README.md)
for the template this server follows, and
[`docs/dev/TOOLS.md`](../../../docs/dev/TOOLS.md) for how the
underlying `backlog.py` CLI fits into the broader tooling.
