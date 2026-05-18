# Backlog

Top of file = next up. Work down. Move done items to `CLOSED.md` in the same commit. No IDs, no dates, no versions.

## Critical — data-risk

Silent corruption, orphan storage, lost local metadata. Fix before anything else.

| Title | Scope |
|---|---|
| OneDrive `fileSystemInfo` round-trip | Preserve mtime/ctime on upload and download. Parsed today in `DriveItem.kt:91-94` but not threaded to `CloudItem.modified` or upload payload. Linux users notice broken timestamps immediately. |
| Internxt `finishUpload` idempotency | A network drop after server-side commit but before client reads the response causes a re-finish; the bridge issues a second `fileId` and the first becomes orphan storage that is billed but unreachable. **The Bridge has no client-addressable session-status endpoint** — `GET /buckets/{bucket}/files/{uuid}/info` rejects the upload-session uuid at middleware (only 24-hex `BucketEntry.id` accepted), and the `uploads` collection isn't exposed by any GET route. Fix path: on a transient mid-finish failure, treat HTTP 409 (`MissingUploadsError`) on retry as "prior commit probably landed" rather than re-finish blindly, and reconcile via the Drive-server file-listing on the parent folder by size + recently-created window. True idempotency needs a vendor-side `Idempotency-Key` header — out of our gift. Confirmed via internxt/bridge source audit. |
| OneDrive upload-session expiry validation | `UploadSessionStore` trusts the stored `expiresAt`; a daemon running for 24+ h hits stale URLs with cryptic 404/410. Fix: prune expired sessions before use or query session status on get. |
| OneDrive delta soft-vs-hard delete semantics | `OneDriveProvider.kt:267` treats `removed` and `deleted` identically; missed deletions after a long pause. Audit Graph's TTL on the soft-delete marker; warn when the cursor is older than the safe window. |
| OneDrive `If-Match` / `@odata.etag` on mutating POST + `conflictBehavior` parity | `moveItem`/`updateItem` don't thread eTag → concurrent editors race; `uploadSimple` defaults `fail` while session uploads `replace` → inconsistent overwrite policy by file size. Thread eTag, unify policy. |
| Internxt 429 on Drive mutations route through retry | `deleteFile`/`deleteFolder` still bypass `retryOnTransient`; on a `createFile` 429 the encrypted bytes are already in the bucket but the Drive row never writes → orphan storage billed but not addressable. The `Retry-After` HTTP header is now parsed and threaded into `InternxtApiException.retryAfterMs` (taking precedence over the JSON-body hint), so the retry primitive will already honour it once the remaining DELETE call sites are wrapped. |

## High — correctness, required for first release

