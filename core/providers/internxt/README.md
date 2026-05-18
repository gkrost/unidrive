# Internxt provider

Talks to Internxt's two-tier HTTP topology:

- **Drive API** at `gateway.internxt.com/drive` — metadata, auth, quota. Bearer JWT.
- **Bridge API** at `api.internxt.com` — encrypted-shard storage descriptors. HTTP Basic with `sha256(bridgeUserId)` as password, `x-api-version: 2`.
- **CDN** — pre-signed S3-flavoured shard PUT/GET URLs handed out by the Bridge.

Plus mandatory client-side encryption between Drive metadata (carries the IV) and Bridge storage (carries the ciphertext). The split makes the upload pipeline order-sensitive.

## API surface used

| Verb | Endpoint | Method in `InternxtApiService` / `InternxtProvider` |
|---|---|---|
| `POST /auth/login*` | login + 2FA | `AuthService.authenticateInteractive` (undocumented endpoint; not in vendor Swagger) |
| `POST /users/refresh` | JWT refresh | `AuthService.fetchRefreshedJwt` |
| `GET /folders/{uuid}/files`, `/folders` | list folder contents | `listFiles`, `listFolders` (sorted by uuid for stable pagination) |
| `POST /files`, `POST /folders` | create | `createFile`, `createFolder` (409 conflict → existing-by-name lookup) |
| `PUT /files/{uuid}/meta`, `PUT /folders/{uuid}/meta` | rename | `moveFile`, `moveFolder` — updates `plainName` only; encrypted `name` left stale (sync uses `plainName`) |
| `PUT /files/{uuid}` | replace-in-place | `replaceFile` — for MODIFIED uploads |
| `DELETE /files/{uuid}`, `DELETE /folders/{uuid}` | delete | `deleteFile`, `deleteFolder` (404 → success) |
| `POST /trash/all` | bulk trash | `trashItems` (DELETE-with-body shape) |
| `GET /files/limits` | per-account size limit | `InternxtProvider.maxFileSizeBytes` (cached) |
| `POST /v2/buckets/{bucket}/files/start` | begin upload, get presigned PUT URL | `startUpload` (Bridge) |
| `PUT {cdnUrl}` | upload encrypted shard | `putEncryptedShardFromFile` |
| `POST /v2/buckets/{bucket}/files/finish` | commit shards, get bridge `fileId` | `finishUpload` (Bridge) |
| `GET /buckets/{bucket}/files/{fileId}/info` + signed shard `GET` | streaming download | `downloadFileStreaming` — AES-256-CTR `Cipher.update()` over a 256 KiB ring buffer |

## Robustness

- **Auth refresh** is per-call: `AuthService.isJwtExpired()` is a pure JWT-payload decode + `exp` comparison; refresh is mutex-serialised with double-check. The refresh body is **not** `NonCancellable`-wrapped today (gap vs OneDrive). `getValidCredentials` does not proactively refresh on expiry — relies on server 401 + re-auth.
- **Retry placement**: `authenticatedGet` (Drive reads) has an inline ladder `[2 s, 4 s, 8 s]` on 500/503/EOF. **POST/PATCH/DELETE/PUT have no retry** — the first 500/503/network blip is fatal. Bridge calls have no retry at all.
- **Retry-After**: not honoured anywhere. Header not read, no body parser, no `X-RateLimit-*` consumption. CLI 1.6+ confirms the server emits 429 on the Drive path; we treat it as fatal.
- **Concurrency**: no `HttpRetryBudget` wired. Parallelism is whatever `SyncEngine` issues (observed 6-wide). Official SDK runs 1-concurrent / 1000 ms global + 2-concurrent / 500 ms on the Drive API — the sensible target.
- **Idempotency**: Drive POSTs are body-replay-safe (String). `finishUpload` is **not** idempotent — a network drop after server-side commit but before client reads the response will re-finish and produce an orphan `fileId`.
- **HTML-body guard**: none. A captive-portal HTML response on the download path would XOR through AES-CTR and produce high-entropy bytes indistinguishable from real plaintext.

## Encryption pipeline and the IV-pinning constraint

Upload (`InternxtProvider.kt`) runs in five stages:

1. Generate random `indexBytes[32]`; derive `iv = indexBytes[0:16]`.
2. Derive `fileKey = SHA-512(bucketKey || indexBytes)[:32]` from the user's mnemonic-seeded bucket key.
3. AES-256-CTR encrypt local file → temp file via `CipherInputStream`; compute SHA-256 of ciphertext in the same pass.
4. PUT encrypted shard to the CDN.
5. `finishUpload` (Bridge) → register the file in Drive metadata with `indexHex` + `hashHex`.

**The `indexBytes` value sent to the server in step 5 is what the server stores and uses to derive the key+IV on download.** Any future retry logic on this pipeline must either:

- pin `indexBytes` (and therefore the derived `iv` and `fileKey`) across the entire retry window, holding the temp file and hash steady; or
- restart the full pipeline including a fresh `indexBytes` and re-encryption.

