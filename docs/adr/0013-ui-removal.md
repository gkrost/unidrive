---
id: ADR-0013
title: Remove the ui/ Compose-Desktop / Swing tray tier
status: accepted
date: 2026-04-29
supersedes_in_part: ADR-0001, ADR-0008
amends: ADR-0011, ADR-0012
related: ADR-0003, ADR-0006
---

## Context

[ADR-0011](0011-shell-win-removal.md) removed the C++ Windows shell
extension. [ADR-0012](0012-linux-mvp-protocol-removal.md) declared a
Linux-only MVP and removed `protocol/`, `NamedPipeServer.kt`, and
`PipeSecurity.kt`. After both, the only remaining client of the

**Note:** `NamedPipeServer.kt` and `PipeSecurity.kt` were removed per ADR-0012.
The current IPC surface is UDS only (`IpcServer.kt` / `IpcProgressReporter.kt`).
daemon's UDS broadcast surface was `ui/` — a Kotlin/Swing system-tray
applet (24 files, 4 main classes, 4 tests). It was carried at
`status: preview` against a v0.2.0 milestone.

A first-release scope review concluded that the tray applet is not on
the critical path for a useful Linux MVP. Day-one users interact with
the CLI (`unidrive sync`, `unidrive get`, `unidrive status`) and the
MCP server (LLM-driven automation). The tray adds visible polish but
no capability — every action it exposes is also reachable from the
CLI, and a dedicated Linux tray applet has overlapping concerns with
desktop-environment-specific tools (KDE / GNOME indicators, dorkbox's
JNI bindings, D-Bus integration) that we don't have the bandwidth to
maintain across distributions.

## Decision

Remove `ui/` from the tree, effective the v0.0.1 amend on 2026-04-29.

What goes:

1. The `ui/` directory and all 24 files (Compose-Desktop / Swing tray,
   `TrayManager.kt`, `IpcClient.kt`, `DaemonDiscovery.kt`,
   `StatusPopover.kt`, icon resources, tests).
2. `includeBuild("ui")` in the root `settings.gradle.kts`.
3. The `ui/` references in `README.md`, `CLAUDE.md`,
   `docs/ARCHITECTURE.md`, `docs/SPECS.md`,
   `docs/ARCHITECTURE.md §Platform support`,
   `docs/SECURITY.md`, `docs/AGENT-SYNC.md`, `CLAUDE.md`, and
   the `ui/` path entry in `scripts/ci/check-boundaries.sh`.
4. The `build-ui.sh` line in `scripts/ci/README.md` and any
   `ui`-pathed lockfile entries in `.github/dependabot.yml`.
5. UI-tier backlog items (UD-502 macOS tray audit, UD-213 tray
   multi-provider layout, UD-215 tray activity indicator, UD-217 tray
   UX brainstorm, anything else in the UD-500..599 range that's
   `status: open`) move from `BACKLOG.md` to `CLOSED.md` with
   `resolved_by:` pointing at this ADR.

What stays:

