# Internxt drive-desktop boosters analysis

Mining `C:\Users\gerno\dev\drive-desktop\` (official Internxt TypeScript/Electron client) for performance optimisations not yet adopted by `core/providers/internxt/` + `core/app/sync/`.

Cross-reference key:

- **Closed** = already in `CLOSED.md` and visibly wired in our tree.
- **Deferred** = explicitly post-MVP per `BACKLOG.md`.
- **New** = candidates below.

Tier legend:

- **High** — measurable wall-clock or politeness win, low risk.
- **Medium** — efficiency / smaller wins, or risk gated by structural rework.
- **Low** — defensive / UX polish.

---

## Top-line gaps

| # | Booster | Tier | Area |
|---|---|---|---|
| 1 | `limit=1000` on `/files` & `/folders` (we use 50) | **High** | Change detection |
| 2 | `sort=updatedAt` on delta scan (we use uuid) | **High** | Change detection |
| 3 | `status=EXISTS` on first scan (we always use ALL) | **High** | Change detection |
| 4 | Built-in temp-file filter (`.tmp/.swp/.crdownload/~$/.dotfiles`) | High | Upload |
| 5 | Drift recovery sync (paginate `sort=uuid`, sleep 30s between pages) | High | Other (correctness drift) |
| 6 | `waitUntilReady` pre-upload Windows-lock poll | Medium (Linux N/A but file-grow loop is portable) | Upload |
| 7 | Re-stat during upload `progressCallback` → abort on size drift | Medium | Upload |
| 8 | Bottleneck JOB PRIORITIES for POST /files (9) / POST /folders (8) | Medium | Concurrency/throttle |
| 9 | Hydration single-flight (`maxConcurrent: 1` on local placeholder hydrate) | Medium | Download |
| 10 | Parallel placeholder/stat walk (`pLimit(20)`) for local enumeration | Medium | Other |
| 11 | Infinite retry on 5xx + NETWORK (we cap) — match drive-desktop politeness | Medium | Concurrency/throttle |
| 12 | INGRESSCOOKIE session-affinity capture on socket.io | Low | Other |
| 13 | Periodic scheduled re-sync (10-min wall-clock floor) | Low (we have adaptive poll already) | Other |
| 14 | Thumbnail upload pipeline | Low (out of scope) | Upload |
| 15 | Bridge download `useProxy: false` parameter | Low (semantic NOP in our path) | Download |

---

## Change detection

### 1. Page size: 1000 vs 50  &mdash; **High**

| | Detail |
|---|---|
| What | drive-desktop fetches `/files` and `/folders` with `limit=1000` in `syncRemoteFiles.ts:26` (file: `src/apps/main/remote-sync/files/sync-remote-files.ts:25-32`) and `syncRemoteFolders.ts:26`. Constant `FETCH_LIMIT_1000 = 1000` in `src/apps/main/remote-sync/store.ts:14`. Recovery-sync also uses 1000 (`src/backend/features/sync/recovery-sync/files/files-recovery-sync.ts:26`). |
| Why | At the user-reported 28&ndash;52 s per-page latency, 1000-item pages mean **20&times; fewer round-trips** to walk the same account. For the 63k-item profile that's the difference between &asymp;1300 calls and &asymp;65 calls. Even with our 2-wide speculative fan-out the wall-clock saving is roughly &times;20. |
| What we do | `core/providers/internxt/src/main/kotlin/.../InternxtConfig.kt:40` &mdash; `LISTING_PAGE_SIZE: Int = 50`. Threaded through `InternxtApiService.listFiles` / `listFolders` / `delta()`. Comment claims "no public maximum documented" &mdash; this audit refutes it. |
| Cost | **Small.** One constant bump. Memory: 1000 InternxtFile rows &asymp; 200&ndash;400 KiB per page &mdash; well under any reasonable cap; speculativeFetchPages already keeps 2 in flight so peak buffer is 4000 rows / &asymp;1.5 MiB. The streaming reconciliation buffer's per-page slice grows proportionally; backpressure channel capacity (4 pages) may need a re-tune. JSON-decode time grows linearly &mdash; not a concern. |
| Risk | Gateway may have an undocumented soft cap; drive-desktop's own production traffic confirms 1000 works. Add a fallback step-down (try 1000; on a 400/422 retry once at 500) if you want belt-and-braces. |
| Suggested rollout | Bump `LISTING_PAGE_SIZE` to 1000. Optional env-var escape hatch (`INTERNXT_LISTING_PAGE_SIZE`) if a gateway change forces a downgrade. |

### 2. Sort by `updatedAt` (not `uuid`) on delta scan &mdash; **High**

| | Detail |
|---|---|
| What | drive-desktop delta path uses `sort: 'updatedAt'` (`sync-remote-files.ts:30`, `sync-remote-folders.ts:30`). Recovery path uses `sort: 'uuid'` (`files-recovery-sync.ts:29`). Our path uses `sort: 'uuid'` everywhere (`InternxtApiService.kt:69`). |
| Why | Sort by `updatedAt ASC` lets the checkpoint cursor advance after every page &mdash; the last item's `updatedAt` is the next-page's lower bound. With `sort=uuid`, your cursor is bound to whichever timestamp the page's last *uuid* happens to land on, which can be arbitrarily old. Result: every restart re-walks more pages than necessary, and a hot account never reaches "caught up". On uuid sort, the `max(updatedAt)` we currently compute across pages (`advanceCursor`) is a best-effort floor that the engine has to defensively cap. Sort by `updatedAt` makes the cursor mathematically tight. |
| What we do | `InternxtApiService.kt:58-74` (`listingQueryParams`) hard-codes `sort=uuid` for *all* call sites. The "stable pagination across snapshots" comment in `InternxtProvider.kt:17` was correct for *recovery* sync (sort by uuid for a deterministic re-walk) but wrong for the *delta* scan (which wants newest-after-cursor). |
| Cost | **Small.** Add a `sortField: String` parameter to `listingQueryParams` (default "updatedAt"); have recovery callers pass `"uuid"`. ~10 LOC. |
| Risk | The current `parallelListing` speculative-pagination relies on offset-based deterministic ordering. With sort=updatedAt and items being mutated *during* the scan, an item could move between pages. Mitigated by Internxt's `updatedAt`-with-`offset` pagination model &mdash; the cursor moves forward only after a page lands, and the next page is anchored to the prior cursor's `updatedAt`. The bug edge is: a freshly-touched item near offset N could re-appear at offset M>N in the same walk. We'd see one item twice; the reconciler is idempotent on duplicates so this is a no-op. |

### 3. `status=EXISTS` on first scan, `status=ALL` only when delta &mdash; **High**

| | Detail |
|---|---|
| What | drive-desktop: `status: from ? 'ALL' : 'EXISTS'` (`sync-remote-files.ts:28`). First scan asks the server to filter out trashed/deleted items; delta scan wants everything to learn about deletions. |
| Why | On a fresh sync, the local DB has no tombstones &mdash; trashed/deleted items in the response are pure noise the client has to filter out (and CPU/memory to parse). `EXISTS` filtering server-side reduces the payload, the parse time, and the items-pushed-into-the-reconciler count by however many trashed items the account has accumulated. On a long-lived account this can be 20&ndash;40% of the listing. |
| What we do | We always pass `status=ALL` (defaulted in `listingQueryParams`). Resumable-scan and delta paths both pay the full tombstone tax. |
| Cost | **Small.** Plumb the choice from `InternxtProvider.delta()` based on `cursor == null`. One added parameter. |
| Risk | None &mdash; the delta walk still asks for `ALL` so soft-delete propagation still works. |

### 4. Per-event verification on watcher deletes (drive-desktop's atomic-save guard) &mdash; in our tree

Just noting for completeness: drive-desktop's `on-event.ts:31-41` re-checks `existsSync(path)` and `existsSync(dirname(path))` before treating a delete as a real delete &mdash; differentiates editor atomic-save (delete + recreate with different inode) from genuine removal, and dedup-against-parent-already-deleted. **We have this via LocalWatcher's 2000ms debounce** (`LocalWatcher.kt:163-175`) which collapses delete+create within the window. No new work.

### 5. Background recovery sync (separate paginated drift walk) &mdash; **High**

| | Detail |
|---|---|
| What | Separate background `recoverySync` that walks ALL files+folders with `sort=uuid, status=EXISTS`, joins with local SQLite by UUID, finds drift in either direction (`localUpdatedAt != remoteUpdatedAt` or local has rows the remote doesn't). Runs in parallel for files+folders. Sleeps 30s between pages to be polite. Source: `src/backend/features/sync/recovery-sync/files/files-recovery-sync.ts` + `common/recovery-sync.ts:7-29`. |
| Why | Catches the case where notifications were missed, a delta cursor advanced past a transient subtree error, or a 500 response on a single page dropped a known item set. drive-desktop is willing to spend O(catalog) bandwidth in the background to *converge* the local state &mdash; cheap politeness, no user wait. |
| What we do | **Not present.** We have a periodic `delta()` walk that uses the same cursor; any drift between cursor and reality requires `unidrive doctor` or `--reset`. The cross-session resumable scan partially compensates but only for forward-progress; backward repair is manual. |
| Cost | **Medium.** Add a `BackgroundDriftReconciler` that walks files+folders in 1000-item pages with 30s inter-page sleep, joins by remote_id against SQLite, queues `MoveLocal/DeleteLocal/DownloadContent` for the differences. Reuse existing reconciliation primitives. Trigger: hourly + on resume from a stale cursor (`getActiveScan()` returned older than `SCAN_CHECKPOINT_STALE_HOURS`). |
| Risk | Two concurrent walks (foreground delta + background drift) doubles read-side load on the Drive REST endpoint &mdash; manage via the existing `Priority.Background` lane (drift is background-priority by definition, will starve to foreground delta) and the 30s sleep. |
| Suggested tier | High because it fixes a class of "stuck/missing items" reports we currently surface to the user as `doctor` work. |

---

## Upload

### 6. Built-in temporary-file filter &mdash; **High**

| | Detail |
|---|---|
| What | drive-desktop's `src/apps/utils/isTemporalFile.ts` hard-codes: skip files where `basename` starts with `.` or `~$` (Office lock files) or `extname` in `{.tmp, .temp, .swp}`. Checked at the top of `createFile` (`src/backend/features/sync/actions/services/create-file.ts:22-26`) so the upload never starts. |
| Why | Editors create thousands of `.swp`/`.tmp`/`~$Doc.docx` files per session. Without a filter we upload them all, then watch them be deleted seconds later. On a multi-MB Office doc that's a wasted PUT + a wasted DELETE every save. Vim's `.swp` is a 10 MiB file that's recreated on every edit. |
| What we do | **Not present.** Our `LocalScanner` and `LocalWatcher` only consult user-supplied `exclude_patterns` from TOML. No defaults. A first-time user with `vim`/`Office`/`JetBrains` open in the sync root uploads megabytes of temp data. |
| Cost | **Small.** New `org.krost.unidrive.sync.TempFileFilter` (with companion `INSTANCE.matches(relPath: String): Boolean`) consulted by `LocalScanner.scan()` and the Upload-decision path. Default-on; opt-out via `[general] disable_temp_file_filter = true` in TOML for cases where someone *really* wants to sync `.swp` files. |
| Patterns | drive-desktop's list is a good baseline; extend with `.crdownload`, `.part`, `.partial`, `.unconfirmed`, `.lock`, `.~lock.*`, `.DS_Store`, `Thumbs.db`. Hidden-files (`.foo`) should NOT be filtered by default &mdash; too many legit users have `.config/` in their sync root. drive-desktop filters those; we should not. |
| Risk | None if the filter is built-in but disable-able. |

### 7. `waitUntilReady` pre-upload &mdash; **Medium**

| | Detail |
|---|---|
| What | `src/backend/features/sync/actions/services/wait-until-ready.ts:17-25` &mdash; before any upload, opens the file in read mode and closes it; loops up to 60s at 500ms intervals if `open()` fails. Catches "the watcher fired before Windows finished copying the file". |
| Why | Eliminates a class of race: `LocalWatcher` fires on `ENTRY_MODIFY` mid-write (especially on Windows but also Linux for slow USB), upload reads partial content, sync sees wrong bytes. drive-desktop's debounce + this poll loop catches the tail. |
| What we do | We have `LocalWatcher.kt:163-175` debounce-2000ms which catches the most common case (editor save = burst within 2s). We do not have the `open()`-as-readiness-test fallback. Mid-upload we *do* re-encrypt the file from scratch with `Files.newInputStream` so a partial read becomes a corrupted upload that downloads to garbage. |
| Cost | **Small.** New `WaitUntilReady.kt` helper called from `InternxtProvider.upload` before `Files.size`. 1 KB. |
| Risk | On Linux opening a file for read while it's being written usually succeeds &mdash; this is more a Windows-specific guard. **However**: the "wait for stable size" variant (mtime/size unchanged for 500ms) IS portable and catches the slow-copy case on all OSes. Closer to "wait until size stops growing" than `open()`-test. |
| Suggested rollout | Implement the "stable size" variant: read size at T, sleep 250 ms, read again, retry until two reads agree or 30 s elapsed. Skip for files with stable mtime > 5s old. |

### 8. Mid-upload size-drift detector &mdash; **Medium**

| | Detail |
|---|---|
| What | drive-desktop's `upload-file.ts:28-38` runs `fileSystem.stat()` inside the `progressCallback` and aborts the upload via `abortController.abort()` if the file size changed during the transfer. Pairs with the encrypt-from-stream pipeline. |
| Why | Catches "user saved over the file mid-upload". Without it, the upload completes with the *original* size and *new* (truncated/garbage) bytes, the server finalises a corrupt file, and the next sync cycle sees nothing changed (same mtime/size lock-in from the upload row). drive-desktop aborts and lets the watcher debounce re-queue the next attempt. |
| What we do | **Not present.** We pre-compute size + mtime once at `InternxtProvider.upload:413-414` and stream the file through the cipher. If size changes mid-stream we silently produce N bytes of garbage (where N = whatever the cipher emitted before EOF). No abort signal. |
| Cost | **Medium.** Restructure `InternxtProvider.upload` to: (a) accept an `AbortController` (already plumbed via coroutine cancellation), (b) re-stat at each cipher-buffer fill (every 8192 bytes), (c) cancel the coroutine if size drifts. Or simpler: re-stat at upload finalise, before `commitWithRetry` &mdash; if the size doesn't match what we encrypted, throw and let the engine re-upload. |
| Risk | Cipher streaming + abort needs careful temp-file cleanup; tombstone path already handles this. |

### 9. Bottleneck job priorities for POST /files (9) / POST /folders (8) &mdash; **Medium**

| | Detail |
|---|---|
| What | `src/apps/shared/HttpClient/schedule-fetch.ts:6-9` defines priorities `{POST /files: 9, POST /folders: 8}` against a default of 5. Higher number = served first when budget is contended. |
| Why | When the global concurrency budget is saturated by a background scan's GETs, a user-initiated upload's "create the row" call jumps the queue. Maps directly to drive-desktop's "user-driven traffic preempts scan" policy. |
| What we do | We have `Priority.Foreground` / `Background` lanes (a coarser version of this &mdash; binary instead of 5-9-8-other). The engine wraps Pass 1 / transfer launches in `Priority.Foreground` (`SyncEngine.kt:872, 920`). createFile/createFolder both currently inherit whatever priority the engine sets &mdash; so a user-driven upload already gets `Foreground`. **Within Foreground**, drive-desktop further prioritises createFile (9) over createFolder (8). |
| Cost | **Small.** Add an optional `priority: Int` override on the budget. Two call sites (createFile, createFolder) pass it. |
| Risk | Marginal value over what we already have. Probably not worth the API-surface churn unless we have a concrete bottleneck. |
| Suggested tier | Medium-low. Defer until we see a foreground-contention symptom. |

---

## Download

### 10. Hydration single-flight (Bottleneck `maxConcurrent: 1`) &mdash; **Medium (Linux-only when VFS lands)**

| | Detail |
|---|---|
| What | `src/apps/sync-engine/callbacks/handle-hydrate.ts:6` &mdash; `const limiter = new Bottleneck({ maxConcurrent: 1 })` wraps every `Addon.hydrateFile` call. Concurrent hydrates serialised at the OS placeholder layer. |
| Why | Decrypting concurrent downloads on the same CPU can saturate the AES core; more importantly, the Windows Cloud Files API doesn't enjoy concurrent placeholder writes. drive-desktop serialises &mdash; one hydrate at a time means the decrypt CPU and the OS placeholder transitions don't fight. |
| What we do | We don't have a placeholder/VFS layer (deferred post-MVP per BACKLOG). Our `transferSemaphore` (`SyncEngine.kt:710-711`) is `maxConcurrentTransfers=4` for *all* concurrent downloads including non-placeholder hydration. The semaphore is correct for the current "downloads write to real files" model. |
| Cost | N/A until VFS lands. Note for future: when the FUSE / placeholder work starts, sequentialise the hydrate path. |
| Suggested tier | Low / deferred. Listed only so the future VFS work doesn't reinvent the wheel. |

### 11. Bridge download `useProxy: false` &mdash; **Low (semantic NOP for us)**

| | Detail |
|---|---|
| What | `contents-downloader.ts:48-49` passes `useProxy: false, chunkSize: 256 * 1024` to the inxt-js download path. |
| Why | The inxt-js library has an opt-in proxy mode that adds a Cloudflare/CDN hop for environments where direct CDN access is blocked; `false` is the default for desktop and explicitly forces the direct path. |
| What we do | We don't use inxt-js; we issue the bridge GET ourselves and stream the CDN URL directly via Ktor. There is no proxy mode to opt out of. Semantic match. **No work needed.** |

---

## Concurrency / throttle

### 12. Infinite retry on 5xx + NETWORK &mdash; **Medium**

| | Detail |
|---|---|
| What | `src/infra/drive-server-wip/in/client-wrapper.service.ts:65-79` &mdash; `SERVER` errors retry **without a max-retry cap**, only exponential backoff (`sleepMs * 2`). `NETWORK` errors get the same treatment (line 88-101). The MAX_RETRIES=3 only applies to the `UNKNOWN` classification. |
| Why | A multi-hour Cloudflare outage doesn't break sync &mdash; the daemon waits it out. Our 3-retry cap surfaces a permanent error to the user when the gateway is in fact "back in 5 minutes". |
| What we do | `HttpRetryBudget` (`core/app/core/src/main/kotlin/.../http/HttpRetryBudget.kt`) caps at `maxRetries` (configurable; default 5 per docs). After exhaustion, the operation fails and the engine moves on. The cursor doesn't advance. The next sync cycle (60s later by default) retries. |
| Cost | **Medium.** Either: (a) raise the cap to effectively infinite (e.g. `maxRetries = Int.MAX_VALUE` for SERVER/NETWORK errors only); or (b) add a "deferred" return path where the action queues for the next sync cycle without surfacing a hard failure. (b) is closer to what we already do at the engine level, just with the retries-then-defer boundary in a different place. |
| Trade-off | Drive-desktop's infinite retry means a daemon can sit in a tight loop on a permanent server bug. Our cap is more defensive but louder. Compromise: 60+ retries (effectively an hour at 60s spacing) before surfacing, matching the user's tolerance for a single sync cycle. |
| Suggested tier | Medium. Re-tune `maxRetries` upward; orthogonal to changes 1-3 above. |

### 13. Per-host priority &mdash; **already wired**

Note: drive-desktop has *two* bottlenecks (DriveApi at 2/500ms, Upload at 4/0ms). We mirror this in `InternxtApiService.kt:44-47` (closed). Match confirmed; no new work.

### 14. `Bottleneck.stop({dropWaitingJobs: true})` on logout &mdash; **Low**

drive-desktop's `src/apps/main/auth/logout.ts:23-25` explicitly drains/stops the bottleneck on logout. We rely on coroutine cancellation through the `abortController`. Functionally equivalent; semantic NOP.

---

## Auth

Closed items:

- JWT pre-expiry refresh margin (24h) &mdash; CLOSED (`AuthService.kt:57`).
- 401 refresh-and-replay &mdash; CLOSED.
- NonCancellable refresh wrap &mdash; CLOSED.

Nothing new to mine from drive-desktop's auth path.

### 15. INGRESSCOOKIE session-affinity capture on socket.io &mdash; **Low**

| | Detail |
|---|---|
| What | `src/apps/main/realtime.ts:24-46` &mdash; on socket.io `pollComplete`, sniffs `set-cookie` for `INGRESSCOOKIE=`, stores it in `socket.io.opts.extraHeaders.cookie` for subsequent requests. Ensures all WS frames in a session hit the same backend pod behind the load balancer. |
| Why | When the LB rotates pods (deploy, scale event), the new pod doesn't have the user's subscription context. The session-affinity cookie pins to the original pod's subscription. Without it, every LB rebalance loses the WS subscription until socket.io's auto-reconnect retries the handshake (more wake-signal misses, more polled deltas). |
| What we do | `NotificationsClient.kt:86-95` sets `transports=[websocket]`, but doesn't sniff/store `INGRESSCOOKIE`. Each reconnect lands on whichever pod the LB happens to route to. |
| Cost | **Medium.** The socket.io-client Java library (we use `io.socket:socket.io-client:2.1.2`) doesn't expose `pollComplete` the same way as the JS client; we'd need a custom WebSocketProvider or HTTP-poll fallback to intercept the cookie. Investigation cost > implementation cost. |
| Risk | Could increase WS reconnect frequency invisibly to the user (we'd still get the wake hint on reconnect). Symptom would be more `Internxt notifications WS connect_error` lines in the log. |
| Suggested tier | Low. Only worth doing if we observe frequent reconnects in production. Document as "investigate if WS flap is reported". |

---

## Caching

### 16. In-memory `database` snapshot built per sync cycle &mdash; **Already aligned**

`src/apps/sync-engine/refresh-item-placeholders.ts:42-49` loads ALL DB files+folders into memory once per cycle. We do the equivalent via `db.getAllEntries()` in `LocalScanner.scan()` (`LocalScanner.kt:36`). No new work.

### 17. `pLimit(20)` for parallel stat+placeholder reads &mdash; **Medium**

| | Detail |
|---|---|
| What | `src/backend/features/remote-sync/sync-items-by-checkpoint/load-in-memory-paths.ts:24` &mdash; walks the entire sync root with `pLimit(20)` so 20 parallel `stat` + placeholder reads happen concurrently. Each entry is a stat() syscall plus a `NodeWin.getFileInfo` placeholder lookup. The walk is iterative (BFS via stack); only the stat-per-entry is parallelised. |
| Why | On a 67k-file local sync root, sequential `Files.walkFileTree` blocks on one stat at a time. SSD: ~5&micro;s per stat = 330 ms total. Spinning disk: ~5 ms per stat = 5+ minutes. Parallelism of 20 brings the spinning-disk case down to ~15 s. |
| What we do | `LocalScanner.kt:57` &mdash; `Files.walkFileTree` is sequential. Per-file body inside the visitor is small (one DB lookup against an already-loaded map). Bottleneck is filesystem-level stat parallelism. |
| Cost | **Medium.** Replace `Files.walkFileTree` with a coroutine-based walk that fans out 20-way on the IO dispatcher. Care needed: `visitFile` writes UD-901 pending-upload rows inside `db.beginBatch()` (`LocalScanner.kt:52`); the SQLite batch is single-thread-only. Refactor: walk + stat in parallel, collect into a list, then drain into the DB serially. |
| Risk | The user's sync root is on `/home`; for SSDs the saving is single-digit seconds. The pure-spinning-disk user is the win case. Not a primary win for our user base. |
| Suggested tier | Medium. Move when LocalScanner becomes a hot path under heartbeat load. |

---

## Other

### 18. 10-minute scheduled re-sync floor &mdash; **Low (we already have this)**

drive-desktop schedules `setInterval(updateRemoteSync, 10 * 60 * 1000)` (`schedule-sync.ts:8-14`). Our adaptive poll (`AdaptiveInterval.kt`) tops out at `maxPollInterval=300` (5 minutes). We're already more aggressive. **No work.**

### 19. Thumbnail upload &mdash; **Low / out of scope**

drive-desktop generates 300&times;300 PNG thumbnails for images, PDFs, videos and posts them via `POST /files/thumbnail` after the main upload (`create-and-upload-thumbnail.ts:85-106`). Fire-and-forget (errors logged but don't fail the upload).

We don't have a thumbnail pipeline. Adding it requires: (a) Java image decoders (JAI / TwelveMonkeys for PDF, FFmpeg for video) &mdash; massive dependency surface; (b) wiring an idempotency check so retries don't double-thumbnail. The benefit is only visible inside Internxt's web/mobile UI &mdash; the unidrive CLI never views thumbnails. **Skip.**

### 20. Watcher event verification (atomic-save sniff) &mdash; **already covered**

drive-desktop's `on-event.ts:31-41` re-checks file existence after the debounce fires:

- delete event with `existsSync(path)==true` &rarr; this was an atomic save (editor wrote `.swp`, renamed over original); skip the delete.
- delete event with `existsSync(dirname)==false` &rarr; parent already deleted; skip (the cascade fires at the root).

Our `LocalWatcher` debounces every event by 2000ms (`LocalWatcher.kt:163-175`) which collapses delete+create within the window. Editor saves that emit `delete + create + modify` get coalesced to one trailing-edge event; engine re-stats on dequeue. **Functionally equivalent without the explicit `existsSync` checks &mdash; the debounce-then-re-stat pattern delivers the same correctness.** No new work.

### 21. `INGRESSCOOKIE` &mdash; see Auth #15.

---

## Refinements to existing wires

These are areas where we already implemented the booster but a closer look at drive-desktop suggests a tweak:

### R-1. `chunkSize: 256 * 1024` on download &mdash; we match

drive-desktop uses 256 KiB for streamed-decrypt buffers (`contents-downloader.ts:50`). We use 256 KiB too (`InternxtApiService.kt:600`). Match confirmed.

### R-2. Upload bottleneck = 4 concurrent &mdash; we match

drive-desktop: `new Bottleneck({ maxConcurrent: 4 })` (`auth/handlers.ts:80`). Our `transferSemaphore = Semaphore(4)` (default via `ProviderMetadata.maxConcurrentTransfers=4`, `SyncEngine.kt:710`). Match.

### R-3. 5_000ms initial backoff &mdash; we use a tighter ladder

drive-desktop: `sleepMs = 5_000` initial, doubling (`client-wrapper.service.ts:40`). Our `retryOnTransient` ladder starts at 2_000ms. Faster recovery on transient blips, slightly more aggressive at the moment of a real outage. Trade-off accepted; no change recommended.

### R-4. 2-minute checkpoint rewind &mdash; we use 6 hours

drive-desktop: `TWO_MINUTES_IN_MILLISECONDS` rewind on checkpoint read (`get-checkpoint.ts:19`). Our staleness threshold is `SCAN_CHECKPOINT_STALE_HOURS = 6` for the resumable-scan offset and the active cursor lookback is whatever's stored (no rewind). **drive-desktop is more defensive** &mdash; they rewind 2 minutes every checkpoint read to recover from clock-skew/stale items. Worth considering: a 2-minute cursor rewind on `delta()` start would catch the "item updated 30 seconds before cursor advanced to that timestamp" race. 30+ second tolerance is well within Internxt's `updatedAt` granularity. **Suggested refinement: add `CURSOR_REWIND_MS = 120_000L` to `InternxtProvider.delta()` &mdash; subtract 120s from the cursor on every read.** Low-cost defence.

### R-5. Recovery sync 30s inter-page sleep &mdash; we don't have a recovery sync

See booster #5. The 30s sleep is what makes the background walk polite enough to not interfere with foreground delta. Adopt as part of #5.

### R-6. Loopback-elision on notifications &mdash; we match

drive-desktop drops self-originating events (`process-web-socket-event.ts:9-12`). We do too (`NotificationsClient.kt:142-148`). Match.

### R-7. Targeted notification payloads (FILE_CREATED, FILE_UPDATED, FOLDER_CREATED, FOLDER_UPDATED, ITEMS_TO_TRASH) &mdash; not used by drive-desktop either

drive-desktop's `processWebSocketEvent` (`process-web-socket-event.ts:17-20`) treats ALL non-self events as a coarse "walk the delta" wake hint and ignores the payload entirely. We do the same in our `NotificationsClient`. Both clients leave the targeted-update strategy on the table. Could be a future win: trigger a targeted `getFileMeta(uuid)` / `getFolderContents(uuid)` for `FILE_UPDATED` / `FOLDER_UPDATED` payloads instead of a full delta walk. Skipping for now per the "as drive-desktop does" boundary.

---

## Summary by tier

| Tier | Item | Rationale |
|---|---|---|
| **High** | #1 page size 50 &rarr; 1000 | 20&times; fewer pages = direct wall-clock win |
| **High** | #2 sort=updatedAt on delta | Cursor convergence; less re-walk on restart |
| **High** | #3 status=EXISTS on first scan | Smaller payload, less reconciler work |
| **High** | #4 built-in temp-file filter | Avoid wasted PUTs of editor `.swp` etc. |
| **High** | #5 background drift recovery sync | Catches missed-notification drift without user action |
| **Medium** | #6 wait-for-stable-size pre-upload | Catches mid-write watcher fire |
| **Medium** | #7 mid-upload size-drift abort | Prevents corrupt-upload-then-silent-success |
| **Medium** | #11 raise/remove retry cap on 5xx+NETWORK | Survive multi-hour Cloudflare outage |
| **Medium** | R-4 cursor rewind 120s on delta start | Defence against the "updatedAt arrived 30s before cursor advanced" race |
| **Medium** | #17 parallel stat in LocalScanner | Win for spinning-disk users |
| **Low** | #8 finer per-endpoint priority | Marginal benefit over our Foreground/Background lanes |
| **Low** | #10 hydration single-flight | N/A until VFS lands |
| **Low** | #15 INGRESSCOOKIE | Only if WS flap reported |
| **Low** | #19 thumbnails | Out of scope &mdash; needs heavy deps for CLI-only client |

---

## What's already aligned and needs no work

| Drive-desktop feature | Our equivalent |
|---|---|
| Drive REST 2-concurrent / 500ms bottleneck | `HttpRetryBudget` Drive REST 2/500ms (closed) |
| Bridge upload 4-concurrent / 0ms bottleneck | `HttpRetryBudget` Bridge 4/0ms (closed) |
| Foreground/background lane | `Priority` element + dual-lane in HttpRetryBudget (closed) |
| In-flight dedup on reads | `InFlightDedup` on getFolderContents/getFileMeta/listFiles/listFolders (closed) |
| 401 refresh-and-replay | `withAuthRetry` on every Drive REST verb (closed) |
| 24h JWT pre-expiry refresh | `JWT_REFRESH_MARGIN_MS = 24h` (closed) |
| 2000ms watcher debounce | `LocalWatcher` debounce 2000ms (closed) |
| 256 KiB download chunk | `InternxtApiService.kt:600` (matches) |
| 4-concurrent upload | `transferSemaphore=4` (matches) |
| Server-trash via POST /storage/trash/add | `trashItems` (closed) |
| Retry-After header parsing | `parseRetryAfterHeader` (closed) |
| finishUpload 409 reconcile | `commitWithRetry` (closed) |
| Resumable scan checkpoint | `scan_staging` table + cross-session resume (closed) |
| WebSocket NOTIFICATIONS_URL wake-signal | `NotificationsClient` (closed) |
| keep_overwritten rename-and-keep | `tryKeepOverwrittenRename` (closed) |
| Best-effort cursor advance | `promotePendingCursor` (closed) |

---

## Deferred (per BACKLOG)

| Item | Status |
|---|---|
| Multipart upload (constants exist) | Deferred &mdash; no smoke test exercises >shard-size files |
| Shares API | Deferred &mdash; not in MVP smoke surface |
| Virtual filesystem layer (FUSE) | Deferred &mdash; out of MVP scope per ADR-0012 |
| Local-modified-while-TRASHED merge | Deferred &mdash; v1 is last-write-wins |
| Tombstone retention policy | Deferred &mdash; until growth becomes visible |

---

## Files referenced

Upstream (drive-desktop, `C:\Users\gerno\dev\drive-desktop\`):

- `src/apps/shared/HttpClient/client.ts` (bottleneck setup, priorities)
- `src/apps/shared/HttpClient/schedule-fetch.ts` (per-endpoint priorities)
- `src/apps/main/auth/handlers.ts` (upload bottleneck)
- `src/apps/main/realtime.ts` (socket.io + INGRESSCOOKIE)
- `src/apps/main/notification-schema.ts` (event payloads)
- `src/apps/main/remote-sync/store.ts` (FETCH_LIMIT_1000)
- `src/apps/main/remote-sync/files/sync-remote-files.ts` (delta scan loop)
- `src/apps/main/remote-sync/folders/sync-remote-folders.ts`
- `src/apps/main/remote-sync/RemoteSyncManager.ts` (parallel files+folders)
- `src/apps/main/background-processes/sync-engine/services/schedule-sync.ts` (10-min sched)
- `src/apps/sync-engine/callbacks/handle-hydrate.ts` (single-flight hydrate)
- `src/apps/sync-engine/refresh-item-placeholders.ts` (pLimit(20) walk)
- `src/apps/utils/isTemporalFile.ts` (temp filter)
- `src/backend/features/sync/actions/services/upload-file.ts`
- `src/backend/features/sync/actions/services/create-file.ts` (temp-skip)
- `src/backend/features/sync/actions/services/wait-until-ready.ts` (60s ready loop)
- `src/backend/features/sync/recovery-sync/common/recovery-sync.ts` (background drift)
- `src/backend/features/sync/recovery-sync/files/files-recovery-sync.ts`
- `src/backend/features/sync/recovery-sync/common/is-item-to-sync.ts` (drift detection)
- `src/backend/features/remote-sync/sync-items-by-checkpoint/get-checkpoint.ts` (2-min rewind)
- `src/backend/features/remote-sync/sync-items-by-checkpoint/load-in-memory-paths.ts` (pLimit(20))
- `src/backend/features/remote-notifications/in/process-web-socket-event.ts`
- `src/infra/drive-server-wip/in/client-wrapper.service.ts` (retry policy, MAX_RETRIES)
- `src/infra/drive-server-wip/in/helpers/error-helpers.ts` (5xx + NETWORK classification)
- `src/infra/drive-server-wip/in/get-in-flight-request.ts` (dedup)
- `src/infra/drive-server-wip/services/files/check-existence.ts` (batched existence)
- `src/infra/inxt-js/file-uploader/upload-file.ts` (size-drift abort)
- `src/infra/inxt-js/file-uploader/process-error.ts` (409/500-only retry shape)
- `src/infra/inxt-js/contents-downloader/contents-downloader.ts` (256K chunk, useProxy=false)
- `src/infra/file-system/services/stat-readdir.ts` (sym-link symlink note)
- `packages/core/src/backend/features/sync/index.tsx` (MAX_FILE_SIZE 40 GiB)

Ours (unidrive-evolve, `C:\Users\gerno\dev\git\unidrive-evolve\`):

- `core/providers/internxt/src/main/kotlin/.../InternxtConfig.kt` (LISTING_PAGE_SIZE)
- `core/providers/internxt/src/main/kotlin/.../InternxtApiService.kt` (listingQueryParams, bottlenecks, dedup)
- `core/providers/internxt/src/main/kotlin/.../InternxtProvider.kt` (delta, upload, speculativeFetchPages)
- `core/providers/internxt/src/main/kotlin/.../AuthService.kt`
- `core/providers/internxt/src/main/kotlin/.../NotificationsClient.kt`
- `core/app/sync/src/main/kotlin/.../LocalScanner.kt`
- `core/app/sync/src/main/kotlin/.../LocalWatcher.kt`
- `core/app/sync/src/main/kotlin/.../SyncEngine.kt`
- `core/app/sync/src/main/kotlin/.../AdaptiveInterval.kt`
- `core/app/core/src/main/kotlin/.../http/HttpRetryBudget.kt`
- `core/app/core/src/main/kotlin/.../http/InFlightDedup.kt`
