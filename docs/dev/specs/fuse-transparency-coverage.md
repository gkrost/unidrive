# FUSE transparency — coverage map and decisions

What "total local FUSE-filesystem transparency" means for the Linux `unidrive mount`,
which dimensions are already covered, which gaps remain (and where they're filed),
and the engine/provider decisions that frame it. This is a coverage/decision record,
not an implementation plan — each open item is planned when picked up.

## Goal

The `unidrive mount` should behave like a normal local filesystem: every cloud
file is visible and operable, metadata is correct, the view stays fresh, the
mount survives daemon churn, and cloud-only files appear without being downloaded
until their bytes are actually needed.

## Decisions

- **The FUSE / tracking-set engine takes over; the legacy `sync` engine is left as-is to fade.** No aggressive retirement of `core/app/sync/`; the mount + tracking-set engine become the primary surface.
- **The placeholder / online-only model is in scope — and is already delivered by the mount** (see below). There is no separate "VFS layer" to build for Linux; the Deferred "Virtual filesystem layer" item is re-scoped to the legacy `sync_root` real-directory case and the Windows CloudFiles tier.

## Key finding: the mount already provides placeholders

The FUSE mount + hydration SPI deliver online-only/placeholder semantics on Linux today:

- `hydration.list` returns **all** enumerated rows including cloud-only ones, with `size = remoteSize` and an `isHydrated` flag (`HydrationImpl.kt`), so cloud-only files appear with correct metadata.
- The co-daemon's `getattr` slow-path calls `populate_from_list` (a metadata fetch) — it never downloads (`mount/src/fuse_fs.rs`); stat/readdir do not hydrate.
- Read hydrates on demand (`openForRead → ensureHydrated`).
- `pin` / `free` / `get` / `ls` CLI commands manage hydration state.

(Empirically: a freshly-refreshed mount lists a trashed file with its real size before any read — metadata-only, no download.)

## Coverage map

| Dimension | Status | Covering item(s) |
|---|---|---|
| POSIX file ops — read/write/open/create/mknod/mkdir/unlink/rmdir/rename/fsync | ✅ implemented | `unidrive-mount-linux` (landed) |
| **`setattr` — chmod/chown/utimes + truncate/ftruncate** | ⛔ gap | `unidrive-mount-linux` BACKLOG **High** (filed) |
| Metadata — getattr/timestamps/perms/inodes | ✅ | landed |
| `statfs` / `nlink` | ⛔→filed | `unidrive-mount-linux` BACKLOG (statfs Medium, nlink Low) |
| xattr (get/list/set/remove) | ⛔→filed | `unidrive-mount-linux` BACKLOG Low |
| Error-code fidelity — `mkdir→ENOENT`, `rmdir→ENOTEMPTY` | ✅ filed | both repos (namespace-verbs R2/R3) |
| Freshness/liveness — mount reflects remote changes | ✅ filed | unidrive: daemon `--poll-interval`, sync-as-daemon-client, Internxt `/files`-lag, OneDrive delta fixes |
| Resilience — mount survives daemon restart; reconnect; stale detection | ✅ filed | unidrive: mount-survives-restart, auto-spawn-daemon, daemon-status-mode |
| **Sparse / placeholder — cloud-only visible, no-hydrate-on-stat, hydrate-on-read, pin/unpin** | ✅ **delivered by the mount** | landed (this doc) |
| No-hydrate-on-thumbnail/preview | ⚠️ partial | `unidrive-mount-linux` thumbnailer-hydration item (remote/slow-fs signal); fundamentally hard on FUSE (a read needs bytes) |
| Hydration-state visibility in the mount | ⛔→filed | `unidrive-mount-linux` BACKLOG Low |
| Full-remote enumeration completeness | ✅ filed | unidrive: refresh/auto-poll + tracking-engine cursor work |

So the op/metadata/freshness/resilience/placeholder dimensions are covered (after this
review's filings). The only genuinely-hard residual limit is **no-hydrate-on-preview**
(thumbnailers read → download); the cheap mitigation is signalling the mount as a
remote/slow filesystem so desktop thumbnailers skip auto-previews by default.

## Provider re-add assessment (S3 / SFTP / WebDAV / LocalFS)

Intent: once Internxt + OneDrive are stable for primary-account use, re-add the
slim-phase-cut providers. **Verdict: tame-able, no engine or mount rewrite.**

- The `CloudProvider` SPI is the single, provider-agnostic integration point, with
  sensible defaults (`hashAlgorithm()=null`, `deltaFromLatest`/`verifyItemExists`/
  `share*`/webhook all default). A provider implements a small core (auth, list,
  getMetadata, download, upload, delete, createFolder, move, delta, quota) + declares
  `capabilities()`.
- The tracking-set engine consumes the SPI **uniformly — no provider-name dispatch**;
  it works **without a change-feed** (`delta` = full enumeration; cursor-persistence is
  an optimization) and **without content hashes** (reconciler size-fallback). The lemma
  makes absence-based deletion safe for tombstone-less providers (S3/SFTP/WebDAV).
- The FUSE mount + hydration ride the same SPI, so each provider gets the mount +
  placeholders for free.

Per provider: **LocalFS** trivial (scan = delta, compute-or-null hash); **S3**
list-objects + ETag-as-hash (size-fallback for multipart), full-list delta, absence =
delete; **SFTP/WebDAV** list + mtime/size, no hash (size-fallback), full-enum delta.

Prerequisites / notes:
- Finish the Cross-cutting **Provider SPI hardening** item first (remove residual
  provider-name dispatch — it lives in the legacy `sync`/factory, not the new engine).
- Per-provider efficiency follow-ups for no-change-feed providers at scale (S3
  inventory, WebDAV sync-token, SFTP mtime-since) are optional; the full-enum default
  is correct, just heavier.
- **Landing this reverses the current `AGENTS.md` "removed providers stay cut" hard
  rule — update that rule when the work starts.**
