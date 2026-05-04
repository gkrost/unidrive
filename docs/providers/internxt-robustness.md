# Internxt — HTTP robustness audit

> Deliverable for [UD-319](../backlog/BACKLOG.md) (part of the UD-228
> cross-provider audit split).
> Vendor docs: [Internxt SDK](https://github.com/internxt/sdk) (canonical reference for the Bottleneck pacer settings), [Internxt CLI 1.6.3 release notes](https://github.com/internxt/cli/releases) ("automatic retry handling for 429" — first-party confirmation that the Drive API does emit 429s), `https://api.internxt.com/drive/` (Swagger shell — endpoint catalogue only, no throttling section), `https://internxt.com/developers` redirects to a marketing page (no public REST reference). Retry-After semantics, `X-RateLimit-*` headers, and the Drive-vs-Bridge concurrency policy are **not publicly documented**.
> Field data: 2026-04-19 capture against `inxt_gernot_krost_posteo` (3 TB plan); `/Personal/contextMenu.xml` 500-storm + JFR `daemon-internxt-final.jfr` 13 657 exceptions/s during the 7 s UD-249 incident window.

Companion to [internxt-client-notes.md](internxt-client-notes.md): client-notes is *what the protocol and our code do*; this is *where the code is brittle and where the server surprises us*. Compared against the [OneDrive baseline](onedrive-robustness.md) — Internxt is the most divergent of the audited providers because it is the only one with a *two-tier* HTTP topology (Drive metadata API + Bridge storage API) **and** mandatory client-side encryption between them.

## Status summary

| Dimension | Finding | Confidence |
|---|---|---|
| Non-2xx body parsing | Raw body concatenated into exception message; no structured parse, no `retryAfterSeconds` extraction, no log-noise truncation | High |
| Retry placement | Hand-rolled GET-only ladder at HTTP layer (`authenticatedGet`); POST/PATCH/DELETE/PUT have **zero** retry | High |
| Retry-After source | Header **not read**, body **not parsed**; CLI 1.6.3 confirms server emits 429 | High |
| Idempotency (auth) | Mutex + double-check (UD-310 equivalent); **no `NonCancellable` wrap**; no automatic 401-replay | High |
| Idempotency (body replay) | Drive POSTs replay-safe (String body); upload pipeline replay is unsafe without IV pinning across stages | High |
| Concurrency | No cap. SDK runs **1 conc / 1000 ms** global + **2 conc / 500 ms** drive-API; we have neither | High |
| Streaming download | `prepareGet().execute { bodyAsChannel() }` + 256 KiB ring buffer feeding `Cipher.update()` — matches OneDrive UD-329 | High |
| HTML-body guard | **None**. Captive-portal HTML would XOR through AES-CTR and silently corrupt the destination | High |
| Cancellation hygiene | Generic `catch (e: Exception)` swallows `CancellationException` (UD-300 baseline gap) | Medium |

## 1. Non-2xx body parsing

`InternxtApiService.checkResponse` ([InternxtApiService.kt:424](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:424)):

```kotlin
if (!response.status.isSuccess()) {
    throw InternxtApiException("API error: ${response.status} - ${response.bodyAsText()}", response.status.value)
}
```

The whole body is interpolated into the exception message. Internxt returns JSON envelopes (`{"error": "USER_NOT_FOUND", "message": "..."}`); we discard the structured `error` code and keep only the status integer on `InternxtApiException.statusCode` ([InternxtApiService.kt:448](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:448)).

| Capability | OneDrive baseline | Internxt today | Cite |
|---|---|---|---|
| `truncateErrorBody` log-noise guard | Yes | **No** | [InternxtApiService.kt:429](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:429) |
| Retry-hint extraction from JSON body | Yes | **No** | [InternxtApiService.kt:424](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:424) |
| Streaming-download HTML guard (UD-231) | Yes | **No** | [InternxtApiService.kt:218](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:218) |

The Internxt-specific risk on the third row is heavier than OneDrive's: the download path runs through `javax.crypto.Cipher` (AES-256-CTR), so a stray HTML payload is XOR'd against the keystream and produces high-entropy bytes that look like real plaintext output — *indistinguishable from a legitimate decrypted file* until a downstream consumer rejects it.

## 2. Retry placement

The target policy is the [canonical matrix](../dev/lessons/http-retry-policy.md);
the table below is a **gap audit** showing which call sites diverge from it
today. Internxt currently has zero retry on most verbs — the divergence is
"absence", not "different policy".

[`authenticatedGet`](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:383) hosts an inline ladder for GETs only with delays `[2 s, 4 s, 8 s]` and a `500/503 + EOFException` predicate.

| Verb / endpoint | 429 retry? | 5xx retry? | I/O retry? | Cite |
|---|---|---|---|---|
| `authenticatedGet` (Drive reads) | **No** | 500/503 only | EOF only | [InternxtApiService.kt:399](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:399) |
| `bridgeGet` / `bridgePost` | No | No | No | [InternxtApiService.kt:250](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:250) |
| `createFile` POST | No | No | No | [InternxtApiService.kt:107](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:107) |
| `moveFile` / `moveFolder` PATCH | No | No | No | [InternxtApiService.kt:148](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:148) |
| `deleteFile` / `deleteFolder` DELETE | No | No | No | [InternxtApiService.kt:179](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:179) |
| `putEncryptedShard` PUT (CDN) | No | No | No | [InternxtApiService.kt:332](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:332) |
| `downloadFileStreaming` GET | No | No | No | [InternxtApiService.kt:217](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:217) |
| 408 / 429 / 502 / 504 (any verb) | **No** | — | — | n/a — gap |

There is no [`HttpRetryBudget`](../../core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt) shared across coroutines: a storm of 500s on parallel sync produces N independent 3-attempt ladders re-bursting in lockstep. UD-249 (closed) confirmed this: 3 consecutive 500s on `/Personal/contextMenu.xml` triggered a full remote rescan instead of graceful continuation.

## 3. Retry-After

**Not honoured anywhere.** No call site reads `response.headers["Retry-After"]`, no JSON-body parser exists, no `X-RateLimit-*` family is consumed.

| Source | Internxt today | OneDrive baseline |
|---|---|---|
| `Retry-After` delta-seconds header | Not read | Honoured |
| `Retry-After` HTTP-date header | Not read | n/a (Graph integer-seconds only) |
| JSON body `retryAfterSeconds` | Not parsed | Honoured |
| `X-RateLimit-*` family | Untested — vendor docs do not cover this | n/a |

CLI 1.6.3 release notes confirm the server emits 429s ("Implemented automatic retry handling for 429 (Too Many Requests) responses"). We treat them as fatal.

## 4. Idempotency

**Auth refresh.** [`AuthService.refreshToken`](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:205) is partially correct: mutex serialises concurrent refreshes ([:207](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:207)), double-check after lock acquisition skips redundant refreshes ([:210](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:210)). Gaps vs OneDrive:

| Feature | OneDrive | Internxt | Cite |
|---|---|---|---|
| `withContext(NonCancellable)` wrap (UD-310) | Yes | **No** | [AuthService.kt:213](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:213) |
| 401 → automatic refresh-and-replay | Yes | **No** — `checkResponse` throws and gives up | [InternxtApiService.kt:425](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:425) |
| Pre-flight near-expiry refresh | No | No (only via 401-reactive, also missing) | [AuthService.kt:177](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:177) |

Under Pass-2 cancellation the in-flight refresh is cancelled between `fetchRefreshedJwt` ([AuthService.kt:213](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:213)) and `saveCredentials` ([:216](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:216)), leaving the in-memory JWT disagreeing with disk.

**Body replay — Drive API.** `authenticatedGet` is body-less so replay is moot today. For mutating verbs (none retry), the body is always a `String` constructed inside the call (e.g. [InternxtApiService.kt:111](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:111)) and `setBody(requestBody.toString())` re-emits cleanly per attempt — same shape as OneDrive's `authenticatedRequest`.

**Body replay — Bridge upload (encryption-vs-retry).** This is the **Internxt-specific concern**. The upload pipeline ([InternxtProvider.kt:124](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:124)) has 5 stages:

1. Generate random `indexBytes[32]` (AES-CTR IV is `[0:16]`) ([InternxtProvider.kt:142](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:142)).
2. Derive `fileKey = SHA-512(bucketKey || indexBytes)[:32]` ([InternxtProvider.kt:155](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:155)).
3. AES-256-CTR encrypt local → temp file via `CipherInputStream`, compute SHA-256 of ciphertext in same pass ([InternxtProvider.kt:159](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:159)).
4. PUT encrypted shard → CDN ([InternxtProvider.kt:194](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:194)).
5. `finishUpload` (Bridge) → `createFile` (Drive) commit `indexHex` + `hashHex` ([InternxtProvider.kt:201](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:201)).

A naive retry that *restarts the pipeline at stage 1* generates a fresh `indexBytes`. Stage 4 ciphertext was produced under the old IV; the bridge accepts it but downloads decrypt against the new IV from the new `createFile` row → content garbage. **The PUT is only safely retriable if the IV is pinned across the whole pipeline run.** Today there is no retry, so the question is theoretical — but the moment we wire UD-262 retries, the loop boundary **must** be either (a) "retry the PUT only, with the temp file held until `finishUpload` succeeds" or (b) "retry the entire pipeline including a fresh `indexBytes`". Mixing stages 1–3 (old IV) with stages 4–5 (new IV) is silent-corruption territory.

For comparison: the [`xtra` layered-encryption strategy](../../core/app/xtra/src/main/kotlin/org/krost/unidrive/xtra/AesGcmStrategy.kt) writes a self-describing `XtraHeader` that carries the IV inline with the ciphertext, so a body replay re-uses the same IV by construction. Internxt splits IV (Drive metadata) from ciphertext (Bridge storage) into two writes — the replay invariant is *cross-API* and depends on sequencing. Grep over `core/app/xtra/` returns zero matches for "internxt"; the two pipelines are independent.

**`finishUpload` non-idempotency.** If the network drops *after* `finishUpload` returns 200 but before we read the body ([InternxtApiService.kt:309](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:309)), a retry re-finishes the same shard set and the bridge issues a second `fileId`. The first is now an orphan — billed for storage, not reachable through any Drive row.

## 5. Concurrency recommendations

The official SDK enforces two layers of pacing (sourced from [github.com/internxt/sdk](https://github.com/internxt/sdk) per [internxt-client-notes.md](internxt-client-notes.md)):

| Layer | SDK setting | unidrive today |
|---|---|---|
| Global (all requests) | `maxConcurrent = 1`, `minTime = 1000 ms` | **None** |
| Drive API | `maxConcurrent = 2`, `minTime = 500 ms` | **None** |
| Bridge / CDN | Unspecified | **None** |

Recommended baseline:

| Setting | Value | Rationale |
|---|---|---|
| `maxConcurrentTransfers` | **2** | Match SDK `driveApiBottleneck`; OneDrive's 8 too aggressive given SDK's 1-global cap |
| `minRequestSpacingMs` (steady) | **500 ms** | SDK `driveApiBottleneck.minTime` |
| `stormSpacingMs` | 1000 ms | Match SDK global limiter — the regime it was designed for |
| Storm threshold / action / recovery | OneDrive defaults (4-per-20s; halve + 1.2× Retry-After; +1 per 5 min clean) | Inherited |

Vendor docs do not cover the Bridge / CDN ceiling; the sensible enforcement point is the Drive metadata calls (`startUpload`, `finishUpload`, `createFile`) which the budget already spans. Until `HttpRetryBudget` is wired into `InternxtApiService` ([:23](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:23)), parallelism is whatever `SyncEngine` issues — observed 6-wide in the UD-249 capture.

## Internxt — field observations

1. **JWT-only auth, no refresh-token round.** `fetchRefreshedJwt` ([AuthService.kt:185](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:185)) takes the *current* JWT as bearer credential and returns a new one. Once fully expired, the user must re-authenticate via `authenticateInteractive()`. JWT lifetime unpublished; observed ~3 days in 2026-04 capture.
2. **Mnemonic in plaintext on disk.** `InternxtCredentials.mnemonic` ([Credentials.kt:8](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/model/Credentials.kt:8)) is the BIP39 master secret for *all* file decryption. `chmod 600` is set ([AuthService.kt:243](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:243)) but `cat` exposes every uploaded byte. Vault layer is opt-in.
3. **Two distinct base URLs in one service.** Drive at `gateway.internxt.com/drive` ([InternxtConfig.kt:10](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtConfig.kt:10)) uses bearer JWT; Bridge at `api.internxt.com` ([InternxtApiService.kt:248](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:248)) uses HTTP Basic with `sha256(bridgeUserId)` as password ([InternxtCrypto.kt:37](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtCrypto.kt:37)). Any pacing budget must span both.
4. **Client-header gating.** `internxt-client`, `internxt-version`, `x-internxt-desktop-header` ([InternxtConfig.kt:11](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtConfig.kt:11)) are required on every Drive call; we hard-code `drive-desktop-linux 2.5.668` — fragile against an Internxt-side allowlist tighten ([internxt/cli#268](https://github.com/internxt/cli/issues/268)).
5. **`/files` 503 fallback exists, but only for delta scan.** `InternxtProvider.delta` ([InternxtProvider.kt:286](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:286)) catches 500/503 from `listFiles` and falls through to recursive `getFolderContents`. The only retry-shaped logic outside `authenticatedGet`, and it is **not a retry** — it is an alternate path.
6. **`createFolder` 409 is rescued.** UD-317 era — [InternxtProvider.kt:237](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:237) catches 409 conflict and looks up the existing folder by name. The only verb-level idempotency rescue we have.
7. **JFR exception storm under load.** `daemon-internxt-final.jfr` shows 13 657 exceptions/s during the 7 s UD-249 window. Each retry attempt allocates a fresh `InternxtApiException` and Ktor wraps several layers around it. `HttpRetryBudget` would cut this ~6× by enforcing one-attempt-per-storm across coroutines.

## API quirks

- **`x-api-version: 2` only on Bridge** ([InternxtApiService.kt:259](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:259)). Drive endpoints don't take it; v1/v2 prefixes mix on Bridge: `/v2/buckets/.../start` for upload but `/buckets/.../files/{id}/info` for metadata. Mixing returns 404 with no documentation.
- **DELETE-folder takes a JSON body listing items** ([InternxtApiService.kt:202](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:202)). RFC 7231 §4.3.5 allows but discourages this; some proxies strip DELETE bodies silently.
- **404 on DELETE → success** ([InternxtApiService.kt:183](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:183)). Same shape as OneDrive.
- **Filenames sometimes arrive with `\n` prefix.** UD-317 closed; we `trim()` defensively. Vendor docs do not cover this.
- **`encryptVersion` hard-coded `"03-aes"`** ([InternxtApiService.kt:103](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:103)); the vendor SDK supports `02-rsa` for legacy buckets, we don't.

## Server-family matrix

| Tier | Auth | Base URL | Pacer (SDK) | unidrive retries |
|---|---|---|---|---|
| Drive (metadata, quota) | Bearer JWT | `gateway.internxt.com/drive` | 2 conc / 500 ms | GET-only ladder |
| Bridge (storage descriptors) | HTTP Basic + sha256 | `api.internxt.com` | 1 conc / 1000 ms global | None |
| CDN (signed shard PUT/GET) | Pre-signed URL | varies (S3-flavoured) | n/a | None |

## Follow-ups

- **Wire `HttpRetryBudget` into `InternxtApiService`** ([:23](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:23)). Single per-service instance spanning Drive + Bridge. Honour `Retry-After` on 408/429/502/503/504. Defaults: 2 conc / 500 ms steady / 1000 ms storm. Files under UD-262 (config surface) + UD-263 (per-provider concurrency hints).
- **JSON error-body parser.** Mirror OneDrive's `pickBackoffMsWithBody`: extract `retryAfterSeconds`, truncate non-JSON bodies to first line + char count. Child of UD-262.
- **`NonCancellable` wrap on `refreshToken()`.** Direct port of UD-310: `withContext(NonCancellable) { fetchRefreshedJwt(...); saveCredentials(...) }` inside the mutex ([AuthService.kt:213](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:213)).
- **401 → automatic refresh-and-replay.** Today `checkResponse` throws on 401 with no recovery ([InternxtApiService.kt:425](../../core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:425)); mirror OneDrive's replay-with-fresh-token shape.
- **HTML-content-type guard on download.** Mandatory before retry-on-flake is added to `downloadFileStreaming`; otherwise a captive-portal page gets XOR'd through AES-CTR and corrupts silently. See OneDrive UD-231.
- **Document the encryption-vs-retry boundary.** Whichever ticket adds upload retries must specify: retry only stages 4–5 (PUT + finish), never stages 1–3 (re-IV). Unit test should freeze the IV between attempts within a single upload run.
- **`finishUpload` idempotency.** Send a client-side request id (or reuse `descriptor.uuid` from `startUpload`); have `finishUpload` accept "already-finished, return cached fileId". Vendor support unverified — file research ticket against the SDK first.
- **Hard-coded `internxt-version`.** Make a config override so we can bump without a release. Tracks the client-allowlist tightening risk.