Mixing stages 1–3 (old IV) with stages 4–5 (new IV) is **silent corruption** — the bridge accepts the ciphertext but downloads decrypt against the new IV and produce garbage. There is no retry today; the moment one is added, this constraint binds. A unit test should freeze the IV across a forced retry.

## Planned robustness work

Five items spec'd for the slim release, in landing order. Each tracks a BACKLOG entry — this section is the design summary, not a parallel todo list.

1. **401 → refresh-and-replay** (mirror OneDrive's `GraphApiService` pattern). Add `forceRefresh: Boolean` to `AuthService.getValidCredentials`; introduce a `withAuthRetry` helper in `InternxtApiService` and wrap every authenticated method body. Replay once on 401; throw on the second. Composition order from outermost in: `retryOnTransient { withAuthRetry { budget.awaitSlot(); call(); budget.recordSuccess() } }`. Dedup fold is innermost. Lands first because items 4/1/5 wrap with `withAuthRetry`.
2. **`HttpRetryBudget` wiring.** Two budgets: Drive `maxConcurrency=2, minSpacing=500ms`, Bridge `maxConcurrency=1, minSpacing=1000ms` (mirrors official SDK's Bottleneck config; the Drive/Bridge split is the spec's recommendation pending vendor confirmation). Budget acquisition is per HTTP attempt (innermost), so backoff sleeps don't starve other coroutines. `recordThrottle(retryAfterMs)` fires from the same site that already reads the body hint.
3. **`Retry-After` on Drive mutations.** Thread the response header through `checkResponse` → `InternxtApiException.retryAfterMs` → `retryOnTransient`, header taking precedence over the JSON-body hint. Caps stay (500 ms floor, 60 s ceiling) so a long server hint can't make the engine appear hung. Closes the `createFile` 429-orphan window: encrypted bytes already on OVH but Drive row never wrote.
4. **`finishUpload` idempotency.** On transient failure mid-finish, do **not** blind-retry — probe the Bridge `/buckets/{bucket}/files/{shardUuid}/info` endpoint first. Probe-200 with a `fileId` shape ⇒ the prior commit landed; synthesize the `BucketEntry` from the probe. Probe-404 ⇒ safe to re-finish. Probe-transient ⇒ one more probe, then propagate (never duplicate-commit). Caps probe at 2 attempts.
5. **Retry coverage on remaining mutating verbs** (`deleteFile`/`deleteFolder`/`putEncryptedShardFromFile`/`downloadFileStreaming`). The upload-pipeline retry lives at the **provider** layer (`InternxtProvider.kt:260-265`), not the API service, because that's where `indexBytes` lives — choose (a) **retry stages 4–5 only** with stable temp file + `indexHex`. Download retry re-resolves the OVH URL (presigned, ~15 min) and constructs a fresh `Cipher` per attempt. `putEncryptedShard` (in-memory variant) appears unused; verify before deletion. **Mandated exception to the no-new-tests rule**: an `indexBytes`-stability unit test under `InternxtApiServiceTest`-sibling that asserts identical bytes across a forced retry.

### Investigation gates

- **Item 4 — Drive/Bridge budget split.** BACKLOG quotes "1 conc / 1000 ms global + 2 conc / 500 ms on Drive" from drive-desktop's Bottleneck config. Whether that's one shared budget or two per-host budgets matters for orphan-window behavior. Read drive-desktop's actual config before wiring; default to two if ambiguous.
- **Item 4 — `finishUpload` probe response shape.** The spec assumes `GET /buckets/{bucket}/files/{shardUuid}/info` returns 404 pre-commit and 200-with-`fileId` post-commit. Reverse-engineering pass against a live Bridge needed before relying on probe semantics for de-duplication.

## Quirks

- **`encryptVersion` hard-coded** to `"03-aes"`. Vendor SDK supports `02-rsa` for legacy buckets; we don't.
- **`internxt-version` header hard-coded.** Internxt gates some endpoints on the client header being one of its own apps. Neutral user-agents have been rejected. Tracked as vendor-flux risk.
- **Filenames sometimes arrive with a leading `\n`.** Defensively `trim()`'d.
- **`DELETE /folders/{uuid}` takes a JSON body** listing items. RFC 7231 allows but discourages; some proxies strip DELETE bodies silently.
- **`x-api-version: 2` only on Bridge.** Drive endpoints don't accept it. Bridge paths mix `/v2/...` and unversioned: `/v2/buckets/.../start` for upload but `/buckets/.../files/{id}/info` for metadata.
- **Mnemonic in plaintext on disk.** `chmod 600`, but `cat` exposes every uploaded byte. The credential vault layer is opt-in.

## Delta path-collapse

`InternxtProvider.buildFolderPath` returns an empty string when an item's `parentUuid` is missing from the in-memory `folderMap`. Items with missing parents currently re-emerge at the root, orphaning subtrees and producing duplicate `remote_id` rows in `state.db`. This is the highest-severity correctness bug on the provider — first item in `BACKLOG.md`.

## Request-id correlation

Internxt's drive server emits `x-request-id` on every response. `InternxtApiService.extractRequestId` reads it; carried on `ProviderException.requestId` and surfaced in `unidrive.log` as `requestId=…`.
