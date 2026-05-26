# Live-sync prior art — how real cloud clients keep a mount fresh

> Research brief. Scope: how production cloud-storage clients deliver an "always-fresh"
> on-demand view, and what that implies for unidrive's strictly-reactive FUSE daemon.
> Goals, in priority order: **correctness → robustness → efficiency**.
> Date: 2026-05-26. No code was modified.

## 0. The unidrive problem in one paragraph

unidrive's per-profile JVM daemon owns `state.db` (the view) and talks to providers
(Internxt, OneDrive). A Rust FUSE co-daemon mounts the profile and serves placeholders
that hydrate-on-read into a cache. The daemon is **strictly reactive** (spec G3/NG5):
it only updates `state.db` when an operator runs `refresh`, so a rename/delete done on
the cloud web UI is invisible in the mount until the next manual refresh
(`BACKLOG.md`, "Daemon doesn't auto-poll…"). We want the mount to reflect remote
changes within seconds, correctly, without mass-delete accidents (the UD-265 deletion
safeguard already fired at 77% on the 195k-file Internxt profile). The whole industry
has solved a near-identical problem; this brief extracts the dominant pattern.

---

## 1. Per-system findings

### 1.1 rclone (`rclone mount` + VFS) — closest analogue

| Aspect | How rclone does it |
|---|---|
| Change detection | Two layers. (a) **Polling/ChangeNotify** per backend — backends implement a `ChangeNotify(fn, pollIntervalChan)` method. OneDrive, Google Drive, Dropbox, Box implement it (OneDrive/Drive via a server *delta/changes* cursor polled at the interval; Dropbox via longpoll). S3, local, SFTP, WebDAV do **not** — they have no change feed. (b) **Dir-cache TTL expiry** — the fallback for backends without polling. |
| View freshness | `--dir-cache-time` (default **5m**) = how long a directory listing is trusted. `--poll-interval` (default **1m**, must be `< dir-cache-time`; `0` disables) = how often a polling backend is asked for changes. On a change, rclone **invalidates the whole affected directory's cache** rather than patching individual entries — next `readdir` re-lists that dir. Backends without polling only refresh when the dir-cache TTL expires. |
| On-demand / placeholder | `--vfs-cache-mode` = `off` / `minimal` / `writes` / `full`. `full` uses sparse files and supports range reads (partial hydration of the file region accessed). Reads come from cache; misses fetch from remote. |
| Conflict / deletion safety | rclone mount is a *view*, not a two-way reconciler, so there is no mass-delete-on-empty class of bug. `rclone sync` (the bidirectional command) is separate and has `--max-delete` / `--max-delete-size` guards plus `--backup-dir`. |
| Pros | Clean split between "keep the view fresh" (mount/VFS) and "reconcile two replicas" (sync). Polling-where-available + TTL-fallback is robust and provider-agnostic. Range hydration in `full` mode. |
| Cons | Cache invalidation is **whole-directory**, not per-entry — a single change re-lists the dir. No webhook/push (interval-bound latency). |

Sources: <https://rclone.org/commands/rclone_mount/>, <https://rclone.org/onedrive/>,
<https://rclone.org/commands/rclone_test_changenotify/>,
<https://forum.rclone.org/t/polling-and-change-notify/51941>.

### 1.2 Microsoft OneDrive — official sync + CfAPI + abraunegg Linux client

The OneDrive "scan at scale" guidance is the single most directly applicable primary
source for unidrive, because it is written for exactly our consumer class (sync engines,
backup tools) against drives with hundreds of thousands of items.

