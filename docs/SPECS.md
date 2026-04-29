# UniDrive — specification

> Normative intent catalog. Each claim is marked **📄 doc-only**
> (described here, not yet verified in code), **💻 code-verified**
> (observed in the current tree with a file:line anchor), **✅
> doc+code agree**, or **⚠ disagreement** (doc says one thing, code
> says another — these need a decision before they can ship).
> [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) is the coarser narrative;
> SPECS.md is the normative catalog with the file:line anchors.

> **Linux MVP scope.** MVP is `core/` only. Sections referencing
> `shell-win/`, `protocol/`, the named pipe, Surface B "shell ↔
> daemon command pipe", `ui/`, or the C++ language matrix row are
> **out of date** below — see ADRs 0011 / 0012 / 0013 for what was
> removed and why. UI ↔ daemon IPC is UDS only with event shapes
> inline in
> [`IpcProgressReporter.kt`](../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt).

## 0. Product at a glance

**UniDrive** — multi-provider bidirectional cloud-storage sync with sparse-placeholder local files. Files appear immediately with correct sizes but consume no disk space until opened or pinned (the OneDrive-on-Windows model, on Linux).

Target platform hierarchy, recovered:

- **Tier-1**: Linux (original target).
- **Tier-2**: Windows 11 (added in the prior session per `shell-win/HANDOVER.md`, 2026-04-16).
- **Tier-3**: macOS — not in scope.

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

### 1.2 Separate tiers (monorepo post-restart)

| Tier | Code | Role |
|------|------|------|
| JVM daemon | [`core/`](../core) | CLI + MCP + sync + providers |
| ~~Swing tray UI~~ | ~~`ui/`~~ | Removed via [ADR-0013](adr/0013-ui-removal.md) — no first-party GUI in v0.0.x; UDS broadcast surface stays open for community trays |
| Windows shell extension | [`shell-win/`](../shell-win) | C++20 / CfApi DLL for Explorer integration |
| Shared IPC schemas | [`protocol/`](../protocol) | Code-free schema contract (new in greenfield restart) |

### 1.3 Toolchain

