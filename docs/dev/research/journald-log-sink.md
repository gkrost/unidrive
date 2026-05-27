# Research + Design: journald as the primary structured log sink

Status: research / design proposal (no code changed)
Scope: Linux (systemd) deployments of the UniDrive two-process stack
Author: research session 2026-05-27
Branch: `docs/journald-log-sink`

---

## 1. Context

UniDrive on Linux runs two cooperating processes:

- **JVM daemon** (`unidrive`, Kotlin): the sync engine, the UDS IPC server, and the
  provider HTTP/delta clients.
- **Rust FUSE co-daemon** (`unidrive-mount`, the `mount/` crate in the sibling repo
  `unidrive-mount-linux`): serves the mount, talks to the daemon over the IPC socket.

A single user action at the mount point (e.g. `cat /mnt/foo.txt`) fans out across both
processes: a FUSE `read`/`open` in the co-daemon → an IPC verb (`hydration.open_read`)
to the JVM → a provider HTTP call (`GET …/content`) inside the JVM. Today there is **no
way to reconstruct that one path** from logs, because the two processes log to different
sinks, in different formats, with no shared correlation key.

This document evaluates adopting **journald as the primary structured log sink** so that a
single `journalctl --user` query reconstructs the whole breadcrumb, eliminates the
stale-file confusion, and gives client-setup debugging a clear, queryable trail.

### Pain points to solve (verified against source — see §3)

1. Logs are scattered across multiple files **and** journald, and which sink is live
   depends on whether the process was started under systemd (because of `JOURNAL_STREAM`
   inheritance). The `/tmp/*` and `*.log` files are frequently **stale and misleading**.
2. There is **no unified structured field** to correlate one FUSE op → IPC verb →
   provider call across the two processes.
3. Even where journald already receives output (co-daemon stderr under systemd), it is
   captured as a **plain message string with no native journal fields** — so the
   structured key/value pairs the Rust code already emits via `tracing` are flattened to
   text and cannot be queried as fields.

---

## 2. Glossary of journald terms used below

- **Native journal protocol**: a process connects to `/run/systemd/journal/socket`
  (AF_UNIX SOCK_DGRAM) and submits records as `FIELD=value` datagrams via
  `sd_journal_send(3)`. This is the only path that lets an application attach **custom
  structured fields** and an explicit `PRIORITY=`.
- **`stdout` transport**: when a service's stdout/stderr is captured by journald (the
  default `StandardOutput=journal`), each line becomes one record with transport
  `stdout`. **No custom fields, no per-line PRIORITY** — the whole line is the `MESSAGE`.
- **`JOURNAL_STREAM`**: env var systemd sets to `dev:inode` of the stderr FD it captures.
  A process can `fstat(STDERR)` and compare; if it matches, stderr is already going to the
  journal, and the process may *upgrade* to the native protocol on its own socket.
  (Source: systemd Native Journal Protocol doc; `sd_journal_stream_fd(3)`.)

---

## 3. Current state (verified, with file refs)

### 3.1 JVM daemon

- **Logging framework: SLF4J + Logback 1.5.32.** Declared in
  `core/gradle/libs.versions.toml` (`logback = "1.5.32"`,
  `logback-classic = { module = "ch.qos.logback:logback-classic", … }`) and pulled into
  `:app:cli`, `:app:sync`, `:app:sync-tracking`, and both provider modules
  (`core/app/cli/build.gradle.kts:435`, `core/app/sync/build.gradle.kts:18`, etc.).
  There is **no log4j2 as a logging facade** — `log4j-core` only appears as a *forced
  pin for the shadow jar's transitive deps* (`core/app/cli/build.gradle.kts:13`), not as
  the app's logger.
- **Sink config:** `core/app/cli/src/main/resources/logback.xml`.
  - `CONSOLE` appender: stderr, `ThresholdFilter` at `WARN`.
  - `FILE` appender: `RollingFileAppender` at
    `${LOCALAPPDATA:-${user.home}/.local/share}/unidrive/unidrive.log`
    (i.e. `~/.local/share/unidrive/unidrive.log` on Linux), 10 MB × 5 files, 50 MB cap.
  - `org.krost.unidrive` logger at `DEBUG`; root at `WARN`.
