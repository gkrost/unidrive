---
id: ADR-0008
title: Greenfield restart at v0.1.0 — core-only MVP scope
status: accepted
date: 2026-04-17
---

## Context

The loss of `.git` and docs on the imported trees — combined with visible inconsistencies (IPC transport divergence, stubbed `NamedPipeServer`, unauthenticated pipe, ~39% test coverage, capability gaps across providers) — made a clean restart cheaper than a gradual rehabilitation. The three tiers vary in maturity: `core/` is most advanced, `ui/` is minimal and focused, `shell-win/` is functional but materially unfinished (pipe hydration not wired; no ACL; no CFG/signing).

**Note:** `NamedPipeServer.kt`, `PipeSecurity.kt`, and the `protocol/` directory were subsequently removed per [ADR-0012](0012-linux-mvp-protocol-removal.md). The current IPC surface is UDS only (`IpcServer.kt` / `IpcProgressReporter.kt`).

## Decision

- **v0.1.0 = core-only.** CLI + MCP + providers `localfs`, `s3`, `sftp`.
- **`ui/` imports at `status: preview`.** Subsequently removed entirely in [ADR-0013](0013-ui-removal.md).
- **`shell-win/` imports at `status: preview`.** Subsequently removed entirely in [ADR-0011](0011-shell-win-removal.md).
- **Provider scope beyond `localfs + s3 + sftp`** is preview in v0.1.0: OneDrive, WebDAV, HiDrive, Internxt, Rclone are present but not in the release quality gate.
- **Git host** is an explicit open question ([retained list](#open-questions)). Does not block v0.1.0.

## Consequences

### Positive
- Aggressive scope cut restores momentum after the backup loss.
- v0.1.0 can close the security baseline items (UD-105, UD-106, UD-107, UD-112) without blocking on shell hardening.
- Every tier imported intact — no lost code; only release gating changed.

### Negative / trade-offs
- "Core-only" MVP has a weaker demo story (no tray, no Explorer integration). Accepted because it ships.
- Provider preview status means `status.toml` / user-facing docs must mark these clearly or risk bug reports against unsupported paths.

## Alternatives considered

- **Skip greenfield, migrate in place and fix piecemeal** — rejected: the IPC divergence and stubbed `NamedPipeServer` are structural; piecemeal change would ship partial fixes with no coherent v0.1.0 story.
- **Keep the three repos and patch them** — rejected: see [ADR-0001](0001-monorepo-layout.md).
- **Ship all three tiers at v0.1.0** — rejected: shell-win hardening alone is a multi-week effort (UD-101, UD-102). MVP should not wait on it.

## Open questions

Retained as non-blocking but must close before certain milestones:

1. Product name — keep "UniDrive" or rename (blocks public v0.1.0 artifacts).
2. ~~Git host (pushes [UD-701](../backlog/BACKLOG.md#ud-701)).~~ **Closed by [ADR-0009](0009-ci-host.md) (2026-04-18) — GitHub Actions.**
3. Code-signing cert source (blocks [UD-103](../backlog/BACKLOG.md#ud-103), v0.3.0 gate).
4. macOS / Linux shell tiers naming commitment.
5. Telemetry sink (local-only vs opt-in remote).
6. MCP consumer for v0.2.0 UI — adopt MCP or keep UDS.
7. Publishing channel (single archive vs per-tier).
8. License — explicitly skipped this session; must land before first public tag.

## Related

- [ADR-0001](0001-monorepo-layout.md), [ADR-0004](0004-security-baseline.md), [ADR-0007](0007-release-versioning.md)
