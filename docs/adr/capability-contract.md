# Provider capability contract

## Context

The `CloudProvider` SPI is wide: `list`, `read`, `write`, `delete`, `delta`, `deltaWithShared`, `handleWebhookCallback`, `share`, `quota`, etc. In practice, adapters implement a subset:

- `deltaWithShared()` — OneDrive only; absent on Internxt.
- `handleWebhookCallback()` — OneDrive only; absent on Internxt.
- `share()` — OneDrive (sharing links); absent on Internxt.

Without a contract, upstream code cannot tell "feature not supported" from "feature supported and returned nothing", and feature work orphans because it cannot know who to call.

## Decision

Formalize a capability contract on `CloudProvider`:

1. Every optional method that is unsupported returns a structured `Capability.Unsupported(<reason>)` result (sealed class) instead of silently no-op-ing.
2. `CloudProvider.capabilities(): Set<Capability>` is mandatory and enumerates what the adapter actually supports.
3. `CapabilityRequired` annotations on `:app:sync` tools document which capabilities they depend on, so the engine can skip or short-circuit.

## Consequences

- Callers branch on `Unsupported` explicitly; no silent degradation.
- Adding a new optional method requires every adapter to either implement it or declare `Unsupported`.
- Breaking change to the SPI shape; acceptable on the slim branch where there are only two implementations.

## Alternatives considered

- Runtime `throw UnsupportedOperationException` — loses type safety; callers still crash.
- Separate subinterfaces (e.g. `Shareable`, `Webhookable`) — Kotlin doesn't handle dynamic subinterface discovery cleanly; increases SPI churn.