- **Existing MDC schema** (this is the foundation we extend, not replace):
  - `build` — short git sha, seeded in `Main.main` (`core/app/cli/.../Main.kt:865`:
    `org.slf4j.MDC.put("build", BuildInfo.COMMIT)`).
  - `profile` — active profile name (UD-212), seeded around the run; rendered as
    `%X{profile:-*}` in the pattern.
  - `scan` — short random scan id (UD-254, `SyncEngine.kt:580`+) and relocate migration
    id (`RelocateMdc.kt`).
  - MDC is propagated across coroutines via `kotlinx-coroutines-slf4j` `MDCContext`
    (`core/app/sync/build.gradle.kts:24`, `RelocateCommand.kt`).
- **HTTP correlation already exists but is message-text only, not MDC.** `RequestIdPlugin`
  (`core/app/core/src/main/kotlin/org/krost/unidrive/http/RequestIdPlugin.kt`) assigns an
  8-char `req=<id>`, injects `X-Unidrive-Request-Id`, and logs `→ req=… / ← req=…`. Its
  own docstring notes Ktor pipelines "don't consistently carry MDC across suspensions,"
  so the id lives in a request attribute + the message string, **not** in MDC. Today you
  grep the *message body* for `req=<id>`.
- The pattern layouts render MDC into bracketed text columns, e.g.
  `… [%X{build}] [%X{profile:-*}] [%X{scan}] …` — good for humans, but the fields are
  **not** queryable as journal fields when the line is captured via the `stdout`
  transport.

### 3.2 Rust FUSE co-daemon (sibling repo, read-only)

- **Logging framework: `tracing` 0.1 + `tracing-subscriber` 0.3 (env-filter) +
  `tracing-appender` 0.2.** Declared in `unidrive-mount-linux/mount/Cargo.toml`.
  There is **no `tracing-journald` dependency today.**
- **Sink selection** (`mount/src/logging.rs`, added by PR #20 — commit `fddf4b5`
  "fix(mount): initialize tracing subscriber (RUST_LOG → file/journald) …"):
  - If `JOURNAL_STREAM` is set → `tracing_subscriber::fmt().with_writer(stderr)`.
    **This is *not* native journald** — it is plain text on stderr that systemd then
    captures via the `stdout` transport. The PR title's "journald" is shorthand for
    "stderr-under-systemd"; there are no native journal fields.
  - Otherwise → `tracing_appender::rolling::never(state_dir(), "unidrive-mount.log")`,
    where `state_dir()` is `$XDG_STATE_HOME/unidrive` (fallback `~/.local/state/unidrive`).
  - **Correction to the system-context premise:** the co-daemon's real file is
    `~/.local/state/unidrive/unidrive-mount.log` — *not* `/tmp/mount-<profile>.log`. The
    `/tmp/mount-<profile>.log` and `/tmp/daemon-<profile>.log` paths come from an external
    launch wrapper (and from running under Claude Code, per MEMORY note), **not** from the
    JVM `MountCommand`, which uses `ProcessBuilder(argv).inheritIO()`
    (`core/app/cli/.../MountCommand.kt:123`) — i.e. it inherits the JVM's own stdio,
    including any inherited `JOURNAL_STREAM`.
  - **Default level is `info`**, not `warn`:
    `EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"))`
    (`logging.rs:21-22`). Successful FUSE ops at DEBUG/TRACE still log nothing by default,
    but INFO-level lines (e.g. `cache_scanner: replayed deferred open_write`,
    `run.rs:125`) do.
- **The co-daemon already emits structured fields** — they are just rendered to text.
  Examples in `mount/src/fuse_fs.rs`:
  `tracing::warn!(error=%e, path=%path, handle_id=%handle_id, "open_read failed")`
  (line 366); `tracing::warn!(?e, cache_path=%cache_path.display(), "open(cache_path) failed")`
  (line 396). These `key=value` pairs map 1:1 onto native journal fields once a journald
  layer is installed — which is the cheap win.

### 3.3 IPC envelope (the correlation seam)

- Wire format: **line-delimited JSON over a Unix domain socket.** The request envelope is
  `{"verb":"<name>", …fields…}` with the verb being the only mandatory top-level key.
  The JVM `IpcServer` only probes for the top-level `"verb"` field
  (`core/app/sync/.../IpcServer.kt:497` `parseVerb`); **any additional top-level keys are
  ignored by the parser** and passed through to the handler as raw JSON. The Rust client
  builds these envelopes with `serde_json::json!({"verb": …, …})`
  (`unidrive-mount-linux/mount/src/ipc.rs`, e.g. `open_read` at line 84).
