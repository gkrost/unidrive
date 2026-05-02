# Subagent handoff chunks — 2026-05-02 (MDC follow-ups)

Filed after the JFR-driven UD-254a fix (`fix/UD-254a-mdc-clone-storm`,
commit `26164ae`). The narrow slice — drop `MDCContext()` from
`runBlocking` in `SyncCommand.kt:357` — is in. **Worker-thread log
lines now show `[-------]` in the `[profile]` and `[scan]` MDC slots**
because the propagation seam was deliberately removed. This file
holds the follow-up work: restore correlation via id-in-message
formatting, and (opportunistically) lift the per-operation debug
mirror lines to the engine.

Rules from [subagent-chunks-2026-04-30.md](subagent-chunks-2026-04-30.md)
apply: agent-able only if XS / S / M with a fully-specified contract
and well-cited file:line locations. Each chunk produces one focused
PR/branch with one or two commits.

Reference patterns:

- [docs/dev/lessons/mdc-in-suspend.md](../lessons/mdc-in-suspend.md) — the canonical guidance that
  motivated UD-254a. *"If it's a cross-coroutine correlation … put
  the id in the log MESSAGE, not MDC."*
- [core/app/core/src/main/kotlin/org/krost/unidrive/http/RequestIdPlugin.kt](../../../core/app/core/src/main/kotlin/org/krost/unidrive/http/RequestIdPlugin.kt) — the existing
  precedent: `RequestId` close lines emit `req=<8-char-hex>` inline,
  no MDC. Mirror this shape exactly for `scan=<id>`.

---

## Chunk D — id-in-message migration on Pass-2 apply path

**Tickets:** UD-254a (Slice B) + UD-753 (paired)
**Effort:** ~3-4 hours (M for UD-254a Slice B, S for UD-753, paired
because they touch the same files)
**Agent-ability:** Full
**Touches:** `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt`
plus 4 provider services (drop per-op debug mirrors)
**End state:** Two commits — one for the engine-side decorator log
lift (UD-753), one for id-in-message format on remaining apply-path
log sites (UD-254a Slice B). Both close their tickets with
`backlog.py close UD-XXX --commit <sha>`.

### Context — why this chunk exists

JFR on the running daemon (60 s, 2026-05-02 19:27, [docs/backlog/BACKLOG.md UD-254a](../../backlog/BACKLOG.md))
showed `LogbackMDCAdapter.getPropertyMap` accounting for **80.63 % of
all allocation pressure**. Stack: `kotlinx.coroutines.slf4j.MDCContext.updateThreadContext`
cloning the MDC HashMap on every coroutine resume — two HashMap
allocations per dispatch, fired every time a Pass-2 worker suspended
through Ktor's HTTP/2 stack.

The narrow fix on `fix/UD-254a-mdc-clone-storm` (commit `26164ae`)
dropped `MDCContext()` from the single hot-path `runBlocking` in
[`SyncCommand.kt:357`](../../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt). The user-facing trade is documented in the
ticket body and the commit message: worker log lines lose
`[profile] [scan]` MDC interpolation; banner lines on the synchronous
main thread keep theirs.

This chunk **restores cross-thread correlation by inline message
format** — `scan=<id> profile=<name>` appended to log calls that
fire from the apply path. Grep is unchanged: `grep "scan=<id>"
unidrive.log` still slices a single sync pass.

### Subagent prompt