| Aspect | How OneDrive does it |
|---|---|
| Recommended pattern | **Discover → Crawl → Notify → Process changes.** (1) Discover the drive(s). (2) **Crawl** = one `…/root/delta` call with no token; page through `@odata.nextLink` to get the full tree. (3) Persist the final `@odata.deltaLink` token. (4) **Notify** via webhooks (`/subscriptions` on the drive, change type `update`). (5) On notification (or periodically) call delta with the saved token → only the changed `driveItem`s. |
| Change detection | **Pull = delta cursor**, **push = webhook**. "Delta query uses a pull model… change notifications use a push model." Webhooks "nearly eliminate the need to frequently poll." `token=latest` gives a "sync from now" cursor (skip the historical crawl). |
| Freshness / safety net | Even with webhooks, run a **periodic delta** to catch missed notifications — **"no more than once per day"** for that backstop. After subscribing, immediately run a delta with the last token so changes between crawl and subscribe aren't lost. |
| Efficiency | `$select` slims the payload (`id,cTag,size,file/hashes,…`). `cTag` tells you whether file *content* changed (skip re-download). Under heavy traffic, "call delta query at a reduced interval rather than after each change notification" (coalesce). |
| Throttling | 429/503 → honor `Retry-After`; it **grows over time** if you keep hammering; **pause ALL requests** (esp. multi-threaded) until it clears, or you extend the ban. Peak hours throttle harder. |
| Resync | A delta token can return **410 Gone** with `resyncChangesApplyDifferences` / `resyncChangesUploadDifferences` → restart the delta loop from the supplied `Location`. (unidrive backlog already tracks this.) |
| CfAPI (Windows placeholder model) | A kernel minifilter (`cldflt.sys`) proxies between apps and the sync engine. Three file states: **placeholder** (≈1 KB header), **full** (hydrated, evictable), **pinned full** (explicitly kept offline). Hydration policy = `max(app, provider)` of {Always-full > Full > Progressive > Partial}; default Progressive (range hydration). The sync engine's job is split: maintain the placeholder *namespace* in sync with cloud, vs. transfer *data* on hydrate callbacks. |
| abraunegg (Linux client) | `--monitor` mode. Local SQLite (`items.sqlite3`) stores the **delta token**. inotify watches local changes (with `inotify_delay`, default 5s debounce); `monitor_interval` polls `/delta`; `webhook_enabled` gives ~1s remote-change latency. Deletion safety: `use_recycle_bin`. (The official Windows/abraunegg engines additionally classify "big deletes" and refuse to propagate them without confirmation.) |

Sources: <https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/scan-guidance?view=odsp-graph-online>,
<https://learn.microsoft.com/en-us/graph/delta-query-overview>,
<https://learn.microsoft.com/en-us/graph/api/driveitem-delta?view=graph-rest-1.0>,
<https://learn.microsoft.com/en-us/windows/win32/cfapi/build-a-cloud-file-sync-engine>,
<https://github.com/abraunegg/onedrive/blob/master/docs/usage.md>.

### 1.3 Dropbox — Smart Sync / Project Infinite / Nucleus

| Aspect | How Dropbox does it |
|---|---|
| Change detection | `list_folder` → **cursor** (pointer to the folder at a point in time). `list_folder/continue` with the cursor returns only deltas. `list_folder/longpoll` **blocks** until a change (or timeout) — push-like without webhooks. Timeout up to **480s**, plus up to **90s random jitter** added server-side to avoid thundering herd. |
| Freshness | "Always update to the latest returned cursor — even if zero results" so the cursor doesn't expire. Cursor expiry → **409 reset** → re-`list_folder`. |
| Backoff | longpoll responses may carry a `backoff` field; 429 → honor `Retry-After`, else exponential backoff. |
| Sync engine (Nucleus) | Rewrite for correctness. **Globally-unique identifiers** for files/folders (path-independent → atomic moves, not delete+add). Strong invariant: server and client must agree on the remote tree before any mutation; **"any discrepancy is a bug"**, enabling deterministic fuzz testing. Implies a synced/last-known tree distinct from local and remote. |
| Online-only / Smart Sync | Placeholder files materialize on access; a kernel/filter component presents them as local. |

