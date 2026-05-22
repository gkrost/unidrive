# Sparse-hydration roadmap — design spec

## Status

Approved design. Implementation plan to follow in a separate document.

## Goal

Replace the current "hydrate everything OR maintain a pin-and-exclude
allowlist" tradeoff with a virtual-file layer that lets users browse
the full cloud namespace at zero local-disk cost and hydrate files
on demand, transparently to the calling program.

## Scope

Three sequenced phases, each delivering value individually:

1. **Phase 1 — Core SPI** (`core/app/hydration/`, Kotlin, this repo).
   Thin verb-based interface that wraps existing engine paths. Single
   concrete consumer: the Phase 2 co-daemon.
2. **Phase 2 — Daemon-mount interim** (Rust co-daemon, new sibling
   repo `unidrive-mount-linux/`). Implements FUSE3, talks to the JVM
   over the existing IPC socket, hands hydrated files to the kernel
   via `FUSE_PASSTHROUGH`. Read + write-through. Shipped via
   `unidrive mount <path>` CLI subcommand.
3. **Phase 3 — Linux UI tier extensions** (same Rust workspace,
   Dolphin / KDE integration crates). Context-menu "Hydrate / Free",
   D-Bus event stream, icon overlay refresh. Built on top of the
   Phase 2 binary.

## Decisions locked

| Question | Decision |
|---|---|
| Roadmap scope | Full three-phase sequenced |
| Phase 2 access model | Read + write-through (full POSIX semantics) |
| Phase 1 SPI shape | Thin verbs; grow domain objects only when a concrete consumer demands them |
| Kernel floor | Hard 6.9+ (`FUSE_PASSTHROUGH` required, no graceful degrade to older kernels) |
| Co-daemon language | Rust |
| Sequencing | Spike-and-harden — Phase 1 SPI and Phase 2 co-daemon evolve together in the first sprint; both ship together in the second; Phase 3 builds on the released co-daemon |
| Cache location | `~/.cache/unidrive/hydration/` (XDG-conformant) |
| Repo grouping | Phase 2 + Phase 3 share a single Rust workspace `unidrive-mount-linux/` with multiple crates |

## Architecture

```
                +----------------------------------+
                |  unidrive  (this repo, JVM)      |
                |                                  |
                |  SyncEngine (existing)           |
                |  state.db   (existing)           |
                |                                  |
                |  core/app/hydration/  (NEW)      |
                |   ├── openForRead / Write        |
                |   ├── hydrate / dehydrate        |
                |   └── Flow<HydrationEvent>       |
                |                                  |
                |  CLI: `unidrive mount <path>`    |
                |   (spawns + supervises co-daemon)|
                +----------------------------------+
                              ▲
                       UDS  ▼ │  ▲
                            │ JSON-line IPC (existing IpcServer protocol)
                              ▼
                +----------------------------------+
                |  unidrive-mount-linux  (NEW repo)|
                |  Rust workspace                  |
                |                                  |
                |  crate `mount` (Phase 2)         |
                |   ├── fuse3 + Tokio              |
                |   ├── FUSE_PASSTHROUGH on        |
                |   │     hydrated files (≥ 6.9)   |
                |   └── kernel-floor check on init |
                |                                  |
                |  crate `kio` (Phase 3)           |
                |   ├── Dolphin context-menu ext.  |
                |   └── D-Bus triggers to `mount`  |
                +----------------------------------+
```

Module-boundary rules inherited from the multi-platform ADR
(`docs/adr/multi-platform.md`):

- **Phase 1** is the named seam between core and platform tiers.
  Single concrete consumer (Phase 2) + structural-safety justification
  (keeps platform IPC out of `core/`) clear the AGENTS.md
  "abstractions earn their keep" bar.
- **Phase 2 and Phase 3** do NOT live inside `core/app/`. AGENTS.md
  "*Don't grow the daemon to host a UI tier*" stays honoured — the
  FUSE binary is its own process, the Dolphin extension is its own
  crate.
- **IPC** reuses the existing `IpcServer` (JSON-line over UDS,
  already speaks to status/sync clients). No new transport — just
  new message types.

## Components

### Phase 1 — `core/app/hydration/` (Kotlin)

