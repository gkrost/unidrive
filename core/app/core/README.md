# `:app:core` — shared provider runtime

What every provider depends on, in one module. No provider-specific code lives here; if it would, it belongs in the provider module.

## What lives here

- **`org.krost.unidrive.CloudProvider`** — the SPI surface. `list`, `delta`, `download`, `upload`, `delete`, `move`, `quota`, etc. Implementations: `internxt`, `onedrive` and the offline-development `localfs`.
- **`org.krost.unidrive.ProviderFactory` / `ProviderRegistry`** — `ServiceLoader`-based discovery. Each provider module ships a `META-INF/services/org.krost.unidrive.ProviderFactory` entry.
- **`org.krost.unidrive.ProviderException`** — base exception with `requestId` field. Providers extract their server-correlation header into this field so cross-provider grep on `requestId=…` in `unidrive.log` finds the value regardless of which adapter raised it.
- **`org.krost.unidrive.AuthenticationException`**, `CredentialHealth` — auth contract.
- **`org.krost.unidrive.HashAlgorithm`** — `QuickXor` (OneDrive), `Md5Hex` (none currently use), `Sha256Hex` (Internxt). Provider declares its algorithm via `CloudProvider.hashAlgorithm()`.
- **Shared HTTP helpers** under `org.krost.unidrive.http`:
  - `HttpRetryBudget` — per-provider concurrency limiter + storm-tracking spacing + Retry-After-honouring backoff. OneDrive wires one, Internxt one per host (Drive REST, Bridge).
  - `RequestId` — Ktor plugin that propagates a per-call correlation id into MDC for log lines that don't carry the response object.
  - `truncateErrorBody` — log-noise guard for HTML 5xx pages.
- **`org.krost.unidrive.auth`** — `OAuthService` base, refresh-token persistence helpers, near-expiry detection helpers (`Token.isNearExpiry(thresholdMs)`).
- **`org.krost.unidrive.io`** — `UnidriveJson` singleton (one `Json {}` config for all wire-format parsers), small `Files`/`Path` adaptors.

## Auth refresh model

Every token-bearing provider checks expiry **per-call**, not on a scheduler. The pattern:

1. At each API call site, read credentials through a closure (`tokenProvider`, `credentialsProvider`) that returns either the cached token or triggers refresh if `isNearExpiry()` / `isJwtExpired()` returns true.
2. On 401 mid-call, force-refresh once and replay the request; a second 401 is an auth failure (both providers).
3. Refresh is mutex-serialised + double-checked so a 401 storm produces exactly one refresh.
4. The refresh body is wrapped in `withContext(NonCancellable)` so sibling cancellation can't half-write the token to disk (`RefreshableTokenLatch`, both providers).

This is strictly more robust than the wall-clock `setTimeout(refresh, exp - now - lead)` pattern other clients use — a suspended-then-resumed daemon either notices the token is expired on the next call (silent refresh) or hits a 401 (reactive refresh). There is no scheduled work that can misfire.

## HTTP client rules for a provider

What both HTTP providers do today and a new one must. The status-by-status retry matrix is the KDoc of `HttpRetryBudget`; it is not repeated here.

1. **`Retry-After` wins over your own backoff.** Order: the header, then a vendor body hint (OneDrive's CDN `retryAfterSeconds`, Internxt's `retry_after`), then exponential backoff. Graph sends delta-seconds; Internxt's parser also takes an HTTP date (RFC 9110 section 10.2.3). Cap each wait and the total so a hint cannot make the engine look hung. Microsoft documents that throttled requests still count against its limits, so an early retry prolongs the throttle.
2. **One throttle pauses the whole client.** Record every 429 and 503 on the client's `HttpRetryBudget` and take a slot from it immediately before each attempt (innermost, so a retry re-acquires one). Per-request waits alone let every coroutine wake at once into the next storm.
3. **Retry only what a replay cannot change.** Reads, a `PUT` to a fixed name or URL, and `DELETE` (a 404 means gone) are replay-safe. A commit step that may have landed server-side before the response was lost (Internxt `finishUpload`) is reconciled by looking, not repeated. A request the server refuses as malformed (400, 413, 415, 422) is refused again with the same bytes: raise `PermanentUploadFailureException` so queues do not retry it.
4. **401 means refresh once, then fail.** Replay the request once after a forced refresh; a second 401 is an auth failure. A pre-authenticated URL (a download or upload URL) that answers 401 or 403 has an expired token, not a bad account: re-resolve it once before treating it as auth.
5. **A 200 with an HTML body is not data.** Streaming downloads call `assertNotHtml`; otherwise a captive-portal page is written to disk as the file (or, on Internxt, decrypted into noise).

## Credential vault

`org.krost.unidrive.sync.Vault` (lives in `:app:sync` for historical reasons; consumer is the CLI `vault` subcommand) stores provider credentials AES-encrypted under a passphrase. OneDrive's refresh token and Internxt's mnemonic both ride through it when the user opts in. Without the vault, credentials live in the provider's per-profile dir under `~/.config/unidrive/<profile>/` with `chmod 600`.

## When you add a helper here

The bar is "two providers would otherwise copy this." If it's specific to OneDrive's Graph quirks or Internxt's two-tier topology, it lives in the provider module. If it's a Ktor plugin, a retry primitive, an exception base, or a JSON config singleton, lift it here. Per `AGENTS.md` — abstractions earn their keep. The two-implementation bar above is what that looks like for provider helpers in this module.
