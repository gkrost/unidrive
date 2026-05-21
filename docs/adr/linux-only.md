# Linux-only

> **Status: Superseded by [`multi-platform.md`](multi-platform.md).** The Linux daemon remains the current ship-target, but the conclusion that Windows and macOS are permanently out of scope no longer holds — Windows desktop and Linux UI are now declared platform surfaces. Body kept as historical record.

## Context

Earlier iterations carried a Windows shell-extension tier (`shell-win/`), a UI tier (`ui/`), and a `protocol/` directory of JSON-schemas + golden NDJSON fixtures designed to be the contract between the daemon and a multi-client world. The Windows extension was abandoned; the UI was abandoned; the schema directory had no consumer because the surviving client (the CLI's IPC reporter) emits NDJSON inline over a Unix-domain socket.

Maintaining the Windows-only IPC code path, the schema directory, and a Windows CI runner cost time that didn't buy anything for the shipping surface.

## Decision

**The shipping target is Linux.** Windows and macOS are out of scope.

Removed entirely from the tree:

1. `protocol/` — JSON-Schema files, golden NDJSON fixtures, `INDEX.md`.
2. `core/app/sync/NamedPipeServer.kt` — Windows named-pipe server, bailed as a no-op on non-Windows JVMs.
3. `core/app/sync/PipeSecurity.kt` — SDDL helper for the named pipe.
4. `core/app/sync/NdjsonValidator.kt` — JSON-Schema validator that loaded files from `protocol/schema/v1/` at runtime.
5. The dependent test classes.

What stays:

- `core/app/sync/IpcServer.kt` — UDS event-broadcast server. Takes only `socketPath: Path`.
- `core/app/sync/IpcProgressReporter.kt` — emits NDJSON events over UDS to whatever client connects.
- The CLI on non-Linux JVMs still builds. Linux is the **target**, not the only platform that compiles.

## Consequences

- Smaller blast radius for releases. CI matrix is one Linux runner.
- No multi-client formal contract. If a second daemon client ever joins, a code-co-located DTO module (kotlinx-serialization data classes) probably beats reintroducing a schema directory.
- Windows / macOS users can still build and run, but no support claim.

## Re-opening criteria

1. A second daemon client joins (FUSE shell tier, a web bridge, a non-MCP automation surface).
2. A specific paying customer or partner whose use-case requires a non-Linux platform.
3. A community contributor proposes to own ongoing maintenance for a non-Linux platform tier.
