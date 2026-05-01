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

> **Out of scope for unidrive-cli.** UI / tray work moved to
> [`UI_UD.md`](../../UI_UD.md) at the repo root on 2026-04-30.
> File new UI tickets there (or in the future UI repo), not here.

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

---

---

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
---
id: UD-900
title: Brainstorm: detect & handle dehydrated/placeholder files when sync_root sits under a foreign cloud client's managed area
category: experimental
priority: medium
effort: L
status: open
opened: 2026-04-30
---
**Status:** brainstorm — research questions, not an implementation
spec. Spawn focused implementation tickets per detector / mitigation
when the shape settles.

## Why

When unidrive's `sync_root` overlaps another cloud client's managed
area (its "own" local mount that clinks into the host filesystem),
files inside that tree may be **dehydrated placeholders**: the
directory entry exists, attributes report a logical size, but the
actual bytes live in the foreign provider's cloud. Reading the file
triggers an OS-level callback to the foreign client; if that client
isn't running (or has been uninstalled), the read fails.

**Real-case (UD-296/297/299 thread, 2026-04-30):** user pointed
`sync_root` at `C:\Users\gerno\InternxtDrive - <UUID>\` (Internxt
official-client mount). Opening a file like
`/dev/zvg/ZVG FileRepo/3A0C20B4...` produced
> "Die Datei kann nicht geöffnet werden. Stellen Sie Sicher, dass
> Internxt Drive auf Ihrem Computer ausgeführt wird."

unidrive's scanner correctly *sees* the placeholder (directory entry
is there), but an upload-side read would fail at byte-fetch time.

**Migration scenario this targets:** user has data in the *foreign*
provider's official client, points unidrive at the same tree to
**replace** that client (or to mirror to a different provider).
unidrive needs to either (a) refuse and tell the user how to
proceed, (b) skip dehydrated entries with a clear report, or (c)
force-hydrate before reading. Silent retry-storms or partial uploads
are not acceptable.

## Research question 1 — detect that sync_root sits inside a foreign client's mount

Per-platform / per-provider:

- **Windows + Cloud Files API** (universal across modern providers):
  `FILE_ATTRIBUTE_REPARSE_POINT` on the directory + a known
  `IO_REPARSE_TAG_CLOUD` on each placeholder file. Reparse-tag
  registry under `HKLM\SYSTEM\CurrentControlSet\Control\FileSystem`.
  This is the cleanest signal — it's how Explorer renders the
  cloud-icon overlay.
- **OneDrive (Windows):** registry `HKCU\Software\Microsoft\OneDrive\
  Accounts\Personal\UserFolder` (and Business variant). Syncroots
  under `SyncEngines\Providers\<...>`.
- **OneDrive (macOS):** `~/Library/Containers/com.microsoft.OneDrive`
  / `OneDrive*` folders under `~/`.
- **Google Drive (Windows):** Drive File Stream's drive letter (often
  `G:`) + `~/AppData/Local/Google/DriveFS/`. macOS: virtual mount
  under `/Volumes/`.
- **Dropbox:** `~/.dropbox/info.json` lists `personal` and `business`
  roots. Smart-Sync placeholders are similar.
- **iCloud Drive (macOS):** `~/Library/Mobile Documents/`. Dehydrated
  files have `.icloud` extension or extended attribute
  `com.apple.metadata:com_apple_ubiquity_unset_*`.
- **Box Drive:** `~/Box/` (or configurable). Box sets reparse points
  on Windows.
- **Internxt:** directory naming pattern `InternxtDrive - <UUID>`
  under `~/`, paired with a Windows Cloud Files reparse tag.

Detection strategies, ranked:

1. **Cloud Files API reparse tag** (Windows, modern OneDrive /
   Internxt / Box / Dropbox Smart Sync): one syscall per directory,
   tag identifies the provider unambiguously. Best signal.
2. **Path heuristic** (cross-platform fallback): match `sync_root`
   against a known list of well-known cloud-mount paths.
3. **Per-file attributes** (also see Q2): cheap to combine with
   the existing `Files.walkFileTree` pass.

## Research question 2 — detect a *file* as dehydrated

- **Windows:** `GetFileAttributesW` → `FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS`
  (0x00400000) or `FILE_ATTRIBUTE_RECALL_ON_OPEN` (0x00040000), often
  paired with `FILE_ATTRIBUTE_OFFLINE` (0x00001000). Java NIO
  `BasicFileAttributes` doesn't surface these — need JNA / sun.nio.fs
  reflection or shell out to PowerShell `Get-ItemProperty`.
- **macOS:** `xattr -l` for `com.apple.bird` (iCloud) or vendor-specific
  attributes. Files with `.icloud` extension are placeholders.
- **Linux:** less standard. rclone vfs has a cache; FUSE mounts vary.

The Windows Cloud Files API ones are the dominant case — most modern
desktop clients on Windows go through it.

## Research question 3 — behaviour once detected

Possible policies, not mutually exclusive:

a. **Refuse** at sync start: "sync_root sits inside <provider>'s
   managed area. unidrive can't safely sync from a foreign cloud's
   mount; copy the data to a regular folder first or set
   `allow_foreign_managed_root = true` in config.toml."
b. **Skip-and-report**: enumerate dehydrated entries during scan,
   exclude them from action plan, surface count in the dry-run
   summary ("skipping 47 dehydrated files; foreign client not
   running"). Re-run after foreign client is up to pick them up.
c. **Force-hydrate**: walk the tree and `Files.copy(file, /dev/null)`
   on each placeholder to trigger recall. Slow + bandwidth-heavy +
   doubles storage during sync; useful for migration but should be
   opt-in.
d. **Lazy-hydrate-on-read**: try to read normally, catch the recall
   failure, mark as transient skip, retry on next pass once foreign
   client is detected running.

For the migration use-case (user replacing the foreign client),
option (c) is the user's intent — they *want* the bytes locally.
For occasional overlap, (a) or (b) are safer defaults.

## Research question 4 — UX

- First-sync banner already exists (UD-296). Extend with a
  foreign-client-detected line:
  > `note: sync_root is inside Internxt's managed area
  > (placeholders may need foreign client running)`
- Surface dehydrated-skipped count in the per-action summary the
  same way conflicts and moves are.
- Document the migration playbook (foreign client running, opt in
  to force-hydrate, then uninstall foreign client) in `docs/`.

## Research question 5 — failure mode when foreign client isn't running

Reading a placeholder when its host client is offline gives an
`IOException` of some specific kind on Windows
(`ERROR_CLOUD_FILE_PROVIDER_TERMINATED` 0x8007016A or similar).
Currently `LocalScanner` has no `visitFileFailed` override (see
deferred E from the UD-296 thread) — a partial walk could go
silently wrong. A specific catch + classification would let unidrive
say "Internxt client appears stopped" instead of a generic IOError.

## Open implementation directions (each = a future ticket)

- **UD-???**: cross-platform `ForeignClientDetector` interface +
  Windows reparse-tag implementation. Returns
  `(provider, mountRoot, status)` for the configured `sync_root`.
- **UD-???**: per-file `isDehydrated(path: Path): Boolean` helper,
  Windows-first, used during scan to tag placeholders in
  `LocalScanner` output.
- **UD-???**: `allow_foreign_managed_root` config flag + abort-or-warn
  policy at sync start (depends on first ticket).
- **UD-???**: dehydrated-skip path in reconciler + summary
  reporting. Re-test on next run.
- **UD-???**: opt-in `--hydrate-first` mode that pre-recalls
  placeholders before the upload phase.
- **UD-???**: `visitFileFailed` override in `LocalScanner` (covers
  the dehydration-failure case + general partial-scan defence-in-depth;
  carry-over from the deferred-E pile).

## Out of scope

- Implementation. Land detectors and mitigations as their own UDs
  once the design here is settled.
- Foreign client orchestration (starting/stopping the foreign client
  programmatically). That's a footgun — leave it to the user.
- Two-way sync of placeholders (treating the foreign cloud as remote
  and a *different* unidrive provider as another remote). Different
  feature entirely.
---
id: UD-739
title: Normalize path strings (NFC) at all entry points to fix NFC/NFD mismatch causing spurious uploads
category: tooling
priority: high
effort: M
status: open
opened: 2026-04-30
---
**Why:** No Unicode normalization is applied to path strings anywhere in
unidrive — neither `LocalScanner` (which returns whatever bytes NTFS
gives via `Path.toString()`) nor any provider's `toCloudItem` /
path-building helpers (which return whatever the upstream API
returned). Reconciler does exact-string Map lookups
(`Reconciler.kt:25-44`). When a file's name contains an accented
character that can be encoded in two equivalent UTF-8 forms, the local
side and the remote side may not match, and the planner mis-classifies
the file as `Upload` (`localState=NEW + remoteState=UNCHANGED`).

**Real-case (2026-04-30, post-UD-296/297/299 dry-run on
inxt_gernot_krost_posteo):** dry-run plan included 29 spurious uploads
of files known to exist on both sides. Example:

* Local path: `/Documents/Calibre/Calibre-Import/Elektroinstallation in
  Wohngebäuden Handbuch für die Installationspraxis Dipl.-lng. Herbert
  Schmolke .pdf`
* Remote URL: `https://drive.internxt.com/file/fb49adb7-69d0-46c1-a579-21566995f076`
* Both refer to the same content; the file is dehydrated locally
  (Internxt placeholder).

