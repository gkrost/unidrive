# API boosters — Internxt + OneDrive

Labels: `used` (wired), `partial` (scaffolded), `gap` (missing), `n/a` (not in MVP scope), `API gap` (upstream doesn't expose it), `known` (server-side fact).

---

## OneDrive (Microsoft Graph)

### Change detection
| Capability | Status | Key refs |
|---|---|---|
| `/drive/root/delta` with deltaLink persistence | used | dedupe by `id`; ignore `parentReference.path` |
| `?token=latest` skip initial crawl on reconnect | used | `GraphApiService.kt:134` |
| 410 Gone `resyncChanges*` restart | gap | `GraphApiService.kt:137-169` |
| `$select` on delta to slim payload | gap | |
| `Prefer: deltashow*` | n/a | SPO/ODB only |
| Webhook subscription on driveItem | partial | `GraphApiService.kt:909-936` |
| Subscription lifetime auto-renewal | used | `SubscriptionRenewalScheduler.kt:31-82` |
| Lifecycle events handling | gap | |
| Periodic delta sweep as safety net | used | `AdaptiveInterval.kt` |

### Upload
| Capability | Status | Key refs |
|---|---|---|
| Simple PUT <4 MB | used | `GraphApiService.kt:305-332` |
| `createUploadSession` boundary | partial | 4 MiB threshold at `GraphApiService.kt:429` |
| Resume from `nextExpectedRanges` | partial | `UploadSessionStore`, `GraphApiService.kt:497-551` |
| `conflictBehavior` user-selectable | partial | hard-coded `replace` at `GraphApiService.kt:522` |
| `deferCommit: true` | n/a | |
| `If-Match` on createUploadSession | gap | |
| `If-Match`/`@odata.etag` on mutating POST | gap | |
| `fileSize` precheck → early 507 | gap | |

### Download
| Capability | Status | Key refs |
|---|---|---|
| `@microsoft.graph.downloadUrl` preauth (~1h) | used | `GraphApiService.kt:176-189` |
| `Range` header parallel segments | gap | |
| `If-None-Match` + cTag conditional GET | gap | |

### Hashes and metadata
| Capability | Status | Key refs |
|---|---|---|
| `file.hashes.quickXorHash`/`sha1Hash`/`sha256Hash` | partial | `OneDriveProvider.kt:265` |
| `fileSystemInfo` round-trip | gap | `DriveItem.kt:91-94` |
| `malware` facet skip download | gap | |
| `quota` facet on `/me/drive` | used | `GraphApiService.kt:57-84` |

### Batch / throttle
| Capability | Status | Key refs |
|---|---|---|
| `$batch` max 20, `dependsOn` ordering | gap | |
| Per-inner 429 retry inside batch | gap | |
| `Retry-After` strict + pause-all-threads on 429 | used | `HttpRetryBudget.kt:102-125` |
| Per-tenant concurrency calibration | gap | |
| `HttpRetryBudget` per-provider override | gap | |

### Sharing
| Capability | Status | Key refs |
|---|---|---|
| `createLink` idempotent | partial | `GraphApiService.kt:824-860` |

### Long-running
| Capability | Status | Key refs |
|---|---|---|
| `copy` 202 + monitor URL polling | n/a | |
| `move` synchronous PATCH | used | `GraphApiService.kt:384-427` |

---

## Internxt Drive

### Auth
| Capability | Status | Key refs |
|---|---|---|
| BIP39 mnemonic → per-file keys | used | `InternxtCrypto.kt` |
| JWT proactive refresh | partial | `AuthService.kt:268-271` |
| 401 → forced logout, no silent re-auth | as-designed | `InternxtApiService.kt:697-707` |
| Required client headers | used | `InternxtHeaders.kt:18-23` |

### Listing
| Capability | Status | Key refs |
|---|---|---|
| `GET /files` `/folders` with pagination | used | `InternxtApiService.kt:42-57,90-106` |
| `status` filter selectable per call | partial | hard-coded `ALL` at `InternxtApiService.kt:50` |
| Page size (no public maximum) | known | default 50 at `InternxtApiService.kt:93,102` |

### Change detection
| Capability | Status | Key refs |
|---|---|---|
| `updatedAt` modified-since poll | used | `InternxtApiService.kt:55,95,104` |
| Sync-token / cursor | API gap | |
| WebSocket gateway integration | gap | |
| WebSocket reconnect / replay | gap | |

### Upload
| Capability | Status | Key refs |
|---|---|---|
| Two-layer: metadata + blob bridge | used | `InternxtApiService.kt:498-576` |
| Multipart at ≥100 MiB threshold | gap | `InternxtConfig.kt:19-26` |
| File size cap | partial | `InternxtApiService.kt:644` |
| Resumable upload protocol | API gap | |
| Hash-based dedup | API gap | |
| mtime on replaceFile | partial | `InternxtApiService.kt:166-168` |
| In-flight dedup of concurrent requests | partial | `InternxtApiService.kt:82-88` |
| Bottleneck throttle | gap | |

### Download
| Capability | Status | Key refs |
|---|---|---|
| Range requests at bridge layer | used | bridge handles internally |
| Mirror selection | opaque | |

### Trash, versions, sharing
| Capability | Status | Key refs |
|---|---|---|
| Trash via `POST /storage/trash/add` | used | `InternxtApiService.kt:399-432` |
| Versioning history | API gap | |
| In-place uuid-preserving overwrite | partial | `InternxtApiService.kt:154-178` |
| Private + public sharings | partial | |

### Quota / events
| Capability | Status | Key refs |
|---|---|---|
| `usage` module | used | `InternxtApiService.kt:625-631` |
| `PLAN_UPDATED` WebSocket event | gap | |

### Rate limits
| Capability | Status | Key refs |
|---|---|---|
| JSON-body `retry_after` honored | used | `InternxtApiService.kt:40` |
| `Retry-After` HTTP header honored | gap | |

### Production bugs (from `internxt-robustness.md`)
| Item | Status | Key refs |
|---|---|---|
| HTML-body guard on streaming crypto | used | `InternxtApiService.kt:470` |
| `NonCancellable` wrap on auth refresh | used | `AuthService.kt:305-318` |
| `buildFolderPath` empty → duplicate `remote_id` rows | gap | `InternxtProvider.kt:850` |
| State.db duplicate-`remote_id` migration / repair | gap | |
| 401 → automatic refresh-and-replay | gap | |
| Retry coverage on mutating verbs | gap | |
| `finishUpload` idempotency | gap | |
| `encryptVersion` hard-coded `03-aes` | gap | |

---

## Adopt next — ranked

High — correctness or large efficiency win:
1. Internxt phantom-folder root cause (`buildFolderPath`)
2. Internxt state-db duplicate-`remote_id` cleanup
3. Internxt `HttpRetryBudget` wiring
4. OneDrive 410 Gone `resyncChanges*` handling
5. OneDrive lifecycle webhook events + notification endpoint
6. OneDrive `fileSystemInfo` round-trip
7. OneDrive `file.hashes` wired into local detector
8. OneDrive `If-Match` on `createUploadSession`
9. OneDrive `If-Match`/`@odata.etag` on mutating POST
10. Internxt WebSocket gateway integration
11. Internxt JWT refresh with pre-expiry margin
12. Internxt 401 → automatic refresh-and-replay
13. Internxt multipart upload
14. Internxt request prioritization + dedup extension

Medium — efficiency:
15. OneDrive `Range` parallel-segment download
16. OneDrive `If-None-Match` + cTag conditional GET
17. OneDrive `$select` slim delta payload
18. OneDrive `$batch` with `dependsOn` + per-inner 429 retry
19. OneDrive `createUploadSession` 416 branch
20. OneDrive `conflictBehavior` user-selectable
21. OneDrive `createLink` client-side dedupe
22. OneDrive per-tenant concurrency calibration
23. OneDrive `HttpRetryBudget` per-provider override
24. Internxt `Retry-After` header read
25. Internxt retry coverage on mutating verbs
26. Internxt `finishUpload` idempotency
27. Internxt local chunk-tombstone for upload resume

Low — guards and UX:
28. OneDrive `malware` facet skip
29. OneDrive `fileSize` precheck → early 507
30. Internxt destructive-overwrite warning
31. Internxt `status` filter selectable per call
32. Internxt hard-coded client header values
33. Internxt `encryptVersion` legacy `02-rsa` support
34. Document Internxt API limitations