Sources: <https://developers.dropbox.com/detecting-changes-guide>,
<https://dropbox.tech/infrastructure/rewriting-the-heart-of-our-sync-engine>,
<https://dropbox.tech/infrastructure/going-deeper-with-project-infinite>.

### 1.4 Nextcloud / ownCloud desktop client — the mass-delete safeguard canon

| Aspect | How it does it |
|---|---|
| Source of truth | A **sync journal SQLite DB** stores per-file/per-directory state including **ETags**. Discovery walks local + remote and compares against the journal to classify each item (new/changed/deleted on which side). |
| Cheap change detection | **Per-directory ETag.** If a directory's ETag is unchanged vs. the journal, its subtree is skipped entirely — no deep enumeration. This is the "don't re-walk what didn't change" trick (analogous to a delta cursor, but client-derived). |
| Reconcile | CSync-style: discover → reconcile (decide propagation direction per node) → propagate. If the journal is deleted, it's **rebuilt by comparing files + mtimes** (re-discovery), not by mass-deleting. |
| **Mass-delete safeguard** | The canonical guard. When discovery finds that **all files in the sync folder were deleted on the server**, the client raises `aboutToRemoveAllFiles` and shows: *"All files in the sync folder were deleted on the server. Choose **keep** to re-upload to the server. Choose **remove** to delete them locally too."* Config key **`promptDeleteAllFiles`** disables it. The key insight: an "everything is gone" signal is treated as **suspicious** (likely a misconfig / unmounted dir / transient empty listing), not as an instruction — propagation is **gated on human confirmation**, defaulting to *keep*. |
| Virtual Files | Windows: CfAPI placeholders. Linux: VFS via a **suffix-placeholder** scheme (`.owncloud`/`.nextcloud` stub files) because Linux had no CfAPI equivalent. |
| Conflict | Keep-both: a conflicted copy is written with a conflict-marker filename; never silently overwrites. |

Sources: <https://docs.nextcloud.com/desktop/2.3/architecture.html>,
<https://github.com/owncloud/client/issues/5276>,
<https://central.owncloud.org/t/remove-all-files-dialog-on-windows-client/7620>,
<https://github.com/nextcloud/desktop/issues/9292>.

### 1.5 Google Drive for Desktop (DriveFS)

| Aspect | How it does it |
|---|---|
| Model | **Streaming** (on-demand, files in cloud, local cache) vs **Mirroring** (full local copy). Streaming is the placeholder/hydrate model. Local cache dir (`DriveFS`), size-bounded, evictable; pinned files stay offline. |
| Change detection | Server `changes`/delta feed (Drive API `changes.list` + page token, push channels available). Same delta-cursor + push shape as OneDrive/Dropbox. |
| Pros/Cons | Mature streaming UX; closed-source so internals inferred. Confirms the cross-vendor convergence on delta-cursor + bounded local cache + pin. |

Sources: <https://support.google.com/a/answer/7644837>, <https://support.google.com/a/answer/2490100>.

### 1.6 Seafile & Syncthing — contrast (not on-demand)

| System | Model | Relevance |
|---|---|---|
| **Seafile** | Git-like data model (Repo/Commit/FS/Block) with **content-defined chunking** (~8 MB avg blocks) for dedup + block-level transfer. A new commit = the change signal. | Full-replica sync, not on-demand placeholders. Useful only for the *content-hashing / block-dedup* idea on the hydration/upload path; not for view freshness. |
| **Syncthing** | Peer-to-peer; each device keeps an **index DB** and exchanges index updates + block lists; no central truth. | Peer model is the *opposite* of unidrive's "cloud is content-truth, DB is view-truth." Useful only as a contrast: without a central authority you need vector clocks / global IDs to order changes — more machinery than a single-provider delta cursor needs. |

Sources: <https://manual.seafile.com/latest/develop/data_model/>,
<https://forum.syncthing.net/t/content-defined-chunking/19629>.

### 1.7 Internxt drive-desktop — our own provider's official client

