# Sparse-hydration roadmap — Phase 2 implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Each task is **component-sized** (multi-hour) by design — the dispatched agent runs its own bite-sized TDD loop inside the component, write-test → run-fail → implement → run-pass → commit, and lands one or more commits per task.

**Goal:** Ship the Linux FUSE co-daemon (`unidrive-mount`) defined in `docs/dev/specs/sparse-hydration-roadmap-design.md` §Phase 2, in a separate sibling repo (`unidrive-mount-linux/`) that consumes the Phase 1 Hydration SPI over UDS-IPC.

**Architecture:** Single Rust binary (`mount` crate, `bin/unidrive-mount.rs` entry point) using `fuse3` + `tokio`. Hard floors: kernel ≥ 6.9 (`FUSE_PASSTHROUGH` required) and libfuse ≥ 3.16. Talks to the existing JVM `IpcServer` over UDS via the eight Phase-1 verbs (`hydration.{open_read, open_write, close_handle, hydrate, dehydrate, subscribe, last_synced, list}`) documented at `../unidrive/core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt`. Cache lives at `~/.cache/unidrive/hydration/` and is **owned by the JVM**; the co-daemon reads cache paths returned from `open_read`/`open_write` replies.

**Tech Stack:** Rust 2024 edition, `fuse3` crate, `tokio` + `tokio-uds`, `serde_json`. No new dependencies on the JVM side unless a wire-format gap is discovered.

**Where this plan lives:** This repo (`unidrive/docs/dev/plans/`). Phase 1's implementation plan lives at the same location. The implementation work itself happens in the sibling repo `unidrive-mount-linux/` (currently at commit `f8abe41` with scaffold complete).

**Sibling-repo discipline:** AGENTS.md in `unidrive-mount-linux/` forbids cross-repo edits in the same commit. Task 4 (JVM-side wiring) is the exception — it lives in this `unidrive` repo, not the sibling. Tasks 1–3 commit only to `unidrive-mount-linux/`.

---

## Spec coverage map (do not skip)

Cross-references from spec §Phase 2 to plan tasks:

| Spec component (line) | Task |
|---|---|
| `bin/unidrive-mount.rs` (116) | 1 |
| Kernel-floor check (117, 260) | 1 |
| `fuse3` FS impl (118) | 2 (reads), 3 (writes) |
| `FUSE_PASSTHROUGH` (118) | 2 |
| `IpcClient` (119) | 1 |
| `LocalCache` (120) | 2 (read), 3 (crash-recovery scan) |
| 7 integration tests (121) | Each one is in the same task as the code it verifies (TDD) |
| `unidrive mount` CLI (350-356) | 4 |
| Distribution shim (337-348) | 4 |

Spec §Error handling table: errors map to tasks by which path they occur in. Spec §Crash semantics (223-252) is owned by Task 3 (the LocalCache scan-on-startup is the load-bearing recovery).

## Inherited JVM-side limits

Phase 2 inherits four hard limits from the existing JVM `IpcServer`. Any of these silently mis-handled means runtime breakage no test in this plan will catch unless the limit is honoured by construction:

- **Socket path length ≤ 90 chars** (`IpcServer.kt:390` `MAX_SOCKET_PATH_LENGTH`). The JVM falls back to a SHA-1-hashed name + `.meta` sidecar for over-90-char paths. Phase 2 mount binary must reuse the JVM's `defaultSocketPath(profileName)` result rather than re-derive the path (Task 4 enforces this).
- **Inbound request line ≤ 64 KiB** (`IpcServer.kt:391` `MAX_REQUEST_BYTES`). The JVM disconnects the client mid-request on overflow; reply will not arrive. The Rust `IpcClient.round_trip` enforces this client-side before write (Task 1.3 code).
- **Max concurrent IPC clients = 10** (`IpcServer.kt:388` `MAX_CLIENTS`). One `unidrive-mount` instance consumes at most one connection (the verb-call connection) + one for the subscribe stream when Phase 3 lands; that's 2 max. Headroom is fine — but multiple co-mounted profiles share the JVM's IPC pool, document if Phase 3 ever surfaces contention.
- **JSON wire format is one-line NDJSON** (`IpcServer.kt:164-180` framing loop). Newlines inside a request line are treated as record terminators by the JVM. `serde_json`'s default `to_string()` produces no internal newlines — safe — but agents customising the request format must verify.

---

## File structure

In the sibling repo `unidrive-mount-linux/`:

**Created in Task 1 (foundation):**

- `Cargo.toml` — workspace root. Members: `mount`.
- `mount/Cargo.toml` — single-binary crate `unidrive-mount`. Dependencies: `fuse3`, `tokio` (rt + io + sync + macros + net), `serde_json`, `anyhow`, `tracing`, `tracing-subscriber`. Dev-dependencies: `tempfile`, `assert_cmd`.
- `mount/src/main.rs` — minimal entry point, calls `mount::run_main()`.
- `mount/src/lib.rs` — re-exports for tests.
- `mount/src/cli.rs` — `clap`-free arg parsing: `--mount <path> --ipc <socket>` (manual `std::env::args` parsing is sufficient at this size; no new dep).
- `mount/src/kernel_floor.rs` — reads `/proc/sys/kernel/osrelease`, parses to `(major, minor)`, returns `Result<(), KernelFloorError>`; takes an optional `osrelease_override: Option<&str>` for tests.
- `mount/src/ipc.rs` — `IpcClient` over `tokio::net::UnixStream`. Length-prefixed-line read/write. One method per verb. Verb method signatures match the canonical contract verbatim. Includes a small `IpcError` enum.
- `mount/src/lib.rs` — exposes `run_main()`, `KernelFloorError`, `IpcClient`, `IpcError` for tests.
- `mount/tests/kernel_floor.rs` — integration test: faked-old kernel string → refusal with exit 78 and the documented stderr.
- `mount/tests/ipc_client.rs` — integration test using a fake JVM (a Tokio task that accepts on a temp UDS, reads one verb, writes a canned reply). One test per verb (8 verbs → 8 tests) verifying request shape and reply parsing.
- `mount/src/fake_jvm.rs` — `#[cfg(test)]` test helper: a `FakeJvm` struct that binds a temp UDS, accepts connections, dispatches per-verb canned replies, optionally records requests for assertion. **Implemented in Task 1** because Task 2/3 tests reuse it.

