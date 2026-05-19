# Backlog

Top of file = next up. Work down. Move done items to `CLOSED.md` in the same commit. No IDs, no dates, no versions.

## Critical — data-risk

Silent corruption, orphan storage, lost local metadata. Fix before anything else.

| Title | Scope |
|---|---|
| OneDrive upload-session expiry validation | `UploadSessionStore` trusts the stored `expiresAt`; a daemon running for 24+ h hits stale URLs with cryptic 404/410. Fix: prune expired sessions before use or query session status on get. |
| OneDrive delta soft-vs-hard delete semantics | `OneDriveProvider.kt:267` treats `removed` and `deleted` identically; missed deletions after a long pause. Audit Graph's TTL on the soft-delete marker; warn when the cursor is older than the safe window. |
| OneDrive `If-Match` / `@odata.etag` on mutating POST + `conflictBehavior` parity | `moveItem`/`updateItem` don't thread eTag → concurrent editors race; `uploadSimple` defaults `fail` while session uploads `replace` → inconsistent overwrite policy by file size. Thread eTag, unify policy. |

## High — correctness, required for first release

| Title | Scope |
|---|---|
| Reach the 5+5+2 smoke target | Current live-integration surface is 4 onedrive (`LiveGraphIntegrationTest`, `OneDriveIntegrationTest`, `OneDriveDeltaIntegrationTest`, `DeltaDiagnosticTest`), 1 internxt (`InternxtIntegrationTest`), 0 sync, plus `CliSmokeTest`. Target: 5 onedrive, 5 internxt, 2 sync (auth/upload/download/delete/delta-reconciles per provider; two end-to-end sync round-trips). Each gated by `UNIDRIVE_INTEGRATION_TESTS=true`. Reducing the existing unit-test surface (≈120 files) is a separate per-test review: don't sweep wholesale, lift the smoke set up to the target first. |
| OneDrive 410 Gone resync handling | Honor `resyncChangesApplyDifferences` / `resyncChangesUploadDifferences` with `Location` restart on the delta loop. |
| OneDrive webhook lifecycle events + validation endpoint | Handle `reauthorizationRequired`, `subscriptionRemoved`, `missed`; add an in-repo HTTP endpoint that echoes `validationToken` within 10 s. |
| OneDrive `file.hashes` in local change detector | Remote scanner uses it (`Reconciler.kt:117`); local scanner still mtime+size only (`LocalScanner.kt:100`). Avoids re-uploading on touch-only changes. |
| OneDrive `If-Match` precondition on `createUploadSession` | Catches concurrent edits before the session URL is handed out. |
| OneDrive refresh `downloadUrl` on `assertNotHtml` | When the CDN serves an HTML throttle page, re-resolve via `getItemById` instead of failing the download. |

## Medium — efficiency

| Title | Scope |
|---|---|
| OneDrive `$batch` for reconciliation reads | Max 20 per batch; `dependsOn` ordering; per-inner 429 retry. Biggest single throughput win on bulk-share / bulk-move. |
| OneDrive `Range` against `@microsoft.graph.downloadUrl` | Parallel-segment download. |
| OneDrive conditional GET via `If-None-Match` + cTag | Make unchanged-file refreshes free. |
| OneDrive `$select` slim payload on delta | Minimum: `id,cTag,size,file/hashes,@microsoft.graph.downloadUrl`. |
| OneDrive `conflictBehavior` user-selectable | Hard-coded `"replace"` at `GraphApiService.kt:522` once parity is fixed in the data-risk item. |
| OneDrive `createLink` client-side dedupe | Check `listPermissions` for an existing matching link before POSTing. |
| OneDrive per-tenant concurrency calibration | Personal vs Business vs GCC; flagged in `core/providers/onedrive/README.md`. |
| OneDrive `HttpRetryBudget` per-provider override | Constants currently global. |

## Low — guards and UX

