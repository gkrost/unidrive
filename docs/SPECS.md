# UniDrive — specification

> Normative intent catalog. Each claim is marked **📄 doc-only**
> (described here, not yet verified in code), **💻 code-verified**
> (observed in the current tree with a file:line anchor), **✅
> doc+code agree**, or **⚠ disagreement** (doc says one thing, code
> says another — these need a decision before they can ship).
> [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) is the coarser narrative;
> SPECS.md is the normative catalog with the file:line anchors.

## 0. Product at a glance

**UniDrive** — multi-provider bidirectional cloud-storage sync. The
local file shape is a **0-byte stub + `isHydrated=false` row in the
state DB** until first open / explicit `unidrive get` / pin-rule
match, at which point the provider streams real bytes into the file.
The earlier sparse-file approach (UD-222 history: `setLength(size)` to
make stat report cloud size with zero blocks allocated) was reverted
because (a) on NTFS `setLength` allocates real NUL bytes — a 346 GB
OneDrive turned into 346 GB of zeroes on disk during UD-712 — and
(b) even on Linux where `setLength` is sparse, apps opening the stub
see NUL bytes, indistinguishable from corruption. True OS-level
placeholders belong to a future shell tier (CFAPI / FileProvider /
FUSE), not user-space file sleight-of-hand. See `PlaceholderManager.kt:40-46`
for the rationale comment in the code.

**Platform scope (per [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)):** Linux is the MVP target.
Windows and macOS are out of scope for v0.0.x → v0.1.0. The CLI and
MCP server still build on the JVM on either platform; provider
adapters are JVM-only and platform-agnostic; but neither non-Linux
platform has CI, packaging, or support claims. See ADR-0012
§"Re-opening criteria for Windows / macOS support" for what would
have to change to bring them back.

## 1. Architecture — intent vs implementation

### 1.1 Module graph (13 Gradle modules)

| Module | Intent (doc) | Current path | Status |
|--------|--------------|--------------|--------|
| `app/core` | Provider-agnostic interfaces (CloudProvider, CloudItem, DeltaPage, ProviderRegistry SPI) | [`core/app/core/`](../core/app/core) | ✅ |
| `app/sync` | Three-phase sync engine (gather→reconcile→apply), SQLite state DB, inotify watcher, placeholder management, conflict log, credential vault | [`core/app/sync/`](../core/app/sync) | ✅ |
| `app/xtra` | AES-256-GCM client-side encryption with RSA-2048-OAEP key wrapping | [`core/app/xtra/`](../core/app/xtra) | ✅ |
| `app/cli` | PicoCLI, 19 subcommands | [`core/app/cli/`](../core/app/cli) | ✅ |
| `app/mcp` | MCP server with 15 tools | [`core/app/mcp/`](../core/app/mcp) | 💻 **15 tools verified** (`core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:20-24`) |
| `providers/onedrive` | MS Graph, OAuth2, webhook subscriptions, shared-folder support | [`core/providers/onedrive/`](../core/providers/onedrive) | ✅ |
| `providers/s3` | SigV4 HTTP, snapshot delta | [`core/providers/s3/`](../core/providers/s3) | ✅ |
| `providers/sftp` | Apache MINA SSHD, snapshot delta | [`core/providers/sftp/`](../core/providers/sftp) | ✅ |
| `providers/webdav` | Ktor+Basic, PROPFIND, MOVE | [`core/providers/webdav/`](../core/providers/webdav) | ✅ |
| `providers/rclone` | CLI gateway to 70+ backends | [`core/providers/rclone/`](../core/providers/rclone) | ✅ |
| `providers/hidrive` | IONOS HiDrive v2.1 | [`core/providers/hidrive/`](../core/providers/hidrive) | ✅ |
| `providers/internxt` | Internxt w/ client-side crypto | [`core/providers/internxt/`](../core/providers/internxt) | ✅ |
| `providers/localfs` | Local-to-local + testing | [`core/providers/localfs/`](../core/providers/localfs) | ✅ |

### 1.2 Tree layout

| Tier | Code | Role |
|------|------|------|
| JVM daemon | [`core/`](../core) | CLI + MCP + sync + providers — the entire v0.0.x → v0.1.0 product |

