# Internxt API ↔ Unidrive Provider Audit
*Generated 2026-05-03 by audit agent. Static — no live calls.*

## Executive summary

`InternxtProvider.delta()` CAN report `/_INBOX/wiederherstellungs_codes.txt` as
remote-deleted while it still exists on Internxt — at least three independent
mechanisms in the current code can produce that outcome. The single most
important finding is that `delta()` synthesizes a change feed from two flat,
independently paginated listings (`GET /files`, `GET /folders`) and that any
mid-pagination 4xx (or any non-`{500,503}` status) returned by `GET /folders`
aborts the whole gather with `hasMore=false` already committed for the file
half — but the engine never sees an "abort" signal because the exception
propagates straight out of `delta()` and the ticket-level reasoning ("the
gather succeeded") was never true. Other contributing risks: the `/files`
fallback path (`collectFilesFromFolders`) silently skips entire subtrees on
500/503, the `--sync-path` filter is engine-side only (no narrowing at the
provider request layer), the cursor `updatedAt` filter does not include
`status` semantics so an item TRASHED-then-restored with no further mutation
can disappear from the page set, and the `/files` GET sends `status=ALL` while
the deletion-detection logic in `toDeltaCloudItem` flags `TRASHED|DELETED`
correctly only when those rows actually appear in the response. The provider
otherwise hits the published API with the documented shapes; the highest-
priority class is **Used-but-divergent on `/auth/login*`** (undocumented
endpoint family) and the structural absence of a true change feed.

## API discovery

### Spec URLs probed

| URL | Result |
|---|---|
| `https://api.internxt.com/drive/` | Swagger UI HTML shell (200) |
| `https://api.internxt.com/drive/swagger-ui-init.js` | **Source of truth** — embeds the entire OpenAPI 3.0.0 doc inline as `options.swaggerDoc` |
| `https://api.internxt.com/drive/api-json` | 404 |
| `https://api.internxt.com/drive/api-docs` | 404 |
| `https://api.internxt.com/drive/swagger.json` | 404 |
| `https://api.internxt.com/drive/openapi.json` | 404 |
| `https://api.internxt.com/drive/v3/api-docs` | 404 |
| `https://api.internxt.com/drive/api-docs.json` | 404 |
| `https://gateway.internxt.com/drive/` | 403 (CDN edge — does not serve docs but does serve the same `/drive/*` routes when bearer-authed) |
| `https://gateway.internxt.com/drive/swagger-ui-init.js` | 403 |
| `https://api.internxt.com/` | 500 |
| `https://api.internxt.com/photos/` | 404 |
| `https://api.internxt.com/payments/` | 404 |
| `https://api.internxt.com/buckets/` | 401 (Bridge — authentication required, no public spec) |

### Final source-of-truth references

* **Drive API**: embedded swagger doc at
  `https://api.internxt.com/drive/swagger-ui-init.js` (OpenAPI 3.0.0 inline).
  Tags: `notifications`, `User`, `Folder`, `File`, `Sharing`. No `info.title`,
  no `info.version`, no `servers[]` block — i.e. the spec is silent on which
  host serves it. The provider treats `https://gateway.internxt.com/drive` as
  the canonical host (`InternxtConfig.kt:10`); `api.internxt.com/drive` serves
  identical routes but is observably the docs host.
* **Bridge API**: no public spec. Reverse-derived from open-source
  `internxt/inxt-js` (legacy Storj-fork). Provider hits
  `https://api.internxt.com/buckets/{bucket}/files/{fileId}/info`,
  `/v2/buckets/{bucket}/files/start`, `/v2/buckets/{bucket}/files/finish`
  (`InternxtApiService.kt:290,326-368`).
* **Auth endpoints** (`/auth/login`, `/auth/login/access`) used by
  `AuthService.kt:100,134`: **NOT in the public Drive swagger**. Undocumented
  but stable per Internxt's open-source desktop client.

### Surface inventory

| Service | Host | Spec | Provider uses |
|---|---|---|---|
| Drive | `gateway.internxt.com/drive` (provider), `api.internxt.com/drive` (docs) | OpenAPI 3.0.0 (inline swagger) | yes — primary surface |
| Auth | same host, `/auth/*` | undocumented | yes — login + 2FA |
| Bridge / Bucket | `api.internxt.com` (`/buckets/*`, `/v2/buckets/*`) | undocumented (reverse-derived from `inxt-js`) | yes — content blob storage |
| Photos | `api.internxt.com/photos/` | none discoverable | no |
| Payments | `api.internxt.com/payments/` | none discoverable | no |
| Notifications | `/notifications` (Drive tag) | swagger | no |
| Workspaces / Backups / Trash (top-level) | not present in swagger | — | no |

## Capability matrix

Legend: ✅ Used — ⚠️ Used-but-divergent — ◯ Available-unused — ❓ Used-but-undocumented

### Drive — File endpoints

| Endpoint | Verb | Status | Provider site | Notes |
|---|---|---|---|---|
| `/files` | POST | ✅ | `InternxtApiService.kt:135-140` | `createFile`. Body shape matches `CreateFileDto` (bucket, folderUuid, plainName, name, size, encryptVersion="03-aes", type, fileId). |
| `/files` | GET | ⚠️ | `InternxtApiService.kt:76-90` | Sends `status=ALL`, `limit`, `offset`, optional `updatedAt`. Spec says `limit` 1-1000; provider hardcodes 50 (`InternxtProvider.kt:301`). Does NOT send `sort`/`order`, so server-side ordering is undefined — pagination correctness depends on stable default order, which the spec does not guarantee. **Highest-risk read path.** |
| `/files/count` | GET | ◯ | — | Could shortcut full-scan ETA. |
| `/files/limits` | GET | ◯ | — | Could feed `maxFileSizeBytes()` (currently null). |
| `/files/recents` | GET | ◯ | — | |
| `/files/meta` | GET (?path) | ◯ | — | Provider builds metadata via folder-walk + listing (`getMetadata`, `InternxtProvider.kt:73-97`). The server can do this in one round trip. |
| `/files/{uuid}/meta` | GET | ✅ | `InternxtApiService.kt:108-111` | `getFileMeta`. |
| `/files/{uuid}/meta` | PUT | ◯ | — | Rename without move. |
| `/files/{uuid}/versions` | GET | ◯ | — | Capability gap — no version recovery. |
| `/files/{uuid}/versions/{versionId}` | DELETE | ◯ | — | |
| `/files/{uuid}/versions/{versionId}/restore` | POST | ◯ | — | |
| `/files/{uuid}` | PUT | ✅ | `InternxtApiService.kt` `replaceFile` | UD-366: replace-in-place. `ReplaceFileDto` accepts `fileId` (new bridge entry), `size`, optional `modificationTime` — endpoint preserves bucket/encryptedName/folderUuid. Routed via MODIFIED `SyncAction.Upload(remoteId=…)` from the reconciler. |
| `/files/{uuid}` | PATCH | ✅ | `InternxtApiService.kt:179-185` | `moveFile`. |
| `/files/{uuid}` | DELETE | ✅ | `InternxtApiService.kt:208-215` | `deleteFile`. |
| `/files/{bucketId}/{fileId}` | DELETE | ◯ | — | Alternate delete by bridge fileId. |
| `/files/thumbnail` | POST | ◯ | — | |

### Drive — Folder endpoints

| Endpoint | Verb | Status | Provider site | Notes |
|---|---|---|---|---|
| `/folders` | POST | ✅ | `InternxtApiService.kt:158-163` | Single-folder create (uses `parentFolderUuid` + `plainName` + `name`). Multi-folder batch form is unused. |
| `/folders` | GET | ⚠️ | `InternxtApiService.kt:92-106` | Same divergence as `/files` GET — hardcoded `limit=50`, no `sort`/`order`. |
| `/folders` | DELETE | ✅ | `InternxtApiService.kt:217-240` | `deleteFolder` (sends `items: [{uuid, type: "folder"}]`). |
| `/folders/count` | GET | ◯ | — | |
| `/folders/content/{uuid}` | GET | ✅ | `InternxtApiService.kt:71-74` | `getFolderContents` returns `{children, files}` per `FolderContentResponse.kt`. **Note:** spec `GetFolderContentDto` returns the folder's own metadata wrapped around children/files; provider DTO at `InternxtFile.kt:25-29` only deserialises `children` + `files` and ignores the rest. Silent-default on parent metadata is benign (provider doesn't need it) but is divergent. |
| `/folders/content/{uuid}/files` | GET | ◯ | — | Paginated child files. Could replace `/folders/content/{uuid}` for large folders. |
| `/folders/content/{uuid}/folders` | GET | ◯ | — | |
| `/folders/content/{uuid}/folders/existence` | POST | ◯ | — | Could replace the 409-recovery dance in `createFolder` (`InternxtProvider.kt:256-267`). |
| `/folders/content/{uuid}/files/existence` | POST | ◯ | — | |
| `/folders/{uuid}/meta` | GET | ◯ | — | |
| `/folders/{uuid}/meta` | PUT | ◯ | — | Rename. |
| `/folders/{uuid}` | PATCH | ✅ | `InternxtApiService.kt:199-205` | `moveFolder`. |
| `/folders/{uuid}` | DELETE | ◯ | — | Provider uses `DELETE /folders` (collection form) instead — see above. |
| `/folders/{uuid}/stats` | GET | ◯ | — | |
| `/folders/{uuid}/ancestors` | GET | ◯ | — | Could short-circuit `buildFolderPath` recursion. |
| `/folders/{uuid}/tree` | GET | ◯ | — | Could collapse `delta()` and `collectFilesFromFolders` into one call. |
| `/folders/{uuid}/size` | GET | ◯ | — | |
| `/folders/meta` | GET (?path) | ◯ | — | |

