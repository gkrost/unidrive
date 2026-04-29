---
id: ADR-0001
title: Monorepo layout
status: accepted
date: 2026-04-17
superseded_in_part_by: ADR-0011, ADR-0012, ADR-0013
---

## Context

UniDrive existed as three sibling repos (`unidrive/`, `unidrive-shell/`, `unidrive-ui/`) forming one product with shared NDJSON contracts. After a backup restore, `.git` and all docs were lost; the trees diverged slightly. Cross-tier changes (e.g. adjusting the pipe protocol) required synchronous edits across three repos with no atomic guarantee, and there was no shared home for architecture, security, or backlog.

## Decision

Consolidate all tiers into a single monorepo **`unidrive`** with the following top-level layout:

- `core/` — Kotlin daemon (was `unidrive/`)
- `ui/` — Kotlin Swing tray (was `unidrive-ui/`)
- `shell-win/` — C++ Windows shell ext DLL (was `unidrive-shell/`)
- `protocol/` — new, code-free IPC contract
- `docs/` — new, single source of truth
- `scripts/` — new, cross-cutting tooling

The original sibling dirs remained on the maintainer's local machine as archived reference; they do not exist in this repository and are no longer relevant after the v0.0.1 amend that incorporated [ADR-0013](0013-ui-removal.md).

## Consequences

### Positive
- Atomic cross-tier commits (pipe protocol + core + shell in one PR).
- Single release tag; one version source of truth.
- Shared `docs/` with one backlog, one changelog, one ADR history.
- Agents can reason about the whole product in one context.

### Negative / trade-offs
- Tooling must accommodate two build systems at the root (Gradle composite + CMake). Accepted because the tiers are genuinely different ecosystems.
- Larger checkout for contributors who only touch one tier. Mitigated by sparse-checkout if it becomes painful.

### Neutral
- Subdirectory names drop the `unidrive-` prefix (`core`, `ui`, `shell-win`) because the monorepo name already supplies that context.

## Alternatives considered

- **Meta-repo with Git submodules** — rejected: breaks atomic cross-tier commits and is hostile to agents that have to juggle three SHAs.
- **Keep three repos with stricter protocol versioning** — rejected: coordination cost scales worse than monorepo overhead, and the drift after the backup restore proves the point.

## Related

- Backlog: [UD-001](../backlog/BACKLOG.md#ud-001)
- Supersedes: —
