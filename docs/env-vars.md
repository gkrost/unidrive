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

### `UNIDRIVE_IPC_IDLE_TIMEOUT_MS`

How long an IPC connection may go without sending a request before
`IpcServer` closes it (no line is written; the client reads end of stream).
Never closed for being idle: a `sync.subscribe` subscriber, and a connection
that has used any `hydration.*` verb (a mount client: its open handles and
its event subscription live on its connections). Time spent waiting for a
reply does not count.

- **Default:** `1800000` (30 minutes)
- **`0`** switches the idle close off.
- **Accepted range:** `60000..86400000` (1 min..24 h); a value outside it is
  clamped to the nearer bound.
- **Negative or unparseable values fall back to the default.**
- **Code:** `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`, `parseIdleTimeoutMs`, `idleExpired`.

A client that pools connections should resend a request once on a new
connection when a pooled connection reaches end of stream before any byte
of the reply: the daemon closes an idle connection only between requests,
so the request was not processed.

### `UNIDRIVE_IPC_MAX_CLIENTS`

How many IPC connections `IpcServer` serves at once. One more connection
reads `{"ok":false,"error":"too_many_clients"}` and is closed; it is never
served.

- **Default:** `32`
- **Accepted range:** `4..256`; a value outside it is clamped to the nearer
  bound.
- **Unset, unparseable, `0` or negative values fall back to the default.**
- **Code:** `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`, `parseMaxClients`.

The Windows client alone may hold 8 connections (a pool of 6 plus 2
subscriptions); the CLI, the tray, the status UI and scripts share the rest.
The daemon logs `IPC: max clients (N) reached, refusing connection` when the
cap is hit, and the start line names the value in use (`max_clients=`).

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

The feed is only a latency optimisation; polling alone is always correct.
Only a process that consumes remote-change hints opens the socket (today
`sync --watch`); one-shot commands and `daemon run` never contact the host.
If the host (`INTERNXT_NOTIFICATIONS_URL`, default
`https://notifications.internxt.com`) is merely unreachable (offline, timeout,
refused, 5xx), the client logs one WARN per outage and keeps reconnecting in
the background with socket.io's own backoff (1 s up to 60 s). If the endpoint
cannot be the notifications server (its TLS certificate is not valid for the
host name or not trusted, or the websocket upgrade is answered with a
non-upgrade status: 2xx, 3xx, 404, 410), the client logs one WARN and stays off
for the rest of the process; restart the process to try again. Set this
variable to `off` to stop even the attempt.

### `INTERNXT_NOTIFICATIONS_URL`, `INTERNXT_CLIENT_NAME`, `INTERNXT_CLIENT_VERSION`, `INTERNXT_DESKTOP_HEADER`, `INTERNXT_CRYPTO_KEY`

Overrides for the identity unidrive presents to the Internxt gateway; leave
them unset. `INTERNXT_NOTIFICATIONS_URL` is the websocket host above.
`INTERNXT_CLIENT_NAME` / `INTERNXT_CLIENT_VERSION` replace the client name and
version headers. `INTERNXT_DESKTOP_HEADER` is sent as the desktop-client token
header only when set (unidrive has no such token and does not invent one; the
override is for testing). `INTERNXT_CRYPTO_KEY` replaces the application key
used to wrap the login password.
- **Code:** `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtConfig.kt`, `InternxtHeaders.kt`.

## Launchers and the JVM

Read by the launchers (`unidrive`, `unidrive.ps1`) and by `DaemonAutospawn`
when a command starts a daemon; the static flags themselves are in
`dist/launcher/jvm-flags.txt` (the single source for the launchers, `install.sh`
and the Windows client's `EngineHost.JvmFlags`).

### `UNIDRIVE_XMX`

The maximum heap as a bare size without the `-Xmx` prefix (`512m`, `2g`).
- **Default:** `2g`. A blank value counts as unset.

### `UNIDRIVE_LOCALE`

`xx` or `xx_YY` (also `xx-YY`): sets `-Duser.language` and `-Duser.country` of
the JVM; the `--locale=xx_YY` command-line option wins over it. A daemon that a
command autospawns inherits the parent's locale.

### `UNIDRIVE_DIAG_DIR`

The folder for post-mortem diagnostics: fatal-crash `hs_err_pid<pid>.log`
files, the OOM heap dump and the daemon's bounded GC log (`gc.log`, 5 files of
10 MB, only for `daemon run`, `sync --watch` and `autostart`).
- **Default:** `%LOCALAPPDATA%` + `/unidrive/diagnostics` on Windows,
  `~/.local/share/unidrive/diagnostics` on Linux. Created on start.

### `UNIDRIVE_COROUTINE_DEBUG`

`1` (any non-blank value other than `0`) arms the daemon's coroutine-dump
facility: the launcher adds the probe jar beside the fat jar as a `-javaagent`
(a running JVM cannot be armed), and creating `coroutine-dump.trigger` in the
profile folder makes the daemon write every live coroutine to
`coroutine-dump.txt` next to it. A debugging-session cost; leave it off.
- **Code:** `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CoroutineDebug.kt`.

### `UNIDRIVE_JAVA`, `UNIDRIVE_JVM_DIR` (Linux launcher)

`UNIDRIVE_JAVA=/path/to/bin/java` picks the runtime. Otherwise the launcher uses
`java` on `PATH` when it is 25 or newer, else the newest 25+ JDK under
`UNIDRIVE_JVM_DIR` (default `/usr/lib/jvm`), else `java` on `PATH`; it refuses
Java below 21. See `dist/README.md`.

### `UNIDRIVE_ASCII`

`1` (any non-empty value other than `0`) forces the ASCII fallback for the
status glyphs; useful for CI and log-scraping pipelines.
- **Code:** `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/GlyphRenderer.kt`.

## Other env vars in use (not yet documented in this file)

The following production env vars are read by the daemon but their
operator-facing semantics aren't fully captured here yet. Fill in as
operator-facing behavior is touched. Test-only flags are intentionally
excluded.

- `UNIDRIVE_CONFIG_DIR`
- `UNIDRIVE_STRICT_CONFIG`
- `UNIDRIVE_VAULT_PASS`
- `UNIDRIVE_WATCHER_DEBOUNCE_MS`
