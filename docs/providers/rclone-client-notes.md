# rclone — client notes

Research input for UD-230 (feeding UD-228 cross-provider robustness audit).

## Vendor recommendations

Unlike the other providers, rclone is a *program we shell out to*, not a
service with an HTTP API. The vendor-equivalent is
[rclone.org/docs/](https://rclone.org/docs/) and
[rclone.org/flags/](https://rclone.org/flags/).

- **`--transfers` (default 4)**: number of file transfers to run in
  parallel. ([forum confirmation](https://forum.rclone.org/t/transfers-vs-checker-ratio/142))
- **`--checkers` (default 8)**: parallel checkers for equality checks.
  Docs advise reducing to 4 or less for slow backends.
- **`--low-level-retries` (default 10)**: retries for low-level
  operations (HTTP requests, chunks). "Shouldn't need to be changed from
  the default in normal operations." Set to `1` to disable.
  ([rclone docs](https://rclone.org/docs/))
- **`--retries` (default 3)**: high-level retries of the whole sync
  operation when transient file errors remain.
- **`--tpslimit` (default 0 = unlimited)**: global HTTP transactions per
  second ceiling. Used to stay under per-API rate limits (Google Drive:
  10, Dropbox: 12). `--tpslimit-burst` (default 1).
- **`--contimeout` (default 1 m) / `--timeout`**: TCP connect timeout and
  idle-connection timeout.
- **Library vs CLI**: rclone is explicitly a CLI. The documentation
  contains **no official guidance for library / subprocess consumers** —
  there is an `rc` remote-control daemon mode, but orchestrating via
  `ProcessBuilder` is undocumented. Subprocess consumers inherit whatever
  exit-code and stderr contract rclone happens to ship; rclone's stderr
  format is explicitly not a stable API.
- **Exit codes**: 0 = success, 1 = syntax/usage, 2 = unknown error,
  3 = directory-not-found, 4 = file-not-found, 5 = transfer exceeded
  retries, 6 = noretry error, 7 = fatal error, 8 = transfer limit
  exceeded, 9 = operation successful but no files transferred.
  ([rclone docs Exit Code section](https://rclone.org/docs/))
- **Per-backend pacers**: each backend has its own internal pacer
  (e.g. Google Drive pacer defaults to ~100 ms between requests). These
  are tuned for each API's published limits and cannot be overridden
  from the outside.
- **Version skew matters**: rclone releases every ~3 months; flag
  semantics can change. Subprocess consumers should pin the binary
  version or probe `rclone version` and adjust.
- **Config file**: rclone.conf contains secrets in a format rclone owns.
  Subprocess consumers must either ship their own config or depend on
  the user's.

## What unidrive does today

`core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneCliService.kt`

- **Invokes the rclone binary via `ProcessBuilder`** (`:160-186`) per
  operation. Uses `--config` to point at a user-specified rclone.conf
  when provided (`:154-156`).
- **Per-command timeouts hardcoded** (`:47`, `:51-68`, `:86-95`):
  15 s (`verifyRemote`), 30 s (`stat`, `mkdir`, `about`), 2 m (`list`,
  `deleteFile`, `moveTo`), 10 m (`copyTo`, `upload`, `listAllRecursive`).
  On timeout: `destroyForcibly` + `RcloneException(exitCode = -1)` (`:168-171`).
- **Exit-code mapping** (`:172-179`): exit 3 (dir-not-found) is swallowed
  in `listAllRecursive` as empty (`:67`); everything else throws
  `RcloneException`. `AuthenticationException` is raised by
  string-matching `stderr` against `AUTH_ERROR_PATTERNS` (`:32-39`) —
  brittle if rclone changes its error text.
- **No `--tpslimit`, no `--transfers`, no `--low-level-retries` overrides**
  passed to rclone — relies entirely on rclone's defaults (4 transfers,
  10 low-level retries, unlimited TPS).
- **`copyto` uses `--no-traverse` and `--ignore-times`** (`:88`) —
  necessary for sparse placeholders but disables rclone's own
  optimization for large trees.
- **`stderr` is read after `waitFor`** (`:165-172`) — OK for small
  outputs, **but on a long 10 m `copyto` of a 10 GB file the stderr
  buffer can fill (OS pipe buffer, typically 64 KB) and deadlock the
  subprocess**. This is a real risk.
- **No stdout streaming** — `readText()` blocks until EOF. For
  `listAllRecursive` on a large remote this buffers the entire JSON list
  into memory before parsing.
- **No binary version probe.** We assume whatever `rclone` is on PATH
  behaves per our expectations.

## Gaps → UD-228

- [ ] **Pipe-buffer deadlock risk on long uploads/downloads.** stderr
      (and stdout) are drained only after `waitFor`. If rclone logs
      verbosely mid-transfer and fills 64 KB pipe buffer, the process
      blocks on write and our `waitFor` hits its 10 m timeout. Fix:
      drain both streams on background threads / `Redirect.PIPE` +
      readers in parallel.
- [ ] **No version pinning or probe.** A user with rclone 1.50 will
      see different behaviour from one with 1.68; flag semantics and
      exit codes have changed across versions.
- [ ] **Auth detection via stderr regex is fragile.** rclone's error
      text is not a stable API; we will misclassify real auth failures
      as generic `RcloneException` after any upstream reword.
- [ ] **No pass-through for `--tpslimit` / `--transfers` /
      `--low-level-retries`.** Users hitting their backend's rate limits
      (Dropbox, Google Drive) cannot tune from unidrive config.
- [ ] **Timeouts are per-command absolute caps, not idle timeouts.**
      A slow-but-making-progress 12 GB upload dies at 10 m even if
      nothing is wrong.
- [ ] **`RcloneException(exitCode = -1)` for timeout is indistinguishable
      from other unknown failures** on the caller side.
- [ ] **No structured parsing of rclone's `--use-json-log`** — we parse
      plain stderr.
- [ ] **`rclone.conf` path is trusted as-is** — no sanitisation of
      `config.rcloneConfigPath`; a malicious config path could be
      exploited if unidrive is invoked with untrusted input.
- [ ] **No `rclone rc` (remote-control) mode** — one subprocess per
      operation means per-op JVM→process startup cost; for large syncs
      this is measurable.

## Priority for UD-228

**High for correctness, medium for performance.** The pipe-buffer
deadlock is a latent hang that will appear only under specific
(verbose-log × long-transfer × slow-consumer) conditions — hard to
reproduce, hard to diagnose. That is the single dangerous gap. Everything
else is degraded UX, not correctness.
