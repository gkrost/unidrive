# OAuth test-injection design note

> Not a spec, not an ADR. A scratchpad for the follow-up to UD-808.
> Captures requirements the 2026-04-19 sessions kept bumping into so
> that whoever picks this up doesn't have to re-derive the problem.

## Why this matters

Every OneDrive behaviour change I ship today goes out blind.
UD-232 (ThrottleBudget), UD-234 (log MDC), UD-310 (token refresh race),
UD-227 (CDN 429), UD-309 (Content-Length mismatch) all landed with unit
tests and a best-guess mapping from the last live-sync log that the
user runs. The real validation happens ~24 hours later when the user
re-runs against a real 346 GB OneDrive and reports whether the fix
held. That's a 1-day round-trip per non-trivial change.

A scoped OAuth token that an agent can request inside a session would
collapse this to a 5-minute integration test.

## Hard constraints

1. **Never commit a refresh token to the repo.** Non-negotiable. Any
   design that involves embedded secrets is rejected.
2. **Scoped: read-only by default.** Agents should not be able to
   mutate the user's real OneDrive without explicit per-request opt-in.
3. **Short-lived.** Hours, not days. If the agent leaks the token in a
   log or transcript, blast radius is minutes.
4. **No IdP round-trip inside the test.** We can't spawn a browser
   during `./gradlew test`. The test must receive a prepared access
   token, not a refresh token.
5. **Windows + Linux + CI.** Must work the same on the MSIX-sandboxed
   Claude Desktop on Windows, native Linux, and GitHub Actions.

## Proposed shape (to evaluate)

```
unidrive-test-oauth MCP
├── tool: grant_token(scope=read|write, ttl_seconds=3600)
│     → returns { access_token, expires_at, scope }
├── tool: revoke_token(access_token)
└── authenticated out-of-band against a test tenant that the user owns
```

The MCP runs in the user's native environment (outside the Claude
sandbox) and holds the long-lived refresh token on-disk at a path the
MSIX virtualisation can't reach. When the agent calls `grant_token`,
the MCP:

1. Uses its local refresh token to mint a short-lived access token
   via Graph's `/oauth2/v2.0/token` endpoint.
2. If `scope=read`, drops the upload/delete scopes from the token
   request — Graph honours `scope` on the refresh-grant.
3. Returns the access token + expiry + the scopes actually granted.
4. Revokes on `ttl_seconds` if the agent doesn't call `revoke_token`
   first (defensive — leaked tokens still expire fast).

The Kotlin test side gets:

```kotlin
@Test fun `throttle budget rides out a real 429 storm`() {
    val token = System.getenv("UNIDRIVE_TEST_ACCESS_TOKEN")
        ?: assumeTrue("no live token, skipping", false)
    val svc = GraphApiService(testConfig) { _ -> token }
    // hammer svc.getDelta() until Graph throttles us; assert the
    // ThrottleBudget circuit opens on schedule.
}
```

No refresh logic, no OAuth dance, no secrets on disk. The CI harness
pipes the MCP tool's output into `UNIDRIVE_TEST_ACCESS_TOKEN` before
invoking gradle.

## What's still unclear

1. **Tenant.** Is this the user's personal OneDrive with a dedicated
   sandbox folder, or a separate test tenant? Personal = real
   throttling patterns but risky; dedicated = safe but may not
   reproduce the 429 storm we're trying to fix.
2. **Storage of the refresh token** on the MCP side. Windows DPAPI is
   the obvious choice; Linux keyring is fine; the MSIX-virtualised path
   question needs a concrete fsutil-verified answer.
3. **Test opt-in.** By default, tests should `assumeTrue(envVar)` and
   skip silently when the token isn't present — so a developer without
   the MCP still gets a green `./gradlew test`.
4. **Rate-limit etiquette.** Real Graph treats us as one client; a CI
   run hammering the test endpoint could land everyone's real user
   account in throttle. Need an MCP-side budget too.

## Related

- UD-808 (OAuth test framework — blocked on secret injection) — this
  note is the "what's blocking" detail.
- UD-232 (ThrottleBudget) — the canonical change that should have been
  live-validated but wasn't.
- UD-312 (TokenManager JWT validation) — filed 2026-04-19; the kind of
  bug that only surfaces under real refresh traffic.
