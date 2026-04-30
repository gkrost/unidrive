# Backlog

> Open items only. Closed items live in [CLOSED.md](CLOSED.md) (append-only). See [AGENT-SYNC.md](../AGENT-SYNC.md) for editing rules.

Each item is a frontmatter block + prose. Required fields: `id`, `title`, `category`, `priority`, `effort`, `status`, `opened`. IDs follow the range table in AGENT-SYNC.md and never reuse.

---

## Architecture / structural (UD-001..099)

---
id: UD-001
title: Monorepo consolidation
category: architecture
priority: high
effort: L
status: in-progress
code_refs:
  - settings.gradle.kts
  - CMakeLists.txt
  - README.md
adr_refs: [ADR-0001, ADR-0002]
opened: 2026-04-17
milestone: v0.1.0
---
Consolidate the three sibling repos into a single monorepo at `greenfield/unidrive/`. Scaffold complete; pending first green build and tag. Acceptance: `./gradlew build` at root builds `core/` and `ui/` via composite; `cmake --build` builds `shell-win/`.

---

## Security (UD-100..199)

---
id: UD-113
title: Structured sync-action audit log (durable record of every local/remote mutation)
category: security
priority: low
effort: M
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
adr_refs: [ADR-0004]
opened: 2026-04-17
---
Surfaced during the UD-112 STRIDE review. The v0.0.0 SECURITY.md seed claimed UD-111 covers "structured audit log per action" — but UD-111 is about token-refresh failure telemetry only, not a per-action audit trail. The STRIDE R-row (Repudiation) therefore has a gap: the sync engine emits WARN/INFO logs but nothing durable enough to prove "this run deleted X at Y on behalf of Z". Scope: an append-only JSONL log with `{ts, action, path, size, oldHash, newHash, result, profile}` per action, rotated daily, surfaced via MCP `unidrive_audit` tool (new). Acceptance: run `unidrive -p x sync` on a non-trivial change, observe `{profile}/audit.jsonl` grows with one entry per action. Deferred past v0.1.0 unless compliance need arises.

---

## Core daemon (UD-200..299)

---
id: UD-201
title: Complete NamedPipeServer hydration handler
category: core
priority: high
effort: L
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt
adr_refs: [ADR-0003]
opened: 2026-04-17
milestone: v0.3.0
chunk: xpb
---
TODOs at lines ~100–110: wire `fetch` → `SyncEngine.downloadFile(path)` + temp file write; wire `dehydrate` → CfDehydratePlaceholder via shell DLL; wire `unregister` → CfUnregisterSyncRoot. Gates v0.3.0 shell release.

---
id: UD-205
title: Peer-review — atomicity across sync transfer phases (Gemma, needs-verification)
category: core
priority: high
effort: M
status: needs-verification
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt
adr_refs: []
opened: 2026-04-17
---
Peer-review (local Gemma) claim: `SyncEngine` wraps its metadata actions in a `db.beginBatch()` / `commitBatch()` but runs concurrent transfers (Pass 2) **outside** that batch. **Spot-check (2026-04-17):** structurally confirmed at `SyncEngine.kt:172-218` (sequential batch) vs `:220-277` (`coroutineScope { launch { ... applyDownload/applyUpload ... } }` outside batch). However the peer's "dirty reads / phantom updates" framing overreaches: SQLite single-row writes are atomic and `ProcessLock` prevents overlapping sync runs, so concurrent row updates on *different* paths are safe. The real risk is **interruption recovery** — if Pass-2 is killed mid-transfer, state DB and local files diverge. Acceptance: audit resume logic (`Reconciler` + placeholder re-entry) against a SIGKILL-during-transfer test; decide whether a cross-phase transaction or a transfer-state marker column is warranted.

---
id: UD-206
title: Delete-recreate corner case on providers without server delta (refined Gemma claim)
category: providers
priority: low
effort: M
status: open
code_refs:
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Snapshot.kt:14
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpSnapshot.kt:13
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavSnapshot.kt:13
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:34
adr_refs: [ADR-0005]
opened: 2026-04-17
---
Spot-check (2026-04-17) refined the original peer-review claim. Change detection in `Reconciler.kt:34-35` compares **both** `hash` AND `modified`, not metadata alone — so Gemma's "size+mtime collision" framing is too strong. The real corner case: SFTP/WebDAV without server-side ETags, file deleted and recreated within the same OS-mtime tick with identical size, where there is also no content hash available. Rare in practice. Acceptance: add a "same-second delete+recreate" integration test fixture for SFTP; if the test can force the collision, add a defensive content-hash check on suspicion. Otherwise close as unreachable.

---
id: UD-208
title: Peer-review — SyncAction refactor to type + payload split (Gemma, deferred)
category: core
priority: low
effort: M
status: deferred
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync
adr_refs: []
opened: 2026-04-17
---
Peer-review structural suggestion: split `SyncAction` (sealed class today) into `SyncActionType` enum + `SyncActionPayload` data classes to shrink the `when` block in `SyncEngine`. **Deferred** because refactor-for-structure without a concrete bug-motivation violates the "don't refactor speculatively" guidance; reopen if `SyncEngine`'s action-dispatch becomes a maintenance bottleneck.

---
id: UD-209
title: Windows sparse-file support for placeholders (PlaceholderManager)
category: core
priority: medium
effort: L
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/PlaceholderManager.kt:144
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/PlaceholderManager.kt:85
adr_refs: [ADR-0004]
opened: 2026-04-17
---
`PlaceholderManager.isSparse()` short-circuits to `false` on Windows (line 147) because it detects sparseness via POSIX `stat --format=%b`. On NTFS, sparse files exist but require `FSCTL_SET_SPARSE` to mark and a different query path. Additionally, `createPlaceholder`/`dehydrate` use `RandomAccessFile.setLength()` which on Windows allocates real bytes, not holes — so placeholders aren't actually sparse on Windows. Four tests are gated with `assumeFalse(IS_WINDOWS)` (PlaceholderManagerTest x2, SyncCornerCaseTest x2) until this lands. Acceptance: placeholders created on Windows consume < 64 KiB on-disk regardless of logical size; `isSparse` returns true for them; gated tests unskip.

---

## Providers (UD-300..399)

---
id: UD-302
title: deltaWithShared gap closure (S3, SFTP, Rclone, LocalFS, WebDAV, HiDrive, Internxt)
category: providers
priority: medium
effort: L
status: open
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
  - core/providers
adr_refs: [ADR-0005]
opened: 2026-04-17
chunk: sg5
---
All non-OneDrive providers silently return empty. Either implement a meaningful `deltaWithShared()` or declare `Unsupported` per UD-301.

---
id: UD-305
title: SFTP host-key selection brittle with multi-algorithm known_hosts (MINA)
category: providers
priority: medium
effort: M
status: open
code_refs:
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt:90
opened: 2026-04-17
chunk: sg5
---
Surfaced during the 2026-04-17 e2e test drive against sg5 (192.168.0.63) and krost.org:22222. When `~/.ssh/known_hosts` contains multiple entries for the same host (e.g. `ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`), `KnownHostsServerKeyVerifier` matches the **first** entry by host only and — if the server then presents a different algorithm during KEX — treats it as a *modified* key and rejects with `SshException: Server key did not validate`. OpenSSH's verifier iterates all matching entries and accepts any algorithm match; MINA's does not. Symptom: `Not authenticated to SFTP` on a host the operator can ssh into interactively one second earlier. Workaround used during the test drive: keep only the `ecdsa-sha2-nistp256` entry for affected hosts. Acceptance options: (a) configure `client.serverKeyVerifier` with an `AggregateServerKeyVerifier` that wraps per-algorithm `KnownHostsServerKeyVerifier` instances, or (b) pre-filter `known_hosts` to keep only the server's actually-negotiated algorithm on first connect (TOFU style), or (c) expose a `host_key_algorithms` knob in the SFTP profile config and pass it to MINA's `HostKeyAlgorithms`. Option (a) is the most faithful to OpenSSH semantics. Add regression test using a stub SSH server that offers ecdsa while known_hosts lists ed25519+ecdsa.

---

## shell-win / CFAPI (UD-400..499)

---

## UI / tray (UD-500..599)

---
id: UD-504
title: Tray icon / status surface — evaluation + requirements doc + polish
category: ui
priority: medium
effort: M
status: open
code_refs:
  - ui/src/main/kotlin/org/krost/unidrive/ui/TrayManager.kt
opened: 2026-04-19
chunk: ipc-ui
---
**First field-observation (2026-04-19, during UD-222 live run):** when the download process died with an auth failure (UD-310), the tray surfaced the error end-to-end for the first time. The good:
  - Red dot icon switched on (first time user has seen the red state in the field).
  - Popup showed "onedrive" + truncated error message ("Authentication failed (401): {\"error\":{\"co...").
  - Menu item mirrored the same truncated error.
  - Menu had a "Sync now: onedrive" + "Open folder: onedrive" pair next to the failing profile, plus a generic "Open sync folder" / "View log" / "Quit" block.

What worked. What needs polish:
  1. **Time lag.** Red state arrived with visible latency after the CLI had already logged the 401. Measure: how is the tray learning of the state change (polling state.db? IPC event?) and what's its tick rate? Document the expected latency upper bound (e.g. "within 5 s of a daemon-side state change" — requires the daemon to push over pipe/UDS, not just the tray polling the DB).
  2. **Truncated error body is hostile.** The popup shows ~60 chars of raw Graph JSON ("Authentication failed (401): {\"error\":{\"co..."). A human-first message ("Microsoft OneDrive sign-in expired — click to re-authenticate") would ship more signal in fewer pixels. Raw details should move behind an expand/details click.
  3. **Multi-account UX is ambiguous.** The screenshot shows only ONE profile ("onedrive") despite 4 being configured; on single-error the menu shows "Sync now: onedrive" specifically. Does the tray already aggregate per-profile status? Or does it pick one profile and rely on UD-213 to fix the multi-account layout? Cross-reference with UD-213 (tray layout), UD-221 (Open folder picks arbitrary profile), UD-218 (status --all multi-account — now closed). A single source of truth for "what the tray shows" is needed.
  4. **Icon state vocabulary is undefined.** Red means "one or more profiles are in error" — but what distinguishes (a) auth expired, (b) network offline, (c) disk full, (d) sync conflict pending? Today's binary ok/error is the minimum viable. A third state (yellow/warning for "degraded but making progress" — e.g. some files failed UD-309 mismatch but sync continues) would match the mental model users already have from Dropbox / OneDrive's own tray.
  5. **Resolution affordance.** Menu shows the error but no one-click action to resolve. Ideally: red + auth error → "Re-authenticate onedrive…" menu item that runs `unidrive -p onedrive auth` and closes on success. "Quit" next to a half-broken sync is the wrong default.

**Deliverables for this ticket:**
  1. `docs/ui/tray-states.md` — state diagram mapping (profile status) → (icon colour, popup wording, menu items, recommended user action). Start from observed states in field use + the scenarios above; document the latency contract.
  2. `docs/ui/tray-popup-wireframes.md` (see UD-216) — 3 alternative layouts for N providers × M accounts, informed by (3).
  3. Polish pass on TrayManager.kt after docs are signed off: human-first error messages, multi-account layout, "Re-authenticate…" affordance, latency instrumentation (log the daemon→tray propagation delay so we can measure).

Related: UD-213 (multi-provider tray layout), UD-216 (tray wireframes companion), UD-221 (folder-picker bug), UD-111 (token refresh telemetry — tray should show this), UD-310 (the auth failure that surfaced this ticket), UD-222 (the sync run that produced the auth failure).

---

## Protocol / IPC (UD-600..699)

---

## Tooling / CI / docs (UD-700..799)

---
id: UD-702
title: Composite Gradle build root
category: tooling
priority: high
effort: S
status: in-progress
code_refs:
  - settings.gradle.kts
  - ui/build.gradle.kts
  - core/build.gradle.kts
adr_refs: [ADR-0006]
opened: 2026-04-17
milestone: v0.1.0
---
Root `settings.gradle.kts` composite scaffold landed; JVM unification to 21 LTS landed. Pending: verify `./gradlew build` from root green.

