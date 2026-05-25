# setattr / truncate for the FUSE mount — Design

**Status:** Proposed — design doc, not yet implemented.
**Version:** 1 (initial).
**Origin:** `unidrive-mount-linux/BACKLOG.md` High item "`setattr` is unimplemented — chmod/chown/utimes and especially truncate/ftruncate", surfaced by the FUSE-transparency review (`docs/dev/specs/fuse-transparency-coverage.md`).
**Touches:**
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt` — no new logic; reuse the existing `resolveCachePath` from the new hook.
- `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt` + `HydrationImpl.kt` + `HydrationIpcHandler.kt` — one new verb, `open_write_begin`.
- `../unidrive-mount-linux/mount/src/{fuse_fs.rs,ipc.rs,reconnect.rs}` — the FUSE `setattr` op, `open(O_TRUNC)` handling, and the new verb's IPC client.

## Problem

The co-daemon implements no FUSE `setattr` op. Consequences:
- `chmod`/`chown`/`touch -d` (utimes) return ENOSYS — cosmetic for a single-user cloud mount.
- **truncate/ftruncate (a SETATTR carrying `FATTR_SIZE`) is unhandled** — so `>` overwrite of an existing file (`open(O_TRUNC)` → SETATTR size=0), `truncate(1)`, and editor ftruncate-on-save either fail or leave stale trailing bytes (write-at-offset-0 overwrites the head but does not shorten the file).

Requirement settled during brainstorming: a truncate-to-0 must **not** download the file (`> bigfile` on a cloud-only file must not pull its bytes just to discard them).

## Verified internals (current write-through model)

- `open(write)` issues `hydration.open_read` (which **downloads** via `ensureHydrated`) to materialise the cache file, then opens the cache FD; `write` does `pwrite` on the cache + sets `dirty`; `fsync`/`release` commit dirty handles via `hydration.open_write`.
- `HydrationImpl.openForWrite(path, cachePath)` → `syncEngine.uploadFromCache(path, cachePath)` uploads the file **at the supplied `cachePath`** (arbitrary path; `provider.upload(cachePath, …)`), then sets `state.db` `localSize`/`localMtime`/`isHydrated=true` from that file. Because it marks the row hydrated, the co-daemon must commit the **canonical** cache path (a scratch temp would leave `state.db` claiming hydrated while the canonical cache is stale → wrong reads).
- `SyncEngine.resolveCachePath(path)` returns the canonical cache path **without downloading** (`<cacheRoot>/unidrive/hydration/<cacheKey>/<path>`), already used by `dehydrate`.

## Design

### New JVM verb: `hydration.open_write_begin`

- Wire: `{"verb":"hydration.open_write_begin","path":"/x"}` → `{"ok":true,"cache_path":"<canonical>"}`; `{"ok":false,"error":"unknown_path"}` if the `state.db` row is absent (same precondition as `open_read`).
- Impl: look up the row, `resolveCachePath(path)`, `Files.createDirectories(parent)`, return the path. **No download, no `provider` call, no `HydrationEvent`, no `state.db` mutation.** The actual upload/commit reuses the existing `open_write` verb — this verb only hands the co-daemon a writable canonical cache path without hydrating.
- Register in `HydrationIpcHandler.VERBS` (the single-source-of-truth set) + add the co-daemon IPC client method (with the `ReconnectingIpcClient` io-retry wrapper, like the other verbs).

### Co-daemon `setattr`

Implement `Filesystem::setattr`; branch on which `SetAttr` fields are present:

- **`size = S` (truncate/ftruncate):**
  - `S == 0`: `open_write_begin(path)` → canonical cache path (no download) → create/truncate the file to length 0 → mark for commit → set `CachedAttr.size = 0`.
  - `S > 0`: ensure the cache holds the bytes — `open_read(path)` (download) → `set_len(S)` on the cache → mark for commit → set `CachedAttr.size = S`. (Rare; download acceptable.)
  - Commit: if the SETATTR carries an `fh` (ftruncate), mark that `OpenHandle` `dirty` (commits at `release`/`fsync`). If no `fh` (bare `truncate(path)`), commit synchronously via `open_write(handle_id, path, cache_path)`, mirroring `fsync`'s commit-and-await + dirty-restore-on-failure semantics.
- **`mode`/`uid`/`gid` (chmod/chown):** accept as success, no-op (cloud has no POSIX mode; static-perms model). Return the existing perms.
- **`atime`/`mtime` (utimes/touch):** set the cache file's times (best-effort); provider-mtime propagation is a fast-follow.
- Return the updated `FileAttr` built from the refreshed `CachedAttr`.

### Co-daemon `open` with `O_TRUNC`

For a write-open carrying `O_TRUNC`, route to the no-hydrate path (`open_write_begin` + create-empty cache) instead of `open_read`, so `> file` never downloads. **Verify the live kernel op sequence** for `open(O_TRUNC)`: depending on `FUSE_ATOMIC_O_TRUNC` negotiation the kernel either sends a separate `SETATTR(size=0)` (then the `setattr` size=0 path covers it and `open` must merely not pre-download) or folds it into `open`. Handle whichever fires.

### Consistency invariant

Always write the **canonical** cache path (from `open_write_begin` / `resolveCachePath`). Then `uploadFromCache`'s `isHydrated=true` and the canonical cache file agree — no stale-cache reads after a truncate.

## Testing (TDD)

- **JVM** (`HydrationImplTest`): `open_write_begin` returns `resolveCachePath` + creates the parent dir, makes **no** `ensureHydrated`/`provider` call (assert via a stub engine), and returns `unknown_path` for an absent row.
- **co-daemon** (fake-JVM integration): `setattr(size=0)` on a cloud-only file truncates to 0 and the fake sees `open_write_begin` — **not** `open_read` (no download); `truncate -s N` (N>0) issues `open_read` then sets length N; a shrink leaves no trailing bytes (read-back); `chmod` returns success (no error); ftruncate via an `fh` commits at `release`; bare `truncate(path)` commits synchronously.

## Scope / non-goals

- Provider-mtime propagation on utimes: fast-follow (not all providers accept a set-mtime).
- truncate-to-`N`>0 on a non-hydrated file downloads first (rare; not optimised).
- `xattr` / `nlink` / `statfs`: separate filed items, out of scope here.

## Risks

- The exact `open(O_TRUNC)` op sequence is kernel-version/negotiation-dependent — must be verified on the live mount, not assumed.
- The synchronous-commit path for bare `truncate(path)` must reuse `fsync`'s dirty-claim (`swap(false)` before upload, restore on failure) to stay correct under a concurrent write.
