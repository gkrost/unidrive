# S3 — client notes

Research input for UD-230 (feeding UD-228 cross-provider robustness audit).

## Vendor recommendations

From AWS's official S3 performance/optimization documentation
([Best practices design patterns](https://docs.aws.amazon.com/AmazonS3/latest/userguide/optimizing-performance.html)
and [Performance guidelines](https://docs.aws.amazon.com/AmazonS3/latest/userguide/optimizing-performance-guidelines.html)):

- **Request rate per prefix**: ≥ 3,500 PUT/COPY/POST/DELETE or ≥ 5,500
  GET/HEAD per second per *partitioned* prefix. Scaling to higher rates
  happens gradually — you will see 503 SlowDown during the ramp.
- **503 SlowDown handling** — the authoritative recipe:
  "If you are not using an AWS SDK, you should implement retry logic when
  receiving the HTTP 503 error … using a new connection to Amazon S3 and
  performing a fresh DNS lookup." Back-off should be **truncated binary
  exponential backoff with jitter**.
  ([Retry behavior](https://docs.aws.amazon.com/sdkref/latest/guide/feature-retry-behavior.html))
- **Concrete latency retry pattern** (AWS's own words): for small requests
  (<512 KB) "retry a GET or PUT operation after 2 seconds … issue one retry
  after 2 seconds and a second retry after an additional 4 seconds." For
  large variably-sized (>128 MB), track throughput and retry the slowest
  5 %.
- **Horizontal scaling**: "Amazon S3 doesn't have any limits for the number
  of connections made to your bucket." Use multiple concurrent HTTP
  connections; reuse each for a series of requests (connection pool) to
  avoid TCP slow-start / TLS handshake per request.
- **Byte-range GETs** for objects that were multipart-uploaded — align reads
  to original part boundaries.
- **Multipart-upload threshold**: AWS SDKs switch to multipart around
  16 MB (Java) / 100 MB (CLI default) and use 8 MB parts. Max single-PUT is
  5 GB; max object 5 TB; max parts 10,000.
- **Prefix distribution**: for write-heavy workloads use a randomised or
  hashed first key segment so load spreads across partitions.
- **Monitor 5xx** via CloudWatch or Storage Lens — not a client obligation
  but an operational one.

## What unidrive does today

`core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt`

- **Single Ktor `HttpClient` with `HttpDefaults` timeouts** (`:31-37`) —
  connect 30 s, socket 60 s, request 600 s. No connection-pool
  configuration beyond Ktor's defaults.
- **Single-PUT upload only** (`putObject`, `:76-106`). `UNSIGNED-PAYLOAD`
  content hash. **No multipart upload at all** — a 10 GB object simply
  fails (exceeds S3's 5 GB single-PUT cap).
- **No retry for 503 SlowDown or any 5xx.** `checkResponse` (`:233-245`)
  throws `S3Exception` on first non-2xx. No exponential backoff, no
  jitter, no Retry-After.
- **Listing uses ListObjectsV2 pagination** (`listAll`, `:145-172`) with
  `max-keys=1000`, serial — one continuation token at a time. Correct but
  slow for large buckets.
- **SigV4 signing is inline, computed per request** (`SigV4Signer.sign`) —
  no caching of derived signing keys within a day. Negligible cost but
  worth noting.
- **`copyObject` uses server-side `x-amz-copy-source`** (`:112-128`) —
  correct; no re-download for server-side copy. But cap is 5 GB
  (undocumented on our side). Larger copies need multipart-copy.

## Gaps → UD-228

- [ ] **No multipart upload.** Objects > 5 GB fail outright. Production
      blocker for real backup use-cases.
- [ ] **No 503 SlowDown retry.** AWS explicitly says "you should implement
      retry logic" for non-SDK clients. Today a single 503 during the S3
      scaling ramp tanks the whole sync.
- [ ] **No exponential backoff + jitter anywhere.** Not for 503, not for
      5xx, not for network errors.
- [ ] **No Retry-After / Date parsing.** (S3 uses 503 SlowDown, not 429.)
- [ ] **No multipart copy for `copyObject`** — moves > 5 GB fail.
- [ ] **No byte-range retry on GET.** A mid-download TCP reset at
      byte 4 GB re-downloads from byte 0.
- [ ] **No connection pool tuning.** Ktor default may open/close
      per-request under load; AWS explicitly calls out "avoiding per-request
      connection setup" as a best practice.
- [ ] **No fresh DNS on retry.** AWS's guidance: new connection + new DNS
      resolution per retry to hit a different edge.
- [ ] **No concurrency guidance / cap surfaced.** Callers can fan out
      unbounded — fine for S3 capacity, but may saturate local FDs.
- [ ] **XML parsing via regex** (`parseListObjectsV2`, `:247-269`) — works
      for AWS, fragile for S3-compatible backends (MinIO, Ceph, Backblaze
      B2/S3) that format XML slightly differently.

## Priority for UD-228

**Critical.** The missing multipart-upload is a silent correctness
failure (objects over 5 GB cannot be synced). The missing 503 SlowDown
retry is a silent availability failure under load — AWS's own docs say
"you *will* receive HTTP 503" during scaling ramps, and we do not handle
that case.