### Drive — User / quota endpoints

| Endpoint | Verb | Status | Provider site | Notes |
|---|---|---|---|---|
| `/users/usage` | GET | ✅ | `InternxtApiService.kt:423` | `getQuota`. |
| `/users/limit` | GET | ✅ | `InternxtApiService.kt:424` | `getQuota`. |
| `/users/refresh` | GET | ✅ | `AuthService.kt:185` | Token refresh. |
| `/users/cli/refresh` | GET | ◯ | — | Distinct CLI variant — provider could move to this for client-attribution. |
| `/users/me/upload-status` | GET | ◯ | — | |
| `/users/generate-mnemonic` | GET | ◯ | — | |
| (35+ other `/users/*` paths) | various | ◯ | — | Account / email / avatar / recovery — out of scope for a sync client. |

### Drive — Sharing endpoints

| Endpoint | Status | Notes |
|---|---|---|
| `/sharings` POST + `~30` related paths | ◯ | Capability-gap. `Capability.Share` / `Capability.ListShares` / `Capability.RevokeShare` are declared in `CloudProvider.kt:127-141` and the Internxt provider returns `Unsupported` (`InternxtProvider.kt:46-50` doesn't include them). Server fully supports a rich sharing model: invitations, roles, public links with passwords, shared-with-me / shared-by-me listings. |

### Drive — Notifications

| Endpoint | Status | Notes |
|---|---|---|
| `/notifications` GET / POST, `/notifications/{id}/expire` PATCH | ◯ (marketing-only) | UD-370 Phase 0 confirmed this is an account-level promotional channel — schema example is literally a Black Friday banner. Not a file-mutation event feed. See [internxt-notifications-feasibility.md](internxt-notifications-feasibility.md). No real-time invalidation surface exists in the public Internxt spec. |

### Auth endpoints (NOT in public spec)

| Endpoint | Verb | Status | Provider site | Notes |
|---|---|---|---|---|
| `/auth/login` | POST | ❓ | `AuthService.kt:100` | Login challenge. Not in swagger; vendor-flux risk. |
| `/auth/login/access` | POST | ❓ | `AuthService.kt:134` | Access token + 2FA. Not in swagger. |

### Bridge / Bucket endpoints (NOT in Drive spec)

| Endpoint | Verb | Status | Provider site | Notes |
|---|---|---|---|---|
| `/buckets/{bucket}/files/{fileId}/info` | GET | ❓ | `InternxtApiService.kt:330` | Encryption index + shard URLs. |
| `/buckets/{bucket}/files/{fileId}` | GET | ❓ | `InternxtApiService.kt:338` | Mirrors. |
| `/v2/buckets/{bucket}/files/start` | POST | ❓ | `InternxtApiService.kt:347` | Start multipart upload. |
| `/v2/buckets/{bucket}/files/finish` | POST | ❓ | `InternxtApiService.kt:362-366` | Finish + register. |
| OVH presigned PUT | PUT | ❓ | `InternxtApiService.kt:375,403` | Third-party `s3.gra.io.cloud.ovh.net` — see `OVH_PUT_MIN_THROUGHPUT_BPS` comment. |

## DTO comparison

### `InternxtFile` ↔ `FileDto`

`core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/model/InternxtFile.kt:5-23`

| Field | Provider type | Spec field exists? | Verdict | Notes |
|---|---|---|---|---|
| `id` | `Long` | yes | ✅ | |
| `uuid` | `String` | yes | ✅ | |
| `fileId` | `String?` | yes | ✅ | |
| `name` | `String?` | yes | ✅ | Encrypted name. |
| `plainName` | `String?` | yes | ✅ | |
| `type` | `String?` | yes | ✅ | Extension. |
| `size` | `String` (default `"0"`) | yes (number in spec) | ⚠️ | Provider deserialises as String — Internxt returns size as JSON string, so parsing as String avoids a Long overflow on 4 GiB+ files where some servers return scientific-notation. Conversion to Long happens in `fileToCloudItem` via `toLongOrNull() ?: 0` — silent zero on parse failure. |
| `bucket` | `String?` | yes | ✅ | |
| `folderId` | `Long` | yes | ✅ | |
| `folderUuid` | `String?` | yes | ✅ | |
| `encryptVersion` | `String?` | yes | ✅ | |
| `status` | `String` (default `"EXISTS"`) | yes (enum: `EXISTS|TRASHED|DELETED|ALL`) | ⚠️ | Default-on-missing means a server response that *omits* `status` deserialises as EXISTS — masks an Internxt-side schema regression. |
| `creationTime` / `modificationTime` | `String?` | yes | ✅ | ISO-8601. |
| `createdAt` / `updatedAt` | `String?` | yes | ✅ | Used by delta cursor. |
| `removed` | — (not in DTO) | yes (boolean in spec) | ❌ | **`InternxtFile` has no `removed` or `deleted` field.** Provider relies solely on `status` for files (`InternxtProvider.kt:494`). If the server ever returns `removed=true`/`deleted=true` while leaving `status="EXISTS"`, the provider treats the file as alive. Asymmetric with `InternxtFolder` which DOES carry `removed`+`deleted`. |
| `deleted` | — (not in DTO) | yes (boolean) | ❌ | Same as above. |

### `InternxtFolder` ↔ `FolderDto`

`core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/model/InternxtFolder.kt:5-24`

| Field | Provider type | Spec field exists? | Verdict | Notes |
|---|---|---|---|---|
| `id`, `uuid`, `name`, `plainName`, `type`, `parentId`, `parentUuid`, `bucket`, `encryptVersion` | matches | yes | ✅ | |
| `status` | `String` (default `"EXISTS"`) | yes (enum) | ⚠️ | Same default-on-missing concern. |
| `size` | `Long` (default 0) | yes | ✅ | |
| `creationTime`/`modificationTime`/`createdAt`/`updatedAt` | `String?` | yes | ✅ | |
| `removed` | `Boolean` (default false) | yes | ✅ | Surfaced into `deleted` flag at `InternxtProvider.kt:515`. |
| `deleted` | `Boolean` (default false) | yes | ✅ | Same. |

### `FolderContentResponse` ↔ `GetFolderContentDto`

`InternxtFile.kt:25-29`

| Field | Provider | Spec | Verdict |
|---|---|---|---|
| `children` | `List<InternxtFolder>` | yes | ✅ |
| `files` | `List<InternxtFile>` | yes | ✅ |
| (parent metadata fields per `GetFolderContentDto`) | — | yes | ⚠️ (silent-drop) |

### Bridge DTOs (`BridgeModels.kt`)

No public spec to compare against. Reverse-derived from `inxt-js` source, structurally stable since Storj fork.

## delta() forensic findings

### 1. Is there a true change-feed?

**No.** The Drive swagger has no `/changes`, no `/delta`, no `/since`
endpoint. Available pagination knobs on `GET /files` and `GET /folders`:
`limit`, `offset`, `status`, `bucket` (deprecated), `sort` (`updatedAt|uuid`),
`order` (`ASC|DESC`), `updatedAt` (filter "modified after"). The `updatedAt`
filter is what the provider calls "cursor", but it is a high-water-mark
filter, not a tombstone-bearing change feed.

`InternxtProvider.kt:293-354` reconstructs change state by paginating two
flat full-listings with `status=ALL` and `updatedAt > cursor-6h`. Items
outside that updatedAt window are invisible to the gather. Combined with
the engine's "items not in remoteChanges → del-local" reconciliation
(`SyncEngine.kt:163-168`), this is the structural class of bug.

### 2. Tombstone semantics

The Drive spec defines `status` as `EXISTS | TRASHED | DELETED | ALL`. With
`status=ALL`, deleted/trashed rows ARE returned and the provider correctly
maps `TRASHED|DELETED` to `CloudItem.deleted=true` (`InternxtProvider.kt:494,
542`). Folders additionally carry `removed`/`deleted` boolean flags, also
surfaced (`InternxtProvider.kt:515,562`).

**Gap:** `InternxtFile` has no `removed`/`deleted` fields (see DTO table).
For files, only the string `status` field signals deletion. The spec lists
`removed` and `deleted` as `FileDto` properties — the provider silently
drops both on deserialisation.

`status=ALL` does ensure tombstones are surfaced rather than dropped, so
there IS a tombstone path; the question is whether the gather always sees
that tombstone. Question 3 below shows it doesn't.

### 3. Pagination correctness

```kotlin
// InternxtProvider.kt:298,452-455
val adjustedCursor = cursor?.let { rewindCursor(it) }
fun rewindCursor(cursor: String): String =
    Instant.parse(cursor).minus(6, ChronoUnit.HOURS).toString()
```

```kotlin
// InternxtProvider.kt:312-340
try {
    var offset = 0
    while (true) {
        val batch = api.listFiles(adjustedCursor, limit, offset)
        allFiles.addAll(batch)
        if (batch.size < limit) break
        offset += limit
    }
} catch (e: InternxtApiException) {
    if (e.statusCode in listOf(500, 503)) {
        log.warn(...)
        collectFilesFromFolders(creds.rootFolderId, allFiles, 0)
    } else {
        throw e
    }
}

var offset = 0
while (true) {
    val batch = api.listFolders(adjustedCursor, limit, offset)
    allFolders.addAll(batch)
    if (batch.size < limit) break
    offset += limit
}
```

**Failure modes that produce a smaller-than-truth inventory:**

a) **`/files` returns 4xx mid-pagination.** The catch block ONLY rescues
`{500, 503}`. A 502 (Cloudflare bad gateway, very common per the UD-335
comment), 504, 429, or 401 mid-walk propagates straight out — but
`authenticatedGet` (`InternxtApiService.kt:430-461`) only retries 500/503
internally. A 502 on page 7 of 100 surfaces as an `InternxtApiException`,
`delta()` aborts with `allFiles` containing pages 0-6 only, and the
exception bubbles to the engine. The engine's gather DOES re-throw, so this
particular sub-case is loud — but only if it's a non-{500,503} error.