| Component | Purpose |
|---|---|
| `Hydration` interface | Defines six verbs: `openForRead`, `openForWrite`, `closeHandle`, `hydrate`, `dehydrate`, `events: Flow<HydrationEvent>`. Package `org.krost.unidrive.hydration`. |
| `HydrationImpl` | Wires verbs to existing `SyncEngine` and `StateDatabase`. No new persistence. Reads `is_hydrated`, triggers existing download/upload paths, emits events on transitions. Maintains an in-memory **connection-scoped** open-set: `openSets: Map<ipc_connection_id, Map<handle_id, path>>`, populated by `openForRead` / `openForWrite` / `closeHandle` calls. `dehydrate` consults the union across all connections to refuse if any handle is open. **IPC-connection drop** (e.g. co-daemon crash): JVM clears that connection's entries automatically — no client cooperation needed; protects against crashes permanently blocking dehydrate. |
| `HydrationEvent` sealed class | Variants: `Hydrating(path)`, `Hydrated(path, bytes)`, `Dehydrated(path)`, `Failed(path, error: HydrationError)`. The `HydrationError` sealed interface starts with a single variant `Generic(message: String)`; Phase 3 adds discriminants (`Transient`, `Permanent`, `QuotaExhausted`, `Busy`) as the icon-overlay UX surfaces them. Extension point pinned now to avoid a Phase-3 wire-format break. |
| `HydrationIpcHandler` | Six message types added to existing `IpcServer`: `hydration.open_read`, `hydration.open_write`, `hydration.close_handle`, `hydration.hydrate`, `hydration.dehydrate`, `hydration.subscribe`. JSON-line wire format, matching the existing protocol. |
| Tests | Unit tests covering cold/warm read, dehydrate refusal, event emission, `close_handle` robustness, IPC-disconnect cleanup, and JSON-line wire format, plus one live-integration smoke (`hydrate-then-read`, gated by `UNIDRIVE_INTEGRATION_TESTS=true`; fills the third sync smoke in the 5+5+2 target). |

Explicit non-features for Phase 1: no `PlaceholderCatalog` domain
object, no `HydrationOrchestrator`, no reactive UI state model. Grow
on demand.

### Phase 2 — `unidrive-mount-linux/mount/` (Rust binary)

| Component | Purpose |
|---|---|
| `bin/unidrive-mount.rs` | Entry point. Parses args (`--mount <path> --ipc <socket>`), runs kernel-version check, mounts. |
| Kernel-floor check | Reads `/proc/sys/kernel/osrelease`. Refuses to start if < 6.9 with exit code 78 (`EX_CONFIG`) and a one-line stderr citing the required kernel + which feature is missing. |
| `fuse3` filesystem impl | `getattr` / `readdir` / `open` / `read` / `write` / `release` / `fsync`. Read path uses `FUSE_PASSTHROUGH` for hydrated files — kernel reads directly from the backing FD, zero userspace round-trip. |
| `IpcClient` | Sync RPC against existing JVM IpcServer over UDS. Tokio + tokio-uds. Issues `close_handle` on every FUSE RELEASE so the JVM's open-set stays accurate. |
| `LocalCache` | Backing storage for hydrated files at `~/.cache/unidrive/hydration/`. Layout mirrors remote paths. Owned by the JVM; Phase 2 reads cache entries by path returned from `open_read`/`open_write` replies. |
| Tests | Six Rust integration tests (kernel floor, cold read, warm read, write-through, dehydrate-while-open, IPC reconnect) + a per-tier smoke set (~5 smoke tests, scope mirror of the existing 5+5 provider smokes). |

### Phase 3 — `unidrive-mount-linux/kio/` and friends

| Component | Purpose |
|---|---|
| Dolphin `.desktop` ServiceMenus | "Hydrate this folder / Free this folder" context-menu entries. |
| Small D-Bus shim | Receives the ServiceMenus calls, forwards to the `mount` binary's D-Bus service. |
| `mount` binary's D-Bus service | Phase 3 work in the `mount` crate — exposes hydrate/dehydrate verbs over D-Bus alongside the existing FUSE behaviour. Subscribes to the IPC event stream and re-publishes as D-Bus signals so Dolphin can refresh icon overlays. |
| KIO slave | Skipped at first. Promote from optional only if ServiceMenus prove insufficient. |

## Data flow

### Cold read (placeholder → first access)

```
user: $ cat ~/Internxt/.tresor/key.txt
          │
          ▼
kernel    open(2) ─► FUSE LOOKUP + OPEN
          │
          ▼
co-daemon ─► LocalCache miss
          ─► IPC: hydration.open_read("/.tresor/key.txt")
                                                       │
                                                       ▼
JVM           HydrationImpl.openForRead
              ─► state.db: is_hydrated=0
              ─► SyncEngine.download() (existing path)
              ─► write to LocalCache
              ─► state.db: is_hydrated=1
              ─► reply { cache_path, handle_id }
                                                       │
                                                       ▼
co-daemon ◄── open(cache_path), FUSE_PASSTHROUGH ioctl
          ─► kernel hands real FD back to user process
          ─► subsequent read(2) bypass FUSE entirely
          ─► FUSE RELEASE on close ─► IPC: close_handle(handle_id)
```