1. **`core/app/sync/IpcServer.kt`** still binds the UDS socket and
   broadcasts NDJSON sync events. Today's consumer set is `unidrive
   log --watch` plus any third-party automation listening on the
   socket; tomorrow's may include a re-introduced UI tier or a
   different consumer entirely.
2. **`IpcProgressReporter.kt`** keeps emitting `sync_started`,
   `scan_progress`, `transfer_progress`, `sync_complete`, etc. The
   surface stays useful even without a tray client.
3. **MCP server** (`core/app/mcp/`) is unaffected — JSON-RPC over
   stdio, separate concern.

## Consequences

### Positive

- v0.0.1's surface area drops from "CLI + MCP + tray" to "CLI + MCP",
  matching the actual Linux power-user ergonomic profile (terminal-
  driven, scripts the daemon, doesn't expect a system-tray icon).
- The competitive brief framing
  ([`docs/internal/competitive-brief-2026-04.md`](../internal/competitive-brief-2026-04.md))
  is honest about this: "abraunegg/onedrive ships zero GUI; rclone
  ships effectively-zero GUI; that bar is already met by the CLI."
  Adding a tray was a v0.2.0+ differentiator, not a v0.1.0 gate.
- CI loses the UI build job. `dorkbox SystemTray` and its JNI/D-Bus
  dependencies stop being part of the dependency surface.
- A third-party tray applet (community or commercial) can still
  consume the UDS event stream — the contract is the
  `IpcProgressReporter.kt` JSON shape, which is documented in
  [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md).

### Negative / trade-offs

- **No first-party visual feedback during a sync.** Users who expect
  a "ones-and-zeros are flowing right now" indicator have to tail the
  log or rely on stdout. For `unidrive sync --watch` the CLI already
  paints progress; for one-shot `sync` there's no live bar.
- **The dorkbox SystemTray + Swing knowledge in the tree is lost.** If
  a future contributor wants to bring the tray back, they restart from
  the `ui/` content recoverable from this commit's parent in the
  reflog (or from any earlier published v0.0.1 SHA pre-amend). Not
  fundamentally hard to revive; just a deliberate design + scope
  reset.
- **One fewer "OSS sync core + GUI" Tier-3 competitor parity check.**
  Mountain Duck and InSync both ship trays; abraunegg/onedrive +
  rclone do not. UniDrive lands in the latter camp until v0.2.0+
  decides otherwise.

### Neutral

- Some closed UI-related backlog work (UD-501 TOML parser refactor,
  UD-503 UI-on-Windows pipe migration — both already in `CLOSED.md`)
  stay as historical record. The work happened, the artifacts are
  gone, the audit trail stays.

## Alternatives considered

- **Keep `ui/` but mark it `status: experimental` and exclude from
  the v0.0.1 build matrix.** Rejected: experimental code that ships
  in the public tree but isn't built or tested rots fast; the next
  contributor reading the tree can't tell what's vendored vs alive.
  Cleaner to delete and document the criteria for re-import.
- **Move `ui/` to a sibling repo (`unidrive-ui`) under the same
  account.** Rejected: the original sibling-repo split is exactly
  what [ADR-0001](0001-monorepo-layout.md) consolidated to fix; a new
  sibling for the unfinished tray would re-introduce the coordination
  cost. If a tray comes back, it lands in this repo.
- **Replace Swing/Compose-Desktop with a smaller native tray (libappindicator
  on Linux, tray-icon-rs binding, etc.).** Rejected at this stage as
  premature — the question "what does a UniDrive tray do that the CLI
  doesn't" is not yet answered, so picking an implementation is
  premature. Solve the design, then pick the toolkit.

## Re-opening criteria

This decision is reversible. Concrete signals that would justify
re-introducing a UI tier:

1. A funded effort to design a tray applet with a defined feature
   set distinct from the CLI (e.g. multi-account quota visibility,
   live conflict resolution UI, drag-drop file pinning) — including
   Linux desktop-environment scope (KDE / GNOME / others) and a
   dedicated maintainer.
2. A specific paying customer or partner who needs a GUI surface
   and is willing to underwrite the work.
3. A community contributor who proposes to own a tray-applet effort
   long-term, including its CI matrix and packaging story.

In any of those cases: open a new ADR, supersede this one, restart
the tray with a fresh design unconstrained by the dorkbox/Swing
inheritance.

## Related

- Backlog: closes UD-213, UD-215, UD-217, UD-502 (see ticket
  migration to `CLOSED.md` with `resolved_by: ADR-0013`).
- Supersedes-in-part: [ADR-0001](0001-monorepo-layout.md),
  [ADR-0008](0008-greenfield-restart.md).
- Amends: [ADR-0011](0011-shell-win-removal.md),
  [ADR-0012](0012-linux-mvp-protocol-removal.md).
- Affects: [ADR-0003](0003-ipc-unification.md) — already superseded;
  no further change needed. [ADR-0006](0006-toolchain.md) — Kotlin
  toolchain claim narrows from "core + ui" to "core".