b) **`/folders` returns ANY error mid-pagination.** The folder loop has NO
try/catch. Any exception aborts gather entirely. That is the loud-fail case
— but if a redirect or 200-with-empty-body short-circuits the loop early
(see (c)), there is no exception.

c) **Server-side ordering is unstable.** The provider sends NO `sort`/
`order` parameter. The spec's default ordering is unspecified. If the
server reorders rows between page requests (e.g. `updatedAt` ties broken
non-deterministically, or new items inserted into the middle of the
sequence during a long walk), pages can drop or duplicate items. A dropped
file from page N → file absent from `allFiles` → engine emits `del-local`.

d) **`hasMore=false` is hardcoded** (`InternxtProvider.kt:353`) regardless
of whether the gather completed cleanly. There is no signal to the engine
that the inventory is partial.

e) **The 6-hour cursor rewind is one-shot.** Items modified between
`cursor-6h` and `cursor` are re-checked, but a delete that propagated
to `status=DELETED` more than 6 hours ago and whose `updatedAt` is
older than the cursor will not appear in the page set. If the engine's
state DB still has the file from a prior sync, and the file is now
absent from `allFiles`, the engine emits `del-local`. **This is a
plausible mechanism for the recovery-codes file.**

f) **Inserts during walk.** A new file created while the walk is in
progress can be inserted at offset N into a list the provider has already
read past; the provider then never sees it. This produces spurious
`upload-local` (in upload mode) or no action (in download-only) — not the
observed `del-local`. Listed for completeness.

