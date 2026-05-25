# setattr / truncate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the FUSE `setattr` op in the co-daemon so chmod/chown/utimes (best-effort) and truncate/ftruncate + `> file` overwrite work on the mount, with **no download** for truncate-to-0.

**Architecture:** A new minimal JVM verb `hydration.open_write_begin(path)` returns the canonical cache path (via `resolveCachePath`) and materialises an empty cache file **without downloading** — reusing `HydrationImpl.create`'s path-prep. The co-daemon's `setattr` (and `open` when `O_TRUNC` is set) routes truncate-to-0 through it and commits via the existing `open_write`. truncate-to-N>0 falls back to `open_read` (download) + `set_len(N)`. Spec: `docs/dev/specs/setattr-truncate-design.md` (v2).

**Tech Stack:** Kotlin (`app:hydration`, IPC over a UNIX socket, JSON-line wire), Rust (`unidrive-mount-linux`, `fuse3 = 0.9`, tokio), JUnit + Rust integration tests with a fake JVM.

---

## File Structure

**JVM (`unidrive`):**
- `core/app/hydration/.../Hydration.kt` — add one interface method `openWriteBegin`.
- `core/app/hydration/.../HydrationImpl.kt` — impl; factor a private `prepareWriteCache(path)` helper shared with `create`.
- `core/app/hydration/.../HydrationIpcHandler.kt` — one new verb branch + add to `VERBS` + wire doc.
- `core/app/hydration/.../HydrationImplTest.kt` — unit tests.

**co-daemon (`unidrive-mount-linux`):**
- `mount/src/ipc.rs` — `open_write_begin` client method + `OpenWriteBeginReply` struct.
- `mount/src/reconnect.rs` — the io-retry wrapper for it.
- `mount/src/fuse_fs.rs` — `Filesystem::setattr` op; `open` `O_TRUNC` branch.
- `mount/tests/setattr_truncate.rs` — new integration test file (fake-JVM driven).

---

## Phase A — JVM `hydration.open_write_begin`

### Task A1: SPI method + impl (reuse `create`'s empty-cache path-prep)

**Files:**
- Modify: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt` (add to interface)
- Modify: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt`
- Test: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt`

- [ ] **Step 1: Write the failing tests** (mirror the existing `HydrationImplTest` fixture — same stub `SyncEngine`/`StateDatabase` setup already used by the `create`/`openForWrite` tests):

```kotlin
@Test
fun `openWriteBegin returns canonical cache path and materialises an empty file without downloading`() = runTest {
    // existing-file row, NOT hydrated (cloud-only)
    stateDb.upsertEntry(fileEntry(path = "/big.bin", remoteSize = 5_000_000, isHydrated = false))
    val before = engine.ensureHydratedCallCount  // stub counter on the test SyncEngine

    val r = hydration.openWriteBegin("/big.bin")

    assertTrue(r is OpenResult.Ok)
    val cache = (r as OpenResult.Ok).cachePath
    assertEquals(engine.resolveCachePath("/big.bin"), cache)
    assertTrue(java.nio.file.Files.exists(cache))
    assertEquals(0L, java.nio.file.Files.size(cache))               // empty
    assertEquals(before, engine.ensureHydratedCallCount)            // NO download
}

@Test
fun `openWriteBegin fails unknown_path for an absent row`() = runTest {
    val r = hydration.openWriteBegin("/nope")
    assertTrue(r is OpenResult.Failed)
    assertEquals("unknown_path", (r as OpenResult.Failed).error.message)
}