### Warm read

Same shape as cold read: co-daemon still calls `hydration.open_read`
(cheap, because `is_hydrated=1` — JVM checks the cache file exists
and returns `{cache_path, handle_id}` without triggering download).
Co-daemon opens the cache file, applies `FUSE_PASSTHROUGH` ioctl,
issues `close_handle(handle_id)` on RELEASE. The performance fast
path is `FUSE_PASSTHROUGH` itself (reads bypass userspace), not
skipping the open-time IPC.

### Write-through

```
user: $ vim ~/Internxt/.tresor/key.txt ; :w
          │
          ▼
kernel    open(O_RDWR) ─► (cold-read first if not hydrated)
          write(2)      ─► kernel writes through passthrough FD → cache
          close(2)      ─► FUSE RELEASE
          │
          ▼
co-daemon ─► IPC: hydration.open_write(path, cache_path)
                                                       │
                                                       ▼
JVM           HydrationImpl.openForWrite
              ─► SyncEngine.upload(cache_path) (existing path)
              ─► on success: emit HydrationEvent.Hydrated(path, bytes)
              ─► on failure: emit HydrationEvent.Failed(path, error)
              ─► state.db row's mtime/size updated for book-keeping
```

This `open_write` IPC at FUSE RELEASE is the **only** write-trigger.
The engine deliberately does NOT sync-scan its own FUSE mount —
walking `~/Internxt/` would call back through FUSE → IPC → JVM,
introducing a loopback the engine has no reason to incur. The cache
tree at `~/.cache/unidrive/hydration/` is not in any sync_root either;
the engine learns about cache changes only via the IPC chain.

If upload fails (network drop, 5xx, quota): cache file stays with the
new content; the existing engine retry queue + transient-failure
handling applies. The user's `close(2)` already returned 0 — the
failure surfaces asynchronously via `HydrationEvent.Failed`, consumed
by Phase 3 (icon overlay) or the CLI `--watch-events` mode.

### Explicit hydrate / dehydrate

`unidrive get /path` or Dolphin "Hydrate" → `hydration.hydrate(path)`
→ engine download → cache file → emit `Hydrated`.

`unidrive free /path` or Dolphin "Free" → `hydration.dehydrate(path)`
→ JVM checks its own `openSet` (no IPC round-trip needed) → if open,
returns `HydrationError.Busy`; else deletes cache file, sets
`is_hydrated=0` in state.db, emits `Dehydrated`.

### Event flow

`hydration.subscribe` opens a long-lived NDJSON stream over the
existing UDS. Phase 3 KIO/D-Bus shim consumes; the CLI
`--watch-events` mode also consumes. Backpressure: bounded channel
on the JVM side; if a subscriber falls behind, drop oldest with a
single "events lost" sentinel rather than block writers.

### Crash semantics

- **Mid-hydrate** (co-daemon dies during download): cache file is a
  partial. state.db still `is_hydrated=0`. Next open re-downloads;
  partial gets overwritten.
- **Mid-upload** (`open_write` IPC fired, upload itself fails):
  cache file has new content; engine has the upload request and
  its existing retry queue + transient/permanent-failure handling
  apply. No fresh scan needed.
- **Co-daemon crashes BETWEEN FUSE RELEASE and `open_write` IPC**:
  cache file has new content; the engine doesn't know. On
  co-daemon restart, the binary scans LocalCache and issues
  `open_write` for any file whose mtime is newer than the JVM's
  last-known sync watermark for that path. The JVM exposes a
  watermark query (`hydration.last_synced(path)`) for this. Recovery
  is automatic; user sees one delayed upload per affected file.
- **Co-daemon crash mid-mount with open handles**: JVM state intact;
  JVM detects the IPC disconnect via the existing `IpcServer`
  connection lifecycle and clears the connection's `openSets`
  entries (see Phase-1 Component table). `unidrive mount` CLI
  exits with the supervisor; mount becomes inert (kernel still has
  the FUSE mount but no userspace to handle it). User runs
  `fusermount3 -u <path>` to clear. No `--respawn` by default.

**Load-bearing assumption** (rephrased from an earlier framing):
every FUSE RELEASE that flushed writes to a cache file triggers a
matching `open_write` IPC, OR the next co-daemon restart's
LocalCache scan replays it. The most important Phase 2 smoke test
pins this — open, write, close, kill the co-daemon, restart, assert
the deferred `open_write` arrives within the recovery window.

