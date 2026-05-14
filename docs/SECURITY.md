# Security

> Threat model + current posture + v0.0.1 baseline. Tracked against [ADR-0004](adr/0004-security-baseline.md). STRIDE formalized in [UD-112](backlog/BACKLOG.md#ud-112).

## Asset inventory

Assets the threat model is scoped around, ranked roughly by blast radius if compromised.

| # | Asset | Where it lives | Sensitivity | Notes |
|---|-------|----------------|-------------|-------|
| A1 | Vault passphrase | Env var `UNIDRIVE_VAULT_PASS` or interactive prompt; **never on disk** | Critical | Minimum 8 chars enforced (`Vault.kt:34`). Derives the AES-GCM key for A2. Read at [`Main.kt:103`](../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt#L103). |
| A2 | Encrypted credential vault | `{profile}/vault.enc`, AES-256-GCM with PBKDF2-HMAC-SHA256 (600k iterations) | Critical | Binary layout `salt(16) \|\| iv(12) \|\| ciphertext+tag` (`Vault.kt:22`). On POSIX, mode clamped to `rw-------` at write time (`Vault.kt:77`). |
| A3 | Provider API tokens / keys | Inside A2 when possible; otherwise inside A5 | Critical | OAuth refresh tokens, S3 keys, SFTP passwords. `SENSITIVE_FIELDS` whitelist at `Vault.kt:37`. |
| A4 | OAuth token cache | `{profile}/token.json` per provider | High | POSIX `0600` file / `0700` dir enforced by `setPosixPermissionsIfSupported` (`OAuthService.kt:213`). Windows DPAPI still TBD. |
| A5 | Config TOML | `{profile}/config.toml` | High (may leak) | Contains provider URLs and, for legacy/basic-auth providers, plaintext credentials until UD-xxx migrates them to vault. |
| A6 | Sync state DB | `{profile}/state.db` (SQLite) | Medium | Contains file paths, hashes, timestamps — not credentials. Protected by single-instance lock (`ProcessLock.kt:16`). |
| A7 | In-flight IPC frames | Unix domain socket `$XDG_RUNTIME_DIR/unidrive-<profile>.sock` (mode `0600`) | Medium | NDJSON on the wire. Size + rate gates at `IpcServer.kt`. |
| A8 | Logs | `unidrive.log` + stderr | Low/Medium | Frame payloads deliberately never logged. Provider responses may still include paths. |

## Data-flow diagram

```
        ┌─────────────────────────────────────────── user trust boundary ──┐
        │                                                                  │
 ╔════════════╗           stdin/stdout                                     │
 ║   human    ║ ─────────────────────────────────► ╔═══════════════╗       │
 ╚════════════╝                                    ║ unidrive CLI  ║       │
        │                                          ╚═══════╤═══════╝       │
        │  JSON-RPC (stdio)                                │  in-process   │
        │                                                  ▼               │
        │                                          ╔═══════════════╗       │
        ├────────────► ╔═══════════════╗ ◄──JSON───║  sync engine  ║       │
        │              ║  MCP client   ║   -RPC    ║  (daemon)     ║       │
        │              ╚═══════════════╝           ╚═══════╤═══════╝       │
        │                                                  │               │
        │                                 UDS today /      │               │
        │                                 named pipe       │               │
        │                                 post-UD-503      │               │
        │                                                  │               │
        │              ╔═══════════════╗                   │               │
        │              ║   UI tray     ║ ◄─────────────────┤               │
        │              ╚═══════════════╝                   │               │
        │                                                  │               │
        └─ same-user local (LOW trust — other ─────────────┼────────────────┘
           user processes can connect to UDS)             │
                                                           │
       ═══════════ NETWORK boundary (HIGH trust, TLS) ═════╪═════
                                                           │
                               HTTPS / SSH / CF API        ▼
                                                    ╔════════════╗
                                                    ║  provider  ║  (trusted
                                                    ║  (cloud)   ║   via API
                                                    ╚════════════╝   contract)
```

Trust boundaries:

- **User boundary** — stdin/stdout, CLI arguments; trusted (process runs as the user).
- **Same-user local** — the UDS at `$XDG_RUNTIME_DIR/unidrive-<profile>.sock` is reachable by any process running as the *same* user; cross-user processes are denied by socket permissions (mode `0600`). Low trust within the user boundary remains: a compromised user-space app can still connect.
- **Network** — HTTPS / SSH / provider APIs. High trust assuming TLS; MitM is mitigated by Ktor's default trust manager. The declared `trust_all_certs` flag was audited and resolved — actual wiring lives in `WebDavApiService.kt` (closed as [UD-104](backlog/CLOSED.md#ud-104)).
- **Provider** — trusted via API contract; we assume an authenticated session returns our data, not another tenant's.

## Attack surfaces

| Surface | Description | Primary risk | Backlog |
|---------|-------------|--------------|---------|
| UDS event broadcast | `IpcServer.kt` binds `$XDG_RUNTIME_DIR/unidrive-<profile>.sock`, mode `0600`, daemon → client push only | Same-user process subscribes to event stream | mode-`0600` + per-user `XDG_RUNTIME_DIR` denies cross-user; SO_PEERCRED follow-up if needed |
| Rclone subprocess | Shell-out to external CLI | Command injection via unsanitized args | mitigated today (array-form `ProcessBuilder` at `RcloneCliService.kt:161`); verify in [UD-109](backlog/BACKLOG.md#ud-109) |
| WebDAV TLS | `trust_all_certs` flag | MitM if enabled without SSLContext wiring | resolved [UD-104](backlog/CLOSED.md#ud-104) |
| Vault passphrase | AES-GCM key derived from user passphrase | Weak passphrases, key-derivation parameters | [UD-112](backlog/BACKLOG.md#ud-112) |
| OAuth tokens on disk | `token.json` per profile | File permission / disk exfiltration | POSIX `0600` today (`OAuthService.kt:213`); Windows DPAPI TBD |
| Scanner gaps | No gitleaks / trivy / semgrep / dependabot | Secrets in commits; vulnerable deps | [UD-107](backlog/BACKLOG.md#ud-107)..[UD-110](backlog/BACKLOG.md#ud-110) |

## Current mitigations (already in code)

- **AES-GCM vault** (`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Vault.kt:26`) with PBKDF2-HMAC-SHA256 / 600k iterations (`Vault.kt:90`).
- **POSIX `0600` / `0700`** on token files and directories (`core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt:213`).
- **Array-form `ProcessBuilder`** in rclone adapter (`core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:161`) — safe against shell-metacharacter injection.
- **`safePath()`** path containment check (`core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt:65`) — blocks `..` traversal via `resolve().normalize()` + `startsWith(rootPath)`.
- **Single-instance lock files** per profile (`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt:16`) — prevents concurrent state-db corruption.
- **NDJSON frame validation** at IPC boundary (`IpcProgressReporter.kt`) — rejects non-conforming frames before dispatch.
- **Per-connection token bucket + frame cap** (`IpcServer.kt`): 50 frames/sec burst 50, 65 536-byte hard cap.
- **Never log frame contents** (`IpcProgressReporter.kt`) — attacker-supplied payloads are not echoed into logs.
- **UDS event server** (`IpcServer.kt`) bound at `$XDG_RUNTIME_DIR/unidrive-<profile>.sock` with mode `0600`.
- **No first-party GUI surface for credentials** — auth lives in the daemon.
- **Per-call token-refresh model** — every provider with token semantics (Internxt, OneDrive) checks expiry at each API call site rather than scheduling refresh on a wall clock. Robust under suspend/resume, unlike drive-desktop's `setTimeout`-based `TokenScheduler`. Audit: [`docs/providers/auth-refresh-model.md`](providers/auth-refresh-model.md) (UD-208).

## STRIDE

Columns: **Threat** — the concrete attack. **Asset/Component** — which row of the asset inventory or which code surface it touches. **Current mitigation** — what's in the tree today, with file:line. **Residual risk** — what's still exposed. **Backlog** — the ticket owning the gap (open or closed).

| # | Threat | Asset/Component | Current mitigation (file:line) | Residual risk | Backlog |
|---|--------|-----------------|--------------------------------|---------------|---------|
| **S1** | Rogue same-user process connects to `$XDG_RUNTIME_DIR/unidrive-<profile>.sock` and impersonates the UI client | A7 UDS | UDS path created with mode `0600`; `$XDG_RUNTIME_DIR` is per-user / mode `0700` on standard Linux | Same-user processes still pass the permission check — cross-user processes denied | follow-up: SO_PEERCRED check on accept() if a real threat materialises |
| **T1** | Tampering with an NDJSON command frame in flight (local loopback) | A7 in-flight frames | Schema validation rejects malformed structure (`IpcProgressReporter.kt`); JSON parse errors dropped | Content-level semantics not authenticated — any well-formed frame from a connected peer is accepted | [UD-101](backlog/BACKLOG.md#ud-101) (peer identity gating is the real fix) |
| **T2** | Tampering with the encrypted vault on disk (replace with attacker-supplied blob) | A2 `vault.enc` | AES-GCM authenticated encryption (`Vault.kt:98-106`) — any bit-flip fails tag verification on read | Whole-file replacement with a vault the attacker controls is still a DoS (can't decrypt), but can't plant readable contents without the passphrase | — (GCM is sufficient) |
| **R1** | A sync action (delete, upload, overwrite) is performed but no trail exists to attribute it | Sync engine | Structured logging via SLF4J; logs rotate via logback (`core/app/mcp/src/main/resources/logback.xml`) | Logs are local and mutable by the same user; no integrity chain | [UD-111](backlog/BACKLOG.md#ud-111) token-refresh telemetry is the closest current item; a dedicated audit-log ticket is not yet opened |
| **R2** | Token refresh fails and the user doesn't know their session is effectively dead | A4 token cache | Errors surface through log lines | User has no UI-surfaced signal until next foreground sync | [UD-111](backlog/BACKLOG.md#ud-111) telemetry + notification |
| **I1** | OAuth token exfiltration via a world-readable token file | A4 `token.json` | POSIX `0600` on the file, `0700` on the directory (`OAuthService.kt:213`) | No Windows DPAPI wrap yet — on Windows the ACL is whatever the home dir provides | Windows DPAPI TBD (no ticket yet) |
| **I2** | Plaintext credentials in `config.toml` read by another user / backup tool | A5 config | Vault migration path via `SENSITIVE_FIELDS` (`Vault.kt:37`) covers the common fields | Any legacy or custom field not in that set stays plaintext | — (expand `SENSITIVE_FIELDS` as providers land) |
| **I3** | Attacker logs scraped for frame content | A8 logs | Frame payloads never logged — only error counts and byte sizes | Provider responses logged at debug may include file paths | Keep debug-level logging off by default in release builds |
| **D1** | One client floods the UDS with NDJSON and starves other clients | A7 in-flight frames | Per-connection `TokenBucket` 50/sec burst 50; hard frame cap 65 536 bytes at `IpcServer.kt` | Connection-level cap is per-peer, not global — N clients × 50 frames/s is still allowed | [UD-106](backlog/CLOSED.md#ud-106) landed per-connection; global cap deferred (UD-106a follow-on) |
| **D2** | Unbounded frame without a newline causes leftover-buffer growth until OOM | A7 in-flight frames | Chunk > MAX_FRAME_BYTES with no `\n` is dropped and leftover reset (`IpcServer.kt`) | — | [UD-106](backlog/CLOSED.md#ud-106) landed |
| **E1** | UDS reachable cross-user via permissive `$XDG_RUNTIME_DIR` (e.g. `/tmp` fallback) → cross-user IPC | A7 UDS + daemon | Daemon runs un-privileged by default; UDS file mode `0600`; standard `$XDG_RUNTIME_DIR` is `/run/user/$UID` (mode `0700`) | If `$XDG_RUNTIME_DIR` is unset and the fallback path lands somewhere world-readable, the file mode still denies cross-user access | follow-up: refuse to start if `$XDG_RUNTIME_DIR` resolves to a world-traversable directory |
| **E2** | Path traversal via an attacker-controlled virtual path writes outside the sync root | LocalFs provider | `safePath()` normalizes and asserts containment (`LocalFsProvider.kt:65`) with `startsWith(rootPath)` guard at `LocalFsProvider.kt:68` | Symlinks inside the root that point outside are *not* rejected (normalize is purely lexical) | — (symlink policy open; file under localfs hardening when opened) |

## v0.0.1 baseline (MVP gates)

Must ship with or before v0.0.1:

1. **JSON-Schema validation** ([UD-105](backlog/CLOSED.md#ud-105), landed) on every NDJSON frame on both tiers; reject non-conforming frames without parsing further.
2. **Message size cap + rate limit** ([UD-106](backlog/CLOSED.md#ud-106), landed) on the IPC channel.
3. **gitleaks** ([UD-107](backlog/BACKLOG.md#ud-107)) in pre-push and CI.
4. **Threat model doc** ([UD-112](backlog/BACKLOG.md#ud-112)) — this file formalized with STRIDE table (you are reading it).

Open: [UD-108](backlog/BACKLOG.md#ud-108) Trivy, [UD-109](backlog/BACKLOG.md#ud-109) Semgrep, [UD-110](backlog/BACKLOG.md#ud-110) Dependabot/Renovate, [UD-111](backlog/BACKLOG.md#ud-111) token refresh telemetry.

## Reporting vulnerabilities

TBD (depends on git host and disclosure policy — [UD-701](backlog/BACKLOG.md#ud-701)).
