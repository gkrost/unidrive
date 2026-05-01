# Handover — Windows session 2026-05-01 → Linux laptop (sg5)

> Pick up here on sg5. Everything below this line is a brief; the
> repo is the source of truth.

## tl;dr

Long Windows session shipped 24 commits to `main` (now on `origin/main`):

- **Three "subagent chunk" bundles from the 2026-04-30 survey:** A (XS housekeeping), B (snapshot wrapper + diff-loop lift), C (OAuth callback server + `CredentialStore<T>`).
- **Two follow-up pairs from a UX feedback session:** UD-901 + UD-756 (status LOCAL column undercount), UD-352 + UD-757 (silent remote-scan progress).
- **Doc + tooling sweep:** SPECS / ARCHITECTURE / README drift fixes; `cli` category rebound to UD-400..499; CRLF/autocrlf lesson + memory update; `.gitattributes` blob normalisation for `gradlew.bat`.
- **Gradle 9.5.0 wrapper bump** merged from origin (dependabot commit `957e48a`); local build green at 1m 04s after the bump.

Tree is clean except for one local-only edit: `NOTICE` had three lines about "UniDrive is the project name. It is not a registered trademark in Germany..." removed. That's an editorial choice the maintainer made mid-session — not pushed yet, decide on Linux side whether to keep, refine, or revert.

## Commit list (newest → oldest, all on `main`, all pushed unless noted)

```
b81b06b  chore: normalise gradlew.bat blob to LF per .gitattributes
0209156  tooling: rebind UD-400..499 from shell-win to cli
d2ca037  backlog: close UD-757 (resolved_by 974fba7)
974fba7  feat(cli): UD-757 — render count + elapsed + ETA on Scanning... line
483e9e5  backlog: close UD-352 (resolved_by 690a4d4)
690a4d4  feat(sync,providers): UD-352 — provider remote-scan heartbeat
b7f64df  Merge branch 'main' (gradle 9.5.0 + security email merge)
481acdb  Correct security contact email formatting        (origin)
957e48a  chore(deps): Bump gradle-wrapper 9.4.1 → 9.5.0   (origin/dependabot)
f68a17d  backlog: close UD-756 (resolved_by 6bf9279)
6bf9279  feat(cli): UD-756 — split status LOCAL into HYDRATED + PENDING
fe842f9  backlog: close UD-901 (resolved_by 4f0f22c)
4f0f22c  feat(sync): UD-901 — LocalScanner writes pending-upload entry
40de11e  Merge branch 'main' (mid-session)
6df54f0  backlog: file UD-901 / UD-756 / UD-352 / UD-757
962f4c8  docs: sync README + ARCHITECTURE + SPECS to current repo state
3dbd874  docs(specs): sync SPECS.md to post-ADR-0011/0012/0013 reality
397c3d2  backlog: close UD-344 (resolved_by 6bcf534)
6bcf534  refactor(core,providers): UD-344 — lift CredentialStore<T>
c3de549  backlog: close UD-348 (resolved_by b8ea60f)
b8ea60f  refactor(core,providers): UD-348 — lift OAuth callback server
027117b  backlog: close UD-346 (resolved_by cf4ce36)
cf4ce36  refactor(sync,providers): UD-346 — lift snapshot diff loop
5604119  backlog: close UD-345 (resolved_by 94622af)
94622af  refactor(sync,providers): UD-345 — lift Snapshot<E> wrapper
fb8f300  backlog: close UD-350 / UD-751 / UD-752
6220e4f  refactor(sync,providers): UD-350 + UD-751 + UD-752 (XS bundle)
```

`git log --oneline 9aa9ea3..HEAD` will show the same list against the
greenfield init commit.

## What's in the four UX-driven tickets

### UD-901 (`feat(sync)`, code at `4f0f22c`)

`LocalScanner` upserts a pending-upload `state.db` row the moment it sees a NEW path:

```kotlin
SyncEntry(
    path, remoteId = null, remoteSize = 0, remoteModified = null,
    localMtime = attrs.lastModifiedTime().toMillis(),
    localSize = attrs.size(),
    isFolder = false, isPinned = false,
    isHydrated = true,                  // bytes are on disk
    lastSynced = Instant.EPOCH,         // never roundtripped
)
```

This closes the window where status read 0 B for files the user dropped into `sync_root` before any sync ran. Two `Reconciler.kt` adjustments came along (both follow the UD-225 synthesis precedent):

1. `localState=DELETED + entry.remoteId==null` → `RemoveEntry` (don't issue a doomed `DeleteRemote` on an entry that was never uploaded).
2. New synthesis loop after the existing UD-225 download-recovery loop: for `entry.remoteId=null + isHydrated=true + on-disk bytes`, synthesise an `Upload` action so interrupted uploads retry.

Acceptance bar (all 7 criteria from the ticket body): met.

### UD-756 (`feat(cli)`, code at `6bf9279`)

Status table `LOCAL` column split into `HYDRATED` + `PENDING`. **FILES column dropped** — would have pushed total width past ~118 chars; SPARSE + the size-bucket triple gives a more useful picture than a raw row count anyway. Final width ~110 chars. Decision documented in the commit body.

- HYDRATED = `entries.filter { isHydrated && remoteId != null }.sumOf { localSize }` (downloaded bytes)
- PENDING = `entries.filter { isHydrated && remoteId == null }.sumOf { localSize }` (awaiting upload)

### UD-352 (`feat(sync,providers)`, code at `690a4d4`)

`ScanHeartbeat` helper lifted from `LocalScanner`'s inline math (5k-items-or-10s gate). Mirrored into providers' `delta()` walks via Shape A: an optional `onPageProgress: ((Int) -> Unit)?` parameter on `CloudProvider.delta()`. Internxt / S3 / SFTP / WebDAV / HiDrive call it after each batch. OneDrive / Rclone don't (engine's outer page loop already ticks per `nextPage()`); LocalFs is fast enough not to need it.

