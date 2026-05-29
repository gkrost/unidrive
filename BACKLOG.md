# Backlog

> **Migrated to GitHub Issues — see the issue links below.** Every actionable backlog
> item now lives as an issue on `gkrost/unidrive` with severity / area / kind labels and
> cross-links. The full structured detail (file:line anchors, repro evidence, Fix directions)
> moved into each issue body. This file keeps the section structure as an index; the
> non-ticket sections (Design constraints, Deferred, Out of scope) stay here as prose.

Top of file = next up. Work down. No IDs, no dates, no versions.

## Critical — data-risk

Silent corruption, orphan storage, lost local metadata. Fix before anything else.

- [#97] OneDrive chunked-upload resume corrupts a file changed between attempts — session keyed on path only (silent data-loss) — **FIXED in #90 (closed)**
- [#98] Hydration open-set is a non-thread-safe map iterated across connections — dehydrate races an open writer (data-loss + CME) — **FIXED in #89 (closed)**
- [#99] Tracking-set sync engine

## High — correctness, required for first release

- [#100] OneDrive download returns 404 itemNotFound for a file present in the listing → mount hydrate-on-read EIO (cloud-only files unreadable)
- [#101] Hydration `_events` MutableSharedFlow.emit head-of-line-blocks every hydration op when a subscriber is slow → mount hangs — **fix in OPEN #96**
- [#102] `applyDeleteRemote` swallows ALL provider errors and marks the row TRASHED anyway — stranded remote + delete never retried — **fix in OPEN #95**
- [#103] OneDrive delta conflates a soft `removed` (permission revocation) with a hard delete → reaps a still-valid local copy (data-risk)
- [#104] Tracking-set adopt-on-content-match silently degrades to size-only for Internxt (null hash) — different-content same-size files adopted as identical
- [#105] Mount doesn't survive daemon restart — operations return EIO until mount is manually remounted
- [#106] FUSE mount: `create`/`mknod`/`fsync` not implemented — all file-creation paths fail with ENOSYS
- [#107] Cut first unidrive-mount-linux release tarball + wire dist/install.sh to download + SHA256-verify it
- [#108] Legacy SyncEngine trips UD-265 deletion-safeguard on the internxt_gernot_krost_posteo profile, daemon stays failed
- [#109] Reach the 5+5+2 smoke target
- [#110] OneDrive 410 Gone resync handling
- [#111] OneDrive webhook lifecycle events + validation endpoint
- [#112] OneDrive `file.hashes` in local change detector
- [#113] OneDrive `If-Match` precondition on `createUploadSession`
- [#114] OneDrive refresh `downloadUrl` on `assertNotHtml`
- [#115] XDG-user-dir locale aliasing across `Pictures`/`Bilder`/`Imágenes`/`Images`/…
- [#117] `status` and `status --all` enumerate different profile sources (orphan-profile-dir divergence)
- [#118] `status` / `status --all` don't reflect the tracking-set engine
- [#119] OneDrive delta loses sight of a path the engine itself just wrote ("not in delta, marking deleted" loop)

## Medium — efficiency

- [#121] `IpcServer` broadcast channel (capacity 256) silently drops sync/progress events when a subscriber lags
- [#122] Hydration `unknown path` maps to two different errnos depending on the verb (EIO vs ENOENT)
- [#123] Bulk directory creation issues one synchronous remote round-trip per directory — large-tree copies into the mount are slow and abort wholesale on any failure
- [#124] OneDrive `$batch` for reconciliation reads
- [#125] OneDrive `Range` against `@microsoft.graph.downloadUrl`
- [#126] OneDrive conditional GET via `If-None-Match` + cTag
- [#127] OneDrive `$select` slim payload on delta
- [#128] OneDrive `conflictBehavior` user-selectable
- [#129] OneDrive `createLink` client-side dedupe
- [#130] OneDrive per-tenant concurrency calibration
- [#131] OneDrive `HttpRetryBudget` per-provider override
- [#132] Internxt deletions aren't promptly reaped — the `/files` listing `status` lags a web-UI trash by ≥60 min
- [#133] Tracking-set live-test runtime is impractical for routine CI
- [#134] Tracking-set engine: migration / coexistence with legacy `state.db`
- [#135] Socket-path name hashing diverges from hydration cache root naming for long profile names
- [#136] Pending-upload predicate (`remoteId == null && isHydrated == true`) is enforced informally across many call sites
- [#137] Pre-reconcile empty-sync_root guard must differentiate "rehydrate intent" from "wrong sync_root"
- [#138] Typed `FolderNotEmpty` provider exception for `HydrationImpl.rmdir` (namespace-verbs R2)
- [#139] `mkdir` parent-missing maps to ENOENT instead of EIO (namespace-verbs R3)
- [#140] Cache-file eviction on `unlink` / `rmdir` (namespace-verbs R5)
- [#141] Convert `unidrive sync` into a client of the `unidrive daemon` process (daemon-decomposition phase 2)
- [#142] Auto-spawn `unidrive daemon` on first client connection (daemon-decomposition phase 3)
- [#143] `unidrive daemon status` should refuse / warn when lock-holder mode is not `daemon`
- [#144] Daemon doesn't auto-poll for remote changes — mount view stays stale until operator runs `refresh`
- [#145] `unidrive ls` (live query) and the FUSE mount (state.db) disagree during the stale window
- [#146] `RefreshRpcHandler` in-flight guard ordering + missing error-path test coverage

## Low — guards and UX

- [#147] `StateDatabase.batch{}` holds the `@Synchronized` lock across a full enumeration → mount `ls` stalls during `enumerateRemoteIntoState(reset)`
- [#148] `uploadFromCache` reads the local watermark AFTER the upload (redundant re-upload, NOT a lost-update)
- [#149] Hydration-cache file deleted inside the reap DB transaction (filesystem op in a `db.batch{}`)
- [#150] Local-dev redeploy + co-daemon observability gaps (`scripts/dev/redeploy-local.sh`)
- [#151] `rm` on the mount records `status=DELETED` but the provider trashes (recoverable) — label vs disposition mismatch
- [#152] `unidrive-ui` needs to call `sync.subscribe` after connecting
- [#153] OneDrive `malware` facet skip on download
- [#154] OneDrive `fileSize` precheck → early 507
- [#155] Internxt keep-overwritten prune
- [#156] `unidrive status` no-default-profile fallback shows misleading "Local Filesystem" row
- [#157] `unidrive status` shows `[✔ OK]` on stale cached auth state
- [#158] Build-time test that `logback.xml` is XML-well-formed
- [#159] Build-time test that `BuildInfo.DIRTY` correctly handles mode-only diffs
- [#160] Hydrated-but-locally-missing rows in `--download-only` mode should re-download, not silently loop
- [#161] Tracking-set live test: JWT/OAuth-refresh path not structurally exercised
- [#162] Tracking-set live test: throttling / 429-storm handling path not exercised
- [#163] Gradle daemon poison: `java.io.EOFException` from `SerializableTestResultStore` with 0-byte results = stop the daemon
- [#164] `RefreshRpcHandler` exception-message JSON escape covers quotes only
- [#165] `.lock.pid` rendered as `mode (no-mode)` for legacy pid-only sidecars — confusing UX
- [#166] `unidrive refresh` against a profile with a pre-existing delta cursor returns only incremental delta, not a full enumeration — operator UX surprise
- [#167] `unidrive ts <sub>` ignores the global `-p` option
- [#168] Regression test for ts-CLI provider authentication
- [#169] Stale `EXPERIMENTAL — not yet verified against real Internxt/OneDrive` warning in `ts`
- [#170] Suggested-command output uses `<path>` placeholders instead of real values (not copy-pasteable)

## Cross-cutting

- [#171] Path normalization (NFC) across sync
- [#172] Retry budget per operation
- [#173] Provider SPI hardening with two providers

## Design constraints (not tickets — bind when related work lands)

- **Platform-surface code lives outside `core/app/`.** Anchor: `AGENTS.md` *What not to do* — "Don't grow the daemon to host a UI tier." Trigger: any work on the Windows desktop, Android, or Linux UI surfaces declared in `docs/adr/multi-platform.md`. Those surfaces consume the core; they don't extend it inline.

- **Hydration cache is keyed per-account (`cacheKey` = profile.name), not per-type (`providerId`).** Anchor: `SyncEngine.resolveCachePath` (uses `cacheKey`) + `SyncEngine.hydrationCacheRoot`; consumers `MountCommand` (co-daemon `--cache`), `SyncCommand` and `DaemonRuntime` all pass `profile.name`. `providerId` stays the provider TYPE for `ProviderRegistry.getMetadata` lookups (concurrency cap, capability flags) and must NOT be reused as the cache namespace. Trigger: any change to the cache layout, the SyncEngine cache params, or the mount/daemon cache wiring. Pinned by `SyncEngineTest.cache path is keyed per-account so same-type profiles do not collide` — if that test is removed or `cacheKey` is collapsed back into `providerId`, two accounts of the same provider type (e.g. two `onedrive` profiles) silently share `hydration/onedrive/<path>` and clobber each other's content for identical remote paths.

## Deferred — post Linux-daemon ship, sequenced by [docs/adr/multi-platform.md](docs/adr/multi-platform.md)

- **Virtual filesystem layer (placeholders for cloud-only items).** Today only physically-downloaded files appear in the sync_root; large cloud accounts (live example: 625 GiB / 171k files) see an empty folder. The two platform-tier implementations that close this gap — Windows CloudFiles API for the Windows desktop client, FUSE for the Linux UI — are the explicit scope of those surfaces in `multi-platform.md`. Daemon-side interim mitigation: a `unidrive get --recursive <path>` (or `unidrive pin <path>` if pin/unpin lands) to bulk-hydrate. The platform implementations are scheduled work, not indefinitely deferred. **Status (Linux): the FUSE mount already delivers this.** `unidrive mount` + the hydration SPI surface cloud-only files as placeholders — `hydration.list` returns non-hydrated rows with `remoteSize` + an `isHydrated` flag, `getattr`/`readdir` serve metadata without downloading, read hydrates on demand, and `pin`/`free`/`get` manage hydration state. This entry now scopes to (a) the legacy `sync_root` real-directory case (still download-only → empty-folder for cloud accounts; the mount is the Linux answer) and (b) the Windows CloudFiles tier. The remaining Linux-FUSE transparency gaps are concrete filed items (no-hydrate-on-thumbnail / remote-slow-fs signal, `setattr`/truncate, xattr, hydration-state visibility) — see `docs/dev/specs/fuse-transparency-coverage.md` and the `unidrive-mount-linux` backlog.

- **Re-add S3 / SFTP / WebDAV / LocalFS via the `CloudProvider` SPI (post Internxt+OneDrive stabilization).** Once Internxt + OneDrive are stable enough for primary-account use, re-add the slim-phase-cut providers. Assessment (verified against the SPI + tracking engine): tame-able, **no engine or mount rewrite**. Each implements `CloudProvider` (core: auth / listChildren / getMetadata / download / upload / delete / createFolder / move / delta / quota) and declares `capabilities()`; the tracking-set engine consumes the SPI uniformly (no provider-name dispatch), works without a change-feed (`delta` = full enumeration; cursor-persistence is an optional optimization), tolerates `hashAlgorithm() = null` (the reconciler falls back to size), and the lemma makes absence-based deletion safe for tombstone-less providers. The FUSE mount + hydration ride the same SPI, so each provider gets the mount + placeholders for free. Prerequisite: finish the Cross-cutting *Provider SPI hardening* item ([#173] — remove the residual provider-name dispatch sites — they're in the legacy `sync`/factory, not the new engine). Per-provider efficiency follow-ups (S3 inventory, WebDAV sync-token, SFTP mtime-since) are optional; LocalFS is trivial (scan = delta), S3 uses ETag-as-hash (size-fallback for multipart). **Landing this reverses the current `AGENTS.md` "removed providers stay cut" hard rule — update that rule when the work starts.** Rationale in `docs/dev/specs/fuse-transparency-coverage.md`.

- **Tombstone retention policy on `state.db`.** v1 keeps TRASHED rows indefinitely. Includes the permanent-delete drift case — Internxt's ~30-day purge means a TRASHED row may eventually stop appearing in cloud listings; v1 leaves the row as TRASHED rather than promoting to DELETED. Revisit alongside a `tombstone_retention_days` knob if `state.db` growth becomes user-visible.
- **Local-modified-while-TRASHED conflict-aware merge.** Editing the local copy of a file currently in cloud trash, then restoring the cloud copy → conflict. v1 is last-write-wins (cloud restore overwrites the local edit on re-download). Conflict-aware merge would mirror the existing `ConflictPolicy` machinery for the editing-during-trash window.
- **Internxt multipart upload** — constants exist in `InternxtConfig.kt` but unconsumed. Single-shard upload works up to the per-account size cap. Defer because no smoke test currently exercises >shard-size files; revisit when one does.
- **Internxt Shares API** — server has ~30 endpoints; provider returns Unsupported. Not in smoke surface, not required for two-way sync.
- **OneDrive resumable-upload 416/417 nuance** — current chunked retry covers the common case; the 416 GET-probe-and-trim path is the rare edge. Defer until smoke surfaces it.
- **Xtra E2EE re-evaluation** — the `xtra` wrapper was removed in the slim prune. Internxt is natively E2EE; OneDrive users who want client-side encryption no longer have a layer for it. Re-evaluate post-MVP if user demand surfaces.

## Out of scope across all surfaces

- MCP server (removed; not coming back).
- Backup-tool features (snapshots, restore points).
- Three-way merge of document conflicts — surface conflicts, offer keep-both or last-writer-wins.
- Embedding the core as a generic third-party library. First-party platform tiers (Windows desktop, Android, Linux UI) consume the core; there is no generic embedding offer for arbitrary third parties.
- Provider-specific niche features without cross-provider generalization.
