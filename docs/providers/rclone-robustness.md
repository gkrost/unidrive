# Rclone wrapper — process-shellout robustness audit

> Deliverable for [UD-321](../backlog/BACKLOG.md#ud-321) (part of the UD-228
> cross-provider audit split).
> Vendor docs: [rclone docs index](https://rclone.org/docs/),
> [global flags reference](https://rclone.org/flags/),
> [JSON log format](https://rclone.org/docs/#use-json-log).
> Companion: [rclone-client-notes.md](rclone-client-notes.md).

This audit is **process-shellout-shaped, not HTTP-shaped.** Unlike
[onedrive-robustness.md](onedrive-robustness.md) (canonical baseline)
or [webdav-robustness.md](webdav-robustness.md), the rclone "provider"
is not a Ktor client — it's a `ProcessBuilder` that forks the `rclone`
binary, blocks on `waitFor`, and parses stdout/stderr/exit code. The
five UD-228 dimensions re-frame: "non-2xx body parsing" → exit-code +
stderr regex; "retry placement" → which `--retries` /
`--low-level-retries` flags we pass through (today: none, defaults
silently inherited); "Retry-After source" → invisible to us, consumed
inside rclone's per-backend pacer; "idempotency" → process lifecycle
+ half-uploaded-file cleanup on SIGKILL; "concurrency" → `--transfers`
+ unidrive's pipeline fan-out.

## Status summary

> "Retry placement" here means **rclone-flag passthrough**, not an
> in-process HTTP retry loop. We have none.

| Dimension | Finding | Confidence |
|---|---|---|
| Non-2xx body parsing | Exit code on `RcloneException.exitCode`; stderr lowercased + substring-matched against a 6-pattern allow-list to promote auth failures; no JSON-log parsing | High |
| Retry placement (flag passthrough) | **Zero flags passed.** rclone's `--retries=3` / `--low-level-retries=10` defaults apply silently | High |
| Retry-After source | Honoured internally by rclone's per-backend pacer; never seen by the wrapper. Retry-warnings printed to stderr are **discarded on success** — they only reach `unidrive.log` on a non-zero exit | High |
| Process lifecycle / idempotency | `destroyForcibly()` (SIGKILL) on cancellation + timeout — no SIGTERM grace, no partial-file cleanup, no byte-offset resume | High |
| Concurrency | Inherits `--transfers=4` / `--checkers=8` silently; no semaphore around `RcloneCliService` calls — N parallel SyncEngine coroutines spawn N rclone processes, each with its own 4-transfer fan-out | Medium |
| Pipe-buffer deadlock | stdout then stderr drained sequentially before `waitFor`; verbose rclone on a long transfer can fill the ~64 KB stderr pipe and stall the child | Medium |
| Auth-pattern fragility | rclone's stderr is explicitly not a stable API; any upstream re-word demotes a real auth failure to generic `RcloneException` | High |
| Version skew | No `rclone version` probe; flag semantics shift across releases | Low |
| `--config` path trust | `rcloneConfigPath` flows directly into argv, no `profileDir` containment check | Low |

## 1. Error parsing

Two channels carry structure: the **exit code** (numeric, the only
stable surface — full list at [rclone docs Exit Code](https://rclone.org/docs/),
mirrored in [rclone-client-notes.md:34-37](rclone-client-notes.md))
and the **stderr stream** (free-form text, explicitly not a stable
API). At [`RcloneCliService.execute`](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:194):

```kotlin
val exitCode = process.exitValue()
if (exitCode != 0) {
    val stderrLower = stderr.lowercase()
    if (AUTH_ERROR_PATTERNS.any { it in stderrLower }) {
        throw AuthenticationException("rclone auth failed: $stderr")
    }
    throw RcloneException("rclone failed (exit $exitCode): $stderr", exitCode = exitCode)
}
```

The integer is preserved on `RcloneException.exitCode`. Only one
caller branches on it: [`listAllRecursive`](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:67)
swallows exit-3 (directory-not-found) as empty. Every other code —
including the load-bearing distinction between **5** (transfer
exceeded retries — *transient*), **6** (noretry — *do not retry*),
**7** (fatal), and **2** (unknown) — collapses to a single
`RcloneException`. Nothing currently differentiates them.

Auth allow-list at [RcloneCliService.kt:32-40](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:32):
six lowercased substrings (`couldn't find remote`, `no such host`,
`access denied`, `unauthorized`, `invalid_grant`, `failed to create
file system`). Confidence the list catches all real auth failures
across rclone 1.50 → 1.70: Low — and a miss demotes
`AuthenticationException` (which would trigger re-auth) to a generic
`RcloneException` (which the action layer marks "transfer failed").

**No JSON-log parsing.**
[`--use-json-log`](https://rclone.org/docs/#use-json-log) emits
NDJSON with `level`, `msg`, `source`, `time` (and `size` / `object`
on transfer events). We do not pass it; we do not parse stderr
structurally. Wiring it would replace the substring regex with
`level: ERROR` matching and let mid-transfer warnings reach
`unidrive.log` (see §3).

## 2. Retry placement (rclone-flag passthrough)

The [canonical HTTP retry matrix](../dev/lessons/http-retry-policy.md)
applies inside rclone (per-backend pacers honour `Retry-After` etc.) but
the wrapper sees none of it — the section below documents what flags we
pass and what defaults rclone silently inherits, which is a fundamentally
different concern from the canonical matrix.

There is no retry loop in the wrapper. Between the caller and
`ProcessBuilder.start()` is one absolute timeout, nothing else.

**Flags we pass:** `--config <path>` ([:174](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:174))
when `rcloneConfigPath` is set; `--max-depth 0`, `--hash`,
`--recursive` for listing; `--no-traverse` + `--ignore-times` on
`copyto` ([:93](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:93)).

**Flags silently inherited** ([rclone flags ref](https://rclone.org/flags/)):

| Flag | Default | Implication |
|---|---|---|
| `--retries` | 3 | No user knob |
| `--retries-sleep` | 0s | Immediate re-run, no backoff |
| `--low-level-retries` | 10 | Generous, rarely a problem |
| `--tpslimit` | 0 (unlimited) | Drive/Dropbox low-quota users cannot throttle |
| `--contimeout` | 1m | OK |
| `--timeout` | 5m | **Below** our 10 m absolute caps — stalled connections hit 5 m first |
| `--use-json-log` | off | Plain text stderr only |

**Failures that escape rclone's internal retries and reach unidrive
as fatal:**

1. **Exit 5** — `--retries=3 × --low-level-retries=10` exhausted.
   We treat identically to exit 2; action layer marks the file failed.
2. **Exit 6** — noretry error. Same treatment.
3. **Exit 7** — fatal. Same treatment.
4. **Stalled past `--timeout=5m` idle** — rclone exits non-zero on IO timeout.
5. **OAuth refresh failure inside rclone** — regex *might* catch
   `invalid_grant` (lucky). If wording differs (e.g. `OAuth2 token
   error`), falls through as generic `RcloneException`.

The structural problem: rclone's exit-code distinction is squashed by
our wrapper. (1)-(3) all look identical to upper layers.

## 3. Retry-After

**Not visible at the wrapper layer.** rclone honours backend
`Retry-After` headers internally (Drive ~100 ms min spacing, Dropbox
12 TPS — see [rclone-client-notes.md:38-41](rclone-client-notes.md)).
The unidrive process sees only the eventual exit code.

**stderr surfacing audit.** Verbose rclone prints `Failed to copy:
googleapi: Error 429: ..., will retry in 5s` to stderr during the
retry. Our flow:

1. `errorStream.bufferedReader().readText()` is called *after*
   `waitFor` ([RcloneCliService.kt:188](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:188)).
2. On exit code 0, the stderr buffer is **discarded** —
   `execute` returns `stdout` only ([:202](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:202)).
3. On non-zero exit, stderr is concatenated into the exception message ([:200](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:200)).

So **retry-warning messages never reach `unidrive.log` on a
successful run**. A user seeing repeated 5-minute syncs has no
diagnostic. `--use-json-log` plus a streaming stderr drainer would
fix this and the §4 deadlock simultaneously.

## 4. Process lifecycle / idempotency

**Cancellation** ([RcloneCliService.kt:203-206](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:203)):
`destroyForcibly()` is SIGKILL on Linux — no SIGTERM grace, rclone
has no chance to flush in-flight transfers or close upload sessions.
`CancellationException` is correctly re-thrown (matches UD-300's
pattern for OneDrive's flake-retry).

**Timeout** ([:189-193](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:189)):
also `destroyForcibly`, with `RcloneException(exitCode = -1)`. The
`-1` sentinel is indistinguishable from any other unknown failure.
Per-command caps (15 s `verifyRemote`, 30 s `stat`/`mkdir`/`about`,
2 m `list`/`deleteFile`/`moveTo`, 10 m
`copyto`/`upload`/`listAllRecursive`) are absolute, not idle — a
slow but progressing 12 GB upload dies at 10 m.

**Half-uploaded files on SIGKILL.** S3 multipart uploads leave
orphaned upload IDs (cost implication). Drive/Dropbox/OneDrive
resumable sessions leave incomplete state server-side. Downloads
leave `.partial`-suffix files on local disk. **unidrive does no
cleanup** — no `rclone cleanup` follow-up; no `.partial` removal.
With `--ignore-times` + `--no-traverse` set on `copyto`
([:93](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:93), [:102](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:102)),
every retry re-transfers from byte 0 — no resume.

`moveto` is non-atomic for cross-backend moves (copy-then-delete);
SIGKILL between the two leaves source intact and a copy at the
destination.

**Process restart on transient failure: structurally clean.** Each
operation spawns a fresh `ProcessBuilder` ([:182](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:182))
— no shared state, no daemon. Restart hygiene is fine; resume
hygiene is absent.

**Pipe-buffer deadlock** ([:187-188](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:187)):
stdout then stderr are drained sequentially from the same thread
before `waitFor`. If rclone produces stderr faster than the JVM
consumes stdout, the OS pipe buffer (~64 KB) fills and the child
blocks on `write(stderr)` while we're stuck reading stdout. The 10 m
`waitFor` then fires and SIGKILL finishes the hung process.
Confidence at default verbosity: Low; under `-v` / `-vv`: High.
**This is the single dangerous correctness gap.**

## 5. Concurrency recommendations

Two layers interact: **inside one rclone process** (4 parallel
transfers, 8 parallel checkers per [rclone flags ref](https://rclone.org/flags/))
and **across unidrive's pipeline** (SyncEngine fan-out spawning
multiple `RcloneCliService` calls).

Nothing in [`RcloneProvider`](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProvider.kt)
gates concurrent invocations. There is no `HttpRetryBudget` analogue
because the wrapper has nothing HTTP-shaped to throttle. With N
SyncEngine coroutines, we spawn N rclone processes — each running
its own 4-transfer fan-out, for an effective N×4 parallel network
operations.

**Per-backend recommended values** ([rclone-client-notes.md:38-41](rclone-client-notes.md)):

| Backend | `--tpslimit` | `--transfers` |
|---|---|---|
| Google Drive | 10 | 2-4 |
| Dropbox | 12 | 4 |
| OneDrive | unset | 4 |
| S3 | unset | 16-64 |
| pCloud / Box | conservative | 4 |
| SFTP | unset | 4 |
| WebDAV / Nextcloud | unset | 4-5 |

**None tunable from `provider.toml`.** Adding `rclone_transfers`,
`rclone_tpslimit`, `rclone_checkers` to
[`RcloneProviderFactory.create`](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProviderFactory.kt:38)
closes this gap with zero protocol risk.

## Rclone — field observations

1. **Exit-code distinctions are wasted.** Codes 5/6/7 all collapse to
   `RcloneException` with no caller branching except the exit-3 swallow
   at [RcloneCliService.kt:67](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:67).
2. **`--ignore-times` defeats rclone's change detection.** Set on
   download ([:93](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:93))
   to handle sparse-file placeholders; side effect is every retry
   re-transfers full content from byte 0.
3. **JSON log NDJSON quirk.** Per [rclone --use-json-log](https://rclone.org/docs/#use-json-log):
   "a complete log file is not strictly valid JSON and needs a parser
   that can handle it" — line-by-line, not array. Small wiring cost
   for a much more stable parsing surface than substring regex on
   free-form English.
4. **Stderr buffered, not streamed.** Mid-transfer warnings live in
   the child's stderr for the run's duration and never reach
   `unidrive.log` on success. Diagnosing why a sync took 8 minutes
   when it should have taken 30 seconds requires re-running manually.
5. **No version probe.** Flag semantics differ across rclone 1.50 →
   1.68; behaviour-in-the-wild is currently unattributable.
6. **`--config` path trusted as-is.**
   [`config.rcloneConfigPath`](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt:174)
   flows directly into argv. No path canonicalisation.
7. **rclone 1.55+ for `--multi-thread-streams`.** We never pass it,
   but if added later, users on rclone < 1.55 would hit `unknown
   flag` and exit 1.

## rclone-backend matrix

unidrive does not "wire" specific rclone backends — `config.remote`
([RcloneConfig.kt:6](../../core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneConfig.kt:6))
is whatever the user named in `rclone.conf`. The rclone binary picks
the backend by remote name. Per-backend behaviours we inherit:

| Backend | Pacer | Retry-After honoured | Resume | Notes |
|---|---|---|---|---|
| Google Drive (`drive`) | ~100 ms min | yes (rclone-internal) | yes | `--tpslimit=10` recommended |
| Dropbox (`dropbox`) | hard 12 TPS | yes | yes | rclone respects 429 with backoff |
| S3 (`s3`) | none | yes (header) | yes (multipart) | SIGKILL leaves orphaned multipart upload IDs |
| OneDrive (`onedrive`) | rclone-internal | yes | yes | UD-227/UD-232 hardening on our native provider is bypassed |
| Box (`box`) | conservative | yes | yes | tight rate limits; `--tpslimit=10` advised |
| pCloud (`pcloud`) | conservative | yes | partial | flaky on large files per upstream issues |
| SFTP (`sftp`) | n/a | n/a | partial | `--multi-thread-streams` not applicable |
| WebDAV (`webdav`) | none | server-dependent | no | same caveats as our native WebDAV |
| Mega, B2, Azure Blob, etc. | varies | varies | varies | untested by unidrive |

The whole point of the wrapper is "any backend rclone supports" —
that generality is the audit's blind spot. We cannot guarantee
per-backend correctness without testing each backend against the
specific rclone version on the user's machine.

## Follow-ups

- **Pipe-buffer deadlock fix.** Drain stdout and stderr on background
  threads. Highest-priority correctness gap.
- **`--use-json-log` + structured stderr parsing.** Replaces substring
  regex with `level + msg` matching; surfaces backend retry warnings
  to `unidrive.log`.
- **Pass-through TOML flags** in `RcloneProviderFactory`:
  `rclone_transfers`, `rclone_tpslimit`, `rclone_checkers`,
  `rclone_low_level_retries`, `rclone_timeout`. Closes §5 tuning gap.
- **Exit-code-aware action layer.** Distinguish exit 5 (retryable)
  from exit 7 (give up) at the SyncEngine level.
- **Idle timeout, not absolute cap.** Byte-counter heartbeat from
  the streaming stdout drainer would let large transfers continue as
  long as bytes flow.
- **`rclone version` probe at startup.** Log the binary version;
  fail-closed below a known-good minimum.
- **Sanitise `--config` path.** Require `rclone_config` under
  `profileDir`; refuse arbitrary filesystem paths.
- **Half-upload cleanup hook.** Schedule `rclone cleanup` after
  SIGKILL on supported backends (S3 multipart, Drive sessions).
- **Concurrency budget across `RcloneProvider`.** Mirror
  `HttpRetryBudget`'s shared-semaphore shape so SyncEngine fan-out
  cannot spawn unbounded rclone processes.

UD-262 (`HttpRetryBudget` config surface) and UD-263 (per-provider
concurrency hints) are the upstream tickets these findings feed into.
