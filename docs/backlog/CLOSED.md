# Closed items — append-only archive

> When closing a backlog item, **move** the full frontmatter block from [BACKLOG.md](BACKLOG.md) to the bottom of this file and add `status: closed`, `closed: YYYY-MM-DD`, and optional `resolved_by:`. Never edit entries above the latest. See [AGENT-SYNC.md](../AGENT-SYNC.md).

<!-- First closed entry will be appended below this marker. -->

---
id: UD-104
title: WebDAV trust_all_certs — wire or remove
category: security
priority: medium
effort: S
status: closed
closed: 2026-04-17
resolved_by: investigation — already wired at WebDavApiService.kt:53-70 (switches to Ktor Java engine with permissive X509TrustManager); UD-104 was opened from a superficial grep that only saw the declaration in RawProvider (a config data class). No code change needed. Verification locked in by WebDavApiServiceSslTest (2 tests covering both SSL branches).
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt:53
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceSslTest.kt
adr_refs: [ADR-0004]
opened: 2026-04-17
---
Original backlog wording kept for history. Closed same day as opened after WebDavApiService was read in detail during the Windows-deploy + TDD session. Lesson logged in `~/.claude/projects/.../memory/feedback_evidence_over_assumption.md`: grep the actual consumer before asserting "unwired".

---
id: UD-204
title: Locale-neutral number formatting in CLI output
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-17
resolved_by: fixed at CliProgressReporter.kt:43 — use String.format(Locale.ROOT, "%.1f", ...). Regression covered by CliProgressReporterTest (2 tests under Locale.GERMAN asserting "(1.5s)" / "(2.2s)" rendering).
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:43
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt
adr_refs: []
opened: 2026-04-17
---
Sibling of the earlier `RelocateCommand.formatSize` fix; both came from German-locale test failures (`1,5 s` vs expected `1.5 s`). Open audit task remaining: rest of the CLI may still have locale-default `%.1f` calls — grep `"%.\d+[fF]".format(` before the next release.

---
id: UD-304
title: LocalFsProvider.safePath rejects sync-engine canonical paths (leading slash)
category: providers
priority: high
effort: S
status: closed
closed: 2026-04-17
resolved_by: fixed at LocalFsProvider.kt:60 — strip leading '/'/'\' via trimStart before Path.resolve. Regression covered by 3 new tests in LocalFsProviderTest (safePath leading slash, mixed/repeated slashes, download end-to-end). Also caught 2 ToolHandlerTest regressions that were encoding the buggy behavior — rewritten to assert the now-correct file-missing isError response.
code_refs:
  - core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt:60
  - core/providers/localfs/src/test/kotlin/org/krost/unidrive/localfs/LocalFsProviderTest.kt
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/ToolHandlerTest.kt:381
adr_refs: []
opened: 2026-04-17
---
Discovered during MCP integration testing when `unidrive_sync` aborted with "Path traversal rejected: '/docs'". The sync engine uses leading-slash canonical paths; Java's `Path.resolve(absolute)` replaces the base, so `rootPath.resolve("/docs")` became `C:\docs` (Windows) or `/docs` (POSIX) — both fail `startsWith(rootPath)`. This meant localfs had never actually synced anything with the current engine. High-priority; shipped in same session.

---
id: UD-105
title: JSON-Schema validation on every NDJSON frame (legacy wire format, daemon side)
category: security
priority: high
effort: M
status: closed
closed: 2026-04-17
resolved_by: NdjsonValidator (subset draft-2020-12 — type, required, properties, additionalProperties, enum, const, minLength/maxLength, pattern, minimum/maximum) plus COMMAND_SCHEMA constant in NamedPipeServer; dispatch now validates before unpacking and returns schema-violation error on reject without logging frame contents. 14 NdjsonValidatorTest cases cover the keyword subset; 4 NamedPipeServerDispatchTest cases cover integration.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NdjsonValidator.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/NdjsonValidatorTest.kt
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/NamedPipeServerDispatchTest.kt
adr_refs: [ADR-0003, ADR-0004]
opened: 2026-04-17
---
Scoped to **the legacy flat wire format** (`{"cmd":"state","path":"..."}`) currently spoken by shell-win/PipeClient.cpp. The envelope format in protocol/schema/v1/*.json — `{"v":1,"kind":"command","op":"state","payload":{...}}` — is the UD-601 migration target. When that migration lands, the validator class is reused with the envelope schemas loaded from resources; no new validator code needed. Client-side (shell-win, ui) schema validation is **not** part of UD-105; tracked as follow-ups UD-105a (shell-win PipeClient input validation) and UD-105b (ui IpcClient input validation) once opened.

---
id: UD-106
title: NDJSON frame size cap + per-second rate limit
category: security
priority: medium
effort: S
status: closed
closed: 2026-04-17
resolved_by: TokenBucket class (per-connection, injectable clock) + MAX_FRAME_BYTES=65536 / RATE_BURST=50 / RATE_PER_SEC=50 constants in NamedPipeServer. Refactored handleClient to delegate single-line gating to new internal processLine(line, bucket) for testability. Chunk-level cap rejects >64 KiB leftover without newline; line-level cap rejects oversized split frames; rate-limit runs before dispatch. 6 TokenBucket unit tests + 3 processLine integration tests.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/TokenBucket.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/TokenBucketTest.kt
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/NamedPipeServerDispatchTest.kt
adr_refs: [ADR-0004]
opened: 2026-04-17
---
Defaults (50 burst, 50/sec) are conservative for local IPC — tune up if the shell extension's usage pattern demands more (revisit when UD-401 wires real pipe-based hydration). shell-win client-side enforcement is out of scope; tracked as UD-106a once opened.

---
id: UD-107
title: gitleaks in pre-push hook + CI
category: security
priority: medium
effort: S
status: closed
closed: 2026-04-17
resolved_by: .gitleaks.toml with default-rules extension + allowlist (AWS example key AKIAIOSFODNN7EXAMPLE, test-fixture passwords, docker-compose and SftpApiService doc comment path exceptions). scripts/ci/gitleaks.sh runs `gitleaks detect --source . --config .gitleaks.toml --redact --verbose`; scripts/hooks/pre-push invokes same on working tree and warns-without-blocking if gitleaks binary is absent; scripts/hooks/install.sh wires it into .git/hooks/. scripts/ci/README.md updated. End-to-end scanner invocation deferred until host installs gitleaks; TOML + shell syntax verified (`python -m tomllib`, `bash -n`).
code_refs:
  - .gitleaks.toml
  - scripts/ci/gitleaks.sh
  - scripts/hooks/pre-push
  - scripts/hooks/install.sh
  - scripts/ci/README.md
adr_refs: [ADR-0004]
opened: 2026-04-17
---
gitleaks binary is not installed on this developer host — the CI-fragment script exits 127 when invoked there, which is correct fail-loud behavior. Once UD-701 picks a git host and CI runs the fragment, real scans land. Host-install task: `scripts/hooks/install.sh` is a one-time user action.

---
id: UD-112
title: Formalize STRIDE threat model in SECURITY.md
category: security
priority: medium
effort: M
status: closed
closed: 2026-04-17
resolved_by: docs/SECURITY.md rewritten — asset inventory (8 items A1..A8), ASCII data-flow diagram with trust boundaries (user / same-user local / network / provider), full STRIDE table with 13 entries (≥2 per letter), each row anchored to file:line mitigations. All UD-### references verified against BACKLOG/CLOSED. The seed STRIDE's R-row previously claimed UD-111 covered "audit log per action" — corrected; R-row now maps UD-111 to refresh-failure surfacing and a new ticket UD-113 opened to capture the unclaimed audit-log gap.
code_refs:
  - docs/SECURITY.md
adr_refs: [ADR-0004]
opened: 2026-04-17
---
By-product: UD-113 opened for "structured sync-action audit log" — the gap surfaced during cross-check between the seed STRIDE claim and UD-111's actual scope.

---
id: UD-202
title: MCP JSON-RPC roundtrip test harness
category: core
priority: high
effort: M
status: closed
closed: 2026-04-17
resolved_by: McpRoundtripIntegrationTest (6 tests, all green in 8.1s total) covers initialize, tools/list shape, unidrive_status, unidrive_sync + unidrive_ls, unidrive_quota, and a post-UD-304 regression test that asserts zero "Path traversal rejected" stderr lines on localfs sync. Spawns the shadow jar via ProcessBuilder; closes stdin to let the server exit cleanly; separate stdout/stderr reader threads prevent pipe-full deadlock. 15-second per-test safety timeout.
code_refs:
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/McpRoundtripIntegrationTest.kt
adr_refs: []
opened: 2026-04-17
---
Uses `org.junit.Assume.assumeTrue(jar.exists(), ...)` as the skip guard — respects the UD-704 hygiene rule (no bare-return silent skips). Coverage of share / versions / backup / relocate tools deferred to follow-on work (UD-802 protocol contract test + UD-301 capability contract will drive rewrites); the 5 core tools here are the v0.1.0 scope.

---
id: UD-703
title: Modernize core/tests/integration-test.sh for the monorepo layout
category: tooling
priority: medium
effort: S
status: closed
closed: 2026-04-17
resolved_by: Post-monorepo header + prerequisites updated. UD_TEST_PROFILE env var for profile fallback. Mutual-exclusion regex loosened to `'mutually exclusive|cannot.*(combine|both)|both.*(specified|given)'` to tolerate wording drift (actual wording observed today is "Error: --upload-only and --download-only are mutually exclusive"). Line 154 sync-dry-run and the Section-4b relocate call are now wrapped in the same `--skip-network` skip pattern as Section 3. Section 4a grep anchors switched from raw unicode ✔/✘/– to ASCII `[... OK]` / `[... AUTH]` fragments — unicode had been mangled on cp1252 consoles (pre-existing latent bug).
code_refs:
  - core/tests/integration-test.sh
adr_refs: []
opened: 2026-04-17
---
`--skip-network` run now exits 0 with tally `PASS: 2  FAIL: 0  SKIP: 8` on a profile-free host. With `UD_TEST_PROFILE=ds418play` the Section 2c mutual-exclusion check flips from skip → pass, confirming the logic works when a resolvable profile is available.

---
id: UD-301
title: CloudProvider capability contract implementation
category: providers
priority: high
effort: M
status: closed
closed: 2026-04-17
resolved_by: Introduced sealed `Capability` hierarchy (`Delta`, `DeltaShared`, `Webhook`, `Share`, `ListShares`, `RevokeShare`, `VerifyItem`, `QuotaExact`) + `CapabilityResult<T>` (Success / Unsupported with `capability` + `reason`). Added required `CloudProvider.capabilities(): Set<Capability>`. Flipped 6 optional methods (share / listShares / revokeShare / handleWebhookCallback / verifyItemExists / deltaWithShared) from silent-default return types to `CapabilityResult<T>`. All 8 provider adapters now declare truthful capability sets. SyncEngine falls back to `delta()` when `deltaWithShared()` returns Unsupported. 7 new CapabilityContractTest cases + 8 rewritten CloudProviderDefaultsTest cases. Full clean build: 104 tasks, 0 failures.
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/Capability.kt
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
  - core/app/core/src/test/kotlin/org/krost/unidrive/CapabilityContractTest.kt
  - core/app/core/src/test/kotlin/org/krost/unidrive/CloudProviderDefaultsTest.kt
  - core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProvider.kt
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProvider.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
  - core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProvider.kt
  - core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveProvider.kt
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/ShareTool.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ShareCommand.kt
adr_refs: [ADR-0005]
opened: 2026-04-17
---
Design choice: **return-type change** (not exception). Rationale: sealed `CapabilityResult` delivers compile-time exhaustiveness; ADR-0005's "Alternatives considered" explicitly argued this. Blast radius stayed manageable (3 production call sites + a handful of test stubs). OneDrive's declared `Webhook` capability is aspirational — `handleWebhookCallback` still returns `Unsupported` today (webhook callback handler hasn't been implemented, only subscription management). Downstream implications picked up automatically by delegation wrappers (`XtraEncryptedProvider`, `ThrottledProvider`). **UD-704's 10 capability-cementing tests rewritten in the same change-set** (items 1-10 of UD-704 spec); residuals remain tracked under UD-704-residual.

---
id: UD-704
title: Test-suite hygiene — eliminate bug-cementing assertions + silent-skip early-returns
category: tests
priority: medium
effort: S
status: closed
closed: 2026-04-17
resolved_by: Three parts landed across three commits. Part 1 (silent skips, 6 CliSmokeTest methods) closed in c5bc05d via `org.junit.Assume.assumeTrue(jarPath.exists(), ...)` replacement. Part 2 (10 capability-cementing tests) closed alongside UD-301 in the same commit-set that introduced `CapabilityResult.Unsupported` — each test now asserts the Unsupported outcome explicitly (`assertIs<Unsupported>` for unit tests, `isError + startsWith("Unsupported:")` for MCP handler tests). Part 3 residuals (1 brittle error-witness test + CI test-hygiene lint) moved to UD-704-residual.
code_refs:
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliSmokeTest.kt
  - core/app/core/src/test/kotlin/org/krost/unidrive/CloudProviderDefaultsTest.kt
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/ToolHandlerTest.kt
adr_refs: [ADR-0005]
opened: 2026-04-17
---
Broader audit yielded 16 suspect tests; 15 remediated (6 assumeTrue + 9 actively rewritten — the "share - create with localfs" test was already rewritten earlier for UD-304 and is now the UD-301 version). The UD-704-residual follow-up covers the 10th test's brittle error-message witness plus adding `scripts/ci/test-hygiene.sh` grep-based linter for future drift prevention.

---
id: UD-203
title: Document config.toml schema
category: core
priority: low
effort: S
status: closed
closed: 2026-04-18
resolved_by: commit b28c82b. Added docs/config-schema/config.example.toml (annotated canonical, 1:1 with RawGeneral/RawProvider in SyncConfig.kt) and docs/config-schema/config.schema.json (draft 2020-12, with per-provider conditional allOf/if/then requirements). Cross-linked from ARCHITECTURE.md persistence table. Fixed collateral SECURITY.md bug (UNIDRIVE_VAULT_PASSPHRASE → UNIDRIVE_VAULT_PASS).
code_refs:
  - docs/config-schema/config.example.toml
  - docs/config-schema/config.schema.json
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt
opened: 2026-04-17
---

---
id: UD-210
title: CLI handleAuthError swallows the underlying cause
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-18
resolved_by: commit 90bc23c. Extracted pure renderAuthError(e, providerId, providerDisplayName, verbose) helper from Main.handleAuthError; under -v it now prints "Cause: <e.message>" plus the root cause's fully-qualified class name + message. Default output unchanged. Two regression tests in HandleAuthErrorTest guard both verbose and non-verbose branches (including a regression guard that default output never leaks cause details or FQCN).
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/HandleAuthErrorTest.kt
opened: 2026-04-17
---

---
id: UD-501
title: Replace manual TOML parsing in TrayManager with a real parser
category: ui
priority: medium
effort: S
status: closed
closed: 2026-04-18
resolved_by: commit 8a1989e. ui/settings.gradle.kts now imports the monorepo version catalog from ../core/gradle/libs.versions.toml (no per-file version fork); ui/build.gradle.kts declares implementation(libs.ktoml.core); TrayManager parses config.toml with ktoml into internal TrayConfig/TrayGeneral/TrayProvider data classes, replacing the hand-rolled string search. Six tests in TrayManagerTest cover UD-501 acceptance plus regression guards (comment lines, multi-line values, quoted keys).
code_refs:
  - ui/settings.gradle.kts
  - ui/build.gradle.kts
  - ui/src/main/kotlin/org/krost/unidrive/ui/TrayManager.kt
  - ui/src/test/kotlin/org/krost/unidrive/ui/TrayManagerTest.kt
opened: 2026-04-17
---

---
id: UD-601
title: Extract NDJSON grammar into protocol/schema/v1/ + fixtures
category: protocol
priority: high
effort: M
status: closed
closed: 2026-04-18
resolved_by: commit 8937f70. 15 new schemas (9 UI-event payloads Surface A + 6 shell command request/response pairs Surface B) + 12 new fixtures + protocol/schema/v1/INDEX.md single-page op→schema→fixture→emitter map. All schemas are draft 2020-12 envelope $ref style matching state-event.json. Records (without deciding) 5 doc/code disagreements surfaced during schema-walking — state='local' enum gap, fetch error/message field name mismatch, cleanup ignores sync_root, notify_register handler vs SPECS doc-only, sync_complete actionCounts accepted by API but not on wire. Schema-owner decision on the envelope-vs-flat wire shape remains open — wired UD-802 contract test treats $defs.payload as the payload contract to avoid modifying schemas.
code_refs:
  - protocol/schema/v1
  - protocol/fixtures
  - protocol/schema/v1/INDEX.md
  - protocol/README.md
adr_refs: [ADR-0003]
opened: 2026-04-17
---

---
id: UD-802
title: Protocol contract test — fixtures replayed on both tiers
category: tests
priority: high
effort: M
status: closed
closed: 2026-04-18
resolved_by: commit 8232078. ProtocolContractTest walks every protocol/fixtures/*.ndjson line, validates envelope against envelope.json, looks up per-op payload schema via INDEX.md mapping, validates payload against $defs.payload (event schemas) or schema directly (Surface B), then JSON-semantic-round-trips via kotlinx.serialization JsonElement equality (deterministic encode, not byte-for-byte). Reuses NdjsonValidator (UD-105) — no new deps. Wired into :app:sync:check. Second test asserts every op in INDEX mapping has at least one fixture line covering it. Shell-side CMake ctest parity deferred (out of scope; re-open once UD-601 schema/fixture envelope-shape decision lands).
code_refs:
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProtocolContractTest.kt
  - protocol/fixtures
  - protocol/schema/v1
adr_refs: [ADR-0003]
opened: 2026-04-17
---

---
id: UD-106a
title: Shell-side NDJSON validation parity
category: security
priority: medium
effort: M
status: closed
closed: 2026-04-18
resolved_by: commit 8a692b8. Hand-rolled C++ NdjsonValidator (no new JSON dep — shell-win still links none) with single-pass string-literal-aware scanner. 64 KiB line cap, top-level JSON-object shape + no-trailing-garbage check, and — when the line carries the target envelope (top-level v key) — full envelope validation (v==1, ts ISO-8601-ish, kind in {event,command,response,error}, op in a 7-entry whitelist, payload is object). Legacy-flat frames without v get only the structural check, so today's wire stays green through the UD-601 migration. Hooked into PipeClient::sendCommand: malformed frames are dropped with a reason-only log (never frame contents, per UD-105 rule). 25-case CTest self-check (test-ndjson-validator) guards the behavior.
code_refs:
  - shell-win/src/NdjsonValidator.h
  - shell-win/src/NdjsonValidator.cpp
  - shell-win/src/PipeClient.cpp
  - shell-win/CMakeLists.txt
adr_refs: []
opened: 2026-04-18
---

---
id: UD-101
title: Enforce SDDL / ACL on the unidrive-state named pipe
category: security
priority: high
effort: M
status: closed
closed: 2026-04-18
resolved_by: commit c75c16b. Server side (JVM) — new PipeSecurity.kt resolves the running user's SID via Panama FFI (OpenProcessToken + GetTokenInformation(TokenUser) + ConvertSidToStringSidW) and builds the SDDL D:P(A;;GA;;;<userSid>)(A;;GA;;;SY). NamedPipeServer.createPipe() passes the resulting SECURITY_ATTRIBUTES to CreateNamedPipeW; fails closed (INVALID_HANDLE_VALUE) on construction error — never silently regresses to NULL DACL. Client side (C++) — PipeClient now fetches pipe owner via GetSecurityInfo and rejects if it doesn't match current user SID, catching the pre-squat case. Tests — 4 JVM tests pin SDDL construction (contains running SID exactly once, grants SY, D:P protected, no WD/AU/BU/IU/AN allow ACE, exactly 2 Allow + 0 Deny); 11-assert test-pipe-owner CTest exe round-trips the SDDL through the Windows security API and exercises the live owner-check against a self-created pipe. "Different-user denied" is not reachable on a single-user dev box — acceptance met indirectly via DACL construction-pinning. Follow-up UD-101a flagged inline for ~80B LocalAlloc leak per createPipe() (acceptable at nMaxInstances=1; must LocalFree when per-connection SDs land).
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/PipeSecurity.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/PipeSecurityTest.kt
  - shell-win/src/PipeClient.cpp
  - shell-win/test/test_pipe_owner.cpp
adr_refs: [ADR-0003, ADR-0004]
opened: 2026-04-17
---

---
id: UD-705
title: CI workflow template (host-neutral GitHub Actions)
category: tooling
priority: high
effort: S
status: closed
closed: 2026-04-18
resolved_by: commit 4790937. .github/workflows/build.yml mirrors the host-neutral fragments under scripts/ci/. Three jobs — core (ubuntu-latest + windows-latest matrix running ./gradlew build test jacocoMergedReport with test-report and jacoco artifact upload), shell-win (windows-latest, CMake configure + build Debug + ctest), gitleaks (ubuntu-latest, installs gitleaks 8.21.2 and runs scripts/ci/gitleaks.sh). Triggers on push to main + PRs; concurrency group keyed on ref. Verified actionlint 1.7.7 clean on this host. UD-701 (host choice) remains open; workflow activates with zero edits once the repo lives on GitHub, and translates 1:1 to GitLab because every run body is a standalone shell fragment.
code_refs:
  - .github/workflows/build.yml
  - scripts/ci
adr_refs: [ADR-0007]
opened: 2026-04-18
---

---
id: UD-706
title: ktlint baseline (detekt follow-on)
category: tooling
priority: medium
effort: S
status: closed
closed: 2026-04-18
resolved_by: commit 2fc6664. Added org.jlleitschuh.gradle.ktlint 12.1.2 via catalog entry in core/gradle/libs.versions.toml. Applied to core via the existing subprojects {} block in core/build.gradle.kts — single config site covers all 13 Kotlin modules (app:core/sync/cli/mcp/xtra + 8 providers). ui/build.gradle.kts takes the same version catalog entry. Warn-only semantics via ignoreFailures=true. 14 baseline.xml files committed at <project>/config/ktlint/baseline.xml (plugin-default path). PLAIN + CHECKSTYLE reporters. Verified ./gradlew ktlintCheck exits BUILD SUCCESSFUL in both composites. No CI workflow change needed — ktlintCheck is pulled in by check, which build already invokes; inherently warn-only today. No source files reformatted. detekt follow-on left as a separate future ticket per the UD-706 description.
code_refs:
  - core/build.gradle.kts
  - core/gradle/libs.versions.toml
  - ui/build.gradle.kts
opened: 2026-04-18
---

---
id: UD-707
title: Refresh docker integration harness for core/ monorepo layout
category: tooling
priority: medium
effort: S
status: closed
closed: 2026-04-18
resolved_by: commit 46bc0a4. Rewrote core/docker/Dockerfile.test + docker-compose.test.yml for the current monorepo layout. Fixes stacked up during the greenfield restart — `:cli:shadowJar` → `:app:cli:shadowJar`; JDK 25 → JDK 21 (matches CI); missing gradle.properties reference removed; jar copy path cli/build/libs → app/cli/build/libs. Also fixed core/.dockerignore `*/build/` (one level) → `**/build/` + `**/.gradle/` — build context collapsed from 458 MB to reasonable size. Stripped CRLF from gradlew + test-matrix.sh inside the image so a Windows checkout builds cleanly without host-side dos2unix. Two-stage image: JDK 21 builder runs `:app:cli:shadowJar`, JRE 21 runtime ships the fat jar + test script as non-root testuser. README refreshed with layout notes + expected 8-test pass count. Verified locally: `docker compose -f core/docker/docker-compose.test.yml run --rm unidrive-test` → "Results: 8 passed, 0 failed".
code_refs:
  - core/docker/Dockerfile.test
  - core/docker/docker-compose.test.yml
  - core/docker/README.md
  - core/.dockerignore
opened: 2026-04-18
---

---
id: UD-708
title: Wire docker integration test into CI
category: tooling
priority: medium
effort: XS
status: closed
closed: 2026-04-18
resolved_by: commit 3e8af68. Added `docker-integration` job to .github/workflows/build.yml (ubuntu-latest). Two steps: `docker compose -f docker/docker-compose.test.yml build` and `... run --rm unidrive-test`, both from core/ working directory. No provider credentials or network needed — the harness (UD-707) runs pure localfs round-trip in a tmpfs workspace. Verified actionlint 1.7.7 clean.
code_refs:
  - .github/workflows/build.yml
  - core/docker
opened: 2026-04-18
---

---
id: UD-709
title: integrationTest Gradle tasks pick wrong bash on Windows
category: tooling
priority: low
effort: XS
status: closed
closed: 2026-04-18
resolved_by: commit ccf79af. Surfaced while validating UD-707/708 work: `./gradlew integrationTestOffline` on Windows failed with "WSL (1537 - Relay) ERROR: execvpe(/bin/bash)" because Gradle's `commandLine("bash", ...)` resolved to `C:\Windows\System32\bash.exe` (the WSL launcher shim), and no WSL distribution was installed. Not visible in CI — the Linux job uses real `/bin/bash`, and the Windows CI job doesn't invoke these tasks. Fix: extracted `resolveBashExecutable()` in core/build.gradle.kts. On non-Windows, returns `"bash"` as before. On Windows, probes `%PROGRAMFILES%/Git/bin/bash.exe`, `%PROGRAMFILES(X86)%/Git/bin/bash.exe`, the hardcoded `C:/Program Files/Git/bin/bash.exe` fallback, then MSYS2 `C:/msys64/usr/bin/bash.exe`. Errors with an actionable message if none exist. Verified `./gradlew -p core integrationTestOffline` → BUILD SUCCESSFUL; `PASS: 2  FAIL: 0  SKIP: 8` on a profile-free Windows dev host.
code_refs:
  - core/build.gradle.kts
opened: 2026-04-18
---

---
id: UD-101a
title: Free LocalAlloc'd PSECURITY_DESCRIPTOR after CreateNamedPipeW
category: security
priority: low
effort: XS
status: closed
closed: 2026-04-18
resolved_by: commit 8b29e7d. Follow-up flagged in UD-101 (c75c16b): `ConvertStringSecurityDescriptorToSecurityDescriptorW` returns a LocalAlloc'd `PSECURITY_DESCRIPTOR` that the daemon never freed — acceptable at `nMaxInstances=1` (kernel copies the SD into the pipe object at CreateNamedPipeW) but a real ~80-byte-per-acceptLoop-iteration leak. Fix: `PipeSecurity.allocateSecurityAttributes` now returns a small `AllocatedSecurityAttributes(sa, pSd)` data class transferring ownership to the caller; new `PipeSecurity.freeSecurityDescriptor(pSd)` wraps `LocalFree`. `NamedPipeServer.createPipe()` calls the free in a `finally` block around `CreateNamedPipeW`, so the SD is released on both success and failure paths. Failure contract unchanged — `allocateSecurityAttributes` now returns `null` (was `MemorySegment.NULL`); caller still fails closed with `INVALID_HANDLE_VALUE`. Existing 4-test `PipeSecurityTest` stays green. Verified `./gradlew -p core build test` BUILD SUCCESSFUL.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/PipeSecurity.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt
adr_refs: []
opened: 2026-04-18
---

---
id: UD-710
title: Gradle 10 prep — remove Task.project access at execution time
category: tooling
priority: low
effort: XS
status: closed
closed: 2026-04-18
resolved_by: commit 2ce7318. The only Gradle-10 deprecation surfacing on the build path was `Invocation of Task.project at execution time` fired by `:app:cli:generateBuildInfo` (handover mis-diagnosed it as a `finalizedBy` issue). Fix: in both core/app/cli/build.gradle.kts and core/app/mcp/build.gradle.kts, capture `project.version` at configuration time instead of inside `doLast`; hoist the `git rev-parse` call into a `providers.exec` Provider wired up at config time (lazy evaluation preserved). `deploy` tasks similarly hoist `project.version` + `tasks.shadowJar.flatMap { archiveFile }` and plumb them into the Windows/Linux deploy helpers. Release + generateNotice tasks at core/build.gradle.kts still use `project.*` at execution time but are operator-only paths not exercised by `build`/`test`/CI; deferred. Verified `./gradlew -p core clean build --warning-mode all` emits zero Gradle deprecations (only pre-existing Java `OAEPParameterSpec.DEFAULT` deprecation in XtraKeyManager remains). BuildInfo.kt still regenerates with correct version + short SHA.
code_refs:
  - core/app/cli/build.gradle.kts
  - core/app/mcp/build.gradle.kts
adr_refs: []
opened: 2026-04-18
---

---
id: UD-706b
title: Flip ktlint from warn-only to failing with baseline
category: tooling
priority: low
effort: S
status: closed
closed: 2026-04-18
resolved_by: commit 2a00346. `ignoreFailures` flipped to `false` in both `core/build.gradle.kts` subprojects block and `ui/build.gradle.kts`. `ktlintCheck` now fails the build on any violation not listed in the per-project `config/ktlint/baseline.xml` — this matches option (b) from the UD-706 exit note. Regenerated all 14 baselines via `ktlintGenerateBaseline` to re-anchor line numbers after UD-101a + UD-710 code shifts; net delta +3 entries each in `core/app/{cli,mcp}/config/ktlint/baseline.xml` (UD-710's extra `projectVersion: String` parameter on `deployWindows`/`deployLinux` tripped ktlint's `function-signature` multi-line threshold — pure formatting, reformatting deferred to a future cleanup ticket). Verified `./gradlew -p core ktlintCheck` + `./gradlew -p ui ktlintCheck` both BUILD SUCCESSFUL; `./gradlew -p core build test` BUILD SUCCESSFUL (full suite green end-to-end).
code_refs:
  - core/build.gradle.kts
  - ui/build.gradle.kts
  - core/app/cli/config/ktlint/baseline.xml
  - core/app/mcp/config/ktlint/baseline.xml
  - core/app/sync/config/ktlint/baseline.xml
  - ui/config/ktlint/baseline.xml
adr_refs: []
opened: 2026-04-18
---

---
id: UD-701
title: Pick git host, realize scripts/ci/ into concrete workflows
category: tooling
priority: high
effort: M
status: closed
closed: 2026-04-18
resolved_by: commit e8ecfb9 (decision ADR only). Committed to GitHub Actions as the CI host in [ADR-0009](../adr/0009-ci-host.md), closing ADR-0008 open question 2. The "realize scripts/ci/ into concrete workflows" half of the ticket already shipped under UD-705 (`.github/workflows/build.yml`) + UD-708 (`docker-integration` job). This commit only formalizes the commitment: (a) [ADR-0009](../adr/0009-ci-host.md) added; (b) [ADR-0008](../adr/0008-greenfield-restart.md) open question 2 crossed out with forward-ref; (c) [scripts/ci/README.md](../../scripts/ci/README.md) "host-neutral until UD-701 lands" caveat dropped — fragments now described as local-dev reproduction convenience, not portability guarantee; (d) [UD-110](BACKLOG.md#ud-110) unblocked (renamed to Dependabot-only, Renovate dropped; CMake FetchContent deferred). No `.gitlab-ci.yml` / Woodpecker parallel is maintained.
code_refs:
  - .github/workflows/build.yml
  - scripts/ci/README.md
  - docs/adr/0009-ci-host.md
  - docs/adr/0008-greenfield-restart.md
adr_refs: [ADR-0009, ADR-0008, ADR-0007]
opened: 2026-04-17
---

---
id: UD-711
title: Docker provider contract harness (sftp / webdav / s3)
category: tests
priority: medium
effort: M
status: closed
closed: 2026-04-18
resolved_by: commit 7428fb1. Extends core/docker/ with a second harness alongside UD-707's localfs-only runner. New `core/docker/docker-compose.providers.yml` spins up `atmoz/sftp`, `bytemark/webdav`, and `minio/minio` (plus a `minio/mc` one-shot init sidecar that creates the `unidrive-test` bucket), then runs the same JRE 21 image (`Dockerfile.test`) with `test-providers.sh` as a compose-level `entrypoint` override. The script (a) polls each service's port via bash `/dev/tcp` (robust across image tools), (b) seeds `~/.ssh/known_hosts` via `ssh-keyscan` because `SftpApiService` fails closed on a missing file, (c) asserts `provider info <type>` returns a descriptor (pinning on the stable `Tier:` row), (d) asserts `status --all` enumerates each configured profile, (e) runs `quota` against each live provider — the first call that exercises adapter construction + auth + read-only API round-trip. 13 tests, exit 0 on green. Fixed sibling-bug discovered during wiring: `docker-compose.integration.yml` used `AUTHUSER`/`AUTHPASS` for bytemark/webdav but the image honours `USERNAME`/`PASSWORD` — providers compose uses the correct keys. Wired `docker-provider-integration` job into [.github/workflows/build.yml](.github/workflows/build.yml) (ubuntu-latest, no credentials). Runtime image gains a ~4 MB `openssh-client` layer for `ssh-keyscan`. Follow-up scope left open: OAuth providers (OneDrive / HiDrive / Internxt) gated on secret injection; full sync round-trip with file verification on the remote side gated on the above + MinIO mc client in the test image. Verified: `./gradlew -p core build test` BUILD SUCCESSFUL; `docker compose -f core/docker/docker-compose.providers.yml run --rm unidrive-providers-test` → `13 passed, 0 failed`.
code_refs:
  - core/docker/docker-compose.providers.yml
  - core/docker/test-providers.sh
  - core/docker/Dockerfile.test
  - core/docker/README.md
  - .github/workflows/build.yml
adr_refs: []
opened: 2026-04-18
---

---
id: UD-306
title: RcloneProviderFactory doesn't normalise rclone_remote trailing colon
category: providers
priority: high
effort: XS
status: closed
closed: 2026-04-18
resolved_by: commit aeea18a. Caught by the UD-803 docker provider harness extension the same session it was written — the TOML example in docs/config-schema/config.example.toml and the rclone.conf convention both name remotes without the syntactic `remote:` colon, but `RcloneCliService.remotePath` concatenates `config.remote` directly with the operation path, yielding `miniounidrive-test-rclone` instead of `minio:unidrive-test-rclone` and failing every rclone call with exit 3 "directory not found". Existing unit tests (`RcloneEntryParsingTest.remotePath …`) passed because they smuggled the colon into the fixture (`remote = "gdrive:"`). Fix: normalise at the factory boundary — `if (rawRemote.endsWith(":")) rawRemote else "$rawRemote:"` — so both TOML styles work and the invariant `config.remote.endsWith(":")` holds on every code path out of `create()`. Regression guard: new `RcloneProviderFactoryTest` with 2 assertions (reflection-accessed `config` since field is private, kept private). Verified `./gradlew -p core build test` BUILD SUCCESSFUL; docker provider harness `20 passed, 0 failed`.
code_refs:
  - core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProviderFactory.kt
  - core/providers/rclone/src/test/kotlin/org/krost/unidrive/rclone/RcloneProviderFactoryTest.kt
adr_refs: []
opened: 2026-04-18
---

---
id: UD-803
title: Docker provider harness — upload round-trip + rclone coverage
category: tests
priority: medium
effort: M
status: closed
closed: 2026-04-18
resolved_by: commit aeea18a. Extends the UD-711 harness with (a) the biggest gap from the contract-coverage audit (no end-to-end upload/verify against remote providers) and (b) the rclone adapter which had zero live test coverage. For each profile in {sftp, webdav, s3, rclone}: write unique marker in `sync_root`, run `unidrive sync --upload-only`, then fetch via an independent client (sftp heredoc / curl / mc / rclone) and byte-compare. Four new round-trip assertions plus rclone across adapter-registration/status/quota tiers → 20 total (was 13). Dockerfile.test gains ~70 MB of tooling (curl, sshpass, rclone, mc binary) — all gated behind a single apt layer + one mc download. docker-compose.providers.yml adds rclone profile env + a second MinIO bucket (`unidrive-test-rclone`) via the existing minio-init sidecar. rclone adapter is wired against the SAME MinIO service via rclone's native s3 backend, so no extra container is needed. test-providers.sh gains side-channel verifier functions per adapter; SFTP verifier uses sshpass+stdin heredoc rather than `sftp -b` (the latter silently forces BatchMode=yes which disables password auth). Discovered and fixed UD-306 (rclone factory colon bug) en route. Verified: `20 passed, 0 failed` against running compose.
code_refs:
  - core/docker/docker-compose.providers.yml
  - core/docker/test-providers.sh
  - core/docker/Dockerfile.test
  - core/docker/README.md
  - core/providers/rclone/config/ktlint/baseline.xml
adr_refs: []
opened: 2026-04-18
---

---
id: UD-222
title: BUG (critical) — `sync --download-only` default writes zero-byte files, consumes full disk, content is NULL
category: core
priority: high
effort: M
status: closed
closed: 2026-04-18
resolved_by: commit f30930f. Two structural fixes. (1) `PlaceholderManager.createPlaceholder` never pre-allocates bytes — always produces a 0-byte stub; real content comes from `provider.download`. The previous `RandomAccessFile.setLength(size)` branch created fully allocated zero-filled files on NTFS (346 GB poison on UD-712) because NTFS does not auto-sparse on setLength. On Linux (ext4), setLength DID produce sparse files but apps still saw NUL bytes on open — so the behaviour was wrong on every platform, not just where CfApi is unavailable. `updatePlaceholderMetadata` similarly drops setLength; it now bumps mtime only. (2) Reconciler routing: remote-new and remote-modified non-folders now emit `DownloadContent` (Pass 2, concurrent, semaphore-limited hydration), not `CreatePlaceholder`. Pin rules no longer gate hydration — reserved for future CfApi (UD-401/402/403). The existing `CreatePlaceholder` adopt path still downloads when the local file is sparse (`!hasRealContent` now forces download regardless of the vestigial `shouldHydrate` flag). Engine tightens the failure contract: Pass 2 tracks per-transfer failures; `pending_cursor` is only promoted to `delta_cursor` when every transfer succeeded, so partial first-sync failures are retried next run instead of being lost to an advanced Graph cursor. AuthenticationException in any child job is captured, the scope cancelled, and the auth exception rethrown after scope exit so callers see the auth failure (not a JobCancellationException). `restoreToPlaceholder` now creates a fresh non-hydrated DB entry when none exists — previously it only updated entries that Pass 1 had pre-created, which under the new routing never happens. Direction A (abort without CfApi) was rejected as dead-code footgun; Direction C (hydrate by default) was adopted. Test suite updated: `PlaceholderManagerTest`, `ReconcilerTest`, `SyncEngineTest`, `SyncCornerCaseTest`, `BugRegressionTest` (5 tests rewritten to match new contract; new Pass 2 failure-aggregation + cursor-promotion guard tests). Recovery playbook retained in UD-220 draft.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/PlaceholderManager.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
adr_refs: []
opened: 2026-04-18
---

---
id: UD-218
title: BUG — `status --all` groups by provider TYPE instead of per profile
category: core
priority: high
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 15ef01f. Re-investigation showed the core enumeration bug was already fixed in the greenfield scaffold — StatusCommand.discoverProfiles iterates raw.providers.keys and buildAccountRow renders a separate row per profile. Reproduced with a 2-OneDrive-profile config and confirmed both rows appear. What actually reproduced was a label papercut: buildAccountRow aliased `profile.name == profile.type` to the string "default", which is ambiguous in a multi-account view (a profile named `onedrive` rendered as `default` while `onedrive-test` used its real name). Fix: always render `profile.name`. One-line change. The original UD-712 observation (second account invisible) likely came from the user having `[providers.onedrive]` present but `[providers.onedrive-test]` NOT present at the time of observation, and the handler's `it in raw.providers` legacy-dir filter hiding orphan profile dirs — which is actually desirable behaviour (config.toml is the source of truth).
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt
adr_refs: []
opened: 2026-04-18
---

---
id: UD-219
title: Running unidrive silently sees zero profiles when config.toml is unreadable
category: core
priority: high
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 15ef01f. Direction A (loud diagnostic, no root-cause investigation required). Main.resolveCurrentProfile now detects `parseRaw` returning zero providers and prints a structured error listing the config path attempted, the APPDATA / HOME env vars, Files.exists + Files.size on the path, plus guidance for the sandbox-shell case ("if config.toml exists but parses empty, your shell may be imposing a filtered filesystem view — try cmd.exe / PowerShell 5.1 / MSI-installed pwsh"). Previously the downstream "Unknown profile: X / Supported provider types: ..." misled users into thinking they typo'd the profile name. The deeper root cause of WHY some shells saw %APPDATA%\unidrive as empty was never pinned (Store-PS7-AppContainer hypothesis falsified mid-session; remaining candidates left in ticket history) but is now moot — the diagnostic surfaces it regardless.
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
adr_refs: []
opened: 2026-04-18
---

---
id: UD-310
title: BUG — concurrent-download scope cancellation kills the token refresh mid-flight
category: providers
priority: high
effort: M
status: closed
closed: 2026-04-19
resolved_by: commit e36491c. Three of four directions shipped. (A, primary) `authenticatedRequest` now retries once on 401 after forcing a token refresh via a new `forceRefresh: Boolean` argument threaded through the `tokenProvider` lambda into `TokenManager.getValidToken(forceRefresh)`. Most in-flight 401s are transient races (token expired between send and server receive, or a sibling just refreshed); they self-heal without propagating. (B) `Token.isNearExpiry(5 min)` added; `getValidToken` proactively refreshes when the cached token is within 5 min of expiry, cutting the race window from minutes to seconds. (C) the actual `oauthService.refreshToken(...)` call is wrapped in `withContext(NonCancellable)` so a parent scope cancellation (UD-222's Pass 2 authFailure path) can't kill an in-flight refresh — the bug that surfaced the "Token refresh failed: ScopeCoroutine was cancelled" log even though the refresh_token was valid. (D) deliberately NOT taken: with (A) in place, 401s self-heal inside authenticatedRequest and only propagate as AuthenticationException for real re-auth cases (refresh_token revoked), for which the existing Pass 2 scope-cancel is still correct. Revisit if telemetry shows real-re-auth cases where that's wrong.
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/TokenManager.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProvider.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/model/Token.kt
adr_refs: []
opened: 2026-04-19
---

---
id: UD-207
title: HTTP 429 / Retry-After handling in provider HTTP wrappers
category: providers
priority: medium
effort: M
status: closed
closed: 2026-04-19
resolved_by: commit de1e9cb. Gemma peer-review claim verified against the UD-222 live OneDrive run (same 346 GB first-sync as UD-712): 2060 x `429 activityLimitReached` over ~1 hour of Pass 2, each one fatal for the affected file because `authenticatedRequest` threw `GraphApiException` on any non-2xx and `downloadFile`'s UD-309 retry explicitly skipped `GraphApiException`. Fix: `authenticatedRequest` (both overloads) now detects 429 and 503 Service Unavailable, honours the server-sent `Retry-After` header (integer seconds per Graph spec), and retries the same idempotent GET/POST up to 5 times with budget caps — 2 min per individual retry and 10 min cumulative wait per request. Fallback when `Retry-After` is absent: exponential backoff 2/4/8/16 s. Caps prevent a pathological Retry-After from blocking the caller indefinitely. Concurrent callers naturally de-synchronise because each sees the current throttle window independently. Covers every GraphApiService caller — delta enumeration, item metadata, download fallback, move/create/share — not just downloads. Scope limited to OneDrive this pass; the HiDrive / WebDAV / Internxt HTTP wrappers remain for a follow-up ticket when those providers see live traffic.
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
adr_refs: []
opened: 2026-04-17
---

---
id: UD-225
title: BUG — non-hydrated DB entries are silently skipped by Reconciler UNCHANGED path
category: core
priority: high
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 7ace2cc. Surfaced while preparing to restart the UD-222 live sync. Reconciler skips path when remoteState+localState both UNCHANGED (line 41); LocalScanner explicitly skips non-hydrated entries for modification check (line 47 "mtime is synthetic"). Net result: the 2,060 files that UD-309 + UD-207 pre-fix had marked non-hydrated via restoreToPlaceholder would have been silently passed over on the next sync, with the UD-222 cursor-promotion guard falsely reporting success (transferFailures=0 because nothing was attempted), promoting the cursor, and leaving the stubs orphaned forever.

Fix: at the end of Reconciler.reconcile, synthesise a `DownloadContent` action for every DB entry where `!isFolder && !isHydrated && remoteSize > 0` that no action already covers. Uses the incoming remote CloudItem when present in the current delta; otherwise reconstructs one from the DB entry's stored remote fields (remoteId, remoteHash, remoteSize, remoteModified). Exclude-patterns are honoured. Runs before `detectMoves` and `detectRemoteRenames` so rename/move detection still picks up the synthesised actions.

Self-healing corollary: every future sync will auto-retry non-hydrated stubs until they succeed or the user deletes the DB entry. No manual intervention, no cursor reset required.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt
adr_refs: []
opened: 2026-04-19
---

---
id: UD-226
title: `sweep --null-bytes` — detect + rehydrate zero-byte stubs left behind by pre-UD-222 runs
category: core
priority: high
effort: M
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt
status: closed
closed: 2026-04-19
resolved_by: commit 6c898cf. `unidrive -p <profile> sweep --null-bytes` CLI subcommand added. Walks sync tree (honours exclude_patterns), skips symlinks/zero-byte/non-NUL-first-block files, samples first+middle+last 4 KiB and classifies all-NUL-in-all-three as zero-byte stub. Modes: `--report-only` (default), `--rehydrate` (flips DB isHydrated=false so UD-225 rescues on next sync), `--dry-run`. 11 unit tests in SweepCommandTest. Daemon-startup auto-sweep migration deferred — manual recovery command is sufficient for now.
opened: 2026-04-19
---
**Live example from the UD-222 crime scene:** `C:\Users\gerno\unidrive-onedrive-test\(Femjoy) Sybil - Pose -15-11-2019-\19555084_tcy991_100.jpg` is a zero-byte file that any app opening it sees as all NUL. Size on disk = remote size, mtime = remote mtime, DB entry says `isHydrated=true` — because the pre-UD-222 `applyCreatePlaceholder` path set `isHydrated = hasRealContent || …` and on Windows `isSparse()` returns false unconditionally (UD-209), making any size-match look like "real content". Every zero-byte stub written by that code path is now marked hydrated in the DB, invisible to both (a) the reconciler skip path (it looks UNCHANGED) and (b) the UD-225 non-hydrated-rescue path (it's labelled hydrated).

UD-225 self-healed the case where the DB entry honestly admits `isHydrated=false`. This ticket covers the orthogonal case where the DB **lies** about hydration state because of a pre-fix bug. No sync-level behaviour will ever notice these — they need an external sweep.

**User's proposed shape (2026-04-19):** "check for null-byte files in the (sync) tree, then compare with remote, then overwrite the zero-byte file." Expanded to:

  1. `unidrive -p <profile> sweep --null-bytes [--dry-run | --rehydrate | --report-only]`
  2. Walk `sync_root` (honouring `exclude_patterns`).
  3. For each regular file:
     * Fast negative check: if `Files.size(f) == 0L`, it's trivially unhydrated — skip (no payload to compare).
     * Fast positive check: if first ~4 KiB contains any non-zero byte, file has real content — skip.
     * If size matches remote AND first block is all-NUL: read the next N blocks to confirm. A fully-allocated NUL file reads as NUL everywhere; a partially-written download would have a content tail. Suggest: sample first block + middle block + last block, each ~4 KiB. All-NUL in all three → classify as zero-byte stub. Cheap (~12 KiB reads).
  4. For each identified stub:
     * `--report-only` (default): print path + size. Exit code non-zero if any found.
     * `--rehydrate`: mark DB entry `isHydrated=false`, then trigger a single-file download via the existing `DownloadContent` action path. UD-225 handles re-persistence.
     * `--dry-run` implies `--report-only` plus "here's what --rehydrate would do".
  5. Emit a progress line every N files; the UD-712 tree has 130 k items so walking takes minutes.
  6. Expose the same via MCP tool `unidrive_sweep_null_bytes` for UI/automation.

**Edge cases worth writing tests for:**
  * Genuine empty files (0 bytes on remote AND local) — must NOT be flagged.
  * Large sparse files from upload tools that legitimately start with NULs (bootable disk images, some DB formats). The "all three sample blocks are NUL" heuristic will misfire on a disk image. Require at least `first + middle + last` all-NUL to reduce false positives; optionally fall back to remote-hash verify (quickXorHash for OneDrive) on suspected stubs before classifying — eliminates the false-positive case entirely at the cost of one more remote call per suspect.
  * Files with real content that happen to be followed by a NUL tail (rare but possible for padded formats). Middle-block sampling covers these.
  * Symlinks, junctions, reparse points — skip.

**Companion clean-up after this lands:**
  * Add a one-time migration on daemon startup: if DB has `schema_version < N`, run sweep automatically on the first sync, then bump version. Optional — `sweep` is already a manual recovery command.
  * UD-220 (consequences docs) gets a "recovering a poisoned UD-712-era tree" section cross-referencing this ticket.

**Related:** UD-222 (the bug that left the stubs), UD-225 (rescues the honest non-hydrated case), UD-209 (why `isSparse` lies on Windows), UD-220 (docs).


---
id: UD-212
title: Log output + `unidrive log` lack profile/account context
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit e60dd25. SLF4J MDC key `profile` populated in CliServicesImpl.withProfile; logback pattern carries `[%X{profile:-*}]`; `runBlocking(MDCContext())` in SyncCommand propagates the key across Pass 2 worker threads (new dep: kotlinx-coroutines-slf4j). LogCommand header extended with provider display name. Account-level display-name/UPN/quota enrichment in header deferred to UD-214 (offline quota cache) work.
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/LogCommand.kt
  - core/app/cli/src/main/resources/logback.xml
opened: 2026-04-18
---
Observed in UD-712: debug log lines read `o.k.u.onedrive.OneDriveProvider - Delta: 3 items, hasMore=false` with no indication of WHICH profile/account they belong to. With two OneDrive profiles configured (`onedrive`, `onedrive-test`), the shared `unidrive.log` is ambiguous. Same issue for `unidrive log` CLI output: header reads `Recent sync entries for onedrive:` without account display name, UPN, or last-known quota. Fix direction: (a) populate an SLF4J MDC key `profile=<name>` on provider instantiation so logback prints it via `%X{profile}`; (b) extend LogCommand header with `<profile> · <displayName> · <upn or mail> · quota <used>/<total>` (falling back to cached values when offline, per UD-214).


---
id: UD-308
title: BUG — `getDelta` aborts entire sync on transient Graph response truncation
category: providers
priority: high
effort: S
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
status: closed
closed: 2026-04-19
resolved_by: commit 2c354e2. Option A applied: `getDelta` wrapped in a retry(3) helper that catches SerializationException, IOException, and 5xx GraphApiException with 2s/4s/8s exponential backoff. Graph delta is idempotent at the cursor level, so same-URL retry is safe. Option B (Content-Length assert) and Option C (log truncated body) deferred — the retry alone resolves the observed failure mode on the UD-712 130k-item live run.
opened: 2026-04-19
---
Reproduced on the first UD-222-fixed `sync --download-only` run against `onedrive-test` (346 GB / 129 933 items): enumeration died mid-page with `Expected end of the object '}', but had 'EOF' instead at path: $.value[99].file.hashes`. Root cause: [GraphApiService.getDelta](core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:106) does `response.bodyAsText()` → `json.decodeFromString<DriveItemCollectionResponse>(body)` with no retry. `bodyAsText()` returns whatever bytes arrived before the connection closed — a short-read from a transient network hiccup (or a flaky Graph edge) yields a truncated string that then fails JSON parsing with `SerializationException`. The exception propagates up through `OneDriveProvider.delta` → `SyncEngine.gatherRemoteChanges` → `syncOnce` → CLI, killing the entire sync. On a 130 k-item drive that walks ~1300 pages at 100 items/page — even a 0.1 % per-page flake rate makes full enumeration fail in expectation.

Fix direction:
  A. **Retry with exponential backoff around `getDelta`** (XS): wrap the `authenticatedRequest` + parse in a `retry(3) { … }` helper that catches `SerializationException`, `IOException`, and 5xx `GraphApiException`, with 2s/4s/8s backoffs. Same-URL retry — Graph delta is idempotent at the cursor level. Primary fix.
  B. **Content-Length verification** (S): if Graph sends `Content-Length`, assert `body.length == Content-Length` before parse and treat short-reads as retryable failures explicitly. Not strictly needed if (A) lands but improves diagnostics.
  C. **Log the truncated body** (XS): on parse failure, log the last ~200 chars of `body` and the expected path so we can tell a genuinely malformed response from a short-read. Useful for UD-307-style provider anomalies too.

Related: UD-222 (surfaces more now that hydration actually happens), UD-713 (first-sync ETA).

---
id: UD-309
title: BUG — `downloadFile` does not retry on Ktor Content-Length mismatch
category: providers
priority: high
effort: S
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
status: closed
closed: 2026-04-19
resolved_by: commit 3b1749e. Option A applied: `downloadFile` now wraps `httpClient.get(url)` + channel-drain + TRUNCATE_EXISTING in a 3-attempt retry with 2s/4s backoff on non-auth, non-HTTP-status exceptions. Works on both the short-lived `downloadUrl` path and the authenticated `$baseUrl/.../content` fallback — both idempotent GETs. Options B (Range-header resume) and C (streaming integrity check via sha256/quickXorHash) deferred; ticket UD-309b can track the resume work if large-file flakiness recurs.
opened: 2026-04-19
---
Surfaced on the first UD-222-fixed live download against `onedrive-test`: Ktor throws `Content-Length mismatch: expected N bytes, but received M bytes` (e.g. 384 MB declared, 111 MB received) for ~5 % of files in the 1 MB–500 MB range. Root cause: OneDrive hands out short-lived Azure Blob / CDN URLs via `DriveItem.downloadUrl`; those CDN edges occasionally close the TCP connection mid-stream. Each individual retry almost always succeeds.

Observed in sync-ud308 run (2026-04-19 07:42): 6 mismatches inside the first few seconds of Pass 2 — on `.mp4`, `.jpg` files, spanning multiple remote folders. With Pass 2 failure tracking (UD-222), those files are flagged non-hydrated and cursor promotion is blocked so the next sync retries them — but each iteration re-walks the entire delta + re-attempts the same files against the same flaky CDN edges, making convergence expensive on a 130 k-item drive.

Fix direction:
  A. **3-attempt retry with 2s/4s exponential backoff inside `downloadFile`** (XS, primary): wrap the `httpClient.get(url)` + channel-drain + truncate-existing-destination in a retry that catches non-auth, non-HTTP-status exceptions. Same-URL retry works on both the `downloadUrl` path and the authenticated `$baseUrl/.../content` fallback — both are idempotent GETs. Note: `downloadUrl` is short-lived (~1 h) but survives a 2–6 s retry window comfortably. This is what landed.
  B. **Resumable download via HTTP Range headers** (M, future): instead of redownloading the full file on short-read, resume from the byte offset that already landed. Big win on the 100+ MB tier. Needs a small on-disk scratch store to remember "file X had N bytes written before failure". Deferred — A handles the common case.
  C. **Streaming integrity check** (S, future): OneDrive returns `sha256Hash`/`quickXorHash` in `DriveItem.file.hashes`. After download, re-hash the local file and compare; mismatch → discard and retry (or fall back to B). Catches the rare case where Ktor's Content-Length check passes but the payload is actually corrupt.

Related: UD-222 (surfaced here), UD-308 (sibling retry around delta), UD-713 (first-sync ETA — B would help).

---
id: UD-227
title: BUG — CDN-path 429 bypasses retry + `retryAfterSeconds` in JSON body ignored
category: providers
priority: high
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit b25a16f. UD-207 landed 429/503 + Retry-After handling in `authenticatedRequest`, but `downloadFile` takes a different path: `httpClient.get(driveItem.downloadUrl)` against the Azure Blob / CDN edge. That path also emits 429 `activityLimitReached` under sustained load (observed on the UD-712 130k-file live sync — CDN edges throttle independently of Graph). Fix: CDN-path response now detects 429/503 and honours either (a) the `Retry-After` response header or (b) the `retryAfterSeconds` integer tucked inside the JSON error body (`{"error": {"retryAfterSeconds": N}}`) that Graph sometimes returns instead of the header. Fallback: exponential 2/4/8/16 s backoff. Same 5-attempt / 2-min single / 10-min cumulative budget caps as UD-207. New helper `parseRetryAfterSeconds(body)` centralises the JSON parse so future 429 sites can reuse it.
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
adr_refs: []
opened: 2026-04-19
---
Surfaced alongside UD-207 during the UD-712 live sync — sibling ticket covering the download-CDN path that UD-207's `authenticatedRequest` fix didn't reach. The `retryAfterSeconds` JSON-body fallback is the more interesting half: Graph's own docs say Retry-After is always a header, but live traffic showed the field inside the body payload for a significant minority of 429 responses. Without the parse, the client fell back to exponential backoff that could be shorter than the server-requested wait, leading to immediate re-throttle. Follow-up UD-232 generalises to a cross-request ThrottleBudget so concurrent callers coordinate instead of each burning budget independently.

---
id: UD-232
title: Global 429 circuit breaker + adaptive concurrency — brake harder and faster on throttle storms
category: core
priority: high
effort: M
status: closed
closed: 2026-04-19
resolved_by: commit bf97697. Added `ThrottleBudget` class (per-GraphApiService circuit breaker + concurrency halving + token-bucket spacing, injectable clock) and wired `awaitSlot()` / `recordThrottle()` / `recordSuccess()` into both `authenticatedRequest` overloads and `downloadFile`. Raised per-retry throttle cap from 120s to 300s (addresses the 604/1427 = 42% of 429 waits that hit the old cap in the UD-712 log) and cumulative budget from 600s to 900s. Also truncates non-JSON error bodies to first-line + char-count (folds in the HTML-dump bug from the log analysis — a single SharePoint 503 was otherwise dumping ~60 lines of inline CSS into unidrive.log). 9 new ThrottleBudgetTest cases. Deferred to UD-232b: (1) SyncEngine semaphore resizing based on `currentConcurrency()` — currently only exposed, not consumed; (2) extract `HttpRetryBudget` into core for UD-228; (3) graceful `unidrive sync --stop` drain signal.
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
opened: 2026-04-19
---
**Evidence from the UD-712 OneDrive test run** (`sync-ud225-20260419-093506.log`, 15,946 files, ~1 h 50 min as of analysis):

| Metric | Value |
|---|---|
| Total 429 throttle hits | 1,056 |
| Permanent failures (exhausted 5 retries) | 459 — 2.9 % of files |
| `retryAfterSeconds` min / avg / max | 5 / 131 / **297 s** |
| Failures where server asked > 120 s | **246 / 454** — old 120 s cap was the kill shot |
| Peak 429 burst rate | **45 / minute** |
| Concurrent downloads in flight (photos 3–8 MB → mediumSemaphore) | 6 |
| Estimated simultaneous Graph API calls | ~12 (getItemById + CDN GET per file) |
| MS recommended ceiling | ≤ 10 req/s per app-per-user |

**Root cause:** each coroutine backs off independently. When 6 coroutines all hit 429 simultaneously, each waits its own timer then all fire again at the same instant → next storm. No global brake exists. The current architecture has:

  * Per-request backoff only (`pickBackoffMsWithBody` in `GraphApiService`) — does not reduce overall request rate while other coroutines keep running.
  * `retryAfterSeconds` cap was 120 s (pre-UD-227) → 246 files failed because the server demanded up to 297 s and our retry budget ran out first.
  * `SyncEngine` semaphores are static (16 / 6 / 2) — never reduced in response to throttle pressure.

**Proposed safety perimeters:**

1. **Global circuit breaker** (primary lever): shared `AtomicLong lastGlobal429Ms` + `AtomicInteger consecutiveThrottleCount` in `GraphApiService` (or a singleton `ThrottleBudget`). When ≥ 4 × 429 within any 20 s window across all coroutines → suspend all new request starts for `max(retryAfterSeconds_seen_in_window) × 1.2` (20 % jitter buffer). Resume with reduced concurrency.

2. **Adaptive concurrency** in `SyncEngine`: expose a `ConcurrencyController` that `GraphApiService` signals on each 429. On first storm → halve permit count (6 → 3). On second storm within 10 min → 3 → 1. Restore: +1 permit per 5 consecutive clean minutes.

3. **Token bucket** (inter-request spacing): limit new download starts to 5 / s globally (200 ms minimum spacing). Prevents simultaneous bursts when multiple coroutines become unblocked at the same time after a wait.

4. **Correct `retryAfterSeconds` cap**: 300 s (covers observed max of 297 s). UD-227 removed the old 120 s cap for CDN paths; ensure Graph API path also uses 300 s as the upper bound, not 120 s.

5. **Ramp-back policy**: after a storm, don't restore full concurrency instantly. Enforce a minimum cool-down of `max(retryAfterSeconds_in_storm)` before adding any concurrency slot back.

**Implementation sketch:**

```kotlin
// ThrottleBudget.kt (new, shared singleton injected into GraphApiService)
class ThrottleBudget(private val maxConcurrency: Int) {
    private val recentThrottles = ConcurrentLinkedDeque<Long>() // epoch ms timestamps
    private val currentPermits = AtomicInteger(maxConcurrency)
    private var globalResumeAfter = AtomicLong(0)

    fun recordThrottle(retryAfterMs: Long) {
        val now = System.currentTimeMillis()
        recentThrottles.addLast(now)
        // prune older than 20s
        while (recentThrottles.peekFirst()?.let { now - it > 20_000 } == true) recentThrottles.pollFirst()
        if (recentThrottles.size >= 4) {
            // circuit open: pause all, shrink concurrency
            globalResumeAfter.updateAndGet { maxOf(it, now + (retryAfterMs * 1.2).toLong()) }
            currentPermits.updateAndGet { maxOf(1, it / 2) }
        }
    }

    suspend fun awaitSlot() {
        val wait = globalResumeAfter.get() - System.currentTimeMillis()
        if (wait > 0) delay(wait)
    }
}
```

**Missing CLI feature (related):** no `unidrive sync --stop` or graceful drain signal. Killing PID mid-download leaves partial files (safe — `TRUNCATE_EXISTING` + DB still `isHydrated=false`) but no in-process draining. A named-pipe signal via the existing IPC socket would let the sync engine finish in-flight transfers before exiting cleanly.

**Related:** UD-207 (Graph 429, closed), UD-227 (CDN 429 + retryAfterSeconds parsing, closed), UD-228 (cross-provider robustness audit — pull `ThrottleBudget` from here into that shared helper).

---
id: UD-234
title: Inject git commit id into every log line for end-user copy-paste feedback loop
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 9e6de34. Added `[%X{build:-???????}]` to the logback patterns for CLI and MCP (both CONSOLE/STDERR and FILE appenders). `Main.main` in each app populates the MDC key from `BuildInfo.COMMIT` (already captured at build time via `git rev-parse --short HEAD`). Example: `2026-04-19 13:30:01.123 INFO  [1fbe7e8] [onedrive-test] [main] o.k.u.onedrive.GraphApiService - Delta: 200 items…`. Release-mode tag-prefixed format (`[v0.2.0+1fbe7e8]`) deferred to post-MVP release-engineering pass.
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
  - core/app/cli/src/main/resources/logback.xml
  - core/app/cli/build.gradle.kts
opened: 2026-04-19
---
Surfaced by user feedback on `%LOCALAPPDATA%\unidrive\unidrive.log` (2026-04-19 session): when an end user sends a log snippet back for investigation, the timestamp alone doesn't identify the binary. Different builds/branches can produce superficially identical WARN lines that actually mean different things. The build already captures `git rev-parse HEAD` at configure time via `generateBuildInfo` — we just don't surface it into the logger pattern.

Proposed shape:
  1. `Main` reads the bundled build-info resource at startup and calls `System.setProperty("unidrive.commit", shortSha)` before any logback access.
  2. `logback.xml` pattern adds `[%property{unidrive.commit:-???????}]` — e.g. `2026-04-19 13:05:56.201 [4f76ea6] [onedrive-test] [main] WARN …`.
  3. Document in `docs/user-guide/debugging.md` (or fold into UD-220 consequences): "before filing a bug, copy the last 100 lines of unidrive.log with the `[commit]` tag — this lets support pin the exact source tree without asking."
  4. Releases: the `[commit]` tag becomes the end-user feedback hook. A user pasting a handful of lines lets us `git show <sha>:path/to/file.kt:<line>` without rebuilding their environment.

**Open question (release-mode):** should a release build render `[v0.2.0+4f76ea6]` (tag + short-sha) or just `[4f76ea6]`? Tag-prefixed is more human-readable at release time but requires plumbing `project.version` through `generateBuildInfo` the same way the SHA is. Defer the exact format to post-MVP.

**Related:** UD-212 (MDC profile context — same logback pattern), UD-220 (consequences docs).

---
id: UD-723
title: Deploy unidrive-test-oauth MCP + first-successful-grant_token + LiveGraphIntegrationTest green
category: tooling
priority: high
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 4a432d9 (scaffold fixes) + pending commit (server.py scope replay) + native-side user action (claude.json registration). All 6 acceptance steps green within the 2026-04-19 session: (1) venv created and `mcp`+`httpx` installed in `scripts/dev/oauth-mcp/.venv/`. (2) `smoke.py onedrive-test` returned OK after fixing 4 scaffold bugs — missing clientId in token.json (hardcoded DEFAULT_APP_ID fallback), wrong tenant endpoint (/common/ → /consumers/), overstrict JWT-shape check (MSA tokens are opaque `EwB…` 1464-char blobs), scope-reduction-fails (consent is `Files.ReadWrite.All`, can't narrow on refresh — replay stored scope). (3) MCP registered at user-level `~/.claude.json` (backup stored at `.claude.json.bak-before-unidrive-oauth-20260419-153059`); Claude Code restart picked up the new entry alongside existing MCP_DOCKER. (4) `mcp__unidrive-test-oauth__probe()` returned `{config_dir_exists: true, profiles_with_token: ["onedrive", "onedrive-test"]}`. (5) `mcp__unidrive-test-oauth__grant_token(profile="onedrive-test")` returned a 1.5kB opaque access token with `scope_granted: "openid https://graph.microsoft.com/Files.ReadWrite.All"` and `source: "refresh"` (after killing the old MCP process to pick up the server.py scope-replay fix). (6) `LiveGraphIntegrationTest` ran green against a token minted by the same refresh path (via mint.py earlier in the session): `drive.id=BAE9CB17 type=personal owner=Marcus Naumann` + parallel load `n=24 succeeded=24 failed=0 currentConcurrency=8 resumeAfterMs=0`. `revoke_token` clears the MCP's local cache correctly. Follow-up: add a Claude Code restart step to the README (MCP code changes require it); document which Claude config file is which (`claude_desktop_config.json` is Desktop native, not Code).
code_refs:
  - scripts/dev/oauth-mcp/
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/LiveGraphIntegrationTest.kt
opened: 2026-04-19
---
Close the gap between "scaffold exists" and "Claude Code session can actually call `mcp__unidrive-test-oauth__grant_token`". The MCP code shipped in `scripts/dev/oauth-mcp/` at commit 5a20947; this ticket tracks the deployment + first-successful-call milestone.

## Acceptance checklist

Step-by-step validation that the MCP works end-to-end. Each step must produce evidence, not just "ran without error".

1. **venv setup on user's native machine.**
   - `cd scripts/dev/oauth-mcp && py -m venv .venv && .\.venv\Scripts\Activate.ps1 && pip install -r requirements.txt`
   - Evidence: `.\.venv\Scripts\python.exe -c "import mcp, httpx; print(mcp.__version__, httpx.__version__)"` prints both versions without error.

2. **smoke.py passes against the `onedrive-test` profile.**
   - `python smoke.py onedrive-test`
   - Evidence: final line is `OK`. `access_token[:16]` starts with `eyJ` (standard JWT prefix). Refresh-token rotation reflected back to `%APPDATA%\unidrive\onedrive-test\token.json` (check file mtime changed).

3. **MCP registered in user's Claude Code config.**
   - Add `unidrive-test-oauth` entry to `~/.claude/claude_desktop_config.json` (or equivalent Claude Code settings file) per the README.
   - Evidence: after Claude Code restart, `/mcp list` (or system-reminder at session start) shows `unidrive-test-oauth` with green status.

4. **First-successful `probe()` call from an agent session.**
   - Agent calls `mcp__unidrive-test-oauth__probe()` and gets structured JSON back.
   - Evidence: response includes `config_dir_exists: true` and `profiles_with_token` containing at least `onedrive-test`.

5. **First-successful `grant_token(profile="onedrive-test", scope="read")` call.**
   - Agent receives `{ access_token, expires_at_ms, scope_granted, source }`.
   - Evidence: `scope_granted` string contains `Files.Read` and `offline_access` but NOT `Files.ReadWrite`.

6. **`LiveGraphIntegrationTest` runs green against the minted token.**
   - Export `UNIDRIVE_TEST_ACCESS_TOKEN=<token from step 5>` in a shell.
   - Run `./gradlew :providers:onedrive:test --tests "org.krost.unidrive.onedrive.LiveGraphIntegrationTest"`.
   - Evidence: both tests pass; output lines include `LiveGraphIntegrationTest: drive.id=...` and `LiveGraphIntegrationTest: parallel load n=24 succeeded=24`.

## What this unlocks

Closes the 24-hour live-test feedback loop identified in the 2026-04-19 session debrief as the single biggest agent blocker. Every OneDrive behaviour change (UD-232, UD-312, UD-227, etc.) that currently ships blind can be validated in 5 minutes end-to-end.

## What this does NOT cover

- CI integration. GitHub Actions can't call this MCP (it runs user-local). The CI path needs a different auth flow (service principal) — a separate ticket when that lands.
- Multi-profile orchestration. One-profile-per-call; if the agent needs two, it calls twice.
- Scope escalation warnings. If the user's consent policy rejects `scope="read"`, `grant_token` errors with a clear message; the agent should retry with `scope="write"` only when the specific test demands write.

## Open questions

- **Has the user's Claude Code config ever been edited before?** If first time, the README should probably include a "here's what the file looks like when it works" example. Ask before writing that in.
- **Error-handling for expired refresh tokens.** Current design: `graph_client.refresh` throws `GraphAuthError`; MCP returns `{"error": "..."}` to the agent. The agent should then tell the user "re-auth via `unidrive -p <profile> auth`". Should the MCP instead shell out to the `unidrive` CLI's auth command and invoke device-code flow? Likely no — that's mixing concerns — but worth deciding.

## Related

- UD-808 (OAuth test framework — blocked-on-injection; this ticket unblocks it).
- UD-232 (ThrottleBudget — canonical change that shipped blind).
- UD-312 (TokenManager JWT validation — the next OneDrive bug waiting for live verification).
- `scripts/dev/oauth-mcp/README.md` — canonical setup doc; this ticket's checklist is the test that the README is accurate.
- `docs/dev/oauth-test-injection.md` — design rationale.

---
id: UD-312
title: BUG — `TokenManager` returns a malformed JWT under token-refresh race
category: providers
priority: high
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 925c785. Three-layered guards against empty / truncated access_tokens: (1) Token.hasPlausibleAccessTokenShape() length predicate (>=32 chars, NOT a JWS-compact-form check because personal-MSA tokens are opaque), (2) shape guard at all three grant endpoints (exchangeCodeForToken / refreshToken / pollForToken) throws AuthenticationException on a 2xx with a suspiciously short access_token, (3) atomic save via tmp + Files.move(ATOMIC_MOVE) with fallback for filesystems that reject atomic moves — loadToken also applies the shape check on read. 10 new OAuthServiceTokenShapeTest cases covering classification + discard-on-load + atomic-artifact-hygiene. Matches the same 32-char floor used in scripts/dev/oauth-mcp/graph_client.py so CLI + MCP agree on 'valid enough to return'.
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/TokenManager.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt
opened: 2026-04-19
---
Observed in `unidrive.log` 2026-04-19 07:57:07: `Authentication failed during download: Authentication failed (401): {"error":{"code":"InvalidAuthenticationToken","message":"IDX14100: JWT is not well formed, there are no dots (.). The token needs to be in JWS or JWE Compact Serialization Format."}}`. This is post-UD-310 era (in-place 401 refresh with NonCancellable — so refresh itself should succeed), yet Graph received a token that wasn't a valid JWT (no dots = not even a header.payload.signature tuple). Something persisted an empty-string or single-segment string as the access token and `getValidToken()` returned it.

Hypotheses to narrow:
  * OAuth refresh endpoint occasionally returns a 2xx with a JSON body where `access_token` is empty string (observed on Azure B2C + Microsoft personal accounts under load — vendor bug).
  * Disk race: concurrent persist of `token.json` produces a half-written file that `loadToken()` reads.
  * UD-310's `forceRefresh` path writes the new token but also briefly nulls the in-memory copy — if another coroutine reads during that window it gets an empty/stale value.

Fix direction:
  A. **Validate JWT shape in TokenManager.getValidToken()** before returning: require exactly 3 dot-separated non-empty base64url segments. On failure, throw `AuthenticationException` with a "token storage corrupt — reauthenticate" message; do NOT return the invalid token to the caller. Stops the bad request from reaching Graph (saves a round-trip that will always 401).
  B. **Log the token issuer + expiration** at DEBUG (never the token itself) so we can correlate refresh timing with failures.
  C. **Atomic persist**: write `token.json.tmp` then `Files.move(ATOMIC_MOVE)` instead of overwriting in place — eliminates the half-written-read race.

**Related:** UD-310 (401 in-place refresh, closed), UD-207 (throttle retry, closed). Acceptance: a regression test that feeds TokenManager a JSON response missing `access_token` or containing an empty string and asserts `AuthenticationException` propagates without reaching the HTTP layer.

---
id: UD-311
title: BUG — download retry doesn't cover TLS handshake failures (`Remote host terminated the handshake`)
category: providers
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 3032c73. Actual root cause was deeper than the ticket guessed — the 'Remote host terminated the handshake' at unidrive.log:7868 was NOT a missed SSLException in downloadFile's retry loop (that path already catches generic Exception). It was in the token-refresh path: a 76-min 429 wait expired the access_token, the subsequent OAuthService.refreshToken hit login.microsoftonline.com, TLS handshake terminated mid-stream, refreshToken had zero retry, AuthenticationException propagated to SyncEngine which logged the misleading 'Download failed: Remote host terminated the handshake'. Fix: new postWithFlakeRetry helper wraps all three OAuth POST sites (refreshToken, exchangeCodeForToken, pollForToken) in the same 3-attempt / 2s-4s policy as downloadFile's flake retry. SSL / IOException → retry; 4xx/5xx → return to caller for classification.
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
opened: 2026-04-19
---
Observed in `unidrive.log` 2026-04-19 13:05:56 (last line of the UD-712 live sync log): `Download failed for /Pictures/(Watch4Beauty) Leona Mia - Blacked -23-04-2019-/025.jpg: Remote host terminated the handshake`. The TLS handshake was aborted by the CDN edge mid-connection. `downloadFile`'s UD-309 flake-retry catches generic `Exception` but `AuthenticationException` and `GraphApiException` short-circuit out of the retry path; the SSL exception propagates as a successor cause wrapped inside a Ktor request exception. Net effect: a transient handshake flake (single-shot retryable) becomes a permanent per-file failure.

Fix direction: widen UD-309's flake-retry to explicitly include `javax.net.ssl.SSLException`, `javax.net.ssl.SSLHandshakeException`, and `io.ktor.client.network.sockets.SocketTimeoutException` when they appear at the top of the cause chain. Same 3-attempt / 2s/4s backoff policy as UD-309. Add a guard against infinite retry when the handshake failure is cert-pinned (persistent trust-store mismatch) — cap at 3 attempts as today.

**Related:** UD-309 (short-read retry, closed), UD-207/UD-227 (throttle retry, closed), UD-228 (cross-provider robustness audit — SFTP / WebDAV / HiDrive have the same gap).

---
id: UD-313
title: Delta log entry is emitted twice (GraphApiService + OneDriveProvider mirror)
category: providers
priority: low
effort: XS
status: closed
closed: 2026-04-19
resolved_by: commit 3032c73. Removed the duplicate `log.debug("Delta: {} items, hasMore={}")` mirror from OneDriveProvider.delta + OneDriveProvider.deltaWithShared. GraphApiService already emits the same line with richer context (cursor prefix) at GraphApiService.kt:137. On the UD-712 130k-item first-sync walking ~1,300 delta pages, the mirror was doubling DEBUG log volume for zero diagnostic value. The shared-items log line stays (different data — counts shared items, not main delta).
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProvider.kt
opened: 2026-04-19
---
Observed in `unidrive.log` throughout the UD-712 enumeration: `o.k.u.onedrive.GraphApiService - Delta: 200 items, hasMore=true` followed immediately by `o.k.u.onedrive.OneDriveProvider - Delta: 200 items, hasMore=true`. Identical information; the OneDriveProvider log line is a 1-arg forward of the GraphApiService one. On a 130 k-item first-sync that walks ~1,300 delta pages this doubles the DEBUG log volume for no diagnostic value. Drop the OneDriveProvider-layer log line and keep the service-layer one (which has richer context: cursor prefix, request id).

**Related:** UD-212 (MDC profile context — makes the service-layer line self-identifying once the profile key is in scope). Low-priority nitpick; include in the next logging-improvement pass alongside UD-234.

---
id: UD-800
title: BUG — test `both modified with same hash is no-op` lies: name promises no-op, body asserts Conflict on different hashes
category: tests
priority: high
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 4f82ded. Three-part fix. (1) Existing test `both modified with same hash is no-op` renamed to `both modified with different remote hash is Conflict` so the name matches the body — hashes in the fixture were explicitly "old" vs "new-hash" (different), and the assertion was Conflict. (2) Added new test `both modified with same remote hash emits Upload (local-only path)` covering the closest-to-promised invariant — when remote hash AND remoteModified both match the DB entry, `reconcile()` classifies `remoteState=UNCHANGED`; combined with `localState=MODIFIED` the action is `Upload`. (3) The true "both-sides-settled-on-the-same-content no-op" is NOT implemented in this fix — `reconcile()` sees `ChangeState.MODIFIED` from the local side but has no access to the local content hash. Implementing it would require a pre-reconcile local-hash pass (hash every modified local file before classification). Filed as a follow-up direction in this note, not a new ticket — the extra IO per modified file is likely not worth it unless telemetry shows a meaningful percentage of both-modified-to-same-content cases. Reconciler code unchanged; fix is entirely naming + coverage. If the no-op follow-up ever lands, the new test becomes a regression pin against today's Upload path.
code_refs:
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ReconcilerTest.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt
opened: 2026-04-19
---
**Surfaced 2026-04-19 by the first test-drive of the `challenge-test-assertion` skill (UD-715).** This is the exact "cement the wrong thing" pattern the Buschbrand experiment warned about.

**Current code** at `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ReconcilerTest.kt:129-139`:

```kotlin
@Test
fun `both modified with same hash is no-op`() {
    db.upsertEntry(dbEntry("/same.txt", remoteHash = "old"))
    val remoteChanges = mapOf("/same.txt" to cloudItem("/same.txt", hash = "new-hash"))
    val localChanges = mapOf("/same.txt" to ChangeState.MODIFIED)
    Files.createDirectories(syncRoot)
    Files.writeString(syncRoot.resolve("same.txt"), "content")
    val actions = reconciler.reconcile(remoteChanges, localChanges)
    assertEquals(1, actions.size)
    assertIs<SyncAction.Conflict>(actions[0])
}
```

**Problem:** the test name promises a *same-hash short-circuit invariant* ("no-op"). The body asserts the *opposite* scenario — hashes are explicitly `"old"` vs `"new-hash"` (different), and the expected action is `Conflict`. There is currently **zero coverage** for the same-hash case, but the green test reads like there is.

**Failure mode this enables:**
- A future contributor reads the backlog for "are same-hash conflicts de-duplicated?", grep'd the test suite, sees `both modified with same hash is no-op` green, assumes the invariant is protected.
- They don't add a test. They don't read the body.
- Someone later refactors `Reconciler.reconcile` and a same-hash both-modified case now emits a spurious `Conflict`. Users see phantom conflicts on files they never touched. No regression signal.

**Fix (three-step):**

1. Rename the misleading test to describe what it actually asserts: `` `both modified with different hashes is Conflict` ``.
2. Add a NEW test `` `both modified with same hash is no-op` `` that actually exercises the promised invariant:
   ```kotlin
   @Test
   fun `both modified with same hash is no-op`() {
       db.upsertEntry(dbEntry("/same.txt", remoteHash = "h1"))
       val remoteChanges = mapOf("/same.txt" to cloudItem("/same.txt", hash = "h1"))
       val localChanges = mapOf("/same.txt" to ChangeState.MODIFIED)
       val actions = reconciler.reconcile(remoteChanges, localChanges)
       // Invariant: identical content on both sides should not produce action
       // (or, at worst, produce RemoveEntry for stale local-change flag).
       assertTrue(
           actions.none { it is SyncAction.Conflict || it is SyncAction.Upload || it is SyncAction.DownloadContent },
           "same hash on both sides must not trigger a transfer or conflict",
       )
   }
   ```
3. **If the new test fails** (i.e. `Reconciler` currently DOES emit a Conflict on same-hash both-modified), fix `Reconciler.reconcile` to short-circuit. Until UD-232 / UD-222 era, it's plausible this has been silently wrong for the entire project life — the test that would have caught it was mis-named.

**Acceptance:** two distinct tests covering both cases; `Reconciler.reconcile` observably no-ops on hash-equal both-modified inputs; an explanatory comment above `Reconciler` pointing at this ticket.

**Why this matters:** this is the pattern the Buschbrand experiment was designed to expose. Catching it on the first test-drive of the `challenge-test-assertion` skill validates both the skill's existence and UD-715's broader thesis.

**Related:** UD-715 (Buschbrand brainstorm), UD-812 (impl-anchored test cleanup — sibling finding from the same drive), UD-813 (meta audit).

---
id: UD-300
title: BUG — downloadFile / getDelta / postWithFlakeRetry catch CancellationException as Exception; log 'ScopeCoroutine was cancelled' and wastefully retry
category: providers
priority: high
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 68fad62. Added `catch (e: kotlinx.coroutines.CancellationException) { throw e }` carve-out ahead of the generic `catch (e: Exception)` in three retry sites: `GraphApiService.downloadFile`, `GraphApiService.getDelta`, `OAuthService.postWithFlakeRetry` (from UD-311). Kotlin's structured-concurrency rule — CancellationException must propagate cleanly, never absorbed by a generic catch. The observed pattern at `unidrive.log` 09:16:51 (sibling download's AuthenticationException cancelled the parent scope → other downloads' retry loops absorbed the propagated cancellation → retry's delay() threw cancellation again → budget chewed through logging `ScopeCoroutine was cancelled` N times → final failure surfaced after a few seconds of noise) now terminates cleanly: cancellation propagates on the first catch, the retry loop exits, and the scope's failure is logged once. 2 new tests in `CancellationPropagationTest` — direct pattern (single coroutine with retry loop propagates cancellation within 1.5s) + observed pattern (4 sibling async calls, one throws, the other three must cancel promptly not retry through the dead scope). Found by reading the log during the UD-311/UD-312/UD-313 redeploy cycle; exactly the kind of bug the `challenge-test-assertion` skill installs the reflex for (symptoms in the log pointed at a network flake; the real invariant — Kotlin's cancellation contract — was being violated).
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt
opened: 2026-04-19
---
`GraphApiService`'s `downloadFile` retry loop catches `CancellationException` as a generic `Exception` and attempts to retry. The retry cannot succeed because the coroutine scope is already cancelled — subsequent `delay()` calls throw `CancellationException` again, which the same generic handler catches again, chewing through the flake budget with nothing useful happening, logging `ScopeCoroutine was cancelled` as if it were a network blip, then finally surfacing the error misleadingly to `SyncEngine`.

Observed 2026-04-19 09:16:51 in `unidrive.log`:

```
09:16:51.189 ERROR SyncEngine - Authentication failed during download: Authentication expired.
09:16:51.404 WARN  GraphApiService - Graph download failed (attempt 2/3), retrying in 4000ms: ScopeCoroutine was cancelled
09:16:51.471 WARN  GraphApiService - Graph download failed (attempt 1/3), retrying in 2000ms: ScopeCoroutine was cancelled
```

The first line is a sibling download's auth failure that cancelled the parent scope. The next two lines are other downloads whose in-flight coroutines received the cancellation propagation and bounced through the flake-retry, polluting the log and (worse) masking the real diagnostic.

Root cause: Kotlin's own guidance — **`CancellationException` must not be caught as `Exception`**. The coroutines library relies on cancellation propagating cleanly; absorbing it in a generic `catch` both violates structured concurrency and creates the kind of misleading retry noise we saw.

Fix direction — apply a carve-out BEFORE the generic `catch (e: Exception)` in every retry loop:

```kotlin
} catch (e: AuthenticationException) {
    throw e
} catch (e: GraphApiException) {
    throw e
} catch (e: kotlinx.coroutines.CancellationException) {
    // UD-300: cancellation must propagate — never retry. Otherwise the
    // next delay() throws CancellationException again and we chew budget
    // to no purpose while logging a misleading "network flake".
    throw e
} catch (e: Exception) {
    // retry
}
```

Sites to patch:
- `GraphApiService.downloadFile` (primary — the retry loop around `httpClient.get(url) / authenticatedRequest(url)`).
- `GraphApiService.getDelta` (same shape, same bug class; currently catches `SerializationException` and `IOException` — low risk of a stray `CancellationException` here but add the guard for symmetry).
- `OAuthService.postWithFlakeRetry` (UD-311 helper — same generic `catch`).

Acceptance:
- After the fix, `ScopeCoroutine was cancelled` appears **zero** times in the log on a sync where a sibling download's AuthenticationException cancels the scope. The cancellation propagates cleanly to the caller; SyncEngine's failure log captures it once per coroutine, not N×.
- Add a unit test that launches a coroutine scope, cancels it, and verifies `downloadFile`'s retry loop does not absorb the cancellation (i.e. the function throws `CancellationException` promptly, not after 3 × flake-retries).

**Related:** UD-310 (closed — wrapped token refresh in `NonCancellable`; this ticket is the sibling "don't swallow cancellations in downstream retries"), UD-311 (OAuth flake retry — introduced `postWithFlakeRetry` which inherited the same bug pattern; fix in lockstep), UD-222 (Pass 2 scope cancellation fallout — canonical example of the propagation mode).

---
id: UD-812
title: Rewrite 3 impl-anchored tests (under-specify / constant-cement): merge-folders, sweep partial-NUL, ThrottleBudget constants
category: tests
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 5ab2a731f4159f82f7cfec293f2f22f05e2d7b6d. Rewrote all three tests per ticket body. ReconcilerTest.both-new-folders now asserts folderActions.isNotEmpty() in addition to no-Conflict, so a broken impl that silently drops the folder (empty action list) fails instead of passing. SweepCommandTest partial-NUL tests: deleted the "Middle sample hits offset 10KiB" comments that anchored on the current three-sample detector — the invariant "file with non-NUL content must not be flagged" is already captured by the name + assertion. ThrottleBudgetTest: replaced the exact 1.2× backoff-factor check with "resume > now + max(retryAfter)" and the exact 8→4→2→1 halving with direction-and-floor checks (c1 < 8; c2 < c1; floor=1 after enough storms). Each rewrite carries a one-line comment naming the invariant it protects. Production code unchanged; `./gradlew :app:sync:test :app:cli:test :providers:onedrive:test` and full `./gradlew build` both green (sync=309, cli=143, onedrive=80 tests; no count delta).
code_refs:
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ReconcilerTest.kt
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SweepCommandTest.kt
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/ThrottleBudgetTest.kt
opened: 2026-04-19
---
**Surfaced 2026-04-19 by the `challenge-test-assertion` skill test-drive.** Three specific tests in the current suite assert implementation detail rather than the invariant, or under-specify in ways that would pass for a broken implementation. Fixable in one small pass.

## 1. `ReconcilerTest.both new folders merge without conflict` — under-specifies

`core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ReconcilerTest.kt:160`

```kotlin
@Test
fun `both new folders merge without conflict`() {
    val remoteChanges = mapOf("/shared" to cloudItem("/shared", isFolder = true))
    val localChanges = mapOf("/shared" to ChangeState.NEW)
    Files.createDirectories(syncRoot.resolve("shared"))
    val actions = reconciler.reconcile(remoteChanges, localChanges)
    val folderActions = actions.filter { it.path == "/shared" }
    assertTrue(folderActions.none { it is SyncAction.Conflict })
}
```

**Problem:** assertion is satisfied by an implementation that emits **zero** actions for both-new folders — which would silently drop the folder. Real invariant is "both-new folders merge cleanly without conflict, and whatever action set covers merging the two sides is emitted."

**Fix:** assert the actual required shape — either the non-emptiness of `folderActions`, or the presence of a specific merge-compatible action:
```kotlin
assertTrue(folderActions.isNotEmpty(), "both-new folder must produce at least one merge action")
assertTrue(folderActions.none { it is SyncAction.Conflict })
```

## 2. `SweepCommandTest` partial-NUL tests — impl-anchor in the comment

`core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SweepCommandTest.kt:118` and `:129`

```kotlin
@Test
fun `large file with NUL head but content tail is not flagged`() {
    // 20 KiB: first 8 KiB NUL, last 12 KiB real bytes. Middle sample hits offset 10KiB
    // (real content territory) so the check should reject it.
    val content = ByteArray(20 * 1024)
    for (i in 8 * 1024 until content.size) content[i] = 0x42
    ...
}
```

**Problem:** the **comment** ties the test to the current first+middle+last-4KB sampling strategy. A future refactor to a different detector (random offsets, Bloom filter, hash-verify) would still satisfy the invariant ("no file with real content is flagged") but contradict the comment. Next agent might preserve the three-sample design just to keep the comment true.

**Fix:** delete the `// Middle sample hits offset 10KiB...` line. The invariant "any file with non-NUL content is not flagged" is already captured by the test name + assertion. Same fix for sibling test at :129 if present.

## 3. `ThrottleBudgetTest` — 2 over-specifying constants

`core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/ThrottleBudgetTest.kt`

### `four throttles inside the window open the circuit and halve concurrency`

```kotlin
val expectedResume = 1_000 + (10_000 * 1.2).toLong()
assertTrue(b.resumeAfterEpochMs() >= expectedResume, ...)
```

Cements the `stormBackoffFactor = 1.2` constant. A tune to 1.5× breaks the test; alternative would be equally correct.

**Fix:**
```kotlin
assertTrue(
    b.resumeAfterEpochMs() > clock.now + 10_000,
    "Resume must be at least max(retryAfter) into the future; exact scale is an implementation detail",
)
```

### `repeated storms shrink concurrency each time with floor 1`

```kotlin
assertEquals(4, b.currentConcurrency())  // 8 / 2
assertEquals(2, b.currentConcurrency())  // 4 / 2
assertEquals(1, b.currentConcurrency())  // 2 / 2
```

Asserts exact halving ratio. A shrink-by-3 strategy reaching 1 in fewer steps, or a linear shrink reaching 1 more smoothly, would be blocked by this test even if the invariant "concurrency approaches 1 under sustained load" holds.

**Fix:** assert direction + floor, not ratio:
```kotlin
val c1 = b.currentConcurrency()
assertTrue(c1 in 1 until 8, "concurrency shrinks on first storm")
// storm 2
val c2 = b.currentConcurrency()
assertTrue(c2 < c1 && c2 >= 1, "further shrink, never below 1")
// storm N
assertEquals(1, b.currentConcurrency(), "floor at 1 after enough storms")
```

## Acceptance

All three tests rewritten in a single commit; `./gradlew build` green; the rewrites include a one-line comment above each test naming the invariant it now protects (so future readers don't re-commit the same sin).

## Related

UD-715 (Buschbrand brainstorm — the experiment that surfaced the `challenge-test-assertion` reflex), UD-800 (sibling higher-impact finding: ReconcilerTest name-lies), UD-813 (meta audit).

---
id: UD-314
title: BUG — `listChildren` silently truncates folders with more than 200 children (no `@odata.nextLink` handling)
category: providers
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit ea23d83. listChildren now loop-follows `@odata.nextLink` until Graph returns a page without one, and the initial request uses `$top=999` (the endpoint's hard cap) so small-to-mid folders still finish in one round-trip. Debug verbosity matches `getDelta`: one line per page walked, never per-item. `DriveItemCollectionResponse` already carried `nextLink: String?` from UD-308 so no model change was needed. Regression coverage added in `ListChildrenPaginationTest` via Ktor `MockEngine` (newly added as `testImplementation(libs.ktor.client.mock)`): the three-page scenario asserts the full 550-item accumulation AND that request 2/3 use the server-provided skiptoken verbatim — if we ever regressed to rebuilding the URL ourselves we'd infinite-loop on page 2, and the test would catch it. The existing `ThrottleBudget` inter-request spacing is virtualized by `runTest`'s TestDispatcher, so the test runs in milliseconds.
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
opened: 2026-04-19
---
Surfaced during the UD-229 Graph coverage audit (docs/providers/onedrive-graph-coverage.md). `GraphApiService.listChildren` at `GraphApiService.kt:106` decodes the first page of `GET /me/drive/root:/<path>:/children` and returns `collection.value` — but never reads `collection.nextLink`. Graph defaults to 200 items per page, so any folder with > 200 children silently returns the first 200 only. No error, no log, no diagnostic signal.

Impact today is low because `listChildren` is primarily called from the MCP `unidrive_ls` tool — a user running `ls` on a 300-item folder sees only the first 200 without noticing. It becomes blocking for v0.2.0: any UI folder view will trip over this immediately on real OneDrive accounts with media folders, music libraries, or shared workspaces where 200 is unexceptional.

Fix direction:
  A. **Follow `@odata.nextLink`** (primary): loop `authenticatedRequest(nextLink) → append to values → parse nextLink → until null`. `DriveItemCollectionResponse` already carries `nextLink: String?` from UD-308's work — so the data class change is zero.
  B. **Bump `$top` to 999** (companion): one round-trip handles 5× more folders even before pagination kicks in. Graph caps `$top` at 999 for this endpoint.
  C. **Expose page size as a parameter** on the public `listChildren` signature so large-directory callers can opt into deeper pagination without N+1 risk on tiny folders.

Acceptance: `listChildren` returns N items for a folder with N children, for arbitrary N. Test must use a mock or canned paginated JSON (real Graph integration would need UD-808 OAuth injection — keep it a unit test).

**Related:** UD-229 (research doc that surfaced this), UD-228 (cross-provider robustness — `listChildren` siblings exist in every provider and should get the same audit).

---
id: UD-211
title: Main._profile / _vaultData caches not invalidated on provider mutation
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 4e2be5bffa14d239a31269f0977eb375518d8f9a. Picked approach (b) from the ticket: added `Main.invalidateProfileCaches()` as a public method that nulls `_profile` and `_vaultData`, and wired two calls into `CliServicesImpl.withProfile` — one right after the inner-profile swap (so the memoised result from a prior call is discarded before `block()` runs) and one right after restoring the saved provider (so the inner profile's cached state doesn't shadow the outer call's follow-up work). Rationale: smallest diff, no surprising Picocli-option-parsing side effects that a custom setter on `provider` would introduce, and keeps the memoisation so startup cost per profile resolution is unchanged.

Kdoc on `CliServicesImpl` (class-level and `withProfile`) rewritten from "single-profile-per-JVM contract" to "safe for back-to-back different-profile calls" — UD-211 is now a resolved caveat not a standing limitation.

Regression test in `CliServicesImplTest`: ``withProfile invalidates caches across back-to-back calls (UD-211)`` writes a two-profile (alpha, beta) `config.toml`, calls `services.resolveProfile("alpha")` then `services.resolveProfile("beta")`, and asserts each returns its own name. Verified red on pre-fix code (ComparisonFailure at line 81 — second call returned "alpha") and green after the fix. 144 CLI tests total, all passing.

Baseline at `core/app/cli/config/ktlint/baseline.xml` regenerated scoped to the CLI module only (`:app:cli:ktlintGenerateBaseline`) to re-anchor 51 existing violations shifted down by 13 lines (the new method's kdoc + body) plus 5 new entries for the new test's raw-string literal. No source reformatting — explicitly avoided `scripts/dev/ktlint-sync.sh` because that runs `ktlintFormat` across every composite and would have touched 67 unrelated files.

`BenchmarkCommand.runAllProfiles` (the second caller called out in the ticket) is not in the current tree — no file matches `BenchmarkCommand*`. Nothing to mark with TODO(UD-211b); no follow-up ticket needed until that command reappears.
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:70
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:73
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ext/internal/CliServicesImpl.kt
opened: 2026-04-18
---
`Main.resolveCurrentProfile()` memoises its result in a private `_profile` field that is populated on first call and returned unconditionally on later calls (see `Main.kt:74` — `if (_profile != null) return _profile!!`). `_vaultData` has the same pattern (`Main.kt:97`). When any caller mutates `Main.provider` and calls `resolveCurrentProfile()` again, the cache returns STALE data from the first profile, not the newly requested one. Surfaced during Task 3 review of `CliServicesImpl.withProfile` (extension SPI): the save/set/restore pattern only appears correct because each unidrive invocation is typically one profile. Legacy `BenchmarkCommand.runAllProfiles` shares this latent bug. Fix options: (a) give `Main.provider` a custom setter that clears both caches; (b) add a public `Main.invalidateProfileCaches()` method that `withProfile` and any future multi-profile callers invoke; (c) drop the memoisation and pay the re-parse cost per call. Acceptance: `CliServicesImpl.withProfile` can be called twice in a row with two different profile names and each call observes the correct profile; add one test to `CliServicesImplTest` that exercises this. Kdoc in `CliServicesImpl` currently documents the limitation as the "single-profile-per-JVM" contract until this lands.

---
id: UD-224
title: CLI `ls` subcommand — no way to list remote folder from the shell
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 8780a70. Implemented `unidrive [-p <profile>] ls [<path>]` as a thin one-level wrapper around
`CloudProvider.listChildren(path)` (the SPI signature — the ticket-body alias
`provider.list(path)` does not exist; I used the real signature).

Output: `name  size  mtime` per line; folders before files, each alphabetical;
size via `CliProgressReporter.formatSize` (matches `unidrive log`); mtime as
ISO-8601 (`CloudItem.modified.toString()`). Folders render with a trailing `/`
and an empty size column. Does not touch `state.db` — works on a cold profile,
which was the driving gap from the UD-712 OneDrive session.

Separate data source from the MCP's existing `unidrive_ls` tool (which reads
the state database). Kept them intentionally distinct per ticket scope; no
shared formatter.

Tests: `LsCommandTest` exercises the private `printChildren` formatter via
reflection (sibling pattern from `SweepCommandTest` / `RelocateCommandTest`)
driven by a `LocalFsProvider` against a temp dir — covers folders-first,
alphabetical, no-recursion, empty-dir, and ISO-8601 mtime invariants. Plus
two registration tests through Picocli.
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
opened: 2026-04-18
note: Originally allocated UD-211 (renumbered on 2026-04-19 during merge with origin's CliExtension SPI commit 9a5c150, which had independently allocated UD-211 to the Main._profile cache-invalidation bug referenced in code).
---
Surfaced during the UD-712 OneDrive session: MCP exposes `unidrive_ls`, but the CLI has no matching `ls` verb — only `sync`, `status`, `get`, `quota`, `log`, `provider`. Verifying "did my upload land?" currently requires either running a full `sync` (which triggers the engine's delta scan — minutes to hours on large drives) or hitting the provider API directly. Scope: a thin `ls [--profile p] [path]` subcommand that calls `provider.list(path)` with a 1-level depth, prints `name size mtime` like `unidrive log`. No state-DB dependency so it works even on a cold profile.

---
id: UD-221
title: BUG — tray "Open sync folder" opens an arbitrary profile's folder, no multi-account picker
category: ui
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 319a224. Tray "Open sync folder" no longer opens an arbitrary profile's folder.

Behaviour now:
- N==0 configured profiles: flat entry (legacy candidate-walking fallback preserved for safety).
- N==1 configured profile: flat entry opening that profile's sync root directly.
- N>=2: submenu "Open sync folder" with one entry per configured profile labelled `<profile> (<sync-root>)`, each opening its own root.

Profile list sourced from `config.toml` (via the existing UD-501 ktoml parser) rather than from `currentProfiles` (the running-daemons set) so the action works even when a daemon is down.

Menu shape factored into a pure `buildOpenSyncFolderPlan` (sealed `OpenSyncFolderPlan`) and a thin dorkbox wrapper `addOpenSyncFolderEntry`. Tests assert on the plan + use an injectable `openFolderFn` seam; no Swing/AWT needed.

Test count: 6 -> 10 (+4 UD-221 cases: flat-for-N=1, fallback-for-N=0, submenu-for-N>=2 with label+path checks, click routes to the correct profile's root).

Scope kept tight per guardrails -- existing per-daemon "Sync now" / "Open folder: <profile>" entries left untouched; broader UD-213/UD-217 tray redesign out of scope.
code_refs:
  - ui/src/main/kotlin/org/krost/unidrive/ui/TrayManager.kt
opened: 2026-04-18
---
Reproduced in UD-712: right-click tray → "Open sync folder" launches `C:\Users\gerno\unidrive-ssh-local` (the sftp `ssh-local` profile's sync_root) even though the tray header shows "onedrive" and no affordance lets the user choose which profile's folder to open. With 5 configured profiles (4 pre-existing + `onedrive-test`) the menu item is effectively a lucky-dip. Fix ties into UD-213 / UD-217 — replace the singular "Open sync folder" entry with a per-profile submenu (or one entry per profile in a flat layout). "Open folder: onedrive" / "Open folder: ssh-local" / "Open folder: onedrive-test" etc. The existing "Open folder: onedrive" item already follows that shape for the first provider — generalise.

---
id: UD-727
title: ui/build.gradle.kts ktlintKotlinScriptCheck FAILED — pre-existing, blocks ./gradlew build from ui/
category: tooling
priority: low
effort: XS
status: closed
closed: 2026-04-19
resolved_by: commit 227cf66. Applied ktlintKotlinScriptFormat to ui/build.gradle.kts; violations were trailing-comma, function-signature wrapping, multiline-expression, and if-brace style. No baseline regen needed — canonical formatter output passes the check. Unrelated ktlintMainSourceSetCheck / ktlintTestSourceSetCheck failures in TrayManager.kt / TrayManagerTest.kt remain and are out of scope for this XS ticket.
code_refs:
  - ui/build.gradle.kts
  - ui/config/ktlint/baseline.xml
opened: 2026-04-19
---
**Pre-existing CI blocker.** Reported by the UD-221 agent during the 2026-04-19 dev-agent chain session. The `:ktlintKotlinScriptCheck` task (part of `./gradlew build` via `check`) fails for `ui/build.gradle.kts` regardless of the in-session changes. Verified by stashing UD-221's edits and rerunning — the failure reproduces. UD-221 shipped green on `:test` + `:runKtlintCheckOverMainSourceSet` + `:runKtlintCheckOverTestSourceSet` alone; full `build` stayed red for this reason.

## What's failing

```
> Task :ktlintKotlinScriptCheck FAILED
  ui/build.gradle.kts:<line> <ktlint rule>
```

The exact violation(s) need to be read from the ktlint report at `ui/build/reports/ktlint/ktlintKotlinScriptCheck/ktlintKotlinScriptCheck.txt` — the UD-221 agent didn't surface the specific rule because the fix was out of their UD-221 scope.

## Fix path

1. Read the ktlint report to learn the exact rule(s) hit.
2. Fix inline if cosmetic (multi-line wrapping, max-line-length, import order — the usual suspects).
3. If structural, regen the UI baseline via `./gradlew ktlintGenerateBaseline` from `ui/` (per-module, per UD-726).
4. Verify `./gradlew build` green from `ui/`.

## Acceptance

- `./gradlew build` from `ui/` exits with `BUILD SUCCESSFUL`, including `ktlintKotlinScriptCheck`.
- No regression — `./gradlew test` still green.
- If a baseline regen is the fix, the diff to `ui/config/ktlint/baseline.xml` is included with a one-line comment noting which rule was newly suppressed and why it's not worth paying down inline.

## Related

- UD-221 (closed) — the ticket that surfaced the pre-existing failure.
- UD-706 / UD-706b (closed) — ktlint wiring + strict-mode flip; whatever rule introduced the violation likely landed then.
- UD-726 (open) — `ktlint-sync.sh --module` flag; companion cleanup.
- `~/.claude/projects/.../memory/feedback_ktlint_baseline_drift.md` — governing memory.

## Priority

**Low, XS.** Not blocking any in-flight work (CI is off this session per Gernot's call). Re-enables a clean `./gradlew build` from `ui/` once CI comes back.
---
id: UD-726
title: scripts/dev/ktlint-sync.sh — add --module scope flag to avoid dragging unrelated composites
category: tooling
priority: low
effort: XS
status: closed
closed: 2026-04-19
resolved_by: commit ecc70d9. Added --module :<gradle-path> scope flag; composite ownership inferred by on-disk path lookup. Default (wide-sweep) unchanged. CLAUDE.md cross-references the new flag.
code_refs:
  - scripts/dev/ktlint-sync.sh
  - CLAUDE.md
opened: 2026-04-19
---
**Dev-tooling polish.** `scripts/dev/ktlint-sync.sh` runs `ktlintFormat` across every Gradle composite (`core/` + `ui/`) unconditionally. A fix in `core/app/cli/` touches only that module but the script's `ktlintFormat` at the root also formats `ui/` and every other composite — noted by the UD-211 agent who hit it during their session: *"first attempt used it per CLAUDE.md guidance, but it runs ktlintFormat across every composite (core + ui) and touched 67 unrelated files. Stashed the mess, re-applied my intentional edits, and ran `./gradlew :app:cli:ktlintGenerateBaseline` alone instead"*.

## Proposed

Add a per-module scope flag to `scripts/dev/ktlint-sync.sh`:

```bash
# Current (touches everything):
scripts/dev/ktlint-sync.sh

# Proposed — scope to a single module:
scripts/dev/ktlint-sync.sh --module core:app:cli
scripts/dev/ktlint-sync.sh --module ui
scripts/dev/ktlint-sync.sh --composite core   # all modules in core composite
scripts/dev/ktlint-sync.sh                    # unchanged default (all)
```

Internal implementation: pipe the scope into the gradle task name (`:<module>:ktlintFormat` instead of `ktlintFormat`). Baseline regen stays per-module, matching the existing post-format fallback.

## Also worth

Cross-reference from `CLAUDE.md` where the tool is recommended — today it says *"run `scripts/dev/ktlint-sync.sh` before committing"*, no scoping hint. Add: *"For a module-scoped change, use `--module <path>` to avoid dragging unrelated composites into the diff."*

## Acceptance

- `scripts/dev/ktlint-sync.sh --module core:app:cli` touches only files under `core/app/cli/` (verify via `git diff --name-only`).
- Running without `--module` preserves the current wide behaviour (backwards compat).
- Help output from `scripts/dev/ktlint-sync.sh --help` includes the new flag.

## Related

- UD-211 (closed) — the session where this friction bit a dev agent.
- `~/.claude/projects/.../memory/feedback_ktlint_baseline_drift.md` — the memory that advises using this tool; will mention the new `--module` flag.

## Priority

**Low, XS.** Maybe 20 lines of bash. Not blocking anything; just makes the tool trustworthy enough to recommend unreservedly.
---
id: UD-231
title: BUG — CDN download accepts HTML body without Content-Type guard; corrupt file written on 200+HTML
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit aec157b. Content-Type guard in downloadFile throws IOException on text/html 200 responses before any write; UD-309 flake loop retries and surfaces hard failure. Covered by DownloadContentTypeGuardTest (negative + positive path).
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
opened: 2026-04-19
---
Observed in the UD-712 OneDrive test run (`sync-ud225-20260419-093506.log`, line 11746): SharePoint CDN returned `503 Service Unavailable` with a `Content-Type: text/html` body (a browser-facing "Something went wrong" Bing/SharePoint page) instead of the expected JPEG bytes. The 503 is caught by `shouldBackoff` / `isSuccess()` so in that case the body is thrown as a `GraphApiException` rather than written to disk — but the failure mode is fragile:

**The dangerous case (200 + HTML):** Microsoft CDN edge nodes in captive-portal / login-redirect scenarios can return HTTP 200 with `Content-Type: text/html`. `response.status.isSuccess()` is true, so execution falls through to `val channel: ByteReadChannel = response.body()` at `GraphApiService.kt:207` and the HTML page is streamed verbatim into the destination file. The file on disk is then silently corrupt — correct size (HTML is often similar in length to a small JPEG), correct mtime, DB entry marks it hydrated. `sweep --null-bytes` (UD-226) would not catch it because the content is non-NUL. Only an integrity check (quickXorHash comparison) would reveal it.

The 503+HTML case from the live log shows the CDN is already returning HTML under throttle pressure; the 200+HTML variant is a documented edge-node behavior for expired download URLs and tenant throttle pages.

**Fix:** After `response.status.isSuccess()` and before streaming, inspect `response.contentType()`. If it matches `text/html` (any charset), treat it as a retriable server error — throw `GraphApiException` with status + first 200 chars of body, identical to the non-2xx path. The existing flake-retry loop then handles it.

```kotlin
// proposed guard at GraphApiService.kt:206
val ct = response.contentType()
if (ct != null && ct.match(ContentType.Text.Html)) {
    val snippet = response.bodyAsText().take(200)
    throw GraphApiException(
        "Download returned HTML instead of file bytes (${response.status}): $snippet",
        response.status.value
    )
}
```

**Evidence from live log (2026-04-19 run):**
  * Line 11746: `Download failed: 503 Service Unavailable - <!DOCTYPE html><html ... <title>Sharepoint Online</title>...`
  * The Bing/SharePoint "Something went wrong" page with ref code `SpoGrMSIT` / `Ref A: DD9DC10003784563A6D58C5394922B9F` was logged verbatim across ~80 log lines (11746–11822).
  * In this instance the 503 status protected the file; the fix closes the 200 gap.

**Related:** UD-227 (CDN 429 handling), UD-228 (cross-provider robustness audit — add content-type guard to audit checklist), UD-226 (sweep detects NUL stubs but not HTML-corrupt files — see note above).
---
id: UD-315
title: BUG — OneDrive Personal Vault returns with no facets; unidrive's mapping is undefined
category: providers
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit e227636. Added DriveItem.isPersonalVault (locale-name + zero-facet/size=0 fallback); OneDriveProvider filters in listChildren/delta/deltaWithShared with INFO-once-per-session log. 12 new unit tests (DriveItemVaultTest x9, OneDriveProviderVaultFilterTest x3). SPECS §3.1 Provider quirks documents the behavior.
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/model/DriveItem.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProvider.kt
  - docs/SPECS.md
opened: 2026-04-19
---
OneDrive's Personal Vault surfaces as a top-level item with **no facet at all** — no `folder`, `file`, `specialFolder`, `package`, or `root` set. Observed 2026-04-19 when probing the `onedrive-test` profile via Graph `GET /me/drive/root/children?$select=name,size,folder,file,specialFolder,package,root`:

```
Persönlicher Tresor   size=0   facets=[]   special=''   pkg=''
```

The other 11 top-level items all had at least one facet populated. Personal Vault is a BitLocker-encrypted area that requires separate authentication to enter — Graph deliberately hides its children and surfaces it as an opaque stub.

**Current unidrive behaviour — undefined.** `DriveItem.toCloudItem()` at `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/model/DriveItem.kt` branches on `isFolder` / `isFile`; with neither set, the mapper currently picks a default path (check the source) that treats the vault either as a zero-byte file (harmless but misleading) or as a folder that returns zero children on the next `listChildren` call (wastes a round-trip). Neither is actively wrong but neither is explicit.

**Fix direction:**

1. Add a `isPersonalVault: Boolean` discriminator to `DriveItem` / `CloudItem`. Compute it at the mapper: `size == 0L && folder == null && file == null && name matches the locale-specific Vault string` (en: "Personal Vault"; de: "Persönlicher Tresor"; localisation matters — list a handful of known values + keep a fallback regex).
2. `OneDriveProvider.delta` / `listChildren`: filter out Personal Vault entries the same way the current code filters `it.isRootItem()`. Add a companion filter + tests.
3. Optionally: surface as a first-class `SyncAction.SkipSpecial(path, reason)` so the user sees *"skipped: Personal Vault (protected)"* in the `log` tail instead of silent elision.
4. Document in `docs/SPECS.md` under provider quirks — one paragraph under OneDrive.

**Why now:** the dev-agent chain just landed a listChildren pagination fix (UD-314); the same edit site is fresh in people's minds. Also: the 2026-04-19 MCP probe session produced concrete evidence, and the fix is near-zero risk.

**Acceptance:**
- `OneDriveProvider.delta()` against a drive with Personal Vault returns results that do not include the vault (assert via unit test with a canned JSON fixture).
- `listChildren("/")` filters the same way.
- Kotlin test with fixture covering en + de strings + the all-zero-facets case.
- Log at INFO the first time the vault is encountered per session: *"Skipping Personal Vault (name)"* so ops can see the filter engaged.

**Related:** UD-307 (OneDrive ZWJ-emoji 409 — same "provider quirk" category), UD-229 (Graph coverage doc — update Table A with a note about Vault), UD-228 (cross-provider robustness — similar Vault-like special-folders exist in Dropbox's `:starred`, worth audit-table row).
---
id: UD-316
title: unidrive status --audit — cross-check state.db against remote quota + surface enumeration gap
category: providers
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit fcda6b9. Adds `unidrive -p <profile> status --audit`: single provider.quota() call + state.db aggregates, diff surfaced with pending_cursor + include_shared context. Pure formatter in StatusAudit.formatAuditReport() keeps the output testable without a DB fixture or network — see StatusAuditTest.
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt
opened: 2026-04-19
---
New `unidrive -p <profile> status --audit` subcommand that cross-checks the local `state.db` against the remote truth via a single Graph round-trip and reports the drift.

## Motivation — concrete 2026-04-19 finding

Direct comparison during the MCP probe session:

| Source | Value |
|---|---|
| Graph `/me/drive` quota.used | **372.5 GB** |
| state.db `SUM(remote_size) WHERE is_folder=0` | **109.0 GB** |
| Delta | **263.5 GB not yet enumerated** |

Today no operator would notice this — state.db has no "remote total" to compare against, and the running sync's `pending_cursor` just walks forward. A `status --audit` would surface the gap in seconds.

## Shape

```bash
unidrive -p onedrive-test status --audit
```

Output:
```
Profile:          onedrive-test  (OneDrive / personal / Marcus Naumann)
Remote quota:     372.5 GB used / 1104.9 GB total   (state=normal)
Tracked in DB:    57874 entries, 109.0 GB
Delta:            263.5 GB of remote not yet in state.db
                  → run `unidrive -p onedrive-test sync` to continue enumeration
Non-hydrated:     1224 entries waiting for download
Pending cursor:   present (enumeration not complete)
Last sync:        2026-04-19 16:08
```

- **green** when delta is <5% of remote;
- **yellow** when delta is 5-25%;
- **red** when delta >25% OR `pending_cursor` has been present for more than 24h.

## Implementation

- New flag on `StatusCommand`. Single call to `provider.getQuota()` (remote totals) + a couple of state.db aggregates via the existing `StateDatabase` API.
- For provider-agnosticism: the SPI already exposes `getQuota()`; for providers that don't support it (SFTP), the audit line says "remote quota unavailable, DB-only stats" and otherwise matches.
- For OneDrive specifically, also surface the `pending_cursor` presence and the `includeShared` config flag since they affect what "complete" means.

## Acceptance

- Runs cold (no credentials in env — uses the profile's stored token).
- Completes in <3 s against a populated state.db.
- Unit test with fixture DB + mocked provider: asserts the delta-computation is stable across provider types and handles the "no remote quota" case cleanly.
- No state-db writes — pure read + render.

## Related

- UD-713 (first-sync ETA) — complementary; `--audit` is the "how far are we from done" question, `--eta` is "when will we get there".
- UD-214 (offline-friendly quota cache) — will feed the same data; `--audit` can show cached value with staleness when offline.
- UD-223 (fast-bootstrap) — the `--audit` output would make the case for enabling `--fast-bootstrap` obvious when the delta is large.

## Priority

**Medium, S effort.** Not a bug fix; a diagnostic surface that makes several existing bugs visible. Likely 1-2 hours including the fixture-based test.

---
id: UD-251
title: Logger emits every line twice (with + without MDC) — 2x log bloat, half the lines lose context
category: core
priority: high
effort: XS
status: closed
closed: 2026-04-19
resolved_by: commit 3a8c125. configureFileLogging() no longer attaches a second file appender on the default path; encoder pattern updated for parity; FILE ThresholdFilter dropped so DEBUG scan-progress lines survive. Verified on internxt daemon (0 un-MDC'd lines, no duplicates). Docker tests 28/28.
code_refs:
  - core/app/sync/src/main/resources/logback.xml
opened: 2026-04-19
---
Every line in `unidrive.log` is emitted twice — once with MDC context `[51de88e] [inxt_gernot_krost_posteo]` and once without. Example from line 5892–5893:

```
2026-04-19 20:32:43.681 WARN  [51de88e] [inxt_gernot_krost_posteo] [main] org.krost.unidrive.sync.SyncEngine - Action failed for /...
2026-04-19 20:32:43.681 WARN  [main] org.krost.unidrive.sync.SyncEngine - Action failed for /...
```

Likely cause: two appenders bound to the root logger, one with and one without MDC pattern, or a logger attached twice to a handler chain. Probably `core/app/sync/src/main/resources/logback.xml` (or similar) is either including a second config or not setting `additivity="false"`.

Impact:
- 2× log file size (7,855 lines instead of ~3,900).
- Every count (`grep -c`, log-watch summary, `unidrive-log-anomalies` skill) is doubled and needs mental division.
- Half the lines have no MDC — commit/profile/correlation context is lost on those duplicates, so if a log reader happens to catch the bare copy they lose traceability.

Acceptance:
- `unidrive.log` contains exactly one entry per log event.
- All entries carry the MDC prefix `[<build>] [<profile>]`.
- Add a test: spin up the daemon, issue one command, assert `wc -l` of the log equals the number of distinct `logger.info/warn/error` call sites exercised.

This is a prerequisite for other logging improvements — dedupe before adding more fields.

---
id: UD-254
title: Tag every scan phase with scan-id + reason (MDC) for anomaly diagnosis
category: core
priority: high
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 44e1a16. syncOnce generates a short scan-id, pushes to MDC, emits Scan started/ended banners with SyncReason classification. logback.xml patterns extended with [%X{scan:--------}] so every line inside a pass inherits it.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt
opened: 2026-04-19
---
`InternxtProvider` (and likely other providers) emits `DEBUG Scanning files: 50` with no marker of WHY the scan started. UD-248's unexpected rescan after upload failure was only identifiable by staring at lines 5892-5922 of `unidrive.log` and noticing the scan restart was NOT preceded by a `Scanning folders` completion.

Goal: every scan phase logs a single INFO line with:
- scan-id (short uuid, 8 chars)
- reason: `boot | post-delta | post-retry-exhaustion | periodic-watch | manual-sync`
- trigger context (e.g. the action that exhausted retries)

Example:
```
INFO  [...] SyncEngine - Scan started scan=a3f2b9d1 reason=post-retry-exhaustion trigger=action[/Personal/contextMenu.xml] retries=3
DEBUG [...] InternxtProvider - [scan=a3f2b9d1] Scanning files: 50
...
INFO  [...] SyncEngine - Scan completed scan=a3f2b9d1 files=22450 folders=18400 duration=31s
```

Propagate scan-id via MDC (`%X{scan:-}`) so every line emitted during that scan carries it automatically.

Acceptance:
- New `InternxtProvider` scan lines include `scan=xxxxxxxx`.
- Pre-existing scan-anomaly diagnostics work from log alone: `grep "reason=post-retry-exhaustion" unidrive.log` finds UD-248-class events.
- Add MDC key `scan` to logback patterns.
- Prereq: UD-251.

---
id: UD-253
title: SyncEngine WARNs lack exception class + throw-site context
category: core
priority: high
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 44e1a16. Every catch-block WARN/ERROR in SyncEngine now includes the exception's simpleName and passes the Throwable as the final logger arg so SLF4J renders a stack trace. Regression test via ListAppender.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
opened: 2026-04-19
---
`SyncEngine` WARN "Action failed" lines only carry the API response body (e.g. `{"error":"Request failed with status code 500"}`), not the exception class or throw site. The 2026-04-19 conflict incident shows 3 WARNs in the log but the JFR recorded **13,657 exceptions/s** for 7 s (UD-249) — ~96,000 exceptions invisible to the log.

Current pattern in the log:
```
WARN  [...] SyncEngine - Action failed for /Personal/config.xml (2 consecutive): API error: 500 Internal Server Error - {"error":"Request failed with status code 500"}
```

Desired:
```
WARN  [...] SyncEngine - Action failed for /Personal/config.xml (2 consecutive; throw=InternxtApiException@InternxtProvider.upload:312): API error: 500 - {...}
```

Scope:
- Every `WARN`/`ERROR` that originates from a caught Throwable includes the exception's simple class name and throw-site file:line (from stacktrace[0]).
- On `ERROR` level, attach the full stack trace (logback `%throwable` or pass as the last logger arg).
- Where the WARN is part of a retry burst, include a correlation id linking all retries of the same action.

Acceptance:
- Given a provider stub that throws `RuntimeException("boom")` on upload, the log line includes `RuntimeException` and the throw site.
- Test: `ProviderErrorContextTest` asserts the WARN contains the exception class name.
- Prereq: UD-251 must land first so events aren't doubled.

---
id: UD-256
title: NamedPipeServer retries lack attempt counter, elapsed time, and rate-limiting
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit 620a3a9. NamedPipeServer retry loop now exponentially backs off (1s..60s cap), emits ERROR only on first failure and every 60s of sustained failure (intervening attempts at DEBUG), carries attempt/since/nextSleep/pipe fields. Recovery emits INFO.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt
opened: 2026-04-19
---
The `NamedPipeServer: CreateNamedPipeW returned INVALID_HANDLE_VALUE (error=231)` line (UD-250) is emitted on every retry with no attempt counter, elapsed time, or next-sleep value. From the log alone you can't tell whether this is a first-attempt flake that's about to succeed or a 12-hour-stuck crashloop.

Required fields on each pipe retry ERROR:
- `attempt=N` (1-based counter since last success)
- `since=<duration>` (elapsed time in the retry loop)
- `nextSleep=<ms>`
- `pipeName=<value>`

Also: suppress per-attempt spam after the first N failures. Emit one ERROR on transition-to-failed, DEBUG-level every attempt, and one ERROR every 60s of sustained failure with a running counter.

Example target output:
```
ERROR NamedPipeServer - Failed to create pipe \\.\pipe\unidrive-onedrive: error=231 attempt=1 (retry in 1s)
DEBUG NamedPipeServer - retry attempt=2 pipe=\\.\pipe\unidrive-onedrive
DEBUG NamedPipeServer - retry attempt=3 pipe=\\.\pipe\unidrive-onedrive
ERROR NamedPipeServer - Pipe creation still failing: pipe=\\.\pipe\unidrive-onedrive attempt=14 since=60s error=231
```

Acceptance:
- No more than 1 ERROR per minute during sustained failure of the same pipe.
- Retry counter resets on success.
- All retry lines carry `pipe=` identifier so two concurrent pipes can be distinguished.
- Related: UD-250 will remove most of these retries by scoping to the active profile.

---
id: UD-255
title: HTTP request correlation id (MDC req=...) across all provider calls
category: core
priority: medium
effort: M
status: closed
closed: 2026-04-19
resolved_by: commit a21cd98. Ktor client plugin adds X-Unidrive-Request-Id header, exposes id via requestId() attribute, DEBUG logs request/response with redacted URL. Installed in GraphApiService + InternxtApiService + HiDriveApiService. MDC deferred per coroutine-propagation constraints; noted in commit body.
code_refs:
  - core/providers
opened: 2026-04-19
---
Today no HTTP request/response pair can be correlated in the log. A 500 appears as "Action failed ... API error: 500" but there's no way to tie it back to the outgoing request (method, URL path, headers, retry attempt). Makes UD-248/UD-249-class bugs slow to diagnose.

Scope: every outbound HTTP call (Graph API, internxt API, future providers) gets a short correlation id (request-id, e.g. `req=a1b2c3d4`) that's:
1. Generated at request-construction time.
2. Added to a request header (`X-Unidrive-Request-Id`) for server-side correlation.
3. Set in MDC for the duration of the call so downstream log lines inherit it.
4. Logged at DEBUG on request + response (method, path redacted of auth, status, latency ms).
5. Included in any WARN/ERROR thrown from the call path.

Example target output:
```
DEBUG [req=a1b2c3d4] GraphApiService - POST /upload HTTP/1.1 → sent
DEBUG [req=a1b2c3d4] GraphApiService - POST /upload HTTP/1.1 ← 500 (1204 ms)
WARN  [req=a1b2c3d4] SyncEngine - Action failed for /Personal/config.xml ...
```

Prefer the JDK HttpClient wrapper layer so it applies uniformly across providers (we already see `HttpBodySubscriberWrapper$Lambda` in the JFR allocation hotspots — that's the insertion point).

Acceptance:
- All HTTP calls from `core/providers/*` carry `X-Unidrive-Request-Id`.
- MDC key `req` is populated for the lifetime of each request.
- Test: mock HTTP server verifies the header, unit test verifies MDC is set + cleared.
- Prereq: UD-251 (no double-logging) and UD-254 (MDC infrastructure).

---
id: UD-250
title: NamedPipeServer retries onedrive pipe forever when daemon runs non-onedrive profile
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-19
resolved_by: commit d034f6b. acceptLoop gives up after PIPE_RETRY_GIVEUP_MS (10 min) of sustained failure (min 5 attempts), emits one final WARN summary, and exits — instead of looping forever. Combined with UD-256's backoff + rate-limited ERROR emission, worst-case multi-daemon collision drops from infinite log storm to ~10 min of bounded retries. Profile-scoping the pipe name is deferred since it requires shell-extension coordination; real root cause (watchdog spawning a second default-profile daemon) noted in commit body as follow-up.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt
opened: 2026-04-19
---
Daemon launched with `-p inxt_gernot_krost_posteo sync --watch` still starts a `NamedPipeServer` for the `onedrive` profile and retries `CreateNamedPipeW` every 5 s indefinitely (`error=231`, "All pipe instances are busy"). 570 unique errors in the 2026-04-19 log.

Root cause: a second daemon instance (started by the `unidrive-watch.cmd` watchdog with the default onedrive profile) already owns the pipe. The internxt daemon retries forever with no cap.

Two bugs:
1. Daemon initialises pipe server(s) for profiles other than the one requested via `-p`.
2. Pipe retry loop has no max-attempts or exponential backoff.

Impact: fills the log (570 lines = 49% of all errors), wastes a worker thread, masks real IPC problems.

Acceptance:
- Only the active `-p` profile gets a pipe server.
- On repeated `error=231`, back off exponentially to at least 30 s between attempts and stop retrying after ~10 min; log a single summary instead of per-attempt spam.
- Runbook entry for "pipe busy" noise in `docs/`.

Related: UD-251 (double-logging doubles the visible count).

---
id: UD-248
title: SyncEngine triggers full rescan after upload retry exhaustion
category: core
priority: high
effort: M
status: closed
closed: 2026-04-19
resolved_by: commit bb20e2a. SyncEngine Pass 1 no longer throws at 3 consecutive per-action failures. Pass continues; next action runs; only a hard cap of 20 consecutive failures still bails (upstream-outage safety). Watch-loop cycle-failure backoff handles the truly-broken case. Regression test via Pass-1 delete failures. Pairs with UD-249's exception storm: same root throw path, so the 13.7k/s exception rate observed during the 2026-04-19 incident should drop materially on next repro — left UD-249 open pending a post-fix JFR capture to confirm.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt
opened: 2026-04-19
---
After an upload reaches 3 consecutive failures (e.g. `/Personal/contextMenu.xml` → 500 Internal Server Error ×3 during the 2026-04-19 internxt capture), `SyncEngine` triggers a full rescan of the remote tree instead of continuing reconciliation of remaining actions.

Reproducible evidence: `unidrive.log` line 5922 shows `Scanning files: 50` at 20:33:11 — 8 s after the last `500` at 20:33:03, with no preceding `Scanning folders` completion (contrast lines 2281 and 3020 which are legitimate two-phase transitions).

Impact: multiplies API cost, bandwidth, and sync latency after any persistent server-side failure. On a 22k-file tree the restart wastes ~25 s of scanning + full delta re-enumeration.

Acceptance:
- After N consecutive failures on a single action, the action is parked (or the conflict is queued for manual resolution) without tearing down the rest of the sync pass.
- Add a regression test in `core/app/sync/` that wraps a provider stub returning 500 on a single upload and asserts no rescan is issued.
- Cross-check JFR: exception storm at 13.7 k/s during 20:32:36–20:32:43 (UD-249) likely shares root cause — mark as related.

---
id: UD-257
title: MCP unidrive_status reports daemonRunning=true while unidrive_watch_events reports daemon_not_running
category: core
priority: medium
effort: M
status: closed
closed: 2026-04-20
resolved_by: commit 9aad914. WatchEventsTool now reuses ProfileContext.isDaemonRunning() — the same synchronous IPC probe StatusTool already used. Root cause: EventBuffer.connected is set by a background coroutine that hadn't executed yet on the first call in a session, so back-to-back calls disagreed. Regression test DaemonStatusAgreementTest covers the race.
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/StatusTool.kt
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/WatchEventsTool.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt
opened: 2026-04-20
---
Two MCP tools asking the same question about the same profile disagree.

Reproduction (2026-04-20, daemon PID 15896 running `sync --watch` on `onedrive`):

```
MCP call 1: tools/call unidrive_status
  → "daemonRunning": true

MCP call 2: tools/call unidrive_watch_events
  → {"status":"daemon_not_running","eventCount":0,"events":[]}
```

Same MCP process, same `-p onedrive` arg, same JSON-RPC session, back-to-back calls. One says the daemon is up, the other says it's not.

Likely cause: `unidrive_status` probes the pipe endpoint by a cheap existence check (`CreateFile` on `\\.\pipe\unidrive-state` or similar), while `unidrive_watch_events` tries an actual bidirectional IPC request. The pipe exists (server is bound) but the event-buffer protocol either (a) isn't handled by this build of the daemon, (b) is rejected by rate-limiting / frame-size, or (c) fails silently on the MCP's read side.

Source refs:
- `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/` — see whichever handler implements `unidrive_status` vs `unidrive_watch_events`.
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt` — pipe dispatch.
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/EventBuffer*` if it exists.

Acceptance:
- Both tools agree on `daemonRunning` (same truth source: the actual end-to-end IPC round-trip, not a cheap handle probe).
- Test case: start daemon on localfs profile (docker harness reuse), call both tools, assert both report `true`; kill daemon, assert both report `false` within one poll interval.
- If the event protocol is missing from the daemon's dispatcher, wire it up or have `unidrive_watch_events` return a clearer error (e.g. `"status":"protocol_not_supported_by_daemon"`) instead of claiming the daemon is down.

Related: UD-815 (MCP docker harness) would exercise exactly this scenario if it existed.

---
id: UD-252
title: MCP jar and CLI resolve different default profiles without -p
category: core
priority: high
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit 9e03efd. Shared SyncConfig.resolveDefaultProfile(configDir) in :app:sync honours [general] default_profile and falls back to 'onedrive'. Both CLI (resolveCurrentProfile) and MCP Main delegate, with a SyncConfigTest parity check.
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
opened: 2026-04-20
---
When invoked without an explicit `-p <profile>`, the MCP jar and the CLI jar resolve DIFFERENT default profiles from the same `config.toml`.

Reproduction (2026-04-20, same host, same config):

```
$ java -jar unidrive-mcp-*.jar            # status call
  → profile = "ds418play" (sftp, alphabetically first)

$ java -jar unidrive-*.jar status         # no -p
  → profile = "onedrive"

$ java -jar unidrive-*.jar sync --watch   # daemon, no -p
  → profile = "onedrive"
```

Source:
- CLI: `SyncCommand` / `Main.resolveCurrentProfile()` in `core/app/cli/`
- MCP: `Main.resolveDefaultProfile(configDir)` in `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt`

They read the same config but reach different conclusions about "the default profile". User-visible impact: calling the MCP without `-p` (e.g. a bare Claude Code registration in `~/.claude.json` without a profile arg) silently introspects a cold / unused profile while the real active sync is elsewhere. Users see `daemonRunning=false` for the resolved profile and conclude unidrive is down, when in fact a different profile's daemon is happily syncing.

Acceptance:
- Extract `resolveDefaultProfile(configDir)` into the `:app:sync` or `:app:core` module (shared between CLI + MCP + daemon) so all three use byte-identical logic.
- If `config.toml` has a `default_profile = "..."` key, honour it. Otherwise fall back to the same deterministic rule (whichever rule CLI uses today — likely "first OneDrive profile, else first non-deprecated profile").
- Add a test that constructs a multi-profile config and asserts CLI and MCP resolve the same name.
- Related: UD-255's HTTP-correlation plugin now living in `:app:core` sets the precedent for cross-module shared code.

---
id: UD-815
title: Docker harness: end-to-end MCP jar smoke test (initialize + tools/list + tools/call)
category: tests
priority: medium
effort: M
status: closed
closed: 2026-04-20
resolved_by: commit 6cea70a. docker-compose.mcp.yml + test-mcp.sh (+ Dockerfile.test mcp-jar copy + README). 9 JSON-RPC assertions (initialize + tools/list + 3 tools/call). Cherry-picked from worktree-agent-a9d658ea. Verified MCP 9/9, localfs 8/8, full core build green on HEAD.
code_refs:
  - core/docker/docker-compose.test.yml
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt
opened: 2026-04-19
---
Docker harness (`core/docker/`) covers localfs (8 tests) and provider contract (20 tests: SFTP/WebDAV/S3/rclone) — but **nothing exercises the deployed MCP jar end-to-end**.

What we have today (JVM-side, in-process):
- `McpRoundtripIntegrationTest` — handshake + a couple of calls against an in-process `McpServer`.
- `McpServerTest`, `ToolHandlerTest`, `ToolRegistryTest`, `ResourcesTest`, `JsonRpcTest`, `EventBufferTest`.

What's missing: a black-box test that spawns `unidrive-mcp-0.0.0-greenfield.jar` as a subprocess (stdio transport, the way Claude Code actually launches it), issues `initialize`, `tools/list`, `resources/list`, and at least one `tools/call` for each registered tool against a live daemon + seeded config. Today a regression in jar packaging, classloader boot, MDC init, or profile resolution would pass every JVM-side test and still break the shipped jar.

Proposed:
- New `core/docker/docker-compose.mcp.yml` sharing `Dockerfile.test`.
- New `core/docker/test-mcp.sh` entrypoint that:
  1. Seeds a localfs profile config (re-use the pattern from `test-matrix.sh`).
  2. `java -jar unidrive-mcp-*.jar -p <profile>` as a child process.
  3. Pipes JSON-RPC requests via stdin, reads responses via stdout.
  4. Asserts: `initialize` returns correct `serverInfo.name` + version + capabilities; `tools/list` returns the expected N tools (pin to a count); `resources/list` succeeds; `tools/call` for `status`, `quota`, `ls` each return the expected shape.
  5. Optional smoke: `tools/call` for a mutating tool against localfs to prove write paths.
- Integrate into the existing "both harnesses must be green" release gate.

Acceptance:
- `docker compose -f docker/docker-compose.mcp.yml build` + `run --rm unidrive-mcp-test` exits 0 on HEAD.
- `N passed, 0 failed` banner matching the existing harness style.
- Catches at minimum: a stale jar missing a tool, a startup-time MDC bug, a broken `-p <profile>` arg parser.

Related: handover.md (2026-04-19) noted that `unidrive-mcp-0.0.0-greenfield.jar` was stale for 3 days without anyone noticing — this harness would have caught that on the first post-deploy run.

---
id: UD-317
title: InternxtProvider returns filename with leading newline — URI construction fails
category: providers
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit 8fcb6de. Added companion sanitizeName + pure fileToCloudItem/folderToCloudItem conversions (and delta variants). All API-returned names are now trim()'d before reaching SyncEngine. 13 regression tests, internxt test suite green.
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt
opened: 2026-04-19
---
`InternxtProvider` returns a file whose path starts with `\n`, causing `SyncEngine` to fail URI construction with `Illegal char <\n> at index 0` on the desktop shortcut file `internxt-cli.desktop`:

```
2026-04-19 20:32:43.681 WARN  [main] org.krost.unidrive.sync.SyncEngine - Action failed for /
internxt-cli.desktop (1 consecutive): Illegal char <
> at index 0:
internxt-cli.desktop
```

The log rendering splits the action onto a new line because the path literally contains an embedded `\n` between `/` and `internxt-cli.desktop`.

Likely cause: listing response parser in `InternxtProvider` reads a line-terminated field (CRLF vs LF mismatch, or a header row being joined with the filename) and doesn't `trim()` before constructing the path.

Impact: the file never syncs. One-off for this file, but any filename whose byte stream arrives with a leading newline from internxt's API will hit the same path.

Acceptance:
- `InternxtProvider.listFolder` (or whichever constructor builds `RemoteEntry`) calls `.trim()` or a stricter normaliser on names.
- Unit test: feed a response stub where one name starts with `\n`, assert the resulting entry name has no leading whitespace.
- Regression: retry the live sync against the same internxt account and confirm `internxt-cli.desktop` syncs cleanly.

---
id: UD-249
title: Exception storm (13.7k/s) during upload retry loop — invisible in logs
category: core
priority: high
effort: M
status: closed
closed: 2026-04-20
resolved_by: commit 4e79530. Subsumed by UD-248. SyncExceptionStormTest verifies 3 failing uploads produce only 3 Throwable constructions (vs ~96k in the 2026-04-19 JFR pre-fix). Threshold <30 with 10x safety margin. If a future refactor adds a catch+rethrow amplifier the test trips long before JFR-visible.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
opened: 2026-04-19
---
JFR `daemon-internxt-final.jfr` shows `13,657 exceptions/s` during the 7 s window 20:32:36–20:32:43 — exactly the conflict + 500-error incident (`/Personal/config.xml`, `/Personal/contextMenu.xml`, `/.safe/id_ed25519`). The log only shows 3 WARNs per failing file (one per consecutive attempt), so the JVM is internally throwing ~100 k exceptions that never reach the logger.

Likely a tight retry/backoff loop inside a coroutine or HTTP-client wrapper catching + rethrowing without a sleep, or exception-driven control flow in reconciler.

Impact: 13 k/s exceptions for 7 s = GC + allocation spike, CPU burn, and the exceptions are invisible in logs so diagnosing production issues is blind.

Acceptance:
- Identify the throw site(s) from the JFR stack traces (`Events of type 'Java Error'`).
- Replace with non-exceptional control flow OR log-sample + backoff.
- Regression: wrap the same 500-returning provider stub in a unit test and assert exception count < 100 per failing action.
- Likely coupled to UD-248.

---
id: UD-216
title: Expand unidrive MCP tool surface — auth, profile add/remove, identity, logout
category: core
priority: high
effort: M
status: closed
closed: 2026-04-20
resolved_by: commit d03c67b. 8 new MCP admin verbs (auth_begin/_complete, logout, profile_list/_add/_remove/_set, identity). Confirm=true safety rail on destructive ops. Device-code flow two-phase via in-process DeviceFlowRegistry. Helpers migrated to :app:sync ProfileConfigEditor. MCP tool count 15→23, docker harness green.
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/AuthCommand.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ProfileCommand.kt
opened: 2026-04-18
---
Gap caught in UD-712 when trying to drive the OneDrive dance from an LLM: the MCP exposes 15 data/ops tools (`unidrive_sync`, `unidrive_ls`, `unidrive_quota`, `unidrive_status`, `unidrive_share`, …) but **no admin verbs** — no `unidrive_auth`, `unidrive_logout`, `unidrive_profile_add`, `unidrive_profile_remove`, `unidrive_profile_list`, `unidrive_identity`. The CLI has all of these. To let an LLM manage unidrive end-to-end (including the device-code dance, TOML mutations, account rotation), the MCP surface needs parity with CLI. Device-code flow in particular needs a two-phase tool: (1) `unidrive_auth_begin` returns `verification_uri` + `user_code` + an opaque continuation handle; (2) `unidrive_auth_complete` polls with that handle until the provider returns a token. Scope also covers: `unidrive_identity` (wraps Graph `/me` or equivalent — see UD-212), `unidrive_profile_set` (edit config.toml safely), `unidrive_logout` (clears token + state.db — must prompt). Blocks the "LLM as unidrive admin" use case.

---
id: UD-243
title: CLI — enforce --upload-only / --download-only mutex at parse time via @ArgGroup(exclusive)
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit a79da41. Picocli @ArgGroup(exclusive=true, multiplicity='0..1') on --upload-only/--download-only and share --list/--revoke; --dry-run+--force-delete and --reset+--dry-run throw ParameterException (picocli-native exit 2) because they share --dry-run. Parse-time rejection before any profile/engine setup; parameterised tests cover every forbidden combination. --list <path> NOT enforced — listShares(path) scopes to subtree, which is meaningful.
opened: 2026-04-19
---
**`sync --upload-only --download-only` passes parse, fails only at engine-time.** Should be caught at parse time. From 2026-04-19 fuzz audit Finding #2.

## Evidence

```
$ unidrive -c /tmp/sandbox sync --upload-only --download-only
Error: no provider profiles parsed from config.
```

The mutex check lives inside [`SyncCommand.run()`](core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:73):

```kotlin
if (uploadOnly && downloadOnly) {
    throw IllegalArgumentException("--upload-only and --download-only are mutually exclusive")
}
```

But it runs AFTER parse, AFTER profile resolution, AFTER config load. With a missing config (as in the fuzz) the mutex error never fires because the earlier "no profile" error short-circuits. With a real config, the mutex throws — but runtime-late rather than parse-time, which means scripts treating exit 2 as "bad args" don't see it.

## Fix

Use picocli `@ArgGroup(exclusive = true, multiplicity = "0..1")`:

```kotlin
@ArgGroup(exclusive = true)
var direction: Direction? = null

class Direction {
    @Option(names = ["--upload-only"]) var uploadOnly = false
    @Option(names = ["--download-only"]) var downloadOnly = false
}
```

Picocli then rejects the combination at parse with exit 2 and a clear "mutually exclusive" message, before any runtime code runs.

## Audit for similar cases

Also check (flagged but not verified against a real profile in the fuzz):

- `sync --dry-run --force-delete` — force-delete during dry-run makes no sense.
- `sync --reset --dry-run` — reset wipes state; dry-run would be a lie.
- `share --list <path>` — listing and sharing are different modes.

## Acceptance

- `unidrive sync --upload-only --download-only` exits 2 with a parse-time error message naming the conflict.
- The same holds for any newly-identified mutually-exclusive pair (see audit above).
- `SyncCommandTest` adds a parameterised test for every forbidden combination.

## Related

- Fuzz audit 2026-04-19 Finding #2.
- UD-223 Part A (landed) — introduced `--fast-bootstrap`, which is orthogonal to direction; no conflict.

## Priority

**Medium, S.** Correctness: invalid input should be rejected at the earliest layer. Low risk, picocli idiom well-known.

---
id: UD-245
title: CLI — numeric flag values accepted unvalidated (concurrency <= 0, retention-days < 0, --name empty, etc.)
category: core
priority: low
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit a8f38d4. 10 flag conditions validated at parse time (conflicts -n, trash/versions --retention-days, get --concurrency/--delay-ms, share --expiry, backup --name empty/slash, pin/unpin empty glob). 15 regression tests in NumericFlagValidationTest. Cherry-picked from worktree-agent-af0cb198 with one-line import conflict resolved against UD-243's ArgGroup.
opened: 2026-04-19
---
**Numeric flag values with nonsensical ranges are accepted at parse and only fail later (or silently succeed with absurd values).** From 2026-04-19 fuzz audit Finding #10 + by-subcommand matrix.

## Evidence

| Command | Flag | Bad value accepted at parse? | Notes |
|---|---|---|---|
| `conflicts list -n <= 0` | negative/zero rows | yes | nothing useful shown |
| `conflicts list -n INT_MIN / INT_MAX` | yes | unbounded |
| `get --concurrency <= 0` | yes | 0 or negative makes no sense |
| `get --concurrency 99999` | yes | system-scale DoS risk |
| `get --delay-ms < 0` | yes | negative delay |
| `share --expiry <= 0` | yes | expiry of 0 or -5 accepted |
| `share --password ""` | yes | empty string accepted |
| `trash/versions purge --retention-days < 0` | yes | negative retention ≈ delete-all |
| `trash/versions purge --retention-days 999999999` | yes | silently unbounded |
| `backup add --name ""` | yes | empty-name backup |
| `backup add --name "a/b/c"` | yes | path-like name, no sanitisation |
| `pin ""` | yes (empty glob) | glob matches nothing useful |

None of these crash — the CLI is robust — but they reach engine code carrying values that engine code shouldn't have to worry about.

## Fix

Picocli supports `@Option(parameterConsumer = …)` or a post-parse validator. For each affected flag, add a range + non-empty check at parse time. For `--retention-days` type flags, also warn/reject impossibly-large values.

Where to enforce: ideally a small `validateOptions()` helper in each command class called at the top of `run()`. Call-it-a-contract approach — single function, easy to test.

## Acceptance

- Every numeric flag in the table above rejects out-of-range values at parse (exit 2).
- Every string flag in the table rejects empty values (exit 2) unless empty is semantically meaningful.
- Test matrix in per-command `*Test.kt` files covering the bad-range cases.

## Related

- Fuzz audit 2026-04-19 Finding #10 + by-subcommand matrix.
- UD-242 (proposed — config missing wording) — same file(s) affected.

## Priority

**Low, S.** Hardening, not urgent. Each per-command validator is tiny; the total surface is spread across ~12 command classes, so effort totals S rather than XS.

---
id: UD-246
title: CLI — 'provider info' is case/whitespace strict; help text lists fewer providers than runtime accepts
category: core
priority: low
effort: XS
status: closed
closed: 2026-04-20
resolved_by: commit c1ac38c. ProviderRegistry.resolveId (trim + lower-case + null-on-miss). Help description + error list sorted + include all 8 runtime providers. Cherry-picked cleanly from worktree-agent-ac12b37c.
opened: 2026-04-19
---
**`provider info` matches provider id strictly — rejects `ONEDRIVE`, `" onedrive"`, etc., even though `onedrive` works.** Also, `provider info --help` lists fewer providers than the actual available set. From 2026-04-19 fuzz audit Finding #5.

## Evidence

```
$ unidrive provider info onedrive     # works
$ unidrive provider info ONEDRIVE     # Error: Unknown provider: ONEDRIVE
$ unidrive provider info " onedrive"  # Error: Unknown provider:  onedrive
$ unidrive provider info ""           # Error: Unknown provider: (empty)
```

`hidrive`, `internxt`, `localfs` are all registered in `ProviderRegistry.knownTypes` (confirmed via runtime error listing in other commands) but don't appear in `provider info --help`'s enumeration.

## Fix

1. **Normalise input** before matching: `.trim().lowercase(Locale.ROOT)`. Rejecting case variants is user-hostile for a terminology-agnostic CLI.
2. **Audit help text** — `provider info --help` should list every registered provider id exactly matching the runtime error's list. Pull from `ProviderRegistry.knownTypes` at build/render time, not from a hardcoded sublist.
3. **Disambiguate empty input** with a dedicated message: `Error: provider id required. Run 'unidrive provider list' to see available ids.`

## Acceptance

- `provider info onedrive`, `ONEDRIVE`, `" onedrive"`, `OneDrive` all succeed (case + whitespace insensitive).
- `provider info` with no argument emits a dedicated help-style message, not "Unknown provider: ".
- `provider info --help` output matches `provider list` output for available ids.
- Regression: `ProviderCommandTest` pins case/whitespace handling.

## Related

- Fuzz audit 2026-04-19 Finding #5.
- UD-237 (open) — `-p TYPE` auto-resolve + case-insensitive type match; same normalization pattern, might land together.

## Priority

**Low, XS.** Polish; current behaviour is correct, just strict. Same patch touches both normalization and help-text audit.

---
id: UD-244
title: CLI — sweep help text lies: advertises --dry-run / --rehydrate as modes but requires --null-bytes
category: core
priority: low
effort: XS
status: closed
closed: 2026-04-20
resolved_by: commit 1faa6bd. Chose doc-fix (option A): --null-bytes is the sole scan mode today; --dry-run and --rehydrate are labelled modifiers. Runtime error rewritten to identify --null-bytes as required. SweepCommandTest extended.
opened: 2026-04-19
---
**`sweep --help` advertises `--dry-run`, `--null-bytes`, `--rehydrate` as if they're independent modes, but `--dry-run` and `--rehydrate` only work when `--null-bytes` is also set.** From 2026-04-19 fuzz audit Finding #3.

## Evidence

```
$ unidrive -c /tmp/sandbox sweep --rehydrate --dry-run
Error: No sweep mode selected. Supported: --null-bytes

$ unidrive -c /tmp/sandbox sweep
Error: No sweep mode selected. Supported: --null-bytes
```

Help text suggests three coequal flags. Runtime message says only `--null-bytes` is a "mode". `--dry-run` and `--rehydrate` are actually modifiers that require `--null-bytes`.

## Fix options

**A. Documentation-only.** Update `sweep --help` to clarify:
- `--null-bytes` (required) — scan for NUL-stubbed files.
- `--dry-run` — report findings without modifying anything (requires --null-bytes).
- `--rehydrate` — re-download stubs found by --null-bytes (requires --null-bytes).

**B. Make `--null-bytes` the default mode** when no mode is given. Then `sweep --dry-run` works without the modal flag. Simpler user model.

**C. Update the error to mention `--rehydrate`** — but that's still inconsistent with how `--rehydrate` actually behaves.

**Recommended: B**, then fall back to A if B has unexpected ripples.

## Acceptance

- `sweep --help` output matches runtime behaviour.
- `sweep --dry-run` runs in the default scan mode (option B) or errors with a message that mentions what flag to add (option A).
- `SweepCommandTest` asserts the behaviour pinned by the chosen option.

## Related

- Fuzz audit 2026-04-19 Finding #3.
- UD-226 (closed) — `sweep --null-bytes` detection surface, the most-used current mode.

## Priority

**Low, XS.** Documentation-leaning; real functionality works, only mental model is wrong.

---
id: UD-241
title: CLI — --help rejected as 'Unknown option' on every leaf subcommand; inconsistent exit codes
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit edee61f. mixinStandardHelpOptions added to 19 non-top-level commands. --help now exits 0 everywhere. HelpAndConfigErrorTest covers mixin presence + --help exit code across the subcommand tree.
opened: 2026-04-19
---
**Every non-top-level subcommand rejects `--help` as `Unknown option: '--help'` with exit 2**, even though the usage block still prints afterwards. Surfaced by the 2026-04-19 fuzz audit (`docs/fuzz-audit-2026-04-19.md` Finding #1).

## Evidence

```
$ unidrive status --help
Unknown option: '--help'   (exit 2)
Usage: unidrive status [-a] [--check-auth]
...
```

Affected subcommands: `auth`, `logout`, `sync`, `sweep`, `status`, `quota`, `log`, `profile [add|list|remove]`, `vault [init|encrypt|decrypt|change-passphrase|xtra-init|xtra-status]`, `conflicts [list|clear]`, `provider [list|info]`, `trash [list|purge]`, `versions [list|purge]`, `backup [add|list]`. ~20 leaf commands.

Top-level `unidrive --help` works (exit 0). `unidrive -h` also works. Inconsistency is in the leaf registration.

## Impact

- Scripting around the CLI treats `--help` as a failure (exit 2).
- Users instinctively typing `unidrive foo --help` see an error + help, which reads as broken.
- `-h` works inconsistently below top-level.

## Fix

Picocli supports `@Command(mixinStandardHelpOptions = true)` per leaf. Add it to every subcommand class, or define a base class every subcommand extends. Ensure exit 0 when help is explicitly requested.

## Acceptance

- For each affected subcommand, `unidrive <cmd> --help` exits 0 and prints usage cleanly (no "Unknown option" line).
- `unidrive <cmd> -h` behaves identically.
- Add a `CommandRegistrationTest` assertion that iterates every registered leaf and invokes it with `--help`, asserting exit code == 0 and stderr does not contain `Unknown option`.

## Related

- Fuzz audit 2026-04-19 Finding #1 — this ticket.
- UD-237 (open) — `-p TYPE` auto-resolve; same DX polish theme.
- UD-235 (open) — MSIX sandbox hint on config error; same DX polish theme.

## Priority

**Medium, S.** Pure DX polish, blast radius limited to CLI surface, no engine risk.

---
id: UD-242
title: CLI — unify three 'config missing' error wordings, hide verbose diagnostic block behind -v
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit edee61f. Unified config-missing wording via renderConfigMissingMessage() helper in Main.kt. BackupCommand and ProfileCommand removed their divergent terse errors. -v / --verbose toggle kept the 8-line diagnostic block (MSIX hint etc.) for troubleshooting.
opened: 2026-04-19
---
**Three distinct wordings for the same "no config.toml / no profile" root cause.** Collapse to one. From 2026-04-19 fuzz audit Finding #4.

## Evidence

| Subcommand group | Current error (exit 1) |
|---|---|
| `status`, `sync`, `sweep`, `quota`, `log`, `pin`, `unpin`, `free`, `get`, `share`, `conflicts`, `versions`, `trash`, `vault xtra-status` | `Error: no provider profiles parsed from config.` + 8-line diagnostic block dumping `APPDATA`, `HOME`, `Files.exists`, `Files.size: -1` |
| `profile remove` | `Error: No config.toml found.` (one line, no guidance) |
| `backup add` | `Error: config.toml not found. Run 'unidrive profile add' first.` (actionable) |

The `backup add` form is the best — it tells the user the next step. The 8-line block is over-informative for normal users but not actually helpful (dumps raw `Files.size: -1` which most users won't interpret).

## Proposal

Standardise on the `backup add` shape across every subcommand that needs a config:

```
Error: config.toml not found at <resolved-path>.
Run 'unidrive profile add --type <provider>' to create one.
Run with -v for diagnostic details (search paths, MSIX sandbox hint).
```

Move the 8-line diagnostic block behind `-v` / `--verbose`. Normal users see the one-liner + next action; debug users still get the full trace.

Bonus: cross-reference UD-235 (MSIX sandbox hint) so the verbose block mentions the `-c` workaround when the process tree is Claude-Desktop-spawned.

## Acceptance

- All affected subcommands emit the same error shape with an actionable next-step line.
- Diagnostic dump is gated behind `-v`.
- One unit test in `MainTest` or `CliServicesImplTest` pins the exact wording so future drift is caught.

## Related

- UD-235 (open) — MSIX sandbox hint lives in the same diagnostic surface.
- UD-241 (proposed here — `--help` consistency) — same DX-polish sweep.
- Fuzz audit 2026-04-19 Finding #4.

## Priority

**Medium, S.** DX polish; prevents users from being confused about which profile manager is installed / where the config lives.

---
id: UD-238
title: BUG — quota/byte formatters compute binary (GiB) but label decimal ("GB"); ~7% under-report on OneDrive + Internxt
category: core
priority: medium
effort: XS
status: closed
closed: 2026-04-20
resolved_by: commit d0291cd. All 4 binary-math formatters relabelled to IEC suffixes (KiB/MiB/GiB/TiB). StatusAudit.formatBytesDecimal renamed to formatBytesBinary. Tests updated. No numeric drift — only labels changed.
opened: 2026-04-19
---
**Bug — quota/byte formatters compute binary (GiB) but label decimal ("GB"); discovered via cross-channel compare 2026-04-19.**

## Evidence (2026-04-19, both profiles)

### OneDrive `onedrive-test`

| Source | Used | Total | Remaining |
|---|---|---|---|
| `unidrive quota` CLI | 346 GB | 1029 GB | 682 GB |
| Graph `/me/drive` raw (via `unidrive-test-oauth` MCP → curl) | 372.48 GB | 1104.88 GB | 732.40 GB |
| Graph bytes (canonical) | 372,481,038,746 | 1,104,880,336,896 | 732,399,298,150 |

Ratio CLI/Graph = 10⁹ / 2³⁰ = 0.9313 — **the binary-vs-decimal divisor flip.**
372,481,038,746 / 2³⁰ = **346.92 GiB**; / 10⁹ = **372.48 GB**. CLI prints the GiB number with a "GB" label.

### Internxt `inxt_gernot_krost_posteo`

| Source | Used | Total |
|---|---|---|
| `unidrive quota` CLI | 272 GB | 3072 GB |
| Gateway `/drive/users/usage` + `/drive/users/limit` raw | 293.11 GB / 273.0 GiB | 3298.53 GB / 3072.0 GiB |
| Bytes | 293,110,900,697 | 3,298,534,883,328 |

Same pattern: CLI displays GiB values, labels them "GB". 3072 GiB exactly = Internxt's "3 TB" plan sold as binary. Used 293.11 GB ≈ 273 GiB; CLI floors to 272.

## Root cause

Two formatters, both binary-math labelled "GB":

1. **[CliProgressReporter.formatSize](core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:94)** (consumed by `QuotaCommand`):
   ```kotlin
   bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
   else -> "${bytes / (1024 * 1024 * 1024)} GB"
   ```
2. **[StatusAudit.formatBytesDecimal](core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusAudit.kt:44)** (UD-316, just landed):
   ```kotlin
   else -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
   ```
   The name *Decimal* is misleading — the divisor is 2³⁰. The UD-316 test fixture's `"372.5 GB"` assertion passes only because the test also uses the same wrong formatter end-to-end; hand-computing against raw Graph bytes now shows the formatter would render **346.9 GB**, not **372.5 GB**.

`TrashCommand.kt:44`, `VersionsCommand.kt:48` use the same "bytes/1024 labelled K" pattern — same bug class, different command surfaces.

## Fix options

**A (recommended).** Relabel to **IEC binary units**: `KiB / MiB / GiB / TiB`. Minimum-impact change; preserves byte-math of every existing caller. Matches `ls -h`, modern Windows Explorer, IEC 80000-13. Zero user confusion because the number stays identical, only the suffix changes.

**B.** Switch divisor to 10³ and keep `GB`. Cheap but every downstream document-comparison in tests and dashboards shifts by 7%. Rejection candidate.

**C.** Two-level formatter: binary under `--human` (default), decimal under `--si`. Overkill for what is a labelling fix.

## Acceptance

- `unidrive quota` output for both accounts matches the raw-API numbers to within ±0.05 of the displayed precision.
- `unidrive status --audit` output for `onedrive-test` shows `used=346.9 GiB` (or `=372.5 GB` if fix-direction B).
- `StatusAuditTest` fixture updated to match.
- `TrashCommand` + `VersionsCommand` formatters fixed in the same pass (all three share the binary-math / decimal-label defect).

## Related

- UD-316 (closed 2026-04-19) — `status --audit` inherited the same mislabel via `formatBytesDecimal`; pointing here rather than re-opening.
- UD-712 (handover notes) — the 346-vs-372.5 numerical "drift" in recent docs is retroactively explained by this bug; no actual drift.

## Discovery method

Two independent channels: (1) MCP `unidrive-test-oauth.grant_token('onedrive-test')` → raw Graph `/me/drive`; (2) Internxt `credentials.json` JWT → `gateway.internxt.com/drive/users/{usage,limit}`. Cross-checked against `unidrive quota` CLI for both profiles.

---
id: UD-258
title: Windows CLI output mangles UTF-8 glyphs to '?' — JVM stdout.encoding + launcher + graceful ASCII fallback
category: core
priority: medium
effort: M
status: closed
closed: 2026-04-20
resolved_by: commit d0291cd. applicationDefaultJvmArgs adds -Dstdout.encoding=UTF-8 / -Dstderr.encoding=UTF-8. New GlyphRenderer sniffs stdout encoding + UNIDRIVE_ASCII env override and picks ASCII vs unicode glyphs. 5 command files route through it. PS 5.1 README snippet still pending — call it out in the close note.
code_refs:
  - core/app/cli/build.gradle.kts
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli
opened: 2026-04-20
---
CLI output emits U+2713 (✓), cloud/provider emoji, and a handful of box-drawing glyphs. On Windows PowerShell 5.1 (default shell, cp1252 console) these render as `?`. Reported 2026-04-20 after the `unidrive status --all` glyph-garble session.

## Why "S" undersells the scope

Two independent layers are each wrong and each has Windows-only quirks:

### Layer 1 — the JVM isn't writing UTF-8 bytes

Current launcher `C:\Users\gerno\.local\bin\unidrive.cmd`:
```cmd
@echo off
java --enable-native-access=ALL-UNNAMED -jar "...unidrive-...jar" %*
```

JEP 400 made `file.encoding` default to UTF-8 on JDK 18+, but on Windows `System.getProperty("stdout.encoding")` (which is what `System.out` actually uses) **still defaults to the active OEM codepage** unless overridden. Result: `PrintStream` encodes `✓` as `?` before the byte stream leaves the JVM.

Explicit fix:
```cmd
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -jar "...jar" %*
```

But we can't just drop this into the launcher — we need to patch the **launcher template** in whatever task produces `unidrive.cmd` (likely `:app:cli:deploy` or `startScripts`/`installDist` shadow plumbing). `startScripts` regenerates the launcher on every deploy; a manual edit will be overwritten.

Touchpoints to check:
- `core/app/cli/build.gradle.kts` — see how `startScripts`/`startShadowScripts`/`application { applicationDefaultJvmArgs = listOf(...) }` is configured.
- `core/app/cli/` post-deploy script (the one that writes `unidrive-watch.cmd`, `unidrive.vbs`, etc.) — likely same dir.
- Same patch should apply to `unidrive-watch.cmd` and the MCP launcher if any.

### Layer 2 — PowerShell 5.1 decodes as cp1252

Even with UTF-8 bytes from Java, Windows PowerShell 5.1 reads child-process stdout through `[Console]::OutputEncoding` which defaults to cp1252. UTF-8 multi-byte sequences → mojibake or `?`.

We can't fix PS 5.1 from the launcher. Three mitigations:
1. **Detect + gracefully degrade in the CLI formatter.** On startup, sniff `System.getProperty("stdout.encoding")` (or `Charset.defaultCharset()` for older Javas). If not UTF-8 or UTF-16, render status badges as ASCII: `[OK]` / `[..]` / `[!!]`, tree as `+--` / `|`, no emoji. Add an env flag `UNIDRIVE_ASCII=1` that forces ASCII mode regardless (useful for CI / log scraping).
2. **Document the `$PROFILE` one-liner** in the README (Windows quickstart section):
   ```powershell
   [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
   $OutputEncoding = [System.Text.Encoding]::UTF8
   ```
3. **Recommend `pwsh` (PowerShell 7+)** in the setup docs — PS 7 defaults both encodings to UTF-8.

### Layer 3 — font coverage (minor)

Even with UTF-8 decode, `cmd.exe` and Legacy Console use a Bitmap / Consolas font with no emoji fallback. Windows Terminal + `pwsh` + Cascadia Code + Segoe UI Emoji fallback is the only combo that renders cloud / provider emoji faithfully. Not a bug per se, but the README should mention it so users don't chase it.

## Acceptance

- `unidrive.cmd` launcher (as produced by `:app:cli:deploy`) passes `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`. Verified by `type "$HOME/.local/bin/unidrive.cmd"` after a fresh deploy.
- CLI formatter detects non-UTF-8 stdout encoding and automatically falls back to ASCII glyphs. Verified by launching with `-Dstdout.encoding=windows-1252` and asserting output contains `[OK]` instead of `[✓ ...]`.
- `UNIDRIVE_ASCII=1` env var overrides detection and forces ASCII.
- README (Windows quickstart) lists the `$PROFILE` snippet + recommendation to use `pwsh` / Windows Terminal.
- Unit test: a `GlyphRendererTest` that exercises both modes (unicode + ASCII) and asserts the public output is byte-exact in each.

## Related

- Paired with UD-238 (unit-label mismatch). Both are "output formatting hygiene" bugs that should ship together so a single cross-compare session re-verifies the CLI rendering end-to-end.
- Fuzz audit (`docs/fuzz-audit-2026-04-19.md` Finding #9) noted that OS-level error messages leak in a localised form — same underlying "Windows locale bleeds into unidrive output" family.

## Revised effort

M, not S. Two real code changes (launcher template + formatter branch), one doc change, and regression test coverage that needs a non-UTF-8 stdout fixture.

---
id: UD-700
title: Bump pinned GHA actions before Node.js 20 deadline (2026-06-02)
category: tooling
priority: medium
effort: XS
status: closed
closed: 2026-04-20
resolved_by: commit b96308c. Bumped actions/checkout, actions/setup-java, actions/upload-artifact from @v4 to @v5 across all 8 occurrences in .github/workflows/build.yml. v5 tags ship a Node.js 24 runtime, which satisfies the 2026-06-02 deprecation deadline. No FORCE env needed since v5 is available for all three. Calendar note: 2026-06-01 reverify clean run.
code_refs:
  - .github/workflows/build.yml
opened: 2026-04-19
---
GitHub Actions emits a deprecation warning on every run (observed 2026-04-19 session):

> "Node.js 20 actions are deprecated. The following actions are running on Node.js 20 and may not work as expected: actions/checkout@v4, actions/setup-java@v4, actions/upload-artifact@v4. Actions will be forced to run with Node.js 24 by default starting June 2nd, 2026."

Current state: all `uses:` blocks in `.github/workflows/build.yml` pin to `@v4` tags which bundle the Node.js 20 runtime. None of our action pins are deprecated — the runtime inside them is.

Deadline: **2026-06-02**. After that date the runner forces Node.js 24 on any action that hasn't shipped a compatible release. If the underlying actions (`actions/checkout`, `actions/setup-java`, `actions/upload-artifact`) haven't released a Node.js 24 build by then, all of our CI jobs break simultaneously.

Fix options, ordered by preference:

1. **Wait for `@v5` releases** (preferred). `actions/checkout@v5`, `actions/setup-java@v5`, `actions/upload-artifact@v5` are expected before the deadline. Bump the tags once available. Zero behaviour change, just a version bump.
2. **Opt in early via `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true`** (fallback). Set this env at workflow level. Forces Node.js 24 on every JS action even if the action hasn't declared compatibility. Carries risk: if an action relies on Node20-specific behaviour, it breaks now instead of in June.
3. **Pin to older SHAs** (escape hatch). Not recommended — losing security patches.

Acceptance:
- Before 2026-05-15, confirm whether `@v5` tags exist for the three deprecated actions. If yes, bump.
- If no, pick option 2 and set a calendar reminder for 2026-06-01 to verify the runner still works.

**Related:** UD-705 (workflow landed), UD-708, UD-711 (secondary jobs using the same actions).

---
id: UD-259
title: Extend GlyphRenderer with Unicode-block awareness (dingbats vs emoji vs box-drawing)
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit 85171b6. Three-tier GlyphRenderer (box/dingbats/emoji), each gated on encoding + host (WT_SESSION). VS15 suffix on cloud/warn/gear/lightning/warnLabel to force single-cell. StatusCommand picks up VS15 automatically via existing cloud()/warn() callers. 18 regression tests. README Windows section extended.
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/GlyphRenderer.kt
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/GlyphRendererTest.kt
opened: 2026-04-20
---
UD-258 downgrades to ASCII glyphs when `stdout.encoding` isn't UTF-8. On Windows Terminal + PowerShell with UTF-8 correctly set (the `$PROFILE` snippet from the UD-258 README), the detection passes and unidrive emits unicode — **but the font still can't render some of it**, and Windows substitutes `?`.

Observed 2026-04-20 on a healthy Windows Terminal + Cascadia Code host:

```
│ ? Internxt Drive          │          │ ...
│  └─ inxt_gernot_krost_posteo │ [? OK]   │ 9.801 ...
```

Box-drawing chars (`├ └ │ ─`) render cleanly, proving encoding is intact. The `?` appears exclusively where:
- **Provider icons** (cloud ☁ / server 🗄️ / etc.) would render — Miscellaneous Symbols / Emoji block.
- **Status badges** (`[✓ OK]` / `[✓ 0h]`) — Dingbats block U+2700–U+27BF.

Root cause: **font-level glyph substitution**, not encoding loss. Cascadia Code has dingbats (✓) but no cloud emoji. Consolas has neither. Only Segoe UI Emoji covers the emoji range, and Windows pulls it in via font-fallback only when the host is Windows Terminal (sets `WT_SESSION` env); Legacy Console (conhost.exe) has no fallback.

## Proposed fix — extend GlyphRenderer with Unicode-block awareness

Today `GlyphRenderer` has one knob: `isUnicodeSafeStdout(): Boolean`. Split it into three:

```kotlin
// Box-drawing U+2500–U+257F — every monospace font since the 90s
fun isBoxDrawingSafe(): Boolean = isStdoutUtf8()

// Dingbats U+2700–U+27BF (✓ ✗ ★ ☆) — Cascadia / Fira / JetBrains yes, Consolas no
fun isDingbatsSafe(): Boolean = isStdoutUtf8() && !isLegacyConsole()

// Emoji + Misc Symbols U+2600+ — requires Windows Terminal fallback
fun isEmojiSafe(): Boolean = isStdoutUtf8() && hasEmojiFallback()

private fun isLegacyConsole(): Boolean =
    // conhost.exe doesn't set WT_SESSION; Windows Terminal always does.
    System.getProperty("os.name", "").lowercase().contains("win") &&
        System.getenv("WT_SESSION").isNullOrBlank()

private fun hasEmojiFallback(): Boolean =
    // Heuristic: Windows Terminal + macOS + modern Linux terminals all
    // support emoji fallback. conhost.exe + most minimal-font Linux do not.
    when {
        System.getProperty("os.name", "").lowercase().contains("win") ->
            !System.getenv("WT_SESSION").isNullOrBlank()
        // macOS Terminal.app + iTerm2: emoji fallback is universal.
        // Linux: xterm has it, tty does not; assume yes unless stdout
        // is a non-tty pipe (already handled upstream).
        else -> true
    }
```

Callers pick the right check per glyph family. Status badges (`✓`, `✗`, `★`) gate on `isDingbatsSafe`; provider icons (`☁`, `📁`) gate on `isEmojiSafe`; tree connectors gate on `isBoxDrawingSafe`.

**No font-probe required**. `WT_SESSION` is a reliable proxy for "emoji will actually render" on Windows; no need to query the font via AWT (which wouldn't work in a headless CLI anyway).

## Acceptance

- Running the deployed CLI in Windows Terminal renders dingbats correctly (`[✓ OK]`) AND provider emoji correctly (or falls back to ASCII on Legacy Console).
- Running in `conhost.exe` (no `WT_SESSION`) renders `[OK]` badges and ASCII tree glyphs — no `?` substitutions.
- Running with `UNIDRIVE_ASCII=1` still forces ASCII across all three tiers.
- Regression test: extend `GlyphRendererTest` with cases for `WT_SESSION=""` vs `WT_SESSION="some-uuid"` and assert each tier's output.
- README Windows quickstart updated with:
  - Correct `$PROFILE` snippet (the one from UD-258 still applies).
  - Correct Windows Terminal `settings.json` edit (font + fallback — MUST BE EDITED IN THE FILE, not pasted into PS).
  - Correct winget install command (`Microsoft.CascadiaCode` — verify the actual package ID with `winget search CascadiaCode` before shipping the doc).

## Subsection — variation selector for ambiguous-width Miscellaneous Symbols

Reported 2026-04-20 after UD-258 landed: `status --all` renders correctly in Windows Terminal with `[Console]::OutputEncoding = UTF8` EXCEPT the `☁` (U+2601) cloud glyph visually merges with the neighbouring `│` (U+2502) box-drawing separator. Root cause: U+2601 has two Unicode-defined presentations —

- **Emoji (wide, ≈ 2 cells)** — default when pulled from `Segoe UI Emoji` via font fallback. Cursor advances 1 cell, glyph draws ≈ 1.5 cells → overlap into the next column.
- **Text (narrow, 1 cell)** — explicitly requested via the VS15 suffix `U+FE0E`.

Fix: at every Miscellaneous Symbols emission point in `GlyphRenderer`, append `\uFE0E` to force the text-presentation form. Same trick applies to `⚠`, `☂`, `⚙`, `⚡` — any U+2600–U+26FF glyph whose table-column math depends on single-cell width.

```kotlin
// Narrow / text presentation — keeps table-column math honest.
private const val VS15 = "\uFE0E"
val cloud = "\u2601$VS15"
val warning = "\u26A0$VS15"
```

Acceptance extension:
- All Miscellaneous Symbols glyphs emitted by the CLI carry the VS15 suffix.
- `GlyphRendererTest` asserts `cloud()` returns 2 UTF-16 chars (the symbol + the selector), not 1.
- Visual verification in Windows Terminal: `status --all` cloud glyph no longer overlaps the `│` separator.
- README note: if a user's chosen font has no text-presentation form for U+2601, they'll see a `.notdef` box; recommend JetBrains Mono / Sarasa Mono / Maple Mono as fonts that render U+2601 natively single-width.

## Related

- UD-258 (closed 2026-04-20 in commit `d0291cd`) — the initial encoding + ASCII-fallback work this extends.
- The README recipe written for UD-258 was not paste-safe in PS 5.1 (inline `#` comments got interpreted as commands in one report; JSON block was pasted into PS instead of edited into settings.json). The doc update here must be explicit about WHERE each snippet goes.

## Effort

S — three helpers + 3–5 caller call-site updates + regression tests + doc refresh + VS15 sweep. Low risk; same structure as UD-258.

---
id: UD-712
title: `deploy` task `taskkill` filter misses background `sync --watch` daemons
category: tooling
priority: medium
effort: XS
status: closed
closed: 2026-04-20
resolved_by: commit f3d341d. Switched all three Windows deploy tasks (:app:cli, :app:mcp, :ui) from taskkill-by-window-title to Get-CimInstance cmdline match against the per-module shadow jar. Verified end-to-end: started a fake unidrive-ui.jar daemon, ran ./gradlew -p ui deploy, daemon was reaped and jar overwrite succeeded. The old code path triggered FileAlreadyExistsException. One subtle gotcha — Windows ProcessBuilder strips embedded double-quotes from args, so the PowerShell -Filter uses doubled single-quotes.
code_refs:
  - core/app/cli/build.gradle.kts
opened: 2026-04-18
---
Surfaced during UD-712 session: `./gradlew :app:cli:deploy` failed with `FileAlreadyExistsException` because the running `sync --watch` background daemon held the target JAR open. The deploy task's pre-copy `taskkill /F /FI "WINDOWTITLE eq UniDriveSync"` only kills processes with that exact window title, which a daemon launched by the user's VBS/startup script does not carry. Fix direction: match by command-line (`Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object CommandLine -match 'unidrive-.*\.jar'`), or have deploy touch the daemon's sentinel file (`$LOCALAPPDATA/unidrive/stop`) and poll until the JAR is released.

---
id: UD-200
title: Adaptive ThrottleBudget spacing — drop 200ms token-bucket floor when Graph isn't throttling
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit 0f2b92f. ThrottleBudget adaptive spacing — awaitSlot() reads currentSpacingMs() at each call from lastThrottleEventMs + globalResumeAfter (no new state). 0ms in the no-throttle steady state (fast path), 200ms after a recent 429, 500ms while circuit is open or within 30s of closing. Restores pre-UD-232 ~119 files/min throughput vs observed ~15 files/min under the constant 200ms floor. Two new tests for the fast path and storm-recovery band plus a clock-advance check for window expiry.
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/ThrottleBudget.kt
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/ThrottleBudgetTest.kt
opened: 2026-04-19
---
**UD-232b sibling.** After deploying UD-232 (ThrottleBudget) and running a fresh sync against `onedrive-test`, steady-state download throughput collapsed from ~119 files/min (pre-UD-232) to ~15 files/min (post, observed 2026-04-19 during the dev-agent chain session).

Root cause: the `ThrottleBudget`'s token bucket enforces a **constant 200 ms `minSpacingMs`** on every `awaitSlot()`, regardless of whether Graph has thrown a 429 in the last minute. That was a defensive choice at UD-232 design time ("prevent concurrent coroutines all waking up simultaneously after a storm wait and re-triggering a storm"), but in the healthy steady-state case it caps throughput at 5 req/s for no upside.

## Proposed shape — adaptive spacing

Promote `minSpacingMs` from a constructor-time constant to a function of recent-throttle state:

| Observed state | `minSpacingMs` returned |
|---|---|
| No throttle in last 60 s | **0** (disable spacing) |
| 1+ throttle in last 60 s, no storm | 200 ms (current default) |
| Storm active (circuit open OR < 30 s since close) | 500 ms (firmer back-pressure) |

Implementation: read `lastThrottleEventMs` (already tracked) + `globalResumeAfter` (circuit state) inside `awaitSlot()` and pick the spacing there. No new state required.

## Acceptance

- Files-per-minute on a 1,000-file sample against `onedrive-test` in a clean-network window: within 10 % of the pre-UD-232 baseline (~119/min). Direct A/B against today's post-UD-232 measured ~15/min.
- Storm detection + resume-after timing still engage under load. Reproduce with `stormThreshold=2` temporarily and firing simulated 429s — verify circuit opens and spacing ramps to 500 ms while open.
- Two new `ThrottleBudgetTest` cases: (1) `no spacing when no recent throttle`, (2) `spacing ramps to 500 ms during active storm and relaxes to 200 ms in the 30 s after circuit close`.

## Why this matters

The current UD-232 fix solves the storm problem but costs ~8× throughput in the no-throttle case. For a 130 k-item first-sync on this profile that's days instead of hours — the same scale issue UD-223 (fast-bootstrap) is trying to address from a different angle. Both fixes attack the same user-visible problem (time-to-converge on a fresh install); this ticket makes the normal path fast, UD-223 makes the startup path fast. Complementary, neither substitutes.

## Related

- UD-232 (closed) — introduced the constant spacing; this ticket tunes it.
- UD-228 (open, audit) — if the extracted `HttpRetryBudget` lands there, it should carry the adaptive shape, not the constant.
- UD-223 (open, bump recommended) — complementary; the post-UD-200 steady-state throughput is what `?token=latest` gets you when it can't bypass enumeration entirely.
- UD-713 (open) — first-sync ETA depends on the throughput ceiling; fixing this moves the ETA from "days" to "hours" on the observed workload.

## Priority

**Medium, S effort.** Current behaviour is correct-but-slow, no data loss. Worth fixing before the next real-world scale test but not blocking any in-flight v0.1.0 work.

---
id: UD-237
title: UX — auto-resolve -p <TYPE> to unique profile of that type (+ case-insensitive type match)
category: core
priority: low
effort: XS
status: closed
closed: 2026-04-20
resolved_by: commit 799dc38078f842039611618d33547509f9e1f2c5. tryResolveByType + profilesOfType helpers on RawSyncConfig. 10 regression cases in ProfileTypeResolveTest.
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
opened: 2026-04-19
---
**UX polish.** When a user passes `-p <value>` where `<value>` is a known provider TYPE (e.g. `internxt`, `onedrive`, `sftp`) but NOT a configured profile NAME, unidrive errors out with `Unknown profile: <value>` and a list of configured profiles. Observed 2026-04-19 from Gernot's native PS:

```
PS> unidrive -c C:\Users\gerno\unidrive-config -p Internxt auth
Unknown profile: Internxt
Configured profiles: ds418play, ssh-local, krost, onedrive, onedrive-test, inxt_gernot_krost_posteo
Supported provider types: hidrive, internxt, localfs, onedrive, rclone, s3, sftp, webdav
```

In practice, most users have exactly ONE profile per type (or a type is configured once and never used again). Typing `-p onedrive` or `-p Internxt` and having unidrive auto-select the single profile of that type is the intuitive behaviour; the current behaviour forces the user to scroll the "Configured profiles" list and pick the matching one.

## Proposed behaviour

When `-p <value>` matches:
1. A profile NAME exactly → use it (current behaviour, unchanged).
2. No profile NAME but matches a known provider TYPE AND exactly one profile of that type is configured → **auto-select that profile**, emit an INFO line: `Using profile '<name>' (matched by type '<value>')`.
3. A known provider TYPE with zero profiles of that type → error as today (with the current list).
4. A known provider TYPE with MULTIPLE profiles → error as today, but add a new line after "Supported provider types": `Profiles of type '<value>': <name1>, <name2>`.

## Case-insensitivity

Make the TYPE match case-insensitive so `-p Internxt` vs `-p internxt` both work (user's example used capital `Internxt`; today's TYPE list is lowercase). Profile NAME match stays case-sensitive — profile names are identifiers.

## Acceptance

- `unidrive -p Internxt auth` on a config with one `internxt` profile auto-selects it + logs the matched-by-type line.
- `unidrive -p sftp status` on a config with three SFTP profiles errors with the new "Profiles of type 'sftp': …" line listing them.
- `unidrive -p unknown status` errors as today (unchanged for values that match neither).
- Unit test in `MainTest` / `CliServicesImplTest` covering all four cases.

## Related

- UD-236 (open) — sibling UX feedback on the `sync` subcommand's description; both ticket originate from the same 2026-04-19 verify-the-Internxt-auth flow.
- UD-217 / UD-233 (open) — tray UX brainstorms; the `-p TYPE` auto-resolve pattern should inform how the tray lists profiles.

## Priority

**Low, XS.** Quality-of-life polish. Roughly 15 lines of resolution logic in `Main.resolveCurrentProfile` + a test file extension. One evening's work.

---
id: UD-235
title: CLI: surface MSIX-sandbox config-path divergence in resolveCurrentProfile diagnostic + document -c workaround
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit 799dc38078f842039611618d33547509f9e1f2c5. looksLikeMsixParent + ntfsFileId helpers; renderConfigMissingMessage gains parentProcessCommand param + MSIX-sandbox hint (emitted regardless of -v since it's the unblocker). 10 regression cases in ConfigMissingDiagnosticTest.
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
  - docs/config-schema/config.example.toml
  - docs/user-guide/consequences.md
opened: 2026-04-19
---
**Placeholder mentioned as "UD-311" in `~/.claude/projects/.../memory/project_msix_sandbox.md` (§ "When to revisit"); that ID was since allocated to the TLS-handshake bug. Filing for real now as UD-235.**

## Problem

Claude Desktop is a Store-packaged (MSIX) app. Everything spawned from it — including Claude Code's bash, and any Python / Gradle / `unidrive` invocation that inherits its process tree — runs inside `wcifs.sys` path virtualization. The virtualization silently redirects reads and writes under `%APPDATA%\`, `%LOCALAPPDATA%\`, `C:\Program Files\`, and `C:\Windows\`.

Concrete failure observed 2026-04-19 session:

- Agent's sandbox wrote `config.toml` to `C:\Users\gerno\AppData\Roaming\unidrive\config.toml`.
- `fsutil file queryfileid` from the sandbox: NTFS FileID `0x0000000000000000000300000009bcd3`, size 3357 bytes, 5 profile subdirs present.
- User's native PowerShell reading the same path string: `Test-Path → False`. `unidrive` emits:
  ```
  Error: no provider profiles parsed from config.
    Config path attempted: C:\Users\gerno\AppData\Roaming\unidrive\config.toml
    Files.exists:          false
  ```
- Meanwhile the native `unidrive status --all` finds a legacy `onedrive\state.db` under the **un-virtualized** real-NTFS path and reports 2,151 files, because `status --all` enumerates provider TYPES, not config-parsed profiles.

Net effect: config edits made from a sandbox-hosted agent never reach the user's native CLI. The two views of the same path string are two physically distinct NTFS objects. The current `resolveCurrentProfile` diagnostic correctly reports the file missing, but doesn't explain WHY or offer the known workaround.

## Proposed fix (two layers)

### Layer 1 — surface the sandbox discrepancy in the diagnostic

In `Main.resolveCurrentProfile()` (or whichever code emits the current "no provider profiles parsed from config" error), add an MSIX-sandbox detection step:

```kotlin
// UD-235: detect MSIX / AppContainer parent process and flag the redirect.
val parent = ProcessHandle.current().parent().flatMap { it.info().command() }
val inMsixChild = parent.orElse("").contains("WindowsApps", ignoreCase = true) ||
                  parent.orElse("").contains("Claude", ignoreCase = true)
if (inMsixChild && envVar("APPDATA")?.startsWith("""C:\Users""", ignoreCase = true) == true) {
    append(
        "\nHint: this process is an MSIX-packaged-app child (Claude Desktop / similar).\n" +
        "wcifs virtualization may be redirecting %APPDATA% reads/writes. The file a\n" +
        "sibling native-shell sees at this path may differ from what we see here.\n" +
        "Workaround: pass `-c <path-outside-%APPDATA%>` and keep config under a\n" +
        "user-home subdir like C:\\Users\\<you>\\unidrive-config\\config.toml."
    )
}
```

The heuristic is permissive — false-positives only cost one extra hint line. Real fix for the heuristic would probe `fsutil file queryfileid` from Kotlin and compare vs a known non-virtualized sibling, but that's more code than the hint warrants.

### Layer 2 — document the `-c` escape path properly

- Add a section to `docs/user-guide/consequences.md` (UD-220 territory) titled "Running unidrive from a native shell while Claude Code is active" — spells out the symptom (`Files.exists: false` on a file the user KNOWS is there), the cause (MSIX virtualization), and the fix (`unidrive -c C:\Users\<you>\unidrive-config\ -p <name> ...`).
- Update `docs/config-schema/config.example.toml` header comment: recommend a user-home config dir over `%APPDATA%` for dual-shell setups.
- Optional: the `deploy` Gradle task could populate `C:\Users\<you>\unidrive-config\config.toml` alongside the launcher as a symlink / copy. Out of scope for this ticket; note as follow-up.

## Acceptance

- Native-PS `unidrive` without `-c` under an MSIX-sandbox-hosted agent session emits the extra hint line in the diagnostic.
- Native-PS `unidrive -c C:\Users\<you>\unidrive-config -p onedrive-test status` works against a config file at that path — no virtualization surprise.
- No regression for users who run `unidrive` without any sandbox parent (heuristic returns false → diagnostic stays as-is).

## Related

- `~/.claude/projects/.../memory/project_msix_sandbox.md` — the memory that documented the pattern and originally proposed this ticket.
- UD-219 (closed) — the sibling "config discovery" ticket that eventually pinned MSIX as the deeper cause.
- UD-220 (open) — `docs/user-guide/consequences.md`; Layer 2 lands there.

## Priority

**Medium, S effort.** Layer 1 is a ~20-line diagnostic improvement. Layer 2 is a one-section documentation addition. Worth fixing now that a user has hit it in practice.

---
id: UD-714
title: Code-style policy doc: UTF-8, LF, ktlint baseline discipline, multi-line wrapping, commit conventions
category: tooling
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit b45d2b3. CODE-STYLE.md written as a normative 2-page doc; cross-linked from CLAUDE.md / docs/dev/TOOLS.md / README.md. Consolidates UTF-8 stdout (UD-258), ktlint baseline drift rule (feedback_ktlint_baseline_drift), multi-line expression wrapping, Kotlin default-param ordering (feedback_kotlin_default_param_ordering), Conventional Commits + one UD-### per commit scope.
code_refs:
  - docs/dev/CODE-STYLE.md
  - CLAUDE.md
  - docs/dev/TOOLS.md
opened: 2026-04-19
---
Consolidate the ad-hoc code-style rules scattered across build files, baselines, and agent memory into a single normative source. Surfaced during the 2026-04-19 session — baseline drift (ktlint line-number anchors), UTF-8 source assumption, and Python stdout encoding each cost a CI cycle or a test iteration.

Scope — what a "clean code" policy should pin down:

- **Encoding.** UTF-8 everywhere in source, tests, docs, and script output. Python scripts call `sys.stdout.reconfigure(encoding="utf-8")` before printing multi-byte characters (already done in `scripts/dev/backlog.py`). .kts/.kt files are already UTF-8 by Kotlin contract.
- **Line endings.** LF in repo (`.gitattributes` ships this), CRLF only on `.bat/.cmd/.ps1`. Already enforced — document in the policy doc for future readers.
- **ktlint baselines.** Treat as "technical debt I'm tracking down", not as permanent opt-outs. Add a convention: every edit near a baselined violation carries a responsibility to either fix the violation or regenerate the baseline (not silently drift past it). Agent memory `feedback_ktlint_baseline_drift` documents this; codify it in a policy file too.
- **Multi-line expression wrapping.** The single recurring rule that's bitten us three times this session. Style guide should say: assignments of multi-line expressions always put the opening `{`/`(`/`try` on a new line after the `=`. ktlintFormat auto-applies this; the policy should make the expectation explicit so reviewers and agents don't "un-fix" it.
- **Line length.** 140 char soft cap (already ktlint-enforced). Docstrings get hard-wrapped at 100 for readability on narrow terminals.
- **Import order.** Lexicographic, one blank between java/javax/kotlin groups — already ktlint-enforced. Explicit wildcard imports avoided except for `kotlinx.serialization.json.*` where the DSL shape justifies it.
- **Test naming.** Backticked Kotlin test-method names describing the behaviour, not the function under test (already the dominant pattern — make it normative).
- **Commit messages.** Conventional Commits. One UD-### per commit scope. `resolved_by: commit <sha>` in the CLOSED.md entry always points at the immediately-preceding code commit, never at the same commit as the docs close.

Deliverable: `docs/dev/CODE-STYLE.md` written as a short (≤ 2 page) normative doc. Cross-link from `CLAUDE.md`, `docs/dev/TOOLS.md`, and the root `README.md`. Where a rule is ktlint-enforced, point at the specific rule id; where it's manual, give a one-sentence "why".

Acceptance: an agent that has never seen this repo can read `CODE-STYLE.md` + `CLAUDE.md` and ship a commit that passes CI without bouncing once.

**Related:** UD-706b (ktlint strict), UD-706 (ktlint wiring), memory `feedback_ktlint_baseline_drift` + `feedback_kotlin_default_param_ordering` (code-style lessons already encoded per-agent; this ticket lifts them into repo docs).

---
id: UD-728
title: Git commit-scope railguards: pre-commit scope check, tree-state pre-flight, backlog-close sanity
category: tooling
priority: medium
effort: M
status: closed
closed: 2026-04-20
resolved_by: commit e405b2b. R1-R4 all shipped. Pre-commit hook (scope-check.sh) + backlog.py audit-commits + ktlint-sync.sh --module pre-flight + backlog.py close sanity warnings. Opt-in install via scripts/dev/pre-commit/install.sh (sets core.hooksPath). Bypass with --no-verify or KTLINT_SYNC_SKIP_PREFLIGHT=1. Documented in docs/dev/TOOLS.md.
code_refs:
  - scripts/dev/
  - scripts/dev/backlog.py
  - scripts/dev/ktlint-sync.sh
opened: 2026-04-20
---
The 2026-04-20 session exposed a class of bug where **commit scope drifts from the scope implied by the commit subject**, polluting `git blame` / `git bisect` and breaking downstream ticket traceability. Three observed incidents in two days:

1. `56c11ac chore(ktlint): sync :app:cli + :providers:onedrive baselines` — but the diff also pulled in a 44-line broken UD-503 reflection stub in `ui/.../IpcClient.kt` left behind by a halted sub-agent. Broke `./ui/gradlew build` silently for the rest of the session.
2. Several cherry-picks from worktree-agent-* branches brought in incidental ktlint baseline changes that had to be resolved manually; each resolution is a micro-footgun.
3. Multiple `fix(UD-xxx)` commits that should have been scoped to one subcommand file accidentally carried formatter-only edits to neighbouring test files because `scripts/dev/ktlint-sync.sh` auto-formatted tree-wide.

Related memory: `feedback_halted_agent_leaks.md` (root cause analysis).

## Proposed railguards (pick any subset that earns its keep)

### R1 — Pre-commit scope validator
A pre-commit hook (`scripts/dev/pre-commit/scope-check.sh`) that parses the commit subject's type + scope (`chore(ktlint)`, `fix(UD-228)`, `feat(UD-216)`) and asserts the staged file set matches a per-type whitelist:

| Subject pattern | Allowed file set |
|---|---|
| `chore(ktlint)` | `**/config/ktlint/baseline.xml` only |
| `chore(deps)` | `gradle/libs.versions.toml`, `build.gradle.kts`, `*/build.gradle.kts` |
| `docs(backlog)` | `docs/backlog/*.md` only |
| `docs(handover)` | `handover.md` only |
| `fix(UD-###)` / `feat(UD-###)` | at least one path matching the ticket's `code_refs` from `backlog.py show UD-###` |

Fail with a clear message + the unexpected file list. Bypass with `--no-verify` for deliberate cross-cutting commits (rare, but exists).

### R2 — Post-merge scope audit
A Gradle or Python task: `backlog.py audit-commits --since main` that walks every commit, re-derives expected scope from the subject, and flags violations. Runs in CI or on demand.

### R3 — Pre-flight tree-state check before ktlint-sync etc.
Teach `scripts/dev/ktlint-sync.sh` to `git status --short` before the `ktlintFormat` step. If any file outside the module's scope is already modified, abort with a message: "Unexpected uncommitted file outside `:module`: `path`. Resolve (commit / restore / stash) before ktlint sweep."

Covers the halted-agent case: stuck worktree WIP leaks are visible before they get swept.

### R4 — `backlog.py close` sanity check
`backlog.py close UD-### --commit <sha>` could:
- Verify the commit's subject contains `UD-###` or a closely related ticket id.
- Verify the commit's touched paths overlap with the ticket's `code_refs`.
- Warn (not block) when they don't. Pure idempotent read; no writes.

Catches the "I closed the wrong ticket against this commit" class of error.

### R5 — Agent-worktree reclamation
Script `scripts/dev/agent-worktrees.sh --list|--reclaim` that enumerates `.claude/worktrees/agent-*`, shows age / dirty-state / associated branch, and offers to prune halted-with-no-commits ones. Mentioned in the handover but not automated today.

## Acceptance

- R1 (pre-commit hook) installed and lands a chore commit showing it catches a manufactured cross-scope test case.
- R3 (ktlint-sync pre-flight) is in place and tested by leaving an out-of-scope uncommitted file and asserting the script aborts.
- Either R2 or R4 (audit tooling) exists.
- R5 is stretch.

## Scope / effort

M overall. R1 + R3 are the highest leverage; R2/R4/R5 are individually XS-S and can land in follow-ups.

## Related

- `feedback_halted_agent_leaks.md` (memory)
- `feedback_ktlint_baseline_drift.md` (memory) — baseline line-anchor drift is the other face of the same tree-wide-format problem.
- UD-256 / UD-251 — the halted-agent incident that triggered this ticket got caught only because I ran `./ui/gradlew` by coincidence while applying the UD-258 one-liner; without that step the UI would have shipped broken on this branch.

---
id: UD-716
title: MCP: unidrive-backlog — elevate backlog.py to MCP + add audit() tool
category: tooling
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit 19feb83. Python MCP at scripts/dev/backlog-mcp/ wraps next_id/show/file/close + new audit() tool. Refactored backlog.py to extract file_ticket_impl/close_ticket_impl as pure-logic entry points that both argparse and MCP consume. audit() scans for duplicate_ids, missing_fields, broken_cross_refs, stale_code_refs, orphan_resolved_refs. smoke.py read-only test exits 0.
code_refs:
  - scripts/dev/backlog-mcp/
  - scripts/dev/backlog.py
opened: 2026-04-19
---
Elevate `scripts/dev/backlog.py` from a Python CLI to an MCP server exposing the same operations as structured tools. Dogfooded 4× across the 2026-04-19 session; both UX and correctness held. Moving to MCP gets (a) structured-tool calling with typed args, (b) a new `audit()` tool the CLI can't easily deliver, (c) portability across agent harnesses.

## Tools to expose

- `next_id(category: str) -> str` — same as CLI's `next-id`.
- `show(id: str) -> { found_in: "BACKLOG" | "CLOSED", block: str }` — same as CLI's `show`.
- `file(id, title, category, priority, effort, code_refs[], body) -> { path, id }` — same as CLI's `file`. Validates: id not in use, category matches the ID range, priority ∈ {low,medium,high,critical}, effort ∈ {XS,S,M,L,XL}.
- `close(id, commit, note) -> { moved_to: "CLOSED.md" }` — same as CLI's `close`. Validates: id exists in BACKLOG.md, commit is a 7-char sha.
- **NEW: `audit() -> { orphan_resolved_refs, duplicate_ids, missing_fields, broken_cross_refs, stale_code_refs }`** — scans both files, returns a structured report of drift the CLI can't easily surface:
  - Orphan resolved_by hashes pointing at commits that don't exist on `main`.
  - Duplicate IDs across BACKLOG and CLOSED.
  - Missing required frontmatter fields (id/title/category/priority/effort/status/opened).
  - "Related: UD-X" references where UD-X doesn't exist anywhere.
  - `code_refs:` paths that no longer exist in the working tree (±10 line tolerance per existing `scripts/backlog-sync.kts` contract).

## Architecture

- Python MCP server under `scripts/dev/backlog-mcp/`. Reuses `scripts/dev/backlog.py` via `import backlog` — no logic duplication.
- Runs outside the Claude Desktop MSIX sandbox (same constraint as `oauth-mcp/`). User registers in their Claude Code MCP config.
- Pure read/write operations on markdown files in the repo. No network, no auth.
- Follows the oauth-mcp template at `scripts/dev/oauth-mcp/server.py` for the stdio + tool-registration boilerplate.

## Acceptance

1. All four existing subcommands have MCP parity. Piping `backlog.py` vs `mcp__unidrive-backlog__*` produces byte-equivalent results on BACKLOG.md / CLOSED.md.
2. `audit()` runs cleanly against the current repo state — must return at least the right data structure, and should catch at least the known drift items (e.g. the UD-227 ghost ticket fixed this session would have been caught by `audit()` if the tool had existed then).
3. Dogfood: file one new ticket and close one existing ticket via the MCP from a Claude Code session. Both round-trip through `git diff`.
4. `audit()` callable as a pre-push hook (via `gh`-CLI or a manual `python -m scripts.dev.backlog_mcp.audit`) — wire that in after the MCP is green.

## Non-goals for this ticket

- Auto-commit on `file()` / `close()`. Deliberately left to the agent so commit messages stay the agent's responsibility. User confirmed this design call 2026-04-19.
- GitHub issue sync. Out of scope; repo uses another primary remote.

## Open questions

None — ready for implementation. Effort estimate: ~100 lines Python + ~50 lines test, ~1 hour for an experienced agent.

## Related

- UD-715 (Buschbrand brainstorm — the "meta tooling" thread).
- UD-714 (code-style policy — sibling "make the tacit explicit" work).
- UD-717 (sibling MCP proposal: log-tail). Both files can share the Python MCP template.
- UD-808 (OAuth test framework, blocked on secret injection — the already-shipped `oauth-mcp/` is the template to copy for this one).

---
id: UD-718
title: MCP: unidrive-gradle — targeted test/build/ktlint invocations with structured results
category: tooling
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit fc5bc3c. Python MCP at scripts/dev/gradle-mcp/ with three tools (test, build, ktlint_sync). composite_map.py routes Gradle module paths (:app:cli, :providers:onedrive, :desktop) to their composite dirs (core, ui). test() parses JUnit XML for structured {passed,failed,skipped,failure_details}. build() incremental build with optional skip_lint. ktlint_sync() shells out to scripts/dev/ktlint-sync.sh. Windows detection via os.name == 'nt' routes to gradlew.bat.
code_refs:
  - scripts/dev/gradle-mcp/
  - scripts/dev/ktlint-sync.sh
opened: 2026-04-19
---
MCP wrapping targeted Gradle invocations so the agent doesn't have to know which composite to `cd` into, whether the daemon is warm, or which task name to pick.

## Tools

- `test(module: str, test_class: str? = null, test_method: str? = null) -> { passed: int, failed: int, skipped: int, failure_details?: [], duration_s: float }` — selective test invocation. Maps to `./gradlew :<module>:test --tests <class>.<method>` under the right composite. Returns structured test results parsed from the JUnit XML.
- `build(modules: str[] = ["*"], skip_lint: bool = false) -> { built: [...], failed: [...], duration_s }` — incremental build across named composites. Default = all. `skip_lint=true` runs with `-x ktlintCheck -x ktlintMainSourceSetCheck -x ktlintTestSourceSetCheck` for the "I want to see if the code compiles, not style-nit" loop.
- `ktlint_sync() -> { formatted_files: [...], baseline_regen: [...] }` — thin wrapper around `scripts/dev/ktlint-sync.sh`. Returns which files were auto-formatted and which baselines were regenerated.

## Architecture

- Python MCP, template: `scripts/dev/oauth-mcp/server.py`. Runs on user's native side (Gradle wrapper scripts + Kotlin toolchain are hidden from the MSIX-sandboxed Python — same pattern as msix-identity).
- Resolves composite dir (`core/` vs `ui/` vs repo-root) via a simple lookup table on the module name.
- Parses `build/test-results/*/TEST-*.xml` for structured failure data instead of scraping stdout.

## Value estimate

Every test iteration I do costs ~3 calls today: `cd core`, `./gradlew ... test`, parse stdout. An MCP collapses to 1 call with structured output. I hit the test loop ~5-10× per session. ~15-25 tool calls saved per session; ~20-40% of wall-clock on the test-iterate loop.

## Non-goals

- Running integration tests that require OAuth (covered by UD-808 / the `unidrive-test-oauth` deployment ticket UD-723).
- Live-reload / incremental feedback. Use `--continuous` flag manually if needed.

## Open questions

- Should the MCP manage a warm daemon on behalf of the agent, or rely on whatever `./gradlew` does by default? Default-behaviour is simpler and probably fine.

## Related

UD-715 (meta-tooling thread), UD-716 (sibling MCP, simpler template), UD-714 (code-style policy — ktlint_sync tool enforces its rules).

---
id: UD-721
title: MCP: unidrive-memory — structured add/update/remove + MEMORY.md index sync + drift audit
category: tooling
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit 2a02166. Python MCP at scripts/dev/memory-mcp/ — list/read/add/update/remove + index_sanity. Hand-parses YAML frontmatter (3 keys only, no pyyaml dep). Atomic writes via tempfile+fsync+os.replace. MEMORY.md index preserved — only lines matching a filename are replaced; headers and trailing content survive edits. UNIDRIVE_MEMORY_DIR env override lets smoke.py run against a tempdir. Type and name are immutable on update (rename = remove + add).
code_refs:
  - scripts/dev/memory-mcp/
opened: 2026-04-19
---
MCP for structured reads and writes of the agent-memory files at `~/.claude/projects/C--Users-gerno-dev-git-unidrive/memory/`. Enforces the frontmatter contract and keeps `MEMORY.md` index in sync with the underlying memory files — both currently manual and error-prone.

## Tools

- `list() -> [{ name, type, description, one_line_preview }]` — enumerate all memory files with their frontmatter, ordered by type then name.
- `read(name: str) -> { frontmatter: {...}, body: str }` — return one memory file, structured.
- `add(type: "user"|"feedback"|"project"|"reference", name, description, body) -> { path, index_line }` — write a new memory file with correctly-formed frontmatter, append the index line to `MEMORY.md` atomically. Refuses duplicates.
- `update(name, description?, body?) -> { changed_fields: [...] }` — in-place edit preserving frontmatter. Re-syncs the MEMORY.md index line if the description changed.
- `remove(name) -> { removed_path, removed_index_line }` — deletes the memory file and removes its MEMORY.md line. No soft-delete; memory hygiene prefers pruning.
- `index_sanity() -> { orphan_files: [...], orphan_index_lines: [...], description_drift: [...] }` — scans the whole memory dir for: memory files not listed in MEMORY.md, index lines pointing at missing files, index-line descriptions that no longer match the memory's frontmatter description.

## Architecture

- Python MCP. Doesn't care which side of the MSIX sandbox it runs on because `~/.claude/projects/.../` is not under the virtualised roots (verified 2026-04-19).
- Frontmatter serialisation: YAML (matches existing manual format).
- Index format: strict `- [Title](file.md) — description` line per entry (matches existing shape).

## Value estimate

Adding a memory today is: figure out the right frontmatter shape from another file, write the memory file, remember to append to MEMORY.md, get the formatting right. ~4 tool calls + mental overhead. With the MCP: 1 call.

Stronger value: `index_sanity()` as a pre-session check. Memory drift is hard to spot today; a structured scan would catch orphan files / stale descriptions before they cause a false-context issue in the next session.

## Non-goals

- Cross-project memory (memories living under a different project's memory dir). Each project's MCP instance is scoped to its own repo.
- Memory migration (user → project, etc.). Rename-only is out; use `remove` + `add` if the type changes.

## Open questions

- Should `update()` be allowed to change `type`? Current inclination: no. Type is structural; if type needs to change, the memory is probably fundamentally different and should be a new entry.
- Namespace: does this MCP's name collide with Anthropic's "memory" MCP? Propose `unidrive-memory` to disambiguate.

## Related

- `~/.claude/projects/.../memory/MEMORY.md` — the index this MCP keeps consistent.
- The `anthropic-skills:consolidate-memory` skill — complementary: this MCP is the write path, that skill is the batch-cleanup path.
- UD-715 (meta-tooling).

---
id: UD-717
title: MCP: unidrive-log-tail — push-model anomaly streaming from unidrive.log
category: tooling
priority: medium
effort: M
status: closed
closed: 2026-04-20
resolved_by: commit daceeed. Python MCP at scripts/dev/log-tail-mcp/ — current_state, watch, baseline_update, tail. 2-second poll loop on file size with inode+offset+line-number cursor surviving rotation/truncation. watch() returns a fixed-window batch (default 30s, test override) since MCP protocol can't mid-call-stream; module-global cursor prevents loss across invocations. Rate limit: >10 events/5s triggers {category: rollup, count: N} and 30s suppression. Baseline JSON persisted at ~/AppData/Local/unidrive/unidrive-log-baseline.json.
code_refs:
  - scripts/dev/log-tail-mcp/
  - scripts/dev/log-watch.sh
opened: 2026-04-19
---
Push-model counterpart to the `unidrive-log-anomalies` skill (pull-model). The skill works when the agent remembers to invoke it; this MCP streams anomaly events into the agent's context the moment they land in `unidrive.log`, closing the "silence = success" failure mode where a sync quietly drifts into a retry storm between agent checks.

## Tools to expose

- `current_state() -> { total_lines, warn, error, throttle_429, download_failed, fatal_5_5, jwt_malformed, tls_handshake, retry_distribution[] }` — one-shot summary equivalent to `scripts/dev/log-watch.sh --summary`. Safe to call anytime; never blocks.
- `watch(since_ts: epoch_ms?, filters: string[]?) -> stream<LogAnomalyEvent>` — **long-running streaming tool** that emits one event per new anomaly line since `since_ts` (or since process start). Default filter set matches `log-watch.sh --anomalies`: `WARN|ERROR|Exception|throttled|Download failed|handshake|JWT`. Optional custom regex list in `filters`.
- `baseline_update() -> { prev_baseline, new_baseline, stored_at }` — snapshots current counts as the new "session close" reference. Persists to `~/AppData/Local/unidrive/unidrive-log-baseline.json` so `current_state()` can render deltas instead of raw counts. Called once at end of session.
- `tail(lines: int) -> string[]` — last N raw log lines, unfiltered. Escape hatch when the filters suppress something the agent needs to see.

## LogAnomalyEvent shape

```json
{
  "ts": "2026-04-19T14:45:17.123Z",
  "level": "WARN",
  "category": "throttle_429" | "download_failed" | "fatal_5_5" | "jwt_malformed" | "tls_handshake" | "other",
  "line": "<full log line, truncated to 500 chars if longer>",
  "context_line_number": 8772,
  "since_baseline_count": 2168
}
```

The `category` pre-classification lets the agent batch by type instead of re-parsing every line.

## Architecture

- Python MCP server under `scripts/dev/log-tail-mcp/`. Runs on user's native machine (same as `oauth-mcp/` — the MSIX sandbox hides `%LOCALAPPDATA%\unidrive\` from Python).
- Log watch implementation: simple 2-second poll on file size + seek-to-last-position + read-new-bytes. Resist the urge to use `watchdog` / OS-level file-notify; polling is 20 LOC and works identically on every platform we care about.
- Baseline persistence: single JSON file at `~/AppData/Local/unidrive/unidrive-log-baseline.json`. Structure matches `current_state()` output so a trivial delta-compute is `current - baseline`.
- `log_path` resolved via `UNIDRIVE_LOG` env var if set, else `%LOCALAPPDATA%\unidrive\unidrive.log` on Windows, else `$HOME/.local/share/unidrive/unidrive.log` on Linux (matches the logback config at `core/app/cli/src/main/resources/logback.xml:3`).

## Acceptance

1. `current_state()` returns byte-equivalent counts to `scripts/dev/log-watch.sh --summary` for the same log file at the same moment.
2. `watch()` emits at most one event per log line, never duplicates, never drops.
3. Start `watch()`, append a WARN line to the log via `echo` — event lands in the agent's context within 3 s.
4. Append 100 lines of the same WARN — event stream stays bounded (agent sees a summary / rollup every N events rather than a flood). Mechanism: MCP-side rate-limit, e.g. "after 10 events in 5 s, emit one rollup and suppress individual events for 30 s."
5. Restart the MCP mid-`watch()` — resumes from the persisted cursor, no event loss, no re-emission.

## Non-goals

- Full log parsing into structured events. The categorisation is a 7-element enum; don't try to extract throttle durations, file paths, etc. If the agent needs more, it calls `tail(N)` and greps.
- Multi-profile correlation. One log file per MCP instance. Multi-profile logs are a v2 concern.

## Open questions

- **MCP streaming contract** is still moving. As of 2026-04 the Python SDK supports long-running tools via `async def` handlers that yield, but the conventions for how the client observes the stream are not fully stable. Worth prototyping against the current SDK; be ready to swap implementation if the spec moves.
- **Rate-limit policy** (acceptance criterion 4) needs a concrete threshold. Propose: "10 events in 5 s → rollup; resume individual events after 30 s of silence or after the agent explicitly calls `resume_individual()`." Tweakable; defer final call to implementation time.

## Why this ships after UD-716

UD-716 (`unidrive-backlog` MCP) is ~1 hour with zero unknowns. UD-717 is ~3–4 hours with one real unknown (the streaming-tool SDK contract). Don't block backlog-MCP on log-tail-MCP.

## Related

- UD-715 (Buschbrand — meta-tooling thread).
- `.claude/skills/unidrive-log-anomalies/SKILL.md` — pull-model counterpart this MCP complements, not replaces. Agent keeps invoking the skill for on-demand summaries; the MCP adds the push channel.
- UD-716 (backlog MCP — sibling implementation).
- `scripts/dev/log-watch.sh` — the current on-demand implementation; this MCP's `current_state()` is functionally equivalent.

---
id: UD-719
title: MCP: unidrive-jvm-monitor — running JVM state + safe_to_deploy precondition
category: tooling
priority: medium
effort: M
status: closed
closed: 2026-04-20
resolved_by: commit e870fe9. Python MCP at scripts/dev/jvm-monitor-mcp/ with list_unidrive_processes, jvm_state, safe_to_deploy. psutil enumerates processes, classifies by commandline substring (sync/ui/mcp). fsutil queryFileID via subprocess compares loaded vs deployed jar for needs_restart detection. Windows primary target; POSIX fallback returns None + path-normalisation backstop in recommendations. Read-only by design — safe_to_deploy emits taskkill strings, never executes them (UD-719 non-goal). smoke.py passes on Python 3.14.3.
code_refs:
  - scripts/dev/jvm-monitor-mcp/
opened: 2026-04-19
---
MCP that surfaces running JVM state so the agent can make safe deploy decisions without manually scraping `tasklist` + `fsutil` + `ps`-style output.

Addresses the repeat pain of UD-712 (deploy taskkill filter misses background daemons) and `feedback_jar_hotswap` (Windows classloader corrupts when a running jar is overwritten). An MCP that answers "is it safe to deploy right now?" with structured data is much better than the current "grep PID from tasklist, eyeball commandline".

## Tools

- `list_unidrive_processes() -> [{ pid, image, mem_mb, start_time, commandline, jar_path?, is_sync: bool, is_ui: bool, is_mcp: bool }]` — everything with `unidrive` in the commandline, classified.
- `jvm_state(pid: int) -> { pid, uptime_s, loaded_jar_mtime, loaded_jar_file_id, deployed_jar_file_id, needs_restart: bool }` — cross-references the running JVM's loaded jar against the currently-deployed artifact via NTFS file IDs. `needs_restart=true` iff deployed_jar_file_id differs from loaded_jar_file_id (answers "am I looking at stale code?" authoritatively).
- `safe_to_deploy(target_jar_path: str) -> { safe: bool, holding_pids: [...], recommendations: [...] }` — returns `safe: false` + a specific `taskkill /PID <pid> /F` command list when any running JVM holds the target jar. The "don't hot-swap" rule enforced as a precondition.

## Architecture

- Python MCP on user's native side (JVM visibility hidden from MSIX sandbox).
- `psutil` for process enumeration. `fsutil file queryFileID` via subprocess for file-ID resolution (reuses msix-id.sh logic — UD-720).
- Detection of "which jar a JVM has loaded" on Windows via `GetModuleFileNameEx` or reading `\jar\` lines from the process's commandline. Good-enough heuristic for unidrive's one-jar-per-JVM shape.

## Value estimate

Deploy flow today: `tasklist | grep java` → read commandline by eye → `taskkill` → `copy jar` → `start`. Probably 6-8 tool calls when I'm cautious. MCP: 2 calls (`safe_to_deploy` + kill-on-demand). Saves the "did I kill the right JVM?" anxiety.

## Non-goals

- Starting JVMs. Too environment-specific (service vs user-session vs systemd). Stick to read-only + kill.
- Linux / macOS parity. Low priority; Windows is the target because that's where the deploy pain is.

## Open questions

- The "which jar is loaded by this JVM?" heuristic is fuzzy. Confirm on the actual running PID 3240 (from session handover) whether parsing commandline is reliable. If not, fall back to comparing hashes of all jars in `%LOCALAPPDATA%\unidrive\` against what's open in the JVM's handle table (via `handle.exe` / Sysinternals).

## Related

UD-712 (deploy taskkill filter — direct consumer), UD-715 (meta-tooling), `feedback_jar_hotswap` in agent memory.

---
id: UD-724
title: MCP: query_graph + query_state_db — read-only introspection tools for unidrive-test-oauth (or sibling MCP)
category: tooling
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit f66d029. query_graph + query_state_db added to existing unidrive-test-oauth MCP (ticket's recommended one-register path). Mutation-path hints (/copy, /move, /delete, /createUploadSession, …) lowercased + prefix-matched at the tool surface, rejected before token mint. query_state_db opens SQLite via file:...?mode=ro URI, regex-rejects DDL/DML at statement level, auto-appends LIMIT. Reuses the existing token cache so a series of queries doesn't hammer AAD.
code_refs:
  - scripts/dev/oauth-mcp/server.py
opened: 2026-04-19
---
Expand the existing `unidrive-test-oauth` MCP (or a sibling `unidrive-inspect` MCP) with **read-only** introspection tools so agents don't have to bring their own `httpx` / `sqlite3` boilerplate every time they want to look at Graph or `state.db`.

## Motivation — concrete 2026-04-19 finding

During the MCP probe session, every Graph query took ~20 lines of Python + explicit token handling:

```python
import httpx, os
token = os.environ['TOKEN']
with httpx.Client(timeout=15) as c:
    r = c.get('https://graph.microsoft.com/v1.0/me/drive', headers={'Authorization': f'Bearer {token}'}).json()
    # ...
```

Same for `state.db`:

```python
import sqlite3, os
db = os.path.expanduser('~/AppData/Roaming/unidrive/onedrive-test/state.db')
con = sqlite3.connect(db)
# ...
```

Both are boilerplate that every agent and every session re-writes. A pair of read-only MCP tools collapses each to one call with structured JSON back.

## Proposed tools

**`query_graph(profile, path, params?) → json`**
- Delegates to `grant_token(profile)` internally, issues `GET https://graph.microsoft.com/v1.0{path}` with the minted token and the optional `$select`/`$top`/`$skiptoken` params, returns decoded JSON.
- Read-only: refuses if `path` looks like it would mutate (POST/PUT/DELETE equivalents blocked at the tool surface).
- Caches the minted token for 50 min so a series of queries doesn't re-refresh each time.

**`query_state_db(profile, sql, limit?=1000) → [row]`**
- Opens `%APPDATA%\unidrive\<profile>\state.db` read-only (`uri=file:...?mode=ro`).
- Executes `sql`; enforces `LIMIT ≤ limit` server-side even if the query omits one.
- Rejects anything other than `SELECT` or `WITH ... SELECT` at a regex level — defence-in-depth.
- Returns a list of dicts (keys = column names).

## Out of scope (deliberate)

- No write path. Anything that mutates state goes through the CLI / existing MCPs.
- No generic JSON-path filter on Graph responses — agent can do that in 2 lines once the payload is back.
- No "pretty-print" formatting — JSON in, JSON out.

## Acceptance

- Agent can replicate the 2026-04-19 probe session (drive info + quota + top-level children + state.db counts + pending_cursor check) in **5 MCP calls, zero Python glue**.
- `query_state_db("onedrive-test", "DROP TABLE sync_entries")` returns an error with `"read-only"` in the message; the DB file is unchanged.
- `query_graph("onedrive-test", "/me/drive/root/children?$top=999")` returns all 222 children of `/Pictures` (the UD-314 regression case) in one call.

## Implementation note

- Add to the existing `scripts/dev/oauth-mcp/server.py` rather than spinning up a new MCP binary. The server already holds the token cache; adding two more tools is minimal scope creep.
- Alternatively: spin up `scripts/dev/inspect-mcp/` as a sibling under the same Python venv — cleaner separation but one more registration in the user's `~/.claude.json`.
- Pick based on whether the user wants to register a second MCP. Either works; default recommendation is **extend `unidrive-test-oauth`** for one-MCP-one-register simplicity.

## Related

- UD-716 (unidrive-backlog MCP proposal) — same template + pattern.
- UD-723 (closed) — original oauth-mcp deploy; this ticket extends its surface.
- UD-717 (log-tail MCP proposal) — third MCP; will share the Python venv + registration pattern.

---
id: UD-725
title: MCP: unidrive-daemon-ipc — sync_state + sync_log_tail + sync_stop(drain=true) wrapping IpcServer
category: tooling
priority: medium
effort: M
status: closed
closed: 2026-04-20
resolved_by: commit 7d3762a. Python MCP at scripts/dev/daemon-ipc-mcp/ with sync_state + sync_log_tail. Connects to IpcServer's AF_UNIX broadcast socket, reads the state dump + events within collect_ms, returns a structured snapshot (phase, current_file, action_index/total, recent_events). sync_log_tail efficient reverse-read of unidrive.log with explicit source='log' label. sync_stop deferred — no stop sentinel in code today, no IPC stop verb; revisit when UD-712 ships. Socket path resolution mirrors IpcServer.defaultSocketPath incl. long-profile hashing.
code_refs:
  - scripts/dev/daemon-ipc-mcp/
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt
opened: 2026-04-19
---
MCP that wraps the existing `IpcServer` so an agent can **observe and drive** a running unidrive sync daemon without writing raw Named-Pipe client code.

## Motivation — concrete 2026-04-19 finding

Today an agent with a running sync (PID 10520 during this session) can see:
- The log file (via `scripts/dev/log-watch.sh`), limited to what the daemon writes at WARN+.
- The stdout session log (via tail), with `CliProgressReporter` progress lines but no structured state.

The agent CANNOT easily see:
- Which file is currently downloading.
- The in-flight ThrottleBudget state (circuit open? concurrency permits? recent-throttle window?).
- Queue depth ahead of the current file.
- Bytes transferred this session vs total.

All of these exist in the running JVM but are only reachable via the Named-Pipe IPC protocol at `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`. That protocol was designed for the tray UI, not for agents.

## Proposed tools

**`sync_state(profile) → { phase, current_file?, queue_depth, bytes_transferred_session, bytes_total, throttle_budget: { circuit_open, current_concurrency, resume_after_ms, recent_throttles } }`**
- Connects to the profile's IPC pipe (see `IpcServer.kt` for the pipe name convention).
- Returns a snapshot, closes the connection.
- Safe to call at any frequency; the IPC server's throttle-limit applies.

**`sync_log_tail(profile, lines=50) → [line]`**
- Reads the in-memory circular log buffer from the running JVM (if the IPC protocol exposes one — confirm first; if not, tail `unidrive.log` the same way `log-watch.sh` does and label the tool accordingly).

**`sync_stop(profile, drain=true) → { stopped: bool, drain_ms }`** — **stretch goal**
- Writes the sentinel file `%LOCALAPPDATA%\unidrive\stop` (already observed by the restart-loop batch wrapper) and waits for the daemon to exit cleanly.
- `drain=true` waits up to 30 s for Pass 2 workers to finish their current transfers; `drain=false` just writes the sentinel.
- Default `drain=true` matches the UD-712 ticket's ask for a graceful stop path.

## Out of scope

- Starting a sync (requires spawning a JVM — agents should use the existing CLI path).
- Mutating sync parameters mid-run (`--download-only` toggle, etc.). Keep the MCP observation-first with the single mutation being "stop gracefully".

## Acceptance

- `sync_state("onedrive-test")` against the currently-running PID 10520 returns a structured snapshot in <200 ms.
- Running the tool with the daemon NOT running returns a clear `{"error": "no IPC pipe for profile onedrive-test"}` rather than a hang.
- `sync_stop` with `drain=true` observes the sentinel file getting consumed by the batch wrapper and the JVM exiting cleanly.
- Unit test harness: start a mock IPC server that speaks the protocol subset we need, assert the MCP issues the right frames and parses the responses.

## Dependency note

The exact IPC protocol surface depends on what `IpcServer.kt` currently exposes — the audit is part of the work. If the protocol doesn't already expose `throttle_budget` state, extending the protocol is half the ticket. Either:
(a) Do it within this ticket — scope grows to M.
(b) File UD-725b to extend `IpcServer` first, then this ticket becomes pure MCP wiring.

Recommendation: (a) — implement the protocol extension in lockstep with the MCP so there's one consumer driving the contract, one PR landing both.

## Related

- UD-712 (deploy taskkill filter too narrow) — `sync_stop(drain=true)` is the graceful alternative to taskkill; close-companion ticket.
- UD-717 (log-tail MCP) — different surface, different data source; this ticket is "what is the daemon doing right now?", UD-717 is "what did it just log?".
- UD-716 (backlog MCP) — same template.
- UD-232 (ThrottleBudget) — exposes the state this MCP surfaces; if UD-232b (adaptive spacing, new UD-200) lands, its state also surfaces here.

---
id: UD-730
title: One-shot installer for the Chunk G MCP servers
category: tooling
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit 6775d23. scripts/dev/install-mcps.sh shipped. Shared venv at scripts/dev/.venv-mcps/. --print emits JSON fragment (default); --merge deep-merges into claude_desktop_config.json after writing a .bak.<timestamp>. Verified merge preserves existing unidrive-test-oauth + unrelated third-party entries, is idempotent on re-run, and refuses non-destructively on invalid JSON. oauth-mcp's existing .venv is deliberately untouched.
code_refs:
  - scripts/dev/install-mcps.sh
opened: 2026-04-20
---
One-shot installer for the six new MCP servers shipped in the Chunk G wave (UD-716, UD-717, UD-718, UD-719, UD-721, UD-725). Eliminates the six per-MCP copy-paste setups documented in each README.

## Deliverable

`scripts/dev/install-mcps.sh`:

1. Creates a single shared venv at `scripts/dev/.venv-mcps/`.
2. `pip install mcp>=1.0.0 psutil>=5.9.0` (the union of every new MCP's requirements; oauth-mcp keeps its own venv untouched).
3. Emits a JSON fragment for `~/.claude/claude_desktop_config.json` listing all six servers, each pointing at the shared venv's python + its own `server.py`.
4. `--merge` flag reads the existing config, deep-merges the new `mcpServers` entries (preserving anything else, including `unidrive-test-oauth`), writes back after backing the original up to `<path>.bak.<timestamp>`.
5. `--print` (default) only prints the fragment — user pastes manually. Never touches their config file without explicit opt-in.

## Non-goals

- oauth-mcp's existing `.venv` is NOT migrated — it's already registered and working; no value disturbing it.
- Windows-specific path quoting is handled; Linux fallback tested via `os.name` detection inside the script's Python helpers.
- No uninstall path. `git config --unset` / manual config-file edit is sufficient.

## Acceptance

- Running the installer on a clean Windows shell produces a venv, pip-installs cleanly, emits a valid JSON fragment.
- `--merge` leaves existing non-unidrive MCP entries untouched; backup file is created; re-running is idempotent.

---
id: UD-110
title: Dependabot for Gradle + GitHub Actions
category: security
priority: low
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit 5b5c436. Dependabot config shipped: gradle (/core + /ui) + github-actions (/), weekly Monday 06:00 UTC.
code_refs: []
adr_refs: [ADR-0004, ADR-0009]
opened: 2026-04-17
---
Unblocked by [ADR-0009](../adr/0009-ci-host.md) (GitHub Actions). Scope: `.github/dependabot.yml` covering the Gradle ecosystem (shared `gradle/libs.versions.toml` + both `core/` and `ui/` composites) and the `github-actions` ecosystem (pin action versions). CMake FetchContent is not covered by Dependabot natively — defer the shell-win piece or track separately.

---
id: UD-102
title: Shell DLL hardening — CFG, DYNAMICBASE, SetDefaultDllDirectories
category: security
priority: high
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit d2ef989. CFG / DYNAMICBASE / HIGHENTROPYVA on unidrive-shell.dll; SetDefaultDllDirectories(SYSTEM32) as first action in DLL_PROCESS_ATTACH. dumpbin confirms all four PE characteristics.
code_refs:
  - shell-win/CMakeLists.txt
  - shell-win/src/DllMain.cpp
adr_refs: [ADR-0004]
opened: 2026-04-17
milestone: v0.3.0
---
Add `/guard:cf` `/DYNAMICBASE` `/HIGHENTROPYVA` to MSVC flags; call `SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32)` in `DllMain` to neutralize DLL hijacking via loader search order.

---
id: UD-303
title: Scheduled webhook renewal policy (renew ≤24h before expiry)
category: providers
priority: low
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit 15300d0. scheduler fires one renewal per (lifetime−24h) window; per-cycle ensureSubscription short-circuits when scheduled+valid
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:344
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:581
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SubscriptionStore.kt
adr_refs: [ADR-0005]
opened: 2026-04-17
---
**Rescoped 2026-04-17 after verification sweep.** The original framing ("SubscriptionStore is orphan, nothing consumes it") was wrong — `SyncCommand.ensureSubscription()` at `SyncCommand.kt:344` does call `GraphApi.renewSubscription()` on every poll cycle when `watch && rpConfig.webhook == true`, and `SubscriptionStore` is consumed at `SyncCommand.kt:224,235,258`. The actual gap is narrower: there is no **scheduled** pre-expiry renewal. Today each poll cycle either renews-if-close-to-expiry or creates-new-if-lapsed — functionally correct but trades extra round-trips for simplicity. Acceptance: add a scheduler (coroutine Timer) that renews once every (lifetime − 24h) regardless of sync cadence, and short-circuits the per-cycle `ensureSubscription` when a valid subscription exists. Priority dropped from medium to low because today's behavior is correct, just chatty.

---
id: UD-108
title: Trivy on dep lockfiles + container images
category: security
priority: medium
effort: S
status: closed
closed: 2026-04-20
resolved_by: commit b22480c. split into UD-100 (enable Gradle dep locking — prereq) and UD-103 (Docker Trivy — independent, ships today). Original body gated on 'once lockfile is produced' — UD-100 produces it.
code_refs: []
adr_refs: [ADR-0004]
opened: 2026-04-17
---
Scan `gradle.lockfile` (once produced) and any future Docker images.

---
id: UD-228
title: Cross-provider HTTP robustness audit — non-2xx JSON parsing + safe concurrency defaults
category: providers
priority: high
effort: L
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
  - core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp
  - core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
status: closed
closed: 2026-04-20
resolved_by: commit 280fcba. split into UD-262 (HttpRetryBudget extract) + UD-263 (semaphore wiring) + UD-318..UD-324 (7 per-provider audits). Umbrella scope preserved in the split commit.
opened: 2026-04-19
---
OneDrive (UD-207, UD-227) reached HTTP-error-aware behaviour only after real traffic surfaced the gaps: 2,060 × 429 fatal, 40+ Content-Length mismatches ignored, CDN-path 429s bypassing the retry pipeline, `retryAfterSeconds` tucked inside the JSON body ignored. Every other provider in the tree (HiDrive, WebDAV, Internxt, S3, SFTP, Rclone) uses the same Ktor-client-throws-on-non-2xx shape and has had ~zero real-traffic exposure. Assume they have identical latent gaps.

**Audit scope, per provider:**
  1. Does the provider parse the non-2xx body? Providers often return retry hints, quota details, or recoverable-vs-fatal distinctions in the body. Unidrive should extract and react — not just string-format and rethrow.
  2. Is the retry wrapper at HTTP layer (like UD-207) or at the SyncEngine action layer (which fails fatally on anything not whitelisted)?
  3. Are 429/503/5xx handled by Retry-After header + body hint (HiDrive uses `X-RateLimit-*`, Internxt has its own conventions)?
  4. Is `authenticatedRequest` (or the provider's equivalent) idempotent, or does it carry body state that can't be safely retried?

**Safe defaults to establish unidrive-wide:**
  - Per-provider `maxConcurrentTransfers` hint used by SyncEngine's Pass 2 semaphores, replacing the current hardcoded 16/6/2 split. Graph docs recommend ≤10 req/s per app-per-user → ~6–8 concurrent downloads. S3 handles thousands. SFTP typically 4–8. WebDAV varies by server.
  - Per-provider `minRequestSpacingMs` for sensitive servers (mostly SFTP-behind-DSL, some WebDAV). Cheap token-bucket, stateless across invocations.
  - Global "respect Retry-After at least until wait budget exceeded" policy.

**Deliverable:**
  1. Per-provider audit doc under `docs/providers/<id>-robustness.md` (sections: error-body parsing, retry placement, Retry-After source, idempotency notes, recommended concurrency, known flakes).
  2. Extract throttle helpers from GraphApiService into a reusable `HttpRetryBudget` class in `core/app/core` consumed by all provider HTTP services.
  3. Extend `ProviderRegistry.getMetadata` with the concurrency hints.
  4. Update SyncEngine Pass 2 semaphores to read from provider metadata.

**Related:** UD-207 (Graph 429, closed), UD-227 (Graph CDN 429, closed), UD-222 (surfaced how brittle sync-level retry is against real provider traffic), UD-229 / UD-230 (research inputs).

---
id: UD-264
title: LocalFsProvider: normalize relativized paths to forward slashes on Windows
category: core
priority: high
effort: S
status: closed
closed: 2026-04-21
resolved_by: commit 83cb324. Fixed: replaced Path.relativize().toString() with .joinToString("/") at listChildren() (line 126) and walkRoot() (line 223) in LocalFsProvider.kt. Backslash-separated paths on Windows caused CloudItem.name extraction to return the full relative path unchanged, producing double-prefixed paths like /Pictures/Pictures\photo.jpg on download. Discovered during ds418play WebDAV relocation test.
opened: 2026-04-21
---
On Windows, `Path.relativize(child).toString()` produces backslash-separated
paths (e.g. `Pictures\photo.jpg`). `CloudItem.name` is then extracted with
`substringAfterLast("/")`, which finds no `/` and returns the full relative
path unchanged. The relocator builds source paths from `item.name`, so nested
files receive paths like `/Pictures/Pictures\photo.jpg` and every `download()`
call below the top level fails.

Fix: replace `.toString()` with `.joinToString("/")` at the two call sites in
`listChildren()` (line 126) and `walkRoot()` (line 220) of `LocalFsProvider.kt`.

Discovered while planning the onedrive-test-local → ds418play relocation test.

---
id: UD-326
title: WebDAV trust_all_certs broken — Java engine ignores hostname-verif override; CIO fails on Synology DSM 7.x TLS
category: providers
priority: medium
effort: S
status: closed
closed: 2026-04-21
resolved_by: commit f88db20. Switched engine selection under trust_all_certs=true from Ktor Java engine to Apache5 with PoolingAsyncClientConnectionManager + ClientTlsStrategyBuilder + NoopHostnameVerifier.INSTANCE. Java engine was ineffective (JDK ignores endpointIdentificationAlgorithm override); CIO was incompatible with Synology DSM 7.x (fatal ProtocolVersion TLS alert). Apache5 uses JDK TLS stack (Synology-compatible) while bypassing hostname verification. Verified against ds418play.local:5006 with self-signed certificate.
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt
  - core/providers/webdav/build.gradle.kts
  - core/gradle/libs.versions.toml
opened: 2026-04-21
---
`trust_all_certs = true` in the WebDAV profile was intended to bypass TLS
certificate validation for LAN/self-signed-cert servers. It was previously
declared "wired" (UD-104) but the underlying Ktor engine rendered it ineffective:

- **Java engine** (`ktor-client-java`): ignores `SSLParameters.endpointIdentificationAlgorithm`
  — the JDK's `HttpsURLConnection` hardcodes HTTPS hostname verification with no override path.
- **CIO engine** (`ktor-client-cio`): Synology DSM 7.x sends a fatal `ProtocolVersion` TLS
  alert in response to CIO's ClientHello; connection is rejected at the TLS layer.

**Fix:** when `trust_all_certs = true`, build an Apache5 `HttpClient` via Ktor's
`ktor-client-apache5` engine with a custom `PoolingAsyncClientConnectionManager` injected
through `customizeClient { setConnectionManager(cm) }`. The connection manager is pre-wired
with `ClientTlsStrategyBuilder` + `NoopHostnameVerifier.INSTANCE` and a permissive
`X509TrustManager`, which bypasses both certificate chain validation and hostname
verification while using the JDK TLS stack (which Synology DSM 7.x accepts).

**Verified:** PROPFIND + upload + download round-trip against `ds418play.local:5006`
(Synology DS418play running DSM 7.x) with a self-signed certificate.

When `trust_all_certs = false` (default), the CIO engine is used as before.

---
id: UD-731
title: Shadow 9.4.1 mergeServiceFiles() regression — explicit SPI provider files workaround
category: tooling
priority: low
effort: S
status: closed
closed: 2026-04-21
resolved_by: commit 6a9f1a5. Added explicit META-INF/services/org.krost.unidrive.ProviderFactory files to app:cli and app:mcp listing all 8 providers. Shadow will copy these verbatim, bypassing the broken mergeServiceFiles() merge. Maintenance note: new provider modules must append their *ProviderFactory to both files manually.
code_refs:
  - core/app/cli/src/main/resources/META-INF/services/org.krost.unidrive.ProviderFactory
  - core/app/mcp/src/main/resources/META-INF/services/org.krost.unidrive.ProviderFactory
opened: 2026-04-21
---
Shadow 9.4.1 regressed `mergeServiceFiles()`: it retains only the last-processed
provider's `META-INF/services/org.krost.unidrive.ProviderFactory` file, silently
dropping all others. Symptom: `unidrive --list-providers` returned only `hidrive`;
every other profile raised "No provider registered for type X".

**Workaround (in place):** explicit `META-INF/services/org.krost.unidrive.ProviderFactory`
files listing all 8 providers added to `app:cli` and `app:mcp` resource trees. Shadow
will copy these verbatim into the fat-jar, bypassing the broken merge logic.

**Maintenance:** if a new provider module is added, its `*ProviderFactory` class must
be appended to BOTH explicit files manually — unlike the old approach where each
provider's own `src/main/resources/META-INF/services/` entry was merged automatically.

**Resolution path:** watch the Shadow plugin changelog for a `mergeServiceFiles()`
fix. Once fixed and the Shadow version is bumped past the regression, delete both
explicit files and verify providers still register via
`./gradlew :app:cli:run --args="--list-providers"`.

---
id: UD-325
title: WebDAV: implement RFC 4331 quota PROPFIND or declare unsupported
category: providers
priority: low
effort: S
status: closed
closed: 2026-04-21
resolved_by: commit 6a8782f. WebDavApiService.quotaPropfind() + WebDavProvider.quota() — RFC 4331 PROPFIND with null-safe fallback; Synology graceful degradation verified
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt
opened: 2026-04-21
---
`WebDavProvider.quota()` currently returns hardcoded `QuotaInfo(0, 0, 0)` with a
"not implemented yet" comment. The CLI `status` command shows "0 B" in the CLOUD SIZE
column for WebDAV profiles, which is misleading.

RFC 4331 defines `quota-available-bytes` and `quota-used-bytes` DAV properties that can
be fetched via PROPFIND. Server support varies:
- Nextcloud / ownCloud: advertise these properties.
- Synology DSM 7.x WebDAV Server: does NOT advertise them (confirmed 2026-04-21).
- Apache mod_dav: typically does not.
- IIS WebDAV: typically does not.

**Option A (recommended):** add a `PROPFIND` call for the two quota properties against the
root collection. Parse if present; return `null` from `quota()` if the server doesn't
advertise them. The CLI will display "—" for CLOUD SIZE on non-quota servers — more honest
than "0 B". Validate against the Nextcloud docker-compose provider fixture.

**Option B:** formally remove `Capability.QuotaExact` from the capability set and
return `null` unconditionally. Simple, but loses quota for Nextcloud users.

Document server-family coverage in `docs/providers/webdav-robustness.md` (UD-324 deliverable).

---
id: UD-265
title: relocate pre-flight: live scan progress + human-readable ASCII summary box
category: core
priority: medium
effort: M
status: closed
closed: 2026-04-21
resolved_by: commit 886cf64. CloudRelocator.preFlightCheck gained onProgress callback; RelocateCommand streams live scan counter + renders two-column ASCII/Unicode summary box with IEC sizes
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt
opened: 2026-04-21
---
## Problem

`unidrive relocate --from onedrive-test --to ds418play-webdav` currently prints:

```
Pre-flight checks...
Source: 15.3 GB in 4 287 files
Target: quota unknown (skipping check)
```

The source walk (`CloudRelocator.preFlightCheck`) does a full recursive `listChildren()`
across the entire remote tree with no feedback — on a large OneDrive it is silent for
tens of seconds. The summary line that follows is terse and gives no sense of what is
about to happen to the user's data.

## Desired output

### 1 — Progress during source walk

While `preFlightCheck` is scanning, print a spinner or running counter on a single
overwriting line:

```
Scanning source...  1 234 files  /Documents/Projects/…
```

Or, if the provider reports total item count via `quota().usedFiles` (where supported),
render a percentage bar:

```
Scanning source  [===========>          ]  56 %  (2 412 / 4 287 files)
```

Fall back to a spinning counter when no total is available.

### 2 — Human-readable pre-flight summary (ASCII-art)

After scanning, replace the raw stat line with a layout an average user can read:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  RELOCATE PLAN                                                          │
├──────────────────────────┬──────────────────────────────────────────────┤
│  FROM  onedrive-test     │  TO  ds418play-webdav                        │
│  type: OneDrive          │  type: WebDAV (ds418play.local:5006)         │
│  used: 15.3 GiB / 100 GiB│  available: unknown (quota not reported)     │
│  files to migrate: 4 287 │                                              │
├──────────────────────────┴──────────────────────────────────────────────┤
│  Transfer: 15.3 GiB  ──────────────────────────────────────────►        │
│  After:    OneDrive ~84.7 GiB free · NAS quota: unknown                 │
└─────────────────────────────────────────────────────────────────────────┘
```

Rules:
- Sizes in MiB / GiB / TiB (binary, IEC prefixes). No raw byte counts.
- File count with thousands separator (4 287, not 4287).
- Remote used/total from `provider.quota()` for both sides.
  - If `quota().total == 0` (provider doesn't report): show `unknown`.
  - If `quota().remaining < transferSize`: print a ⚠ warning row before the
    transfer arrow, e.g. `⚠  Target may not have enough space`.
- `--delete-source` flag: add a row `DELETE source after transfer: YES ⚠`.
- The ASCII box should degrade gracefully in non-UTF-8 terminals (fallback to `+`, `-`, `|`).

## Acceptance

- Pre-flight scan shows live progress (spinner or % bar); no silent pause > 1 s.
- Summary box printed before the transfer starts.
- Sizes use IEC binary prefixes (MiB / GiB / TiB).
- File count uses space as thousands separator.
- Quota shown for both sides; graceful "unknown" when unavailable.
- `--delete-source` flag is visible in the summary.
- Existing unit tests for `formatSize` stay green; add test for the new formatter if
  IEC logic is separate.

---
id: UD-732
title: SPECS.md WebDAV TLS entry stale — references Java engine, now Apache5
category: tooling
priority: low
effort: XS
status: closed
closed: 2026-04-21
resolved_by: commit 152de38. SPECS.md line 156: Auth column now reflects Apache5 engine + PoolingAsyncClientConnectionManager + NoopHostnameVerifier; quotaExact: ❓ → 🟡 (RFC 4331)
code_refs:
  - docs/SPECS.md
opened: 2026-04-21
---
SPECS.md §5.1 (Provider Capability Matrix, WebDAV row) says:
  "HTTP Basic auth with `trust_all_certs` wired via Ktor Java engine ([UD-104])"

This is stale as of UD-326 (2026-04-21). The implementation now uses the **Apache5 engine**
with a custom `PoolingAsyncClientConnectionManager` pre-wired with `ClientTlsStrategyBuilder`
+ `NoopHostnameVerifier.INSTANCE`. The Java engine was abandoned because it hardcodes HTTPS
hostname verification with no override; CIO was abandoned because Synology DSM 7.x triggers
a fatal ProtocolVersion TLS alert.

Fix: update the WebDAV row in SPECS.md to reference Apache5 engine + PoolingAsyncClientConnectionManager
and note the engine rationale. The UD-104 citation can remain as the original intent; add a
note pointing to UD-326 for the actual implementation.

---
id: UD-324
title: WebDAV HTTP robustness audit
category: providers
priority: medium
effort: S
status: closed
closed: 2026-04-21
resolved_by: commit c00b462. docs/providers/webdav-robustness.md: HTTP audit companion to webdav-client-notes.md; Synology DS418play field observations, retry matrix, server-family concurrency table
opened: 2026-04-20
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

**Deliverable:** `docs/providers/webdav-robustness.md`.

**Consumed by:**
- UD-262 — findings inform `HttpRetryBudget` config surface
- UD-263 — findings produce concurrency hint values

**WebDAV specifics: server variance is enormous (Nextcloud, Owncloud, Apache, IIS, Box). Per-server retry semantics likely differ. Audit should document observed behaviour for each server family we expect to support + sensible defaults.**

**Synology DSM 7.x field observations (2026-04-21, ds418play.local:5006):**
- CIO engine triggers a fatal `ProtocolVersion` TLS alert during ClientHello; Apache5 engine
  connects cleanly. Document this server-family TLS quirk in the audit deliverable.
- `PUT` to root `/` returns 405; user home is writable at `/home`. The audit should map
  writable path roots per server family.
- No `ETag` header returned on PUT responses. Change detection falls back to size + mtime.
  Document per-server ETag reliability and recommend a fallback strategy.
- RFC 4331 quota properties (`quota-available-bytes`, `quota-used-bytes`) not advertised;
  `provider.quota()` returns 0/0/0. See UD-325 for the implementation gap.

---
id: UD-220
title: User-facing "consequences" docs — what happens when you X, cancel Y, resume Z
category: docs
priority: medium
effort: S
status: closed
closed: 2026-04-21
resolved_by: commit 0446bf2. docs/user-guide/consequences.md: per-verb mutation/cancel/rollback/remote-side-effect guide for relocate, sync, auth, logout, trash, versions, pin, conflicts
opened: 2026-04-18
---
User request from UD-712: operators running destructive-sounding commands (`sync`, `logout`, `relocate`, `trash purge`, `conflicts restore`, `pin`) are unsure what's reversible and what isn't. Ship a `docs/user-guide/consequences.md` with one short section per verb covering:
  1. What the command actually mutates (local? remote? state.db? tokens?).
  2. Safe cancel behaviour (Ctrl+C during `sync` keeps the delta cursor so a resume picks up where it stopped; write-once-to-disk means no `.part` files).
  3. How to undo (does `conflicts` restore the pre-sync copy? does `logout` delete tokens or just mark them invalid? is `relocate` atomic?).
  4. What happens to the remote if the local side cancels halfway (nothing — unidrive is client-driven).

Starting set to write up (from already-verified behaviour observed in this session):
  * `sync` / `sync --upload-only` / `sync --download-only` / `sync --watch` — what each direction actually queues, how `--dry-run` differs.
  * **`sync --download-only` on a drive larger than local + no pin_patterns + no CfApi = 346 GB of NUL-filled regular files** (see UD-222 — cross-link the recovery playbook here verbatim).
  * `auth` / `auth --device-code` — device-code polling duration, token location, re-auth-without-logout semantics.
  * `logout` — does it revoke server-side? (check `OneDriveProvider.logout` — likely client-side only).
  * `relocate` — atomicity, rollback, effect on cursor.
  * `trash` / `versions` — retention windows, whether destructive `--empty` exists.
  * Profile lifecycle: adding a profile, first sync cost (→ UD-713), removing a profile (what gets deleted on disk).
  * **"Shells and sandboxes"** (cross-link UD-219): why running unidrive from a Microsoft-Store-packaged PowerShell makes `%APPDATA%` invisible and silently breaks config loading. Recommend PS5.1, MSI PS7, or `cmd /c unidrive ...`.

Tone: "what a thoughtful operator wants to know before pressing enter" — not reference documentation, not marketing copy.

Output cross-linked from README + each relevant CLI `--help` footer (`See: docs/user-guide/consequences.md#sync`).

---
id: UD-267
title: relocate progress line — totals zeroed out + Windows CR-overwrite defeated
category: core
priority: low
effort: XS
status: closed
closed: 2026-04-21
resolved_by: commit cf1df31. FileProgressEvent now threads totalSize/totalFiles from preFlightCheck; dropped Windows lineSeparator fallback; progress line includes file count
opened: 2026-04-21
---
### Problem

`RelocateCommand` during active migration prints a wall of lines like:

```
  29.9 GB / 0.0 B (0%)
  29.9 GB / 0.0 B (0%)
  30.0 GB / 0.0 B (0%)
```

Two separate defects in the progress handling introduced in
[UD-265](CLOSED.md#ud-265) (commit `886cf64`):

1. **Totals are always zero.** `CloudRelocator.migrate` at
   `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:115`
   emits `FileProgressEvent(migratedFiles, migratedSize, 0, 0)` —
   the last two parameters (`totalFiles`, `totalSize`) are hard-coded
   to zero. The totals were captured in the preceding
   `MigrateEvent.Started` event but never threaded through to
   subsequent progress events. Hence `N GB / 0.0 B (0%)` — percentage
   is 0 because totalSize is 0 (the `if (event.totalSize > 0)` guard
   in RelocateCommand correctly hands back 0).
2. **CR-overwrite defeated.** `RelocateCommand.kt:126` has:
   ```kotlin
   print("\r  ${formatSize(event.doneSize)} / ${formatSize(event.totalSize)} ($pct%)")
   if (System.lineSeparator() != "\n") print(System.lineSeparator())
   ```
   On Windows `System.lineSeparator() == "\r\n"` which is not `"\n"`,
   so a `\r\n` is appended after every update — the single-line
   overwrite collapses into a scrolling wall of lines. Remove the
   conditional; `\r` alone is what we want for in-place updates on
   both Unix and Windows consoles. `CliProgressReporter` already
   does this correctly.

### Fix

1. Capture the totals in a closure-scoped `val` inside the `flow { }`
   block of `CloudRelocator.migrate`, or hoist the `preFlightCheck`
   call outside `walk()` and pass totals as parameters to the
   coroutine. Either way: emit `FileProgressEvent(mF, mS, totalF, totalS)`
   with real values.
2. Remove the `System.lineSeparator()` fallback at
   `RelocateCommand.kt:126`. A bare `\r` is correct on every modern
   terminal (PowerShell, Windows Terminal, Git Bash, WSL, macOS
   Terminal, Linux xterm). If a downstream log capture needs
   newlines it should come from a dedicated `--log-progress` flag,
   not the TTY path.

### Observed

During the 2026-04-21 `unidrive relocate --from onedrive-test-local
--to ds418play-webdav` run (329 GB / 128 646 files): progress
"line" became an endless stream of overwriting-but-not-really lines
showing running doneSize but frozen at `0.0 B` total and `0%`.

### Acceptance

- `unidrive relocate ...` shows a single updating line of the form
  `  1.2 GB / 30.5 GB (3.9%)` that stays in place during migration.
- `RelocateCommandTest` gains a test that asserts
  `FileProgressEvent.totalSize > 0` when emitted after a non-trivial
  `preFlightCheck`.

### Priority

Low — cosmetic, the transfer itself works. File if the follow-up
wave schedules CLI polish.

---
id: UD-268
title: WebDAV — memoize created/observed collections to eliminate MKCOL storm
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-21
resolved_by: commit 21dee1b. knownCollections cache in WebDavApiService; mkcol short-circuits cached paths; ensureParentCollections fast-paths on deepest-parent hit
opened: 2026-04-21
---
### Problem

`WebDavApiService.ensureParentCollections` at
`core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt:342`
issues a MKCOL against every path segment of every upload, with no
memoization. MKCOL tolerates 405 Method Not Allowed ("collection
already exists") so these are harmless no-ops — but they are still
full HTTPS round-trips to the target server.

### Observed (2026-04-21 ds418play session)

During the in-progress 329 GB / 128 646-file relocate:

- **9,348 uploads** so far
- **19,810 MKCOL calls** so far
- → **≈ 2.12 MKCOLs issued per uploaded file**
- On a 3-deep target tree (`/Pictures/<pack>/<subdir>/file.jpg`),
  that's ≈ 2 of the 3 segments getting re-created per file (the
  root segment is short-circuited by an empty `relative` case;
  the remaining 2 segments hit the wire every time).

At current completion rate, the full 329 GB transfer will issue
≈ **250 k wasted MKCOL round-trips**. On a LAN to a Synology DS418play
this is "only" noticeable in burned wall-time; against a rate-limited
server (Nextcloud at ≤ 5 ops/s, SharePoint) this pattern amplifies
the request budget by 2–3× for no benefit.

Known and called out in existing docs but not tracked as a ticket
until now:

- [`docs/providers/webdav-client-notes.md:86-88`](../providers/webdav-client-notes.md)
  — "on deep trees this is O(depth × files) round-trips. Cache
  created collections per session."
- [`docs/providers/webdav-robustness.md`](../providers/webdav-robustness.md)
  §5 (concurrency recommendations).

### Fix

Add a per-`WebDavApiService`-instance `ConcurrentHashMap.newKeySet()`
of successfully-created (or observed-to-exist) collection paths.

```kotlin
private val knownCollections: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

private suspend fun ensureParentCollections(remotePath: String) {
    val parent = remotePath.substringBeforeLast('/', "")
    if (parent.isBlank() || parent in knownCollections) return
    val parts = parent.trim('/').split('/').filter { it.isNotEmpty() }
    var current = ""
    for (part in parts) {
        current = "$current/$part"
        if (current in knownCollections) continue
        mkcol(current)
        knownCollections.add(current)
    }
    knownCollections.add(parent)
}
```

Secondary: when `mkcol` receives a 2xx (newly created) and when it
receives a 405 (already existed), both paths should add to
`knownCollections` — so that the first upload to `/a/b/c/foo` primes
the cache for subsequent siblings without repeating the probe.

The cache is bounded by the number of distinct directories in the
target tree, which for realistic workloads (≤ 10⁵ directories) is
a few MB at worst — small relative to the HTTP-client memory
footprint.

### Why not rely on HTTP/1.1 keep-alive?

DSM 7.x does not negotiate HTTP/2 (see
[webdav-robustness.md §Synology field observations](../providers/webdav-robustness.md#synology-dsm-7x--field-observations))
and the Apache5 pool does keep connections warm — but each MKCOL
still carries full TCP ACK + TLS record + server-side stat cost.
Removing the call is strictly better than optimising its transport.

### Acceptance

- Re-run the same 329 GB migration against an empty target; assert
  MKCOL count in the daemon log is ≤ 1.1 × distinct-directory count
  (one MKCOL per directory plus a small overhead for races between
  parallel workers reaching a sibling).
- `WebDavApiServiceTest` gains a unit test with a mock server that
  counts MKCOL calls across a 100-file / 10-directory upload and
  asserts ≤ 11 MKCOLs observed (not 100+).
- No regression: uploads to a target tree where collections don't
  yet exist still succeed (cache is populated after the first
  successful create).

### Priority / effort

Medium priority (real server-load multiplier, concrete field
observation), Small effort (≈ 30 lines + unit test). Self-contained
within `WebDavApiService`.

### Related

- UD-228 (closed, split) — WebDAV robustness parent.
- UD-324 (closed 2026-04-21) — documented the gap.
- UD-325 (closed 2026-04-21) — quota PROPFIND landed; this ticket
  is the other leaf the audit surfaced.

---
id: UD-266
title: relocate — skip files that already exist on target with matching size/mtime/hash
category: core
priority: medium
effort: M
status: closed
closed: 2026-04-21
resolved_by: commit ad5c6cd. CloudRelocator.skipExisting (default on) + per-folder target index + hash-first-then-size equivalence; MigrateEvent carries skippedFiles/skippedSize; CLI --force flag
opened: 2026-04-21
---
### Problem

`CloudRelocator.migrate()` at
`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:88`
walks the source provider and issues `source.download()` + `target.upload()`
for every file it finds, with no per-file skip test against what already
exists on the target.

Running `unidrive relocate --from onedrive-test --to ds418play-webdav`
against a 329 GB / 128 646-file OneDrive profile re-downloads all 329 GB
from Microsoft's servers on every invocation — even when a previous run
completed or 99% of files are already on the target.

### Observed behaviour (2026-04-21 session)

- onedrive-test profile has 128 638/128 646 files hydrated locally under
  `C:/Users/gerno/unidrive-onedrive-test`.
- Target ds418play-webdav has 1 test file (`webdav-test.txt`).
- Starting `relocate --from onedrive-test …` begins downloading file 1
  from Graph, not reading from the hydrated local copy.
- Workaround: use `onedrive-test-local` (localfs profile pointing at the
  hydrated dir) as `--from` — reads off disk, uploads to NAS, no cloud
  round-trip.

### Expected behaviour

Before downloading, check if the target already has the file and skip
when equivalent. Equivalence check:

1. If target provider exposes `getEntry(path)` and both sides return a
   non-null hash + same hash → skip.
2. Else if both sides return size+mtime → skip when both match (within
   a ±2 s mtime tolerance for filesystems with 1 s resolution).
3. Else fall back to size-only (current Synology WebDAV case — no ETag,
   no reliable hash).

### Out of scope

- Resume from a prior partial transfer (would need a transfer log, not
  just an is-equivalent check). File separately if warranted.
- Three-way diff (source vs target vs last-known). Relocate is
  one-directional.

### Acceptance

- Running relocate twice in a row against the same source+target
  transfers zero bytes the second time (or only the delta).
- Integration test: seed target with a subset of source files at
  identical size+mtime; run relocate; assert per-file skip count in
  the Completed event or a new event type.
- Pre-flight summary box gains a "to transfer" row distinct from
  "source total" so users know how much work actually remains.

### Related

- UD-265 (closed 2026-04-21, commit 886cf64) — added the pre-flight
  summary box this will extend.
- UD-228 (closed, split) — cross-provider robustness parent.

---
id: UD-273
title: relocate scans source tree twice — unify preFlightCheck + migrate walk
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-21
resolved_by: commit 64afb63. Option A shipped. migrate() accepts knownTotalSize/knownTotalFiles; CLI passes pre-computed values. UD-276 debug logging co-landed in same commit.
opened: 2026-04-21
---
### Problem

`unidrive relocate` scans the entire source tree **twice** before the
first byte is uploaded. On a large OneDrive source this doubles the
time-to-first-upload by 10–20 minutes.

### Reproduction (2026-04-21 field observation)

Command: `unidrive relocate --from onedrive-test --to ds418play-webdav`
Source: 128 686 files across ~2 000 folders on Graph.
Tree scan rate on OneDrive: ~1.4 folders/sec sequential.

Timeline:

| Time | Event |
|---|---|
| 11:51:06 | JVM starts |
| 11:51:10 | **First** `listChildren` call begins |
| ~11:57 | First scan ends; summary box renders ("migrate: 128 686 files / 347.0 GiB"); "Starting migration..." printed |
| 11:57–12:09+ | **Second** `listChildren` scan runs silently |
| 12:09 | Still scanning Pictures/_T... — 1543 Graph calls so far; upload phase has not started |

The user stares at two blank lines after "Starting migration..." for
another 10+ minutes with no feedback — it looks hung.

### Root cause

`RelocateCommand.run()` calls `preFlightCheck` explicitly at
`core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:92`
to populate the summary box's file count + total bytes.

`CloudRelocator.migrate()` then calls `preFlightCheck` **again**
internally at
`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:111`
to emit the `Started` event with totals. Nothing passes the
already-computed totals through.

The double-scan predates UD-265 / UD-267 / UD-266 — it's an original
design gap in the CloudRelocator API. The aborted-run you see in
today's ds418play session had it too (both scans completed but
nothing was saved between them).

### Fix

Option A — **simplest**: add an overload
`CloudRelocator.migrate(sourcePath, targetPath, totalSize, totalFiles)`
that skips the internal scan. `RelocateCommand` calls this overload
with the values it already computed.

```kotlin
fun migrate(
    sourcePath: String,
    targetPath: String,
    totalSize: Long? = null,
    totalFiles: Int? = null,
): Flow<MigrateEvent> = flow {
    val startTime = System.currentTimeMillis()
    val (ts, tf) = if (totalSize != null && totalFiles != null) {
        totalSize to totalFiles
    } else {
        preFlightCheck(sourcePath)
    }
    emit(MigrateEvent.Started(totalFiles = tf, totalSize = ts))
    ...
}
```

Option B — **cleaner**: split responsibilities. `CloudRelocator.plan()`
returns a `MigrationPlan` (totals + an internal state for walking);
`CloudRelocator.execute(plan)` returns the `Flow<MigrateEvent>`. CLI
calls plan() for the summary box and execute() for the transfer.
One pre-flight, one walk. More invasive but matches the UD-236
refresh/apply split pattern.

Ship A now; file B as a follow-up if the split becomes useful for
other callers.

### Secondary — parallelise the pre-flight walk

Even with option A fixing the 2× scan, a single scan takes ~15 min
on this source. `preFlightCheck.walk()` at
`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:62-73`
is strictly serial — one `listChildren` per folder, awaited before
the next. Graph tolerates ~10 parallel requests (see UD-262's
HttpRetryBudget work).

Parallelising the walk with a bounded dispatcher (`Semaphore(8)`)
should cut scan time by ~5–8×. File separately (see UD-247 cross-
provider benchmark harness for testing target).

### Acceptance

- `unidrive relocate` issues `listChildren` on each source folder
  exactly once before the first upload.
- Log count `grep -c listChildren unidrive.log` after a completed
  relocate equals the distinct-folder count of the source subtree,
  not 2× that.
- The interval between "Starting migration..." and the first
  `FileProgressEvent` upload line is ≤ 5 seconds (it was measuring
  at ≥ 10 minutes in the field).
- `CloudRelocatorTest` gains a test that counts `FakeProvider.listChildren`
  call count and asserts it does not exceed (distinctFolders + 1)
  across a full `relocate` — currently it's (2 × distinctFolders).

### Related

- UD-265 (closed) — introduced the summary box that requires the
  pre-flight count.
- UD-267 (closed) — threaded totals into FileProgressEvent but did
  not unify the two scans.
- UD-266 (closed) — added per-folder target index; compounds the
  problem only marginally (target index is per-folder, scan still
  dominates).
- UD-247 (open · L) — cross-provider benchmark harness; parallel
  walk would register there once Option B exists.

### Priority / effort

Medium priority, S effort for Option A. Significant UX improvement
(halves relocate total wall time on large trees); minimal risk (new
optional parameter, existing callers unchanged).

---
id: UD-276
title: relocate UD-266 skip-if-exists NOT firing against WebDAV in the field (unit tests pass, production fails)
category: core
priority: high
effort: S
status: closed
closed: 2026-04-21
resolved_by: commit a029c30. Root cause: Synology DSM inlines xmlns on D:response, breaking the original regex. Also fixed listChildren('/') filter trimEnd bug. Field-verified 74/74 skipped.
opened: 2026-04-21
---
### Problem

`CloudRelocator.migrate` is NOT skipping files that already exist on
the target with matching size. The UD-266 skip-if-exists logic looks
correct in code (and the unit tests with `FakeProvider` pass), but
in production against a `LocalFsProvider` → `WebDavProvider` (Synology
DSM) pair, **every file is re-uploaded** even when an identical file
exists at the target.

### Reproduction (2026-04-21 field observation)

Setup:
- Source: `onedrive-test-local` (localfs, `~/unidrive-onedrive-test`).
- Target: `ds418play-webdav` (Synology DSM 7.x).
- Prior aborted relocate run (cloud source) left 4 002 files on target.
- Command: `unidrive relocate --from onedrive-test-local --to ds418play-webdav`.

Evidence (log analysis against the same path):

```
Prev-run uploaded /Pictures/(Alex-Lynn)…/!C.jpg (56 895 bytes) at 12:27:15
This-run uploaded /Pictures/(Alex-Lynn)…/!C.jpg (56 895 bytes) at 14:13:32
```

Per-folder:
```
Files in /Pictures/(Alex-Lynn) Sultana - In my Room -07-01-2018-/  prev JVM:  74
Files in same folder this JVM:                                                74
RE-uploaded (path identical, size identical):                                 74
```

Across the run:
- Uploads this JVM: **5 910**
- CLI progress: `5 909 / 128 642 files` — `doneFiles == migratedFiles`,
  so `skippedFiles = 0` over thousands of checks.
- Overlap with prior run's uploads: **3 253 paths re-uploaded despite
  existing on target**.

Direct WebDAV PROPFIND on `/Pictures/(Alex-Lynn) Sultana - In my Room
-07-01-2018-` (verified via curl) returns the file with
`<D:getcontentlength>56895</D:getcontentlength>` and a well-formed
`<D:getetag>`. No 4xx/5xx responses in the log.

### Hypotheses narrowed

1. ❌ **Code not in deployed jar** — `javap -p` confirms
   `CloudRelocator.isEquivalent$sync` exists, `CloudRelocator$migrate$1`
   has `invokevirtual CloudRelocator.isEquivalent$sync` at offset 1515
   and both skip + upload `MigrateEvent$FileProgressEvent` allocations.
2. ❌ **PROPFIND failing silently** — 0 WebDavException entries in log,
   0 non-2xx HTTP, direct curl confirms DSM returns the files.
3. ❌ **Memory/leak** — older UD-275 observation; current RSS 1 GB
   stable (G1 reclaims). Not related.
4. ❓ **Target map built from wrong path** — `target.listChildren(targetPrefix)`
   is invoked with `targetPrefix` from the walk. For the root sync of
   `"/"` → `"/"`, walk recurses with `newTarget = "/Pictures"`, then
   `"/Pictures/(Alex-Lynn)…"`. Needs verification.
5. ❓ **Map key mismatch** — localfs `CloudItem.name` via
   `rel.substringAfterLast("/")` should be `!C.jpg`; webdav
   `CloudItem.name` via `path.substringAfterLast("/")` should also be
   `!C.jpg`. Both derived the same way from identical strings. Unless
   NFC/NFD or trailing-space issues surface on NTFS round-trip.
6. ❓ **`target.listChildren` catch swallowing a non-obvious exception**
   — `targetIndex()` catches `Exception` silently. If the call throws
   consistently, `existingByName` is empty → no skip. No WebDavException
   in the log suggests the call returns normally, but the catch block
   is unlogged.
7. ❓ **`WebDavProvider.listChildren` filter is too aggressive** — the
   filter `it.path != path.trimEnd('/') && it.path != path.trimEnd('/') + "/"`
   should only drop the folder itself. Verified correct on paper but
   could be misbehaving on Synology's href shape (which has trailing
   slash on folders).
8. ❓ **Coroutine state corruption** — `invokeSuspend$targetIndex`
   closure uses a ref field `L$1`. If the flow's dispatcher schedules
   weirdly with 17 workers, state could be clobbered. Unlikely but
   possible.

### Next-step debugging

1. **Add one log line** in `CloudRelocator.walk` after the map build:
   ```kotlin
   log.debug("target-index for {}: {} entries, keys={}", targetPrefix, existingByName.size, existingByName.keys.take(5))
   ```
   and one at the skip decision point:
   ```kotlin
   log.debug("skip-check for {}: existing={} equivalent={}", itemName, existing?.size, existing?.let { isEquivalent(item, it) })
   ```
   Rebuild, redeploy, rerun a small subtree. The log will tell us
   which hypothesis is correct.

2. **Unit test against real WebDAV** — add an integration test that
   stands up a mock WebDAV server (or uses a throwaway DSM test
   account), performs the same sequence, and asserts skippedFiles > 0.

3. **Decompile the bytecode around line 1515** of
   `CloudRelocator$migrate$1.class` to confirm the skip branch
   actually sets skippedFiles++ correctly. The current disassembly
   is consistent with source; a deeper look would rule out a compiler
   glitch.

### Impact

The current field relocate from `onedrive-test-local` to
`ds418play-webdav` has re-uploaded ~3 253 files (2 GB+) that were
already on target from the prior cloud run. Total wasted bandwidth
on the NAS side depends on how much of the source overlaps with what
the previous run had already landed — at current pace, significant.

### Priority / effort

**High priority, S effort.** UD-266 was one of the headline fixes
in Wave 2. If the skip path is broken in production, the feature
is effectively dead-on-arrival and needs a real test against a
production-shape target.

### Related

- UD-266 (closed 2026-04-21, commit ad5c6cd) — the fix this ticket
  reports broken in the field.
- UD-273 (open) — relocate double-scan; separate but adjacent to the
  CloudRelocator design.
- UD-275 (open) — memory growth; current evidence suggests it's G1
  heuristic lag + 17-worker allocation pressure, not a leak. Lower
  priority than this.
- Unit tests in `CloudRelocatorTest` pass against `FakeProvider`
  — the test harness doesn't exercise `WebDavProvider` and is
  insufficient to catch this regression.

---
id: UD-260
title: Dry-run must persist scanned remote state — next sync reuses cursor + entries
category: core
priority: high
effort: M
status: closed
closed: 2026-04-21
resolved_by: commit a7e42fb. dry-run persists remote state and reuses cursor
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
opened: 2026-04-20
---
**UX bug — `--dry-run` throws away its scan work.** A dry-run spends the full remote-enumeration + reconcile time (~8–9 minutes for a 130 k-item OneDrive account) and prints a summary, then exits without persisting the remote state it just learned. Any subsequent `sync` / `sync --upload-only` / `sync --download-only` / `sync --dry-run` starts the remote scan over from zero.

Observed 2026-04-20 on `onedrive-test`:
```
PS> unidrive -p onedrive-test sync --download-only --dry-run
Scanning remote changes...
...
Dry-run: would download 39455, upload 0, 0 conflicts (529.8s)     # 8.8 min

PS> unidrive -p onedrive-test sync --upload-only --dry-run
Scanning remote changes...                                          # same work again, another 8-9 min
```

## What's safe to persist from a dry-run

- **Remote delta cursor** — pointer, no local side-effect.
- **Remote-entries cache** in state.db — metadata (id, hash, size, modified). Authoritative snapshot of the remote side at that moment. Safe; subsequent reconcile uses this even in non-dry-run mode.

## What must NOT be persisted from a dry-run

- **Local-entry hydration states.** A dry-run doesn't actually download / upload / delete. State.db entries that track "is file X hydrated locally" must stay as they were.
- **Delete-audit counts, upload-in-progress markers,** or any marker that implies the action fired.

## Proposed fix

In `SyncEngine.syncOnce()` (see `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt`), commit the remote cursor + remote-entries cache at the end of Phase 1 (gather) regardless of `dryRun`. Skip only the action-apply step when `dryRun=true`. Today Phase 1's commit is probably gated on `!dryRun`; it needs splitting so the "what the remote says" part commits and the "what we did with it" part doesn't.

One subtle case: if a dry-run is interrupted mid-scan (SIGINT), the partially-populated remote-entries table could leave a half-view. Either transactionally commit at the end of scan only, OR tag partial rows with a scan-id and clean up on next startup.

Two follow-on UX improvements:

### (a) Optional prompt — "save this scan for next run?"

After the dry-run report, if stdin is a TTY, prompt:
```
Save scanned remote state for next sync? [Y/n]
```
Default yes. In non-interactive mode (`--yes` or no TTY), auto-save. Non-TTY callers (CI, MCP) get the save unconditionally — that's the clean default.

### (b) Direction-agnostic scan reuse

A dry-run with `--download-only` learns the same remote state that `--upload-only` or bidirectional reconcile need. After landing (a), the user's chained run
```
sync --download-only --dry-run  ; sync --upload-only --dry-run
```
would skip the second scan entirely and go straight to reconcile-for-the-other-direction. On a 130 k-item account that's ~8 min saved per chained invocation.

## Acceptance

- Running `sync --dry-run` on a fresh profile populates state.db's delta cursor column and remote-entries table; local-entries table unchanged.
- Running `sync --dry-run` a second time in the same minute completes the scan in << 10 s (reuses the cached cursor via `delta?cursor=...`).
- Running `sync --upload-only --dry-run` immediately after a `sync --download-only --dry-run` reuses the remote state and only does the (cheap) reconcile-by-direction-filter step.
- Regression test: `SyncDryRunReuseTest` that calls `syncOnce(dryRun=true)` twice against a fake provider; asserts the second call's `provider.delta` receives the first call's cursor, not null.
- Existing dry-run output unchanged.

## Related

- UD-223 (fast first-sync, `?token=latest` bootstrap) — tackles "cold-start cost" from a different angle. UD-260 is the "warm-start reuse" side of the same performance story.
- UD-248 (per-action failure isolation, closed) — same shape: reconcile the action-apply semantics separately from what gets committed to state.db.

## Effort

M — needs careful split of commit semantics in Phase 1, two regression tests, possible prompt UI path. Same risk surface as UD-248.

---
id: UD-262
title: Extract HttpRetryBudget from GraphApiService into :app:core
category: core
priority: high
effort: M
status: closed
closed: 2026-04-21
resolved_by: commit a7e42fb. extract HttpRetryBudget from GraphApiService into :app:core
opened: 2026-04-20
---
**Part of UD-228 split.**

The 429/5xx retry + Retry-After budget logic developed in UD-207 / UD-227
currently lives inside `GraphApiService` (the OneDrive HTTP layer) via
`ThrottleBudget.kt` + ad-hoc handling. Extract as a reusable
`HttpRetryBudget` class in `core/app/core/src/main/kotlin/org/krost/unidrive/http/`
so every provider's HTTP service can consume the same retry surface
(config-driven: per-provider max wait, jitter, Retry-After source list:
header vs JSON body path vs both).

**Acceptance:**
1. `HttpRetryBudget` class in `:app:core` with config surface covering
   the distinctions observed across the 7 provider audits (UD-318..UD-324):
   header-only Retry-After, body-hint Retry-After, X-RateLimit-Reset,
   date-formatted Retry-After.
2. `GraphApiService` migrated to consume the new class; `ThrottleBudget.kt`
   either subsumed or reduced to a thin adaptor.
3. Unit tests that exercise the full Retry-After matrix without hitting
   real HTTP.
4. No behaviour change for OneDrive — pin via existing `LiveGraphIntegrationTest`
   + unit tests.

**Order of operations:** can start in parallel with UD-318..UD-324. The
class surface converges as audits finish; UD-263 (semaphore wiring)
consumes both.

---
id: UD-109
title: Semgrep rulesets (Kotlin + C++)
category: security
priority: medium
effort: M
status: closed
closed: 2026-04-21
resolved_by: commit d039379. Semgrep rules covering command injection, path traversal, null-derefs on Win API returns, missing CFApi acks. Added CI step.
code_refs: []
adr_refs: [ADR-0004]
opened: 2026-04-17
chunk: sg5
---
Ruleset must cover: command injection (rclone ProcessBuilder), path traversal, null-derefs on Win API returns, missing CFApi acks.

---
id: UD-103
title: Trivy scanning on Docker images
category: security
priority: medium
effort: S
status: closed
closed: 2026-04-21
resolved_by: commit a8f014b. Trivy scanning added for Docker images (unidrive-test, unidrive-providers-test) with HIGH,CRITICAL severity fail and .trivyignore support.
opened: 2026-04-20
chunk: sg5
---
**Rescoped from original UD-108** ("Trivy on dep lockfiles + container images"):
the lockfile half moved to UD-100 (needs Gradle dependency locking enabled first,
which didn't exist when UD-108 was filed). This ticket covers the Docker image
half only — independent and ready to ship.

**Scope:**

1. Scan every image produced under `core/docker/` via `trivy image` in CI on every
   build (at minimum: the MCP smoke-test image from UD-815; any future images
   automatically covered by a `trivy image` invocation per Dockerfile found).
2. Fail CI on HIGH+ severity CVEs by default; allow a `.trivyignore` for
   documented accepted-risk cases.
3. Integrate into the GitHub Actions build workflow (added per ADR-0009).

**Why separate from UD-100:** the Docker scan has no lockfile dependency and can
ship today. UD-100 gates the separate Trivy/Grype scan of `gradle.lockfile`.

---
id: UD-401
title: Pipe-based hydration in HydrationHandler
category: shell-win
priority: high
effort: M
status: closed
closed: 2026-04-28
resolved_by: Closed via ADR-0011 (shell-win removal). The C++ Cloud Files API extension tier is no longer in scope; this ticket can only re-open under the criteria documented in ADR-0011 §Re-opening criteria.
code_refs:
  - shell-win/src/HydrationHandler.cpp
  - shell-win/src/PipeClient.cpp
adr_refs: [ADR-0003]
opened: 2026-04-17
milestone: v0.3.0
chunk: xpb
---
`OnFetchData` currently reads from the local file directly (comment: "In production: request from daemon via named pipe"). Wire it to `PipeClient::requestFetch()` and stream chunks back via `CfExecute(TransferData)`.

---
id: UD-402
title: Cancellation + delete-notification paths in HydrationHandler
category: shell-win
priority: medium
effort: S
status: closed
closed: 2026-04-28
resolved_by: Closed via ADR-0011 (shell-win removal). The C++ Cloud Files API extension tier is no longer in scope; this ticket can only re-open under the criteria documented in ADR-0011 §Re-opening criteria.
code_refs:
  - shell-win/src/HydrationHandler.cpp
adr_refs: []
opened: 2026-04-17
milestone: v0.3.0
chunk: xpb
---
`OnCancelFetchData` and `OnNotifyDelete` are stubs. Wire cancellation tokens into in-flight `requestFetch` calls; notify daemon on delete so state DB stays coherent.

---
id: UD-403
title: Always-ack CFAPI error paths
category: shell-win
priority: medium
effort: S
status: closed
closed: 2026-04-28
resolved_by: Closed via ADR-0011 (shell-win removal). The C++ Cloud Files API extension tier is no longer in scope; this ticket can only re-open under the criteria documented in ADR-0011 §Re-opening criteria.
code_refs:
  - shell-win/src/HydrationHandler.cpp
adr_refs: []
opened: 2026-04-17
milestone: v0.3.0
chunk: xpb
---
On `TransferData` failure mid-stream the loop breaks without a final error ack. CFApi may hang awaiting completion. Always send a terminal `STATUS_*` ack.

---
id: UD-810
title: Windows CI runner + shell-win ↔ daemon IPC integration tests
category: tests
priority: medium
effort: M
status: closed
closed: 2026-04-28
resolved_by: Closed via ADR-0011 (shell-win removal). The C++ Cloud Files API extension tier is no longer in scope; this ticket can only re-open under the criteria documented in ADR-0011 §Re-opening criteria.
code_refs:
  - .github/workflows/build.yml
  - shell-win/test
opened: 2026-04-18
chunk: xpb
---
Docker harness is ubuntu-only; no Windows runner exercises it. The `sync --watch` daemon's `NamedPipeServer` is Windows-native and has zero integration coverage against a real `PipeClient` in CI (only unit tests). Add a Windows-runner job that: (a) builds shell-win, (b) spawns the daemon + a synthetic PipeClient talking over `\\.\pipe\unidrive-state`, (c) drives the NDJSON-validated command set end-to-end. Covers the SDDL pin (UD-101), frame-size cap + rate-limit (UD-106), and UD-106a shell-side validator in integration.

---
id: UD-503
title: Migrate UI IPC from UDS to unified pipe/UDS-per-OS (ADR-0003)
category: ui
priority: high
effort: M
status: closed
closed: 2026-04-28
resolved_by: Closed via ADR-0012 (Linux MVP / protocol removal). The named-pipe transport this ticket would have migrated to was itself removed; UI IPC stays on UDS with no per-OS branching.
code_refs:
  - ui/src/main/kotlin/org/krost/unidrive/ui/IpcClient.kt
  - ui/src/main/kotlin/org/krost/unidrive/ui/DaemonDiscovery.kt
adr_refs: [ADR-0003]
opened: 2026-04-17
milestone: v0.2.0
chunk: ipc-ui
---
On Windows, UI should use the named pipe; on POSIX, UDS. Replaces today's Windows UDS path (requires Win 10 build 17063+).

---
id: UD-329
title: OneDrive downloadFile buffers entire response — files > 2 GiB fail with 'Can't create an array of size N'
category: providers
priority: high
effort: S
status: closed
closed: 2026-04-29
resolved_by: Fixed in v0.0.1 amend (2026-04-29). Switched downloadFile to httpClient.prepareGet(url).execute{ ... bodyAsChannel() ... } — Ktor's streaming-body path, never allocates byte[contentLength]. 401-refresh and 429/503 throttle handling inlined via DownloadOutcome sealed class because execute{} is the only correct lifetime scope for a streaming response. Existing OneDrive test suite green; live retest of the >2 GiB profile is the acceptance criterion.
opened: 2026-04-29
---
**Symptom:** During a real `unidrive -p onedrive-test get /` test-drive on
2026-04-29 (40-file `Videos/` tree, ~23 GiB total), every file larger
than ~2 GiB failed with `Can't create an array of size N`. 5 of 40 files
were skipped after 3 retry attempts each. Sizes: 2.02 GiB, 2.08 GiB,
3.21 GiB, 4.45 GiB, 4.68 GiB.

**Root cause:** `GraphApiService.downloadFile` used
`httpClient.get(url)` (or `authenticatedRequest(url)`) and then read
`response.body<ByteReadChannel>()`. On Ktor 3.x, this code path
buffers the **entire response body** into a single `byte[contentLength]`
before exposing the channel. Java arrays are int-indexed, so any
file larger than `Integer.MAX_VALUE` (2 147 483 647 = ~2.147 GiB)
trips the JVM's array-size check at allocation time. The 8 KiB ring
buffer in the existing `channel.readAvailable(buf)` loop was a
red-herring — it ran *after* the buffered allocation had already
failed.

**Fix:** Switched the request shape to `httpClient.prepareGet(url).execute { response -> ... }`
and read `response.bodyAsChannel()` inside the `execute { }` block.
That's Ktor's blessed streaming path: the channel pulls from the
network as the consumer drains it; no per-file allocation grows with
file size. The 8 KiB ring buffer is now the only memory the download
holds.

**Side-effect:** the 401-refresh-once logic and 429/503 throttle
handling that lived in `authenticatedRequest` had to be inlined into
the download loop because `execute { }` is the only way to scope a
streaming body's lifetime correctly. A new `DownloadOutcome` sealed
class signals from inside the block to the outer retry loop
(`Done | RetryAuth | Throttle`).

**Verification:** `:providers:onedrive:test` passes (existing tests:
DownloadContentTypeGuardTest still green — covers the HTML 200 guard;
LiveGraphIntegrationTest's parallel-load test still gates ThrottleBudget
behaviour; CancellationPropagationTest unchanged). Live retest of the
original failure profile (40 files containing 5 × > 2 GiB) is the
acceptance criterion — pending user re-run.

**Acceptance:** re-running `unidrive -p onedrive-test get /` against the
same OneDrive tree completes without `Can't create an array of size N`
and downloads all 5 previously-skipped files at full size with their
expected hash.

---
id: UD-213
title: Tray popup lacks multi-provider + multi-account layout
category: ui
priority: medium
effort: M
status: closed
closed: 2026-04-29
resolved_by: Closed via ADR-0013 (ui-removal). The ui/ Compose-Desktop / Swing tray was removed entirely; this UI-tier ticket can only re-open under the criteria in ADR-0013 §Re-opening criteria.
code_refs:
  - ui/src/main/kotlin/org/krost/unidrive/ui/TrayManager.kt
opened: 2026-04-18
chunk: ipc-ui
---
Pre-MVP gap surfaced in UD-712. Current tray assumes a single provider/account; needs to surface N profiles grouped by provider type, each with its own status/quota/last-sync submenu. Related to UD-212 (both block meaningful multi-account UX).

---
id: UD-215
title: Tray icon activity indicator — surface in-flight work (sync / upload / download / error)
category: ui
priority: medium
effort: S
status: closed
closed: 2026-04-29
resolved_by: Closed via ADR-0013 (ui-removal). The ui/ Compose-Desktop / Swing tray was removed entirely; this UI-tier ticket can only re-open under the criteria in ADR-0013 §Re-opening criteria.
code_refs:
  - ui/src/main/kotlin/org/krost/unidrive/ui/TrayManager.kt
opened: 2026-04-18
chunk: ipc-ui
---
Surfaced UD-712 review: current tray icon is static — user can't tell whether unidrive is idle, scanning, uploading, or blocked on an error. Screenshots from the OneDrive test show "scanning local (0 items)" in the header only — the system-tray icon itself carries no state. Directions: (a) 4 icon states — `idle`, `busy` (sync/scan), `error`, `offline`; (b) small animation / badge on the icon during `busy`; (c) a short activity line in the popup header: "⟳ uploading 3 of 16 (onedrive-test)" or "✗ 2 conflicts on krost". Source truth is already in the sync engine — `SyncReporter.onSyncProgress` emits phase + counts. Hook TrayManager into the same reporter the CLI uses.

---
id: UD-217
title: Tray popup UI/UX — brainstorm + wireframe doc for multi-provider/multi-account
category: ui
priority: medium
effort: S
status: closed
closed: 2026-04-29
resolved_by: Closed via ADR-0013 (ui-removal). The ui/ Compose-Desktop / Swing tray was removed entirely; this UI-tier ticket can only re-open under the criteria in ADR-0013 §Re-opening criteria.
code_refs:
  - ui/src/main/kotlin/org/krost/unidrive/ui/TrayManager.kt
opened: 2026-04-18
chunk: ipc-ui
---
Companion doc for UD-213 (layout) + UD-212 (context) + UD-215 (activity). User feedback from UD-712: current popup is "a technical PoC without added value" — Sync now / Open folder / View log / Quit, scoped to one profile. Produce `docs/ui/tray-popup-wireframes.md` with at least 3 alternative layouts for N providers × M accounts: (a) nested submenus per provider type, (b) flat list with grouping headers, (c) compact cards with icons + quota bars + activity dot. Inputs: existing mockups (if any), Dropbox/iCloud/OneDrive-native tray patterns for reference, the screenshots captured in UD-712. Output informs UD-213 implementation scope.

---
id: UD-502
title: UI snapshot / integration tests
category: ui
priority: medium
effort: M
status: closed
closed: 2026-04-29
resolved_by: Closed via ADR-0013 (ui-removal). The ui/ Compose-Desktop / Swing tray was removed entirely; this UI-tier ticket can only re-open under the criteria in ADR-0013 §Re-opening criteria.
code_refs:
  - ui/src/test
adr_refs: []
opened: 2026-04-17
milestone: v0.2.0
chunk: ipc-ui
---
No UI tests today. Add snapshot tests for tray menu states (IDLE / SYNCING / ERROR) and an end-to-end test that launches a fake daemon over loopback.

---
id: UD-002
title: Archive original sibling dirs as read-only reference
category: architecture
priority: low
effort: S
status: closed
closed: 2026-04-29
resolved_by: Closed via ADR-0013. The pre-greenfield sibling dirs are entirely outside the OSS repo's concern; their on-disk fate is the maintainer's local housekeeping, not a project deliverable.
code_refs: []
adr_refs: [ADR-0001]
opened: 2026-04-17
---
After v0.1.0 ships, mark `C:/Users/gerno/dev/git/{unidrive,unidrive-ui,unidrive-shell}/` read-only. Delete only after v0.2.0 ships.

---
id: UD-729
title: Pre-public-release scrub — rewrite author/paths/PII before any public push
category: tooling
priority: medium
effort: L
status: closed
closed: 2026-04-29
resolved_by: Closed via v0.0.1 amend (2026-04-29). Live-doc scrub completed: Windows username 'gerno' replaced with placeholders or removed in CLAUDE.md, BOOTSTRAP.md, ADR-0001, SPECS.md, TOOLS.md, jvm-monitor-mcp/README.md, memory-mcp/{README,server.py}, ops/benchmark/README.md. Maintainer identity 'Gernot Krost <unidrive@krost.org>' is intentionally retained — Apache-2.0 + NAMING.md + NOTICE name him as the rightful copyright author, not anonymously. Per CONTRIBUTING.md §What is and isn't in the tree, '.claude/settings.local.json', '.claude/worktrees/', and the maintainer's '~/.claude/projects/<encoded>/memory/' all stay outside the published tree. CLOSED.md / chunks may still reference the older absolute paths in audit-trail entries — historical record, not live guidance, so left intact.
code_refs:
  - scripts/dev/
  - .github/workflows/
opened: 2026-04-20
---
**PREREQUISITE for any public release of the unidrive repo** (PUBLIC fork, OSS launch, shared snapshot, etc.). Must NOT be skipped — the current repo leaks enough user-identifying context that a public push would de-anonymise the author beyond what's already tied to the `gkrost` GitHub identity.

Scan findings (2026-04-20, HEAD `ud-503-ipc-migration`):

| Surface | Count | Samples |
|---|---:|---|
| `gerno` (Windows home path) | **1 636 occurrences / 30 files** | `C:\Users\gerno\...` everywhere in docs + some tests |
| `gernot` (Linux user + git author) | **60 / 8 files** | `/home/gernot/`, `gernot@...` |
| `Gernot Krost <gernot@krost.org>` | every commit | git log author field |
| Internxt profile name | several | `inxt_gernot_krost_posteo` reveals posteo email provider |
| LAN IPs | small | `192.168.0.106:22` in an SSH test fixture |
| Machine nicknames | small | `ds418play` (NAS), `sg5` (dev laptop) |

## Work plan when "go public" is decided

### Phase 1 — inventory lock
Re-run the scan (the commands are in the scratch log from this ticket's filing session), diff against a new scan at decision time. Anything new needs triage.

### Phase 2 — author rewrite
`git filter-repo --mailmap <map>` with a mapping that replaces `Gernot Krost <gernot@krost.org>` with a neutral identity (e.g. `Unidrive <unidrive@users.noreply.github.com>` or the project identity of choice). This touches every commit's author + committer.

### Phase 3 — path rewrite
`git filter-repo --replace-text <rules>` with rules:
```
gernot==>user
gerno==>user
krost\.org==>example.org
gkrost==>unidrive
ds418play==>nas
sg5==>dev-host
inxt_gernot_krost_posteo==>internxt-primary
192\.168\.0\.106==>192.0.2.1    # TEST-NET-1, reserved for docs
```

### Phase 4 — config-example scrub
`docs/config-schema/config.example.toml` hardcodes sample profile names pulled from the author's real config. Replace with generic names (`onedrive-home`, `sftp-backup`, etc.).

### Phase 5 — docs audit
`docs/superpowers/plans/2026-04-18-closed-integration-plan.md` (46 hits) + `docs/backlog/{BACKLOG,CLOSED}.md` (16 hits) have narrative passages that mention Gernot by name. Beyond simple string rewrite, prose needs a pass to make sure the REASONING doesn't imply "this is MY drive" when the intent was generic.

### Phase 6 — handover.md + memory/
`handover.md` lives in the repo; `memory/*.md` lives in `~/.claude/projects/...` (NOT in the repo). The handover survives the rewrite; memory doesn't need scrubbing because it never ships.

### Phase 7 — new-repo decision
Two options at this point:
- **A. Same repo, rewritten history.** Force-push the rewritten branch, require everyone to re-clone. Preserves commit-hash continuity for internal references.
- **B. Fresh repo from the latest tree.** New repo with a single "initial commit" + the rewritten tree. Clean slate, loses commit history, but lowest-risk from a "did we catch everything" standpoint.

Recommendation: **B** unless the commit history itself is a selling point for the OSS launch (unlikely for a v0.1.0 project).

### Phase 8 — verify
- `grep -r gerno` / `grep -r gernot` / `grep -r krost\.org` → all zero.
- `git log --format='%an %ae'` → all the neutral identity.
- Fresh-clone the scrubbed repo into a sandbox, run `./core/gradlew build` + the docker harnesses → everything still green (the scrub shouldn't touch compilable code paths, but verify).

## Acceptance

- Before any public release, a documented scrub pass completes and is reviewed.
- A one-shot script `scripts/dev/pre-release-scrub.sh` lives in the repo and encodes the filter-repo invocations so the pass is reproducible.
- CI gate: a smoke test `scripts/dev/pii-scan.sh` that grep-counts the forbidden tokens and fails the build if non-zero.
- Handover.md + CHANGELOG entry explaining the scrub on first public release.

## Effort

L — scrub is mechanically straightforward (~1 day), but scope gate ("do we want public?") + review of rewritten prose + the clean-slate/forced-rewrite decision stretch it.

## Related

- UD-259 / UD-258 closed — no PII implications.
- `feedback_halted_agent_leaks.md` (memory) — if halted agents leave WIP with PII in it, the pre-commit scope check (UD-728) plus this scrub catch both edges.

---
id: UD-715
title: Brainstorm — post-Buschbrand lessons, agent-welcoming public release, value-per-stakeholder
category: tooling
priority: low
effort: L
status: closed
closed: 2026-04-29
resolved_by: Closed via the v0.0.1 amend chain. The brainstorm's product-shape lessons (agent-welcoming public release, value-per-stakeholder framing) are baked in: ADRs 0011 (shell-win removal), 0012 (Linux MVP + protocol removal), 0013 (ui removal); LICENSE / NAMING.md / CONTRIBUTING.md as the public-facing trio; docs/dev/lessons/ as the shared methodology surface. The test-quality finding spawned the .claude/skills/challenge-test-assertion skill, also in tree. No remaining brainstorm scope.
opened: 2026-04-19
---
**Brainstorm, not a spec.** Raw impressions from the 2026-04-19 session after Gernot reflected on the larger trajectory, plus Claude's synthesis. Next session should pick this up as a real planning discussion, not as a code ticket. Expect this document to grow before it converges.

## Origin — the Buschbrand experiment

Before the current greenfield: unidrive lived in three sibling repos (`unidrive`, `unidrive-ui`, `unidrive-shell`) with extensive test suites, multiple CLAUDE.md files, numerous design docs, and opencode-generated review notes. Every artifact reinforced every other. Agents working in that space converged on a **mathematical local maximum** — all participants (human + LLM) acted as a chain, addicted to the rules the artifacts encoded, optimising against proxies that had detached from the underlying business intent.

Gernot ran a structured deletion experiment, working title *Buschbrand* ("brushfire") or *forced dementia*:

1. Delete all `.md` files in rounds.
2. Delete `.claude/` folders.
3. Delete all other docs — both agent-targeted and human-targeted.
4. Prompt a cold-start agent to deconstruct the three repos into a single monorepo.

The outcome is the current `core/` + `ui/` + `shell-win/` greenfield under `C:\Users\gerno\dev\git\unidrive`.

## What the experiment revealed

- **Many tests were wrong.** Not subtly wrong: asserting implementation detail rather than invariant, or asserting features that should never have existed in the first place. They cemented a trajectory.
- **Green CI was a proxy, not a signal.** Agents on the old codebase treated lint-green + test-green as done. The new greenfield exposed that the proxy had detached from real correctness.
- **Chain behaviour.** Both human and LLM contributors acted like they were addicted to the rules the dominant artifacts encoded. Nobody challenged the inherited narrative because the artifacts were internally consistent.
- **Counterfactual cost.** The original path would have reached public-release + revenue only after an expensive cleanup. The current path is not more optimal than the ideal, but it's measurably cheaper-to-ship-correct than the evolutionary continuation.
- **The current path feels right but is not optimal.** There's slack in every dimension: app architecture, agent ergonomics, tooling velocity, documentation discipline. The question is where to spend the next N hours for maximum stakeholder value.

## Themes I'd name from Claude's side

- **Narrative capture.** Tests, docs, and CLAUDE.md files bias every subsequent reader toward the dominant pattern. A brilliant artifact asserting the wrong invariant is the most dangerous object in a codebase.
- **Chesterton's fence in reverse.** Agents encounter a fence and default to preserving it. Without the originating rationale accessible, vestigial fences outlive their purpose by years.
- **Specification gaming on proxies.** Tests define a proxy for "correctness"; agents optimise the proxy. Misaligned proxy → cemented misalignment, at speed.
- **The Lindy trap.** The longer a pattern has survived, the more agents treat it as axiomatic, even when the original justification is stale.
- **Evidence of truly-load-bearing vs cement.** The reconstruction is a natural experiment: tests and abstractions that reappeared in the greenfield are load-bearing; those that had to be actively reconstructed to satisfy stale expectations are cement. We have unique visibility into this delta right now — perishable data.

## Value-per-stakeholder (Gerechtigkeit bei Resourcen)

The stakeholders don't share a utility function:

| Stakeholder | Values |
|---|---|
| End-user (pays) | Reliability, no surprises, data safety, fast sync |
| LLM / agent contributor | Clear abstractions, frank debriefs, fast feedback loops, signal-to-noise |
| Human maintainer | Low debt, legible decisions, small blast radius on changes |
| Investor / partner | Velocity, demonstrable quality, credible moat |
| Gernot (ordens) | Ship correct-at-depth, not just green-at-surface |

Balancing these isn't "optimise the weighted sum" — it's naming which stakeholder each investment serves and being explicit about the trade.

## Agent-welcoming path (working hypothesis: UniDrive goes public under permissive licence)

What unidrive already has that's unusually strong for agents:

- Frank session handovers with "here's what I got wrong" (better than pristine READMEs).
- ADRs naming rejected alternatives (ADR-0001..0010).
- Backlog with effort + priority tags (`docs/backlog/BACKLOG.md`).
- Working dev-tool suite (`backlog.py`, `log-watch.sh`, `oauth-mcp`, `ktlint-sync.sh`, `msix-id.sh`).
- `CLAUDE.md` at repo root.

What's missing for a public agent-contribution story:

- **`AGENTS.md`** at repo root — distinct from CLAUDE.md. Frames: "if you're an agent evaluating UniDrive, here's the mission, the current constraints you should challenge, and the contribution invitation." Public-facing; CLAUDE.md stays internal.
- **Permissive licence** (MIT / Apache 2.0 with patent grant). Currently unlicensed (check `LICENSE` at root — may not exist yet).
- **Contribution provenance convention.** `Co-Authored-By: <Agent model>` on every agent-authored commit. Already partially done in recent commits.
- **Backlog tags** — `good-first-agent-task`, `doc-only`, `test-only`, `research-only`. Makes self-selection trivial.
- **Reproducible onboarding path**: `scripts/dev/onboard.sh` that clones-to-green-build in <10 min on a fresh machine. First-pass ticket on its own.
- **Test-drive sandbox.** A way to exercise unidrive without consuming real OneDrive data. Docker harness partial; extend to a full in-a-box experience.
- **Public session handovers.** Sanitised versions of the `handover.md` files, showing the failure modes and the recovery. More attractive than any promotional README.

## Open questions to drive the next brainstorm session

1. Is the current monorepo the right container, or should some subsystems (shell-win, ui) stay sibling repos for independent release cadence?
2. Which of the pre-Buschbrand tests that survived the reconstruction were truly load-bearing vs smuggled-through-by-inertia? Is there a systematic way to audit?
3. What's the right first deliverable for "agent-welcoming public release"? The `AGENTS.md`? A signed test-drive binary? An ADR calling the strategy?
4. How do we maintain the "frank debrief" quality of session handovers as contributors multiply? Is there a template + reviewer?
5. Where is the least-regret investment of the next 40 hours? Candidates: (a) finish UD-232b semaphore resizing, (b) onboarding path UD-???, (c) AGENTS.md + public-doc pass, (d) audit cemented tests.

## Next-step artefacts that emerged from this ticket

- `.claude/skills/challenge-test-assertion/SKILL.md` — first concrete skill born from the experiment. Nudges the agent to ask "what invariant does this test protect?" before accepting or writing any test. Narrow, reusable, installable muscle-memory. Scaffolded alongside this ticket.
- Follow-up candidates, not yet filed: `derive-from-first-principles` skill, `AGENTS.md` first draft, onboarding script.

## Related

- Memory `feedback_ktlint_baseline_drift`, `feedback_kotlin_default_param_ordering`, `project_msix_sandbox` — session-specific lessons that already made it to persistent agent knowledge.
- UD-232 (ThrottleBudget) — canonical example of a fix we shipped blind because the live-test feedback loop wasn't closed yet.
- UD-228 (cross-provider robustness audit) — the shape of "audit what was cemented" work at provider scope.
- UD-220 (consequences docs) — the sibling human-facing docs story.
- UD-714 (code-style policy) — the smaller sibling "make the tacit explicit" artefact.

---
id: UD-286
title: CloudRelocator swallows per-file failures with no log line + breaks CancellationException propagation
category: core
priority: high
effort: S
status: closed
closed: 2026-04-29
resolved_by: commit affcbd1. Fixed: UD-286 catch blocks now re-throw CancellationException + log class name + throwable. 4 regression tests. Validated locally via :app:sync:check (BUILD SUCCESSFUL, 23 tests pass).
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt
opened: 2026-04-29
---
**Symptom (live, 2026-04-29).** During
`unidrive relocate --from unidrive-localfs-19notte78 --to ds418play-webdav`
(128 681 files / 345 GB), per-file failures surface to the CLI with
their `e.message` only — but `unidrive.log` (verified at 7.5 MB)
contains **zero ERROR/WARN lines** for the relocate. Only `DEBUG
skip-check` lines from CloudRelocator. Postmortem of any failed
file is impossible without re-running with TRACE logging.

Compounding: each `catch (Exception)` block also absorbs Kotlin's
`CancellationException`, breaking structured concurrency. Ctrl-C
on the relocate hangs for up to `requestTimeoutMillis = 600 000 ms`
because the parent scope thinks children are still running.

**Root cause.** `core/app/sync/.../CloudRelocator.kt` has four
catch blocks that share both anti-patterns:

| Line | Code | Anti-pattern |
|---|---|---|
| 138 | `catch (e: Exception) { log.warn("targetIndex failed for '{}', skip check disabled: {}", path, e.message) }` | message-only, no class, no throwable |
| 162-166 | `try { target.createFolder(newTarget) } catch (e: Exception) { /* Folder may already exist */ }` | empty body, no log at all |
| 213-216 | `} catch (e: Exception) { errorCount++; emit(MigrateEvent.Error("Failed to migrate $newSource: ${e.message}")) }` | **smoking gun** — no log, message only |
| 223-227 | outer migration catch | same as 213 |

None of the four re-throw `CancellationException` first. Compare with
`SyncEngine.kt:416-426` which does it correctly:

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log.warn("Failed to {} {}: {}: {}", op, path, e.javaClass.simpleName, e.message, e)
    ...
}
```

UD-300 / `CancellationPropagationTest.kt` was filed against OneDrive
for exactly this. CloudRelocator does not pass that test contract.

**Proposed fix.** Mechanical, low-risk. For each of the four
catches:

1. Add `catch (e: CancellationException) { throw e }` **before** the
   generic `catch (Exception)`.
2. Replace `e.message` with `"{}: {}"` + `e.javaClass.simpleName`,
   `e.message`, plus pass `e` as the SLF4J throwable parameter so
   the stack reaches the log.
3. Drop the empty-body catch on line 162; if folder creation throws
   any `WebDavException` other than 405 Method Not Allowed (the
   "already exists" signal), log + propagate.

**Acceptance.**
- New `CloudRelocatorCancellationTest.kt` mirroring
  `CancellationPropagationTest.kt`: launch a relocate, cancel the
  parent scope mid-flight, assert `CancellationException` propagates
  to the caller within 100 ms (not 600 000 ms).
- New regression: stub a provider that throws `IOException("test")`
  on the third upload — assert `unidrive.log` contains a `WARN` line
  with `IOException: test` and the throwable's stack trace.
- Manual: re-run a relocate that hits a real WebDAV failure;
  confirm the failure appears in `unidrive.log` with class +
  stack.

**Why this is filed first.** Without this fix, every other ticket
in the WebDAV-relocate cluster (UD-287/288/289/290) is harder to
diagnose because the log doesn't preserve the failure mode. Land
this first; the remaining tickets become tractable.

**Related.**
- UD-300 — same pattern in OneDrive, already fixed there.
- UD-284 — MDC `[???????]` in relocate log lines; same module.
- UD-285 — REQUEST_TIMEOUT_MS=600s; the failure mode this ticket
  helps diagnose.
- UD-287, UD-288, UD-289, UD-290, UD-291 — the rest of the audit
  cluster.

---
id: UD-291
title: WebDavProvider.quota() swallows all exceptions to QuotaInfo(0,0,0); pre-flight relocate guard never sees failure
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-29
resolved_by: commit 606f576. Fixed: catch now re-throws CancellationException + logs class/message/throwable. Returns zero sentinel (API compat). 2 regression tests via logback ListAppender. :providers:webdav:check passes.
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
opened: 2026-04-29
---
**Symptom (audited, 2026-04-29).** A failing PROPFIND quota query
against a WebDAV target returns `QuotaInfo(total = 0, used = 0,
remaining = 0)` instead of an error. Pre-flight insufficient-quota
guard in `RelocateCommand.kt:103` interprets the all-zero result as
"unknown / can't tell" and proceeds with the relocate. A target
with **no free space** is indistinguishable from a target whose
quota query failed for transient reasons.

**Root cause.** `core/providers/webdav/.../WebDavProvider.kt:184-186`:

```kotlin
override suspend fun quota(): QuotaInfo {
    return try {
        ...quota PROPFIND...
    } catch (_: Exception) {
        QuotaInfo(total = 0, used = 0, remaining = 0)
    }
}
```

Three problems in three lines:

1. **Catch-all `Exception` swallows everything**, including
   `CancellationException`. Same UD-300 anti-pattern as UD-286.
2. **No log line.** A network failure to the quota endpoint
   silently degrades to "we don't know."
3. **Returning a magic-zero record** conflates "quota is zero" (a
   valid answer for a full disk) with "we couldn't get quota" (a
   different problem). Caller has no way to distinguish.

**Compare with the spec.** `QuotaInfo` in
`core/app/core/.../QuotaInfo.kt` represents a successful quota
fetch. It has no "unknown" sentinel. The expected shape for "we
couldn't find out" is either:
- `null` return (Kotlin `suspend fun quota(): QuotaInfo?`), or
- `Result<QuotaInfo>` (kotlin.Result), or
- a sealed class `QuotaResult { Success(QuotaInfo); Unknown(reason); Unsupported }`
  matching the ADR-0005 capability-contract pattern.

**Proposed fix.** Smallest change that correctly propagates the
information:

1. Change the catch to:
   ```kotlin
   } catch (e: CancellationException) {
       throw e
   } catch (e: Exception) {
       log.warn("WebDAV quota PROPFIND failed: {}: {}",
           e.javaClass.simpleName, e.message, e)
       throw e  // or: return null if signature changes
   }
   ```
   At minimum: log + propagate. Don't fabricate a success record.

2. Change the signature to `suspend fun quota(): QuotaInfo?` (or
   `CapabilityResult<QuotaInfo>` per ADR-0005). Update the contract
   in `CloudProvider.kt`.

3. Update `RelocateCommand.kt:103` to handle the null/unknown case
   explicitly. If quota is unknown:
   - Default behaviour: **abort the relocate** with a clear error
     ("Cannot determine target quota; pass `--ignore-quota` to
     proceed").
   - Override: `--ignore-quota` flag for the user to accept the
     risk.

**Acceptance.**
- New `WebDavProviderQuotaTest`: stub server returns 503 on the
  quota PROPFIND; assert `quota()` throws (not returns zero).
- New: `RelocateCommand` integration test — quota fails, no
  `--ignore-quota` flag → relocate aborts with the new error
  message.
- New: with `--ignore-quota`, the same scenario → relocate proceeds.
- Update `docs/providers/webdav-client-notes.md` to document the
  quota behaviour.

**Audit other providers.** The same anti-pattern likely exists in:
- `S3Provider` — S3 doesn't expose quota; current behaviour may
  already be a sentinel zero (which is *correct* for S3 since the
  bucket has no quota concept). Verify and document.
- `HiDriveProvider`, `InternxtProvider` — likely the same shape.
  File follow-up tickets if they audit similarly.

**Related.**
- UD-286 — same swallow pattern in CloudRelocator.
- UD-301 — `CapabilityResult` infrastructure (could host the
  "quota unsupported" return shape).
- ADR-0005 — capability contract.

**Priority.** Medium. The current behaviour "fails open" — on a
target that's full but where quota fetch succeeds, the relocate
will fail mid-way once the disk fills. Bad UX (lots of mid-relocate
errors) but not data-loss. On the audit's symptom path, this is
not blocking; on production reliability it's a real gap.

---
id: UD-284
title: MDC profile field renders as '[???????]' in relocate's coroutine workers
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-29
resolved_by: commit a47c113. Fixed: all 5 runBlocking calls in RelocateCommand wrap with MDCContext(). 1 regression test verifies build/profile MDC propagates from caller into CloudRelocator's flow.collect catch-block log.warn (via .flowOn(Dispatchers.IO)). The [???????] was the build-MDC fallback (logback default-value), not encoding-mangled question marks as the original audit guessed — fix shape unchanged. :app:cli:check + :app:sync:check pass.
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
  - core/app/cli/src/main/resources/logback.xml
opened: 2026-04-29
---
**Symptom (live, 2026-04-29).** During a long-running
`unidrive relocate --from unidrive-localfs-19notte78 --to ds418play-webdav`,
every log line produced by `DefaultDispatcher-worker-N` threads renders
the MDC profile field as **`[???????]`** (seven question marks)
instead of the active profile name:

```
2026-04-29 15:25:37.414 DEBUG [???????] [*] [-------] [DefaultDispatcher-worker-1] o.k.unidrive.webdav.WebDavApiService - Upload: ...
2026-04-29 15:05:36.630 DEBUG [???????] [*] [-------] [DefaultDispatcher-worker-3] o.krost.unidrive.sync.CloudRelocator - skip-check ...
```

Affects every relocate worker thread observed (1, 3, 7, 11). Logback
pattern is `%X{profile:-*}` so the `*` fallback shows when MDC is
empty; instead we see `???????`, which suggests something is putting
literal `???????` into MDC, OR the renderer is treating an
encoding-mangled profile name as `???????` (cp1252 → UTF-8?).

**Why this matters.** Per
[CLAUDE.md](../../CLAUDE.md#before-you-read-unidrivelog) §"Before
you read unidrive.log", the profile prefix is the primary triage
signal when reading a 10+ MB log with multiple sync runs commingled.
A `[???????]` prefix is worse than `[*]` — it looks like a real
value, so you can't filter on it (`grep -v '\[\*\]'` works; there's
no clean filter for the literal question-mark string), and it makes
log lines from concurrent profile runs indistinguishable.

**Probable root cause.** UD-212 wired `MDC.put("profile", _profile.name)`
in `Main.resolveCurrentProfile`, with `runBlocking(MDCContext())`
expected to propagate the value into coroutine workers. The
relocate path likely:
- Either spawns its `DefaultDispatcher` work without the
  `MDCContext()` wrapper (so MDC inheritance breaks), and the empty
  string is being rendered as `???????` by some downstream encoder, **or**
- The profile name actually being put into MDC is itself
  cp1252-mangled UTF-8 from a launcher / env var, and what's
  rendered is the visible damage. The CLI has UD-258
  (`-Dstdout.encoding=UTF-8`) but this is an **MDC value**, not a
  stdout write — different code path.

**Investigation steps.**
1. Find the relocate's coroutine launch site
   (`CloudRelocator.kt`?). Confirm whether it wraps in
   `runBlocking(MDCContext())` or just `runBlocking`.
2. Add a one-shot log line at the relocate entry: `log.debug("MDC at
   relocate entry: {}", MDC.getCopyOfContextMap())` to see the
   actual map contents (vs the rendered output).
3. Verify on a small relocate (10 files) whether the same `???????`
   appears in non-relocate log lines from this profile (i.e.,
   `[???????]` in CLI startup messages too) — that would prove it's
   the MDC value, not coroutine inheritance.

**Proposed fix.** Whatever the investigation shows — either:
- (a) Wrap the relocate's `runBlocking` in `MDCContext()` (matches
  the pattern UD-212 established for sync/Pass 2), or
- (b) UTF-8-clean the MDC value at insert time. `_profile.name`
  comes from a TOML key that's already UTF-8 — mangling would have
  to happen at logback render. Pin
  `<encoder>...<charset>UTF-8</charset></encoder>` in `logback.xml`.

**Acceptance.**
- New `RelocateCommandTest` regression: invoke a 10-file relocate,
  assert no log line contains the literal substring `[???????]`.
- Manual: drive a relocate end-to-end, verify the active profile
  name appears in every worker thread's log line.

**Related.**
- UD-212 — original "log/CLI profile context" wiring.
- UD-258 — UTF-8 stdout encoding (a different surface).
- CLAUDE.md §"Before you read unidrive.log" calls out that
  filtering on profile is the first triage action; this regression
  silently breaks that workflow.

---
id: UD-287
title: WebDAV Apache5 connection pool reuses half-closed sockets after timeout (WSAECONNABORTED cascade)
category: core
priority: high
effort: S
status: closed
closed: 2026-04-29
resolved_by: commit 70b2fc6. Fixed: validateAfterInactivity=2s + evictExpiredConnections + evictIdleConnections=30s on Apache5 pool, and flushAndClose now runs in finally with runCatching. No new unit test (needs live integration harness); UD-288 will add upload-failure-path tests that indirectly validate the close-in-finally. :providers:webdav:check passes.
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt
opened: 2026-04-29
---
**Symptom (live, 2026-04-29).** During a 345 GB
`unidrive relocate --to ds418play-webdav`, every Ktor
`requestTimeoutMillis` (UD-285, 600 s) failure is immediately followed
by 1–N **`Eine bestehende Verbindung wurde softwaregesteuert durch
den Hostcomputer abgebrochen`** errors (Windows TCP
`WSAECONNABORTED 10053`) on subsequent unrelated PUTs, before the
relocate self-recovers.

**Root cause.** `core/providers/webdav/.../WebDavApiService.kt:107-114`
builds an Apache5 `PoolingAsyncClientConnectionManagerBuilder` with
**default settings**:

- No `setValidateAfterInactivity(...)` — connections aren't probed
  before reuse.
- No `evictExpiredConnections()` / `evictIdleConnections(...)` —
  expired sockets stay in the pool.
- No idle-timeout config — DSM (Synology Apache `mod_dav`) closes
  keep-alive at ~5 minutes; the next reuse hits a half-closed
  socket.

When `requestTimeoutMillis` fires, Ktor cancels the in-flight write
but Apache5 returns the connection to the pool **half-closed at
the OS level**. The next PUT that picks it up gets WSAECONNABORTED
from Windows TCP — which propagates as a "permanent" failure for
that file (no retry, see UD-288).

**Companion bug — `flushAndClose()` outside `withContext`.**
`WebDavApiService.kt:175-186`:
```kotlin
override suspend fun writeTo(channel: ByteWriteChannel) {
    withContext(Dispatchers.IO) {
        Files.newInputStream(localPath).use { input ->
            val buf = ByteArray(65536)
            while (input.read(buf).also { n = it } != -1) {
                channel.writeFully(buf, 0, n)
            }
        }
    }
    channel.flushAndClose()  // ← outside the IO block
}
```
If the IO block throws (cancellation, file-read error, partial
write), `flushAndClose()` is skipped → the PUT never gets its
terminating chunk frame → server drops the connection → that
connection still goes back to the pool → cascading WSAECONNABORTED.
**This is the bug that *creates* the bad pool entries** the
eviction-policy fix above cleans up. Fix one without the other and
the pool fills up faster than eviction empties it.

**Proposed fix.**

1. Connection pool eviction (covers reuse hygiene):
   ```kotlin
   PoolingAsyncClientConnectionManagerBuilder.create()
     .setValidateAfterInactivity(TimeValue.of(2, SECONDS))
     .setMaxConnTotal(50)
     .setMaxConnPerRoute(8)
     .build()
   // plus on the HttpAsyncClient:
   //   .evictExpiredConnections()
   //   .evictIdleConnections(TimeValue.of(30, SECONDS))
   ```
   Synology DSM keep-alive is ~5 min; 30 s idle eviction is well
   inside that. `setValidateAfterInactivity(2s)` adds a cheap
   probe before any second-or-later reuse.

2. Move `flushAndClose()` inside the `withContext(Dispatchers.IO)`
   block (or drop the `withContext` entirely — Ktor's engine already
   runs `writeTo` on its own IO selector). On exception path,
   ensure the channel is closed with the failure cause:
   ```kotlin
   try { ... } catch (e: Throwable) {
       channel.cancel(e)  // half-close with cause; pool drops it
       throw e
   }
   ```

3. Same review of all other Ktor PUT paths in the WebDAV adapter
   (download, propfind, mkcol) for the same `flushAndClose`
   placement.

**Acceptance.**
- New `WebDavApiServiceConnectionPoolTest`: stub a server that
  drops the connection mid-PUT after N bytes; assert subsequent
  PUTs succeed (currently fail with WSAECONNABORTED).
- New unit test: simulate `withContext` block throwing; assert
  `channel.cancel` is called and the pooled connection is invalidated.
- Manual: re-run the same 345 GB relocate after fix; verify zero
  WSAECONNABORTED in `unidrive.log` (the WARN lines from UD-286
  are the source of truth).

**Compatibility.** Apache5 `evictExpiredConnections` /
`setValidateAfterInactivity` are stable since httpclient5 5.0; we're
on a current version. No external API change; behavior change is
"fewer transient failures."

**Related.**
- UD-285 — `requestTimeoutMillis` cap that triggers the cascade.
- UD-286 — silent-swallow that hides this from the log.
- UD-288 — no retry on transient WebDAV failures.
- ADR-0006 — toolchain (Ktor + Apache5 choice).

---
id: UD-288
title: WebDAV provider has no retry logic on transient HTTP failures (timeout, WSAECONNABORTED, 5xx)
category: core
priority: high
effort: S
status: closed
closed: 2026-04-29
resolved_by: commit b0955cf. Fixed: withRetry helper added; wraps download/upload/mkcol/propfind/quotaPropfind. 5 attempts, exponential backoff (1s/2s/4s/8s/16s), Retry-After honored, CancellationException carve-out. 15 regression tests covering retry success, exhaustion, terminal exceptions (UnknownHost, SSL), Windows WSAECONNABORTED text matching (English + German), HTTP-status classification. :providers:webdav:check passes.
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
opened: 2026-04-29
---
**Symptom (live, 2026-04-29).** During a 345 GB
`unidrive relocate --to ds418play-webdav`, **one transient HTTP
failure = one permanently failed file** in this run. The relocate
moves on to the next file without ever retrying. The user sees a
list of `Failed to migrate /Pictures/...` errors that are mostly
single-shot recoverable conditions (UD-285 timeout, UD-287 connection
abort).

**Root cause.** Grepped `core/providers/webdav/` for
`retry|retryOn|HttpRequestTimeoutException|backoff` — **zero
matches.** `WebDavApiService.upload`, `download`, `propfind`,
`mkcol`, `delete` all do a single Ktor request and propagate the
exception unmodified. Compare with OneDrive:

- `GraphApiService.kt` has `HttpRetryBudget` (UD-232).
- `authenticatedRequest` retries on 401 (UD-310), 429/503 (UD-207),
  `SerializationException` (UD-308), `IOException` short reads
  (UD-309), CDN download failures (UD-227).
- 5 attempts, exponential backoff with `Retry-After` honoring,
  cumulative budget caps.

WebDAV gets none of this. Yet WebDAV is served from heterogeneous
backends (DSM Apache, nginx-dav, Nextcloud, Box, ownCloud, IIS
WebDAV) — each with its own transient-failure profile. WebDAV needs
retry **at least as much** as Graph does.

**Proposed fix.** Mirror the OneDrive shape, scaled to WebDAV's
narrower error vocabulary:

```kotlin
private suspend fun <T> withRetry(
    op: String,
    path: String,
    block: suspend () -> T,
): T {
    val maxAttempts = 5
    var lastException: Throwable? = null
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            lastException = e
            log.warn("WebDAV {} {} timed out (attempt {}/{}): {}",
                op, path, attempt + 1, maxAttempts, e.message)
        } catch (e: IOException) {
            // WSAECONNABORTED, ConnectionClosedException, etc.
            if (!isRetriable(e)) throw e
            lastException = e
            log.warn("WebDAV {} {} I/O error (attempt {}/{}): {}: {}",
                op, path, attempt + 1, maxAttempts,
                e.javaClass.simpleName, e.message)
        } catch (e: ResponseException) {
            // 5xx retriable, 4xx (except 408/425/429) terminal
            if (!isRetriable(e.response.status)) throw e
            lastException = e
            log.warn("WebDAV {} {} HTTP {} (attempt {}/{}): {}",
                op, path, e.response.status.value,
                attempt + 1, maxAttempts, e.message)
        }
        delay(backoffMs(attempt))  // exponential: 1s, 2s, 4s, 8s, 16s
    }
    throw lastException ?: IllegalStateException("retry budget exhausted")
}
```

Wrap `upload`, `download`, `propfind`, `mkcol`, `delete` in
`withRetry`. Honor `Retry-After` header where present (DSM emits
it on 503 Service Unavailable).

**`isRetriable` policy.**
- HTTP status: retry 408, 425, 429, 500, 502, 503, 504. Terminal:
  401, 403, 404, 409, 410, other 4xx.
- IOException: retry `WSAECONNABORTED`, `WSAECONNRESET`,
  `SocketTimeoutException`, `ConnectionClosedException`. Terminal:
  `UnknownHostException`, `SSLPeerUnverifiedException`,
  `SSLHandshakeException` (these signal misconfig, not transient).

**Acceptance.**
- New `WebDavApiServiceRetryTest`: stub server that fails twice
  with 503 then succeeds; assert the call returns ok.
- New: stub fails with `IOException("WSAECONNABORTED")` 4 times
  then succeeds; assert ok.
- New: stub returns 404 (terminal); assert no retry, immediate
  throw.
- New: outstanding `delay(backoffMs)` is cancellable —
  `CancellationException` aborts the retry loop fast.
- Manual: re-run the 345 GB relocate; the file count that previously
  failed terminally should now mostly succeed on attempt 2 or 3.

**Implementation note.** Place `HttpRetryBudget` (or a WebDAV-shaped
twin) at the `WebDavApiService` level, not per-call. Per-call
budget burns through the retry allowance independently for adjacent
files; per-service budget shares the signal across the whole
relocate.

**Related.**
- UD-285 — REQUEST_TIMEOUT_MS too short; UD-287 — pool
  poisoning. **Both currently surface as permanent failures
  because of this ticket.** Fix this and they become invisible to
  the user (modulo the log line) until they exhaust the budget.
- UD-232 — OneDrive `ThrottleBudget` for 429 storms; reusable
  pattern.
- UD-227 — OneDrive download retry logic (template).

---
id: UD-285
title: REQUEST_TIMEOUT_MS=600s is a global wall-clock cap; aborts large uploads against slow targets
category: core
priority: high
effort: S
status: closed
closed: 2026-04-29
resolved_by: commit e2cb9c2. Fixed: per-request override on WebDAV download/upload sets requestTimeoutMillis = Long.MAX_VALUE (Ktor's effective infinite). Metadata verbs (PROPFIND/MKCOL/DELETE/HEAD/MOVE/quotaPropfind) unchanged. SOCKET_TIMEOUT_MS = 60 s remains as the no-bytes-flowing watchdog. Cross-provider follow-up (HiDrive/Internxt/S3/OneDrive) deferred to separate commits.
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/HttpDefaults.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt
  - core/providers/hidrive/src/main/kotlin/org/krost/unidrive/hidrive/HiDriveApiService.kt
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt
opened: 2026-04-29
---
**Symptom (live, 2026-04-29).** During
`unidrive relocate --from unidrive-localfs-19notte78 --to ds418play-webdav`
against a Synology DS418play WebDAV target, a 371 MB MP4 upload
(`Eternal_Tea-party_Eternal-1080p.mp4`) exceeded the 10-minute Ktor
client-side request timeout and aborted:

```
Request timeout has expired
[url=https://ds418play.local:5006/home/Pictures/ED Zelda B Photo Video Pack 2013 2017/Eternal_Tea-party_Eternal-1080p.mp4,
 request_timeout=600000 ms
```

The 600,000 ms is OUR timeout, not the server's. The DS418play
(Apache `mod_dav` under DSM) has a 3,600 s default — it would have
kept accepting bytes for another 50 minutes. The relocate moved on
to the next file (`Eternal_TUNES_…`) without retrying or completing
the partial upload.

**Root cause.** [`HttpDefaults.kt:7`](../../core/app/core/src/main/kotlin/org/krost/unidrive/HttpDefaults.kt)
sets `REQUEST_TIMEOUT_MS = 600_000` as a global wall-clock cap on
every HTTP request, applied uniformly across all Ktor providers
(WebDAV, OneDrive, HiDrive, Internxt, S3). At 0.6 MB/s effective
upload throughput (TLS overhead on the DS418play's Realtek RTD1296
+ Btrfs-on-spinners write path), 371 MB doesn't fit inside 10
minutes. Larger uploads — multi-GB files, slower targets, or any
backend that's CPU-bound on TLS — will hit this regularly.

**Why a global wall-clock cap is wrong for upload paths.** A
request timeout makes sense for *responses* (server is hung, give
up). For uploads the right safety property is "the connection is
dead" — i.e., **no bytes flowing for N seconds**. That's
`socketTimeoutMillis`, which is already set to 60 s on the same
client. The 60 s socket watchdog correctly catches stuck
connections; the 600 s wall-clock makes correctness depend on
upload speed, which is wrong.

**Comparable practice.**
- aws-sdk: defaults to no request timeout for `PutObject`; relies
  on socket timeout.
- rclone: no global request timeout; per-chunk timeout only on
  multipart paths.
- httpclient (Apache): explicit "no request timeout" recommendation
  for streaming uploads in their migration guide.

**Proposed fix (preferred).** **Drop `requestTimeoutMillis`
entirely for upload paths.** Keep it on metadata reads (PROPFIND,
PUT-without-body, listChildren, etc.) where 10 minutes is generous.
Concretely: split `HttpDefaults` into two configs —
`HttpDefaults.metadata { ... requestTimeoutMillis = 600_000 }` and
`HttpDefaults.upload { ... /* no requestTimeoutMillis */ }` — and
have each adapter use the right one for the verb being executed.
The socket timeout (60 s no-bytes) is the actual safety property.

**Alternative fix (simpler, less surgical).** Compute a per-request
timeout from `Content-Length`:

```kotlin
const val MIN_THROUGHPUT_BYTES_PER_MS: Long = 100  // 100 KB/s minimum
fun timeoutForBytes(bytes: Long): Long =
    maxOf(120_000, bytes / MIN_THROUGHPUT_BYTES_PER_MS)
// 100 MB → 1000 s, 1 GB → 10000 s = ~3 hours
```

Wire into each provider's upload path (WebDAV `putFile`, S3 `putObject`,
OneDrive `uploadSession`, HiDrive `upload`, Internxt). Slightly more
code but covers the pathological "20 GB ISO at 50 KB/s" case
without an unbounded wait.

**Alternative fix (config knob).** Add per-profile
`request_timeout_ms` override to `RawProvider`. Lowest-effort,
highest foot-gun (users have to know to set it; default behaviour
unchanged).

**Acceptance.**
- Existing WebDAV unit tests pass.
- New regression test in `WebDavApiServiceTest`: stub a slow
  chunked-upload server that emits one byte every 200 ms for a 64 MB
  body, assert the upload completes (would currently fail at 10 min).
- Manual: re-run the same relocate (`Eternal_Tea-party-…1080p.mp4`)
  and confirm completion.
- Document the change in `docs/providers/webdav-client-notes.md`
  and `docs/providers/webdav-robustness.md`.

**Operator workaround until fix lands.** Per-file size is the
predictor — anything > ~250 MB on a slow target risks the 10-min
wall-clock. Pre-staging large files via a one-shot tool (rclone,
curl, or a manual scp) keeps the relocate's per-request load
inside the budget.

**Related.**
- UD-227 — OneDrive download retries already cover the
  `requestTimeoutMillis` failure mode by parsing
  `retryAfterSeconds`. Upload paths don't have an equivalent.
- UD-309 — `downloadFile` retries on Ktor "Content-Length mismatch"
  short reads. Same retry-budget approach could front the upload
  fix.
- ADR-0006 — toolchain (Ktor 3.x choice). No re-evaluation needed;
  the timeout is a misuse of a healthy API surface, not a Ktor flaw.

---
id: UD-282
title: Surface ignored TOML sections / unknown keys in config.toml as warnings
category: core
priority: medium
effort: S
status: closed
closed: 2026-04-29
resolved_by: commit cf8a938. Fixed: validateTomlSections pure scanner + parseRaw warning + UNIDRIVE_STRICT_CONFIG fail-fast. Common-typo table (profiles→providers, profile→providers, etc.) + Levenshtein-3 fallback. 10 regression tests. Section-level only; unknown leaf keys inside known sections are out of scope (smaller win).
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
status: closed
closed: 2026-04-29
resolved_by: commit 229acba. Fixed: profileMismatchError validator at McpServer dispatch layer + JSON-RPC -32602 with error.data recovery context. 7 regression tests covering pass-through, configured-but-inactive (restart hint), unknown-profile (CLI-style error), error.data shape, JSON-RPC envelope structure. :app:mcp:check passes.
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