---
id: UD-801
title: Core test coverage ≥ 60% for v0.1.0 scope
category: tests
priority: high
effort: L
status: open
code_refs:
  - core/app/sync
  - core/app/mcp
adr_refs: []
opened: 2026-04-17
milestone: v0.1.0
---
Imported coverage is ~39% file ratio. Prioritize MCP roundtrip (UD-202), sync reconciler corner cases, provider adapters for `localfs + s3 + sftp`. Uses existing JaCoCo wiring in `core/build.gradle.kts`.

---
id: UD-229
title: Research — Microsoft Graph API feature table: what unidrive uses vs ignores
category: docs
priority: low
effort: M
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive
status: open
opened: 2026-04-19
---
Produce two tables in `docs/providers/onedrive-graph-coverage.md`:

**Table A — Top 10 Graph features unidrive DOES use** — e.g. `/me/drive/root/delta` (delta enum), `/me/drive/items/{id}/content` (download), upload session, webhook subscriptions, `/createLink` sharing, etc. Per row: endpoint, why unidrive calls it, file:line of the caller, known quirks (UD-307-style ZWJ rejections, CDN 429 patterns, Retry-After header habits).

**Table B — Top 10 Graph features unidrive DOES NOT use** — candidates worth evaluating for v0.2.0+. Ideas:
  * **Drive-level search** (`/me/drive/search(q='...')`) — would let `unidrive search` skip full enumeration.
  * **Item preview** (`/items/{id}/preview`) — tray hover thumbnails / MCP previews.
  * **OneNote scope** — currently invisible to unidrive.
  * **Shared items `/me/drive/sharedWithMe`** — partially wired via UD-301 deltaWithShared, but explicit listings would help UX.
  * **Sensitivity labels / retention** — compliance features relevant for enterprise pitches.
  * **Thumbnail sizes** — cheaper than download for tray previews.
  * **Item versioning** `/items/{id}/versions` — unidrive has its own `.unidrive-versions/` scheme; overlap.
  * **Long-running operations** (`/async-monitor`) — we probably want these for large moves.
  * **Audit logs** (enterprise tenants) — relevant for UD-113 audit trail.
  * **Batch requests** (`/$batch`) — up to 20 ops in one HTTP call, reduces throttle pressure.

Each row: endpoint, what it does, cost (RPS impact, throttle class), unidrive-side integration cost (S/M/L), priority.

Companion cross-reference to ADR-0005 (Provider capability contract).

---
id: UD-230
title: Research — cross-provider client-implementation recommendations
category: docs
priority: low
effort: L
code_refs:
  - core/providers
status: open
opened: 2026-04-19
---
Companion to UD-229 for every non-OneDrive provider in the tree. Per provider, produce a short doc `docs/providers/<id>-client-notes.md` that captures what the provider's own docs recommend for a well-behaved client:

  * **Amazon S3** — SDK retry config, exponential-backoff-with-jitter standard, multipart vs single PUT thresholds, `SlowDown`/503 handling, request-rate prefixing guidance.
  * **WebDAV (Nextcloud / ownCloud / bytemark / generic)** — `DAV:` depth header etiquette, LOCK/UNLOCK interplay, 507 Insufficient Storage handling, propfind pagination.
  * **SFTP (OpenSSH / MINA / Synology DSM)** — concurrent channels per session, keepalive, known_hosts trust-first-use, algorithm negotiation (UD-305).
  * **HiDrive (Strato)** — their throttle and retry conventions, OAuth2 quirks, WebDAV compatibility mode.
  * **Internxt (Inxt)** — documented API rate limits, bucket-limit vs account-limit, 2FA implications on auth flow.
  * **Rclone (library mode)** — flags unidrive should set/avoid, bandwidth limiter interplay, chunk-size tuning.
  * **Dropbox / Google Drive** (future providers) — preliminary docs so we don't start blind when adding them.

Each doc ends with a concrete "what unidrive should do but doesn't yet" list that becomes input to UD-228.

**Related:** UD-207, UD-227, UD-228, UD-229, UD-305 (SFTP host-key), UD-301 (deltaWithShared gap), ADR-0005 (capability contract).

---
id: UD-233
title: Brainstorm — tray popup + desktop sync window UX (multi-account, activity, actions)
category: ui
priority: medium
effort: M
status: open
code_refs:
  - ui/src/main/kotlin/org/krost/unidrive/ui/TrayManager.kt
opened: 2026-04-19
chunk: ipc-ui
---
The current tray popup is functional but minimal: icon (static), profile name, sync status string, and 4 menu items (Sync now / Open folder / View log / Quit). With the full product vision (N providers × M accounts, real-time sync activity, conflict resolution, quota display) the current shape doesn't scale. This ticket is a design brainstorm and wireframe doc before UD-213 / UD-217 implement anything.

## What the popup should surface

**Minimum useful state (every account, at a glance):**
  * Provider icon + account display name / UPN
  * Sync state: idle / scanning / downloading N of M / uploading / error / throttled
  * Last sync timestamp ("2 min ago") — already partly in UD-214
  * Quota bar: used / total with colour (green → amber → red at 80 / 95 %)
  * A single action button per account: "Sync now" if idle, "View errors" if failed, "Re-auth" if token expired

**Nice to have:**
  * Transfer rate + ETA during active sync (feeds from `onTransferProgress` / `onActionProgress`)
  * Throttle indicator: show when unidrive is in backoff ("Waiting — MS throttle, 47 s")  — connects to UD-232
  * Conflict badge: "3 conflicts" with one-click to open resolution UI
  * Per-file activity ticker: last 3 files transferred (like a mini log)

## Desktop sync window (separate window, not tray popup)

Rationale: the tray popup is inherently transient (closes on click-away). Operators running a 130 k-file initial sync need a persistent window they can leave open and check back on. Options:

  1. **Borderless overlay** (à la Dropbox): always-on-top, docked to screen edge, auto-hides when not in focus. Shows live transfer list + quota + speed graph.
  2. **Regular JFrame window** ("UniDrive Activity"): standard title bar, minimisable. Easier to implement (Swing), less polished.
  3. **System notification + detail sheet**: tray popup triggers a native Windows notification; click opens a separate detail JFrame. Native feel, but two-step UX.

Recommendation before committing: wireframe all three and show to at least one non-technical user.

## What actions should be reachable

From popup (fast, one or two clicks):
  * Sync now / Pause sync / Resume
  * Open sync folder (per-account)
  * Open log file
  * Re-authenticate (triggers device-code flow — see UD-216)
  * Quit (with "sync in progress" warning if active)

From desktop window (deeper):
  * Per-file conflict resolution (show local vs remote, pick winner)
  * Sweep --null-bytes trigger (UD-226) with progress display
  * Quota detail: breakdown by folder
  * Transfer history: last N completed files with size, duration, speed
  * Error list: permanent failures with per-file retry button

## Icon states (UD-889 / UD-221 adjacent)

4 minimum: `idle` (static), `busy` (animated spin or pulse), `error` (red badge), `throttled` (amber hourglass). The throttled state is new and specifically useful given UD-232 — user sees "why is it slow?" without opening the log.

## Questions to decide before implementation

  1. One popup for all accounts or tabbed/scrollable list?
  2. Does "Sync now" sync ALL accounts or just the focused one?
  3. Should the desktop window be opt-in (setting) or always available?
  4. Windows 11 tray behaviour: does the icon appear in the overflow chevron by default? Should unidrive pin itself?
  5. Dark mode / light mode — Swing doesn't auto-follow; need FlatLaf or manual theme wiring.
  6. Accessibility: keyboard navigation for popup (Tab / Enter / Escape)?

**Related:** UD-213 (tray layout), UD-215 (activity feed), UD-217 (wireframe doc), UD-221 (open-folder bug), UD-214 (last-sync timestamp), UD-216 (auth MCP/CLI gap), UD-232 (throttle state → UI indicator).

---

---

---

---
id: UD-713
title: First-sync ETA probe + progress output
category: tooling
priority: medium
effort: M
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
opened: 2026-04-18
---
User-reported during UD-712: first-ever sync (or sync after state reset) against a large OneDrive can take "unknown time" — silent for many minutes while the engine walks the Graph Delta API. Rough-bucket ETA (`<5m`, `5-15m`, `15-60m`, `>1h`) is enough; exact numbers not required. Probe direction before starting the real scan:
  1. TTFB measurement of a Graph metadata call.
  2. Optional upload + download sample (~16 KB throwaway) for bandwidth signal — gated on `--estimate` flag since it touches remote.
  3. If provider exposes `about.driveItemCount` or equivalent, use it; otherwise scale from `quota.used` plus `/me/drive/root/children/$count`.
  4. Historical cursor-complete timings persisted in `state.db` for subsequent syncs.
Present the estimated bucket + a live "scanning: N items seen, Mt elapsed" tick line during the scan. Tie UX into UD-212 (log context) so scan-in-progress is visible from `unidrive log` and the tray.

---
id: UD-804
title: Delete propagation + bidirectional round-trip in docker provider harness
category: tests
priority: medium
effort: M
status: open
code_refs:
  - core/docker/test-providers.sh
  - core/docker/docker-compose.providers.yml
opened: 2026-04-18
chunk: sg5
---
Highest-leverage next gap after UD-803's upload-only round-trip. For each of {sftp, webdav, s3, rclone}: (a) upload, verify via side-channel; (b) delete locally, sync, verify absence via side-channel (delete propagation); (c) edit local, edit remote, sync bidirectional, assert conflict resolution follows `[general].conflict_policy`. `delete` is base-contract (CloudProvider.kt:32) so this covers half of the capability-contract audit in one pass.

---
id: UD-805
title: Capability contract round-trips — move, mkdir, share/listShares/revokeShare, webhook, verifyItemExists, deltaWithShared
category: tests
priority: medium
effort: L
status: open
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
  - core/docker/test-providers.sh
opened: 2026-04-18
chunk: sg5
---
ADR-0005 defines 8 capability cases; the current harness only exercises upload/download/delete. For each adapter, drive each capability via the MCP or CLI surface and verify via side-channel where applicable. Share/webhook require provider-specific fixtures (share link retrieval, webhook delivery endpoint); use the MinIO/SFTP servers already in compose where supported, skip gracefully (`assumeTrue`) where not.

---
id: UD-806
title: Failure-path harness — 401/403/429/500, wrong-password, known_hosts missing (fail-closed)
category: tests
priority: medium
effort: M
status: open
code_refs:
  - core/docker/test-providers.sh
opened: 2026-04-18
chunk: sg5
---
Ensure adapters surface structured errors (not crashes) under: wrong credentials → `AuthenticationException`; 401 after token expiry → refresh attempt; 403 → typed permission error; 429 → backoff + retry respecting `Retry-After`; 500/502/503 → graceful degradation; SFTP `known_hosts` missing → fail-closed per UD-101 (regression guard). Use toxiproxy or an in-container stub HTTP server; no new external infra.

---
id: UD-808
title: OAuth provider test framework — OneDrive, HiDrive, Internxt
category: tests
priority: medium
effort: L
status: blocked
blocked_by: secret-injection design
code_refs:
  - core/docker/test-providers.sh
opened: 2026-04-18
chunk: sg5
---
Gated on a secret-injection design: GitHub Actions repo secrets for OAuth refresh tokens stored per provider, threaded into the compose file via `.env` or `--env-file`, rotated on schedule. Once that lands, the same round-trip template used for sftp/webdav/s3/rclone applies unchanged (provider.upload/download/delete are base contract).

---
id: UD-809
title: Scale tests — large-file, many-file, N-cycle convergence
category: tests
priority: low
effort: L
status: open
code_refs:
  - core/docker/test-providers.sh
opened: 2026-04-18
---
Three behaviours the unit tests can't catch: (a) large-file chunked upload path (>250 MB for OneDrive, multipart for S3) — validates `UploadSession` resumability on simulated drops; (b) many-file (10k files with Delta pagination) — validates engine's state-DB growth + reconciler performance; (c) N cycles of `sync` on an unchanged tree — expects 0 actions on cycles 2..N (idempotency guard against ghost re-uploads). Use tmpfs for the test set; cap total size to stay within CI runner limits.