| Title | Scope |
|---|---|
| Reach the 5+5+2 smoke target | Current live-integration surface is 4 onedrive (`LiveGraphIntegrationTest`, `OneDriveIntegrationTest`, `OneDriveDeltaIntegrationTest`, `DeltaDiagnosticTest`), 1 internxt (`InternxtIntegrationTest`), 0 sync, plus `CliSmokeTest`. Target: 5 onedrive, 5 internxt, 2 sync (auth/upload/download/delete/delta-reconciles per provider; two end-to-end sync round-trips). Each gated by `UNIDRIVE_INTEGRATION_TESTS=true`. Reducing the existing unit-test surface (≈120 files) is a separate per-test review: don't sweep wholesale, lift the smoke set up to the target first. |
| OneDrive 410 Gone resync handling | Honor `resyncChangesApplyDifferences` / `resyncChangesUploadDifferences` with `Location` restart on the delta loop. |
| OneDrive webhook lifecycle events + validation endpoint | Handle `reauthorizationRequired`, `subscriptionRemoved`, `missed`; add an in-repo HTTP endpoint that echoes `validationToken` within 10 s. |
| OneDrive `file.hashes` in local change detector | Remote scanner uses it (`Reconciler.kt:117`); local scanner still mtime+size only (`LocalScanner.kt:100`). Avoids re-uploading on touch-only changes. |
| OneDrive `If-Match` precondition on `createUploadSession` | Catches concurrent edits before the session URL is handed out. |
| OneDrive refresh `downloadUrl` on `assertNotHtml` | When the CDN serves an HTML throttle page, re-resolve via `getItemById` instead of failing the download. |
| Internxt request prioritization | Folder-walk and metadata reads compete on equal footing today; under storm the engine can spend the global concurrency budget on speculative metadata while user-facing downloads queue behind them. Priority lanes (foreground vs background) would mirror the OneDrive `HttpRetryBudget` shape without copying it. Dedup is now in place across `getFolderContents` / `getFileMeta` / `listFiles` / `listFolders`, so a priority overlay can layer on top without re-fetching the same payload twice. |
| Internxt resumable scan with persistent checkpoint | `InternxtProvider.delta()` accumulates pages in memory and only writes to `state.db` after the reconciler runs. A daemon kill (or the gateway's silent ~20k-item throttle dropping us to ~2.5 items/sec) loses all progress; next launch restarts from `offset=0`. Persist each retrieved page to a staging slice in `state.db` immediately, checkpoint `(last_offset, updatedAt)` after each successful page, and resume from the checkpoint on restart. Reconciler still runs after the full scan completes — no user-visible behavior change at the reconcile layer, only the resume capability. Sort is already stable (`sort=uuid&order=ASC`), so offsets are deterministic. Lands after the state.db redesign — that work re-shapes the schema this checkpoint would write into. Streaming reconciliation (process pages as they arrive, don't wait for full scan) is a separate follow-up; needs its own spec and a re-entrant reconciler. |

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
| Internxt retry coverage on remaining mutating verbs | `deleteFile`/`deleteFolder`/`putEncryptedShard`/`downloadFileStreaming`. |
| Internxt local chunk-tombstone for upload resume | Compensate for missing resumable-upload protocol. |

## Low — guards and UX

| Title | Scope |
|---|---|
| OneDrive `malware` facet skip on download | One-line guard. |
| OneDrive `fileSize` precheck → early 507 | Avoid wasted upload attempts. |
| Internxt destructive-overwrite warning | Opt-in rename-and-keep mode given no server versioning. |
| Internxt `encryptVersion` legacy support | Hard-coded `03-aes`; legacy `02-rsa` buckets unreadable. |

## Cross-cutting

| Title | Scope |
|---|---|
| Path normalization (NFC) across sync | Required for cross-platform file matching. |
| Retry budget per operation | Cap retries to keep the sync loop responsive. |
| Provider SPI hardening with two providers | Either trim what isn't earning its keep or extend to remove provider-name dispatch sites. |

## Design constraints (not tickets — bind when related work lands)

- **Internxt upload retry must pin `indexBytes`.** Before any retry is wired around `InternxtProvider.kt:210-269`, decide the retry boundary so the random 32-byte `indexBytes` (and the derived `iv` and `fileKey`) stay stable across attempts. The server stores `indexHex` in `finishUpload`; ciphertext encrypted with an old index alongside a new index on the metadata side is silent corruption on download. Options: (a) retry stages 4–5 only (PUT + finish), hold the temp file + hash steady, or (b) restart the full pipeline including re-encryption. **Don't** mix the two. Add a unit test that asserts `indexBytes` stability across a forced retry.

## Deferred — post-MVP, with reasons

- **Tombstone retention policy on `state.db`.** v1 keeps TRASHED rows indefinitely. Includes the permanent-delete drift case — Internxt's ~30-day purge means a TRASHED row may eventually stop appearing in cloud listings; v1 leaves the row as TRASHED rather than promoting to DELETED. Revisit alongside a `tombstone_retention_days` knob if `state.db` growth becomes user-visible.
- **Local-modified-while-TRASHED conflict-aware merge.** Editing the local copy of a file currently in cloud trash, then restoring the cloud copy → conflict. v1 is last-write-wins (cloud restore overwrites the local edit on re-download). Conflict-aware merge would mirror the existing `ConflictPolicy` machinery for the editing-during-trash window.
- **Internxt WebSocket change feed** — coarse-grained debounce would cut delta latency. Correctness unaffected; latency/battery win only. Defer until the polling cadence becomes a user complaint.
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
