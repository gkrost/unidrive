---
id: ADR-0002
title: Documentation & backlog single source of truth
status: accepted
date: 2026-04-17
---

## Context

Prior state: docs were scattered (and then deleted). No shared backlog. In-code `TODO` / `FIXME` comments drifted without a mechanism to reconcile them against planned work. Agents had no stable references to cite.

## Decision

All cross-cutting artifacts live under `docs/` at the monorepo root:

- `docs/ARCHITECTURE.md`, `docs/SECURITY.md`, `docs/CHANGELOG.md`, `docs/AGENT-SYNC.md`
- `docs/adr/` — numbered ADRs
- `docs/backlog/BACKLOG.md` + `CLOSED.md` — stable `UD-###` IDs, frontmatter per item
- Code references backlog items with inline comments: `// UD-123: <short rationale>`.
- `scripts/backlog-sync.kts` is the canonical reconciliation tool.

Per-tier READMEs may exist but delegate deeper topics to `docs/`.

## Consequences

### Positive
- One place to look.
- Agent-safe editing rules codified in [AGENT-SYNC.md](../AGENT-SYNC.md).
- Backlog/code drift is mechanically detectable.
- Changelog entries tie to backlog IDs, giving release notes automatic traceability.

### Negative / trade-offs
- Contributors touching only one tier still navigate to `docs/` for cross-cutting topics. Mitigated by README pointers.

## Alternatives considered

- **Per-tier docs** — rejected: duplication, divergence, poor agent ergonomics.
- **External tool (Linear, Notion, Jira)** — rejected for MVP: adds auth dependencies and prevents `grep` reconciliation.

## Related

- Backlog: [UD-001](../backlog/BACKLOG.md)
- Related: [ADR-0001](0001-monorepo-layout.md), [AGENT-SYNC.md](../AGENT-SYNC.md)