Removed (kept here for historical context — re-import criteria in each ADR):
- **`ui/`** — Swing/Compose-Desktop system-tray applet. Removed via [ADR-0013](adr/0013-ui-removal.md). Third-party trays / indicators can still consume the UDS event stream.
- **`shell-win/`** — C++20 / CfApi DLL for Windows Explorer integration. Removed via [ADR-0011](adr/0011-shell-win-removal.md).
- **`protocol/`** — JSON-Schema contract directory + golden NDJSON fixtures. Removed via [ADR-0012](adr/0012-linux-mvp-protocol-removal.md); event shapes now defined inline in `IpcProgressReporter.kt`.

### 1.3 Toolchain

| | Doc says | Code says | Note |
|---|----------|-----------|------|
| Kotlin | 2.3.20 ✅ | 2.3.20 ([`core/gradle/libs.versions.toml`](../core/gradle/libs.versions.toml)) | agree |
| JDK | 21 LTS ([`core/app/cli/build.gradle.kts:8`](../core/app/cli/build.gradle.kts#L8) — `jvmToolchain(21)`) | per [ADR-0006](adr/0006-toolchain.md). |
| Gradle | 9.4.1 ✅ | 9.4.1 | agree |

## 2. IPC — two wire protocols

Two distinct IPC surfaces exist. Each has its own transport and format. (A third surface — a Windows named pipe for `shell-win` ↔ daemon — was removed via [ADR-0012](adr/0012-linux-mvp-protocol-removal.md) when the shell tier was cut.)

### 2.1 Surface A — daemon → consumer (push-only event stream)

**Transport:** Unix-domain socket at `$XDG_RUNTIME_DIR/unidrive-<profile>.sock`, mode `0600`.

**Direction:** Push-only daemon-to-client broadcast. **Not** request-response.

**Framing:** NDJSON, one JSON object per line.

**Event shape** (💻 verified in code):

```
{"event":"sync_started","profile":"onedrive","timestamp":"2026-04-01T18:00:00Z"}
{"event":"scan_progress","profile":"...","phase":"...","count":42,"timestamp":"..."}
{"event":"action_count","profile":"...","total":1697,"timestamp":"..."}
{"event":"action_progress","profile":"...","index":529,"total":1697,"action":"upload","path":"/Photos/img.jpg","timestamp":"..."}
{"event":"transfer_progress","path":"...","bytes_transferred":123456,"total_bytes":987654,"profile":"...","timestamp":"..."}
{"event":"sync_complete","downloaded":10,"uploaded":3,"conflicts":0,"duration_ms":4521,"profile":"...","timestamp":"..."}
{"event":"sync_error","message":"...","profile":"...","timestamp":"..."}
{"event":"warning","message":"...","profile":"...","timestamp":"..."}
{"event":"poll_state","profile":"...","timestamp":"..."}
```

💻 **Verified**: [`IpcProgressReporter.kt:15-63`](../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt#L15) emits these shapes; state-dump replay for late joiners at [`IpcServer.kt:144-154`](../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt#L144). The `poll_state` event fires once per idle poll cycle so late-joining clients know the daemon is alive but idle.

**Late-joiner semantics:** clients connecting mid-sync receive an ordered state dump: `sync_started → scan_progress → action_count → action_progress` synthesized from cached `SyncState`. 💻 verified in `IpcServer.kt`.

**Limits (doc):** max 10 concurrent clients, 5-second write timeout, 256-slot async `Channel<String>` for broadcast.

### 2.2 Surface B — MCP JSON-RPC 2.0 over stdio

**Transport:** child process stdin/stdout. Framing: one JSON-RPC frame per line (no `Content-Length` prefixes).

**Tool set (15, 💻 verified** at `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:20-24`):

```
unidrive_status, unidrive_sync, unidrive_get, unidrive_free, unidrive_pin,
unidrive_conflicts, unidrive_ls, unidrive_config, unidrive_trash, unidrive_versions,
unidrive_share, unidrive_relocate, unidrive_watch_events, unidrive_quota, unidrive_backup
```

**Resources (3):** `unidrive://config`, `unidrive://conflicts`, `unidrive://profiles`.

MCP is a separate surface — it speaks to external LLM clients, not to the shell or the UI. ✅ verified by `McpRoundtripIntegrationTest` ([UD-202](backlog/CLOSED.md#ud-202)).

**Profile selection is process-scoped, not per-call.** The MCP server binds to exactly one profile at startup, chosen in this precedence (see [`core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:10`](../core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt#L10)):

1. `--profile <name>` CLI flag to the MCP launcher, or
2. `UNIDRIVE_PROFILE` environment variable, or
3. the first profile in `config.toml` (fallback via `resolveDefaultProfile(configDir)`).

A `profile` field inside a tool-call's `arguments` object is **silently ignored** — tool input schemas do not declare it. To drive a different profile, relaunch the MCP server with a different env or flag. Driving multiple profiles from one LLM conversation today means spawning multiple MCP processes; a per-call profile argument would require redesigning `ProfileContext` to be per-tool rather than per-process. Tracked loosely under UD-601 because the envelope migration is the natural place to add routing metadata.

## 3. Provider capability matrix

> Cross-checked against current adapters. The shorter capability summary lives in [ARCHITECTURE.md §Provider capability matrix](ARCHITECTURE.md#provider-capability-matrix); this version adds the per-provider auth + per-provider notes columns.

| Provider | list/read/write/delete | delta | deltaShared | webhook | share | quotaExact | Auth |
|----------|:-:|:-:|:-:|:-:|:-:|:-:|---|
| onedrive | ✅ | ✅ (Graph `/delta`) | ✅ | ✅ (subscriptions — no auto-renewal, [UD-303](backlog/BACKLOG.md#ud-303)) | ✅ (with expiry, password, revoke, list) | ✅ | OAuth2 PKCE + device code |
| s3 | ✅ | 🟡 (snapshot via ETag+size) | ❌ | ❌ | ✅ (presigned URL, X-Amz-Expires) | 🟡 (estimate) | SigV4 env-based |
| sftp | ✅ | 🟡 (snapshot via mtime+size) | ❌ | ❌ | ❌ | 🟡 (df) | SSH key / password |
| webdav | ✅ | 🟡 (PROPFIND snapshot) | ❌ | ❌ | ❓ audit pending | 🟡 (RFC 4331 PROPFIND; server-dependent — see [UD-325](backlog/CLOSED.md#ud-325)) | HTTP Basic; `trust_all_certs` wired via Ktor Apache5 engine + `PoolingAsyncClientConnectionManager` + `NoopHostnameVerifier` ([UD-104](backlog/CLOSED.md#ud-104); engine changed in [UD-326](backlog/CLOSED.md#ud-326)) |
| rclone | ✅ | 🟡 (lsjson snapshot) | ❌ | ❌ | ❌ | 🟡 (`rclone about`) | rclone config |
| hidrive | ✅ | 🟡 | ❌ | ❌ | ❓ | ❓ | OAuth2 |
| internxt | ✅ | 🟡 | ❌ | ❌ | ❓ | ❓ | REST + client-side AES-256 |
| localfs | ✅ | ✅ (`Files.walk`) | ❌ | ❌ | 🟡 (`file://` URI, see `LocalFsProvider.kt:189-192`) | ✅ | N/A |

**Release gating:** [ADR-0008](adr/0008-greenfield-restart.md) keeps v0.1.0-mvp to `localfs + s3 + sftp` only — matches [ARCHITECTURE.md §Provider capability matrix](ARCHITECTURE.md#provider-capability-matrix).

### 3.1 Provider quirks

- **OneDrive — Personal Vault ([UD-315](backlog/CLOSED.md#ud-315))**: surfaces as a top-level item with **no facet at all** (no `folder`, `file`, `specialFolder`, `package`, or `root`). The Vault is a BitLocker-encrypted area that requires separate authentication to enter; Graph deliberately hides its children and surfaces an opaque zero-facet stub. `DriveItem.isPersonalVault` discriminates by name (locale-specific list — en "Personal Vault", de "Persönlicher Tresor", fr/es/it/pt-BR/ja/zh-Hans) and by the zero-facet + size=0 signature as a future-locale fallback. `OneDriveProvider.delta` / `listChildren` / `deltaWithShared` filter these out and emit an INFO log `Skipping Personal Vault entry (protected): <name>` on first encounter per session (DEBUG thereafter). Verified by `DriveItemVaultTest` + `OneDriveProviderVaultFilterTest`.

- **OneDrive — ZWJ-compound emoji filenames ([UD-307](backlog/CLOSED.md#ud-307))**: uploading a filename containing ZWJ-joined emoji codepoints (e.g. `👨‍👩‍👧.txt` = U+1F468 ZWJ U+1F469 ZWJ U+1F467) via Graph `PUT /me/drive/root:/<path>/<name>:/content` returns HTTP 409 `nameAlreadyExists` *"The specified item name is incompatible with a similar name on an existing item"* — even when no item with that name exists in the folder. OneDrive normalises or collapses ZWJ sequences server-side and the result collides in the search/index layer. The other 14 codepoint families in the UD-712 test set (¡, æ, ñ, Ω, Ж, א, ع, क, ก, ☃, あ, 中, 글, 🎉) round-trip byte-equal. Per UD-307 Option-C resolution: `OneDriveProvider.upload` emits a WARN with `nameAlreadyExists-zwj` filename + 409 detail, skips the file, continues the sync — no policy knob, no auto-rename. Vanishingly rare in real sync sets; revisit only if telemetry surfaces frequency.

## 4. Sync engine — three-phase cycle

> 💻 verified against `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt`.

1. **Gather** — remote: delta API (cursor-based incremental, full on first sync). Local: scan filesystem, compare mtime/size against DB. 💻 verified.
2. **Reconcile** — compare per path, detect moves/renames/case collisions, produce ordered `SyncAction` list. 💻 verified.
3. **Apply** — create placeholders, upload/download, execute moves, resolve conflicts, update DB. 💻 verified; **split into Pass 1 (sequential metadata batch) and Pass 2 (concurrent transfers)** at [`SyncEngine.kt:172,220`](../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt#L172). [UD-205](backlog/BACKLOG.md#ud-205) tracks the interruption-recovery audit.

### 4.1 Key semantics

| Feature | Intent | Status |
|---------|--------|--------|
| **Move detection (local renames)** | `DeleteRemote(old) + Upload(new)` with same size → collapse to `MoveRemote`; folder children removed | 💻 verified |
| **Move detection (remote renames)** | `CreatePlaceholder(new)` where `remoteId` matches existing entry at different path → `MoveLocal` + `renamePrefix` for children | 💻 verified |
| **Conflict policy** | `keep_both` (default) → save remote as `file.conflict-remote-TIMESTAMP.ext`; `last_writer_wins` → most recent mtime wins | 💻 verified |
| **Pin rules** | glob patterns for eager hydration, matched during apply | 💻 verified |
| **0-byte placeholders** | Stub created via `Files.createFile`; `isHydrated=false` row in state DB; real bytes arrive via `provider.download` on access. The earlier sparse-file approach (`RandomAccessFile.setLength(size)`) was reverted in UD-222 — see `PlaceholderManager.kt:40-46` for the rationale | 💻 verified |
| **Interrupt/resume** | delta cursor persisted in SQLite `sync_state` | 💻 verified; see [UD-205](backlog/BACKLOG.md#ud-205) for the Pass-2 concurrency angle |
| **Deletion safeguard** | `max_delete_percentage` (default 50%) aborts sync if over-threshold | 💻 verified (integration-test Section 3 covers the CLI flag) |
| **Selective sync** | `exclude_patterns` glob per profile, skipped during reconciliation | 💻 verified |
| **Adaptive polling** | ACTIVE (<3 idle cycles) → `min`, NORMAL (<8) → `normal`, IDLE → `max`; inotify trigger collapses wait on POSIX | 💻 `computePollInterval` / `pollStateName` at [`AdaptiveInterval.kt`](../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/AdaptiveInterval.kt); defaults come from `SyncConfig`. |

### 4.2 Deletion safeguards + force-delete

`max_delete_percentage` guard (default 50 %) aborts a sync if it would touch more than the threshold. CLI flag `--force-delete` bypasses. 💻 verified — exercised by `integration-test.sh` Section 3.

## 5. Security posture

Reconciled against the post-Wave-1 [`docs/SECURITY.md`](SECURITY.md) (which subsumes UD-112's STRIDE formalization).

### 5.1 In-code mitigations (all 💻 verified)

| Mitigation | Anchor | Covered |
|-----------|--------|---------|
| AES-256-GCM vault, PBKDF2-HMAC-SHA256 600k iter, 16-byte salt | `core/app/xtra/src/main/kotlin/org/krost/unidrive/xtra/XtraKeyManager.kt:31,118-119` | A2 |
| POSIX `0600` on token files | `core/providers/onedrive/src/main/kotlin/.../OAuthService.kt:213` | A4 |
| Array-form `ProcessBuilder` (no shell injection) | `core/providers/rclone/src/main/kotlin/.../RcloneCliService.kt:161` | rclone |
| `safeResolveLocal()` path-traversal guard | `core/app/sync/src/main/kotlin/.../PlaceholderManager.kt:20-33` | UD-304 |
| Per-profile `FileChannel.tryLock()` | `core/app/sync/src/main/kotlin/.../ProcessLock.kt:16` | A6 |

Note: the prior `NamedPipeServer.kt`-based mitigations (NDJSON schema
validation per UD-105; frame-size cap + token-bucket rate limit per
UD-106) were removed in [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)
along with the named-pipe transport itself. UDS is broadcast-only with
no command surface, so neither attack class applies.

### 5.2 Gaps (all tracked in BACKLOG.md)

- Structured audit log per sync action ([UD-113](backlog/BACKLOG.md#ud-113)).
- Vault passphrase hardening (rotation, lockout) — doc-only wishlist; no ticket yet.

The prior Windows-shell-tier gaps (named-pipe SDDL UD-101, DLL hardening UD-102, DPAPI token wrap) closed via [ADR-0011](adr/0011-shell-win-removal.md) / [ADR-0012](adr/0012-linux-mvp-protocol-removal.md) when those tiers were cut. They re-open only if Windows support is re-introduced under the criteria documented in those ADRs.

## 6. Webhook flow


**OneDrive subscriptions only.** S3 / SFTP / WebDAV / Rclone do not have webhook protocols in scope.

Flow:
1. User sets `webhook = true` per-profile in `config.toml`.
2. Daemon calls `GraphApiService.createSubscription(notificationUrl, expirationDateTime)` — 💻 verified at `core/providers/onedrive/src/main/kotlin/.../GraphApiService.kt:556`.
3. Graph API expires after 4230 minutes (~3 days).
4. **Auto-renewal is fully wired ([UD-303](backlog/CLOSED.md#ud-303), closed):** `SubscriptionRenewalScheduler` arms a `(expiry − 24h)` callback that drives a single `renewSubscription()` Graph call per subscription lifetime. Per-cycle `ensureSubscription()` short-circuits via `scheduler.isScheduledAndValid(profileName)` when the scheduler is armed and the persisted subscription still has >24h left. See [`SyncCommand.kt:480-541`](../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt#L480) for the renewal logic (KDoc on `ensureSubscription` documents the fast-path).
5. `SubscriptionStore` is consumed by `SyncCommand` (the CLI-level orchestrator drives both the store and the scheduler). The pre-verification "orphan" framing in earlier drafts of this doc was wrong.

**Tunneling for NAT traversal:** ngrok, cloudflared, serveo.net, or production VPS with public IP + reverse proxy. Doc-only; no automation.

## 7. Test strategy — current reality

Snapshot at HEAD (2026-05-01, post UD-350/UD-751/UD-752 bundle). Total: **1427 tests, 0 failed, 14 skipped** (UD-209 sparse-Windows gates + a few UD-704 `assumeTrue` skips for live integration without OAuth credentials).

The earlier per-module "doc target vs actual" table reflected pre-greenfield numbers from `0.2.x` and is no longer load-bearing — the test counts have moved well past it as the duplication-survey lifts (UD-336/UD-340/UD-342/UD-343/UD-347/UD-348/UD-351/UD-350/UD-751/UD-752) added shared-helper test coverage in `:app:core`. Re-baseline from `./gradlew test` if a fresh per-module breakdown is needed.

### 7.1 Coverage of the previously-flagged high-risk areas

- **`RelocateCommand` (477 lines) + `CloudRelocator` (457 lines)** — well covered now: `CloudRelocatorTest` (881 lines) + `RelocateCommandTest` (261 lines). The destructive `--delete-source` paths have explicit cases. Outstanding live-mode coverage tracked under [UD-329](backlog/BACKLOG.md#ud-329).
- **`ShareCommand` (131 lines)** — covered by `ShareCommandTest` (179 lines) + `ToolHandlerTest`.
- **`Main.kt` config factory (839 lines)** — still the sparsest spot in the CLI. No dedicated ticket; lift candidates ([UD-754](backlog/BACKLOG.md#ud-754) auto-format research) may shrink it before targeted tests are worth writing.

### 7.2 Integration tests

The Docker test harness lives under [`core/docker/`](../core/docker) — three compose files, all driven from the Gradle root:

| Harness | Compose file | Scope | Tests |
|---|---|---|---|
| localfs round-trip | `docker-compose.test.yml` | `localfs`-only sync; no external services | 8 |
| provider contract | `docker-compose.providers.yml` | live `sftp` + `webdav` + `s3` (MinIO) + `rclone` | 20 |
| MCP JSON-RPC (UD-815) | `docker-compose.mcp.yml` | shadow-jar MCP end-to-end vs seeded localfs profile | 9 |

Expected on HEAD per [`core/docker/README.md`](../core/docker/README.md): all three harnesses pass with exit 0 (`8 + 20 + 9 = 37`). Runnable wherever `docker compose` is available.

In-tree integration tests:
- ✅ `McpRoundtripIntegrationTest` (6 tests).
- ✅ `LiveGraphIntegrationTest` (canonical consumer of `UNIDRIVE_TEST_ACCESS_TOKEN` per CLAUDE.md; cleanly `assumeTrue`-skipped without an OAuth token).

Out-of-tree:
- ⚠ OneDrive live webhook tests require a tenant + tunneling (ngrok / cloudflared); not part of any automated run.

## 8. Version anchors

Context-setting only — this repo restarted at `0.0.0-greenfield`. Pre-restart:

- **0.2.8 (prior greenfield)** — "project restructure, 977 tests, 45% coverage"; shell-win test fixture still referenced this version before the session's version unification.
- **0.2.7** — cloud speed ranking spec+plan; CodeQL fix; #174 provider subdir restructure.
- **0.1.0 — 2026-04-12** — initial public release; 8 modules; 5 built-in providers (OneDrive, S3, SFTP, WebDAV, Rclone); ServiceLoader SPI.
- **[Unreleased] (post-0.1.0, pre-0.2.x restart)** — 30+ feature items (#104 OneDrive shared folders, #111 share-link CLI, #130 expiry, #143 vault in systemd, #137 file versioning, #100 deletion safeguards, #101 HashVerifier, #102 parallel transfers, #96 Entra ID multi-tenant, #37 X-tra encryption, etc.).

Interpretation: the current greenfield **inherits all those features in code** — the restart was about structure, not feature rewrite. The reset to `0.0.0-greenfield` is a version-space reset, not a functional one.

## 9. Intent-vs-code delta — things the docs assumed that the code doesn't do

Items where the spec assumes behaviour the current code does not yet exhibit. Rows for tiers that were cut (`shell-win` UD-401 / UD-101 / UD-102; UI-on-Windows UD-503) closed via [ADR-0011](adr/0011-shell-win-removal.md), [ADR-0012](adr/0012-linux-mvp-protocol-removal.md), [ADR-0013](adr/0013-ui-removal.md) and dropped from this list.

| # | Topic | Doc assumption | Current reality | Tracked as |
|---|-------|----------------|-----------------|-----------|
| 1 | JDK target | (older docs assumed JDK 25) | JDK 21 LTS | [ADR-0006](adr/0006-toolchain.md) |
| 2 | Provider capability defaults | silent `null/empty/false` | same (legacy); to be replaced by `Capability.Unsupported(reason)` | [UD-301](backlog/BACKLOG.md#ud-301) |
| 3 | Audit log per sync action | not yet specced; doc-speculative pipe to MCP | not implemented | [UD-113](backlog/BACKLOG.md#ud-113) |
| 4 | Pass-2 transfer atomicity | concurrent transfers happen outside the metadata batch; SIGKILL-during-transfer recovery not audited | structural gap confirmed but not yet exercised by a fault-injection test | [UD-205](backlog/BACKLOG.md#ud-205) |
| 5 | OS-level placeholder hydration | (legacy doc — implied OneDrive-on-Linux semantics) | reverted to 0-byte stubs in UD-222; shell-tier work cut by [ADR-0011](adr/0011-shell-win-removal.md). Re-opens only under that ADR's criteria | n/a |


## 10. Open questions

Long-running unresolved items not on the v0.1.0 critical path:

- **Pluggable encryption** — strategy interface undecided.
- **Block-level delta** — librsync JVM viability unknown.
- **OneDrive relocate tested end-to-end** — needs live OAuth token; partially covered by UD-329's live retest.

## 11. How to use this document

- **Treat SPECS.md as the normative statement of intent.** `docs/ARCHITECTURE.md` is the narrative overview; SPECS is the detail.
- **When writing code,** cite specs: "per SPECS §2.1, the IPC event `sync_started` fires once per cycle".
- **When docs and code disagree,** open a UD item that either corrects the doc (if code is correct) or corrects the code (if doc is correct). Don't let drift accumulate.
