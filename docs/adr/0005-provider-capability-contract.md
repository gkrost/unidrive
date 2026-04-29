---
id: ADR-0005
title: Provider capability contract
status: accepted
date: 2026-04-17
---

## Context

The imported `CloudProvider` interface is wide: `list`, `read`, `write`, `delete`, `delta`, `deltaWithShared`, `handleWebhookCallback`, `share`, `quota`, etc. In practice, adapters implement a subset:

- `deltaWithShared()` — OneDrive only; others return empty list silently.
- `handleWebhookCallback()` — OneDrive only; others no-op.
- `share()` — S3 (presigned URLs), OneDrive (sharing links); others absent.
- `verifyItemExists()` — most providers return `true` unconditionally.

This means upstream code cannot tell "feature not supported" from "feature supported and returned nothing", and feature work (e.g. `SubscriptionStore`) has orphaned because it can't know who to call.

## Decision

Formalize a **capability contract** on `CloudProvider`:

1. Every optional method that is unsupported returns a structured `Capability.Unsupported(<reason>)` result (sealed class) instead of silently no-op'ing.
2. `CloudProvider.capabilities(): Set<Capability>` is mandatory and enumerates what the adapter actually supports.
3. [ARCHITECTURE.md §Provider capability matrix](../ARCHITECTURE.md#provider-capability-matrix) is the authoritative list; updates to it and to the adapter must land in the same PR.
4. `CapabilityRequired` annotations on `app/sync` / `app/mcp` tools document which capabilities they depend on, so the engine can skip or short-circuit.

## Consequences

### Positive
- Callers can branch on `Unsupported` explicitly; no silent degradation.
- The matrix stays honest (CI could enforce this in future via a reflection test).
- Makes it cheap to add new capabilities — you declare, implement or declare `Unsupported`, update matrix.

### Negative / trade-offs
- Breaking API change for adapter authors. Acceptable at v0.1.0 greenfield.
- Some boilerplate per adapter; mitigated with default implementations in a `AbstractCloudProvider` base.

## Alternatives considered

- **Runtime `throw UnsupportedOperationException`** — rejected: loses type safety; callers still crash.
- **Separate subinterfaces** (e.g., `Shareable`, `Webhookable`) — rejected for MVP: Kotlin doesn't handle dynamic subinterface discovery cleanly; increases SPI churn.

## Related

- Backlog: [UD-302](../backlog/BACKLOG.md#ud-302), [UD-303](../backlog/BACKLOG.md#ud-303)
- Matrix: [ARCHITECTURE.md §Provider capability matrix](../ARCHITECTURE.md#provider-capability-matrix)