### 4. Recursive fallback divergence

```kotlin
// InternxtProvider.kt:375-398
private suspend fun collectFilesFromFolders(folderUuid, accumulator, depth) {
    val content = try { api.getFolderContents(folderUuid) }
                  catch (e: InternxtApiException) {
                      if (e.statusCode in listOf(500, 503)) {
                          log.warn("Skipping folder {} ({}): {}", ...)
                          return                              // <-- silent skip
                      }
                      throw e
                  }
    accumulator.addAll(content.files)
    for (child in content.children) {
        if (child.status == "EXISTS" && !child.removed && !child.deleted) {
            collectFilesFromFolders(child.uuid, accumulator, depth + 1)
        }
    }
}
```

Two divergences from the primary `/files` + `/folders` path:

i) **Silent subtree skip on 500/503.** A flaky `getFolderContents` for
`/_INBOX` returns from `collectFilesFromFolders` with no entries from that
subtree, no exception, no signal to delta(). Every file under `/_INBOX`
is now absent from `allFiles`. The engine then emits `del-local` for
each. **This is the second plausible mechanism.**

ii) **`children` filter excludes TRASHED.** `child.status == "EXISTS"`
means a trashed-then-restored folder whose row currently shows `EXISTS`
is recursed into, but a TRASHED folder containing a file the user later
restored will NOT have its files surfaced. Less relevant to the
recovery-codes case (which is a file under `/_INBOX`, not a TRASHED
ancestor) but still a divergence vs the flat `/files` listing which
returns all files regardless of containing-folder status.

