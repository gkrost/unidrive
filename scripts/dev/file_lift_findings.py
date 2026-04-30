#!/usr/bin/env python3
"""One-shot script to file the 18 lift-findings tickets surfaced by the
2026-04-30 provider-duplication survey agent. Each call shells out to
`scripts/dev/backlog.py file --id <auto> --title --category --priority --effort
--body-file <tmp>` so we get the exact same frontmatter shape as a hand-run.

Run: python scripts/dev/file_lift_findings.py
"""
from __future__ import annotations
import os
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BACKLOG = REPO / "scripts" / "dev" / "backlog.py"


def next_id(category: str) -> str:
    out = subprocess.check_output(
        [sys.executable, str(BACKLOG), "next-id", category], cwd=REPO, text=True
    )
    return out.strip()


def file_one(title: str, category: str, priority: str, effort: str, body: str) -> str:
    uid = next_id(category)
    with tempfile.NamedTemporaryFile(
        "w", suffix=".md", delete=False, encoding="utf-8"
    ) as f:
        f.write(body.rstrip() + "\n")
        body_path = f.name
    try:
        subprocess.check_call(
            [
                sys.executable,
                str(BACKLOG),
                "file",
                "--id",
                uid,
                "--title",
                title,
                "--category",
                category,
                "--priority",
                priority,
                "--effort",
                effort,
                "--body-file",
                body_path,
            ],
            cwd=REPO,
        )
    finally:
        os.unlink(body_path)
    return uid


# ───────────────────────────────────────────────────────────────────
# Tickets, in filing order. Each tuple: (handle, title, category, priority, effort, body)
# ───────────────────────────────────────────────────────────────────

