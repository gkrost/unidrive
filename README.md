# `unidrive`

Multi-platform cloud-sync core. Pure JVM, zero telemetry. Modular SPI for Internxt Drive (zero-knowledge E2EE) and Microsoft OneDrive (Graph API). Runs as a user-space Linux daemon via systemd; platform tiers consume the engine over IPC — [`unidrive-mount-linux`](https://github.com/gkrost/unidrive-mount-linux) (FUSE) and [`unidrive-windows`](https://github.com/gkrost/unidrive-windows) (CfAPI, read-only tier in MVP).

## Technical Layout

```
.
├── core/
│   ├── app/
│   │   ├── cli/            # CLI entry point and subcommand mapping
│   │   ├── core/           # Engine, crypto, model sets
│   │   ├── hydration/      # De/hydration pipeline
│   │   ├── sync/           # Sync engine (ships the MVP), state.db, IPC server
│   │   └── sync-tracking/  # Frozen tracking-set engine (`unidrive ts`)
│   └── providers/
│       ├── internxt/       # Zero-knowledge encrypted client
│       └── onedrive/       # Microsoft Graph API client
└── dist/                   # User-space install scripts and systemd units
```

## Build

JDK 21+, Linux with `systemd --user`.

```bash
./gradlew :core:app:cli:assemble    # build fat JAR
```

## Install (user-space, no root)

```bash
cd dist/
./install.sh
```

Installs to:
- `~/.local/bin/unidrive`
- `~/.local/lib/unidrive/unidrive-<version>.jar`
- `~/.config/systemd/user/unidrive.service`

Start daemon:
```bash
systemctl --user daemon-reload
systemctl --user enable --now unidrive.service
```

The installed unit runs `unidrive sync --watch`. That process serves the hydration verbs and `sync.subscribe` only; `refresh`, the mount and the Windows client also need the daemon verbs (`refresh.run`, `sync.enumerate`, `daemon.status`), which only `unidrive daemon run` serves. Run `daemon run` instead of `sync --watch` when one of those clients is in use.

Daemon log: `~/.local/share/unidrive/unidrive.log`. Quick triage: `scripts/dev/log-watch.sh --summary`.

## Commands

`unidrive` uses a git-inspired subcommand design.

### Session & Identity
- `auth` — OAuth/token provisioning
- `profile` — multi-profile management
- `logout` — destroy session, invalidate credentials

### Sync
- `sync` — run the sync engine (`SyncEngine`, the engine the MVP ships); the frozen tracking-set engine is reachable only as `ts`
- `status` — show alignment, transfers, exceptions
- `conflicts` — convergence paths for conflicting hashes

### Storage
- `pin / get` — hydrate specific folders
- `free` — dehydrate to sparse markers
- `vault` — vault operations
- `sweep` — garbage-collect detached index fragments

## Key source files

- `ProviderFactory.kt` — SPI provider registration framework
- `SyncEngine.kt` — the sync engine behind `sync` and the daemon
- `TrackingEngine.kt` — the frozen tracking-set engine, reachable only as `unidrive ts ...`
- `HydrationImpl.kt` — physical payload de/reconstruction lifecycle
