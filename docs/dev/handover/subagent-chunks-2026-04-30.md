# Subagent handoff chunks — 2026-04-30

Filtered from the 13 open lift tickets surfaced by the 2026-04-30
provider-duplication survey. Inclusion criterion (per maintainer):
**the more precise the UD, the more it's in.** That means:

- **IN** — XS / S, fully agent-able (`Y` in survey), well-cited
  file:line locations, contract fully specified in the ticket body,
  no design call needed.
- **MAYBE** — M with a fully-specified contract.
- **OUT** — `partial` agent-ability that hides a design decision,
  research items (UD-754, UD-755), brainstorms, anything touching
  user state-db, anything subsumed by a parent umbrella.

Tickets are intentionally bundled into chunks that share a module /
package destination, so each subagent run produces one focused PR.

---

## Chunk A — XS housekeeping bundle

**Tickets:** UD-350, UD-751, UD-752
**Effort:** ~1 hour total (3 × XS)
**Agent-ability:** Full
**Touches:** 4 provider services + 2 sync log call sites
**End state:** One commit on `main`, three closed tickets

### Subagent prompt

> You are picking up three pre-filed XS tickets from the 2026-04-30
> provider-duplication survey. All three are pure mechanical lifts;
> no design decisions; no contract calls.
>
> **UD-350 — Internxt-internal: extract `applyInternxtHeaders`**
> Four call sites in `core/providers/internxt/...` paste the same
> 4-header block (`internxt-client / internxt-version /
> x-internxt-desktop-header / accept(Json)`):
> - `InternxtApiService.kt:454-460` (`applyAuth` — already extracts
>   them but has a different `Authorization` scheme; preserve)
> - `InternxtApiService.kt:296-300, 313-317` (bridgeGet, bridgePost)
> - `AuthService.kt:103-104, 138-140` (login flow)
>
> Extract `private fun HttpRequestBuilder.applyInternxtHeaders()`.
> Replace the four paste sites. Keep `applyAuth`'s Bearer-token
> behaviour separate.
>
> **UD-751 — Move `Delta: N items` debug log to engine**
> Five providers each fire a `log.debug("Delta: {} items", ...)`
> after computing delta. Move the line to the `:app:sync` site that
> calls `provider.delta()` (read `DeltaPage.hasMore` from the page
> itself). Drop the per-provider lines:
> - `core/providers/webdav/.../WebDavProvider.kt:138`
> - `core/providers/s3/.../S3Provider.kt:176`
> - `core/providers/rclone/.../RcloneProvider.kt:76`
> - `core/providers/hidrive/.../HiDriveProvider.kt:119`
> - `core/providers/onedrive/.../GraphApiService.kt:172`
>
> The sync-side wrapper is in `SyncEngine.kt` — find the
> `provider.delta(cursor)` call and add the log there.
>
> **UD-752 — Move `Auth: ... authenticated` debug to engine**
> OneDrive and HiDrive each fire identical post-authenticate debug
> lines:
> - `core/providers/onedrive/.../OneDriveProvider.kt:55-59`
> - `core/providers/hidrive/.../HiDriveProvider.kt:38-42`
>
> Wrap the `CloudProvider.authenticate()` call in the engine, log
> there with `provider.id`. Drop the per-provider lines. Internxt
> /S3/SFTP/WebDAV/Rclone don't have these lines today; the engine
> wrapper means they get the log for free.
>
> **Workflow:**
> 1. Read each ticket via `python scripts/dev/backlog.py show UD-350`
>    (and UD-751, UD-752).
> 2. Implement all three.
> 3. Run `./gradlew :app:core:test :providers:onedrive:test
>    :providers:hidrive:test :providers:internxt:test
>    :providers:s3:test :providers:webdav:test
>    :providers:rclone:test :app:sync:test` from `core/`.
> 4. Run `KTLINT_SYNC_SKIP_PREFLIGHT=1 bash
>    scripts/dev/ktlint-sync.sh --module <m>` for each touched module.
> 5. Single commit with subject
>    `refactor(sync,providers): UD-350 + UD-751 + UD-752 — XS
>    housekeeping bundle`.
> 6. Close all three via `python scripts/dev/backlog.py close
>    UD-XXX --commit <sha>` then commit the BACKLOG/CLOSED move.
>
> **Reference patterns:** UD-343 + UD-347 + UD-351 bundle (commit
> `b3f5755`) is the exact precedent for "ship multiple XS lifts in
> one commit." Mirror its structure.

