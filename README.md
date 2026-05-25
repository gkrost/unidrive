# `unidrive`

A lean, zero-telemetry, pure JVM multi-cloud synchronization tool for Linux power users.

Most commercial cloud sync agents are opaque, resource-heavy binary blobs bundled with aggressive telemetry, proprietary background services, or closed shell integrations. `unidrive` breaks away from this model. It strips out user tracking, cuts out non-standard background runtimes, and provides a fully auditable, decentralized synchronization core that interfaces natively with standard Unix utilities via subcommands, standard streams, and localized control structures.

---

## Architectural Principles & Core Claims

* **Zero Telemetry, Pure Sovereignty:** No tracking endpoints, no behavioral analytics, and no remote crash reporting. Your metadata, tokens, and data blocks remain within your defined filesystem boundaries.
* **Modular Multi-Cloud Interface:** Built over an extensible Service Provider Interface (SPI) framework (`ProviderFactory`). It natively supports decentralized/zero-knowledge cloud endpoints (**Internxt Drive**) alongside mainstream endpoints (**Microsoft OneDrive**) through an identical programmatic interface.
* **Decoupled Architecture:** Features a clean architectural separation between the multi-platform engine (`core/app/core`), individual client interfaces (`core/app/cli`), and cloud transport mechanisms (`core/providers/*`).
* **Native Linux Daemon Integration:** Installs locally without system-wide root privileges. Synchronization routines run cleanly via user-space standard `systemd` structures.

---

## Technical Layout

```
.
├── core/
│   ├── app/
│   │   ├── cli/            # Main entry point and CLI wrapper subcommand mapping
│   │   ├── core/           # Synchronization engine, cryptographic pipelines, and model sets
│   │   ├── hydration/      # Local file dehydration/hydration pipeline
│   │   └── sync-tracking/  # State reconciler and tracking-set engines
│   └── providers/
│       ├── internxt/       # End-to-end encrypted AES-GCM zero-knowledge client bridge
│       └── onedrive/       # Microsoft Graph API protocol client bridge
└── dist/                   # Non-root user space installation scripts and systemd definitions

```

---

## Build and Environment

The project compiles with a modular Gradle structure running on top of modern JVM targets. Code quality constraints are tightly pinned via Ktlint baselines and Static Analysis rules (`semgrep`, `gitleaks`) to minimize security slips or credential leakage across providers.

### Compile Requirements:

* JDK 21+
* Linux user-space environment (`systemd --user` availability)

```bash
# Build the project fat JAR
./gradlew :core:app:cli:assemble

```

---

## Non-Root Installation

To keep your base system uncontaminated, the installer scripts run in user land and use paths within `~/.local` and `~/.config`:

```bash
cd dist/
./install.sh

```

This populates:

* **Binary Execution:** `~/.local/bin/unidrive`
* **Library Storage:** `~/.local/lib/unidrive/unidrive-<version>.jar`
* **Process Management:** `~/.config/systemd/user/unidrive.service`

To initialize the sync daemon under your user space context:

```bash
systemctl --user daemon-reload
systemctl --user enable --now unidrive.service

```

---

## Command Reference

`unidrive` follows a strictly git-inspired subcommand design pattern. The binary maps commands directly to their underlying execution contexts:

### Session & Identity Controls

* `unidrive auth` — Provisions provider profiles, setups OAuth loops or token entries.
* `unidrive profile` — Manages multi-profile client targets.
* `unidrive logout` — Destroys current session contexts and cryptographically invalidates local credential store latches.

### Core Synchronisation Pipelines

* `unidrive sync` — Starts tracking-set delta calculation engines, verifying local snapshots against remote cloud state trees.
* `unidrive status` — Prints structural alignment states, pending transfers, in-flight deduplications, and active exceptions.
* `unidrive conflicts` — Scans local change tracking states and presents deterministic convergence paths for conflicting hashes.

### Block & Storage Management

* `unidrive pin / get` — Targets exact folders or structures for complete hydration/pinning mechanics.
* `unidrive free` — Dehydrates structural payloads to sparse markers, saving physical blocks while preserving remote pointers.
* `unidrive vault` — Controls interactions against secure crypt-boundaries or cloud-vault sub-allocations.
* `unidrive sweep` — Forces garbage collection of detached index fragments and stale file tombstones.

---

## Verifying Code Mechanics

To ensure everything stays auditable, you can verify how `unidrive` handles state mapping inside the codebase:

1. **SPI Provider Implementations:** Look inside `core/app/core/src/main/kotlin/org/krost/unidrive/ProviderFactory.kt` to see how new remote backends register using standard JVM mechanisms.
2. **Deterministic Tracking Sets:** Inspect `core/app/sync-tracking/src/main/kotlin/org/krost/unidrive/tracking/TrackingEngine.kt` to trace how local and remote state differences are calculated without central server telemetry tracking.
3. **Sparse States:** Read through `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt` to analyze the lifecycle of physical payload deletion and reconstruction when using local files.