**Created in Task 2 (FUSE read-path):**

- `mount/src/fuse_fs.rs` — `Fuse3Filesystem` impl. Holds an `IpcClient` (`Arc<Mutex<…>>` or actor-style channel — agent decides) + a `PathMap` (inode ↔ remote path, populated by `lookup`/`readdir`). Read-side methods: `lookup`, `getattr`, `readdir`, `open`, `read`, `release`. `release` issues `hydration.close_handle`. Hydrated files: `FUSE_PASSTHROUGH` ioctl on open via `fuse3`'s passthrough API (verify the API surface against the crate version pinned in `Cargo.toml`).
- `mount/src/path_map.rs` — inode ↔ path bidirectional map. Inodes are u64 monotonic from a counter starting at `FUSE_ROOT_ID + 1` (the FUSE root inode is 1).
- `mount/src/run.rs` — `run_main()`: parse args → kernel-floor check → root check (refuse `geteuid() == 0`) → mount-already-exists check → connect IPC → mount FUSE → block on FUSE event loop → on shutdown signal, unmount cleanly.
- `mount/tests/cold_read.rs` — full integration test (mount under tempdir, fake JVM serving a single placeholder file, `cat` via shell, assert correct bytes).
- `mount/tests/warm_read.rs` — pre-populate cache file, mount, `cat`, assert no `open_read` IPC during the read (the test fixture records IPC traffic; passthrough means kernel-direct reads after open).
- `mount/tests/getattr_readdir.rs` — fake JVM serves a `hydration.list` reply with three entries (file, folder, hydrated-file); assert `ls -la` shows them with correct size/mtime/type.

**Created in Task 3 (FUSE write-path + crash recovery):**

- `mount/src/fuse_fs.rs` — extended with `write`, `fsync`, write-side `release` (on dirty FD → issue `hydration.open_write` with the cache path). Tracks dirty state per open handle.
- `mount/src/cache_scanner.rs` — `scan_and_replay(ipc: &IpcClient, cache_root: &Path) -> Result<usize>`. Walks `~/.cache/unidrive/hydration/`, for each file calls `hydration.last_synced(remote_path)`, compares cache mtime vs returned watermark. If cache_mtime > watermark, issues `hydration.open_write` with the cache path. Returns count of replayed files.
- `mount/src/run.rs` — extended: scan-and-replay runs once at startup, BEFORE the FUSE mount goes live (so the JVM sees the catch-up uploads before user-space sees the mount).
- `mount/tests/write_through.rs` — `echo hi > /mnt/foo.txt`, assert fake JVM receives `hydration.open_write` with the cache path and close returns 0.
- `mount/tests/dehydrate_while_open.rs` — open a file via the mount, request dehydrate via fake JVM (which returns `busy`), assert the operation surfaces as `EBUSY` to the test.
- `mount/tests/crash_recovery_replay.rs` — pre-populate a cache file with mtime > a faked `last_synced` watermark; start `unidrive-mount`; assert the fake JVM receives the deferred `hydration.open_write` BEFORE the mount accepts FUSE traffic.
- `mount/tests/ipc_reconnect.rs` — kill the fake JVM mid-operation; assert the binary retries (5 s up to 60 s per spec); subsequent `open(2)` calls fail with `EIO`; restart fake JVM; assert recovery.

**Created in Task 4 (JVM-side wiring) — in THIS unidrive repo, NOT the sibling:**

- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt` — new `MountCommand` extending the existing Picocli `Command` pattern (read `SyncCommand.kt` for the established pattern). Subcommand: `unidrive mount <path> [--profile <name>]`. Resolves the co-daemon binary path (`~/.local/lib/unidrive/unidrive-mount`), **resolves the IPC socket path via `IpcServer.defaultSocketPath(profileName)`** (NOT a hardcoded path — `IpcServer.kt:419-441` resolves to `/run/user/$UID/` on Linux with a tempdir fallback, and applies a SHA-1-hashed fallback name plus a `.meta` sidecar when the resolved path would exceed `MAX_SOCKET_PATH_LENGTH = 90` chars; reusing `defaultSocketPath` is the only way to stay in lockstep with that logic). Spawns the binary with `--mount <path> --ipc <socket>` where `<socket>` is the resolved path, supervises until child exits or SIGTERM/SIGINT received. On signal: send SIGTERM to child, wait for clean exit (timeout 10 s), then return.
- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` — register `MountCommand` on the root command (read the existing `Main.kt` for the pattern; the registration is a one-liner).
- `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/MountCommandTest.kt` — unit tests: arg parsing, binary-not-found error, supervisor-handles-child-exit, supervisor-forwards-SIGTERM. Mock the `ProcessBuilder` invocation.
- `dist/install.sh` — modify: add a co-daemon download step (download tarball from GitHub Releases of `unidrive-mount-linux`, verify SHA256, drop binary at `~/.local/lib/unidrive/unidrive-mount`). The SHA256 is baked into install.sh. Empty/stub initially — there's no released binary yet; the install.sh edit can be a placeholder that prints a "co-daemon download skipped, see [link]" message and exits non-fatal. Real download lands in a follow-up commit on this repo once `unidrive-mount-linux` ships a release.

