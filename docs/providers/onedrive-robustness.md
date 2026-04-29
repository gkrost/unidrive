# OneDrive (Microsoft Graph) — HTTP robustness audit

> Deliverable for [UD-320](../backlog/CLOSED.md#ud-320) (part of the UD-228
> cross-provider audit split — **reference / baseline**).
> Vendor docs: [Graph throttling](https://learn.microsoft.com/en-us/graph/throttling),
> [Best-practice client guidance](https://learn.microsoft.com/en-us/graph/best-practices-concept),
> [Files API](https://learn.microsoft.com/en-us/graph/api/resources/onedrive).
> Field data: 130 k-item OneDrive Personal drive (UD-712), Direct Microsoft
> Graph + the Azure Blob/CDN edge that hands out short-lived `downloadUrl`s.

OneDrive is the canonical baseline because it landed first (UD-207, UD-227,
UD-232) and most of the cross-provider primitives —
[`HttpRetryBudget`](../../core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt),
[`RequestId`](../../core/app/core/src/main/kotlin/org/krost/unidrive/http/RequestId.kt),
the `truncateErrorBody` log-noise guard — were extracted from
`GraphApiService` after they were proven in production here. The other
audits ([webdav](webdav-robustness.md), [hidrive](hidrive-robustness.md),
etc.) reference back to this file when their finding is "missing the
OneDrive equivalent of X".

## Status summary

| Dimension | Finding | Confidence |
|---|---|---|
| Non-2xx body parsing | `truncateErrorBody` keeps the diagnostic hint without bloating the log; `pickBackoffMsWithBody` parses `retryAfterSeconds` from the JSON body when the header is absent (UD-227) | High |
| Retry placement | HTTP layer — `authenticatedRequest` and the streaming `downloadFile` loop both retry transparently; SyncEngine sees Done/Throttle/Failure outcomes only | High |
| Retry-After source | Header (preferred) + JSON body `error.retryAfterSeconds` fallback (UD-227) | High |
| Idempotency | 401-refresh-once + same-URL replay; refresh is mutex-guarded and `NonCancellable` (UD-310) so sibling-cancellation doesn't abort the refresh mid-flight | High |
| Concurrency | `HttpRetryBudget(maxConcurrency = 8)` shared across all coroutines on one `GraphApiService`; halves on storm, restores on quiet (UD-232 + UD-200) | High |
| Streaming download | `prepareGet().execute { bodyAsChannel() }` → 8 KiB ring buffer; no allocation grows with file size (UD-329) | High |
| HTML-body guard | CDN captive-portal / login-page detection before any byte hits disk (UD-231) | High |
| Cancellation hygiene | `CancellationException` always re-thrown from catch blocks; flake-retry budget unaffected (UD-300) | High |
| Long-running refresh | `withContext(NonCancellable) { oauthService.refreshToken(...) }` — outlasts parent scope cancellation (UD-310) | High |

## 1. Non-2xx body parsing

Two shapes of body parsing live in `GraphApiService`:

**Log-noise guard** ([truncateErrorBody](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:841)). SharePoint-Online error responses are full HTML pages (~3-5 KiB inline CSS + branding) — one 503 dumps ~60 lines into `unidrive.log`. JSON bodies pass through unchanged; non-JSON bodies are reduced to first-line + char-count tail:

```
"<!DOCTYPE html>… [4823 chars, non-JSON body truncated]"
```

**Structured retry hint** ([parseRetryAfterFromJsonBody](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:371)). Graph's CDN edge sometimes omits the `Retry-After` header but encodes the wait inside the JSON error body:

```json
{ "error": { "code": "throttledRequest", "retryAfterSeconds": 5 } }
```

Both `error.retryAfterSeconds` and the top-level `retryAfterSeconds` are honoured. Falls back to the exponential schedule when neither is present.

The download path additionally guards against **HTML-content responses** that pass the status check ([GraphApiService.kt:275](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:275)). A captive-portal page or expired-URL login page would otherwise stream straight into the destination at the right byte count — UD-226 sweep detectors don't flag it because HTML is non-NUL content. The 200+`text/html` combination is treated as a retriable flake (UD-231).

## 2. Retry placement

Every HTTP entry point has its retry loop **inline at the HTTP layer**:

- [`authenticatedRequest(url, method)`](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:721) — bodyless GET / DELETE
- [`authenticatedRequest(url, method, body)`](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:782) — POST / PATCH / PUT with JSON
- [`downloadFile(itemId, destPath)`](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:200) — separate inline loop because `prepareGet().execute { }` (streaming-body lifetime) doesn't compose with the buffered shape of the two `authenticatedRequest` overloads

SyncEngine sees only `Done`, throttle-converted-to-`delay`, or a final `GraphApiException` / `AuthenticationException`. There is no SyncEngine-level retry; the action layer treats a propagated exception as fatal for the file.

Constants ([GraphApiService.kt:898](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:898)):

| Constant | Value | Purpose |
|---|---|---|
| `MAX_THROTTLE_ATTEMPTS` | 5 | Cap on per-request 429/503 retries |
| `MAX_SINGLE_BACKOFF_MS` | 300 000 (5 min) | Cap on a single Retry-After hint we'll honour |
| `MAX_TOTAL_THROTTLE_WAIT_MS` | 900 000 (15 min) | Cumulative throttle budget per request |
| `DEFAULT_BACKOFF_START_MS` | 2 000 | Exponential schedule when no header / body hint |
| `MAX_FLAKE_ATTEMPTS` | 3 | Download-only — IO/connection-mid-stream errors (UD-309) |

The flake-retry budget (3 attempts × 2 s × 2ⁿ) is download-specific because UD-309 surfaced the Azure Blob CDN closing connections mid-stream on ~5 % of files in the 1 MB–500 MB band.

## 3. Retry-After

Header-first, JSON-body fallback. Both forms are integer seconds (Graph never sends HTTP-date format).

```
Retry-After: 30                                                          ← header (preferred)
{"error": {..., "retryAfterSeconds": 30}}                                ← body (fallback)
```

Implementation at [pickBackoffMsWithBody](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:357) — the body parse handles both `error.retryAfterSeconds` (the documented Graph shape) and a top-level `retryAfterSeconds` (observed on some CDN edge responses). Server hint is capped at `MAX_SINGLE_BACKOFF_MS`; cumulative is capped at `MAX_TOTAL_THROTTLE_WAIT_MS`.

## 4. Idempotency

OneDrive's mutating verbs:

- **GET** — fully idempotent; 401 retry replays the same URL with a fresh token.
- **PUT (upload simple, < 4 MB)** — replay-safe because we hold the byte array in memory and `setBody(content)` re-emits it for each retry; Graph treats the upload as item-create-or-replace by path.
- **PUT (upload large, ≥ 4 MB)** — uses **upload sessions** ([uploadLargeFile](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:506)); each chunk is a `Content-Range`-tagged PUT to the session URL. Failed chunks resume from the last server-acknowledged byte (Graph maintains state). Idempotent by design.
- **POST (move, rename, sharingLink)** — Graph sends `If-Match` semantics through `eTag` precondition headers; our `moveItem` does not currently set them. Concurrent editors race the same way the WebDAV audit calls out for PUT.
- **DELETE** — idempotent: 404 on a previously-deleted item is silently treated as success at the SyncEngine layer.

Body-replay safety in `authenticatedRequest(url, method, body)`: the `body: String` is a value parameter, not a stream — Ktor's `setBody(body)` is called inside the retry loop on each attempt, so JSON payloads round-trip cleanly. Streaming bodies would need a builder-replay shape; only the upload-session chunk path uses streaming, and it has its own retry per chunk ([uploadChunkWithRetry](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:635)).

**OAuth refresh idempotency** ([TokenManager.getValidToken](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/TokenManager.kt:71)). UD-310 wraps the refresh in `withContext(NonCancellable) { ... }` because:

1. Pass-2 scope cancellation on a sibling coroutine's 401 was cancelling the in-flight refresh as collateral damage.
2. The `refresh_token` was perfectly valid; cancellation chewed through the flake budget logging the misleading `ScopeCoroutine was cancelled`.
3. Refresh is now atomic: it either completes and saves the new token to disk, or surfaces the OAuth error to the user — never half-runs.

`refreshMutex` serialises concurrent refresh attempts: a flood of 401s from a parallel pass produces exactly one refresh, not N.

## 5. Concurrency recommendations

OneDrive's recommendation is **read from documentation + reinforced by the throttle budget**:

| Layer | Setting | Source |
|---|---|---|
| Per-`GraphApiService` concurrency | `HttpRetryBudget(maxConcurrency = 8)` | UD-712 measured ~119 files/min as the steady-state rate before throttling kicks in; 8 permits keeps the pipeline full |
| Inter-request spacing (steady) | 0 ms | UD-200 — quiet steady state pays no spacing cost |
| Inter-request spacing (post-throttle, ≤ 60 s) | 200 ms | `minSpacingMs` — dampens concurrent wake-ups after a single 429 |
| Inter-request spacing (storm) | 500 ms | `stormSpacingMs` — circuit-open or within `postStormRecoveryMs=30 s` of close |
| Storm threshold | 4 throttles per 20 s | `stormThreshold` × `stormWindowMs` |
| Storm action | Halve concurrency, pause `Retry-After × 1.2` | `stormBackoffFactor=1.2` |
| Concurrency recovery | +1 permit per 5 min of clean traffic | `recoveryCleanIntervalMs` |

The `HttpRetryBudget` instance is shared across all coroutines on one `GraphApiService` so a sibling's 429 immediately tightens the spacing for everyone — pre-UD-232, six concurrent downloads each had their own 5-attempt allowance and re-burst in lockstep after the first storm (459 permanent failures observed on UD-712 = 2.9 % of files).

Personal vs Business: Microsoft documents Personal at "lower" limits but doesn't publish numbers. Empirically the 8-permit + 200-ms-spacing default holds for both; Business tenants with conditional-access policies may need a tighter cap (untested — leave for a future ticket).

## OneDrive — field observations

From the UD-712 130 k-item live sync and the multi-month UD-207/UD-227/UD-232 hardening cycle:

1. **Azure Blob CDN closes connections mid-stream.** The short-lived `downloadUrl` Graph hands out for `/drive/items/{id}` resolves to an Azure Blob endpoint behind CDN. ~5 % of files in the 1 MB–500 MB range trip a "Content-Length mismatch" mid-stream. Same-URL retry recovers almost all of them. UD-309 wired the flake-retry loop specifically for this.
2. **CDN serves HTML on tenant throttle.** When a tenant exceeds its throttle, the CDN edge sometimes returns HTTP 200 with `Content-Type: text/html` instead of the expected file bytes — a captive-portal-style "please wait" page. Without UD-231's content-type guard, the HTML streams straight into the destination at the matching byte count and corrupts the local file silently.
3. **`Retry-After` header is integer seconds.** Graph never sends HTTP-date format; the `pickBackoffMs` parser only handles integer-seconds.
4. **`retryAfterSeconds` JSON body is the fallback.** When the CDN edge response goes through a layer that strips response headers, the wait hint shows up only inside the JSON body. UD-227 added the body parse so we don't fall through to the 2 s default and re-storm.
5. **UD-329: streaming body for large files.** Pre-fix the download path was `httpClient.get(url) → response.body<ByteReadChannel>()` which on Ktor 3.x buffered the entire response into a single `byte[contentLength]` before exposing the channel — files larger than `Integer.MAX_VALUE` (~2.147 GiB) failed at allocation time with "Can't create an array of size N". `prepareGet(url).execute { response.bodyAsChannel() }` returns a true streaming channel; the 8 KiB ring buffer is the only memory the download holds.
6. **UD-300: cancellation must propagate.** Pre-fix, a sibling download's 401 cancellation was caught by the generic `catch (e: Exception)` in the flake-retry loop, the next `delay()` re-threw `CancellationException`, and the loop chewed through the flake budget logging the misleading `ScopeCoroutine was cancelled`. Now `CancellationException` is re-thrown explicitly before the generic catch.
7. **Token refresh under cancellation pressure** (UD-310). `NonCancellable` on the refresh wrap is the difference between "in-flight refresh survives Pass-2 cancellation cleanly" and "refresh aborts mid-write to disk, leaves a half-saved token, next session can't start". The mutex serialises concurrent refresh attempts so a 401-storm produces one refresh, not N.
8. **No `If-Match` on POST verbs.** `moveItem` and similar mutating POSTs don't currently send `eTag` preconditions. Concurrent editors are detectable via Graph's `@odata.etag`, but the SyncEngine doesn't yet thread it through. Same gap WebDAV calls out — likely closed together in a future cross-provider ticket.

## Server-family matrix

OneDrive is a single product family (Microsoft Graph 1.0 + Beta), but the runtime behaviour splits on tenant type:

| Tenant | Throttle docs | `retryAfterSeconds` body | CDN edge | HTML-throttle response | Notes |
|---|---|---|---|---|---|
| Personal (consumer) | "Lower limits" — no numbers | Yes | Azure Blob | Observed | UD-712 baseline; default settings work |
| Business (Office 365) | [Documented per service](https://learn.microsoft.com/en-us/graph/throttling) — varies by API | Yes | SharePoint CDN | Observed (heavier) | Conditional-access policies may need a tighter cap |
| GCC / GCC High / DoD | [Not publicly documented](https://learn.microsoft.com/en-us/graph/deployments) | Untested | US-government data centres | Untested | Likely matches Business; sample size of 0 |

## Follow-ups

- **`If-Match` on mutating POST** — sibling concern with WebDAV audit §4. Thread `@odata.etag` through `SyncAction` and into `moveItem` / `updateItem`. No ticket yet — file under UD-228 follow-ups when a concurrent-editor incident surfaces it.
- **Personal-vs-Business concurrency tuning** — `HttpRetryBudget(maxConcurrency = 8)` is a single value across tenants. UD-263 (per-provider concurrency hints) extends `HttpRetryBudget` to read a per-profile `max_concurrency` knob; once that lands, the Business / GCC defaults should be calibrated separately.
- **`HttpRetryBudget` config surface** — UD-262 will extract the constants in [GraphApiService.kt:898](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:898) (`MAX_THROTTLE_ATTEMPTS`, `MAX_SINGLE_BACKOFF_MS`, `MAX_TOTAL_THROTTLE_WAIT_MS`) into the budget so per-provider overrides become possible.