---
id: UD-813
title: Audit all tests with challenge-test-assertion rubric; triage into healthy/impl-anchored/misleading/delete
category: tests
priority: medium
effort: L
status: open
code_refs:
  - docs/dev/TEST-AUDIT.md
opened: 2026-04-19
---
**Surfaced 2026-04-19 by the first test-drive of `challenge-test-assertion` (UD-715).** A 25-test sample across SweepCommand, Reconciler, ThrottleBudget, and BuildInfo turned up **1 actively misleading test** (UD-800) and **3 impl-anchored tests** (UD-812). Extrapolating to ~113 test files in the repo: expect 4–10 more findings. Worth one dedicated audit pass.

## Scope

Systematically run the `challenge-test-assertion` rubric against every test file under `core/**/src/test/`. For each test, ask:

1. Name the invariant being protected in one sentence, without looking at the implementation under test.
2. Would a reasonable alternative implementation satisfying that invariant still fail this test?
3. If deleted, what real-world harm would reach production?

Triage each test into one of four buckets:

- **Healthy** — asserts the invariant. No change.
- **Impl-anchored** — asserts implementation detail (exact constants, specific sampling strategies, comment anchors). Rewrite to assert the invariant.
- **Misleading** — test name describes a different assertion than the body (UD-800 pattern). Split into name-correct + missing-invariant tests.
- **Delete-candidate** — asserts behaviour that isn't load-bearing. Remove with a CHANGELOG note.

## Expected artefacts

- `docs/dev/TEST-AUDIT.md` — table of every audited test, its bucket, the invariant it should protect, and a link to any rewrite ticket. Produced via the audit pass.
- Follow-up tickets for each rewrite batch (group by module: one ticket per `core/app/<X>` test dir, one per `core/providers/<Y>`).
- Updated `docs/dev/CODE-STYLE.md` (UD-714) with an "invariant-first test design" section pointing at this audit's findings as exemplars.

## Priority reasoning

This ticket is the generalisation of UD-800 + UD-812. The specific fixes are actionable now (UD-800 ~S, UD-812 ~S). The audit is the L-sized systematic pass that decides which other tests deserve the same treatment. Don't do the audit before the two specific fixes land — they're the proof-of-concept.

## Anti-scope

- **Not** a "delete every test that looks suspicious" pass. The bar is "what real-world harm does this test prevent?" — not "does it look pretty?"
- **Not** a ktlint / style sweep. Those have their own ticket (UD-714).
- **Not** a coverage-chasing ticket. Coverage ≠ invariant protection. UD-801 tracks coverage separately.

## Process suggestion for whoever takes this

- Run per-module, commit per-module. Audit-then-rewrite loops are long; module-boundaried chunks stay reviewable.
- Include a sampled human review on at least one rewrite per module — the rubric is opinionated, and some cases will be judgement calls.
- Spawn a subagent with the `challenge-test-assertion` skill loaded plus read-access to `docs/ARCHITECTURE.md` (the canonical source of invariants). Don't let the agent invent invariants — it must point at an existing document or file a clarification ticket.

## Related

UD-715 (Buschbrand brainstorm — parent philosophy), UD-800 (first concrete finding — misleading test name), UD-812 (second concrete finding — impl-anchored bundle), UD-714 (code-style policy — sibling "make the tacit explicit" work), UD-801 (coverage — distinct but adjacent).
---
id: UD-720
title: MCP: unidrive-msix-identity — structured NTFS file-ID + virtualisation classification
category: tooling
priority: low
effort: S
status: open
code_refs:
  - scripts/dev/msix-mcp/
  - scripts/dev/msix-id.sh
opened: 2026-04-19
chunk: xpb
---
Thin MCP wrapping `scripts/dev/msix-id.sh` so the agent can disambiguate its own (MSIX-sandboxed) view of a path from the user's native view without remembering which script to call or how to format the output.

## Tools

