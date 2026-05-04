---
id: ADR-0006
title: Toolchain — JVM 21 LTS, Kotlin 2.3.20
status: accepted
date: 2026-04-17
amended_by: ADR-0011, ADR-0013
---

## Context

Imported trees diverged:

- `core/` — root Kotlin compiler `jvmTarget = JVM_21` but per-module `jvmToolchain(25)`. Effective bytecode target was 21 while toolchain required 25 at build.
- `ui/` (removed in [ADR-0013](0013-ui-removal.md)) — was `jvmToolchain(25)`.

JVM 25 is a six-month feature release (Sept 2025); JVM 21 is the current LTS through 2028. Kotlin 2.3.20 supports both. There is no feature in the codebase that requires > JVM 21 (`--enable-native-access` is JVM 21+; virtual threads are JVM 21+).

## Decision

- **JVM 21 LTS** across all Kotlin modules in `core/`.
- **Kotlin 2.3.20** remains the language version.
- **Gradle toolchain auto-provisioning** is allowed; contributors may also set `org.gradle.java.home` explicitly.

## Consequences

### Positive
- Longer supported lifetime for the runtime (to 2028 vs. 6 months).
- Wider pool of available JDK builds for self-hosted CI.
- Removes the bytecode-vs-toolchain mismatch that was latent in the `core/` import.

### Negative / trade-offs
- Cannot use JVM 22–25 preview features (scoped values finalized in 24, structured concurrency in 25). None are used today.

## Alternatives considered

- **JVM 25 everywhere** — rejected: short-term LTS-less runtime; not materially faster for this workload.
- **JVM 17 LTS** — rejected: pattern matching for switch / virtual threads are too useful to give up.

## Related

- Backlog: [UD-702](../backlog/BACKLOG.md#ud-702)
- Changelog: [Unreleased](../CHANGELOG.md#unreleased) — "Unified JVM toolchain to 21 LTS"
