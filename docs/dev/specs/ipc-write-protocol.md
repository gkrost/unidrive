# Hydration IPC Write Protocol — Spec

**Status:** Implemented. This document is the client-facing contract for the
write-back side of the hydration IPC: the verb sequences a co-client follows
to create, write, move, and delete files through the daemon, the events it
gets back, and the guarantees the daemon makes underneath. A co-client should
be able to implement write-back from this document without reading engine
code. Request/reply byte shapes are pinned by the golden corpus under
`core/app/cli/src/test/resources/ipc-contract/` (replayed by
`IpcContractCorpusTest`), and the end-to-end sequences below are pinned by
`IpcWriteSequenceContractTest` over a live socket.

**Touches:**
- `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/` — SPI, implementation, IPC handler.
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/` — transfer budget, state rows, scope.
- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt` — wiring, startup order, replay.

## 1. Transport and lifecycle

- One Unix-domain socket per profile (same socket serves the push-only sync
  progress stream; the two subscriber sets are independent). Framing is NDJSON:
  one JSON object per `\n`-terminated line, UTF-8. One request line in, one
  reply line out, in order, on the same connection.
- The daemon binds the socket only after every handler is registered: **a
  connect that succeeds is guaranteed a reply for every documented verb.**
  Clients poll for the socket file's existence, connect, and send; there is no
  window in which an accepted request is silently dropped. A verb the daemon
  does not know is answered with `{"ok":false,"error":"unknown_verb"}`; a
  request without a `verb` field gets `{"ok":false,"error":"missing_verb"}` —
  never silence. A client should still bound its wait for a reply line.
- Replies are `{"ok":true,...}` or `{"ok":false,"error":"<token>"}`. Error
  tokens are the stable cross-repo contract listed in §7.
- Events flow only on connections that have issued `hydration.subscribe`.
  Each subscriber has a bounded queue (capacity 64); when it overflows the
  oldest event is dropped and one `{"event":"lost","since_last":N}` sentinel
  precedes the next deliverable event. A client must treat `lost` as
  "resync your per-file state from `hydration.list`".

## 2. Handle model

- Write verbs that open state (`hydration.create`, `hydration.open_write`,
  `hydration.open_write_begin`) are issued with a client-supplied opaque
  `handle_id`. Handles are scoped to the connection: when the connection
  drops, the daemon releases that connection's handles automatically (no
  close storm after a crash).
- One connection per mount is the expected shape; a client correlates its own
  `handle_id` on the `completed` event to the write it started.
- `hydration.close_handle` releases a handle early (optional; the daemon
  also cleans up on connection close).

## 3. Write sequences

All remote paths are absolute (`/a/b.txt`). For a scoped profile the mount
root maps to the scope root, so the client prefixes the scope; paths outside
the scope are refused (§7).

### 3.1 New file

```
1. hydration.create  {handle_id, path}          -> {"ok":true,"cache_path":<local>,"handle_id":...}
2. write the user's bytes into <cache_path>     (local file I/O, no daemon involvement)
3. hydration.open_write {handle_id, path, cache_path}
                                                -> {"ok":true,"cache_path":...}
4. await completed (direction=upload) for handle_id
```

- Step 3 returns immediately: the upload runs in the daemon (queued, then
  transferred under the daemon-wide per-provider budget — see §6).
- The upload phase emits `queued` → `hydrating` → (`uploading`…) → `hydrated`
  → `completed ok:true`.
- `completed ok:true` is the ONLY signal that the cloud has the content. A
  client must not mark a file in-sync from `hydrated` alone — `hydrated`
  fires for downloads too and, for uploads, only says the daemon finished
  the transfer, which (for providers without atomic replace) is still the
  completion event's job to correlate.

### 3.2 Overwrite (save of an existing file)

