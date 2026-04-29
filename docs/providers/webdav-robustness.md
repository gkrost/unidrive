# WebDAV — HTTP robustness audit

> Deliverable for [UD-324](../backlog/CLOSED.md#ud-324) (part of the UD-228
> cross-provider audit split).
> Field data: Synology DS418play / DSM 7.x, `ds418play.local:5006`,
> 2026-04-21. Protocol reference: [RFC 4918](https://datatracker.ietf.org/doc/html/rfc4918)
> (WebDAV), [RFC 4331](https://datatracker.ietf.org/doc/html/rfc4331)
> (quota), [RFC 6585](https://datatracker.ietf.org/doc/html/rfc6585) (429).

Companion to [webdav-client-notes.md](webdav-client-notes.md): where the
client-notes file is *what the protocol and our code do*, this one is
*where the code is brittle and where production servers surprise us*.

## Status summary

| Dimension | Finding | Confidence |
|---|---|---|
| Non-2xx body parsing | Only status code is surfaced; error body is dropped on the floor | High |
| Retry placement | Zero retries anywhere — first 429/503/network blip fails the sync | High |
| Retry-After source | Not honoured; header not read | High |
| Idempotency | PUT/DELETE/MOVE issue no `If-Match`; concurrent editors clobber | High |
| Concurrency | No cap; upstream fan-out unbounded | High |
| TLS robustness (LAN) | Apache5 engine + `NoopHostnameVerifier` bypass works; CIO/Java both fail on Synology | High |
| Quota (RFC 4331) | PROPFIND wired ([UD-325](../backlog/CLOSED.md#ud-325)); Synology returns `null` — graceful fallback | High |
| XML parsing | Regex-based; fragile against non-`D:` namespace prefixes | Medium |

## 1. Non-2xx body parsing

`WebDavApiService.checkResponse` at `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt:351` throws with status + URL only:

```kotlin
!response.status.isSuccess() && response.status.value !in listOf(207) ->
    throw WebDavException("WebDAV request failed (${response.status.value}): $url", ...)
```

RFC 4918 §11.3 permits a 207 Multi-Status body with per-resource error
elements even under an otherwise-successful top-level status. `move()`
at `WebDavApiService.kt:214` does not inspect the body — a deep MOVE
that fails on one sub-resource silently reports success. Same gap for
`PROPFIND` with partial-failure responses.

**Recommended change:** when status ∈ {207, 4xx, 5xx}, read `bodyAsText()`,
extract `<D:status>HTTP/1.1 NNN …</D:status>` per `<D:response>`, and
surface the worst per-resource status.

## 2. Retry placement

There is no retry policy. `checkResponse` raises on the first non-2xx/207.
A single 429 from Nextcloud under load or a 503 from `mod_dav` behind
PHP-FPM fails the enclosing sync cycle. Network-layer errors
(`ConnectException`, `SocketTimeoutException`) propagate the same way.

**Recommended placement:** retry inside `WebDavApiService` at the method
level, not at the caller. Only idempotent verbs (PROPFIND, GET, PUT with
`If-Match`, DELETE, MKCOL, MOVE with the target-not-exists precondition)
are safe to retry transparently.

Retry matrix:

| Status | Retry? | Cap | Backoff base |
|---|---|---|---|
| 408 Request Timeout | yes | 3 | 1 s × 2ⁿ + jitter |
| 423 Locked | yes, bounded | 2 | 5 s fixed (lock holders usually release quickly) |
| 429 Too Many Requests | yes | 5 | `Retry-After` if present, else 2 s × 2ⁿ + jitter |
| 502/503/504 | yes | 5 | `Retry-After` if present, else 1 s × 2ⁿ + jitter |
| 507 Insufficient Storage | no | — | fatal, surface to user |
| Network I/O | yes | 3 | 500 ms × 2ⁿ + jitter |

Jitter: ±20% of the base delay to avoid synchronised retries from
parallel workers.

## 3. Retry-After

RFC 7231 §7.1.3: `Retry-After` is an integer (delta-seconds) OR an
HTTP-date. Our parser today: nonexistent. Must support both forms:

```
Retry-After: 120
Retry-After: Fri, 21 Apr 2026 14:30:00 GMT
```

When both retries are planned and the server gives `Retry-After`, the
header value wins — overriding the client's exponential schedule.

## 4. Idempotency

PUT, DELETE, MOVE, and COPY are potentially-destructive. Without
preconditions, concurrent editors race:

- **PUT**: no `If-Match: <etag>` → the last writer wins silently, even
  if the local copy is stale relative to the server.
- **DELETE**: same shape; a resource edited between our GET and our
  DELETE is silently lost.
- **MOVE**: `Overwrite: T` is our default (inherited from Ktor defaults);
  the overwrite decision should be taken at the planner level, not the
  HTTP layer.

**Recommended:**
- Pass the most recent ETag to every mutating call. `WebDavEntry.etag`
  already exists at `WebDavApiService.kt:382`; thread it through.
- Default `Overwrite: F`; overwrite requires an explicit caller opt-in
  and a fresh ETag precondition.
- Synology DSM 7.x does not return an `ETag` header on PROPFIND
  (see [§ Synology field observations](#synology-dsm-7x--field-observations))
  — treat `etag == null` as "precondition not available" and fall back
  to size+mtime equality.

## 5. Concurrency recommendations

| Server family | Recommended parallelism | Source |
|---|---|---|
| Nextcloud | ≤ 5 | [nextcloud/server#33688](https://github.com/nextcloud/server/issues/33688), [rclone/rclone#6166](https://github.com/rclone/rclone/issues/6166) |
| ownCloud | ≤ 5 | inherited Nextcloud lineage |
| Synology DSM | ≤ 4 | unpublished; observed 500 above ≈4 parallel uploads on DS418play |
| Apache `mod_dav` | ≤ 8 | MPM-worker limit; 503 above that |
| SharePoint | ≤ 2 | heavy throttling, no public guidance |
| mailbox.org / Strato | ≤ 3 | conservative default |

Enforcement: a `Semaphore(N)` wrapping the HTTP call site inside
`WebDavApiService`. Default N=4 if the server family is unknown.

## Synology DSM 7.x — field observations

From the 2026-04-21 session on DS418play running DSM 7.2.2:

1. **CIO engine**: fails the TLS handshake with
   `javax.net.ssl.SSLHandshakeException: Received fatal alert: protocol_version`.
   Java 11 `java.net.http.HttpClient`: connects but hardcodes HTTPS
   hostname verification — fails on self-signed certs no matter what
   Ktor's `trust_all_certs` says. **Apache5 engine** with
   `PoolingAsyncClientConnectionManager` + `NoopHostnameVerifier` is the
   only combination that works end-to-end — landed in
   [UD-326](../backlog/CLOSED.md#ud-326).
2. **No `ETag` header on PROPFIND.** `getetag` is simply absent from the
   response body. `WebDavEntry.etag` is `null` for every resource; our
   `If-Match` strategy (if we had one) would collapse to size+mtime.
3. **No RFC 4331 quota.** PROPFIND Depth:0 with
   `quota-available-bytes` / `quota-used-bytes` returns the usual 207
   but neither property is in the `<D:prop>` block — UD-325 returns
   `null` and the CLI shows "unknown". No `DAV:` header either.
4. **Root PROPFIND 405.** Issuing PROPFIND against the configured base
   URL's root (`/`) returns 405 Method Not Allowed. Our `listAll("")`
   at `WebDavApiService.kt:238` starts from the configured base which
   already includes `/webdav/<share>` — so we don't hit this, but any
   caller that tries to traverse upward from the base will.
5. **Content-Length**: always present on files; on collections it's
   absent (correct per RFC).
6. **HTTP/1.1 only.** DSM 7.x's built-in WebDAV server does not
   negotiate HTTP/2. Connection keep-alive works; pooling does not
   materially help for the PROPFIND-bound list workload.

## Server-family matrix

| Family | ETag | Quota (RFC 4331) | 429 under load | Locks | HTTP/2 |
|---|---|---|---|---|---|
| Nextcloud | yes | yes | yes | yes (optional) | yes (reverse-proxy) |
| ownCloud | yes | yes | yes | yes | yes |
| Apache `mod_dav` | yes | depends on module | no (503 via MPM) | optional | yes |
| Synology DSM 7.x | **no** | **no** | no | no | **no** |
| SharePoint | yes (weak) | yes | yes (heavy) | yes | yes |
| mailbox.org | yes | yes | yes | yes | yes |
| Strato HiDrive (WebDAV façade) | yes | yes | yes | no | yes |

"No" in ETag / Quota / HTTP/2 columns is the important signal —
those servers need the size+mtime fallback path.

## Follow-ups

- Retry + Retry-After wiring: see the matrix in [§2](#2-retry-placement).
- Concurrency cap: see [§5](#5-concurrency-recommendations).
- Multi-status body parsing: [§1](#1-non-2xx-body-parsing).
- ETag-aware mutations: [§4](#4-idempotency).

Filed separately as follow-on tickets during the UD-228 split — see the
backlog for the current owners.
