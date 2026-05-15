# Backlog

> Open items only. Closed items live in [CLOSED.md](CLOSED.md) (append-only). See [AGENT-SYNC.md](../AGENT-SYNC.md) for editing rules.

Each item is a frontmatter block + prose. Required fields: `id`, `title`, `category`, `priority`, `effort`, `status`, `opened`. IDs follow the range table in AGENT-SYNC.md and never reuse.

---

## Architecture / structural (UD-001..099)

---

## Security (UD-100..199)

---

## Core daemon (UD-200..299)

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

> **Out of scope for unidrive-cli.** 
> File new UI tickets there (or in the future UI repo), not here.

---

## Protocol / IPC (UD-600..699)

---

## Tooling / CI / docs (UD-700..799)

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
`/mnt/nas/nas_share/3A0C20B4...` produced
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
inxt_user):** dry-run plan included 29 spurious uploads
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
   C:\Users\gerno\InternxtDrive                       /userhome
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

 [F] NEW/MODIFIED LOCALLY  ====== COPY/UPLOAD ======>  Added to /userhome
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
id: UD-759
title: Salvage benchmark observatory pipeline (scripts/benchmark/) from pre-greenfield repo
category: tooling
priority: medium
effort: M
status: open
code_refs:
  - scripts/benchmark/
opened: 2026-05-01
---
**Salvage the multi-source benchmark observatory pipeline from the pre-greenfield repo (`logic-arts-official/unidrive` HEAD `b8e4223`, 2026-04-16).**

The old repo shipped a complete public-cloud-speed-ranking pipeline that produced the rankings displayed at `unidrive.krost.org`. The pipeline was lost during the greenfield restart (ADR-0008) and is currently absent from public.

## What's there

`scripts/benchmark/` in the old repo (Python + bash, no Kotlin coupling):

| File | Purpose |
|------|---------|
| `benchmark-collect.sh` | Cron-driven collector. Runs `unidrive provider benchmark`, reshapes JSON into a per-provider median structure, uploads to remote SFTP drop. |
| `benchmark-aggregate.py` | Aggregates collector JSONs from many machines/locations into `rankings.json`. Assigns A/B/C grades. 7-day rolling window with janitor for stale drops. |
| `benchmark-publish.py` | Renders rankings as HTML table with affiliate-tagged "Sign up" CTAs. Posts to WordPress via REST API. |
| `benchmark-healthcheck.py` | Standalone health probe. |
| `unidrive-benchmark.{service,timer}` | Daily 03:00 systemd timer, randomised 600 s jitter. |
| `providers.json` | 14-provider catalogue with country, GDPR status, free-tier, pricing, signup/affiliate URLs. |
| `tests/fixtures/` | Real captured collector output (hetzner-dc, kubuntu-home) — useful as integration-test fixtures. |
| `source.conf.example` | Per-source config (location, ISP, profile→provider_id map). |
| `tests/test_aggregator.py` | pytest covering aggregator. |
| `tests/test_publisher.py` | pytest covering publisher. |

## Why we want it back

The current public `ProviderMetadata` data class still has `userRating`, `benchmarkGrade`, `affiliateUrl` fields — these are **read** by `unidrive provider list` and `unidrive provider info` for display. But the **engine that produces the grades** is gone.

Today these fields are static defaults in each `ProviderFactory` subclass. The old pipeline pushed measured grades back via WordPress publish — that monetisation/marketing surface was real and worked.

Note: the current backlog has a separate, narrower ticket for "cross-provider benchmark harness" (synthetic Gradle task, single source) — different shape. The observatory pipeline is the multi-source aggregator + publisher, not the one-shot Gradle harness.

## Acceptance

- `scripts/benchmark/` re-imported 1:1 from old repo (5 Python files, 1 shell, 2 systemd unit files, 1 providers.json, fixtures).
- `scripts/benchmark/README.md` updated for current repo paths (e.g. `core/` instead of root for the JAR).
- The `unidrive provider benchmark` CLI subcommand it depends on lives in `unidrive-closed:benchmark` — verify the JSON shape it emits still matches what `benchmark-collect.sh` reshapes.
- Optional: regenerate `providers.json` against current `ProviderMetadata` fields rather than the snapshot.

## Out of scope

- Re-deploying the cron to sg5 — this ticket is only about getting the code back into the repo.
- Re-validating the WordPress endpoint (`unidrive.krost.org`) — separate operational concern.
- Closed-side benchmark code (lives in `unidrive-closed:benchmark`).

## Provenance

`logic-arts-official/unidrive` HEAD `b8e4223` (2026-04-16), `scripts/benchmark/`. Pre-dates ADR-0008 greenfield restart by ~2 weeks.
---
id: UD-760
title: Salvage Nautilus/Nemo/Dolphin context-menu integration
category: tooling
priority: medium
effort: S
status: open
code_refs:
  - scripts/nautilus/
  - scripts/install-menus.sh
opened: 2026-05-01
---
**Salvage the file-manager context-menu integration from the pre-greenfield repo.**