| | Doc says | Code says | Note |
|---|----------|-----------|------|
| Kotlin | 2.3.20 ✅ | 2.3.20 ([`core/gradle/libs.versions.toml`](../core/gradle/libs.versions.toml)) | agree |
| JDK | 21 LTS ([`core/app/cli/build.gradle.kts:8`](../core/app/cli/build.gradle.kts#L8) — `jvmToolchain(21)`) | per [ADR-0006](adr/0006-toolchain.md). |
| Gradle | 9.4.1 ✅ | 9.4.1 | agree |
| C++ (shell-win) | — (not mentioned in recovered docs) | C++20 / MSVC 2022 ([`shell-win/CMakeLists.txt:5`](../shell-win/CMakeLists.txt#L5)) | code-only |

## 2. IPC — three wire protocols

The recovered docs reveal that **three distinct IPC surfaces exist**, not one. This is the single biggest clarification SPECS delivers over the earlier `ARCHITECTURE.md` in this repo. Each has its own transport and format.

### 2.1 Surface A — daemon → UI (push-only event stream)

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
| **Sparse placeholders** | `RandomAccessFile.setLength()`; `stat` reports cloud size, zero blocks allocated | ⚠ **POSIX only**; on Windows returns `false` unconditionally ([UD-209](backlog/BACKLOG.md#ud-209)) |
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
| `safePath()` containment check (post-UD-304) | `core/providers/localfs/src/main/kotlin/.../LocalFsProvider.kt:60` | localfs |
| NDJSON schema validation on IPC frames | `core/app/sync/src/main/kotlin/.../NamedPipeServer.kt:138` | UD-105 |
| Frame size cap + token-bucket rate limit | `NamedPipeServer.kt:98,157-167` | UD-106 |
| Per-profile `FileChannel.tryLock()` | `core/app/sync/src/main/kotlin/.../ProcessLock.kt:16` | A6 |

### 5.2 Gaps (all tracked in BACKLOG.md)

- Named pipe has no SDDL → **LPE-adjacent** if daemon ever runs elevated ([UD-101](backlog/BACKLOG.md#ud-101)).
- DLL compiled without CFG / DYNAMICBASE / `SetDefaultDllDirectories` ([UD-102](backlog/BACKLOG.md#ud-102)).
- Windows DPAPI wrap for token files not implemented (doc-only wishlist).
- Structured audit log per sync action ([UD-113](backlog/BACKLOG.md#ud-113) — opened this session when the UD-112 STRIDE review found the prior SECURITY.md had mis-attributed it).

## 6. Webhook flow


**OneDrive subscriptions only.** S3 / SFTP / WebDAV / Rclone do not have webhook protocols in scope.

Flow:
1. User sets `webhook = true` per-profile in `config.toml`.
2. Daemon calls `GraphApiService.createSubscription(notificationUrl, expirationDateTime)` — 💻 verified at `core/providers/onedrive/src/main/kotlin/.../GraphApiService.kt:556`.
3. Graph API expires after 4230 minutes (~3 days).
4. **Auto-renewal is partially wired:** `renewSubscription()` is called from [`SyncCommand.kt:344`](../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt#L344) inside `ensureSubscription()`, invoked on every poll cycle when `watch && webhook == true`. What's missing is the *scheduled* renewal policy (renew ≤24 h before expiry); today the cycle creates a new subscription if the old one lapsed, which works but trades one round-trip per sync cycle for correctness.
5. `SubscriptionStore` exists and **is consumed** by [`SyncCommand.kt:224,235,258`](../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt#L224) — not by `SyncEngine` directly, but by the CLI-level orchestrator that drives the engine. My earlier "orphan" framing in the pre-verification draft of this doc was wrong; [UD-303](backlog/BACKLOG.md#ud-303) was refined during the verification sweep to reflect "scheduler-level auto-renewal policy missing, not consumer-missing".

**Tunneling for NAT traversal:** ngrok, cloudflared, serveo.net, or production VPS with public IP + reverse proxy. Doc-only; no automation.

## 7. Test strategy — doc target vs current reality

Test coverage targets:

| Module | Doc target (tests) | Doc target (coverage) | Current (2026-04-17) |
|--------|-------------------:|----------------------:|---------------------:|
| sync | 255 | 87% | **290** (+TokenBucket, NdjsonValidator, NamedPipeServerDispatch) |
| cli | 83 | 43% | **113** (+CliProgressReporter, post-UD-704 assumeTrue) |
| mcp | 23 | moderate | **114** (+McpRoundtripIntegrationTest after UD-202) |
| core | 9 | low | **38** |
| localfs | 31 | good | **34** (+3 UD-304 regressions) |
| webdav | 24 | good | **73** (+WebDavApiServiceSsl post-UD-104) |
| xtra | 20 | good | 20 |
| onedrive | 42 | good | 57 |
| internxt | 35 | good | 35 |
| rclone | 33 | good | 33 |
| s3 | 27 | good | 82 |
| hidrive | 23 | good | 23 |
| sftp | 15 | moderate | **91** |
| **TOTAL** | **674** | — | **997 tests, 0 failed, 5 skipped (UD-209 sparse gates)** |


### 7.1 Untested areas the checklist flagged as high-risk

- **RelocateCommand + CloudRelocator** (312 lines, destructive `--delete-source`) — ⚠ still largely uncovered; add to [UD-801](backlog/BACKLOG.md#ud-801) scope.
- **ShareCommand** (90 lines, user-facing) — partial via `ToolHandlerTest`; standalone CLI tests TBD.
- **Main.kt config factory** (486 lines) — ⚠ still sparse.

### 7.2 Integration tests

Doc expectation: `LocalFS roundtrip`, `Trash & Versioning`, `Share & Webhook (OneDrive live)`, `Relocate (2 profiles)`, `Vault & Encryption`, `Profile Management`, `Backup`, `Docker container`.

Current:
- ✅ `integration-test.sh --skip-network` passes after [UD-703](backlog/CLOSED.md#ud-703) — 2 local checks pass, 8 network checks cleanly skipped.
- ✅ `McpRoundtripIntegrationTest` (6 tests).
- ⚠ Docker container tests not runnable (no `docker` on this host).
- ⚠ OneDrive live webhook tests not runnable (no OAuth token on this host).

## 8. Version anchors

Context-setting only — this repo restarted at `0.0.0-greenfield`. Pre-restart:

- **0.2.8 (prior greenfield)** — "project restructure, 977 tests, 45% coverage"; shell-win test fixture still referenced this version before the session's version unification.
- **0.2.7** — cloud speed ranking spec+plan; CodeQL fix; #174 provider subdir restructure.
- **0.1.0 — 2026-04-12** — initial public release; 8 modules; 5 built-in providers (OneDrive, S3, SFTP, WebDAV, Rclone); ServiceLoader SPI.
- **[Unreleased] (post-0.1.0, pre-0.2.x restart)** — 30+ feature items (#104 OneDrive shared folders, #111 share-link CLI, #130 expiry, #143 vault in systemd, #137 file versioning, #100 deletion safeguards, #101 HashVerifier, #102 parallel transfers, #96 Entra ID multi-tenant, #37 X-tra encryption, etc.).

Interpretation: the current greenfield **inherits all those features in code** — the restart was about structure, not feature rewrite. The reset to `0.0.0-greenfield` is a version-space reset, not a functional one.

## 9. Intent-vs-code delta — things the docs assumed that the code doesn't do

The important list. Items where the spec assumes behaviour the current code does not yet exhibit.

| # | Topic | Doc assumption | Current reality | Tracked as |
|---|-------|----------------|-----------------|-----------|
| 1 | Named-pipe hydration | `fetch` / `dehydrate` over pipe drive CfApi callbacks | stubs returning "not yet implemented" | [UD-201](backlog/BACKLOG.md#ud-201), [UD-401](backlog/BACKLOG.md#ud-401) |
| 2 | Webhook auto-renewal | subscriptions renewed <24 h before expiry | `renewSubscription()` exists, nothing calls it | [UD-303](backlog/BACKLOG.md#ud-303) |
| 3 | Windows sparse placeholders | NTFS sparse-flag detection | always returns false on Windows | [UD-209](backlog/BACKLOG.md#ud-209) |
| 4 | JDK target | JDK 25 | JDK 21 LTS (session choice) | [ADR-0006](adr/0006-toolchain.md) |
| 5 | Named-pipe ACL | implicitly trusted; no SDDL | no SDDL; LPE if daemon elevated | [UD-101](backlog/BACKLOG.md#ud-101) |
| 6 | DLL hardening | (docs silent) | no CFG / DYNAMICBASE | [UD-102](backlog/BACKLOG.md#ud-102) |
| 7 | Provider capability defaults | silent `null/empty/false` | same (legacy); to be replaced by `Capability.Unsupported(reason)` | [UD-301](backlog/BACKLOG.md#ud-301), UD-704 |
| 8 | IPC transport for UI | UDS on Linux; UDS on Windows via `%TEMP%` workaround | same | [UD-503](backlog/BACKLOG.md#ud-503) will unify to named pipe on Windows |
| 9 | Audit log per sync action | not yet specced | not implemented | [UD-113](backlog/BACKLOG.md#ud-113) |
| 11 | Sync action audit log reported via MCP | (doc-speculative; never released) | not present | [UD-113](backlog/BACKLOG.md#ud-113) |


## 10. Open questions

Long-running unresolved items not on the v0.1.0 critical path:

- **Pluggable encryption** — strategy interface undecided.
- **Block-level delta** — librsync JVM viability unknown.
- **OneDrive relocate tested end-to-end** — needs live OAuth token; partially covered by UD-329's live retest.

## 11. How to use this document

- **Treat SPECS.md as the normative statement of intent.** `docs/ARCHITECTURE.md` is the narrative overview; SPECS is the detail.
- **When writing code,** cite specs: "per SPECS §2.1, the IPC event `sync_started` fires once per cycle".
- **When docs and code disagree,** open a UD item that either corrects the doc (if code is correct) or corrects the code (if doc is correct). Don't let drift accumulate.