## Error handling

### Errors this design introduces

| Class | Surface | Handling |
|---|---|---|
| Kernel < 6.9 at startup | Co-daemon | Hard refuse with exit code 78 (`EX_CONFIG`). One-line stderr citing the required kernel + the `FUSE_PASSTHROUGH` requirement. No fallback. |
| libfuse < 3.16 at startup | Co-daemon | Same shape. |
| IPC connection lost (JVM dies) | Co-daemon | Outstanding RPCs fail with `EIO`. New `open(2)` on placeholders fail with `EIO`. Already-hydrated files keep working (passthrough is kernel-direct). Co-daemon retries IPC every 5 s up to 60 s; logs to `~/.local/share/unidrive/unidrive-mount.log`. |
| Co-daemon dies | JVM CLI | `unidrive mount` supervisor (parent process) detects exit, logs to stderr, exits non-zero. Does NOT auto-restart by default — the user explicitly chose to mount. |
| Mount path already mounted | Co-daemon | Refuse at startup. No heuristic `fusermount3 -u`; user must clear first. |
| Dehydrate while file is open | JVM | JVM's connection-scoped `openSets` (maintained by `open_read` / `open_write` / `close_handle`) sees the conflict; returns `HydrationError.Busy`. Caller renders the error. No silent failure. |
| Co-daemon crash with open handles | JVM | JVM detects IPC disconnect via the existing `IpcServer` connection lifecycle; clears the connection's `openSets` entries. Dehydrate becomes possible on those paths immediately — no leaked block. |
| Cache disk full during cold read | JVM | Existing download error path (insufficient-space). Co-daemon translates the IPC error to `ENOSPC` on the FUSE response. |
| Cache file truncated externally (user `rm`'s it) | Co-daemon | Detected on next `open` via `stat`. Treat as cache miss; trigger fresh hydrate. State.db `is_hydrated=1` becomes incorrect transiently — JVM's existing local-modification detector flips it back on the next reconcile. |

### Errors inherited from existing engine

The co-daemon's IPC calls wrap existing engine paths, so the
following error surfaces unchanged:

- Upload failures: existing retry budget, throttle, 429 backoff, JWT refresh.
- Download 404 ("Bucket entry not found"): existing
  `PermanentDownloadFailureException` quarantine path. Co-daemon receives
  `EIO`; the row is quarantined; subsequent opens fail fast until
  the row is unquarantined.
- Quota exhaustion: existing handling, surfaced as `EDQUOT` on the
  FUSE response.
- Auth / token expiry: existing refresh-and-replay. Calls take
  longer; if refresh fails, surfaces as `EACCES`.

### Deliberately not in scope

- Two concurrent `unidrive mount` invocations on the same path
  (relies on kernel-level rejection of the second `fusermount3`).
- Running as root (refused with an explicit error; mount is
  per-user).
- Mountpoint deleted while mount is live (kernel handles).

## Testing

### Phase 1 (Kotlin, `core/app/hydration/`)

| Test | What it pins |
|---|---|
| `HydrationImplTest.open_read_unhydrated_triggers_download` | Cold-read → download → cache populated → state.db flipped → reply path correct. Fake provider. |
| `HydrationImplTest.open_read_hydrated_skips_download` | Warm-read path; no provider call. |
| `HydrationImplTest.dehydrate_with_open_handle_refuses` | `openSet` coordination. Two open handles, dehydrate refuses; close one, refuses; close other, succeeds. |
| `HydrationImplTest.hydration_events_emitted_on_transitions` | Event-flow correctness: `Hydrating → Hydrated`, `Hydrating → Failed`. |
| `HydrationImplTest.close_handle_unknown_id_is_noop` | Robustness against duplicate or stale `close_handle` calls. |
| `HydrationImplTest.ipc_disconnect_clears_open_set` | Connection-scoped cleanup: open two handles on one IPC connection, drop the connection, assert `dehydrate` succeeds on both paths immediately afterward. |
| `HydrationIpcHandlerTest.verbs_round_trip_through_json_line` | IPC wire-format compatibility with the existing `IpcServer`. |
| Smoke (live integration) — `hydrate-then-read` against a real Internxt profile, gated by `UNIDRIVE_INTEGRATION_TESTS=true`. | Fills the third sync smoke in the 5+5+2 target. |

### Phase 2 (Rust, `unidrive-mount-linux/mount/`)

| Test | What it pins |
|---|---|
| `tests/kernel_floor.rs` | Refuses to start on a faked-old kernel-string. |
| `tests/cold_read.rs` | Mount + `cat /mnt/foo.txt` → IPC sees `hydration.open_read` → returns a cache path → cat succeeds. Fake JVM IPC server. |
| `tests/warm_read.rs` | Pre-populate cache; mount; `cat` reads via passthrough without IPC traffic. Assert via IPC-trace. |
| `tests/write_through.rs` | `echo hi > /mnt/foo.txt` → IPC sees `hydration.open_write` with the cache path → close returns 0. **Load-bearing test** pinning the FUSE-RELEASE → `open_write` IPC chain reliability. Engine does NOT sync-scan the FUSE mount; the IPC is the only write-trigger. |
| `tests/dehydrate_while_open.rs` | Open `/mnt/foo.txt`, request dehydrate, expect `EBUSY` / `HydrationError.Busy`. |
| `tests/crash_recovery_replay.rs` | Open, write, close; intercept the `open_write` IPC and drop it; kill co-daemon; restart. Assert the restart-time LocalCache scan finds the file (mtime > JVM watermark) and issues the deferred `open_write`. Pins the second half of the load-bearing assumption. |
| `tests/ipc_reconnect.rs` | Kill JVM mid-operation; assert co-daemon retries, opens return `EIO` while disconnected, recover once JVM is back. |
| Smoke (live integration) — mount a real `~/Internxt`, `ls`, `cat` a couple of files, `vim ; :w` one, `umount`. ~5 smokes mirroring the existing provider smoke shape. |

### Phase 3 (KDE integration)

| Test | What it pins |
|---|---|
| Dolphin context-menu manual checklist | Right-click → "Hydrate" fires; status icon overlay updates within 1 s; "Free" works on a hydrated file; "Free" on an open file shows a user-visible error. |
| D-Bus contract test | Co-daemon's exposed methods, signal stream. Scriptable from `dbus-send`. |

### CI

- Kotlin tests run in this repo's existing `./gradlew check` gate.
- Rust tests run in the new `unidrive-mount-linux/` repo's CI
  (`cargo test` + a containerised FUSE-enabled runner —
  `--cap-add=SYS_ADMIN`). Per ADR: "CI matrix grows lazily."
- Phase 3 tests stay manual at first; promote to scripted once the
  surface is stable.

## Distribution and packaging

The current repo's `dist/install.sh` learns a new "co-daemon" step:

1. Downloads a release tarball from `unidrive-mount-linux`'s GitHub
   Releases.
2. Verifies a SHA256 checksum baked into `install.sh`.
3. Drops the binary at `~/.local/lib/unidrive/unidrive-mount`.
4. Updates the JVM wrapper at `~/.local/bin/unidrive` to find it.

No system-wide install. Everything stays under `~/.local/` and
`~/.cache/`. Honours XDG and the existing one-user-per-install model.

When the user runs `unidrive mount <path>`, the JVM CLI:

1. Resolves the binary at `~/.local/lib/unidrive/unidrive-mount`.
2. Spawns it with `--mount <path> --ipc <existing-socket>`.
3. Supervises until either the binary exits or the user hits Ctrl+C
   (which sends SIGTERM, the binary unmounts gracefully).

## Deliberate non-goals

- No FUSE-level locking between concurrent writers — inherits the
  backing FS's locking semantics via passthrough.
- No in-memory page cache override or client-side read-ahead beyond
  what the kernel provides.
- No mount-already-exists auto-resolution.
- No `--respawn` of a crashed co-daemon by default.
- No graceful degradation to kernels older than 6.9.
- No platform-tier scope creep — Phase 2 + 3 stay in
  `unidrive-mount-linux/`; AGENTS.md's "platform-surface code
  outside `core/app/`" remains intact.

## Open questions for writing-plans

Settled enough for the design; nailed down in the implementation plan:

- Exact IPC verb message schemas (field names, error codes).
- Which `fuse3` crate version to pin (latest at plan-writing time,
  then locked).
- Whether the Dolphin ServiceMenus shim is bash or a tiny Rust binary.
- Whether to ship Phase 2 with `cargo install`-from-source as a
  fallback to the GitHub Releases tarball.
- Smoke-test infrastructure for `unidrive-mount-linux/` CI —
  containerised vs hosted Linux runner with `--cap-add=SYS_ADMIN`.

## References

- `docs/adr/multi-platform.md` — the ADR that mandates this work
  and frames the platform-tier boundary.
- `docs/dev/research/virtual-file-layer-linux-1.md`,
  `docs/dev/research/virtual-file-layer-linux-2.md` — the two
  independent research briefs the design draws on.
- `AGENTS.md` — abstractions-earn-their-keep bar and the
  platform-surface-outside-`core/app/` rule.
