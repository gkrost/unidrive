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
id: UD-111
title: Token refresh-failure telemetry + user notification
category: security
priority: medium
effort: M
status: open
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt
  - core/providers/hidrive
  - core/providers/internxt
adr_refs: [ADR-0004]
opened: 2026-04-17
chunk: ipc-ui
---
OAuth refresh failures currently surface only as exception traces. Emit a structured log event and expose via MCP `status` so a user-facing client can prompt for re-auth.

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
id: UD-704-residual
title: Test-hygiene residuals — brittle error-message witness + CI lint
category: tests
priority: low
effort: S
status: open
code_refs:
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/ToolHandlerTest.kt:818
adr_refs: [ADR-0005]
opened: 2026-04-17
---
The bulk of UD-704 landed with UD-301 (10 capability-cementing tests rewritten to `CapabilityResult.Unsupported` assertions) and the earlier CliSmokeTest `assumeTrue` conversion (commit c5bc05d). Two residuals remain:

1. **Brittle error-message witness (1 test):** `ToolHandlerTest.relocate - same source and dest returns error` asserts `.contains("Failed to create source provider")` — an implementation detail. Change to `isError + contains("same")` or a cleaner behavior assertion.

2. **CI lint:** add `scripts/ci/test-hygiene.sh` with grep rules banning `if.*!.*exists.*return` in test files and flagging suspicious `contains("not yet")` / `contains("does not support")` patterns. Keep it cheap (grep + exit code); wire into CI once UD-701 picks a host.

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
id: UD-214
title: Offline-friendly quota — cache last-seen, show "as of T" when offline
category: core
priority: low
effort: S
status: open
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
opened: 2026-04-18
chunk: core
---
Surfaced alongside UD-212/213: `unidrive quota` is a live call. When the network is down, status/tray/log should still show the last successful quota snapshot with a "stale since T" label rather than failing or going silent. Persist the quota tuple + fetch timestamp in `state.db` on every successful call.

---

---

---

---
id: UD-307
title: OneDrive rejects ZWJ-compound emoji filenames with 409 nameAlreadyExists
category: providers
priority: low
effort: S
status: open
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
  - docs/SPECS.md