- Electron + per-workspace Node "sync engine worker" processes.
- **Windows**: virtual drive via **CfAPI** (`CldApi.lib`) through a C++ N-API addon — placeholders, hydrate callbacks, sync-root registration. Same model as everyone else.
- **Linux**: virtual drive via **FUSE (v2)**.
- **No real-time change feed**: sync workers poll. This matches unidrive's own finding that Internxt has no notifications and the `/files` listing's `status` field lags a web-UI trash by ≥60 min (`BACKLOG.md`, "Internxt deletions aren't promptly reaped"). **Conclusion for unidrive: Internxt = poll-only, and even polling has a deletion-visibility lag we must design around.**

Sources: <https://github.com/internxt/drive-desktop>, <https://github.com/internxt/drive-desktop-linux>,
<https://deepwiki.com/internxt/drive-desktop/3.1-file-synchronization>,
<https://help.internxt.com/en/articles/8448123-what-is-virtual-drive>.

### 1.8 Linux on-demand FS mechanisms (general)

| Mechanism | State | Fit for unidrive |
|---|---|---|
| **FUSE** | The only Linux primitive that makes "the file is there until you `open()`, then it hydrates" true for *every* `open(2)` caller. No native placeholder/state concept — the daemon synthesizes it. Cache invalidation done via `fuse_lowlevel_notify_inval_entry` / `notify_inval_inode`. | **The chosen path** (per the two existing `virtual-file-layer-linux-*.md` briefs). `FUSE_PASSTHROUGH` (6.9+) gives native-speed reads after hydration. |
| **fanotify `FAN_PRE_ACCESS`/`FAN_PRE_MODIFY` (pre-content) HSM** | Landed ~6.14 (used in production at Meta). Lets a *normal* filesystem hold files that an fanotify listener fills on first access — "appear local, hydrate on access" without FUSE. More self-contained (listener crash ≠ app crash), better perf for mostly-passthrough. | **Limitations rule it out as primary**: no directory placeholder/enumeration semantics (you still need a real FS namespace populated somehow), partial-range support is constrained, and writing content inside a permission-event handler can **deadlock with filesystem freeze**. It complements but does not replace FUSE for a *browsable cloud namespace*. Worth watching. |
| `cachefilesd` / EROFS-over-fscache | Kernel-side read caching for network FS. | Caching layer, not a namespace/freshness mechanism. |
| libprojfs (VFSForGit Linux) | **Archived 2024-07.** | Dead. |

Sources: <https://lwn.net/Articles/932415/>, <https://lwn.net/Articles/981392/>,
<https://www.phoronix.com/news/Linux-6.14-precontent-fanotify>,
<https://github.com/amir73il/fsnotify-utils/wiki/Hierarchical-Storage-Management-API>.

---

## 2. The extracted patterns

**P1 — Separate "refresh the view" from "reconcile a replica."** Every mature system
splits these. rclone: `mount`/VFS (view) vs `sync` (reconcile). OneDrive: delta-into-DB
(view) vs the bidirectional sync engine. ownCloud: discovery populates the journal
*before* any propagation decision. The view-refresh is **one-way remote→DB** and has no
authority to delete user data on either side.

**P2 — Delta cursor is the unit of efficiency.** Crawl once with an empty cursor, page
through, persist the token; thereafter ask "what changed since this token?" Full
re-enumeration is the exception (cursor lost / 410 Gone), not the steady state. ownCloud
achieves the same with per-directory ETags (skip unchanged subtrees).

**P3 — Push where available, poll always as backstop.** OneDrive webhooks / Dropbox
longpoll give seconds-latency; but everyone *also* runs a periodic delta as a
safety net for missed notifications (OneDrive: "no more than once per day" for the
backstop, faster when driven by push). Providers without a feed (Internxt, S3, SFTP)
are poll-only.

