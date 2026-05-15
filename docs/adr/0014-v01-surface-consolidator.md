---
id: ADR-0014
title: v0.1.0 shipping surface — consolidator of ADR-0008/0011/0012/0013
status: accepted
date: 2026-05-14
consolidates: ADR-0008, ADR-0011, ADR-0012, ADR-0013
---

## Context

The v0.1.0 shipping surface is the cumulative result of four decisions
made between 2026-04-26 and 2026-04-29:

| ADR | Date | Decision | In tree? |
|---|---|---|---|
| ADR-0008 | 2026-04-26 | Greenfield restart. v0.1.0 = core-only; `ui/` and `shell-win/` at "preview" tier. | Removed in commit `466d5f8` |
| ADR-0011 | 2026-04-28 | Remove `shell-win/` entirely. Named-pipe transport initially retained for a future Windows UI. | Removed in commit `466d5f8` |
| [ADR-0012](0012-linux-mvp-protocol-removal.md) | 2026-04-28 | Linux-only MVP. Remove `protocol/` schemas, `NamedPipeServer.kt`, `PipeSecurity.kt`, `NdjsonValidator.kt`. Windows/macOS out of scope for v0.0.x → v0.1.0. | Yes |
| ADR-0013 | 2026-04-29 | Remove `ui/` entirely. The shipping surface is the CLI + daemon + MCP server. | Removed in commit `466d5f8` |

**Three of the four source ADRs are not retained in tree.** They were
deliberately removed along with other relocated/outdated docs in
`466d5f8`; only ADR-0012 (the latest amendment) survives. The
decisions themselves still hold — they're recorded in commit history
plus this consolidator — but a reader cannot navigate to ADR-0008,
ADR-0011, or ADR-0013 from a working copy. That gap is precisely
what motivates this consolidator: a single in-tree document that
states the resulting v0.1.0 surface without needing to chase
removed files.

This ADR is a **consolidator, not a replacement.** ADR-0012 remains
authoritative for the Linux-MVP + protocol-removal decision; the
three removed ADRs remain authoritative in git history for their
respective decisions. This document is the live working-copy entry
point that answers "what's shipping?" without re-deriving it.

## Decision

The v0.1.0 shipping surface is the following. It is the cumulative
result of the four source decisions (ADR-0008, ADR-0011,
[ADR-0012](0012-linux-mvp-protocol-removal.md), ADR-0013) and has
been unchanged since the last of them landed (2026-04-29).

### Platforms

- **Linux** only. x86_64 + aarch64.
- **Windows and macOS** are out of scope for v0.0.x → v0.1.0.
  [ADR-0012](0012-linux-mvp-protocol-removal.md) §"Re-opening criteria
  for Windows / macOS support" enumerates the conditions under which
  either platform would return: funded effort (≥ 6 weeks), a specific
  paying customer, or a community contributor who owns ongoing
  maintenance.

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
| `shell-win/` (Windows Explorer overlay icons + CFAPI placeholders) | ADR-0011 (not in tree; see commit history at or before `466d5f8`) | Bundled with the Windows re-opening criteria in [ADR-0012](0012-linux-mvp-protocol-removal.md) §"Re-opening criteria for Windows / macOS support" since the Windows surface as a whole is out of scope. |
| `ui/` (Compose-Multiplatform tray + settings UI) | ADR-0013 (not in tree; see commit history) | Community-owned tray UIs over the public UDS IPC are explicitly welcome — the daemon's NDJSON event stream is documented in `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt`. An in-tree tray would re-open ADR-0013's reasoning; needs a separate ADR. |
| `protocol/` (JSON-Schema NDJSON envelope contract + golden fixtures) | [ADR-0012](0012-linux-mvp-protocol-removal.md) | See [ADR-0012](0012-linux-mvp-protocol-removal.md) §"Re-opening criteria". |
| Windows named-pipe transport (`NamedPipeServer.kt`, `PipeSecurity.kt`, `NdjsonValidator.kt`) | [ADR-0012](0012-linux-mvp-protocol-removal.md) | Bundled with the Windows re-opening criteria in [ADR-0012](0012-linux-mvp-protocol-removal.md). |

### IPC

- **One transport: Unix Domain Socket** at the daemon's per-profile state directory.
- **One client family** today: the CLI + MCP server in-tree. Third-party
  consumers may subscribe to the same NDJSON event stream — there is
  no policy against it, but no formal contract layer maintained for them
  either. If a second in-tree consumer or a paying integration partner
  materialises, [ADR-0012](0012-linux-mvp-protocol-removal.md)'s
  re-opening criteria for `protocol/` kick in.

### Backlog ID-range bookkeeping

- **UD-400..499** — rebound 2026-05-01 from the retired `shell-win` tier to `cli`. UD-401..403 stay in CLOSED.md with `category: shell-win` as historical record.
- **UD-500..599** — reserved, formerly `ui` (retired by ADR-0013, removed from tree along with the ADR file).
- **UD-600..699** — reserved, formerly `protocol` (retired by [ADR-0012](0012-linux-mvp-protocol-removal.md)).

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
- No retraction of any prior decision. [ADR-0012](0012-linux-mvp-protocol-removal.md)
  remains authoritative in tree; ADR-0008 / ADR-0011 / ADR-0013 remain
  authoritative in git history. This document summarises their
  cumulative effect for working-copy readers.

## Alternatives considered

- **Restore ADR-0008 / ADR-0011 / ADR-0013 from git history.** Rejected:
  removing them was a deliberate cleanup in commit `466d5f8`
  ("removed relocated/outdated .md's"); reintroducing them walks back
  that decision without authority. Their content is preserved in git
  history and recoverable via `git show 466d5f8^:docs/adr/0008-...`
  for the rare reader who wants to audit the source decisions in their
  original form.
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
