# Breadth-first bug-class sweep — unidrive (JVM) ↔ unidrive-mount-linux (Rust)

Read-only audit, 2026-05-26. Lens: grep-driven scan for code-smell bug classes,
edge cases, resource leaks, retry/throttle correctness, and cross-repo IPC
contract drift. Distinct from a deep-flow auditor — this is a wide net, each hit
verified against source before listing.

Repos / commits:
- JVM `unidrive` @ `main` 7a5f245
- Rust `unidrive-mount-linux` @ `main` adc2d25

`.claude/worktrees/**` were excluded from all scans (stale per-agent copies).

## Ranked findings

| # | Severity | Class | Title | Location |
|---|----------|-------|-------|----------|
| 1 | High | Concurrency / data-loss | `openSets` inner map is a non-thread-safe `HashMap`; the `dehydrate` busy-check iterates it cross-connection while another connection mutates it (stale TODO foresaw this; the gating verbs have since landed) | `HydrationImpl.kt:48-49,161` + 83/103/124/141/317/358 |
| 2 | High | Retry idempotency | `ReconnectingIpcClient` retries **non-idempotent mutating verbs** (`rename`/`open_write`/`create`/`unlink`/`rmdir`/`mkdir`) on `IpcError::Io` — a JVM disconnect *after acting but before the reply* re-sends the op; for `rename` the retry hits `old_path_not_found` → spurious ENOENT to userland on an `mv` that actually succeeded | `mount/src/reconnect.rs:272-285` (and 98,212,227,242,257) |
| 3 | Medium | IPC contract / errno | JVM `open_read`/`open_write` return the unknown-path failure as `"Unknown path: <p>"` (free text), which the Rust side maps via the catch-all `ipc_error_to_errno` → **EIO** instead of ENOENT; `open_write_begin` correctly uses the bare `unknown_path` token mapped to ENOENT — the two open paths disagree on the wire word for the same condition | `HydrationImpl.kt:65,89` vs `:308`; `mount/src/fuse_fs.rs:174-195` |
| 4 | Medium | Edge case / unicode | FUSE `lookup` builds `child_path` from raw kernel name bytes and does byte-exact `e.path == child_path` against JVM `list` results; neither side runs NFC/NFD normalization (`java.text.Normalizer` is absent repo-wide), so an NFD-vs-NFC mismatch makes an existing file resolve to ENOENT. Connected to the already-filed JVM "Path normalization (NFC)" item — the FUSE byte-exact compare is the second half of that gap | `mount/src/fuse_fs.rs:254-288` + JVM (no `Normalizer`) |
| 5 | Low | Error classification | `rmdir` NotEmpty and `mkdir` ParentNotFound are detected by substring-matching provider English error text (`"not empty"`, `"not found"`, `"404"`); locale-translated or reworded provider messages silently fall through to EIO | `HydrationImpl.kt:218-219,281-283` |
| 6 | Low | Edge case | Empty / root path (`""`→`/`) on `unlink`/`rmdir` returns the free-text `"Unknown path: /"` → EIO rather than POSIX EISDIR/ENOENT; only reachable if the kernel skips `lookup` (it doesn't in practice) | `HydrationImpl.kt:232-234,271-273` |
| 7 | Info | Perf (not a bug) | FUSE `read` holds the `open_handles` tokio mutex across a blocking `pread` on the cache fd, serialising all handle ops during a large read | `mount/src/fuse_fs.rs:401-408` |

Count by severity: **High 2, Medium 2, Low 2, Info 1** (7 total; all NEW unless noted).

---

## Per-finding detail

### 1. `openSets` inner-map concurrency hazard (High)

`HydrationImpl.kt:48-49`:
```kotlin
private val openSets =
    ConcurrentHashMap<String, MutableMap<String, String>>()
```
The outer map is concurrent; the **inner** value is a plain `mutableMapOf()`
(a `LinkedHashMap`). The TODO directly above it (lines 44-47) reads:

> TODO(Task 8): when closeHandle and openForWrite land, two coroutines for the
> same connectionId may race on the inner map. Switch to `ConcurrentHashMap<...>`
> … before this becomes load-bearing.

`closeHandle` (`:141`), `openForWrite` (`:124`), `openForRead` (`:83`),
`create` (`:358`), and the recovery path (`:103`) have all since landed and
mutate the inner map. `dehydrate` (`:161`) reads it:
```kotlin
val anyOpen = openSets.values.any { perConn -> perConn.containsValue(path) }
```
Per-connection requests are serialised by `IpcServer`'s reader loop, so the
mount connection can't race itself. But `dehydrate` can arrive on a **second**
connection (a transient `unidrive` CLI one-shot) and iterate the mount
connection's inner `HashMap` via `containsValue` while the mount connection is
concurrently `put`/`remove`-ing on it from a `Dispatchers.IO` worker
(`IpcServer.handlerDispatcher = Dispatchers.IO`, multi-threaded;
`dispatchRequest` runs each verb in `withContext(handlerDispatcher)`).
Concurrent structural mutation of a `HashMap` from two threads can corrupt it
(lost entries, resize spin) or throw `ConcurrentModificationException` mid-
`containsValue`. Consequences: a `dehydrate` that drops the cache file of a
file that is in fact open for write (**local-edit data loss**), or a CME →
EIO. The TODO's "before this becomes load-bearing" precondition is now met, so
the deferral is stale.

Fix direction: make the inner map a `ConcurrentHashMap` (or
`ConcurrentHashMap.newKeySet`-style structure keyed by handleId), or guard all
six sites with a per-connection lock. `dehydrate`'s busy-check should read a
concurrency-safe structure.

### 2. Reconnecting wrapper retries non-idempotent verbs (High)

`mount/src/reconnect.rs` wraps every verb in
`loop { ensure_connected; match c.<verb>(...).await { Ok=>return, Err(Io)=>{inner=None;continue} … } }`.
`round_trip` (`ipc.rs:215-238`) writes+flushes the request, then `read_line`s
the reply; if the JVM disconnects (redeploy under a live mount — the documented
repro for the BACKLOG "IPC teardown surfaces as bare EIO" item) **after**
processing the verb but before/while writing the reply, `read_line` returns
`n==0` → `IpcError::Io(UnexpectedEof)`. The wrapper then reconnects and re-issues
the **same mutating verb**:
- `rename` (`:272`): first attempt already moved the file; the retry sees the
  source gone → JVM returns `old_path_not_found` → `namespace_err_to_errno`
  → **ENOENT** surfaced on an `mv` that actually succeeded.
- `create`/`open_write`: duplicate upload (idempotent-ish per the CLOSED
  fsync note, but still a redundant cloud PUT).
- `mkdir` of an already-created dir / `unlink` of an already-removed file: the
  retry surfaces a spurious EEXIST/ENOENT.

The module docstring already excludes `subscribe` from wrapping for a related
"don't silently retry" reason; the same reasoning was not extended to the
non-idempotent namespace verbs. Distinct from the existing CLOSED "Mount
survives daemon restart" item, which only established that *read* verbs survive
a restart cycle — it did not address replay safety of mutating verbs across the
reply-loss window.

Fix direction: don't blind-retry deterministic-mutation verbs; either retry only
read/idempotent verbs on `Io`, or have the JVM key mutations by an idempotency
token so a replay is a no-op, or treat `UnexpectedEof` *after* a successful
write as "unknown outcome — surface, don't replay" for mutating verbs.

### 3. `Unknown path` vs `unknown_path` wire-word split (Medium)

`HydrationImpl.openForRead:65` and `openForWrite:89` return
`HydrationError.Generic("Unknown path: $path")`. `HydrationImpl.openWriteBegin:308`
returns `HydrationError.Generic("unknown_path")`. Rust:
- `open_read`/`open_write` failures map through `ipc_error_to_errno`
  (`fuse_fs.rs:174`) → catch-all **EIO**.
- `open_write_begin` failures map through `namespace_err_to_errno`
  (`fuse_fs.rs:184-195`), which has `"unknown_path" => ENOENT`.

So the same "path not in state.db" condition yields ENOENT on the truncate path
but EIO on the read/write open path, and the free-text `"Unknown path: <p>"`
(with the path appended) can never match any `namespace_err_to_errno` arm even
if those calls were rerouted. In practice the kernel runs `lookup` first so a
genuinely-missing path rarely reaches `open_read`, which is why this hasn't
bitten — but the wire contract is inconsistent. Fix: emit the bare
`unknown_path` token from `openForRead`/`openForWrite` too (drop the appended
path / capitalisation), and/or route those failures through `namespace_err_to_errno`.

### 4. FUSE lookup byte-exact path match + no Unicode normalization (Medium)

`mount/src/fuse_fs.rs:254-288`: `child_path` is `format!("{parent}/{name}")`
from the raw kernel `name`, then matched with `e.path == child_path` against
`hydration.list` rows. Grep of the JVM tree finds **no** `java.text.Normalizer`
usage anywhere — the "Path normalization (NFC) across sync" BACKLOG item is
unimplemented. If a provider returns NFC-normalized names but the user/kernel
presents NFD (or vice versa — common for content originating on macOS or
copied from external media), the equality fails and an existing file resolves
to ENOENT through the mount. This is the FUSE half of the already-filed JVM NFC
gap; note it on that ticket rather than as a fully independent issue. Fix
direction belongs with the NFC work: normalize on both the JVM list/store side
and the co-daemon compare side to the same form.

### 5. Provider-error substring classification (Low)

`mkdir` (`:218-219`) and `rmdir` (`:281-283`) classify parent-missing / not-empty
by matching English substrings in the provider exception message
(`"not found"`, `"404"`, `"not empty"`, `"non-empty"`). A reworded or
locale-translated provider message falls through to `Failed` → EIO instead of
the POSIX-correct ENOENT/ENOTEMPTY. **Already partially filed**: JVM BACKLOG
"Typed `FolderNotEmpty` provider exception for `HydrationImpl.rmdir`
(namespace-verbs R2)" covers the rmdir half; the mkdir substring match is the
companion (the namespace-verbs R3 work added the `parent_not_found` token but
still derives it from substring matching). Listed for completeness; dedup
against R2/R3.

### 6. Empty/root path on unlink/rmdir → EIO not POSIX errno (Low)

`unlink`/`rmdir` normalise `""`→`"/"` then `getEntry("/")` returns null (root
isn't a row) → free-text `"Unknown path: /"` → EIO. Unreachable in normal FUSE
flow (kernel `lookup`s first), so low. Noted for the same wire-word cleanup as
finding 3.

### 7. read() holds handle mutex across blocking pread (Info, not a bug)

`mount/src/fuse_fs.rs:401-408`: `read` holds `self.open_handles.lock().await`
across the synchronous `read_at` (pread) on the cache fd. Correct, but
serialises all handle-map operations for the duration of a large read. The
userspace-read cost is already acknowledged by the BACKLOG fuse3-passthrough
item; this is a sub-note, not a new ticket.

---

## Grep methodology note

Sweep patterns run over `core/**/src/main/kotlin/*.kt` (worktrees + generated
excluded) and `mount/src/*.rs`:

1. Swallowed errors: `catch \([^)]*\)\s*\{\s*\}` (none); `runCatching`,
   `.getOrNull()`, `.getOrElse`, `.getOrDefault` (each hit opened — all are
   best-effort cleanup, idempotent records, or documented fall-throughs).
2. Resource leaks: `HttpClient` (every provider has matching `httpClient.close()`),
   `RandomAccessFile` (all `.use{}`), SQLite `prepareStatement`/`createStatement`
   (46 statements vs 46 `.use {}` — all closed), Rust `unwrap`/`expect`/`panic!`
   (all in `#[cfg(test)]` or compile-time constants / `ensure_connected`-
   guaranteed invariants).
3. Retry/throttle: `Retry-After`, `429`, `backoff`, `while (true)`. The
   `HttpRetryBudget` matrix, OneDrive `getDelta`/`downloadFile`, OAuth
   `pollForToken`/`postWithFlakeRetry`, and Internxt `retryOnTransient`/
   `withAuthRetry` are all bounded, capped, and honor `Retry-After` — clean.
   The cross-request retry hazard is in the Rust wrapper (finding 2), not the
   JVM HTTP layer.
4. Concurrency: `ConcurrentHashMap`, `AtomicReference`, `@Volatile`, inner-map
   mutation sites — surfaced finding 1.
5. IPC contract: diffed `HydrationIpcHandler.VERBS` (14) against `ipc.rs`
   `IpcClient` methods (14, match) and `serialiseListEntries`/`serialiseHydrationEvent`
   field names against the Rust `as_str`/`as_u64`/`as_i64` parsers — surfaced
   findings 3, 4. `size`/`mtime_ms` are non-null Longs JVM-side, so no
   Malformed/EIO risk from null fields.

## False positives rejected / already filed

- **JVM `pluck` hand-rolled JSON parser** (`HydrationIpcHandler.kt:345`) — finds
  the first `"key"` substring; theoretically fragile against a path value
  containing a quoted key token. Rejected as a practical bug: all requests are
  serde_json-generated machine messages where embedded quotes are `\"`-escaped,
  so the literal needle `"path"` can't false-match inside a value. Worth a
  comment but not a defect.
- **`IpcServer.parseVerb` quote-scan** — same shape, same reasoning; verbs are
  fixed literals.
- **OneDrive `runCatching { Instant.parse(it) }.getOrNull()` on modified/created
  metadata** (`OneDriveProvider.kt:289-290,357-358`, `GraphApiService.kt`) —
  intentional best-effort timestamp parse; a malformed timestamp yields null
  metadata, not a swallowed hard error.
- **`GraphApiService.checkDeltaCursorAge` / `recordDeltaSeen` runCatching** —
  best-effort safety-file IO; failure correctly degrades to "no warning."
- **`IpcServer` runCatching on socket/channel close** — close-on-teardown, errors
  are non-actionable.
- **`SyncCommand` watch-loop shutdown hook not removed** — process exits at
  shutdown; not a leak (contrast `MountCommand`, which correctly removes its hook
  because it returns to the CLI).
- **Rust `unwrap`/`expect` in op path** — none; all in tests or guaranteed
  invariants. The BACKLOG "co-daemon vanished / no breadcrumb" + "IPC teardown
  EIO" items already cover the observability gap, so no new panic-hardening
  finding is filed.
- **`rmdir` NotEmpty substring match** — see finding 5; folded into the
  already-filed namespace-verbs R2 ticket.
