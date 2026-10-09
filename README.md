# `unidrive`

Multi-platform cloud-sync core. Pure JVM, zero telemetry. Modular SPI for Internxt Drive (zero-knowledge E2EE) and Microsoft OneDrive (Graph API). Runs as a user-space Linux daemon via systemd; platform tiers consume the engine over IPC — [`unidrive-mount-linux`](https://github.com/gkrost/unidrive-mount-linux) (FUSE) and [`unidrive-windows`](https://github.com/gkrost/unidrive-windows) (CfAPI; new, changed, renamed and deleted files sync from the mount — unidrive-windows#86, then unidrive-windows#87).

## Technical Layout

```
.
├── core/
│   ├── app/
│   │   ├── cli/            # CLI entry point and subcommand mapping
│   │   ├── cli-spi/        # CLI extension SPI (`org.krost.unidrive.cli.ext`)
│   │   ├── core/           # Provider SPI (`CloudProvider`, `ProviderFactory`), auth and HTTP helpers
│   │   ├── engine-core/    # Shared engine primitives: state.db, path and scope rules, remote gather
│   │   ├── hydration/      # De/hydration pipeline
│   │   ├── sync/           # Sync engine (ships the MVP), IPC server
│   │   └── sync-tracking/  # Frozen tracking-set engine (`unidrive ts`)
│   └── providers/
│       ├── internxt/       # Zero-knowledge encrypted client
│       ├── localfs/        # No-auth local-directory provider for offline development and tests
│       └── onedrive/       # Microsoft Graph API client
└── dist/                   # User-space install scripts and systemd units
```

## Build

JDK 21+, Linux with `systemd --user`. The Gradle wrapper lives in `core/`.

```bash
cd core && ./gradlew :app:cli:shadowJar    # build fat JAR → core/app/cli/build/libs/unidrive-*.jar
```

## Packaged distribution (no user-installed JDK)

A platform package ships a `jlink` runtime image next to the fat JAR. The image
is jlinked from the bundled runtime JDK — the current JDK release train — while
the engine's bytecode stays at the compile toolchain level (`jlink` produces a
runtime of its own version, so the image JVM deliberately leads the bytecode
target):

```bash
cd core && ./gradlew :app:cli:shadowJar :app:cli:runtimeImage
# → core/app/cli/build/runtime-image/  (bin/java + legal/, ~50 MB)
#   core/app/cli/build/runtime-image/bin/java -jar core/app/cli/build/libs/unidrive-*.jar --version
```

The image's JDK module set is pinned — a new dependency that changes it fails
`check` (see `RuntimeModulesTest`), and CI runs the whole gate a second time
with the engine executing on the bundled runtime JDK. Launchers honour
`UNIDRIVE_XMX` (bare size, e.g. `512m`, `2g`; default `2g`) and pin
`-Djdk.net.unixdomain.tmpdir` to the host's temp directory. Launchers and the
CLI's auto-spawn write post-mortem diagnostics — fatal-crash `hs_err` logs, OOM
heap dumps, the daemon's bounded GC log — into `%LOCALAPPDATA%\unidrive\diagnostics`
(Windows) or `~/.local/share/unidrive/diagnostics` (Linux), overridable with
`UNIDRIVE_DIAG_DIR`; pruning stays manual. The bundled runtime
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

The installed unit runs `unidrive autostart`: mirror profiles use `sync --watch`, while mount profiles use `daemon run`. This keeps the background process aligned with the selected profile's fixed mode.

Daemon log: `~/.local/share/unidrive/unidrive.log`. Quick triage: `scripts/dev/log-watch.sh --summary`.

## Commands

`unidrive` uses a git-inspired subcommand design.

### Session & Identity
- `auth` — OAuth/token provisioning
- `profile` — multi-profile management (`add`, `list`, `remove`)
- `backup` — backup profiles (`add`, `list`)
- `logout` — destroy session, invalidate credentials

### Sync
- `sync` — run the sync engine (`SyncEngine`, the engine the MVP ships); the frozen tracking-set engine is reachable only as `ts`
- `refresh` — update `state.db` with remote changes through the running daemon (`--reset` re-enumerates the whole cloud tree)
- `apply` — drain pending transfers left by a `refresh`, without fetching remote changes again
- `status` — show alignment, transfers, exceptions
- `conflicts` — show recent conflicts; restore or clear conflict backups (`list`, `restore`, `clear`)
- `verify` — report-only audit of the sync root, `state.db` and a live remote listing (exit 0 converged, 1 divergence, 2 could not audit)
- `doctor` — read-only diagnostics: drift, staleness, destructive activity, scope, hydration
- `log` — show recent sync activity

### Daemon & Mount
- `daemon` — per-profile daemon: `run` (foreground until SIGTERM), `status`, `stop`
- `mount` — start the Linux FUSE co-daemon for a profile ([`unidrive-mount-linux`](https://github.com/gkrost/unidrive-mount-linux)); needs a running `daemon run`
- `migrate` — one-time conversion of a legacy profile to a fixed mirror or mount mode ([independent-profiles](docs/adr/independent-profiles.md)); only the read-only `inventory` step exists so far

### Storage
- `ls` — list a remote folder (no recursion; `--live` forces a provider query)
- `get` — download file content (hydrate)
- `pin / unpin` — add or remove an eager-download rule (glob pattern)
- `free` — dehydrate to sparse markers
- `quota` — show storage quota
- `share` — generate a shareable link for a file or folder
- `trash` — trash emulation (`list`, `restore`, `purge`)
- `versions` — versioned files (`list`, `restore`, `purge`)
- `relocate` — migrate data between cloud providers
- `vault` — credential vault (`init`, `encrypt`, `decrypt`, `change-passphrase`)
- `sweep` — detect and rehydrate zero-byte stub files (`--null-bytes` scan; `--dry-run` and `--rehydrate` modify it)

### Tracking-set engine (experimental, frozen)
- `ts` — `sync`, `claim`, `unclaim`, `status` (see `core/app/sync-tracking/README.md`)

## Key source files

- `ProviderFactory.kt` — SPI provider registration framework
- `SyncEngine.kt` — the sync engine behind `sync` and the daemon
- `TrackingEngine.kt` — the frozen tracking-set engine, reachable only as `unidrive ts ...`
- `HydrationImpl.kt` — physical payload de/reconstruction lifecycle
- `MountEngine.kt` — mount operations (cache, hydration verbs, mirror hand-off) since the engine split