**P4 — Coalesce + back off.** Don't delta-per-notification under load; reduce the
interval and batch. Honor `Retry-After`; it escalates if ignored; pause *all* traffic
during a 429, don't just slow one thread.

**P5 — Invalidate at directory granularity.** rclone and the FUSE notify API both
operate per-dir/per-inode. A change → invalidate that directory's cached listing →
re-list lazily on next access. Cheap, correct, avoids patching individual rows.

**P6 — "Everything disappeared" is suspicious, not authoritative.** The ownCloud
`aboutToRemoveAllFiles` guard, OneDrive/abraunegg "big delete" classification, and
rclone `--max-delete` all encode: a delete count/percentage over a threshold is far
more likely a bug (unmounted dir, transient empty listing, auth scope change, provider
index lag) than a user's intent. Gate it; default to *keep*; require confirmation.

**P7 — Three logical trees.** local, remote, and synced/last-known. Reconciliation
compares all three to attribute a change to the correct side. Nucleus, ownCloud's
journal, and OneDrive's delta-DB all do this. For unidrive's *view* refresh we only
need two (remote ↔ DB), but the deletion-safety logic benefits from the "last-known"
notion.

**P8 — Range/partial hydration + bounded evictable cache + pin.** CfAPI (Progressive),
rclone (`full` sparse cache), DriveFS (streaming cache + pin). Hydrate only the bytes
read; evict by LRU/size; let the user pin to keep offline. unidrive's hydration cache
already matches this shape.

---

## 3. Source-of-truth model — confirmed

The brief's hypothesis is **correct and near-universal**:

> The local metadata DB is the truth for the **view**; the cloud is the truth for
> **content state**; a **one-way remote→DB enumeration** keeps the view fresh, kept
> **separate** from any two-way file reconciliation.

- rclone `mount` = remote→VFS-cache only; `rclone sync` = the separate two-way job.
- OneDrive scan-guidance literally tells sync/backup/indexer apps to delta-into-a-local-store and treats that as distinct from re-uploading.
- ownCloud's journal is the local truth-of-record for "what I last saw"; discovery refreshes it; propagation is a *separate phase* gated by safeguards.

For unidrive this means: **the live-sync feature is a remote→`state.db` enumerator
that NEVER deletes a local cache file or a remote object as a side effect of refreshing
the view.** It flips row status (e.g. `EXISTS`→`TRASHED`) and invalidates FUSE dir
caches; it does not touch user bytes. Two-way reconciliation (the `SyncEngine` /
tracking-set engine, where UD-265 lives) stays a separate, separately-guarded path.
This is exactly why the mount can be made fresh **without** re-arming the mass-delete
risk — the freshness path has no delete authority over content.

---

## 4. Synthesized live-sync design for unidrive

### 4.1 Change detection (per provider, capability-driven)

| Provider | Mechanism | Default |
|---|---|---|
| OneDrive | **Delta cursor poll**, persisted per profile. (Webhooks are the long-term upgrade but need a public callback endpoint — out of scope for a local daemon initially.) | Poll every **30–60s**; backstop full delta (`token` reset) **daily**. |
| Internxt | **Poll-only** (no change feed). Folder-contents endpoint reflects deletes faster than `/files status`; use it for deletion detection. | Poll every **60s**; accept documented deletion-visibility lag; widen interval for very large accounts. |
| Future (S3/SFTP/WebDAV/LocalFS) | Poll = enumerate (cursor optional per provider). | Provider-specific; default conservative. |

Mechanics:
- The daemon gains a `--poll-interval <dur>` (0 = off, default e.g. 60s) that fires the
  existing `refresh.run` path on a timer, in-process, serialized by the existing
  single-in-flight guard (I6). This is `BACKLOG.md` resolution path (a), and it
  composes cleanly with the strictly-reactive contract (the timer is just an
  internal client issuing `refresh.run`).
