# Architecture

> Narrative overview. For the normative intent catalog — doc-vs-code deltas, provenance, IPC wire formats, capability matrix, inherited open questions — see [SPECS.md](SPECS.md). Revisions tracked via [ADRs](adr/).

## System tiers

```
+-----------------+
|   core daemon   |
|   (Kotlin/JVM)  |
|   v0.1.0 GA     |
+--+-----+--------+
   |     |
   | CLI | MCP (stdio JSON-RPC)
   |     |
   v     v
 user / scripts / model clients
```

**Single product, single tier, Linux MVP.** v0.1.0 ships the `core/`
Kotlin daemon on Linux. The CLI emits NDJSON sync events over UDS via
[`IpcProgressReporter.kt`](../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt)
— `unidrive log --watch` consumes them, and so can any third-party
listener.

## core/ module graph

Derived from `core/settings.gradle.kts` and the `:app:*` build files.

```
app:cli ──┬─→ app:core
          ├─→ app:sync  ──→ app:core
          ├─→ app:xtra  ──→ app:core
          ├─→ app:mcp   ──→ app:core + app:sync + app:xtra
          └─→ providers/*  ──→ app:core  (discovered via SPI)

app:mcp   ──→ all providers (ServiceLoader at runtime)
```

Providers implement `CloudProvider` (download, upload, delete, delta, quota, share) and register via `META-INF/services/org.krost.unidrive.ProviderFactory`. See [§Provider capability matrix](#provider-capability-matrix) below for the current support matrix.

## IPC topology

| Caller | Transport | Codec |
|--------|-----------|-------|
| `unidrive log --watch` (CLI sub-command) | Unix-domain socket at `$XDG_RUNTIME_DIR/unidrive-<profile>.sock` (mode `0600`) | NDJSON, hand-built `JsonObject` per frame in `IpcProgressReporter.kt` |
| Third-party listener | Same UDS, same NDJSON shape | (consumer's choice) |
| MCP client (external) | stdio JSON-RPC (separate surface) | JSON-RPC 2.0 |

NDJSON event shapes are defined inline in `IpcProgressReporter.kt`.
Listeners parse defensively: unknown ops dropped, additional fields
ignored. When a second consumer materialises that justifies a formal
schema directory, see [ADR-0012 §Re-opening criteria](adr/0012-linux-mvp-protocol-removal.md#re-opening-criteria).

## Provider plugin model

- **Discovery:** `java.util.ServiceLoader` on `ProviderFactory` SPI.
- **Contract:** `core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt` (list, read, write, delete, delta, quota, share, webhook).
- **Capability gaps:** `deltaWithShared()` and `handleWebhookCallback()` are only overridden by `onedrive`; others no-op. UD-302 / UD-303 formalize this via an `UNSUPPORTED` return per ADR-0005.

## Persistence

| Artifact | Location | Format | Notes |
|----------|----------|--------|-------|
| Global config | `~/.config/unidrive/config.toml` (POSIX) / `%APPDATA%\unidrive\config.toml` (Win) | TOML | Profiles + provider defaults. Schema: [config-schema/config.schema.json](config-schema/config.schema.json); annotated example: [config-schema/config.example.toml](config-schema/config.example.toml). |
| Encrypted vault | `{profile}/vault.enc` | AES-GCM, passphrase-derived | UD-xxx: passphrase hardening TBD |
| OAuth tokens | `{profile}/token.json` | JSON | POSIX mode `0600`; Windows: DPAPI TBD |
| Sync state | `{profile}/.state.db` | SQLite | `SyncEntry`, `PinRule`, `ConflictLog` |
| Process lock | `{profile}/.lock` | pidfile | Per-profile single-instance guard |

## Async model

- Kotlin coroutines throughout (`kotlinx-coroutines-core`).
- Sync engine uses structured concurrency; `ThrottledProvider` applies per-provider rate limits.
- Rclone adapter spawns a subprocess via array-form `ProcessBuilder` (safe against shell injection).
- SFTP: Apache SSHD. HTTP providers: Ktor client.

## Key files (v0.1.0 scope)

- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` — CLI entry, config+vault merge.
- `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/McpServer.kt` — JSON-RPC dispatch.
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt` — 3-way merge.
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt` — SQLite persistence.
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` — UDS event-broadcast server (Linux/POSIX).
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt` — emits sync-progress NDJSON events over the UDS.
- `core/app/xtra/src/main/kotlin/org/krost/unidrive/xtra/Vault.kt` — encrypted credential vault.
- `core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt` — provider contract.

## Platform support

> The MVP target is **Linux**. macOS and Windows are listed for honesty
> about what does or does not currently boot the JVM modules, but
> neither platform is in scope for v0.0.x → v0.1.0. See
> [ADR-0012](adr/0012-linux-mvp-protocol-removal.md) for the
> reasoning.

Legend: ✅ supported · 🟡 community best-effort (no support) · ❌ not in scope

| Tier / platform | Linux (glibc) | macOS 12+ | Windows 10/11 | Notes |
|---|:-:|:-:|:-:|---|
| `core/` CLI | ✅ | 🟡 | 🟡 | JVM 21 LTS; tested only on Linux |
| `core/` MCP server | ✅ | 🟡 | 🟡 | stdio JSON-RPC — platform-independent |
| `core/` sync engine | ✅ | 🟡 | 🟡 | SQLite state DB; `ProcessLock` via lockfile |

> No first-party GUI tier. The UDS broadcast surface
> (`IpcProgressReporter.kt` over `$XDG_RUNTIME_DIR/unidrive-<profile>.sock`)
> is open for a community-built tray / third-party indicator to
> subscribe to. See [ADR-0013](adr/0013-ui-removal.md) for the
> re-opening criteria if a first-party GUI ever returns.

> File-manager / shell-extension tiers (Linux FUSE, macOS
> `FileProviderExtension`, Windows CFAPI) are **out of scope**. See
> [ADR-0011](adr/0011-shell-win-removal.md) and
> [ADR-0012 §Re-opening criteria](adr/0012-linux-mvp-protocol-removal.md#re-opening-criteria)
> for what would have to change for shell-tier work to come back.

### Known platform quirks

- **Systemd user services** — Linux deployment uses systemd `--user`; older init systems (SysVinit, OpenRC) require manual start scripts.

## Provider capability matrix

> Source of truth per [ADR-0005](adr/0005-provider-capability-contract.md);
> updates to this section must accompany adapter-level changes.

Legend: ✅ supported · 🟡 partial (degrades gracefully) · ❌ `Capability.Unsupported` · ❓ unknown / needs audit

| Provider | list | read | write | delete | delta | deltaShared | webhook | share | quotaExact | MVP v0.1.0 |
|----------|:----:|:----:|:-----:|:------:|:-----:|:-----------:|:-------:|:-----:|:----------:|:----------:|
| **localfs** | ✅ | ✅ | ✅ | ✅ | ✅ (walk) | ❌ | ❌ | ❌ | ✅ | ✅ **in scope** |
| **s3** | ✅ | ✅ | ✅ | ✅ | 🟡 (snapshot) | ❌ | ❌ | ✅ (presigned) | 🟡 (estimate) | ✅ **in scope** |
| **sftp** | ✅ | ✅ | ✅ | ✅ | 🟡 (snapshot) | ❌ | ❌ | ❌ | 🟡 (df) | ✅ **in scope** |
| **onedrive** | ✅ | ✅ | ✅ | ✅ | ✅ (Graph `/delta`) | ✅ | ✅ (subscriptions) | ✅ (link) | ✅ | preview |
| **webdav** | ✅ | ✅ | ✅ | ✅ | 🟡 | ❌ | ❌ | ❓ | ❓ | preview |
| **rclone** | ✅ | ✅ | ✅ | ✅ | 🟡 (lsjson) | ❌ | ❌ | ❌ | 🟡 (`about`) | preview |
| **hidrive** | ✅ | ✅ | ✅ | ✅ | 🟡 | ❌ | ❌ | ❓ | ❓ | preview |
| **internxt** | ✅ | ✅ | ✅ | ✅ | 🟡 | ❌ | ❌ | ❓ | ❓ | preview |

### Change-signal source (per provider)

For the 🟡 snapshot-based adapters, the fields used to detect "did this item change since last scan" differ per provider:

| Provider | Fields compared | Notes |
|----------|-----------------|-------|
| s3 | `ETag` + `size` | ETag is unreliable across multipart uploads; size catches the gap |
| sftp | `mtime` + `size` | SSHD attrs; no native hash |
| webdav | `size` + (`getetag` or `getlastmodified`) | server-dependent — some emit only one |
| rclone | `hash` (preferred) or `modTime` + `size` | hash column present in `lsjson` when remote supports it |
| localfs | `Files.walk` + `mtime`/`size` | incremental watcher optional |

Adding a new snapshot adapter: pick the pair that gives the lowest false-positive rate and document it here.

### Notes per column

- **delta**: primary sync-engine input. All providers must supply some form; snapshot-based adapters (S3, SFTP, rclone) are marked 🟡 because their cost is O(tree) rather than incremental.
- **deltaShared**: enumerating items shared *with* the user (not *by* the user). Only Graph exposes this cleanly. Tracked by [UD-302](backlog/BACKLOG.md#ud-302).
- **webhook**: push notifications from the provider. OneDrive subscriptions are partially wired; the orphan `SubscriptionStore` is tracked in [UD-303](backlog/BACKLOG.md#ud-303).
- **share**: first-class "give me a URL I can share" API. Generic "copy file & email it" is not this capability.
- **quotaExact**: the provider returns true free/used/total. Estimated or derived values (from `df`, `about`, presence-based) are 🟡.

### Gating for v0.1.0

Only `localfs`, `s3`, `sftp` are in-scope for the MVP release quality gate. Others are preview: present, buildable, testable, but their bugs do not block v0.1.0.

## References

- [ADR-0001 Monorepo Layout](adr/0001-monorepo-layout.md)
- [ADR-0005 Provider Capability Contract](adr/0005-provider-capability-contract.md)
- [ADR-0008 Greenfield Restart](adr/0008-greenfield-restart.md) — context for the v0.0.x scope cut.
- [ADR-0011 shell-win Removal](adr/0011-shell-win-removal.md)
- [ADR-0012 Linux MVP & protocol/ Removal](adr/0012-linux-mvp-protocol-removal.md)
- [ADR-0013 ui/ Removal](adr/0013-ui-removal.md)