opened: 2026-04-18
chunk: core
---
Reproduced in UD-712: uploading `👨‍👩‍👧.txt` (U+1F468 ZWJ U+1F469 ZWJ U+1F467) via Graph `PUT /me/drive/root:/<path>/<name>:/content` returns HTTP 409 `nameAlreadyExists` with message *"The specified item name is incompatible with a similar name on an existing item."* — even when no prior item with that name exists in the folder. OneDrive normalises or collapses ZWJ sequences internally and the resulting name collides with something (possibly the individual emoji characters in its search/index layer, or OneDrive's own NFC-folded form). The other 14 entries in the test set (¡, æ, ñ, Ω, Ж, א, ع, क, ก, ☃, あ, 中, 글, 🎉) upload cleanly and round-trip byte-equal.

**Rescoped 2026-04-20 to option C** (from the original A/B/C direction set). The original scope covered three options: (a) surface a structured `NameNormalisationCollision` error; (b) auto-retry with NFC-normalised form; (c) document in SPECS as a known OneDrive limitation. ZWJ-compound emoji filenames are vanishingly rare in real sync sets — the cost of options A/B (new error type + policy plumbing + UI knob, or extra Graph round-trip) isn't justified by observed frequency.

**Acceptance (option C only):**
1. Document the limitation in `docs/SPECS.md` under a "Known provider limitations — OneDrive" section: ZWJ-compound emoji filenames may 409; no workaround short of renaming the source file.
2. On 409 `nameAlreadyExists` for a path that has no prior entry in `sync_entries`, emit a WARN log with filename + 409 detail, skip the file, continue the sync.
3. No user-facing error, no policy knob, no new error type.

Effort dropped from M to S. Revisit only if real-world telemetry surfaces the case often.

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
id: UD-807
title: TLS + `trust_all_certs` branch coverage
category: tests
priority: low
effort: S
status: open
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceSslTest.kt
opened: 2026-04-18
chunk: sg5
---
`trust_all_certs = true` in WebDAV now activates an Apache5 engine path (UD-326, fixed
2026-04-21 — CIO triggers a fatal ProtocolVersion alert on Synology DSM 7.x; Java engine
hardcodes HTTPS hostname verification with no override). The branch builds a
`PoolingAsyncClientConnectionManager` pre-wired with `ClientTlsStrategyBuilder` +
`NoopHostnameVerifier.INSTANCE` and a permissive `X509TrustManager`.

`WebDavApiServiceSslTest` has two structural smoke-tests (both branches construct without
throwing) but exercises no actual TLS handshake. Add a second docker-compose profile with
a self-signed HTTPS Nginx/WebDAV variant and a matching integration-test class that:
  1. Verifies the Apache5 branch activates (`trust_all_certs = true`).
  2. Confirms a PROPFIND → upload → PROPFIND round-trip succeeds over HTTPS with a self-signed cert.
  3. Confirms `trust_all_certs = false` fails the handshake (negative case).

Synology-specific note for the test author: the DSM 7.x WebDAV Server returns no ETag
header on PUT responses, so `remote_hash` will be NULL in state.db — expected; not a
bug in the test fixture.

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
id: UD-811
title: Assertion-quality hardening — quota regex, image-size budget, failure reporting
category: tests
priority: low
effort: S
status: open
code_refs:
  - core/docker/test-providers.sh
  - .github/workflows/build.yml
opened: 2026-04-18
---
(a) Harness `quota` regex matches `"unlimited"/"0 bytes"` — would pass a broken adapter. Tighten to: parse response as `QuotaInfo`, assert `total > 0` and `used >= 0`. (b) No CI-minute / image-size budget tracked; add an informational step that prints `docker image inspect` size + compares against a committed baseline. (c) Harness emits pass/fail lines only; add machine-readable `report.json` so regressions surface in CI summaries.
---
id: UD-223
title: Fast first-sync — `?token=latest` blind bootstrap + state.db reuse across profile rename
category: core
priority: high
effort: M
status: open
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt
opened: 2026-04-18
chunk: core
---
User intuition during UD-712 (346 GB OneDrive, ~130 k items, ~80 % static): "most of the drive just sits there. Can we skip the 11-min first-sync enumeration?" Answer: state.db itself is not portable (it's bound to a specific drive ID + an opaque Graph-issued delta cursor + account-specific item IDs — so no cross-account reuse, no shipping to other users), but the underlying optimisation the user is asking for exists as a Graph-specific feature.

## Two related optimisations

### A. `?token=latest` blind bootstrap (OneDrive-specific)

`GET /me/drive/root/delta?token=latest` returns in ≈1 s with a cursor that represents **now**, zero items enumerated. Subsequent delta calls return only changes since then. Unidrive would skip the 11-min walk entirely.

**Tradeoff (must be loud in UX):** unidrive has no view of remote items that currently exist. Anything that only lives remotely stays invisible until it mutates and shows up in the next delta. For a mostly-static drive the user wants local-only additions to propagate (upload direction), which is fine. For users who want a full local mirror, they should NOT pick fast-bootstrap.

**Surface:** new `unidrive -p x sync --fast-bootstrap` (or `profile add --fast-bootstrap` at profile-creation time). Logs emit a clear "using remote state snapshot as of 2026-04-18T12:34Z; items that existed before this timestamp will not be seen until next mutation" warning.

**Provider matrix:**
  * OneDrive / Graph: yes (`?token=latest`).
  * Box, Dropbox (if added): yes (similar `cursor=latest` semantics).
  * Rclone-wrapped backends: inherit whatever the upstream supports; rclone's own `--no-traverse` flag is the closest equivalent.
  * S3, SFTP, WebDAV, HiDrive, Internxt: no — no server-maintained delta token exists. Fast-bootstrap flag is a no-op + warning on these.

### B. state.db reuse across profile rename / restore (same account)

If the user renames `onedrive-test` → `onedrive2` in config.toml and moves the profile dir, the existing cursor + sync_entries stay valid because drive ID + item IDs don't change. Same for recovering from an accidental `state.db` delete if a backup exists. Today, unidrive simply refuses an invalid profile name and emits no hint that an existing state dir under a different name could be adopted.

**Surface:** at profile resolve time, if `state.db` for this profile name doesn't exist but a sibling profile dir has a cursor tagged with the same drive ID (readable from token.json → a one-line Graph call), offer: `Profile 'onedrive2' is new but account matches existing profile 'onedrive-test' (drive id 3D49B6B2BAE9CB17). Reuse its sync state? (y/N)`. On `y`, rename the on-disk dir. On N / headless, fresh state.

## Observed numbers from UD-712 to inform the feature

  * Full first-sync enumeration on 346 GB / 129 933 items: **695 s** (2026-04-18 run).
  * Graph Delta metadata throughput observed: ~187 items/s, ~1.8 MB/s (metadata JSON).
  * For a user with 1 M items that's ~90 min vs 1 s via `?token=latest`. Not a subtle win.

## Related

  * UD-713 (first-sync ETA probe) — `?token=latest` makes the ETA a moot point; the features overlap but both stay in-scope because an operator who wants a full mirror still needs an ETA.
  * UD-220 (consequences docs) — fast-bootstrap's invisibility-until-mutation tradeoff belongs in the Consequences section.
  * UD-222 (adopt writes zeros) — orthogonal; `?token=latest` means the adopt-placeholder storm wouldn't happen at all on a fresh profile because remote items are unknown.

## Progress

  * **Part A landed in `7211b25` (2026-04-19):** `--fast-bootstrap` CLI flag + per-profile `fast_bootstrap` config key + `Capability.FastBootstrap` + `CloudProvider.deltaFromLatest()` + OneDrive override via `GraphApiService.getDelta(fromLatest=true)`. Non-OneDrive providers log a WARN and fall through. Engine promotes the adopted cursor directly to `delta_cursor` (not `pending_cursor`) because `syncOnce` short-circuits on empty action list. Tests: `DeltaFromLatestTest` (MockEngine URL assertions), `SyncEngineTest` UD-223 trio, `SyncConfigTest` round-trip.
  * **Part B still pending:** state.db reuse across profile rename when the drive ID in `token.json` matches an existing sibling profile's cursor. Not yet scoped into a commit; ticket stays `open`.

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
id: UD-261
title: status CLOUD column uses enumerated file sizes instead of quota.used — under-reports OneDrive usage
category: core
priority: medium
effort: S
status: open
opened: 2026-04-20
chunk: core
---
## Observed

`unidrive status --all` shows **164 GB** in the CLOUD column for `onedrive-test`
(81 185 files, last sync: never). Actual OneDrive quota used is **~349 GB**.

```
│  └─ onedrive-test  │ [✓ 0h] │ 81.185 │ 0 │ 164 GB │ 164 GB │ never │
```

## Root cause

`buildAccountRow()` ([StatusCommand.kt:275](core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt)) computes CLOUD as:

```kotlin
val totalRemoteSize = entries.filter { !it.isFolder }.sumOf { it.remoteSize }
```

`remoteSize` comes from `DriveItem.size` via Graph — the **download size** of each file.
OneDrive's quota usage also includes revision history, the recycle bin, and OneNote
metadata. These never appear as discrete `DriveItem` entries, so the sum always
under-reports against `Drive.quota.used`.

`quota.used` is already fetched via `provider.quota()` in the `runAudit()` path
([StatusCommand.kt:116–124](core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt))
but that result never reaches `buildAccountRow()`.

## Expected

For providers that declare `Capability.QuotaExact`, `CLOUD` should show
`quota.used` (the authoritative figure from `GET /me/drive`), not the sum of
enumerated file sizes.

## Fix sketch

Pass a pre-fetched `QuotaInfo?` into `buildAccountRow()` (or call `provider.quota()`
inside it for QuotaExact providers). Use `quota.used` for the `cloudSize` field when
non-null; fall back to `totalRemoteSize` for non-quota providers (SFTP, WebDAV).

One extra Graph round-trip per QuotaExact account per `status` invocation —
acceptable since the command already authenticates.

## Acceptance

- `status --all` for `onedrive-test` shows ≈ 349 GB in CLOUD column.
- SFTP/WebDAV accounts are unaffected (no `QuotaExact` capability).
- No regression on `onedrive` account (should still match real quota.used).
---
id: UD-100
title: Enable Gradle dependency locking for core/ + ui/ composites
category: security
priority: medium
effort: S
status: open
opened: 2026-04-20
chunk: sg5
---
Prerequisite for any lockfile-based security scanning (Trivy, Grype) and
for Dependabot's ability to pin transitive versions deterministically.

**Current state:** no `gradle.lockfile` exists anywhere in the repo, and no
`dependencyLocking { ... }` block is configured in either composite's
build scripts. Builds resolve transitive dependencies at build time with
no recorded graph.

**Acceptance:**

1. Enable `dependencyLocking { lockAllConfigurations() }` in both composites'
   root build.gradle.kts (typically via a `subprojects` / `allprojects` block
   so every module participates).
2. Generate `gradle.lockfile` via `./gradlew dependencies --write-locks`
   from both `core/` and `ui/`; commit the lockfiles.
3. Add CI step (`./gradlew dependencies --verify-locks`) to the GitHub
   Actions build workflow so any unexpected version drift fails the build.
4. Document the regeneration procedure in `docs/dev/` (e.g., when bumping
   a `libs.versions.toml` entry).

**Why this exists as its own ticket:** UD-108 was originally "Trivy on dep
lockfiles + container images". Split 2026-04-20 — the Docker half lives
in UD-101 (Trivy scanning on Docker images) and unblocks now; the lockfile
half needs this ticket first.
---
id: UD-263
title: Per-provider concurrency hints + SyncEngine Pass 2 semaphore wiring
category: core
priority: medium
effort: S
status: open
opened: 2026-04-20
chunk: core
---
**Part of UD-228 split.**

SyncEngine Pass 2 currently uses a hardcoded 16/6/2 concurrency split
across providers. Per UD-228's findings, that's too blunt: Graph docs
recommend ≤10 req/s per app-per-user (~6–8 concurrent downloads), S3
handles thousands, SFTP typically 4–8, WebDAV varies by server.

**Acceptance:**
1. Extend `ProviderRegistry.getMetadata` (or the equivalent capability/
   metadata API) with `maxConcurrentTransfers: Int` and
   `minRequestSpacingMs: Long` hints.
2. Per-provider values sourced from the audit docs produced by
   UD-318..UD-324.
3. `SyncEngine` Pass 2 replaces its hardcoded `16/6/2` with a lookup from
   provider metadata; semaphores sized per-provider per-run.
4. Token-bucket for `minRequestSpacingMs` is stateless across invocations,
   acceptable tradeoff for simplicity.

**Depends on:** UD-318..UD-324 (audit docs produce the values).
**Relates to:** UD-262 (HttpRetryBudget extract — independent, can land either order).
---
id: UD-318
title: HiDrive HTTP robustness audit
category: providers
priority: high
effort: S
status: open
opened: 2026-04-20
chunk: sg5
---
**Part of UD-228 split — per-provider HTTP robustness audit.**

**Audit scope (same across UD-318..UD-324 — see UD-228 for full rationale):**

1. **Non-2xx body parsing** — does this provider extract structured detail
   (retry hints, quota info, recoverable-vs-fatal distinction) from error
   bodies, or just stringify the Ktor exception?
2. **Retry placement** — at HTTP layer (transparent to SyncEngine) or at
   SyncEngine action layer (fatal on non-whitelisted errors)?
3. **Retry-After source** — header, body, both, or X-RateLimit-* family?
4. **Idempotency** — is the authenticatedRequest equivalent body-replay
   safe?
5. **Concurrency recommendations** — `maxConcurrentTransfers` +
   `minRequestSpacingMs` based on provider docs + observed behaviour.

**Deliverable:** `docs/providers/hidrive-robustness.md`.

**Consumed by:**
- UD-262 — findings inform `HttpRetryBudget` config surface
- UD-263 — findings produce concurrency hint values

**HiDrive specifics to check: X-RateLimit-* headers, JSON error body conventions, Strato endpoint quirks.**
---
id: UD-319
title: Internxt HTTP robustness audit
category: providers
priority: high
effort: S
status: open
opened: 2026-04-20
chunk: sg5
---
**Part of UD-228 split — per-provider HTTP robustness audit.**

**Audit scope (same across UD-318..UD-324 — see UD-228 for full rationale):**

1. **Non-2xx body parsing** — does this provider extract structured detail
   (retry hints, quota info, recoverable-vs-fatal distinction) from error
   bodies, or just stringify the Ktor exception?
2. **Retry placement** — at HTTP layer (transparent to SyncEngine) or at
   SyncEngine action layer (fatal on non-whitelisted errors)?
3. **Retry-After source** — header, body, both, or X-RateLimit-* family?
4. **Idempotency** — is the authenticatedRequest equivalent body-replay
   safe?
5. **Concurrency recommendations** — `maxConcurrentTransfers` +
   `minRequestSpacingMs` based on provider docs + observed behaviour.

**Deliverable:** `docs/providers/internxt-robustness.md`.

**Consumed by:**
- UD-262 — findings inform `HttpRetryBudget` config surface
- UD-263 — findings produce concurrency hint values

**Internxt specifics: encrypted-at-rest model, bridge vs drive API distinction, custom error envelope.**
---
id: UD-320
title: OneDrive HTTP robustness audit (reference / baseline)
category: providers
priority: high
effort: S
status: open
opened: 2026-04-20
chunk: sg5
---
**Part of UD-228 split — per-provider HTTP robustness audit.**

**Audit scope (same across UD-318..UD-324 — see UD-228 for full rationale):**

1. **Non-2xx body parsing** — does this provider extract structured detail
   (retry hints, quota info, recoverable-vs-fatal distinction) from error
   bodies, or just stringify the Ktor exception?
2. **Retry placement** — at HTTP layer (transparent to SyncEngine) or at
   SyncEngine action layer (fatal on non-whitelisted errors)?
3. **Retry-After source** — header, body, both, or X-RateLimit-* family?
4. **Idempotency** — is the authenticatedRequest equivalent body-replay
   safe?
5. **Concurrency recommendations** — `maxConcurrentTransfers` +
   `minRequestSpacingMs` based on provider docs + observed behaviour.

**Deliverable:** `docs/providers/onedrive-robustness.md`.

**Consumed by:**
- UD-262 — findings inform `HttpRetryBudget` config surface
- UD-263 — findings produce concurrency hint values

**OneDrive is the reference implementation (UD-207/UD-227 closed). The audit here is a write-down of the existing behaviour in GraphApiService + ThrottleBudget so the other provider audits can compare against a canonical baseline. Expect this to be the smallest audit — mostly docs extraction.**
---
id: UD-321
title: Rclone wrapper robustness audit
category: providers
priority: medium
effort: S
status: open
opened: 2026-04-20
chunk: sg5
---
**Part of UD-228 split — per-provider HTTP robustness audit.**

**Audit scope (same across UD-318..UD-324 — see UD-228 for full rationale):**

1. **Non-2xx body parsing** — does this provider extract structured detail
   (retry hints, quota info, recoverable-vs-fatal distinction) from error
   bodies, or just stringify the Ktor exception?
2. **Retry placement** — at HTTP layer (transparent to SyncEngine) or at
   SyncEngine action layer (fatal on non-whitelisted errors)?
3. **Retry-After source** — header, body, both, or X-RateLimit-* family?
4. **Idempotency** — is the authenticatedRequest equivalent body-replay
   safe?
5. **Concurrency recommendations** — `maxConcurrentTransfers` +
   `minRequestSpacingMs` based on provider docs + observed behaviour.

**Deliverable:** `docs/providers/rclone-robustness.md`.

**Consumed by:**
- UD-262 — findings inform `HttpRetryBudget` config surface
- UD-263 — findings produce concurrency hint values

**Rclone provider is a process-exec wrapper, not a direct HTTP client. Audit scope narrows to: exit-code handling, stderr-parsing for recoverable errors, subprocess lifecycle + cancellation. Retry-After / concurrency hints inherit from the wrapped rclone process (--transfers, --tpslimit flags).**
---
id: UD-322
title: S3 HTTP robustness audit
category: providers
priority: medium
effort: S
status: open
opened: 2026-04-20
chunk: sg5
---
**Part of UD-228 split — per-provider HTTP robustness audit.**

**Audit scope (same across UD-318..UD-324 — see UD-228 for full rationale):**

1. **Non-2xx body parsing** — does this provider extract structured detail
   (retry hints, quota info, recoverable-vs-fatal distinction) from error
   bodies, or just stringify the Ktor exception?
2. **Retry placement** — at HTTP layer (transparent to SyncEngine) or at
   SyncEngine action layer (fatal on non-whitelisted errors)?
3. **Retry-After source** — header, body, both, or X-RateLimit-* family?
4. **Idempotency** — is the authenticatedRequest equivalent body-replay
   safe?
5. **Concurrency recommendations** — `maxConcurrentTransfers` +
   `minRequestSpacingMs` based on provider docs + observed behaviour.

**Deliverable:** `docs/providers/s3-robustness.md`.

**Consumed by:**
- UD-262 — findings inform `HttpRetryBudget` config surface
- UD-263 — findings produce concurrency hint values

**S3 specifics: AWS SDK already provides retry policies + backoff — audit whether we use the SDK defaults or override. 503 SlowDown handling, date-formatted Retry-After. Concurrency: S3 handles thousands of parallel requests; likely a soft cap of 64-128 is fine.**
---
id: UD-323
title: SFTP transport robustness audit
category: providers
priority: medium
effort: S
status: open
opened: 2026-04-20
chunk: sg5
---
**Part of UD-228 split — per-provider HTTP robustness audit.**

**Audit scope (same across UD-318..UD-324 — see UD-228 for full rationale):**

1. **Non-2xx body parsing** — does this provider extract structured detail
   (retry hints, quota info, recoverable-vs-fatal distinction) from error
   bodies, or just stringify the Ktor exception?
2. **Retry placement** — at HTTP layer (transparent to SyncEngine) or at
   SyncEngine action layer (fatal on non-whitelisted errors)?
3. **Retry-After source** — header, body, both, or X-RateLimit-* family?
4. **Idempotency** — is the authenticatedRequest equivalent body-replay
   safe?
5. **Concurrency recommendations** — `maxConcurrentTransfers` +
   `minRequestSpacingMs` based on provider docs + observed behaviour.

**Deliverable:** `docs/providers/sftp-robustness.md`.

**Consumed by:**
- UD-262 — findings inform `HttpRetryBudget` config surface
- UD-263 — findings produce concurrency hint values

**SFTP is not HTTP — audit scope shifts to: SSH connection failure classes (EOF, auth-fail, channel-close), MINA SshException subclass handling, reconnect vs fail-fast policy. Concurrency: 4-8 typical (DSL-limited servers). Crosses into UD-305 territory for host-key handling — keep separate.**
---
id: UD-269
title: relocate — graceful CTRL-C cleanup + abort summary + stale temp-dir GC
category: core
priority: low
effort: S
status: open
opened: 2026-04-21
chunk: core
---
### Problem

When the user interrupts `unidrive relocate` (CTRL-C on Windows,
SIGINT elsewhere), the running migration dies abruptly:

1. **Temp directories are leaked.** `CloudRelocator.migrate` creates a
   per-run dir at `%TEMP%\unidrive-relocate-<random>\` via
   `Files.createTempDirectory` at
   `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:91`
   and deletes it at the end of the flow at line ~168. On abort the JVM
   terminates before the deletion runs. Observed in the field
   (2026-04-21 session): two leftover dirs survive after the aborted
   ds418play-webdav relocate — one empty (09:55 run), one holding the
   last mid-upload tempfile (`081.jpg`, 1.5 MB, 11:27 run).

2. **No abort summary.** `MigrateEvent.Completed` is emitted only at
   the natural end of the flow. If the flow collector is cancelled
   mid-transfer, the CLI prints nothing about what was already
   transferred vs what remains. The user sees the last `\r`-overwritten
   progress line (e.g. "38.4 GB / 300 GB (12%)") and the shell prompt
   — no record of the 38.4 GB of files already on the target.

3. **No log marker.** `CloudRelocator` doesn't log abort — the daemon
   log in the 2026-04-21 session has no `Migration cancelled` or
   `InterruptedException` entry; the last line is the final upload
   request before the JVM died.

### Fix

Three parts:

1. **Shutdown hook in `RelocateCommand`.** On SIGINT / SIGTERM:
   - Emit a final summary line (transferred / skipped / remaining /
     time) to stdout.
   - Log `Migration aborted — N files transferred, M GB, error count K`
     via the existing logger.
   - Recursively delete the migration `tempDir`.
   - Return a non-zero exit code so callers can detect.

2. **Temp-dir GC on next start.** Scan `%TEMP%` for
   `unidrive-relocate-*` dirs older than 24 h and delete them. Runs
   at the start of every `unidrive relocate` invocation, silently.
   Covers the case where a previous abort left orphans AND the case
   where shutdown-hook cleanup itself was skipped (kill -9 / OS
   force-terminate / power loss).

3. **Pending-transfer state file (optional).** While the migration
   runs, maintain `<tempDir>/pending.jsonl` with one line per file
   successfully uploaded. On re-run, if an unaborted tempDir is found,
   prompt the user: "Resume previous migration (Y/n)?" — skipping
   files already in the manifest. UD-266 skip-if-exists already
   handles the common case where the target is intact; this is a
   finer-grained resume for partial uploads. **Defer** until UD-266
   in-field performance is understood.

### Acceptance

- CTRL-C during `relocate` prints a summary line and deletes its
  tempDir before exit.
- A subsequent `relocate` invocation deletes any `unidrive-relocate-*`
  dirs under `%TEMP%` older than 24 h.
- Log contains a single `INFO Migration aborted ...` line after every
  abort.
- `RelocateCommandTest` gains an abort-summary unit test (mocked
  SIGINT via flow cancellation).

### Related

- UD-267 (closed 2026-04-21) — fixed the progress-line cosmetics;
  this is the next-order UX pass on relocate.
- UD-266 (closed 2026-04-21) — skip-if-exists covers the common
  re-run case; this one covers mid-transfer abort cleanup.
- UD-220 (closed 2026-04-21) — user-guide/consequences.md already
  calls out "no rollback" under `relocate`; this ticket narrows the
  damage and logs it.

### Priority / effort

Low priority, S effort. Not a correctness bug — the user can rerun
with UD-266 skip logic and converge. This is UX polish that would
matter to an operator running long transfers.
---
id: UD-270
title: Windows launcher — drop cmd.exe 'Terminate batch job' prompt on CTRL-C
category: tooling
priority: low
effort: XS
status: open
opened: 2026-04-21
chunk: xpb
---
### Problem

The Windows launcher at `C:\Users\gerno\.local\bin\unidrive.cmd` and
its sibling `%LOCALAPPDATA%\unidrive\unidrive.cmd` are plain `.cmd`
batch files that invoke `java -jar`. When the user presses CTRL-C
inside a long-running command (e.g. `unidrive relocate`), `cmd.exe`
intercepts the signal and — after the JVM has already died — prompts:

```
Batchvorgang abbrechen (J/N)? j   (German)
Terminate batch job (Y/N)? y       (English)
```

This is **pure noise**: the operation has already ended, the JVM is
gone, and the answer to the question has no effect on anything the
user cares about. But it forces an extra keystroke and confuses
first-time users who wonder whether they're being asked to retry.

Root cause: `cmd.exe` installs a console control handler that
intercepts CTRL-C inside batch files. Answering "N" doesn't resume
the JVM (it's already dead); "Y" just exits the batch. The prompt
exists because cmd doesn't know whether the batch file has more
commands to execute after the Java call. Observed today during an
aborted `unidrive relocate --from onedrive-test-local --to
ds418play-webdav`.

### Fix options

Four viable paths, ranked cleanest first:

1. **Native launcher (jpackage).** `jpackage --type app-image
   --name unidrive --main-jar unidrive-0.0.0-greenfield.jar` produces
   an `unidrive.exe` that has no batch-file intermediary. CTRL-C
   propagates cleanly to the JVM and exits. Requires adding a
   jpackage task to the gradle distribution. Also gives us a proper
   Windows icon and version-info resource for free. **Best
   long-term.**

2. **PowerShell wrapper (`unidrive.ps1`).** PowerShell's CTRL-C
   behaviour doesn't emit the abort-prompt for started exes. Simple
   content: `& java -jar "$PSScriptRoot\unidrive-0.0.0-greenfield.jar" @args`.
   Install as `unidrive.ps1` + `unidrive.cmd` shim that calls
   `pwsh -NoProfile -File %~dp0unidrive.ps1 %*`. Slightly more
   indirection than #1 but zero build-tool changes.

3. **cmd wrapper with `CHOICE` dance.** Eat the CTRL-C by setting
   `Ctrl-C` via `setlocal EnableExtensions` + reading via `set
   /p`. Works but fragile; breaks if Java outputs to stdin.

4. **Start the JVM with `start /B /WAIT`.** Moves the JVM to a child
   process group that cmd doesn't intercept. Simpler than #3.
   Drawback: changes how the JVM inherits stdio (can affect the
   `\r`-overwrite progress line from UD-267).

### Proposed

Ship #1 (jpackage) when the gradle distribution layer is next
touched — it subsumes this ticket + gives us Windows Start-menu
registration for free. Until then, land #2 as a one-liner
`.ps1` + shim `.cmd` in the existing deploy target.

### Related

- UD-269 (open) — relocate CTRL-C cleanup. Complementary: UD-269
  fixes what happens *inside* the JVM on abort; this ticket fixes
  what cmd.exe does *around* the JVM.
- Windows-only. Unix `.sh` launchers exec the JVM directly and
  don't exhibit this wart.

### Acceptance

- CTRL-C during `unidrive relocate` returns the user straight to
  the PowerShell prompt with no "Batchvorgang abbrechen" / "Terminate
  batch job" question.
- Exit code is propagated from the JVM (not the batch file).
- Works identically in PowerShell 7, Windows Terminal, and the
  legacy `cmd.exe` console.

### Priority / effort

Low priority, XS effort for PS-wrapper fix, S for jpackage path.
UX polish, not a bug. Worth bundling with any release-prep work.
---
id: UD-271
title: CLI flag placement — --verbose/-v rejected at subcommand level + misleading --version suggestion
category: core
priority: low
effort: S
status: open
opened: 2026-04-21
chunk: core
---
### Problem

Three related CLI flag anomalies observed in the 2026-04-21 session:

**1. `-v` / `--verbose` rejected at subcommand level.**

```
PS> unidrive -p ds418play auth --verbose
Unknown option: '--verbose'
Possible solutions: --version
```

```
PS> unidrive relocate --from … --to … -v
Unknown option: '-v'
Possible solutions: --version
```

The top-level `Main` at
`core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:63-64`
declares `@Option(names = ["-v", "--verbose"])`, but picocli does
not automatically inherit top-level options on subcommands. The
user must write `unidrive -v auth …` (flag before the subcommand
name), which violates git-style / docker-style conventions where
flags can appear anywhere. Worse, the "Possible solutions:
--version" hint is actively misleading — users who accept the
suggestion and run `auth --version` hit problem #2 below.

**2. `--version` on subcommands silently prints nothing.**

```
PS> unidrive -p ds418play auth --version
PS>                                      ← silent, exit 0
```

Every subcommand has `mixinStandardHelpOptions = true`, which
installs `-V/--version` pointing at the command's own version
provider. `AuthCommand` et al. don't supply one, so picocli
prints an empty string and exits cleanly. Users who follow the
"Possible solutions: --version" hint from problem #1 get zero
feedback and assume the CLI is broken.

**3. Ambiguous shadowing: `-v` vs `-V`.**

Top-level `-v` = verbose; picocli's mixinStandardHelpOptions
always installs `-V` = version. They differ only in case; easy
to mis-type on the command line. Most tools now use `-V` = verbose
and `--version` = version to avoid this collision, or don't alias
version at all.

### Fix

1. **Make `--verbose` accepted on every subcommand.** Two options:
   - Declare `-v/--verbose` on every subcommand individually with
     the Kotlin mixin pattern (`@Mixin VerboseOption`). Picocli
     then attaches it to each subcommand's spec.
   - Use picocli's `@ParentCommand` + post-parse fixup: walk the
     remaining args after subcommand dispatch and promote any
     stray `-v/--verbose` back to the parent. Fragile.
   The mixin path is cleanest.

2. **Disable version on subcommands** (or supply a per-command
   provider that delegates to the top-level version). Simplest:
   change `mixinStandardHelpOptions = true` to explicit
   `@Option(names=["-h","--help"], usageHelp=true)` on each
   subcommand and omit the version mixin. Users run
   `unidrive --version` at the top level.

3. **Stop picocli's "Possible solutions" suggestion when the
   suggestion is `--version`.** Override `UnmatchedArgumentException`
   handling in `Main.run()` (or at the commandline level) to drop
   `--version` from the suggestion list.

### Acceptance

- `unidrive auth --verbose` and `unidrive auth -v` both succeed,
  producing the same verbose output as `unidrive -v auth`.
- `unidrive relocate -v --from X --to Y` succeeds.
- `unidrive auth --version` either prints the same banner as
  `unidrive --version` or returns a useful error ("use
  `unidrive --version` at the top level"); not a silent exit.
- Unknown-arg error at a subcommand no longer suggests `--version`
  when the user typed `--verbose`.

### Related

- Earlier CLI polish tickets (UD-237, UD-240) touched adjacent
  ergonomics but not the flag-placement question.

### Priority / effort

Low priority, S effort. Not a correctness bug — users who know
the convention can still use `unidrive -v <subcommand>`. UX polish
that would matter to anyone learning the tool.
---
id: UD-272
title: ProcessLock error — print holder PID + taskkill/kill hint
category: core
priority: low
effort: XS
status: open
opened: 2026-04-21
chunk: core
---
### Problem

`Main.acquireProfileLock()` at
`core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:559-573`
prints:

```
Another unidrive process is running for profile 'onedrive'.
Stop it first, or wait for it to finish.
```

on lock contention. The message is accurate but offers no actionable
information — the user has to independently hunt down which PID holds
the lock, via `tasklist | findstr java` / Task Manager / Get-Process.

Observed 2026-04-21 when running a second `unidrive-watch.cmd`
against a profile where a previous `sync --watch` was already
running — took ~30 seconds to figure out which `java.exe` was the
one to kill (there are usually ≥ 3 JVMs on this host: gradle daemon,
kotlin daemon, unidrive daemon, unrelated tool MCPs).

### Fix

Record the holder's PID inside the lock file on successful
`tryLock()`, read it back inside the "contended" error path. The
lock file is already written via `FileChannel.open(…, WRITE)` — we
just need to `channel.write()` the PID once the exclusive lock is
acquired.

```kotlin
// ProcessLock.tryLock() — add after `acquired = true` block:
val pid = ProcessHandle.current().pid()
channel!!.truncate(0)
channel!!.write(java.nio.ByteBuffer.wrap("$pid\n".toByteArray()))
```

```kotlin
// Main.acquireProfileLock() — failure path:
val holderPid = runCatching {
    Files.readString(lockFile).trim().toLongOrNull()
}.getOrNull()
val holderHint = if (holderPid != null) " (PID $holderPid)" else ""
System.err.println("Another unidrive process$holderHint is running for profile '${profile.name}'.")
System.err.println("Stop it with `taskkill /PID $holderPid /F`, or wait for it to finish.")
```

On Windows, adding the `taskkill` hint is tangible help; on Unix,
fall back to `kill $holderPid` / `kill -9 $holderPid`.

### Edge cases

- Crashed/killed processes: the PID in the lock file is stale but
  the OS-level FileLock was released, so `tryLock()` succeeds. No
  issue — the new holder's PID gets written over.
- PID reuse: in the rare case the OS has recycled the stale PID
  to an unrelated process, the `taskkill` suggestion would target
  the wrong one. Mitigation: check `ProcessHandle.of(pid).isPresent`
  + verify the process is a Java one before suggesting kill. Or
  just flag the suggestion as "hint — verify first."

### Acceptance

- Contended lock error includes the holder's PID on every platform
  where `ProcessHandle.current().pid()` is meaningful (Java 9+).
- A `ProcessLockTest` verifies the PID round-trip (acquire → peek
  file contents).
- `taskkill` / `kill` hint printed, guarded by OS check.

### Related

- UD-269 (open) — relocate CTRL-C cleanup. If the held lock is
  from an aborted relocate, UD-269's tempdir GC plus this ticket's
  PID hint cover the "clean up after yourself" story together.

### Priority / effort

Low priority, XS effort. 15 lines of code + 1 test.
---
id: UD-274
title: relocate — MigrateEvent.Error goes to stdout only, never reaches unidrive.log
category: core
priority: low
effort: XS
status: open
opened: 2026-04-21
chunk: core
---
### Problem

`CloudRelocator.migrate` emits `MigrateEvent.Error` for per-file
failures. `RelocateCommand`'s collector prints them to `stderr` /
`stdout`. **No slf4j logger call is made** — the error never reaches
`unidrive.log`.

### Reproduction (2026-04-21 field observation)

During `unidrive relocate --from onedrive-test --to ds418play-webdav`
the user's terminal showed:

```
Error: Failed to migrate /Pictures/(ArtOfDan) Katya Clover - Sign of Beauty/sign_000042.jpg: Connection closed by peer
Error: Failed to migrate /Pictures/(ArtOfDan) Katya Clover - Sign of Beauty/sign_000057.jpg: Connection closed by peer
```

Running `grep -cE "peer|IOException|SocketException" unidrive.log`
after this returned **0**. The only WARN/ERROR in the log was the
single 503 throttle from Graph — not the WebDAV peer-closes.

Root cause:

- `CloudRelocator.kt:164-166` emits `MigrateEvent.Error("Failed to migrate $newSource: ${e.message}")`
- `RelocateCommand.kt:~156` prints `event.message` to stderr
- No call to `log.warn(...)` or `log.error(...)` anywhere in the
  error path.

Consequences:

- The post-hoc `log-watch.sh --anomalies` run misses these.
- `scripts/dev/log-watch.sh --summary` reports 0 errors even when
  dozens failed.
- Unattended `nohup unidrive relocate ... > out.log 2>&1 &` users
  see nothing in `unidrive.log` because the stdout+stderr went to
  their shell redirect, not to the structured log.
- Error classification, retry budgets, and aggregate counts are all
  blind to per-file failures.

### Fix

At the `catch (e: Exception)` block in `CloudRelocator.migrate`'s
walk (currently `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:164`),
add a logger call before emitting the Error event:

```kotlin
private val log = LoggerFactory.getLogger(CloudRelocator::class.java)

// inside catch
log.warn("Failed to migrate {}: {}", newSource, e.message, e)
errorCount++
emit(MigrateEvent.Error("Failed to migrate $newSource: ${e.message}"))
```

The `e` as final argument captures the stack trace for diagnosis.
`log.warn` (not `log.error`) because per-file failures don't kill
the migration — they're individually recoverable and counted in
`Completed.errorCount`.

### Acceptance

- After a relocate run with per-file failures, `unidrive.log` contains
  one `WARN` entry per failure with the path and cause.
- `scripts/dev/log-watch.sh --summary` reports the correct error
  count.
- `CloudRelocatorTest.migrate emits Error event on download failure`
  (existing) gains an assertion that a WARN log line was written
  via a captured appender.

### Related

- UD-269 (open) — CTRL-C cleanup + abort summary. This ticket
  covers the *error path* during normal (non-aborted) runs; UD-269
  covers the abort path.
- UD-324 (closed) — WebDAV robustness audit called out "Non-2xx
  body parsing: only status code is surfaced". This ticket is the
  sibling concern: "non-2xx surfacing happens via Exception but is
  never logged."

### Priority / effort

Low priority, XS effort. 4 lines of code. High value for anyone
running long unattended relocates — the difference between a
post-mortem grep of `unidrive.log` finding all failures vs finding
none.
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
id: UD-277
title: Size-adaptive HTTP request timeout for large WebDAV uploads
category: core
priority: high
effort: S
status: open
opened: 2026-04-21
chunk: core
---
Surfaced during a ds418play WebDAV relocation (307 GiB, 128 k files, migrated
from `onedrive-test-local`): large 1080p `.mp4` files (hundreds of MB) fail
with `Request timeout has expired [request_timeout=600000 ms]` on the first
PUT. The flat 600 s cap is too short once sustained throughput drops below
~1.8 MB/s on a flaky local link — and each timeout means the entire file
is re-uploaded from byte 0 (no resume; see UD-328).

## Scope

WebDAV client request timeout (and by extension any provider that inherits
the shared HTTP client's per-request deadline when uploading a stream).

## Proposal

Replace the flat `request_timeout` with a size-adaptive policy:

```
timeout = max(baseTimeoutMs, fileSizeBytes / minThroughputBytesPerMs)
```

- `baseTimeoutMs` keeps the current 600 s floor.
- `minThroughputBytesPerMs` is configurable per provider; sensible
  default for local-LAN WebDAV is ~512 KiB/s.

## Acceptance

1. WebDAV upload of a 2 GiB fixture on a deliberately slow link
   (tc-netem 1 MB/s) completes instead of timing out.
2. Unit test: given `fileSize=2 GiB, minThroughput=512 KiB/s`, computed
   timeout is >= 4096 s.
3. Config knob documented.
4. No regression on small-file throughput.

## Related

- UD-278 (retry-on-connection-reset) — complementary.
- UD-328 (Range/Content-Range resume) — preferable when server supports it.
---
id: UD-278
title: Retry on connection-reset / premature EOF via HttpRetryBudget
category: core
priority: high
effort: M
status: open
opened: 2026-04-21
chunk: core
---
Same ds418play relocation run surfaced `Eine bestehende Verbindung wurde
softwaregesteuert durch den Hostcomputer abgebrochen` (TCP RST from the
Synology NAS) mid-upload on large files. `CloudRelocator` currently treats
this as a permanent per-file failure and skips the file.

This is a *different error class* than 429/5xx (UD-207 / UD-227): no HTTP
status arrives, the socket just dies. Recovery shape is the same
(exponential backoff, bounded retries, global budget).

## Proposal

Wire `HttpRetryBudget` (UD-262) into the shared HTTP client so
`IOException` subclasses matching connection-reset / broken-pipe /
premature EOF trigger bounded retry.

## Acceptance

1. Retryable `IOException` taxonomy documented in `HttpRetryBudget`
   KDoc (ConnectionResetException, SocketException "connection reset",
   "broken pipe", premature EOF during body transfer).
2. Default: 3 attempts, exponential backoff with jitter, capped by
   the existing global budget.
3. Restart from byte 0 when Range PUT unavailable; coordinate with
   UD-328 when available.
4. Regression test: mock server closes socket after 1 MiB; upload
   succeeds on attempt 2.
5. `net_retry_reset` metric so `unidrive-log-anomalies` can classify.

## Related

- UD-207 / UD-227 — HTTP-layer retry.
- UD-262 — shared `HttpRetryBudget`.
- UD-277 — adaptive timeout reduces false-positive resets.
- UD-328 — range resume lets retry start from last-acked byte.
---
id: UD-279
title: Relocation planner warns on poor transport fit (WebDAV for bulk)
category: core
priority: medium
effort: M
status: open
opened: 2026-04-21
chunk: core
---
The ds418play relocation failure pattern (UD-277 / UD-278 / UD-327 /
UD-328) was partly self-inflicted: `ds418play-webdav` was chosen as
target transport when the same NAS speaks SFTP, SMB, and rclone-native —
all dramatically better fits for a 300 GiB media migration than DSM's
non-chunked nginx WebDAV.

The relocation planner has enough information to warn at plan time.

## Proposal

Planner-time "transport fitness" check that emits a warning (not a
hard block):

1. Inputs: target-provider kind, total plan size, per-file size
   distribution, sibling profiles on the same host.
2. Rules:
   - target=webdav && plan_bytes > 50 GiB → warn on throughput ceiling.
   - target=webdav && max_file_size > 1 GiB → warn on DSM cap +
     restart-from-zero risk (link UD-327, UD-328).
   - target=webdav && same host has configured sftp profile → name
     the alternative in the warning.
3. Warning appears once at plan time.
4. `--confirm-transport` suppresses for scripts / CI.

## Acceptance

1. `relocate --from onedrive-test-local --to ds418play-webdav` on a
   300 GiB plan emits a single planner-time warning naming the
   fitness issue and any better-configured sibling profile.
2. WebDAV-only target still warns about size ceiling but does not
   invent a sibling recommendation.
3. `--confirm-transport` suppresses for non-interactive use.
4. No warning under 10 GiB plan size.

## Related

- UD-277 / UD-278 / UD-327 / UD-328 — the concrete pain this catches
  at *plan* time rather than transfer time.
---
id: UD-327
title: WebDAV pre-flight: detect DSM per-file size cap before transfer
category: providers
priority: medium
effort: S
status: open
opened: 2026-04-21
chunk: core
---
DSM WebDAV (Synology DS418play and siblings) has a per-file upload cap
(default 4 GiB unless the admin reconfigures
`/etc/nginx/app.d/server.webdav.conf`). When the relocator sends a
>4 GiB file over WebDAV, DSM responds with 409 or silently resets *after*
the full-body PUT times out — the error only surfaces at minute 10 of a
wasted transfer.

## Proposal

Pre-flight probe at relocation-plan time:

1. PROPFIND target root for `{DAV:}quota-available-bytes` and any
   `X-WebDAV-Max-File-Size` / server-identifying headers.
2. If the server advertises a max-file-size cap (or identifies as
   DSM with no explicit cap in profile), compare against the plan's
   max file size.
3. If plan contains files larger than cap: planner-time warning, or
   skip with "exceeds server cap, use sftp/smb" message, or fail
   plan loudly if `--strict`.

## Acceptance

1. `relocate` to DSM WebDAV with a single >4 GiB file surfaces the
   cap *before* any transfer starts.
2. Warning names specific files, not just a count.
3. Overridable via `webdav.max_file_size_bytes` in profile toml.
4. PROPFIND failure falls back to "unknown cap" — generic WebDAV
   servers (Box, Nextcloud) must not be broken.
5. Unit test against a stub server returning the DSM header.

## Related

- UD-277 (adaptive timeout) — orthogonal.
- UD-279 (transport fitness warning) — same spirit at a higher level.
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
id: UD-280
title: Investigate NoSuchMethodError burst at relocation startup
category: core
priority: high
effort: S
status: open
opened: 2026-04-21
chunk: core
---
JFR capture from the 21-04-26 ds418play relocation (saved at
`%LOCALAPPDATA%\unidrive\relocate-20260421-190326.jfr`) shows a burst
of **40 `java.lang.NoSuchMethodError` exceptions in a single minute
at 15:28:20** during the relocation startup window. This is not
relocation traffic — it's classloader / dependency drift. `NoSuchMethodError`
at runtime almost always means: compiled against API version A, resolved
against version B at load time. Candidate causes in the current tree:

- Recent dependabot PRs (Ktor 3.2 → 3.4.2, kotlinx-serialization 1.9 →
  1.11, logback 1.5.18 → 1.5.32, sqlite-jdbc 3.49.1 → 3.53, bouncycastle
  1.80 → 1.84) are open but unmerged; a partial local install could
  mix versions. Check whether the running jar has stale transitives.
- Shadow-jar repackaging: if `com.gradleup.shadow` bundled two
  incompatible versions of the same dependency, one wins at jar-build
  time but the other's bytecode references survive. Dependabot PR for
  shadow 9.0.0-beta12 → 9.4.1 is open — known-unstable line.

## Acceptance

1. Identify the throwing class + missing method from the JFR stack traces
   (Java Error events have stack-truncation warning but depth 64 is
   plenty for this). Commit fix or pin the specific transitive.
2. Add a startup smoke test: on `main.kt` entry in debug builds, run a
   lightweight reflection check against the top-level API surfaces of
   the three main risk deps (Ktor client, kotlinx-serialization, logback)
   and log a WARN with version-stamp if a `NoSuchMethodError` is caught.
3. No new `NoSuchMethodError` in a fresh JFR capture of a 1000-file
   relocation.

## Related

- UD-262 (HttpRetryBudget extraction, just closed) — package move; low
  probability but check this isn't the cause.
- The cluster of open dependabot PRs (core Ktor, core serialization,
  ui flatlaf/slf4j, shadow, logback, bouncycastle, sqlite-jdbc) — if
  root cause is version drift, closing the right dependabot PR fixes
  this for free.
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
id: UD-733
title: Stamp short git SHA into every log line + startup banner
category: tooling
priority: medium
effort: S
status: open
opened: 2026-04-21
chunk: core
---
When debugging across the daemon + MCP + UI processes, correlating a
log line to a specific code state currently requires `git log` guesswork
because the build plants no version stamp at log time. Every bug report
("this happened on yesterday's build") loses the `commit → line`
provenance chain.

## Proposal

At build time, stamp the short git commit SHA into a BuildInfo singleton
consumed by the logging layer, and append it to every `DEBUG`-or-lower
log line via the logback pattern or an MDC default.

Concretely:

1. Gradle task `generateBuildInfo` writes `BuildInfo.kt` (or a
   `build-info.properties` resource) under `:app:core` with at least:
   ```
   GIT_SHORT_SHA = "0a43158"
   GIT_DIRTY     = true|false
   BUILD_INSTANT = ISO-8601
   ```
   Hook into `:app:core:processResources` so the file is refreshed on
   every build without forcing a re-compile when the SHA hasn't changed.
2. At startup, `BuildInfo.gitShortSha` is pushed into the root logger's
   MDC (or exposed as a logback pattern converter `%X{git}`) so that
   every log event — not just `DEBUG` — carries it. Rationale: making
   it debug-only means any captured `INFO`/`WARN` from a user's bug
   report still loses the stamp. One extra 7-char field per line is
   cheap.
3. Logback `%m` patterns in `core/`, `ui/`, `:app:mcp` config updated
   to include the stamp. Pattern example:
   `%d{HH:mm:ss.SSS} %-5level [%X{git:-NOGIT}] %logger{36} - %msg%n`
4. `--version` / startup banner prints the same stamp so the bottom
   of the log matches the version banner at the top.
5. `GIT_DIRTY=true` surfaces as a WARN at startup so users don't
   report bugs against uncommitted builds without knowing.

## Acceptance

1. Every log line in `unidrive.log` and `daemon.jfr` run metadata
   contains the short SHA. Verified by `grep -c "\[[0-9a-f]\{7\}\]"`
   equal to total-line-count on a 1000-line capture.
2. Release builds stamp the SHA of the build commit; dev builds stamp
   the working-tree SHA and warn on dirty.
3. No measurable overhead on the hot log path (benchmark: 1 M lines
   at INFO, pre vs post, within 2 % noise).
4. The three log outputs stay in lockstep (daemon stdout, daemon file,
   `ui`/`:app:mcp` forwarded lines) — same stamp on all.

## Related

- `unidrive-log-anomalies` skill — would add `git` as a dimension in
  the anomaly summary.
- Bug-report template in CONTRIBUTING / ISSUE templates should drop the
  "paste your version" field once the log has it inline.
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
id: UD-282
title: Surface ignored TOML sections / unknown keys in config.toml as warnings
category: core
priority: medium
effort: S
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt
  - docs/config-schema/config.example.toml
opened: 2026-04-29
---
**Symptom (live, 2026-04-29).** A user editing
`%APPDATA%\unidrive\config.toml` wrote the section header
`[profiles.unidrive-localfs-19notte78]` (instead of the schema-correct
`[providers.unidrive-localfs-19notte78]`). Running
`unidrive relocate --from unidrive-localfs-19notte78 --to ds418play`
returned `Unknown profile: unidrive-localfs-19notte78` — the section
was silently dropped at parse, the user had no signal that their
intent was misread.

**Root cause.** `core/app/sync/.../SyncConfig.kt:13` initialises
ktoml with `TomlInputConfig(ignoreUnknownNames = true)`. The setting
is load-bearing for forward-compat (we add fields and don't want
old configs to break), but it also drops sections whose name doesn't
match the schema — including `[profiles.X]`, `[provider.X]` (singular
typo), `[profile.X]`, etc. Same root cause for unknown leaf keys
inside a valid `[providers.X]` block: e.g. the same user wrote
`local_root = ...` / `remote_root = ...` for a localfs profile, but
the `RawProvider` schema accepts only `sync_root` or `root_path`
(SyncConfig.kt:55-57). Silent drop again.

**Proposed fix.** Two-tier diagnostic, no schema change:

1. **Soft warn (default behaviour).** Pre-parse pass: re-run the
   TOML decoder with `ignoreUnknownNames = false` against an inner
   `JsonObject`-style tree, collect unrecognised top-level sections
   and unrecognised keys-inside-known-sections, then print
   ```
   warning: ignored TOML section '[profiles.unidrive-localfs-19notte78]'
            — did you mean '[providers.unidrive-localfs-19notte78]'?
   warning: ignored key 'local_root' inside '[providers.foo]'
            — RawProvider accepts: sync_root, root_path, ...
   ```
   to stderr, once per parse. Levenshtein distance ≤ 2 against the
   known names list earns the "did you mean" suggestion.

2. **Strict mode (opt-in).** A `--strict-config` flag (or
   `UNIDRIVE_STRICT_CONFIG=1` env var) flips the warning to a fatal
   `IllegalArgumentException` with the same message. Useful for CI
   and for users who want their typos to halt the run rather than
   surface as "Unknown profile".

**Out of scope for this ticket.** Auto-aliasing `[profiles.X]` →
`[providers.X]` (or accepting both) is a larger schema decision and
should land separately if at all — the warning fix is enough to
unblock the surprise.

**Acceptance.**
- New unit test in `SyncConfigTest`: parse a config with
  `[profiles.foo]` + `[providers.bar]` and assert (a) `bar` is
  visible, (b) a warning line for `profiles.foo` is emitted on stderr.
- New unit test: parse `[providers.foo]` with `local_root = "..."`,
  assert warning lists `local_root` as unknown with the suggestion
  list including `root_path`.
- New unit test: under `--strict-config`, both cases throw.
- Update `docs/config-schema/config.example.toml` header comment
  to mention the strict-mode flag.

**Why now.** The relocate-v1 spec (forward-looking,
`docs/specs/relocate-v1.md`) introduces four new ops — every one of
them takes a profile name as `--from`/`--to`. The
"silent-drop-then-Unknown-profile" failure mode will keep surfacing
as relocate gets exercised. Fixing the diagnostic before the spec
ships saves every future user the same dead-end debug session.

**Live trigger.** 2026-04-29 session, user `gerno` retesting the
relocate verb. Took ~45 min of CLI-vs-config-vs-state-db spelunking
(across two config.toml locations and a config.toml.bak swap) before
the typo surfaced. A one-line stderr warning would have closed it
in 10 seconds.
---
id: UD-283
title: MCP silently routes to default profile on unknown profile name (more permissive than CLI)
category: core
priority: high
effort: S
status: open
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/McpServer.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt
opened: 2026-04-29
---
**Symptom (live, 2026-04-29).** When invoking an MCP tool with
`profile=<name-not-in-config>`, the MCP **silently falls back to the
default profile** instead of erroring. The caller (LLM, in practice)
never knows their requested profile was misrouted; the response
returns data for a *different* target.

Reproduced against the deployed v0.0.0-greenfield (ea60e1e) MCP via
JSON-RPC over stdio:

| Tool call | Configured profiles | Behaviour |
|---|---|---|
| `unidrive_status` `{profile: "onedrive-test"}` | onedrive-test absent; onedrive present | returned data for `onedrive` (response `"profile":"onedrive"`) |
| `unidrive_ls` `{profile: "ssh-local", path: "/", limit: 10}` | ssh-local absent | returned 14 entries **byte-identical** to `profile=onedrive` (same paths, same modified timestamps, same hydration flags) |
| `unidrive_quota` `{profile: "onedrive-test"}` | onedrive-test absent | returned default profile's quota |

**Compare with CLI:** `unidrive -p onedrive-test status` produces:
```
Error: Unknown profile: onedrive-test
Configured profiles: ds418play, ds418play-webdav, ...
Supported provider types: hidrive, internxt, localfs, ...
```
The MCP is **more permissive than the CLI** for the same input.

**Why this is worse than UD-282.** UD-282 covers the silent drop on
parse — `[profiles.X]` typos that ktoml ignores. UD-283 is the same
class of failure mode (silent drop of unknown input) but at the
tool-invocation surface, where the caller is an **LLM, not a
human**. Three reasons that's worse:

1. The LLM has no `Configured profiles:` listing in its context — it
   can't course-correct by re-reading the error.
2. Tool calls that mutate (`unidrive_sync`, `unidrive_pin`,
   `unidrive_relocate`, `unidrive_profile_remove`) could operate on
   the wrong target without any signal. `unidrive_relocate
   --from typo --to real` could relocate the *default* profile if
   the typo isn't caught.
3. The LLM trusts the response. A "200 OK with data" response that's
   actually for a different profile is harder to detect than a
   missing profile (which would be a clear "X tool returned no
   data" signal).

**Root cause hypothesis.** Probably in the MCP tool dispatch layer,
profile resolution falls through to `resolveDefaultProfile()` when
`SyncConfig.resolveProfile(name, raw)` throws `IllegalArgumentException`,
instead of propagating the exception to a JSON-RPC `error` object.
`core/app/mcp/.../McpServer.kt` and the per-tool handler classes are
the likely sites; the CLI in `Main.kt:118-142` does the right thing
(prints error, calls `System.exit(1)`).

**Proposed fix.** Mirror the CLI behaviour exactly. When a tool is
invoked with an explicit `profile=X` and X doesn't resolve:

1. Return a JSON-RPC error response with the same wording the CLI
   produces:
   ```json
   {
     "jsonrpc": "2.0",
     "id": <id>,
     "error": {
       "code": -32602,
       "message": "Unknown profile: X",
       "data": {
         "configuredProfiles": ["ds418play", "ds418play-webdav", ...],
         "supportedProviderTypes": ["hidrive", "internxt", ...]
       }
     }
   }
   ```
   (`-32602` = JSON-RPC "Invalid params". The configured-profiles
   list goes into `error.data` so an LLM client can present it in
   tool-call retry context.)

2. Same handling for the UD-237 type-fallback: if `profile=X` matches
   no name **and** matches no provider type **and** there's exactly
   one configured profile of a similar type, surface a structured
   suggestion in `error.data` rather than silently selecting it.
   Mutation tools (`*_sync`, `*_pin`, `*_relocate`, `*_profile_*`)
   should never auto-resolve by type even when there's exactly one
   match — the safety floor is "explicit name or fail".

3. Where profile is **omitted**, current behaviour (use
   `resolveDefaultProfile`) is fine and stays.

**Acceptance.**
- New `McpServer` integration test: invoke each tool that takes a
  `profile` arg with `profile="does-not-exist"`, assert each returns
  a JSON-RPC error (not a result), and `error.data.configuredProfiles`
  is populated.
- New unit test: with two profiles `foo-A` (sftp) and `foo-B` (sftp),
  invoke `unidrive_sync` `{profile: "sftp"}` and assert it errors
  with multiple-matches feedback rather than silently picking one.
- Manual: `python scripts/dev/oauth-mcp/...` smoke check passes (no
  regression on `profile=` omission path).

**Related.**
- UD-282 — same failure-mode class, different surface (TOML parse vs
  tool call). Both should land in the same release; UD-283 is higher
  priority because LLM-in-the-loop has no recovery path.
- UD-218 — `status --all` collapses by provider type. UD-283 fix may
  inadvertently surface UD-218 too if the `--all` MCP path goes
  through the same resolver.
- UD-237 — CLI's "matched by type" auto-selection. The fix above
  proposes that auto-selection is **CLI-only** for safety.
---
id: UD-289
title: CloudRelocator is fully sequential; one hung PUT halts entire migration for up to 10 minutes
category: core
priority: medium
effort: M
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt
opened: 2026-04-29
---
**Symptom (live, 2026-04-29).** During a 345 GB
`unidrive relocate --to ds418play-webdav`, the user reports long
unexplained pauses where progress stops moving entirely, then
resumes. Pauses correlate with errors but stretch up to 10 minutes
(the `requestTimeoutMillis` ceiling, UD-285).

**Root cause.** `core/app/sync/.../CloudRelocator.kt` walks the
source tree **fully sequentially**. Grepped the file for `launch`,
`async`, `coroutineScope`, `supervisorScope`, `Semaphore` — **zero
matches.** Migration is a single coroutine doing one
download-then-upload at a time:

```kotlin
fun migrate(...) = flow {
    walk(source, sourcePath).forEach { entry ->
        emit(MigrateEvent.Progress(...))
        val tempFile = downloadFromSource(entry)        // serial
        try {
            uploadToTarget(tempFile, newTarget)         // serial
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
```

Combined with UD-285 (`requestTimeoutMillis = 600 000 ms`), one hung
PUT halts the **entire** 345 GB walk for up to 10 minutes. With
128 681 files and even a 1% transient-failure rate, the wall-clock
cost in stalls dominates the actual transfer time.

**Why parallelism is safe here.** Source = localfs (no rate limit,
abundant disk-read bandwidth). Target = WebDAV → DSM Apache (LAN
+ disk-bound, but accepts concurrent connections happily — Apache
worker MPM defaults to 256 simultaneous). The throttling shape of
the relocate is "saturate target's accept rate without driving disk
to thrash"; that's not 1.

**Compare with `SyncEngine.kt`.** Already does this for downloads
via `semaphoreForSize(...)` — sliding window per provider, sized
by file size class, configurable via `concurrency_per_provider`.
Same module conventions; lift-and-shift.

**Proposed fix.**

```kotlin
fun migrate(
    source: CloudProvider,
    target: CloudProvider,
    sourcePath: String,
    targetPath: String,
    concurrency: Int = 4,  // configurable via [providers.X].concurrency
): Flow<MigrateEvent> = channelFlow {
    val semaphore = Semaphore(concurrency)
    coroutineScope {
        walk(source, sourcePath).forEach { entry ->
            launch {
                semaphore.withPermit {
                    try {
                        send(MigrateEvent.Progress(...))
                        migrateOne(entry)
                        send(MigrateEvent.Completed(entry))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Failed to migrate {}: {}: {}", entry.path,
                            e.javaClass.simpleName, e.message, e)
                        send(MigrateEvent.Error(entry.path, e))
                    }
                }
            }
        }
    }
}
```

`coroutineScope` (not `supervisorScope`) — one fatal failure should
still halt the migration. But UD-286's per-job `try/catch` ensures
only true cancellation/auth/programmer-error escalates; transient
failures are absorbed into `MigrateEvent.Error` and the next file
proceeds.

**Concurrency knob.**
- Default `4` — sane for residential link + Synology.
- Per-target override via `[providers.X].concurrency` in
  config.toml. UD-282 lands the warning surface for unknown keys
  before this can be merged.
- Per-source-class override might be useful (localfs source has no
  rate limit; OneDrive source needs to stay below the Graph
  throttle). Defer to follow-up if the static default works.

**Acceptance.**
- New `CloudRelocatorParallelismTest`: instrument a fake provider
  that records concurrent inflight count; assert peak ≥ 2 with
  default config and 10 small files.
- New: assert one slow file (1 s `delay`) doesn't block other
  files completing — total time < `concurrency` × per-file time.
- New: under `coroutineScope` semantics, asserts a single auth
  failure halts the migration (does not become Error event).
- Manual: re-run the 345 GB relocate with `concurrency = 4`;
  expected wall-clock improvement ~3-4× when transient failures
  are present.

**Order matters.** Land **after** UD-286 (need cancellation hygiene
for the `coroutineScope` semantics to work) and **after** UD-288
(per-file retry; without it, parallelism multiplies failures
N-fold). UD-287 is independent.

**Related.**
- UD-285 — request timeout that the parallelism is hiding from.
- UD-286 — cancellation hygiene (prerequisite).
- UD-288 — retry (prerequisite for safe parallelism).
- `SyncEngine.semaphoreForSize` — pattern source.
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