- **Use the delta cursor, don't re-enumerate.** Steady-state polls resume from the
  persisted cursor (OneDrive) / do an incremental enumeration. This directly addresses
  the 195k-file thundering-herd risk: a no-change poll is one cheap delta call, not a
  195k-row walk. (Note the existing UX wrinkle: a cursor-resumed `refresh` returns only
  incremental items — for live-sync that is *correct and desired*; the "full
  re-enumerate" surprise belongs to manual `refresh --reset`.)
- **Coalesce + jitter + backoff.** Skip a tick if one is in flight. Add small random
  jitter to the interval (Dropbox-style) so multiple profiles don't align. On 429/`Retry-After`,
  pause this profile's polling for the indicated duration with exponential backoff;
  do not let retries stack (cf. the legacy `--watch` retry-storm in `BACKLOG.md`).

### 4.2 Source-of-truth / freshness application

- Refresh is **one-way remote→`state.db`**. It (a) upserts changed rows, (b) flips
  status for items gone server-side (`EXISTS`→`TRASHED`/`DELETED`), (c) evicts the
  corresponding hydration-cache file on any transition to a deleted/trashed status
  (closes the orphaned-bytes backlog item), and (d) tells the FUSE co-daemon to
  invalidate the affected directories' entry/attr caches (`fuse_lowlevel_notify_inval_*`)
  so the next `ls` re-reads `state.db`.
- This also closes the "`unidrive ls` (live) vs mount (`state.db`) disagree" wart: with
  continuous refresh the stale window shrinks to one poll interval; longer term, point
  `ls` at the same `state.db` the mount serves (single source of truth).

### 4.3 Efficiency

- Delta cursor over full enumeration (P2). `$select` slim payloads on OneDrive
  (`id,cTag,size,file/hashes,downloadUrl`). `cTag`/hash to skip re-hydration of
  unchanged content (conditional GET via `If-None-Match` where supported).
- Directory-granular FUSE cache invalidation (P5), not full-mount drop.
- Long FUSE `entry_timeout`/`attr_timeout` (already planned in the layer briefs) with
  **explicit** invalidation on refresh — so desktop indexers (Baloo/Tracker) don't
  thrash, but freshness is still event-driven.
- Partial/range hydration stays as designed; refresh never forces hydration.

### 4.4 Deletion & conflict robustness (the load-bearing part)

- **The freshness path has no delete authority over user content** (§3). Marking a row
  `TRASHED` and evicting a *cache* file is not a user-data delete; the remote object and
  any pinned/uploaded local edits are untouched. This is the structural reason live-sync
  doesn't re-arm UD-265.
- **Keep UD-265 on the reconcile path** (the two-way engine), where it belongs. Do not
  let the new poll timer drive the two-way reconcile by default — drive only `refresh.run`
  (view), per the daemon spec separation.
- **Add a P6-style guard to the view path too, but tuned for "suspicious bulk
  disappearance," defaulting to keep-and-warn rather than apply.** If a single refresh
  would flip more than N rows to deleted **or** more than P% of known rows (e.g.
  `max_delete_absolute` analog, plus a percentage like the 77% that tripped UD-265),
  **do not apply the deletions to the view**; surface a warning event and keep serving
  the last-known listing until confirmed or until a second corroborating poll agrees.
  This protects against: provider index lag (Internxt `/files` flapping
  `EXISTS`/gone), a transient auth-scope/empty-listing glitch, and a cursor-reset
  returning a partial tree. Mirrors ownCloud `aboutToRemoveAllFiles` + OneDrive
  big-delete classification.
- **Corroborate before reaping on lag-prone providers.** For Internxt specifically,
  require the deletion to be seen by the faster folder-contents endpoint (or two
  consecutive polls) before flipping status, given the ≥60-min `/files status` lag.
- **Conflict policy = keep-both** for the eventual two-way path (ownCloud/Dropbox
  precedent); never last-write-wins silently. Not needed for the view-only refresh.

### 4.5 Recommended defaults (starting point, tune on the 195k account)

```
poll_interval            = 60s        # 0 disables; per-profile override
poll_jitter              = ±10%       # avoid multi-profile alignment
backstop_full_resync     = 24h        # OneDrive token reset / full enumerate
delta_select (OneDrive)  = id,cTag,size,file/hashes,@microsoft.graph.downloadUrl
on_429                   = honor Retry-After, exp-backoff, pause this profile
view_delete_guard        = warn-don't-apply if deletes > max(50 abs, 20% of rows)
internxt_delete_policy   = require folder-contents corroboration or 2 consecutive polls
fuse_invalidation        = per-directory on each applied refresh delta
cache_evict_on_delete    = true       # on any →TRASHED/DELETED transition
```

---

## 5. What to copy / what to avoid

### Copy

- **rclone's two-layer model**: poll-where-supported + dir-cache-TTL fallback, and
  **directory-granular cache invalidation**. <https://rclone.org/commands/rclone_mount/>
- **OneDrive "Discover→Crawl→Notify→Process"** with a persisted delta cursor and a
  **daily full-delta backstop**; `$select` slim payloads; `cTag` to skip downloads;
  honor escalating `Retry-After`.
  <https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/scan-guidance?view=odsp-graph-online>
- **ownCloud `aboutToRemoveAllFiles` / `promptDeleteAllFiles`** guard semantics — treat
  bulk disappearance as suspicious, default to keep, gate on confirmation; and the
  **per-directory ETag skip** for cheap change detection where a delta cursor isn't
  available. <https://github.com/owncloud/client/issues/5276>,
  <https://docs.nextcloud.com/desktop/2.3/architecture.html>
- **Dropbox longpoll discipline**: always advance the cursor even on empty results;
  server-added jitter to avoid thundering herd; cursor-expiry → reset.
  <https://developers.dropbox.com/detecting-changes-guide>
- **CfAPI three-state model + Progressive (range) hydration** as the conceptual target
  for the FUSE placeholder/hydration states (placeholder / full / pinned).
  <https://learn.microsoft.com/en-us/windows/win32/cfapi/build-a-cloud-file-sync-engine>
- **Nucleus invariant** "any client/server view discrepancy is a bug" — make the
  remote→DB enumeration deterministically testable.
  <https://dropbox.tech/infrastructure/rewriting-the-heart-of-our-sync-engine>

### Avoid

- **Don't drive the two-way reconcile (UD-265 path) from the live-sync timer.** Live-sync
  must be view-only (remote→DB) with no content-delete authority. Conflating them is how
  a "make the mount fresh" feature turns into a mass-delete incident.
- **Don't full-enumerate on every poll.** It's the 195k-file thundering-herd; use the
  delta cursor. Full re-enumerate is `refresh --reset` only.
- **Don't trust a single "everything's gone" poll** — providers lag (Internxt `/files`
  ≥60 min) and listings flap. Corroborate or warn-don't-apply.
- **Don't poll-per-notification under load or ignore `Retry-After`** — OneDrive escalates
  the ban; the legacy `--watch` retry-storm in `BACKLOG.md` is the local cautionary tale.
- **Don't invalidate the whole mount cache on one change** — per-directory only.
- **Don't bet the primary namespace on fanotify HSM yet** — no directory-placeholder
  semantics, constrained range support, freeze-deadlock risk; keep FUSE primary, watch
  fanotify. <https://lwn.net/Articles/981392/>
- **Don't adopt Syncthing's peer/vector-clock model** — overkill for single-provider,
  single-truth.

---

## 6. One-line bottom line

Make live-sync a **per-provider delta-cursor poller (push later for OneDrive) that runs
one-way into `state.db`, invalidates FUSE caches per-directory, never deletes user
content as a side effect, and treats bulk disappearance as suspicious-until-corroborated**
— which keeps the mount fresh within a poll interval while leaving the UD-265 two-way
deletion guard exactly where it is.