Old `unidrive` (CHANGELOG #110) shipped right-click → UniDrive → Hydrate / Dehydrate / Pin / Unpin for Nautilus (GNOME Files), Nemo (Cinnamon), and Dolphin (KDE). All three file managers, one dispatcher script.

## What's there

`scripts/nautilus/` and `scripts/install-menus.sh` in the old repo:

| File | Purpose |
|------|---------|
| `scripts/nautilus/unidrive-menu.sh` | Dispatcher (~190 lines). Parses `config.toml`, resolves which profile a path belongs to (sync_root match), computes remote path, invokes `unidrive -p <name> {get|free|pin|unpin} <remote-path>`. Handles all three FM selection conventions: `$NAUTILUS_SCRIPT_SELECTED_FILE_PATHS`, `$NEMO_SCRIPT_SELECTED_FILE_PATHS`, Dolphin positional `%F`. |
| `scripts/nautilus/UniDrive — Hydrate` | Thin wrapper script (~5 lines) calling dispatcher with `hydrate` action. |
| `scripts/nautilus/UniDrive — Dehydrate` | Same pattern, `dehydrate` action. |
| `scripts/nautilus/UniDrive — Pin` | Same pattern, `pin` action. |
| `scripts/nautilus/UniDrive — Unpin` | Same pattern, `unpin` action. |
| `scripts/install-menus.sh` | One-shot installer. Drops Nautilus + Nemo entries under `~/.local/share/{nautilus,nemo}/scripts/UniDrive/`. Generates Dolphin `unidrive.desktop` ServiceMenu in `$XDG_DATA_HOME/kio/servicemenus/`. Auto-detects which FMs are installed. |

## Why we want it back

This is desktop polish that closes a real UX gap on placeholder-based sync: most users want point-and-click hydration. The dispatcher is non-trivial — it correctly resolves a local file path to its `(profile, remote_path)` tuple by parsing TOML, which is the only reason it works for arbitrary multi-profile setups.

The CLI commands it depends on (`get`, `free`, `pin`, `unpin`) all survived the greenfield restart, so the dispatcher should still work as-is.

## Acceptance

- `scripts/nautilus/` and `scripts/install-menus.sh` re-imported 1:1.
- Smoke test: install on the dev machine, right-click a placeholder, hydrate, verify file content downloads.
- Optional: Nautilus's "Open Sync Folder" extension (separate from the menus) — out of scope for this ticket.

## Provenance

`logic-arts-official/unidrive` HEAD `b8e4223` (2026-04-16), `scripts/nautilus/` + `scripts/install-menus.sh`.
---
id: UD-761
title: Salvage end-user installer (dist/install.sh + systemd unit)
category: tooling
priority: low
effort: S
status: open
code_refs:
  - dist/install.sh
  - dist/unidrive.service
opened: 2026-05-01
---
**Salvage the end-user one-shot installer (`dist/install.sh` + `dist/unidrive.service`) from the pre-greenfield repo.**

Current public has the `:app:cli:deploy` Gradle task which works for developers, but no end-user-facing installer. `git clone && ./gradlew :app:cli:deploy` requires JDK + Gradle + understanding the toolchain, which is friction for the "I just want to try this" path.

## What's there

Old repo's `dist/`:

| File | Purpose |
|------|---------|
| `dist/install.sh` | 57-line bash. Drops fat JAR into `~/.local/lib/unidrive/`, generates `~/.local/bin/unidrive` wrapper, mkdirs `~/.local/share/unidrive/`, copies systemd unit, runs `systemctl --user daemon-reload`. Prints next-steps including `systemctl --user enable --now unidrive` and the vault-env recipe. |
| `dist/unidrive.service` | systemd user unit. `ExecStart=%h/.local/bin/unidrive sync --watch`, `Restart=on-failure`, `RestartSec=30`, `EnvironmentFile=-%h/.config/unidrive/vault-env`. |
| `dist/uninstall.sh` | Symmetric uninstaller (referenced in CHANGELOG, content unverified). |

## Why we want it back

`./gradlew :app:cli:deploy` is fine for the dev loop but it:
1. Requires JDK on the user's machine just to install (they only need a JRE to run).
2. Couples installation to the source tree.
3. Doesn't print clear next-steps.

A standalone `install.sh` that consumes a pre-built fat JAR is the natural artefact for a future GitHub release (`gh release download v0.1.0 unidrive.jar && ./install.sh unidrive.jar`).

## Acceptance

- `dist/install.sh` and `dist/unidrive.service` re-imported, paths updated for current repo layout (the old script assumes JAR at `cli/build/libs/`; current is `core/app/cli/build/libs/`).
- Optional: the script accepts a JAR path argument so it works against a downloaded release artefact, not just a local build.
- Smoke test on a clean Ubuntu container.
- Document at `docs/user-guide/install.md` (or wiki page).

## Out of scope

- Distribution packaging (deb/rpm/AppImage/snap) — separate tickets if needed.
- Windows installer — see `BACKLOG_IDEAS_UI.md` W16 (jpackage MSI).

## Provenance

`logic-arts-official/unidrive` HEAD `b8e4223` (2026-04-16), `dist/`.
---
id: UD-762
title: Salvage lightweight doc-drift checker (check-docs.sh)
category: tooling
priority: low
effort: S
status: open
code_refs:
  - scripts/ci/
opened: 2026-05-01
---
**Salvage the lightweight doc-drift checker `check-docs.sh` from the pre-greenfield repo.**

The old repo's `check-docs.sh` is a 67-line shell script with 6 grep+regex checks for the most common drift patterns between docs and code. Cheap pre-commit hook material.

## What's there

`check-docs.sh` in the old repo, with these specific checks:

1. **Kotlin version** in `gradle/libs.versions.toml` matches `ARCHITECTURE.md`.
2. **Module count** in `CLAUDE.md` ("Eight Gradle modules", etc.) matches `settings.gradle.kts`.
3. **CLI subcommand list** in `CLAUDE.md` matches `@Command(name=...)` annotations in `Main.kt`.
4. **`SyncAction` sealed-subtypes count** matches the number cited in `CLAUDE.md`.
5. **Provider modules** in `settings.gradle.kts` match `CLAUDE.md` provider list.
6. **Logback version** in `gradle/libs.versions.toml` matches `ARCHITECTURE.md`.

Each check that fails prints a one-line `FAIL: ...` with the mismatch. Total error count printed at the end. Exit code is the count of failures.

## Why we want it back

Current public has `scripts/dev/` with proper MCP servers (`backlog-mcp`, `gradle-mcp`, etc.) and `scripts/ci/` with deeper checks. But there's no lightweight pre-commit-friendly script for the cheap-to-detect doc drifts. The old script catches the "I bumped Kotlin in `libs.versions.toml` but `ARCHITECTURE.md` still says 2.0.21" class of drift in <1 second with no Gradle invocation.

This complements (not replaces) the heavier MCP checks. The right home is a pre-commit hook + a `scripts/ci/` companion.

## Acceptance

- `scripts/ci/check-docs.sh` (or similar location, agree on placement) re-imported and adapted to current repo layout:
  - JAR path now `core/app/cli/build/libs/` not `cli/build/libs/`.
  - Module count check should match `settings.gradle.kts` includes including all 13 current modules.
  - Add a check for `docs/AGENT-SYNC.md` ID-range table consistency with what `scripts/dev/backlog.py` knows about.
- Wired into `scripts/dev/pre-commit/` if appropriate.
- Run in CI as part of the lint pass.

## Out of scope

- Replacing the existing MCP-based doc tooling — this is an addition, not a replacement.
- KDoc/Javadoc consistency — out of scope of the original script.

## Provenance

`logic-arts-official/unidrive` HEAD `b8e4223` (2026-04-16), `check-docs.sh`.
---
id: UD-763
title: Restore lost reference docs (WEBHOOKS, IPC-PROTOCOL, TEST-CHECKLIST)
category: tooling
priority: medium
effort: S
status: open
code_refs:
  - docs/dev/
opened: 2026-05-01
---
**Restore the three operational reference docs that didn't survive the greenfield restart: `WEBHOOKS.md`, `IPC-PROTOCOL.md`, `TEST-CHECKLIST.md`.**

Each was a self-contained reference doc, not a backlog or session note, so they're salvage candidates rather than archival.

## What's missing

| Old path | Size | Why we want it |
|----------|------|----------------|
| `docs/WEBHOOKS.md` | 4.5 KB | OneDrive Graph webhook NAT setup recipes (ngrok, cloudflared quick + named tunnels, serveo, production reverse proxy), troubleshooting steps, security notes. The current SubscriptionRenewalScheduler is in code but the user-facing setup doc is gone. |
| `docs/IPC-PROTOCOL.md` | 2.3 KB | Full NDJSON event schema with every field documented + a minimal Kotlin client snippet. Useful as the public contract for any third-party tray/UI that subscribes to the daemon socket — relevant given ADR-0013 deliberately keeps that surface open for community tools. |
| `docs/TEST-CHECKLIST.md` | 8.6 KB | 53-step manual ADD-friendly test checklist organised in 9 parts (automated → CLI smoke → localfs roundtrip → trash/versioning → OneDrive share+webhook → relocate → vault → profiles → backup wizard). Pre-release sweep scaffolding. |

## What changed since they were written

- WEBHOOKS.md mentions auto-renewal as TODO. Current code has `SubscriptionRenewalScheduler` (closed: see CLOSED.md). Update the doc to reflect that.
- IPC-PROTOCOL.md describes the event schema as it was 2026-04-16. Verify current `IpcProgressReporter` events match; update if they diverged.
- TEST-CHECKLIST.md references the closed `BenchmarkCommand` and `provider benchmark` CLI which now lives in `unidrive-closed`. Either skip those steps or document the unidrive-closed dependency.

## Acceptance

- `docs/dev/webhooks-nat-setup.md` — port `WEBHOOKS.md`. Add a "current state" section noting auto-renewal works.
- `docs/dev/ipc-protocol.md` — port `IPC-PROTOCOL.md`. Verify event field list against `IpcProgressReporter.kt` and `SyncReason.kt`. Mark as the contract for third-party tray clients.
- `docs/dev/manual-test-checklist.md` — port `TEST-CHECKLIST.md`. Trim the OneDrive-specific parts that no longer make sense, or mark them as "requires `unidrive-closed`".
- Cross-link from `docs/SPECS.md` and `docs/ARCHITECTURE.md`.

## Out of scope

- `WINDOWS-BACKLOG.md` — see `BACKLOG_IDEAS_UI.md` (lives there because all of W11–W16 are UI/Desktop concerns).
- Pure changelogs and session-handover docs — those are tied to the old repo's commit history; not salvage candidates.

## Provenance

`logic-arts-official/unidrive` HEAD `b8e4223` (2026-04-16), `docs/{WEBHOOKS,IPC-PROTOCOL,TEST-CHECKLIST}.md`.
---
id: UD-765
title: Comprehensive CHANGELOG audit: pre-greenfield vs current code
category: tooling
priority: medium
effort: M
status: open
code_refs:
  - docs/CHANGELOG.md
  - docs/backlog/CLOSED.md
opened: 2026-05-01
---
**Track the systematic audit of the pre-greenfield CHANGELOG against current public code, and any follow-ups it surfaces.**

The CHANGELOG of `logic-arts-official/unidrive` HEAD `b8e4223` (2026-04-16) has 90+ "Added" / "Changed" / "Fixed" bullets representing work that was shipped and tested before the greenfield restart (ADR-0008). On 2026-05-01 a sample-based audit was run; this ticket tracks completing it comprehensively + filing fix tickets for anything that regressed.

## Audit mechanics

For each CHANGELOG bullet, grep current public code for the canonical symbols / config keys / CLI subcommand names and confirm:

1. **Symbol present** — class/function exists at expected path.
2. **Wired** — referenced by SyncEngine / CLI / MCP somewhere, not orphaned.
3. **Tested** — there's a test class with `Test` suffix referring to it.

If any of (1)(2)(3) fails: file a follow-up ticket, link it here.

## Sample audit done 2026-05-01 (positive coverage)

These are confirmed surviving (symbol present + wired + tested):

`#1, #5, #24, #25, #26, #27, #28, #33, #34, #35, #36, #37, #41, #42, #43, #44, #46, #47, #48, #49, #52, #54, #57, #66, #73, #74-grade-fields, #75, #76, #78, #79, #82, #88, #89, #90, #91, #92, #94, #96, #97, #98, #99, #100, #101, #102, #103, #104, #105, #111, #115, #116, #118, #120, #121, #123, #129, #130, #131, #132, #133, #136, #137, #143, #144, #145, #146, #147` plus `SubscriptionRenewalScheduler` (new post-snapshot, supersedes the old "auto-renewal not implemented" caveat).

## Sample audit found regressed (filed)

- **UD-764** — `#122` git commit hash in `unidrive log` entries lost.

## Sample audit found intentionally moved

- **`#74` — `provider benchmark` CLI subcommand** is in `unidrive-closed:benchmark` module, not public. Closed-source by design (benchmark CLI is part of the closed CLI bundle). Not a regression.
- **`#37` — `xtra-init` / `xtra-status`** moved from top-level CLI to `vault xtra-init` / `vault xtra-status` subcommands. Behaviour preserved.

## Remaining work

Comprehensive sweep — not the sample. Focus areas:

1. Walk every "Added" entry in `[Unreleased]` (60+ items) and `[0.1.0]` (40+ items).
2. For each, identify the load-bearing symbol/key and grep current code.
3. Cross-check with `docs/backlog/CLOSED.md` — many entries map to closed UD-### tickets and may have been re-implemented under a different name.
4. File regression tickets for losses, file "moved" notes here for re-orgs that aren't true losses.

Useful starting points:
- `git log --oneline main..origin/main` — but won't help (greenfield commit is the floor).
- `git log --all --grep='UD-2..\|UD-3..'` — see how many tickets came from re-implementation vs new design.
- `docs/SPECS.md` "Intent vs code" matrix — already does adjacent work.

## Acceptance

- A `docs/dev/2026-05-changelog-audit.md` report with one row per old CHANGELOG entry: status `present | regressed | moved | replaced | obsolete`, link to current code path or successor ticket.
- Every `regressed` entry has a follow-up UD-### filed (priority calibrated to actual user impact, not just feature parity).
- Audit report committed; this ticket closes pointing at the report.

## Out of scope

- Re-implementing every regressed feature — that's per-ticket work.
- The closed-source `provider benchmark` subcommand — out of scope, lives in `unidrive-closed`.

## Provenance

Sample audit 2026-05-01. Source: `logic-arts-official/unidrive`@`b8e4223`, `docs/CHANGELOG.md`.
---
id: ADR-0014
title: v0.1.0 release surface, post-amendments
status: accepted
date: 2026-05-01    # or whatever date this is filed
consolidates: ADR-0008, ADR-0011, ADR-0012, ADR-0013
related: ADR-0001, ADR-0003, ADR-0006, ADR-0009
---
```

Body sections (each one paragraph max — ADR-0014 is a summary, not a redo):

1. **Context (1 paragraph):** The amendment chain ADR-0008 → 0011 → 0012 → 0013 settled into a coherent shape; this ADR records the consolidated surface.
2. **What ships in v0.1.0:**
   - **Tiers:** `core/` only (CLI + MCP + sync engine + 8 provider adapters).
   - **Platform:** Linux-only.
   - **IPC:** UDS broadcast (`IpcServer.kt`, `IpcProgressReporter.kt`). NDJSON push-only.
   - **Providers in the v0.1.0 quality gate:** `localfs`, `s3`, `sftp`. Others (OneDrive, WebDAV, HiDrive, Internxt, Rclone) present but at `status: preview` per ADR-0008.
   - **What's gone:** `ui/`, `shell-win/`, `protocol/`, `NamedPipeServer.kt`, `PipeSecurity.kt`, all CFAPI plumbing.
3. **Out of v0.1.0 (one-liners pointing at the right home):**
   - Windows / macOS support → ADR-0012 plus `BACKLOG_IDEAS_UI.md` for distribution-channel design (jpackage, Scoop, WinGet).
   - Tray UI / system-tray indicator → `BACKLOG_IDEAS_UI.md` (W11, W12).
   - Shell-extension overlays (Windows Explorer / Nautilus / Dolphin) → `BACKLOG_IDEAS_UI.md` (W14).
   - File-manager context menus (Linux) → `BACKLOG.md` UD-760 (in scope, just not yet salvaged).
4. **Consequences (1 paragraph):** New contributors get one ADR to read for the "what's shipping" question. The four amendment ADRs remain authoritative for the *why*.
5. **References:** Link each consolidated ADR + `BACKLOG_IDEAS_UI.md` + the `BACKLOG.md` salvage tickets (UD-759..765).

## Out of scope

- Rewriting any of ADR-0008..0013. They stay as-is.
- Defining v0.2.0 or later — separate ADR when that scope crystallises.
- Updating `ARCHITECTURE.md` or `SPECS.md` — those already reflect the post-amendment state via their own update path.

## Provenance

Discussed 2026-05-01 with maintainer. Identified during the post-CHANGELOG-audit retrospective as the highest-leverage doc fix for new-contributor onboarding.
---
id: UD-004
title: Decompose SyncEngine.kt into Reconciler + ScanCoordinator + ActionPlanner + ActionExecutor; enforce single-flight-per-profile in code
category: architecture
priority: high
effort: XL
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
opened: 2026-05-02
---
## Problem

`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt`
is 1249 LoC with 28 methods. The single function `doSyncOnce` is
467 lines. The class is the central reconciliation engine.

Concurrency footprint today: 8 atomic primitives
(`AtomicInteger`/`AtomicReference`) used to track per-pass progress
counters during a single sync. There are **no `synchronized` blocks,
no `Mutex`, no `@Volatile` fields** on the engine's state machine.

This is currently safe because `ProcessLock` serialises `syncOnce` per
profile (one in-flight sync per profile, enforced at the OS-pidfile
level outside SyncEngine itself). The "no engine-internal locking
needed" invariant lives only in the comment that introduces
`ProcessLock`. Nothing in SyncEngine fails loudly if that comment
ever stops being true.

## Risk

Any future change that:
- parallelises a scan pass (e.g. fanning out provider listings),
- introduces a second invocation surface that bypasses `ProcessLock`
  (e.g. an MCP tool that calls `doSyncOnce` directly without going
  through the CLI's `SyncCommand` path),
- or splits SyncEngine across multiple coroutine scopes,

…would race silently. The atomics in place catch counter contention,
not state-machine contention.

## Proposed split (to be designed in an ADR before any refactor)

A reasonable decomposition into smaller units with explicit
boundaries:

- **Reconciler** (already exists at
  `core/app/sync/.../Reconciler.kt`) — pure 3-way merge, no I/O.
  Already isolated; keep.
- **ScanCoordinator** — owns provider-side delta/snapshot fetch +
  local-side `LocalScanner` walk. Currently spread across
  `doSyncOnce` lines ~150-350 (approx; needs survey).
- **ActionPlanner** — turns reconciled diff into the action list
  (upload / download / conflict). Currently inline in `doSyncOnce`
  around lines 350-450 (approx).
- **ActionExecutor** — runs the planned actions, owns the retry /
  failure-collection state. Currently inline in `doSyncOnce` after
  line ~450 (approx).
- **SyncEngine** thin orchestrator — wires the above together,
  enforces the single-flight invariant in code (not in a sibling
  comment).

Each unit has one clear responsibility, communicates through a
typed interface, and can be tested independently with fakes.

## Acceptance criteria

- [ ] ADR (architecture range, e.g. ADR-0015) lays out the boundary
      contract for the four units above before any code moves.
- [ ] `SyncEngine.kt` < 300 LoC, no method > 100 LoC.
- [ ] Single-flight-per-profile invariant is enforced *in code*
      (e.g. private `Mutex` per profile, or a typestate that prevents
      reentry), not by a comment about `ProcessLock`.
- [ ] No regressions in `:app:sync:test` and the localfs round-trip
      smoke.
- [ ] Existing 1450-test suite still green, no test removed.

## Why this is a separate ticket, not done in PR #7

PR #7 is salvage + cleanup. This is a multi-week structural refactor
behind an ADR. Conflating them would obscure the salvage commits,
balloon the diff, and put real regression risk on a PR whose value
is "land what's already there cleanly".

## Out of scope

- Threading model overhaul (coroutine scope topology, cancellation
  contracts) — separate ADR if needed.
- Provider-side concurrency primitives — those live in each
  `*ApiService.kt`, not here.
---
id: UD-400
title: Sweep 10 os.name branches from non-test code; honour Linux-MVP per ADR-0011/0012; add CI guard
category: cli
priority: medium
effort: M
status: open
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:586
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:719
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:286
  - core/app/core/src/main/kotlin/org/krost/unidrive/io/OpenBrowser.kt:19
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/ConflictsTool.kt:25
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/SyncTool.kt:52
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt:252
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:166
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/PlaceholderManager.kt:194
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1202
opened: 2026-05-02
---
## Problem

ADR-0011 + ADR-0012 narrowed the MVP to **Linux**. Yet 10
`os.name` / `System.getProperty("os.name")` branches survive in
non-test code:

| File | Line |
|---|---|
| `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` | 586 |
| `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` | 719 |
| `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt` | 286 |
| `core/app/core/src/main/kotlin/org/krost/unidrive/io/OpenBrowser.kt` | 19 |
| `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/ConflictsTool.kt` | 25 |
| `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/SyncTool.kt` | 52 |
| `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` | 252 |
| `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt` | 166 |
| `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/PlaceholderManager.kt` | 194 |
| `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt` | 1202 |

Each branch typically guards a Windows-specific path (named-pipe
fallback, `chcp`, Win-only `OpenBrowser` shell-out, etc.).

Most of the protocol-level Windows surface was removed in
ADR-0012 (named-pipe transport gone), but these per-callsite
branches remained as defensive code "just in case the JVM happens
to be on Windows." That's not honest: the rest of the build
(launcher scripts, smoke test, CI matrix, documentation) treats
Windows as community-best-effort.

## Risk

- **Documentation drift.** ADR-0012 says Linux MVP; the code
  reads as if it serves three platforms.
- **Dead-code maintenance burden.** Every Windows branch is a
  thing future readers must understand and reviewers must
  consider when changing the surrounding code.
- **False-positive test scenarios.** A few of these branches
  (e.g. `IpcServer.kt:252`, `LocalScanner.kt:166`) sit on hot
  paths; their existence implies they're tested, but there is no
  Windows runtime in CI for the protocol/IPC layers.

## Proposed action

For each of the 10 sites:

1. Read the branch. Classify as one of:
   - **Pure Windows specifics with no Linux behaviour** → delete.
     Add `// removed in UD-XXX (Linux MVP per ADR-0012); restore
     when re-opening Windows tier per ADR-0012 §re-opening
     criteria.` if the deletion is non-obvious.
   - **Genuine cross-platform dispatch where Linux happens to
     share a branch** → keep, but rewrite to make Linux the
     happy path and Windows the unsupported fallback (e.g.
     `error("Windows is not supported in v0.1.0; see ADR-0012")`).
   - **Stale defensive guard from before ADR-0012** → delete
     unconditionally.
2. After the sweep, add a check to `scripts/ci/check-boundaries.sh`
   (or a new `scripts/ci/check-os-branches.sh`) that fails CI
   on any new `os.name` branch added to non-test code outside
   an explicitly allow-listed file (e.g. the Windows-specific
   parts of the launcher generator, if any).

## Acceptance criteria

- [ ] All 10 sites triaged, decisions documented either in
      individual commit messages or in this ticket's resolution.
- [ ] After cleanup, `grep -rE 'os\.name|System\.getProperty\("os'
      core --include="*.kt" | grep -v /test/` returns ≤ 2 hits,
      and each surviving hit has a comment justifying it.
- [ ] CI guard script wired into `.github/workflows/build.yml`
      (host-neutral fragment first, per `scripts/ci/README.md`).
- [ ] No behaviour change on Linux. Smoke test (`scripts/smoke.sh`)
      still passes after the sweep.

## Why this is a separate ticket

Each branch deletion is a small judgement call; bundling them
into a single sweep keeps the cognitive surface small per commit.
PR #7 is salvage scope; OS-cleanup is its own pass.
---
id: UD-354
title: Verify Internxt does not call computeSnapshotDelta; if confirmed, adopt or document exemption
category: providers
priority: medium
effort: M
status: open
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt
  - docs/ARCHITECTURE.md
opened: 2026-05-02
---
## Problem

`docs/ARCHITECTURE.md` documents `computeSnapshotDelta` as the
shared cross-provider helper for snapshot-mode delta computation
(every provider whose API does not expose `/delta` natively is
expected to use it). An external code-review pass flagged that
**Internxt does not call `computeSnapshotDelta`** despite being
a snapshot-mode provider, and that no exemption is documented.

This ticket has two parts: (a) verify the claim against current
code (the auditor's snapshot may pre-date refactors); (b) if the
claim holds, decide whether Internxt should adopt the helper or
whether its custom path needs an explicit ADR-level exemption.

## Verification gap

At ticket-filing time the gap was not personally re-verified
against current `:app:sync` code. Before any fix:

```bash
grep -rn "computeSnapshotDelta" core/app/sync/src/main/
grep -rn "computeSnapshotDelta\|InternxtSnapshot\|InternxtDelta" \
    core/providers/internxt/src/main/
```

…to confirm whether Internxt actually opts out, or whether the
helper is invoked from a layer the auditor didn't trace.

If verification disproves the claim, **close as wontfix** with a
note pointing at the call-site that proves the helper is used.

## If verified

Two acceptable resolutions:

1. **Adopt the helper.** Refactor `InternxtProvider` (or its
   `Snapshot.kt` peer) to call `computeSnapshotDelta`, deleting
   the duplicate implementation. Behaviour-preserving;
   golden-file test on a sample snapshot pair to lock parity.
2. **Document the exemption.** Add a short ADR (or a
   `docs/providers/internxt.md` § "Why Internxt does not use
   computeSnapshotDelta") explaining the technical reason
   (e.g. Internxt's API returns deltas in a non-snapshot shape
   that doesn't fit the helper's contract). Update
   ARCHITECTURE.md to acknowledge the exemption alongside the
   helper's documentation.

## Acceptance criteria

- [ ] Verification step run; result documented in this ticket.
- [ ] Either: helper adoption merged with a parity test, OR
      exemption documented in the right place
      (`docs/providers/internxt.md` + ARCHITECTURE.md cross-link).
- [ ] No regression in `InternxtIntegrationTest` and
      `InternxtNameSanitizationTest`.

## Context

Internxt test ratio at filing time was 0.46 (915 test LoC over
1968 main LoC) — second-lowest in the repo after the now-archived
HiDrive. Encryption is in scope (`InternxtCrypto.kt`), which
makes any silent semantic drift in the snapshot path
particularly costly to debug after the fact.
---
id: UD-355
title: Install RequestId + HttpRetryBudget in S3ApiService and WebDavApiService; replace ARCHITECTURE.md 'where applicable' with adoption table
category: providers
priority: medium
effort: M
status: open
code_refs:
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt
  - docs/ARCHITECTURE.md
opened: 2026-05-02
---
## Problem

`docs/ARCHITECTURE.md` documents `RequestId` and `HttpRetryBudget`
as the shared cross-provider helpers Ktor-using providers install
"where applicable." Verified at filing time:

```
$ grep -rln "RequestId\|HttpRetryBudget" core/providers/*/src/main/
core/providers/internxt/src/main/kotlin/.../InternxtApiService.kt
core/providers/onedrive/src/main/kotlin/.../OneDriveProviderFactory.kt
core/providers/onedrive/src/main/kotlin/.../GraphApiService.kt
core/providers/onedrive/src/main/kotlin/.../model/ApiResponse.kt
```

So **only `onedrive` and `internxt` actually install them.**
`s3`, `webdav` (and `sftp`, `rclone`, `localfs`) do not.

For `localfs`, `sftp`, `rclone` the absence is justified — they
either don't use Ktor or don't speak HTTP at all. **For `s3` and
`webdav` the absence is a real gap:**

- `s3` (or any S3-compatible endpoint, e.g. AWS S3, MinIO,
  Cloudflare R2, Synology C2) returns 503 / `SlowDown` /
  `RequestTimeout` under load. No retry-budget circuit means
  the daemon will hammer a degraded endpoint and either get
  rate-limited harder or amplify a brief outage into a sustained
  one.
- `webdav` servers (NextCloud, Synology WebDAV, hoster
  webdrives) similarly return 429 / 503 under congestion.
  Without `RequestId` correlation, a multi-request failure
  cluster can't be tied to a single root cause in
  `unidrive.log`.

## Why this isn't caught today

The "where applicable" hedge in ARCHITECTURE.md is doing all the
load-bearing work. The only test that would catch the gap is a
live integration test against a throttling endpoint, and:
- `s3` integration tests use MinIO which doesn't simulate 503
  storms.
- `webdav` integration tests use a local Apache instance with no
  rate-limit path.

So the gap is silent in CI. It would surface as a user-visible
"sync stuck on retries" report against a real provider, with no
correlatable log lines.

## Proposed action

1. Update ARCHITECTURE.md "shared cross-provider utilities" §
   to drop the "where applicable" hedge and instead enumerate
   per-provider adoption status:
   ```
   | Provider | RequestId | HttpRetryBudget | Justification |
   |---|---|---|---|
   | localfs | n/a | n/a | local FS, no HTTP |
   | sftp    | n/a | n/a | SFTP transport |
   | rclone  | n/a | n/a | shells out to rclone binary |
   | s3      | TODO | TODO | UD-XXX |
   | webdav  | TODO | TODO | UD-XXX |
   | onedrive | ✓  | ✓  | adopted UD-XXX |
   | internxt | ✓  | ✓  | adopted UD-XXX |
   ```
2. Implement `RequestId` + `HttpRetryBudget` installation in
   `S3ApiService` and `WebDavApiService`. Pattern is already
   established in `GraphApiService` and `InternxtApiService`;
   lift to `:app:core/http` if that lift hasn't already
   happened (check the cross-provider duplication audit
   2026-04-30 referenced in CLAUDE.md).
3. Add a unit test per provider that asserts `RequestId` is
   present in outgoing request headers and that
   `HttpRetryBudget` opens the circuit after N synthetic 503s.
   Pattern: see `ThrottleBudgetTest` in onedrive.

## Acceptance criteria

- [ ] ARCHITECTURE.md "where applicable" replaced with per-provider
      adoption table.
- [ ] `S3ApiService` and `WebDavApiService` install both helpers.
- [ ] Unit tests assert presence (not implementation) — per
      `challenge-test-assertion`, the invariant is "outgoing
      requests are correlatable and retries are bounded", not
      "exactly N retries occur".
- [ ] No regression in S3 / WebDAV integration tests.

## Why this is a separate ticket

Adding retry budgets to two providers is a 2-3 file change per
provider plus a doc edit. Doable in one session, but distinct
from PR #7's salvage scope and worth its own atomic commit so
the rationale survives in `git log`.
---
id: UD-404
title: CLI-Architektur: 3-Ebenen Command-Baum mit Single-Responsibility-Klassen
category: cli
priority: medium
effort: M
status: open
opened: 2026-05-02
---
### Die Architektur (Clean Code im CLI-Design)

Wenn man die CLI über eine moderne Bibliothek wie `picocli` (oder `clikt` falls Kotlin bevorzugt wird) aufbaut, lässt sich diese 3-Ebenen-Struktur perfekt in kleine, isolierte Klassen zerlegen. Das verhindert die typischen "Gott-Klassen", in denen hunderte Zeilen Argument-Parsing stattfinden.

Die Paketstruktur spiegelt dabei exakt den CLI-Baum wider:

```text
org.krost.unidrive.cli
├── UnidriveCommand         (Top-Level: unidrive)
├── commands
│   ├── show
│   │   ├── ShowCommand     (Level 2: show)
│   │   ├── StatusCommand   (Level 3: status)
│   │   └── QuotaCommand    (Level 3: quota)
│   ├── manage
│   │   ├── ManageCommand   (Level 2: manage)
│   │   ├── AuthCommand     (Level 3: auth)
│   │   └── ProfileCommand  (Level 3: profile)
│   └── run                 (Level 2: run / do)
│       ├── RunCommand
│       └── SyncCommand     (Level 3: sync)
```

Jedes Kommando ist nach dem Single-Responsibility-Prinzip nur für genau *eine* Aufgabe zuständig.

### Umsetzung der Kombination aus Ansatz 5 und 3

Du baust den Baum nach Ansatz 5 auf, nutzt aber die Annotations-Features deiner CLI-Library, um die Hilfe-Ausgabe (Ansatz 3) zu stylen. Hier ist ein Beispiel, wie so etwas konzeptionell aussieht:

```java
@Command(
    name = "unidrive",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    description = "The universal cloud drive synchronization engine.",
    commandListHeading = "%n🚀 ACTIONS%n",
    subcommands = {
        ShowCommand.class,
        ManageCommand.class,
        RunCommand.class
    }
)
public class UnidriveCommand implements Runnable { ... }
```

### Vorteile für den Entwicklungs-Workflow

1. **Wartbarkeit:** Neues Feature → neue Klasse, keine bestehenden Klassen anfassen.
2. **Git & Teamwork:** Jeder Befehl in eigener Datei → kaum Merge-Konflikte.
3. **Kontext-Sensitivität:** Subcommands können validieren ob der Nutzer sich in einem aktiven `sync_root` befindet.
---
id: UD-010
title: Lift extractJwtClaim() into :app:core/auth; eliminate 3-site duplicate (OneDrive + 2x Internxt)
category: architecture
priority: low
effort: XS
status: open
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProviderFactory.kt:49
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProviderFactory.kt:64-65
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:42-48
opened: 2026-05-02
---
## Problem

JWT body claim extraction is duplicated:

| File | Function | Lines |
|---|---|---|
| `core/providers/onedrive/.../OneDriveProviderFactory.kt:49` | `extractJwtClaim()` (private) | ~10 |
| `core/providers/internxt/.../InternxtProviderFactory.kt:64-65` | inline | ~5 |
| `core/providers/internxt/.../AuthService.kt:42-48` | inline | ~7 |

All three do: split JWT on `.`, Base64-decode middle segment, find claim by key in JSON body.

## Proposed action

Lift to `:app:core` as a single helper:

```kotlin
// core/app/core/src/main/kotlin/org/krost/unidrive/auth/JwtClaim.kt
package org.krost.unidrive.auth

import kotlinx.serialization.json.*
import java.util.Base64

/**
 * Extract a string claim from a JWT body without verifying the signature.
 *
 * For unidrive's purposes (sub-claim from a freshly minted refresh-token,
 * tenant-id from an access-token, etc.) we trust the issuer and just need
 * the claim value. Signature verification happens at the API layer when
 * the token is actually used.
 *
 * Returns null if the JWT is malformed, the body is unparsable, or the
 * claim is absent.
 */
fun extractJwtClaim(jwt: String, claim: String): String? {
    val parts = jwt.split(".")
    if (parts.size < 2) return null
    val body = try {
        String(Base64.getUrlDecoder().decode(parts[1]))
    } catch (_: IllegalArgumentException) {
        return null
    }
    val json = try {
        Json.parseToJsonElement(body).jsonObject
    } catch (_: Exception) {
        return null
    }
    return json[claim]?.jsonPrimitive?.contentOrNull
}
```

Replace the three duplicates.

## Acceptance criteria

- [ ] `core/app/core/src/main/kotlin/org/krost/unidrive/auth/JwtClaim.kt` exists.
- [ ] OneDrive's `extractJwtClaim` private helper deleted; calls migrated to the new public helper.
- [ ] Internxt's two inline JWT parses migrated.
- [ ] Behaviour byte-identical for representative valid + malformed JWTs (verify via test).

## Security note

This helper does NOT verify the JWT signature. Document this in the
helper's KDoc (already done above) so a future caller doesn't
mistakenly use it for trust decisions.

## Out of scope

JWT signature verification, JWKS fetching, expiration checking. Each of those is its own ticket.
---
id: UD-011
title: EPIC: refactor 6 god classes (GraphApiService, Main, WebDavApiService, SyncCommand, StatusCommand, InternxtProvider) — UD-004 already covers SyncEngine
category: architecture
priority: medium
effort: XL
status: open
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt
opened: 2026-05-02
---
## Problem

Audit (2026-05-02) measured 7 god classes by line count + methods per file:

| Class | Lines | Methods | Lines/method |
|---|---|---|---|
| `SyncEngine` | 1249 | 16 | 78 |
| `GraphApiService` (OneDrive) | 1080 | 26 | 42 |
| `Main` (CLI) | 839 | 30 | 28 |
| `WebDavApiService` | 699 | 22 | 32 |
| `SyncCommand` | 647 | 9 | 72 |
| `StatusCommand` | 646 | 18 | 36 |
| `InternxtProvider` | 538 | 15 | 36 |

`SyncEngine` already filed (UD-004 — decompose into Reconciler + ScanCoordinator + ActionPlanner + ActionExecutor).

This ticket tracks the **remaining 6 god classes** as a single epic so they show up together in any prioritisation, but each will need its own ADR/sub-ticket before refactor lands.

## Per-class quick triage

### `GraphApiService` (1080 LoC, 26 methods, OneDrive HTTP monolith)
Delta queries, upload/download, share management, retry budgets, error handling all in one. Natural split: `GraphDeltaService`, `GraphUploadService`, `GraphShareService`, with `GraphApiService` as a thin facade. Effort: M-L.

### `Main` (CLI, 839 LoC, 30 methods)
Picocli annotations, subcommand registration, provider creation, MDC context all inline. The picocli `@Command` annotations on subcommands force a flat shape; the real win is extracting the helper functions (`profilesOfType`, `buildEnvWarnings` (T8), MDC bootstrap) into focused files. Effort: S.

### `WebDavApiService` (699 LoC, 22 methods)
PROPFIND, upload/download, chunked uploads, quota, auth in one. Mirrors GraphApiService's shape. Same split pattern: `WebDavListService`, `WebDavUploadService`, `WebDavQuotaService`. Effort: M.

### `SyncCommand` (647 LoC, 9 methods, 72 lines/method — second-highest)
Provider construction, SyncEngine lifecycle, IPC loop, encryption wrapping. The methods are large because each one orchestrates a phase. Splitting into a `SyncOrchestrator` class that `SyncCommand` consumes would shrink `SyncCommand` to a thin CLI shell. Effort: M.

### `StatusCommand` (646 LoC, 18 methods)
Status rendering, table building, audit reports, credential checks. T11 already migrated some logic to `provider.statusFields()`; further wins from extracting `StatusRenderer` (table building) into its own file. Effort: S.

### `InternxtProvider` (538 LoC, 15 methods, 2× the next-largest provider at 336)
Multiple `toCloudItem` variants (file/folder × regular/delta) suggest a mapping layer worth extracting. UD-354 (Internxt computeSnapshotDelta verification) overlaps; consider doing both together. Effort: M.

## Proposed sequencing

Recommend tackling in this order:

1. **`Main` (CLI)** — easiest, biggest readability win for newcomers.
2. **`StatusCommand`** — T11 already cleaned the worst dispatch site; extracting `StatusRenderer` is straightforward.
3. **`WebDavApiService`** — same pattern as Graph but smaller; rehearse the split shape here.
4. **`GraphApiService`** — apply the rehearsed pattern.
5. **`SyncCommand`** — depends on `SyncEngine` decomposition (UD-004) being at least partway through.
6. **`InternxtProvider`** — couple with UD-354.

## Acceptance criteria for this epic

This is an EPIC; it does not have its own done-criterion. Done when each of the 6 sub-decisions above has its own UD-### ticket and its own ADR (where structural). The completion of each sub-ticket should reference back to this one.

## Why architecture-range

Structural concern across multiple modules. Each child ticket may go
to its specific category range when filed.

## Out of scope

`SyncEngine` (UD-004 already exists), provider-name-equality dispatch (just resolved by refactor/provider-spi-contract).
---
id: UD-356
title: Lift duplicated Snapshot test boilerplate (empty-snapshot + invalid-base64) up to :app:sync; keep round-trip tests per-provider
category: providers
priority: low
effort: XS
status: open
code_refs:
  - core/providers/localfs/src/test/kotlin/org/krost/unidrive/localfs/LocalFsSnapshotTest.kt
  - core/providers/sftp/src/test/kotlin/org/krost/unidrive/sftp/SftpSnapshotTest.kt
  - core/providers/s3/src/test/kotlin/org/krost/unidrive/s3/S3SnapshotTest.kt
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavSnapshotTest.kt
  - core/providers/rclone/src/test/kotlin/org/krost/unidrive/rclone/RcloneSnapshotTest.kt
opened: 2026-05-02
---
## Problem

5 snapshot test files contain the same 3 tests with provider-specific data:

| File | Tests duplicated |
|---|---|
| `core/providers/localfs/src/test/.../LocalFsSnapshotTest.kt` | round-trip encode/decode, empty snapshot, reject invalid base64 |
| `core/providers/sftp/src/test/.../SftpSnapshotTest.kt` | same 3 |
| `core/providers/s3/src/test/.../S3SnapshotTest.kt` | same 3 |
| `core/providers/webdav/src/test/.../WebDavSnapshotTest.kt` | same 3 |
| `core/providers/rclone/src/test/.../RcloneSnapshotTest.kt` | same 3 |

The empty-snapshot and invalid-base64 tests are **byte-identical** — they don't exercise any provider-specific data, just the generic `Snapshot<E>` wrapper's parse/encode contract.

## Proposed action

1. **Move the two byte-identical tests up to `:app:sync`** (where `Snapshot<E>` is defined). Replace the 5 per-provider duplicates with imports of the shared test (or just delete them — the contract being tested is the `Snapshot` wrapper's, not any provider-specific behaviour).

2. **Keep round-trip tests per-provider** — those use real provider-specific entry types and DO test something provider-specific. Possibly parameterise via a base class or `@TestFactory`-style discovery, but per-provider is acceptable.

## Per-`challenge-test-assertion` skill

Before deleting the 4 duplicates of `empty snapshot returns null`, verify:
1. **Invariant?** "An empty `Snapshot<E>` round-trips through encode/decode and stays empty" — generic, provider-independent.
2. **Reasonable alternative impl?** Any implementation of `Snapshot.encode/decode` that handles the empty case. Test asserts the contract.
3. **Delete harm?** If `:app:sync` has the same test asserting the same invariant, none. Otherwise, hold the test where the contract is most reachable.

## Acceptance criteria

- [ ] Tests moved up where appropriate; per-provider files don't carry generic-contract tests.
- [ ] Provider-specific round-trip tests remain (or are parameterised; user choice).
- [ ] Total test count goes down or stays flat; no invariant lost.
- [ ] CHANGELOG note that 4 tests were deleted as duplicates (with the kept location named).

## Why providers-range

The work is in `:providers:*/src/test/`. Providers range fits.

## Out of scope

Lifting the snapshot wrapper itself (already done; see UD-008's notes on `Snapshot<E>` already shared via `:app:sync`).
---
id: UD-013
title: Delete ProviderRegistry.isKnownType + defaultTypes dead code; fail loud on empty SPI discovery instead of silent fallback
category: architecture
priority: low
effort: XS
status: open
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/ProviderMetadata.kt:41
  - core/app/core/src/main/kotlin/org/krost/unidrive/ProviderMetadata.kt:74-76
  - core/app/core/src/test/kotlin/org/krost/unidrive/ProviderMetadataTest.kt
  - core/app/core/src/test/kotlin/org/krost/unidrive/SpiDiscoveryTest.kt
opened: 2026-05-02
---
## Problem

`core/app/core/src/main/kotlin/org/krost/unidrive/ProviderMetadata.kt`
defines two near-identically-named methods with different semantics
on `ProviderRegistry`:

```kotlin
private val defaultTypes = setOf("localfs", "onedrive", "rclone", "s3", "sftp", "webdav")

val knownTypes: Set<String> by lazy {
    val discovered = loader.toList().map { it.id }.toSet()
    if (discovered.isEmpty()) defaultTypes else discovered
}

fun isKnown(type: String): Boolean = type in knownTypes      // line 74
fun isKnownType(type: String): Boolean = type in defaultTypes // line 76
```

Two distinct issues:

1. **`isKnownType` has zero non-test callers.** Verified
   2026-05-02 against the post-refactor/provider-spi-contract tree:

   ```
   $ grep -rn "isKnownType" core/ --include="*.kt" | grep -v build/ | grep -v /test/
   core/app/core/src/main/kotlin/org/krost/unidrive/ProviderMetadata.kt:76:    fun isKnownType(type: String): Boolean = type in defaultTypes
   ```

   Only the definition. The two test classes that exercise it
   (`ProviderMetadataTest`, `SpiDiscoveryTest`) test the method
   against the same `defaultTypes` set the method itself consults
   — so the tests are tautological: they confirm the hardcoded
   list contains its own members.

2. **`defaultTypes` is a stale fallback.** It includes `onedrive`
   but is missing `internxt` (added later). It existed because
   "discovered.isEmpty()" can happen at unit-test time when the
   `META-INF/services/...` file is absent from the test classpath.
   But the right fix for that scenario is to register a
   `META-INF/services/...` file in test resources, not to keep a
   stale fallback that silently masks SPI registration failures.

## Risk

- **Footgun semantics.** A reader who sees `isKnown` and
  `isKnownType` next to each other reasonably assumes they are
  synonyms. They are not — one consults the live SPI, the other
  consults the hardcoded fallback. A future caller that picks the
  wrong one will silently get inconsistent behaviour for newly
  added providers.
- **Stale fallback masks SPI registration failures.** If the
  `META-INF/services/org.krost.unidrive.ProviderFactory` file goes
  missing from the runtime classpath (e.g. shadow-jar
  misconfiguration), `knownTypes` silently falls back to the 6
  members of `defaultTypes` — which currently does NOT include
  `internxt`. A user with an Internxt profile would see
  "unknown provider type internxt" with no helpful error pointing
  at the SPI loading problem.

## Proposed action

1. **Delete `defaultTypes` and `isKnownType` outright.** Both are
   dead in production code; the tautological tests
   (`ProviderMetadataTest`'s `isKnownType validates built-in
   providers`, `SpiDiscoveryTest`'s `isKnownType validates
   built-in providers`) get deleted with them.
2. **Replace the silent fallback** in `knownTypes` with a fail-loud
   error:

   ```kotlin
   val knownTypes: Set<String> by lazy {
       val discovered = loader.toList().map { it.id }.toSet()
       if (discovered.isEmpty()) {
           error(
               "ProviderRegistry: no providers discovered via ServiceLoader. " +
                   "Either no provider modules are on the classpath, or their " +
                   "META-INF/services/org.krost.unidrive.ProviderFactory files " +
                   "are missing from the runtime classpath."
           )
       }
       discovered
   }
   ```

   This converts the silent-empty-discovery footgun into a loud
   failure at first call.
3. **Test impact:** the empty-classpath scenario in tests must now
   either provide the SPI registration file in test resources (the
   right fix), or test against a fake-registered set. Most existing
   tests already work because they're in `:app:cli/src/test` (which
   has all 7 providers on the classpath) or use mocks.

## Acceptance criteria

- [ ] `defaultTypes`, `isKnownType` deleted from `ProviderMetadata.kt`.
- [ ] `knownTypes` falls loud on empty discovery instead of falling
      back silently.
- [ ] `grep -rn "isKnownType\|defaultTypes" core/` returns zero hits.
- [ ] `:app:core:test` and the global `./gradlew test` still pass;
      no test deleted that asserted a real invariant.
- [ ] Per `challenge-test-assertion`: the deleted tests
      (`isKnownType validates built-in providers` ×2) are documented
      in the resolution as "tautological — asserted the hardcoded
      list contains its own members; deleted with the hardcoded
      list itself".

## Why architecture-range

It's a contract-surface decision (does `ProviderRegistry` keep a
fallback or fail loud?) and an API change (deleting a public
method). Architecture range fits.

## Out of scope

Lifting the SPI registration concern into a build-time check (e.g.
a Gradle task that asserts every `:providers:*` module ships its
`META-INF/services/...` file). Worth its own ticket if the
fail-loud approach surfaces real CI noise.

## Why this wasn't done in refactor/provider-spi-contract

The SPI-contract PR (UD-???-equivalent, the spec
`docs/specs/2026-05-02-provider-spi-contract-extension-design.md`)
explicitly listed this in its §6 "out-of-scope follow-ups" along
with three other small cleanups, but did not file a ticket for it
at the time. This ticket closes that gap.
---
id: UD-014
title: Make MCP auth_begin/complete provider-agnostic via ProviderFactory.beginInteractiveAuth(); drop OneDrive-narrowing guard in AuthTool
category: architecture
priority: high
effort: M
status: open
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt:80-101
  - core/app/core/src/main/kotlin/org/krost/unidrive/ProviderFactory.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt
opened: 2026-05-02
---
## Problem

`core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt`'s `handleAuthBegin` (and `handleAuthComplete`) currently has a two-step gate:

1. **Capability check** (post-refactor/provider-spi-contract): `if (factory == null || !factory.supportsInteractiveAuth()) return error`. Long-term contract; correct semantically.
2. **OneDrive-specific narrowing** (added 2026-05-02 by Codex review on PR #9): `if (ctx.profileInfo.type != "onedrive") return "handler is currently OneDrive-specific"`. **Temporary** — the body below the gate hardcodes `OneDriveConfig`, `OAuthService`, `DeviceCodeResponse` etc.

Without step 2, any future provider whose factory returns `true` from `supportsInteractiveAuth()` (e.g. Internxt, hypothetical OAuth providers) would enter the handler and **execute OneDrive's OAuth flow against a non-OneDrive profile**. The Codex reviewer caught this on PR #9; step 2 was added as a stopgap with a `// allow: UD-014` marker.

The proper fix: make the auth flow itself provider-agnostic in the SPI, then drop step 2.

## Proposed action

Extend `ProviderFactory` (or `CloudProvider`) with two new SPI methods:

```kotlin
interface ProviderFactory {
    // … existing capabilities including supportsInteractiveAuth()

    /**
     * Begin an interactive auth flow. Returns a [BeginAuthResult]
     * describing what the user must do next (visit URL, enter code,
     * etc.) and a [continuationHandle] that the corresponding
     * completeAuth() call will use to resume.
     *
     * Only invoked when supportsInteractiveAuth() returns true.
     * Default throws UnsupportedOperationException — the gate
     * should prevent that path being reached.
     */
    suspend fun beginInteractiveAuth(profileDir: Path): BeginAuthResult =
        throw UnsupportedOperationException("$id has no interactive auth flow")

    /**
     * Resume an interactive auth flow with the user-provided handle.
     * Returns success/error; on success the credentials should now
     * be persisted under profileDir.
     */
    suspend fun completeInteractiveAuth(profileDir: Path, continuationHandle: String): CompleteAuthResult =
        throw UnsupportedOperationException("$id has no interactive auth flow")
}

data class BeginAuthResult(
    val userMessage: String,           // "Go to https://… and enter code XYZ"
    val continuationHandle: String,    // opaque token the user passes to completeAuth
    val pollableEndpoint: String? = null, // optional: for device-flow polling
    val expiresAt: Instant? = null,
)

sealed class CompleteAuthResult {
    object Success : CompleteAuthResult()
    data class Pending(val message: String) : CompleteAuthResult()  // user hasn't completed in browser yet
    data class Error(val message: String) : CompleteAuthResult()
}
```

Then:

1. **OneDrive** implements both methods, lifting the existing `OneDriveConfig` + `OAuthService` + `DeviceCodeResponse` plumbing inside the override. Existing `OneDriveProvider` keeps the OAuth-flow code where it belongs (with the provider).
2. **`AuthTool.handleAuthBegin`** becomes provider-agnostic:
   ```kotlin
   if (factory == null || !factory.supportsInteractiveAuth()) return error("…")
   val result = runBlocking { factory.beginInteractiveAuth(ctx.profileDir) }
   storeContinuation(result.continuationHandle, ctx.profileInfo.name)
   return buildToolResult(result.userMessage)
   ```
3. **Drop the UD-014 narrowing guard** in `AuthTool.kt`.
4. **Internxt** can then implement `beginInteractiveAuth` (it does have OAuth) without touching `AuthTool.kt`.

## Acceptance criteria

- [ ] `BeginAuthResult` / `CompleteAuthResult` types added in `:app:core`.
- [ ] `ProviderFactory.beginInteractiveAuth` + `completeInteractiveAuth` added with throwing defaults.
- [ ] OneDrive overrides both, lifting current `AuthTool` body internals.
- [ ] `AuthTool.handleAuthBegin`/`handleAuthComplete` no longer mention `OneDriveConfig` / `OAuthService` / `"onedrive"`.
- [ ] `// allow: UD-014` marker + the narrowing `if (type != "onedrive")` block deleted from `AuthTool.kt`.
- [ ] Existing `AdminToolsTest` cases still pass (the capability-rejection invariant is preserved).
- [ ] Add a contract test asserting OneDrive's `beginInteractiveAuth()` returns a non-empty `userMessage` and a non-empty `continuationHandle` (without doing live network — fixture-driven).

## Why architecture-range

API change to the SPI (two new methods). Architecture range fits.

## Out of scope

Re-implementing the OneDrive OAuth flow itself (the existing `OAuthService` is the lift target, not a rewrite). Internxt's `beginInteractiveAuth` impl (separate ticket if/when Internxt's interactive flow ships).

