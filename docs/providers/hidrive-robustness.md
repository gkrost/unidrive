# HiDrive (IONOS / Strato) — HTTP robustness audit

> Deliverable for [UD-318](../backlog/BACKLOG.md) (part of the UD-228
> cross-provider audit split).
> Vendor docs: [HiDrive Developer portal](https://developer.hidrive.com/),
> [OAuth2 endpoints](https://developer.hidrive.com/oauth2-endpoints/),
> [Get Started](https://developer.hidrive.com/get-started/).
> Field data: none yet — HiDrive provider has not had a UD-712-class
> live-sync run; findings are **static-analysis only** unless marked
> otherwise.
> Reference baseline: [onedrive-robustness.md](onedrive-robustness.md)
> (UD-320). Closest sibling: [webdav-robustness.md](webdav-robustness.md)
> (UD-324) — the WebDAV "no retries anywhere" pattern is what HiDrive
> ships today.

HiDrive is a German-hosted REST cloud (IONOS, formerly 1&1 / Strato).
Companion to [hidrive-client-notes.md](hidrive-client-notes.md): where
the client-notes file is *what HiDrive's REST surface looks like*, this
one is *where our client is brittle and what HiDrive's docs decline to
tell us*.

## Status summary

| Dimension | Finding | Confidence |
|---|---|---|
| Non-2xx body parsing | `bodyAsText()` concatenated into the exception message; no JSON `error`/`error_description` extraction, no retry-hint parse, no log-noise guard | High |
| Retry placement | **Zero retries anywhere** — every transient 429 / 503 / connection-reset propagates as a fatal `HiDriveApiException` to SyncEngine | High |
| Retry-After source | Not honoured. Header not read, body not parsed, no `X-RateLimit-*` family awareness | High |
| Idempotency | 401 throws immediately, no refresh-and-replay; `getValidToken()` mutex-guarded but **not** `NonCancellable`-wrapped (UD-310 gap); upload PUT body is replayable in principle but no retry calls it | High |
| Concurrency | No `HttpRetryBudget`; no provider-internal semaphore; SyncEngine's hardcoded `16/6/2` is the only cap | High |
| Streaming download | UD-329 anti-pattern: `httpClient.get()` + `response.body<ByteReadChannel>()` buffers entire response on Ktor 3.x | Medium |
| Vendor-published limits | None — `developer.hidrive.com` publishes no rate limits, 429 behaviour, or `Retry-After` semantics anywhere | High (negative) |

## 1. Non-2xx body parsing

Every HiDrive API method follows the same shape: stringify status +
body into an exception message and throw. There is no JSON parse, no
diagnostic field extraction, no log-truncation guard.

`authenticatedGet` at [HiDriveApiService.kt:339-357](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:339):

```kotlin
if (response.status == HttpStatusCode.Unauthorized) {
    throw AuthenticationException("Authentication failed (401): ${response.bodyAsText()}")
}
if (!response.status.isSuccess()) {
    throw HiDriveApiException("API error: ${response.status} - ${response.bodyAsText()}", response.status.value)
}
```

The same pattern repeats verbatim in `downloadFile`
([:122](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:122)),
`uploadFile` ([:180](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:180)),
`deleteFile` ([:201](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:201)),
`createFolder` ([:238](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:238)),
`moveDirectory` ([:263](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:263)),
and `moveFile` ([:294](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:294)).

HiDrive's documented OAuth error envelope is JSON
`{ "error": "...", "error_description": "..." }`
([source](https://developer.hidrive.com/oauth2-endpoints/)); the
data-plane API isn't documented on this point.

**Pre-fix: missing structured error parse.** OneDrive equivalent:
[`parseRetryAfterFromJsonBody`](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:371)
+ [`truncateErrorBody`](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:841).
Recommended: parse `error` / `error_description`, distinguish
`refresh token is no longer valid` (terminal) from `unknown access
token` (recoverable via refresh).

## 2. Retry placement

**Zero retries.** Every HTTP call site is a single Ktor verb followed
by status check followed by throw. No inline retry loop, no
[`HttpRetryBudget`](../../core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt)
import, no per-method retry helper. The pattern matches WebDAV's
pre-fix shape ([webdav-robustness.md §2](webdav-robustness.md#2-retry-placement));
the first 429 from HiDrive under load, the first 503 from a Strato
edge node, or the first network-layer `SocketTimeoutException` will
fail the enclosing sync cycle.

SyncEngine sees a `HiDriveApiException` (status code preserved as
`statusCode: Int`) or an `AuthenticationException`. The action layer
treats both as fatal for the file. The HiDriveProvider's
[`delete`](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveProvider.kt:81)
catches 404 specifically to fall through file→directory delete; this
is the only cross-status logic in the whole module.

**Pre-fix: missing transparent retry.** OneDrive equivalent:
[`authenticatedRequest`](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:721)
inline retry loop with `MAX_THROTTLE_ATTEMPTS=5` and
`MAX_TOTAL_THROTTLE_WAIT_MS=900_000`. Recommended remediation matrix
(matches WebDAV §2):

| Status | Retry? | Cap | Backoff base |
|---|---|---|---|
| 408 Request Timeout | yes | 3 | 1 s × 2ⁿ + jitter |
| 429 Too Many Requests | yes | 5 | `Retry-After` if present, else 2 s × 2ⁿ + jitter |
| 502/503/504 | yes | 5 | `Retry-After` if present, else 1 s × 2ⁿ + jitter |
| 507 Insufficient Storage | no | — | quota fatal |
| Network I/O | yes | 3 | 500 ms × 2ⁿ + jitter |

## 3. Retry-After

HiDrive's developer portal does not publish anything on this. WebFetch
of `developer.hidrive.com/get-started/`, `/`, `/oauth2-endpoints/`,
`/basics/`, and the linked synchronisation PDF turned up zero
references to rate limits, throttling, 429, `Retry-After`, or
`X-RateLimit-*`. The `static.hidrive.com/dev/0001` redirect lands on a
marketing PDF with no technical content.

This is a **negative finding** with high confidence: HiDrive does not
document its rate-limit contract. The audit-scope question
"X-RateLimit-* headers, JSON error body conventions, Strato endpoint
quirks" (UD-318 brief) cannot be answered from public docs.

| Source | HiDrive status | OneDrive equivalent |
|---|---|---|
| `Retry-After` header | not read | [pickBackoffMs](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:357) reads first |
| JSON body retry hint | not parsed | parsed at the same site |
| `X-RateLimit-*` family | not parsed | not used (Graph doesn't emit) |
| HTTP-date `Retry-After` | not handled | not handled (Graph integer-only) |

Recommended: when retry lands, follow the OneDrive integer-seconds
shape and add a body-parse hook permissive enough to handle whatever
JSON HiDrive actually emits on 429 (TBD by live capture).

## 4. Idempotency

[`TokenManager.getValidToken`](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/TokenManager.kt:50)
is mutex-guarded — `refreshMutex.withLock` serialises concurrent
refresh attempts so a 401-storm produces one refresh, not N. So far
matches OneDrive. Two important gaps vs the
[OneDrive baseline](onedrive-robustness.md#4-idempotency):

1. **No `NonCancellable` wrap.** UD-310 wired
   `withContext(NonCancellable) { oauthService.refreshToken(...) }` on
   the OneDrive side because Pass-2 sibling cancellation aborted
   in-flight refreshes mid-write to disk. The HiDrive
   [`refreshToken`](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/TokenManager.kt:66)
   call has no such wrap — it inherits whatever scope the caller is in.
2. **No 401-refresh-once-and-replay.** When `authenticatedGet` sees a
   401, it throws `AuthenticationException` immediately
   ([HiDriveApiService.kt:349](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:349)).
   Refresh only fires when the local `isExpired` clock-check at
   [Token.kt:24](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/model/Token.kt:24)
   ticks past `expiresAt - 60_000`. A clock-skewed client whose local
   "valid" token has been server-revoked hard-fails on the first 401
   instead of recovering. OneDrive equivalent:
   [`authenticatedRequest`](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:721)
   force-refreshes and replays the same URL once.

**Body-replay safety:**

| Verb | Body shape | Replay-safe? | Notes |
|---|---|---|---|
| GET | none | yes | fully idempotent |
| PUT (upload) | `WriteChannelContent` re-reading from `Files.newInputStream` ([:163](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:163)) | yes in principle | each `writeTo` re-opens the file; replay is clean — but no retry calls it today |
| POST (create dir, copy, move-dir) | parameter-only | yes | URL params only |
| DELETE | parameter-only | yes | HiDrive treats already-deleted as 404 (handled at [provider.kt:84](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveProvider.kt:84)) |
| `moveFile` (copy + delete) | two-call sequence ([:282-301](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:282)) | **no** | a partial-failure window between `/file/copy` succeeding and `/file?path=src` deleting leaves a duplicate. HiDrive has no native `/file/move` so this is unavoidable, but the layer should surface partial state |

**No `If-Match` / ETag preconditions** on any mutating call — same gap
WebDAV §4 calls out. HiDrive's `chash` (content hash) is in metadata
responses but isn't threaded through to mutating verbs as a
precondition. Concurrent editors race last-writer-wins.

## 5. Concurrency recommendations

HiDrive publishes nothing. The recommendations below are inferences
from the protocol shape + Strato lineage, with explicit Confidence
markers.

| Layer | Recommended | Source / confidence |
|---|---|---|
| `maxConcurrentTransfers` | **4** (default) | Confidence Low — WebDAV audit puts Synology DSM at ≤ 4 ([webdav-robustness.md §5](webdav-robustness.md#5-concurrency-recommendations)); Strato HiDrive's WebDAV façade is in that family matrix and is the closest comparator. No vendor number to anchor against |
| `minRequestSpacingMs` (steady) | 0 ms | Match OneDrive default — quiet steady state pays no spacing cost |
| `minRequestSpacingMs` (post-throttle) | 200 ms | Match OneDrive default once retry lands ([HttpRetryBudget.kt:41](../../core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:41)) |
| `stormSpacingMs` | 500 ms | Match OneDrive |
| `MAX_THROTTLE_ATTEMPTS` | 5 | Match OneDrive |
| `MAX_TOTAL_THROTTLE_WAIT_MS` | 900 000 (15 min) | Match OneDrive |
| `MAX_FLAKE_ATTEMPTS` (download) | 3 | Match OneDrive UD-309 — no Strato CDN evidence yet, but the streaming-download path has the same shape so the same risk likely applies |

UD-263 (per-provider concurrency hints) is the remediation ticket;
values above feed into that.

## HiDrive — field observations

No live-sync run yet. From static analysis + the OAuth flow:

1. **Vendor docs publish nothing on rate limits.** No
   `/best-practices/`, `/error-handling/`, or `/throttling/` page on
   `developer.hidrive.com` (all 404 on WebFetch). The only published
   technical content is the OAuth flow + endpoint reference. This is
   the audit's most striking finding: we are flying blind compared to
   OneDrive's [documented throttling matrix](https://learn.microsoft.com/en-us/graph/throttling).
2. **Strato `api.hidrive.strato.com` is the data plane;
   `my.hidrive.com` is the auth plane** ([HiDriveConfig.kt:12-15](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveConfig.kt:12)).
   Both operated by Strato (an IONOS subsidiary). CDN edge behaviour
   unknown — file follow-up to capture `Server:` / `Via:` on a real
   session.
3. **OAuth token validity is documented.** Access tokens expire after
   3600 s; refresh tokens after ~3 months
   ([source](https://developer.hidrive.com/oauth2-endpoints/)). The
   `expiresAt - 60_000` skew window in
   [Token.kt:24](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/model/Token.kt:24)
   matches the OneDrive convention.
4. **`moveFile` is copy+delete by necessity.** HiDrive has no
   `/file/move` endpoint ([HiDriveApiService.kt:279](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:279)).
   A crash between the two calls leaves a duplicate. `moveDirectory`
   has a native `/dir/move` and is safe.
5. **Download path is the UD-329 anti-pattern.**
   [HiDriveApiService.kt:114-126](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:114)
   uses `httpClient.get(url)` + `response.body<ByteReadChannel>()`. On
   Ktor 3.x this buffers the entire response into a `byte[contentLength]`
   before exposing the channel — files > Integer.MAX_VALUE (~2.147 GiB)
   will fail at allocation time. OneDrive landed the fix at UD-329:
   `prepareGet(url).execute { response.bodyAsChannel() }`.
6. **OAuth callback HTTP server is bound to `127.0.0.1`** ([TokenManager.kt:87](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/TokenManager.kt:87)).
   PKCE wired ([OAuthService.kt:74-83](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/OAuthService.kt:74)),
   state validation wired with the load-bearing comment at
   [TokenManager.kt:124-130](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/TokenManager.kt:124).
7. **Delta is a full re-listing, not a server cursor.**
   [`HiDriveProvider.delta`](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveProvider.kt:116)
   re-walks the whole tree via `listRecursive("/")` and diffs against
   a base64-encoded snapshot. Functional but pays the full-listing
   cost every sync; out of scope for this audit but worth a feature
   ticket.

## API quirks matrix

HiDrive is single-vendor (no Nextcloud / DSM / Apache fan-out like
WebDAV), so the matrix is endpoint-by-endpoint:

| Endpoint | Idempotent | Documented? | Body on success | Quirk |
|---|---|---|---|---|
| `GET /user/me` | yes | partial | JSON `HiDriveUserInfo` | `?fields=` controls projection |
| `GET /dir` | yes | partial | JSON with `members` | `members=all` required to get children |
| `GET /meta` | yes | partial | JSON `HiDriveItem` | distinct from `/dir` even for directories |
| `GET /file` | yes | partial | binary | streaming download endpoint |
| `PUT /file` | yes (overwrite) | partial | JSON `HiDriveItem` | `dir` + `name` query params; body `application/octet-stream` |
| `DELETE /file` | yes | partial | empty / 204 | 404-on-already-deleted is treated as success |
| `DELETE /dir` | yes | partial | empty / 204 | `recursive=true` required for non-empty |
| `POST /dir` | yes (`on_exist=autoname`) | partial | JSON `HiDriveItem` | avoids 409 on collision |
| `POST /dir/move` | yes | partial | JSON `HiDriveItem` | native server-side move |
| `POST /file/copy` | yes (`on_exist=overwrite`) | partial | JSON `HiDriveItem` | the only way to "move" a file |

"partial" = endpoint name + parameters are documented, but the **error
envelope, rate-limit headers, and throttle behaviour are not**. That
gap is what makes the §1-§3 fixes hard to specify exactly — we'll
reverse-engineer against live traffic when retry lands.

## Follow-ups

- **HiDrive retry wiring** — the §2 matrix needs a ticket. Match the
  OneDrive shape: inline retry loop in `authenticatedGet` /
  `authenticatedPost`, wire `HttpRetryBudget`, parse `Retry-After`
  header and any HiDrive JSON-body equivalent (TBD by live capture).
  Consumed by UD-262 / UD-263.
- **HiDrive 401 refresh-and-replay** — §4 gap. Match
  [`GraphApiService.authenticatedRequest`](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:721):
  on 401, force a refresh and replay the same URL once. Recovers from
  server-side revocation / clock-skew without surfacing as fatal auth.
- **HiDrive `NonCancellable` token refresh** — wrap
  [TokenManager.kt:66](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/TokenManager.kt:66)
  in `withContext(NonCancellable) { ... }` per UD-310. Cheap, low
  risk; applies the lesson without waiting for an incident.
- **HiDrive streaming download (UD-329 port)** — replace
  `httpClient.get + response.body<ByteReadChannel>()` at
  [HiDriveApiService.kt:114](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:114)
  with `prepareGet(url).execute { response.bodyAsChannel() }`.
- **HiDrive `truncateErrorBody`** — port the log-noise guard from
  [GraphApiService.kt:841](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:841).
  HiDrive bodies are likely small JSON, but cheap to add alongside
  retry wiring.
- **HiDrive moveFile partial-failure surfacing** — when copy succeeds
  and delete fails ([HiDriveApiService.kt:301](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:301)),
  the duplicate is silent. Surface as a distinct exception type so
  SyncEngine can clean up or alert.
- **Live capture of HiDrive 429 / 5xx behaviour** — without vendor
  docs, the only way to specify the retry parser is to provoke
  throttling on a real account and capture headers + body.
  Pre-requisite for the above to be specified at high confidence.