**Modified in Task 4:**

- `BACKLOG.md` (sibling unidrive repo) — move closed entries to `CLOSED.md`. Add one new entry: "Cut the first `unidrive-mount-linux` release tarball + wire `dist/install.sh` to download + SHA256-verify it." (This is the work that lights up the install.sh placeholder.)

---

## Task 1: Foundation — Cargo workspace, kernel-floor, IPC client + fake JVM

**Where:** `unidrive-mount-linux/` (sibling repo).

**BACKLOG entry closed:** "Cargo workspace scaffold + kernel-floor check + kernel-floor unit test" (currently seeded as the only High-tier entry). This task EXTENDS the entry's scope to also include the IPC client + fake JVM fixture, because Tasks 2 and 3 both depend on the IPC client and the fake JVM is needed for any non-trivial test. The seeded BACKLOG entry is INTENTIONALLY broadened by this plan; the dispatched agent rewrites the BACKLOG entry text to match the actual landed scope before moving it to CLOSED.md.

**Files (per the structure above):**
- Create: `Cargo.toml`, `mount/Cargo.toml`, `mount/src/main.rs`, `mount/src/lib.rs`, `mount/src/cli.rs`, `mount/src/kernel_floor.rs`, `mount/src/ipc.rs`, `mount/src/fake_jvm.rs`, `mount/tests/kernel_floor.rs`, `mount/tests/ipc_client.rs`.

### Step 1.1: Workspace + crate scaffold

- [ ] **Write `Cargo.toml`** (workspace root):

```toml
[workspace]
members = ["mount"]
resolver = "2"

[workspace.package]
edition = "2024"
license = "Apache-2.0"
authors = ["Gernot Krost <unidrive@krost.org>"]
```

- [ ] **Write `mount/Cargo.toml`**:

```toml
[package]
name = "unidrive-mount"
version = "0.1.0"
edition.workspace = true
license.workspace = true

[lib]
name = "unidrive_mount"
path = "src/lib.rs"

[[bin]]
name = "unidrive-mount"
path = "src/main.rs"

[dependencies]
fuse3 = { version = "0.8", features = ["tokio-runtime"] }
tokio = { version = "1", features = ["rt-multi-thread", "io-util", "sync", "macros", "net", "signal", "time", "process"] }
serde_json = "1"
anyhow = "1"
tracing = "0.1"
tracing-subscriber = "0.3"
libc = "0.2"

[dev-dependencies]
tempfile = "3"
assert_cmd = "2"
```

Note: `fuse3` crate version pinned to `0.8`. Before committing, the dispatched agent verifies `0.8` is the latest at plan-execution time and bumps to current latest. If the latest major has breaking changes to the passthrough API, the agent files a BACKLOG entry and pins the prior major.

- [ ] **Write `mount/src/main.rs`**:

```rust
use std::process::ExitCode;

fn main() -> ExitCode {
    unidrive_mount::run_main()
}
```

- [ ] **Write `mount/src/lib.rs`**:

```rust
pub mod cli;
pub mod ipc;
pub mod kernel_floor;

#[cfg(test)]
pub mod fake_jvm;

use std::process::ExitCode;

pub fn run_main() -> ExitCode {
    // Real impl lands in Task 2/3; for Task 1 we only need kernel_floor wired.
    match kernel_floor::check_kernel_floor(None) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("{e}");
            ExitCode::from(78) // EX_CONFIG
        }
    }
}
```

- [ ] **Run `cargo check` from `unidrive-mount-linux/`.** Expected: compile error because `kernel_floor`, `cli`, `ipc`, `fake_jvm` modules don't exist yet. Add empty `pub fn _stub() {}` to each so the workspace compiles cleanly. Iterate until `cargo check` is green.

- [ ] **Commit:**

```bash
git add Cargo.toml mount/Cargo.toml mount/src/
git commit -m "feat: cargo workspace scaffold for mount crate"
```

### Step 1.2: Kernel-floor check (TDD)

- [ ] **Write `mount/tests/kernel_floor.rs`** (the failing test):

```rust
use unidrive_mount::kernel_floor::{check_kernel_floor, KernelFloorError};

#[test]
fn check_kernel_floor_refuses_below_6_9() {
    let err = check_kernel_floor(Some("6.8.0-generic")).unwrap_err();
    assert!(matches!(err, KernelFloorError::TooOld { .. }));
    let msg = format!("{err}");
    assert!(msg.contains("6.9"), "stderr must cite the required kernel: {msg}");
    assert!(msg.contains("FUSE_PASSTHROUGH"), "stderr must cite the missing feature: {msg}");
}

#[test]
fn check_kernel_floor_accepts_6_9() {
    check_kernel_floor(Some("6.9.0-generic")).unwrap();
}

#[test]
fn check_kernel_floor_accepts_7_0() {
    check_kernel_floor(Some("7.0.9-070009-generic")).unwrap();
}

#[test]
fn check_kernel_floor_rejects_unparseable() {
    let err = check_kernel_floor(Some("not-a-version")).unwrap_err();
    assert!(matches!(err, KernelFloorError::Unparseable { .. }));
}
```

- [ ] **Run `cargo test --test kernel_floor`.** Expected: FAIL because `check_kernel_floor` returns `()` from stub.

- [ ] **Implement `mount/src/kernel_floor.rs`**:

```rust
use std::fs;

#[derive(Debug, thiserror::Error)]
pub enum KernelFloorError {
    #[error("kernel {found} too old; FUSE_PASSTHROUGH requires Linux 6.9 or newer")]
    TooOld { found: String },
    #[error("could not parse kernel release {raw:?}; FUSE_PASSTHROUGH requires Linux 6.9 or newer")]
    Unparseable { raw: String },
    #[error("could not read /proc/sys/kernel/osrelease: {0}")]
    IoError(#[from] std::io::Error),
}

/// Returns Ok if running on Linux ≥ 6.9, else error explaining what's missing.
/// `osrelease_override` is for tests; production calls pass None and read from /proc.
pub fn check_kernel_floor(osrelease_override: Option<&str>) -> Result<(), KernelFloorError> {
    let raw = match osrelease_override {
        Some(s) => s.to_string(),
        None => fs::read_to_string("/proc/sys/kernel/osrelease")?,
    };
    let trimmed = raw.trim();
    let (major, minor) = parse_major_minor(trimmed)
        .ok_or_else(|| KernelFloorError::Unparseable { raw: trimmed.to_string() })?;
    if (major, minor) < (6, 9) {
        return Err(KernelFloorError::TooOld { found: trimmed.to_string() });
    }
    Ok(())
}

fn parse_major_minor(raw: &str) -> Option<(u32, u32)> {
    // Examples: "6.9.0-generic", "7.0.9-070009-generic", "5.15.0".
    let prefix = raw.split('-').next()?;
    let mut parts = prefix.split('.');
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    Some((major, minor))
}
```

Add `thiserror = "1"` to `mount/Cargo.toml` dependencies.

- [ ] **Run `cargo test --test kernel_floor`.** Expected: PASS (4/4).

- [ ] **Add CLI-level exit-code test** at `mount/tests/exit_code.rs`:

```rust
use assert_cmd::Command;

#[test]
fn refuses_to_start_below_kernel_6_9_when_real_kernel_too_old() {
    // We can't fake the real kernel here; we test the binary runs at all.
    // The actual kernel-floor logic is covered by the unit test above.
    // This test verifies the binary parses, links, and exits cleanly on the host kernel.
    let host_kernel_ok = std::fs::read_to_string("/proc/sys/kernel/osrelease")
        .ok()
        .and_then(|s| {
            let p = s.split('-').next()?;
            let mut it = p.split('.');
            let maj: u32 = it.next()?.parse().ok()?;
            let min: u32 = it.next()?.parse().ok()?;
            Some((maj, min) >= (6, 9))
        })
        .unwrap_or(false);
    let mut cmd = Command::cargo_bin("unidrive-mount").unwrap();
    let assert = cmd.assert();
    if host_kernel_ok {
        assert.success();
    } else {
        assert.failure().code(78);
    }
}
```

- [ ] **Run `cargo test`.** Expected: ALL PASS.

- [ ] **Commit:**

```bash
git add mount/src/kernel_floor.rs mount/tests/kernel_floor.rs mount/tests/exit_code.rs mount/Cargo.toml
git commit -m "feat: kernel-floor check refuses Linux < 6.9 with exit 78"
```

### Step 1.3: IPC client + fake JVM fixture (TDD)