## Why this wasn't done in PR #9

Adding two SPI methods + lifting OneDrive's auth plumbing + a contract test is its own focused change — would have doubled PR #9's scope. The narrowing guard added on review keeps the runtime behaviour correct (only OneDrive auth begins) while the SPI cleanup proceeds in this ticket.
---
id: UD-774
title: Temporarily disable ktlint plugin to speed up session iteration
category: tooling
priority: low
effort: S
status: open
code_refs:
  - core/build.gradle.kts
  - core/gradle/libs.versions.toml
opened: 2026-05-02
---
**Disable the ktlint plugin's `apply` block in [core/build.gradle.kts](core/build.gradle.kts) across all subprojects, behind a single boolean toggle. ktlint adds ~20–30 s to every full `./gradlew build` and was the dominant cost in tight edit-test loops during multi-fix sessions (e.g. the UD-240g/UD-240i sequence on 2026-05-02). Re-enable is one line.**

## Why temporary

Long term, ktlint stays. The discipline of consistent formatting + the baseline mechanism for legacy violations is load-bearing for the project's "agents and humans share the codebase" model ([CODE-STYLE.md](docs/dev/CODE-STYLE.md), [docs/dev/lessons/ktlint-baseline-drift.md](docs/dev/lessons/ktlint-baseline-drift.md)).

Short term, when iterating on a single module's `.kt` files (typical session shape: edit → run module test → repeat), ktlint runs on the full project's main + test source sets every time, even when only one file changed. On a 9-module composite that's the longest task in the build graph, by a wide margin.

The toggle is intentionally crude — flip a `val ktlintEnabled = false` to `true` and the plugin lights up everywhere. This trades zero re-enable friction for zero per-invocation flexibility, which is the right tradeoff for a session-pace-of-work concern.

## Acceptance

- `core/build.gradle.kts` has a clearly-named compile-time toggle at the top of the `subprojects { ... }` block. When `false` (the new default after this ticket), no `ktlintCheck` / `ktlintFormat` / `ktlintMainSourceSetCheck` / `ktlintTestSourceSetCheck` tasks register on any subproject.
- `./gradlew tasks --all` no longer lists ktlint tasks.
- `./gradlew build` runs ~20–30 s faster on a warm daemon. Spot-check.
- `./gradlew :app:sync:test` (single-module) runs end-to-end with no ktlint dependency.
- The plugin declaration in [core/gradle/libs.versions.toml](core/gradle/libs.versions.toml) is left in place — only the *application* in `core/build.gradle.kts` is gated. Re-enable is one line of code.
- Existing `<module>/config/ktlint/baseline.xml` files stay where they are. They become unused-but-harmless while the toggle is off; they snap back into use on re-enable.
- [scripts/dev/ktlint-sync.sh](scripts/dev/ktlint-sync.sh) is left as-is. It will fail with "task not found" while the toggle is off — expected and acceptable for a temporary disable. A user re-enabling ktlint also re-enables the sync script in the same flip.
- [scripts/dev/pre-commit/scope-check.sh](scripts/dev/pre-commit/scope-check.sh)'s `chore(ktlint)` rule (which restricts ktlint scope commits to `baseline.xml` only) is left as-is. The rule fires only on `chore(ktlint)`-scoped commits, which won't happen while the toggle is off.

## Re-enable plan

When session-pace work settles, flip the toggle back to `true` and:

1. Run `scripts/dev/ktlint-sync.sh` to absorb any drift in baselines from the disable window.
2. Verify `./gradlew build` is fully green (ktlint + tests).
3. Close UD-774 with a `resolved_by: commit <sha>` referencing the re-enable commit.

No data-loss path exists: nothing about disabling stores state outside the toggle line, so re-enable is reversible at any time.

## Surfaced

2026-05-02 session, after the UD-240g + UD-240i sequence (4 successive `./gradlew build` invocations totalling ~6 minutes of which ktlint was ~30 % of wall time). User explicitly asked to disable in-session.

---
id: UD-254a
title: MDC clone storm under MDCContext — 80 percent of JFR allocation pressure
category: core
priority: medium
effort: M
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt
  - docs/dev/lessons/mdc-in-suspend.md
opened: 2026-05-02
---
**Every coroutine context switch clones the SLF4J MDC map. Captured live via JFR (60-s recording, 17:27:37–17:28:37 UTC, 2026-05-02): `LogbackMDCAdapter.getPropertyMap()` accounts for **80.63 % of all allocation pressure** in the JVM. The cause is `kotlinx.coroutines.slf4j.MDCContext.updateThreadContext(...)` calling `MDC.getCopyOfContextMap()` on every dispatched task — a fresh `HashMap<String, String>` per coroutine resume.**

## Captured live (jfr.exe view allocation-by-class on flight_recording_…15780.jfr)

```
                                     Allocation by Class
Object Type                                                              Allocation Pressure
------------------------------------------------------------------------ -------------------
java.util.HashMap                                                                      80,63%
byte[]                                                                                 10,72%
kotlinx.coroutines.JobSupport$Finishing                                                 2,73%
java.io.EOFException                                                                    2,15%
kotlin.Result$Failure                                                                   1,14%
java.nio.HeapByteBuffer                                                                 1,01%
…
```

Stack-traced via `jfr.exe print --events ObjectAllocationSample --stack-depth 12`:

```
ch.qos.logback.classic.util.LogbackMDCAdapter.getPropertyMap()           ← 80% of allocation
LogbackMDCAdapter.getCopyOfContextMap()
org.slf4j.MDC.getCopyOfContextMap()
kotlinx.coroutines.slf4j.MDCContext.updateThreadContext(...)
kotlinx.coroutines.internal.ThreadContextKt.updateThreadContext(...)
kotlinx.coroutines.DispatchedTask.run()
kotlinx.coroutines.EventLoopImplBase.processNextEvent()
kotlinx.coroutines.BlockingCoroutine.joinBlocking()
kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(...)
org.krost.unidrive.cli.SyncCommand.run() line: 357
```

The companion stack (`MDC.setContextMap`) shows the symmetric write side, fired from the `DefaultDispatcher-worker-*` runWorker loop on every task. The pattern is bidirectional — read on save, write on restore — so each coroutine resume costs **2 HashMap clones**, not 1.

## Root cause

`SyncEngine.syncOnce` in [core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:80-105](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt) pushes a `scan` MDC slot for the duration of one sync pass (UD-254). The CLI / providers add `commit` (build hash) + `profile` (config profile) + `thread` slots, populated at process start (logback.xml pattern `[%X{commit:--------}] [%X{profile:--------}] [%X{scan:--------}]`).

While the sync is running, `kotlinx-coroutines-slf4j`'s `MDCContext` is in the coroutine context (added so log lines emitted inside `launch { }` / `async { }` blocks inherit the MDC). The integration's contract:

> `updateThreadContext` → `MDC.getCopyOfContextMap()` (one HashMap allocation, snapshotting old MDC)
> *coroutine body runs with the right MDC*
> `restoreThreadContext` → `MDC.setContextMap(savedMap)` (one HashMap allocation, restoring old MDC)

Every `DispatchedTask.run()` does both. With thousands of upload coroutines × per-PUT continuation suspensions × Ktor's HTTP/2 callback chains, the count goes into the millions.

## Why this matters

