# Hydration Namespace Verbs — Design

**Status:** Proposed — design doc, not yet implemented.
**Origin:** High-tier BACKLOG entries in `unidrive-mount-linux/BACKLOG.md` (committed in sibling repo's `7209c21`): "FUSE `mkdir` not implemented — returns ENOSYS" and "FUSE `unlink` / `rmdir` not implemented — returns ENOSYS".
**Prerequisite:** `docs/dev/specs/mount-sync-mode-mutex-design.md` MUST land first. Without the mode mutex, every successful `mkdir`/`unlink`/`rmdir` through the mount would be racing the legacy `SyncEngine`'s next `--watch` cycle.
**Touches:**
- `core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt` (no changes — `delete` and `createFolder` already exist on the SPI).
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt` (new public entry points wrapping the existing inline `provider.delete` / `provider.createFolder` calls with state.db updates).
- `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt` (SPI: three new methods).
- `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt` (impl bodies).
- `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt` (three new verb branches + add to `VERBS` set).
- `../unidrive-mount-linux/mount/src/ipc.rs` (three new client methods).
- `../unidrive-mount-linux/mount/src/reconnect.rs` (three new wrapper methods retrying on `IpcError::Io`).
- `../unidrive-mount-linux/mount/src/fuse_fs.rs` (three new FUSE op impls: `mkdir`, `unlink`, `rmdir`).

## 1. Problem

### 1.1 Observed failures

Live evidence (2026-05-24 against `/tmp/onedrive-smoke`):

```
gernot@sg5:/tmp/onedrive-smoke$ mkdir test_gernot
mkdir: Function not implemented

gernot@sg5:/tmp/onedrive-smoke/260410_anlagen$ rm *.pdf
rm: cannot remove '251118_mail_geschwärzt.pdf': Function not implemented
rm: cannot remove '251124_mail_anlage.pdf':   Function not implemented
(... five files, same error each)
```

The Linux kernel returns `ENOSYS` ("Function not implemented") because the `unidrive-mount` co-daemon's `fuse_fs.rs` only implements read + write-back ops: `lookup`, `getattr`, `opendir`, `readdir`, `readdirplus`, `open`, `read`, `write`, `fsync`, `release`. Namespace mutation (`mkdir`, `unlink`, `rmdir`, `create`, `rename`, `setattr`) is entirely absent.

### 1.2 Root cause

Phase 2 of `unidrive-mount-linux` was scoped as "read-mostly mount" — the write-back path was added in `feature/fuse-write-path` (sibling repo's `bf3ba76`), but the namespace ops were never spec'd. Operators trying to use the mount as a working filesystem hit ENOSYS on the first `mkdir`, `rm`, or `rmdir`.

### 1.3 What this design does NOT address

- **`create` for new files (not folders).** Today, `open(O_CREAT)` lands at the FUSE `create` op which the co-daemon doesn't implement either. Filed as a separate follow-up because `create` couples with `open_write` and the dirty-release writeback path in non-trivial ways. The current scope is `mkdir` (new folder) + `unlink` (remove existing file) + `rmdir` (remove existing folder); creating new files through the mount is deferred.
- **`rename` / `setattr` / `truncate` / extended attributes.** Deferred — not on the path to "basic FUSE stuff working" per the session goal.
- **The delta-loop phantom-delete finding** (BACKLOG `976ca75`). Independent.

## 2. Goals & non-goals

**Goal G1:** `mkdir /tmp/onedrive-smoke/foo` on a mounted profile creates the folder on the cloud and inserts the row into `state.db`, returning 0 to the kernel.

**Goal G2:** `unlink /tmp/onedrive-smoke/foo.txt` on a mounted profile deletes the file on the cloud and marks the row deleted in `state.db`, returning 0.

**Goal G3:** `rmdir /tmp/onedrive-smoke/foo` on a mounted profile deletes the folder on the cloud (provider-side `delete` handles non-empty refusal per provider semantics) and removes the row from `state.db`, returning 0 on success or the provider's error code on failure.

**Goal G4:** Subsequent FUSE ops see the namespace change consistently. After `mkdir foo`, `ls` shows `foo`. After `unlink bar.txt`, `ls` does not show `bar.txt`.

**Goal G5:** Symmetry with existing hydration verb shape (`hydration.open_read`, `hydration.open_write`, `hydration.close_handle`, `hydration.list`): each new verb has a clear single-purpose contract and its own error envelope.

**Non-goal NG1:** Resilience against the legacy `SyncEngine`'s `--watch` cycle re-creating deleted files from `~/Onedrive/`. The mode mutex (spec sibling) blocks `sync --watch` from running while the mount holds the lock, eliminating that race by construction. This design assumes the mutex holds.

**Non-goal NG2:** Idempotent retry semantics on the FUSE op side. If `unlink` fails with a transient provider error (e.g. 500), the FUSE caller (`rm`) sees the error and can retry; the JVM-side handler is single-shot per call. Reconnecting-client retry on `IpcError::Io` is preserved for the wire-level case (handled by `ReconnectingIpcClient` in the co-daemon).

**Non-goal NG3:** Atomic multi-path operations (e.g. `rm -rf` is a sequence of N unlinks + 1 rmdir; each is a separate verb call). The kernel issues them sequentially; the mount doesn't batch.

**Non-goal NG4:** Local cache management for deleted files. When the JVM deletes a file remotely, any hydrated cache entry under `~/.cache/unidrive/hydration/<provider>/<path>` becomes stale. Cleanup of the cache file is deferred — it's harmless (just consumes disk) and the existing cache-eviction logic will handle it. File a follow-up if it becomes user-visible.

## 3. Design

### 3.1 Wire contract

Three new verbs, parallel shape to the existing hydration verbs:

**`hydration.mkdir`** — request `{"verb":"hydration.mkdir","path":"/foo"}`, reply `{"ok":true}` on success, `{"ok":false,"error":"<msg>"}` on failure. Server-side: calls `syncEngine.createRemoteFolder("/foo")` which delegates to `provider.createFolder("/foo")` and inserts a row into `state.db` with `is_folder=true, is_hydrated=false`. Errors propagate from the provider (e.g. "Folder already exists at /foo", "Parent /missing not found").

**`hydration.unlink`** — request `{"verb":"hydration.unlink","path":"/foo.txt"}`, reply `{"ok":true}` on success, `{"ok":false,"error":"<msg>"}` on failure. Server-side: calls `syncEngine.deleteRemote("/foo.txt")` which delegates to `provider.delete("/foo.txt")` and marks the `state.db` row as deleted. Errors include "Path not found", "Path is a folder, use rmdir".

**`hydration.rmdir`** — same shape as unlink, but the path must be a folder. Server-side: calls `syncEngine.deleteRemote("/foo")` after validating `state.db` says it's a folder. Provider-side `delete` handles non-empty refusal (OneDrive returns 409 on non-empty deletion; Internxt errors out similarly). Errors include "Path not found", "Path is a file, use unlink", "Folder not empty".

Replies use the same `{"ok":true/false,"error":...}` JSON shape as the existing hydration verbs (per `HydrationIpcHandler.kt:218-219` `reply()` helper). No new fields.

### 3.2 SyncEngine entry points

`SyncEngine` already calls `provider.delete(path)` at `:2304` and `provider.createFolder(path)` at `:2360` from inside its action-execution loop. The fix extracts those calls into reusable public entry points that the hydration SPI can invoke:

```kotlin
/**
 * Create a folder on the remote provider and record it in state.db.
 * Used by the hydration SPI (`HydrationImpl.mkdir`) to back FUSE
 * `mkdir` requests from `unidrive-mount`. The legacy action-execution
 * loop (`SyncEngine.applyActions`) continues to call `provider.createFolder`
 * directly for its own scheduling; this method is a separate code
 * path for on-demand mount operations.
 *
 * Throws ProviderException on cloud-side failure (e.g. parent missing,
 * folder already exists at path). state.db is only updated after the
 * provider call succeeds.
 */
suspend fun createRemoteFolder(path: String): CloudItem {
    val item = provider.createFolder(path)
    stateDb.insertFolder(path = path, remoteId = item.remoteId, mtime = item.modified)
    return item
}

/**
 * Delete a path on the remote provider and update state.db.
 * Handles both files and folders — the provider distinguishes by
 * remoteId/path. Caller (`HydrationImpl.unlink` or `.rmdir`) is
 * responsible for type-checking via `stateDb.getEntry(path)` before
 * calling this if it wants to enforce file-vs-folder semantics
 * (per spec G3, rmdir refuses files and vice versa).
 *
 * Throws ProviderException on cloud-side failure (e.g. not found,
 * folder not empty). state.db is only updated after the provider
 * call succeeds.
 */
suspend fun deleteRemote(path: String) {
    provider.delete(path)
    stateDb.markDeleted(path)
}
```

The inline calls at `SyncEngine.kt:2304` and `:2360` are NOT refactored to use these entry points in this change — the action-execution loop's surrounding code (logging, error envelope, dry-run handling) is more specific than the on-demand path needs. Refactoring would broaden the scope. Two code paths to the same provider call is acceptable; the BACKLOG already tracks the broader engine retirement.

**On `stateDb.insertFolder` / `stateDb.markDeleted`:** these helpers may not exist on `StateDatabase` today; if not, they're trivial wrappers around the existing insert/update SQL. The plan will check and add them if missing. Per CLAUDE.md "good developer improves code they're working in," adding two single-purpose methods adjacent to the existing schema is in scope.

### 3.3 Hydration SPI extension

`core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt` (the interface) gains three methods:

```kotlin
/**
 * Create a folder on the remote backing the mount. Returns Ok on
 * success or Failed with an error envelope. Symmetric in shape with
 * openForWrite — the path is canonical (leading slash, no trailing
 * slash), and after success the path is reachable via the next
 * `list()` call.
 */
suspend fun mkdir(path: String): MkdirResult

/**
 * Delete a file on the remote. Refuses if state.db says the path is
 * a folder (caller should use `rmdir` for those). Returns Ok on
 * success.
 */
suspend fun unlink(path: String): UnlinkResult

/**
 * Delete a folder on the remote. Refuses if state.db says the path is
 * a file (caller should use `unlink`). Provider-side semantics handle
 * non-empty refusal.
 */
suspend fun rmdir(path: String): RmdirResult
```

Result types follow the existing pattern (`OpenResult`, `HydrateResult`, etc.):

```kotlin
sealed class MkdirResult {
    object Ok : MkdirResult()
    data class Failed(val error: HydrationError) : MkdirResult()
}

sealed class UnlinkResult {
    object Ok : UnlinkResult()
    data class Failed(val error: HydrationError) : UnlinkResult()
    // PathIsFolder: caller asked unlink on a folder; co-daemon should
    // map this to EISDIR rather than a generic error.
    object PathIsFolder : UnlinkResult()
}

sealed class RmdirResult {
    object Ok : RmdirResult()
    data class Failed(val error: HydrationError) : RmdirResult()
    // PathIsFile: caller asked rmdir on a file; co-daemon should map
    // this to ENOTDIR.
    object PathIsFile : RmdirResult()
    // NotEmpty: folder contains entries; co-daemon maps to ENOTEMPTY.
    object NotEmpty : RmdirResult()
}
```

The distinct result variants exist so the co-daemon can return the correct kernel `errno` for each case (EISDIR / ENOTDIR / ENOTEMPTY) rather than a generic EIO. Co-daemon side mapping:

| Variant | Kernel errno |
|---|---|
| `Ok` | `0` |
| `Failed(...)` | `EIO` |
| `PathIsFolder` | `EISDIR` (libc::EISDIR) |
| `PathIsFile` | `ENOTDIR` (libc::ENOTDIR) |
| `NotEmpty` | `ENOTEMPTY` (libc::ENOTEMPTY) |

### 3.4 HydrationImpl bodies

```kotlin
override suspend fun mkdir(path: String): MkdirResult =
    runCatching {
        _events.emit(HydrationEvent.Hydrating(path))
        syncEngine.createRemoteFolder(path)
        _events.emit(HydrationEvent.Hydrated(path, bytes = 0L))
        MkdirResult.Ok
    }.getOrElse { e ->
        val err = HydrationError.Generic(e.message ?: "mkdir failed")
        _events.emit(HydrationEvent.Failed(path, err))
        MkdirResult.Failed(err)
    }

override suspend fun unlink(path: String): UnlinkResult {
    val entry = stateDb.getEntry(path)
        ?: return UnlinkResult.Failed(HydrationError.Generic("Unknown path: $path"))
    if (entry.isFolder) return UnlinkResult.PathIsFolder

    return runCatching {
        syncEngine.deleteRemote(path)
        UnlinkResult.Ok
    }.getOrElse { e ->
        UnlinkResult.Failed(HydrationError.Generic(e.message ?: "unlink failed"))
    }
}

override suspend fun rmdir(path: String): RmdirResult {
    val entry = stateDb.getEntry(path)
        ?: return RmdirResult.Failed(HydrationError.Generic("Unknown path: $path"))
    if (!entry.isFolder) return RmdirResult.PathIsFile

    // Provider-side delete handles non-empty refusal; we detect it
    // from the exception message. Different providers wording differs:
    // - OneDrive: "Folder not empty" (HTTP 409 surface)
    // - Internxt: "cannot delete non-empty folder"
    // Both substrings appear in ProviderException.message; match any.
    return runCatching {
        syncEngine.deleteRemote(path)
        RmdirResult.Ok
    }.getOrElse { e ->
        val msg = e.message ?: ""
        if (msg.contains("not empty", ignoreCase = true) ||
            msg.contains("non-empty", ignoreCase = true)
        ) {
            RmdirResult.NotEmpty
        } else {
            RmdirResult.Failed(HydrationError.Generic(msg.ifBlank { "rmdir failed" }))
        }
    }
}
```

The non-empty detection is intentionally string-matchy because the provider SPI doesn't expose a typed "FolderNotEmpty" exception today. Open to refactoring later (file a follow-up if it bites); for the session goal of "basic FUSE stuff working" the substring match is good enough.

### 3.5 HydrationIpcHandler verb dispatch

Three new branches in the `when (verb)` block (`HydrationIpcHandler.kt:160-216` area), plus three entries in the `VERBS` set:

```kotlin
companion object {
    val VERBS = setOf(
        "hydration.open_read",
        "hydration.open_write",
        "hydration.close_handle",
        "hydration.hydrate",
        "hydration.dehydrate",
        "hydration.subscribe",
        "hydration.last_synced",
        "hydration.list",
        "hydration.mkdir",     // NEW
        "hydration.unlink",    // NEW
        "hydration.rmdir",     // NEW
    )
}
```

Verb handlers:

```kotlin
"hydration.mkdir" -> {
    val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
    when (val r = hydration.mkdir(path)) {
        is MkdirResult.Ok -> reply(ok = true)
        is MkdirResult.Failed -> reply(ok = false, error = r.error.message)
    }
}
"hydration.unlink" -> {
    val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
    when (val r = hydration.unlink(path)) {
        is UnlinkResult.Ok -> reply(ok = true)
        UnlinkResult.PathIsFolder -> reply(ok = false, error = "path_is_folder")
        is UnlinkResult.Failed -> reply(ok = false, error = r.error.message)
    }
}
"hydration.rmdir" -> {
    val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
    when (val r = hydration.rmdir(path)) {
        is RmdirResult.Ok -> reply(ok = true)
        RmdirResult.PathIsFile -> reply(ok = false, error = "path_is_file")
        RmdirResult.NotEmpty -> reply(ok = false, error = "not_empty")
        is RmdirResult.Failed -> reply(ok = false, error = r.error.message)
    }
}
```

The error literal strings `"path_is_folder"` / `"path_is_file"` / `"not_empty"` are part of the wire contract; the co-daemon matches on them to choose the kernel errno.

### 3.6 Co-daemon IPC client (`mount/src/ipc.rs`)

Three new methods on `IpcClient`, parallel shape to the existing `open_read`/`open_write`/`hydrate`/etc:

```rust
pub async fn mkdir(&mut self, path: &str) -> Result<(), IpcError> {
    let req = serde_json::json!({"verb":"hydration.mkdir","path":path});
    let reply = self.round_trip(&req).await?;
    if !reply["ok"].as_bool().unwrap_or(false) {
        return Err(server_error(&reply));
    }
    Ok(())
}

pub async fn unlink(&mut self, path: &str) -> Result<(), IpcError> {
    let req = serde_json::json!({"verb":"hydration.unlink","path":path});
    let reply = self.round_trip(&req).await?;
    if !reply["ok"].as_bool().unwrap_or(false) {
        return Err(server_error(&reply));
    }
    Ok(())
}

pub async fn rmdir(&mut self, path: &str) -> Result<(), IpcError> {
    let req = serde_json::json!({"verb":"hydration.rmdir","path":path});
    let reply = self.round_trip(&req).await?;
    if !reply["ok"].as_bool().unwrap_or(false) {
        return Err(server_error(&reply));
    }
    Ok(())
}
```

The existing `server_error` helper (`ipc.rs:200-208`) maps the `"error"` field of the reply to an `IpcError::ServerError` with the message. The wire-contract error strings (`path_is_folder`, `path_is_file`, `not_empty`) propagate as `IpcError::ServerError("path_is_folder")` etc.; the FUSE op handler downstream matches on them to choose the errno.

### 3.7 Reconnect-wrapper extension (`mount/src/reconnect.rs`)

Three new methods on `ReconnectingIpcClient`, parallel shape to the existing `open_read`/`open_write`/`close_handle`:

```rust
pub async fn mkdir(&mut self, path: &str) -> Result<(), IpcError> {
    loop {
        self.ensure_connected().await?;
        let c = self.inner.as_mut().expect("ensure_connected guarantees Some");
        match c.mkdir(path).await {
            Ok(v) => return Ok(v),
            Err(IpcError::Io(_)) => { self.inner = None; }
            Err(e) => return Err(e),
        }
    }
}

// Symmetric for unlink and rmdir.
```

The retry-on-`IpcError::Io` only is intentional: a `ServerError` (including `path_is_folder` / `not_empty`) is a deterministic refusal that won't change on retry. Same retry semantics as the existing wrapper methods.

### 3.8 FUSE op impls (`mount/src/fuse_fs.rs`)

Three new methods on the `Filesystem` impl:

```rust
async fn mkdir(
    &self,
    _req: Request,
    parent_inode: u64,
    name: &OsStr,
    _mode: u32,
    _umask: u32,
) -> Result<ReplyEntry> {
    let parent_path = self.path_for_inode(parent_inode)
        .ok_or_else(|| Errno::from(libc::ENOENT))?;
    let child_path = format!("{}/{}",
        parent_path.trim_end_matches('/'),
        name.to_string_lossy(),
    );
    let mut ipc = self.ipc.lock().await;
    ipc.mkdir(&child_path).await
        .map_err(map_ipc_err_to_errno)?;
    drop(ipc);
    // Allocate an inode for the new folder via the path-map and return
    // its attrs. Symmetric with `lookup`'s allocation path.
    self.populate_after_namespace_change(&child_path, is_folder = true).await
        .map_err(|_| Errno::from(libc::EIO))
}

async fn unlink(
    &self,
    _req: Request,
    parent_inode: u64,
    name: &OsStr,
) -> Result<()> {
    let parent_path = self.path_for_inode(parent_inode)
        .ok_or_else(|| Errno::from(libc::ENOENT))?;
    let child_path = format!("{}/{}",
        parent_path.trim_end_matches('/'),
        name.to_string_lossy(),
    );
    let mut ipc = self.ipc.lock().await;
    ipc.unlink(&child_path).await.map_err(map_ipc_err_to_errno)?;
    drop(ipc);
    self.forget_inode_for_path(&child_path).await;
    Ok(())
}

async fn rmdir(
    &self,
    _req: Request,
    parent_inode: u64,
    name: &OsStr,
) -> Result<()> {
    // Body parallel to `unlink` but calls `ipc.rmdir`.
}
```

The exact path-map / inode-allocation calls (`path_for_inode`, `populate_after_namespace_change`, `forget_inode_for_path`) are sketched names; the implementation plan will reconcile against `path_map.rs` and the existing `readdir`/`lookup` impls.

The error-mapping helper:

```rust
fn map_ipc_err_to_errno(e: IpcError) -> Errno {
    match e {
        IpcError::ServerError(msg) if msg == "path_is_folder" => Errno::from(libc::EISDIR),
        IpcError::ServerError(msg) if msg == "path_is_file" => Errno::from(libc::ENOTDIR),
        IpcError::ServerError(msg) if msg == "not_empty" => Errno::from(libc::ENOTEMPTY),
        IpcError::Io(_) => Errno::from(libc::EIO),
        _ => Errno::from(libc::EIO),
    }
}
```

### 3.9 Testing

Four named tests pin the invariants. Two are JVM-side; two are integration tests on the Rust side.

**T1 (JVM) — `mkdir_creates_folder_on_provider_and_inserts_state_db_row`.**
Mock provider's `createFolder` returns a known `CloudItem`. Call `HydrationImpl.mkdir("/foo")`. Assert provider was called with `/foo`, state.db has a folder row at `/foo`, and the result is `MkdirResult.Ok`.

**T2 (JVM) — `unlink_refuses_folder_path_with_PathIsFolder`.**
Pre-populate state.db with a folder row at `/foo`. Call `HydrationImpl.unlink("/foo")`. Assert result is `UnlinkResult.PathIsFolder` AND `provider.delete` was NOT called. Symmetric test `rmdir_refuses_file_path_with_PathIsFile` for the rmdir case.

**T3 (Rust, integration in `mount/tests/`) — `mkdir_round_trip_returns_zero_on_jvm_ok`.**
Stand up a `FakeJvm` that replies `{"ok":true}` to `hydration.mkdir`. Drive `unidrive-mount` to `mkdir` via the FUSE mount. Assert exit 0.

**T4 (Rust, integration) — `rmdir_returns_enotempty_when_jvm_signals_not_empty`.**
`FakeJvm` replies `{"ok":false,"error":"not_empty"}`. Drive `rmdir`. Assert `Errno::ENOTEMPTY` propagates to the kernel (visible via `rmdir` exit code 39 or stderr "Directory not empty").

### 3.10 Live smoke test (manual, post-merge)

Assumes spec sibling (mode mutex) has landed AND `unidrive -p posteo_onedrive sync --watch` is STOPPED.

1. `mkdir -p /tmp/onedrive-smoke-ns && unidrive -p posteo_onedrive mount /tmp/onedrive-smoke-ns`
2. **mkdir:** `mkdir /tmp/onedrive-smoke-ns/test_$(date +%s)` — must succeed (exit 0). Verify on the cloud (e.g. visit the OneDrive web UI or `unidrive ls /test_<n>` after).
3. **unlink:** create a temp test file via the mount-write path: `echo test > /tmp/onedrive-smoke-ns/throwaway.txt; sleep 5` (wait for the dirty-release upload). Then `rm /tmp/onedrive-smoke-ns/throwaway.txt` — must succeed. Verify the file is gone on the cloud.
4. **rmdir empty:** `rmdir /tmp/onedrive-smoke-ns/test_<n>` (the folder created in step 2) — must succeed.
5. **rmdir non-empty:** `rmdir /tmp/onedrive-smoke-ns/<some-existing-non-empty-folder>` — must fail with `Directory not empty`.
6. **unlink on folder:** `rm /tmp/onedrive-smoke-ns/<some-folder>` — must fail with `Is a directory` (EISDIR).
7. **rmdir on file:** `rmdir /tmp/onedrive-smoke-ns/<some-file>` — must fail with `Not a directory` (ENOTDIR).
8. Cleanup: `fusermount3 -u /tmp/onedrive-smoke-ns && rmdir /tmp/onedrive-smoke-ns`.

## 4. Risks and open questions

**R1 — Cross-repo coordination.** This spec spans `unidrive` (JVM/Kotlin) and `unidrive-mount-linux` (Rust). The plan must sequence the changes: JVM-side verbs ship first (the wire surface), then the Rust co-daemon adds the FUSE op impls that call them. Reverse order would leave the co-daemon calling a verb the JVM doesn't know. Plan ordering captured in §5 acceptance.

**R2 — Non-empty rmdir error detection is string-matchy.** §3.4 detects "folder not empty" by substring match on the provider exception message. Fragile against provider message changes (e.g. localised error text from Microsoft Graph). Mitigation: cite the source of each substring in the code comment, file a follow-up to plumb a typed `FolderNotEmpty` exception through the SPI. For the session goal of "basic FUSE stuff working," substring match is the pragmatic choice.

**R3 — `mkdir` doesn't pre-check parent existence.** The provider call `provider.createFolder("/a/b/c")` will fail if `/a/b` doesn't exist (OneDrive 404 on parent lookup, Internxt similar). The error propagates as `MkdirResult.Failed` and the kernel sees EIO. **This is a difference from POSIX mkdir, which returns ENOENT in that case.** The cost of mapping it correctly is substantial (parse the provider error to detect "parent missing"), and it's a corner case (`mkdir -p` from userspace creates intermediate folders one at a time, each is its own FUSE call against a known parent). Decision: ship the EIO mapping, file a follow-up for refining to ENOENT.

**R4 — Concurrent FUSE ops against the same path.** The co-daemon's `ipc` lock (`self.ipc.lock().await` in §3.8) serializes IPC requests, so two `unlink`s of the same path race on the JVM side: first one wins, second gets "unknown path" → EIO. Acceptable per POSIX (the kernel doesn't guarantee N-thread `rm` consistency); document.

**R5 — Cache file leak after unlink/rmdir.** If a file was hydrated (cache file exists at `~/.cache/unidrive/hydration/<provider>/<path>`), `unlink` removes the cloud copy and the state.db row, but the cache file stays. Same for `rmdir` of a folder containing hydrated files. Disk-space concern, not correctness. Filed as NG4; existing cache-eviction can clean up later.

**R6 — Spec sibling prerequisite.** This design assumes the mode mutex (spec sibling) prevents `sync --watch` from running while the mount holds the lock. If that spec doesn't land first, every `mkdir`/`unlink`/`rmdir` through the mount will be racing the next sync cycle — `unlink` will be undone by the engine re-uploading the file from `~/Onedrive/` 30-60 seconds later. The plan sequences the two specs: mode mutex commits first, namespace verbs after.

## 5. Acceptance

- Spec sibling `mount-sync-mode-mutex-design.md` has landed (or is part of the same plan and lands earlier).
- All four tests in §3.9 pass:
  - `mkdir_creates_folder_on_provider_and_inserts_state_db_row` (JVM)
  - `unlink_refuses_folder_path_with_PathIsFolder` (JVM) + symmetric rmdir-on-file test
  - `mkdir_round_trip_returns_zero_on_jvm_ok` (Rust)
  - `rmdir_returns_enotempty_when_jvm_signals_not_empty` (Rust)
- Existing JVM `:app:hydration:test`, `:app:sync:test` and Rust `cargo test` suites green.
- Manual smoke per §3.10 confirms `mkdir`/`unlink`/`rmdir`/error cases against `posteo_onedrive`.
- BACKLOG entries in `unidrive-mount-linux/BACKLOG.md` ("FUSE `mkdir` not implemented" and "FUSE `unlink` / `rmdir` not implemented") move to `CLOSED.md` in the sibling repo's commit.
