# Auth refresh model — per-provider audit

This audit was triggered by drive-desktop research (agent `a876235`,
2026-05-04). drive-desktop's `src/apps/main/token-scheduler/TokenScheduler.ts`
schedules JWT refresh with a wall-clock `setTimeout(refresh, exp - now - 1d)`.
That pattern is fragile under suspend/resume — if the host was suspended for
longer than the lead-time the timer fires after the token has already expired,
or never fires at all (process restarted, scheduler reset). The question for
unidrive (UD-208): do any of our providers exhibit the same anti-pattern, or
do they all check expiry per-call?

Verdict, up front: **no provider in the unidrive tree uses a wall-clock
scheduler.** Every provider with token semantics checks expiry at each API
call site (or refreshes on a 401 response). This is strictly more robust than
drive-desktop's pattern. Details per provider below.

## Internxt

- **Model:** Per-call (robust).
- **Code refs:**
  - `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:41-62`
    `isJwtExpired()` — pure JWT-payload decode + `exp` comparison; no network.
  - `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:201-224`
    `refreshToken()` — mutex-serialised, double-checked, wraps the network
    call + persistence in `NonCancellable` (UD-331 / UD-310 carry-over).
  - `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:53-58`
    `authenticate()` — checks `isJwtExpired()` and falls back to interactive
    auth if true.
  - `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:622-653`
    `authenticatedGet()` — pulls credentials via the `credentialsProvider`
    closure on every request (`InternxtProvider.kt:24` wires it to
    `authService.getValidCredentials()`).
- **Verdict:** Robust under suspend/resume. There is no background scheduler,
  `setInterval`, `delay()`-as-scheduler, or coroutine launched at startup that
  decides when to refresh. Refresh is initiated either at `authenticate()`
  time (one-shot expiry check) or implicitly via the call-site reading
  credentials.
- **Notes:** One sharp edge worth flagging: `getValidCredentials()`
  (`AuthService.kt:175`) does **not** itself call `refreshToken()` — it
  returns the stored `InternxtCredentials` or throws if absent. The current
  flow relies on the server returning 401 on an expired JWT and the caller
  re-running `authenticate()`. There is no proactive refresh-before-expiry
  beyond the boot-time `isJwtExpired()` check. This is still robust under
  suspend/resume (a stale-after-suspend JWT just hits 401 on the next call
  and triggers re-auth), but it does mean the user sees an interactive
  re-auth prompt instead of a silent rotation. Not a fragility bug; an UX
  edge that may merit its own ticket.

## OneDrive

- **Model:** Per-call (robust).
- **Code refs:**
  - `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/model/Token.kt:24-27`
    `Token.isExpired` and `Token.isNearExpiry(thresholdMs = 5min)` — pure
    timestamp comparison against `System.currentTimeMillis()`.
  - `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/TokenManager.kt:80-129`
    `getValidToken(forceRefresh)` — mutex-serialised double-check, refreshes
    via `OAuthService.refreshToken()` if near expiry; on failure records
    `lastRefreshFailure` and throws.
  - `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProvider.kt:24-27`
    `GraphApiService` is constructed with a `tokenProvider` closure that
    invokes `tokenManager.getValidToken(forceRefresh)` on every call.
  - `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:34`
    declares the `tokenProvider` field; usages at lines 188, 318, 337, 369,
    413, 520, 658, 708, 862, 930 — **every** Graph call site reads through
    the closure.
- **Verdict:** Robust under suspend/resume. The 5-minute pre-expiry buffer
  in `isNearExpiry` is checked at request time, so a process that was
  suspended past the buffer simply triggers refresh on the first call after
  resume. No wall-clock scheduler exists.
- **Notes:**
  - Microsoft access tokens are 1h TTL; refresh tokens are long-lived
    (90-day sliding window).
  - `forceRefresh` is plumbed through the closure
    (`GraphApiService.kt:188, 658, 708`) so that a 401 mid-call can demand
    a fresh token without waiting for the 5-min near-expiry window — this
    is the per-call recovery path.
  - `TokenManager.lastRefreshFailure` (`TokenManager.kt:23-30`) exposes the
    last refresh-failure record for telemetry — UD-111 territory, not
    relevant to the suspend/resume question.

