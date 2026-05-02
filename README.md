# UniDrive

**One CLI and one MCP server for multiple cloud-storage providers — Linux-first, auditable, Apache-2.0.**

[![Build](https://github.com/gkrost/unidrive/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/gkrost/unidrive/actions/workflows/build.yml?query=branch%3Amain)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Why** — your data lives across multiple clouds and no two vendors agree on what "sync" means. UniDrive is one Kotlin daemon that knows about all of them: one config, one CLI, one MCP server an LLM can drive, one SQLite state DB per profile, structured sync events over a Unix-domain socket. No telemetry, no hidden control plane.

**What** — a Kotlin/JVM monorepo at [`core/`](core/) with provider plugins discovered via SPI (`META-INF/services/org.krost.unidrive.ProviderFactory`). Adapters in tree: localfs, s3, sftp, onedrive, webdav, rclone, internxt. The reconciler ([`Reconciler.kt`](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt)) is provider-agnostic; adapters supply truth via `/delta` where the API exposes one, `walk + mtime/size/etag` snapshots where it doesn't. Capability matrix at [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

**How** — build from source:

```bash
git clone https://github.com/gkrost/unidrive
cd unidrive/core
./gradlew build test
./scripts/smoke.sh                  # localfs round-trip, no network
./gradlew :app:cli:deploy :app:mcp:deploy
```

Config is a single TOML at `~/.config/unidrive/config.toml` (Linux). Annotated example: [`docs/config-schema/config.example.toml`](docs/config-schema/config.example.toml). The MCP server (shadow jar at `core/app/mcp/build/libs/`) wires into any [Model Context Protocol](https://modelcontextprotocol.io/) client over stdio.

## Status

Early public preview. Linux is the MVP target; macOS and Windows may build on best-effort. Public API, IPC contract, and on-disk layout are still moving. No prebuilt binaries — build from source. Roadmap and gates: [docs/CHANGELOG.md](docs/CHANGELOG.md), [docs/backlog/BACKLOG.md](docs/backlog/BACKLOG.md).

## Trust

- **Threat model** — [docs/SECURITY.md](docs/SECURITY.md) (STRIDE, file:line-anchored).
- **ADR trail** — [docs/adr/](docs/adr/).
- **Spec-vs-code intent catalog** — [docs/SPECS.md](docs/SPECS.md).
- **Reporting security issues** — [`SECURITY.md`](SECURITY.md), `unidrive@krost.org`, subject `[SECURITY] …`.

## License & contributing

Apache-2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE), [NAMING.md](NAMING.md). Contributor contract in [CONTRIBUTING.md](CONTRIBUTING.md): discuss first for non-trivial work, sign commits (`git commit -s`), reference a `UD-###` backlog ID. Backlog mutations go through `python scripts/dev/backlog.py`, never hand-edited.

Maintainer: Gernot Krost · <unidrive@krost.org>