Same sequence as 3.1 minus `create`: the client writes the full new content
to the cache path the engine returned (from `open_write_begin`, `create`, or
`open_read` for 3.4) and issues `open_write` with that path. Clients must send
the path the engine returned; a cache path outside the profile's hydration
cache folder is refused with `invalid_path` before anything is queued (§7).

- Base-etag guard: if the client observed an `etag` for the file (via
  `hydration.list`) it SHOULD send it as `base_etag`. The guard runs twice:
  when the row's token no longer matches — someone changed the cloud copy
  since the client last looked — the write is refused with
  `{"ok":false,"error":"conflict"}` BEFORE anything is uploaded; and where
  the provider supports conditional writes, the token is forwarded so a
  change that lands between the guard and the transfer fails the upload with
  `conflict` as well (upload-time convergence). Either way both copies
  survive. Absent `base_etag` (or a row with no token) the write is
  unconditional. A conflicted upload is terminal — it is not retried; the
  row stays visibly failed until the client re-sends with a fresh etag.

### 3.3 Truncate / fresh empty file

- `hydration.open_write_begin {path[, handle_id]}` prepares an empty cache
  file (O_TRUNC shape) and answers `{"ok":true,"cache_path":...}`. With
  `handle_id` the open is registered (dehydrate refuses while it is open);
  without one it is a one-shot truncate that registers nothing.

### 3.4 Append

No dedicated verb: the client reads the cache copy (after `open_read` if the
content is not local), appends, and issues `open_write` with the full new
content. The upload always replaces the remote object.

### 3.5 Rename / move (POSIX safe-save is 3.6)

```
hydration.rename {old_path, new_path}      -> {"ok":true}
errors: old_path_not_found / new_parent_not_found / new_path_exists /
        outside_scope / busy
```

- A never-uploaded source is renamed purely locally (row + cache file move);
  an uploaded source is moved in the cloud.
- `replace:false` refuses an existing destination (`new_path_exists`) — the
  historic contract; userland does unlink-then-rename.
- Both ends must lie inside the profile's scope; either end outside refuses
  with `outside_scope` and nothing is moved.

### 3.6 Replace-rename (editors' safe-save: temp file + rename over target)

```
1. hydration.create {handle_id, path=/dir/.tmp-save}   (or open_write_begin)
2. write the new content into the temp cache file
3. hydration.open_write {handle_id, path=/dir/.tmp-save, cache_path}
4. hydration.rename {old_path=/dir/.tmp-save, new_path=/dir/target, replace:true}
```

- With `replace:true` an existing FILE destination is deleted first through
  the same path `unlink` takes (trash/undo semantics), then the source takes
  its place. A folder destination is never replaced (`new_path_exists`).
- The delete-then-move is not atomic: a crash between the two leaves the
  destination tombstoned (recoverable) and the temp file intact.
- If an upload of the source or the destination is still queued or running,
  the rename is refused with `{"ok":false,"error":"busy"}` and nothing is
  touched; the client retries after the `completed` event for that handle.
  Safe-save closes the temp file before renaming, so this window is the
  normal case, not an edge.

### 3.7 Delete file / delete folder

```
hydration.unlink {path}   -> {"ok":true} | {"ok":false,"error":"path_is_folder"} | ...
hydration.rmdir   {path}  -> {"ok":true} | {"ok":false,"error":"path_is_file"} |
                                          {"ok":false,"error":"not_empty"} | ...
```

- A never-uploaded file's row is hard-deleted (there is no cloud object to
  trash); otherwise the delete goes through the provider's trash.
- If the user deleted a file whose upload is still in flight, the client
  first issues `hydration.cancel {path}` (§5), then `unlink`.

### 3.8 Mkdir / rmdir

`hydration.mkdir` creates the folder in the cloud and emits
`hydrating`/`hydrated` for it; `hydration.rmdir` refuses non-empty folders
(`not_empty`). Both refuse out-of-scope paths.

## 4. Events (subscription stream)