- `file_id(path: str) -> { abs_path, file_id: str, exists: bool, classification: "likely-virtualised" | "native" | "unknown" }` — NTFS file-ID lookup via `fsutil` + path-prefix heuristic. Same logic as msix-id.sh; structured output.
- `compare(path: str, other_file_id: str) -> { same_underlying_file: bool, mine_file_id, other_file_id }` — cross-check against a file ID the user supplied from their native shell. Answers "are we looking at the same physical file?" definitively.
- `list_divergence() -> [{ path, my_file_id, native_file_id?, notes }]` — best-effort comparison for the known MSIX-virtualised roots (`%APPDATA%\unidrive\`, `%LOCALAPPDATA%\unidrive\`, `C:\Program Files\`). Returns only the paths where divergence is verifiable; silent on paths where the native view can't be probed from the MCP's side.

## Architecture

- Python MCP. Can run on either side of the sandbox — most useful run **inside** the sandbox, because that's where the agent lives and where path ambiguity bites. Native-side comparison uses a shared JSON snapshot convention (user runs a native probe, MCP reads the snapshot).
- Template: trivial wrapper around subprocess calls to `fsutil`. ~60 lines.

## Value estimate

Low-frequency tool but high-leverage when it's needed. Current cost: remember the memory, run the shell script, parse the output. MCP turns it into a one-call structured query.

## Non-goals

- Fixing the sandbox. That's a Claude-Desktop-installer / MSIX-policy concern, not unidrive's.
- Redirecting file access away from virtualised paths. Out of scope; the user's `-c <path>` flag is the workaround.

## Open questions

- The `list_divergence()` tool needs a convention for sharing the native-side snapshot. Propose: user runs `scripts/dev/msix-snapshot.ps1` natively; drops JSON at `C:\Users\<user>\.unidrive-msix-snapshot.json`; MCP reads from there. That PowerShell script doesn't exist yet — would need to ship alongside the MCP.

## Related

- `~/.claude/projects/.../memory/project_msix_sandbox.md` — the knowledge this MCP operationalises.
- `scripts/dev/msix-id.sh` — shell equivalent the MCP wraps.
- UD-715 (meta-tooling).
---
id: UD-722
title: MCP: unidrive-research — provider-audit automation (endpoint enum + vendor-docs + gap report)
category: tooling
priority: low
effort: L
status: open
code_refs:
  - scripts/dev/research-mcp/
opened: 2026-04-19
---
Broad-scope MCP for the kind of vendor-docs + repo-code research we did for UD-228/229/230. Currently each research task is bespoke (a subagent with WebFetch + grep + synthesis). An MCP that knows the shape of "provider audit" work could cut setup + context-loading to near-zero.

## Tools

- `enumerate_endpoints(provider: str) -> [{ method, path, caller_file, caller_line, notes }]` — grep the provider's API service for all HTTP calls. Replaces the "search for url patterns and file:line-map each" manual loop.
- `fetch_vendor_docs(provider: str, topic: str = "rate-limits"|"retry"|"pagination"|"batch"|"auth") -> [{ title, url, excerpt, retrieved_at }]` — canned URL lookups per provider (MS Graph throttling docs, AWS S3 best-practices, rclone flags, etc.). Caches results locally for 7 days.
- `compare_our_wrapper(provider: str) -> { implemented: [...], recommended_but_missing: [...], questionable_choices: [...] }` — cross-checks what the unidrive wrapper does against the vendor's documented best practices. Prepopulated rules per provider based on UD-230 research.
- `audit_report(providers: str[] = ["all"]) -> markdown_doc` — full `<id>-robustness.md` per provider in one call. The thing UD-228 asks for, but on-demand and cached.

## Architecture

- Python MCP with a provider-knowledge JSON file baked in (per-provider URL map + rule set). Update quarterly.
- `WebFetch` equivalent under the hood: `httpx.get` with a 7-day cache at `~/.unidrive-mcp-cache/research/`.
- Runs either side of the MSIX sandbox; the cache dir is outside virtualised roots.

## Value estimate

UD-229 research took a subagent ~120 seconds (one-off cost of agent spawn + WebFetch + synthesis). UD-230 took ~4800 seconds across 7 providers. An MCP with cached vendor docs + structured queries could collapse UD-230-style work to minutes per provider.

This is the single biggest per-task time save among the proposed MCPs — but low frequency. Worth building only if the provider audit cadence becomes quarterly (currently one-off).

## Non-goals

- Full vendor SDK wrapping. Just docs + heuristics; no API emulation.
- Automated fix generation. The MCP flags gaps; humans/agents write the fix.

## Open questions

- **Is the provider audit frequency actually quarterly, or one-off?** If one-off, the MCP isn't worth building — do it as a subagent each time. If quarterly+, build it. Needs Gernot's product-direction input.
- The vendor-docs cache needs a refresh policy that handles breaking URL changes (MS Graph docs have moved several times this year). Propose: refresh every 7 days with fallback to the stale cached version if fetch fails.

## Related

- UD-228 (cross-provider HTTP robustness audit — primary consumer).
- UD-229 (OneDrive Graph coverage — done as a one-off by a subagent; template for what this MCP would automate).
- UD-230 (per-provider client notes — second one-off; also a template).
- UD-715 (meta-tooling).

## Priority note

**Lower than UD-716/717.** Build only if / when the provider audit cadence warrants it. Keep this ticket as a reminder, not a commitment.
---
id: UD-814
title: Cross-verify unidrive adapter vs rclone-backend results for every provider in the overlap set
category: tests
priority: medium
effort: L
status: open
code_refs:
  - core/docker/docker-compose.providers.yml
  - core/docker/test-cross-verify.sh
opened: 2026-04-19
chunk: sg5
---
**Cross-verification of unidrive provider adapters against rclone's backend for the same remote, as an end-to-end test harness.**

## Rationale

The 2026-04-19 session discussed three paths for an "OneDrive-local-tree → Internxt-cloud" mirror. Option C was "just use rclone outside unidrive" — rejected as the active solution because the goal is to exercise the unidrive stack, NOT the Internxt desktop client (which has its own reliability issues). But rclone IS a well-understood, battle-tested reference implementation for roughly the same set of providers unidrive targets. That's a testing opportunity: for any provider supported by BOTH unidrive and rclone, we can run matched operations and diff the results.

## Shape

A new test layer under `core/docker/` (sibling of UD-711's provider-harness and UD-803's upload-round-trip) that, for each provider in the overlap set:

1. Seeds a temp local folder with a deterministic payload (small-file fixture + one large-file fixture — the two testing modes UD-228 would eventually want).
2. Pushes to the remote via **unidrive** (`unidrive -p <profile> sync --upload-only`).
3. Independently pulls the remote state via **rclone** (`rclone lsjson <remote>:<path>`).
4. Asserts shape parity: same set of paths, same sizes, same content hashes.
5. Repeats in the pull direction: seed via rclone, read via unidrive (`unidrive ls` — UD-224 just landed — or the MCP `unidrive_ls`).

## Provider overlap (candidate set)

| Provider | unidrive adapter | rclone backend | In overlap? |
|---|---|---|---|
| OneDrive | `core/providers/onedrive/` (Graph) | `onedrive` | Yes |
| HiDrive | `core/providers/hidrive/` | `hidrive` | Yes |
| SFTP | `core/providers/sftp/` | `sftp` | Yes |
| WebDAV | `core/providers/webdav/` | `webdav` | Yes |
| S3-compat | `core/providers/s3/` | `s3` | Yes |
| Internxt | `core/providers/internxt/` | (no native rclone backend as of 2026-04) | **No** |
| Localfs | `core/providers/localfs/` | `local` | Yes |
| Rclone | `core/providers/rclone/` (wraps rclone itself) | — | trivial / circular |

Internxt is the notable gap — this harness explicitly can't cross-check it. That's a known limit; surface it in the test-matrix docs.

## Wiring

- Extend `core/docker/docker-compose.providers.yml` (UD-711) with a new service `cross-verify` that has BOTH the unidrive CLI AND `rclone` installed, plus the per-provider rclone.conf entries for the ephemeral containers.
- One bash test script `test-cross-verify.sh` per provider, run by the same `docker-integration` GitHub Actions job that UD-708 wired up.
- Assertion primitive: a `diff-trees.py` helper that takes `{name, size, sha256}` tuples from both sides and reports first mismatch. Reuse UD-803's test-harness style.

## Acceptance

- One green docker-compose test for at least 3 providers (propose SFTP / WebDAV / S3) covering both directions of the comparison.
- Test matrix passes when unidrive and rclone see identical trees; fails clearly (structured assertion output, not a stack trace) when they don't.
- Internxt gap documented in the test-harness README.

## Why this matters

- **Silent drift detection.** Today the unidrive adapter for any given provider could quietly diverge from the vendor's real behaviour and we'd only notice via a user bug report. A daily CI run of this harness catches it.
- **UD-228 input.** The cross-provider HTTP-robustness audit ticket would gain directly measurable "unidrive does X while rclone does Y" deltas from this harness's output, sharpening its own recommendations.
- **Refactor safety net.** Any future extraction of `HttpRetryBudget` (UD-228), `ThrottleBudget` (UD-232 / UD-200), etc. across provider adapters can rely on this harness as the regression wall.

## Priority

**Medium, L effort.** Not blocking v0.1.0 but durable testing infrastructure. Pair well with UD-228; could be built by the same agent working on UD-228's audit artefacts.

## Related

- UD-228 (cross-provider HTTP robustness audit) — direct consumer.
- UD-711 (closed — docker-compose.providers.yml harness) — the wiring pattern.
- UD-803 (closed — upload-round-trip extension) — the assertion style.
- UD-230 (closed — per-provider client notes) — vendor-docs baseline this harness verifies against in practice.
- UD-808 (open — OAuth test framework, blocked on secret injection) — necessary for the OneDrive half of this harness in CI. Use the UD-723-deployed `unidrive-test-oauth` MCP for local dev; CI waits on UD-808.
---
id: UD-236
title: CLI: split `sync` into `refresh` + `apply` + `sync` — git-style three-verb model (Direction B chosen)
category: core
priority: medium
effort: M
status: open
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
  - docs/user-guide/consequences.md
opened: 2026-04-19
chunk: core
---
**UX feedback 2026-04-19.** Gernot's paraphrase during the Internxt-auth session: *"sync state of files (from) cloud provider; the action is idempotent to the local files, that changes when download is triggered"* — matches the pre-UD-222 mental model (placeholder stubs until `get`). Post-UD-222 the code actually moves bytes by default because remote-new non-folders emit `DownloadContent` in Pass 2; the user-facing mental model and the implementation drifted apart.

**Direction chosen 2026-04-19: B — git-style three-verb split.** Gernot: *"it's more git'ish style, i like that"*.

## Target shape

```
unidrive -p X refresh     # Gather + Reconcile only; update state.db with remote changes. Moves ZERO bytes.
unidrive -p X apply       # Apply pending DB actions (download, upload) without re-gathering.
unidrive -p X sync        # refresh + apply (the current combined path — kept as-is so existing scripts keep working).
```

Parallels `git fetch` → `git merge` → `git pull`. Matches unidrive's internal three-phase architecture: Gather → Reconcile → Apply.

## Implementation sketch

- Extract the Gather+Reconcile portion of `SyncCommand.call()` into a `RefreshCommand`. It runs the engine up to the point actions are generated, persists `pending_cursor` + any inserted/updated DB entries, and prints the action summary (e.g. *"12 download, 3 upload, 1 conflict pending — run `apply` to execute"*).
- Extract the Apply portion into `ApplyCommand`. It reads the persisted pending actions from `state.db` (new table or field per action — needs a design bit), executes Pass 1 + Pass 2, clears pending on success.
- `SyncCommand` becomes a thin composition: `refresh` then (if refresh produced actions) `apply`. Flags `--download-only` / `--upload-only` propagate to the apply phase.
- `status --pending` (companion; was direction C's idea, fits here too) prints what a hypothetical `apply` would do — cheap, reads the same pending table.

## Persistence for pending actions

Today `SyncAction` is in-memory only, generated per `sync` run. To make `apply` work as a separate command, the action list needs to survive between processes. Options:
(a) A new `pending_actions` SQLite table in `state.db` with rows per action (path + type + remote-ref).
(b) A serialised JSON file alongside `state.db` (`pending.json`).
(a) is cleaner; (b) is simpler. Lean (a) for rollback-ability + atomic commits.

## Breaking changes

None for existing scripts that invoke `sync`. Anything new goes under `refresh` / `apply`. Old `--download-only` / `--upload-only` semantics preserved on `sync`; propagated to `apply` as well.

## Acceptance

1. `unidrive -p X refresh` updates `state.db` with the current remote delta and prints a summary of pending actions. No bytes moved.
2. `unidrive -p X apply` executes the pending actions from (1); `state.db` after is identical to what `unidrive -p X sync` would have produced.
3. `unidrive -p X sync` with no changes behaves identically to today's `sync` (no regressions in MCP round-trip tests).
4. `unidrive -p X status --pending` (new) prints the action plan without executing.
5. `docs/user-guide/consequences.md` gets a section "The three verbs" explaining when to use which.
6. Existing `SyncCommand`-level integration tests pass unchanged. New tests cover the split flow.

## Related

- UD-222 (closed) — the change that drove sync to move bytes by default; this ticket is the UX consequence.
- UD-220 (open) — `consequences.md` user doc; home for the "three verbs" section.
- UD-713 (open) — first-sync ETA; with `refresh` as a separate command, the ETA question becomes cleanly scoped to enumeration.
- UD-237 (open, sibling) — `-p TYPE` auto-resolve polish; both tickets raised at the same verify-the-Internxt-auth moment.

## Priority

**Medium, M effort.** Breaks nothing existing, matches git's proven ergonomics, explicit three-phase visibility makes first-sync ETA + offline-quota queries cleaner. Not v0.1.0 critical but pulls a meaningful weight of UX clarity across several open tickets.

---
id: UD-239
title: Vault — elevate xtra_encryption to first-class multi-layered OSS-crypto concept (JDK hardening, Bouncy Castle, age)
category: core
priority: medium
effort: L
status: open
opened: 2026-04-19
chunk: core
---
**Elevate `xtra_encryption` to a first-class "unidrive vault" concept with multi-layered OSS encryption.** The xtra module already has the pluggable shape (`XtraEncryptionStrategy` interface, AES-GCM JDK implementation, passphrase-protected `XtraKeyManager`). This ticket tracks a staged hardening + breadth expansion and spawns sub-tickets per layer.

## Scope — three layers

### Layer 0 — today (already in tree)

- `XtraEncryptionStrategy` interface: `algorithmId`, `ivLength`, `wrappedKeyLength`, `encrypt/decrypt(InputStream, OutputStream, fileKey, iv)`.
- `AesGcmStrategy` — JDK `javax.crypto.Cipher("AES/GCM/NoPadding")`, 12-byte IV, 16-byte tag.
- `XtraKeyManager` — passphrase-protected master key, per-file wrapped keys.
- `XtraEncryptedProvider` — wraps any `CloudProvider`.
- CLI: `vault xtra-init`, `xtra_encryption = true` per profile in config.toml.

### Layer 1 — JDK-only hardening (recommended as the immediate next commit surface)

1. **Swap PBKDF2 -> Argon2id for passphrase-to-key derivation.** PBKDF2 with ~100k iterations is weak against GPU-class attackers in 2026. Argon2id is the OWASP default. JDK 25 does not ship it natively — needs a JNI-free lib like `de.mkammerer:argon2-jvm-nolibs` or a pure-Kotlin port.
2. **Swap AES-GCM -> AES-GCM-SIV (RFC 8452) for filesystem-scale write volumes.** AES-GCM's security proof requires unique nonce per key; a single nonce reuse = catastrophic plaintext recovery. With 2^32 files per profile the birthday bound on random 96-bit nonces is uncomfortably close. GCM-SIV is nonce-misuse resistant. JDK does not ship GCM-SIV -> first Bouncy-Castle crossover.
3. **Key-wrap header versioning.** `XtraHeader` needs a clean algorithm/version discriminator so Layer 2 can co-exist with Layer 0/1 files without migration.
4. **AEAD-authenticated filename + path metadata** — today the encrypted provider encrypts bytes but filenames/paths travel in clear. Either encrypt separately or bind them into the AEAD associated-data (AAD) so tampering is detectable.

### Layer 2 — Bouncy Castle + age breadth

1. **Add Bouncy Castle FIPS (`bc-fips`) as the second `XtraEncryptionStrategy` provider.** Give users a choice: `encryption_strategy = "jdk-gcm-siv"` vs `"bc-chacha20-poly1305"`.
2. **ChaCha20-Poly1305** — Bernstein's AEAD, better on ARM / no-AES-NI hardware (Raspberry Pi, older Android). Constant-time by design.
3. **`age`-format recipient encryption** — via `com.github.str4d:tink-rage` or a port. Unlocks multi-party vaults: share a vault folder with a second user by adding their age recipient key, no shared passphrase, no re-encrypt. Huge for team-shared folders under HiDrive / S3.
4. **Hardware-token key-release** — YubiKey PIV / PKCS#11. Requires Bouncy Castle PKCS#11 bridge. Gates the passphrase behind a physical device.

## Non-goals (out of scope for this umbrella)

- FIPS 140-3 validation (would force bc-fips exclusively, drop JDK). Track separately if a regulated customer asks.
- Vault-as-separate-profile (a "vault profile" that doesn't sync remotely). Distinct feature, file separately.
- Client-side encrypted search. Big research project.

## Immediate-next-step recommendation

**Layer 1.1 (Argon2id) + Layer 1.2 (GCM-SIV) in a single commit.** These are the two JDK-only defects that have a CVE-grade blast radius; everything else is gravy. Spawn a Layer-1 sub-ticket once this umbrella is filed.

## Related

- UD-225 (closed) — existing xtra behaviour around partial downloads + integrity.
- UD-401 / UD-402 (open) — CFAPI placeholder pipeline; vault files must be hydrate-on-open too, not just walked-through.
- UD-315 (closed 2026-04-19) — Personal Vault filter for OneDrive; note name collision. Our "vault" is a local encryption concern, Microsoft's is a server-side 2FA-gated container. Docs need to disambiguate.
- UD-228 (open) — cross-provider robustness audit; vault is provider-agnostic so every adapter must round-trip encrypted bytes correctly.

## Acceptance for the umbrella ticket

- Three sub-tickets filed: **Layer 1 (JDK hardening)**, **Layer 2 (Bouncy Castle)**, **Docs + migration plan**.
- Architecture decision record in `docs/adr/` laying out the `XtraEncryptionStrategy` version discriminator and the file-header binary layout for forwards/backwards compatibility.
- No code lands against UD-239 directly; it closes when its children are either merged or explicitly deferred.

## Priority

**Medium, L effort** as an umbrella; individual children range XS (docs) to L (Bouncy Castle integration + tests). The JDK-hardening child is the highest-leverage chunk and should land first.
---
id: UD-240
title: Feedback for long-running (>0.5s) CLI/UI actions — always-on IPC, progress state file, heartbeat, inline CLI progress
category: core
priority: medium
effort: M
status: open
opened: 2026-04-19
chunk: ipc-ui
---
**Improve feedback for long-running (>0.5s) unidrive actions across both CLI and UI. Research done 2026-04-19; concrete recommendations below.**

## Observed failure mode that motivated this ticket

2026-04-19 session: daemon PID 10792 ran `unidrive -c <config> -p inxt_gernot_krost_posteo sync` (one-shot, no `--watch`) for ~50 min. The UI tray reported "no daemon" the whole time, which read as broken. Root cause is structural, not a UI bug per se.

## Root cause

[`SyncCommand.kt:153-157`](core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:153) only starts `IpcServer` + `NamedPipeServer` when `watch = true`. [`DaemonDiscovery.kt:34-49`](ui/src/main/kotlin/org/krost/unidrive/ui/DaemonDiscovery.kt:34) is pure file-glob on `unidrive-*.sock`. **One-shot syncs never advertise -> UI is blind to them by design.** Compounding: on Internxt-scale backfills (hours), one-shot means "long-running invisible".

## State inventory (where truth lives today)

| Carrier | Scope | Readable by | Liveness |
|---|---|---|---|
| `SyncState` (in `DaemonDiscovery`) | in-memory | UI only | only during `--watch` |
| `state.db` | persistent | anyone | between syncs (not live) |
| `failures.jsonl` | persistent, append | anyone | record, not liveness signal |
| `unidrive.log` | persistent, 10 MB rotate | anyone | volatile tail |
| IPC NDJSON (AF_UNIX / NamedPipe) | volatile stream | UI only | only during `--watch` |

## Proposed concrete wins (each a separate sub-ticket candidate)

### 240a — Always-on IPC (XS-S)

Drop the `if (watch)` guard on `IpcServer`/`NamedPipeServer` creation in `SyncCommand.kt`. Socket advertises for every `sync` run, one-shot or continuous. **UI discovers one-shot daemons automatically; no other change needed on the UI side.** Cheapest + highest-leverage fix.

### 240b — Progress state file (S)

Daemon writes `<profile>/progress.json` atomically every ~500 ms containing phase / items-processed / items-total / transfer-bps / eta / last-action. Survives UI restarts. Lets `unidrive status` (and the stale-UI tooltip) report real progress without requiring a live IPC connection. Also captures progress of already-running daemons after UI restart.

### 240c — Heartbeat + idle-alive event (XS)

Even when engine is quiescent, emit `{event:"idle", since: <ISO8601>, cycles_idle: N}` every 10 s. Distinguishes "alive, nothing to do" from "dead". Kills 90% of the "is the tray ok?" uncertainty.

### 240d — CLI inline progress reporter (XS)

Add a `--progress` flag (default on when stdout is a TTY, off when piped) that prints one compact line re-painted with `\r` every 500 ms:
```
[scan] 12,345 items  |  [downloads] 47/200 (23%)  |  2.3 MB/s  |  ETA 4m
```
`CliProgressReporter` already receives all the events; just needs a throttled painter.

### 240e — Daemon discovery fallback (S)

When UI sees no socket **but** `<profile>/progress.json` mtime is < 60 s old, show "syncing (legacy non-IPC)" in the tray instead of "no daemon". Graceful handling of old daemons or the transition window before 240a lands everywhere.

### 240f — Action-level "still working" nudges (S)

For specific operations that can exceed 500 ms without emitting progress (large file upload, folder delete, graph listChildren pagination), emit a `{event:"still_working", operation:"uploadLargeFile", elapsed_ms:1200, path:"..."}` nudge every ~1 s. Symmetric coverage so nothing is silent.

## Ordering recommendation

**240a first** (server-side fix is cheap, unblocks UI discovery immediately). Then **240d** (CLI inline progress — independent, user-visible win). Then **240b + 240c** together (progress.json + heartbeat — they co-design nicely). **240e + 240f** last.

## Non-goals

- GUI progress bar rework — out of scope; the tray already has progress rendering.
- Prometheus metrics / Grafana dashboard — that's a separate observability track.
- Replacing NDJSON with gRPC / Protobuf — tested previously; not warranted.

## Related

- UD-212 (closed) — MDC profile tag propagation; any new progress fields must respect the profile context.
- UD-221 (closed) — tray submenu work; same UI module that shows the per-profile status.
- UD-222 (closed) — "adopt writes zeros" incident; heartbeat + progress.json would have made it 10 min of triage instead of 2 hours.
- UD-316 (closed 2026-04-19) — `status --audit` computes a snapshot; progress.json would let `status` (no flag) do the same for in-flight syncs.

## Acceptance for the umbrella

- Six sub-tickets filed (240a-f), each scoped to its own commit.
- Tray correctly shows a running one-shot sync within 10 s of start, with phase + progress%.
- Piped CLI output preserves current quiet behaviour; interactive CLI gets the new `\r`-painted progress line.
- No regression in existing `--watch` daemon path.

## Priority

**Medium, M effort** as an umbrella. **240a alone is XS and worth landing tomorrow** regardless of the rest. 240d is next easiest standalone win.
---
id: UD-247
title: Cross-provider benchmark harness — synthetic workload, per-provider metrics, tracked baselines in docs/benchmarks/
category: core
priority: medium
effort: L
status: open
opened: 2026-04-19
chunk: sg5
---
**Cross-provider benchmark harness for apples-to-apples throughput, latency, and error-rate comparison.** Motivated by the 2026-04-19 observation: Internxt sustains ~358 Mbit/s receive (~45 MB/s) on this machine once Cloudflare's rate window opens, while OneDrive via Graph is capped far below that; UD-712 measured ~1.8 MB/s Graph-delta metadata throughput and ~119 files/min download on a 346 GB workload. Today we have one-off anecdotal numbers scattered across tickets (UD-712 handover, UD-232 / UD-200 write-ups, UD-712 first-sync timings). No canonical harness exists.

## Problem

- Each provider's real-world performance is only visible by running an actual sync, which contaminates the account, burns throttle budget, and is not reproducible.
- Existing test surfaces (`LiveGraphIntegrationTest`, `OneDriveIntegrationTest`, `HiDriveIntegrationTest`) are correctness tests, not performance tests.
- When we change `ThrottleBudget` (UD-232 / UD-200) or the retry pipeline (UD-227 / UD-231 / UD-309 / UD-311), we have no before/after number; regressions are only caught the next time someone runs a large sync.
- Cross-provider comparison ("is Internxt 10× faster than OneDrive, or just 2× on this workload?") is currently vibes, not data.

## Proposal

A new Gradle task `:benchmarks:run` that runs a **synthetic, self-cleaning workload** against every provider for which credentials are present in env vars:

### Workload matrix (per provider)

| Name | Purpose |
|---|---|
| `metadata-scan` | List 10k items in a pre-seeded folder; measures pure metadata throughput. |
| `small-files-upload` | Upload 1 000 × 1 KiB; measures per-request overhead. |
| `small-files-download` | Download the above set; same axis, other direction. |
| `large-files-upload` | Upload 10 × 100 MiB; measures steady-state bandwidth + chunking overhead. |
| `large-files-download` | Download the same set; ditto. |
| `delta-noop` | Delta with a stable cursor, no changes; measures minimum poll cost. |
| `delta-100changes` | Delta after touching 100 random items remotely; measures incremental enumeration cost. |
| `concurrent-download-16` | Download 16 × 10 MiB with concurrency=16; stresses the adapter's session pool. |

### Metrics per workload × provider

- Wall time (ms, min/mean/p95/max across N runs, default N=3).
- Bytes/s sustained.
- Request count, 2xx / 4xx / 5xx / throttle (429) breakdown.
- Retry count.
- JVM heap peak (for regression detection — large-file workloads should not balloon).
- Client-observed error codes (timestamp, url, status) as a JSONL side-file for forensics.

### Output

- `docs/benchmarks/YYYY-MM-DD-<short-commit>/results.json` — raw numbers.
- `docs/benchmarks/YYYY-MM-DD-<short-commit>/report.md` — table rendered from results.json with provider comparison + delta-vs-previous-baseline.
- `docs/benchmarks/latest.md` symlink/copy for quick dashboard access.

### Execution model

- **Never runs in default CI.** Gated on `UNIDRIVE_BENCHMARK=true` env var + per-provider credential env vars (`UNIDRIVE_BENCHMARK_ONEDRIVE_PROFILE=...`, etc.).
- Each workload runs in an isolated subfolder of the remote namespace (`benchmarks/<uuid>/`) and tears it down in a `finally` block. Abort safely on Ctrl-C — the folder stays, but the harness's next run detects and purges stale `benchmarks/*` older than 24h.
- Designed to be safe to run against a production account: ≤ 500 MB transfer per full run, ≤ 5 min wall time per provider.
- Can be scoped: `./gradlew :benchmarks:run -Pprovider=internxt -Pworkload=large-files-download`.

### Module layout

- New Gradle module `core/benchmarks/` with its own `build.gradle.kts`.
- Reuses `CloudProvider` interface — same code path production uses.
- JMH is overkill (we're measuring network-bound work, not nanos) — plain `kotlin.time.measureTime` + simple stats are enough.

## Non-goals

- Comparing raw HTTP against unidrive's overhead. That's a different ticket (UD-<future>).
- Benchmarking the UI.
- Benchmarking xtra-encryption throughput (will be a separate workload family once UD-239 Layer 1 lands).

## Why single-ticket, not sub-tickets

The harness skeleton is the 70% of effort; adding a seventh or eighth workload is an hour each once the skeleton exists. Splitting this into "framework" + "workload A" + "workload B" tickets creates more coordination overhead than it saves.

## Acceptance

- `./gradlew :benchmarks:run` green against `localfs` (zero-network baseline; always runnable).
- `./gradlew :benchmarks:run -Pprovider=onedrive` green when `UNIDRIVE_BENCHMARK=true` + `UNIDRIVE_BENCHMARK_ONEDRIVE_PROFILE` is set; fails cleanly with a skip message otherwise.
- First committed baseline in `docs/benchmarks/` captures OneDrive + Internxt numbers on the current ThrottleBudget settings — **the 2026-04-19 358 Mbit/s Internxt observation should appear verbatim as the first row** of the first baseline.
- `report.md` shows a provider × workload table that a reviewer can read in 10 seconds.
- Regression: re-running the harness two days later produces a diff-vs-baseline summary in `report.md` that flags any metric that moved >20%.

## Related

- UD-200 (open) — adaptive ThrottleBudget. Becomes reviewable the moment this harness exists.
- UD-223 (open, Part B pending) — `?token=latest` Part A landed; benchmark proves the 11 min → 1 s claim.
- UD-232 (closed) — introduced the 200 ms spacing floor; this harness would have caught the 8× regression immediately.
- UD-712 (closed) — source of the ~119 files/min OneDrive number; retroactively reproducible.
- UD-713 (open) — first-sync ETA probe; depends on the same metric collection layer the benchmark harness needs.
- UD-814 (open) — rclone cross-verification; overlap in the "run each provider through the same workload" design.

## Priority

**Medium, L effort.** Not blocking any in-flight work, but unlocks quantitative reasoning on every remaining throttling / throughput ticket. Worth landing before UD-200 so UD-200's improvement is measurable.
---
id: UD-275
title: relocate — JVM memory grows ~630 KiB/file (894 MB → 3.4 GB over 4 k uploads); possible leak
category: core
priority: medium
effort: M
status: open
opened: 2026-04-21
chunk: core
---
### Problem

`unidrive relocate` JVM working-set grew from **894 MB at startup
to 3 420 MB after ~2 h 15 min** during an `onedrive-test →
ds418play-webdav` run. Growth is ≈ 1 MB / 4 uploaded files. At that
rate a complete 128 686-file run would hit ~32 GiB resident before
the target is full — well beyond what the default `-Xmx2g` in
`unidrive.cmd` allows (process would OOM long before then, but the
`.cmd` wrapper currently omits `-Xmx`, letting the JVM claim up to
~25% of host RAM per its default ergonomics).

### Reproduction (2026-04-21 field observation)

```
11:51:06 — JVM start, 894 MB RSS
14:08:00 — 4 002 uploads in, 3 420 MB RSS, killed
```

Growth: **2 526 MB / 4 002 uploads = ~631 KiB per file**. No 5xx
backlog, no retry queue. MKCOL cache holds ~50 paths (UD-268) —
negligible footprint. No errors or exceptions pooling.

### Suspects (ranked by likelihood)

1. **Ktor connection pool / response-body retention.** OneDrive
   download + WebDAV upload both go through Ktor clients. If the
   response body from a download isn't fully drained + released
   before the next call, byte buffers accumulate in the engine's
   pool. `Apache5` engine (used for WebDAV when `trust_all_certs`)
   pools aggressively.

2. **Tempfile stream objects.** `CloudRelocator.migrate` downloads
   to a tempfile then uploads. If the InputStream for the upload
   is never closed (e.g. `setBody(object : OutgoingContent.WriteChannelContent()`
   in WebDavApiService.kt uses `Files.newInputStream(localPath).use { ... }`
   — that one is clean), each upload leaks one FD + buffer. Worth
   auditing the whole path.

3. **OneDrive SDK per-call allocations not released.** The Graph
   download path goes through GraphApiService.download which reads
   the response body into a ByteReadChannel — if any intermediate
   buffer is held.

4. **Dispatcher worker pool growth.** 17 concurrent workers observed
   (observed across both relocates today). Each worker retains its
   own coroutine stack + any local references it held. Worth
   capping concurrency (see UD-263).

5. **Logger accumulation.** Each upload emits 2–3 DEBUG log lines;
   logback's async appender might be buffering. Unlikely to add up
   to GB but worth ruling out.

### Investigation steps (unclaimed)

1. Add an explicit `-Xmx2g` to `%LOCALAPPDATA%\unidrive\unidrive.cmd`
   so OOM happens predictably (not silently growing to 25% of RAM).
2. Reproduce with a heap dump trigger: `-XX:+HeapDumpOnOutOfMemoryError
   -XX:HeapDumpPath=%LOCALAPPDATA%\unidrive\heap.hprof`. Run a small
   relocate to completion, analyse the hprof in VisualVM / Eclipse
   MAT / jhsdb.
3. Look for `io.ktor.utils.io.ByteReadChannel` / `ChunkBuffer` /
   `ByteBuffer` retained by chains of `SuspendContinuationImpl`.
4. Verify `HttpClient.close()` is called after each run (it is —
   `fromProvider.close()`, `toProvider.close()` in RelocateCommand).
   Per-call leak is the problem, not per-run.

### Workaround (until root-caused)

- Add `-Xmx2g` to the launcher. Anything near 2g in a long relocate
  will then crash with OOM rather than silently eating host RAM.
- Users on large tree: chunk the relocate with `--source-path` to
  break a 328 GiB run into ten 30 GiB pieces; each piece's JVM is
  short-lived.

### Acceptance

- After a full 128 686-file relocate, RSS at completion is within
  ±50% of RSS at first-upload time.
- Added heap-dump on OOM flag to the launcher.
- Root-cause identified and ticket updated with the real leak
  (may spawn further tickets).

### Related

- UD-263 (open) — concurrency caps; would reduce steady-state
  footprint.
- UD-262 (open) — HttpRetryBudget extraction; if the leak is in
  retry queue growth, that touches the relevant code.
- UD-272 (open) — ProcessLock holder-PID hint; useful for
  heap-dump attachment (`jmap -dump <pid>`).

### Priority / effort

Medium priority, M effort. Not a correctness bug but a long-run
stability problem — a relocate that works on a 10k-file tree may
OOM on a 100k-file one without warning. Investigation is the bulk
of the work; fix is usually small (close a resource, bound a queue).
---
id: UD-328
title: WebDAV upload resume via Content-Range PUT
category: providers
priority: medium
effort: M
status: open
opened: 2026-04-21
chunk: core
---
For the 300 GiB ds418play relocation, every mid-upload failure (UD-277
timeout, UD-278 reset, UD-327 cap) currently restarts the file from byte
0. At ~12 % failure rate on large 1080p `.mp4` files, this doubles the
effective transfer cost for the long tail.

DSM WebDAV supports `Accept-Ranges: bytes` and accepts `Content-Range`
on PUT when configured (RFC 7233 PUT-range semantics are optional but
widely implemented; DSM's nginx WebDAV module honours them).

## Proposal

Resume support in `WebDavProvider.upload`:

1. Before PUT, HEAD the target. If it returns a partial file size,
   PUT with `Content-Range: bytes <offset>-<end>/<total>`.
2. On retry (UD-278), the next HEAD sees the partial upload and
   picks up where the last attempt died.
3. Config flag `webdav.resume_uploads = true|false`; default true.
4. Capability probe: on profile first-use, upload a tiny test file
   via two PUTs with Content-Range + HEAD between. If the round-trip
   fails, flip per-profile flag off and log once.

## Acceptance

1. Upload 1 GiB file, kill connection at 400 MiB, retry — retry
   transfers 600 MiB not 1 GiB. Verified via bytes-sent counter.
2. Capability probe correctly disables resume for non-range server.
3. `CloudRelocator` logs `resumed from Xm Y MiB` so ds418play
   scenario shows clearly in `unidrive.log`.
4. No behaviour change for OneDrive / Graph (uses its own
   upload-session resume).

## Related

- UD-277 / UD-278 / UD-327 — together cover the ds418play-class
  WebDAV relocation failure surface.
---
id: UD-281
title: Right-size heap + stream Ktor upload body to cut GC pressure
category: core
priority: medium
effort: M
status: open
opened: 2026-04-21
chunk: core
---
JFR from the 21-04-26 ds418play relocation shows:

- Full GC + Concurrent Mode failure during the run.
- Longest GC pause 224,639 ms (0.22 s). Highest ratio of application
  halts 16.3 % during a one-minute window.
- One window where the JVM was **paused 92.3 %** during 16:03:01.923 –
  16:03:02.201 (a single 278 ms wall window dominated by GC).
- Top allocator: `Ktor-client-apache` thread, `ByteBuffer.allocate`
  (`java.nio.HeapByteBuffer.<init>`) — 12.8 TiB of total allocation
  over the recording.

The run was launched with `-Xmx4g`. For a 128 k-item relocation
holding full-file ByteBuffers on the upload path, that's tight; the
circuit-breaker-free upload pattern (UD-277, UD-278, UD-328 fix the
retry side) amplifies it because each retry re-allocates.

## Proposal

Two tracks:

1. **Right-size the heap default** in the launcher. For `relocate`
   specifically, suggest `-Xmx` based on `planFileCount × targetMaxFileSize`
   with a 2 GiB floor and 16 GiB ceiling; warn if the user-provided
   `-Xmx` is below the computed minimum for the plan. Cheap, ships
   today.
2. **Stream the upload body instead of buffering** in the Ktor-Apache
   client path. The biggest allocation is `ByteBuffer.allocate(<fileSize>)`
   on the upload side; switching to a chunked `InputStream` body (or
   Okio `Source`) drops peak heap to O(chunkSize) regardless of file
   size. This is the same refactor that unlocks UD-328 (Range-PUT
   resume), so they should probably land together.

## Acceptance

1. Track 1: `relocate --dry-run` emits a sizing recommendation in the
   plan output. `relocate` warns on mismatch.
2. Track 2: a 1 GiB upload observes peak heap delta below 128 MiB
   (down from ~1 GiB today), verified via a JFR assertion in an
   integration test (or a cheap `-XX:+HeapDumpOnOutOfMemoryError`
   smoke).
3. Full GC count over a 10 k-file relocation drops from ≥ 1 to 0.
4. No regression on small-file throughput.

## Related

- UD-277 / UD-278 / UD-328 — network-side fixes; heap-side is the
  missing half.
- `feedback_jar_hotswap` memory — reminder not to swap the running
  daemon mid-profile.
---
id: UD-734
title: MCP: unidrive-local-deploy — stereotyped xpb build→clean→deploy→verify loop
category: tooling
priority: medium
effort: M
status: open
opened: 2026-04-21
chunk: xpb
---
The build→kill→deploy→verify sequence on xpb is stereotyped enough to warrant
its own MCP server. Today it's three manual steps (`cd core && ./gradlew
:app:cli:deploy :app:mcp:deploy` + `cd ui && ./gradlew deploy` + a manual
`--version` smoke), with pre-kill and stale-jar hygiene left to the human.
That hygiene is load-bearing: the user found cold dead jars from earlier
unidrive versions sitting in the deploy tree and cleaned them manually —
one-off greenfield that should be structural.

The `feedback_jar_hotswap` memory captures the hard rule (Windows classloader
corrupts when a running jar is overwritten) and every deploy has to honour
it. Surfacing this as an MCP server lets Claude Desktop drive the workflow
without having to re-discover the invocation each time, and gives structured
preflight / verify output we can assert on.

## Proposal

New MCP server under `core/mcps/unidrive-local-deploy/` (or similar),
speaking the standard JSON-RPC surface and exposing:

| Tool | Arguments | Behaviour |
|---|---|---|
| `preflight` | — | Lists running `java.exe` whose cmdline references a unidrive jar (`unidrive-…-greenfield.jar`, `unidrive-ui.jar`). Returns `{pid, jarPath, startedAt}` so the caller can decide before anything is killed. |
| `clean` | `{scope: "jars" \| "all"}` | Deletes any `unidrive*.jar` under `%LOCALAPPDATA%\unidrive\` and `%USERPROFILE%\.local\bin\` that is NOT in the current-version set. Returns the deleted list. `scope=all` also clears `unidrive-*.cmd` / `unidrive-*.vbs` launchers that no longer match a live jar. Idempotent. |
| `build_all` | `{skipTests?: bool}` | Runs `./gradlew :app:cli:deploy :app:mcp:deploy` in `core/` + `./gradlew deploy` in `ui/`, sequentially. Pipes stdout to a tmp log; returns per-module wall time + the installed-jar paths + short git SHA from `BuildInfo`. |
| `verify` | — | For each installed jar, spawns `java -jar … --version` (or the MCP equivalent), returns the banner string. Compares short SHA to `HEAD` and warns on mismatch. |
| `watchdog_restart` | — | Touches `%LOCALAPPDATA%\unidrive\stop` (the sentinel `unidrive-watch.cmd` already honours), polls until the loop picks it up, confirms a fresh daemon came back. |

## Acceptance

1. `preflight` correctly identifies a running daemon (start one manually,
   call preflight, see it in the response); returns empty list when nothing
   runs.
2. `clean scope=jars` removes a deliberately-planted `%LOCALAPPDATA%\unidrive\
   unidrive-0.0.0-legacy.jar` but leaves the current-version jars untouched.
   Integration test with a temp install dir.
3. `build_all` end-to-end on a clean checkout finishes green; installed jars
   carry the HEAD short SHA (matches `git rev-parse --short HEAD`).
4. `verify` on the just-deployed tree reports three matching SHAs; when the
   tree is edited but not redeployed, `verify` flags the dirty state.
5. `watchdog_restart` returns success when the watchdog cycles; returns a
   clear error if the watchdog isn't running (no sentinel-touch loop).
6. Honours `feedback_jar_hotswap` — `clean` refuses to delete a jar that a
   live `java.exe` has mapped (cross-check against `preflight`); caller must
   kill the process explicitly or use a separate `kill_daemons` tool. We do
   NOT auto-kill daemons as a side-effect of `clean` or `build_all`.

## Non-goals (keep the surface small)

- No Linux deploy path. sg5 has its own equivalent (`.local/bin/`, UDS under
  `$XDG_RUNTIME_DIR`) and belongs in a separate MCP or a shared core with
  os-dispatch. Start with xpb.
- No automatic Claude Desktop MCP-config rewrite (`claude_desktop_config.json`
  already points at the versioned jar name; we don't touch that file).
- No MSIX packaging. Stays strictly in the developer-inner-loop lane.

## Related

- `feedback_jar_hotswap` memory — the hard rule the server must honour.
- `project_msix_sandbox` memory — `%LOCALAPPDATA%\unidrive\` is at risk of
  virtualisation from MSIX'd callers; the server must run from a native
  shell (Bash / PowerShell), not from inside an MSIX-sandboxed process.
- UD-733 (git-SHA in every log line) — the `verify` tool is a cheap consumer
  of that work once it lands: grep for the SHA across the running log vs the
  installed banner.
- UD-270 (Windows launcher CTRL-C prompt) — `watchdog_restart` will also
  benefit from that fix once it lands.
- The `unidrive-msix-identity` MCP in UD-720 is the natural sibling: both
  are Windows-dev-inner-loop MCPs. If UD-720 lands first, share a monorepo
  MCP layout.
---
id: UD-290
title: WebDavApiService.listAll buffers entire PROPFIND XML in memory; should stream via Flow
category: core
priority: medium
effort: M
status: open
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
opened: 2026-04-29
---
**Symptom (audited, 2026-04-29).** During a deep audit of WebDAV
behaviour against a 128 681-file source tree, `WebDavApiService.listAll`
accumulates entire PROPFIND XML responses into a single
`mutableListOf` for the entire BFS. JVM heap pressure from a
multi-MB string per directory (and many directories on a 128 681
file tree) is a candidate cause for the user's reported "long
unexplained pauses" — GC cycles on a near-full heap pause every
running thread, including the relocate.

**Root cause.** `WebDavApiService.kt:294, 321` — both PROPFIND-
response paths call `response.bodyAsText()`:

```kotlin
val xmlText = response.bodyAsText()  // entire body buffered
val parsed = parseMultistatus(xmlText)
```

For DSM serving a 5 000-entry directory, the multistatus XML easily
runs 1-3 MB. With Java's UTF-16 String storage, that's 2-6 MB on the
heap **per call**. `listAll`'s recursive BFS holds onto the entire
flattened result list:

```kotlin
fun listAll(path: String): List<DavResource> {
    val results = mutableListOf<DavResource>()
    walk(path, results)  // mutates `results` for every dir
    return results
}
```

For 128 681 entries, that's ~10-15 MB of `DavResource` objects (each
holding `path`, `etag`, `lastModified`, `contentLength`, `isCollection`).
Combined with the per-PROPFIND text buffering, peak heap during the
relocate's pre-scan can hit 100+ MB.

**Why this matches the symptom.** GC pauses on a 256-512 MB JVM
heap (default for `java -jar` without `-Xmx`) under that load can be
1–5 seconds. Several back-to-back can compound into a perceived
multi-second freeze with no log activity.

**Proposed fix.** Stream PROPFIND in two layers:

1. **Parse XML incrementally** via SAX or StAX (Java's built-in
   `XMLStreamReader`), emitting `DavResource` records as
   `<D:response>` elements close — never buffering the full XML.
2. **Return a `Flow<DavResource>`** from `listAll` instead of
   `List<DavResource>`. CloudRelocator already consumes via
   `.forEach`; minor `flow.collect` change at the call site.

```kotlin
fun listAll(path: String): Flow<DavResource> = flow {
    val resp = httpClient.request(...) {
        method = HttpMethod("PROPFIND")
        ...
    }
    resp.body<ByteReadChannel>().use { channel ->
        val parser = XMLStreamReaderFactory.create(channel.toInputStream())
        while (parser.hasNext()) {
            if (parser.next() == END_ELEMENT
                && parser.localName == "response") {
                emit(parseDavResource(parser))
            }
        }
    }
}
```

Caveat: needs a buffered StAX parser variant (Aalto, Woodstox)
because the JDK default `XMLInputFactory` allocates the full
character buffer up-front for some configurations. Aalto-XML is the
safe choice for true streaming.

**Acceptance.**
- New `WebDavApiServiceListAllStreamingTest`: stub a server that
  emits a 100 000-entry multistatus response chunked over the wire;
  assert peak heap allocation during `listAll` stays under 20 MB
  (use `-XX:+PrintGCDetails` + JFR if needed for the regression).
- New: assert `listAll` returns a `Flow` that the caller can
  `take(10)` from without buffering the rest of the tree.
- Update `WebDavProvider.listChildren` and `listAll` consumers
  (CloudRelocator, SyncEngine, ProfileEnumerationService) to consume
  the flow.

**Priority.** Medium. The user's reported "long pauses" are most
plausibly explained by UD-289 (sequential migration on hung
requests) and UD-287 (connection-pool poisoning) — fixing those
should make the GC-pause contribution invisible. This ticket
targets the *next* layer: when the visible bugs are gone, the
streaming gap will surface as the bottleneck on truly large trees.

**Out of scope for this ticket.**
- Other providers (S3, OneDrive, HiDrive, Internxt) likely have
  similar buffering. File companion tickets if they audit the
  same way.

**Related.**
- UD-329 — UD-329-class buffering bug that hit OneDrive download
  on byte arrays > 2 GiB. Same shape, different surface (XML vs
  binary).
- UD-289 — sequential relocate; reduces the heap pressure surface
  for now.
---
id: UD-330
title: Adopt HttpRetryBudget across HiDrive / Internxt / S3 / Rclone-stderr (UD-228 cross-cutting)
category: providers
priority: high
effort: L
status: open
opened: 2026-04-29
---
**Cross-cutting follow-up surfaced by the UD-318 / UD-319 / UD-322 /
UD-321 audits (Phase D of UD-228 split).**

Four of the five non-OneDrive providers ship **zero HTTP-layer
retries** despite the OneDrive baseline (UD-207 + UD-227 + UD-232)
having proven the pattern on a 130 k-item production sync (UD-712).
Every transient 429, 503, or network blip propagates as a fatal
exception to SyncEngine.

## Affected providers + audit citations

| Provider | Retry coverage | Audit reference |
|---|---|---|
| **HiDrive** | Zero — `checkResponse` throws on first non-2xx | [hidrive-robustness.md §2](../providers/hidrive-robustness.md#2-retry-placement) |
| **Internxt** | Partial — only `authenticatedGet` retries 500/503/EOF; every POST/PATCH/DELETE/PUT/CDN-PUT/streaming-GET is one-shot | [internxt-robustness.md §2](../providers/internxt-robustness.md#2-retry-placement) |
| **S3** | Zero (no AWS SDK; raw Ktor + manual SigV4) | [s3-robustness.md §2](../providers/s3-robustness.md#2-retry-placement) |
| **Rclone-wrapper** | Internal rclone retries are present, but stderr "retry warnings" on a successful exit-0 are discarded — sync that took 8 min looks identical to one that took 8 s in `unidrive.log` | [rclone-robustness.md §2](../providers/rclone-robustness.md#2-retry-placement-rclone-flag-passthrough) |

OneDrive baseline reference: [`HttpRetryBudget` at
core/app/core/.../HttpRetryBudget.kt](../../core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt).

## Proposal

Adopt `HttpRetryBudget` (or its config-lift sibling — see UD-262)
into each affected provider's HTTP entry points:

1. **HiDrive** — wrap `HiDriveApiService.authenticatedRequest` in
   the same retry loop shape as `GraphApiService.authenticatedRequest`.
2. **Internxt** — extend the existing `authenticatedGet` retry
   shape to every mutating verb. Encryption-vs-retry boundary
   (audit §4.3) MUST be designed around — IV-pinning means a
   naïve retry is silent-corruption territory.
3. **S3** — introduce a retry loop honouring both `Retry-After`
   header and `x-amz-retry-after-millis`. Multipart upload
   (currently absent — files >5 GB hard-fail at AWS cap) is a
   separate ticket but blocks meaningful retry for large objects.
4. **Rclone** — surface stderr retry warnings as `log.warn` rows
   when exit code is 0 but stderr contains backoff hints, so
   `unidrive.log` reflects what rclone actually did.

## Dependencies

- **UD-262** (HttpRetryBudget config surface lift) — prerequisite
  so per-provider overrides become possible. The current
  `MAX_THROTTLE_ATTEMPTS = 5` etc. constants in `GraphApiService`
  would otherwise be hard-coded into each new adopter.
- **UD-263** (per-provider concurrency hints) — sibling ticket;
  retry adoption and concurrency tuning land best together.

## Acceptance

- Each affected provider's API service has a retry loop on its
  HTTP entry point(s), wired to a shared `HttpRetryBudget`
  instance.
- 429 / 503 with `Retry-After` honoured.
- Cancellation propagates through the retry loop (UD-300 pattern).
- A regression test per provider that pins the retry contract
  (mock server emits 429 → 200; assert exactly one retry; assert
  `Retry-After` honoured).

## Priority / effort

**High priority, L effort.** This is the highest-impact remediation
across all 5 audit docs — closes the single biggest delta between
the OneDrive baseline and the rest.
---
id: UD-331
title: Cross-provider NonCancellable OAuth refresh wrap (HiDrive, Internxt)
category: providers
priority: medium
effort: S
status: open
opened: 2026-04-29
---
**Cross-cutting follow-up surfaced by the UD-318 + UD-319 audits
(Phase D of UD-228 split).**

Two of the three OAuth-bearing providers (HiDrive, Internxt) have
the OneDrive baseline mutex+double-check shape on token refresh
but **lack the UD-310 `withContext(NonCancellable) { ... }` wrap**.
A Pass-2 scope cancellation on a sibling coroutine's 401 can
abort the in-flight refresh mid-write to disk, leaving the
in-memory token disagreeing with what's persisted.

## Affected providers + audit citations

| Provider | NonCancellable wrap? | File:line |
|---|---|---|
| **OneDrive** (reference) | Yes | [TokenManager.kt:99](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/TokenManager.kt#L99) — `withContext(NonCancellable) { oauthService.refreshToken(refreshToken) }` |
| **HiDrive** | **No** | [hidrive-robustness.md §4](../providers/hidrive-robustness.md#4-idempotency) |
| **Internxt** | **No** | [internxt-robustness.md §4.2](../providers/internxt-robustness.md#4-idempotency) |

## Why it matters

UD-310 root cause (verbatim from OneDrive's TokenManager):
> Pass-2 scope cancel on a sibling's 401 was cancelling the
> in-flight refresh as collateral damage. The `refresh_token`
> itself was perfectly valid; cancellation chewed through the
> flake budget logging the misleading "ScopeCoroutine was
> cancelled" and falling through to the re-auth error.

For HiDrive and Internxt this manifests as: a sync hits a 401 on
worker A, the scope is cancelled to retry the file, worker B's
in-flight refresh is cancelled, the refresh `oauthService.refreshToken(...)`
half-completes (network call out, response came back, `saveToken`
to disk did NOT run because cancellation interrupted between
the receive and the file-write). Next session loads a stale
token, gets 401, refreshes again — eventually thrashes.

## Proposal

In each affected `TokenManager.refreshToken` (or equivalent), wrap
the actual refresh call in `withContext(NonCancellable) { ... }`.
The mutex-guarded outer block stays on the parent context; the
inner refresh becomes atomic w.r.t. cancellation.

```kotlin
val refreshed =
    withContext(NonCancellable) {
        oauthService.refreshToken(refreshToken)
    }
token = refreshed
oauthService.saveToken(refreshed)
return refreshed
```

## Acceptance

- Each affected provider's refresh path uses `NonCancellable`.
- A test per provider that cancels the parent scope mid-refresh
  and asserts the refresh completes anyway (saveToken is called).
  Pattern: copy `OneDriveTokenManagerTest`'s NonCancellable
  test where it exists, or write a new one against a mock
  OAuth service.

## Priority / effort

**Medium priority, S effort.** Each provider is a one-line wrap
change plus one test. Could be bundled into one PR across the
two providers.

## Related

- **UD-310** (closed) — original OneDrive fix; the canonical
  example to copy.
- **UD-330** (sibling cross-cutting follow-up) — HttpRetryBudget
  adoption. Lands well alongside since both touch
  TokenManager / authenticatedRequest call sites.
---
id: UD-332
title: HiDrive download path uses UD-329 byte[contentLength] anti-pattern; >2 GB OOM
category: providers
priority: high
effort: S
status: open
opened: 2026-04-29
---
**Cross-cutting follow-up surfaced by the UD-318 audit (Phase D of
UD-228 split).**

`HiDriveApiService.download` uses the **UD-329 anti-pattern**:
`response.body<ByteReadChannel>()` after a buffered `httpClient.get(url)`
call. On Ktor 3.x this buffers the entire response body into a
single `byte[contentLength]` array before the channel is exposed.
Files larger than `Integer.MAX_VALUE` (~2.147 GiB) fail at
allocation time:

```
java.lang.OutOfMemoryError: Can't create an array of size N
```

Symptom is identical to the original UD-329 OneDrive incident
(closed) — same Ktor mechanism, same anti-pattern, just a different
provider that didn't get the fix when OneDrive did.

## Affected files

- [HiDriveApiService.kt](../../core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt)
  — download path. See [hidrive-robustness.md "field observations"](../providers/hidrive-robustness.md#hidrive--field-observations).

## Proposal

Mirror the closed UD-329 OneDrive fix at
[GraphApiService.kt:235-296](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt#L235):

```kotlin
val statement = httpClient.prepareGet(url) { ... }
statement.execute { response ->
    // ... checks ...
    val channel: ByteReadChannel = response.bodyAsChannel()
    withContext(Dispatchers.IO) {
        Files.newOutputStream(destPath, ...).use { out ->
            val buf = ByteArray(8192)
            while (true) {
                val n = channel.readAvailable(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
    }
    DownloadOutcome.Done
}
```

8 KiB ring buffer is the only memory the download holds; allocation
no longer scales with file size.

## Acceptance

- HiDrive download path uses `prepareGet(url).execute { ... }`
  pattern.
- Regression test that mocks a 3 GB response body (or asserts that
  `bodyAsChannel()` is called via `prepareGet(url).execute { }`,
  not via buffered `body()`).
- Bonus: extract the streaming-download primitive into a shared
  helper in `:app:core` so future providers don't re-introduce
  the anti-pattern. (Defer to a follow-on if scope-creeps.)

## Priority / effort

**High priority, S effort.** ~30 lines of code change. High
priority because it's silent until a user uploads a >2 GB file,
and then it's a hard OOM crash — not a graceful failure.

## Related

- **UD-329** (closed) — original OneDrive fix; the canonical
  example to copy.
- **UD-293** (open, M, high) — GraphApiService still buffers
  byte[] for >2 GB downloads on a non-downloadFile path. Likely
  the same anti-pattern lurks on the OneDrive `simple-download`
  fallback that UD-329 didn't reach. Worth checking sibling-style.
---
id: UD-333
title: UD-231 HTML-body guard missing on HiDrive + Internxt download (Internxt = silent corruption)
category: providers
priority: high
effort: M
status: open
opened: 2026-04-29
---
**Cross-cutting follow-up surfaced by the UD-318 + UD-319 audits
(Phase D of UD-228 split).**

UD-231's HTML-body guard on the OneDrive download path is the
single line that prevents a captive-portal page (CDN throttle
"please wait" page, expired-URL login page) from streaming
straight into the destination at the matching byte count and
silently corrupting the local file. Two of the five non-OneDrive
providers ship the same download path **without** the guard.

## Affected providers + audit citations

| Provider | HTML-body guard? | Severity |
|---|---|---|
| **OneDrive** (reference) | Yes — [GraphApiService.kt:275](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt#L275) | — |
| **HiDrive** | **No** | Medium — captive-portal HTML written verbatim to disk; UD-226 NUL-stub sweep doesn't flag it because HTML is non-NUL content |
| **Internxt** | **No** | **High — silent corruption** | 

The Internxt severity is the headline. Internxt downloads pass
the response body through `Cipher.update()` (AES-CTR
decryption — see [internxt-robustness.md §4.3](../providers/internxt-robustness.md#4-idempotency))
**before** writing to disk. A captive-portal HTML page XOR'd
through the AES-CTR keystream produces byte output that is:

- The right length (matches the encrypted-file's expected size).
- High-entropy (XOR with a strong keystream looks random).
- **Indistinguishable from a real decrypted file** — no NUL
  patterns, no HTML tags, no plaintext signatures.

UD-226 sweep detection catches NUL-stub corruption. It does NOT
catch AES-CTR-encrypted HTML. The user's local file is permanently
wrong, no observable signal until they open it.

## Proposal

Mirror UD-231 across the affected provider download paths. Before
ANY decryption, transform, or write-to-disk, check
`Content-Type`:

```kotlin
val contentType = response.contentType()
if (contentType != null && contentType.match(ContentType.Text.Html)) {
    val snippet = truncateErrorBody(response.bodyAsText().take(200))
    throw java.io.IOException(
        "Download returned HTML instead of file bytes (status=${response.status.value}, " +
            "Content-Type=$contentType): $snippet",
    )
}
```

For Internxt specifically: this guard MUST be applied **before** the
`Cipher.update()` call in the streaming-decrypt loop, otherwise
the AES-CTR keystream has already been advanced and the IV state
is invalid for any retry.

## Acceptance

- HiDrive + Internxt download paths reject 200 + `Content-Type:
  text/*` responses before any byte hits disk.
- Internxt's guard sits before the AES-CTR `Cipher.update()`.
- Test per provider that mocks a 200 response with a small HTML
  body and asserts the download throws (not silently succeeds).

## Priority / effort

**High priority, M effort.** ~15 lines per provider plus the
Internxt-specific cipher-ordering surgery. High priority because
the Internxt failure mode is silent corruption — the user has no
observable signal that their files are wrong until they try to
open them.

## Related

- **UD-231** (closed) — original OneDrive fix; the canonical
  example.
- **UD-226** — NUL-stub sweep; complementary detection but does
  not catch this case.
- **UD-330** (sibling cross-cutting follow-up) — HttpRetryBudget
  adoption. The HTML-body guard is part of "what does
  retry/non-retry classification look like" so the two tickets
  land naturally together.
---
id: UD-334
title: Cross-provider structured error parsing + truncateErrorBody lift to :app:core
category: providers
priority: medium
effort: M
status: open
opened: 2026-04-29
---
**Cross-cutting follow-up surfaced by the UD-318 / UD-319 / UD-322
/ UD-323 audits (Phase D of UD-228 split).**

Outside OneDrive's `truncateErrorBody` + `parseRetryAfterFromJsonBody`
pair, four of the five non-OneDrive providers stringify the entire
error response body into the exception message. This produces two
problems:

1. **Log noise.** SharePoint-style HTML error pages (or S3 XML, or
   Internxt JSON) bloat `unidrive.log`. One SharePoint 503 dumps
   ~60 lines of inline CSS + branding. The `truncateErrorBody`
   log-noise guard solved this on OneDrive.
2. **Lost diagnostic detail.** The structured fields the
   provider's API returns — `<RequestId>`/`<HostId>` for S3
   support tickets, `error.retryAfterSeconds` for Graph CDN-edge
   throttle hints, `error_description` for HiDrive OAuth — get
   buried inside a giant string and never reach the operator
   in usable form.

## Affected providers + audit citations

| Provider | `truncateErrorBody` analogue? | Structured field extraction? | Audit |
|---|---|---|---|
| **OneDrive** (reference) | Yes | `error.retryAfterSeconds`, `error.code`, `error.message` | — |
| **HiDrive** | **No** | None — `bodyAsText()` raw into exception | [hidrive §1](../providers/hidrive-robustness.md#1-non-2xx-body-parsing) |
| **Internxt** | **No** | None — `bodyAsText()` raw | [internxt §1](../providers/internxt-robustness.md#1-non-2xx-body-parsing) |
| **S3** | **No** | Only `<Code>` + `<Message>`; `<RequestId>`/`<HostId>`/`<Resource>` dropped | [s3 §1](../providers/s3-robustness.md#1-non-2xx-body-parsing) |
| **SFTP** | N/A (no HTTP body) | Only 4 of ~25 SSH_FX_* status codes branched | [sftp §1](../providers/sftp-robustness.md#1-error-parsing) |

## Proposal

Two-part change:

### Part A — extract `truncateErrorBody` to shared `:app:core`

The OneDrive helper at
[GraphApiService.kt:841](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt#L841)
is provider-agnostic — it just does first-line + char-count tail
for non-JSON bodies. Move to `core/app/core/.../http/ErrorBodyTruncate.kt`.
Wire into HiDrive, Internxt, S3 exception construction.

### Part B — per-provider structured-field extraction

| Provider | Fields to extract | Surface as |
|---|---|---|
| HiDrive | `error`, `error_description` (OAuth-style JSON) | `HiDriveApiException(message, code, description)` |
| Internxt | top-level `error` field | `InternxtApiException(message, errorCode)` |
| S3 | `<Code>`, `<Message>`, `<RequestId>`, `<HostId>`, `<Resource>` | `S3Exception(message, code, requestId, hostId, resource)` — RequestId/HostId in particular for AWS support tickets |
| SFTP | All 25 `SSH_FX_*` status codes mapped to typed exceptions | `SftpException` subclasses (PermissionDenied, QuotaExceeded, ConnectionLost, OpUnsupported, etc.) — drives retry-vs-fatal classification |

## Acceptance

- `truncateErrorBody` lives in `:app:core` and is used by ≥ 3
  providers.
- HiDrive / Internxt / S3 exception types carry structured fields
  beyond a single message string.
- SFTP differentiates SSH_FX_FAILURE (overloaded by OpenSSH for
  benign mkdir-on-existing) from real failures — see
  [sftp-robustness.md §1](../providers/sftp-robustness.md#1-error-parsing).
- A test per provider that asserts the structured fields
  round-trip from a fixture response into the exception object.

## Priority / effort

**Medium priority, M effort.** Each provider is ~30-50 lines of
code + tests. Bigger lift than the `NonCancellable` wrap (UD-331)
but smaller than the full retry-loop adoption (UD-330).

## Related

- **UD-330** (sibling) — HttpRetryBudget adoption. Lands well
  together since retry classification depends on structured
  error fields.
- **UD-228** (umbrella) — the cross-provider audit ticket this
  follow-up was filed against.
---
id: UD-295
title: state.db reuse across profile rename / restore (UD-223 Part B)
category: core
priority: medium
effort: M
status: open
opened: 2026-04-30
---
**Split out from UD-223 Part B.** UD-223 Part A (`?token=latest`
blind bootstrap) landed in `7211b25`; this ticket carries the
remaining "state.db reuse across profile rename" work.

## Problem

The user renames `onedrive-test` → `onedrive2` in `config.toml` and
moves the profile dir, OR recovers from an accidental `state.db`
delete with a backup. The existing cursor + sync_entries are still
valid because OneDrive's drive ID and item IDs don't change. Today,
unidrive simply notices `state.db` is missing and re-enumerates
the drive — burning the 11-minute first-sync time + Graph throttle
budget for nothing.

## Required surface

At profile-resolve time, when `state.db` for this profile name
doesn't exist:

1. Authenticate the current profile and call a new method
   `CloudProvider.accountFingerprint(): String?` (default null;
   OneDrive returns `getDrive().id`, e.g. `3D49B6B2BAE9CB17`).
2. Walk sibling profile dirs under `<configBaseDir>/<profile-name>/`
   looking for ones whose `state.db.sync_state["account_fingerprint"]`
   matches the current fingerprint.
3. If a match is found and the run is interactive, prompt:
   ```
   Profile 'onedrive2' is new but the OneDrive account matches
   existing profile 'onedrive-test' (drive id 3D49B6B2BAE9CB17).
   Reuse its sync state? (y/N)
   ```
4. On `y` — rename the source dir to the new profile name (atomic;
   verify no in-flight sync is using it via `ProcessLock`).
5. On `N` or headless — fresh state. Optionally emit a one-line
   WARN with the actionable hint so the user can do it manually.

## Foundation work (prerequisite)

- `CloudProvider` gains `suspend fun accountFingerprint(): String? =
  null` — default no-op for providers that don't have a stable
  account ID concept (LocalFs, SFTP, WebDAV-without-quota).
- `OneDriveProvider` overrides to return `graphApi.getDrive().id`.
- HiDrive + Internxt: similar overrides — HiDrive's user ID,
  Internxt's user_uuid. (Filed as follow-up if not in initial PR.)
- A new `sync_state["account_fingerprint"]` key written on first
  successful authenticate + every subsequent (cheap idempotent
  upsert).

## Acceptance

- Renaming a profile and re-running `unidrive sync` adopts the
  existing state.db on user `y` confirmation.
- Headless run with no matching sibling: pre-fix behaviour (fresh
  enumeration).
- Headless run with matching sibling: pre-fix behaviour + new
  WARN one-liner pointing the user at the manual move.
- Test: 2-profile fixture with the same fake fingerprint;
  Main.resolveProfile returns the renamed profile dir on `y`,
  rejects on `N`.

## Related

- **UD-223** — parent. Part A (`?token=latest`) is the perf win
  that makes profile rename / reuse the natural follow-up.
- **UD-272** — closed; ProcessLock surfaces holder PID. Required
  for the atomic-rename safety check.
- **UD-280** — closed lesson. The dir-walk should NOT confuse
  benign JVM-internal files for profile state.

## Priority / effort

**Medium priority, M effort.** UX nicety, not a correctness bug.
Worth scheduling alongside any future "profile management" work
(UD-236 three-verb model, UD-233 tray brainstorm).