iii) **No `accumulator` for folders.** The fallback only fills `allFiles`,
not `allFolders`. The folder loop runs separately and CAN throw if the
flat `/folders` listing fails — but the file fallback already executed.
Net: in the fallback regime, the folder map could be partial too, which
breaks `buildFolderPath` for files whose containing folder isn't in
`folderMap` — `parentPath` resolves to `""` and the file appears at the
drive root with the wrong path. The engine then sees the file as missing
from its expected `/_INBOX/...` path → `del-local`. **Third mechanism.**

### 5. Sync-path scope handling

**Engine-side only.** `SyncEngine.kt:163-168`:

```kotlin
val remoteChanges =
    if (syncPath != null) {
        allRemoteChanges.filterKeys { it.startsWith(syncPath) || it == syncPath }
    } else {
        allRemoteChanges
    }
```

`InternxtProvider.delta()` ignores `--sync-path` entirely. There is no
provider-side narrowing of the API request — every sync, regardless of
`--sync-path=/_INBOX`, walks the entire drive. Any of the failure modes
in §3/§4 affects items globally; if `delta()` returns an inventory missing
`/_INBOX/wiederherstellungs_codes.txt`, the post-filter still produces the
spurious `del-local`.

The recursive fallback `collectFilesFromFolders` IS root-rooted at
`creds.rootFolderId` (`InternxtProvider.kt:325`) — also no `--sync-path`
narrowing. A `--sync-path=/_INBOX` could in principle resolve `/_INBOX` to
a folder UUID and recurse from there, but the code does not.