@Test
fun `openWriteBegin fails path_is_folder for a directory row`() = runTest {
    stateDb.insertFolder(path = "/dir", remoteId = "f1", mtime = java.time.Instant.now())
    val r = hydration.openWriteBegin("/dir")
    assertTrue(r is OpenResult.Failed)
    assertEquals("path_is_folder", (r as OpenResult.Failed).error.message)
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd core && ./gradlew :app:hydration:test --tests "*HydrationImplTest*" -q`
Expected: FAIL — `openWriteBegin` unresolved.

- [ ] **Step 3: Add the interface method.** In `Hydration.kt`, after `create(...)`:

```kotlin
suspend fun openWriteBegin(path: String): OpenResult
```

- [ ] **Step 4: Implement it in `HydrationImpl.kt`.** Factor the empty-cache materialisation out of `create` into a private helper and call it from both:

```kotlin
/** Resolve the canonical cache path, create its parent dirs, and materialise
 *  an empty (or truncated) cache file there. NO download. Shared by create()
 *  and openWriteBegin(). */
private fun prepareEmptyCache(path: String): java.nio.file.Path {
    val cachePath = syncEngine.resolveCachePath(path)
    java.nio.file.Files.createDirectories(cachePath.parent)
    java.nio.file.Files.newByteChannel(
        cachePath,
        java.util.EnumSet.of(
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
        ),
    ).close()
    return cachePath
}

override suspend fun openWriteBegin(path: String): OpenResult {
    val entry = stateDb.getEntry(path)
        ?: return OpenResult.Failed(HydrationError.Generic("unknown_path"))
    if (entry.isFolder) return OpenResult.Failed(HydrationError.Generic("path_is_folder"))
    return try {
        OpenResult.Ok(prepareEmptyCache(path))
    } catch (e: Exception) {
        OpenResult.Failed(HydrationError.Generic(e.message ?: "open_write_begin failed"))
    }
}
```

Then replace the inline `resolveCachePath` + `createDirectories` + `newByteChannel(...)` block inside `create` with `val cachePath = prepareEmptyCache(normalised)` (behaviour identical — `create` already does exactly this).

- [ ] **Step 5: Run to verify pass**

Run: `cd core && ./gradlew :app:hydration:test --tests "*HydrationImplTest*" -q`
Expected: PASS (including the unchanged `create` tests — the refactor is behaviour-preserving).

- [ ] **Step 6: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt \
        core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt \
        core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt
git commit -m "feat(hydration): open_write_begin — canonical write-cache path without hydrating"
```

### Task A2: IPC handler verb branch + `VERBS` + wire doc

**Files:**
- Modify: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt`
- Test: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationIpcHandlerTest.kt` (or wherever the per-verb handler tests live — mirror the `open_read` handler test)

- [ ] **Step 1: Failing test** — add `open_write_begin` to the existing "every VERB is dispatched (not `unknown_verb`)" test's expectation, and a branch test:

```kotlin
@Test
fun `open_write_begin returns cache_path`() = runTest {
    stateDb.upsertEntry(fileEntry("/x.txt", isHydrated = false))
    val reply = handler.handle("conn1",
        """{"verb":"hydration.open_write_begin","path":"/x.txt"}""")
    assertTrue(reply.contains("\"ok\":true"))
    assertTrue(reply.contains("\"cache_path\""))
}

@Test
fun `open_write_begin missing path`() = runTest {
    val reply = handler.handle("conn1", """{"verb":"hydration.open_write_begin"}""")
    assertEquals("""{"ok":false,"error":"missing_path"}""", reply)
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cd core && ./gradlew :app:hydration:test --tests "*HydrationIpcHandler*" -q`
Expected: FAIL — `unknown_verb` / branch absent.

- [ ] **Step 3: Implement.** Add `"hydration.open_write_begin"` to the `VERBS` list, add the wire-doc line near the top doc-comment, and add the branch in `handle(...)`'s `when` (mirror `open_read` but with no `handle_id`):

```kotlin
"hydration.open_write_begin" -> {
    val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
    when (val r = hydration.openWriteBegin(path)) {
        is OpenResult.Ok -> """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())}}"""
        is OpenResult.Failed -> reply(ok = false, error = r.error.message)
    }
}
```

Wire doc line: `*   open_write_begin request: {"verb":"hydration.open_write_begin","path":"/foo"}  reply ok: {"ok":true,"cache_path":"..."}  errs: unknown_path / path_is_folder`.

- [ ] **Step 4: Run to verify pass**

Run: `cd core && ./gradlew :app:hydration:test -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt \
        core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationIpcHandlerTest.kt
git commit -m "feat(hydration): wire open_write_begin IPC verb"
```

### Task A3: Rust IPC client method + reconnect wrapper

**Files:**
- Modify: `mount/src/ipc.rs` (new method + reply struct)
- Modify: `mount/src/reconnect.rs` (io-retry wrapper)
- Test: `mount/tests/ipc_client.rs` (mirror the `open_read` client test)

- [ ] **Step 1: Failing test** in `mount/tests/ipc_client.rs` (fake server returns `cache_path`):

```rust
#[tokio::test]
async fn open_write_begin_returns_cache_path() {
    let (mut client, _server) = fake_ipc(replies(&[(
        "hydration.open_write_begin",
        r#"{"ok":true,"cache_path":"/cache/x.txt"}"#,
    )])).await;
    let reply = client.open_write_begin("/x.txt").await.unwrap();
    assert_eq!(reply.cache_path, std::path::PathBuf::from("/cache/x.txt"));
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cargo test --test ipc_client open_write_begin -q`
Expected: FAIL — method absent.

- [ ] **Step 3: Implement** in `ipc.rs` (mirror `open_read`):

```rust
pub struct OpenWriteBeginReply { pub cache_path: PathBuf }

pub async fn open_write_begin(&mut self, path: &str) -> Result<OpenWriteBeginReply, IpcError> {
    let req = serde_json::json!({ "verb": "hydration.open_write_begin", "path": path });
    let reply = self.round_trip(&req).await?;
    if !reply["ok"].as_bool().unwrap_or(false) {
        return Err(server_error(&reply));
    }
    let cache = reply["cache_path"].as_str()
        .ok_or_else(|| IpcError::Malformed(reply.to_string()))?;
    Ok(OpenWriteBeginReply { cache_path: PathBuf::from(cache) })
}
```

And the `ReconnectingIpcClient` wrapper in `reconnect.rs` (mirror its `open_read` wrapper — the `loop { ensure_connected; match inner.open_write_begin(path).await { Ok(v)=>return Ok(v), Err(Io(_))=>{self.inner=None; continue}, Err(e)=>return Err(e) } }` shape).

- [ ] **Step 4: Run to verify pass**

Run: `cargo test --test ipc_client open_write_begin -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mount/src/ipc.rs mount/src/reconnect.rs mount/tests/ipc_client.rs
git commit -m "feat(ipc): open_write_begin client + reconnect wrapper"
```

---

## Phase B — co-daemon `setattr` + `open(O_TRUNC)`

> Before B1, confirm the `fuse3 = 0.9` `Filesystem::setattr` signature and the `SetAttr` field names against the crate source: `ls ~/.cargo/registry/src/*/fuse3-0.9*/src/` then grep `fn setattr` and `struct SetAttr`. Expected shape: `async fn setattr(&self, req: Request, inode: u64, fh: Option<u64>, set_attr: SetAttr) -> Result<ReplyAttr>`, with `set_attr.size: Option<u64>`, `.mode: Option<u32>`, `.atime`/`.mtime: Option<Timestamp>`. Use the actual field names from the crate.

### Task B1: `setattr` op

**Files:**
- Modify: `mount/src/fuse_fs.rs` (add `setattr` to the `Filesystem` impl)
- Test: `mount/tests/setattr_truncate.rs` (new)

- [ ] **Step 1: Failing tests** (`mount/tests/setattr_truncate.rs`, mirror `write_through.rs`'s fake-JVM harness):

```rust
// (a) truncate-to-0 of a cloud-only file uses open_write_begin, NOT open_read
#[tokio::test]
async fn setattr_size_zero_uses_open_write_begin_no_download() {
    let jvm = FakeJvm::with(replies(&[
        ("hydration.list", r#"{"ok":true,"entries":[{"path":"/big.bin","size":5000000,"mtime_ms":0,"hydrated":false,"folder":false}]}"#),
        ("hydration.open_write_begin", r#"{"ok":true,"cache_path":"<TMP>/big.bin"}"#),
        ("hydration.open_write", r#"{"ok":true,"cache_path":"<TMP>/big.bin"}"#),
    ]));
    let fs = mount_with(&jvm).await;
    fs.setattr(/* inode for /big.bin */, /* fh */ None, set_attr_size(0)).await.unwrap();
    assert!(jvm.saw("hydration.open_write_begin"));
    assert!(!jvm.saw("hydration.open_read"));   // NO download
    assert_eq!(std::fs::metadata("<TMP>/big.bin").unwrap().len(), 0);
}

// (b) truncate-to-N>0 downloads (open_read) then sets length N
#[tokio::test]
async fn setattr_size_positive_downloads_then_sets_len() { /* open_read reply yields a cache file of 100 bytes; setattr(size=40); assert file len==40, jvm.saw("open_read") */ }

// (c) set_len failure → EIO, no commit
#[tokio::test]
async fn setattr_set_len_failure_returns_eio_and_does_not_commit() { /* make set_len fail (read-only cache dir); assert Err(EIO) and !jvm.saw("open_write") */ }

// (d) chmod returns success without error
#[tokio::test]
async fn setattr_chmod_is_accepted_noop() { /* setattr(mode=0o600) -> Ok, returns attr; no open_read/open_write */ }
```

- [ ] **Step 2: Run to verify fail**

Run: `cargo test --test setattr_truncate -q`
Expected: FAIL — `setattr` not implemented (or panics on the default).

- [ ] **Step 3: Implement `setattr`** in the `Filesystem` impl. Skeleton (use the confirmed `SetAttr` field names):

```rust
async fn setattr(&self, _req: Request, inode: u64, fh: Option<u64>, set: SetAttr) -> Result<ReplyAttr> {
    let path = self.paths.lock().await.path_for(inode)
        .map(|s| s.to_string()).ok_or_else(|| Errno::from(libc::ENOENT))?;

    if let Some(new_size) = set.size {
        // Resolve a writable canonical cache path.
        let cache_path = if new_size == 0 {
            // No download: JVM materialises an empty canonical cache file.
            self.ipc.lock().await.open_write_begin(&path).await
                .map_err(ipc_error_to_errno)?.cache_path
        } else {
            // Need the kept prefix: hydrate then truncate.
            let hid = format!("th-{}", self.next_handle_id.fetch_add(1, Ordering::Relaxed));
            let cp = self.ipc.lock().await.open_read(&hid, &path).await
                .map_err(ipc_error_to_errno)?.cache_path;
            std::fs::OpenOptions::new().write(true).open(&cp)
                .and_then(|f| f.set_len(new_size))
                .map_err(|e| { tracing::warn!(?e, "set_len failed"); Errno::from(libc::EIO) })?;  // failure → EIO, NO commit below
            cp
        };
        // Refresh cached attr.
        self.attrs.lock().await.entry(inode).and_modify(|a| a.size = new_size);
        // Commit: dirty the open handle if ftruncate; else commit synchronously.
        if let Some(fh) = fh.and_then(|h| /* handle exists */ Some(h)) {
            if let Some(h) = self.open_handles.lock().await.get(&fh) { h.dirty.store(true, Ordering::Relaxed); }
        } else {
            let hid = format!("th-{}", self.next_handle_id.fetch_add(1, Ordering::Relaxed));
            self.ipc.lock().await
                .open_write(&hid, &path, &cache_path.to_string_lossy()).await
                .map_err(ipc_error_to_errno)?;   // synchronous commit (mirrors fsync) — see Risks
        }
    }
    // mode/uid/gid: accept, no-op. atime/mtime: best-effort set on the cache file.
    if let (Some(_), _) = (set.mode, ()) { /* no-op accept */ }
    // (apply set.atime/set.mtime to the cache file via filetime if present — best-effort)

    let a = self.get_or_fetch_attr(inode).await?;
    Ok(ReplyAttr { ttl: Duration::from_secs(1), attr: file_attr_from_cached(inode, &a) })
}
```

(Match the exact `set_len`-failure-before-dirty ordering: the EIO `?` returns before any `dirty`/`open_write`, so a failed truncate never commits.)

- [ ] **Step 4: Run to verify pass**

Run: `cargo test --test setattr_truncate -q`
Expected: PASS (a–d).

- [ ] **Step 5: Commit**

```bash
git add mount/src/fuse_fs.rs mount/tests/setattr_truncate.rs
git commit -m "feat(fuse): setattr — truncate (no-download to 0) + chmod/utimes best-effort"
```

### Task B2: `open(O_TRUNC)` routing

**Files:**
- Modify: `mount/src/fuse_fs.rs` (the `open` handler at ~313)
- Test: `mount/tests/setattr_truncate.rs` (add)

- [ ] **Step 1: Failing test**

```rust
#[tokio::test]
async fn open_with_o_trunc_uses_open_write_begin_not_open_read() {
    // open a cloud-only file with O_WRONLY|O_TRUNC
    // assert jvm.saw("open_write_begin") && !jvm.saw("open_read")
}
```

- [ ] **Step 2: Run to verify fail** — `cargo test --test setattr_truncate open_with_o_trunc -q` → FAIL (open still calls open_read).

- [ ] **Step 3: Implement.** In `open`, after computing `writable`, branch on `O_TRUNC`:

```rust
let truncating = writable && (flags as i32) & libc::O_TRUNC != 0;
let reply_cache_path = if truncating {
    self.ipc.lock().await.open_write_begin(&path).await.map_err(ipc_error_to_errno)?.cache_path
} else {
    let r = { let mut ipc = self.ipc.lock().await; ipc.open_read(&handle_id, &path).await }
        .map_err(ipc_error_to_errno)?;
    r.cache_path
};
```

…then open the cache FD against `reply_cache_path` as today (read+write for `writable`).

- [ ] **Step 4: Run to verify pass** — `cargo test --test setattr_truncate -q` → PASS.

- [ ] **Step 5: Commit**

```bash
git add mount/src/fuse_fs.rs mount/tests/setattr_truncate.rs
git commit -m "feat(fuse): route open(O_TRUNC) through open_write_begin (no download)"
```

### Task B3: concurrent write + truncate race test

**Files:** Test: `mount/tests/setattr_truncate.rs` (add); mirror `mount/tests/fsync_durability.rs`.

- [ ] **Step 1: Write the test** — a `write` lands during the truncate's synchronous commit; assert the written bytes are still committed (the dirty-claim `swap(false)`-then-restore on the open handle does not drop a racing write). Reuse `fsync_durability.rs`'s race harness verbatim, swapping the `fsync` call for the bare-`truncate` synchronous-commit path.

- [ ] **Step 2: Run** — `cargo test --test setattr_truncate race -q` → PASS.

- [ ] **Step 3: Commit**

```bash
git add mount/tests/setattr_truncate.rs
git commit -m "test(fuse): concurrent write+truncate race (dirty-claim safety)"
```

### Task B4 (optional, optimisation): negotiate `FUSE_ATOMIC_O_TRUNC`

Only if `fuse3 = 0.9` exposes the flag in `ReplyInit`/`init`. Negotiating it avoids the redundant separate `SETATTR(size=0)` after `open(O_TRUNC)` on capable kernels. Correctness does NOT depend on it (B2 handles `O_TRUNC` in `open` unconditionally). Skip if the flag isn't exposed; file as a follow-up.

---

## Self-Review

- **Spec coverage:** open_write_begin verb (A1–A3) ✓; setattr size=0 no-download / size>0 download+set_len / set_len-failure-EIO / chmod-utimes (B1) ✓; open(O_TRUNC) both-kernels (B2 + B1 setattr=0) ✓; concurrent-write race (B3) ✓; directory rejection (A1 path_is_folder) ✓; canonical-cache consistency (A1 reuses resolveCachePath; commit always uses the returned path) ✓. Non-goals (provider-mtime propagation, truncate-N>0 prefix download) explicitly deferred/filed.
- **Type consistency:** `openWriteBegin → OpenResult` (Ok(cachePath)/Failed) used consistently in A1/A2; `open_write_begin → OpenWriteBeginReply{cache_path}` in A3/B1/B2; commit always via the existing `open_write`.
- **Placeholder scan:** B1/B2 skeletons use the real method names; the two intentionally-elided test bodies (B1 c/d, B3) cite the exact harness to mirror (`write_through.rs`, `fsync_durability.rs`) and the precise assertion — fill from those when implementing.
- **Risks (from spec):** bare-`truncate(path)` synchronous commit blocks the FUSE op for large dirty files (intentional; mirrors `fsync`). `set_len` failure returns EIO before any commit. `SetAttr` field names confirmed against the crate before B1.