- **80 % of allocation pressure** means GC frequency tracks coroutine churn, not actual work. The post-fix daemon (UD-240g + UD-240i deployed) sustains many concurrent uploads precisely because the algorithmic fixes work — but each of those uploads is paying a 2-HashMap-per-suspend tax.
- The recording showed only **0.53 % avg JVM CPU** — the daemon is mostly waiting on network. So the user isn't feeling this *as throughput*, only as memory churn. The cost is real but masked by I/O dominance.
- **At larger scale** (e.g. UD-247's cross-provider benchmark workload) or with a faster network this allocation rate becomes a throughput cliff — see UD-742 / UD-757 ETA renderer that fires per scan tick; same pattern.
- The project's existing [`docs/dev/lessons/mdc-in-suspend.md`](docs/dev/lessons/mdc-in-suspend.md) lesson already names this hazard ("MDC inside a suspend function doesn't survive MDCContext re-apply on resume — for cross-coroutine correlation prefer id-in-message"). The lesson is correct; we just kept using `MDCContext` on the hot path anyway.

## Proposal — two slices

### Slice A — clear `scan` from MDC at coroutine boundaries (S effort, low risk)

The `scan` slot is the most expensive: it's set at `syncOnce` entry and held for the lifetime of one sync pass (the longest live MDC entry by far). Inside the long-lived coroutines launched from `syncOnce` (Pass 2 transfer slots, the IPC accept loop, etc.) the `scan` slot is rarely *needed* in log output — the per-action lines already carry path info. Drop it from the MDC scope, keep it as a banner-only field on the `Scan started`/`Scan ended` log lines.

This alone should cut HashMap allocation by ~half — every map clone after entering the sync pass currently carries 4 entries; cutting `scan` makes it 3 (and the dominant size driver is the values, not the count, but the alloc count drops uniformly).

### Slice B — drop `MDCContext` from coroutine context on hot paths (M effort, behavioural change)

Stop installing `MDCContext()` in the coroutine context for `Pass 2: Apply` (the per-action upload/download workers) and the IPC server's accept loop. Replace with id-in-message logging:

- `log.info("Upload {} ({} bytes) action={}", path, size, scanId)` instead of relying on MDC `[scan]` interpolation
- Provider code already does this for `req=ID` (via UD-255's Ktor plugin) — extend the pattern

Per the lessons file:
> *MDC inside a suspend function doesn't survive MDCContext() re-apply on resume. For cross-coroutine correlation prefer id-in-message over MDC.*

This is the canonical implementation of that guidance.

## Acceptance

- New JFR recording over the same workload shape shows `LogbackMDCAdapter.getPropertyMap` < 10 % of allocation pressure (down from 80.63 %).
- `byte[]` (Internxt encrypt + S3 PUT) becomes the dominant allocation source, as JMC's rule originally suggested — confirms we've drained the MDC noise and what's left is intrinsic.
- Log lines on the upload hot path still carry scan-id correlation (via inline format, not MDC) so a single `grep "scan=<id>"` still slices a sync pass.
- No regression in `IpcProgressReporter` events — IPC clients already get `profile` in the JSON body (not via MDC).
- `logback.xml`'s `%X{scan:--------}` pattern is left in place for the synchronous main-thread paths that still benefit (the `Scan started`/`Scan ended` banners).

## Related

- **UD-254** (closed) — introduced the `scan` MDC slot. This ticket undoes the scope of that change for hot-path coroutines while keeping the diagnostic value at the start/end banners.
- **UD-255** (closed) — HTTP `req=ID` correlation. Set the precedent for id-in-message over MDC.
- **lessons/mdc-in-suspend.md** — the project already named this hazard. UD-254a is the perf-evidence-driven fix.
- **UD-247** (open) — cross-provider benchmark harness; doing this fix first means the harness measures the post-fix shape and gives a stable baseline.

## Surfaced

2026-05-02 19:27 — JFR recording on the running daemon (post-UD-240g/i deploy at 18:41) examined with `jfr.exe view allocation-by-class` and `jfr.exe print --events ObjectAllocationSample`. The signal was only visible because JMC's overview report (rule-based, summary-level) collapsed it under "Allocated Classes" without crediting the call site; the application-level views show it unambiguously.
---
id: UD-352a
title: gather-phase 3:40 on narrow --sync-path: sequential pagination + no scoped delta + no streaming
category: providers
priority: medium
effort: M
status: open
opened: 2026-05-03
---
**`unidrive sync --download-only --sync-path=/_INBOX` against a 22,700-item Internxt drive sat in `Scanning remote changes... 22,700 items · 3:40` before the first download started. The 3:40 is dominated by **sequential pagination of `/files` and `/folders`** in [`InternxtProvider.delta()` lines 313-340](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:313). 22,700 items / 50 per page ≈ 454 round-trips × ~485ms ≈ 220s, entirely API-bound. Two compounding issues: (a) no parallelism on the offset-paginate; (b) `--sync-path` is filtered in-engine *after* the full delta returns ([SyncEngine.kt:163-168](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:163)), so the engine pulls every item in the entire drive even when the user wants 1 % of it.**

## Dig findings (2026-05-03)

Triaged the 3:40 cost on `unidrive -p inxt_gernot_krost_posteo sync --download-only --sync-path=/_INBOX`:

1. **`gatherRemoteChanges` does NOT honor `syncPath`.** [SyncEngine.kt:578-690](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:578) fetches the entire remote delta, full-set; filtering happens later at [SyncEngine.kt:163-168](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:163). For a narrow `--sync-path` against a wide drive the wasted work is the dominant cost.

2. **`InternxtProvider.delta` paginates sequentially.** [InternxtProvider.kt:313-340](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:313):
   ```kotlin
   var offset = 0
   while (true) {
       val batch = api.listFiles(adjustedCursor, limit, offset)   // limit=50
       allFiles.addAll(batch)
       …
       if (batch.size < limit) break
       offset += limit
   }
   // identical loop for /folders
   ```
   No concurrency. 22,700 items × 50/page = 454 sequential requests. Observed 220s ÷ 454 ≈ 485 ms/request — entirely network/server time.

3. **`resolveItemPath` is cheap.** [SyncEngine.kt:703-709](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:703) only does DB lookup for deleted items at root; non-deleted items return directly. Not the bottleneck.

4. **Fast-bootstrap (UD-223) is bypassed** because the operator has a populated DB (`storedCursor != null`), so `isFullSync = false`. Even if it weren't, this is `--download-only` — fast-bootstrap adopts the cursor and emits zero actions, which is the wrong answer when the user wants files pulled.

5. **No streaming.** Engine's gather → reconcile → apply phases are strict. First download cannot start until gather + reconcile complete.

## Three layers of fix, smallest to largest

### (a) Parallelize Internxt offset-paginate — easy win, biggest absolute saving

Replace the sequential offset loop with a parallel fetcher. Pseudo:

```kotlin
val pages = generateSequence(0) { it + limit }.takeWhile { it < probedTotal }
val sem = Semaphore(8)
val batches = pages.map { offset ->
    async { sem.withPermit { api.listFiles(adjustedCursor, limit, offset) } }
}.awaitAll()
```

Risks: the offset-paginate API contract assumes "stop when batch < limit" — without a count probe the parallel fan-out can't know how many pages exist. Either (i) request via `Range`-like total-count header (if Internxt supports it), (ii) over-fetch with `limit*N` slots and ignore empty pages, or (iii) spawn N requests, fan out more if any returned full pages. Concurrency 8 should cut 220 s to ~30 s.

Same pattern applies to `listFolders`. Could be done in parallel with `listFiles`.

### (b) Folder-scoped delta when `--sync-path` is set — eliminates wasted work

For Internxt, the engine could pass `syncPath` (or its resolved folder UUID via `FolderUuidCache`) to the provider's `delta()` and have the provider only enumerate the subtree. Current `listFiles`/`listFolders` API doesn't take a folder filter — needs API research (does Internxt's REST API support `?folder=<uuid>` on `/files`?). If yes, narrow paths drop from 22,700 → ~hundreds.

If the API doesn't support it, the engine could call `listFiles(folder=<uuid>)`-style folder-by-folder traversal as a `Capability.ScopedDelta` opt-in, falling back to full delta when absent. Adds capability bookkeeping but is the right answer for narrow `--sync-path` use cases.

### (c) Streaming gather → apply for `--download-only` — biggest UX win, biggest refactor

User's original ask. Pipeline pages of remote items into Pass-2 download workers as they arrive, before reconcile completes. Constraints relax in `--download-only` mode:
- Move detection ([Reconciler.detectMoves](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt) — UD-240i/j/k) needs the full remote+local set; in `--download-only` move-as-download-new + delete-old is acceptable (slightly more bytes, correct outcome).
- Conflict detection in `--download-only` defaults to remote-wins-content; per-item resolution is fine.
- Cursor-promotion guard (UD-222) still works — just promote at end of gather only if every transfer succeeded.

Implementation shape: a new `SyncMode.STREAM_DOWNLOAD` that bypasses `gatherRemoteChanges`/`reconcile`, enumerates remote pages, and per-page emits `DownloadContent` actions into a channel that Pass-2 workers consume. Initial impl could be Internxt-only behind a flag.

## Why this fits as UD-352a

[UD-352](docs/backlog/CLOSED.md) closed the gather-phase visibility problem (heartbeat per page so users see progress). UD-352a is the next layer: the gather phase is now visible *and* observably slow. The fixes here either preserve UD-352's heartbeat contract (parallelize, scoped delta) or relax it (streaming = no fixed total to count toward).

## Acceptance

- (a) is a self-contained provider-side change. Acceptance: sync wall-clock for the operator's `--sync-path=/_INBOX` against 22,700-item drive drops below 60 s with parallelism = 8. New `InternxtProviderTest` case asserts concurrent in-flight requests (mock with delay assertion).
- (b) requires API research first. Acceptance: a `Capability.ScopedDelta` opt-in path; when set, narrow-path syncs enumerate < 1 s independent of total drive size.
- (c) needs an ADR (streaming changes engine invariants). Acceptance: `--download-only --sync-path=/_INBOX` starts the first download within 10 s, regardless of drive size.

Pick (a) first as the unblocking fix; (b) and (c) can be filed as siblings once the easy win is in.

## Related

- **UD-352** (closed) — gather-phase heartbeat. Sets the visibility contract this ticket builds on.
- **UD-742** (closed) — heartbeat per page. Same gather-phase family.
- **UD-223** (open) — fast-bootstrap. Adjacent: skips full enumeration on first sync via `Capability.FastBootstrap`. The `Capability.ScopedDelta` of (b) is the symmetric capability for non-first syncs.
- **UD-405** (closed) — `--sync-path` Windows backslash normalisation. Same `--sync-path` UX axis.
- **UD-901a** (closed) — recovery loops respect `syncPath`. Same scope-honoring concern at a different code site.

## Surfaced

2026-05-03, in operator's iteration after the UD-901 fix-stack landed. The 3:40 wait for a narrow `--download-only` is acceptable for a one-off but kills the "quick fetch this folder" use case — the dominant `--download-only` mental model.

## 2026-05-04 update — concrete throughput target from a 165k-item baseline

A fresh-profile sync against a 165,509-item Internxt account took 2012s end-to-end (Reconcile started: 165509 remote, 0 local; scan duration 2012178ms in `unidrive.log`). That is **~82 items/sec sequential**, broadly consistent with this ticket's 22,700-item ÷ 220s = ~103 items/sec earlier observation.

**Acceptance addition** for the parallel-pagination fix: at parallelism = 8, observed throughput should be **≥500 items/sec** on a 165k-item account, i.e. the same 165,509-item scan completes in ≤350s. Verified by re-running the same `unidrive -p <profile> sync --download-only --sync-path /` and asserting the `Scan ended ... duration=Xms` log line shows `X ≤ 350000`.

Also: pair the parallel pagination with **UD-303 (tie-break by `(updatedAt, lastUuid)`)** so concurrent paginators don't race each other into duplicate or dropped items at page boundaries. UD-303 must land first or alongside.

---
id: UD-362
title: Narrow Internxt delta() request to --sync-path scope when set
category: providers
priority: medium
effort: M
status: open
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:293
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:578
opened: 2026-05-03
---
**`unidrive sync --sync-path=/_INBOX` still pulls the entire drive's `/files` + `/folders` listing** — the scope filter is engine-side only, applied after the full delta returns. The Internxt Drive API exposes per-folder content endpoints (`/folders/content/{uuid}`, `/folders/{uuid}/tree` per the spec); the provider could narrow the wire-level call set when `syncPath` is plumbed through. Two costs today: (a) wasted bandwidth/time pulling 22,700 items to filter down to ~50 (cf. UD-352a 3:40 gather phase), and (b) a wider blast radius for the partial-gather problems in UD-360/UD-361 — the more surface area we walk, the more chances for a transient error to silently truncate.

Filed from the [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) (§5 sync-path scope handling).

Related: UD-352a (which already raised the parallelism + scoped-delta angle from the perf side). This ticket is the API-correctness framing of the same gap; coordinate or merge.

## What to change

Plumb `syncPath` from the engine to the provider via the existing `delta()` signature or a new overload. When set, the provider:
- Resolves `syncPath` to a folder UUID once (cached per-cursor).
- Walks `/folders/content/{uuid}` recursively scoped to that subtree, instead of `/files` + `/folders` flat listings.
- Or, post-UD-363, uses `/folders/{uuid}/tree` for atomic scoped retrieval.

## Acceptance

- When `syncPath` is non-null and the provider supports scoped delta, the wire-level call set is restricted to the subtree.
- An integration test asserts that `--sync-path=/_INBOX` against a 22 700-item drive issues O(items-in-subtree) requests, not O(items-in-drive).

## Related

- UD-352a (parallelism + scoped delta — perf framing).
- UD-360, UD-361 (partial-gather signalling — narrower scope reduces exposure).
- UD-363 (`/folders/{uuid}/tree` — atomic alternative).
- [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) §5.
---
id: UD-363
title: Replace flat-listing reconstruction with /folders/{uuid}/tree
category: providers
priority: medium
effort: L
status: open
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:293
opened: 2026-05-03
---
**The Internxt Drive OpenAPI spec exposes `/folders/{uuid}/tree`, an atomic single-call subtree fetch — the provider doesn't use it.** Today's [`InternxtProvider.delta()`](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:293) reconstructs the inventory from two independently paginated flat listings (`/files`, `/folders`); any insert, update, or delete on the server during the walk shifts page boundaries and can drop or duplicate rows. A single `/tree` call is atomic from the server's POV and eliminates the entire reorder/insert race class.

Filed from the [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) (§3c, §3f, §6 mechanism 4).

## What to change

Behind a feature flag (so we can compare result sets on live drives), implement an alternative gather path that calls `/folders/{uuid}/tree` rooted at the drive root (or at `syncPath` per UD-362). Convert the tree response into the same `DeltaPage` shape the existing flat-listing path emits. Keep the legacy path until live-fixture parity is demonstrated.

## Acceptance

- Feature-flagged gather path uses `/folders/{uuid}/tree`.
- Live-fixture test asserts the tree-based result set matches the legacy flat-listing result set across at least one full sync cycle on a stable test drive.
- Performance comparison (request count, wall time) recorded in the lessons doc.

## Related

- UD-358 (stable sort) — sibling fix for the legacy path; this ticket bypasses the problem instead.
- UD-362 (scoped delta) — `/tree` is the natural primitive for that.
- UD-352a (parallelism perf) — `/tree` is one round-trip vs 454; collapses both perf and correctness wins into one change.
- [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) §3c.
---
id: UD-364
title: Use /files/limits to populate maxFileSizeBytes()
category: providers
priority: low
effort: S
status: open
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt
opened: 2026-05-03
---
**`InternxtProvider.maxFileSizeBytes()` returns null** — the SPI default — so the engine has no early reject for oversized uploads. A wasted `PUT` against the OVH shard backend can run to its full timeout (10 KB/s floor × file size, see UD-353) before failing. The Internxt Drive API exposes `/files/limits` (per the OpenAPI spec) which returns the server-reported per-file size cap; the provider should consume it and override the SPI method.

Filed from the [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) (§capability matrix, available-unused).

## What to change

In [`InternxtApiService.kt`](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt) add a `getFileLimits()` call. In [`InternxtProvider.kt`](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt) override `maxFileSizeBytes()` to return the cached server value, refreshed lazily on auth.

## Acceptance

- `maxFileSizeBytes()` returns a non-null Long matching the value reported by `/files/limits`.
- Engine pre-flight check rejects oversized files before the upload pipeline starts (verify existing engine consumes `maxFileSizeBytes` — if not, this ticket also covers wiring it in).

## Related

- UD-353 (OVH shard upload throughput floor) — context for why oversized uploads hurt today.
- [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) §capability matrix.
---
id: UD-365
title: Declare and implement Capability.Share family for Internxt
category: providers
priority: low
effort: L
status: open
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
opened: 2026-05-03
---
**Internxt's Drive API exposes a fully-featured sharing model (~30 endpoints under `/sharings/*` per the OpenAPI spec); the provider returns `Unsupported` for the entire `Capability.Share` family.** That covers `share()`, `listShares()`, `revokeShare()` on [`CloudProvider`](core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt). The functionality is server-side ready and free; this is pure provider implementation work.

Filed from the [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) (§capability matrix, available-unused).

## What to change

In [`InternxtProvider.kt`](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt):
- Add `Capability.Share` (or whatever the SPI granularity is — verify against `Capability` enum) to the `capabilities()` set.
- Override `share(path, expiryHours, password)` → POST `/sharings/...` with the spec-defined request body, return the share URL.
- Override `listShares(path)` → GET `/sharings?...`.
- Override `revokeShare(path, shareId)` → DELETE `/sharings/{id}`.
- Add corresponding DTOs to `model/`.

## Acceptance

- All three SPI methods round-trip through `/sharings/*` and return live data.
- `capabilities()` declares the share family.
- Integration test creates a share, lists it, revokes it, and asserts the share is gone.

## Related

- UD-364 (`/files/limits`) — sibling capability-gap ticket for unused but advertised API surface.
- [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) §capability matrix.
---
id: UD-015
title: Document /auth/login* and Bridge endpoints as undocumented vendor-flux surfaces
category: architecture
priority: low
effort: S
status: open
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:100
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:290
  - docs/ARCHITECTURE.md
opened: 2026-05-03
---
**Three vendor-flux Internxt API surfaces are absent from `docs/ARCHITECTURE.md`** — `/auth/login`, `/auth/login/access`, and the entire Bridge family (`/buckets/*`, `/v2/buckets/*`). They're called by [`AuthService.kt`](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt) and [`InternxtApiService.kt:290,326-368`](core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:290) but **not in the public Drive OpenAPI spec at `https://api.internxt.com/drive/`**. They're stable per Internxt's open-source desktop client, but documenting their reverse-derived nature protects future agents from assuming the spec is canonical.

Filed from the [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) (§API discovery, "Used-but-undocumented" classification).

## What to change

Either add a section to [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) under the Internxt provider summary, or create a new `docs/dev/lessons/internxt-undocumented-surfaces.md` listing:

- `/auth/login` and `/auth/login/access` — auth challenge + token mint, called from `AuthService.kt:100,134`. Source of truth: Internxt's open-source desktop client (`internxt/drive-desktop` on GitHub).
- `/buckets/{bucket}/files/{fileId}/info` — Bridge file metadata for download, called from `InternxtApiService.kt:290`. Source of truth: `internxt/inxt-js` (legacy Storj fork).
- `/v2/buckets/{bucket}/files/start` and `/v2/buckets/{bucket}/files/finish` — Bridge upload protocol, called from `InternxtApiService.kt:326-368`. Source of truth: same as above.

For each, note the open-source repo path used to derive the wire shape and date of derivation. This is a vendor-flux risk register, not an SLA — Internxt could change these without notice.

## Acceptance

- Section exists in `ARCHITECTURE.md` (or a dedicated lessons doc) listing all three undocumented surfaces.
- Each entry has the call site, the source-of-truth open-source link, and a "verified on" date.
- Cross-link from [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) §API discovery.

## Related

- [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md).
---
id: UD-371
title: Wire SyncEngine Pass 1 to dispatch grouped CreateRemoteFolder via provider.createFolders (UD-368 follow-on)
category: sync
priority: low
effort: M
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:355
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt:65
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt
opened: 2026-05-03
---
**Wire `SyncEngine` Pass 1 to dispatch grouped `CreateRemoteFolder` actions through `provider.createFolders(paths)` so the bulk-folder optimisation landed in [UD-368](docs/backlog/CLOSED.md) actually runs in production.**

UD-368 added `CloudProvider.createFolders(paths)` SPI (default loops `createFolder`) and `InternxtProvider.createFolders` (bulk-aware override calling `POST /folders` with the 5-item-capped `folders` array). But [SyncEngine.kt:375](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:375) still dispatches `CreateRemoteFolder` actions one at a time via `applyCreateRemoteFolder`. So in production every folder-create is still a single `createFolder` round-trip — the bulk path is dormant.

## Realistic value

Modest. The orphan-recovery cascade ([Reconciler.kt:225-275](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:225)) emits `CreateRemoteFolder` for ancestors **depth-first**, not breadth-first — a 10-deep new path is 10 sequential POSTs even with bulk (each level needs its parent's UUID first). Bulk only saves round-trips for **siblings under the same parent**, capped at 5x → 1x reduction.

Cases where it actually helps:
- A user dumps a directory with many flat-list new subfolders (e.g. importing an `archive/` with 20 immediate children).
- Sibling-spawning recovery loops where multiple files under the same not-yet-existing parent each emit ancestor synthesis for the parent.

The savings are real but bounded. Time-box this against the dispatch-loop refactor risk before committing to the work.

## What to change

### `SyncEngine` Pass 1 dispatch ([core/app/sync/src/main/kotlin/.../SyncEngine.kt:355-410](core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:355))

1. Pre-process `sequentialActions` before the per-action loop: scan for **consecutive runs** of `CreateRemoteFolder` actions sharing a parent path. Consecutive matters because `sortActions()` already orders them — if two `CreateRemoteFolder`s are interleaved with a `Conflict` or `MoveRemote`, don't try to coalesce across the boundary (preserves dispatch ordering invariants).
2. For each run of size > 1, call `provider.createFolders(paths)` once; iterate the returned `List<CloudItem>` to do the per-folder DB upserts that today's `applyCreateRemoteFolder` does inline.
3. For runs of size 1 or providers without bulk semantics, fall through to today's per-action `applyCreateRemoteFolder` path.
4. Error handling: a bulk failure must not consume the whole consecutive-failure budget at once — count it as one failure (the operator's mental model is "the batch failed", not "5 folders failed"). Conversely on success, increment any counters once per folder, not once per batch.

### Tests

5. Extend [SyncEngineTest](core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt) FakeProvider with a `lastCreateFoldersBatch: List<String>?` spy. Test: enqueue 3 sibling `CreateRemoteFolder` actions sharing `/dir/`, run sync, assert spy captured all 3 paths in one `createFolders` call (not 3 separate `createFolder` calls).
6. Test the size-1 fallthrough: single `CreateRemoteFolder` still hits `createFolder` (verifies the pre-pass doesn't introduce a regression for the common case).
7. Test the boundary case: `CreateRemoteFolder("/a/b") → Conflict("/a/c") → CreateRemoteFolder("/a/d")` produces two separate `createFolder` calls (no cross-boundary coalescing).

## Acceptance

- A sibling fan-out of 3 `CreateRemoteFolder` actions under the same parent issues exactly **1** HTTP POST to Internxt (not 3).
- The 10-deep depth-first cascade still issues 10 sequential POSTs (no regression — depth-first is structural).
- Pass 1 consecutive-failure budget treats a batch failure as one failure, not N.
- All existing SyncEngine tests pass unchanged.
- A new SyncEngine test asserts the consecutive-`CreateRemoteFolder`-coalescing behaviour for siblings.

## Out of scope

- Reordering Pass 1 actions to maximise batching opportunities (the current `sortActions()` ordering is sacred for correctness reasons elsewhere in the engine).
- Cross-parent batching (the bulk endpoint requires a single `parentFolderUuid`).
- Async / parallel dispatch of separate batches (Pass 1 is intentionally sequential per UD-901 etc.).

## Related

- UD-368 (closed) — the API + SPI work that this ticket plugs into.
- UD-901b (closed) — the orphan-recovery cascade emitter that's the main beneficiary on the breadth-first edge.
- UD-317 (closed) — the per-item 409 recovery dance that the bulk path falls back to on conflict.
- [Internxt API ↔ provider audit](docs/audits/internxt-api-vs-spi.md) — `POST /folders` row already notes the deferral.
---
id: UD-200
title: First-time CLI UX: profile/account terminology, default-account handling, inline auth prompts
category: core
priority: medium
effort: M
status: open
opened: 2026-05-04
---
First-time CLI experience surfaces several friction points (drive-by feedback from a fresh `~/.config/unidrive/` walkthrough, 2026-05-04). Issues cluster into four orthogonal threads — fix individually, share the same UX-language refresh.

## 1. Profile vs provider terminology is muddled

`profile add` is named "Add a new provider profile (interactive wizard)" but the underlying entity is closer to an **account** (an authenticated identity at a provider). `profile list` shows columns `PROFILE / TYPE / SYNC ROOT / AUTH` — `TYPE` is the provider, `PROFILE` is the user-facing account label. Help text and column headings should agree.

Proposed naming pass:
- `profile` → keep as the CLI verb (it's accurate: a saved configuration), but reframe the help as "Manage cloud accounts (a profile binds one account at one provider to one local sync root)".
- Column rename: `PROFILE → ACCOUNT`, `TYPE → PROVIDER`. Matches the `STORAGE PROVIDER / ACCOUNT` column already used in `unidrive status`.
- `--provider` flag is doubly-overloaded — it accepts either a profile name *or* a provider type ("Provider profile name or type"). Split into `--account <name>` (preferred) with `--provider` kept as a deprecated alias for one minor version. Disambiguates the most common confusion: "is `internxt` the provider or my account?".

## 2. `unidrive profile add <type> <name>` positional form silently rejected

The error message advertises `Run 'unidrive profile add <type> <name>' to create your first profile`, but invoking it that way prints `Unmatched arguments from index 2`. The wizard-only form is the only working path. Either:
- (a) implement the positional form (`profile add <provider-type> <account-name> [--sync-root PATH]`), so the bootstrap message is honest and scriptable, or
- (b) fix the bootstrap message to say `Run 'unidrive profile add' to launch the interactive wizard.`

(a) is the right fix — Picocli supports it trivially and it unblocks scripted onboarding. The wizard stays as the default when args are absent.

## 3. Default-account handling is half-implemented and confusing

After `profile add internxt_gernot_…`, `unidrive status` showed:

```
⚠︎ Microsoft OneDrive  [– –]  …  never
```

i.e. the "default" provider fell through to a hard-coded OneDrive shape rather than the only configured account. Two clean options:

- **Option A (preferred): drop "default account" entirely.** Promote `--all` to be the default of `status`, `quota`, `log`, etc. Then `--all` becomes a no-op flag (deprecated alias) and bare `unidrive status` shows every configured account. Disambiguate via `-p <name>` for single-account view. Eliminates an entire class of "why is it showing OneDrive when I added Internxt?" surprises and matches user mental model (the CLI manages a *fleet* of accounts).
- Option B: track default-account explicitly. Add `profile set-default <name>` and pick the most recently added account if no default is set. Fine but more state to maintain — A is simpler.

## 4. Auth flow is disconnected from `profile add` and from "needs auth" errors

`profile add` finishes without ever offering to authenticate. Then `status --all` (or any operation) tries to query the unauthorized account and surfaces only error noise. Two integration points:

- **(a)** After a successful `profile add`, prompt: `Authenticate now? [Y/n]` — if yes, run the same auth flow as `unidrive auth -p <name>`. Skippable for non-interactive contexts (`--no-auth` flag or `UNIDRIVE_NONINTERACTIVE=1`).
- **(b)** Any command that hits `AccountNotAuthenticated` should offer to start the auth flow inline: `Account 'foo' is not authenticated. Run 'unidrive auth -p foo' now? [Y/n]` and, on confirmation, exec it. Same gate via `--no-prompt`/non-interactive env.

If a user has 23 accounts and none is authed, they should expect to be walked through 23 auth flows — that's the truth of "I have 23 unauthed accounts," not a UX bug. The bug is silently failing at the operation layer.

## Acceptance criteria

- Help text and column headings consistently use **account** for the user-facing identity and **provider** for the storage system. (#1)
- `profile add <provider> <name>` works as a positional form, in addition to the wizard. (#2)
- `unidrive status` (no flags) shows every configured account; `--all` becomes a deprecated no-op alias. Same for `quota`, `log`, and any other inventory-style command. Single-account view via `-p <name>`. (#3)
- The hard-coded OneDrive fallback in `unidrive status` is removed. (#3)
- `profile add` prompts for auth-now after success in interactive mode; suppressible. (#4a)
- `AccountNotAuthenticated`-class errors offer to start the auth flow; suppressible. (#4b)
- Smoke test: empty `~/.config/unidrive/` → `profile add internxt name --sync-root …` → auth prompt → `unidrive status` shows the new account, `[– –]` until first sync.

## Notes

- Threads 1 and 3 share a documentation refresh (README, `--help` text, every `man-page-style` doc). Worth doing in one pass to keep terminology consistent.
- Thread 2 is the smallest and a good first commit.
- Thread 4b touches every command's error handler — could be implemented as a single `runRequiringAuth` wrapper so the prompt logic lives in exactly one place.
---
id: UD-201
title: CLI sync summary misleading: 'Reconciled: 0 actions' when --upload-only/--download-only filters everything out
category: core
priority: medium
effort: S
status: open
opened: 2026-05-04
---
CLI sync summary line is misleading when reconciler actions are filtered out by `--upload-only` / `--download-only`.

## Repro (2026-05-04 session)

Empty local sync root, fresh Internxt profile with ~165k remote items. Ran:

```
unidrive -p internxt_gernot_krost_posteo sync --upload-only
```

CLI output:

```
Scanning remote changes... 10,700 items · 1:58
Reconciling... 165,509 / 165,509 items · 0:00
Reconciled: 0 actions
Sync complete: 0 downloaded, 0 uploaded, 0 conflicts (2012.2s)
```

`~/Internxt` stayed empty. User concluded "I'm doing something wrong" — the CLI gave no signal that 165k actions were reconciled and then thrown away by the upload-only filter.

## What the log actually says

```
2026-05-04 17:04:24.835 INFO  Reconciler - Reconcile started: 165509 remote, 0 local
2026-05-04 17:04:25.549 INFO  Reconciler - Reconcile complete: 165509 actions in 714ms
```

So the reconciler decided on 165,509 actions (all download-side, given local is empty). The executor then dropped all of them because `--upload-only` filters out download actions, and the CLI summary reported the post-filter count as if it were the reconciler verdict.

## Bug shape

`Reconciled: N actions` should reflect what the reconciler decided, not what the executor accepted after directional filtering. The current behaviour conflates two distinct numbers: "what the reconciler *wanted* to do" vs "what the executor *did* after `--upload-only` / `--download-only` filtering". They can differ by 165k.

## Proposed fix

Differentiate the counters in the CLI summary:

```
Reconciled: 165,509 actions
  └─ 165,509 skipped (--upload-only filtered out download actions)
Executed:   0 actions
Sync complete: 0 downloaded, 0 uploaded, 0 conflicts (2012.2s)
```

Or, more compact:

```
Reconciled: 165,509 actions (165,509 skipped by --upload-only); 0 executed
Sync complete: 0 downloaded, 0 uploaded, 0 conflicts (2012.2s)
```

Either form makes it impossible to misread "0 actions" as "nothing to do" when in reality the user told us to ignore everything.

## Acceptance

- Test scenario: empty local + N>0 remote + `--upload-only` → CLI reports a non-zero "reconciled" count and a non-zero "skipped" count whose reason mentions `--upload-only`.
- Inverse: full local + empty remote + `--download-only` → reconciled count > 0, skipped count > 0 referencing `--download-only`.
- Bidirectional sync (no flag) → skipped count is 0 (or absent).
- Bonus: when `skipped > 0` print a one-line hint: `Hint: re-run without --upload-only to actually download these items.` Hint suppressible via `--no-hints` for scripted callers.

## Notes

- The log line `Reconcile complete: 165509 actions in 714ms` is correct and stays — only the CLI presentation layer needs the differentiation.
- This pairs nicely with UD-200's "first-time UX" theme but is mechanically separate (reconciler/executor accounting, not terminology). Keep the tickets independent.
- `--dry-run` already prints "would do X" semantics correctly; the same accounting shape is the precedent to follow.
---
id: UD-202
title: Provider-specific CLI flags should be registered via the provider SPI, not hardcoded in core CLI commands
category: core
priority: medium
effort: M
status: open
opened: 2026-05-04
---
Provider-specific CLI flags should not be hard-coded in the core CLI. Providers should register their own flags via the capability/SPI surface, and the CLI should surface them in `--help` only for the active provider.

## Symptom (2026-05-04)

`unidrive sync --help` always shows:

```
--fast-bootstrap   UD-223: skip first-sync enumeration by adopting the
                   remote's current state as the cursor. Items already
                   present on the remote before this moment stay invisible
                   until they next mutate. OneDrive only; other providers
                   warn + ignore.
```

The flag is **hard-coded in `SyncCommand.kt`** but is declared OneDrive-only in its own help text. On every other provider (Internxt, S3, SFTP, WebDAV, …) it's a footgun: the user reads the option, tries it, and gets a runtime warning ("provider 'X' does not declare the capability"). It pollutes help, confuses first-time users, and locks an extension point into the wrong layer.

## What the architecture already supports

The capability machinery is there:
- `core/app/core/src/main/kotlin/org/krost/unidrive/Capability.kt:11` — `sealed class Capability` with `FastBootstrap`, `DeltaShared`, etc.
- `core/providers/onedrive/.../OneDriveProvider.kt:43` — declares `Capability.FastBootstrap`.
- `core/app/sync/.../SyncEngine.kt:641-678` — checks `Capability.FastBootstrap in provider.capabilities()` at runtime.

What's missing is the **CLI binding side**: providers can't register CLI options/flags scoped to a particular command (`sync`, `quota`, …), so `SyncCommand` ends up with a hard-coded list that includes every provider's special-case flag.

## Code refs

- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:104-111` — hardcoded `--fast-bootstrap` declaration
- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:365` — flag plumbed into `SyncEngine`
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:226` — config-side wiring, also generic
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:635-678` — capability gate (correct layer for the runtime check)
- `core/providers/onedrive/.../OneDriveProvider.kt:43` — `Capability.FastBootstrap`

## Proposed shape

Two layers:

1. **Provider SPI for command-scoped CLI options.** Each provider can declare options it supports per command:
   ```kotlin
   interface CloudProvider {
       fun cliOptions(command: CliCommand): List<CliOption> = emptyList()
   }

   data class CliOption(
       val names: List<String>,                  // ["--fast-bootstrap"]
       val description: String,                  // "skip first-sync enumeration..."
       val arity: Arity = Arity.FLAG,            // FLAG | SINGLE | REPEATABLE
       val capability: Capability? = null,       // for capability-gated runtime check
       val configKey: String? = null,            // optional config-file mirror, e.g. "fast_bootstrap"
   )
   ```
2. **CLI command resolves provider at parse time** (the active profile is known via `-p`/`--account` or default), then composes the provider's `cliOptions(SYNC)` into the picocli command's option list before parsing.

When `-p` selects a non-OneDrive profile, `unidrive sync --help` shows no `--fast-bootstrap`. When `-p` selects OneDrive, it does. Multi-provider invocations (no `-p`, fleet mode after UD-200 lands) show only options that are common to *all* configured providers, with a hint that provider-specific flags exist via `unidrive -p <name> sync --help`.

## Acceptance

- `unidrive -p <internxt-profile> sync --help` does **not** list `--fast-bootstrap`.
- `unidrive -p <onedrive-profile> sync --help` **does** list `--fast-bootstrap`, sourced from `OneDriveProvider.cliOptions(SYNC)`.
- Adding a new provider-specific flag requires zero edits to `SyncCommand.kt` — only a `cliOptions()` override on the provider.
- `SyncCommand.kt` no longer references `FastBootstrap` or any other provider-specific feature by name.
- Runtime capability check at `SyncEngine.kt:641-678` stays — the SPI registers the option, the engine still gates execution.
- Test: provider with no special options → vanilla `sync` help. Provider with one option → option appears. Two providers each with their own option → composing `sync --help` for one shows only that provider's option.

## Notes

- Mirrors UD-200 thread #1 spirit (CLI surface should match the operator's mental model). Implementations are independent — file separately, can land in either order.
- Picocli supports dynamic option registration via `CommandLine.addSubcommand` / `CommandLine.addOption` at runtime (or via `IFactory`). Picking the right hook so `--help` rendering works correctly is the implementation crux.
- Same pattern should apply to *every* command that today includes provider-specific behaviour: `quota` (some providers don't have quotas), `share` (provider-specific link-type flags), `versions` (some providers have no version history). Don't add the SPI just for `sync` — design it command-agnostic.
- Out of scope: profile-level config keys (e.g. `fast_bootstrap = true` in `config.toml`). Those should also be capability-gated but live on a different surface; flag this in implementation as a follow-up.

## Cross-refs

- UD-223 — original `--fast-bootstrap` ticket.
- UD-200 — first-time UX (terminology, default-account, inline auth) — sibling but mechanically separate.
---
id: UD-205
title: InFlightDedup<K,V>: share concurrent identical requests via ConcurrentHashMap<K, Deferred<V>>
category: core
priority: high
effort: S
status: open
opened: 2026-05-04
---
**Source:** drive-server-wip + swift-core + drive-desktop research (agent `a876235`, 2026-05-04). The single highest-leverage finding across all three repos. Drive-desktop's `get-in-flight-request.ts` keeps a `Map<key, Promise<T>>` so concurrent callers asking for the same logical request share one HTTP round-trip. Trivial to port to Kotlin coroutines as `ConcurrentHashMap<K, Deferred<V>>`.

## Why

Reconciler enumeration, status polling, UI fan-out, and the scheduler all hit the same provider methods (`listChildren`, `getMetadata`, `delta`) concurrently. Without dedup, N callers = N round-trips. With dedup, N callers = 1 round-trip + (N-1) cache hits on the in-flight `Deferred`.

## Code refs

- New file: `core/app/core/src/main/kotlin/org/krost/unidrive/http/InFlightDedup.kt`
- Wire into provider call sites that are reasonably idempotent and frequently fan-out:
  - `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt` — `getFolderContents`, `getFileInfo`, `getFolderTree`
  - `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveApiService.kt` — `getMetadata`, listings
  - `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt` — `getValidCredentials` (token refresh under concurrent callers must dedup)

## Proposed shape

```kotlin
class InFlightDedup<K : Any, V> {
    private val inFlight = ConcurrentHashMap<K, Deferred<V>>()

    suspend fun load(key: K, loader: suspend () -> V): V = coroutineScope {
        // Hot path: existing in-flight Deferred.
        inFlight[key]?.let { return@coroutineScope it.await() }
        val deferred = async(start = CoroutineStart.LAZY) { loader() }
        val winner = inFlight.putIfAbsent(key, deferred) ?: deferred
        try {
            winner.await()
        } finally {
            // Whoever started it is responsible for clearing it; losers no-op.
            inFlight.remove(key, winner)
        }
    }
}
```

Key choice: `(provider-id, method, args)` — e.g. `"internxt:listChildren:/Documents"`. Document the hashing requirement on `K`.

## Acceptance

- `InFlightDedupTest`: 100 concurrent `load("k", { delay(500); 42 })` calls — exactly one loader invocation, all 100 callers receive 42.
- Failure propagation: loader throws → all callers receive the same exception, in-flight map is cleared.
- Cancellation: caller cancels → if it's the only caller, loader is cancelled; if other callers are still awaiting, loader continues.
- Wired into Internxt's `getFolderContents`: integration test with mock that delays 500ms — 100 parallel `listChildren("/Documents")` calls produce one mock invocation.
- Token refresh: 100 concurrent calls observing a near-expired JWT → exactly one refresh call.

## Notes

- Cache scope is **in-flight only** — not a TTL cache. Once the request completes, the entry is removed. Successful results may be cached separately by callers; this ticket does not introduce a stale-data concern.
- Pairs naturally with UD-352a (parallel pagination) — the dedup map prevents the parallel paginators from racing on the same offset under the offset-walled-pagination model.
- Out of scope: distributed dedup across processes. In-process only.

## Cross-refs

- drive-desktop `src/infra/drive-server-wip/in/get-in-flight-request.ts` — direct shape source.
- Cross-repo synthesis: "the single highest-leverage finding."
---
id: UD-207
title: Codify HTTP retry-policy matrix (5xx/4xx/network/unknown) in HttpRetryBudget KDoc + tests
category: core
priority: low
effort: XS
status: open
opened: 2026-05-04
---
**Source:** drive-desktop research (agent `a876235`, 2026-05-04). Drive-desktop's `client-wrapper.service.ts` carries this comment as the cleanest retry-policy doc-comment in any of the three Internxt repos:

> 2XX: success, 3XX/4XX: don't retry (except 408/429), 5XX: retry always, network errors: retry always, unknown exceptions: retry up to 3.

unidrive's `HttpRetryBudget` (UD-228) should adopt this as its **canonical, documented matrix** — written into the budget's KDoc, locked in unit tests, and cross-linked from `docs/dev/lessons/`. Currently the policy lives in code and per-provider audits but isn't centralised.

## Code refs

- `core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt` — KDoc target
- `core/app/core/src/test/kotlin/org/krost/unidrive/http/HttpRetryBudgetTest.kt` — extend with explicit matrix-row tests

## Proposed matrix (verbatim)

| Class | HTTP status / signal | Retry? | Cap | Backoff | Notes |
|-------|----------------------|--------|-----|---------|-------|
| Success | 2xx | n/a | n/a | n/a | |
| Permanent client error | 4xx (except 408, 429) | **No** | n/a | n/a | Fail fast |
| Timeout | 408 | Yes | budget.maxRetries | exp + jitter | |
| Throttle | 429 | Yes | budget.maxRetries | honor `Retry-After` (cap = budget.maxRetryAfter) | UD-232 alignment |
| Server error | 5xx | Yes | budget.maxRetries | exp + jitter | |
| Network error (connect/read/SSL) | (no status) | Yes | budget.maxRetries | exp + jitter | |
| Unknown exception | (caller-classified `Unknown`) | Yes | min(3, budget.maxRetries) | exp + jitter | The lower cap is deliberate |

`exp + jitter` = exponential backoff with full jitter, base=1s, cap=`budget.maxRetryAfter`.

## Acceptance

- `HttpRetryBudget.kt` KDoc includes the table verbatim (markdown table fine).
- `HttpRetryBudgetTest` has one test per row, asserting the budget's `decide(...)` returns the expected outcome.
- A second test: removing any row (mocked) fails the suite — locks the matrix as a contract.
- `docs/dev/lessons/http-retry-policy.md` (new) summarises the matrix with a link back to the budget code.
- Per-provider audit docs (`docs/providers/*-robustness.md`) cross-link to the matrix instead of restating it.

## Notes

- This is documentation + test-locking, not a behaviour change (assuming the current `HttpRetryBudget` already implements the matrix). If implementation diverges from the matrix, file follow-up tickets per divergence — do not silently rewrite the budget here.
- Pairs with UD-330 (cross-provider rollout) — when reviewers ask "what's the retry policy?" they should be able to point to this doc, not the code.
- Out of scope: per-provider override hooks. The budget is intentionally one-policy-fits-all; provider-specific exceptions get their own ticket.

## Cross-refs

- drive-desktop `src/infra/drive-server-wip/in/client-wrapper.service.ts` — quotation source.
- UD-228 / UD-330 — the budget itself and its rollout.
- UD-232 — throttle / Retry-After.
---
id: UD-208
title: Audit JWT-refresh model across providers; verify per-call check vs scheduler-based
category: core
priority: low
effort: XS
status: open
opened: 2026-05-04
---
**Source:** drive-desktop research (agent `a876235`, 2026-05-04). drive-desktop's `TokenScheduler.ts` uses `setTimeout(refresh, exp - now - 1d)` to schedule JWT refresh. This is **fragile under suspend/resume** — if the system was suspended for >24h, the `setTimeout` doesn't catch up.

unidrive's `AuthService.kt` *appears* to use a per-call refresh model (`isJwtExpired()` checked at every operation, see `InternxtProvider.kt:54-58`), which is robust. But this hasn't been confirmed across the AuthService surface. **First step is verification, not implementation.**

## Investigation

1. Read `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt` end-to-end.
2. Confirm: is JWT expiry checked at every API call site, or is there any background scheduler / `setInterval`-style refresh?
3. Audit the same question for OneDrive (`core/providers/onedrive/.../auth/`) and any other provider with token refresh.
4. Document findings in `docs/providers/auth-refresh-model.md` (new) — short doc, one paragraph per provider, citing code refs.

## Decision tree

- **All providers refresh per-call:** No code change. Land the `auth-refresh-model.md` audit doc, close ticket as informational.
- **Any provider uses a wall-clock scheduler:** File a follow-up ticket per offending provider, scoped to "replace scheduler with per-call check OR add a wall-clock-aware re-arm on resume."

## Acceptance

- `docs/providers/auth-refresh-model.md` exists, lists each provider's auth refresh strategy with one code-ref per provider.
- A clear verdict: "robust per-call" or "scheduler-based with risk".
- If risk found: linked follow-up tickets.
- If no risk found: a one-line note in this ticket's resolution explaining why the drive-desktop pattern is worse than what unidrive already does.

## Notes

- This is verify-then-decide. Don't pre-commit to an implementation strategy.
- Out of scope: token storage encryption, refresh-token rotation policy, OAuth flow specifics. Just refresh-trigger model.
- Cheap ticket — one read pass per provider's `AuthService`.

## Cross-refs

- drive-desktop `src/apps/main/token-scheduler/TokenScheduler.ts` — fragile pattern source.
- drive-desktop research, "Cons / footguns" → token refresh.
---
id: UD-300
title: Internxt: streaming AES-CTR decrypt during download (no buffer-then-decrypt)
category: providers
priority: high
effort: M
status: open
opened: 2026-05-04
---
**Source:** internxt/sdk research (agent `a4990ef`, 2026-05-04). The SDK's `decryptFile(...)` callback is invoked **after** the full encrypted file lands on disk — forcing any consumer to write ciphertext to a temp file then read+decrypt to the final file (2× I/O).

unidrive's Internxt provider should not inherit this anti-pattern. AES-CTR is seekable, so streaming decrypt is mechanically simple: a single `Cipher.getInstance("AES/CTR/NoPadding")` instance updated chunk-by-chunk while the bytes flow into the final file.

## Code refs (verify before editing)

- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtCrypto.kt` — current decrypt path
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt` — `getContent` / download orchestration
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt` — HTTP body handling for shard GETs

## Proposed shape

```kotlin
val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
    init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
}
response.body!!.byteStream().use { src ->
    Files.newOutputStream(target, CREATE, TRUNCATE_EXISTING).use { dst ->
        CipherOutputStream(dst, cipher).use { cos ->
            src.copyTo(cos, bufferSize = 64 * 1024)
        }
    }
}
```

Variant for multi-shard downloads: the same `Cipher` instance is updated across shards in shard-index order, since CTR is a stream cipher with state preserved across chunks.

## Acceptance

- `dd if=/dev/urandom of=/tmp/big bs=1M count=500` → upload → `unidrive free /big` → re-hydrate via `unidrive get`. Peak write byte count (`iotop -d 1` or `pidstat -d`) equals file size, not 2×.
- Integration test: hydrate a 100 MB file, assert no temp files remain in `${java.io.tmpdir}` or `XDG_CACHE_HOME` matching `unidrive-*` after success.
- Throughput regression test: hydrate of a 500 MB file completes ≥30 % faster than the buffer-then-decrypt baseline (one-shot benchmark, recorded in PR).
- Existing `InternxtIntegrationTest` and `InternxtCryptoTest` pass unchanged.

## Notes

- Pairs with UD-301 (shard SHA-256 verification) — the streaming pipeline must interleave `MessageDigest.update` with `Cipher.update` so verification happens before plaintext reaches disk. Land them together.
- Memory profile: O(buffer size = 64 KB), independent of file size.
- Out of scope: parallel multi-shard download (single seekable stream is fine for v1).

## Cross-refs

- internxt/sdk research report — `Top 5 ideas to steal` #2.
- swift-core's `Encrypt.swift` uses the same single-`StreamCryptor` pattern in reverse for upload — borrow the shape.
---
id: UD-301
title: Internxt: client-side shard SHA-256 verification before fsync
category: providers
priority: high
effort: S
status: open
opened: 2026-05-04
---
**Source:** internxt/sdk research (agent `a4990ef`, 2026-05-04). AES-CTR provides confidentiality but **no integrity** — a bit-flip in cloud storage silently corrupts plaintext at the same offset. The SDK exposes `shards[].hash` (SHA-256 of encrypted bytes) but **never verifies it**. unidrive must, since the AES-CTR + RIPEMD-160(SHA-256) bridge contract puts the integrity burden on the client.

## Code refs (verify before editing)

- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt` — `getContent`, shard iteration
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt` — `getFileInfo` returns `shards: [{index, size, hash, url}]`
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/model/` — shard DTO

## Proposed shape

Per shard, during the streaming decrypt path (UD-300):

```kotlin
val sha = MessageDigest.getInstance("SHA-256")
val cipher = Cipher.getInstance("AES/CTR/NoPadding")...

while (src.read(buffer).also { n = it } > 0) {
    sha.update(buffer, 0, n)            // hash the *encrypted* bytes
    cipher.update(buffer, 0, n, plain)  // decrypt
    dst.write(plain, 0, ...)
}

val computed = sha.digest()              // 32 bytes raw
val expected = HexFormat.of().parseHex(shard.hash)
if (!MessageDigest.isEqual(computed, expected)) {
    throw ShardHashMismatchException(shard.index, expected, computed)
}
```

Hash check happens **before** `dst.flush()` / fsync. If verification fails, delete the partial output and fail the hydrate cleanly so retry semantics work.

The SDK derives the upload-side shard hash as `RIPEMD-160(SHA-256(encrypted_bytes))` (see swift-core `Encrypt.getFileContentHash`). Confirm the **download-side** `shard.hash` field is the same form (RIPEMD-160 of SHA-256) before wiring; if so, the comparison runs both digests in sequence over the same bytes.

## Acceptance

- New `ShardHashMismatchException(shardIndex: Int, expected: ByteArray, computed: ByteArray)` in `internxt.error`.
- Unit test corrupts 1 byte mid-stream via mock `Source` wrapper → download throws `ShardHashMismatchException`, target file does not exist after the throw.
- Multi-shard test: 5-shard file with shard 3 corrupted → download throws referencing `shardIndex=3`.
- Happy path: 100 MB file integration test passes; manifest hash matches end-to-end.
- Log line on mismatch contains `shardIndex`, expected/computed hex prefixes (8 bytes each), and the file's plain-name.

## Notes

- **Land together with UD-300.** Streaming verify on top of streaming decrypt is one PR; bolting verification onto a buffer-then-decrypt path doubles the I/O before the perf fix lands.
- Hash algorithm verification: confirm `RIPEMD-160(SHA-256(...))` vs raw `SHA-256(...)` against a known-good fixture before committing the comparison code. The two SDK reports disagree slightly on framing.
- This is a security-relevant change. Add to `docs/SECURITY.md` "client-side integrity verification" section.

## Cross-refs

- internxt/sdk research — `Top 5 things to avoid` #3 ("Don't trust AES-CTR ciphertext without out-of-band integrity").
- swift-core `Encrypt.getFileContentHash` documents the hash construction.
- UD-300 (streaming decrypt) — sibling, must land together.
---
id: UD-302
title: Internxt: idempotency-key on mutations (header + dedup-by-natural-key fallback)
category: providers
priority: high
effort: S
status: open
opened: 2026-05-04
---
**Source:** internxt/sdk research (agent `a4990ef`, 2026-05-04). Internxt's REST API exposes no idempotency-key contract. With UD-330 expanding retry coverage to non-GET verbs, a retried `POST /folders` after a network blip can create a duplicate folder with a different UUID. Same for `POST /files/start`, `POST /files/finish`, `POST /folders/{uuid}/move`, etc.

## Code refs

- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt` — every `POST/PATCH/DELETE/PUT` call site
- `core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt` (UD-228 budget that gates retry behaviour)

## Two-layer fix

**Layer 1 — opportunistic header.** Generate a v4 UUID per *logical* mutation (not per HTTP attempt). Pass it through the retry budget so all attempts share the same key. Header name candidates: `X-Idempotency-Key`, `Idempotency-Key`. **Server support unverified** — if the server ignores the header, this is a no-op; if accepted later, it just starts working. Free upside, near-zero cost.

**Layer 2 — client-side dedup-by-natural-key fallback.** Required because we cannot trust the server. For mutations that *should* be unique, dedup by:
- `createFolder(parentUuid, plainName)` — pre-flight `POST /folders/content/{parentUuid}/folders/existence` (batch existence, see drive-server-wip §1) before retry. If exists, treat retry as success and return the existing UUID.
- `createFile(parentUuid, plainName, ...)` — same shape, different endpoint (`/files/existence`).
- `moveFile`, `renameFile`, `deleteFile` — naturally idempotent if the target state matches; can be retried safely without dedup.

For the upload finish endpoint specifically: a retried `POST /files/finish` with the same `(uuid, index, shards[].hash)` payload should be treated by the server as the same file (uuid is server-assigned by `/start`). Verify server behavior; document.

## Acceptance

- `InternxtApiService` carries an opaque `idempotencyKey: String?` parameter into every mutation method; the retry budget reuses the same key for all attempts of one logical call.
- Unit test: mock returns 503 once then 200; `createFolder` is invoked once at the SyncEngine level, results in exactly one folder remotely (`listChildren` count == 1), HTTP is observed twice with identical `X-Idempotency-Key` header.
- Existence-check fallback: forced retry where the first attempt actually succeeded server-side (mock returns 503 to client but persisted) — second attempt detects the existing folder via existence-check and returns its UUID instead of creating a duplicate.
- Documented in `docs/providers/internxt-robustness.md` — header sent unconditionally, dedup-fallback active for create-class mutations.

## Notes

- This ticket has a research dependency: confirm by testing whether the server respects `X-Idempotency-Key` (or any sibling header). If yes, Layer 2 is a belt-and-braces safety net; if no, Layer 2 is the only defence.
- Pairs with UD-330 — adding retry to non-GETs without idempotency safety = duplicate-storm risk. **Block UD-330 rollout to non-GETs on this ticket landing.**
- Out of scope: cross-provider idempotency model. Each provider has its own contract (S3 has request-id, OneDrive uses ETag preconditions).

## Cross-refs

- internxt/sdk research — `Top 5 things to avoid` #1 ("No idempotency-key on POSTs").
- UD-330 — HttpRetryBudget cross-provider rollout (must coordinate).
- drive-server-wip research — `/files/existence` and `/folders/existence` batch endpoints (200-item arrays).
---
id: UD-303
title: Internxt: tie-break delta walk by (updatedAt, lastUuid) to defeat offset-pagination boundary drift
category: providers
priority: high
effort: M
status: open
opened: 2026-05-04
---
**Source:** drive-server-wip + drive-desktop research (agent `a876235`, 2026-05-04). The Internxt API's only delta-ish surface is `(updatedAt ASC, status=ALL, limit=N, offset=M)` walked from a saved high-water-mark. The server has no secondary sort key. Items sharing an `updatedAt` value that fall on a page boundary can be **dropped or duplicated** between pages. Drive-desktop's checkpoint store has the same vulnerability — confirmed in `sync-remote-files.ts`.

## Why this is silent

If items A, B, C all have `updatedAt = T`, and the page break falls between B and C:
- Page N ends with [..., A, B] @ `updatedAt = T`.
- Cursor saved as `T`. Next sync queries `updatedAt > T` — **C is missed**.
- Or: cursor saved as `T` inclusive, page N+1 returns [B, C, ...] — **B is duplicated** and treated as a new mutation.

165k-item account → at any non-trivial timestamp granularity, the boundary collision is statistically inevitable on first cold sync.

## Code refs

- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:313-340` — `delta()` paginates flat
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt` — `listFiles(cursor, limit, offset)`
- Sync state DB schema — wherever the cursor is persisted (likely `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/state/`)

## Proposed shape

Extend the cursor from `Instant` to `(Instant, lastUuid: String)`:

```kotlin
data class InternxtCursor(val updatedAt: Instant, val lastUuid: String?)
```

Walk:
1. Query `updatedAt >= cursor.updatedAt` (inclusive) — ASC by `(updatedAt, uuid)` if the server supports it; otherwise sort client-side post-fetch.
2. Drop items where `updatedAt == cursor.updatedAt && uuid <= cursor.lastUuid` (already seen).
3. After processing batch, set new cursor to `(lastItem.updatedAt, lastItem.uuid)`.
4. If the entire batch shares one `updatedAt`, *do not advance the timestamp* — only advance `lastUuid` until a different `updatedAt` is seen.

If the API doesn't return `uuid` in the listing payload, fall back to `(updatedAt, lastPlainNameLexicographic)` as a weaker tiebreak. Document the choice.

## Acceptance

- Synthetic fixture: 5 items all with `updatedAt = T`, paginated with `limit=2`, straddling a 2-page boundary at item index 2 → all 5 items observed exactly once across the walk.
- Regression test: removing the tiebreak causes the test to fail (drops 1 or duplicates 1).
- Persisted cursor schema gains `lastUuid` column with backwards-compatibility migration.
- Resumed sync from saved `(T, "uuid-X")` cursor against a remote that has gained new items at `updatedAt = T` after `uuid-X` lexicographically picks them up; items at `updatedAt = T` before `uuid-X` are not re-seen.

## Notes

- **Verify server sort behavior first.** If `/files?sort=updatedAt&order=ASC` actually does secondary sort on `uuid` server-side, the client-side dedup is enough; otherwise we need post-fetch resort. Drive-server-wip controllers don't show secondary sort in the audit — assume client-side resort is needed.
- Pairs with UD-352a (parallel pagination) — parallelizing the walk across non-overlapping `(updatedAt, lastUuid)` ranges is harder than sequential; ordering matters. Sequence: B2 first, then UD-352a builds on the cursor shape.
- Out of scope: stable cursor across server-side cluster failover (Internxt's server may have its own consistency story; not our problem until proven otherwise).

## Cross-refs

- drive-server-wip `src/lib/http/limits.ts` (offset cap = 1M).
- drive-server-wip `src/modules/file/dto/get-files.dto.ts` (the DTO shape).
- drive-desktop `src/apps/main/remote-sync/files/sync-remote-files.ts` (the mirror-image bug in their checkpoint code).
- Cross-repo synthesis: "the single highest-leverage takeaway."
---
id: UD-304
title: Internxt: adopt swift-core multipart constants (100 MB threshold, 50 MB chunk, 6 parallel)
category: providers
priority: medium
effort: S
status: open
opened: 2026-05-04
---
**Source:** swift-core research (agent `a876235`, 2026-05-04). The bridge contract is empirically tuned to:

```
MULTIPART_MIN_SIZE   = 100 * 1024 * 1024   // 100 MB threshold
MULTIPART_CHUNK_SIZE =  50 * 1024 * 1024   //  50 MB per part
maxConcurrent        = 6                   // (or 1 in low-bandwidth mode)
```

Confirmed in `Sources/InternxtSwiftCore/Services/Network/NetworkFacade.swift`. The JS SDK delegates to `inxt-js` which mirrors the same numbers. **Diverging from these constants risks finish-API edge cases** (unverified server expectations on part size, count, alignment).

unidrive's Internxt provider should adopt the same constants as defaults.

## Code refs

- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtConfig.kt` — add three named constants
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt` (or wherever `upload()` lives) — branch on size, dispatch to multipart path
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt` — `startMultipart`, `finishMultipart` endpoints (verify present; if not, build them on the bridge contract)

## Proposed shape

```kotlin
data class InternxtConfig(
    ...
    val multipartMinSize: Long = 100L * 1024 * 1024,
    val multipartChunkSize: Long = 50L * 1024 * 1024,
    val maxParallelParts: Int = 6,
)
```

Configurable per profile (`config.toml`) for power users; defaults match swift-core exactly. Document in `docs/providers/internxt.md` that diverging from the defaults is unsupported.

## Acceptance

- `InternxtConfig` exposes the three knobs with the swift-core defaults.
- Upload of a 200 MB synthetic file uses the multipart path with: 4 parts of 50 MB, ≤6 in-flight at peak, finish-API payload `parts: [{ETag, PartNumber}]` of length 4.
- Upload of a 50 MB file uses the **single-PUT** path (below threshold).
- Upload of a 99 MB file uses single-PUT; 101 MB uses multipart.
- Integration test asserts the size threshold is exclusive at 100 MB and the chunking math (`ceil(size / 50MB) = partCount`).
- `--reduce-bandwidth` (or equivalent config flag) caps concurrency to 1.

## Notes

- **Memory profile warning**: 6 × 50 MB = 300 MB peak RSS if parts are held in `ByteArray`/`Buffer` like swift-core does. UD-307 (stream parts via temp file) is the mitigation; **land UD-304 and UD-307 together** so we never ship the in-memory variant.
- Out of scope: dynamic chunk sizing based on observed throughput. Static defaults match the upstream contract.
- Out of scope: parallel multipart-finish (the bridge accepts one finish call per upload).

## Cross-refs

- swift-core `Sources/InternxtSwiftCore/Services/Network/NetworkFacade.swift`.
- swift-core `Sources/InternxtSwiftCore/Services/Network/Encrypt.swift` — chunking + encryption interleave.
- UD-307 (streaming parts via temp file) — required co-landing.
---
id: UD-306
title: Internxt: investigate WebSocket NOTIFICATIONS_URL — change-feed or marketing-only?
category: providers
priority: low
effort: XS
status: open
opened: 2026-05-04
---
**Source:** drive-desktop research (agent `a876235`, 2026-05-04). drive-desktop opens a `socket.io-client` connection to `process.env.NOTIFICATIONS_URL` and uses received events as **wake signals** (not source-of-truth) to trigger a checkpointed re-sync. UD-370 (CLOSED, 2026-05-03) disproved that Internxt's `/notifications` REST endpoint is a change feed — it's marketing-only.

Open question: **does the WebSocket endpoint expose a different surface than the REST `/notifications`?** drive-desktop subscribes via socket.io and forwards to `RemoteNotificationsModule.processWebSocketEvent`, but the message schema lives in a closed-source `@internxt/drive-desktop-core/build/backend` package — not visible from the public repo.

This ticket is **research-only**. If the answer is "yes, real change-feed exists," design follow-up. If "no, it's the same marketing surface," close as duplicate-of-UD-370.

## Investigation steps

1. Run drive-desktop locally with a test account, capture the WebSocket frames (browser devtools or `wireshark` against the unencrypted socket if possible — likely TLS, so use mitmproxy or the Electron devtools).
2. Inspect message types and payload shapes. Look specifically for `file.created`, `file.updated`, `file.deleted`, `folder.*` events.
3. Note the `NOTIFICATIONS_URL` value (config-published or hardcoded in the binary).
4. Document findings in `docs/audits/internxt-websocket-feasibility.md` (companion to the existing `internxt-notifications-feasibility.md`).

## Acceptance

- `docs/audits/internxt-websocket-feasibility.md` exists and contains:
  - The endpoint URL.
  - Sample message frames (with sensitive fields elided).
  - A one-sentence verdict: "real change feed" / "marketing only" / "ambiguous".
  - If real: a sketch of how it would slot into unidrive's sync engine (wake signal triggering checkpointed re-sync, NOT source-of-truth).
- If real → file a follow-up implementation ticket.
- If not → close this ticket as resolved-with-no-action and update `internxt-notifications-feasibility.md` with the cross-reference.

## Notes

- Investigation only. No code changes.
- Even if the WebSocket is a real change feed, the implementation is **DEFER post-MVP** — the offset-walked checkpoint walk works (UD-352a improvements + UD-303 tiebreak make it more robust) and adding a real-time push surface is an optimization, not a correctness gap.
- This ticket's value is binary: either it kills speculation about a phantom optimization, or it surfaces a real one. Either way, the audit is cheap.

## Cross-refs

- UD-370 (closed) — `/notifications` REST is marketing-only.
- drive-desktop `src/apps/main/realtime.ts` — socket.io subscription site.
- drive-desktop `src/apps/main/remote-notifications-module/` — handler layer (private package, not directly inspectable).
---
id: UD-210
title: Re-stat-during-upload abort guard: detect local file mutation mid-upload
category: core
priority: medium
effort: S
status: open
opened: 2026-05-04
---
**Source:** drive-desktop research (agent `a876235`, 2026-05-04). drive-desktop's `upload-file.ts` calls `fs.stat(path)` inside the upload progress callback and aborts the upload if `stats.size !== sizeAtStart`. This catches the "user edited the file mid-upload" case which would otherwise corrupt the remote with a truncated tail or mixed-version content.

unidrive should apply this provider-agnostic guard across **every** provider's upload path.

## Code refs

- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Executor.kt` (or wherever `upload` is dispatched per-action)
- `core/providers/internxt/.../InternxtProvider.kt` — `upload` / `putContent`
- `core/providers/onedrive/.../OneDriveProvider.kt` — `upload`
- `core/providers/s3/...`, `core/providers/sftp/...`, `core/providers/webdav/...` — same surface

## Proposed shape

Two compatible places for the check:

**Option A (per-provider, in the upload coroutine):** progress callback re-stats and cancels the parent `Job`.

**Option B (orchestrator-level, in Executor):** wrap the upload call in a coroutine that periodically (or in the progress callback) re-stats and cancels. Provider doesn't need to know.

Option B is preferable because it covers all providers uniformly without per-provider patches. Sketch:

```kotlin
suspend fun uploadWithMutationGuard(
    file: Path,
    upload: suspend (progress: (Long) -> Unit) -> Unit,
) {
    val sizeAtStart = Files.size(file)
    val mtimeAtStart = Files.getLastModifiedTime(file)
    upload { _ ->
        val current = Files.size(file)
        val mtime = Files.getLastModifiedTime(file)
        if (current != sizeAtStart || mtime != mtimeAtStart) {
            throw LocalFileMutatedDuringUploadException(file, sizeAtStart, current)
        }
    }
}
```

Both `size` and `mtime` checked: an editor that overwrites in place with the same size still bumps mtime. A `truncate -s 1k` followed by an append back to original size *might* preserve size; mtime catches it.

## Acceptance

- New `LocalFileMutatedDuringUploadException(path, sizeBefore, sizeAfter)` with the path and sizes for the operator log.
- Integration test: upload a 100 MB file, mid-flight `truncate -s 1k` it. Upload aborts. Remote contains no half-uploaded file (or contains an aborted multipart that gets cleaned up by the provider's existing abort path).
- Test variant: append data instead of truncate — same outcome.
- Test variant: rewrite in place same size, but mtime moves forward — same outcome.
- Test variant: file size and mtime stable for the whole upload — happy path passes.
- Sync resumes the file in the next pass since the file change was detected.

## Notes

- Out of scope: editor atomic-rename pattern (vim, jetbrains "atomic save" via `tmpfile + rename`). That's a separate watcher concern (UD-211). The mutation guard fires when the underlying inode has changed during upload — `Files.size(originalPath)` follows the path to the *new* inode and detects the size mismatch naturally.
- Memory cost: zero (just two stat calls per progress event).
- This is a UX safety net more than a correctness fix; the alternative ("upload corrupted, sync re-detects mismatch on next pass and re-uploads") works but is expensive in bytes and confusing if the user notices a "wrong" file in cloud storage temporarily.

## Cross-refs

- drive-desktop `src/infra/inxt-js/file-uploader/upload-file.ts` — direct shape source.
- Top 3 ideas to steal from drive-desktop research, item #3.
---
id: UD-307
title: Internxt: stream multipart parts via temp file (not in-memory ByteArray) for bounded heap
category: providers
priority: high
effort: M
status: open
opened: 2026-05-04
---
**Source:** swift-core research (agent `a876235`, 2026-05-04). swift-core holds each 50 MB encrypted chunk as a `Data` blob in memory until the S3 PUT succeeds. With `maxConcurrent = 6`, peak RSS is ~300 MB per active upload. **Acceptable on a macOS desktop, hostile on a Linux JVM with bounded heap** — a `-Xmx512m` daemon will OOM uploading a single large file.

Solution: stage each encrypted chunk to a temp file, upload via `RequestBody.create(file)`, delete on success. Peak heap usage drops to O(1).

## Code refs

- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtCrypto.kt` — encrypt path
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt` (upload orchestration)
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt` — multipart PUT

## Proposed shape

```kotlin
val tempDir = Files.createTempDirectory("unidrive-mp-")
try {
    val chunkFiles = (0 until partCount).map { i ->
        val chunk = tempDir.resolve("part-$i.enc")
        encryptChunkToFile(source, offset = i * chunkSize, len = chunkSize, out = chunk, cipher)
        chunk
    }
    coroutineScope {
        chunkFiles.mapIndexed { i, chunk ->
            async(uploadDispatcher) {
                val body = chunk.toRequestBody("application/octet-stream".toMediaType())
                val response = httpClient.newCall(...).execute()
                Part(partNumber = i + 1, etag = response.header("ETag")!!)
            }
        }.awaitAll()
    }
} finally {
    tempDir.toFile().deleteRecursively()
}
```

Temp dir under `${java.io.tmpdir}/unidrive-mp-<random>/` — co-located on the same filesystem as the daemon's cache when possible (avoid cross-fs `rename` on cleanup). Honor `XDG_CACHE_HOME` if set.

## Acceptance

- Under JVM `-Xmx512m`, uploading a 2 GB file with `maxParallelParts=6` succeeds without OOM.
- `lsof` (or equivalent) shows ≤6 open temp files at peak during a multipart upload.
- After successful upload: `find ${java.io.tmpdir} -name 'unidrive-mp-*'` returns nothing.
- After failed upload (e.g. mock returns 500 mid-multipart): same — temp dir is cleaned up by the `finally` block.
- After daemon crash mid-upload: a separate cleanup pass at daemon startup removes `unidrive-mp-*` directories older than 1 hour. (Lightweight, no state-file needed.)
- Integration test injects a 1 GB synthetic file; assertions: peak RSS reported by the test < 200 MB (well under the in-memory variant's ~300 MB), success.

## Notes

- **Land together with UD-304** (multipart constants). The two together make the upload path correct *and* memory-safe.
- Disk-pressure caveat: multi-GB uploads transiently use ~6 × 50 MB = 300 MB on disk. Document in `docs/providers/internxt.md`.
- Out of scope: streaming-encrypt-during-upload (encrypt and PUT byte-by-byte without ever materialising the chunk). Possible but more complex; not needed at MVP scale.
- Out of scope: tmpfs detection / preference. Use `${java.io.tmpdir}` as-is.

## Cross-refs

- swift-core `NetworkFacade.swift` and `UploadMultipart.swift` — the in-memory variant we're explicitly *not* copying.
- UD-304 (multipart constants) — sibling, must land together.
- Cross-repo synthesis: "Memory profile of upload parts: ... swift-core holds whole encrypted chunk in `Data`; ... the streaming model is preferable on a Linux JVM with bounded heap."
---
id: UD-212
title: HttpRetryBudget.decide(status, exception, retryAfter): make the canonical retry matrix actually enforceable
category: core
priority: medium
effort: L
status: open
opened: 2026-05-04
---
**Source:** UD-207 implementation report (agent `ab7efd3`, 2026-05-04). The canonical retry-policy matrix is now documented in `HttpRetryBudget.kt` KDoc and locked-where-possible by `HttpRetryBudgetMatrixTest`, but **the matrix is aspirational, not enforced** — per-provider `withRetry` / `authenticatedRequest` loops each carry their own status-code matrix, retry caps, and backoff scheme. Four of six matrix tests are `@Ignore`d with TODO references to this ticket because the budget has no surface to test against.

To make the matrix actually enforceable, refactor `HttpRetryBudget` from "coordination layer (token bucket + circuit breaker + IOException classifier)" into "coordination layer **plus** status-code policy engine."

## Code refs (today's reality, post-UD-207)

- `core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt` — KDoc has the matrix; runtime has no `decide(...)` entry point, no `maxRetries` field, no `maxRetryAfter` field, no backoff helper.
- `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt:162-228` — `maxAttempts: Int = 5`, `backoffMs: (attempt: Int) -> Long = { 1000L * (1L shl it) }` (no jitter), `isRetriableStatus(status) = status.value in setOf(408, 425, 429, 500, 502, 503, 504)`.
- `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt` — constants `MAX_THROTTLE_ATTEMPTS = 5`, `MAX_FLAKE_ATTEMPTS = 3`, `MAX_SINGLE_BACKOFF_MS = 300_000`. Inline `if (status == 429) … if (status in 400..499) throw … else fall-through to retry`.
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt` — `authenticatedGet` retries 500/503/EOF only; non-GET verbs are one-shot (UD-330 is the rollout ticket).
- `core/app/core/src/test/kotlin/org/krost/unidrive/http/HttpRetryBudgetMatrixTest.kt` — 4 cases skipped: `4xx (404) does not retry`, `408 retries`, `5xx (503) retries`, `unknown caps at 3`.

## Proposed shape

Five sub-tasks. Land in this order; each can be its own PR.

### Sub-task A — fields + decide() entry point
Add to `HttpRetryBudget`:
```kotlin
val maxRetries: Int = 5,                // default; provider can override
val maxRetryAfter: Duration = 5.minutes, // cap on Retry-After honoring

sealed class RetryDecision {
    data class Retry(val delay: Duration) : RetryDecision()
    object Fail : RetryDecision()
}

fun decide(
    attempt: Int,
    status: Int? = null,           // null when no HTTP response (network error)
    exception: Throwable? = null,  // null on plain HTTP non-2xx
    retryAfter: Duration? = null,  // from response header, if any
): RetryDecision
```

The `decide(...)` body implements the matrix verbatim:
- `attempt >= maxRetries` (or for Unknown: `>= min(3, maxRetries)`) → `Fail`.
- `status == 200..299` → caller shouldn't be calling decide; document as precondition violation.
- `status == 408 || 429 || in 500..599` → `Retry(backoff)`. For 429, `Retry(retryAfter.coerceAtMost(maxRetryAfter))` if `retryAfter != null`, else `Retry(backoff)`.
- `status in 400..499 && status !in setOf(408, 429)` → `Fail`.
- `exception != null && status == null` (network error: IOException, SocketTimeout) → `Retry(backoff)`.
- `exception` not classified above (Unknown class) → `Retry(backoff)` capped at `min(3, maxRetries)` attempts.

### Sub-task B — backoff helper with full jitter
Add to `HttpRetryBudget`:
```kotlin
fun nextBackoff(attempt: Int, base: Duration = 1.seconds): Duration {
    val expCap = base * (1L shl attempt.coerceAtMost(10))
    val capped = expCap.coerceAtMost(maxRetryAfter)
    val jitterMs = Random.nextLong(capped.inWholeMilliseconds + 1)
    return jitterMs.milliseconds
}
```
Full-jitter pattern (AWS Architecture Blog, 2015) — equally distributed across `[0, capped]`, smooths thundering-herd retry storms without sacrificing throughput. Existing per-provider exponential-without-jitter is the regression target.

### Sub-task C — `RetryClass` for caller-classified Unknown
Add an enum the caller can pass to `decide`:
```kotlin
enum class RetryClass { Http, Network, Unknown }
```
`Unknown` is for "exception didn't match any known retriable predicate but the caller wants to give it a few tries." Caps at `min(3, maxRetries)`.

### Sub-task D — wire each provider into `decide()`
- Internxt `InternxtApiService.authenticatedGet` and the rest of UD-330's rollout list.
- OneDrive `GraphApiService.authenticatedRequest` + chunk uploader.
- WebDAV `WebDavApiService.withRetry`.
- HiDrive (separate module if it lands).
- S3 `S3ApiService` (UD-330 scope).
- Document any **legitimate** divergence as a constructor-arg override (e.g. WebDAV's 425 Too Early — pass `additionalRetriableStatuses = setOf(425)`).

### Sub-task E — un-skip the matrix tests
Remove the four `@Ignore` annotations on `HttpRetryBudgetMatrixTest` once `decide(...)` is in place; rewrite each to call `decide` directly with a synthetic status/exception/retry-after. The test class becomes the canonical contract.

## Acceptance

- `HttpRetryBudget` exposes `decide(...)` with the matrix-prescribed semantics.
- `HttpRetryBudgetMatrixTest` runs 6/6 PASS, no skips.
- Each provider's `withRetry`/`authenticatedRequest` calls into `budget.decide(...)` rather than carrying its own matrix.
- Provider-specific divergences (WebDAV 425, S3 SlowDown, MinIO 507) appear as constructor overrides, not parallel matrices.
- Adding a new provider requires zero changes to `HttpRetryBudget` and zero new retry matrices.
- Documentation updated: lesson note moves from "aspirational matrix" to "enforced matrix"; KDoc gains a "How to use `decide`" example.

## Notes

- **Sub-tasks can be filed separately** if this turns out to be a multi-PR effort. The natural cut is A+B+C in one PR (engine), D+E in a second PR (rollout).
- Pairs with UD-330 (cross-provider retry rollout). Likely UD-330 should be re-scoped to "rollout via `budget.decide`" once this lands; otherwise UD-330 ships per-provider matrices that this ticket then has to undo.
- Out of scope: per-provider divergence beyond status-set additions. If a provider needs entirely different backoff semantics (e.g. honour `x-amz-retry-after-millis`), that's a follow-up.
- Out of scope: distributed retry budget (cross-process). In-process token bucket only.

## Cross-refs

- UD-207 — surfaced this finding; closed once-per-row tests with @Ignore TODOs.
- UD-228 — original HTTP-robustness audit that produced the matrix.
- UD-330 — cross-provider rollout, must coordinate.
- UD-232 — Retry-After / throttle handling, lives at the same surface.
- drive-desktop `client-wrapper.service.ts` — the matrix's source.
---
id: UD-701
title: Cloud provider speed ranking publication
category: tooling
priority: high
effort: XL
status: open
code_refs:
  - core/app/benchmark/src/main/kotlin/org/krost/unidrive/sync/BenchmarkRunner.kt
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/BenchmarkCommand.kt
  - docs/CLOUD_PROVIDERS_DATABASE.md
opened: 2026-05-13
---
Sign up for free tiers of viable cloud storage providers, run
standardized speed tests from Kubuntu (Germany ISP), rank by
upload/download throughput and latency, publish via static site
generator. EU-first ordering. Revenue model: affiliate signups.

Phase 1 (static benchmark page generator) shipped in unidrive-closed
commit 45b9407. This ticket tracks Phase 2: expand provider coverage,
periodic re-tests, dynamic site updates.

Reference: `docs/CLOUD_PROVIDERS_DATABASE.md` v1.0 (100+ providers).

Was `#63` in `unidrive-closed/docs/BACKLOG.md` before the 2026-05-13
dissolution.
---
id: UD-401
title: Enhanced provider table Phase 2 (remote API, dynamic grades)
category: cli
priority: medium
effort: M
status: open
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
opened: 2026-05-13
---
Remote-API-backed dynamic provider table in CLI output. Phase 1
(static table baked at compile time) already shipped.

Phase 2: read live grades from the benchmark site (UD-701) with a
sensible offline fallback to the baked-in table. The exact CLI
surface depends on whether the public repo still has a `provider list`
subcommand (it was removed in commit b07d864 on 2026-05-04) — if a
replacement listing surface is added, it would be the natural host
for the dynamic table; otherwise this work might expand the `benchmark`
subcommand itself.

Was `#107` in `unidrive-closed/docs/BACKLOG.md` before the 2026-05-13
dissolution.
---
id: UD-800
title: CloudForge E2E scenario incomplete (Playwright not wired)
category: tests
priority: low
effort: M
status: open
code_refs:
  - core/app/e2e-360/src/main/kotlin/org/krost/unidrive/e2e/Main360.kt:71
  - core/app/e2e-360/src/main/kotlin/org/krost/unidrive/e2e/scenarios/CloudForgeRunner.kt
opened: 2026-05-13
---
`CloudForgeCommand` in `Main360.kt:71` prints a TODO. Playwright
browser integration not wired. The `--headed` flag is accepted but
unused. Only `GroundTruthRunner` is fully implemented.

Acceptance: CloudForge (cloud→local) scenario callable from CLI;
Playwright wired for headed mode; one passing end-to-end test in
CI or marked `@Tag("manual")`.

Was `#85` in `unidrive-closed/docs/BACKLOG.md` before the 2026-05-13
dissolution.
---
id: UD-104
title: UD-104 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceSslTest.kt:6
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-104 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-104 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-104` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-104b`,
   `UD-104c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceSslTest.kt:6`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-106
title: UD-106 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/TokenBucket.kt:4
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/TokenBucketTest.kt:6
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-106 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-106 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-106` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-106b`,
   `UD-106c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/TokenBucket.kt:4`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-108
title: UD-108 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/ci/README.md:12
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-108 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-108 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-108` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-108b`,
   `UD-108c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/ci/README.md:12`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-109
title: UD-109 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/ci/README.md:13
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-109 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-109 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-109` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-109b`,
   `UD-109c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/ci/README.md:13`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-111
title: UD-111 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/audit/AuditLog.kt:21
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/LogCommand.kt:31
  - scripts/dev/file_lift_findings.py:78
  - scripts/dev/file_lift_findings.py:80
  - scripts/dev/file_lift_findings.py:104
  - scripts/dev/file_lift_findings.py:105
  - scripts/dev/file_lift_findings.py:112
  - scripts/dev/file_lift_findings.py:124
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-111 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-111 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-111` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-111b`,
   `UD-111c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/audit/AuditLog.kt:21`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-113
title: UD-113 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:52
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:959
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1001
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1038
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1106
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1164
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1219
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/audit/AuditLog.kt:15
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-113 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-113 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-113` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-113b`,
   `UD-113c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:52`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-216
title: UD-216 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/LogoutTool.kt:9
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/IdentityTool.kt:9
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt:13
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:53
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/ProfileTools.kt:17
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/AdminToolsTest.kt:13
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/ToolRegistryTest.kt:27
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/McpRoundtripIntegrationTest.kt:230
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-216 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-216 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-216` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-216b`,
   `UD-216c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/LogoutTool.kt:9`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-218
title: UD-218 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:359
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-218 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-218 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-218` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-218b`,
   `UD-218c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:359`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-219
title: UD-219 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:111
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-219 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-219 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-219` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-219b`,
   `UD-219c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:111`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-222
title: UD-222 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:81
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:322
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:575
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:498
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:613
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:860
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1300
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1447
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-222 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-222 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-222` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-222b`,
   `UD-222c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:81`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-223
title: UD-223 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DeltaFromLatestTest.kt:16
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:644
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:659
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:668
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:677
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:686
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:223
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:1015
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-223 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-223 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-223` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-223b`,
   `UD-223c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DeltaFromLatestTest.kt:16`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-224
title: UD-224 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/LsCommand.kt:12
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/LsCommandTest.kt:22
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/LsCommandTest.kt:108
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-224 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-224 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-224` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-224b`,
   `UD-224c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/LsCommand.kt:12`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-225
title: UD-225 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:173
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:212
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:219
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:362
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:378
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:382
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:383
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:392
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-225 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-225 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-225` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-225b`,
   `UD-225c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:173`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-226
title: UD-226 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DownloadContentTypeGuardTest.kt:22
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:21
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SweepCommand.kt:16
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SweepCommand.kt:29
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SweepCommandTest.kt:14
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-226 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-226 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-226` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-226b`,
   `UD-226c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DownloadContentTypeGuardTest.kt:22`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-227
title: UD-227 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceRetryTest.kt:20
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/ErrorBody.kt:11
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:35
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-227 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-227 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-227` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-227b`,
   `UD-227c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceRetryTest.kt:20`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-228
title: UD-228 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/dev/pre-commit/scope-check.sh:5
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-228 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-228 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-228` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-228b`,
   `UD-228c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/dev/pre-commit/scope-check.sh:5`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-231
title: UD-231 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DownloadContentTypeGuardTest.kt:18
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DownloadContentTypeGuardTest.kt:105
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:9
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:24
  - scripts/dev/file_lift_findings.py:193
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-231 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-231 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-231` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-231b`,
   `UD-231c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DownloadContentTypeGuardTest.kt:18`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-232
title: UD-232 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceRetryTest.kt:20
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/LiveGraphIntegrationTest.kt:75
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:356
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:10
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:19
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:116
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:138
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:155
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-232 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-232 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-232` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-232b`,
   `UD-232c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceRetryTest.kt:20`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-234
title: UD-234 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:7
  - core/app/mcp/src/main/resources/logback.xml:6
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/ErrorBody.kt:8
  - core/app/cli/src/main/resources/logback.xml:7
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-234 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-234 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-234` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-234b`,
   `UD-234c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:7`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-235
title: UD-235 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:167
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:675
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:688
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:776
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ConfigMissingDiagnosticTest.kt:9
  - scripts/dev/pre-commit/scope-check.sh:126
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-235 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-235 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-235` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-235b`,
   `UD-235c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:167`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-237
title: UD-237 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:130
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:640
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:657
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ProfileTypeResolveTest.kt:10
  - scripts/dev/pre-commit/scope-check.sh:126
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-237 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-237 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-237` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-237b`,
   `UD-237c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:130`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-238
title: UD-238 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/TrashCommand.kt:47
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/VersionsCommand.kt:51
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusAudit.kt:41
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:299
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:300
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StatusAuditTest.kt:10
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt:52
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-238 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-238 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-238` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-238b`,
   `UD-238c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/TrashCommand.kt:47`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-241
title: UD-241 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/HelpAndConfigErrorTest.kt:13
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/HelpAndConfigErrorTest.kt:23
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-241 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-241 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-241` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-241b`,
   `UD-241c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/HelpAndConfigErrorTest.kt:13`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-242
title: UD-242 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:108
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:159
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:632
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/HelpAndConfigErrorTest.kt:16
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/HelpAndConfigErrorTest.kt:102
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ConfigMissingDiagnosticTest.kt:135
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ConfigMissingDiagnosticTest.kt:140
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ConfigMissingDiagnosticTest.kt:144
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-242 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-242 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-242` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-242b`,
   `UD-242c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:108`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-243
title: UD-243 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ShareCommand.kt:29
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:65
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:119
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ShareCommandTest.kt:136
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ShareCommandTest.kt:154
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ShareCommandTest.kt:160
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ShareCommandTest.kt:169
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ShareCommandTest.kt:175
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-243 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-243 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-243` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-243b`,
   `UD-243c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ShareCommand.kt:29`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-244
title: UD-244 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SweepCommandTest.kt:176
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-244 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-244 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-244` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-244b`,
   `UD-244c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SweepCommandTest.kt:176`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-245
title: UD-245 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/NumericFlagValidationTest.kt:11
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-245 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-245 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-245` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-245b`,
   `UD-245c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/NumericFlagValidationTest.kt:11`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-248
title: UD-248 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:441
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1533
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncReason.kt:30
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncRetryNonFatalTest.kt:13
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncRetryNonFatalTest.kt:86
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncExceptionStormTest.kt:19
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncExceptionStormTest.kt:26
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncExceptionStormTest.kt:31
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-248 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-248 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-248` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-248b`,
   `UD-248c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:441`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-249
title: UD-249 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncExceptionStormTest.kt:19
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-249 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-249 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-249` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-249b`,
   `UD-249c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncExceptionStormTest.kt:19`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-251
title: UD-251 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:164
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:631
  - core/app/cli/src/main/resources/logback.xml:27
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-251 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-251 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-251` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-251b`,
   `UD-251c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:164`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-252
title: UD-252 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:22
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:151
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:456
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt:769
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt:772
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt:778
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt:779
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt:798
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-252 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-252 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-252` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-252b`,
   `UD-252c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:22`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-253
title: UD-253 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:409
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:424
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:521
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:537
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:567
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:581
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1457
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineWarnContextTest.kt:18
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-253 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-253 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-253` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-253b`,
   `UD-253c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:409`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-255
title: UD-255 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:68
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/RequestIdPlugin.kt:12
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/RequestIdPluginTest.kt:19
  - core/app/core/build.gradle.kts:13
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-255 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-255 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-255` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-255b`,
   `UD-255c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:68`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-257
title: UD-257 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/WatchEventsTool.kt:28
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/DaemonStatusAgreementTest.kt:28
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/DaemonStatusAgreementTest.kt:140
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/DaemonStatusAgreementTest.kt:152
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-257 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-257 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-257` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-257b`,
   `UD-257c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/WatchEventsTool.kt:28`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-258
title: UD-258 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/mcp/build.gradle.kts:17
  - core/app/mcp/build.gradle.kts:184
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/GlyphRenderer.kt:6
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/GlyphRenderer.kt:9
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/GlyphRendererTest.kt:12
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/GlyphRendererTest.kt:42
  - core/app/cli/build.gradle.kts:16
  - scripts/dev/unidrive-jfr.sh:133
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-258 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-258 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-258` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-258b`,
   `UD-258c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/mcp/build.gradle.kts:17`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-259
title: UD-259 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/GlyphRenderer.kt:6
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/GlyphRenderer.kt:15
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/GlyphRenderer.kt:226
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/GlyphRendererTest.kt:12
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/GlyphRendererTest.kt:19
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/GlyphRendererTest.kt:158
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/GlyphRendererTest.kt:243
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-259 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-259 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-259` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-259b`,
   `UD-259c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/GlyphRenderer.kt:6`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-260
title: UD-260 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:256
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:294
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:340
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-260 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-260 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-260` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-260b`,
   `UD-260c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:256`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-261
title: UD-261 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:320
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:353
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:405
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-261 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-261 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-261` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-261b`,
   `UD-261c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:320`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-263
title: UD-263 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProviderFactory.kt:32
  - core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProviderFactory.kt:36
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ProviderFactory.kt:40
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProviderFactory.kt:42
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt:37
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:82
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:403
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:349
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-263 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-263 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-263` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-263b`,
   `UD-263c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProviderFactory.kt:32`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-269
title: UD-269 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:90
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:213
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:410
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:448
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:511
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:514
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:536
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:552
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-269 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-269 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-269` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-269b`,
   `UD-269c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:90`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-270
title: UD-270 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/build.gradle.kts:212
  - core/app/cli/build.gradle.kts:221
  - core/app/cli/build.gradle.kts:257
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-270 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-270 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-270` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-270b`,
   `UD-270c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/build.gradle.kts:212`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-271
title: UD-271 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:67
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/RelocateCommandTest.kt:89
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/RelocateCommandTest.kt:98
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/RelocateCommandTest.kt:105
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-271 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-271 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-271` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-271b`,
   `UD-271c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:67`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-272
title: UD-272 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt:26
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt:56
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt:90
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt:108
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt:39
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt:42
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt:50
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ProcessLockTest.kt:59
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-272 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-272 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-272` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-272b`,
   `UD-272c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProcessLock.kt:26`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-273
title: UD-273 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:355
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-273 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-273 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-273` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-273b`,
   `UD-273c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:355`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-274
title: UD-274 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:649
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:659
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-274 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-274 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-274` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-274b`,
   `UD-274c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:649`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-276
title: UD-276 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavFolderDetectionTest.kt:166
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavFolderDetectionTest.kt:169
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-276 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-276 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-276` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-276b`,
   `UD-276c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavFolderDetectionTest.kt:166`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-277
title: UD-277 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt:63
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/UploadTimeoutPolicy.kt:4
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/UploadTimeoutPolicy.kt:25
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/UploadTimeoutPolicy.kt:54
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/UploadTimeoutPolicy.kt:66
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/UploadTimeoutPolicyTest.kt:8
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/UploadTimeoutPolicyTest.kt:16
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/UploadTimeoutPolicyTest.kt:20
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-277 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-277 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-277` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-277b`,
   `UD-277c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt:63`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-278
title: UD-278 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/HttpRetryBudgetTest.kt:282
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/HttpRetryBudgetTest.kt:291
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/HttpRetryBudgetTest.kt:300
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/HttpRetryBudgetTest.kt:316
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/HttpRetryBudgetTest.kt:330
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:77
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:193
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:199
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-278 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-278 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-278` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-278b`,
   `UD-278c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/HttpRetryBudgetTest.kt:282`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-279
title: UD-279 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:51
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:186
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:198
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-279 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-279 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-279` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-279b`,
   `UD-279c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:51`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-282
title: UD-282 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:21
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:51
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:133
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:429
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigStrictTest.kt:15
  - scripts/dev/log-watch.sh:45
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-282 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-282 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-282` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-282b`,
   `UD-282c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:21`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-283
title: UD-283 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/JsonRpc.kt:39
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/ProfileArgValidator.kt:7
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/McpServer.kt:87
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/ProfileArgValidatorTest.kt:13
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-283 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-283 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-283` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-283b`,
   `UD-283c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/JsonRpc.kt:39`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-284
title: UD-284 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:692
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:707
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:797
  - core/app/sync/build.gradle.kts:22
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:69
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:102
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/RelocateCommandTest.kt:176
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-284 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-284 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-284` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-284b`,
   `UD-284c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:692`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-285
title: UD-285 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt:66
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceRetryTest.kt:17
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/UploadTimeoutPolicy.kt:66
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/UploadTimeoutPolicyTest.kt:51
  - scripts/dev/file_lift_findings.py:723
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-285 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-285 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-285` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-285b`,
   `UD-285c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt:66`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-286
title: UD-286 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavProviderTest.kt:181
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:235
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:239
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:309
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:328
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:395
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:406
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:436
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-286 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-286 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-286` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-286b`,
   `UD-286c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavProviderTest.kt:181`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-287
title: UD-287 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt:126
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceRetryTest.kt:18
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/StreamingUpload.kt:13
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/StreamingUpload.kt:19
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/StreamingUpload.kt:27
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/StreamingUpload.kt:75
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/StreamingUploadTest.kt:20
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/StreamingUploadTest.kt:75
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-287 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-287 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-287` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-287b`,
   `UD-287c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt:126`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-288
title: UD-288 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt:146
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavApiServiceRetryTest.kt:16
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/HttpRetryBudgetTest.kt:284
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:80
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt:246
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-288 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-288 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-288` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-288b`,
   `UD-288c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavApiService.kt:146`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-289
title: UD-289 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:77
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:183
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:199
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:252
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:305
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:328
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:397
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:121
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-289 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-289 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-289` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-289b`,
   `UD-289c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:77`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-291
title: UD-291 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt:27
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt:183
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt:188
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavProviderTest.kt:97
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavProviderTest.kt:140
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavProviderTest.kt:189
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavProviderTest.kt:206
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-291 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-291 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-291` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-291b`,
   `UD-291c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt:27`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-292
title: UD-292 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/mcp/build.gradle.kts:118
  - core/app/cli/build.gradle.kts:123
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-292 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-292 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-292` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-292b`,
   `UD-292c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/mcp/build.gradle.kts:118`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-293
title: UD-293 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/ErrorBody.kt:36
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:9
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:24
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:43
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/HtmlBodySniffGuardTest.kt:114
  - scripts/dev/file_lift_findings.py:193
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-293 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-293 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-293` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-293b`,
   `UD-293c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/http/ErrorBody.kt:36`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-294
title: UD-294 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/RelocateTool.kt:57
  - core/app/mcp/build.gradle.kts:238
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:403
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/RelocateMdc.kt:7
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:64
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/RelocateCommandTest.kt:171
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/RelocateCommandTest.kt:192
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/RelocateCommandTest.kt:201
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-294 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-294 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-294` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-294b`,
   `UD-294c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/RelocateTool.kt:57`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-296
title: UD-296 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:272
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-296 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-296 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-296` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-296b`,
   `UD-296c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:272`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-297
title: UD-297 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:231
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1477
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:624
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:716
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:729
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:742
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:755
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:773
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-297 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-297 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-297` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-297b`,
   `UD-297c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:231`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-298
title: UD-298 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:302
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1479
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:764
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:785
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:788
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:807
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:822
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-298 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-298 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-298` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-298b`,
   `UD-298c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:302`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-299
title: UD-299 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:139
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1486
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:837
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:849
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:858
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:867
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:884
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:151
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-299 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-299 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-299` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-299b`,
   `UD-299c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:139`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-309
title: UD-309 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DownloadContentTypeGuardTest.kt:25
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-309 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-309 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-309` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-309b`,
   `UD-309c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DownloadContentTypeGuardTest.kt:25`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-310
title: UD-310 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:289
  - scripts/dev/file_lift_findings.py:78
  - scripts/dev/file_lift_findings.py:80
  - scripts/dev/file_lift_findings.py:83
  - scripts/dev/file_lift_findings.py:88
  - scripts/dev/file_lift_findings.py:105
  - scripts/dev/file_lift_findings.py:123
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-310 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-310 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-310` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-310b`,
   `UD-310c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:289`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-311
title: UD-311 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt:75
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-311 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-311 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-311` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-311b`,
   `UD-311c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt:75`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-312
title: UD-312 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:312
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt:99
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt:227
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt:265
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/OAuthServiceTokenShapeTest.kt:17
  - core/app/core/src/main/kotlin/org/krost/unidrive/auth/CredentialStore.kt:13
  - core/app/core/src/main/kotlin/org/krost/unidrive/auth/CredentialStore.kt:17
  - core/app/core/src/main/kotlin/org/krost/unidrive/auth/CredentialStore.kt:19
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-312 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-312 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-312` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-312b`,
   `UD-312c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:312`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-313
title: UD-313 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/dev/file_lift_findings.py:862
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-313 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-313 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-313` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-313b`,
   `UD-313c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/dev/file_lift_findings.py:862`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-314
title: UD-314 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/ListChildrenPaginationTest.kt:15
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/ListChildrenPaginationTest.kt:105
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-314 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-314 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-314` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-314b`,
   `UD-314c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/ListChildrenPaginationTest.kt:15`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-315
title: UD-315 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DriveItemVaultTest.kt:11
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/OneDriveProviderVaultFilterTest.kt:16
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-315 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-315 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-315` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-315b`,
   `UD-315c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/DriveItemVaultTest.kt:11`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-316
title: UD-316 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:88
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusAudit.kt:7
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusAudit.kt:72
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StatusAuditTest.kt:10
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-316 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-316 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-316` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-316b`,
   `UD-316c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:88`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-317
title: UD-317 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:85
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:302
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:319
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:366
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:531
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:581
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:592
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:678
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-317 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-317 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-317` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-317b`,
   `UD-317c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:85`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-327
title: UD-327 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt:59
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt:74
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/CloudRelocator.kt:127
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:580
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:583
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:595
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:614
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:634
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-327 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-327 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-327` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-327b`,
   `UD-327c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt:59`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-329
title: UD-329 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/dev/unidrive-jfr.ps1:116
  - scripts/dev/log-watch.sh:149
  - scripts/dev/file_lift_findings.py:294
  - scripts/dev/file_lift_findings.py:693
  - scripts/dev/file_lift_findings.py:706
  - scripts/dev/file_lift_findings.py:732
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-329 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-329 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-329` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-329b`,
   `UD-329c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/dev/unidrive-jfr.ps1:116`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-331
title: UD-331 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:289
  - scripts/dev/file_lift_findings.py:79
  - scripts/dev/file_lift_findings.py:82
  - scripts/dev/file_lift_findings.py:88
  - scripts/dev/file_lift_findings.py:125
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-331 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-331 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-331` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-331b`,
   `UD-331c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:289`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-332
title: UD-332 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/dev/file_lift_findings.py:295
  - scripts/dev/file_lift_findings.py:693
  - scripts/dev/file_lift_findings.py:706
  - scripts/dev/file_lift_findings.py:733
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-332 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-332 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-332` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-332b`,
   `UD-332c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/dev/file_lift_findings.py:295`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-333
title: UD-333 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:9
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:25
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/HtmlBodySniffGuardTest.kt:16
  - scripts/dev/file_lift_findings.py:180
  - scripts/dev/file_lift_findings.py:186
  - scripts/dev/file_lift_findings.py:190
  - scripts/dev/file_lift_findings.py:195
  - scripts/dev/file_lift_findings.py:197
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-333 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-333 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-333` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-333b`,
   `UD-333c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:9`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-335
title: UD-335 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:37
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:108
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:119
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:127
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:134
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:149
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:265
  - scripts/dev/file_lift_findings.py:174
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-335 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-335 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-335` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-335b`,
   `UD-335c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:37`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-336
title: UD-336 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/ErrorBody.kt:8
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/ErrorBody.kt:18
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/ErrorBody.kt:36
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/ErrorBody.kt:53
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/ErrorBodyTest.kt:16
  - scripts/dev/file_lift_findings.py:126
  - scripts/dev/file_lift_findings.py:232
  - scripts/dev/file_lift_findings.py:406
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-336 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-336 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-336` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-336b`,
   `UD-336c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/http/ErrorBody.kt:8`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-337
title: UD-337 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:583
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/UploadTimeoutPolicy.kt:4
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/UploadTimeoutPolicy.kt:14
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/UploadTimeoutPolicy.kt:25
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/UploadTimeoutPolicyTest.kt:8
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/UploadTimeoutPolicyTest.kt:100
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/UploadTimeoutPolicyTest.kt:103
  - scripts/dev/file_lift_findings.py:353
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-337 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-337 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-337` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-337b`,
   `UD-337c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:583`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-340
title: UD-340 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:9
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:26
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/HtmlBodySniffGuardTest.kt:16
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-340 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-340 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-340` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-340b`,
   `UD-340c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/http/HtmlBodySniffGuard.kt:9`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-342
title: UD-342 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/StreamingUpload.kt:13
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/StreamingUpload.kt:15
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/StreamingUploadTest.kt:19
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/StreamingUploadTest.kt:93
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-342 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-342 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-342` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-342b`,
   `UD-342c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/http/StreamingUpload.kt:13`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-343
title: UD-343 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/SharedJson.kt:6
  - core/app/core/src/main/kotlin/org/krost/unidrive/SharedJson.kt:8
  - core/app/core/build.gradle.kts:19
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-343 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-343 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-343` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-343b`,
   `UD-343c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/SharedJson.kt:6`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-344
title: UD-344 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:311
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:312
  - core/app/core/src/main/kotlin/org/krost/unidrive/auth/CredentialStore.kt:12
  - core/app/core/src/main/kotlin/org/krost/unidrive/auth/CredentialStore.kt:15
  - core/app/core/src/main/kotlin/org/krost/unidrive/io/PosixPermissions.kt:9
  - core/app/core/src/test/kotlin/org/krost/unidrive/auth/CredentialStoreTest.kt:17
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-344 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-344 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-344` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-344b`,
   `UD-344c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:311`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-345
title: UD-345 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneSnapshot.kt:9
  - core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsSnapshot.kt:10
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Snapshot.kt:10
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpSnapshot.kt:10
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavSnapshot.kt:10
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Snapshot.kt:19
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/DeltaSnapshotTest.kt:15
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/DeltaSnapshotTest.kt:56
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-345 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-345 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-345` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-345b`,
   `UD-345c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneSnapshot.kt:9`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-346
title: UD-346 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SnapshotDeltaEngine.kt:11
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SnapshotDeltaEngineTest.kt:11
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-346 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-346 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-346` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-346b`,
   `UD-346c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SnapshotDeltaEngine.kt:11`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-347
title: UD-347 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/io/PosixPermissions.kt:9
  - core/app/core/src/main/kotlin/org/krost/unidrive/io/PosixPermissions.kt:24
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-347 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-347 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-347` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-347b`,
   `UD-347c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/io/PosixPermissions.kt:9`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-348
title: UD-348 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/auth/OAuthCallbackServer.kt:13
  - core/app/core/src/main/kotlin/org/krost/unidrive/auth/OAuthCallbackServer.kt:16
  - core/app/core/src/main/kotlin/org/krost/unidrive/io/OpenBrowser.kt:4
  - core/app/core/src/main/kotlin/org/krost/unidrive/io/OpenBrowser.kt:7
  - core/app/core/src/test/kotlin/org/krost/unidrive/auth/OAuthCallbackServerTest.kt:9
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-348 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-348 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-348` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-348b`,
   `UD-348c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/auth/OAuthCallbackServer.kt:13`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-350
title: UD-350 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtHeaders.kt:9
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-350 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-350 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-350` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-350b`,
   `UD-350c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtHeaders.kt:9`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-351
title: UD-351 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/auth/Pkce.kt:8
  - core/app/core/src/main/kotlin/org/krost/unidrive/auth/Pkce.kt:11
  - core/app/core/src/test/kotlin/org/krost/unidrive/auth/PkceTest.kt:9
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-351 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-351 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-351` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-351b`,
   `UD-351c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/auth/Pkce.kt:8`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-353
title: UD-353 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:583
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:281
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:287
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:302
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:414
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-353 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-353 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-353` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-353b`,
   `UD-353c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:583`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-357
title: UD-357 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/FolderUuidCache.kt:6
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:26
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:307
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:319
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:359
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:570
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:584
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/FolderUuidCacheTest.kt:8
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-357 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-357 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-357` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-357b`,
   `UD-357c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/FolderUuidCache.kt:6`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-358
title: UD-358 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:321
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:335
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-358 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-358 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-358` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-358b`,
   `UD-358c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:321`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-359
title: UD-359 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:723
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:784
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtFileDeletionFlagTest.kt:11
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtFileDeletionFlagTest.kt:85
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-359 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-359 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-359` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-359b`,
   `UD-359c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:723`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-360
title: UD-360 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:500
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:714
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:780
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:267
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:278
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:280
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:290
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:297
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-360 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-360 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-360` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-360b`,
   `UD-360c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:500`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-361
title: UD-361 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:501
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:542
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:617
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtFolderRecursionTest.kt:15
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-361 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-361 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-361` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-361b`,
   `UD-361c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:501`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-366
title: UD-366 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:145
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:226
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:253
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:258
  - core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt:98
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt:81
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProvider.kt:61
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt:79
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-366 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-366 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-366` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-366b`,
   `UD-366c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:145`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-367
title: UD-367 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:385
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:275
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:228
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:240
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:253
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-367 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-367 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-367` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-367b`,
   `UD-367c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:385`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-368
title: UD-368 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:180
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:316
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:365
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:368
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:203
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:215
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt:68
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-368 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-368 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-368` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-368b`,
   `UD-368c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:180`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-369
title: UD-369 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:277
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:310
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:404
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:670
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:68
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:76
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-369 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-369 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-369` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-369b`,
   `UD-369c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt:277`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-372
title: UD-372 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/model/InternxtFile.kt:28
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/model/InternxtFolder.kt:27
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/model/InternxtTimestamps.kt:6
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:166
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:178
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:186
  - core/providers/internxt/src/test/kotlin/org/krost/unidrive/internxt/InternxtApiServiceTest.kt:194
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-372 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-372 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-372` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-372b`,
   `UD-372c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/model/InternxtFile.kt:28`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-373
title: UD-373 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:714
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:720
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ReconcilerTest.kt:131
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ReconcilerTest.kt:149
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-373 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-373 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-373` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-373b`,
   `UD-373c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:714`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-405
title: UD-405 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:146
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:704
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SyncCommandTest.kt:48
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SyncCommandTest.kt:51
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SyncCommandTest.kt:55
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SyncCommandTest.kt:63
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SyncCommandTest.kt:71
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SyncCommandTest.kt:80
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-405 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-405 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-405` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-405b`,
   `UD-405c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:146`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-406
title: UD-406 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt:308
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt:322
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt:330
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:506
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-406 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-406 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-406` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-406b`,
   `UD-406c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt:308`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-407
title: UD-407 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:292
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:300
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:325
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt:913
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt:923
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt:937
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt:950
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt:963
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-407 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-407 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-407` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-407b`,
   `UD-407c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:292`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-408
title: UD-408 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:142
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:20
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:133
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:147
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:200
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:206
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:289
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:310
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-408 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-408 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-408` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-408b`,
   `UD-408c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:142`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-704
title: UD-704 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/McpRoundtripIntegrationTest.kt:25
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/ToolHandlerTest.kt:395
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/ToolHandlerTest.kt:409
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/ToolHandlerTest.kt:430
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/ToolHandlerTest.kt:447
  - core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/ToolHandlerTest.kt:863
  - core/app/core/src/test/kotlin/org/krost/unidrive/CloudProviderDefaultsTest.kt:23
  - core/app/core/src/test/kotlin/org/krost/unidrive/CloudProviderDefaultsTest.kt:99
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-704 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-704 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-704` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-704b`,
   `UD-704c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/McpRoundtripIntegrationTest.kt:25`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-706
title: UD-706 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/build.gradle.kts:4
  - core/build.gradle.kts:28
  - core/build.gradle.kts:35
  - core/build.gradle.kts:41
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-706 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-706 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-706` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-706b`,
   `UD-706c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/build.gradle.kts:4`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-709
title: UD-709 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/build.gradle.kts:242
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-709 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-709 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-709` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-709b`,
   `UD-709c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/build.gradle.kts:242`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-711
title: UD-711 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/docker/test-providers.sh:4
  - core/docker/Dockerfile.test:34
  - core/docker/Dockerfile.test:63
  - core/docker/docker-compose.providers.yml:1
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-711 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-711 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-711` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-711b`,
   `UD-711c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/docker/test-providers.sh:4`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-712
title: UD-712 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/rclone/src/test/kotlin/org/krost/unidrive/rclone/RcloneProviderFactoryTest.kt:15
  - core/docker/test-providers.sh:4
  - core/docker/Dockerfile.test:34
  - core/docker/Dockerfile.test:38
  - core/docker/Dockerfile.test:39
  - core/docker/docker-compose.providers.yml:98
  - core/app/mcp/build.gradle.kts:157
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:325
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-712 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-712 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-712` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-712b`,
   `UD-712c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/rclone/src/test/kotlin/org/krost/unidrive/rclone/RcloneProviderFactoryTest.kt:15`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-714
title: UD-714 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/dev/pre-commit/scope-check.sh:42
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-714 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-714 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-714` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-714b`,
   `UD-714c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/dev/pre-commit/scope-check.sh:42`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-723
title: UD-723 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/dev/install-mcps.sh:15
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-723 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-723 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-723` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-723b`,
   `UD-723c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/dev/install-mcps.sh:15`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-728
title: UD-728 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/dev/ktlint-sync.sh:93
  - scripts/dev/backlog.py:172
  - scripts/dev/backlog.py:337
  - scripts/dev/pre-commit/scope-check.sh:13
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-728 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-728 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-728` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-728b`,
   `UD-728c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/dev/ktlint-sync.sh:93`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-730
title: UD-730 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/dev/install-mcps.sh:17
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-730 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-730 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-730` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-730b`,
   `UD-730c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/dev/install-mcps.sh:17`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-733
title: UD-733 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:10
  - core/app/mcp/build.gradle.kts:42
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:797
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/BuildInfoTest.kt:12
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/BuildInfoTest.kt:19
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/BuildInfoTest.kt:25
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/BuildInfoTest.kt:40
  - core/app/cli/build.gradle.kts:46
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-733 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-733 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-733` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-733b`,
   `UD-733c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:10`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-735
title: UD-735 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:143
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:290
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:310
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:313
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt:75
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-735 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-735 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-735` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-735b`,
   `UD-735c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:143`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-736
title: UD-736 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:21
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:127
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/LocalScannerTest.kt:197
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/LocalScannerTest.kt:200
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/LocalScannerTest.kt:298
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-736 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-736 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-736` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-736b`,
   `UD-736c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:21`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-737
title: UD-737 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:34
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:266
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:899
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:913
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:940
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:961
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:94
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:137
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-737 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-737 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-737` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-737b`,
   `UD-737c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:34`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-738
title: UD-738 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:13
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/StateDatabaseTest.kt:299
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/StateDatabaseTest.kt:302
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/StateDatabaseTest.kt:322
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:131
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:215
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SyncCommandTest.kt:196
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SyncCommandTest.kt:209
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-738 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-738 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-738` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-738b`,
   `UD-738c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:13`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-740
title: UD-740 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1427
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1505
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:629
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:979
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:982
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:1006
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-740 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-740 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-740` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-740b`,
   `UD-740c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1427`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-741
title: UD-741 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:285
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-741 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-741 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-741` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-741b`,
   `UD-741c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:285`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-742
title: UD-742 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ScanHeartbeat.kt:15
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:37
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:216
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:760
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/LocalScannerTest.kt:207
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/LocalScannerTest.kt:210
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/LocalScannerTest.kt:218
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:25
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-742 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-742 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-742` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-742b`,
   `UD-742c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ScanHeartbeat.kt:15`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-745
title: UD-745 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:371
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProgressReporter.kt:82
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt:660
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:185
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt:163
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt:166
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt:174
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt:182
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-745 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-745 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-745` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-745b`,
   `UD-745c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:371`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-746
title: UD-746 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:259
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-746 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-746 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-746` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-746b`,
   `UD-746c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:259`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-747
title: UD-747 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:167
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:200
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProgressReporter.kt:10
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProgressReporter.kt:31
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:29
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:39
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:59
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:230
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-747 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-747 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-747` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-747b`,
   `UD-747c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:167`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-748
title: UD-748 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:171
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:200
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ProgressReporter.kt:26
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:35
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:59
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:230
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:239
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt:192
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-748 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-748 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-748` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-748b`,
   `UD-748c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:171`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-749
title: UD-749 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SyncCommandTest.kt:201
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-749 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-749 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-749` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-749b`,
   `UD-749c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SyncCommandTest.kt:201`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-751
title: UD-751 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:731
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-751 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-751 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-751` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-751b`,
   `UD-751c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:731`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-752
title: UD-752 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/AuthenticateAndLog.kt:6
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-752 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-752 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-752` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-752b`,
   `UD-752c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/AuthenticateAndLog.kt:6`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-753
title: UD-753 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt:90
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt:123
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt:151
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt:389
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:999
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1050
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:1162
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-753 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-753 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-753` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-753b`,
   `UD-753c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt:90`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-756
title: UD-756 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:24
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:50
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:409
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:463
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StatusCommandTest.kt:34
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StatusCommandTest.kt:57
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StatusCommandTest.kt:80
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StatusCommandTest.kt:92
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-756 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-756 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-756` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-756b`,
   `UD-756c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt:24`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-757
title: UD-757 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:53
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:62
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:209
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:224
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:230
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt:80
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt:82
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/CliProgressReporterTest.kt:85
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-757 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-757 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-757` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-757b`,
   `UD-757c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:53`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-764
title: UD-764 (auto-filed orphan anchor — see body)
category: cli
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/LogCommand.kt:36
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-764 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-764 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-764` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-764b`,
   `UD-764c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/LogCommand.kt:36`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-772
title: UD-772 (auto-filed orphan anchor — see body)
category: tooling
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - scripts/dev/log-stats.py:76
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-772 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-772 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-772` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-772b`,
   `UD-772c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `scripts/dev/log-stats.py:76`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-773
title: UD-773 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/RequestIdPlugin.kt:64
  - core/app/core/src/main/kotlin/org/krost/unidrive/http/RequestIdPlugin.kt:96
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/RequestIdPluginTest.kt:88
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/RequestIdPluginTest.kt:94
  - core/app/core/src/test/kotlin/org/krost/unidrive/http/RequestIdPluginTest.kt:101
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-773 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-773 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-773` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-773b`,
   `UD-773c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/core/src/main/kotlin/org/krost/unidrive/http/RequestIdPlugin.kt:64`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-812
title: UD-812 (auto-filed orphan anchor — see body)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/HttpRetryBudgetTest.kt:35
  - core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/HttpRetryBudgetTest.kt:74
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ReconcilerTest.kt:330
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SweepCommandTest.kt:133
  - core/app/cli/src/test/kotlin/org/krost/unidrive/cli/SweepCommandTest.kt:144
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-812 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-812 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-812` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-812b`,
   `UD-812c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/HttpRetryBudgetTest.kt:35`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-815
title: UD-815 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/docker/Dockerfile.test:42
  - core/docker/Dockerfile.test:58
  - core/docker/Dockerfile.test:66
  - core/docker/README.md:9
  - core/docker/test-mcp.sh:4
  - core/docker/docker-compose.mcp.yml:1
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-815 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-815 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-815` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-815b`,
   `UD-815c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/docker/Dockerfile.test:42`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-816
title: UD-816 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt:70
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt:144
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt:177
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt:225
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-816 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-816 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-816` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-816b`,
   `UD-816c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt:70`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-817
title: UD-817 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:416
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-817 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-817 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-817` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-817b`,
   `UD-817c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/CloudRelocatorTest.kt:416`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-901
title: UD-901 (auto-filed orphan anchor — see body)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:238
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:239
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:45
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:50
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:72
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:82
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/LocalScanner.kt:168
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:46
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-901 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-901 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-901` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.
3. **Sub-letter variant of an already-closed parent** (e.g. `UD-901b`,
   `UD-901c` exist but the parent does not) → close this as
   `wontfix-historical, parent of closed sub-tickets`.

First source ref: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:238`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 to clear the inventory blocking PR #17's UD-766
CI gate. The clearing script lived briefly at `/tmp/file-orphans.py`;
its design notes are in the PR #17 comment thread.

---
id: UD-707
title: UD-707 (auto-filed orphan anchor — see body)
category: e2e
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/docker/Dockerfile.test:1
  - .github/workflows/build.yml:101
opened: 2026-05-15
---

**AUTO-FILED ORPHAN ANCHOR.** UD-707 is referenced in source code but had
no entry in `BACKLOG.md` / `CLOSED.md`, blocking the
`scripts/backlog-sync.kts` CI gate landed under UD-766. This stub
exists to unblock the gate.

## Next steps for a reviewer

When you touch the referenced code or want to drive UD-707 to completion:

1. **Real concern** → replace this body with a real title + scope + priority,
   remove the `auto_filed: true` flag, set `effort`. The ticket is yours.
2. **Stale comment** → strip the `UD-707` from the source ref(s) below
   and close this ticket as `wontfix-historical` in CLOSED.md.

First source ref: `core/docker/Dockerfile.test:1`. See `code_refs` for the full list.

## Provenance

Bulk-filed 2026-05-15 follow-up to PR #17's UD-766 CI gate — slipped past
the initial scan because the script's `scanRoots` doesn't include
`.github/`, but the `core/docker/Dockerfile.test` ref still surfaces it.

---
id: UD-254
title: UD-254 (parent anchor — see sub-letter variants)
category: core
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt:376
opened: 2026-05-15
---

**AUTO-FILED PARENT ANCHOR.** Source code cites `UD-254` (bare, no
sub-letter) but only sub-letter variants (`UD-254a`) exist as real
entries. This stub exists so the `scripts/backlog-sync.kts` CI gate
under UD-766 doesn't see bare `UD-254` cites as orphans.

## Sub-letter variants

- [UD-254a](#ud-254a) — MDC clone storm under MDCContext.

When the bare `UD-254` cites are touched, prefer pointing them at the
specific sub-letter variant they relate to and close this anchor as
`wontfix-historical, parent of sub-tickets`.

---
id: UD-352
title: UD-352 (parent anchor — see sub-letter variants)
category: providers
priority: low
effort: ?
status: open
auto_filed: true
code_refs:
  - core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt:160
opened: 2026-05-15
---

**AUTO-FILED PARENT ANCHOR.** Source code cites `UD-352` (bare, no
sub-letter) but only sub-letter variants (`UD-352a`) exist as real
entries. This stub exists so the `scripts/backlog-sync.kts` CI gate
under UD-766 doesn't see bare `UD-352` cites as orphans.

## Sub-letter variants

- [UD-352a](#ud-352a) — see entry above for full scope.

When the bare `UD-352` cites are touched, prefer pointing them at the
specific sub-letter variant they relate to and close this anchor as
`wontfix-historical, parent of sub-tickets`.

---
id: UD-904
title: Migrate core test framework from JUnit 4 to JUnit 5
category: tooling
priority: low
effort: M
status: open
code_refs:
  - core/app/sync/build.gradle.kts
  - core/app/core/build.gradle.kts
  - core/app/cli/build.gradle.kts
  - core/app/mcp/build.gradle.kts
  - core/app/xtra/build.gradle.kts
  - core/app/benchmark/build.gradle.kts
  - core/app/e2e-360/build.gradle.kts
  - core/providers/internxt/build.gradle.kts
  - core/providers/rclone/build.gradle.kts
  - core/providers/localfs/build.gradle.kts
  - core/providers/s3/build.gradle.kts
  - core/providers/sftp/build.gradle.kts
  - core/providers/webdav/build.gradle.kts
  - core/providers/onedrive/build.gradle.kts
opened: 2026-05-15
---
2026-05-15: All 14 core modules currently declare `useJUnit()`
(JUnit 4) in their `build.gradle.kts`. Surfaced when writing the
`LocalWatcherShutdownTest` for the Top-10 hygiene round (the test
was originally drafted in JUnit 5 / Jupiter and had to be rewritten
in `kotlin.test` to match the existing convention).

Most existing tests use `kotlin.test`, which is framework-agnostic
and works on either backend. ~10 files use hard `org.junit.*`
imports (JUnit 4 annotations); these need rewriting. Zero files
use `org.junit.jupiter.*` today.

Migration outline:
1. Add JUnit 5 BOM + `junit-jupiter-engine` to `gradle/libs.versions.toml`.
2. Flip `useJUnit()` → `useJUnitPlatform()` in all 14 build files.
3. Rewrite the ~10 files with hard `org.junit.*` imports.
4. Verify the full test suite passes per module.

Out of scope for the Top-10 hygiene round (separate concern from
bug-fixing). Filed here so it doesn't get lost.

Spec context: ../unidrive-android/docs/superpowers/specs/2026-05-15-top10-hygiene-design.md
(JUnit 4 vs 5 issue documented in Task 4 of the Tier 1 plan)

---
id: UD-905
title: StateDatabase explicit transaction policy (ADR territory)
category: design
priority: low
effort: M
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt
opened: 2026-05-15
---
2026-05-15: `StateDatabase` runs `autoCommit = true` for everything.
Single shared connection + per-method `@Synchronized`, but bulk
reconciler ops eat per-statement commit latency. Explicit `BEGIN/COMMIT`
for batches would help; correctness today is preserved by the process
lock.

ADR territory — needs a written decision on transaction policy
before code change.

Tier 3 / deferred from the Top-10 hygiene round.

Spec: ../unidrive-android/docs/superpowers/specs/2026-05-15-top10-hygiene-design.md §6

---
id: UD-906
title: Re-enable Semgrep / Trivy / docker-integration CI jobs
category: tooling
priority: medium
effort: M
status: open
code_refs:
  - .github/workflows/build.yml
opened: 2026-05-15
---
2026-05-15: `.semgrep.yml` is referenced in CI but not present;
Trivy is commented out; the docker-integration job has been
disabled since 2026-05-01. UD-107..110 are nominally baseline
gates but only gitleaks is wired today.

Tier 3 / deferred from the Top-10 hygiene round.

Spec: ../unidrive-android/docs/superpowers/specs/2026-05-15-top10-hygiene-design.md §6
---
id: UD-100
title: UDS socket has no explicit POSIX 0600 chmod
category: security
priority: medium
effort: XS
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt:77-78
opened: 2026-05-15
---
**Source:** External review pass 2026-05-15 cross-verified by codebase audit. Independent finding: the UDS server binds the socket file but never sets POSIX permissions on it.

## Issue

`IpcServer.start()` opens a `ServerSocketChannel` of family `UNIX` and binds it to `socketPath` (`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt:77-78`). No `Files.setPosixFilePermissions(socketPath, ...)` call follows. The socket file inherits the umask default — typically `srwxr-xr-x` (world-readable, owner-writable).

The parent directory under `$XDG_RUNTIME_DIR/unidrive/` is created with `rwx------` (0700) by `tempSocketDir()`, which prevents cross-user access on a properly-configured tmpfs. However, on systems where `$XDG_RUNTIME_DIR` is not set, the fallback path under `/tmp/` may not have the same parent-dir protection, and same-user processes can always traverse `rwx------` if they are the same UID.

## Impact

Severity: medium.

The IPC channel is push-only (broadcast loop at `IpcServer.kt:105-121`), so observers cannot inject commands. But they can:
- Subscribe to live sync state updates (file paths being synced, progress percentages, throttle events)
- Correlate sync activity with timing side-channels (e.g., infer file sizes from broadcast cadence)
- Use the broadcast frames as a probe to detect whether the user is running unidrive at all

Any malicious same-user process (browser extension, sandboxed app, npm install postinstall) gets free read access to the user's cloud-sync activity stream.

## Fix shape

After `server.bind(...)` on `IpcServer.kt:78`, add:

```kotlin
runCatching { Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------")) }
```

Wrap in `runCatching` because Windows lacks POSIX file permissions and the existing `PosixPermissions` helper at `core/app/core/src/main/kotlin/org/krost/unidrive/io/PosixPermissions.kt:31-33` already follows this pattern (returns early on non-POSIX filesystems).

## Verification

- Add a test `IpcServerPermissionsTest` that starts the server, reads `Files.getPosixFilePermissions(socketPath)`, and asserts the set equals `{OWNER_READ, OWNER_WRITE}`.
- `assumeTrue` skip on non-POSIX hosts.

## Cross-refs

- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt:77-78` — bind site missing chmod
- `core/app/core/src/main/kotlin/org/krost/unidrive/io/PosixPermissions.kt:27-48` — existing 0600/0700 helper used by Vault and CredentialStore
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Vault.kt:86` — reference pattern for setting POSIX perms on a sensitive file
- `docs/SECURITY.md` STRIDE row I3 (information disclosure) — should be updated once fixed
---
id: UD-215
title: StateDatabase.getAllEntries() missing @Synchronized
category: core
priority: medium
effort: XS
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:203
opened: 2026-05-15
---
**Source:** External review pass 2026-05-15, cross-verified.

## Issue

`StateDatabase.getAllEntries()` at `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:203` is missing `@Synchronized`. 21 of the 22 methods on `StateDatabase` use the monitor (e.g. `getEntryCount()` at line 195, `getEntriesByPrefix()` at line 213, `deleteEntry()` at line 224). `getAllEntries()` is the lone outlier, iterating a `ResultSet` without holding the lock.

## Impact

Severity: medium.

The Kotlin-level monitor serializes JDBC access across the engine. Without it, `getAllEntries()` runs an `executeQuery` + `while(rs.next())` loop while another coroutine — most plausibly the reconciler's writer path or `pruneEntries` — can execute `DELETE FROM sync_entries` on the same `Connection`. Realistic failure modes:

1. `SQLiteException: ResultSet closed` thrown mid-iteration if a concurrent statement on the same connection invalidates the cursor.
2. Silent under-iteration: the `ResultSet` returns rows for entries that have already been deleted by the time the caller acts on them, yielding stale `SyncEntry` snapshots.

SQLite's connection-level mutex prevents corruption, but the JDBC API does not guarantee cursor stability when sibling statements run on the same `Connection`.

## Fix shape

One line: add `@Synchronized` to the method declaration at line 203, matching the convention of every other reader on this class.

## Verification

- Compile + `./gradlew :app:sync:test` confirms no regression.
- Optionally add a stress test that runs `getAllEntries()` in a tight loop while another coroutine mutates the table; the test should not throw.

## Followup question

Should `StateDatabase` migrate to a `ReentrantReadWriteLock` so concurrent readers don't serialize on the monitor? Worth a separate ticket if scan-phase parallelism becomes a bottleneck. For now `@Synchronized` is the consistent fix.

## Cross-refs

- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:203` — unsynchronized method
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:194,212,223` — peer methods showing the expected pattern
---
id: UD-217
title: StateDatabase lateinit conn has no reconnect/init guard
category: core
priority: low
effort: S
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:20
opened: 2026-05-15
---
**Source:** External review pass 2026-05-15, cross-verified.

## Issue

`StateDatabase` holds `private lateinit var conn: Connection` at `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:20`. The only assignment site is `initialize()` (lines 22-37). If the SQLite connection is closed externally — by an explicit `close()` call followed by a re-entry that misses calling `initialize()`, or by a JVM-level fault on the FileChannel backing the DB — every subsequent `@Synchronized` method throws either:
- `kotlin.UninitializedPropertyAccessException: lateinit property conn has not been initialized`, or
- `java.sql.SQLException: database connection is closed` (after `close()` was called explicitly).

There is no auto-reconnect or `ensureConnection()` guard.

## Impact

Severity: low.

SQLite is in-process and file-backed; connection drops are rare under normal operation. The realistic failure mode is operator error: a callsite forgets to call `initialize()` after constructing the `StateDatabase`, or a test fixture leaks a closed instance into a coroutine. Both surface as a hard exception rather than data corruption.

The reason this is worth filing despite low severity: the failure mode is opaque (`UninitializedPropertyAccessException` does not name `StateDatabase` in its stack frame), and the fix is cheap.

## Fix shape options

Option A — minimal: replace `lateinit var conn` with a getter that throws an explicit `IllegalStateException("StateDatabase not initialized — call initialize() first")` with class name.

Option B — defensive: add `private fun ensureConnected(): Connection { if (!::conn.isInitialized || conn.isClosed) initialize(); return conn }` and route all method bodies through it. Trades clarity for resilience.

Option C — structural: make `StateDatabase` open the connection in `init {}` and remove `initialize()` entirely. Connection lifecycle becomes tied to the `StateDatabase` instance.

Recommended: Option C. The current two-phase init (`constructor + initialize()`) exists for the `inMemory=true` path used by `--reset --dry-run`, but that flag is set in the constructor anyway. There is no reason `initialize()` cannot be called from `init {}`.

## Verification

- Existing tests for `StateDatabase` should pass without modification.
- Add a regression test: construct `StateDatabase`, immediately call a method without `initialize()`, assert a clear error message (Option A) or that it works (Option C).

## Cross-refs

- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:20` — `lateinit var conn`
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:22-37` — only initialization site
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:40-42` — `close()` site, no reconnect guard
---
id: UD-220
title: Reconciler.kt recovery synthesis has high cognitive load (3 phases + bookkeeping)
category: core
priority: medium
effort: M
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:175-269
opened: 2026-05-15
---
**Source:** External review pass 2026-05-15, cross-verified.

## Issue

`Reconciler.reconcile()` accumulates a three-phase recovery structure between `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:182-264` that is hard to reason about locally:

1. **Phase A — UD-225 download recovery (lines 183-204).** Walks `allDbEntries` for placeholder rows (non-folder, non-hydrated, `remoteSize > 0`) not already covered by the action set. Synthesises `DownloadContent` actions. Guarded by exclude-glob and `pathInSyncScope` filters.
2. **Phase B — UD-901 upload recovery (lines 213-224).** Walks `allDbEntries` for pending-upload rows (non-folder, `remoteId == null`, hydrated, local file exists). Synthesises `Upload` actions. Same scope/exclude guards.
3. **Phase C — UD-901b ancestor synthesis (lines 245-264).** Post-pass over `recoveryEmitted = actions.subList(recoveryStartIdx, actions.size).toList()`. For each recovery-emitted action's path, walks the ancestor chain and synthesises `CreateRemoteFolder` for any ancestor not already in `coveredPaths`/`ancestorsToCreate` and not known on remote.

The bookkeeping spans four mutable sets (`coveredPaths`, `actions`, `recoveryEmitted`, `ancestorsToCreate`) and one index (`recoveryStartIdx`). Each phase has 5-7 `continue` filters that must stay aligned across phases (folder check, hydrated check, covered-paths check, exclude-glob, scope guard).

## Impact

Severity: medium.

Functionally correct — the inline UD-225/UD-901/UD-901a/UD-901b comments and `ReconcilerTest.kt` cover the known regressions. Cognitive load is the issue:

- Adding a new recovery case requires understanding all three phases plus the `recoveryStartIdx`/`recoveryEmitted` convention.
- A new filter requirement (e.g., a future "skip files under a paused profile") must be added in 3 places to stay consistent.
- The phases are not unit-testable in isolation; only the composed `reconcile()` result is.

## Fix shape

Extract each phase into a named method on `Reconciler`:

```kotlin
private fun synthesisDownloadRecovery(allDbEntries, remoteChanges, coveredPaths, syncPath): List<SyncAction>
private fun synthesisUploadRecovery(allDbEntries, syncRoot, coveredPaths, syncPath): List<SyncAction>
private fun synthesisAncestorFolders(recoveryEmitted, entryByPath, coveredPaths): List<SyncAction>
```

Each method returns the actions to append; `reconcile()` becomes:

```kotlin
actions += synthesisDownloadRecovery(...)
actions += synthesisUploadRecovery(...)
val recoveryEmitted = actions.takeLast(...)  // or track index
actions += synthesisAncestorFolders(recoveryEmitted, entryByPath, coveredPaths)
```

Lift the shared filter chain (folder, hydrated, covered, exclude, scope) into a private predicate `isRecoveryCandidate(entry)` to enforce alignment.

## Followup question

The current shape evolved across UD-225 → UD-901 → UD-901a → UD-901b incrementally. Before refactoring, audit `ReconcilerTest.kt` coverage to confirm each phase has a regression test pinning its behaviour. Add tests for any uncovered branch before the extraction so the refactor is a pure rename.

## Verification

- `./gradlew :app:sync:test` green pre- and post-refactor.
- Diff coverage: no new uncovered branches.
- Code-style sanity: line count of `reconcile()` should drop from ~90 lines (lines 175-269) to ~30.

## Cross-refs

- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Reconciler.kt:175-269` — current monolithic block
- `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ReconcilerTest.kt` — regression coverage
- UD-225 (download recovery), UD-901 (upload recovery), UD-901a (scope guard), UD-901b (ancestor synthesis) — historical context
---
id: UD-775
title: Re-enable ktlint (close UD-774; CI-only or full)
category: tooling
priority: medium
effort: S
status: open
code_refs:
  - core/build.gradle.kts:26-51
opened: 2026-05-15
---
**Source:** External review pass 2026-05-15. Continuation of UD-774's reactivation plan.

## Issue

`val ktlintEnabled = false` at `core/build.gradle.kts:31` disables the entire ktlint pipeline across all subprojects. The conditional at line 36 (`if (ktlintEnabled) { apply(plugin = "org.jlleitschuh.gradle.ktlint") ... }`) skips `ktlintCheck`, `ktlintFormat`, baseline drift detection, and the per-project baseline.xml convention from UD-706b.

UD-774's comment block (lines 26-30) documents the disable as temporary: "Flip back to `true` to restore the UD-706 / UD-706b setup. Re-enable plan: run `scripts/dev/ktlint-sync.sh` after flip to absorb any baseline drift, then `./gradlew build` to confirm green, then close UD-774."

Since the disable on 2026-05-02, ~50 commits have landed without ktlint enforcement (`git log --oneline --since="2026-05-02" | wc -l`). The longer ktlint stays off, the larger the eventual baseline absorption.

## Impact

Severity: medium.

Drift accumulates silently. Currently no enforcement means:
- New code lands without style consistency checks.
- The 1541-line `SyncEngine.kt` and the 1072-line `WebDavApiService.kt` (the two largest files) have no automated guard against the kind of large-block-style drift that ktlint catches cheaply.
- When ktlint is re-enabled, `ktlint-sync.sh` will absorb a large diff into baseline.xml entries, which silently downgrades enforcement quality (each absorbed violation is one we agreed to never re-check).

## Fix shape

Three sub-steps; each can be a separate commit:

1. **Audit drift first.** Run `./gradlew :app:sync:ktlintMainSourceSetCheck` (or equivalent) on a temporary branch with `ktlintEnabled = true` and the existing baseline. Capture the new-violation count per subproject.
2. **Decide absorb vs fix.** If the new-violation count is small (e.g., <50), fix them in code rather than baseline. If large, absorb via `scripts/dev/ktlint-sync.sh` but tag each absorption with a UD-775 reference in the baseline so the debt is trackable.
3. **Flip the flag.** `val ktlintEnabled = true` at `core/build.gradle.kts:31`, delete the UD-774 comment block, close UD-774.

## Cost analysis

The original UD-774 disable rationale: "ktlint costs ~20-30s per `./gradlew build` and was the dominant per-iteration cost during the UD-240g/UD-240i sessions on 2026-05-02."

Mitigations available:
- Run ktlint only on changed files via `ktlintCheck --include` patterns.
- Gate ktlint behind a build property: `if (project.hasProperty("noKtlint")) false else true` so iterative dev can opt out via `./gradlew build -PnoKtlint`.
- Move ktlint to CI-only (skip locally) by checking `System.getenv("CI") != null`.

Recommended: CI-only enforcement. Local dev gets fast iteration; PR gate catches drift before merge.

## Verification

- After flip: `./gradlew clean build` from `core/` green.
- After flip: `scripts/dev/ktlint-sync.sh` reports no surprise baseline drift.
- After flip: close UD-774 with `python3 scripts/dev/backlog.py close UD-774 --commit <sha>`.

## Cross-refs

- `core/build.gradle.kts:26-51` — current disable + conditional apply
- `scripts/dev/ktlint-sync.sh` — baseline absorption helper
- `docs/dev/lessons/ktlint-baseline-drift.md` — prior failure mode this guards against
- UD-706 (initial ktlint setup), UD-706b (per-project baseline), UD-774 (current disable)
---
id: UD-318
title: Migrate S3 provider from custom SigV4Signer to AWS SDK
category: providers
priority: low
effort: M
status: open
code_refs:
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/SigV4Signer.kt
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt
opened: 2026-05-15
---
**Source:** External review pass 2026-05-15, cross-verified.

## Issue

`core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/SigV4Signer.kt` is a 154-line hand-rolled implementation of AWS Signature Version 4. The S3 provider uses it to sign every request rather than going through the AWS SDK for Kotlin or the AWS SDK for Java v2.

## Impact

Severity: low.

The current implementation works for the request shapes unidrive actually issues (PUT, GET, DELETE, HEAD on standard S3 endpoints). The maintenance burden is realised when:

- AWS rotates the canonical-request hashing algorithm (rare but has happened — SigV4a regional/global signing was added 2022).
- A new request shape needs signing: presigned URLs, S3 Express One Zone, chunked uploads with trailing checksums, IAM role assumption.
- A new authentication mode is required: SSO, IAM Identity Center, container credential provider, EC2 instance metadata service v2.
- A subtle bug is found (e.g., URI-encoding edge case for `+` vs `%20` in query strings) — the SDK has these regression-tested.

The cost of staying on the hand-rolled signer compounds with every S3 feature unidrive wants to support.

## Fix shape

Two paths:

**Path A — AWS SDK for Kotlin (preferred).** `aws.sdk.kotlin:s3:1.x`. Idiomatic Kotlin coroutines, native suspend functions, smaller dependency surface than the Java SDK. Cost: ~5 MB added to the fat jar, all transfers go through their HTTP engine (loses unidrive's `HttpRetryBudget` integration unless wired in via interceptor).

**Path B — AWS SDK for Java v2.** `software.amazon.awssdk:s3:2.x`. Mature, well-documented, but pulls a larger dep graph (~15 MB) and requires `Future`-to-coroutine adapters.

Path A recommended. The provider already isolates HTTP via `S3ApiService.kt`; switching the underlying client is a localised change.

## Migration notes

- `HttpRetryBudget` integration: AWS SDK has its own retry policies. Need to either disable SDK retry and wire `HttpRetryBudget` as a `RequestInterceptor`, or accept SDK retries and remove the budget for S3. Decision affects retry semantics consistency across providers.
- Throttle handling: SDK surfaces `S3Exception` with retryable HTTP status; unidrive's `ThrottleBudget` (UD-232) is HTTP-layer; need to verify rate-limit signals propagate.
- Test coverage: existing 8 tests in `core/providers/s3/src/test/kotlin/` should pass post-migration. Add a contract test that confirms PUT/GET/DELETE/HEAD round-trip against a live S3 (or LocalStack) bucket.
- SigV4Signer.kt removal: keep file in git history; delete from main after green CI on the migration commit.

## Followup question

Worth coordinating with the WebDAV adapter's `trustAllCerts` story (UD-104 closure) before migrating: if the AWS SDK pins its own HTTP engine, the user's "trust all certs for self-hosted S3 (MinIO, Garage)" requirement may need a separate SDK-level escape hatch.

## Verification

- `./gradlew :providers:s3:test` green.
- Integration smoke against a MinIO container: upload, download, delete, list.
- Fat-jar size delta documented in commit message.

## Cross-refs

- `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/SigV4Signer.kt` — 154 LoC to remove
- `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ApiService.kt` — main consumer
- `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Config.kt` — endpoint/region config
- `core/providers/s3/src/test/kotlin/` — 8 existing tests as regression net
---
id: UD-819
title: Adopt property-based testing for path/glob/escape invariants (rename misleading SftpProviderPropertyTest)
category: tests
priority: low
effort: M
status: open
code_refs:
  - core/providers/sftp/src/test/kotlin/org/krost/unidrive/sftp/SftpProviderPropertyTest.kt
  - core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt:70-77
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:324
opened: 2026-05-15
---
**Source:** External review pass 2026-05-15, cross-verified.

## Issue

`core/providers/sftp/src/test/kotlin/org/krost/unidrive/sftp/SftpProviderPropertyTest.kt` is misleadingly named: despite "Property" in the filename, the file contains standard example-based JUnit tests. Codebase-wide grep finds no property-based-testing framework imports (no `io.kotest.property`, no `kotlin-test-property`, no `junit-quickcheck`, no `Arbitrary`/`forAll`).

## Impact

Severity: low.

Example-based tests cover the cases the author thought of. Property-based testing (PBT) shines for code shapes unidrive has plenty of:

- **Path handling** — `LocalFsProvider.safePath()` at `core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt:70-77`. The containment invariant is "for all relative paths `p`, `safePath(p).startsWith(rootPath)` OR throws". PBT generates adversarial paths (`../`, `./`, NUL bytes, mixed separators, percent-encoded) that no hand-written test will enumerate.
- **Glob matching** — `matchesGlob()` used by exclude patterns in `Reconciler.kt:187,218`. PBT generates random glob/path pairs and asserts symmetry properties (escape round-trip, anchor-independence).
- **SQL LIKE escaping** — `escapeLike()` at `StateDatabase.kt:324`. Property: `escapeLike(s) + "%"` matches `s` and nothing prefixed by `s` plus an additional `%` or `_`. PBT generates strings containing `%`, `_`, `\`.
- **Sparse-file dehydrate/hydrate round-trip** — `PlaceholderManager.dehydrate()` at `PlaceholderManager.kt:110-121`. Property: dehydrate-then-hydrate is identity for any file of size N ≥ 0.
- **JWT base64url padding** — `isJwtExpired()` at `AuthService.kt:73` (UD-308). Property: for any base64url string of length L, `padded.length % 4 == 0`.

The filename suggests someone planned PBT for SFTP path-handling and didn't follow through. Renaming is half the fix; the other half is actually adding PBT.

## Fix shape

Three sub-steps:

1. **Add kotest-property.** `testImplementation("io.kotest:kotest-property:5.9.x")`. Kotest's PBT module is the most idiomatic Kotlin option and integrates with JUnit5.
2. **Rename `SftpProviderPropertyTest.kt` to `SftpProviderBasicsTest.kt`.** Decouples filename from the framework absence.
3. **Add a PBT module per high-value invariant.** Priorities, in order: `safePath()` containment, `escapeLike()` correctness, `isJwtExpired()` padding. Each as a separate test file: `LocalFsPathPropertyTest.kt`, `EscapeLikePropertyTest.kt`, `JwtPaddingPropertyTest.kt`.

## Cost

- `kotest-property` adds ~2 MB to test classpath, no production impact.
- Each invariant test: ~20-40 lines.
- PBT runs are slower than example tests (default 1000 iterations) but parallelisable.

## Verification

- `./gradlew test` green with new tests.
- Mutation-test a known bug in `safePath()` (e.g., temporarily remove the `startsWith` check); PBT should catch it within ≤100 iterations.
- Document the PBT pattern in `docs/dev/CODE-STYLE.md` so future contributors know when to reach for it.

## Cross-refs

- `core/providers/sftp/src/test/kotlin/org/krost/unidrive/sftp/SftpProviderPropertyTest.kt` — misleadingly named file
- `core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt:70-77` — `safePath()` candidate
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt:324` — `escapeLike()` candidate
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/AuthService.kt:73` — JWT padding candidate (UD-308)
---
id: UD-702
title: Add AGENTS.md pointer file for tool-agnostic onboarding
category: tooling
priority: low
effort: XS
status: open
code_refs:
  - CLAUDE.md
  - CONTRIBUTING.md
opened: 2026-05-15
---
**Source:** External review pass 2026-05-15. Tool-agnostic onboarding gap surfaced when comparing unidrive's repo conventions to the cross-tool `AGENTS.md` convention adopted by Codex, OpenCode, Aider, and other non-Claude coding agents.

## Issue

`CLAUDE.md` at the repo root provides detailed project-scoped agent onboarding: quick-start, "Before you X" sections for backlog mutation / log reading / ktlint, commit etiquette, infrastructure context. There is no equivalent file readable by other coding agents that ship with `AGENTS.md` conventions:

- **Codex CLI** reads `AGENTS.md` (Anthropic-derived but tool-distinct).
- **OpenCode** reads `AGENTS.md`.
- **Aider** reads `AGENTS.md` and `.aider.conf.yml`.
- **GitHub Copilot CLI** reads `AGENTS.md` (recent convention).

A new contributor or external agent landing in the repo via one of those tools sees no project-level guidance, has no idea backlog mutation must go through `backlog.py`, may hand-edit `BACKLOG.md` (the exact failure mode `backlog.py` was built to prevent), or may not know about `ktlint-sync.sh` / `log-watch.sh`.

## Impact

Severity: low.

Functionally invisible to the daily Claude Code workflow. Surfaces when:

1. External contributor uses Codex/OpenCode/Aider against the repo — onboarding friction.
2. The maintainer wants to A/B-test a different coding agent without porting the entire CLAUDE.md content.
3. Future automation (CI checks, repo-policy linters) wants a stable "agent guidance" anchor file by convention.

## Fix shape

Option A — symlink (zero duplication): `ln -s CLAUDE.md AGENTS.md`. Both files point to the same content. Works on POSIX; Windows clones via WSL or with `core.symlinks=true` also work, but native Windows clones see the symlink as a regular text file containing the literal string `CLAUDE.md`.

Option B — thin pointer file: create `AGENTS.md` with a 3-line body:
```markdown
# Agent guidance

The canonical agent-onboarding doc for this repo is [CLAUDE.md](CLAUDE.md).
Read that file first. All conventions there apply to any coding agent.
```
No duplication risk, no symlink concerns. Tool-agnostic agents follow the link.

Option C — fork the content: duplicate `CLAUDE.md` content into `AGENTS.md` and keep both in sync. Highest maintenance burden — drift is guaranteed.

Recommended: **Option B**. Cheap, robust, no platform footguns.

## Verification

- `cat AGENTS.md` from a fresh clone returns the pointer text.
- Codex/OpenCode/Aider opened against the repo surface the project conventions.
- Add a one-liner to `CONTRIBUTING.md` noting that both files exist and `CLAUDE.md` is canonical.

## Cross-refs

- `CLAUDE.md` — current canonical agent doc at repo root
- `CONTRIBUTING.md` — should reference both files once `AGENTS.md` lands
- Earlier verification pass at 2026-05-15 confirmed `AGENTS.md` is absent