| Event | Shape | Meaning |
|---|---|---|
| `queued` | `{"event":"queued","path":…}` | upload accepted, waiting for its turn (column: Waiting) |
| `hydrating` | `{"event":"hydrating","path":…}` | transfer started (upload or download) |
| `uploading` | `{"event":"uploading","path":…,"handle_id":…,"bytes_done":N,"bytes_total":M}` | upload byte progress, coalesced to a few per second, correlated by handle |
| `hydrated` | `{"event":"hydrated","path":…,"bytes":N}` | the cache copy is complete |
| `skipped` | `{"event":"skipped","path":…}` | excluded name: accepted, deliberately never uploaded |
| `dehydrated` | `{"event":"dehydrated","path":…}` | cache copy freed |
| `failed` | `{"event":"failed","path":…,"error":"…"[,"retry_scheduled":true\|false]}` | attempt failed; uploads say whether the daemon will retry |
| `completed` | `{"event":"completed","path":…,"handle_id":…,"direction":"upload\|download","ok":true\|false[,"error":"…"]}` | handle-scoped outcome — the in-sync signal |
| `view.invalidated` | `{"event":"view.invalidated","paths":[…],"full":…}` | enumeration changed the view; drop readdir/getattr caches |
| `lost` | `{"event":"lost","since_last":N}` | your queue overflowed; resync from `hydration.list` |

Excluded names (`exclude_patterns`): `create`/`open_write` answer
`{"ok":true,...,"excluded":true}` and the stream carries `skipped` and a
`completed ok:false error:excluded` — never `hydrating`/`hydrated`. The file
is served from local content only. `hydration.list` entries carry
`"excluded":true` for such rows.

## 5. Cancel

`hydration.cancel {path}` aborts every in-flight submission for the path —
queued, waiting for the transfer budget, mid-transfer, or in a retry backoff.
Reply `{"ok":true,"cancelled":true}` when something was aborted,
`{"ok":true,"cancelled":false}` when nothing was in flight (idempotent). Each
aborted submission reports `completed ok:false error:cancelled` for its
handle. A cancel mid-transfer cuts the provider call at its next suspension
point, so a provider that commits a partial object before suspending can
still leave a remote item; the caller follows with the row-level verb
(`unlink`/`rename`) for the path itself.

## 6. Ordering, budget, and durability

- **Per-path FIFO:** same-path uploads run strictly in submission order; a
  burst of saves cannot land an older save after a newer one.
- **Daemon-wide transfer budget:** all uploads — mount submissions and sync
  passes — share one per-provider cap (Internxt 2, default 4). A burst larger
  than the cap waits, not races the provider's rate limits.
- **Bounded waiting queue with back-pressure:** at most
  `uploadQueueDepth` (default 256) uploads wait at a time. A burst beyond
  that suspends the `open_write` caller — the client's own queue is the
  pressure valve, not daemon memory.
- **Retry:** a failed attempt is retried with backoff up to `maxUploadAttempts`
  (default 3). Every failed attempt stamps the row (`error` flag in
  `hydration.list`, doctor surfacing) and emits `failed` with
  `retry_scheduled:true`; the last attempt emits `retry_scheduled:false`.
  Two failures are never retried and emit `retry_scheduled:false` at once: a `conflict` (the cloud copy
  changed under the edit) and an upload whose row or cache copy has vanished while it was queued (renamed away,
  unlinked, reaped): nothing a later attempt can change, and waiting would keep the path busy to `dehydrate` and
  replace-`rename`.
- **Replay:** state.db rows are the durable queue. At daemon start every
  hydrated file row whose content never reached the cloud is re-enqueued once
  through the same queue — a restart alone drains the backlog with no client
  connected. Excluded and out-of-scope rows are skipped. The co-daemon's own
  `recovery-<n>` scanner remains the client-side complement for cache files
  whose rows it knows better; duplicate replays are harmless (same bytes,
  per-path serialized).