| Title | Scope |
|---|---|
| OneDrive `malware` facet skip on download | One-line guard. |
| OneDrive `fileSize` precheck → early 507 | Avoid wasted upload attempts. |
| Internxt keep-overwritten prune | `unidrive prune --backups [--older-than 30d] [--profile NAME]` for `keep_overwritten` archives. Glob on `.unidrive-prev-*` in plainName. |
| `unidrive status` no-default-profile fallback shows misleading "Local Filesystem" row | When invoked without `--profile` and with no default configured in `config.toml`, `unidrive status` renders a phantom row labeled "Local Filesystem" with a `⚠︎` marker, `[– –]` status code, and all-zero columns — looks like a real but broken entry, isn't. `unidrive status --all` correctly shows the configured providers. Either resolve a default profile up front and show its row, or emit a one-line message ("No default profile configured. Use --all to list, or --profile <name> to select.") and exit zero. The current placeholder is worse than either alternative because it suggests something is broken. |

## Cross-cutting

| Title | Scope |
|---|---|
| Path normalization (NFC) across sync | Required for cross-platform file matching. |
| Retry budget per operation | Cap retries to keep the sync loop responsive. |
| Provider SPI hardening with two providers | Either trim what isn't earning its keep or extend to remove provider-name dispatch sites. |

## Design constraints (not tickets — bind when related work lands)

(none currently)

## Deferred — post-MVP, with reasons

- **Virtual filesystem layer (sparse / placeholder representation of cloud-only items).** Today `state.db` tracks all known cloud items as metadata rows (`is_hydrated=0, is_pinned=0` by default), but they are invisible on the local FS — the sync_root contains only physically downloaded files. Users with large cloud accounts (live example: 625 GiB / 171k files) see an empty folder despite the daemon "knowing" everything. Two platform-specific implementations would close the gap: **Windows CloudFiles API** (Cloud Sync Engine — explicitly out of MVP scope per ADR-0012 "linux-only"), and **Linux FUSE** (substantial architectural arc; no existing FUSE surface in the tree). Either path is structurally larger than any current BACKLOG item. Interim mitigation: a `unidrive get --recursive <path>` (or `unidrive pin <path>` if pin/unpin lands) to bulk-hydrate specific subtrees on demand; an opt-in `--hydrate-on-scan` flag could give dropbox-style auto-download for users who explicitly want it. The virtual-FS work itself stays out of MVP scope; revisit once the MVP is shipped and a real platform target (Windows reinstatement, or a Linux user complaint specifically about the metadata-vs-files asymmetry) justifies the surface.

- **Tombstone retention policy on `state.db`.** v1 keeps TRASHED rows indefinitely. Includes the permanent-delete drift case — Internxt's ~30-day purge means a TRASHED row may eventually stop appearing in cloud listings; v1 leaves the row as TRASHED rather than promoting to DELETED. Revisit alongside a `tombstone_retention_days` knob if `state.db` growth becomes user-visible.
- **Local-modified-while-TRASHED conflict-aware merge.** Editing the local copy of a file currently in cloud trash, then restoring the cloud copy → conflict. v1 is last-write-wins (cloud restore overwrites the local edit on re-download). Conflict-aware merge would mirror the existing `ConflictPolicy` machinery for the editing-during-trash window.
- **Internxt multipart upload** — constants exist in `InternxtConfig.kt` but unconsumed. Single-shard upload works up to the per-account size cap. Defer because no smoke test currently exercises >shard-size files; revisit when one does.
- **Internxt Shares API** — server has ~30 endpoints; provider returns Unsupported. Not in smoke surface, not required for two-way sync.
- **OneDrive resumable-upload 416/417 nuance** — current chunked retry covers the common case; the 416 GET-probe-and-trim path is the rare edge. Defer until smoke surfaces it.
- **Xtra E2EE re-evaluation** — the `xtra` wrapper was removed in the slim prune. Internxt is natively E2EE; OneDrive users who want client-side encryption no longer have a layer for it. Re-evaluate post-MVP if user demand surfaces.

## Out of scope for this branch

- Windows / macOS — Linux only.
- Shell extensions, tray UI, GUI.
- MCP server (removed).
- Backup-tool features (snapshots, restore points).
- Three-way merge of document conflicts — surface conflicts, offer keep-both or last-writer-wins.
- Embedding sync as a library.
- Provider-specific niche features without cross-provider generalization.
