# AGENT-SYNC — how agents (and humans) maintain backlog/code alignment

This file is the contract between contributors (humans and LLM agents) and the two source-of-truth artifacts:

- [BACKLOG.md](backlog/BACKLOG.md) — open work, frontmatter per item.
- [CLOSED.md](backlog/CLOSED.md) — append-only archive of resolved items.

It also governs [CHANGELOG.md](CHANGELOG.md) and in-code `UD-###` references.

## The `UD-###` contract

- IDs live forever. Once allocated, an ID is **never renumbered** and **never reused**.
- IDs are 3-digit, zero-padded: `UD-001` — `UD-999`.
- Categories are encoded in the ID range (see below); a new ID is taken from the next free slot **in its category range**. Do not grab the next free global number.
- Every open item has `status: open | in-progress | blocked`. Closed items move to `CLOSED.md` with `status: closed`.

### ID ranges

| Range | Category |
|-------|----------|
| UD-001..099 | Architecture / structural |
| UD-100..199 | Security |
| UD-200..299 | Core daemon |
| UD-300..399 | Providers |
| UD-400..499 | `cli` — CLI surface (subcommands, rendering, output formatting). Rebound 2026-05-01 from the retired `shell-win` tier ([ADR-0011](adr/0011-shell-win-removal.md) + [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)); UD-401..403 stay in CLOSED.md with `category: shell-win` as historical record. |
| UD-500..599 | Reserved (was UI / tray; removed via [ADR-0013](adr/0013-ui-removal.md)) |
| UD-600..699 | Reserved (was `protocol/` IPC contract; directory removed via [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)) |
| UD-700..799 | Tooling / CI / docs |
| UD-800..899 | Tests |
| UD-900..999 | Reserved / experimental |

### When a range fills up

A category caps at 100 IDs (`core` saturated late April 2026 — UD-200..299 fully allocated). The escalation ladder, from least to most invasive:

1. **Letter suffixes** (`UD-101a`, `UD-205b` — already permitted by the regex `\d{3}[a-z]?`) for follow-up tickets that share the parent's scope. `next-id` doesn't auto-allocate suffixes; type them by hand.
2. **Repurpose retired ranges.** UD-400..499 was rebound from `shell-win` → `cli` on 2026-05-01 after [ADR-0011](adr/0011-shell-win-removal.md) retired the original tier. UD-500..599 (`ui`, retired by [ADR-0013](adr/0013-ui-removal.md)) and UD-600..699 (`protocol`, retired by [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)) are similarly available for rebinding when the need arises.
3. **Widen to 4 digits.** Update the regex `\d{3}` → `\d{3,4}` in `scripts/dev/backlog.py` and the format string `:03d` → variable; bump category ranges 10× (e.g. `core 2000..2999`). Backwards-compatible — UD-101 still parses. One-commit migration when needed; no rewrite of historical IDs.

Do **not** recycle closed IDs. The "IDs live forever" line above is load-bearing — git history, code comments (`// UD-205: ...`), and ADRs reference IDs forever. A recycled ID would point at a different change in different parts of the tree.

## BACKLOG.md entry format

Each entry is a fenced frontmatter block followed by Markdown prose. The block is parsed by [scripts/backlog-sync.kts](../scripts/backlog-sync.kts).

```markdown
---
id: UD-101
title: Enforce SDDL on unidrive-state named pipe
category: security
priority: high       # low | medium | high | critical
effort: M            # S | M | L | XL
status: open         # open | in-progress | blocked | closed
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/PipeSecurity.kt
adr_refs: [ADR-0004]
opened: 2026-04-17
---
Rationale. Acceptance criteria. Links to related items.
```

**Required fields:** `id`, `title`, `category`, `priority`, `effort`, `status`, `opened`.

**Optional fields:** `code_refs` (path or `path:line`), `adr_refs`, `blocked_by`, `depends_on`, `milestone` (e.g. `v0.1.0`).

Line numbers in `code_refs` are best-effort; `backlog-sync.kts` tolerates drift within ±10 lines when validating.

## Closing an item

1. In `BACKLOG.md`, **remove the block** from its position.
2. In `CLOSED.md`, **append** the same block with two added fields:
   - `status: closed`
   - `closed: YYYY-MM-DD`
   - Optional: `resolved_by: <commit-sha | PR-url | note>`
3. In `CHANGELOG.md` under `[Unreleased]`, add a bullet referencing the ID.
4. Remove any `// UD-xxx` comments in code that referenced the now-closed item — OR keep them if the comment documents an invariant that's still load-bearing (then move the ID to a successor item).

`CLOSED.md` is append-only. Never edit entries above the latest one.

## In-code references

When code touches or implements a backlog item, mark it inline:

```kotlin
// UD-101: pipe is SDDL-restricted to current user SID — see ADR-0004
val sd = createRestrictedSecurityDescriptor(currentUserSid())
```

```cpp
// UD-105: schema-validate every NDJSON frame before dispatch
if (!validate_ndjson_v1(frame)) return REJECT;
```

Rules:
- One `UD-###` per comment is fine; multiple (`// UD-101, UD-106:`) allowed when the same hunk satisfies several items.
- Prefer referencing items over duplicating prose — the item file is the source of truth.
- When an item closes, audit all its `// UD-xxx` comments; remove or re-tag.

## CHANGELOG conventions

- `[Unreleased]` section is editable freely.
- Once a version ships and a tag lands, its section becomes **frozen**. Do not edit released sections (typos or factual corrections excepted).
- Every bullet ends with one or more `[UD-xxx]` tags.
- Sections: **Added / Changed / Deprecated / Removed / Fixed / Security** (Keep-a-Changelog).

## Before every commit / PR

Run (once the script is in place):

```bash
kotlinc -script scripts/backlog-sync.kts
```

It will fail (non-zero exit) on:
- **Orphan code refs** — `UD-xyz` in source but no matching block in BACKLOG.md or CLOSED.md.
- **Stale closed** — ID in CLOSED.md still referenced in source.

It will warn on:
- **Anchorless open** — `code_refs` entries that resolve to non-existent files.
- **Abandoned** — `status: open`, no `code_refs`, older than 30 days since `opened`.

## Agent-safe editing rules

LLM agents (Claude included) modifying these files must:

1. **Never renumber IDs.** Even if the list looks sparse.
2. **Never rewrite CLOSED.md history.** Append only.
3. **Never change an item's `id` field.** If an item is wrong, close it and open a successor.
4. **Never edit released CHANGELOG sections.**
5. **Always prefer** updating `code_refs` to re-anchor an item over silently deleting it when a file moves.
6. When introducing a new backlog item, allocate the **next free ID in its category range** (see table above); if the range looks tight, bump to the next range only with explicit maintainer approval.
7. Run `backlog-sync.kts` locally if possible; if not, note that in the PR description.