---

## Chunk B — Snapshot-storage pair

**Tickets:** UD-345 + UD-346
**Effort:** ~6 hours (S + M, paired)
**Agent-ability:** Full for UD-345; partial for UD-346 (per-provider
indexing varies subtly)
**Touches:** 6 providers' Snapshot data classes + 6 providers' delta()
**End state:** Two commits — one for the data-class lift (UD-345),
one for the diff-loop lift (UD-346) using the new shared types.

### Subagent prompt

> You are lifting the snapshot-cursor pattern — the highest-volume
> code duplication in the codebase by line count. Six providers each
> ship a `XxxSnapshotEntry` + `XxxSnapshot` pair with identical
> wrapping structure (entries Map + timestamp + Base64-of-JSON
> encode/decode). All six file paths in the ticket bodies.
>
> Read both tickets first:
> - `python scripts/dev/backlog.py show UD-345` — storage layer
> - `python scripts/dev/backlog.py show UD-346` — diff loop
>
> **Sequence:**
>
> 1. **UD-345 first.** Lift the wrapper to
>    `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/DeltaSnapshot.kt`:
>    ```kotlin
>    @Serializable
>    class Snapshot<E>(val entries: Map<String, E>, val timestamp: Long) {
>        fun encode(serializer: KSerializer<E>): String
>        companion object {
>            fun <E> decode(cursor: String, serializer: KSerializer<E>): Snapshot<E>
>        }
>    }
>    ```
>    Per-provider `XxxSnapshotEntry` types stay in their providers.
>    Round-trip test required.
>
> 2. **Verify the cursor format is on-disk-compatible** with the
>    six existing per-provider implementations. The Base64 of the
>    JSON string serialised by kotlinx-serialization must match
>    byte-for-byte; otherwise existing user state.db cursors break.
>    If it doesn't match, STOP — this needs design escalation.
>
> 3. Adopt in all 6 providers, drop the per-provider Snapshot files:
>    - `core/providers/hidrive/.../DeltaSnapshot.kt`
>    - `core/providers/s3/.../S3Snapshot.kt`
>    - `core/providers/sftp/.../SftpSnapshot.kt`
>    - `core/providers/webdav/.../WebDavSnapshot.kt`
>    - `core/providers/rclone/.../RcloneSnapshot.kt`
>    - `core/providers/localfs/.../LocalFsSnapshot.kt`
>
> 4. Commit + close UD-345.
>
> 5. **UD-346 next.** Lift the diff loop. Each provider's
>    `delta(cursor)` does ~30 lines of "build current + return-all-on-
>    null + diff loop." Reference: Rclone already has it in a
>    companion-fun `RcloneProvider.computeDelta` (lines 98-151) —
>    use as the starting shape. Lift to:
>    ```kotlin
>    fun <E> computeDelta(
>        currentEntries: Map<String, E>,
>        currentItems: List<CloudItem>,
>        prevCursor: String?,
>        hasChanged: (E, E) -> Boolean,
>        deletedItem: (path: String, entry: E) -> CloudItem,
>    ): DeltaPage
>    ```
>    in `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SnapshotDeltaEngine.kt`.
>
> 6. **Per-provider find-by-path lookup varies** — WebDAV uses
>    `it.path == path`, S3 uses `api.keyToPath(it.key) == path`,
>    Rclone uses `"/${it.path}" == path`. The shared helper takes
>    `currentItems: List<CloudItem>` AND `currentEntries: Map<String, E>`
>    so the provider pre-computes the indexing. Don't try to
>    abstract the lookup.
>
> 7. Adopt in all 6 providers' delta() implementations.
>
> 8. Each provider's delta() drops from ~50 lines to ~10. Round-trip
>    test fixture per provider (added/changed/deleted).
>
> 9. Second commit + close UD-346.
>
> **Workflow:** Per chunk-A footer, but TWO commits not one.
> Re-test all 6 providers + `:app:sync:test` after each.

