# OneDrive provider

Talks to Microsoft Graph v1.0 (`https://graph.microsoft.com/v1.0`) through `GraphApiService`, which centralises bearer-token auth, 401-on-expiry refresh, and the 429/503 throttle budget.

## API surface used

| Endpoint | Method in `GraphApiService` | Notes |
|---|---|---|
| `GET /me/drive/root/delta` (+ `@odata.nextLink` / `@odata.deltaLink`) | `getDelta` | Cursor-replay retry on truncated bodies; cursor is opaque, treated as long-lived. |
| `GET /me/drive/items/{id}/content` and pre-signed `@microsoft.graph.downloadUrl` | `downloadFile` | `downloadUrl` is short-lived (Microsoft: "might expire within minutes"); resolved via `getItemById` immediately before GET, never cached. Azure-CDN closes mid-stream on ~5 % of 1–500 MB files; same-URL retry. |
| `GET /me/drive/items/{id}` | `getItemById` | Used to refresh `downloadUrl` right before a download. |
| `GET /me/drive/root:/{path}` and `GET /me/drive/root` | `getItemByPath` | ZWJ-emoji path segments can 409 after correct percent-encoding (Graph normalises composite codepoints differently from the filesystem). |
| `GET /me/drive/root/children` and `:/{path}:/children` | `listChildren` | Paginated via `@odata.nextLink` with `$top=999`. |
| `PUT /me/drive/root:/{path}:/content` | `uploadSimple` | ≤4 MiB payloads. |
| `POST :/createUploadSession` + chunked `PUT` + `GET` (status) | `resolveUploadSession`, `uploadChunkWithRetry` | 10 MiB chunks (multiple of 320 KiB), `UploadSessionStore` persists session URL + expiry across daemon restarts; on 5xx GET session status and trim `currentBytes` to the server-committed offset. `conflictBehavior: replace`. |
| `DELETE /me/drive/items/{id}` | `deleteItem` | 204 and 404 both treated as success (idempotent). |
| `POST /me/drive/root/children` etc. | `createFolder` | Graph defaults to `conflictBehavior: fail`. |
| `PATCH /me/drive/items/{id}` | `moveItem` | Only sets `parentReference` when the parent actually changes; one extra `getItemByPath` to resolve the new parent's id. No cross-drive moves. |
| `GET /me/drive` | `getDrive` / `getQuota` | |
| `GET /me` | `getMe` | Token-validation paths. |
| `POST /me/drive/items/{id}/createLink` | `createSharingLink` | |
| `GET /me/drive/items/{id}/permissions` | `listPermissions` | |
| `DELETE /me/drive/items/{id}/permissions/{permId}` | `deletePermission` | |
| `GET /me/drive/sharedWithMe` | `listSharedWithMe` | Wired but not surfaced through `CloudProvider`. |
| `POST /subscriptions`, `PATCH`, `DELETE` | webhook lifecycle | |

## Robustness

- **Auth refresh** is per-call: `TokenManager.isNearExpiry(5 min)` is checked at each request; on 401 the token is force-refreshed once. Refresh is `withContext(NonCancellable)`-wrapped and mutex-serialised so sibling cancellation can't half-write the token to disk and a 401 storm produces exactly one refresh.
- **Retry placement** is inline at the HTTP layer (`authenticatedRequest`, `downloadFile`); `SyncEngine` only sees `Done` or terminal exceptions.
- **Throttle budget** (`HttpRetryBudget`): shared across coroutines on one `GraphApiService` with `maxConcurrency = 8`, 200 ms steady spacing post-throttle, 500 ms storm spacing. Storm trigger = 4 throttles in 20 s → halve concurrency, pause `Retry-After × 1.2`; recovery +1 permit per 5 min of clean traffic.
- **Retry-After** is honoured header-first, then JSON-body fallback (`error.retryAfterSeconds`). Per-request cap 5 attempts / 5 min single backoff / 15 min cumulative. Download-only flake budget: 3 attempts × 2 s × 2ⁿ for mid-stream Azure-CDN drops.
- **Idempotency**: GET / PUT (simple+session) / DELETE are replay-safe. The content PUT, upload-session creation and DELETE carry `If-Match` when the engine supplies a plan-time eTag; `move` guards with the eTag of the item it fetches just before the PATCH, which narrows the race window but does not close the engine's stale-view window.
- **HTML-body guard**: download path rejects 200 + `text/html` responses (captive-portal / login-page CDN payloads).
- **Streaming**: `prepareGet().execute { bodyAsChannel() }` with an 8 KiB ring buffer; download memory is constant regardless of file size.

## Vendor limits and signals

**Published** = in the linked Microsoft documentation (limits are defaults that Microsoft may change). **Observed** = seen in unidrive runs, not documented by the vendor.

Throttling:

