# HiDrive — client notes

Research input for UD-230 (feeding UD-228 cross-provider robustness audit).

## Vendor recommendations

HiDrive is a STRATO-operated REST API. The portal is noticeably thin on
client-behaviour guidance; the following is what is actually published.

- **Base URL**: `https://api.hidrive.strato.com/2.1/`. Registration (client_id
  / client_secret) required per app.
  ([developer.hidrive.com](https://developer.hidrive.com/))
- **HTTP semantics**: the API is a conventional REST+OAuth2 surface
  (`/user/me`, `/dir`, `/file`, `/meta`, `/file/copy`, `/dir/move`).
  Apidoc at `https://api.hidrive.strato.com/2.1/static/apidoc/index.html`.
  ([api.hidrive.strato.com apidoc](https://api.hidrive.strato.com/2.1/static/apidoc/index.html))
- **No published rate-limit document.** Neither the HTTP API Reference
  ([developer.hidrive.com/http-api-reference](https://developer.hidrive.com/http-api-reference/))
  nor the apidoc nor the unofficial OpenAPI mirror
  ([gist `hidrive_openapi_v2.1-unofficial.yaml`](https://gist.github.com/printminion/e0b7e2799ef15d87e1eb0c09cf1f34cf))
  documents numeric rate limits, a `Retry-After` header, a `429` response, or
  concurrency guidance. STRATO's tenancy model is per-user quota rather than
  per-second request budgets; empirical throttling shows up as opaque 503s.
- **Token refresh**: OAuth2 access tokens expire after ~1 h; refresh token is
  the documented well-behaved recovery path. 401 → refresh → retry once is the
  expected shape. ([developer.hidrive.com OAuth section](https://developer.hidrive.com/))
- **Batch idioms**: none — each endpoint is single-object. Directory
  traversal requires recursive `/dir` calls.
- **Fair-use, implicit**: STRATO's general T&Cs reserve the right to
  throttle, but no machine-readable signal is defined. Clients should treat
  *all* 5xx as potentially-transient and back off; treat 429 as transient if
  ever observed (not currently documented).

## What unidrive does today

`core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt`

- **Single shared Ktor `HttpClient`** built with `HttpDefaults` timeouts
  (connect 30 s, socket 60 s, request 600 s).
  `HiDriveApiService.kt:31-37`, `HttpDefaults.kt:4-8`.
- **No retry loop anywhere.** `authenticatedGet` (`:296-310`) and every
  PUT/POST/DELETE helper throws `HiDriveApiException` on the first non-2xx.
  401 is mapped to `AuthenticationException`; 5xx, 429, network blips all
  propagate immediately.
- **No concurrency cap.** Callers (SyncEngine) decide parallelism; the
  service does not serialize or semaphore anything.
- **Move-file is copy+delete** (`moveFile`, `:234-264`) because HiDrive has
  no `/file/move`. A failure between copy and delete leaves both copies —
  no transactional compensation.
- **Home directory cached in `homePath`** (`:40`, `:42-50`) — good; avoids
  repeat `/user/me` calls.

## Gaps → UD-228

- [ ] **No retry/backoff for 5xx or I/O errors** — the simplest transient
      failure fails the whole sync. Compare internxt's `listOf(2s, 4s, 8s)`
      ladder at `InternxtApiService.kt:306-327`.
- [ ] **No handling of `Retry-After` if STRATO ever starts emitting it.**
      Worth a cheap header peek in one central place.
- [ ] **`moveFile` copy+delete is non-atomic and non-compensating.** If the
      delete half fails, we leak the source. Log + surface a partial-success
      error, or retry the delete.
- [ ] **Recursive `listRecursiveInternal` is unbounded-depth, serial,
      in-memory.** Large HiDrives OOM / stall. Consider depth cap and
      streaming.
- [ ] **No circuit break on repeated 401s** — the token refresh happens via
      `tokenProvider()` lambda; if refresh itself is broken we hammer it
      every request.
- [ ] **No shared HTTP connection pool metrics** — cannot distinguish "slow
      HiDrive" from "exhausted local sockets" in the field.

## Priority for UD-228

**Medium.** HiDrive is lightly used and its failure mode is "no retry at
all" — but that is *universal* across operations, so a single-point fix in
a shared helper is high-leverage. The moveFile non-atomicity is the sharp
edge most likely to cause data corruption today.