---

## Chunk C — OAuth lift bundle

**Tickets:** UD-348 + UD-344
**Effort:** ~5 hours
**Agent-ability:** Full for UD-348 (already-pure helpers);
partial for UD-344 (CredentialStore<T> generics need a small
design call — but the contract is sketched in the body).
**Touches:** OneDrive + HiDrive `OAuthService`, Internxt `AuthService`
**End state:** Two commits, two closed tickets, both targeting
`core/app/core/src/main/kotlin/org/krost/unidrive/auth/`.

### Subagent prompt

> You are landing two related OAuth lifts. Both target the existing
> `:app:core/auth` package (where UD-351 `Pkce.kt` already lives).
>
> Read both tickets:
> - `python scripts/dev/backlog.py show UD-348`
> - `python scripts/dev/backlog.py show UD-344`
>
> **UD-348 — OAuth loopback callback server**
> OneDrive + HiDrive `TokenManager` each ship 35-line `waitForCallback`
> + 25-line `parseAndValidateCallback` + 17-line `openBrowser`.
> Lift to:
> - `core/app/core/.../auth/OAuthCallbackServer.kt` —
>   `awaitOAuthCallback(port, expectedState, providerLabel, timeout): String`
> - `core/app/core/.../io/OpenBrowser.kt` — pure utility
>
> Files (cited exactly):
> - `core/providers/onedrive/.../TokenManager.kt:157-191, 204-227, 234-250`
> - `core/providers/hidrive/.../TokenManager.kt:97-131, 144-167, 174-189`
>
> Difference between the two copies: success-page HTML text only.
> Parameterise on `providerLabel`. `parseAndValidateCallback` is
> already pure with great test coverage — port the tests into
> `:app:core` once.
>
> **UD-344 — Token-file load/save (CredentialStore<T>)**
> **NOTE: UD-347 already shipped (b3f5755)** —
> `setPosixPermissionsIfSupported` is already at
> `org.krost.unidrive.io`. Three providers consume it. So UD-344's
> remaining scope is: `CredentialStore<T>` lift + UD-312 atomic-
> write adoption in HiDrive + Internxt. The chmod helper is done.
>
> Sketch from the ticket body:
> ```kotlin
> class CredentialStore<T>(
>     private val dir: Path,
>     private val fileName: String,
>     private val serializer: KSerializer<T>,
> ) {
>     fun load(): T?
>     fun save(value: T)
>     fun delete()
> }
> ```
> with UD-312 atomic-move (write to `.tmp`, `Files.move(...
> ATOMIC_MOVE)`, fallback to non-atomic on
> `AtomicMoveNotSupportedException`) baked in. OneDrive's existing
> implementation at `OAuthService.kt:67-94` is the canonical
> pattern.
>
> Three providers:
> - OneDrive `OAuthService.loadToken()` / `saveToken()` — uses the
>   pattern already; refactor to consume `CredentialStore<Token>`
> - HiDrive `OAuthService.loadToken()` / `saveToken()` — currently
>   non-atomic; lifting fixes the UD-312 race
> - Internxt `AuthService.loadCredentials()` / `saveCredentials()` — same
>
> **Validation hook**: OneDrive has
> `parsed.hasPlausibleAccessTokenShape()` shape-guard on load
> (UD-312 lineage). The shared `CredentialStore` should accept an
> optional `validate: (T) -> Boolean = { true }` lambda so OneDrive
> wires its existing guard.
>
> **Workflow:**
> 1. UD-348 first (smaller, fully-mechanical). Commit + close.
> 2. UD-344 second (touches more files). Commit + close.
> 3. Test all three providers' OAuth happy-path AND validate-fails
>    path.
> 4. ktlint-sync each touched module.
>
> Same backlog-close + commit etiquette as Chunk A.

