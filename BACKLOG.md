# Backlog

Top of file = next up. Work down. Move done items to `CLOSED.md` in the same commit. No IDs, no dates, no versions.

## Critical — data-risk

Silent corruption, orphan storage, lost local metadata. Fix before anything else.

| Title | Scope |
|---|---|
| Tracking-set sync engine | Replace `state.db`-as-authoritative with a tracking-set predicate over deletion. Untracked paths are invisible to deletion logic; the `.safe/` phantom-row delete-cascade class of bug becomes structurally impossible by construction. Lands at `core/app/sync-tracking/` as a new module; legacy `core/app/sync/` enters a retirement path once the new engine has provider-integration parity. Lemma + regression test for the `.safe/` incident shape are non-negotiable parts of the smoke set. Identity moves to `(provider_id, remote_file_id)` with content-hash as rename heuristic; first-run non-empty `sync_root` uses adopt-on-exact-content-match with LOUD collisions; batch-level max-delete-ratio guard backstops the residual "provider transiently lied" case. Spike + prototype exist as branches in the legacy repo (`spike/tracking-set-reconcile`, `prototype/tracking-set-engine`) — relocate, don't rewrite from scratch. |

## High — correctness, required for first release

| Title | Scope |
|---|---|
| Reach the 5+5+2 smoke target | Current live-integration surface is 4 onedrive (`LiveGraphIntegrationTest`, `OneDriveIntegrationTest`, `OneDriveDeltaIntegrationTest`, `DeltaDiagnosticTest`), 1 internxt (`InternxtIntegrationTest`), 0 sync, plus `CliSmokeTest`. Target: 5 onedrive, 5 internxt, 2 sync (auth/upload/download/delete/delta-reconciles per provider; two end-to-end sync round-trips). Each gated by `UNIDRIVE_INTEGRATION_TESTS=true`. Reducing the existing unit-test surface (≈120 files) is a separate per-test review: don't sweep wholesale, lift the smoke set up to the target first. |
| Tracking-set engine: verify Internxt provider end-to-end | `unidrive ts sync --dry-run` against a real Internxt profile + a live-integration test gated by `UNIDRIVE_INTEGRATION_TESTS=true`. Owns 429-storm / JWT-refresh / malformed-delta interactions in the engine path; surface concrete issues as follow-up entries rather than fix them inline. Blocks closing the Critical-tier tracking-set entry. |
| Tracking-set engine: verify OneDrive provider end-to-end | Same shape against a real OneDrive profile. Owns delta-cursor / 410-Gone-resync / FastBootstrap interactions in the engine path. Blocks closing the Critical-tier tracking-set entry. |
| OneDrive 410 Gone resync handling | Honor `resyncChangesApplyDifferences` / `resyncChangesUploadDifferences` with `Location` restart on the delta loop. |
| OneDrive webhook lifecycle events + validation endpoint | Handle `reauthorizationRequired`, `subscriptionRemoved`, `missed`; add an in-repo HTTP endpoint that echoes `validationToken` within 10 s. |
| OneDrive `file.hashes` in local change detector | Remote scanner uses it (`Reconciler.kt:117`); local scanner still mtime+size only (`LocalScanner.kt:100`). Avoids re-uploading on touch-only changes. |
| OneDrive `If-Match` precondition on `createUploadSession` | Catches concurrent edits before the session URL is handed out. |
| OneDrive refresh `downloadUrl` on `assertNotHtml` | When the CDN serves an HTML throttle page, re-resolve via `getItemById` instead of failing the download. |
| Internxt permanent-failure quarantine on download 404 | `InternxtApiException: 404 Bucket entry … not found` is treated as transient. Live evidence: single zero-byte file retried 1,248 times over ~8h after the user deleted it. Quarantine policy: on 404-bucket-not-found from `downloadFile`, mark the local index row as `download_quarantined` with `last_error_at` and skip until a delta event for that `remote_id` clears the flag. Smoke: 5th internxt smoke asserts one failed-download and zero retries on the next pass. |

## Medium — efficiency

