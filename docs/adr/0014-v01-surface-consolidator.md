---
id: ADR-0014
title: v0.1.0 shipping surface — consolidator of ADR-0008/0011/0012/0013
status: accepted
date: 2026-05-14
consolidates: ADR-0008, ADR-0011, ADR-0012, ADR-0013
---

## Context

A new contributor reading the ADR set today must mentally compose four
documents to answer "what is unidrive v0.1.0 actually shipping?":

| ADR | Date | Decision |
|---|---|---|
| [ADR-0008](0008-greenfield-restart.md) | 2026-04-26 | Greenfield restart. v0.1.0 = core-only; `ui/` and `shell-win/` at "preview" tier. |
| [ADR-0011](0011-shell-win-removal.md) | 2026-04-28 | Remove `shell-win/` entirely. Named-pipe transport retained for the future v0.2.0 UI-on-Windows path. |
| [ADR-0012](0012-linux-mvp-protocol-removal.md) | 2026-04-28 | Linux-only MVP. Remove `protocol/` schemas, `NamedPipeServer.kt`, `PipeSecurity.kt`, `NdjsonValidator.kt`. Windows/macOS out of scope for v0.0.x → v0.1.0. |
| [ADR-0013](0013-ui-removal.md) | 2026-04-29 | Remove `ui/` entirely. The shipping surface is the CLI + daemon + MCP server. |

ADR-0008's stated trade-offs ("no tray, no Explorer integration") are
now the actual shipped state, not a temporary acceptance. Each amendment
ADR explicitly cross-references the others via the `amends` /
`amended_by` / `supersedes_in_part` frontmatter chain. The chain is
faithfully recorded but never summarised. A reader who lands on
ADR-0008 first reads dated trade-offs without realising they're already
permanent decisions; a reader who lands on ADR-0013 last sees the final
state but not why.

This ADR is a **consolidator, not a rewrite.** The four source ADRs
remain authoritative for their respective decisions. This document
states the resulting v0.1.0 surface in one place so a contributor can
answer "what's shipping?" without re-deriving it from four amendment
docs.

## Decision

The v0.1.0 shipping surface is the following, derived from
ADR-0008/0011/0012/0013 and unchanged since ADR-0013 landed
(2026-04-29):

### Platforms

- **Linux** only. x86_64 + aarch64.
- **Windows and macOS** are out of scope for v0.0.x → v0.1.0. ADR-0012
  + ADR-0013 enumerate the re-opening criteria for each.

### Components that ship

| Component | Path | What it is |
|---|---|---|
| `:app:cli` | `core/app/cli/` | The `unidrive` command-line tool. Sole user-facing surface. Subcommands cover sync (`sync`, `daemon`, `apply`, `refresh`, `status`, `pause`, `resume`), profile management (`profiles`, `add-profile`, `remove-profile`), trash (`trash`), versions (`versions`), benchmark (`benchmark`, `groundtruth` via `:app:e2e-360`), and the various diagnostic commands documented in `docs/EXTENSIONS.md`. |
| `:app:sync` | `core/app/sync/` | The reconciliation engine (`SyncEngine`), state DB (`StateDatabase`), local watcher (`LocalWatcher`), IPC reporter (`IpcProgressReporter`). The daemon's heart. |
| `:app:core` | `core/app/core/` | Cross-provider primitives: `ProviderRegistry`, `ProviderFactory`/`Capability` SPI, HTTP utilities (`HttpRetryBudget`, `ThrottleBudget`, `InFlightDedup`, request-id headers), auth helpers (PKCE, OAuth callback server, credential store). |
| `:app:mcp` | `core/app/mcp/` | The Model Context Protocol server tooling so LLM-driven workflows can operate a unidrive profile. Communicates with the daemon over the same UDS IPC the CLI uses. |
| `:app:benchmark` | `core/app/benchmark/` | Multi-profile cloud-provider speed ranking — produces the data behind the rankings published at `unidrive.krost.org`. |
| `:app:cli-full` | `core/app/cli-full/` | Shadow-JAR aggregator that bundles `:app:cli` + `:app:benchmark` for distribution. |
| `:app:e2e-360` | `core/app/e2e-360/` | Dockerized end-to-end test harness ("CloudForge ground-truth"). Runs the daemon against ephemeral cloud-provider containers / live providers in a controlled environment. |
| `:providers:*` | `core/providers/{onedrive,internxt,localfs,s3,sftp,webdav,rclone,hidrive}/` | One module per provider. Implementations of `ProviderFactory` + `CloudProvider`. |