`docs/ARCHITECTURE.md` "Shared cross-provider utilities" table updated.

### UD-757 (`feat(cli)`, code at `974fba7`)

`CliProgressReporter.onScanProgress` now renders count + elapsed + ETA. Output:

- With history present: `Scanning remote changes... 12,450 items · 0:18 · ETA 1:02`
- Without history: `Scanning remote changes... 12,450 items · 0:18`

ETA uses the bucket-based extrapolation already plumbed via `onScanHistoricalHint` / `onScanCountHint` (UD-747/UD-748). Folder/file split deferred — flagged in the ticket as out-of-scope-for-v1.

## Open follow-ups worth knowing about

From `docs/dev/handover/subagent-chunks-2026-04-30.md` ("Maybes"), still on the backlog and not auto-shippable without a small design call:

| Ticket | One-liner | What still needs deciding |
|---|---|---|
| UD-338 | Token-refresh-mutex pattern | Does `RefreshableCredentialStore<T>` own persistence or only orchestrate? |
| UD-341 | Streaming-download cipher-aware loop (Internxt) | `transform: (ByteArray, Int) -> Pair<ByteArray, Int>` or accept a `Cipher?` directly? |
| UD-339 | Retry-on-transient helper | Subsumed by UD-330; coordinate with that umbrella's intended shape first |
| UD-750 | OAuth stdout prompts → `AuthInteractor` | Define interface contract (browser-auth start/success, refresh-failure, console-echo warning) before lifting |
| UD-753 | Download-upload debug mirror | Per-line judgement call — best done by maintainer review |

From the 2026-05-01 ticket file, `UD-353` onward is open territory in `providers`; `UD-758` onward in `tooling`; `UD-902` onward in `experimental`. `UD-400` is the next free `cli` slot (rebound from `shell-win` this session).

There's a new lesson at `docs/dev/lessons/crlf-autocrlf-gitattributes.md` covering the Windows-side phantom-diff gotcha; not relevant on Linux, just noting for completeness.

## Linux-specific environment notes

- JDK 21 LTS is the toolchain (`jvmToolchain(21)`). Older note in `reference_sg5.md` mentioned JDK 25 — that's pre-ADR-0006 history; verify `java -version` reports 21+ before building.
- `gh` CLI is set up on sg5 per the older memory; the public repo is `gkrost/unidrive` (note: not `unidrive-cli`; the `-cli` form was used briefly in older docs and got fixed mid-session in commit `962f4c8`).
- The `core/docker/` test harness (`test-matrix.sh` localfs / `test-providers.sh` SFTP+WebDAV+S3+rclone / `test-mcp.sh`) should run on sg5 if you want to exercise the integration path. Last-known-passing: `8 + 20 + 9` tests across the three compose files. Not run on Windows this session because Docker Desktop wasn't available.

## What I'd do first on sg5

1. `git pull` — grab the 24-commit push.
2. `cd core && ./gradlew build` — confirm clean on Linux too. Should be quick (Gradle 9.5.0 wrapper, daemon will warm up the first time).
3. Decide on `NOTICE`: keep the trimmed three-line removal or restore the trademark-disclaimer block. The diff is local-only on the Windows machine; on sg5 the file currently still has the original three lines.
4. If you want progress on the "maybes" — UD-750 (`AuthInteractor`) is the highest-leverage one because it'd land the HiDrive token-refresh-failure surfacing that's currently a silent `println`. UD-338 is also a good target if you're thinking about token-mutex hygiene.

## What was *not* done this session

- No `unidrive-test-oauth` MCP tokens were minted; no live integration tests ran. `LiveGraphIntegrationTest` is `assumeTrue`-skipped without `UNIDRIVE_TEST_ACCESS_TOKEN`. If you want a confidence pass on the OneDrive provider, run that on sg5 with a fresh token.
- The maintainer's editorial NOTICE change (3 lines removed) was left for the user to review on the Linux side rather than committed by the agent.
- Pre-existing `core/settings-gradle.lockfile` "phantom modified" flag was resolved by `git checkout` and isn't in this push; it should be clean on Linux too.

## Pointers

- **Full session reasoning:** look at the commit messages — every one carries the WHY (especially `b81b06b` for the CRLF normalisation backstory and `0209156` for the cli-category rebind).
- **Backlog:** `docs/backlog/BACKLOG.md`, `docs/backlog/CLOSED.md`. `python scripts/dev/backlog.py show UD-XXX` is the read interface.
- **State of the spec:** `docs/SPECS.md` is post-ADR-0011/0012/0013 clean; doc-vs-code §9 table currently lists 5 known deltas.
- **State of the architecture narrative:** `docs/ARCHITECTURE.md` shared-utilities table is current through UD-345/346 + UD-352.
- **Reading order for a fresh-eyes catch-up:** CLAUDE.md → docs/dev/lessons/README.md → docs/SPECS.md §9 → docs/backlog/BACKLOG.md (filter `priority: high`).

That's it. The Windows machine handed off cleanly; nothing in flight.
