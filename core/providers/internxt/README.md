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
| `GET /files/sync`, `GET /folders/sync` | cursor listing, keyset `(updatedAt, uuid)`, 1000 per page | `getFilesSync`, `getFoldersSync` — a full enumeration of the whole drive pages both streams to the end and resumes from the cursor of its last page (`InternxtCursorListing.kt`); the offset listing above is the fallback of a server without these endpoints |
| `GET /folders/v2/content/{uuid}/files`, `/folders` | cursor listing of one folder, `order`, `cursor`, `limit` 50..1000 (1000 used), reply `{files\|folders, nextCursor}` | `getFolderContentsPaged` — the fallback of the tree walk (#640) for a folder whose combined `GET /folders/content/{uuid}` call fails server-side. Only a 404/405 on a v2 call falls back to the deprecated offset endpoints `GET /folders/content/{uuid}/files`, `/folders`, whose `limit` is required and at most 50 (50 used, `sort=uuid`, #647); any other client error, a 400 included, propagates |
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

- **Auth refresh** is per-call: `AuthService.getValidCredentials` refreshes when the JWT is within its refresh margin (a pure JWT-payload decode + `exp` comparison) or when forced. Refresh is mutex-serialised with double-check and runs `NonCancellable` (`RefreshableTokenLatch`). On Drive REST a 401 forces one refresh and one replay (`withAuthRetry`); a second 401 is an auth failure. Bridge calls use HTTP Basic and are not wrapped.
- **Retry placement**: Drive reads (`authenticatedGet`) make up to three attempts, 2 s then 4 s apart, on 429/500/502/503/504 and on network errors. Drive mutations go through `retryOnTransient` (three attempts, same statuses, 1 s doubling). Cloudflare's origin errors (520 to 524, "the origin did not answer") end as the 503 the fallbacks act on: 520 to 523 go through the ladder, a 524 is one attempt (the gateway had waited about two minutes, a repeat gets the same 524); the gateway's status and body stay in the exception's cause, its `Retry-After` on the exception. The Bridge calls of an upload retry at the provider layer (`retryShardCommit`, with `indexBytes` pinned); the in-memory shard PUT uses `retryOnTransient`.
- **Retry-After**: the HTTP header (delta-seconds or HTTP-date) wins over the JSON-body `retry_after`, which wins over the exponential default; every wait is clamped to 500 ms..60 s. A 429 or 503 is also recorded on the host's `HttpRetryBudget`. No `X-RateLimit-*` header is consumed.
- **Timeouts**: the socket timeout is a read-idle timer (time since bytes were last *received*). Drive GETs run with 60 s; the listings whose cost grows with what they list, the account-wide `/files` and `/folders` (a page needs a minute or more on a large account), the cursor listings `/files/sync` and `/folders/sync`, and a folder's `/folders/content/{uuid}` (106 s for a flat folder of 29,000 files), run with 330 s and a request timeout a minute above it. The gateway answers a request that took about two minutes with a 524 of its own, so a folder needing more than that is out of reach of the content call.
- **A cursor the server cannot answer**: resuming a cursor repeats the identical request, so a cursor the server answers with a timeout every time (live, HTTP 408 after a constant ~115 s) would keep a first scan from ever ending. When a cursor stream fails twice in a row on the same cursor with the same kind of failure (408, or the 5xx family), it leaves the cursor and continues from an `updatedAt` two minutes before the cursor's own instant, without the status filter, the way the official client does; the rows that are not live (`status` other than `EXISTS`) are dropped locally. The first failure still resumes the same cursor, and a 5xx still hands the gather to the tree walk first. The failure record and the switch are part of the scan marker, so a restart in between carries on correctly; a row of an unfiltered page without a `status` fails the listing instead of being guessed at.
- **Unavailable account-wide listing**: when a gather ends in a 500/503 (or a gateway 520 to 524, which ends as a 503) on `/files` or `/folders`, `delta()` walks the folder tree instead; inside the walk a folder whose content ends that way is skipped and the gather is reported incomplete. If it was a full gather, the next full gathers within 30 minutes do not try the listings again but walk straight away (kept in the process: the first full gather after a restart pays one attempt); an incremental poll never reads or opens that window and keeps asking its cheap `updatedAt` listing.
- **Concurrency**: one `HttpRetryBudget` per host, acquired per HTTP attempt: Drive REST 2 concurrent with 500 ms spacing (1 s during a throttle storm), Bridge and CDN 4 concurrent with no spacing. These are unidrive's own caps, chosen to stay at or below what Internxt's desktop client uses (see *Vendor limits and signals*).
- **Idempotency**: Drive POSTs are body-replay-safe (String). `finishUpload` is not idempotent on the server: after a lost response or a 409 `MissingUploadsError` the provider lists the parent folder and adopts an entry matching name, type, size and a recency window (`commitWithRetry`). If a 409 has no matching entry, that Bridge session is exhausted: one bounded recovery starts a fresh session and re-PUTs the pinned ciphertext/index; another miss is refused without repeating finish. Refused content is eligible for another attempt after six hours. The server offers no `Idempotency-Key`, so some orphan shards stay unrecoverable client-side.
- **HTML-body guard**: the download path rejects an HTML body (`assertNotHtml`) and checks the ciphertext against the shard hash the network recorded, before any plaintext is published; otherwise a captive-portal page would run through AES-CTR and come out as bytes indistinguishable from plaintext. The check follows the recorded shape (`ShardHashExpectation`): 40 hex characters are RIPEMD-160(SHA-256(ciphertext)), which every official client records in `files/finish` (inxt-js `HashStream.getHash`, drive-web `getRipemd160FromHex`, the mobile and SDK clients); 64 hex characters are SHA-256(ciphertext), which unidrive's own uploads record. A mismatch of either refuses the content (`RemoteContentRefusedDownloadException`, the message names the algorithm). A value in no known shape (blank, another length, not hex) is not a mismatch: the object is read without the ciphertext check and one WARN per object and run gives the value's length and character class, never the value.

## Vendor limits and signals

Internxt publishes no rate-limit numbers and no throttling contract that we found. **Published** below means visible in Internxt's open-source SDK, CLI or desktop client, or in their release notes; **observed** means seen in unidrive runs. The API documentation host could not be reached when this was last checked, so its text is not covered.

- Published: the server answers 429. Internxt's CLI release notes list automatic retry for 429 ([releases](https://github.com/internxt/cli/releases)), and the SDK's opt-in `retryWithBackoff` ([source](https://github.com/internxt/sdk/blob/master/src/shared/http/retryWithBackoff.ts)) retries only 429, waits the `Retry-After` header read as integer seconds, caps the wait at 70 s, gives up after 5 retries, and rethrows at once when the header is missing. unidrive differs on purpose: it also accepts an HTTP-date, falls back to the JSON `retry_after` and then to exponential backoff, and retries 5xx.
- Observed: Cloudflare-fronted error bodies can carry `"retry_after": <seconds>` instead of the header; both are read (`InternxtApiService.parseRetryAfter`).
- Published, pacing: the SDK has no request limiter. Internxt's desktop client wraps its Drive API client in one: at last check 3 concurrent requests with a 333 ms minimum interval, 4 concurrent uploads and 1 concurrent download (`drive-desktop`, `src/apps/shared/HttpClient/client.ts` and `src/apps/main/auth/handlers.ts`). These are that client's own choices, not server limits, and they have changed over time. unidrive's Drive budget (2 concurrent, 500 ms) stays below the desktop client's.
- Published, client identity: the SDK sends `internxt-client` and `internxt-version` on every call and `x-internxt-desktop-header` only when the host application supplies a token ([source](https://github.com/internxt/sdk/blob/master/src/shared/headers/index.ts)). Whether the gateway refuses unknown client names is unverified; see *Client headers* under Quirks.

## Encryption pipeline and the IV-pinning constraint

Upload (`InternxtProvider.kt`) runs in five stages:

For an overwrite, the provider compares the listing token (file UUID,
modification time, and size) with fresh metadata immediately before the
replace. Internxt has no conditional replace endpoint, so a concurrent change
in the narrow metadata-to-replace window remains possible; a mismatch before
that window is returned as a conflict and leaves the cloud copy untouched.

1. Generate random `indexBytes[32]`; derive `iv = indexBytes[0:16]`.
2. Derive `fileKey = SHA-512(bucketKey || indexBytes)[:32]` from the user's mnemonic-seeded bucket key.
3. AES-256-CTR encrypt local file → temp file via `CipherInputStream`; compute SHA-256 of ciphertext in the same pass.
4. PUT encrypted shard to the CDN.
5. `finishUpload` (Bridge) → register the file in Drive metadata with `indexHex` + `hashHex`.

**The `indexBytes` value sent to the server in step 5 is what the server stores and uses to derive the key+IV on download.** Any future retry logic on this pipeline must either:

- pin `indexBytes` (and therefore the derived `iv` and `fileKey`) across the entire retry window, holding the temp file and hash steady; or
- restart the full pipeline including a fresh `indexBytes` and re-encryption.

Mixing stages 1–3 (old IV) with stages 4–5 (new IV) is **silent corruption** — the bridge accepts the ciphertext but downloads decrypt against the new IV and produce garbage. There is no retry today; the moment one is added, this constraint binds. A unit test should freeze the IV across a forced retry.

## Robustness design record

The five pieces below have landed (status per capability in `BOOSTERS.md`); the section keeps the reasoning, including what an audit of Internxt's own sources refuted.

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
- **Client headers.** Every Internxt call sends `internxt-client: unidrive` and `internxt-version` (defaults `unidrive` / `0.0.1`; override with `INTERNXT_CLIENT_NAME` / `INTERNXT_CLIENT_VERSION`). `x-internxt-desktop-header` is **not sent by default**: in Internxt's official SDK it is an optional token that is forwarded only when a host application supplies one, and unidrive has none. An earlier build sent a made-up placeholder value that exists in no Internxt client. Set `INTERNXT_DESKTOP_HEADER` to a non-empty value to send it, e.g. to test against the live API. It has not been verified against the live API that the gateway accepts requests without it; that needs a check on a test account.
- **Filenames sometimes arrive with a leading `\n`.** Defensively `trim()`'d.
- **`DELETE /folders/{uuid}` takes a JSON body** listing items. RFC 7231 allows but discourages; some proxies strip DELETE bodies silently.
- **`x-api-version: 2` only on Bridge.** Drive endpoints don't accept it. Bridge paths mix `/v2/...` and unversioned: `/v2/buckets/.../start` for upload but `/buckets/.../files/{id}/info` for metadata.
- **Credentials use native host protection at rest.** Linux stores each version in the user's Secret Service collection through `secret-tool` (`libsecret-tools`); the collection must be unlocked. Windows uses DPAPI scoped to the current user profile. macOS stores credentials in the user's login Keychain; it must be unlocked and accessible to the process, including headless sessions. macOS is not a supported platform yet and is not exercised in CI; the Keychain code and its tests stay in the repo but only run when someone runs them on a Mac. Unsupported platforms and missing, locked, or inaccessible stores fail closed; credentials are never silently written in plaintext. Existing plaintext credential files are imported and verified with the selected native store before an atomic owner-only replacement removes the plaintext.
- **`keep_overwritten` per-profile flag** (default off). When on, every replace renames the prior cloud content to `${plainName}.unidrive-prev-${utcStamp}` before creating the new version. Pure recovery insurance — Internxt has no server-side versioning. Storage cost doubles per edit; no automatic prune. Encrypted `name` field on the archived row stays bound to the original plainName (rename only updates `plainName`). Manual cleanup via the upcoming `unidrive prune --backups` command (separate BACKLOG entry).

## Delta path-collapse

`InternxtProvider.buildFolderPath` returns null when an item's `parentUuid` is missing from the in-memory `folderMap`. Delta drops such an item and leaves the gather incomplete rather than re-emitting it at the root, which would orphan subtrees and duplicate `remote_id` rows (`docs/audits/internxt-phantom-investigation.md`).

## Resolving the paths after a listing

A listing returns live items with the uuid of their parent folder; a path needs every folder up to the root, and a folder that holds live items without being live itself (a trashed folder) is not in a listing of the live items. After a full listing the provider therefore finds, before it builds any path, every folder that some path runs through and that the listing did not return, in this order of cost:

1. **The listing.** The folders it returned.
2. **Ancestor-only records.** The listing without the status filter (see *A cursor the server cannot answer*) returns the trashed folders too. They are kept, never emitted as items, and staged beside the live rows with a tag of their own in the same transaction as their page, so a restart in between keeps them. A deleted folder is not kept: on the account this was measured on, none of the 948 folders needed was deleted, of 421,897 deleted against 47,241 trashed. The scan marker is unchanged, and a scan staged before these records existed resumes as it did.
3. **A request.** `GET /folders/{uuid}/meta` for each folder still missing, once each, with at most four in flight (the tree walk's limit). The server answers a trashed folder with HTTP 200 and its row. A folder that hangs below another missing one is found in a second round. A folder whose meta cannot be read or is gone drops the items below it and makes the page incomplete.

The step is reported in `daemon.status.enumeration` as `phase: resolving_paths` with `paths_done` and `paths_total`; before it existed the status stood on the listing's last item count for as long as the requests took (eight minutes for 949 folders asked for one after the other).

## The trash

Internxt trashes a folder and leaves what is in it as it was: the items below keep reporting `EXISTS`, name the trashed
folder as their parent, and a listing of the live items carries them at the path of their trashed ancestors, as if they
were live. A first scan of one account listed 5,536 files and 266 folders that way. They are not live, and the web UI shows
the trash apart.

**Detection.** A folder is in the trash when its own `status` is not `EXISTS` or when a folder above it is not; an item is
in the trash when its parent folder is (`TrashChains`). The status is read the same way for a folder the listing returned,
one of the ancestor-only records of the walk without the status filter, and one fetched by its meta, which answers HTTP 200
for a trashed folder like for a live one. The provider marks such an item `CloudItem.trashed`, at the path of its own tree,
and reports the trashed folders that a live item hangs below as trashed folders too, so the engine can show the way to them.
A tombstone stays one: a file trashed in a live folder, and (in an incremental delta) a folder trashed since the cursor, are
deletions as before.

**What the engine does with the flag** (provider-neutral, `TrashNamespace`): the item goes under a virtual top-level folder
(default `Papierkorb`, profile key `trash_folder_name`) at `<folder><original path>`, keeps its id, and is read-only: the
engine never sends an upload, a delete, a move or a rename for it, ignores local changes there, and reading, hydrating
and copying out work as everywhere else. `hydration.list` entries carry `read_only` and `trash_root`, `daemon.status` the
`trash` object (docs/dev/specs/ipc-write-protocol.md section 4.1, unidrive-daemon-design.md section 4.3).

**Scope.** The folders that hold live items below them, and those items. The listings already carry them: no extra request
is made. Trashed files, and trashed folders with nothing live below them, are not shown. A profile stored by an earlier
version holds these items at their original paths; the next full listing gives them the new path with the same id, which the
engine's rename machinery turns into a move of the row (a modified file moves with its bytes and is not uploaded). An
incremental poll does not re-place items it does not deliver again, so a profile moves at its next full listing (a reset or a
re-scan).

## Request-id correlation

Internxt's drive server emits `x-request-id` on every response. `InternxtApiService.extractRequestId` reads it; carried on `ProviderException.requestId` and surfaced in `unidrive.log` as `requestId=…`.
