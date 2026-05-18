# OneDrive provider

Talks to Microsoft Graph v1.0 (`https://graph.microsoft.com/v1.0`) through `GraphApiService`, which centralises bearer-token auth, 401-on-expiry refresh, and the 429/503 throttle budget.

## API surface used

| Endpoint | Method in `GraphApiService` | Notes |
|---|---|---|
| `GET /me/drive/root/delta` (+ `@odata.nextLink` / `@odata.deltaLink`) | `getDelta` | Cursor-replay retry on truncated bodies; cursor is opaque, treated as long-lived. |
| `GET /me/drive/items/{id}/content` and pre-signed `@microsoft.graph.downloadUrl` | `downloadFile` | `downloadUrl` is short-lived (~1 h); resolved via `getItemById` immediately before GET, never cached. Azure-CDN closes mid-stream on ~5 % of 1–500 MB files; same-URL retry. |
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
- **Idempotency**: GET / PUT (simple+session) / DELETE are replay-safe. PATCH (`moveItem`) does **not** thread `If-Match`/eTag today — concurrent editors can race.
- **HTML-body guard**: download path rejects 200 + `text/html` responses (captive-portal / login-page CDN payloads).
- **Streaming**: `prepareGet().execute { bodyAsChannel() }` with an 8 KiB ring buffer; download memory is constant regardless of file size.

## Server-family matrix

| Tenant | Throttle docs | `retryAfterSeconds` body | CDN edge | HTML-throttle response |
|---|---|---|---|---|
| Personal (consumer) | "Lower limits" — no numbers | Yes | Azure Blob | Observed |
| Business (Office 365) | Documented per service | Yes | SharePoint CDN | Observed (heavier) |
| GCC / GCC High / DoD | Not publicly documented | Untested | US-government data centres | Untested |

Personal-vs-Business concurrency: the 8-permit default holds for both empirically; Business tenants with conditional-access policies may need a tighter cap.

## Known gaps

The forward work — `If-Match` on PATCH, batch API, `fileSystemInfo` round-trip, hash-based local change detection, soft-vs-hard delete semantics — is in `BACKLOG.md` and `BOOSTERS.md`.

## Request-id correlation

Graph emits `request-id` on every response (preferred) plus `client-request-id` (echoed). `GraphApiService.extractRequestId` reads the former first; the value rides on `ProviderException.requestId` and shows up in `unidrive.log` as `requestId=…`.
