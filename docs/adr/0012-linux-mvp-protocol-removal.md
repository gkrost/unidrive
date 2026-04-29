---
id: ADR-0012
title: Linux-only MVP and removal of the protocol/ contract directory
status: accepted
date: 2026-04-28
supersedes_in_part: ADR-0001, ADR-0003, ADR-0008
amends: ADR-0011
amended_by: ADR-0013
---

## Context

[ADR-0011](0011-shell-win-removal.md) removed the `shell-win/` Windows
Explorer extension tier on the same day v0.0.1 was first cut. With the
shell extension gone, the named-pipe transport (`NamedPipeServer.kt` +
`PipeSecurity.kt`) had no current consumer, but [ADR-0011] retained it
as the *future* v0.2.0 UI-on-Windows transport per
[ADR-0003](0003-ipc-unification.md). The same logic applied to the
[`protocol/`](#protocol-removal) tree of NDJSON schemas + fixtures: half
of them were CFAPI-specific (consumer-less), half were UI-event payloads
already emitted by `IpcProgressReporter.kt` over UDS.

That arrangement made sense if the v0.2.0 plan was to bring UI to
Windows under the named pipe. It does not make sense if Windows is no
longer the priority platform for the MVP.

## Decision

**The MVP target is Linux.** Windows and macOS are out of scope for
v0.0.x → v0.1.0. They may return in v0.2.x or later if there is concrete
demand and capacity to do them properly.

In consequence, the following are removed entirely from the tree:

1. **`protocol/`** — the directory of JSON-Schema files (`schema/v1/`),
   golden NDJSON fixtures, and `INDEX.md`. The schemas were a
   contract layer between daemon and a notional multi-client world
   (UI + shell-win). With one client (UI on Linux) co-located in the
   same monorepo and consuming events directly over UDS, the schema
   layer is over-engineered for the current scope. UI's NDJSON parsing
   stays inline; reintroducing a formal contract is cheap when a real
   second consumer materialises.
2. **`core/app/sync/NamedPipeServer.kt`** — Windows named-pipe server.
   Bailed out as a no-op on non-Windows JVMs anyway. Bound the pipe but
   had no consumer post-ADR-0011. Removed.
3. **`core/app/sync/PipeSecurity.kt`** — SDDL helper for the named pipe.
   Only consumer was `NamedPipeServer`. Removed.
4. **`core/app/sync/NdjsonValidator.kt`** — minimal JSON-Schema validator
   that loaded files from `protocol/schema/v1/` at runtime. With
   `protocol/` gone there is nothing to validate against, and the
   inline `COMMAND_SCHEMA` in `NamedPipeServer.kt` (the only frame
   validator that ever ran in production) goes with `NamedPipeServer`.
   Removed.
5. **The 5 dependent test classes** — `ProtocolContractTest.kt`,
   `NdjsonValidatorTest.kt`, `PipeSecurityTest.kt`,
   `NamedPipeServerGiveUpTest.kt`, `NamedPipeServerDispatchTest.kt`.

What stays:

- **`core/app/sync/IpcServer.kt`** — the UDS event-broadcast server. Now
  takes only `socketPath: Path`; the optional `pipeServer:
  NamedPipeServer?` parameter was dropped.
- **`core/app/sync/IpcProgressReporter.kt`** — emits NDJSON events over
  UDS to whatever client connects (the UI tray today, MCP clients
  potentially).
- The CLI on Windows still builds and runs; MCP-over-stdio still
  works; provider adapters are JVM-only and platform-agnostic. Linux
  is the **target**, not the only platform that will ever boot.

## Consequences

### Positive

- **Smaller blast radius for v0.0.1.** ~600 lines of Windows-only Kotlin
  + ~36 schema files removed. The CI matrix no longer needs a Windows
  runner.
- **Honest positioning.** The competitive brief frames UniDrive as
  "the trust-auditable Linux sync client made in Germany — what
  Boxcryptor users have been waiting on for four years." That story
  doesn't need a Windows shell extension. Removing the half-finished
  Windows infrastructure stops setting expectations the brief doesn't
  cash.
- **Cheaper v0.0.2 / v0.1.0 development.** Linux-only IPC = single
  transport = simpler tests, simpler debugging, no `os.name` branching
  in `SyncCommand.kt`.
- **Cleaner contract for the relocate-v1 spec.**
  [`docs/specs/relocate-v1.md`](../specs/relocate-v1.md) §P0-13 envisaged
  emitting four new ops as schemas under `protocol/schema/v1/`. Without
  that directory, the four ops still exist as `IpcProgressReporter`
  emissions over UDS — the spec just loses the "validate against
  schema in protocol/" line, replaced by inline JSON-shape unit tests
  in the relocate module.

### Negative / trade-offs

- **No multi-client formal contract.** If a second daemon-client ever
  joins (a future shell tier on Linux via FUSE, a future web-UI
  bridge, a third-party MCP-like surface), there is no machine-readable
  schema to validate against. Either a fresh `protocol/` is reintroduced
  at that point, or a code-co-located contract (kotlinx-serialization
  data classes published as a library module) replaces it. Either is
  cheap to do *when needed*; speculative now.
- **Windows users can still build and run, but no support claim.**
  Documentation marks Windows / macOS as "not in MVP scope; community
  best-effort." This is honest but loses the speculative "we run
  everywhere JVM 21 runs" pitch — which was never load-bearing.
- **CI simplification removes a regression net.** The Windows-runner
  matrix entry was finding real Windows-specific bugs (path separators,
  file locks, line endings). Those classes of bug can resurface in
  Linux CI only if a contributor opens a Windows issue; otherwise they
  go uncaught.
- **Re-importing `protocol/` is a non-zero migration.** Any future
  schema work has to reconstitute the directory + reseed the schemas
  + rewire the validator. Estimated at 1–2 days of work the next time
  it's needed.

### Neutral

- The named-pipe SDDL work (UD-101) and the JSON-Schema validator work
  (UD-105) shipped in v0.0.1's first cut and are recorded in
  `CLOSED.md` regardless of this removal — the work happened, the
  artifacts are gone, the audit trail stays.

## Alternatives considered

- **Keep `protocol/` + `NamedPipeServer.kt` for v0.2.0 UI-on-Windows.**
  Rejected: that path required (1) a UI client implementation against
  the named pipe, (2) ongoing schema maintenance, (3) Windows runner
  CI, (4) a Windows-tier release process. Total ~3–4 weeks of work
  spread across milestones. With Windows out of MVP, none of that work
  has a ROI window.
- **Keep `protocol/` even on Linux as a forward-compatibility hedge.**
  Rejected: the whole point of moving fast at this stage is to keep
  the hedge cost low. A schema directory with no consumer is a
  documentation artifact, not a contract. Documentation that doesn't
  pay rent gets pruned.
- **Move `protocol/` schemas inline into a kotlinx-serialization data
  class hierarchy in `core/app/core/` instead of deleting them.**
  Rejected for now: the current `IpcProgressReporter.kt` emits frames
  via `Json.encodeToString(JsonObject.serializer(), …)` on a hand-built
  `JsonObject`. Replacing that with typed DTOs is a worthwhile
  refactor (see backlog chunk_1 line 129 for the pre-existing
  proposal), but it's bigger than this scope cut and should land
  separately when there's a real second consumer to motivate the
  contract.

## Re-opening criteria

This decision is reversible. Concrete signals that would justify
re-introducing `protocol/`:

1. A second daemon client (a Linux shell-tier via FUSE, a web bridge,
   a non-MCP third-party automation surface) joins the project. At
   that point a code-co-located DTO module probably beats a
   filesystem-rooted schema directory; either path counts as
   "reintroduction."
2. A compliance / certification process requires a machine-readable
   IPC contract.
3. `IpcProgressReporter`'s inline JSON construction turns out to be
   a recurring source of drift bugs that schema validation would have
   caught.

## Re-opening criteria for Windows / macOS support

As above for `protocol/`, plus:

1. A funded effort (≥ 6 weeks) to do the platform properly, including
   CI runners, packaging, and end-user documentation.
2. A specific paying customer or partner whose use-case requires a
   non-Linux platform.
3. A community contributor who proposes to own ongoing maintenance
   for the platform-tier code.

## Related

- Supersedes-in-part: [ADR-0001](0001-monorepo-layout.md) (no more
  `protocol/` tier), [ADR-0003](0003-ipc-unification.md) (one-schema-
  one-transport stops being a goal because there is one client and
  one transport), [ADR-0008](0008-greenfield-restart.md) (Linux is
  now the explicit MVP target, not the implicit one).
- Amends: [ADR-0011](0011-shell-win-removal.md) (the named-pipe
  retention argued there is now overturned).
- Backlog: closes UD-503 (UI on Windows pipe migration), UD-601
  (envelope-format migration), folds in UD-101 / UD-105 / UD-106 /
  UD-802 history (already closed).
