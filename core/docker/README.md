# Docker test containers

Three harnesses share the same `Dockerfile.test` image:

| Harness | Compose file | Entrypoint | Scope |
|---------|--------------|------------|-------|
| localfs round-trip | `docker-compose.test.yml` | `test-matrix.sh` (default) | `localfs`-only sync, no external services |
| provider contract | `docker-compose.providers.yml` | `test-providers.sh` (override) | live `sftp` + `webdav` + `s3` (MinIO) + `rclone` adapters |
| MCP JSON-RPC (UD-815) | `docker-compose.mcp.yml` | `test-mcp.sh` (override) | deployed MCP shadow jar end-to-end against a seeded localfs profile |

All three run from the Gradle root (`core/`):

```bash
# Localfs harness (8 tests).
docker compose -f docker/docker-compose.test.yml build
docker compose -f docker/docker-compose.test.yml run --rm unidrive-test

# Provider contract harness (20 tests).
docker compose -f docker/docker-compose.providers.yml build unidrive-providers-test
docker compose -f docker/docker-compose.providers.yml run --rm unidrive-providers-test

# MCP JSON-RPC harness (9 tests).
docker compose -f docker/docker-compose.mcp.yml build
docker compose -f docker/docker-compose.mcp.yml run --rm unidrive-mcp-test
```

Exit code 0 = all tests passed. Expected on HEAD: `8 passed, 0 failed` + `20 passed, 0 failed` + `9 passed, 0 failed`.

## What the localfs harness tests

[`test-matrix.sh`](test-matrix.sh) drives the shadow-jar CLI through:

1. `sync --dry-run` against a golden file structure
2. `provider list` includes "Local Filesystem"
3. `status --all` shows the configured localfs profiles
4. `quota` returns non-zero storage info
5. `provider info localfs` resolves
6. `sync` (real, no `--dry-run`) runs without exception
7. A second `sync` after touching a new file runs without exception
8. A second profile on the same config is surfaced by `status --all`

## What the provider harness tests

[`test-providers.sh`](test-providers.sh) drives the CLI against live
`sftp` (atmoz/sftp), `webdav` (bytemark/webdav), `s3` (MinIO), and
`rclone` (MinIO via rclone's s3 backend) containers started by the
providers compose file:

1. Each service's listening port is reachable (bash `/dev/tcp` probe).
2. `ssh-keyscan` seeds `known_hosts` for SFTP (the adapter fails closed
   without it).
3. `provider info <type>` returns the descriptor (Tier: row check) —
   four adapters.
4. `status --all` enumerates each configured profile — four profiles.
5. `quota` succeeds for each profile — first call that hits the live
   server, so it validates adapter construction + auth + read-only API
   round-trip.
6. **Full upload round-trip** — write a unique marker file in
   `sync_root`, run `sync --upload-only`, then fetch the file via a
   second independent client (sftp / curl / mc / rclone) and byte-compare.
   Proves upload semantics + server-side persistence, not just adapter
   construction.

20 assertions total. The rclone adapter re-uses the MinIO service via
`rclone`'s native `s3` backend (bucket `unidrive-test-rclone`) — no extra
container for that one.

OAuth-backed providers (OneDrive / HiDrive / Internxt) are out of scope
— they require credentials and are gated on a future ticket for secret
injection.

## What the MCP harness tests

[`test-mcp.sh`](test-mcp.sh) boots
`java -jar /opt/unidrive/unidrive-mcp-*.jar -p <profile>` as a
subprocess against a seeded localfs profile, writes JSON-RPC requests
through a FIFO on the server's stdin, and reads responses line-by-line
from its stdout. `jq` handles framing and assertions.

1. `initialize` returns `serverInfo.name == "unidrive-mcp"` and a
   non-empty `version`.
2. `tools/list` returns at least the 15 tools currently registered in
   `app/mcp/Main.kt` (status, sync, get, free, pin, conflicts, ls,
   config, trash, versions, share, relocate, watchEvents, quota,
   backup). The expected count is pinned in the script; raising it
   above 15 is a deliberate test change.
3. `tools/list` surfaces `unidrive_status`, `unidrive_quota`,
   `unidrive_ls` by name (spot-check before tool calls).
4. `tools/call unidrive_status` returns non-error JSON `content` with
   `profile`, `providerType`, `syncRoot`, `files`,
   `credentialHealth`.
5. `tools/call unidrive_quota` returns numeric, non-negative `used` /
   `total` / `remaining` byte counters.
6. `tools/call unidrive_ls {path:"/"}` succeeds (empty JSON array is a
   valid response on an unsynced profile — the assertion is "no
   isError + parseable JSON array").

Nine assertions on HEAD. No external services.

## Layout notes

- Build context is `core/` (the Gradle root), not the repo root. The Dockerfile
  copies `gradlew`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/`, then
  `app/` and `providers/`.
- `**/build/` and `**/.gradle/` are excluded via [`.dockerignore`](../.dockerignore);
  missing that pattern pushes context to ~450 MB.
- CRLF is stripped from `gradlew`, `test-matrix.sh`, and `test-providers.sh`
  in the Dockerfile so the image works from a Windows checkout without the
  user pre-running `dos2unix`.
- `openssh-client`, `sshpass`, `curl`, `rclone`, and the MinIO `mc`
  binary are added to the runtime image so `test-providers.sh` can probe
  each adapter end-to-end via a second independent client. Added layers
  ≈ 70 MB; the localfs harness ignores them.

## Security

`read_only: true`, `no-new-privileges`, memory/CPU/PID limits, json-file log
rotation, non-root `testuser` — per the project security policy.
