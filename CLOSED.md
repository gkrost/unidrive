# Closed

Things that were done before this branch started. Append new entries when items move out of `BACKLOG.md`.

## Pre-slim

- Reconciler O(D×U) syscall storm replaced with bySize prepass
- CLI verification matrix captured
- LocalWatcher atomic-save coalescing (vim, JetBrains write-tmp-and-rename debounce)
- BenchmarkCommand profile cache invalidation across profiles
- Provider data-class compiler-generated tests removed (no domain invariant pinning)
- Defence-in-depth deletion guards on the reconciler
- IPC close after one-shot commands
- Folder-move dedup and parent-overlap edge cases in the reconciler
- Internxt folder-uuid cache for repeated lookups
- Sync path normalization correctness fixes
- Recovery loops for transient failure modes
- Rename-prefix primary-key collision fix
- Internxt API and SPI mapping audit
- Internxt WebSocket feasibility audit
- Provider robustness audits for Internxt and OneDrive
- Architecture decision records: Linux-only first release, no tray UI in core, no shell extensions in core

## Slim-branch consolidation

- Strip non-MVP providers from build (`localfs`, `s3`, `sftp`, `webdav`, `rclone`); strip `:app:{benchmark,cli-full,e2e-360,xtra}`. Regenerate gradle lockfiles. Trim dependent CLI tests.
- Delete `core/app/mcp/` and all references. MCP surface dropped from scope.
- Remove CI policing (`gitleaks`, `semgrep`, `version-drift`, doc-drift, backlog-sync linter, test-hygiene). `./gradlew check` is the only gate.
- Delete Windows-only scripts and `dist/windows/`; Linux-only surface.
- Delete `core/docker/` test harnesses (compose files all targeted removed providers or removed MCP server).
- Scrub `dist/install.sh` / `dist/README.md` of MCP references; installer is CLI-only.
- Absorb `CONTRIBUTING.md` and `CLAUDE.md` into `AGENTS.md` as the single rulebook; collapse `CLAUDE.md` to a one-line pointer.
- Co-locate provider docs with provider modules (`core/providers/{internxt,onedrive}/README.md`, `core/app/core/README.md`).
- Trim surviving ADRs (capability contract, Linux-only, shipping surface) and drop the seven obsolete ones.
- Rewrite `README.md` as the Linux-power-user marketing front.

## Drained from BACKLOG

- Internxt delta path-collapse (phantom folders). `buildFolderPath` now returns `String?`; null on missing non-root ancestor propagates through `toDeltaCloudItem` (also nullable) into `delta()`, which filters dropped items via `filterNotNull`, counts them, logs the count, and returns `DeltaPage(complete=false)` so the engine skips `detectMissingAfterFullSync` and leaves the cursor un-advanced. Regression test flipped from pinned-buggy to pinned-correct.
- Internxt state-db duplicate-`remote_id` cleanup. One-shot migration in `StateDatabase.createTables()` runs on first `initialize()` after upgrade. For each `remote_id` with more than one row, the row with the longest path wins (the bug shallowed paths, so the deepest is the pre-bug truth); the rest are deleted in a single SQL window-function DELETE. Idempotent: a `migration:dedupe_remote_id` marker in `sync_state` carries the deletion count and gates re-runs. Fresh databases set the marker with zero rows touched.
