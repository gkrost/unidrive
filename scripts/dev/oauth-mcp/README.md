# unidrive-test-oauth MCP

Scoped, short-lived OneDrive access-token issuer. Runs in the user's
native environment (outside the Claude Desktop MSIX sandbox) and
exposes two tools — `probe` and `grant_token` / `revoke_token` — to a
Claude Code session so the agent can exercise real OneDrive behaviour
during a live-test without obtaining or storing long-term credentials
itself.

**Design context:** [`docs/dev/oauth-test-injection.md`](../../../docs/dev/oauth-test-injection.md).

## How the security model works

- **Refresh token never leaves this machine.** It lives in `%APPDATA%\unidrive\<profile>\token.json`,
  the same file the `unidrive` CLI already writes. This MCP never
  transmits or logs it.
- **Access tokens are short-lived.** Graph caps them at ~1h. The MCP
  caches recent ones in memory only and clears the cache on
  `revoke_token`.
- **Scope reduction is the default.** `grant_token(scope="read")`
  (the default) requests `offline_access Files.Read User.Read`. The
  agent cannot modify OneDrive data with a read-scope token. Pass
  `scope="write"` explicitly when testing upload / delete paths.
- **Malformed tokens are rejected.** `graph_client.refresh` validates
  that the returned access_token is a 3-segment JWS before returning.
  Catches the UD-312 "empty access_token on 2xx" class of bug at the
  issue site instead of at the HTTP call site later.

## Setup (one-time, on the user's machine)

1. Install dependencies into a dedicated venv:
   ```powershell
   cd scripts\dev\oauth-mcp
   py -m venv .venv
   .\.venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

2. Make sure the profile you want to test against has been authenticated
   via the regular unidrive CLI. This populates `%APPDATA%\unidrive\<profile>\token.json`:
   ```powershell
   unidrive -p onedrive-test auth
   ```

3. Sanity-check the refresh flow end-to-end:
   ```powershell
   python .\smoke.py onedrive-test
   ```
   Expected output ends with `OK`. If `FAIL (refresh)`, read the error —
   usually either an expired refresh token (re-auth) or a consent-level
   scope reduction rejection (use `scope="write"` in `grant_token`).

4. Register the MCP in your Claude Code config. Add to
   `~/.claude/claude_desktop_config.json` (or the equivalent Claude Code
   settings file):
   ```json
   {
     "mcpServers": {
       "unidrive-test-oauth": {
         "command": "C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\oauth-mcp\\.venv\\Scripts\\python.exe",
         "args": ["C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\oauth-mcp\\server.py"]
       }
     }
   }
   ```
   Restart Claude Code. The tools should appear as `mcp__unidrive-test-oauth__probe`
   and `mcp__unidrive-test-oauth__grant_token`.

## Usage from inside an agent session

```
probe()
→ { "config_dir": "C:\\Users\\...\\AppData\\Roaming\\unidrive",
    "config_dir_exists": true,
    "profiles_with_token": ["onedrive", "onedrive-test"] }

grant_token(profile="onedrive-test", scope="read")
→ { "access_token": "eyJ0eXAi...",
    "expires_at_ms": 1745067600000,
    "scope_granted": "openid profile offline_access Files.Read User.Read",
    "source": "refresh" }
```

### Read-only introspection tools (UD-724)

Two extra tools collapse the "bring-your-own httpx / sqlite3" boilerplate
that every session used to re-write:

```
query_graph(profile="onedrive-test", path="/me/drive")
→ { ...full Graph drive object, JSON-decoded... }

query_graph(profile="onedrive-test",
            path="/me/drive/root/children",
            params={"$select": "name,size", "$top": "10"})
→ { "value": [ { "name": "Pictures", "size": 1234 }, ... ] }

query_state_db(profile="onedrive-test",
               sql="SELECT COUNT(*) AS n FROM sync_entries")
→ { "rows": [ { "n": 66855 } ], "row_count": 1 }
```

`query_graph` reuses the token cache, so a series of queries doesn't
re-refresh. Paths that smell like mutations (`/copy`, `/move`, `/delete`,
`/send`, …) are refused at the tool surface.

`query_state_db` opens the DB with SQLite URI `mode=ro` and regex-rejects
any statement that isn't a `SELECT` or `WITH ... SELECT`. Missing `LIMIT`
is auto-appended with the caller's `limit` arg (default 1000).

Both tools are read-only by design. Anything that mutates state goes
through the CLI or the dedicated admin MCPs.

A Kotlin integration test can then pick up the token from an env var:

```kotlin
@Test fun `ThrottleBudget rides out a real 429 storm`() {
    val token = System.getenv("UNIDRIVE_TEST_ACCESS_TOKEN")
    org.junit.Assume.assumeTrue("no live token, skipping", !token.isNullOrEmpty())
    val svc = GraphApiService(testConfig) { _ -> token }
    // hammer svc until Graph throttles us; assert the circuit opens on schedule.
}
```

The agent pipes the `grant_token` output into the env var before
invoking `./gradlew :providers:onedrive:test`. The test is
`assumeTrue`-gated so a developer without the MCP still gets green.

## What this deliberately does NOT do

- **No device-code flow.** The CLI already handles that. Re-running it
  here would mean a second refresh token to manage.
- **No multi-account juggling.** The MCP operates on one profile per
  call. If the agent needs two accounts, it calls `grant_token` twice.
- **No access-token persistence.** Tokens only exist in the MCP's
  memory and in the returned JSON. `Ctrl-C` wipes them.
- **No rate limiting of its own.** Graph's own throttle budget applies
  to the minted tokens; the MCP itself does not introduce a separate
  lock.

## Known limitations / open work

- **Work/School tenants may reject scope reduction.** Microsoft
  personal accounts honour a narrowed scope in refresh grants, but
  some enterprise tenant policies require the original consent scope
  on every refresh. If `grant_token(scope="read")` returns an
  invalid_scope error, fall back to `scope="write"` until the test
  tenant's consent policy is understood.
- **CI integration unfinished.** The MCP is a developer-machine tool
  today. For GitHub Actions we need a headless client-credentials
  path (service principal with scoped permissions), which is a
  different OAuth flow and out of scope here.
- **Token rotation side effects.** Graph sometimes rotates the
  refresh token on a refresh grant. The MCP writes the rotated token
  back to `token.json` atomically, but a concurrent unidrive CLI run
  may see a brief window where both old and new tokens are in flight.
  In practice the CLI's UD-310 in-place refresh makes this a non-issue,
  but worth noting.

See also: `docs/dev/TOOLS.md`, `~/.claude/projects/.../memory/project_msix_sandbox.md`.
