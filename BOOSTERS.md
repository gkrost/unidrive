# API boosters — Internxt + OneDrive

What each API offers, what we already use, and what we should adopt. Labels: `used` (wired), `partial` (scaffolded but incomplete — see Notes), `gap` (missing), `n/a` (not applicable to MVP scope), `API gap` (the upstream API doesn't expose it), `known` (server-side fact, no client work).

Labels verified against `core/providers/{onedrive,internxt}/` source plus the `docs/providers/*-robustness.md` audits.

---

## OneDrive (Microsoft Graph)

### Change detection
| Capability | Status | Notes |
|---|---|---|
| `/drive/root/delta` with deltaLink persistence | used | dedupe by last-occurrence per `id`; ignore `parentReference.path` |
| `?token=latest` to skip initial crawl on reconnect | used | `GraphApiService.kt:134`, `OneDriveProvider.kt:163-173` (`Capability.FastBootstrap`) |
| 410 Gone → `resyncChanges*` restart handling | gap | delta loop at `GraphApiService.kt:137-169` has no 410-resync branch |
| `$select` on delta to slim payload | gap | minimal set: `id,cTag,size,file/hashes,@microsoft.graph.downloadUrl` |
| `Prefer: deltashowsharingchanges, deltashowremovedasdeleted` | n/a personal | SPO/ODB only — out of MVP scope |
| Webhook subscription on driveItem | partial | subscription creation wired at `GraphApiService.kt:909-936`; no in-repo HTTP endpoint receives notifications or echoes `validationToken` |
| Subscription lifetime auto-renewal (cap ~29.4 d) | used | `SubscriptionRenewalScheduler.kt:31-82` |
| Lifecycle events `reauthorizationRequired` / `subscriptionRemoved` / `missed` | gap | no occurrences in repo |
| Periodic delta sweep as webhook safety net | used | `AdaptiveInterval.kt` drives polling regardless of webhook |

### Upload
| Capability | Status | Notes |
|---|---|---|
| Simple PUT for `<4 MB` | used | `OneDriveProvider.kt:96-98`, `GraphApiService.kt:305-332` |
| `createUploadSession` boundary | partial | threshold is 4 MiB at `GraphApiService.kt:429`, not the 10 MiB the spec recommends; sequential PUTs respect 320 KiB / 60 MiB limits |
| Resume from `nextExpectedRanges` + cross-restart persistence | partial | `UploadSessionStore`, `GraphApiService.kt:497-551` + GET probe at `:622-640`; 416 branch absent |
| `@microsoft.graph.conflictBehavior` user-selectable | partial | hard-coded `"replace"` at `GraphApiService.kt:522` |
| `deferCommit: true` | n/a | not needed in MVP |
| `If-Match` precondition on createUploadSession | gap | catches concurrent edits |
| `If-Match` / `@odata.etag` on mutating POST (`moveItem` etc.) | gap | called out in `onedrive-robustness.md` §4 |
| `fileSize` field → early 507 on quota fail | gap | cheap to add |

### Download
| Capability | Status | Notes |
|---|---|---|
| `@microsoft.graph.downloadUrl` preauth (~1h) | used | `GraphApiService.kt:176-189` (uses `item.downloadUrl` when present, skips bearer; falls back to `/content`) |
| `Range` header on resolved download URL (parallel segments) | gap | no Range header anywhere in download path |
| `If-None-Match` + cTag for conditional GET | gap | makes unchanged-file refreshes free |

### Hashes and metadata
| Capability | Status | Notes |
|---|---|---|
| `file.hashes.quickXorHash` / `sha1Hash` / `sha256Hash` | partial | surfaced at `OneDriveProvider.kt:265`, used for **remote** change detection at `Reconciler.kt:117`; **local** scanner still mtime+size only (`LocalScanner.kt:100-104`) |
| `fileSystemInfo` round-trip (createdDateTime/lastModifiedDateTime) | gap | parsed in `DriveItem.kt:91-94` but not threaded to `CloudItem.modified` or upload |
| `malware` facet → skip download | gap | trivial guard |
| `quota` facet on `/me/drive` | used | `GraphApiService.kt:57-84` |

### Batch / throttle
| Capability | Status | Notes |
|---|---|---|
| `$batch` max 20, `dependsOn` ordering | gap | bulk metadata reads during reconciliation |
| Per-inner-response 429 retry inside batch | gap | depends on `$batch` |
| `Retry-After` strict + pause-all-threads on 429 | used | `HttpRetryBudget.kt:102-125` (circuit breaker + concurrency halving); `GraphApiService.kt:284-289, 789-803` honors header + JSON body fallback |
| Per-tenant concurrency calibration (Personal / Business / GCC) | gap | flagged in `onedrive-robustness.md` |
| `HttpRetryBudget` per-provider override surface | gap | constants currently global |

### Sharing
| Capability | Status | Notes |
|---|---|---|
| `createLink` idempotent per app/type | partial | `GraphApiService.kt:824-860` always POSTs without checking `listPermissions` for an existing matching link; relies on Graph server idempotency |

### Long-running
| Capability | Status | Notes |
|---|---|---|
| `copy` 202 + monitor URL polling | n/a | not in MVP path |
| `move` synchronous PATCH | used | `GraphApiService.kt:384-427` |

---

## Internxt Drive

### Auth
| Capability | Status | Notes |
|---|---|---|
| BIP39 mnemonic → per-file keys; PBKDF2 from password | used | `InternxtCrypto.kt`, `InternxtProvider.kt:174-178` |
| JWT proactive refresh | partial | `AuthService.kt:268-271` refreshes when `isJwtExpired()` is **already true**; no pre-expiry margin (e.g. 1 day before `exp`) |
| 401 → forced logout, no silent re-auth | as-designed | `InternxtApiService.kt:697-707` |
| Required headers `internxt-client` / `internxt-version` / `x-internxt-desktop-header` | used | `InternxtHeaders.kt:18-23` set on every call; values hard-coded `unidrive`/`0.0.1`/`internxt-desktop-dev-header` (allowlist risk) |

### Listing
| Capability | Status | Notes |
|---|---|---|
| `GET /files` `/folders` with `limit/offset/sort/order/status/updatedAt` | used | `InternxtApiService.kt:42-57, 90-106` |
| `status=EXISTS\|TRASHED\|DELETED` selectable per call | partial | hard-coded `status=ALL` at `InternxtApiService.kt:50` |
| Page size (no public maximum) | known | default 50 at `InternxtApiService.kt:93,102` |

### Change detection
| Capability | Status | Notes |
|---|---|---|
| `updatedAt` filter as "modified-since" poll | used | `InternxtApiService.kt:55,95,104` |
| Sync-token / cursor | API gap | client maintains own snapshot diff |
| WebSocket gateway integration (`FILE_*` / `FOLDER_*` / `PLAN_UPDATED`) | gap | zero `WebSocket`/`socket.io` matches in `core/providers/internxt`; protocol must be reverse-engineered from drive-desktop (see `internxt-websocket-feasibility.md`) |
| WebSocket reconnect / replay semantics | gap | depends on WS client existing |

### Upload
| Capability | Status | Notes |
|---|---|---|
| Two-layer: metadata via drive-server, blobs via network bridge | used | `InternxtApiService.kt:498-576`; AES-256-GCM per-file key |
| Multipart at ≥100 MiB threshold | gap | constants exist in `InternxtConfig.kt:19-26` (50 MiB chunk, 6 concurrent) but `InternxtProvider.kt:189` notes "not yet consumed" — single-shard uploads only |
| File size cap | partial | `InternxtApiService.kt:644` `getFileLimits()` queries server cap dynamically (UD-364) |
| Resumable upload protocol | API gap | no tus-style offset resumption upstream |
| Hash-based dedup | API gap | upstream backend does not expose content hashes |
| mtime on replaceFile | partial | `InternxtApiService.kt:166-168` sends `modificationTime` on replace path; create path still doesn't |
| In-flight de-dup of duplicate concurrent requests | partial | `InternxtApiService.kt:82-88` `folderContentsDedup` (UD-205) covers `getFolderContents` only; `getFileMeta`/`listFiles`/`listFolders` uncovered |
| Bottleneck throttle (1–2 concurrent, 500–1000 ms, priorities) | gap | `HttpRetryBudget` exists in `:app:core` but is not wired into `InternxtApiService` |

### Download
| Capability | Status | Notes |
|---|---|---|
| Range requests at network/bridge layer | used | bridge handles internally; no client knobs |
| Mirror selection in `inxt-js` | opaque | no client knobs |

### Trash, versions, sharing
| Capability | Status | Notes |
|---|---|---|
| Trash via `POST /storage/trash/add` | used | `InternxtApiService.kt:399-432` |
| Versioning history | API gap | no server-side version history |
| In-place uuid-preserving overwrite | partial | `InternxtApiService.kt:154-178` `replaceFile` preserves uuid (UD-366) — softer than no versioning |
| Private + public sharings; VIEWER/EDITOR/ADMIN/OWNER; password + optional expiry | partial | document MVP-supported subset |

### Quota / events
| Capability | Status | Notes |
|---|---|---|
| `usage` module | used | `InternxtApiService.kt:625-631` |
| `PLAN_UPDATED` WebSocket event | gap | depends on WS client existing |

### Rate limits
| Capability | Status | Notes |
|---|---|---|
| JSON-body `retry_after` honored | used | `InternxtApiService.kt:40` `RETRY_AFTER_REGEX` (UD-335) |
| `Retry-After` HTTP header honored | gap | only body-side hint currently consulted |

### Production bugs (from `internxt-robustness.md`, verified)
| Item | Status | Notes |
|---|---|---|
| HTML-body guard on streaming crypto | used | `InternxtApiService.kt:470` `assertNotHtml` — already closed |
| `NonCancellable` wrap on auth refresh under Pass-2 cancellation | used | `RefreshableTokenLatch.withRefresh` (`AuthService.kt:305-318`, UD-338) — already closed |
| `buildFolderPath` silent-empty → ~84k duplicate `remote_id` rows | gap | `InternxtProvider.kt:850` still returns `""`; phantom-folder root cause not fixed |
| State.db duplicate-`remote_id` migration / `unidrive repair` | gap | one-shot cleanup to drop the duplicate rows already produced |
| 401 → automatic refresh-and-replay (mid-session expiry race) | gap | currently throws without retry-with-fresh-token |
| Retry coverage on mutating verbs (`deleteFile`/`deleteFolder`/`putEncryptedShard`/`downloadFileStreaming`) | gap | `retryOnTransient` wraps create/move/trash only |
| `finishUpload` idempotency | gap | second call after dropped 200 creates orphan fileId |
| `encryptVersion` hard-coded `03-aes` | gap | legacy `02-rsa` buckets unreadable |

---

## Adopt next — ranked

High — correctness or large efficiency win:
1. Internxt phantom-folder root cause (`buildFolderPath` returning `""`) — fixes ongoing 84k-row state-db corruption
2. Internxt state-db duplicate-`remote_id` cleanup (`unidrive repair` one-shot)
3. Internxt `HttpRetryBudget` wiring into `InternxtApiService`
4. OneDrive 410 Gone `resyncChanges*` handling on delta path
5. OneDrive lifecycle webhook events (`reauthorizationRequired` / `subscriptionRemoved` / `missed`) + in-repo notification HTTP endpoint with `validationToken` echo
6. OneDrive `fileSystemInfo` round-trip (mtime/ctime on upload and download)
7. OneDrive `file.hashes` wired into **local** change detector (remote side already uses it)
8. OneDrive `If-Match` precondition on `createUploadSession`
9. OneDrive `If-Match` / `@odata.etag` on mutating POST (`moveItem` etc.)
10. Internxt WebSocket gateway integration (reverse-engineer protocol; design replay-on-reconnect)
11. Internxt JWT refresh with pre-expiry margin (currently fires only after expiry)
12. Internxt 401 → automatic refresh-and-replay (mid-session race)
13. Internxt multipart upload — wire the existing constants to a real chunked-upload code path
14. Internxt request prioritization + extend dedup beyond `folderContentsDedup`

Medium — efficiency:
15. OneDrive `Range` against `@microsoft.graph.downloadUrl` for parallel-segment download
16. OneDrive `If-None-Match` + cTag conditional GET
17. OneDrive `$select` slim payload on delta
18. OneDrive `$batch` with `dependsOn` + per-inner 429 retry
19. OneDrive `createUploadSession` 416 branch
20. OneDrive `conflictBehavior` user-selectable
21. OneDrive `createLink` client-side dedupe via `listPermissions`
22. OneDrive per-tenant concurrency calibration (Personal / Business / GCC)
23. OneDrive `HttpRetryBudget` per-provider override surface
24. Internxt `Retry-After` header read (body hint already honored)
25. Internxt retry coverage on `deleteFile`/`deleteFolder`/`putEncryptedShard`/`downloadFileStreaming`
26. Internxt `finishUpload` idempotency (avoid orphan fileId on dropped 200)
27. Internxt local chunk-tombstone for upload resume

Low — guards and UX:
28. OneDrive `malware` facet skip on download
29. OneDrive `fileSize` precheck → early 507
30. Internxt destructive-overwrite warning + opt-in rename-and-keep
31. Internxt `status` filter selectable per call (currently hard-coded `ALL`)
32. Internxt hard-coded client header values (allowlist tightening risk)
33. Internxt `encryptVersion` legacy `02-rsa` support
34. Document Internxt API limitations (no sync cursor, no resumable upload, no server mtime, no versioning, no batch)
