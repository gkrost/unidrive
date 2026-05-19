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

1. **401 → refresh-and-replay** (mirror OneDrive's `GraphApiService` pattern). Add `forceRefresh: Boolean` to `AuthService.getValidCredentials`; introduce a `withAuthRetry` helper in `InternxtApiService` and wrap every authenticated method body. Replay once on 401; throw on the second. Composition order from outermost in: `retryOnTransient { withAuthRetry { budget.awaitSlot(); call(); budget.recordSuccess() } }`. Dedup fold is innermost. Lands first because items 2/4/5 wrap with `withAuthRetry`.
2. **`HttpRetryBudget` wiring.** Two budgets — one per host — confirmed via drive-desktop source audit (not "global + Drive layered" as the BACKLOG once suggested):
   - **Drive REST** (`gateway.internxt.com/drive`): `maxConcurrency=2, minSpacing=500ms`. Matches the live `driveApiBottleneck` in `drive-desktop/src/apps/shared/HttpClient/client.ts`. The legacy `1 / 1000ms` `schedule-fetch.ts` path is being retired; don't mimic it.
   - **Bridge upload** (`api.internxt.com/v2/buckets/.../start|finish` and CDN PUT): `maxConcurrency=4, minSpacing=0`. Matches `uploadBottleneck` in `drive-desktop/src/apps/main/auth/handlers.ts`. **No spacing.** Adding one makes us slower than the reference client.
   - Budget acquisition is per HTTP attempt (innermost), so backoff sleeps don't starve other coroutines. `recordThrottle(retryAfterMs)` fires from the same site that already reads the body hint.
   - Internxt's own client does *not* serialise Drive metadata against Bridge upload — orphan shards happen in their topology too and are reaped by a bucket-level GC sweep. Our finishUpload-idempotency item below is *more* defensive than upstream.
3. **`Retry-After` on Drive mutations.** Thread the response header through `checkResponse` → `InternxtApiException.retryAfterMs` → `retryOnTransient`, header taking precedence over the JSON-body hint. Caps stay (500 ms floor, 60 s ceiling) so a long server hint can't make the engine appear hung. Closes the `createFile` 429-orphan window: encrypted bytes already on OVH but Drive row never wrote.
4. **`finishUpload` idempotency.** **Note — the original spec's `/info` probe approach was refuted by an audit of internxt/bridge.** `GET /buckets/{bucket}/files/{shardUuid}/info` rejects the upload-session uuid at `_validate` middleware: it only accepts 24-hex Mongo ObjectIds, and the upload-session uuid (v4 UUID, 36 chars) is in a separate id-space with no derivable mapping to the post-commit `BucketEntry.id`. The `uploads` collection is also not exposed by any GET route. The replacement design lives in BACKLOG: on a transient failure mid-finish, treat HTTP 409 (`MissingUploadsError`) on retry as "probable prior commit" rather than blind-retrying, and reconcile by listing the parent folder's Drive entries by size + recently-created window. Accept that some orphans will be unrecoverable client-side without a server-side `Idempotency-Key` header (a vendor change request, out of our gift).
5. **Retry coverage on remaining mutating verbs** (`deleteFile`/`deleteFolder`/`putEncryptedShardFromFile`/`downloadFileStreaming`). The upload-pipeline retry lives at the **provider** layer (`InternxtProvider.kt:260-265`), not the API service, because that's where `indexBytes` lives — choose (a) **retry stages 4–5 only** with stable temp file + `indexHex`. Download retry re-resolves the OVH URL (presigned, ~15 min) and constructs a fresh `Cipher` per attempt. `putEncryptedShard` (in-memory variant) appears unused; verify before deletion. **Mandated exception to the no-new-tests rule**: an `indexBytes`-stability unit test under `InternxtApiServiceTest`-sibling that asserts identical bytes across a forced retry.

### Observed gateway slowdown

Live `unidrive sync --dry-run` against the gateway has been seeing `GET /drive/files?limit=50&offset=...` responses uniformly in the **28–52 s** range; a previous run showed a mix of fast (400–700 ms) and slow-burst (15–20 s every ~10 calls). No 429s, no retry-exhaustion, no server errors other than an occasional 502 after >30 s. The slowdown is upstream (gateway and/or Cloudflare), not in our code. The HttpRetryBudget item above is the highest-leverage local mitigation — moving from 1 to 2 concurrent page fetches would roughly halve sync wall-clock under the current latency profile.

## Quirks

- **`encryptVersion` always written as `"03-aes"`** on new uploads. The server stores the column as a free string with no enum constraint, but the public SDK / drive-desktop / drive-web all use AES-256-CTR unconditionally and define no other constant. A `"02-rsa"` legacy format was assumed in earlier docs but couldn't be found in any public Internxt repo (search trail in the closed encryptVersion legacy-support entry). `downloadWithFileUuid` fail-fasts on any non-`03-aes` / non-null value rather than silently producing garbage bytes.
- **`internxt-version` header hard-coded.** Internxt gates some endpoints on the client header being one of its own apps. Neutral user-agents have been rejected. Tracked as vendor-flux risk.
- **Filenames sometimes arrive with a leading `\n`.** Defensively `trim()`'d.
- **`DELETE /folders/{uuid}` takes a JSON body** listing items. RFC 7231 allows but discourages; some proxies strip DELETE bodies silently.
- **`x-api-version: 2` only on Bridge.** Drive endpoints don't accept it. Bridge paths mix `/v2/...` and unversioned: `/v2/buckets/.../start` for upload but `/buckets/.../files/{id}/info` for metadata.
- **Mnemonic in plaintext on disk.** `chmod 600`, but `cat` exposes every uploaded byte. The credential vault layer is opt-in.

## Delta path-collapse

`InternxtProvider.buildFolderPath` returns an empty string when an item's `parentUuid` is missing from the in-memory `folderMap`. Items with missing parents currently re-emerge at the root, orphaning subtrees and producing duplicate `remote_id` rows in `state.db`. This is the highest-severity correctness bug on the provider — first item in `BACKLOG.md`.

## Request-id correlation

Internxt's drive server emits `x-request-id` on every response. `InternxtApiService.extractRequestId` reads it; carried on `ProviderException.requestId` and surfaced in `unidrive.log` as `requestId=…`.
