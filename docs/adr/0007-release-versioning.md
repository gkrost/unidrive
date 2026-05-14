---
id: ADR-0007
title: Release & versioning — monorepo SemVer, tier sub-tags optional
status: accepted
date: 2026-04-17
---

## Context

With three tiers now in one repo ([ADR-0001](0001-monorepo-layout.md)), a versioning policy is needed. Options range from fully decoupled per-tier versions to a single monolithic tag.

## Decision

- **Primary version** is a single monorepo SemVer: `vMAJOR.MINOR.PATCH` (e.g., `v0.1.0`, `v1.2.3`).
- **Scope per release** is declared in `docs/CHANGELOG.md` — not every tier needs to change each release.
- **Component sub-tags** are permitted as additional tags on the same commit when a tier needs an independently-citable build (e.g., `core-v0.1.0`, `mcp-v0.1.0`). They are *not* primary and must match the monorepo tag's commit. (The original second example here was `shell-win-v0.3.0`; the shell-win tier was retired by [ADR-0011](0011-shell-win-removal.md), so this ADR was updated 2026-05-03 under UD-771 to cite a surviving tier.)
- **Pre-release identifiers** allowed: `v0.1.0-mvp`, `v0.2.0-rc.1`.
- **Build metadata** after `+` (e.g., `v0.1.0+sha.abc123`) permitted in artifact names but not in tags.

## Consequences

### Positive
- Single source of truth for "what version is this installation".
- Atomic cross-tier releases stay atomic.
- Sub-tags preserve per-tier citation without fragmenting version space.

### Negative / trade-offs
- A tier-only change still bumps the monorepo version. Accepted: keeps human mental model simple ("I'm on v0.4.2").

## Alternatives considered

- **Per-tier independent versions** — rejected: loses atomic coupling; users must track three versions that must pair correctly.
- **CalVer** — rejected: doesn't communicate breakingness; SemVer wins for a library + CLI combo.

## Related

- Backlog: [UD-701](../backlog/BACKLOG.md#ud-701) (pick host → finalize release workflow)
- Changelog policy: [../CHANGELOG.md](../CHANGELOG.md)