- [ ] **Write `mount/src/fake_jvm.rs`** (test-only helper, but in `src/` so both unit and integration tests can use it; gate with `#[cfg(any(test, feature = "test-helpers"))]` or move to a separate `test-helpers` crate if needed. Default: `#[cfg(test)]` in lib.rs + duplicate-import for integration tests via a `pub mod` re-export. Simplest: put it under `mount/src/fake_jvm.rs` with `#[cfg(any(test, debug_assertions))]` and accept the slight test-helper-in-binary cost. Final pattern is the agent's call; document the decision in the commit body.)

The `FakeJvm` API the tests need:

```rust
pub struct FakeJvm {
    pub socket_path: std::path::PathBuf,
    handle: tokio::task::JoinHandle<()>,
    recorded: std::sync::Arc<tokio::sync::Mutex<Vec<String>>>,
}

impl FakeJvm {
    /// Bind a UDS at a temp path. `replies` is a map of verb-name → static reply line.
    pub async fn spawn(replies: std::collections::HashMap<String, String>) -> Self { /* … */ }

    /// Return the requests received so far (verb-line strings).
    pub async fn recorded_requests(&self) -> Vec<String> { /* … */ }

    pub async fn shutdown(self) { /* … */ }
}
```

Wire format on the UDS: one JSON request per line, one JSON reply per line (NDJSON). Matches the canonical contract in `../unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`. The agent reads that file to confirm the exact framing (CR/LF behaviour, max line length, etc.) before writing the fake.

- [ ] **Write `mount/tests/ipc_client.rs`** — one test per verb. Example for `open_read`:

```rust
use std::collections::HashMap;
use unidrive_mount::fake_jvm::FakeJvm;
use unidrive_mount::ipc::IpcClient;

#[tokio::test]
async fn open_read_round_trips() {
    let mut replies = HashMap::new();
    replies.insert(
        "hydration.open_read".into(),
        r#"{"ok":true,"cache_path":"/tmp/cache/foo.txt"}"#.into(),
    );
    let jvm = FakeJvm::spawn(replies).await;
    let mut client = IpcClient::connect(&jvm.socket_path).await.unwrap();

    let reply = client.open_read("handle-1", "/foo.txt").await.unwrap();
    assert_eq!(reply.cache_path, std::path::Path::new("/tmp/cache/foo.txt"));

    let recorded = jvm.recorded_requests().await;
    assert_eq!(recorded.len(), 1);
    assert!(recorded[0].contains(r#""verb":"hydration.open_read""#));
    assert!(recorded[0].contains(r#""handle_id":"handle-1""#));
    assert!(recorded[0].contains(r#""path":"/foo.txt""#));

    jvm.shutdown().await;
}
```

Replicate the test shape for the other seven verbs: `open_write`, `close_handle`, `hydrate`, `dehydrate`, `subscribe`, `last_synced`, `list`. Reply shapes for each verb are pinned in `../unidrive/core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt` lines 14-25 (the doc-comment) and the verb-handling `when` block.

Specifically (verified against the current Kotlin source `HydrationIpcHandler.kt` lines 134-95 for verb dispatch and lines 208+ for `pluck` — both moved post-Phase-1-gap-#9 landing; agents MUST re-verify line numbers before quoting them in commit messages):
- `open_read` request `{"verb":"hydration.open_read","handle_id":"…","path":"/foo"}` → reply `{"ok":true,"cache_path":"…"}` or `{"ok":false,"error":"…"}`.
- `open_write` request `{"verb":"hydration.open_write","handle_id":"…","path":"/foo","cache_path":"…"}` — three required fields, NOT two. Reply same shape as `open_read`. The `cache_path` field tells the JVM where on disk to find the modified bytes; semantically post-tense (called AT FUSE RELEASE after writes have flushed to the cache file). The Rust client method takes `(handle_id, path, cache_path)`.
- `close_handle` request `{"verb":"hydration.close_handle","handle_id":"…"}` → reply `{"ok":true}` always (per the Kotlin Hydration interface, `closeHandle` returns Unit; the handler always replies ok).
- `hydrate` / `dehydrate` request `{"verb":"hydration.{verb}","path":"…"}` → reply `{"ok":true/false, error?:…}`.
- `dehydrate` returning `{"ok":false,"error":"busy"}` MUST surface to the caller as a distinct `IpcError::Busy` variant (so Task 3's dehydrate-while-open test can assert `EBUSY` propagation).
- `subscribe` opens a one-way stream — its reply is `{"ok":true}` AND the connection then stays open for server-pushed events. The Task 1 test covers only the handshake reply; Phase 3 tests cover the stream (out of scope for Phase 2). The reconnect wrapper that lands in Task 3 must NOT auto-reconnect a subscribe connection (the server-pushed event stream is stateful — a reconnect would silently re-issue subscribe and the caller would miss whatever events fired during the disconnect window). Document this in the wrapper: subscribe gets a dedicated long-lived connection NOT wrapped in retry logic; verb calls get the retried connection.
- `last_synced` Unknown reply: `{"ok":false,"error":"<reason>"}` where `<reason>` is a dynamic string set by the JVM-side `LastSyncedResult.Unknown(reason: String)` data class. Current Kotlin emits `"unknown_path"` (no row in state.db) or `"no_mtime"` (row exists but `localMtime` is null), but the contract guarantees only "any non-empty reason string." The test asserts: (a) the reply maps to a dedicated client-side variant (rename `IpcError::Unknown` to `IpcError::Unknown { reason: String }` if you want to expose the reason; the client surface decision is the agent's). It MUST NOT assert on the exact reason literal — that would couple the Rust test to JVM-side wording.
- `list` reply shape: `{"ok":true,"entries":[{"path":"…","size":N,"mtime_ms":N,"hydrated":bool,"folder":bool}, …]}`. Test asserts deserialisation into `Vec<ListEntry>` round-trips. The reply size scales linearly with the number of direct children under `prefix` — at 195k-file scale the worst case is the cloud root with thousands of top-level entries, which is why the 4 MiB inbound cap in `round_trip` exists.

- [ ] **Run `cargo test --test ipc_client`.** Expected: FAIL (`IpcClient` doesn't exist).

- [ ] **Implement `mount/src/ipc.rs`**:

```rust
use std::path::{Path, PathBuf};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;

#[derive(Debug, thiserror::Error)]
pub enum IpcError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("malformed reply: {0}")]
    Malformed(String),
    #[error("server reported error: {0}")]
    ServerError(String),
    #[error("busy")]
    Busy,
    #[error("unknown")]
    Unknown,
}

pub struct IpcClient {
    reader: BufReader<tokio::net::unix::OwnedReadHalf>,
    writer: tokio::net::unix::OwnedWriteHalf,
}

pub struct OpenReadReply {
    pub cache_path: PathBuf,
}

pub struct ListEntry {
    pub path: String,
    pub size: u64,
    pub mtime_ms: i64,
    pub hydrated: bool,
    pub folder: bool,
}

impl IpcClient {
    pub async fn connect(socket: &Path) -> Result<Self, IpcError> {
        let stream = UnixStream::connect(socket).await?;
        let (r, w) = stream.into_split();
        Ok(Self { reader: BufReader::new(r), writer: w })
    }

    pub async fn open_read(&mut self, handle_id: &str, path: &str) -> Result<OpenReadReply, IpcError> {
        let req = serde_json::json!({
            "verb": "hydration.open_read",
            "handle_id": handle_id,
            "path": path,
        });
        let reply: serde_json::Value = self.round_trip(&req).await?;
        if !reply["ok"].as_bool().unwrap_or(false) {
            return Err(server_error(&reply));
        }
        let cache = reply["cache_path"].as_str()
            .ok_or_else(|| IpcError::Malformed(reply.to_string()))?;
        Ok(OpenReadReply { cache_path: PathBuf::from(cache) })
    }

    // ... one method per verb. Each follows the same pattern: serde_json::json!
    //     build, round_trip, dispatch on reply["ok"], read fields with as_str/as_i64/etc.

    async fn round_trip(&mut self, req: &serde_json::Value) -> Result<serde_json::Value, IpcError> {
        let line = req.to_string();
        // Enforce the same MAX_REQUEST_BYTES = 64 * 1024 cap the JVM IpcServer
        // imposes on its inbound buffer (IpcServer.kt:391). Going past it would
        // cause the JVM to disconnect mid-request rather than reply.
        if line.len() + 1 > 64 * 1024 {
            return Err(IpcError::Malformed(format!("request {} bytes exceeds 64 KiB JVM cap", line.len())));
        }
        self.writer.write_all(line.as_bytes()).await?;
        self.writer.write_all(b"\n").await?;
        self.writer.flush().await?;
        // Bound the inbound line too: the JVM's `list` reply can be sizeable for
        // populous prefixes but the spec doesn't permit unbounded subscribers
        // either. Cap reads at 4 MiB and surface anything larger as Malformed.
        let mut line = String::with_capacity(1024);
        let n = (&mut self.reader).take(4 * 1024 * 1024).read_line(&mut line).await?;
        if n == 0 { return Err(IpcError::Io(std::io::Error::from(std::io::ErrorKind::UnexpectedEof))); }
        let trimmed = line.trim_end_matches('\n');
        serde_json::from_str(trimmed).map_err(|e| IpcError::Malformed(format!("{e}: {trimmed}")))
    }
}

fn server_error(reply: &serde_json::Value) -> IpcError {
    let err = reply["error"].as_str().unwrap_or("unknown");
    match err {
        "busy" => IpcError::Busy,
        // last_synced returns dynamic reasons ("unknown_path", "no_mtime", …); the
        // canonical shape on this verb is "ok=false with any non-empty reason".
        // Callers checking for Unknown should match IpcError::Unknown { .. },
        // not on a specific message — the JVM-side gap-#6 PR explicitly chose a
        // dynamic-string contract (Hydration.kt: LastSyncedResult.Unknown(reason)).
        _ => IpcError::ServerError(err.to_string()),
    }
}
```

The dispatched agent fills in the remaining verb methods using the same `serde_json::json!` build + `round_trip` + ok-dispatch shape. Phase 1's Kotlin handler uses a hand-rolled `pluck()` because the JVM side wanted zero new dependencies; Phase 2's Rust client unconditionally depends on `serde_json` (it's required for the `list` reply's nested array regardless), so using `Value` throughout is cheaper than a second hand-rolled parser. Two JVM-side limits are inherited and enforced here at the client: `MAX_REQUEST_BYTES = 64 KiB` (`IpcServer.kt:391`) caps outbound; an internal 4 MiB cap on inbound prevents an unbounded `list` reply from causing OOM. Subscribe is the streaming exception — see Step 1.3's verb-list note below for how `subscribe` deviates from the round-trip shape (the subscribe verb's reply IS a round-trip but the connection stays open for server-pushed events afterward; the client must NOT reuse `round_trip` for the post-subscribe event stream).

- [ ] **Implement `mount/src/fake_jvm.rs`** to make the integration tests pass. Read `../unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` to confirm the line-framing. The fake JVM listens on a temp UDS, accepts one connection at a time, reads NDJSON lines, looks up the verb in the replies map, writes the reply line + `\n`, records each request.

- [ ] **Run `cargo test`.** Expected: ALL PASS (kernel_floor tests + 8 ipc_client tests).

- [ ] **Commit:**

```bash
git add mount/src/ipc.rs mount/src/fake_jvm.rs mount/tests/ipc_client.rs
git commit -m "feat: IPC client + fake JVM fixture for all eight hydration verbs"
```

### Step 1.4: Close the seeded BACKLOG entry

- [ ] **Read `BACKLOG.md` in `unidrive-mount-linux/`** to confirm the seeded entry. Rewrite its scope to match what landed (kernel-floor + IPC client + fake JVM, not just kernel-floor). Move it to `CLOSED.md` with the format from `CLOSED.md`'s template (it's currently empty; use a one-line bullet).

- [ ] **Append new High-tier BACKLOG entries** for Tasks 2, 3, 4 — one entry per task, named to match this plan. Scope text references this plan document.

- [ ] **Commit the BACKLOG move + seeding** as a separate commit:

```bash
git add BACKLOG.md CLOSED.md
git commit -m "docs(backlog): close foundation entry; seed entries for FUSE read/write/jvm-wiring"
```

### Step 1.5: Final verification

- [ ] **Run `cargo test`.** Expected: ALL PASS.
- [ ] **Run `cargo build --release`.** Expected: a `target/release/unidrive-mount` binary that exits 78 on this kernel if < 6.9, or exits 0 otherwise.
- [ ] **Manual smoke:** `./target/release/unidrive-mount; echo $?` — confirms exit code matches host kernel.

**Exit criteria for Task 1:** `cargo test` green, binary builds, kernel-floor refusal behaves as specified, IPC client exercises all 8 verbs against the fake JVM with round-trip assertions, BACKLOG → CLOSED move landed. Task 2 can start.

---

## Task 2: FUSE read-path

**Where:** `unidrive-mount-linux/` (sibling repo).

**BACKLOG entry:** "FUSE read-path: lookup, getattr, readdir, open, read, release with FUSE_PASSTHROUGH on hydrated files" (seeded in Task 1's Step 1.4).

**Files:**
- Create: `mount/src/fuse_fs.rs`, `mount/src/path_map.rs`, `mount/src/run.rs`, `mount/tests/cold_read.rs`, `mount/tests/warm_read.rs`, `mount/tests/getattr_readdir.rs`.
- Modify: `mount/src/lib.rs` (export new modules, wire run_main to call into `run.rs`).
- Modify: `mount/src/cli.rs` (real arg parsing).

**Sub-skill for the dispatched agent:** Same TDD discipline as Task 1. Write test → run-fail → implement → run-pass → commit. Each FUSE method (lookup, getattr, readdir, open, read, release) gets at least one test and one commit. Recommended order:

1. **CLI arg parsing** (`--mount <path> --ipc <socket>`) + tests. One commit.
2. **`path_map.rs`** with bidirectional inode↔path tests. One commit.
3. **`run.rs`** scaffold (parse args → kernel-floor → root check → mount-already-exists check → connect IPC → enter FUSE event loop). Test: invocation against a fake JVM produces a mount on a tempdir, then unmounts cleanly. One commit.
4. **`getattr` + `readdir` via `hydration.list`** + `getattr_readdir.rs` integration test. One commit.
5. **`open` + `read` cold path** (calls `hydration.open_read`, opens cache file, returns FD) + `cold_read.rs`. One commit.
6. **`FUSE_PASSTHROUGH` on hydrated files** + `warm_read.rs`. The test fixture asserts no IPC after the open returns. One commit.
7. **`release` issues `hydration.close_handle`** (existing tests verify this happens; new test if needed). One commit.

**Sibling-repo state check at task start:** Per AGENTS.md "Check sibling-repo state before composite-build conclusions" — before the agent claims anything works, it runs `git -C ../unidrive fetch origin && git -C ../unidrive log main..origin/main` to confirm no contract changes have landed on the JVM side since this plan was written.

**FUSE_PASSTHROUGH API note:** The `fuse3` crate's passthrough API exact shape MUST be verified against the version pinned in `mount/Cargo.toml`. If the crate doesn't yet expose `FUSE_PASSTHROUGH` (it's a relatively new kernel feature), the agent STOPS, files a BACKLOG entry "fuse3 crate version pin needs upgrade for FUSE_PASSTHROUGH support" with the version requirement, and does NOT proceed with the passthrough sub-step. Cold-read and warm-read tests both still pass (warm-read just incurs an extra IPC round-trip per open until passthrough lands).

**Exit criteria for Task 2:** `cargo test` green with all 3 new integration tests + the 8 IPC tests + kernel-floor; manual mount of a fake-JVM-backed test produces a working `cat /mnt/foo.txt`; BACKLOG → CLOSED move landed; Task 3 can start.

---

## Task 3: FUSE write-path + crash-recovery

**Where:** `unidrive-mount-linux/` (sibling repo).

**BACKLOG entry:** "FUSE write-path: write, fsync, dirty-release-triggers-upload; LocalCache crash-recovery scanner at startup" (seeded in Task 1's Step 1.4).

**Files:**
- Modify: `mount/src/fuse_fs.rs` (add write, fsync, write-side release).
- Create: `mount/src/cache_scanner.rs`.
- Modify: `mount/src/run.rs` (wire scan-and-replay at startup, BEFORE the mount goes live).
- Create: `mount/tests/write_through.rs`, `mount/tests/dehydrate_while_open.rs`, `mount/tests/crash_recovery_replay.rs`, `mount/tests/ipc_reconnect.rs`.

**Recommended sub-order for the dispatched agent:**

1. **Write/fsync/dirty-release** + `write_through.rs`. Dirty-tracking state in `fuse_fs`: per-handle `dirty: bool` flag flipped by any `write` call, checked at `release` time. On dirty-release: issue `hydration.open_write` with the cache path then `hydration.close_handle`. One commit.
2. **Dehydrate-while-open via fake JVM busy reply** + `dehydrate_while_open.rs`. The mount itself doesn't issue dehydrate; this test exercises the IPC client's busy-error mapping AND confirms that an `EBUSY` surfaces correctly when a downstream component (e.g. a future CLI) requests dehydrate on an open file. One commit.
3. **`cache_scanner.rs`** with unit tests (does the right thing for: empty cache → 0 replays; cache file newer than watermark → 1 replay; cache file older than watermark → 0 replays; cache file with unknown_path watermark → log warn, skip). One commit.
4. **Wire `cache_scanner` into `run.rs`** to run BEFORE FUSE mount goes live + `crash_recovery_replay.rs`. The integration test pre-populates a cache file with mtime > fake-JVM-`last_synced` reply, starts the binary, asserts the fake JVM received the deferred `hydration.open_write` BEFORE any FUSE traffic arrived. One commit.
5. **IPC reconnect resilience** (5 s retry up to 60 s per spec) + `ipc_reconnect.rs`. Implementation: wrap the `IpcClient` in a reconnecting wrapper or move retry logic into the verb methods themselves. Agent's call. One commit.

**Spec line 247-252 load-bearing assumption:** "every FUSE RELEASE that flushed writes to a cache file triggers a matching `open_write` IPC, OR the next co-daemon restart's LocalCache scan replays it." The `write_through.rs` test covers the first half; the `crash_recovery_replay.rs` test covers the second half. Both are non-negotiable. The agent does NOT mark Task 3 done until both are green.

**Exit criteria for Task 3:** `cargo test` green with all 7 integration tests; both load-bearing tests (write_through + crash_recovery_replay) explicitly verified; BACKLOG → CLOSED move landed; binary builds and self-mounts cleanly via `cargo run -- --mount /tmp/test-mount --ipc /tmp/test-jvm.sock` (with a manual `FakeJvm` script if necessary, or the agent supplies a `bin/integration-runner` test helper); Task 4 can start.

---

## Task 4: JVM-side wiring — `unidrive mount` CLI + install.sh placeholder

**Where:** THIS unidrive repo (`/home/gernot/dev/git/unidrive/`), NOT the sibling.

**BACKLOG entry:** "Phase 2 JVM wiring: `unidrive mount` CLI subcommand + dist/install.sh co-daemon placeholder" (seeded in Task 1's Step 1.4 on the sibling, but this entry lives on THIS repo's BACKLOG.md — duplicate the entry to this repo's BACKLOG.md as part of Task 4's setup).

**Files (this repo, NOT the sibling):**
- Create: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt`.
- Modify: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` (register MountCommand).
- Create: `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/MountCommandTest.kt`.
- Modify: `dist/install.sh` (add co-daemon placeholder section).
- Modify: `BACKLOG.md` (move closed entry to CLOSED.md; add new entry "Cut first unidrive-mount-linux release tarball + wire dist/install.sh to download/verify it").

**Sub-skill for the dispatched agent:** TDD. Read `SyncCommand.kt` first (it's the canonical example of a Picocli subcommand with a profile parameter). Read `Main.kt` to see the subcommand registration pattern.

**Recommended sub-order:**

1. **Read SyncCommand.kt + Main.kt** to understand the Picocli pattern. No commit; this is the "read three nearby source files" rule from AGENTS.md.
2. **Add the BACKLOG entry on THIS repo** (one commit, docs-only): "Phase 2 JVM wiring: `unidrive mount` CLI subcommand + dist/install.sh co-daemon placeholder" under High tier.
3. **Write `MountCommandTest.kt`** — failing tests for: arg parsing (`mount /tmp/foo`), profile parameter (`mount --profile X /tmp/foo`), binary-not-found error path, supervisor handles child exit cleanly, supervisor forwards SIGTERM. One commit (the failing tests).
4. **Implement `MountCommand.kt`** + register in `Main.kt`. Tests pass. One commit. Body explains the spawn/supervise loop: resolves binary at `~/.local/lib/unidrive/unidrive-mount`, resolves UDS socket path via `IpcServer.defaultSocketPath(profileName)` (this honours the Linux `/run/user/$UID/` resolution, the tempdir fallback, the `MAX_SOCKET_PATH_LENGTH = 90` hashed-name fallback, AND writes the `.meta` sidecar — none of which Phase 2 should re-derive), spawns the binary with `--mount <path> --ipc <socket>`, waits on its exit code, propagates back. SIGTERM/SIGINT in the supervisor sends SIGTERM to child and waits up to 10s.
5. **Modify `dist/install.sh`** — add a "co-daemon download" stub section that prints a message ("co-daemon download is a follow-up; see [URL of the new BACKLOG entry]") and exits non-fatal. One commit.
6. **BACKLOG → CLOSED move** for "Phase 2 JVM wiring: …" entry, simultaneously add new entry "Cut the first `unidrive-mount-linux` release tarball + wire `dist/install.sh` to download + SHA256-verify it" (High tier — this is the follow-up that lights up the install.sh placeholder once the sibling repo has a shippable artefact). Same commit as the dist/install.sh edit OR a separate one; agent's call. AGENTS.md says one BACKLOG item per commit — so SEPARATE commit for the BACKLOG move.

**Verification:** `./gradlew :app:cli:check` green. Manual: `unidrive mount /tmp/test-mount` invokes a child process at the expected path (will fail because the binary isn't installed yet — that's fine, the test asserts the spawn was attempted with correct args, not that the child succeeds).

**Exit criteria for Task 4:** `./gradlew :app:cli:check` green; new MountCommand + tests landed; `dist/install.sh` modified to handle the (currently-absent) co-daemon binary gracefully; two BACKLOG entries moved (one closed, one new). Phase 2 is now feature-complete from the JVM side modulo the actual release-tarball cut, which is the new follow-up entry.

---

## What Phase 2 is NOT

These come in later phases or follow-ups, not in this plan:

- **Phase 3** (Dolphin context-menu, D-Bus signals, icon overlays). Separate plan, separate task list. The `mount` binary's D-Bus service interface is Phase 3 work.
- **Release tarball cut + actual install.sh download wiring.** Filed as a follow-up entry in Task 4. Needs the first shippable `unidrive-mount` binary, a GitHub release, and a SHA256.
- **`--watch-events` CLI mode on the JVM** to consume `hydration.subscribe`. The IPC verb is wired (gap #9 landed) but no user-facing consumer exists. Separate Low-tier entry on the unidrive sibling BACKLOG.
- **Performance optimisation beyond what `FUSE_PASSTHROUGH` gives for free.** No read-ahead, no client-side caching beyond what the kernel page cache provides.
- **Two-mount-on-the-same-path coordination.** Spec rules it out; kernel rejects it.
- **macOS / Windows.** Out of scope; AGENTS.md hard rule.

## Self-review notes (run before handing off)

1. **Spec coverage:** Every Phase 2 component in the spec table (`bin/unidrive-mount.rs`, kernel-floor, fuse3 FS impl, IpcClient, LocalCache, 7 tests) maps to a task above. Phase 2 distribution (spec §337-348) maps to Task 4. Verified.
2. **Placeholder scan:** Searched for "TBD", "TODO", "implement later" — none in this plan. The one place that *could* be — the `fuse3` crate version pin — is explicitly handled with a verify-and-bump instruction in Task 1.1 and a STOP-and-file-entry escape valve for FUSE_PASSTHROUGH support in Task 2.
3. **Type consistency:** The IPC client method signatures, the wire-format reply shapes (`OpenReadReply.cache_path`, `ListEntry.{path,size,mtime_ms,hydrated,folder}`), and the canonical contract in `HydrationIpcHandler.kt` all use the same field names. The plan does NOT reinvent any wire shape — every shape references the source-of-truth file path.
4. **Cross-repo discipline:** Tasks 1-3 are sibling-only. Task 4 is unidrive-only. No task crosses the line. Per the sibling AGENTS.md "Don't reach into the sibling unidrive repo" rule, this separation is enforced by the plan structure itself.

## Execution handoff

This plan supports BOTH execution modes. Each task is component-sized (multi-hour), with bite-sized steps inside. The dispatched agent in subagent-driven mode runs the steps as its own TDD loop and lands multiple commits per task.

**Subagent-Driven (recommended):** One agent dispatch per task (4 total). Main session reviews each task's exit criteria before dispatching the next. Mirrors the discipline that just shipped the 3 Phase 1 SPI gaps.

**Inline Execution:** Open this plan in the current session and execute step-by-step. Suitable if the operator wants tight per-step control. Slower wall-clock.