### 6. Concrete answer

**YES.** `InternxtProvider.delta()` can report `/_INBOX/wiederherstellungs_codes.txt`
as remote-deleted while it still exists on Internxt. Distinct mechanisms:

1. **`/files` cursor-rewind window misses the row** — file's `updatedAt`
   is older than `cursor - 6h`, so the `updatedAt` filter excludes it,
   and the engine treats absence-from-`allRemoteChanges` as deletion.
   (§3e)

2. **`/files` non-{500,503} error mid-pagination** does NOT throw past
   the engine — wait, it does throw. Re-classify: this is loud. But:
   **`/folders` non-{500,503} error mid-pagination silently truncates the
   folder map.** A file whose containing folder is missing from
   `folderMap` resolves to `parentPath=""` and the file appears as
   `/wiederherstellungs_codes.txt` (drive root). Engine looks for
   `/_INBOX/wiederherstellungs_codes.txt`, finds nothing → `del-local`. (§4iii)

3. **`/files` 500/503 triggers `collectFilesFromFolders` fallback, and
   `/_INBOX` itself returns 500/503** — the entire `/_INBOX` subtree is
   silently skipped. (§4i)

4. **Server-side row reorder during walk** drops the file from page set.
   No `sort`/`order` parameter sent. (§3c)