- **Eviction and rename interlocks:** `dehydrate` and replace-`rename` refuse
  with `busy` while an upload of the path is in flight; re-listing after a
  `completed` event observes `pending_upload:false`. If a queued upload's row
  disappears anyway (renamed away, unlinked, reaped), the upload refuses —
  the client gets a failed `completed` and the bytes stay in the cache; the
  daemon never resurrects the old remote path.

## 7. Error tokens (stable cross-repo contract)

| Token | Raised by | Meaning |
|---|---|---|
| `unknown_path` | row-lookup verbs | no such row (ENOENT) |
| `parent_not_found` | create, mkdir | parent row missing or not a folder |
| `path_exists` | create | row already exists (EEXIST) |
| `new_path_exists` | rename | destination exists and replace was not asked |
| `old_path_not_found` / `new_parent_not_found` | rename | row/parent missing |
| `path_is_folder` / `path_is_file` | unlink/rmdir/open_write_begin | wrong kind |
| `not_empty` | rmdir | folder still has children |
| `conflict` | open_write | base_etag no longer matches the row |
| `invalid_path` | open_write (`cache_path`) | the cache path lies outside the profile's hydration cache folder, or is not a valid local path; refused before anything is queued |
| `outside_scope` | create, mkdir, rename (both ends), open_write_begin | path outside the profile's sync_path set |
| `busy` | dehydrate, replace-rename | an upload is in flight on that path |
| `excluded` | completed event | keep-local name, never uploaded |
| `cancelled` | completed event | upload aborted by hydration.cancel |
| `invalid_path` | every verb with a path | a `.` or `..` segment, a control character, an empty segment other than that of a leading or trailing slash, or a segment over 255 UTF-16 units; a new name the host's file system cannot hold (create, mkdir, rename); a path whose cache file would lie outside the profile's hydration cache folder. Refused before anything is changed |
| `unknown_verb` / `missing_verb` | any | request-level refusal (startup-safe) |

## 8. Who may write the mounted folder

> **Superseded in part (2026-10-05), see [`docs/adr/independent-profiles.md`](../../adr/independent-profiles.md) and #560.** The single-writer rule below is the target for a mount profile. Today it does not hold in full: since #478 the daemon also writes the mount's files into the profile's `sync_root`, and since #510 it uploads what appears there; this stays until the #560 cutover. Under the decision, a mirror of the same account is a separate profile, an independent cloud-side writer whose changes reach the view only through enumeration. The verbs and events in this document are unchanged.

While a mount runs, the mount's co-daemon is the single writer of the
profile's view: the daemon serves reads from the hydration cache and writes
from the co-daemon's verbs, and direct cloud edits reach the view only
through enumeration (`refresh.run`, `sync.enumerate`, or the poll). A second
`unidrive sync` on the same profile is excluded by the process lock. A client
that cannot reach the daemon must never write user content into the sync
folder from its own authority — it would desync from the rows.

## 9. Contract tests

- Golden request/reply corpus: `core/app/cli/src/test/resources/ipc-contract/<verb>.ndjson`,
  replayed by `IpcContractCorpusTest` (one fixture per registered verb, both
  sides key-order-insensitive).
- Sequences (this document §3), at the SPI level against a real
  `HydrationImpl` + engine: the full write sequence and the safe-save
  replace-rename sequence in `HydrationUploadQueueTest`; scope refusals and
  the keep-local excluded sequence in `HydrationScopeAndExclusionTest`;
  cancel, progress, and replay sequences in `HydrationUploadProgressAndCancelTest`
  and `HydrationUploadQueueTest`; the recovery-<n> handle contract in
  `HydrationImplTest`.
- Startup liveness: `DaemonRuntimeTest.verbs_sent_as_soon_as_the_socket_appears_all_get_a_reply`.
- Path validation (`invalid_path`): the boundary checks in `HydrationIpcHandlerPathValidationTest`; the
  cache-folder containment of the verbs in `HydrationPathContainmentTest`, `ResolveCachePathContainmentTest`
  and `CachePathsTest`.