### Components that do NOT ship

| Component | Removed by | Re-opening criteria |
|---|---|---|
| `shell-win/` (Windows Explorer overlay icons + CFAPI placeholders) | ADR-0011 | See ADR-0011 §"Re-opening". |
| `ui/` (Compose-Multiplatform tray + settings UI) | ADR-0013 | See ADR-0013 §"Re-opening". Community-owned tray UIs over the public UDS IPC are explicitly welcome — the daemon's NDJSON event stream is documented in `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt`. |
| `protocol/` (JSON-Schema NDJSON envelope contract + golden fixtures) | ADR-0012 | See ADR-0012 §"Re-opening". |
| Windows named-pipe transport (`NamedPipeServer.kt`, `PipeSecurity.kt`, `NdjsonValidator.kt`) | ADR-0012 | Bundled with the Windows re-opening criteria in ADR-0012. |

### IPC

- **One transport: Unix Domain Socket** at the daemon's per-profile state directory.
- **One client family** today: the CLI + MCP server in-tree. Third-party
  consumers may subscribe to the same NDJSON event stream — there is
  no policy against it, but no formal contract layer maintained for them
  either. If a second in-tree consumer or a paying integration partner
  materialises, ADR-0012's re-opening criteria for `protocol/` kick in.

### Backlog ID-range bookkeeping

- **UD-400..499** — rebound 2026-05-01 from the retired `shell-win` tier to `cli`. UD-401..403 stay in CLOSED.md with `category: shell-win` as historical record.
- **UD-500..599** — reserved, formerly `ui` (retired by ADR-0013). Available for rebinding.
- **UD-600..699** — reserved, formerly `protocol` (retired by ADR-0012). Available for rebinding.

See `docs/AGENT-SYNC.md` §"ID ranges" for the canonical table.

## Consequences

### Positive

- A reader can land on ADR-0014 and learn the v0.1.0 surface in one
  document instead of mentally composing four.
- The "what's shipping?" question now has a single citable answer
  (ADR-0014 §"Decision"), reducing churn in onboarding and PR review.
- The retired-tier ID-range bookkeeping is documented in one place
  rather than scattered across four ADRs + AGENT-SYNC.md.

### Negative / trade-offs

- One more ADR to maintain on every surface change. Mitigation: this
  ADR is intentionally short and structured; surface changes that
  affect ADR-0014 will also affect their source ADRs, and updating
  both is a one-paragraph diff in practice.

### Neutral

- No code change.
- No retraction of any prior decision. ADR-0008/0011/0012/0013 remain
  authoritative for their respective decisions; this document just
  summarises their cumulative effect.

## Alternatives considered

- **Rewrite ADR-0008 in place to reflect the current surface.**
  Rejected: ADRs are append-only by convention (see ADR-template's
  `status: accepted | deprecated | superseded-by` field). Rewriting
  the founding ADR would erase the decision trail of why the trade-offs
  were originally accepted, and obscure the historical context that
  motivated the three amendment ADRs.
- **Inline the v0.1.0 surface into AGENT-SYNC.md or ARCHITECTURE.md
  instead of writing an ADR.** Rejected: those documents are for
  process and architecture description, not for decision records.
  An ADR is the right shape because the v0.1.0 surface is the
  cumulative result of a chain of decisions, and any future change
  to the surface should produce another ADR that supersedes this one.
- **Leave the situation as-is** (four amendment ADRs, no consolidator).
  Rejected: cost is paid by every new contributor and every retrospective
  reader. A 5-minute consolidator amortises across all future readings.

## Related

- Consolidates: ADR-0008, ADR-0011, ADR-0012, ADR-0013.
- Closes: [UD-003](../backlog/CLOSED.md#ud-003).
- Cross-references: `docs/AGENT-SYNC.md` §"ID ranges",
  `docs/ARCHITECTURE.md`, `docs/SPECS.md`,
  `docs/dev/dependency-locking.md`.