The umlaut `ä` in `Wohngebäuden` can be encoded as:

* **NFC** (precomposed): `0xC3 0xA4` — 1 codepoint, 2 bytes
* **NFD** (decomposed): `0x61 0xCC 0x88` — 2 codepoints, 3 bytes

NTFS preserves whatever bytes the writer gave it, and every API may
return whichever form its server stored. Java's `Path.toString()`
does NOT normalize.

## Verification step (TODO — needs user data)

Run on the user's machine:

```powershell
$local = "Wohngebäuden"
$remote = "<plainName from Internxt API for fb49adb7-...>"
[System.Text.Encoding]::UTF8.GetBytes($local) | ForEach-Object { '{0:X2}' -f $_ }
[System.Text.Encoding]::UTF8.GetBytes($remote) | ForEach-Object { '{0:X2}' -f $_ }
```

Different byte sequences → confirms the hypothesis.

## Proposed fix

Normalize every path string to **NFC** (the form recommended by
Unicode Annex #15 and what Linux/macOS userland tools tend to use)
at every entry point into reconciler-visible state. Two entry
points:

1. `LocalScanner` — wrap `syncRoot.relativize(file).toString()` in
   `java.text.Normalizer.normalize(s, Form.NFC)` before adding to
   `seenPaths` / `changes`.
2. Each provider's `toCloudItem` / `fileToCloudItem` /
   `folderToCloudItem` — normalize the constructed `path` and `name`
   to NFC before returning the `CloudItem`. Affects:
   * Internxt (`InternxtProvider.kt:419, 434, 455`)
   * OneDrive (`GraphApiService.kt` toCloudItem)
   * HiDrive
   * WebDAV
   * SFTP
   * S3 / Rclone
3. Reconciler — defensive: also normalize keys on lookup. Belt+braces.

## Migration concern

Existing state.db entries from prior syncs were stored with whatever
form the original path arrived in. If we normalize on read going
forward, *some* paths may flip form between the DB and the new scan,
producing one round of spurious actions on the first sync after this
ticket lands.

Mitigation options:

a. One-shot DB migration: walk `sync_entries`, NFC-normalize every
   `path` column.
b. Lazy: ignore the issue; the planner will fix itself within one
   sync cycle (the spurious actions resolve to "no-op" since
   target paths match after normalization).
c. Document the risk; ask users to `--reset` if they see weirdness.

(a) is cleanest. Land it as part of the same ticket so users don't
need to know.

## Tests

* Unit: NFC and NFD forms of the same path are equal in
  `LocalScanner.scan()` output (synthesise the two byte forms in a
  temp dir; assert the same key in the result Map).
* Unit: `InternxtProvider.fileToCloudItem` with NFD `plainName`
  returns NFC `path`.
* Integration: round-trip through reconciler — local NFD + remote NFC
  → no Upload action.

## Out of scope

* Other normalization issues (case sensitivity is already handled
  via `getEntryCaseInsensitive`; trailing-space differences are
  separate).
* Backward-compatible reading (always normalize on read; the DB
  migration above handles legacy data).
---
id: UD-743
title: Brainstorm: rich pre-flight sync banner with src/target visualisation + action matrix + exec status
category: tooling
priority: low
effort: L
status: open
opened: 2026-04-30
---
**Status:** brainstorm — design proposal from a real user session, not
yet a build spec.

**Why:** The current sync banner is a single key=value line. Real-case
2026-04-30 user proposal: a richer ASCII layout that shows source,
target, direction, action breakdown, and execution status as a
structured pre-flight summary. Conceptually similar to a Windows
progress dialog, ASCII-rendered.

User-supplied mockup:

```
==============================================================================
                     UNIDRIVE SYNC OPERATION (SIMULATION)
==============================================================================

   [ LOCAL SOURCE ]                                   [ REMOTE TARGET ]
   Local Windows File System                          Internxt Cloud Drive
   (Profile: gerno)                                   (Profile: inxt_gernot...)
   -------------------------                          -------------------------
   C:\Users\gerno\InternxtDrive                       /19notte78
         |                                                 ^
         |                                                 |
         |--------------- UPLOAD-ONLY SYNC -----------------|
         |             (Local pushes to Remote)             |


==============================================================================
                    WHAT WOULD BE TRANSFORMED? (File Level)
==============================================================================
Because you used `--reset` combined with `--upload-only`, the tool ignores
previous sync history (state.db) and evaluates everything from scratch as a
one-way push from Local to Remote:

 [F] NEW/MODIFIED LOCALLY  ====== COPY/UPLOAD ======>  Added to /19notte78
 [X] DELETED LOCALLY       ====== DO NOTHING =======>  Retained on Remote
 [=] IDENTICAL FILES       ====== DO NOTHING =======>  Skipped

 * In strict upload-only modes, local deletions typically do not
   trigger remote deletions, protecting the remote as a backup.


==============================================================================
EXECUTION STATUS: [DRY-RUN ACTIVE]
   SIMULATION ONLY: No bytes are transferred.
   STATE UNTOUCHED: The local state.db is not modified.
==============================================================================
```

(Original mockup used emoji + box-drawing; ASCII-only here per UD-291
Windows-codepage lessons.)

## What it adds over the current banner

* **Source ↔ target visualisation** with the actual paths labelled by
  side. Current banner gives `sync_root=...` only, no remote-side
  parity.
* **Direction arrows.** Current banner says `mode=upload` but doesn't
  visually anchor "what flows where."
* **Per-flag action matrix.** Current banner does NOT explain what
  `--upload-only --reset` actually means for each file class. The
  user just had to learn this from UD-737 / UD-296 thread; an inline
  cheat sheet would short-circuit that.
* **Execution status callout.** `dry-run` is a single token in the
  current banner; the proposal makes the consequences explicit
  (no bytes transferred / state untouched).

## Open design questions

1. Width: 78-col cap? 100? Wrap on narrow terminals?
2. Verbosity tiers: full vs current single-line vs `--quiet` mode?
   Maybe a `--banner=full|short|quiet` flag (default short = current).
3. Profile labels: where does `Profile: gerno` for the local side
   come from? unidrive's profile is the *remote* side. Probably show
   only the remote profile and label local as "Local FS" / "Local
   Filesystem" with the path.
4. Action matrix: derived from `(syncDirection, propagateDeletes,
   reset)`. Need a small lookup table; compute and render at banner
   time.
5. Internationalisation? Probably no — keep CLI English only.
6. Should non-dry-run runs also get the rich banner? Probably yes,
   without the "SIMULATION" callout. Could replace `EXECUTION STATUS`
   with `LIVE: Bytes will transfer`.

## Implementation sketch

* New module: `core/app/cli/src/main/kotlin/.../BannerRenderer.kt`
  with a single `render(opts: BannerOpts): String`. Pure function
  for testability.
* `BannerOpts` data class holding profile name + type, syncRoot,
  syncPath, direction, dryRun, forceDelete, propagateDeletes, watch,
  reset.
* `SyncCommand` calls `BannerRenderer.render(opts)` once before
  `runBlocking { ... }`.

## Relationship

* Builds on UD-296 (banner content) and UD-735 (terminal width).
* If UD-741 (sync_path in banner) lands first, this rich version
  subsumes it — render via the new system.
* UD-240 covers the *runtime* progress story; this is the *pre-flight*
  story.

## Out of scope

Implementation. This ticket is for design discussion. Once the open
questions are answered, file the implementation as its own ticket.
---
id: UD-744
title: Bucketed ETA + per-provider item-count probe (UD-713 follow-up)
category: tooling
priority: medium
effort: M
status: open
opened: 2026-04-30
---
**Why:** UD-713 originally specified a comprehensive ETA story:
TTFB probe + optional bandwidth sample + per-provider count probes +
historical timings persisted in state.db + bucketed ETA display
(`<5m`, `5-15m`, `15-60m`, `>1h`).

UD-713 closed with a smaller subset: items-per-minute **throughput**
in the scan heartbeat (commit ca2ba97). Throughput alone is enough
for the "should I go to lunch?" decision when the operator knows
their dataset. Bucketed ETA is the next step up — it requires the
*total*, which neither LocalScanner nor most remote-delta APIs
expose without provider-specific work.

This ticket is the unimplemented remainder of UD-713.

## What's needed

1. **Per-provider total-count probe.** Where supported, query the
   provider for the total item count before scan starts. Optional
   for first-sync (no DB hint).

   - OneDrive Graph: `/me/drive/root?$count=true` returns
     `driveItemCount`. Cheap.
   - Internxt: there's a `/files/count` API or similar — verify; or
     piggy-back on the user/drive metadata call already in the
     auth flow.
   - HiDrive: `/quota` provides used bytes but not item count;
     directory tree walk is the only option (expensive).
   - WebDAV: PROPFIND with depth=infinity gives count via collection
     enumeration (expensive — defeats the heartbeat's purpose).
   - SFTP: not feasible without a full walk.

2. **DB-derived total fallback.** When the count probe is unavailable
   or the provider is offline, fall back to `db.getEntryCount()` as
   the expected total. Skip ETA on `--reset` (or virtual-reset
   per UD-738) since the shadow DB has 0 entries.

3. **Bucketed ETA computation.** Once `target` and current `count` and
   `elapsedSecs` are known:
   - `remaining = max(0, target - count)`
   - `rate = count / elapsedSecs.coerceAtLeast(1)` (items/sec)
   - `etaSecs = remaining / rate`
   - Bucket per UD-713 spec: `<5m / 5-15m / 15-60m / >1h`

4. **Display.** Append to existing heartbeat:
   ```
   Scanning local changes... 8412 of ~33000 items (4m 02s elapsed, ~2100 items/min, ETA: 5-15m)
   ```
   Suppressed when target is unknown (already current behaviour
   without this ticket).

5. **Historical timings.** Persist `last_full_scan_seconds` per
   provider in state.db (table `sync_state` already exists). Use as
   a sanity check / second-opinion against extrapolation, especially
   when the rate is unstable in the first few seconds.

## Where

* `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt`
  — add a `provider.estimateItemCount(): CapabilityResult<Long>?`
  capability call before `gatherRemoteChanges`. Pipe the answer to
  `reporter.onScanProgress` somehow (extra param? side channel?).
* New `Capability.ItemCount` capability, opt-in per provider.
* `core/providers/onedrive/.../GraphApiService.kt` — implement count
  probe.
* `core/providers/internxt/.../InternxtApiService.kt` — implement count
  probe.
* `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt`
  — extend `onScanProgress` signature OR accept the total via a new
  reporter call (`onScanTotalEstimate(phase, total)`).

## Acceptance

* On a sync against a provider that supports count probes, the user
  sees `ETA: <bucket>` in the heartbeat after >5s of scan time.
* On a `--reset` / virtual-reset / unsupported-provider scan, ETA is
  silently absent (current behaviour, throughput still shown).
* Historical timing persisted across runs; future runs can use it to
  validate the in-flight extrapolation.

## Priority / effort

**Medium priority, M effort.** Touches every provider. Defer until a
specific operator with multi-hour syncs asks. The throughput
shipped in UD-713 is the 80/20 fix; this is the polish.
---
id: UD-338
title: Lift token-refresh mutex+NonCancellable pattern to shared :app:core/auth
category: providers
priority: high
effort: M
status: open
opened: 2026-04-30
---
**From the 2026-04-30 provider-duplication survey (agent run after UD-748).**

Three near-identical `getValidToken` / `refreshToken` flows live in the
provider modules:

- `core/providers/onedrive/.../TokenManager.kt:88-149` — full version
  with UD-310 forceRefresh + UD-111 `lastRefreshFailure` recording.
- `core/providers/hidrive/.../TokenManager.kt:51-88` — UD-331
  NonCancellable wrap; missing UD-310 forceRefresh and UD-111 failure
  record.
- `core/providers/internxt/.../AuthService.kt:207-230` — UD-331
  NonCancellable wrap; missing UD-310 forceRefresh; has a
  `fetchRefreshedJwt` overridable seam for tests.

The skeleton is identical: `refreshMutex`, recheck-after-acquire,
`withContext(NonCancellable)` around the refresh + persist. The
divergence has already burnt time — UD-331 had to mirror UD-310 by
hand.

## Proposal

Extract a generic helper to `:app:core/auth/RefreshableCredentialStore.kt`:

```kotlin
class RefreshableCredentialStore<T>(
    private val isStale: (T) -> Boolean,
    private val refresh: suspend (T) -> T,   // network call
    private val persist: suspend (T) -> Unit,
)
```

with `currentValue: T?`, `forceRefresh: Boolean`, and a
`RefreshFailure` record matching UD-111's shape. HiDrive automatically
gets UD-310 forceRefresh + UD-111 failure recording for free.

## Acceptance

- All three providers consume the shared store; no duplicate refresh
  loops remain.
- HiDrive's silent `println("Token refresh failed")` (TokenManager.kt:82)
  becomes a UD-111-style failure record + log.warn.
- Tests pin the contract: stale-then-fresh path, refresh-failure
  recording, NonCancellable-survives-cancellation, force-refresh.

## Effort / agent-ability

**M effort**, agent-able partial — design contract (seam for
persistence, generic over credential type) needs human input first.

## Related

- **UD-310** (closed) — OneDrive forceRefresh.
- **UD-111** (closed/open?) — token-refresh failure telemetry.
- **UD-331** (closed) — NonCancellable wrap mirror across providers.
- **UD-336** (closed, sibling lift) — error-body helpers in same package.
---
id: UD-339
title: Per-call HTTP retry helper — unify across OneDrive/Internxt/WebDAV (likely subsumed by UD-330)
category: providers
priority: medium
effort: M
status: open
opened: 2026-04-30
---
**From the 2026-04-30 provider-duplication survey.**

Three different per-call retry-loop implementations on the same logical
surface (transient HTTP statuses + Retry-After + exponential backoff):

- `core/providers/onedrive/.../GraphApiService.kt:759-854` — inline
  401 + 429/503 retry with header `Retry-After` + JSON body
  `retryAfterSeconds` fallback.
- `core/providers/internxt/.../InternxtApiService.kt:491-526` —
  `retryOnTransient` with `TRANSIENT_STATUSES` + `RETRY_AFTER_REGEX`.
- `core/providers/internxt/.../InternxtApiService.kt:421-452` —
  `authenticatedGet` has its OWN separate retry on `[500, 503]` only —
  hidden bug-pit: the two Internxt code paths have different retry
  profiles.
- `core/providers/webdav/.../WebDavApiService.kt:181-242` — `withRetry`
  with header parsing, status set `[408, 425, 429, 500, 502, 503, 504]`.

UD-330 is open as the cross-provider retry-budget umbrella. This per-
call helper sits ABOVE the budget. **Likely subsumed by UD-330** —
coordinate before lifting.

## Proposal

Either fold into UD-330's planned shape, or extract
`:app:core/http/RetryPolicy.kt` with composable building blocks
(transient-status set, max-attempts, header parser, body parser,
backoff curve, jitter).

Interim mitigation regardless: bring Internxt's `authenticatedGet`
retry into agreement with `retryOnTransient` (same status set, same
backoff) so the two paths stop diverging.

## Effort / agent-ability

**M effort**, agent-able partial — must coordinate with UD-330 first.

## Related

- **UD-330** (open, parent umbrella) — HttpRetryBudget cross-provider.
- **UD-335** (closed) — Internxt retry on transient (introduced
  `retryOnTransient`).
---
id: UD-341
title: Lift Ktor streaming-download skeleton (prepareGet ->bodyAsChannel) to :app:core/http
category: providers
priority: medium
effort: M
status: open
opened: 2026-04-30
---
**From the 2026-04-30 provider-duplication survey.**

Three providers ship the same Ktor 3.x streaming-download skeleton
(`prepareGet().execute { resp -> resp.bodyAsChannel() ... 8 KiB ring
buffer write }`):

- `core/providers/onedrive/.../GraphApiService.kt:243-312` — full
  version with auth-retry, throttle handling.
- `core/providers/hidrive/.../HiDriveApiService.kt:120-164` — minimal
  variant, no retry, has UD-333 HTML guard.
- `core/providers/internxt/.../InternxtApiService.kt:231-282` —
  variant that threads `Cipher.update` between read and write.

Two providers still use the **unfixed Ktor 3.x trap**
(`response.body<ByteReadChannel>()` allocates the full content-length
into a single byte array, OOMs > 2 GiB):

- `core/providers/s3/.../S3ApiService.kt:55-72`
- `core/providers/webdav/.../WebDavApiService.kt:306-329`

## Proposal

`:app:core/http/StreamingDownload.kt`:

```kotlin
suspend fun streamToFile(
    client: HttpClient,
    url: String,
    dest: Path,
    transform: (ByteArray, Int) -> Pair<ByteArray, Int> = ::passthrough,
    contentTypeGuard: Boolean = true,
    requestBuilder: HttpRequestBuilder.() -> Unit = {},
): Long
```

Internxt's cipher.update flows through `transform`. Returns bytes-
written. UD-333 HtmlBodySniffGuard called when `contentTypeGuard=true`.

## Acceptance

- All five providers consume the shared helper.
- S3 + WebDAV no longer use `response.body<ByteReadChannel>()` — the
  Ktor 3.x trap is closed.
- Internxt's cipher-doFinal edge case is preserved (test fixture).

## Effort / agent-ability

**M effort**, agent-able partial — contract decision needed (does the
helper own retry? does it own parent-dir creation? does it know about
the HTML guard?). Once specified, lift is mechanical.

## Related

- **UD-329** (closed) — OneDrive streaming-download fix.
- **UD-332** (closed) — HiDrive streaming-download fix.
- **UD-333** (closed) — HTML guard.
---
id: UD-750
title: Route OAuth UX prints through an AuthInteractor seam (CLI vs daemon)
category: tooling
priority: high
effort: M
status: open
opened: 2026-04-30
---
**From the 2026-04-30 provider-duplication survey.**

OneDrive and HiDrive `TokenManager.authenticateWithBrowser()` and
Internxt `AuthService` print user-facing messages with raw `println()`.
In a daemon / IPC context these go to the wrong place — stdout, not
log, not IPC event. Worse: HiDrive does
`println("Token refresh failed: ${e.message}")` — silent failure
dropped to stdout, no log line, no MDC, no UD-111 record.

- `core/providers/onedrive/.../TokenManager.kt:54-56,71-78` —
  `println("Opening browser for authentication...")`,
  `println("URL: $authUrl")`, `println("Authentication successful!")`.
- `core/providers/hidrive/.../TokenManager.kt:37-39,82` — same
  prompts; line 82 is the silent-loss bug.
- `core/providers/internxt/.../AuthService.kt:86` —
  `System.err.println("Warning: no console available — password will
  be echoed")`.

## Proposal

Define `AuthInteractor` in `:app:cli` (or `:app:core` if MCP server
also needs it):

```kotlin
interface AuthInteractor {
    fun browserAuthStarting(url: String)
    fun browserAuthSucceeded()
    fun tokenRefreshFailed(provider: String, e: Throwable)
    fun consoleEcho(warning: String)
}
```

`AuthCommand` implements with println. Daemon implements with
log.warn + IPC event. Pass to `TokenManager` via constructor.

HiDrive's silent `println("Token refresh failed")` line 82 becomes
`interactor.tokenRefreshFailed(...)` + a UD-111 `lastRefreshFailure`
record (the OneDrive treatment HiDrive currently lacks — see also
finding 2.1).

## Acceptance

- All three providers route prints through `AuthInteractor`.
- Daemon path emits log.warn + IPC events instead of stdout.
- HiDrive's silent token-refresh failure becomes visible in
  `unidrive.log`.

## Effort / agent-ability

**M effort**, agent-able partial — design call needed for daemon-
side contract (does `browserAuthStarting` open the URL itself,
or does the daemon refuse OAuth flow entirely? what's the IPC
event shape?).

## Related

- **UD-111** (open) — token-refresh failure telemetry.
- **2.1 token-refresh-mutex-pattern** (sibling) — addresses the
  silent-failure pit at the same time.
---
id: UD-753
title: Move per-operation debug log lines (Download/Upload/Delete/Move) to engine via decorator
category: tooling
priority: low
effort: S
status: open
opened: 2026-04-30
---
**From the 2026-04-30 provider-duplication survey.**

Same per-operation log shape repeated across providers. Five providers
each emit `Download: X` / `Upload: X (N bytes)` / `Delete: X` /
`Move: X -> Y` at debug. The wrapping engine call already knows the
path + bytes; logging there once would suffice.

- `core/providers/onedrive/.../GraphApiService.kt:207, 432, 451, 487`
- `core/providers/hidrive/.../HiDriveApiService.kt:111, 176, 216, 252, 277, 301`
- `core/providers/s3/.../S3ApiService.kt:52, 85, 133, 161`
- `core/providers/webdav/.../WebDavApiService.kt:294, 349, 418, 439, 460`

## Proposal

Either:

(a) Wrap providers in a `LoggingCloudProvider` decorator that emits
the debug lines around delegate calls. Drop per-provider lines.

(b) Just log at the engine `applyAction` call site. Drop per-provider
lines.

## Acceptance

- `git grep "log.debug.\"(Download|Upload|Delete|Move):"` in
  providers/ shrinks to provider-specific detail only (e.g.
  HiDrive's home-relative dir resolution is genuinely useful and
  stays).

## Effort / agent-ability

**S effort**, agent-able partial — judgment call: which provider-
specific log details are worth keeping vs deleting?

## Related

- **1.2 delta-log-line** (sibling) — same shape lift.
- **1.3 provider-auth-banner** (sibling) — same shape lift.
---
id: UD-754
title: Research: automate Kotlin code formatting so ktlint passes near-100% post-edit
category: tooling
priority: low
effort: M
status: open
opened: 2026-04-30
---
**Research item — can we automate Kotlin code formatting so contributors
(human or agent) run a single script post-edit and ktlint passes
near-100% of the time?**

## Symptom

Every non-trivial `.kt` edit in this repo currently requires a manual
`scripts/dev/ktlint-sync.sh --module <m>` pass before committing,
because `ktlint` flags wildcard imports / blank-line / property-naming
violations that the IDE doesn't surface during edit. The
`docs/dev/lessons/ktlint-baseline-drift.md` lesson explicitly
acknowledges line-anchored baselines re-surface violations on
unrelated edits.

In the past two sessions alone the agent invoked ktlint-sync 6+
times after small edits — every one a routine "fix imports / shift
baseline lines" pass, none required human judgement.

## Question

Can we eliminate (or near-eliminate) this loop by:

1. **Pre-commit format hook** that runs `ktlintFormat` (the auto-fix
   variant of ktlintCheck) on every staged `.kt` file before the
   commit lands. Either via:
   - A git pre-commit hook installed by `scripts/dev/setup.sh`, OR
   - An on-save IDE setting documented in `CONTRIBUTING.md`, OR
   - A `gradle.kts` task that the user runs before `git add`.

2. **Commit-time validation hook** that fails the commit if `.kt`
   files are touched but their module's ktlint baseline is stale.
   Force the contributor to either run format OR explicitly opt
   out (`--no-verify` with a warning).

3. **Lift the baseline-shift fragility entirely** by switching to
   a baseline-less ktlint mode where the rules either pass or
   require explicit suppress comments. This is a bigger lift and
   may require auditing every existing baseline entry.

## Spike

Spend a half-day on each of the three options. Specifically:

- Option 1: prototype a pre-commit hook in `scripts/dev/git-hooks/`
  that auto-formats staged files. Measure: how many of the past
  two weeks' commits would have been auto-fixed?
- Option 2: extend `ktlint-sync.sh` to detect "would-shift-baseline"
  via `git diff --stat` heuristics. Output a one-line warning when
  a `.kt` edit hasn't been baseline-rebuilt.
- Option 3: build a fresh module from scratch (no baseline, fresh
  rules), measure how many violations exist in current code if no
  baseline is allowed. Decide whether the audit cost is worth the
  fragility savings.

## Acceptance

- One of the three options ships as a contributor-visible rule
  (hook installed, lesson updated, or rules audited).
- ktlint-related cycles in agent sessions drop measurably (ideally
  zero on routine edits).
- Document the chosen option in `docs/dev/lessons/`.

## Why this matters

Routine ktlint cycles are a cache-invalidation cost — each
ktlint-sync invocation in an agent session is ~15 s wall-clock plus
context for "did the baseline regenerate?" verification. Across a
session of 5 commits that's ~75 s of pure noise. Cumulatively, this
is one of the larger remaining sources of "agent did mechanical
maintenance instead of substantive work" in the repo.

## Effort / priority

**Low priority, M effort.** Quality-of-life, not a correctness or
user-impact issue. Worth scheduling against the next available
quiet slot. May fold into a broader "developer ergonomics" pass
alongside `unidrive-log-anomalies` skill UX, etc.

## Related

- `docs/dev/lessons/ktlint-baseline-drift.md` — current state lesson.
- `scripts/dev/ktlint-sync.sh` — current manual script.
- UD-728 (closed) — backlog tooling automation; same spirit.
---
id: UD-755
title: Research: mechanize docs <-> code drift detection (CI gate or hook)
category: tooling
priority: low
effort: M
status: open
opened: 2026-04-30
---
**Research item — detect docs ↔ code drift mechanically before it
poisons the next contributor's mental model.**

## Why

The 2026-04-30 lift session (UD-336/337/340/342/343/347/349/351)
moved six helpers from provider-private locations into `:app:core`.
Without an active sweep, the following `.md` artifacts would have
silently drifted:

- **`docs/ARCHITECTURE.md`** — "Key files" list silently
  not-mentioning a growing shared-helpers layer (`:app:core/http`,
  `:app:core/auth`, `:app:core/io`).
- **Open ticket UD-344** — body said "Subsumes 3.3 posix-permissions-
  helper" after UD-347 (3.3) shipped independently.
- **Open ticket UD-348** — references "2.7 oauth-pkce-helpers" by
  the survey handle, never updated to the now-closed UD-351.
- **Cross-package KDoc references** — three providers'
  `setPosixPermissionsIfSupported` copies pointed at each other in
  KDoc comments; the helpers are now gone, the KDoc anchors broken.

The user articulated the principle in one line:
**"sync knowledge ↔ code ↔ .md's — there can only be one truth"**.
The lesson is now codified at
[docs/dev/lessons/one-truth-sync-discipline.md](../dev/lessons/one-truth-sync-discipline.md).

This ticket is about **mechanizing** the sweep so the lesson doesn't
depend on agent / human discipline alone.

## Hypotheses to spike

### Option A — pre-push git hook

Hook script that fails (or warns) when `git diff --name-only main..`
shows source `.kt` paths but no docs paths. Heuristic:

- if the branch touches `core/app/core/src/main/**/*.kt` AND
- it doesn't touch any of `docs/ARCHITECTURE.md`, `docs/SPECS.md`,
  `docs/dev/lessons/`, OR
- it touches `core/providers/*/src/main/**/*.kt` AND
  doesn't touch `docs/backlog/BACKLOG.md` (lifts often demand
  follow-up tickets)
- → `echo` a one-line warning with `--no-verify` escape hatch.

Cheap to implement; high false-positive rate (many code changes
genuinely don't need docs); needs tuning.

### Option B — CI gate that resolves KDoc cross-package refs

Walk every `.kt` file's KDoc, extract `[fully.qualified.name]`
anchors, verify each one resolves to an existing public symbol.
Catches "see [org.krost.unidrive.onedrive.setPosixPermissionsIfSupported]"
when the helper has moved. Probably needs Dokka or a custom
lightweight parser.

Higher implementation cost; near-zero false positives once tuned.

### Option C — periodic agent sweep

A scheduled CI job that runs an "audit drift" agent prompt across
the repo every N days, like the 2026-04-30 provider-duplication
survey but for docs ↔ code. Outputs a triage list as a GitHub issue.

Cheapest to start, but produces a backlog of items that may go
stale themselves.

### Option D — scoped post-commit reminder

Single-line message in the post-commit hook output when a `.kt`
file under a "watched" path is touched: "Reminder: did you sweep
docs/ARCHITECTURE.md and any open ticket bodies that reference
the changed symbol?" Zero blocking, just nudges.

Lowest friction; relies on the contributor reading the message.

## Acceptance

- One of A–D ships as a contributor-visible mechanism.
- The next "lift to `:app:core`" or "rename a public symbol" change
  in an agent session generates a doc-sweep prompt without the user
  having to articulate the principle from scratch.
- Documented in
  [docs/dev/lessons/one-truth-sync-discipline.md](../dev/lessons/one-truth-sync-discipline.md)
  alongside the manual discipline.

## Why this matters (in agent terms)

A fresh-context agent reads CLAUDE.md, follows the doc trail, takes
the code's current state as ground truth. When the trail
contradicts the code, the agent confidently builds on the wrong
premise. By the time the contradiction surfaces in a build error or
runtime symptom, the agent has spent context and tool calls on an
inconsistent foundation. Multiplied across daily sessions, this is
a real cost.

## Effort / priority

**Low priority, M effort.** Quality-of-life. Pairs naturally with
UD-754 (auto-format Kotlin). Both are "developer-ergonomics"
research items — a future quiet slot can spike all three options
cheaply and pick whichever ships fastest.

## Related

- [docs/dev/lessons/one-truth-sync-discipline.md](../dev/lessons/one-truth-sync-discipline.md)
  — the manual-discipline counterpart.
- **UD-754** (open) — auto-format Kotlin codebase. Sibling
  developer-ergonomics ticket.
- **UD-728** (closed) — backlog tooling automation. Same spirit.
---
id: UD-816
title: IpcServerTest broadcast races runTest virtual time vs real UDS I/O
category: tests
priority: medium
effort: S
status: open
opened: 2026-05-01
---
**Surfaced 2026-05-01 on sg5 during `./gradlew build`.**

## Symptom

`IpcServerTest.broadcast sends message to connected client` fails on
sg5 (JDK 21 jbrsdk on Linux) with an empty assertion:

```
AssertionError: Expected event in:
        at IpcServerTest.kt:78
```

## Root cause

The test wraps real Unix-domain-socket I/O inside `runTest { ... }`:

```kotlin
runTest {
    server = IpcServer(socketPath)
    server!!.start(backgroundScope)
    delay(100)
    val client = connectClient()
    delay(100)
    server!!.emit("""{"event":"test"}""")
    val received = readFromClient(client)
    assertTrue(received.contains(...))
}
```

`runTest` skips `delay(100)` in virtual time. The real UDS plumbing —
`AFUNIXSocketAddress`, `Channel<String>`-backed broadcast pump,
client-side `readFromClient` (which itself has `delay(50)` polls) — runs
in real wall-clock. Server fires `emit()`, client's read races the
broadcast.

CLAUDE.md global guidance line: *"`runTest` with real NIO operations
will hang; use virtual time or mocks."* Here it doesn't hang, it races.

## Proposed fix

Replace `runTest` with `runBlocking(Dispatchers.IO)` for tests that
exercise real UDS I/O. Use condition-based waiting (poll the client
read until expected event appears or wall-clock timeout fires) instead
of `delay()` time-based fudging. ~6 tests in IpcServerTest.kt likely
need the same treatment.

```kotlin
@Test
fun `broadcast sends message to connected client`() = runBlocking(Dispatchers.IO) {
    server = IpcServer(socketPath)
    server!!.start(this)
    awaitCondition { Files.exists(socketPath) }
    val client = connectClient()
    server!!.emit("""{"event":"test","data":"hello"}""")
    val received = awaitCondition(timeout = 2.seconds) {
        readFromClient(client).takeIf { it.contains(""""event":"test"""") }
    }
    assertNotNull(received)
    client.close()
}
```

## Acceptance

- All `IpcServerTest` tests pass deterministically on sg5 + the
  Windows machine (current Linux red, Windows green per handover).
- No `runTest` use in any test that opens a real socket.
- A small `awaitCondition(timeout) { … }` helper in the test file
  (or lifted to `:app:core/test-fixtures` if other modules need it).

## Out of scope

- The other `runTest`-based tests in the module (most use only virtual
  time or fakes — not affected). Audit them as part of the fix but
  don't refactor unless they actually break.

## Related

- CLAUDE.md "Build System (Gradle/Kotlin)" line about runTest + NIO.
- UD-209a (open) — sibling test fixes also surfaced by same build.
- UD-817 (open) — sibling: CloudRelocator UD-286 cancel test race.
---
id: UD-817
title: CloudRelocatorTest UD-286 cancel test races default maxConcurrentTransfers=4
category: tests
priority: medium
effort: S
status: open
opened: 2026-05-01
---
**Surfaced 2026-05-01 on sg5 during `./gradlew build`.**

## Symptom

`CloudRelocatorTest.UD-286 - per-file CancellationException propagates
instead of being absorbed` fails:

```
AssertionError: walk should halt on cancellation; uploaded=[/never-reached.txt]
```

## Root cause

Test fixture has two files: `/cancelled.txt` (whose download throws
`CancellationException`) and `/never-reached.txt` (which should NOT
get uploaded if the cancel propagates correctly).

Production `CloudRelocator.migrate()` defaults to
`maxConcurrentTransfers = 4`. The walk launches both files'
upload coroutines via `launch { transferSemaphore.withPermit { … } }`
back-to-back. With concurrency = 4, both get permits immediately and
race in parallel.

`cancelled.txt`'s download throws CE → `outerScope.cancel(e)` fires →
walk halts. But by the time `cancel(e)` propagates,
`never-reached.txt`'s coroutine may already have completed download +
upload. Result: `target.uploadedPaths` contains `/never-reached.txt`,
test fails.

The production CE-propagation contract at
[CloudRelocator.kt:305-332](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt#L305)
is correct. The test fixture is racy.

## Proposed fix

Construct the relocator under test with `maxConcurrentTransfers = 1`
in the two UD-286 cancel tests. Forces sequential per-file
processing, so `cancelled.txt`'s CE always fires before
`never-reached.txt`'s launch.

```kotlin
val relocator = CloudRelocator(source, target, maxConcurrentTransfers = 1)
```

Add an inline comment explaining concurrency = 1 is required for
deterministic CE-vs-sibling ordering. Don't change production
defaults.

## Acceptance

- `UD-286 - per-file CancellationException propagates instead of being absorbed` passes deterministically.
- `UD-286 - listChildren CancellationException propagates from targetIndex` (the sibling test at line 432) reviewed for the same pattern; if it has only one fixture file the change is unnecessary.
- No production code change; only test construction args.

## Related

- UD-286 (closed) — original CE-propagation work.
- UD-289 (closed) — concurrency + structured-concurrency design.
- UD-816 (open) — sibling: IpcServerTest race surfaced same build.
---
id: UD-758
title: Docs sweep: remove Linux-setLength-IS-sparse claim post UD-209a
category: tooling
priority: low
effort: S
status: open
opened: 2026-05-01
---
**Surfaced 2026-05-01 on sg5 during `./gradlew build`.**

## Why

UD-209a found that `RandomAccessFile.setLength()` on JDK 21 jbrsdk
Linux is non-sparse (writes physical zeros). SPECS.md §0 currently
states:

> Even on Linux where `setLength` is sparse, apps opening the stub see
> NUL bytes, indistinguishable from corruption.

That phrasing assumes Linux setLength IS sparse. After UD-209a the
production `dehydrate()` no longer uses `setLength` — it uses
`FileChannel.position` past EOF — so the JDK-conditional caveat no
longer applies and the doc text is misleading.

Also affected:
- `PlaceholderManager.kt:40-46` (createPlaceholder rationale comment) —
  references `setLength` as the historical broken approach; the comment
  stays correct in spirit but should add a brief Linux+JDK-21 note for
  the next reader who wonders why we moved to FileChannel.
- ARCHITECTURE.md does not currently claim sparse-file behavior in
  narrative form, but if any future ADR cites JDK 21 sparse semantics
  this ticket should catch it.

## Scope

After UD-209a + UD-816 + UD-817 land:

1. SPECS.md §0 (Product at a glance, placeholder rationale paragraph) —
   replace the "on Linux setLength IS sparse" line with the FileChannel
   explanation. One paragraph rewrite.
2. PlaceholderManager.kt:40-46 KDoc — keep the UD-712 history reference
   but add UD-209a as the Linux JDK 21 footnote.
3. CHANGELOG.md `[Unreleased]` — add bullets `[UD-209a]`, `[UD-816]`,
   `[UD-817]`, `[UD-758]` under Fixed / Tests / Documentation per
   AGENT-SYNC.md §"CHANGELOG conventions".
4. Confirm no other doc references the "Linux setLength is sparse" claim
   (`grep -ri "setLength.*sparse\|sparse.*setLength" docs/` → expected
   empty after edits).

## Acceptance

- `grep -r "Linux where.*setLength" docs/` returns no matches.
- CHANGELOG.md `[Unreleased]` carries the four new bullets.
- A reader landing on PlaceholderManager.kt's createPlaceholder KDoc
  can find why the file uses `FileChannel.position` not
  `setLength` without re-deriving the JDK 21 trap.

## Related

- UD-209a, UD-816, UD-817 (open) — companions; this ticket closes the
  paper trail when they land.
- one-truth-sync-discipline.md — the lesson this ticket operationalises.
- UD-755 (open) — proposed mechanical drift detection; this ticket is
  the manual sweep UD-755 would automate.
