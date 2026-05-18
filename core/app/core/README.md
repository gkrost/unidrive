# `:app:core` — shared provider runtime

What every provider depends on, in one module. No provider-specific code lives here; if it would, it belongs in the provider module.

## What lives here

- **`org.krost.unidrive.CloudProvider`** — the SPI surface. `list`, `delta`, `download`, `upload`, `delete`, `move`, `quota`, etc. Two implementations: `internxt` and `onedrive`.
- **`org.krost.unidrive.ProviderFactory` / `ProviderRegistry`** — `ServiceLoader`-based discovery. Each provider module ships a `META-INF/services/org.krost.unidrive.ProviderFactory` entry.
- **`org.krost.unidrive.ProviderException`** — base exception with `requestId` field. Providers extract their server-correlation header into this field so cross-provider grep on `requestId=…` in `unidrive.log` finds the value regardless of which adapter raised it.
- **`org.krost.unidrive.AuthenticationException`**, `CredentialHealth` — auth contract.
- **`org.krost.unidrive.HashAlgorithm`** — `QuickXor` (OneDrive), `Md5Hex` (none currently use), `Sha256Hex` (Internxt). Provider declares its algorithm via `CloudProvider.hashAlgorithm()`.
- **Shared HTTP helpers** under `org.krost.unidrive.http`:
  - `HttpRetryBudget` — per-provider concurrency limiter + storm-tracking spacing + Retry-After-honouring backoff. OneDrive wires it; Internxt is queued.
  - `RequestId` — Ktor plugin that propagates a per-call correlation id into MDC for log lines that don't carry the response object.
  - `truncateErrorBody` — log-noise guard for HTML 5xx pages.
- **`org.krost.unidrive.auth`** — `OAuthService` base, refresh-token persistence helpers, near-expiry detection helpers (`Token.isNearExpiry(thresholdMs)`).
- **`org.krost.unidrive.io`** — `UnidriveJson` singleton (one `Json {}` config for all wire-format parsers), small `Files`/`Path` adaptors.

## Auth refresh model

Every token-bearing provider checks expiry **per-call**, not on a scheduler. The pattern:

1. At each API call site, read credentials through a closure (`tokenProvider`, `credentialsProvider`) that returns either the cached token or triggers refresh if `isNearExpiry()` / `isJwtExpired()` returns true.
2. On 401 mid-call, force-refresh once and replay the request (OneDrive does this; Internxt doesn't yet).
3. Refresh is mutex-serialised + double-checked so a 401 storm produces exactly one refresh.
4. The refresh body is wrapped in `withContext(NonCancellable)` so sibling cancellation can't half-write the token to disk (OneDrive does this; Internxt doesn't yet).

This is strictly more robust than the wall-clock `setTimeout(refresh, exp - now - lead)` pattern other clients use — a suspended-then-resumed daemon either notices the token is expired on the next call (silent refresh) or hits a 401 (reactive refresh). There is no scheduled work that can misfire.

## Credential vault

`org.krost.unidrive.sync.Vault` (lives in `:app:sync` for historical reasons; consumer is the CLI `vault` subcommand) stores provider credentials AES-encrypted under a passphrase. OneDrive's refresh token and Internxt's mnemonic both ride through it when the user opts in. Without the vault, credentials live in the provider's per-profile dir under `~/.config/unidrive/<profile>/` with `chmod 600`.

## When you add a helper here

The bar is "two providers would otherwise copy this." If it's specific to OneDrive's Graph quirks or Internxt's two-tier topology, it lives in the provider module. If it's a Ktor plugin, a retry primitive, an exception base, or a JSON config singleton, lift it here. Per AGENTS.md, *don't* add a new abstraction speculatively — two SPI implementations is not enough to justify a third layer.
