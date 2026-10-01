# Environment Variables

Operator-tunable knobs read by the daemon at startup. Test-only env vars
(e.g. `UNIDRIVE_INTEGRATION_TESTS`) live in their respective test
documentation, not here.

## IPC

### `UNIDRIVE_IPC_WRITE_TIMEOUT_MS`

Per-write socket deadline for `IpcServer`'s non-blocking write loop.
Applies to every byte written to a connected IPC client (broadcast
events, verb replies, initial state dumps). When the deadline expires
without the kernel accepting more bytes, the client is dropped and the
close-listener fires.

- **Default:** `5000` (5 seconds)
- **Accepted range:** `100..600000` (100ms..10min)
- **Out-of-range or unparseable values silently fall back to the default.**
- **Code:** `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`, `writeNonBlocking`.

Raise this when an operator is seeing legitimate `Write timeout exceeded
for IPC client` log lines on a system with known socket back-pressure
(e.g. laptop sleep cycles, kernel-side throttling, intermittently slow
filesystem on the receiving side). Do not raise it to mask
`Dispatchers.IO` saturation — that's a structural bug, not a tuning
problem. See `docs/dev/specs/ipc-transport-dispatcher-isolation-design.md`
for the history.

## OneDrive

### `UNIDRIVE_ONEDRIVE_OAUTH_PORT`

Loopback port the browser sign-in (`unidrive auth`) listens on for the OAuth
redirect. The redirect URI sent to Microsoft is built from it
(`http://localhost:<port>/callback`).

- **Default:** `8080`
- **Accepted range:** `1..65535`
- **An unusable value is logged as a warning and the default is used.**
- **Code:** `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveConfig.kt`.

Set this when another program already holds 8080: sign-in then fails with
`Cannot listen on 127.0.0.1:8080 ...`. Microsoft must accept the redirect URI
for the chosen port. Matching of a `localhost` redirect is documented to ignore
the port, but a non-default port has not been tried against this app
registration. `unidrive auth --device-code` needs no port at all.

## Internxt

### `INTERNXT_NOTIFICATIONS`

Switch for the socket.io wake-signal client (the connection to Internxt's
change feed that lets a watching daemon sync early instead of waiting for the
next poll).

- **Default:** on (unset)
- **Values:** `off`, `0` or `false` (any case) skip starting the client and log
  one INFO line (`Internxt notifications disabled by INTERNXT_NOTIFICATIONS; sync
  polls only`). Any other value leaves it on.
- **Code:** `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtConfig.kt`,
  `InternxtProvider.ensureNotificationsClient`.

The feed is only a latency optimisation; polling alone is always correct. If
the host (`INTERNXT_NOTIFICATIONS_URL`, default `https://notifications.internxt.com`)
does not accept connections, the client logs one WARN per outage and keeps
reconnecting in the background with socket.io's own backoff (1 s up to 60 s);
set this to `off` to stop even that.

## Other env vars in use (not yet documented in this file)

The following production env vars are read by the daemon but their
operator-facing semantics aren't fully captured here yet. Fill in as
operator-facing behavior is touched. Test-only flags are intentionally
excluded.

- `UNIDRIVE_CONFIG_DIR`
- `UNIDRIVE_STRICT_CONFIG`
- `UNIDRIVE_VAULT_PASS`
- `UNIDRIVE_WATCHER_DEBOUNCE_MS`
