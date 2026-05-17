# Changelog

All notable changes to this project will be documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Session review packet
  [`docs/dev/2026-05-16-mcp-cli-session-review.md`](dev/2026-05-16-mcp-cli-session-review.md)
  — self-contained brief for an external reviewer (peer AI agent or
  human co-maintainer) auditing the three PRs that landed 2026-05-16
  (UD-758 / UD-708+UD-283 / UD-776). Files four follow-up tickets
  (UD-777 CI gate, UD-778 MCP matrix, UD-409 `-c` UX, UD-410 `-p`
  ordering docs), sweeps residual doc drift in
  `docs/dev/manual-test-checklist.md` (three fabricated CLI
  commands), and adds a supersession note to the UD-708 closed-
  ticket body where the pre-fix JSON example used a jar path the
  build doesn't produce.
- UD-776: [`scripts/dev/verify-cli.py`](../scripts/dev/verify-cli.py) +
  [`docs/dev/cli-verification-matrix.md`](dev/cli-verification-matrix.md)
  — empirical CLI surface verifier. Drives every `@Command` × representative
  option mutation against an ephemeral `localfs` sandbox, captures
  exit/stdout/stderr per row, and emits a diff-stable markdown matrix
  (127 PASS · 0 FAIL · 5 SKIP at first run). Includes regression rows
  pinning the four fabricated commands from PR #39 (`identity`,
  `auth begin`, `auth complete`, `profile add --name`) as picocli
  usage errors. Lesson at
  [`docs/dev/lessons/cli-surface-verify-before-doc.md`](dev/lessons/cli-surface-verify-before-doc.md).
- UD-708: [`docs/MCP-USER-GUIDE.md`](MCP-USER-GUIDE.md) — canonical
  operator + LLM-user guide for the unidrive MCP server. Covers build,
  deploy, profile + auth, MCP-client registration (Claude Code, Claude
  Desktop, generic stdio), the 23-tool / 3-resource surface, common
  workflows, and triage. Plus a short
  [`core/app/mcp/README.md`](../core/app/mcp/README.md) module pointer
  and a "see install:" cross-link from
  [SPECS.md §2.2](SPECS.md#22-surface-b--mcp-json-rpc-20-over-stdio).
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
- UD-375: OneDrive MCP `unidrive_auth_begin` now emits `interval_seconds`
  and `expires_in` as JSON numbers (not strings). Pre-fix the post-UD-014
  refactor stringified them via `.toString()` when packing into
  `BeginAuthResult.fields: Map<String, String>`, breaking the documented
  wire-format contract (OAuth device-code RFC: both are numeric). External
  MCP clients parsing those fields numerically would see `"5"` instead of
  `5` and either fail or need string-coercion special-casing. Fix: lift
  `BeginAuthResult.fields` to `Map<String, JsonElement>` so each provider
  emits the right `JsonPrimitive(Long/String/etc.)` and the MCP
  serializer preserves types end-to-end. New regression test
  `OneDriveInteractiveAuthContractTest.begin_wire_format_keeps_numerics_unquoted_after_mcp_jsonbuilder`
  mirrors `AuthTool`'s JsonObjectBuilder logic and pins
  `"interval_seconds":5` (unquoted) on the wire.
- UD-283: SPECS.md §2.2 was stale on MCP `profile` tool-arg semantics
  — claimed the field was "silently ignored", but `ProfileArgValidator`
  (UD-283 implementation) actually short-circuits with a structured
  `-32602 INVALID_PARAMS` error when the arg doesn't match the active
  profile. Doc now matches the code at
  [`ProfileArgValidator.kt:33`](../core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/ProfileArgValidator.kt).
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
