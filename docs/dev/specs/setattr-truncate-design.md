# setattr / truncate for the FUSE mount — Design

**Status:** Proposed — design doc, not yet implemented.
**Version:** 2 — incorporates spec-review feedback (mandated `O_TRUNC` handling, directory rejection on the new verb, `set_len` failure path, synchronous-commit blocking documented, concurrent-write+truncate race test, `create`-precedent reuse, sparse-truncate follow-up).
**Origin:** `unidrive-mount-linux/BACKLOG.md` High item "`setattr` is unimplemented — chmod/chown/utimes and especially truncate/ftruncate", surfaced by the FUSE-transparency review (`docs/dev/specs/fuse-transparency-coverage.md`).
**Touches:**
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt` — no new logic; reuse the existing `resolveCachePath` from the new hook.
- `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt` + `HydrationImpl.kt` + `HydrationIpcHandler.kt` — one new verb, `open_write_begin`.
- `../unidrive-mount-linux/mount/src/{fuse_fs.rs,ipc.rs,reconnect.rs}` — the FUSE `setattr` op, `open(O_TRUNC)` handling, and the new verb's IPC client.

## Problem

The co-daemon implements no FUSE `setattr` op. Consequences:
- `chmod`/`chown`/`touch -d` (utimes) return ENOSYS — cosmetic for a single-user cloud mount.
- **truncate/ftruncate (a SETATTR carrying `FATTR_SIZE`) is unhandled** — so `>` overwrite of an existing file (`open(O_TRUNC)`), `truncate(1)`, and editor ftruncate-on-save either fail or leave stale trailing bytes (write-at-offset-0 overwrites the head but does not shorten the file).

Requirement settled during brainstorming: a truncate-to-0 must **not** download the file (`> bigfile` on a cloud-only file must not pull its bytes just to discard them).

## Verified internals (current write-through model)

- `open(write)` issues `hydration.open_read` (which **downloads** via `ensureHydrated`) to materialise the cache file, then opens the cache FD; `write` does `pwrite` on the cache + sets `dirty`; `fsync`/`release` commit dirty handles via `hydration.open_write`. The current `open` handler (`fuse_fs.rs:333`) extracts `O_ACCMODE` only and **ignores `O_TRUNC`**.
- `HydrationImpl.openForWrite(path, cachePath)` → `syncEngine.uploadFromCache(path, cachePath)` uploads the file **at the supplied `cachePath`** (arbitrary path; `provider.upload(cachePath, …)`), then sets `state.db` `localSize`/`localMtime`/`isHydrated=true` from that file. Because it marks the row hydrated, the co-daemon must commit the **canonical** cache path (a scratch temp would leave `state.db` claiming hydrated while the canonical cache is stale → wrong reads).
- `SyncEngine.resolveCachePath(path)` returns the canonical cache path **without downloading** (`<cacheRoot>/unidrive/hydration/<cacheKey>/<path>`), already used by `dehydrate`.
- **Precedent:** `HydrationImpl.create` (line ~241) already does exactly `resolveCachePath(path)` + `Files.createDirectories(cachePath.parent)` + returns the path with no download — for *new* paths. `open_write_begin` is "`create`, but for an existing row." Factor the shared path-prep (resolve + mkdir-parent) into one private helper and call it from both.

## Design

### New JVM verb: `hydration.open_write_begin`

- Wire: `{"verb":"hydration.open_write_begin","path":"/x"}` → `{"ok":true,"cache_path":"<canonical>"}`. Errors: `{"ok":false,"error":"unknown_path"}` if the `state.db` row is absent (same precondition as `open_read`); `{"ok":false,"error":"path_is_folder"}` if the row is a directory (mirrors the existing `unlink`/`rmdir` `path_is_folder` → EISDIR pattern at `HydrationIpcHandler` ~256).
- Impl: look up the row; reject folders; reuse the `create` path-prep helper (`resolveCachePath(path)` + `Files.createDirectories(parent)`); return the path. **No download, no `provider` call, no `HydrationEvent`, no `state.db` mutation.** The actual upload/commit reuses the existing `open_write` verb — this verb only hands the co-daemon a writable canonical cache path without hydrating.
- Register in `HydrationIpcHandler.VERBS` (single source of truth) + add the co-daemon IPC client method (with the `ReconnectingIpcClient` io-retry wrapper, like the other verbs).

### Co-daemon `setattr`

Implement `Filesystem::setattr`; branch on which `SetAttr` fields are present:

- **`size = S` (truncate/ftruncate):**
  - `S == 0`: `open_write_begin(path)` → canonical cache path (no download) → create/truncate the file to length 0 → mark for commit → set `CachedAttr.size = 0`.
  - `S > 0`: ensure the cache holds the bytes — `open_read(path)` (download) → `set_len(S)` on the cache → mark for commit → set `CachedAttr.size = S`. (Rare.) **If `set_len` fails (ENOSPC/quota), do NOT mark dirty — no bytes changed — and propagate EIO; the cloud copy stays untouched.**
  - Commit: if the SETATTR carries an `fh` (ftruncate), mark that `OpenHandle` `dirty` (commits at `release`/`fsync`). If no `fh` (bare `truncate(path)`), commit **synchronously** via `open_write(handle_id, path, cache_path)`, reusing `fsync`'s dirty-claim (`swap(false)` before upload, restore on failure) — see Risks for the blocking implication.
- **`mode`/`uid`/`gid` (chmod/chown):** accept as success, no-op (cloud has no POSIX mode; static-perms model). Return the existing perms.
- **`atime`/`mtime` (utimes/touch):** set the cache file's times (best-effort); provider-mtime propagation is a fast-follow.
- Return the updated `FileAttr` built from the refreshed `CachedAttr`.

### Co-daemon `open` with `O_TRUNC` — mandated handling

`O_TRUNC` delivery is kernel-negotiation-dependent, so handle it at **both** entry points rather than relying on one:

- **`open` always checks `flags & libc::O_TRUNC`.** On a write-open with `O_TRUNC` set, route to the no-hydrate path (`open_write_begin` + truncate-to-0) instead of `open_read`. This covers **both** kernels: with `FUSE_ATOMIC_O_TRUNC` the kernel folds the truncate into `open` and sends **no** separate SETATTR, so `open` MUST do it; without it, the kernel sends `open` (carrying `O_TRUNC`) then a separate `SETATTR(size=0)`, and `open` simply must not pre-download.
- **`setattr(size=0)`** independently handles the standalone `truncate(1)` / non-atomic separate-SETATTR. The two paths are idempotent — both truncate the canonical cache to 0; a redundant pair on the non-atomic kernel is harmless.
- **`FUSE_ATOMIC_O_TRUNC` negotiation in `init` is an optimisation, not a correctness requirement** — negotiate it only if the `fuse3` version exposes the flag (avoids the redundant separate SETATTR round-trip). Correctness holds either way because `open` checks `O_TRUNC` unconditionally.

### Consistency invariant

Always write the **canonical** cache path (from `open_write_begin` / `resolveCachePath`). Then `uploadFromCache`'s `isHydrated=true` and the canonical cache file agree — no stale-cache reads after a truncate.

## Testing (TDD)

- **JVM** (`HydrationImplTest`): `open_write_begin` returns `resolveCachePath` + creates the parent dir, makes **no** `ensureHydrated`/`provider` call (assert via a stub engine); returns `unknown_path` for an absent row and `path_is_folder` for a directory.
- **co-daemon** (fake-JVM integration):
  - `setattr(size=0)` on a cloud-only file truncates to 0 and the fake sees `open_write_begin` — **not** `open_read` (no download).
  - **Both `O_TRUNC` paths** (the point-1 catch): (a) atomic — `open` carrying `O_TRUNC` with no following SETATTR does the truncate via `open_write_begin`; (b) non-atomic — `open(O_TRUNC)` then a separate `SETATTR(size=0)` causes no double-download and is idempotent.
  - `truncate -s N` (N>0) issues `open_read` then sets length N; a shrink leaves no trailing bytes (read-back); `set_len` failure propagates EIO and does **not** commit.
  - `chmod` returns success (no error); ftruncate via an `fh` commits at `release`; bare `truncate(path)` commits synchronously.
  - **Concurrent write + truncate race** — mirror `mount/tests/fsync_durability.rs`'s fsync race: a `write` landing during the truncate's commit must still be committed (the dirty-claim `swap(false)`-then-restore must not drop it).

## Scope / non-goals

- Provider-mtime propagation on utimes: fast-follow (not all providers accept a set-mtime).
- truncate-to-`N`>0 on a non-hydrated file downloads first. For **shrinking** to N>0 the kept bytes are genuinely needed, so a download is unavoidable; for **growing** (N > current size) a sparse `set_len` could skip allocating/downloading. Optimising the grow case is a **filed follow-up** (`unidrive-mount-linux` BACKLOG, Low) — not in scope here.
- `xattr` / `nlink` / `statfs`: separate filed items, out of scope here.

## Risks

- **Synchronous commit blocks the FUSE op (bare `truncate(path)`).** With no `fh` to defer to, we commit inline (like `fsync`), so a `truncate` on a large *dirty* file blocks the calling process — and `truncate` is common in scripts — until the upload finishes. This is an **intentional** choice: we must own the byte commit, and there is no handle to defer it to. Documented stuck-until-uploaded risk for large dirty files; mirrors `fsync`'s existing behaviour. (A future bounded-deadline-then-background-commit variant could soften it.)
- `open(O_TRUNC)` op delivery is kernel-negotiation-dependent — the design handles both paths unconditionally (see above) and tests both, rather than assuming one.