- Published: 429 and 503 both mean throttling and carry `Retry-After` in seconds. Throttled requests still count against the limits, so retrying early prolongs the throttle, and an app that keeps ignoring `Retry-After` can be blocked outright. While throttled, a multi-threaded client pauses all its requests, not only the failed one (`HttpRetryBudget` does this). Without the header, back off exponentially. ([Graph throttling](https://learn.microsoft.com/en-us/graph/throttling), [SharePoint Online throttling](https://learn.microsoft.com/en-us/sharepoint/dev/general-development/how-to-avoid-getting-throttled-or-blocked-in-sharepoint-online), [scan guidance](https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/scan-guidance))
- Published: SharePoint Online (OneDrive for Business) returns no IETF `RateLimit` headers; do not build on them.
- Published, OneDrive for Business and SharePoint: calls cost resource units. 1 for delta with a token, a single-item get and a download; 2 for list children, create, update, delete and upload; 5 for every permission call. Limits apply per app and tenant (smallest licence tier: 1,250 units per minute and 1,200,000 per day) and per user (3,000 requests per 5 minutes), and scale with the licence count. No numbers are published for personal accounts.
- Published: throttling is likelier at peak hours. Polling and rescanning collections invites it; the vendor's pattern is delta plus change notifications, with a safety-net delta sweep no more often than once a day.
- Observed: the pre-signed download CDN answers 429/503 with the wait only in the JSON body (`retryAfterSeconds`); the provider reads the header first, then the body.

Client identity:

- Published, SharePoint Online: traffic that names its app (a registered app ID plus `User-Agent: ISV|CompanyName|AppName/Version`) is prioritised over undecorated traffic. ([SharePoint Online throttling](https://learn.microsoft.com/en-us/sharepoint/dev/general-development/how-to-avoid-getting-throttled-or-blocked-in-sharepoint-online))
- Sign-in uses the personal-account authority (`OneDriveConfig.DEFAULT_AUTH_ENDPOINT`) unless the profile sets `authority_url`.

Uploads:

- Published: a simple `PUT` takes files up to 250 MB. The switch to an upload session at 4 MiB (`OneDriveProvider.upload`) is unidrive's choice for resumability, not a Graph limit.
- Published, upload sessions ([createUploadSession](https://learn.microsoft.com/en-us/graph/api/driveitem-createuploadsession)): fragments go in order; every fragment but the last is a multiple of 320 KiB (other sizes can fail the commit after the last byte) and under 60 MiB; 5 to 10 MiB is the recommended size. Do not send `Authorization` on the `PUT` to `uploadUrl` (it can answer 401). Retry 5xx and dropped connections with exponential backoff; limit retries for other errors. 404 means the session is gone: start over. 416 means the fragment was already received: `GET` the session for the missing ranges (`nextExpectedRanges` may list several ranges and is not a fragment-size hint). A session that sees no fragment before `expirationDateTime` is discarded; each fragment extends it. A name conflict can surface only on the last fragment.

Listing and delta ([delta](https://learn.microsoft.com/en-us/graph/api/driveitem-delta)):

- Published: track items by `id`, never by path; delta omits `parentReference.path` because a folder rename does not return its descendants. The feed carries the latest state per item, not each change, and an item can appear more than once: the last occurrence wins. Deleted items carry the `deleted` facet. Delta omits some properties by service (Business: `ctag`, and `name` on deletes; personal: `ctag` and `size` on deletes).
- Published: paging through `children` is not guaranteed to return every item while writes happen; `delta` is the only complete enumeration.
- Published: 410 Gone carries a `Location` for a fresh enumeration and a `resyncChangesApplyDifferences` / `resyncChangesUploadDifferences` code that says how to reconcile.

Names ([restrictions](https://support.microsoft.com/en-us/office/restrictions-and-limitations-in-onedrive-and-sharepoint-64883a5d-228e-48f5-b3d2-eb39e07630fa)):

- Published: names cannot contain `" * : < > ? / \ |`, cannot start or end with a space, and cannot be `.lock`, `CON`, `PRN`, `AUX`, `NUL`, `COM0`-`COM9`, `LPT0`-`LPT9`, `_vti_`, `desktop.ini` or start with `~$`. Some organisations also refuse `#` and `%`. The maximum file size is 250 GB.
- The provider does not pre-validate names. Graph refuses them with `400 invalidRequest`, which `OneDriveProvider.upload` turns into a `PermanentUploadFailureException` so the file is not retried.

## Server-family matrix

| Tenant | Throttle docs | `retryAfterSeconds` body | CDN edge | HTML-throttle response |
|---|---|---|---|---|
| Personal (consumer) | "Lower limits" — no numbers | Yes | Azure Blob | Observed |
| Business (Office 365) | Documented: resource units per app, tenant and user (see above) | Yes | SharePoint CDN | Observed (heavier) |
| GCC / GCC High / DoD | Not publicly documented | Untested | US-government data centres | Untested |

Personal-vs-Business concurrency: the 8-permit default holds for both empirically; Business tenants with conditional-access policies may need a tighter cap.

## Known gaps

The forward work — `If-Match` on PATCH, batch API, `fileSystemInfo` round-trip, hash-based local change detection, soft-vs-hard delete semantics — is in `BACKLOG.md` and `BOOSTERS.md`.

## Request-id correlation

Graph emits `request-id` on every response (preferred) plus `client-request-id` (echoed). `GraphApiService.extractRequestId` reads the former first; the value rides on `ProviderException.requestId` and shows up in `unidrive.log` as `requestId=…`.