TICKETS: list[tuple[str, str, str, str, str, str]] = [
    # ── Chunk 2 — OneDrive + Internxt specific
    (
        "2.1 token-refresh-mutex-pattern",
        "Lift token-refresh mutex+NonCancellable pattern to shared :app:core/auth",
        "providers",
        "high",
        "M",
        """**From the 2026-04-30 provider-duplication survey (agent run after UD-748).**

Three near-identical `getValidToken` / `refreshToken` flows live in the
provider modules:

- `core/providers/onedrive/.../TokenManager.kt:88-149` — full version
  with UD-310 forceRefresh + UD-111 `lastRefreshFailure` recording.
- `core/providers/hidrive/.../TokenManager.kt:51-88` — UD-331
  NonCancellable wrap; missing UD-310 forceRefresh and UD-111 failure
  record.
- `core/providers/internxt/.../AuthService.kt:207-230` — UD-331
  NonCancellable wrap; missing UD-310 forceRefresh; has a
  `fetchRefreshedJwt` overridable seam for tests.

The skeleton is identical: `refreshMutex`, recheck-after-acquire,
`withContext(NonCancellable)` around the refresh + persist. The
divergence has already burnt time — UD-331 had to mirror UD-310 by
hand.

## Proposal

Extract a generic helper to `:app:core/auth/RefreshableCredentialStore.kt`:

```kotlin
class RefreshableCredentialStore<T>(
    private val isStale: (T) -> Boolean,
    private val refresh: suspend (T) -> T,   // network call
    private val persist: suspend (T) -> Unit,
)
```

with `currentValue: T?`, `forceRefresh: Boolean`, and a
`RefreshFailure` record matching UD-111's shape. HiDrive automatically
gets UD-310 forceRefresh + UD-111 failure recording for free.

## Acceptance

- All three providers consume the shared store; no duplicate refresh
  loops remain.
- HiDrive's silent `println("Token refresh failed")` (TokenManager.kt:82)
  becomes a UD-111-style failure record + log.warn.
- Tests pin the contract: stale-then-fresh path, refresh-failure
  recording, NonCancellable-survives-cancellation, force-refresh.

## Effort / agent-ability

**M effort**, agent-able partial — design contract (seam for
persistence, generic over credential type) needs human input first.

## Related

- **UD-310** (closed) — OneDrive forceRefresh.
- **UD-111** (closed/open?) — token-refresh failure telemetry.
- **UD-331** (closed) — NonCancellable wrap mirror across providers.
- **UD-336** (closed, sibling lift) — error-body helpers in same package.
""",
    ),
    (
        "2.2 retry-on-transient-helper",
        "Per-call HTTP retry helper — unify across OneDrive/Internxt/WebDAV (likely subsumed by UD-330)",
        "providers",
        "medium",
        "M",
        """**From the 2026-04-30 provider-duplication survey.**

Three different per-call retry-loop implementations on the same logical
surface (transient HTTP statuses + Retry-After + exponential backoff):

- `core/providers/onedrive/.../GraphApiService.kt:759-854` — inline
  401 + 429/503 retry with header `Retry-After` + JSON body
  `retryAfterSeconds` fallback.
- `core/providers/internxt/.../InternxtApiService.kt:491-526` —
  `retryOnTransient` with `TRANSIENT_STATUSES` + `RETRY_AFTER_REGEX`.
- `core/providers/internxt/.../InternxtApiService.kt:421-452` —
  `authenticatedGet` has its OWN separate retry on `[500, 503]` only —
  hidden bug-pit: the two Internxt code paths have different retry
  profiles.
- `core/providers/webdav/.../WebDavApiService.kt:181-242` — `withRetry`
  with header parsing, status set `[408, 425, 429, 500, 502, 503, 504]`.

UD-330 is open as the cross-provider retry-budget umbrella. This per-
call helper sits ABOVE the budget. **Likely subsumed by UD-330** —
coordinate before lifting.

## Proposal

Either fold into UD-330's planned shape, or extract
`:app:core/http/RetryPolicy.kt` with composable building blocks
(transient-status set, max-attempts, header parser, body parser,
backoff curve, jitter).

Interim mitigation regardless: bring Internxt's `authenticatedGet`
retry into agreement with `retryOnTransient` (same status set, same
backoff) so the two paths stop diverging.

## Effort / agent-ability

**M effort**, agent-able partial — must coordinate with UD-330 first.

## Related

- **UD-330** (open, parent umbrella) — HttpRetryBudget cross-provider.
- **UD-335** (closed) — Internxt retry on transient (introduced
  `retryOnTransient`).
""",
    ),
    (
        "2.3 html-body-sniff-guard",
        "Lift UD-333 HTML body-sniff guard to :app:core/http",
        "providers",
        "high",
        "XS",
        """**From the 2026-04-30 provider-duplication survey.**

UD-333 lineage: identical 8-line "got 200 with text/html — captive
portal / throttle redirect — don't write to disk" guard duplicated
3× across providers. Skipping the guard = silent file corruption,
particularly bad for Internxt (AES-CTR XORs HTML into high-entropy
garbage — UD-333 InternxtApiService.kt:240-250 comment).

- `core/providers/onedrive/.../GraphApiService.kt:289-296` —
  origin (UD-231 ->UD-293).
- `core/providers/hidrive/.../HiDriveApiService.kt:143-150` —
  copied under UD-333.
- `core/providers/internxt/.../InternxtApiService.kt:251-258` —
  copied under UD-333 with stronger threat-model note.

Each block is essentially:

```kotlin
val ct = response.contentType()
if (ct?.match(ContentType.Text.Html) == true) {
    val snippet = readBoundedErrorBody(response, maxBytes = 4096).take(200)
    throw IOException("Download returned HTML instead of file bytes ...: $snippet")
}
```

## Proposal

`:app:core/http/HtmlBodySniffGuard.kt` —
`suspend fun assertNotHtml(response: HttpResponse, contextMsg: String? = null)`
that throws `IOException` with a bounded snippet. Single call site per
provider download path.

## Acceptance

- All three providers call the shared helper; no duplicate guards.
- A future provider's download path that forgets the guard surfaces
  in code review as "didn't import assertNotHtml" — easier to spot
  than "8 lines copied into a new file".
- Test: a captive-portal-style fixture (HTML body + 200 status)
  triggers the IOException with the bounded snippet.

## Effort / agent-ability

**XS effort**, agent-able fully — purely mechanical extraction.

## Related

- **UD-333** (closed) — HiDrive + Internxt guard added.
- **UD-336** (closed) — sibling extraction (truncate/readBoundedErrorBody).
""",
    ),
    (
        "2.4 streaming-download-loop",
        "Lift Ktor streaming-download skeleton (prepareGet ->bodyAsChannel) to :app:core/http",
        "providers",
        "medium",
        "M",
        """**From the 2026-04-30 provider-duplication survey.**

Three providers ship the same Ktor 3.x streaming-download skeleton
(`prepareGet().execute { resp -> resp.bodyAsChannel() ... 8 KiB ring
buffer write }`):

- `core/providers/onedrive/.../GraphApiService.kt:243-312` — full
  version with auth-retry, throttle handling.
- `core/providers/hidrive/.../HiDriveApiService.kt:120-164` — minimal
  variant, no retry, has UD-333 HTML guard.
- `core/providers/internxt/.../InternxtApiService.kt:231-282` —
  variant that threads `Cipher.update` between read and write.

Two providers still use the **unfixed Ktor 3.x trap**
(`response.body<ByteReadChannel>()` allocates the full content-length
into a single byte array, OOMs > 2 GiB):

- `core/providers/s3/.../S3ApiService.kt:55-72`
- `core/providers/webdav/.../WebDavApiService.kt:306-329`

## Proposal

`:app:core/http/StreamingDownload.kt`:

```kotlin
suspend fun streamToFile(
    client: HttpClient,
    url: String,
    dest: Path,
    transform: (ByteArray, Int) -> Pair<ByteArray, Int> = ::passthrough,
    contentTypeGuard: Boolean = true,
    requestBuilder: HttpRequestBuilder.() -> Unit = {},
): Long
```

Internxt's cipher.update flows through `transform`. Returns bytes-
written. UD-333 HtmlBodySniffGuard called when `contentTypeGuard=true`.

## Acceptance

- All five providers consume the shared helper.
- S3 + WebDAV no longer use `response.body<ByteReadChannel>()` — the
  Ktor 3.x trap is closed.
- Internxt's cipher-doFinal edge case is preserved (test fixture).

## Effort / agent-ability

**M effort**, agent-able partial — contract decision needed (does the
helper own retry? does it own parent-dir creation? does it know about
the HTML guard?). Once specified, lift is mechanical.

## Related

- **UD-329** (closed) — OneDrive streaming-download fix.
- **UD-332** (closed) — HiDrive streaming-download fix.
- **UD-333** (closed) — HTML guard.
""",
    ),
    (
        "2.5 streaming-upload-loop",
        "Lift Ktor streaming-upload OutgoingContent.WriteChannelContent block to :app:core/http",
        "providers",
        "high",
        "S",
        """**From the 2026-04-30 provider-duplication survey.**

Four providers each construct the same anonymous
`OutgoingContent.WriteChannelContent` with `contentLength = fileSize`,
`contentType = OctetStream`, `writeTo = Files.newInputStream + 64 KiB
ring buffer + writeFully + flushAndClose`:

- `core/providers/hidrive/.../HiDriveApiService.kt:184-201`
- `core/providers/internxt/.../InternxtApiService.kt:388-407`
- `core/providers/s3/.../S3ApiService.kt:101-118`
- `core/providers/webdav/.../WebDavApiService.kt:368-401` — only one
  with **UD-287's `runCatching { channel.flushAndClose() }` finally
  guard**.

**Silent bug**: HiDrive / Internxt / S3 lack the UD-287 finally-flushAndClose
— a cancellation mid-write corrupts the connection pool. Lifting the
helper closes the gap on three providers in one commit.

## Proposal

`:app:core/http/StreamingUpload.kt`:

```kotlin
fun fileBody(
    localPath: Path,
    fileSize: Long,
    contentType: ContentType = ContentType.Application.OctetStream,
): OutgoingContent
```

Always include UD-287's flushAndClose-in-finally. Document on the
helper that forgetting it is a silent class of bug.

## Acceptance

- All four providers consume the shared helper.
- HiDrive / Internxt / S3 inherit UD-287's finally guard.
- Cancellation-during-upload fixture exercises the connection-pool
  recovery path on every provider.

## Effort / agent-ability

**S effort**, agent-able fully — UD-287 finally-flushAndClose is the
load-bearing detail; the rest is mechanical.

## Related

- **UD-287** (closed) — WebDAV finally-flushAndClose.
- **UD-337** (closed, sibling) — UploadTimeoutPolicy adoption — this
  helper would be the natural neighbour at every upload PUT site.
""",
    ),
    (
        "2.6 json-config-block",
        "Lift `Json { ignoreUnknownKeys; isLenient }` config to :app:core/SharedJson",
        "providers",
        "low",
        "XS",
        """**From the 2026-04-30 provider-duplication survey.**

Every Ktor-based provider service builds its own `Json {}` instance
with the same config. ProviderFactories that read TOML stash a Json
again. Two test files instantiate yet more.

- `core/providers/onedrive/.../GraphApiService.kt:54-58`
- `core/providers/onedrive/.../OAuthService.kt:25`
- `core/providers/onedrive/.../UploadSessionStore.kt:32`
- `core/providers/onedrive/.../OneDriveProviderFactory.kt:22, 122`
- `core/providers/hidrive/.../HiDriveApiService.kt:42-45`
- `core/providers/hidrive/.../OAuthService.kt:19`
- `core/providers/internxt/.../AuthService.kt:26`
- `core/providers/internxt/.../InternxtApiService.kt:52-56`
- `core/providers/internxt/.../InternxtProviderFactory.kt:13`
- `core/providers/rclone/.../RcloneCliService.kt:30`

## Proposal

`:app:core/SharedJson.kt`:

```kotlin
internal val UnidriveJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
```

Replace all 10 sites.

## Acceptance

- `git grep "Json { ignoreUnknownKeys"` returns only the shared
  declaration.
- All ten replacement sites compile + unit tests still pass.

## Effort / agent-ability

**XS effort**, agent-able fully. Bundle with another XS lift in the
same commit.

## Related

- **UD-336** (closed, sibling) — error-body helpers.
""",
    ),
    # ─── 2.7 oauth-pkce-helpers — RESERVED for manual-filing comparison test
    (
        "2.8 token-file-load-save-pattern",
        "Lift token-file load/save helper (with UD-312 atomic write) to :app:core/auth",
        "providers",
        "medium",
        "S",
        """**From the 2026-04-30 provider-duplication survey.**

Three providers persist credentials with the same shape:

- Load: `Files.exists() ->Files.readString ->json.decodeFromString`.
- Save: `Files.createDirectories ->setPosixPermissionsIfSupported(rwx)
  ->writeString ->chmod 600`.

OneDrive added UD-312 (atomic-move + shape guard) on top. HiDrive and
Internxt copied the basic flow **without UD-312** — both have a real
(small) crash-window race the UD-312 OneDrive comment explicitly
identifies.

`setPosixPermissionsIfSupported` itself is duplicated **3 times** with
internal-fun visibility (one per provider), each copy explicitly
commenting `See [org.krost.unidrive.onedrive.setPosixPermissionsIfSupported]`.

- `core/providers/onedrive/.../OAuthService.kt:39-94, 422-443`
- `core/providers/hidrive/.../OAuthService.kt:26-45, 167-189`
- `core/providers/internxt/.../AuthService.kt:238-257, 264-287`

## Proposal

`:app:core/auth/CredentialStore.kt`:

```kotlin
class CredentialStore<T>(
    private val dir: Path,
    private val fileName: String,
    private val serializer: KSerializer<T>,
) {
    fun load(): T?
    fun save(value: T)
    fun delete()
}
```

- UD-312 atomic-move + POSIX chmod baked in.
- `setPosixPermissionsIfSupported` lifts to its own
  `:app:core/io/PosixPermissions.kt` (sub-finding 3.3 — covered here).

## Acceptance

- All three providers consume `CredentialStore`.
- HiDrive + Internxt inherit UD-312 atomic-write — partial-write
  crashes no longer leave corrupted token files.
- ~60 lines of cross-package "see other module" comment-references
  deleted.

## Effort / agent-ability

**S effort**, agent-able fully. Subsumes finding 3.3
(posix-permissions-helper).

## Related

- **UD-312** (closed) — OneDrive atomic-write + shape guard.
- **UD-336** (closed, sibling) — same package destination.
""",
    ),
    # ── Chunk 3 — Other providers
    (
        "3.1 snapshot-base64-json-cursor",
        "Lift Snapshot data-class wrapper (entries+timestamp+Base64 JSON cursor) to :app:sync",
        "providers",
        "medium",
        "S",
        """**From the 2026-04-30 provider-duplication survey.** Highest-volume
duplication in the codebase by line count.

Six providers each have a `XxxSnapshotEntry` + `XxxSnapshot` data-class
pair with `entries: Map<String, ...>`, `timestamp: Long`, and
`encode(): String` / `decode(cursor: String): XxxSnapshot` doing
Base64-of-JSON. The entry fields differ (etag/size/mtime/chash/hash)
but the wrapping structure and encode/decode are identical line-by-line.

- `core/providers/hidrive/.../DeltaSnapshot.kt:1-33`
- `core/providers/s3/.../S3Snapshot.kt:1-37`
- `core/providers/sftp/.../SftpSnapshot.kt:1-36`
- `core/providers/webdav/.../WebDavSnapshot.kt:1-37`
- `core/providers/rclone/.../RcloneSnapshot.kt:1-43` — adds `hasChanged`
  helper (sibling finding 3.2).
- `core/providers/localfs/.../LocalFsSnapshot.kt:1-36`

## Proposal

`:app:sync/DeltaSnapshot.kt`:

```kotlin
@Serializable
class Snapshot<E>(val entries: Map<String, E>, val timestamp: Long) {
    fun encode(serializer: KSerializer<E>): String
    companion object {
        fun <E> decode(cursor: String, serializer: KSerializer<E>): Snapshot<E>
    }
}
```

Per-provider `XxxSnapshotEntry` types stay where they are.

## Acceptance

- All six providers consume the shared `Snapshot<E>`.
- Round-trip test: encode a fixture, decode, assert entries equal.
- Existing snapshot-version compatibility preserved — no on-disk
  cursor format change.

## Effort / agent-ability

**S effort**, agent-able fully. Pairs naturally with finding 3.2
(snapshot-delta-compute).

## Related

- **3.2 snapshot-delta-compute** (sibling) — the diff loop that
  consumes these snapshots.
""",
    ),
    (
        "3.2 snapshot-delta-compute",
        "Lift snapshot-delta diff loop to :app:sync/SnapshotDeltaEngine",
        "providers",
        "medium",
        "M",
        """**From the 2026-04-30 provider-duplication survey.**

Six providers each have the same 30-line block in `delta(cursor: String?)`:
build current snapshot, return-all-on-null-cursor, diff loop ("for path
in current: if missing or changed ->add", "for path in previous: if not
in current ->add deleted CloudItem"). The `hasChanged` predicate varies
per provider (etag vs hash vs size+mtime).

- `core/providers/webdav/.../WebDavProvider.kt:136-191`
- `core/providers/sftp/.../SftpProvider.kt:115-164`
- `core/providers/s3/.../S3Provider.kt:174-224`
- `core/providers/rclone/.../RcloneProvider.kt:74-78,98-151` — already
  separated into companion-fun `computeDelta`, useful starting reference.
- `core/providers/hidrive/.../HiDriveProvider.kt:116-170`
- `core/providers/localfs/.../LocalFsProvider.kt:152-201`

## Proposal

`:app:sync/SnapshotDeltaEngine.kt`:

```kotlin
fun <E> computeDelta(
    currentEntries: Map<String, E>,
    currentItems: List<CloudItem>,
    prevCursor: String?,
    hasChanged: (E, E) -> Boolean,
    deletedItem: (path: String, entry: E) -> CloudItem,
): DeltaPage
```

## Acceptance

- All six providers' `delta()` shrinks from ~50 lines each to ~10.
- Delta-content semantics preserved per provider (changed-but-equal
  fixture, deleted, added, all-three mixed).

## Effort / agent-ability

**M effort**, agent-able partial — per-provider "find by path" lookup
varies subtly (WebDAV `it.path == path`, S3 `api.keyToPath(it.key)`,
Rclone `"/${it.path}" == path`). Indexing pre-pass needs design.

## Related

- **3.1 snapshot-base64-json-cursor** (sibling) — the storage layer
  this consumes.
""",
    ),
    (
        "3.3 posix-permissions-helper",
        "Lift `setPosixPermissionsIfSupported` to :app:core/io",
        "providers",
        "low",
        "XS",
        """**From the 2026-04-30 provider-duplication survey.** Sub-finding
of 2.8 — file standalone in case 2.8 is deferred.

Same internal helper triplicated — three identical implementations of
chmod 600/700 with POSIX-or-noop. Two of the copies' KDoc explicitly
say `See [org.krost.unidrive.onedrive.setPosixPermissionsIfSupported]`.

- `core/providers/onedrive/.../OAuthService.kt:422-443`
- `core/providers/hidrive/.../OAuthService.kt:167-189`
- `core/providers/internxt/.../AuthService.kt:264-287`

## Proposal

`:app:core/io/PosixPermissions.kt`:

```kotlin
fun setPosixPermissionsIfSupported(path: Path, ownerRwx: Boolean = true)
```

Drop the three copies and ~60 lines of cross-package comment refs.

## Acceptance

- `git grep setPosixPermissionsIfSupported` returns only the shared
  declaration + three import sites.
- POSIX systems still get owner-rwx; Windows still no-ops.

## Effort / agent-ability

**XS effort**, agent-able fully.

## Related

- **2.8 token-file-load-save-pattern** (parent) — subsumes this if
  filed first.
""",
    ),
    (
        "3.4 oauth-loopback-callback-server",
        "Lift OAuth loopback callback server (waitForCallback + parse + openBrowser) to :app:core/auth",
        "providers",
        "medium",
        "S",
        """**From the 2026-04-30 provider-duplication survey.**

OneDrive and HiDrive `TokenManager` each implement a 35-line
`waitForCallback(expectedState)` opening
`ServerSocket(port, 0, 127.0.0.1)`, accepting one connection, calling
a 25-line `parseAndValidateCallback`, writing a hardcoded HTML
success/error page, returning the auth code.

`parseAndValidateCallback` is itself duplicated, including the same
load-bearing comment about provider-error-vs-state-vs-code regex
ordering.

- `core/providers/onedrive/.../TokenManager.kt:157-191, 204-227, 234-250`
- `core/providers/hidrive/.../TokenManager.kt:97-131, 144-167, 174-189`

## Proposal

`:app:core/auth/OAuthCallbackServer.kt`:

```kotlin
suspend fun awaitOAuthCallback(
    port: Int,
    expectedState: String,
    providerLabel: String,    // parameterises the success-page HTML
    timeout: Duration,
): String  // auth code
```

Plus `:app:core/io/OpenBrowser.kt` for the 17-line `openBrowser` util.
`parseAndValidateCallback` lifts internal to the callback module.

## Acceptance

- OneDrive + HiDrive `TokenManager` consume the shared helper.
- HTML success page differs only by `providerLabel` substitution.
- Future S3/WebDAV/Internxt OAuth flavour gets the helper for free.

## Effort / agent-ability

**S effort**, agent-able fully — `parseAndValidateCallback` is already
pure with great test coverage; `openBrowser` is 17 lines pure utility.

## Related

- **2.7 oauth-pkce-helpers** (sibling) — same package destination.
""",
    ),
    (
        "3.5 s3-webdav-streaming-download-trap",
        "Fix Ktor 3.x streaming-download trap (`response.body<ByteReadChannel>()`) in S3 + WebDAV",
        "providers",
        "high",
        "XS",
        """**From the 2026-04-30 provider-duplication survey. Lurking bug,
not just duplication-removal.**

UD-329 (OneDrive) + UD-332 (HiDrive) fixed the Ktor 3.x trap where
`response.body<ByteReadChannel>()` buffers the **full** response into
`byte[contentLength]` before exposing the channel — a > 2 GiB file
OOMs at allocation. S3 and WebDAV both still use the trap pattern.

- `core/providers/s3/.../S3ApiService.kt:55-72` — `httpClient.get(url)`
  then `response.body<ByteReadChannel>()`.
- `core/providers/webdav/.../WebDavApiService.kt:306-329` —
  `httpClient.get(url) { timeout {...} }` then `response.body()` typed
  as `ByteReadChannel`.

## Proposal

Apply the UD-329 / UD-332 pattern verbatim:

```kotlin
val statement = httpClient.prepareGet(url) { ... }
statement.execute { response ->
    val channel = response.bodyAsChannel()
    // 8 KiB ring buffer write
}
```

Remember UD-333 `text/html` content-type guard while touching the
same lines.

## Acceptance

- S3 download of a > 2 GiB object completes without OOM.
- WebDAV download of a > 2 GiB resource same.
- 8 KiB ring buffer + UD-333 guard + UD-285 `Long.MAX_VALUE` timeout
  override (WebDAV existing) intact.

## Effort / agent-ability

**XS each, S total**, agent-able fully.

## Related

- **UD-329** (closed) — OneDrive fix.
- **UD-332** (closed) — HiDrive fix.
- **UD-333** (closed) — HTML guard.
- **2.4 streaming-download-loop** — would close this naturally if
  filed first.
""",
    ),
    (
        "3.6 internxt-client-headers",
        "Internxt: extract `applyInternxtHeaders` for the four call sites that paste it",
        "providers",
        "low",
        "XS",
        """**From the 2026-04-30 provider-duplication survey. Internxt-internal
cleanup — not cross-provider.**

Internxt requires four custom headers on every authenticated call
(`internxt-client / internxt-version / x-internxt-desktop-header /
accept(Json)`). The `applyAuth` helper handles them for the main API
path; auth-flow + bridge calls paste them by hand.

- `core/providers/internxt/.../InternxtApiService.kt:454-460`
  (`applyAuth`)
- `core/providers/internxt/.../InternxtApiService.kt:296-300, 313-317`
  (`bridgeGet`, `bridgePost` — different `Authorization` scheme but
  same internxt-client/version triplet)
- `core/providers/internxt/.../AuthService.kt:103-104, 138-140`
  (login-challenge + login-access)

## Proposal

Stay in the Internxt module. Extract a private extension:

```kotlin
private fun HttpRequestBuilder.applyInternxtHeaders()
```

Replace the four paste sites.

## Acceptance

- Four call sites use the extension; no header triplet inline.
- A future header change (e.g. version bump) edits one place.

## Effort / agent-ability

**XS effort**, agent-able fully.

## Related

- **2.1 token-refresh-mutex-pattern** (sibling) — same module.
""",
    ),
    # ── Chunk 1 — Log/CLI output related
    (
        "1.1 oauth-stdout-prompts",
        "Route OAuth UX prints through an AuthInteractor seam (CLI vs daemon)",
        "tooling",
        "high",
        "M",
        """**From the 2026-04-30 provider-duplication survey.**

OneDrive and HiDrive `TokenManager.authenticateWithBrowser()` and
Internxt `AuthService` print user-facing messages with raw `println()`.
In a daemon / IPC context these go to the wrong place — stdout, not
log, not IPC event. Worse: HiDrive does
`println("Token refresh failed: ${e.message}")` — silent failure
dropped to stdout, no log line, no MDC, no UD-111 record.

- `core/providers/onedrive/.../TokenManager.kt:54-56,71-78` —
  `println("Opening browser for authentication...")`,
  `println("URL: $authUrl")`, `println("Authentication successful!")`.
- `core/providers/hidrive/.../TokenManager.kt:37-39,82` — same
  prompts; line 82 is the silent-loss bug.
- `core/providers/internxt/.../AuthService.kt:86` —
  `System.err.println("Warning: no console available — password will
  be echoed")`.

## Proposal

Define `AuthInteractor` in `:app:cli` (or `:app:core` if MCP server
also needs it):

```kotlin
interface AuthInteractor {
    fun browserAuthStarting(url: String)
    fun browserAuthSucceeded()
    fun tokenRefreshFailed(provider: String, e: Throwable)
    fun consoleEcho(warning: String)
}
```

`AuthCommand` implements with println. Daemon implements with
log.warn + IPC event. Pass to `TokenManager` via constructor.

HiDrive's silent `println("Token refresh failed")` line 82 becomes
`interactor.tokenRefreshFailed(...)` + a UD-111 `lastRefreshFailure`
record (the OneDrive treatment HiDrive currently lacks — see also
finding 2.1).

## Acceptance

- All three providers route prints through `AuthInteractor`.
- Daemon path emits log.warn + IPC events instead of stdout.
- HiDrive's silent token-refresh failure becomes visible in
  `unidrive.log`.

## Effort / agent-ability

**M effort**, agent-able partial — design call needed for daemon-
side contract (does `browserAuthStarting` open the URL itself,
or does the daemon refuse OAuth flow entirely? what's the IPC
event shape?).

## Related

- **UD-111** (open) — token-refresh failure telemetry.
- **2.1 token-refresh-mutex-pattern** (sibling) — addresses the
  silent-failure pit at the same time.
""",
    ),
    (
        "1.2 delta-log-line",
        "Move per-provider `Delta: N items` debug log to the engine call site",
        "tooling",
        "low",
        "XS",
        """**From the 2026-04-30 provider-duplication survey.**

Every provider that snapshots fires `log.debug("Delta: {} items", ...)`
after computing the delta. UD-313 OneDrive comment already calls this
out as wasteful — the engine call site already knows the same data.

- `core/providers/webdav/.../WebDavProvider.kt:138`
- `core/providers/s3/.../S3Provider.kt:176`
- `core/providers/rclone/.../RcloneProvider.kt:76`
- `core/providers/hidrive/.../HiDriveProvider.kt:119` — variant
  with `(snapshot comparison)` suffix.
- `core/providers/onedrive/.../GraphApiService.kt:172` — variant
  with `hasMore=` suffix.

## Proposal

Move to the `:app:sync` site that calls `provider.delta(cursor)`. Read
`DeltaPage.hasMore` from the page itself (covers OneDrive's variant).
Drop the per-provider lines.

## Acceptance

- `git grep "Delta:.*items"` in providers/ returns nothing.
- `:app:sync` log emits `Delta: N items, hasMore=...` once per delta
  call.

## Effort / agent-ability

**XS effort**, agent-able fully.
""",
    ),
    (
        "1.3 provider-auth-banner",
        "Move `Auth: {provider} authenticated` debug log to the engine call site",
        "tooling",
        "low",
        "XS",
        """**From the 2026-04-30 provider-duplication survey.**

OneDrive and HiDrive providers each emit identical
`Auth: {provider} authenticated / not authenticated after initialize`
debug lines after `authenticate()`. Other providers (Internxt, S3,
SFTP, WebDAV, Rclone) silently skip — three of them probably should
be logging too.

- `core/providers/onedrive/.../OneDriveProvider.kt:55-59`
- `core/providers/hidrive/.../HiDriveProvider.kt:38-42`

## Proposal

Wrap the `CloudProvider.authenticate()` call in the engine / factory,
log with `provider.id`. Drop the per-provider lines.

## Acceptance

- All providers benefit from a consistent post-auth log line via the
  shared wrapper.
- No per-provider `Auth: ... authenticated` line remains.

## Effort / agent-ability

**XS effort**, agent-able fully.
""",
    ),
    (
        "1.4 download-upload-debug-mirror",
        "Move per-operation debug log lines (Download/Upload/Delete/Move) to engine via decorator",
        "tooling",
        "low",
        "S",
        """**From the 2026-04-30 provider-duplication survey.**

Same per-operation log shape repeated across providers. Five providers
each emit `Download: X` / `Upload: X (N bytes)` / `Delete: X` /
`Move: X -> Y` at debug. The wrapping engine call already knows the
path + bytes; logging there once would suffice.

- `core/providers/onedrive/.../GraphApiService.kt:207, 432, 451, 487`
- `core/providers/hidrive/.../HiDriveApiService.kt:111, 176, 216, 252, 277, 301`
- `core/providers/s3/.../S3ApiService.kt:52, 85, 133, 161`
- `core/providers/webdav/.../WebDavApiService.kt:294, 349, 418, 439, 460`

## Proposal

Either:

(a) Wrap providers in a `LoggingCloudProvider` decorator that emits
the debug lines around delegate calls. Drop per-provider lines.

(b) Just log at the engine `applyAction` call site. Drop per-provider
lines.

## Acceptance

- `git grep "log.debug.\\"(Download|Upload|Delete|Move):"` in
  providers/ shrinks to provider-specific detail only (e.g.
  HiDrive's home-relative dir resolution is genuinely useful and
  stays).

## Effort / agent-ability

**S effort**, agent-able partial — judgment call: which provider-
specific log details are worth keeping vs deleting?

## Related

- **1.2 delta-log-line** (sibling) — same shape lift.
- **1.3 provider-auth-banner** (sibling) — same shape lift.
""",
    ),
]


def main() -> None:
    # 2.1 was already filed as UD-338 (the script crashed mid-loop on a
    # Windows cp1252 encode of an arrow char). Skip the first entry.
    remaining = TICKETS[1:]
    print(f"filing {len(remaining)} tickets via backlog.py ...")
    for handle, title, category, priority, effort, body in remaining:
        try:
            uid = file_one(title, category, priority, effort, body)
            print(f"  {handle:<35} ->{uid}")
        except subprocess.CalledProcessError as e:
            print(f"  {handle:<35} FAILED: {e}", file=sys.stderr)
            sys.exit(1)
    print("done.")


if __name__ == "__main__":
    main()
