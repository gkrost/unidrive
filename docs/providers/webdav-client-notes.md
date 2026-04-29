# WebDAV — client notes

Research input for UD-230 (feeding UD-228 cross-provider robustness audit).

## Vendor recommendations

WebDAV is specified by [RFC 4918](https://datatracker.ietf.org/doc/html/rfc4918).
Real-world deployments (Nextcloud, Apache `mod_dav`, nginx, Synology DSM,
SharePoint) layer their own limits on top.

- **`Depth` header (RFC 4918 §9.1)**: servers MUST support `Depth: 0` and
  `Depth: 1`; they SHOULD support `Depth: infinity`, but "infinite-depth
  support MAY be disabled, due to the performance and security concerns
  associated with this behavior." Clients that require wide compatibility
  should use `Depth: 1` and recurse client-side.
- **PROPFIND property economy (§9.1)**: servers increasingly expose
  "expensively-calculated or lengthy properties"; `allprop` is explicitly
  discouraged. Request only the properties you need.
- **Status codes (§11)**: 207 Multi-Status, 422 Unprocessable Entity,
  423 Locked, 424 Failed Dependency, 507 Insufficient Storage. RFC 4918
  does **not** define a rate-limit status; servers that throttle do so via
  HTTP 429 (RFC 6585).
- **Nextcloud-specific**: the `nextcloud/server` backend emits 429 Too
  Many Requests for WebDAV under load; recommended client parallelism is
  ≤ 5 for heavy operations (vs 20 for the web UI), per community
  guidance. 429 should be honoured with exponential backoff and
  `Retry-After`.
  ([nextcloud/server issue #33688](https://github.com/nextcloud/server/issues/33688),
  [rclone/rclone issue #6166](https://github.com/rclone/rclone/issues/6166))
- **MOVE/COPY (§9.9-9.10)**: servers MAY implement these atomically but
  are not required to. Client must handle the multi-status (207) body for
  partial-failure cases on deep moves. `Overwrite: T|F` header governs
  clobber behaviour.
- **MKCOL idempotency**: 405 Method Not Allowed = collection already
  exists; 409 Conflict = parent missing. Both are recoverable client-side.
- **Lock tokens (§7)**: if a resource is locked and the client doesn't
  hold the token, writes return 423. Well-behaved sync clients either
  take a lock or retry on 423.
- **RFC 7231 HTTP defaults** apply: follow Retry-After (integer seconds
  or HTTP-date), honour Connection: close, support chunked transfer.
- **Apache `mod_dav` / nginx**: neither publishes a rate limit — the
  reverse proxy / PHP-FPM in front does, and those typically return 503
  under saturation rather than 429.

## What unidrive does today

`core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt`

- **BFS listing with `Depth: 1`** (`propfind`, `:210-230` + `listAll`,
  `:186-207`) — explicitly "to avoid servers that reject `Depth: infinity`
  (Nextcloud, SharePoint)" (`:31-33`). Good.
- **PROPFIND body requests only 4 props**: resourcetype,
  getcontentlength, getlastmodified, getetag (`:214-221`). Avoids
  allprop. Good.
- **Basic auth pre-sent** via `sendWithoutRequest { true }` (`:48`) —
  saves the 401 round-trip cost.
- **TLS**: dual engine path — CIO normally, Java engine when
  `trustAllCerts = true` because "CIO's internal TLS stack rejects some
  servers (e.g. Synology DSM 7.2.2) with ProtocolVersion alerts"
  (`:53-73`). Pragmatic.
- **Redirects disabled** (`:50`). Fine; prevents surprise cross-host
  auth leaks.
- **MKCOL tolerates 405/409** (`:156-158`) — matches RFC 4918.
- **No retry anywhere.** `checkResponse` (`:261-268`) throws on the first
  non-2xx/207. 429, 503, 423, network drops — all fatal.
- **No concurrency cap.** Callers fan out freely.
- **Regex XML parsing** (`parsePropfindResponse`, `:270-307`) — works
  against Nextcloud / Synology / ownCloud / Apache; fragile against
  namespaces other than `D:`.

## Gaps → UD-228

- [ ] **No 429 Too Many Requests handling.** Nextcloud returns 429 under
      load; we surface it as a generic `WebDavException(429)` and fail the
      sync. Must honour `Retry-After` header.
- [ ] **No 503 handling.** `mod_dav` behind PHP-FPM under load returns
      503 routinely.
- [ ] **No 423 Locked handling.** If another client holds a lock we fail
      instead of retrying or taking a lock of our own.
- [ ] **No exponential backoff + jitter.**
- [ ] **No concurrency cap.** Nextcloud community explicitly recommends
      ≤ 5 parallel WebDAV requests; we allow unbounded.
- [ ] **`move` (`:166-178`) doesn't inspect 207 Multi-Status body**
      for partial failures on deep MOVE. A move that fails on one
      sub-resource silently reports success on the top-level 201.
- [ ] **`ensureParentCollections` (`:252-259`) issues one MKCOL per path
      segment serially per upload** — on deep trees this is O(depth × files)
      round-trips. Cache created collections per session.
- [ ] **No If-Match / ETag preconditions on PUT or DELETE.** Concurrent
      editors will clobber each other.
- [ ] **trustAllCerts path is silent** — the log line says "Synology
      workaround" but users enabling it on arbitrary hosts get no MITM
      warning.
- [ ] **No Depth: 0 PROPFIND optimisation for stat()-like calls.** We
      always Depth:1 even when we only want the target resource.

## Priority for UD-228

**High.** WebDAV is the most heterogeneous provider (Nextcloud, ownCloud,
Synology, Apache, nginx, SharePoint, mailbox.org, Strato all differ) and
the current zero-retry posture means any transient 429/503/network blip
becomes a sync failure. 429 handling + a concurrency cap (≤ 5) would
dramatically reduce field failures.
