---
id: ADR-0004
title: Security baseline for v0.1.0
status: accepted
date: 2026-04-17
---

## Context

The imported trees have real mitigations (AES-GCM vault, `0600` tokens, array-form subprocess) but also concrete gaps, most notably the unauthenticated named pipe and absent schema validation on NDJSON frames. No scanner tooling is wired. No threat model exists.

## Decision

Define a v0.1.0 **security baseline** that must land before the MVP tag. Items are tracked in [BACKLOG.md](../backlog/BACKLOG.md) under the UD-1xx range.

### Must-land for v0.1.0 (core-only scope)

- **UD-105** — JSON-Schema validation on every NDJSON frame on both sides; reject on mismatch without further parsing.
- **UD-106** — Message size cap (64 KiB) + per-second rate limit per connection.
- **UD-107** — `gitleaks` in pre-push hook and in `scripts/ci/`.
- **UD-112** — STRIDE threat model formalized in [SECURITY.md](../SECURITY.md).

### Deferred to v0.2.0 (UI)

- **UD-501** — Replace manual TOML parsing in `TrayManager.kt` with a real parser.
- Windows DPAPI wrap of OAuth token files (new UD-1xx on allocation).

### Closed via [ADR-0011](0011-shell-win-removal.md) and [ADR-0012](0012-linux-mvp-protocol-removal.md)

- ~~**UD-101** — SDDL / ACL on named pipe~~ — landed in v0.0.1's first cut; pipe surface itself removed in ADR-0012 (Linux MVP).
- ~~**UD-102** — DLL hardening~~ — N/A; no DLL ships.

### Always-on (introduce progressively)

- **UD-108** — Trivy on dependency lockfiles (+ container images if any).
- **UD-109** — Semgrep rulesets for Kotlin (the C++ ruleset retired with ADR-0011).
- **UD-110** — Dependabot or Renovate for Gradle pins.
- **UD-111** — Token refresh-failure telemetry (structured log + user prompt).

## Consequences

### Positive
- Explicit gate list prevents v0.1.0 shipping with known LPE-adjacent risks in-scope.
- Deferred items are explicitly out-of-scope — nothing hidden.
- Scanner integration is staged so each adds value without requiring all at once.

### Negative / trade-offs
- Some MED-severity items (UD-104 WebDAV `trust_all_certs` wire-up) slip past v0.1.0 because WebDAV itself is not in the v0.1.0 provider set (`localfs`, `s3`, `sftp` only).

## Alternatives considered

- **Ship everything in v0.1.0** — rejected: triples scope, delays MVP indefinitely.
- **Defer all security to v1.0 stable** — rejected: UD-105 / UD-106 are cheap, prevent whole categories of bug, and establish habit.

## Related

- Backlog: [UD-101..112](../backlog/BACKLOG.md)
- Related: [ADR-0003](0003-ipc-unification.md), [SECURITY.md](../SECURITY.md)
