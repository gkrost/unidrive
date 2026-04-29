---
id: ADR-0003
title: IPC unification — one schema, per-OS transport
status: superseded
date: 2026-04-17
superseded_by: ADR-0012
---

## Context

The imported trees show three different local-IPC stacks on the daemon:

| Caller | Transport | Codec |
|--------|-----------|-------|
| `ui/` (`IpcClient.kt`) | Unix-domain socket, per-profile `unidrive-*.sock`, glob-discovered | NDJSON |
| `shell-win/` (`PipeClient.cpp`) | Named pipe `\\.\pipe\unidrive-state` | NDJSON |
| Core's `NamedPipeServer.kt` | Stubbed; hydration TODOs at lines 100–110 | NDJSON |

Grammar is the same; transport and discovery diverge; there is no schema validation, no shared fixtures, no contract test. The named pipe opens with a NULL security descriptor — local privilege escalation risk if the daemon is ever elevated.

**Note:** `NamedPipeServer.kt`, `PipeSecurity.kt`, and the `protocol/` directory were subsequently removed per [ADR-0012](0012-linux-mvp-protocol-removal.md). The current IPC surface is UDS only (`IpcServer.kt` / `IpcProgressReporter.kt`).

MCP (`core/app/mcp`) is a separate surface (JSON-RPC over stdio for model clients) and stays out of scope.

## Decision

1. Extract the NDJSON grammar into **`protocol/schema/v1/`** (JSON-Schema) and **`protocol/fixtures/`** (golden NDJSON samples). Both tiers load these at build / test time.
2. **Transport is OS-selected**, not caller-selected: on Windows the daemon opens a named pipe; on POSIX a Unix-domain socket. UI and shell on the same host talk to the same server endpoint.
3. **Version the schema** via `envelope.version: "1"`. Peers validate before dispatch and reject on mismatch (UD-105).
4. **Secure the transport**:
   - Named pipe: SDDL restricting to the current user SID (UD-101).
   - UDS: mode `0600`, path under `$XDG_RUNTIME_DIR` or fallback `/tmp/unidrive/` with sticky bit.
5. **Bound the frame**: max 64 KiB, per-second rate limit (UD-106).

## Consequences

### Positive
- One shared contract. Fixture-based contract tests catch drift at CI time (UD-802).
- Eliminates the LPE risk at v0.1.0 baseline.
- MCP stays decoupled; nothing forces UI to adopt it.

### Negative / trade-offs
- Migration work: the Unix-socket discovery convention in the UI must be replaced by a per-OS discovery (UD-501, deferred to v0.2.0).

### Neutral
- The schema version bump policy is SemVer on the major: v1 → v2 requires a new directory `protocol/schema/v2/`.

## Alternatives considered

- **gRPC** — rejected for MVP: adds protobuf toolchain to C++ and Kotlin; NDJSON already works on both.
- **MCP everywhere** — rejected: MCP is designed for model-facing APIs, not high-frequency shell-to-daemon callbacks; JSON-RPC framing overhead is unnecessary.
- **Keep three stacks, add schema validation only** — rejected: leaves discovery divergence and pipe-ACL gap.

## Related

- Backlog: [UD-101](../backlog/BACKLOG.md#ud-101), [UD-105](../backlog/BACKLOG.md#ud-105), [UD-106](../backlog/BACKLOG.md#ud-106), [UD-201](../backlog/BACKLOG.md#ud-201), [UD-401](../backlog/BACKLOG.md#ud-401), [UD-601](../backlog/BACKLOG.md#ud-601)
- Related: [ADR-0004](0004-security-baseline.md)
