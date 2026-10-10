# API boosters — Internxt + OneDrive

Labels: `used` (wired), `partial` (scaffolded), `gap` (missing), `n/a` (not in MVP scope), `API gap` (upstream doesn't expose it), `known` (server-side fact).

Key refs name symbols (class, function), not line numbers — the code moves faster than this table.

---

## OneDrive (Microsoft Graph)

### Change detection
| Capability | Status | Key refs |
|---|---|---|
| `/drive/root/delta` with deltaLink persistence | used | dedupe by `id`; ignore `parentReference.path` |
| `?token=latest` skip initial crawl on reconnect | used | `GraphApiService.getDelta` (`fromLatest`) |
| 410 Gone `resyncChanges*` restart | used | `OneDriveProvider.getDeltaConverting410` → `DeltaCursorExpiredException` → full re-enumeration |
| `$select` on delta to slim payload | gap | #127 |
| `Prefer: deltashow*` | n/a | SPO/ODB only |
| Webhook subscription on driveItem | partial | `GraphApiService.createSubscription`; validation endpoint missing |
| Subscription lifetime auto-renewal | used | `SubscriptionRenewalScheduler` |
| Lifecycle events handling | gap | #111 |
| Periodic delta sweep as safety net | used | `AdaptiveInterval` |

### Upload
| Capability | Status | Key refs |
|---|---|---|
| Simple PUT <4 MB | used | `GraphApiService.uploadSimple`; threshold in `OneDriveProvider` |
| `createUploadSession` boundary | partial | 4 MiB threshold in `OneDriveProvider` |
| Resume from `nextExpectedRanges` | partial | `UploadSessionStore`, `GraphApiService.resolveUploadSession` |
| `conflictBehavior` user-selectable | partial | parameter on the upload calls; the provider picks `replace` (own file) or `rename` (keep-both), nothing in config |
| `deferCommit: true` | n/a | |
| `If-Match` on createUploadSession | used | `ifMatchETag` on `createUploadSession`; stored sessions are bound to it |
| `If-Match`/`@odata.etag` on mutating calls | used | `uploadSimple`, `moveItem`, `deleteItem` |
| `fileSize` precheck → early 507 | gap | #154 |

### Download
| Capability | Status | Key refs |
|---|---|---|
| `@microsoft.graph.downloadUrl` preauth (~1h) | used | `GraphApiService.downloadFile`; refreshed when the tempauth token expires |
| `Range` header parallel segments | gap | #125 |
| `If-None-Match` + cTag conditional GET | gap | #126 |

### Hashes and metadata
| Capability | Status | Key refs |
|---|---|---|
| `file.hashes.quickXorHash`/`sha1Hash`/`sha256Hash` | used | `OneDriveProvider` prefers `quickXorHash`, falls back to `sha256Hash`; `hashAlgorithm()` is QuickXor |
| `fileSystemInfo` round-trip | used | `GraphApiService.patchFileSystemInfo`, `FileSystemInfo` in `DriveItem.kt` |
| `malware` facet skip download | gap | #153 |
| `quota` facet on `/me/drive` | used | `GraphApiService.getQuota` |

### Batch / throttle
| Capability | Status | Key refs |
|---|---|---|
| `$batch` max 20, `dependsOn` ordering | gap | #124 |
| Per-inner 429 retry inside batch | gap | |
| `Retry-After` strict + pause-all-threads on 429 | used | `HttpRetryBudget` (`:app:core`) |
| Per-tenant concurrency calibration | gap | #130 |
| `HttpRetryBudget` per-provider override | gap | #131 |

### Sharing
| Capability | Status | Key refs |
|---|---|---|
| `createLink` idempotent | partial | `GraphApiService.createSharingLink`; client-side dedupe #129 |

### Long-running
| Capability | Status | Key refs |
|---|---|---|
| `copy` 202 + monitor URL polling | n/a | |
| `move` synchronous PATCH | used | `GraphApiService.moveItem` |

---

## Internxt Drive

### Auth
| Capability | Status | Key refs |
|---|---|---|
| BIP39 mnemonic → per-file keys | used | `InternxtCrypto` |
| JWT proactive refresh | used | `AuthService.isJwtNearExpiry`, 1-day pre-expiry margin |
| 401 → one forced refresh and replay; a second 401 is an auth failure | used | `InternxtApiService.withAuthRetry` (Drive REST only; Bridge uses Basic auth) |
| Required client headers | used | `InternxtHeaders`; `clientName`/`clientVersion`/`desktopHeader` configurable via `InternxtConfig` |

### Listing
| Capability | Status | Key refs |
|---|---|---|
| `GET /files` `/folders` with pagination | used | `InternxtApiService.listFiles`/`listFolders`, page size `LISTING_PAGE_SIZE` |
| `GET /files/sync` `/folders/sync` cursor listing | used | `InternxtApiService.getFilesSync`/`getFoldersSync`, `InternxtCursorListing` |
| `status` filter selectable per call | used | `status` parameter, default `"ALL"` |
| Page size (no public maximum) | known | `InternxtConfig.LISTING_PAGE_SIZE` = 999; the cursor listings take at most `SYNC_PAGE_SIZE` = 1000 |

### Change detection
| Capability | Status | Key refs |
|---|---|---|
| `updatedAt` modified-since poll | used | `InternxtApiService.listFiles`/`listFolders` (`updatedAt`) |
| Sync-token / cursor | used | cursor listing above (`nextCursor`) |
| WebSocket gateway integration | partial | `NotificationsClient` (socket.io wake signal, opened only by `sync --watch`, off with `INTERNXT_NOTIFICATIONS=off`); stops for the process when the endpoint is the wrong server (TLS identity or non-upgrade answer, #679); as of 2026-10-09 `notifications.internxt.com` presents a certificate for another host, so it never connects; #244 |
| WebSocket reconnect / replay | partial | reconnect with exponential backoff by socket.io; no replay of missed events |

### Upload
| Capability | Status | Key refs |
|---|---|---|
| Two-layer: metadata + blob bridge | used | `InternxtApiService.startUpload`/`finishUpload`, `createFile`/`replaceFile` |
| Multipart at ≥100 MiB threshold | gap | `InternxtConfig.MULTIPART_*` constants unconsumed, #578 |
| File size cap | used | `InternxtProvider.maxFileSizeBytes` (`GET /files/limits`) |
| Resumable upload protocol | API gap | client-side only: `UploadTombstoneStore` |
| Hash-based dedup | API gap | |
| mtime on replaceFile | used | `modificationTime` on `createFile`/`replaceFile` |
| In-flight dedup of concurrent requests | used | `InFlightDedup` on file and folder metadata, listings and folder contents |
| Bottleneck throttle | used | per-host `HttpRetryBudget` (Drive REST, Bridge), two-lane Foreground/Background priority |

### Download
| Capability | Status | Key refs |
|---|---|---|
| Range requests at bridge layer | used | bridge handles internally |
| Mirror selection | opaque | |

### Trash, versions, sharing
| Capability | Status | Key refs |
|---|---|---|
| Trash via `POST /storage/trash/add` | used | `InternxtApiService.trashItems` |
| Versioning history | API gap | |
| In-place uuid-preserving overwrite | used | `InternxtApiService.replaceFile` |
| Private + public sharings | gap | `InternxtProvider.capabilities()` declares no `Share` |

### Quota / events
| Capability | Status | Key refs |
|---|---|---|
| `usage` module | used | `InternxtApiService.getQuota` (`/users/usage`) |
| `PLAN_UPDATED` WebSocket event | gap | |

### Rate limits
| Capability | Status | Key refs |
|---|---|---|
| JSON-body `retry_after` honored | used | `InternxtApiService.RETRY_AFTER_REGEX` |
| `Retry-After` HTTP header honored | used | `InternxtApiService.parseRetryAfterHeader` |

### Production bugs (from the earlier Internxt robustness audit)
| Item | Status | Key refs |
|---|---|---|
| HTML-body guard on streaming crypto | used | `assertNotHtml` in the download path |
| `NonCancellable` wrap on auth refresh | used | `RefreshableTokenLatch` (`:app:core`) |
| `buildFolderPath` empty → duplicate `remote_id` rows | used | `InternxtProvider.buildFolderPath` returns null on a missing ancestor; delta drops such items |
| State.db duplicate-`remote_id` migration / repair | used | recorded in `CLOSED.md`; the state.db redesign gave rows a uuid identity |
| 401 → automatic refresh-and-replay | used | `withAuthRetry` |
| Retry coverage on mutating verbs | used | `retryOnTransient` |
| `finishUpload` idempotency | used | 409 reconcile via listing-based collision detection in `InternxtProvider` |
| `encryptVersion` hard-coded `03-aes` | partial | `03-aes` only; an unknown version fails fast in `InternxtProvider` |

---

## Adopt next — ranked

High — correctness or large efficiency win:
1. Internxt multipart upload (#578)
2. OneDrive lifecycle webhook events + notification endpoint (#111)
3. Internxt WebSocket gateway: verify the live endpoint (#244)

Medium — efficiency:
4. OneDrive `Range` parallel-segment download (#125)
5. OneDrive `If-None-Match` + cTag conditional GET (#126)
6. OneDrive `$select` slim delta payload (#127)
7. OneDrive `$batch` with `dependsOn` + per-inner 429 retry (#124)
8. OneDrive `createUploadSession` 416 branch
9. OneDrive `conflictBehavior` user-selectable
10. OneDrive `createLink` client-side dedupe (#129)
11. OneDrive per-tenant concurrency calibration (#130)
12. OneDrive `HttpRetryBudget` per-provider override (#131)

Low — guards and UX:
13. OneDrive `malware` facet skip (#153)
14. OneDrive `fileSize` precheck → early 507 (#154)
15. Internxt `encryptVersion` legacy `02-rsa` support
16. Document Internxt API limitations