| Title | Scope |
|---|---|
| OneDrive `$batch` for reconciliation reads | Max 20 per batch; `dependsOn` ordering; per-inner 429 retry. Biggest single throughput win on bulk-share / bulk-move. |
| OneDrive `Range` against `@microsoft.graph.downloadUrl` | Parallel-segment download. |
| OneDrive conditional GET via `If-None-Match` + cTag | Make unchanged-file refreshes free. |
| OneDrive `$select` slim payload on delta | Minimum: `id,cTag,size,file/hashes,@microsoft.graph.downloadUrl`. |
| OneDrive `conflictBehavior` user-selectable | Hard-coded `"replace"` at `GraphApiService.kt:522` once parity is fixed in the data-risk item. |
| OneDrive `createLink` client-side dedupe | Check `listPermissions` for an existing matching link before POSTing. |
| OneDrive per-tenant concurrency calibration | Personal vs Business vs GCC; flagged in `core/providers/onedrive/README.md`. |
| OneDrive `HttpRetryBudget` per-provider override | Constants currently global. |
| Tracking-set engine: persist delta cursor per profile | Engine currently re-enumerates the full remote every pass. Persist cursor in `tracking.db` so subsequent passes see only changes. Required before tracking-set becomes the default engine. |
| Tracking-set engine: migration / coexistence with legacy `state.db` | First `ts sync` on a profile with an existing `state.db` runs adopt-on-content-match against live observations rather than importing legacy rows. Document bulk-collision rendering for fresh-adopt operators; consider a `--auto-match` switch (size or name) for providers without a content hash. |
| Internxt --watch cadence: reduce poll when WS connected | Notifications WS is already connected as a wake signal; WATCH_POLL still scans every ~23s. Reduce to a stale-heartbeat cadence (e.g. 5 min) when the WS transport is healthy, returning to aggressive poll only after a WS disconnect. |
| `unidrive status --all` enumerates only one profile | With two Internxt profiles configured (e.g. `internxt` and `gernot_krost_internxt_pst`), `status --all` shows ONLY the second and skips the first. Enumerate all configured profiles ordered by `config.toml` declaration; default to a fast read of cached state, no auth. |
| `unidrive status` must be side-effect-free; no interactive auth | `status --all` triggers an email/password/2FA prompt mid-render when a profile's token has gone stale. Status is a read-only diagnostic; auth happens via `unidrive auth`. Render a stale-state glyph (e.g. `⚠︎ STALE`) for tokens that need refresh and exit cleanly. |

## Low — guards and UX