> You are landing two paired tickets that follow up the UD-254a
> narrow fix already merged on the parent branch
> `fix/UD-254a-mdc-clone-storm`. Your branch should be
> `fix/UD-254a-slice-b-id-in-message` stacked on top.
>
> Read the prerequisites:
> - `python scripts/dev/backlog.py show UD-254a`
> - `python scripts/dev/backlog.py show UD-753`
> - `docs/dev/lessons/mdc-in-suspend.md` (one-page lesson)
> - `core/app/core/src/main/kotlin/org/krost/unidrive/http/RequestIdPlugin.kt`
>   (existing id-in-message precedent — `req=<id>` is the canonical
>   shape)
> - `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt`
>   lines 738-940 (the `applyXxx` family — the apply path that now
>   runs MDC-empty)
>
> **Rules of engagement:**
>
> - **Do NOT add `MDCContext()` back anywhere on the sync hot path.** That
>   propagation is what UD-254a's narrow fix removed; restoring it
>   re-introduces the 80 % allocation pressure. The entire premise
>   of this chunk is "id-in-message instead of MDC propagation."
> - **Do NOT add `MDC.put`/`MDC.remove` calls inside `applyXxx`.** They
>   would fire on a worker thread where MDC is empty; the put would
>   stick only until the next suspension. Per the lessons file,
>   "MDC inside a suspend function" is a known trap.
> - The `scanId` field is generated in `SyncEngine.syncOnce` at
>   [SyncEngine.kt:85-89](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt) and currently passed to `doSyncOnce` as
>   `@Suppress("UNUSED_PARAMETER")` (line 110). Drop the suppress
>   annotation; thread `scanId: String` through the apply path.
> - The `providerId` field already exists on `SyncEngine` as a
>   constructor param (line 40). Use that for `provider=<id>`
>   correlation when needed.
>
> **Sequence:**
>
> ### Step 1 — UD-753 first (decorator lift, 5 file edits)
>
> The provider per-op debug lines in `UD-753`'s ticket body are
> redundant after the lift. Drop these:
>
> - `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:207, 432, 451, 487`
> - `core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt:111, 176, 216, 252, 277, 301`
> - `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt:52, 85, 133, 161`
> - `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt:294, 349, 418, 439, 460`
>
> Replace with engine-side logging in each `applyXxx` in
> `SyncEngine.kt` — wrap the actual `provider.upload(...)` /
> `provider.download(...)` / etc. calls. The single canonical place
> to log is around the call site at [SyncEngine.kt:762, 783, 804, ~830, ~860, ~895, ~920](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt).
>
> Format (UD-753 + UD-254a Slice B combined):
>
> ```kotlin
> log.debug(
>     "Upload {} ({} bytes) provider={} scan={} path={}",
>     action.path, localSize, providerId, scanId, action.path,
> )
> ```
>
> Use SLF4J `{}` placeholders (no string interpolation; project
> convention).
>
> Internxt's `Scanning files: 50` style lines in
> `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt` are
> NOT in scope — they fire from `delta()` (the gather phase, on main
> thread) and still inherit MDC correctly. Leave them alone.
>
> Commit with subject:
> `refactor(sync): UD-753 — lift per-op Download/Upload/Delete/Move debug to engine apply path`
>
> ### Step 2 — UD-254a Slice B (id-in-message on remaining apply-path lines)
>
> Audit the rest of `SyncEngine.kt` for `log.warn` / `log.error`
> calls inside any `applyXxx` body or the `coroutineScope { launch }`
> block that wraps them ([SyncEngine.kt:336-450](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt) area). Examples
> already in the file:
>
> - [SyncEngine.kt:411](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt) `log.error(...)` — already uses `e.javaClass.simpleName, e.message` inline; just append `scan={}` and `provider={}`.
> - [SyncEngine.kt:460](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt), 476, 506, 520 — same shape.
> - [SyncEngine.kt:608, 613](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt), 622 — `log.warn` inside the action loop.
> - [SyncEngine.kt:921](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt) `log.debug("DeleteRemote skipped for ${action.path}: …")` — change to `log.debug("DeleteRemote skipped path={} scan={} reason={}", …)`.
>
> Pattern (consistent with `RequestIdPlugin`'s `req=<id>` shape):
> ```
> scan=<8-char-hex> provider=<provider-id> path=<path>
> ```
>
> No MDC reads; values come from in-scope variables or are threaded
> in via parameter / class field.
>
> The IPC server's accept loop ([core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt:72](../../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt)) is
> a separate worker-thread log site. It already has `profileName`
> on the `IpcServer` instance — append `profile={}` to its log
> lines. No `scan` correlation needed there (IpcServer is process-
> wide, not per-sync).
>
> Commit with subject:
> `fix(UD-254a): id-in-message scan/profile on apply-path log sites — Slice B`
>
> ### Step 3 — verify
>
> 1. `cd core && ./gradlew build` — must be green (ktlint is
>    disabled per UD-774, no extra setup).
> 2. Spot-check one log line in the working tree's `unidrive.log`
>    after a manual `./gradlew :app:cli:deploy` and a 5-file sync
>    (any provider with credentials configured). The Pass-2 lines
>    should now read:
>    ```
>    DEBUG ... [-------] [DefaultDispatcher-worker-3] o.k.unidrive.sync.SyncEngine - Upload /foo/bar.txt (1234 bytes) provider=onedrive scan=a3f2b9d1
>    ```
>    Note `[-------]` in the MDC slot is **expected and correct** —
>    the inline `scan=a3f2b9d1` is the new correlation seam.
>
> 3. Re-record JFR with `bash scripts/dev/unidrive-jfr.sh` during a
>    short sync pass. Run `jfr.exe view allocation-by-class
>    flight_recording_*.jfr`. **Acceptance:** `byte[]` is the top
>    allocation source (Internxt encrypt + S3 PUT — intrinsic).
>    `LogbackMDCAdapter.getPropertyMap` is gone or below 5 %.
>
> ### Close + commit etiquette
>
> Per [docs/AGENT-SYNC.md](../../AGENT-SYNC.md): close each ticket via
> `python scripts/dev/backlog.py close UD-XXX --commit <sha>` then
> commit the BACKLOG → CLOSED move separately from the code commit.
> So the branch ends with FOUR commits in order:
>
> 1. code: `refactor(sync): UD-753 …`
> 2. code: `fix(UD-254a): … Slice B`
> 3. backlog: `docs(backlog): close UD-753 …`
> 4. backlog: `docs(backlog): close UD-254a …`

### Notes for the maintainer (optional reading)

- **UD-275** ("relocate JVM memory grows ~630 KiB/file") is a
  candidate accomplice of the same MDC-clone storm. Worth a JFR
  re-record on the relocate path AFTER this chunk lands — the
  RelocateCommand still uses `runBlocking(MDCContext())` (UD-294,
  intentional) and allocates 4 k uploads' worth of MDC clones.
  That recording may either confirm UD-275 is the same root cause
  (in which case file UD-294a) or rule it out.
- **UD-281** ("right-size heap + stream Ktor upload body") shares
  the GC-pressure framing. After this chunk lands, re-record JFR
  for `byte[]` allocation-by-site to give UD-281 a clean baseline
  not contaminated by HashMap noise.
- **UD-247** (cross-provider benchmark harness) wants stable
  baselines. Land this chunk first so the harness measures the
  post-Slice-B shape.

---

## Maybes — partial agent-ability

- **UD-294 (relocate-tool MDCContext)** — the MCP relocate path
  still installs MDCContext. Same allocation pattern, lower
  frequency (one-shot, not per-sync-loop). Could file as
  UD-294a sibling to UD-254a once the JFR confirms the relocate
  hot path. **Ask:** is relocate frequent enough on the projected
  workload to warrant the same treatment? If `unidrive relocate`
  is rare, leave UD-294 in place for log-correlation value.
- **UD-113 (structured sync-action audit log)** — broader audit
  log concern. The id-in-message format from this chunk would be
  the substrate. Coordinate naming once UD-113 has a contract.

---

## Out of scope

- **Replacing UD-754's whole-codebase MDC removal** — too wide.
  This chunk is bounded to the apply path + IPC server. Other MDC
  consumers (RelocateCommand, RelocateTool, watch-loop banners) keep
  MDC for now.
- **Reintroducing `MDCContext()` anywhere** — explicitly forbidden
  per the chunk rules above. The 80 % allocation finding is the
  whole point.
- **Refactoring logback.xml's pattern** to drop `[%X{profile}]` /
  `[%X{scan}]` slots. Leaving them as `[-------]` placeholders
  preserves grep tooling that expects those columns.
