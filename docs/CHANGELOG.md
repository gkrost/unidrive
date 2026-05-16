# Changelog

All notable changes to this project will be documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Intake of `benchmark` module from unidrive-closed (UD-701 cloud
  provider speed ranking). New CLI surface: `unidrive benchmark ...`
  (top-level subcommand on `Main`; was `unidrive provider benchmark`
  in the private repo before the public `provider` subcommand was
  removed in commit b07d864).
- Intake of `cli-full` shadow-JAR aggregator from unidrive-closed
  (transitively bundles `:app:cli` + `:app:benchmark`).
- Intake of `e2e-360` Dockerized integration harness from
  unidrive-closed (UD-800 CloudForge follow-up).
- `docs/CLOUD_PROVIDERS_DATABASE.md` (cloud provider catalog).
- `docs/dev/BENCHMARK_HANDOVER.md` (benchmark operational handover).
- UD-401 (Enhanced provider table Phase 2).
- This `CHANGELOG.md` file.

### Removed
- UD-810: tests asserting Kotlin data-class-generated machinery on
  `ProviderMetadata` / `ShareInfo` / `CloudItem`. The deleted tests
  exercised the compiler's `equals` / `hashCode` / `copy` / generated
  getters — none of them pinned a domain invariant. If `data class`
  ever becomes a regular `class`, callers fail at compile time;
  runtime tests don't add coverage there. Deleted:
  `ProviderMetadataTest.{ProviderMetadata stores all required fields,
  ProviderMetadata optional fields, ProviderMetadata data class
  equality and copy, ShareInfo stores all fields, ShareInfo defaults}`
  and `CloudItemTest.{equal items have equal hashCodes, items
  differing in any field are not equal, hashCode is stable across
  calls, works correctly as HashMap key}`. Per UD-813 audit.

### Fixed
- UD-758: MCP `initialize` now responds with the server-supported
  protocol version instead of rejecting any non-matching client
  version with `-32602 INVALID_PARAMS`. Pre-fix, Claude Code 2.1.143
  (announcing `2025-11-25`) and every other client on a post-2024-11-05
  protocol rev would see `claude mcp list` report
  `unidrive: ✗ Failed to connect`. Lifts the version into the
  `SUPPORTED_PROTOCOL_VERSION` constant in `JsonRpc.kt`; emits a single
  WARN for operator visibility when the client and server versions
  diverge. See [`docs/dev/lessons/mcp-protocol-version-negotiation.md`](dev/lessons/mcp-protocol-version-negotiation.md).
- UD-211: `LocalWatcher` coalesces editor atomic-save bursts (vim
  `:w`, JetBrains/Emacs write-tmp-and-rename) into a single
  trailing-edge emit per path per quiet window. Previous leading-edge
  emit propagated to providers as destructive delete-then-recreate of
  the cloud file. Default debounce 2000 ms, configurable via
  `UNIDRIVE_WATCHER_DEBOUNCE_MS`.
- UD-213: `BenchmarkCommand` multi-profile loop now invalidates
  `Main`'s memoised profile / vault caches on each `main.provider`
  mutation. Previously iterations 2..N read the cached first profile
  while printing later profile names — multi-profile benchmark
  results would have been silently misattributed. (Ticket filed
  retroactively for shipped commit 407c664; previously squatted on
  UD-211 in source / EXTENSIONS.md.)