- Reply envelope: `{"ok":true|false, …}` (or `{"error":"…", …}` on handler throw,
  `IpcServer.kt:477`).
- Verbs in use (`HydrationIpcHandler` docstring,
  `core/app/hydration/.../HydrationIpcHandler.kt:17-65`): `hydration.open_read`,
  `open_write`, `open_write_begin`, `close_handle`, `hydrate`, `dehydrate`, `mkdir`,
  `unlink`, `rmdir`, `create`, `rename`, `last_synced`, `list`, `subscribe`. Plus
  `sync.subscribe` / `refresh.run` from the sync side.
- **Key fact for §6:** because both the JVM `parseVerb` and the Rust `serde_json` parse
  are tolerant of unknown top-level keys, a `"cid":"…"` correlation field can be added to
  the envelope **without breaking the existing wire contract** — old and new peers
  interoperate (the field is simply ignored by a peer that doesn't read it).

### 3.4 systemd unit

- `dist/unidrive.service` is a minimal user-style unit: `Type=simple`,
  `ExecStart=%h/.local/bin/unidrive sync --watch`, `Restart=on-failure`. **No logging
  knobs set** — no `SyslogIdentifier`, `LogNamespace`, `LogExtraFields`,
  `StandardOutput/Error`. It defaults to `StandardOutput=journal`, so its stdout/stderr
  is already captured by journald via the `stdout` transport (plain text, no fields).
- The daemon-design spec (`docs/dev/specs/unidrive-daemon-design.md`) lists
  systemd-user-unit generation as a deferred non-goal (NG3) — so a journald-aware unit
  template is greenfield, not a rework.

### 3.5 Platform stance

- ADR `docs/adr/multi-platform.md`: UniDrive is a **multi-platform core**; the Linux
  daemon is the first ship target, with Windows and Android tiers planned. Therefore
  **journald cannot be the *only* sink** — the design must keep a file fallback for
  non-systemd Linux, macOS, Windows, and containers without a journal socket.

---

## 4. Options per layer

### 4.1 JVM → journald

| Option | Mechanism | Native fields? | PRIORITY? | Maintenance | Verdict |
|---|---|---|---|---|---|
| **(a1) `org.gnieh:logback-journal` 0.3.0** | Logback appender → `sd_journal_send` via JNA; maps MDC → journal fields | Yes | Yes | **Archived 2024-03-22**, last 0.3.0 | Reject — abandoned, JNA-in-`/tmp` risk |
| **(a2) `de.bwaldvogel:log4j-systemd-journal-appender` 2.6.0** | Native, structured, MDC→fields | Yes | Yes | Active (2.6.0) | Reject — it is a **Log4j2** appender; UniDrive uses **Logback**, not log4j2 |
| **(b) stdout/stderr JSON → systemd `stdout` transport** | Logback `ConsoleAppender` + JSON encoder; systemd captures it | **No** (one `MESSAGE` per line, transport=`stdout`) | No (per-line) | Trivial | Partial — simple, portable, but loses the field-query power that is the whole point |
| **(c) small custom Logback appender → native journal socket** | Write `FIELD=value` datagrams to `/run/systemd/journal/socket` directly (no libsystemd link), per the documented native protocol; or thin JNA wrapper over `sd_journal_sendv` | Yes | Yes | We own it (~150 LOC) | **Recommended** — see §7 |
| (d) GELF / journal-gateway | Ship to a remote collector | n/a | n/a | Extra infra | Reject — out of scope; client-setup debugging is local |

Why (c) over (a1): the gnieh appender is exactly the right shape (Logback + MDC→fields +
`PRIORITY`) but is archived and ships a bundled native lib it extracts into `java.io.tmpdir`
— a `noexec /tmp` hazard and a supply-chain liability for an abandoned dep. The native
journal protocol is **documented and stable** (systemd.io `JOURNAL_NATIVE_PROTOCOL.md`);
a tiny appender that opens `/run/systemd/journal/socket` and sends `FIELD\nvalue` datagrams
needs no JNA, no libsystemd link, and no extracted native blob. It can be vendored into
`:app:cli` and gated on "is the socket present + are we on Linux."

**Minimal change either way:** the appender consumes MDC keys we *already* set (`build`,
`profile`, `scan`) and we add a few more (§5). No call-site logging changes are required
for the basics; the schema is populated by MDC, which the existing `MDCContext`
propagation already threads through coroutines.

### 4.2 Rust → journald

| Option | Mechanism | Native fields? | Composes with #20 `tracing` setup? | Verdict |
|---|---|---|---|---|
| **`tracing-journald` 0.3.1** | `tracing_subscriber::Layer` that writes records to the journal native socket; sanitizes field names; supports `with_syslog_identifier(...)`, `with_field_prefix(None)`, and `Layer::with_field(...)` for constant fields | **Yes** | Yes — it's a `Layer`, drop-in alongside the existing `EnvFilter` | **Recommended** |
| `systemd`/`libsystemd` FFI `sd_journal_send` | Direct FFI | Yes | Manual plumbing into `tracing` needed | Reject — `tracing-journald` already wraps this idiomatically |
| keep stderr-only (#20 status quo) | text via `stdout` transport | No | n/a | Reject — the gap we're closing |

`tracing-journald` is the natural fit: the co-daemon **already** emits the right
key/value pairs (`error=…, path=…, handle_id=…`), and the layer turns each into a native
journal field automatically (after name sanitization: dots→underscores, leading
underscores stripped, uppercased). Note its default field prefix is `F` (so `path`
becomes `F_PATH`); we will set `with_field_prefix(None)` and instead namespace via the
explicit `UNIDRIVE_*` constant fields (§5) so field names match the JVM side exactly.

**Composition with #20:** replace the sink-selection in `logging.rs` with a layered
subscriber:
- journal socket present (probe `/run/systemd/journal/socket`, or `JOURNAL_STREAM` set)
  → `tracing-journald` layer **+** the constant `UNIDRIVE_*` fields;
- else → keep the existing `tracing_appender` file sink (fallback).
The `EnvFilter` (default `info`) stays exactly as is.

---

## 5. Structured field schema

Journal field naming rules (systemd.journal-fields(7), verified):
- Field names: **ASCII uppercase letters, digits, and `_` only**, **must not start with
  `_`** (leading underscore is reserved for *trusted* fields journald adds itself, e.g.
  `_PID`, `_SYSTEMD_UNIT`), **max 64 bytes**.
- Values are arbitrary (binary-safe via the native protocol).
- `MESSAGE` and `PRIORITY` (0–7, syslog levels) are the well-known fields.

Proposed shared schema (identical names on both processes):

| Field | Values | Meaning | Source |
|---|---|---|---|
| `MESSAGE` | free text | human line | both |
| `PRIORITY` | 0–7 | 3=ERROR, 4=WARN, 6=INFO, 7=DEBUG | mapped from log level |
| `SYSLOG_IDENTIFIER` | `unidrive` / `unidrive-mount` | per-process tag (filter via `journalctl -t`) | unit / `with_syslog_identifier` |
| `UNIDRIVE_COMPONENT` | `daemon` \| `mount` \| `provider` | which layer emitted it | constant per process; `provider` set in MDC inside provider calls |
| `UNIDRIVE_PROFILE` | profile name | the active profile | JVM: from `profile` MDC; Rust: constant set at startup from `--ipc`/profile |
| `UNIDRIVE_OP` | e.g. `fuse.read`, `ipc.open_read`, `provider.get_content` | the operation verb at this layer | per-call |
| `UNIDRIVE_PATH` | `/foo/bar.txt` | the mount-relative path | per-call where applicable |
| `UNIDRIVE_CID` | 8–16 char id | **correlation id** threading one op across processes (§6) | per-op |
| `UNIDRIVE_REQUEST_ID` | 8 char | existing HTTP `req=` id (JVM provider layer) | `RequestIdPlugin` |
| `UNIDRIVE_BUILD` | git sha | build that produced the line | JVM `build` MDC / Rust constant |
| `UNIDRIVE_SCAN` | scan id | existing sync-scan id | JVM `scan` MDC |
| `UNIDRIVE_HANDLE` | handle id | FUSE/hydration handle | both, per-call where applicable |

Mapping notes:
- **JVM:** the custom appender emits one journal field per MDC key, prefixed `UNIDRIVE_`
  and uppercased (`profile`→`UNIDRIVE_PROFILE`, `build`→`UNIDRIVE_BUILD`,
  `scan`→`UNIDRIVE_SCAN`), plus a constant `UNIDRIVE_COMPONENT=daemon` (or `provider`
  when inside a provider call), the level→`PRIORITY` map, and `MESSAGE`. `UNIDRIVE_CID`
  and `UNIDRIVE_OP` come from new MDC keys (`cid`, `op`).
- **Rust:** `tracing-journald` with `with_field_prefix(None)` emits each event field as a
  field. We rename the existing ad-hoc fields to the schema (`path`→`UNIDRIVE_PATH`,
  `handle_id`→`UNIDRIVE_HANDLE`) at the call sites *incrementally*, and add constant
  fields `UNIDRIVE_COMPONENT=mount`, `UNIDRIVE_PROFILE=<name>`, `UNIDRIVE_BUILD=<sha>` via
  `Layer::with_field(...)`. `UNIDRIVE_OP` and `UNIDRIVE_CID` are set per-FUSE-op (ideally
  via a `#[tracing::instrument(fields(UNIDRIVE_OP = "fuse.read", UNIDRIVE_CID = %cid))]`
  span so every line inside the op inherits them).

---

## 6. Cross-process correlation design

Goal: one `journalctl … UNIDRIVE_CID=<id>` query returns the full breadcrumb:
`fuse.read` (mount) → `ipc.open_read` (mount client + daemon handler) →
`provider.get_content` (daemon) — across **both** processes.

### 6.1 Where the id is born

The correlation id is **minted in the co-daemon at the top of each FUSE op**, because the
FUSE op is the true root of the causal chain (the kernel calls in; everything else is
downstream). A `u64`/16-hex random per op is sufficient (collision-free within a debugging
window). It is stored in the FUSE op's tracing span field `UNIDRIVE_CID`.

### 6.2 Threading it across the IPC seam

Add **one optional top-level field** to the IPC request envelope:

```jsonc
{"verb":"hydration.open_read","handle_id":"h1","path":"/foo.txt","cid":"a1b2c3d4e5f6"}
```

- **Rust side** (`mount/src/ipc.rs`): each verb builder adds `"cid": cid` from the current
  op's span. (`round_trip` could inject it centrally if the cid is carried on the client,
  but per-verb is explicit and avoids hidden state.)
- **JVM side** (`HydrationIpcHandler`): parse the optional `cid`, and **seed it into MDC**
  (`MDC.put("cid", cid)`) for the duration of the handler, inside the existing
  `withContext(handlerDispatcher)` dispatch in `IpcServer.dispatchRequest`. Because the
  daemon already propagates MDC across coroutines via `MDCContext`, the cid then rides
  along into the provider call automatically — and the JVM appender emits it as
  `UNIDRIVE_CID` on every line of that handler, including the `RequestIdPlugin` lines (so
  `UNIDRIVE_CID` and `UNIDRIVE_REQUEST_ID` co-occur, linking the IPC verb to its HTTP
  call).

Backward/forward compatibility: §3.3 established that both parsers ignore unknown
top-level keys, so adding `cid` is wire-compatible — a new mount talking to an old daemon
just gets no `UNIDRIVE_CID` on the daemon side (graceful degradation), and an old mount
talking to a new daemon yields handler lines with no cid (also fine).

### 6.3 Op-name (`UNIDRIVE_OP`) convention

Each layer tags its own verb so the chain reads top-down:
- mount: `fuse.read`, `fuse.open`, `fuse.create`, `fuse.rename`, …
- mount IPC client + daemon handler: `ipc.open_read`, `ipc.hydrate`, `ipc.list`, …
  (derive directly from the verb string — drop the `hydration.` prefix or keep it).
- daemon provider call: `provider.get_content`, `provider.upload`, `provider.delta`, …

Result: `UNIDRIVE_CID=<id>` filtered, sorted by time, yields a clean stack of
`fuse.read → ipc.open_read → provider.get_content (req=…)`.

---

## 7. Recommendation

1. **JVM → journald: a small in-tree Logback appender writing the native journal
   protocol** (option 4.1c). It maps MDC → `UNIDRIVE_*` fields, sets `PRIORITY` from the
   level, and is gated on `os == Linux && /run/systemd/journal/socket exists`. Keep the
   existing `RollingFileAppender` as the **fallback** appender (selected when the socket
   is absent: non-systemd Linux, macOS, Windows, containers). Reject the archived
   `gnieh` appender and the log4j-only `bwaldvogel` one.
   - *Lower-effort interim:* if writing the appender is deprioritized, ship option 4.1b
     (JSON-on-stdout via a Logback JSON encoder + an explicit `SyslogIdentifier=unidrive`)
     as a stop-gap — but document that it has **no native fields / no per-line PRIORITY**,
     so `UNIDRIVE_*=` field filters won't work; you'd grep the JSON `MESSAGE`. This is a
     bridge, not the destination.
2. **Rust → journald: `tracing-journald` 0.3.1** as a `tracing_subscriber::Layer`
   (option 4.2), composed alongside the existing `EnvFilter`, gated the same way. Set
   `with_syslog_identifier("unidrive-mount")`, `with_field_prefix(None)`, and constant
   `UNIDRIVE_COMPONENT=mount` / `UNIDRIVE_PROFILE` / `UNIDRIVE_BUILD` via `with_field`.
   Keep `tracing_appender` file sink as the fallback. Critically, **fix the #20
   selection logic**: detecting `JOURNAL_STREAM` should *upgrade to the native protocol*
   (so structured fields survive), not merely write text to stderr.
3. **IPC correlation: add an optional `"cid"` field to the request envelope**, minted in
   the co-daemon FUSE op, seeded into JVM MDC by `HydrationIpcHandler`/`IpcServer`. Emit
   it as `UNIDRIVE_CID` from both appender/layer. Wire-compatible by construction (§6.2).
4. **Shared field schema (§5)** — identical field names on both sides; `UNIDRIVE_`
   prefix; uppercase; ≤64 bytes; never leading underscore.
5. **systemd unit knobs** (`dist/unidrive.service` and a future co-daemon unit):
   `SyslogIdentifier=unidrive` (daemon) / `unidrive-mount` (co-daemon), optionally
   `LogNamespace=unidrive` to isolate the stream, and `LogExtraFields=UNIDRIVE_PROFILE=…`
   for unit-level constants. Leave `StandardOutput=journal` (default) as the safety net
   for anything logged before the native sink is wired.

---

## 8. Field schema + retrieval cheat-sheet

All commands assume user services (`--user`). Drop `--user` for system units.

```bash
# Follow everything UniDrive (both processes) live, structured:
journalctl --user -t unidrive -t unidrive-mount -f -o cat

# Follow one profile across BOTH processes:
journalctl --user UNIDRIVE_PROFILE=posteo_onedrive -f

# *** The single most valuable recipe — reconstruct ONE op end-to-end across
#     both processes, in time order, as structured JSON: ***
journalctl --user UNIDRIVE_CID=a1b2c3d4e5f6 -o json-pretty
#   → fuse.read (mount) → ipc.open_read (mount+daemon) → provider.get_content (daemon, req=…)

# Follow one path (everything that touched /foo/bar.txt):
journalctl --user UNIDRIVE_PATH=/foo/bar.txt -o short-iso

# Only warnings and worse, one profile:
journalctl --user UNIDRIVE_PROFILE=work -p warning

# Just the co-daemon, just FUSE reads, with fields:
journalctl --user -t unidrive-mount UNIDRIVE_OP=fuse.read -o json | jq '{t:.__REALTIME_TIMESTAMP, path:.UNIDRIVE_PATH, cid:.UNIDRIVE_CID, msg:.MESSAGE}'

# Link an HTTP request to its IPC op:
journalctl --user UNIDRIVE_REQUEST_ID=1a2b3c4d -o json-pretty   # shows the cid alongside

# Everything since the last boot for one component:
journalctl --user UNIDRIVE_COMPONENT=provider -b

# Field-filter combinations are ANDed for the same field as OR, across fields as AND:
journalctl --user UNIDRIVE_PROFILE=work UNIDRIVE_COMPONENT=mount UNIDRIVE_COMPONENT=daemon
#   → profile=work AND (component=mount OR component=daemon)
```

journald config caveats to call out in docs:
- **Rate limiting:** `RateLimitIntervalSec` / `RateLimitBurst` in `journald.conf` (default
  ~10000 msgs / 30 s per service) can *drop* lines during a burst (e.g. a `readdir` over a
  huge folder at DEBUG). Set `LogRateLimitIntervalSec=0` (or a higher burst) on the unit
  for debugging sessions, or keep DEBUG behind `RUST_LOG`/logger level so bursts don't
  happen in normal operation.
- **Storage:** user journals honor `Storage=` (default `auto` → persistent only if
  `/var/log/journal` exists). On a setup with `Storage=volatile`, the breadcrumb is lost
  on reboot — note this for client-setup debugging where you want history.
- **Field size:** values are not truncated by the 64-byte rule (that's names); but very
  large `MESSAGE` payloads are subject to `LineMax`.

---

## 9. Phased implementation plan

**Phase 0 — schema + unit knobs (docs/config only, no behavior change).**
Land the field schema (§5) as the contract. Add `SyslogIdentifier=unidrive` to
`dist/unidrive.service` and create a co-daemon unit template with
`SyslogIdentifier=unidrive-mount`. *Minimal first step, zero risk.*

**Phase 1 — Rust co-daemon native journald (highest value / lowest cost).**
Add `tracing-journald = "0.3.1"`. In `mount/src/logging.rs`, when the journal socket /
`JOURNAL_STREAM` is present, install the `tracing-journald` layer (native protocol) with
`with_syslog_identifier`, `with_field_prefix(None)`, and constant `UNIDRIVE_*` fields;
keep the file appender as fallback. No call-site changes needed for the win — existing
`tracing::warn!(path=…, handle_id=…)` fields become queryable immediately. *This alone
fixes pain point #3 and the stale-file problem for the co-daemon.*

**Phase 2 — JVM native journald appender.**
Add the in-tree native-protocol Logback appender (or, as interim, JSON-on-stdout +
`SyslogIdentifier`). Wire it into `logback.xml` as a third appender selected at runtime by
"Linux + socket present," falling back to the existing `FILE` appender. Map MDC →
`UNIDRIVE_*` and level → `PRIORITY`. *Now both processes land in the journal natively.*

**Phase 3 — correlation id.**
Add `cid` to the Rust verb builders (`mount/src/ipc.rs`) sourced from a per-FUSE-op span
field `UNIDRIVE_CID`. Parse + MDC-seed `cid` in `HydrationIpcHandler`/`IpcServer`
dispatch. Add `UNIDRIVE_OP` tagging at each layer. *Now `UNIDRIVE_CID=` reconstructs the
whole path — the headline deliverable.*

**Phase 4 — polish.**
Rename remaining ad-hoc Rust fields to the schema; add `UNIDRIVE_OP` on the JVM provider
calls; document the cheat-sheet in the README/runbook; add a `unidrive doctor` hint that
prints the right `journalctl` command for the active profile.

---

## 10. Risks & trade-offs

- **Portability — journald is Linux/systemd only.** The multi-platform ADR
  (`docs/adr/multi-platform.md`) means the file sink **must remain** as the fallback for
  macOS, Windows, non-systemd Linux, and containers. Every phase keeps the file appender;
  journald is *primary on systemd Linux*, not *exclusive*. Mitigation is built into the
  selection gate (socket-present probe).
- **Native-protocol appender is in-tree code we maintain.** ~150 LOC, but it must handle
  socket-absent gracefully and never take the process down (the Rust side already states
  "logging must not be able to take the mount down"). The protocol is documented and
  stable; risk is low but nonzero. Alternative interim (JSON-on-stdout) sidesteps it at
  the cost of losing native fields.
- **`tracing-journald` is 0.3.1, ~1 year since last release.** It is a tokio-rs crate
  (the `tracing` org), widely used, low-churn surface — acceptable. Pin the version.
- **Rate-limit drops** can silently lose breadcrumb lines under DEBUG bursts (§8). Keep
  verbose levels gated behind `RUST_LOG`/logger config; raise the unit's rate limit only
  for active debugging.
- **Volatile storage** loses history on reboot — note in the client-setup runbook;
  recommend `Storage=persistent` (or `/var/log/journal` present) for support scenarios.
- **The stale-file problem is *eliminated* for systemd runs** (single sink), but the
  fallback files still exist off-systemd; the doc/runbook must tell operators which sink
  is live (the `doctor` hint in Phase 4 addresses this).
- **Correlation id adds a field to a hot wire path.** It's ~20 bytes/verb on an
  already-JSON line under the 64 KiB cap; negligible. The MDC put/remove per handler is
  cheap and already the pattern used for `scan`.

### Minimal first step
Phase 0 + the Phase-1 Rust change: add `tracing-journald`, upgrade the `JOURNAL_STREAM`
branch in `mount/src/logging.rs` to the native layer with `SyslogIdentifier=unidrive-mount`
and constant `UNIDRIVE_COMPONENT`/`UNIDRIVE_PROFILE` fields. One crate, one file, no wire
changes, no JVM changes — and it immediately makes the co-daemon's existing structured
fields queryable and kills the stale `unidrive-mount.log` confusion under systemd.

---

## 11. References (consulted 2026-05-27)

Library docs / versions:
- `tracing-journald` **0.3.1** (latest; tokio-rs/tracing) — `Layer`, `with_syslog_identifier`,
  `with_field_prefix` (default `"F"`), field-name sanitization, native journal socket.
  crates.io/crates/tracing-journald ; docs.rs/tracing-journald/0.3.0/…/struct.Layer.html
- `org.gnieh:logback-journal` **0.3.0** — **repo archived 2024-03-22**; JNA + bundled
  native lib; MDC→fields, `syslogIdentifier`, `logMdc`. github.com/gnieh/logback-journal ;
  mvnrepository.com/artifact/org.gnieh/logback-journal
- `de.bwaldvogel:log4j-systemd-journal-appender` **2.6.0** (active) — **Log4j2** appender,
  not Logback; `CODE_FILE/CODE_LINE/CODE_FUNC`, `STACKTRACE`, `THREAD_NAME`, MDC; JNA.
  github.com/bwaldvogel/log4j-systemd-journal-appender
- `systemd-journal-logger` (Rust, alternative to tracing-journald for the `log` facade) —
  noted, not chosen. crates.io/crates/systemd-journal-logger

systemd / journald specs:
- Native Journal Protocol — `/run/systemd/journal/socket`, `FIELD=value` datagrams,
  `JOURNAL_STREAM` upgrade path, structured metadata vs stderr. systemd.io/JOURNAL_NATIVE_PROTOCOL/
- `sd_journal_send(3)` — `VARIABLE=value`, uppercase/digits/underscore, no leading
  underscore, assignments violating syntax are ignored. man7.org / man.archlinux.org
- `systemd.journal-fields(7)` — field name rules (ASCII upper + digits + `_`, ≤64 bytes),
  well-known fields (`MESSAGE`, `PRIORITY`, `SYSLOG_IDENTIFIER`, `_PID`, `_SYSTEMD_UNIT`).
  freedesktop.org / man7.org
- `systemd-journald.service(8)` + `journald.conf` — `Storage=`, rate-limiting
  (`RateLimitIntervalSec`/`RateLimitBurst`), `LogNamespace`. freedesktop.org / man7.org
- `journalctl(1)` — `-t`/`SYSLOG_IDENTIFIER`, `FIELD=value` filters (OR within a field,
  AND across fields), `-o json` / `-o json-pretty`, `-p`, `-b`, `-f`. man pages.

Source files verified in this session (UniDrive repos):
- JVM: `core/gradle/libs.versions.toml`; `core/app/cli/src/main/resources/logback.xml`;
  `core/app/cli/.../Main.kt` (MDC `build`); `core/app/sync/.../SyncEngine.kt`,
  `RelocateMdc.kt` (MDC `scan`/`profile`); `core/app/core/.../http/RequestIdPlugin.kt`;
  `core/app/sync/.../IpcServer.kt` (`parseVerb`, dispatch);
  `core/app/hydration/.../HydrationIpcHandler.kt` (verb wire format);
  `core/app/cli/.../MountCommand.kt` (`inheritIO`); `dist/unidrive.service`;
  `docs/adr/multi-platform.md`; `docs/dev/specs/unidrive-daemon-design.md`.
- Rust (`unidrive-mount-linux`, read-only): `mount/Cargo.toml`; `mount/src/logging.rs`
  (PR #20 / commit `fddf4b5`); `mount/src/ipc.rs` (verb builders, `round_trip`);
  `mount/src/fuse_fs.rs`, `mount/src/run.rs` (structured `tracing` call sites).
