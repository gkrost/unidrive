# Multi-platform core

## Context

The earlier ADRs `linux-only.md` and `shipping-surface.md` declared a Linux-only shipping target with the smallest possible surface — one CLI, two providers, no shell extensions, no UI tier, no non-Linux platforms. That was the right call for the slim phase: it let the core stabilise without paying maintenance tax for abandoned half-built surfaces.

Two things have changed since then.

First, the slim phase surfaced a class of correctness bug that the existing engine cannot defend against by patching. The legacy `state.db`-as-authoritative model treats absence locally as user-delete intent; a crashed first-sync leaves phantom rows that read as a bulk delete on the next pass. The accumulated bolt-on guards (recovery branches, hydration heuristics, batch caps) compose poorly with each other and have stopped being enough. The structural fix is a tracking-set predicate over deletion: paths the client has never crossed the sync boundary for are invisible to deletion logic. Landing it cleanly is a new module, not a surgical edit inside the existing reconciler.

Second, the product direction has expanded. The core is the foundation for three platform surfaces beyond the daemon itself — a Windows desktop client (Cloud Files API placeholders, Explorer overlay), an Android app (cloud-drive sync, not Office editing), and a Linux UI (FUSE + file-on-demand + Dolphin context menus). The slim Linux-only contract is now blocking those decisions rather than focusing them.

## Decision

**unidrive is a multi-platform cloud-sync core. Today it ships as a Linux daemon; tomorrow it ships as the engine that powers three platform apps.**

| Surface | Purpose | Status |
|---|---|---|
| Linux daemon | Background sync with a CLI control surface. | Active — Internxt + OneDrive |
| Windows desktop client | Full Explorer integration via the Cloud Files API. Placeholder representation of cloud-only items. Replaces the first-party OneDrive / Internxt desktop clients. | In progress — `unidrive-windows`; read-only tier in MVP (#290), writeback post-MVP |
| Android app | Cloud-drive sync (browse, hydrate, upload). Out of scope: in-app document editing, mail, contacts, anything that isn't sync. | Planned |
| Linux UI | FUSE-backed file-on-demand + Dolphin context menus (hydrate / dehydrate per directory or file). | Planned |

The Linux daemon stays the first ship-target — finishing what's in-flight beats starting the next surface. Other surfaces follow as separate platform tiers that consume the core, not as modules inside the daemon. The core's job is to be the hardened sync engine + provider SPI + state model that those surfaces depend on.

This supersedes `linux-only.md` and `shipping-surface.md`. Their historical context remains accurate; their conclusions no longer hold. The shipping-surface ADR's re-opening criterion (a) — a funded effort that includes ongoing maintenance for the additional tiers — is what motivates this ADR.

## Consequences

- **A new module boundary is needed.** The sync engine has to be usable from both the background daemon and from in-process platform apps, without the latter inheriting the daemon's IPC, systemd, and config-file conventions. The tracking-set engine landing as the structural-safety fix is the right moment to draw that boundary.
- **Provider SPI contract is unchanged.** [`capability-contract.md`](capability-contract.md) continues to govern how providers declare supported and unsupported methods; the contract was already engine-agnostic and survives the surface expansion without amendment.
- **Virtual filesystem layer (placeholders) is no longer indefinitely deferred.** The Windows desktop surface requires Cloud Files API placeholders; the Linux UI requires FUSE-backed file-on-demand. Each gets its own platform tier; the core grows the hydration / pin primitives those tiers need.
- **Smoke target stays per-surface.** The current 5+5+2 target was sized for the Linux daemon. Each new surface gets its own smoke set; defer those until that surface has working code rather than writing speculative ones now.
- **CI matrix grows lazily.** One Linux runner today. Windows + Android runners come online as those tiers gain code, not preemptively.
- **No platform-parity claim yet.** README + AGENTS.md signal future expansion without overpromising. The Windows tier now has working read-only code (`unidrive-windows`); parity claims wait until its surface gate (unidrive-windows#9, per `docs/dev/specs/mvp-acceptance-criteria.md`) is green.
- **Abstractions earn their keep, but the bar is no longer zero.** The slim "no new abstractions" rule made sense when the answer was always "two implementations isn't enough." With four surfaces planned, some module seams are required. New abstractions still have to clear the bar (named justification tied to a surface or to a structural-safety property), they just no longer get refused on principle.

## Re-opening criteria

If the multi-platform direction proves unsustainable — funding shifts, contributor base too thin, the structural-safety work alone turns out to satisfy demand — this ADR can be re-superseded by a return to single-surface focus. Don't shed surfaces mid-build; finish the in-flight surface, or formally postpone it, before adding the next.
