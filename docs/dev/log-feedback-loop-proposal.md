# Log → code feedback loop (proposal, 2026-04-29)

Source: evaluation of `~/AppData/Local/unidrive/unidrive*.log` from 2026-04-29
(~32 MB across 4 rolled files plus the live tail).

## Findings

Run profile: dual-provider day. WebDAV bulk-upload (94,566 `Upload`,
1,244 `MKCOL`, 1,248 `PROPFIND`) running alongside background OneDrive
sync (180 `Delta`, 9 `Download`). 35+ `Scan started/ended` cycles all
completed cleanly (1.8–6.8 s).

Four WARN lines, no ERRORs:

1. **Two `Can't create an array of size 2.1–2.2 GB`** from
   `GraphApiService - Graph download failed (attempt 1/3)`. Same code
   path, two different files, 16 min apart. The values are
   `2_171_403_393` and `2_233_659_189` — both above
   `Integer.MAX_VALUE` (2,147,483,647). The Graph download is
   buffering the whole body into a `byte[]`. Any OneDrive file > ~2 GB
   fails deterministically at allocation. Retries papered over it but
   the second attempt also allocates the same array, so the fact that
   it passed on retry is suspicious — verify whether the file was
   actually persisted or silently skipped.
2. One `Connection reset` on Graph download, retried OK.
3. One `403 Forbidden` in `unidrive-mcp.log`, single isolated line,
   needs separate investigation.

Quiet but pervasive: large fraction of `DefaultDispatcher-worker-N`
lines carry `[???????] [*] [-------]` instead of
`[sessionId] [provider] [scanId]`. That is exactly the failure mode
documented in `docs/dev/lessons/mdc-in-suspend.md` — MDC is not
crossing suspension on the WebDAV upload path and the Graph download
path.

## Why `log-watch.sh --summary` showed clean

The script reads only `$UNIDRIVE_LOG` (default
`~/AppData/Local/unidrive/unidrive.log`). All today's WARNs sit in the
rolled-over `unidrive.2026-04-29.0.log`. Summary against the live
tail returns `WARN lines: 0` even though the day had 4 WARNs.

Secondary gap: `unidrive-log-anomalies` baseline is OneDrive-Graph
specific (429, JWT, TLS handshake). A WebDAV-bound day with a 94k
upload burst looks empty under that rubric.

## Proposal — two layers

### Layer 1: log evaluation tooling

Extend `scripts/dev/log-watch.sh`:

- Glob today's rolled set, not one file:
  `LOG_GLOB="${UNIDRIVE_LOG_GLOB:-$HOME/AppData/Local/unidrive/unidrive*.log}"`.
- Add counters: `Upload failed`, `MKCOL failed`, `PROPFIND failed`,
  `← req=... [45]xx` (RequestId 4xx/5xx responses),
  `Can't create an array of size`, MDC-missing
  (`\[\?\?\?\?\?\?\?\]`).
- Summarise scan boundaries from `SyncEngine` `Scan started/ended`:
  count, p50/p95 duration, longest, aborted (started without matching
  ended).
- Add `--json` mode for machine consumers (skill + CI hook).

Refresh `.claude/skills/unidrive-log-anomalies/SKILL.md`:

- Add a second baseline row for "WebDAV bulk-upload day" beside the
  existing OneDrive 346 GB run.
- Add `Can't create an array of size` and `[???????]` patterns to the
  rubric with explicit follow-ups (file UD-3xx / append to it).

### Layer 2: code-side changes the logs already point at

File two tickets via `python scripts/dev/backlog.py file
--category core`:

- **Stream large files in `GraphApiService.download` instead of
  buffering.** Switch to `okio.Sink` / chunked `InputStream` copy
  straight to disk. Acceptance: a unit test against a fake response
  with content-length > 2.2 GB completes without
  `OutOfMemoryError: Can't create an array of size N`. Reproduces
  against the two specific files in the user's OneDrive that
  triggered the WARNs today.
- **Restore MDC propagation on coroutine workers for upload/download
  paths.** Reference `docs/dev/lessons/mdc-in-suspend.md`. Add an
  integration test: 10-file upload run, assert
  `grep -c '\[\?\?\?\?\?\?\?\]'` on the test log is 0.

After both land:

- CI hook on commits touching `core/providers/onedrive/` or
  `core/app/sync/`: run `log-watch.sh --summary --json` over a
  fixture log and fail if `mdc_missing > 0` or
  `array_of_size > 0`.
- New lesson `docs/dev/lessons/large-file-array-limit.md` documenting
  the JVM array cap and the streaming pattern as canonical.

### Layer 3 (longer horizon, not blocking)

- Logback pattern: change `%X{sessionId}` to `%X{sessionId:-MDC_MISSING}`
  (and same for `provider`, `scanId`). Then `grep -c MDC_MISSING`
  becomes a first-class metric instead of relying on the
  `[???????]` placeholder regex.
- Sampled DEBUG on the upload path: 1-of-N + an INFO checkpoint every
  100 items (`Uploaded N items, X MiB/s, Y queued`). 94k DEBUG lines
  rolled 3 × 10 MB files in one sync run; the checkpoint also gives
  per-run throughput, which today cannot be derived from the log.
- Include `bytes=` on the `← req=...` RequestId completion lines so
  throughput rates can be derived from logs alone.

## Counts seen today (for the skill baseline update)

| Metric | 2026-04-29 dual-provider day |
|---|---|
| Total lines (all rolled + tail) | 132,536 |
| DEBUG / INFO / WARN / ERROR | 134,644 / 93 / 4 / 0 |
| WebDAV Upload | 94,566 |
| WebDAV PROPFIND / MKCOL | 1,248 / 1,244 |
| OneDrive Delta / Download | 180 / 9 |
| `Can't create an array of size` | 2 |
| `Connection reset` (Graph) | 1 |
| 4xx/5xx RequestId | 1 (mcp log only) |
| MDC-missing line share | high; not yet quantified |