5. **`status` field comes back missing/changed** — `InternxtFile.status`
   defaults to `"EXISTS"`, masking server schema drift. The provider does
   not deserialise `removed`/`deleted` on files at all. (§DTO)

There is no safety net against any of these. `hasMore` is always
hardcoded `false` (`InternxtProvider.kt:353`); the engine has no signal
that the gather may be partial.

The `SyncEngine.kt:201-218` empty-local-but-populated-DB guard does NOT
fire here (DB has entries, local has files — they just disagree about
one specific path). The `maxDeletePercentage` safeguard (`SyncEngine.kt:
279-296`) only fires when `deleteCount > 10` AND exceeds the percentage
threshold — a single-file silent-drop sails past it.

## Recommended UD-### tickets

1. **Send `sort=uuid&order=ASC` on `/files` and `/folders` GETs** — class:
   bug — P1 / S — Without an explicit stable sort, paginated full-scans
   can drop or duplicate rows whenever the server-side default order
   reshuffles between requests; this is a structural correctness
   prerequisite for `delta()`. AC: both `listFiles` and `listFolders` send
   `sort` + `order`, and an integration test asserts that two consecutive
   walks of the same drive return the same `uuid` set.

2. **Add `removed` / `deleted` to `InternxtFile` DTO** — class: bug — P1 /
   S — `FileDto` in the spec exposes both, the provider silently drops
   them, and the deletion-detection logic ignores any signal that doesn't
   come through `status`. AC: `InternxtFile` has both fields with
   `Boolean = false` defaults, and `fileToDeltaCloudItem` ORs them into
   the `deleted` flag analogous to folders.

