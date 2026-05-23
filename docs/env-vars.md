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
problem. See `docs/superpowers/specs/ipc-transport-dispatcher-isolation-design.md`
for the history.

## Other env vars in use (not yet documented in this file)

The following production env vars are read by the daemon but their
operator-facing semantics aren't fully captured here yet. Fill in as
operator-facing behavior is touched. Test-only flags are intentionally
excluded.

- `UNIDRIVE_CONFIG_DIR`
- `UNIDRIVE_STRICT_CONFIG`
- `UNIDRIVE_VAULT_PASS`
- `UNIDRIVE_WATCHER_DEBOUNCE_MS`
