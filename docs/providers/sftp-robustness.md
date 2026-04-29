# SFTP — transport robustness audit

> Deliverable for [UD-323](../backlog/BACKLOG.md#ud-323) (part of the UD-228
> cross-provider audit split).
> Protocol references: [RFC 4253](https://datatracker.ietf.org/doc/html/rfc4253)
> (SSH transport / KEX / rekey), [RFC 4254](https://datatracker.ietf.org/doc/html/rfc4254)
> (channels), [draft-ietf-secsh-filexfer-13](https://datatracker.ietf.org/doc/html/draft-ietf-secsh-filexfer-13)
> (SFTP — never finalised; v3 is the de-facto baseline).
> Server: [sshd_config(5)](https://man.openbsd.org/sshd_config).
> Library: [Apache MINA SSHD 2.17.1](https://github.com/apache/mina-sshd/blob/master/docs/sftp.md),
> wired in [core/providers/sftp/build.gradle.kts](../../core/providers/sftp/build.gradle.kts)
> via [core/gradle/libs.versions.toml](../../core/gradle/libs.versions.toml).
> Field data: 2026-04-17 e2e drive against `sg5` (192.168.0.63) and
> `krost.org:22222` — see [UD-305](../backlog/BACKLOG.md#ud-305).

> **Transport-protocol-shaped, not HTTP-shaped.** SFTP is a request/response
> protocol on a persistent SSH-2 channel, not stateless RPC over
> TCP-per-call. The five dimensions inherited from the
> [OneDrive baseline](onedrive-robustness.md) are re-framed: status-code
> parsing → SFTP error codes; retry placement → reconnect-on-disconnect;
> `Retry-After` → server-side admission control (`MaxStartups`, fail2ban);
> idempotency → rename atomicity, host-key stability, partial-file resume;
> concurrency → per-session subsystem channels under `MaxSessions`.

Companion to [sftp-client-notes.md](sftp-client-notes.md).

## Status summary

| Dimension | Finding | Confidence |
|---|---|---|
| SFTP error parsing | `SftpException.statusCode` carries the integer; only `NO_SUCH_FILE/NO_SUCH_PATH/FAILURE/FILE_ALREADY_EXISTS` are branched. `PERMISSION_DENIED`, `QUOTA_EXCEEDED`, `CONNECTION_LOST`, `OP_UNSUPPORTED` rethrow as generic `SftpException` with `e.message` only | High |
| Reconnect placement | **Zero retries anywhere.** Channel-close, EOF, `SshException`, `SocketException` propagate; the broken `SftpClient` is discarded but the next call fails the same way | High |
| Backoff (re-framed Retry-After) | No header equivalent. TCP RST, fail2ban ban, `MaxStartups` drop, and wrong-password all surface as opaque `SshException` — we cannot differentiate | High |
| Idempotency | `mkdir` idempotent; `delete` swallows 404-equiv; `rename` uses plain SFTPv3 opcode 0x12 — no `posix-rename@openssh.com`. No upload resume despite using the offset API | High |
| Host-key (UD-305) | MINA `KnownHostsServerKeyVerifier` matches first entry by host only; multi-algorithm `known_hosts` rejects working hosts. Hashed `\|1\|` entries unparseable — filtered to a tempfile per connect | High |
| Concurrency | `Semaphore(maxConcurrency=4)` + pooled `SftpClient` subsystem channels on one `ClientSession`. Stays under OpenSSH `MaxSessions=10` and Synology's ~10 ceiling | High |
| Rekey resilience | Untested ([RFC 4253 §9](https://datatracker.ietf.org/doc/html/rfc4253#section-9): MUST every 1 GB / 1 h). MINA handles transparently *if* the channel is healthy | Low |
| Keepalive / NAT | No client-side heartbeat; idle channels behind NAT die silently | Medium |
| MINA defaults | `POOL_SIZE`, `CHANNEL_WINDOW_SIZE`, `CHANNEL_PACKET_SIZE` all inherited; [SSHD-1067](https://issues.apache.org/jira/browse/SSHD-1067) cites ~2× slowdown vs JSCH at defaults | Medium |

## 1. Error parsing

The wire-level error model is a single integer status from
[draft-ietf-secsh-filexfer-13 §9.1](https://datatracker.ietf.org/doc/html/draft-ietf-secsh-filexfer-13#section-9.1).
MINA surfaces it as `org.apache.sshd.sftp.common.SftpException.status`. Our
wrapper at
[SftpException.kt:5](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpException.kt:5)
keeps the integer as `statusCode: Int`.

Codes that are **branched**:

| Status | Code | Where | Behaviour |
|---|---|---|---|
| `SSH_FX_NO_SUCH_FILE` | 2 | `delete` ([SftpApiService.kt:240](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:240)), `stat` ([:285](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:285)) | swallowed / null |
| `SSH_FX_NO_SUCH_PATH` | 7 v6+ | same | swallowed / null |
| `SSH_FX_FAILURE` | 4 | `ensureParentDirs` ([:379](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:379)) | swallowed (assumed mkdir-on-existing) |
| `SSH_FX_FILE_ALREADY_EXISTS` | 11 | same | swallowed |

Codes that are **not** branched and rethrow opaquely:

- `SSH_FX_PERMISSION_DENIED` (3) — looks identical to a generic failure.
- `SSH_FX_QUOTA_EXCEEDED` (15, v4+) — silently fatal; no surfacing to the
  CLI quota display ([SftpProvider.kt:170](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProvider.kt:170)
  is hardcoded `0/0/0`).
- `SSH_FX_CONNECTION_LOST` (7 in v3, 12 in v4+) — fatal; no reconnect
  attempt; sync cycle dies. Single biggest gap vs the
  [OneDrive baseline](onedrive-robustness.md#2-retry-placement).
- `SSH_FX_OP_UNSUPPORTED` (8) — no fallback path (e.g. cross-volume
  rename → read+write+remove).

**`SSH_FX_FAILURE` is overloaded.** OpenSSH's
[`sftp-server.c`](https://github.com/openssh/openssh-portable/blob/master/sftp-server.c)
returns it for both "mkdir on existing" and "anything else broke".
`ensureParentDirs` swallows status==4 unconditionally — a permission-denied
or disk-full mid-mkdir is silently lost on conformant servers and only
re-surfaces on the next op.

## 2. Reconnect placement (re-framed retry)

There is no retry loop anywhere. `withSftp { ... }` at
[SftpApiService.kt:410](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:410)
catches every exception, discards the broken `SftpClient`, and rethrows.
This recycles a single channel correctly but does not distinguish:

- **Channel-level failure** (`ChannelClosedException`) — session is fine,
  reopening the subsystem channel would recover.
- **Session-level failure** (`SshException` after KEX teardown / TCP RST /
  idle-timeout) — `session` itself is dead; next `borrowClient` fails at
  `createSftpClient` with no recovery path. The `connect()` precondition
  check at [:412](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:412)
  catches never-connected, not was-connected-now-dead.
- **Transport-level failure** (`SocketException`, `EOFException`).

Recommended retry matrix (modelled on OneDrive's `MAX_FLAKE_ATTEMPTS`):

| Failure class | Detection | Action | Cap | Backoff |
|---|---|---|---|---|
| `SSH_FX_CONNECTION_LOST` | `statusCode==7` (v3) / `12` (v4+) | reopen channel + replay | 5 | 500 ms × 2ⁿ + jitter |
| `ChannelClosedException` | MINA class | reopen channel | 5 | 200 ms × 2ⁿ |
| `SshException` (session) | not channel-level | full reconnect + replay | 3 | 2 s × 2ⁿ |
| `SocketException` / `EOFException` | java.net | full reconnect | 3 | 1 s × 2ⁿ |
| `PERMISSION_DENIED`, `QUOTA_EXCEEDED`, auth-fail | named | fatal | — | — |

Jitter ±20 % to avoid synchronised reconnect across the 4-permit semaphore.

## 3. Backoff (re-framed Retry-After)

**There is no `Retry-After` equivalent in SFTP.** Server-side admission
control happens at three layers, none surface a structured wait hint:

1. **TCP refusal** — listening-socket queue full → `ECONNREFUSED`
   (`java.net.ConnectException`). No metadata.
2. **`MaxStartups`** — OpenSSH default `10:30:100`
   ([sshd_config(5)](https://man.openbsd.org/sshd_config)): once 10
   unauthenticated connections are in flight, drop probability ramps from
   30 % at 10 to 100 % at 100. Drop = immediate `close()` of the accepted
   socket → client sees `EOF before banner`. No metadata.
3. **fail2ban / `ipset`** — N failed-auths in M seconds → IP banned at
   firewall layer for typically 600 s. Manifests as TCP timeout. No
   SSH-layer message.

Our `connect()` at
[SftpApiService.kt:121](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:121)
treats all three identically and surfaces `AuthenticationException`. **We
cannot differentiate wrong-password from `MaxStartups` drop from fail2ban
ban.** Recommended: when retry is wired (§2), schedule `connect()`
attempts with a 30 s base / 600 s cap exponential, abort after 3 with a
"server may have rate-limited or banned this client" hint.

## 4. Idempotency / state

### Mutating verbs

- **PUT** ([:185](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:185))
  uses `Write|Create|Truncate` → byte-0 replay is correct, but **no
  partial-file resume** despite the offset API at
  [:210](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:210).
  9 GB upload that fails at 8 GB restarts from 0.
- **DELETE** ([:229](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:229))
  swallows `NO_SUCH_FILE/NO_SUCH_PATH`. Idempotent.
- **MKDIR** ([:374](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:374))
  idempotent on `FAILURE/FILE_ALREADY_EXISTS` — see §1 caveat on the
  `FAILURE` overload.
- **RENAME** ([:268](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:268))
  uses plain SFTPv3 opcode 0x12. Per
  [draft-ietf-secsh-filexfer-13 §8.3](https://datatracker.ietf.org/doc/html/draft-ietf-secsh-filexfer-13#section-8.3),
  v3 rename is **not guaranteed atomic** and **fails if target exists**.
  OpenSSH and ProFTPD-mod_sftp both expose
  [`posix-rename@openssh.com`](https://github.com/openssh/openssh-portable/blob/master/PROTOCOL)
  for atomic-replace; we don't probe `session.getServerExtensions()` or
  request it.

### Host-key verification — [UD-305](../backlog/BACKLOG.md#ud-305) (open)

Three branches at
[SftpApiService.kt:55-119](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:55):

1. **`knownHostsFile == null`** → `AcceptAllServerKeyVerifier`. Logged
   loud, but one config-edit away.
2. **Hashed-entry workaround** ([:71-98](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:71))
   — MINA 2.x cannot parse OpenSSH's `HashKnownHosts yes` format
   (`|1|salt|hash`, default since OpenSSH 8.0). We probe-parse, filter
   to plain entries, write a `deleteOnExit` tempfile per connect, point
   MINA at it. Not content-cached.
3. **UD-305 — multi-algorithm `known_hosts`.** Surfaced 2026-04-17 against
   `sg5` and `krost.org:22222`. When `known_hosts` has multiple entries
   for one host (e.g. `ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`),
   `KnownHostsServerKeyVerifier` matches the **first** entry by host
   only; if the server then negotiates a different algorithm during KEX,
   MINA treats it as a *modified* key and rejects with
   `SshException: Server key did not validate`. OpenSSH's verifier
   iterates all matching entries — MINA's does not. Symptom: unidrive
   rejects a host the operator can `ssh` into one second earlier.
   Workaround during the test drive: keep one algorithm per host in
   `known_hosts`. Preferred fix on the ticket: `AggregateServerKeyVerifier`
   wrapping per-algorithm `KnownHostsServerKeyVerifier` instances.

### Rekey

[RFC 4253 §9](https://datatracker.ietf.org/doc/html/rfc4253#section-9)
mandates rekey every 1 GB / 1 h. MINA handles it transparently if the
channel is healthy; we have no regression test. A 10 GB upload crosses
the 1 GB threshold ~10 times. **Confidence = Low** until tested.

## 5. Concurrency

Per-`SftpApiService`:
[SftpApiService.kt:42](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:42)
wires `Semaphore(config.maxConcurrency)`, default **4** at
[SftpConfig.kt:37](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpConfig.kt:37).
One `ClientSession`, multiplexing up to 4 SFTP subsystem channels.

OpenSSH default ceilings ([sshd_config(5)](https://man.openbsd.org/sshd_config)):

| Setting | Default | Caps |
|---|---|---|
| `MaxSessions` | 10 | subsystem + shell + exec channels per *authenticated* connection |
| `MaxStartups` | 10:30:100 | *unauthenticated* concurrent connections to listener |
| `ClientAliveInterval` | 0 | server-side keepalive (disabled) |
| `LoginGraceTime` | 120 s | auth must complete within window |

Our 4-of-10 keeps headroom for operator overhead (interactive ssh, scp).
Single authenticated connection means we consume `MaxSessions`, not
`MaxStartups`. **Inheriting silently:** no `SftpModuleProperties.POOL_SIZE`
set, no `CHANNEL_WINDOW_SIZE` / `CHANNEL_PACKET_SIZE` tuning, no
`CoreModuleProperties.HEARTBEAT_INTERVAL`. Upload buffer 32 KB
([:207](../../core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:207))
matches typical `remote_maximum_packet_size`.

## SFTP — field observations

From the 2026-04-17 e2e drive:

1. **UD-305: multi-algorithm `known_hosts` rejects working hosts.** The
   load-bearing field finding — see §4. Manual `known_hosts` editing was
   the workaround.
2. **Hashed `known_hosts` writes a tempfile per connect.** No content
   hash cache; `deleteOnExit` doesn't run if the JVM is force-killed.
3. **MINA cannot parse `|1|` entries.** OpenSSH 8+ defaults
   `HashKnownHosts yes`, so any modern OpenSSH-populated `known_hosts`
   triggers the tempfile dance.
4. **`AcceptAllServerKeyVerifier` is one config-edit away.** WARN log
   visible only in `unidrive.log`; the tray cannot surface it.
5. **No keepalive — idle channels die silently.** NAT gateways drop
   idle TCP after 300–900 s; the next op surfaces as `Pipe broken`.
6. **No `posix-rename@openssh.com`.** Both load-bearing servers
   (OpenSSH, ProFTPD-mod_sftp) support it; we don't probe.
7. **`SSH_FX_FAILURE` overload bites on disk-full.** Anecdotally:
   `ensureParentDirs` on a quota-exceeded server returned status==4
   instead of `QUOTA_EXCEEDED=15`; we swallowed it as "directory exists",
   the subsequent file `open` then failed with the same status.

## Server-family matrix

| Family | SFTP version | `posix-rename` | `MaxSessions` default | Hashed `known_hosts` | Notes |
|---|---|---|---|---|---|
| OpenSSH (Linux) | v3 + extensions | yes | 10 | yes (default since 8.0) | Reference; UD-305 affects MINA, not the server |
| Synology DSM | v3 (OpenSSH-derived) | yes | ~10 (observed) | depends on DSM version | 4-cap calibrated for this |
| ProFTPD `mod_sftp` | v3 | yes | ~10 (configurable) | n/a (server-side) | `MaxClientsPerHost` is per-IP, not per-session |
| Apache MINA SSHD-server | v3 + selected v6 | configurable | configurable | n/a | Out of scope (we're the client) |
| Hetzner Storage Box | v3 (custom OpenSSH fork) | unknown | undocumented | yes | Conservative ≤4 cap |
| Bitvise SSH (Windows) | v3 + v4 | yes | configurable, default 10 | yes | v4 attribute set surfaces; we never use v4 |
| Commercial (WS_FTP, etc.) | varies | varies | varies | varies | Treat as v3-only |

The "varies" / "unknown" columns are the ones that bite first.

## Follow-ups

- **[UD-305](../backlog/BACKLOG.md#ud-305)** (open) — host-key brittleness.
  Preferred: `AggregateServerKeyVerifier` wrapping per-algorithm verifiers,
  with regression test using a stub SSH server that offers ecdsa while
  `known_hosts` lists ed25519+ecdsa.
- **Reconnect-on-disconnect.** Wire the §2 retry matrix. Channel-level
  reopen first; session-level reconnect as fallback.
- **Partial-file resume on upload.** Persist `(remotePath, offset)` so a
  failed transfer resumes from the last successful `write(handle, offset, …)`.
- **`posix-rename@openssh.com`.** Probe `session.getServerExtensions()`;
  use when present, fall back to plain rename.
- **Rekey regression test.** Drive > 1 GB transfer; assert no interruption.
- **Symlink-loop guard in `listAll`.** Depth cap + path-prefix-cycle
  detector (server-side device/inode is unavailable to clients).
- **MINA tuning.** Set `SftpModuleProperties.POOL_SIZE = maxConcurrency`;
  bump `CHANNEL_WINDOW_SIZE` / `CHANNEL_PACKET_SIZE` per
  [MINA docs](https://github.com/apache/mina-sshd/blob/master/docs/sftp.md).
- **Client keepalive.** `CoreModuleProperties.HEARTBEAT_INTERVAL` ~60 s
  to survive NAT idle timeouts.
- **`SftpException` semantic classes.** Branch `PERMISSION_DENIED`,
  `QUOTA_EXCEEDED`, `CONNECTION_LOST`, `OP_UNSUPPORTED` into named
  subclasses so callers can switch on type, not parse `statusCode`.
- **Connection-refusal differentiation.** Distinguish `ConnectException`,
  `SocketTimeoutException`, and `EOF before banner` on `connect()` so
  the operator gets a hint at fail2ban / `MaxStartups` vs wrong-port vs
  wrong-credentials.
</content>
</invoke>