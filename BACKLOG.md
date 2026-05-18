# Backlog

Top of file = next up. Work down. Move done items to `CLOSED.md` in the same commit. No IDs, no dates, no versions.

## Critical — data-risk

Silent corruption, orphan storage, lost local metadata. Fix before anything else.

| Title | Scope |
|---|---|
| OneDrive `fileSystemInfo` round-trip | Preserve mtime/ctime on upload and download. Parsed today in `DriveItem.kt:91-94` but not threaded to `CloudItem.modified` or upload payload. Linux users notice broken timestamps immediately. |
| Internxt `finishUpload` idempotency | A network drop after server-side commit but before client reads the response causes a re-finish; the bridge issues a second `fileId` and the first becomes orphan storage that is billed but unreachable. Fix: pin a client request id (or reuse `descriptor.uuid` from `startUpload`) and have the server return the cached `fileId` on retry, or query session status before retrying. |
| OneDrive upload-session expiry validation | `UploadSessionStore` trusts the stored `expiresAt`; a daemon running for 24+ h hits stale URLs with cryptic 404/410. Fix: prune expired sessions before use or query session status on get. |
| OneDrive delta soft-vs-hard delete semantics | `OneDriveProvider.kt:267` treats `removed` and `deleted` identically; missed deletions after a long pause. Audit Graph's TTL on the soft-delete marker; warn when the cursor is older than the safe window. |
| OneDrive `If-Match` / `@odata.etag` on mutating POST + `conflictBehavior` parity | `moveItem`/`updateItem` don't thread eTag → concurrent editors race; `uploadSimple` defaults `fail` while session uploads `replace` → inconsistent overwrite policy by file size. Thread eTag, unify policy. |
| Internxt 429 + `Retry-After` on Drive mutations | POST/PATCH/DELETE bypass `retryOnTransient`; on `createFile` 429 the encrypted bytes are already in the bucket but the Drive row never writes → orphan storage billed but not addressable. Route mutations through retry, parse `Retry-After` header (only the JSON-body hint is read today). |
| Internxt 401 → automatic refresh-and-replay | Mid-session JWT expiry currently throws without retry-with-fresh-token. Mirror OneDrive's mutex-serialised + replay pattern. |
| Internxt `NonCancellable` wrap on `refreshToken` | Under Pass-2 cancellation, refresh can abort between `fetchRefreshedJwt` and `saveCredentials`, leaving in-memory JWT disagreeing with disk. Direct port of OneDrive's pattern. |

## High — correctness, required for first release

| Title | Scope |
|---|---|
| Reach the 5+5+2 smoke target | Current live-integration surface is 4 onedrive (`LiveGraphIntegrationTest`, `OneDriveIntegrationTest`, `OneDriveDeltaIntegrationTest`, `DeltaDiagnosticTest`), 1 internxt (`InternxtIntegrationTest`), 0 sync, plus `CliSmokeTest`. Target: 5 onedrive, 5 internxt, 2 sync (auth/upload/download/delete/delta-reconciles per provider; two end-to-end sync round-trips). Each gated by `UNIDRIVE_INTEGRATION_TESTS=true`. Reducing the existing unit-test surface (≈120 files) is a separate per-test review: don't sweep wholesale, lift the smoke set up to the target first. |
| Internxt `HttpRetryBudget` wiring | Budget class exists in `:app:core` but `InternxtApiService` doesn't consume it; observed 6-wide parallelism under storm. SDK enforces 1 conc / 1000 ms global + 2 conc / 500 ms on Drive — match that. |
| OneDrive 410 Gone resync handling | Honor `resyncChangesApplyDifferences` / `resyncChangesUploadDifferences` with `Location` restart on the delta loop. |
| OneDrive webhook lifecycle events + validation endpoint | Handle `reauthorizationRequired`, `subscriptionRemoved`, `missed`; add an in-repo HTTP endpoint that echoes `validationToken` within 10 s. |
| OneDrive `file.hashes` in local change detector | Remote scanner uses it (`Reconciler.kt:117`); local scanner still mtime+size only (`LocalScanner.kt:100`). Avoids re-uploading on touch-only changes. |
| OneDrive `If-Match` precondition on `createUploadSession` | Catches concurrent edits before the session URL is handed out. |
| OneDrive refresh `downloadUrl` on `assertNotHtml` | When the CDN serves an HTML throttle page, re-resolve via `getItemById` instead of failing the download. |
| Internxt JWT pre-expiry refresh margin | Today fires only after `isJwtExpired()` returns true; add a day-of margin so users don't see an interactive re-auth prompt mid-sync. |
| Internxt request prioritization + extend dedup | `folderContentsDedup` covers `getFolderContents` only; extend to `getFileMeta`/`listFiles`/`listFolders`. |

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
| Internxt hard-coded client-header values | `internxt-client` / `internxt-version` / `x-internxt-desktop-header` literals risk allowlist tightening. Make a config override. |
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