---

## Maybes — not in the auto-handoff set

These are PRECISE enough to ship soon but each one needs a small
design call before a fresh subagent can act:

- **UD-338 token-refresh-mutex-pattern** — M, partial. The agent
  survey called this "highest-leverage single lift" but the
  generic `RefreshableCredentialStore<T>` contract isn't fully
  specified. **Ask:** does the helper own persistence, or only
  orchestrate? Once decided, ships in ~1 day.

- **UD-341 streaming-download-loop** — M, partial. UD-349 already
  shipped the worst-case fix (S3+WebDAV Ktor 3.x trap). Remaining
  is the cipher-aware lambda contract for Internxt. **Ask:** is
  `transform: (ByteArray, Int) -> Pair<ByteArray, Int>` the right
  shape, or should we accept a `Cipher?` directly?

- **UD-339 retry-on-transient-helper** — Subsumed by UD-330. Don't
  ship as a standalone subagent task; coordinate with UD-330's
  intended shape first.

- **UD-750 oauth-stdout-prompts** — M, partial. The
  `AuthInteractor` interface contract needs a design pass:
  - browser-auth start (URL display)
  - browser-auth success
  - token-refresh failure (most important — HiDrive currently
    silently `println`s this; an actual UD-111 record should land)
  - console-echo warning
  Once the interface is defined, the lift is mechanical.

- **UD-753 download-upload-debug-mirror** — S, partial. Judgement
  call: which per-provider details are worth preserving (e.g.
  HiDrive's home-relative dir resolution) vs. which redundant.
  Best done by maintainer review of each line, not auto-handoff.

---

## Out of scope

- **UD-754** (auto-format research), **UD-755** (drift detection
  research) — research items, no implementation pass possible yet.
- **UD-330** (HttpRetryBudget cross-provider) — L, parent umbrella.
  Too big for one agent; needs decomposition first.
- **UD-739** (NFC normalization) — deferred per maintainer pending
  remote-bytes verification.
- **UD-275 / UD-281** (relocate memory leak / Ktor stream upload
  body) — multi-track investigation; needs maintainer JFR review.
- **UD-295** (state.db rename reuse) — touches user state-db,
  destructive on misfire; explicit human supervision required.
- **UD-744** ETA count probes — needs per-provider API research
  (Internxt has no obvious endpoint).

---

## Reference patterns for any subagent

- **UD-343 + UD-347 + UD-351 bundle** (`b3f5755`) — exact precedent
  for "ship 3 XS lifts in one commit + close all three at once."
- **UD-336** (`538592f`) — exact precedent for "lift error-body
  helpers from OneDrive to `:app:core/http`, adopt across HiDrive
  + Internxt." Same shape as Chunk B/C.
- **UD-342** (`f152787`) — exact precedent for "lift streaming
  pattern with safety-critical detail (UD-287 finally-flushAndClose)
  baked into the shared helper."
- **`scripts/dev/file_lift_findings.py`** — the bulk-filing script
  used to land the survey tickets. Subagents don't need to file
  new tickets; they just close existing ones.
- **`docs/dev/lessons/one-truth-sync-discipline.md`** — every
  subagent must update `docs/ARCHITECTURE.md`'s "Shared cross-
  provider utilities" table when adding a new shared helper.

## Per-subagent acceptance bar

Every chunk's subagent must, before opening a PR:
1. `./gradlew build` from `core/` BUILD SUCCESSFUL.
2. ktlint-sync clean on every touched module.
3. New shared helpers added to `docs/ARCHITECTURE.md` table.
4. Closed tickets via `python scripts/dev/backlog.py close UD-XXX
   --commit <sha> --note "..."`.
5. Backlog move committed separately from the code change.
6. Co-author trailer on every commit.