3. **Make `delta()` signal partial gather instead of silent
   `hasMore=false`** — class: bug — P0 / M — The current
   `DeltaPage(hasMore=false)` is a lie when any subtree was skipped or
   the cursor window omitted older rows. The engine's "absence ⇒
   delete" reconciliation depends on the inventory being complete. AC:
   `delta()` sets `hasMore=true` if `collectFilesFromFolders` skipped any
   folder OR if any non-{500,503} error was caught; the engine treats
   `hasMore=true` as "do not run del-local sweep this run".

4. **Surface `collectFilesFromFolders` skip count to the engine** —
   class: bug — P0 / S — Sub-ticket of (3): right now the silent-skip on
   500/503 inside the recursion has no observable effect except a
   log.warn. AC: the recursion accumulates a skip-counter and the
   provider raises a `ProviderException` (or marks the page partial) if
   any subtree was skipped.

5. **Narrow `delta()` request to `--sync-path` when set** — class:
   capability-gap — P2 / M — `--sync-path=/_INBOX` still pulls the entire
   drive's `/files` + `/folders` listing. With server-side
   `/folders/{uuid}/tree` or `/folders/content/{uuid}/files` the provider
   could request only the subtree. AC: when `syncPath` is plumbed to the
   provider, the wire-level call set narrows accordingly.

6. **Replace flat-listing reconstruction with `/folders/{uuid}/tree`** —
   class: divergence-risk — P2 / L — The endpoint exists in the spec but
   is unused. A single tree call is atomic where two flat paginated calls
   are not, eliminating reorder/insert races (§3c, §3f). AC: a feature-
   flagged code path uses `/tree` and matches the legacy result set on
   live test fixtures.

7. **Document `/auth/login*` and Bridge endpoints as undocumented in
   architecture docs** — class: doc — P3 / S — These three vendor-flux
   surfaces are absent from `docs/ARCHITECTURE.md`'s shared utilities
   inventory and the Internxt section is silent on Bridge. AC: a section
   in ARCHITECTURE.md (or a new `docs/dev/lessons/internxt-undocumented-
   surfaces.md`) lists them with the source-of-truth links and a
   reverse-derivation note.

8. **Use `/files/limits` to populate `maxFileSizeBytes()`** — class:
   capability-gap — P3 / S — Currently null; a wasted PUT can run to its
   full timeout for an oversized file. AC: provider override returns the
   server-reported limit, refreshed on auth.

9. **Declare and implement `Capability.Share` family** — class:
   capability-gap — P3 / L — Server has a fully-featured sharing model
   (~30 endpoints) and the `share` / `listShares` / `revokeShare` SPI
   slots return Unsupported. AC: provider declares the capabilities and
   the three methods round-trip through `/sharings/*`.

## Out of scope

* Live API calls — the agent did not authenticate or hit any
  authenticated endpoint. All findings are static.
* No code or backlog edits — provider, SyncEngine, BACKLOG.md, CLOSED.md
  untouched. Tickets in §"Recommended" are recommendations, not files.
* Fixing the recovery-codes regression — the audit identifies the
  mechanism class; the actual fix sits in the §3/§4 ticket batch.
* Photos, Payments, Workspaces APIs — no public spec discoverable; the
  provider does not call them; out of audit scope.
* Cryptography correctness — the audit is API-shape-level; AES-CTR
  keystream / mnemonic-derived bucket key validation is a separate
  topic (see UD-340 etc.).
