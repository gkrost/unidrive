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
| Global config | `~/.config/unidrive/config.toml` | TOML | Profiles + provider defaults. Schema: [config-schema/config.schema.json](config-schema/config.schema.json); annotated example: [config-schema/config.example.toml](config-schema/config.example.toml). The CLI also resolves `%APPDATA%\unidrive\config.toml` and `~/Library/Application Support/unidrive/config.toml` for community best-effort use on Win/macOS, but neither is in MVP scope per [ADR-0012](adr/0012-linux-mvp-protocol-removal.md). |
| Encrypted vault | `{profile}/vault.enc` | AES-GCM, passphrase-derived | Passphrase hardening (rotation, lockout) is on the roadmap as a doc-only wishlist — no UD ticket yet. |
| OAuth tokens | `{profile}/token.json` | JSON | POSIX mode `0600` via `setPosixPermissionsIfSupported` (UD-347). |
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

## Shared cross-provider utilities (`:app:core` and `:app:sync`)

Helpers that ≥ 2 providers need live in `:app:core` (the no-runtime-deps
contract module) or `:app:sync` (helpers that already lean on sync-side
concerns like state.db cursors). **Before adding a new shared concern
to a provider, grep this section first** — if the helper already
exists, import it. If you find yourself copy-pasting across providers,
lift it here and update this list.

| Package | File | Purpose | UD origin |
|---|---|---|---|
| `org.krost.unidrive.http` | `RequestIdPlugin.kt` | Per-request correlation id Ktor plugin (`X-Unidrive-Request-Id`) | UD-255 |
| `org.krost.unidrive.http` | `HttpRetryBudget.kt` | Cross-call circuit breaker + token bucket | UD-232 |
| `org.krost.unidrive.http` | `ErrorBody.kt` | `truncateErrorBody`, `readBoundedErrorBody` | UD-336 |
| `org.krost.unidrive.http` | `UploadTimeoutPolicy.kt` | Size-adaptive request timeout for data-plane PUTs | UD-337 |
| `org.krost.unidrive.http` | `StreamingUpload.kt` | `streamingFileBody(localPath, fileSize)` with UD-287 finally-flushAndClose | UD-342 |
| `org.krost.unidrive.http` | `HtmlBodySniffGuard.kt` | `assertNotHtml(response, contextMsg?)` — captive-portal / throttle-redirect guard | UD-340 |
| `org.krost.unidrive.auth` | `Pkce.kt` | RFC 7636 verifier + challenge | UD-351 |
| `org.krost.unidrive.auth` | `OAuthCallbackServer.kt` | `awaitOAuthCallback(port, expectedState, providerLabel, timeout)` — single-shot loopback `ServerSocket` + parse-and-validate the redirect | UD-348 |
| `org.krost.unidrive.auth` | `CredentialStore.kt` | Generic `CredentialStore<T>(dir, fileName, serializer, validate)` — `load()` / `save()` / `delete()` with UD-312 atomic move + `validate(T)` shape guard + UD-347 chmod baked in | UD-344 |
| `org.krost.unidrive.io` | `PosixPermissions.kt` | `setPosixPermissionsIfSupported` for token-file storage | UD-347 |
| `org.krost.unidrive.io` | `OpenBrowser.kt` | `openBrowser(url)` — `Desktop.browse` with `xdg-open` / `open` / `start` fallback | UD-348 |
| `org.krost.unidrive` (root) | `SharedJson.kt` | `UnidriveJson` — `Json { ignoreUnknownKeys; isLenient }` | UD-343 |
| `org.krost.unidrive.sync` (`:app:sync`) | `Snapshot.kt` | Generic `Snapshot<E>` snapshot-cursor wrapper (`entries: Map<String, E>` + `timestamp: Long` + Base64-of-JSON encode/decode); on-disk-compatible with the pre-UD-345 per-provider classes | UD-345 |
| `org.krost.unidrive.sync` (`:app:sync`) | `SnapshotDeltaEngine.kt` | `computeSnapshotDelta(currentEntries, currentItemsByPath, prevCursor, entrySerializer, hasChanged, deletedItem)` — shared diff loop for snapshot-based providers | UD-346 |

Adoption status: every Ktor-using provider (OneDrive, HiDrive,
Internxt, S3, WebDAV) installs the helpers above where applicable.
SFTP and LocalFs are non-HTTP and use only the JSON / I/O helpers.

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
- **webhook**: push notifications from the provider. OneDrive subscriptions are fully wired with scheduled `(expiry − 24h)` renewal via `SubscriptionRenewalScheduler` ([UD-303](backlog/CLOSED.md#ud-303), closed). `SubscriptionStore` is consumed by `SyncCommand.kt:480-541`.
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