## S3 / S3-compatible

- **Model:** N/A (no token refresh).
- **Code refs:**
  - `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt:46-57`
    `authenticate()` — validates by listing the bucket; sets
    `isAuthenticated = true`.
  - `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt:55, 93, 133, 153, 188`
    every request signed via `SigV4Signer.sign(...)` using
    `config.accessKey` + `config.secretKey` — long-lived credentials, no
    rotation, no token.
- **Verdict:** Not applicable. AWS SigV4 with static access-key+secret-key
  has no expiry. (STS / temporary credentials would change this, but the
  current implementation only supports static credentials per
  `S3Config.kt`.)
- **Notes:** Suspend/resume is irrelevant — every request is freshly signed
  with timestamps drawn from `System.currentTimeMillis()` at sign time, so
  there's no cached signature to go stale.

## SFTP

- **Model:** N/A (key-based or password-based SSH auth, no tokens).
- **Code refs:**
  - `core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProvider.kt:33-44`
    `authenticate()` opens a connection via `api.connect()`; `logout()`
    closes it. No token state.
- **Verdict:** Not applicable. SSH session lifetime is governed by the
  underlying transport; reconnection on transport failure is the SFTP
  library's concern, not a token-refresh concern.

## WebDAV

- **Model:** N/A (HTTP Basic auth, no tokens).
- **Code refs:**
  - `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt:61-66`
    Ktor `Auth { basic { credentials { ... } } }` block — username/password
    sent on every request. No token, no refresh, no expiry.
  - `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt:30`
    explicitly labels `authType = "HTTP Basic (username + password)"`.
- **Verdict:** Not applicable. Credentials are static and re-sent
  per-request by the Ktor Auth plugin.

## Rclone

- **Model:** N/A (delegated to the rclone binary).
- **Code refs:**
  - `core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProvider.kt:28-31`
    `authenticate()` is a thin `cli.verifyRemote()` shell-out — rclone owns
    its own credential cache and refresh logic.
  - `core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:32-37, 197-198`
    auth-error patterns are pattern-matched in stderr and surfaced as
    `AuthenticationException`; no token state in this process.
- **Verdict:** Not applicable in unidrive. Whatever refresh model rclone
  uses internally is opaque to us. If rclone itself uses a fragile
  scheduler, that is rclone's concern; from unidrive's process's
  perspective, every CLI invocation re-reads rclone's config.

## LocalFs

- **Model:** N/A (no auth — local filesystem).
- **Code refs:** `core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt`.
- **Verdict:** Not applicable.

## Summary table

| Provider  | Model      | Verdict                          | Follow-up needed |
|-----------|------------|----------------------------------|------------------|
| Internxt  | Per-call   | Robust under suspend/resume      | N (UX-only edge, optional) |
| OneDrive  | Per-call   | Robust under suspend/resume      | N |
| S3        | N/A        | No tokens; static SigV4          | N |
| SFTP      | N/A        | SSH session, not tokens          | N |
| WebDAV    | N/A        | HTTP Basic, no expiry            | N |
| Rclone    | N/A        | Delegated to rclone binary       | N |
| LocalFs   | N/A        | No auth                          | N |

## Follow-up tickets

None — every provider with token semantics (Internxt, OneDrive) uses a
per-call check, which is strictly more robust than drive-desktop's wall-clock
`setTimeout` pattern. A suspended-then-resumed unidrive process either (a)
notices the JWT/access-token is expired on the next call (Internxt
`isJwtExpired()` at `authenticate()`-time, OneDrive `isNearExpiry()` at every
`getValidToken()` call) and refreshes synchronously, or (b) hits a 401 on the
first network call after resume and triggers refresh on that path. There is
no scheduled work that can misfire. The drive-desktop pattern is *worse* than
what unidrive already does because a `setTimeout` set before suspend is
either delayed-but-incorrect-w.r.t-expiry or never fires; the per-call
pattern has neither failure mode.

Optional, deferred: an Internxt UX nicety where `getValidCredentials()` could
proactively call `refreshToken()` when `isJwtExpired()` returns true mid-sync,
avoiding the interactive re-auth prompt. Tracked verbally here, not yet
ticketed — file if a user ever reports the symptom.
