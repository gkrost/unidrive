# Core / client contract

## Context

[`multi-platform.md`](multi-platform.md) declares four planned surfaces (Linux daemon, Windows desktop, Android, Linux UI). Each surface decomposes into a sync engine and one or more clients — but the boundary between them isn't yet written down. Without a contract, every BACKLOG item triages by ad-hoc judgment, and the daemon accumulates UI-tier responsibilities (the failure mode the multi-platform ADR was shaped to prevent). This file is the triage rule and the split for the hard cases.

## Decision

**The sync engine has no human-facing strings, no platform-specific APIs, no user interaction.** Clients own all three. The CLI is the first client and the only one currently shipping; future Windows desktop, Android, and Linux UI clients sit at the same boundary.

### Vocabulary

- **Surface** — a deployable platform tier, per `multi-platform.md`. The Linux daemon is one surface; Windows desktop, Android, Linux UI are three more.
- **Engine** — the sync logic, state model, provider adapters, auth, HTTP, configuration *schema*. Today: `core/app/{sync,sync-tracking,core,config,providers/*}`.
- **Client** — anything that presents the engine to a human or consumes its events. Today the CLI is the only client and lives at `core/app/cli/` inside the core repo. Graphical clients will live outside per `multi-platform.md`.

A surface = engine + one or more clients. The Linux daemon surface = engine + CLI client. A future Windows desktop surface = engine + a Windows-specific client (Cloud Files API integration, Explorer overlay).

### Triage rule

For a new requirement, in order:

1. Same behaviour across all four surfaces? Yes → engine.
2. Needs a platform-specific API (Cloud Files API, FUSE, Android SAF, system notifications, browser launch)? Yes → client.
3. Policy decision (what to do) vs presentation decision (how to show it)? Policy → engine. Presentation → client.
4. Data-correctness or data-safety property? Always engine, so every client inherits the win.
5. Tiebreaker: would sync correctness break if any one client behaved differently? Yes → engine.

### Hard cases — what splits

| Concern | Engine owns | Client owns |
|---|---|---|
| Conflict resolution | Detection, sealed `ConflictPolicy`, conflict log | UI to ask the user, keep-both rename rendering |
| OAuth | Token mechanics, refresh, vault | Redirect handler, browser / Custom Tabs / WebView launch |
| Notifications | The event ("sync paused, OAuth refresh denied") | The rendering (toast, system notification, CLI stderr) |
| File-on-demand placeholders | State model — which paths are hydrated vs cloud-only | Platform-API binding (Cloud Files API, FUSE, SAF) |
| Progress reporting | Event stream over a stable schema | Progress bars / notifications / status icons |
| Background lifecycle | The deterministic `syncOnce()` operation | When to call it (systemd loop, Windows Service tick, WorkManager schedule, FUSE on-demand) |
| Configuration | Schema — what fields a profile has, validation | Persistence format and location (TOML for CLI today; SharedPreferences/DataStore on Android, registry/appdata on Windows) |
| Logging | Structured log events (Logback events with stable keys) | Destination (stdout/log file, logcat, Event Log, ETW) |
| Internationalisation | Nothing — no user-facing strings cross the boundary | All translations, all UX phrasing, all framework choices |

### Internationalisation, specifically

No human-facing strings in engine code. The engine emits structured outcomes — sealed result types, error codes with parameters, log keys. Each client renders those outcomes in whatever locale and tone matches its platform. The CLI's English strings today are the de-facto reference for "what does this event mean," not the system's only strings; as more clients land, the CLI's table becomes documentation of the event schema, not the schema itself.

Defer a shared "canonical English → locale" mapping until at least two graphical clients are shipping. Premature centralisation forces every client into the same lookup mechanism and blocks per-platform UX phrasing for marginal duplication savings.

## Consequences

- **Engine module names stay stable across clients.** Renaming `:app:sync-tracking` mid-stream breaks every downstream client. The engine carries the same compatibility weight as a published SDK once a second client lands.
- **Some current engine modules mix client concerns** — `AdaptiveInterval.kt` is in `core/app/sync/` but encodes "when to call `syncOnce()`," which is client policy under this contract. `Vault.kt` lives in `core/app/sync/` for historical reasons but is consumed by the CLI's `vault` subcommand. Today's code is the starting point; future refactors pull client concerns out of engine packages as platform clients land and the seam tightens. This ADR sets the target, not a sweep.
- **Bringing in a new engine abstraction** (events package, structured-outcome data classes) clears the bar in [`AGENTS.md`](../../AGENTS.md) because it serves four planned client consumers — a named justification, not speculation.
- **Clients vary widely in tech stack.** The Linux daemon's CLI client is Kotlin/picocli. A Windows desktop client may use C# / WinUI / Cloud Files API directly. An Android client is likely Kotlin / Jetpack Compose / WorkManager. They all consume the same engine boundary; they don't share each other's code.
- **The headless reference client (CLI) lives in the core repo.** That doesn't make it engine code. It's a client that happens to ship alongside the engine because it's the operations interface for the daemon surface.

## Re-opening criteria

If the engine/client split makes a real bug structurally harder to fix — a case where the right code genuinely needs to span the boundary — revisit rather than working around it. If a current mixing of engine and client concerns (`AdaptiveInterval`, the daemon-loop wrapping `syncOnce`) turns out to be load-bearing for a reason we don't currently see, document it as an accepted exception rather than refactoring it away.
