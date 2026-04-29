# Internxt — client notes

Research input for UD-230 (feeding UD-228 cross-provider robustness audit).

## Vendor recommendations

Internxt publishes an OpenAPI at `https://api.internxt.com/drive/` and a
first-party SDK at [github.com/internxt/sdk](https://github.com/internxt/sdk).
The "bridge" (storage node) API runs at `https://api.internxt.com`.

- **Two-tier architecture**: the Drive API (metadata, auth, quota) and the
  Bridge API (encrypted-shard storage nodes). Clients must coordinate both.
  ([deepwiki: Remote API Integration](https://deepwiki.com/internxt/drive-desktop/8-remote-api-integration))
- **429 is emitted and must be honoured.** Internxt's CLI 1.6+ ships
  automatic retry for 429, confirming the server issues rate-limit
  responses. ([internxt/cli releases](https://github.com/internxt/cli/releases))
- **Official SDK pacer**: the SDK uses a Bottleneck-based singleton limiter
  — **1 concurrent request, 1000 ms minimum interval between dispatches**;
  the Drive API client additionally creates its own
  `driveApiBottleneck` with **2 concurrent, 500 ms minimum interval**.
  ([internxt/sdk](https://github.com/internxt/sdk))
- **Auth headers**: `internxt-client`, `internxt-version`, and a desktop
  header are expected on every Drive API call; Bridge uses
  `Authorization: Basic <user>:<sha256(userId)>` plus `x-api-version: 2`.
- **Multipart upload**: shards are PUT directly to signed CDN URLs returned
  by `POST /v2/buckets/{bucket}/files/start`; finishing requires
  `POST /v2/buckets/{bucket}/files/finish` with shard hashes.
- **No published `Retry-After` semantics** — treat as standard HTTP: honour
  the header if present, otherwise exponential backoff.
- **Client identity is inspected** — Internxt gates some endpoints on the
  client header being one of its own apps. Neutral user-agents have been
  observed to be rejected. ([internxt/cli issues #268](https://github.com/internxt/cli/issues/268))

## What unidrive does today

`core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt`

- **Hand-rolled retry ladder on GETs only**: `authenticatedGet` retries
  500/503 and `EOFException` with delays `[2 s, 4 s, 8 s]`
  (`:303-328`). POST/PATCH/DELETE/PUT have **no retry at all**.
- **No rate limiter / no request pacing.** Parallelism is whatever the
  caller issues. UD-227 added 429 handling with `retryAfterSeconds` parsing
  for CDN-path uploads on the rclone side but the native internxt client
  does not yet treat 429 as retryable.
- **Impersonates desktop client** via `internxt-client`, `internxt-version`,
  `x-internxt-desktop-header` (`:330-336`) — matches what the docs require.
- **Single shared Ktor client** with `HttpDefaults` timeouts (connect 30 s,
  socket 60 s, request 600 s).
- **Streaming download with AES cipher update** (`downloadFileStreaming`,
  `:171-200`) — no resume, no range retries; a mid-transfer 5xx kills the
  stream and the file is re-downloaded from zero.

## Gaps → UD-228

- [ ] **POST/PATCH/DELETE have no retry.** `createFile`, `createFolder`,
      `moveFile`, `moveFolder`, `deleteFile`, `deleteFolder` die on the
      first 500/503/network blip.
- [ ] **429 is not handled on the Drive API path.** UD-227 addressed CDN
      PUT 429s; the Drive side will throw `InternxtApiException(429)` and
      surface it as a generic error.
- [ ] **No client-side pacing.** The official SDK enforces 1 req/s; we can
      fan out unbounded and get ourselves rate-limited.
- [ ] **Bridge PUT (`putEncryptedShard`) has no retry and no timeout
      override** — a hanging CDN node hangs the whole sync for 10 minutes.
- [ ] **Download streaming has no resume.** A 2 GB file that 503s at
      1.9 GB redownloads from 0.
- [ ] **No circuit breaker for repeated 401s**; the `credentialsProvider()`
      lambda is called on every single request.
- [ ] **`finishUpload` is not idempotent on our side** — if the network
      drops *after* finish but before we get the response, we re-upload.

## Priority for UD-228

**High.** Internxt's two-tier architecture means transient failures are
both more likely (storage nodes are globally distributed, churning CDN
edges) and more expensive (multi-MB re-uploads). The missing 429 Retry-After
on the Drive path is the likeliest silent data-loss vector: under sustained
429, `createFile` fails, the file bytes are already in the bucket, and the
Drive DB row never gets written → orphan storage billed but not addressable.
