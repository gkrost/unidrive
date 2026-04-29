# S3 / S3-compatible — HTTP robustness audit

> Deliverable for [UD-322](../backlog/BACKLOG.md#ud-322) (part of the UD-228
> cross-provider audit split).
> Vendor docs:
> [S3 error responses](https://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html),
> [Best practices for performance](https://docs.aws.amazon.com/AmazonS3/latest/userguide/optimizing-performance.html),
> [AWS SDK retry strategy](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html),
> [5xx troubleshooting](https://docs.aws.amazon.com/AmazonS3/latest/userguide/troubleshooting.html),
> [Retry behavior reference](https://docs.aws.amazon.com/sdkref/latest/guide/feature-retry-behavior.html).
> Field data: light — exercised against MinIO local during dev, no
> AWS-production-scale cycle equivalent to UD-712. Primary input is the
> [s3-client-notes.md](s3-client-notes.md) gap list.

Companion to [s3-client-notes.md](s3-client-notes.md). The S3 audit shape is
normally "what does the AWS SDK do for us + what do we configure on top?",
but **unidrive's S3 provider doesn't use the AWS SDK** — it is a raw Ktor
`HttpClient` with manual SigV4 signing
([SigV4Signer.kt](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/SigV4Signer.kt))
and regex-based XML parsing
([S3ApiService.kt:299](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L299)).
Every robustness primitive AWS expects an SDK to provide is therefore *our*
obligation, and as audited below almost none of them are wired up. Reads
much closer to [webdav-robustness.md](webdav-robustness.md) than to the
hardened [onedrive-robustness.md](onedrive-robustness.md) baseline.

## Status summary

| Dimension | Finding | Confidence |
|---|---|---|
| HTTP stack | Raw Ktor `HttpClient` + manual SigV4 — **not** AWS SDK Java v2 / aws-sdk-kotlin | High |
| Non-2xx body parsing | XML `<Code>` + `<Message>` extracted via regex; `<RequestId>`, `<HostId>`, `<Resource>` dropped | High |
| Retry placement | **None.** First 503 SlowDown / 5xx / network blip throws `S3Exception` | High |
| Retry-After source | **Not honoured.** Neither `Retry-After` nor `x-amz-retry-after-millis` are read | High |
| Idempotency (SigV4) | `X-Amz-Date` re-computed per `sign()` call — a future retry path would naturally re-sign | Medium |
| Idempotency (multipart) | **No multipart upload.** Single-PUT only; > 5 GB objects fail at the AWS cap | High |
| Idempotency (preconditions) | No `If-Match` / `If-None-Match` / `x-amz-copy-source-if-match` anywhere | High |
| Concurrency | No cap; no `HttpRetryBudget` analogue; no per-prefix vs per-bucket awareness | High |
| Connection pool / DNS | Ktor defaults; no pool tuning, no fresh-DNS-on-retry guidance applied | Medium |
| XML parsing | Regex (`<Code>...</Code>`); fragile against namespaced output (Ceph strict mode) | Medium |
| Streaming download | 8 KiB ring buffer ([S3ApiService.kt:64](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L64)); no mid-stream resume | Medium |

## 1. Non-2xx body parsing

S3 error responses are XML envelopes
([API/ErrorResponses](https://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html))
with `<Code>`, `<Message>`, `<RequestId>`, `<HostId>`, `<Resource>`.
`checkResponse` at
[S3ApiService.kt:282](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L282)
extracts only `<Code>` and `<Message>`:

```kotlin
val code = xmlValue(body, "Code") ?: response.status.value.toString()
val message = xmlValue(body, "Message") ?: ""
```

**Dropped silently:** `<RequestId>` and `<HostId>` (the two fields AWS
support asks for to correlate against CloudWatch / S3 access logs);
`<Resource>` (per-key vs bucket-level disambiguation). The 401/403 split
into `AuthenticationException`
([S3ApiService.kt:291](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L291))
is status-code-based, not error-code-based — but the actionable distinction
between `InvalidAccessKeyId`, `SignatureDoesNotMatch`, and
`TokenRefreshRequired` lives inside `<Code>`.

`xmlValue` ([S3ApiService.kt:323](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L323))
is regex-based and does not strip namespace prefixes. AWS proper sends
unprefixed elements; some Ceph RadosGW deployments send `<s3:Code>` under
strict-mode XML and our parse silently fails, falling through to the bare
HTTP status. No log-noise guard equivalent to OneDrive's `truncateErrorBody`
is needed (S3 bodies are short XML), but the inverse problem applies — we
discard the structured fields the body is designed to carry.

## 2. Retry placement

**No retry loop exists.** `checkResponse`
([S3ApiService.kt:282-297](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L282))
throws on the first non-2xx; every public verb (`getObject`, `putObject`,
`copyObject`, `deleteObject`, `listAll`) calls it once and propagates. There
is no `HttpRetryBudget`
([cross-provider primitive](../../core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt))
integration, no inline `for (attempt in 1..N)`, no jittered backoff. Same
shape as the WebDAV finding
([webdav-robustness §2](webdav-robustness.md#2-retry-placement)).

AWS explicitly addresses this case:

> If you are not using an AWS SDK, you should implement retry logic when
> receiving the HTTP 503 error … truncated binary exponential backoff with
> jitter.
> — [Retry behavior reference](https://docs.aws.amazon.com/sdkref/latest/guide/feature-retry-behavior.html)

The "if you are not using an AWS SDK" clause is the one that applies.
The SDK Java v2 default
([retry-strategy](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html))
is `StandardRetryStrategy`: 3 attempts, full-jitter exponential backoff with
20 ms base, plus a token-bucket throttle. **None of that runs in our path**
because we don't use the SDK. This is the most consequential audit finding:
every robustness behaviour an AWS user would assume is happening is silently
absent.

Recommended retry matrix for a future loop:

| Status / error | Retry? | Cap | Backoff base |
|---|---|---|---|
| 503 SlowDown | yes | 5 | `x-amz-retry-after-millis` if present, else 2 s × 2ⁿ + jitter |
| 500 InternalError | yes | 3 | 1 s × 2ⁿ + jitter |
| 502 / 504 | yes | 3 | 1 s × 2ⁿ + jitter |
| 429 (S3-compat only — AWS proper does not send 429) | yes | 5 | `Retry-After` |
| Network I/O (Connect / Socket / SSL) | yes | 3 | 500 ms × 2ⁿ + jitter |
| 507 Insufficient Storage (MinIO disk full) | no | — | fatal |

## 3. Retry-After

Two header families are relevant:

- **`Retry-After`** (RFC 7231 §7.1.3) — integer seconds or HTTP-date. AWS S3
  does not document sending this for 503; some S3-compatible backends do
  (MinIO admin endpoints; Backblaze B2's S3 façade for 429s).
- **`x-amz-retry-after-millis`** — S3-specific, integer milliseconds, sent
  on some 503 responses
  ([troubleshooting](https://docs.aws.amazon.com/AmazonS3/latest/userguide/troubleshooting.html));
  AWS SDKs honour it inside `StandardRetryStrategy`.

**Neither is read.**
[S3ApiService.kt:282-297](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L282)
does not access `response.headers` on the error path. Even with a retry
loop in place, the server hint would be ignored and we would replay on the
client's exponential schedule, risking the re-storm pattern UD-227
hardened OneDrive against.

## 4. Idempotency

- **GET** — fully idempotent. `getObject`
  ([S3ApiService.kt:47](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L47))
  streams to disk via 8 KiB ring buffer. **No mid-stream resume**: a TCP
  reset at byte 4 GiB re-downloads from byte 0; no `Range:` retry. AWS
  recommends byte-range GETs for multipart-uploaded objects
  ([performance optimization](https://docs.aws.amazon.com/AmazonS3/latest/userguide/optimizing-performance.html)) —
  we don't.
- **PUT (single-object)** — `putObject`
  ([S3ApiService.kt:79](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L79))
  signs with `bodyHash = "UNSIGNED-PAYLOAD"` and streams via a
  `WriteChannelContent` builder
  ([line 101](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L101))
  that re-reads from disk per invocation — a transparent retry would replay
  cleanly, but no retry path exists. The 5 GB single-PUT cap is unenforced
  on our side; > 5 GB sends, gets 400 `EntityTooLarge`, surfaces as
  `S3Exception`. **No multipart upload anywhere** — confirmed by absence
  of any reference to `UploadId`, `<CompleteMultipartUpload>`, `?uploads`,
  or `?partNumber` in the codebase.
- **PUT (server-side copy via `x-amz-copy-source`)** — `copyObject`
  ([S3ApiService.kt:129](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L129))
  is the *non-multipart* CopyObject API and hard-fails for sources > 5 GB
  ([CopyObject docs](https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html)).
  `S3Provider.move()`
  ([S3Provider.kt:128](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt#L128))
  silently fails for any object large enough to have needed multipart-copy.
  The compensating delete-of-destination
  ([S3Provider.kt:138-142](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt#L138))
  cleans up if delete-source fails *after* a successful copy — but the
  > 5 GB case fails before that path is reached.
- **DELETE** — `deleteObject`
  ([S3ApiService.kt:160-166](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L160))
  treats 404 as success; idempotent by design.
- **No `If-Match` / `If-None-Match` / `x-amz-copy-source-if-match` on any
  verb.** Concurrent editors race; same gap WebDAV calls out
  ([webdav-robustness §4](webdav-robustness.md#4-idempotency)) and OneDrive
  notes as outstanding for `moveItem`
  ([onedrive-robustness §4](onedrive-robustness.md#4-idempotency)).

**SigV4 retry safety.** `SigV4Signer.sign(...)` reads `now()` per invocation
([SigV4Signer.kt:46](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/SigV4Signer.kt#L46)).
AWS rejects requests where `X-Amz-Date` is more than 15 minutes from server
clock with `RequestTimeTooSkewed`. A naive retry that *reused* a captured
signature would fail past that window. Calling `sign()` per attempt is the
correct pattern, and a future retry implementation should sign inside the
loop body. Property is theoretical pending the retry path.

## 5. Concurrency recommendations

S3's capacity model is fundamentally different from OneDrive's:

> Amazon S3 doesn't have any limits for the number of connections made to
> your bucket. […] Achieve at least 3,500 PUT/COPY/POST/DELETE or 5,500
> GET/HEAD requests per second per partitioned prefix.
> — [Performance optimization](https://docs.aws.amazon.com/AmazonS3/latest/userguide/optimizing-performance.html)

The cap is **per partitioned prefix**, not per bucket. Concurrent fan-out
across distinct prefixes is encouraged. The throttle signal is **503
SlowDown** during the partition-split ramp, not a steady-state limit.

**Our handling: no cap, no awareness of prefix vs bucket.** `S3Provider`
fans out unbounded; SyncEngine sees no semaphore between calls. For a
local-FD-bound CLI this is mostly harmless on AWS proper, but:

1. PUT-heavy syncs against a single prefix will hit 503 SlowDown during
   the partition split. Without §2's retry loop and §3's `x-amz-retry-after-millis`
   honouring, we fail.
2. Connection pooling is Ktor-default. AWS calls out "avoiding per-request
   connection setup" as a best practice
   ([performance optimization](https://docs.aws.amazon.com/AmazonS3/latest/userguide/optimizing-performance.html)) —
   we don't tune the pool.
3. AWS's documented retry ritual includes "use a new connection … perform
   a fresh DNS lookup" so retries hit a different edge. We do neither
   (OneDrive doesn't either, and is fine — this is more theoretical).

S3-compatible flavors disagree with AWS on these limits — see the matrix
below. The per-prefix model is AWS-specific; MinIO, R2, Backblaze, Wasabi,
DO Spaces have their own caps that are generally **lower** and **per-bucket**.

## S3 — field observations

The provider has not been through a UD-712-equivalent live cycle, so this
section is short and the confidence is correspondingly lower than OneDrive's.

1. **MinIO XML parses correctly via our regex** — unprefixed, matches AWS
   shape. No empirical data against namespaced backends (Ceph RadosGW
   strict-mode); §1 stays at Medium.
2. **MinIO does not send `x-amz-retry-after-millis`** for rate-limit 503s;
   it sends `Retry-After: <seconds>`. We honour neither (§3).
3. **`copyObject` against a > 5 GB MinIO source** returns 400 with
   `<Code>EntityTooLarge</Code>`. The `move()` rollback at
   [S3Provider.kt:138](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt#L138)
   never fires because the copy itself failed before delete-source.
4. **Folder marker objects are zero-byte PUTs** with trailing `/`
   ([S3Provider.kt:104-114](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt#L104)).
   Works on AWS, MinIO, Backblaze, Wasabi. **R2 reportedly drops the
   trailing-`/` object silently** in subsequent listings (rclone has a
   workaround flag); untested in our code.
5. **Streaming download is 8 KiB ring buffer** — same shape as OneDrive's
   UD-329 fix; appears correct for files > `Integer.MAX_VALUE` but untested
   at that size.
6. **Listing pagination is serial** with `max-keys=1000`
   ([S3ApiService.kt:174-203](../../core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt#L174)).
   For a million-object bucket that is 1000 sequential round trips; a
   parallel-shard implementation across `Delimiter`-derived `CommonPrefixes`
   could be 10-100× faster.

## S3-flavor matrix

| Flavor | Endpoint shape | 503 / 429 hint | `x-amz-retry-after-millis` | Per-prefix scaling | XML namespace | Multipart cutoff (rec.) |
|---|---|---|---|---|---|---|
| AWS S3 (proper) | `s3.<region>.amazonaws.com` | 503 SlowDown | yes (some) | 3,500 W / 5,500 R per prefix | unprefixed | 16 MB (Java SDK) |
| MinIO | `host:9000` | 503 + `Retry-After: N` | no | per-bucket throttle (config) | unprefixed | 64 MB |
| Cloudflare R2 | `<acct>.r2.cloudflarestorage.com` | 429 + `Retry-After` | no | flat per-bucket | unprefixed | 100 MB |
| Backblaze B2 (S3 façade) | `s3.<region>.backblazeb2.com` | 429 + `Retry-After` | no | per-bucket | unprefixed | 200 MB |
| Wasabi | `s3.<region>.wasabisys.com` | 503 occasionally | untested | per-bucket, conservative | unprefixed | 100 MB |
| DigitalOcean Spaces | `<region>.digitaloceanspaces.com` | 503 SlowDown analog | no | per-bucket | unprefixed | 64 MB |
| Hetzner Object Storage | `<region>.your-objectstorage.com` | 503 occasionally | no | per-bucket | unprefixed | unspecified |
| Ceph RadosGW (self-host) | varies | varies | no | per-bucket | **`<s3:Code>` namespaced under strict mode** | varies |

The "no" for `x-amz-retry-after-millis` everywhere except AWS proper means a
future retry loop must fall back to standard `Retry-After` for S3-compat;
AWS-only honouring would leave our retry blind on every other flavor in the
matrix. The Ceph-namespace cell is why §1's XML-parsing finding stays at
Medium confidence.

## Follow-ups

- **Retry loop with 503-SlowDown + 5xx coverage.** Largest gap; mirror
  OneDrive's `authenticatedRequest` shape using §2's matrix.
- **Honour `x-amz-retry-after-millis` (AWS) and `Retry-After` (S3-compat)**
  on the new retry loop (§3).
- **Multipart upload for > 16 MB (or > 5 GB hard floor).** Critical-priority
  silent correctness gap — backups of any DB dump or video fail today. Top
  item in [s3-client-notes.md](s3-client-notes.md#gaps--ud-228).
- **Multipart copy** for `copyObject` > 5 GB (UploadPartCopy via UploadId).
- **`If-Match` / `If-None-Match` / `x-amz-copy-source-if-match` on mutating
  verbs.** Cross-provider concern — sibling of the same gap in WebDAV §4 and
  OneDrive §4; close together.
- **Surface `<RequestId>` and `<HostId>` in `S3Exception`.** Operator
  debugging; cheap.
- **Replace regex XML parsing with a proper parser.** Defends against
  namespaced backends and is a precondition for parsing multipart response
  shapes anyway.
- **Mid-stream resume on `getObject`** via HTTP `Range:` — depends on the
  retry loop landing first.
- **`HttpRetryBudget` adoption.** When UD-262 extracts the OneDrive
  constants into the shared budget, the S3 provider should adopt it; a
  per-prefix-aware variant could halve concurrency on sustained 503 the way
  OneDrive halves on 429 storms
  ([onedrive-robustness §5](onedrive-robustness.md#5-concurrency-recommendations)).
