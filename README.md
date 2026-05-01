# UniDrive

**One CLI and one MCP server for eight cloud-storage providers — multi platform design
tested and developed for Linux, auditable, Apache-2.0.**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Status: v0.0.1 preview](https://img.shields.io/badge/status-v0.0.1%20preview-orange.svg)](docs/CHANGELOG.md)
[![Toolchain: JVM 21](https://img.shields.io/badge/JVM-21%20LTS-success.svg)](docs/adr/0006-toolchain.md)
[![Kotlin: 2.3.20](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF.svg)](docs/adr/0006-toolchain.md)
[![MVP target: Linux](https://img.shields.io/badge/MVP-Linux-informational.svg)](docs/adr/0012-linux-mvp-protocol-removal.md)

**Repository:** <https://github.com/gkrost/unidrive>

UniDrive aggregates multiple cloud-storage backends behind a single
sync engine. One config file. One CLI. One MCP server an LLM can
drive. Multiple providers in tree, swappable per profile, with a SQLite
state database and structured sync events over a Unix-domain socket.

> **Status: v0.0.1 — early public preview.** MVP target is **Linux**;
> macOS and Windows may build and run on best-effort. Public API,
> IPC contract, and on-disk layout are still moving. No prebuilt
> binaries yet — build from source. See
> [docs/CHANGELOG.md](docs/CHANGELOG.md) for what's in this tag and
> [docs/backlog/BACKLOG.md](docs/backlog/BACKLOG.md) for what's next.

---

## Table of contents

- [Why UniDrive](#why-unidrive)
- [Providers](#providers)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [CLI in 60 seconds](#cli-in-60-seconds)
- [MCP server (LLM integration)](#mcp-server-llm-integration)
- [Architecture](#architecture)
- [Platform support](#platform-support)
- [Linux console (CLI glyph rendering)](#linux-console-cli-glyph-rendering)
- [Security](#security)
- [Project status and roadmap](#project-status-and-roadmap)
- [License and naming](#license-and-naming)
- [Contributing](#contributing)
- [Contact](#contact)

---

## Why UniDrive

If your data lives across half a dozen clouds — OneDrive at work, an
S3 bucket for backups, an SFTP server at home, a WebDAV mount your
hoster provides, a rclone-shaped pile of everything else — you have
already discovered that no two vendors agree on what "sync" means.
UniDrive is one Kotlin daemon that knows about all of them.

Design choices that fall out of that:

- **Provider plugins are SPI-discovered.** Each backend is a
  `CloudProvider` implementation registered via
  `META-INF/services/org.krost.unidrive.ProviderFactory`. Adding a
  ninth provider is one module, not a fork.
- **One sync engine, not eight.** A 3-way reconciler in
  [`Reconciler.kt`](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt)
  handles delta merging; provider adapters supply truth (`/delta`
  where the API exposes one, `walk + mtime/size/etag` snapshots
  where it doesn't). See the
  [provider capability matrix](docs/ARCHITECTURE.md#provider-capability-matrix)
  for what each adapter actually delivers.
- **Profiles, not global state.** Every provider connection is a
  named profile with its own OAuth tokens, its own SQLite state DB,
  and its own UDS socket. Two OneDrive accounts side-by-side is a
  two-line config change.
- **Trust-auditable.** The repo carries the threat model
  ([docs/SECURITY.md](docs/SECURITY.md), STRIDE-anchored to
  file:line), the ADR trail ([docs/adr/](docs/adr/)), and the
  spec-vs-code intent catalog ([docs/SPECS.md](docs/SPECS.md)). No
  hidden control plane, no telemetry — your bytes stay between you
  and the providers you configured.
- **LLM-native.** The MCP server in
  [`core/app/mcp/`](core/app/mcp/) exposes the full sync surface as
  JSON-RPC tools over stdio so any
  [Model Context Protocol](https://modelcontextprotocol.io/) client
  (Claude Desktop, Claude Code, others) can list, sync, share, and
  inspect quotas without shelling out.

---

## Providers

| Provider | Transport | Delta source | Quota | v0.1.0 MVP |
|---|---|---|---|:-:|
| **localfs** | filesystem | `Files.walk` + `mtime`/`size` | exact | ✅ |
| **s3** / S3-compatible | AWS SDK | snapshot (ETag + size) | estimate | ✅ |
| **sftp** | Apache SSHD | snapshot (mtime + size) | `df`-based | ✅ |
| **onedrive** | Microsoft Graph | Graph `/delta` (incremental) | exact | preview |
| **webdav** | Ktor HTTP | snapshot (size + ETag/Last-Modified) | server-dependent | preview |
| **rclone** | subprocess bridge | `lsjson` (hash where remote supports it) | `rclone about` | preview |
| **hidrive** | IONOS REST | snapshot | server-dependent | preview |
| **internxt** | Internxt API | snapshot | server-dependent | preview |

Legend: ✅ MVP-quality gate — bugs in these block v0.1.0. *Preview*
adapters are present, buildable, and unit-tested but their bugs do
not block the MVP.

Full capability matrix (read / write / delta / deltaShared / webhook
/ share / quotaExact, per provider) lives at
[docs/ARCHITECTURE.md §Provider capability matrix](docs/ARCHITECTURE.md#provider-capability-matrix).
Per-provider engineering notes — vendor-recommendation citations,
known gaps, edge cases — live under
[docs/providers/](docs/providers/).

---

## Quick start

UniDrive is a Kotlin/JVM project; the daemon is a Gradle composite at
[`core/`](core/).

### Build from source

```bash
git clone https://github.com/gkrost/unidrive
cd unidrive/core
./gradlew build test
```

Toolchain requirements (per [ADR-0006](docs/adr/0006-toolchain.md)):

- **JDK 21 LTS** (Gradle's `jvmToolchain(21)` will provision one if
  you have a Foojay-aware setup; otherwise install OpenJDK 21
  manually).
- **Git** (for the `BuildInfo` short-SHA stamping at build time).

The build produces shadow jars at:

- `core/app/cli/build/libs/unidrive-x.y.z-suffix.jar` — the CLI.
- `core/app/mcp/build/libs/unidrive-mcp-x.y.z-suffix.jar` — the
  MCP server.

### Smoke-test the build

```bash
./scripts/smoke.sh
```

Spins up a localfs profile in a temp directory, drops a marker file,
runs `sync`, asserts round-trip. No network, no credentials.

### Deploy to your `$PATH`

```bash
# From core/, build deploy targets
./gradlew :app:cli:deploy :app:mcp:deploy
```

The `deploy` task copies the shadow jar into a launcher script. Where
exactly is platform-dependent — see
[docs/dev/TOOLS.md](docs/dev/TOOLS.md) for the per-platform paths.

---

## Configuration

UniDrive reads a single TOML file:

| Platform | Path |
|---|---|
| Linux | `~/.config/unidrive/config.toml` |
| macOS | `~/Library/Application Support/unidrive/config.toml` |
| Windows | `%APPDATA%\unidrive\config.toml` |

A canonical annotated example with every option lives at
[`docs/config-schema/config.example.toml`](docs/config-schema/config.example.toml);
the JSON Schema (draft 2020-12) at
[`docs/config-schema/config.schema.json`](docs/config-schema/config.schema.json)
covers per-provider conditional requirements.

Minimal two-profile example:

```toml
[general]
# Default profile when no `-p` flag is passed.
default_profile = "home"

[profiles.home]
type = "localfs"
local_root = "~/CloudSync/home"
remote_root = "/home"

[profiles.work-onedrive]
type = "onedrive"
local_root = "~/CloudSync/work"
client_id = "<your azure-ad app client id>"
# OAuth tokens are stored separately under `{profile}/token.json`,
# not in this file.
```

Every profile gets:

- `{profile}/.state.db` — SQLite state (per-file `SyncEntry`,
  pinning, conflict log).
- `{profile}/token.json` — OAuth tokens (mode `0600` on POSIX).
- `{profile}/.lock` — pidfile (single-instance guard).
- `{profile}/vault.enc` — optional AES-GCM encrypted credential
  vault.

Storage layout is documented at
[docs/ARCHITECTURE.md §Persistence](docs/ARCHITECTURE.md#persistence).

---

## CLI in 60 seconds

Pick a profile with `-p <name>` (or fall back to `default_profile`):

```bash
# What does this profile think the world looks like?
unidrive -p work-onedrive status
unidrive -p work-onedrive quota

# List remote directory
unidrive -p work-onedrive ls /Documents

# Sync (bidirectional by default; --upload-only / --download-only flip it)
unidrive -p work-onedrive sync

# Pull one tree
unidrive -p work-onedrive get /Photos/2026

# Watch live sync events from a separate shell
unidrive -p work-onedrive log --watch
```

Multi-profile fan-out:

```bash
# Status across every configured profile
unidrive status --all
```

Every CLI log line is prefixed with the daemon's short git SHA
(e.g. `[3d81c49]`) so a tail of `unidrive.log` is self-identifying
when pasted into a bug report (UD-234).

---

## MCP server (LLM integration)

UniDrive ships an MCP server alongside the CLI, exposing the same
operations as JSON-RPC tools so an MCP-aware LLM client can drive it.

### Wire it into Claude Desktop / Claude Code

```json
{
  "mcpServers": {
    "unidrive": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/unidrive-mcp-x.y.z-suffix.jar"
      ]
    }
  }
}
```

After a client restart you should see tools like `unidrive_status`,
`unidrive_sync`, `unidrive_ls`, `unidrive_quota`, `unidrive_share`,
and friends. The full set is defined in
[`core/app/mcp/`](core/app/mcp/). Integration coverage lives in
`McpRoundtripIntegrationTest` (six end-to-end JSON-RPC roundtrips
against a temp localfs profile, spawned via `ProcessBuilder`).

### Sync events over UDS

The daemon also broadcasts sync progress as NDJSON over a
Unix-domain socket at `$XDG_RUNTIME_DIR/unidrive-<profile>.sock`
(mode `0600`). The frame shape is defined inline in
[`IpcProgressReporter.kt`](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt).
`unidrive log --watch` is one consumer; any third-party tray /
indicator / dashboard can subscribe.

---

## Architecture

```
+-----------------+
|   core daemon   |
|   (Kotlin/JVM)  |
+--+-----+--------+
   |     |
   | CLI | MCP (stdio JSON-RPC)
   |     |
   v     v
 user / scripts / model clients
```

```
app:cli ──┬─→ app:core
          ├─→ app:sync  ──→ app:core
          ├─→ app:xtra  ──→ app:core
          ├─→ app:mcp   ──→ app:core + app:sync + app:xtra
          └─→ providers/*  ──→ app:core   (discovered via SPI)
```

Key files:

- [`core/app/cli/.../Main.kt`](core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt) — CLI entry, config + vault merge.
- [`core/app/mcp/.../McpServer.kt`](core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/McpServer.kt) — JSON-RPC dispatch.
- [`core/app/sync/.../Reconciler.kt`](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt) — 3-way merge.
- [`core/app/sync/.../StateDatabase.kt`](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt) — SQLite persistence.
- [`core/app/sync/.../IpcServer.kt`](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt) — UDS event-broadcast server.
- [`core/app/core/.../CloudProvider.kt`](core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt) — provider contract.

For the narrative architecture overview see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). For the normative intent
catalog (every doc claim labelled 📄 doc-only / 💻 code-verified
/ ✅ agree / ⚠ disagreement, with file:line anchors) see
[docs/SPECS.md](docs/SPECS.md). All structural decisions are
documented as ADRs under [docs/adr/](docs/adr/).

---

## Platform support

> The MVP target is **Linux**. macOS and Windows are listed for
> honesty about what does or does not currently boot the JVM
> modules, but neither platform is in the v0.0.x → v0.1.0 scope.
> See [ADR-0012](docs/adr/0012-linux-mvp-protocol-removal.md).

| Tier / platform | Linux (glibc) | macOS 12+ | Windows 10/11 |
|---|:-:|:-:|:-:|
| `core/` CLI | ✅ | 🟡 | 🟡 |
| `core/` MCP server | ✅ | 🟡 | 🟡 |
| `core/` sync engine | ✅ | 🟡 | 🟡 |

Legend: ✅ supported · 🟡 community best-effort (no support) · ❌ not in scope

There is **no first-party GUI tier** in v0.0.x. The UDS broadcast
surface is open for community trays / third-party indicators that
want to subscribe — see [ADR-0013](docs/adr/0013-ui-removal.md) for
re-opening criteria. File-manager / shell-extension tiers (Linux
FUSE, macOS `FileProviderExtension`, Windows CFAPI) are out of scope;
see [ADR-0011](docs/adr/0011-shell-win-removal.md) and
[ADR-0012 §Re-opening criteria](docs/adr/0012-linux-mvp-protocol-removal.md#re-opening-criteria).

---

## Linux console (CLI glyph rendering)

The CLI emits unicode badges (`✓`, `✗`), tree connectors (`├─`, `└─`)
and provider icons (`☁`, `⚠`). On a UTF-8 Linux terminal these render
without ceremony. If you build the CLI on Windows for personal use
(unsupported per [ADR-0012](docs/adr/0012-linux-mvp-protocol-removal.md))
the rest of this section walks you through the two console quirks
that will otherwise break the table layout. The Windows-specific
notes are retained for non-MVP / community-best-effort use.

### Windows console (community best-effort)

For the glyphs to render correctly on Windows two things must be true:

1. **Stdout encoding is UTF-8.** The launcher already passes
   `-Dstdout.encoding=UTF-8` (UD-258). PowerShell 7+ inherits this;
   on PowerShell 5.1 add
   `[Console]::OutputEncoding = [Text.UTF8Encoding]::new()` to your
   `$PROFILE`. To skip glyphs entirely (CI / log scraping), set
   `UNIDRIVE_ASCII=1` and the CLI emits ASCII fallbacks (`[OK]`,
   `+--`, `[CLD]`).
2. **The console has emoji-font fallback.** Windows Terminal does
   this automatically (`Segoe UI Emoji`); Legacy Console
   (`conhost.exe`) does not. UD-259 detects this via `WT_SESSION`
   and downgrades the Misc-Symbols tier to ASCII on `conhost.exe`.

### Font note (UD-259)

Misc-Symbols glyphs like `☁` (U+2601) have two Unicode-defined
presentations: an **emoji** form (≈2 cells wide, served from
`Segoe UI Emoji` via fallback) and a **text** form (1 cell). The CLI
appends VS15 (U+FE0E) to every Misc-Symbols glyph it emits,
requesting the text form so table-column math stays honest.

If your monospace font has a native text-presentation glyph for
U+2601 (and its neighbours), you'll see crisp single-width icons.
Cascadia Code does **not** have one — Windows falls back to
`Segoe UI Emoji`, which still renders wider than the cell even with
VS15. Fonts that ship a single-cell text-form glyph for the
Misc-Symbols block:

- **JetBrains Mono**
- **Sarasa Mono**
- **Maple Mono**

If you see `.notdef` boxes where icons should be, your font has
neither form — switch to one of the above or pin Cascadia and accept
the wide emoji rendering.

---

## Security

- **Reporting:** [`SECURITY.md`](SECURITY.md) — email
  `unidrive@krost.org` with subject `[SECURITY] <short summary>`.
- **Threat model:** [`docs/SECURITY.md`](docs/SECURITY.md) — asset
  inventory (A1..A8), data-flow diagram with trust boundaries, full
  STRIDE table with mitigations anchored to file:line.
- **Baseline:** [ADR-0004](docs/adr/0004-security-baseline.md).

Scanner integration in tree:

- **gitleaks** — `.gitleaks.toml` + `scripts/ci/gitleaks.sh` +
  pre-push hook (UD-107).
- **Semgrep** — `.semgrep.yml` + `.semgrep/` rules (UD-109).
- **Trivy** — `.trivyignore` (UD-108).
- **Dependabot** — `.github/dependabot.yml` (UD-110).

Token handling: OAuth tokens are stored at `{profile}/token.json`
with POSIX mode `0600`. Vault encryption is AES-GCM with a
passphrase-derived key (passphrase hardening is on the backlog).

---

## Project status and roadmap

**v0.0.1 (this release)** — name-claim public preview. Greenfield
monorepo at `core/`, eight provider adapters in tree, MCP integration
tests landing, scanner/CI workflow scaffolded. No prebuilt binaries.

**v0.1.0-mvp (TBD)** — gates documented at
[docs/CHANGELOG.md](docs/CHANGELOG.md):

- All UD-2xx core backlog items at priority `high` closed.
- Test coverage ≥ 60% on `core/app/sync` and `core/app/mcp` (UD-801).
- Smoke script `scripts/smoke.sh` passes end-to-end.
- Security baseline: gitleaks (UD-107) and the STRIDE threat model in
  `docs/SECURITY.md` (UD-112) shipped. Per-frame validation /
  rate-limit / SDDL items (UD-101 / UD-105 / UD-106) closed via
  [ADR-0012](docs/adr/0012-linux-mvp-protocol-removal.md) along with
  the named-pipe transport they protected.
- Provider scope: **localfs, s3, sftp**. Others remain preview.

**v0.2.0 and beyond** — the
[backlog](docs/backlog/BACKLOG.md) tracks specific tickets; the
forward-looking specs (e.g. the
[relocate-v1](docs/specs/relocate-v1.md) operation contract) live
under [docs/specs/](docs/specs/).

Every code change references a `UD-###` backlog ID. Backlog
mutations go through `python scripts/dev/backlog.py` — never
hand-edit `BACKLOG.md` / `CLOSED.md`. The full contract is
[docs/AGENT-SYNC.md](docs/AGENT-SYNC.md).

---

## License and naming

**License:** [Apache 2.0](LICENSE). Apache 2.0 explicitly permits
proprietary derivative works, including closed-source commercial
software. The source you incorporate must keep its copyright notice
plus the [LICENSE](LICENSE) and [NOTICE](NOTICE) files (Apache 2.0
§4); the rest of your derivative work can be under any license of
your choosing.

**Naming:** "UniDrive" is a project name. See
[NAMING.md](NAMING.md) for the full naming policy and
the rules for forks and derivatives. The author is also building a
non-OSS Android app on this codebase — Apache 2.0's permissive grant explicitly allows that, and contributors who sign off (`git commit -s`) understand those patches are licensed permissively to anyone.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full contributor
contract. The short version:

1. **Discuss first** for non-trivial work — open an issue with the
   problem and the proposed direction before writing more than a few
   hours of code.
2. **Sign your commits** (`git commit -s`). The
   [Developer Certificate of Origin](https://developercertificate.org/)
   applies.
3. **Match backlog discipline.** Every change references a `UD-###`
   ticket; allocate one with
   `python scripts/dev/backlog.py next-id <category>`.
4. **Build and test locally.**
   ```bash
   cd core && ./gradlew build test
   ./scripts/smoke.sh
   ```

Read before bigger changes:

- [docs/dev/CODE-STYLE.md](docs/dev/CODE-STYLE.md) — Kotlin style,
  UTF-8/LF discipline, conventional-commit format.
- [docs/dev/lessons/](docs/dev/lessons/) — six short notes on the
  rakes contributors keep stepping on (jar hot-swap on Windows,
  ktlint baseline drift, Kotlin trailing-lambda parameter ordering,
  MDC inside suspend functions, verify-before-narrative,
  halted-agent WIP leaks).
- [docs/AGENT-SYNC.md](docs/AGENT-SYNC.md) — backlog-edit contract.
- [docs/SPECS.md](docs/SPECS.md) — normative intent catalog.

---

## Contact

- **General / partnership / naming:** `unidrive@krost.org`
- **Security:** `unidrive@krost.org` with subject prefix
  `[SECURITY]`
- **Bug reports / feature requests:** GitHub Issues at
  <https://github.com/gkrost/unidrive/issues> — please include
  the `[<short-sha>]` prefix from your `unidrive.log` so the daemon
  build is identifiable.

Maintainer: Gernot Krost.
Apache-2.0, copyright 2026, see [NOTICE](NOTICE).
