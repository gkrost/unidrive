# `unidrive`

Multi-platform cloud-sync core. Pure JVM, zero telemetry. Modular SPI for Internxt Drive (zero-knowledge E2EE) and Microsoft OneDrive (Graph API). Runs as a user-space Linux daemon via systemd; platform tiers consume the engine over IPC — [`unidrive-mount-linux`](https://github.com/gkrost/unidrive-mount-linux) (FUSE) and [`unidrive-windows`](https://github.com/gkrost/unidrive-windows) (CfAPI, read-only tier in MVP).

## Technical Layout

```
.
├── core/
│   ├── app/
│   │   ├── cli/            # CLI entry point and subcommand mapping
│   │   ├── cli-spi/        # CLI extension SPI (`org.krost.unidrive.cli.ext`)
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

## Packaged distribution (no user-installed JDK)

A platform package ships a `jlink` runtime image next to the fat JAR. The image
is jlinked from the bundled runtime JDK — the current JDK release train — while
the engine's bytecode stays at the compile toolchain level (`jlink` produces a
runtime of its own version, so the image JVM deliberately leads the bytecode
target):

```bash
./gradlew :core:app:cli:shadowJar :core:app:cli:runtimeImage
# → core/app/cli/build/runtime-image/  (bin/java + legal/, ~50 MB)
#   runtime-image/bin/java -jar core/app/cli/build/libs/unidrive-*.jar --version
```

The image's JDK module set is pinned — a new dependency that changes it fails
`check` (see `RuntimeModulesTest`), and CI runs the whole gate a second time
with the engine executing on the bundled runtime JDK. Launchers honour
`UNIDRIVE_XMX` (bare size, e.g. `512m`, `2g`; default `2g`) and pin
`-Djdk.net.unixdomain.tmpdir` to the host's temp directory. The bundled runtime
follows the JDK release train: each feature release ships as a normal app
update, with the LTS marks as the long-haul targets.

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