| Title | Scope |
|---|---|
| OneDrive `malware` facet skip on download | One-line guard. |
| OneDrive `fileSize` precheck → early 507 | Avoid wasted upload attempts. |
| Internxt keep-overwritten prune | `unidrive prune --backups [--older-than 30d] [--profile NAME]` for `keep_overwritten` archives. Glob on `.unidrive-prev-*` in plainName. |
| `unidrive status` no-default-profile fallback shows misleading "Local Filesystem" row | When invoked without `--profile` and with no default configured in `config.toml`, `unidrive status` renders a phantom row labeled "Local Filesystem" with a `⚠︎` marker, `[– –]` status code, and all-zero columns — looks like a real but broken entry, isn't. `unidrive status --all` correctly shows the configured providers. Either resolve a default profile up front and show its row, or emit a one-line message ("No default profile configured. Use --all to list, or --profile <name> to select.") and exit zero. The current placeholder is worse than either alternative because it suggests something is broken. |
| `unidrive status` shows `[✔ OK]` on stale cached auth state | When the persisted state row is from a previous sync where the token was valid, but the current token has since expired (and `status` hasn't probed the provider yet), the STATUS column still renders `[✔ OK]` — same as a freshly-verified profile. Should render a stale-state glyph (e.g., `⚠︎ STALE`, `⏳`) when the displayed data predates the current auth-cache invalidation. Distinguish "looks healthy because we just checked" from "looks healthy because we haven't re-checked since the token went stale." |
| Dirty-build WARN must ignore mode-only changes | `Main.kt` emits `Build was made from a dirty git worktree` on any uncommitted change including mode-only. Ignore mode-only diffs in the dirtiness check. One-shot: commit current `core/gradlew` chmod. |
| MDC empty-slot renders as [???????] instead of [--------] | `EventThread` and `DefaultDispatcher-worker-N` sit outside the per-scan MDC scope, so the build slot is unset. Today emits `[???????]`; convention uses `[--------]` for empty. |
| Repo-root docs outside bounded doc surface | `internxt-boosters-analysis.md` and `opencode-review.md` sit at repo root outside the bounded doc surface defined in AGENTS.md. Fold into `BOOSTERS.md` / `docs/audits/` or add as explicit exceptions. |
| Audit code/comment drift on hydration filtering | `SyncEngine.kt:527-528` claims "LocalScanner now skips unhydrated rows" — implementation does not. Grep for similar "now does X" assertions whose code contradicts the comment; either land the missing code or remove the misleading comment. |

## Cross-cutting

| Title | Scope |
|---|---|
| Path normalization (NFC) across sync | Required for cross-platform file matching. |
| Retry budget per operation | Cap retries to keep the sync loop responsive. |
| Provider SPI hardening with two providers | Either trim what isn't earning its keep or extend to remove provider-name dispatch sites. |

## Design constraints (not tickets — bind when related work lands)

- **Platform-surface code lives outside `core/app/`.** Anchor: `AGENTS.md` *What not to do* — "Don't grow the daemon to host a UI tier." Trigger: any work on the Windows desktop, Android, or Linux UI surfaces declared in `docs/adr/multi-platform.md`. Those surfaces consume the core; they don't extend it inline.

## Deferred — post Linux-daemon ship, sequenced by [docs/adr/multi-platform.md](docs/adr/multi-platform.md)

- **Virtual filesystem layer (placeholders for cloud-only items).** Today only physically-downloaded files appear in the sync_root; large cloud accounts (live example: 625 GiB / 171k files) see an empty folder. The two platform-tier implementations that close this gap — Windows CloudFiles API for the Windows desktop client, FUSE for the Linux UI — are the explicit scope of those surfaces in `multi-platform.md`. Daemon-side interim mitigation: a `unidrive get --recursive <path>` (or `unidrive pin <path>` if pin/unpin lands) to bulk-hydrate. The platform implementations are scheduled work, not indefinitely deferred.

- **Tombstone retention policy on `state.db`.** v1 keeps TRASHED rows indefinitely. Includes the permanent-delete drift case — Internxt's ~30-day purge means a TRASHED row may eventually stop appearing in cloud listings; v1 leaves the row as TRASHED rather than promoting to DELETED. Revisit alongside a `tombstone_retention_days` knob if `state.db` growth becomes user-visible.
- **Local-modified-while-TRASHED conflict-aware merge.** Editing the local copy of a file currently in cloud trash, then restoring the cloud copy → conflict. v1 is last-write-wins (cloud restore overwrites the local edit on re-download). Conflict-aware merge would mirror the existing `ConflictPolicy` machinery for the editing-during-trash window.
- **Internxt multipart upload** — constants exist in `InternxtConfig.kt` but unconsumed. Single-shard upload works up to the per-account size cap. Defer because no smoke test currently exercises >shard-size files; revisit when one does.
- **Internxt Shares API** — server has ~30 endpoints; provider returns Unsupported. Not in smoke surface, not required for two-way sync.
- **OneDrive resumable-upload 416/417 nuance** — current chunked retry covers the common case; the 416 GET-probe-and-trim path is the rare edge. Defer until smoke surfaces it.
- **Xtra E2EE re-evaluation** — the `xtra` wrapper was removed in the slim prune. Internxt is natively E2EE; OneDrive users who want client-side encryption no longer have a layer for it. Re-evaluate post-MVP if user demand surfaces.

## Out of scope across all surfaces

- MCP server (removed; not coming back).
- Backup-tool features (snapshots, restore points).
- Three-way merge of document conflicts — surface conflicts, offer keep-both or last-writer-wins.
- Embedding the core as a generic third-party library. First-party platform tiers (Windows desktop, Android, Linux UI) consume the core; there is no generic embedding offer for arbitrary third parties.
- Provider-specific niche features without cross-provider generalization.
