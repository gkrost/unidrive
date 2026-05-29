# Closed

Things that were done before this branch started. Append new entries when items move out of `BACKLOG.md`.

## Slim-branch consolidation

- Strip non-MVP providers + build targets; trim dependent CLI tests
- Remove MCP module and all references
- Remove CI policing; `./gradlew check` is the only gate
- Delete Windows-only scripts; Linux-only surface
- Delete Docker test harnesses for removed providers
- Scrub MCP references from dist scripts
- Absorb CONTRIBUTING.md and CLAUDE.md into AGENTS.md
- Co-locate provider docs with provider modules
- Trim surviving ADRs, drop seven obsolete ones
- Rewrite README as Linux-power-user marketing front

## Drained from BACKLOG

- `RefreshRpcHandler` reserves the in-flight slot via `compareAndSet`-before-launch (placeholder swap), with launch-failure cleanup so the invariant-I6 guard can't stick; added named tests for the busy, provider_error, and shutdown reply paths (docs/dev/specs/unidrive-daemon-design.md, `RefreshRpcHandler` / invariant I6)
- Apply-phase action ordering respects parent-folder dependencies via Kahn's algorithm in `ApplyOrder.kt` (BACKLOG: High)
- Tracking-set cursor-resume no longer forces `exists=true` for `PendingDeleteLocal` rows, fixing stale-local crash-recovery (BACKLOG: Critical)
- Default-ignore-list surface (c): tracking-set `scanLocal` honours `effectiveExcludePatterns` via `TsContext` (BACKLOG: High)
- Create-collision blind-PUT-overwrite fixed on both providers via `existingRemoteId` guard and rename-on-conflict fallbacks (BACKLOG: UD-366)
- Positional `<profile>` argument on `daemon run|status|stop`, `sync`, `refresh` — works both as `-p` and positional
- `hydration.dehydrate` on unknown path returns `Failed` instead of silent `Ok`
- Hydration SPI Phase 1 — `app:hydration` module with openForRead/Write, closeHandle, hydrate/dehydrate, events flow
- Internxt permanent-failure quarantine on download 404; quarantined rows skipped until fresh delta clears flag
- Audit code/comment drift on hydration filtering — two comments rewritten, eight verified accurate
- Unhydrated folder rows no longer turn into DeleteRemote — post-detectMoves filter in Reconciler drops them
- Repo-root docs realigned to bounded doc surface; boosters analysis moved to docs/audits/
- MDC empty `build` slot renders as `--------` instead of `???????`, matching `scan` convention
- Dirty-build WARN ignores mode-only changes; dirtiness from `git diff --numstat` + untracked files
- `unidrive mount` IPC lifecycle deadlock resolved by per-profile daemon process model
- Streaming reconciliation (process pages as they arrive) across 6 commits + CLI/TOML config
- Internxt `encryptVersion` — investigation found no `02-rsa` usage; added fail-fast guard for unknown versions
- Internxt retry coverage on `putEncryptedShard`, `putEncryptedShardFromFile`, `downloadFileStreaming`
- Internxt 429 on Drive DELETE mutations routes through `retryOnTransient`
- Internxt delta path-collapse — `buildFolderPath` returns null on missing ancestor; delta filters dropped items
- Internxt state-db duplicate-`remote_id` cleanup via one-shot SQL window-function migration
- Internxt listing `status` filter selectable per call via optional parameter (default `"ALL"`)
- Internxt `Retry-After` HTTP header parsing — RFC 7231 delta-seconds and IMF-fixdate support
- Internxt JWT pre-expiry refresh margin (1 day) to avoid mid-stream 401s
- Internxt `NonCancellable` wrap on `refreshToken` verified (already covered by `RefreshableTokenLatch`)
- Internxt in-flight dedup extended to `getFileMeta`, `listFiles`, `listFolders`
- Internxt hard-coded client-header values (`clientName`/`clientVersion`/`desktopHeader`) now configurable
- Internxt request prioritization — two-lane Foreground/Background via coroutine context
- Internxt `HttpRetryBudget` wiring — per-host budgets for Drive REST and Bridge upload
- Internxt parallel listing pagination — fans out 2 page fetches per stream
- Internxt 401 → automatic refresh-and-replay on Drive REST surface
- state.db redesign: tombstones, uuid identity, parent_uuid — drop-and-rescan upgrade
- Mount-write clobbered by SyncEngine resolved by per-profile mode mutex per design spec
- Internxt resumable scan with persistent checkpoint in `scan_staging` table
- Internxt cross-session resumable scan — incomplete scans survive clean exit
- Internxt best-effort cursor advance on incomplete scan — promotes cursor unconditionally
- Internxt WebSocket change feed (socket.io wake-signal via `NotificationsClient`)
- Internxt `finishUpload` idempotency via 409 reconcile with listing-based collision detection
- Internxt local chunk-tombstone for upload resume via `UploadTombstoneStore`
- OneDrive `If-Match`/`@odata.etag` on `moveItem` + `conflictBehavior=replace` parity on uploadSimple
- OneDrive delta soft-vs-hard delete semantics with age-gap WARN
- OneDrive upload-session expiry validation — self-pruning store, probe resilience, two-attempt loop
- OneDrive `fileSystemInfo` round-trip — local mtime/ctime preserved through upload/download
- Internxt destructive-overwrite warning — opt-in `keep_overwritten=true` renames prior cloud file before replace
- Internxt `--watch` cadence collapses to `max_poll_interval` while WebSocket is healthy
- `unidrive status --all` enumerates profiles in config declaration order
- `unidrive status` is side-effect-free — gates quota probe on `CredentialHealth.Ok`
- IPC write-timeout drops Phase 2 mount clients — fixed with dedicated 4-thread transport pool
- IPC transport plan doc fixed from non-existent `:app:sync:installDist` to `:app:cli:deploy`
- Phase 2 co-daemon `Broken pipe` mid-IPC resolved by explicit subscriber-set on broadcast path
- `unidrive refresh --reset` restored via daemon RPC verb extension after Task 11 refactor
- Daemon constructed `SyncEngine` with wrong sync root (`dbPath.parent` vs configured `sync_root`) — fixed
- Tracking-set engine: Internxt provider verified end-to-end — structural assertions hold on 196k-file profile
- Tracking-set engine: OneDrive provider verified end-to-end — structural assertions hold on 3,232-file profile
- `unidrive ts` CLI unreachable — fixed via `runtimeOnly` dependency to avoid classpath cycle
- Tracking-set engine: per-profile delta-cursor persistence — incremental path safe from deletion-cascade
- Internxt delta-page ancestor-uuid drop self-healing via bounded re-fetch
- Internxt deleted-marker investigation — trashing bumps `updatedAt`; `/files` status-flip lag is the real blocker
- OneDrive delta-endpoint 410-Gone self-heals into full re-enumeration in tracking engine
- `ts` CLI never authenticated provider — fixed by adding `authenticateAndLog()` to sync/claim commands
- Tracking-set Internxt trash→reap live-verify — trashing bumps `updatedAt`; flat `/files` listing lags ≥60 min
