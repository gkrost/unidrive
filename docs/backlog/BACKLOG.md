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
id: UD-766
title: Wire backlog-sync.kts into CI (build.yml)
category: tooling
priority: high
effort: XS
status: open
code_refs:
  - scripts/backlog-sync.kts
  - scripts/ci/backlog-sync.sh
  - .github/workflows/build.yml
opened: 2026-05-01
---
**Wire `scripts/backlog-sync.kts` into `.github/workflows/build.yml` as a CI step. Best leverage-to-effort ratio in the tree.**

The script and shell wrapper both already exist (`scripts/backlog-sync.kts`, 179 lines; `scripts/ci/backlog-sync.sh`, 6 lines), but no CI job invokes them — drift goes uncaught until someone runs `kotlinc -script` locally, and most contributors don't.

## What's already there

- `scripts/backlog-sync.kts` — canonical orphan/stale/anchorless/abandoned checker per AGENT-SYNC.md. Exit 0 on clean (or warnings only); exit 1 on hard errors (orphan code refs, stale closed items).
- `scripts/ci/backlog-sync.sh` — already exists as the CI wrapper. Three lines: `cd` to repo root, `exec kotlinc -script scripts/backlog-sync.kts`. Ready to call.
- `docs/AGENT-SYNC.md` — already documents that this script is the contract. The contract just isn't enforced.

## What gets caught the moment we wire it

In one shot:
- **Orphan code refs** — `// UD-xyz` in source with no matching block in `BACKLOG.md`/`CLOSED.md`.
- **Stale closed** — IDs in `CLOSED.md` still referenced in source.
- **Non-canonical statuses** — frontmatter `status:` outside `open|in-progress|blocked|closed`.
- **Anchorless open** (warning) — `code_refs:` pointing at non-existent files.
- **Abandoned** (warning) — `status: open`, no `code_refs`, opened > 30 d ago.
- **Source-vs-CLOSED drift** — entries that disagree between BACKLOG and CLOSED.

These are the failure modes that today only surface during PR review or session handover. Wiring catches them at push time.

## Acceptance

- New job `backlog` in `.github/workflows/build.yml` running on `ubuntu-latest` only (kotlinc-only — no JDK build needed):
  - Checkout
  - Set up JDK 21 (kotlinc needs it)
  - Install kotlinc (`curl` from GitHub releases, or use a marketplace action — pick whichever is cheaper)
  - `bash scripts/ci/backlog-sync.sh`
- Job runs on push to `main` and on PRs (same triggers as `core`).
- Job is **fast** — script reads docs + greps `core/` for `UD-###` patterns, no Gradle invocation. Should be < 30 s including kotlinc warmup.
- `concurrency:` group shared with the existing `core` group so PRs don't queue duplicates.
- Failure surfaces in the PR check list with a useful summary (the script's stderr is already shaped for humans).

## Out of scope

- Re-using the existing Gradle daemon — kotlinc is fine standalone for a 179-line script.
- Strict mode for warnings — keep "anchorless open" + "abandoned" as warnings only (script default). If we want to escalate later, that's a separate ticket.
- Running this in a pre-commit hook — separate ticket, related to UD-762's `check-docs.sh` salvage.

## Provenance

Discussed 2026-05-01 with maintainer. Highest leverage/effort ratio in the tree because: (a) script + wrapper already exist, (b) checks every PR + every push to `main`, (c) catches 6 distinct drift classes simultaneously.
---
id: UD-003
title: ADR-0014 consolidating ADR-0008/0011/0012/0013 — v0.1.0 surface
category: architecture
priority: high
effort: XS
status: open
code_refs:
  - docs/adr/
opened: 2026-05-01
---
**Write ADR-0014 consolidating the v0.1.0 surface as it stands after ADR-0008 + 0011 + 0012 + 0013.**

A new contributor reading the ADR set today must mentally compose four documents to answer "what's actually shipping?":

- ADR-0008 (greenfield restart): "v0.1.0 = core-only, ui/ + shell-win/ at preview"
- ADR-0011 (remove shell-win): "actually, shell-win/ is gone"
- ADR-0012 (Linux-only MVP + protocol/ removal): "actually, protocol/ + named pipes are gone too"
- ADR-0013 (remove ui/): "actually, ui/ is also gone"

ADR-0008's stated trade-offs ("no tray, no Explorer integration") are now the actual shipped state, not a temporary acceptance. Each amendment ADR explicitly cross-references the others (see the `amends` / `amended_by` frontmatter chain). The chain is faithfully recorded but not summarised.

## Why a consolidator is the right shape (not a rewrite)

ADR-0008..0013 are **historical** — they record decisions and the context at the time. ADR rule of thumb: never rewrite history; instead supersede with a new ADR that captures the *current* decision surface. ADR-0014 is that consolidation.

It does **not** invalidate the four it consolidates. They stay accepted; ADR-0014 cites them as `consolidates: ADR-0008, ADR-0011, ADR-0012, ADR-0013` and is the **single answer** to "what's shipping in v0.1.0?"

## Acceptance

`docs/adr/0014-v0_1_0-surface.md`, ~half a page, frontmatter:

```yaml
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
id: UD-767
title: Add docs/ROADMAP.md + docs/NON-GOALS.md (half page each)
category: tooling
priority: high
effort: XS
status: open
code_refs:
  - docs/ROADMAP.md
  - docs/NON-GOALS.md
  - README.md
opened: 2026-05-01
---
**Add `docs/ROADMAP.md` and `docs/NON-GOALS.md` — half a page each. Raises the project from "preview with strong opinions" to "preview with discoverable strategy."**

The current public has rigorous tactical docs (`SPECS.md`, `ARCHITECTURE.md`, `AGENT-SYNC.md`, `BACKLOG.md`, 13 ADRs, lessons-learned files) but no single document that answers a contributor's first two questions:

1. **"Where is this going?"** — answered today only by reading 13 ADRs + the BACKLOG.md (90+ tickets) + the wiki. That's a 30-minute reading task, and even after it the answer is implicit.
2. **"What is this *not* trying to be?"** — answered today only by ADR-0011/0012/0013 (each phrased as "we removed X"), which is hard to discover proactively.

Both gaps are common in open-source previews. Closing them is a half-day of writing that pays off every time someone asks "does it support Y?", "will it ever do Z?", "should I file this as a feature request?"

## What goes where

### `docs/ROADMAP.md` (~half a page)

**Audience:** prospective contributor, prospective user, prospective sponsor.

**Shape:** time-anchored milestones, not a feature list. Each milestone is one paragraph.

```markdown
# Roadmap

## v0.1.0 — first release (Linux MVP)
Quality-gated providers: localfs, s3, sftp. CLI + MCP + sync engine.
Linux-only. Outstanding gates: <link to BACKLOG.md milestone:v0.1.0>.

## v0.2.0 — preview providers graduate
OneDrive, WebDAV, HiDrive, Internxt, Rclone leave preview status. Each
needs: live-integration test in CI, capability-contract round-trip
(ADR-0005), parallelism budget tuned in `ProviderMetadata`. Likely Q3.

## v0.3.0 — release artefacts
Standalone installer (`dist/install.sh`, UD-761), GitHub Releases with
fat JAR, Scoop bucket / WinGet manifest if community appetite exists.
Webhook-driven sync exits experimental status (UD-???).

## Beyond v0.3.0 — not committed
- Shell-extension overlays (Linux: Nautilus + Dolphin; Windows: depends
  on appetite). See `BACKLOG_IDEAS_UI.md`.
- Companion projects: `unidrive-android` (in flight in adjacent repo),
  `unidrive-tray` (community).
- Provider expansion to Google Drive, Dropbox, Box (currently only via
  rclone gateway).
```

Cross-links into `BACKLOG.md`'s `milestone:v0.1.0` field. If we don't currently use the `milestone:` field consistently, the ROADMAP creation forces that audit.

### `docs/NON-GOALS.md` (~half a page)

**Audience:** anyone about to file a feature request that won't land.

**Shape:** explicit list with one-line "why not" for each. Doesn't need numbering.

```markdown
# Non-goals

unidrive-cli explicitly does NOT aim to:

- **Be a backup tool.** Sync ≠ backup. We sync deltas; we do not snapshot
  history-aware archives. Use restic/borg/duplicacy for that. (We do
  retain `unidrive backup add` for one-way replication, which is
  different from a backup tool.)
- **Run on Windows or macOS in v0.1.0.** ADR-0012 is the authority. Both
  are post-v0.3.0 candidates per `BACKLOG_IDEAS_UI.md`.
- **Ship a system-tray UI in core.** ADR-0013 moved that to companion
  projects; the daemon's UDS broadcast surface is the contract for
  third-party trays. See `BACKLOG_IDEAS_UI.md` W11/W12.
- **Implement provider-specific features that don't generalise.** The
  `CloudProvider` interface is deliberately minimal; provider quirks
  hide behind capabilities (ADR-0005). "OneDrive shared notebooks" or
  "Dropbox Paper documents" are out unless they map to a generalisable
  capability.
- **Be a sync conflict resolver.** We surface conflicts (`unidrive
  conflicts`) and offer two policies (`keep_both`, `last_writer_wins`),
  but we do not auto-merge document contents. Document-merge tooling is
  a different product.
- **Replace native cloud storage clients.** OneDrive's official client
  has features we won't replicate (cloud streams in Office, embedded
  Teams sharing, etc.). We're a **second-class citizen** for any single
  provider, but a **first-class citizen** for the multi-provider use
  case. That's the trade.
```

Each non-goal cites either an ADR, a BACKLOG_IDEAS_UI section, or a "why" sentence. The list isn't fixed — adding a non-goal as the project matures is fine, and gets a date footer (`Updated YYYY-MM-DD`).

## Why both, not just one

A roadmap without non-goals reads as "all the things, eventually, just be patient" — which is what every preview looks like and trains contributors to file maximalist requests. Non-goals constrain expectations bidirectionally: contributors know what won't land, users know what to look elsewhere for, sponsors know the scope they're actually backing.

## Acceptance

- `docs/ROADMAP.md` exists, ~half a page, links to BACKLOG.md milestone field + ADR-0014 (when filed under UD-003).
- `docs/NON-GOALS.md` exists, ~half a page, references ADR-0011/0012/0013 + ADR-0005 + `BACKLOG_IDEAS_UI.md`.
- `README.md` adds two links in the docs section pointing at both.
- Wiki Home page (built 2026-05-01) gets a "What's next" section with a one-liner pointer to ROADMAP.md.
- If `BACKLOG.md` items don't currently have `milestone:` set, populate it for the ones in v0.1.0 / v0.2.0 / v0.3.0 buckets — keeps ROADMAP.md and BACKLOG.md in sync via the milestone field.

## Out of scope

- Detailed feature specs for v0.2.0 or v0.3.0 — those ship as separate spec files in `docs/specs/` when the time comes.
- Marketing copy / pitch deck — different artefact, different audience.

## Provenance

Discussed 2026-05-01 with maintainer. Pairs with UD-003 (ADR-0014 surface consolidator) — together they make the project navigable for new contributors without reading 13 ADRs + 90 tickets.
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
id: UD-114
title: SECURITY.md drift: NDJSON validation claim vs ADR-0012 (NamedPipeServer + NdjsonValidator deleted)
category: security
priority: medium
effort: S
status: open
code_refs:
  - docs/SECURITY.md:81
  - docs/SECURITY.md:94
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcProgressReporter.kt
opened: 2026-05-02
---
## Problem

`docs/SECURITY.md:81` and `docs/SECURITY.md:94` claim:

> **NDJSON frame validation** at IPC boundary
> (`IpcProgressReporter.kt`) — rejects non-conforming frames before
> dispatch.

> Schema validation rejects malformed structure
> (`IpcProgressReporter.kt`); JSON parse errors dropped.

This was true before [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)
retired the bidirectional named-pipe transport. With ADR-0012:

- `core/app/sync/.../NamedPipeServer.kt` — **deleted**.
- `core/app/sync/.../NdjsonValidator.kt` — **deleted**.
- The remaining `IpcProgressReporter.kt` is a **push-only UDS
  broadcaster**: the daemon writes NDJSON sync events out to a Unix
  socket; nothing reads frames *into* the daemon over IPC.

Verified at filing time:

```
$ find core -name "NamedPipeServer.kt" -o -name "NdjsonValidator.kt"
(no results)
```

The mitigation referenced in `SECURITY.md` therefore does not exist
in the current architecture. The threat model row T1 ("Tampering
with an NDJSON command frame in flight (local loopback)") is also
moot — there are no inbound IPC frames to tamper with.

## Risk

This is a **threat-model honesty** issue. A reader of `SECURITY.md`
believes the daemon validates incoming IPC frames. It doesn't,
because there are no incoming IPC frames. If a future change
re-introduces an IPC inbound surface (e.g. a CLI-to-daemon command
channel), the existing doc would falsely claim it's already
validated.

## Proposed action

Update `docs/SECURITY.md`:

1. **§"Current mitigations"** (line 81 area): remove the "NDJSON
   frame validation at IPC boundary" bullet. Replace with a
   short note describing the actual UDS-broadcast topology and
   why it doesn't need inbound validation (because it has no
   inbound surface).

2. **STRIDE table T1** (line 94 area): either drop the row
   entirely (no inbound IPC = no T1 threat surface) or rephrase
   to cover what *is* exposed today — a malicious local reader
   binding to `unidrive-<profile>.sock` and consuming frames
   intended for the legitimate observer. That has its own
   mitigations (`mode 0600` on the socket, `SO_PEERCRED` if we
   ever want peer identity).

3. Cross-reference [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)
   §"Removed surfaces" so the historical context is one click away.

## Acceptance criteria

- [ ] `grep -rn "NamedPipeServer\|NdjsonValidator" docs/SECURITY.md`
      returns nothing.
- [ ] `grep -n "IpcProgressReporter" docs/SECURITY.md` either
      returns nothing or the surrounding text accurately describes
      what the file does today (push-only UDS broadcaster).
- [ ] STRIDE T1 row either removed or rewritten for the actual
      threat surface.
- [ ] No code changes required — this is purely doc drift.

## Why this is its own ticket

It's a security-doc fix; bundling it with general doc cleanup
would bury it. Filing under `security` range keeps it on the
right reviewer's radar (anyone scanning the security tier in
`AGENT-SYNC.md`).
---
id: UD-768
title: scripts/dev/log-watch.sh defaults to Windows AppData path on Linux MVP
category: tooling
priority: low
effort: XS
status: open
code_refs:
  - scripts/dev/log-watch.sh:22
opened: 2026-05-02
---
## Problem

`scripts/dev/log-watch.sh:22` defaults the live-log path to a
**Windows AppData path** on a project whose MVP is Linux:

```
LOG="${UNIDRIVE_LOG:-$HOME/AppData/Local/unidrive/unidrive.log}"
```

On Linux (the MVP per ADR-0012), `$HOME/AppData/Local/...` does
not exist; the daemon writes to either `$XDG_STATE_HOME/unidrive/`
or `$HOME/.local/state/unidrive/` depending on env. The script
therefore prints "no such file" until the user remembers to set
`UNIDRIVE_LOG`.

This default is also the seed of broader doc drift: any reader
who looks at `log-watch.sh` to understand "where does unidrive
log on this platform?" gets a misleading answer.

## Proposed action

Two acceptable paths:

**A) Pick the right Linux default** and document the env-var
override:

```bash
LOG="${UNIDRIVE_LOG:-${XDG_STATE_HOME:-$HOME/.local/state}/unidrive/unidrive.log}"
```

This matches the daemon's actual write path (verify against
the daemon's logging config before committing).

**B) Refuse to default at all** if `UNIDRIVE_LOG` is unset on
Linux, exit with a one-line error pointing at the env var. More
explicit, less convenience.

Recommend **A**: matches the daemon, removes the cliff for new
contributors trying out the script.

## Acceptance criteria

- [ ] `log-watch.sh` default resolves to a path the daemon
      actually writes to on Linux.
- [ ] If macOS / Windows fallbacks are kept, they're behind an
      `os.name` check at the top of the script with a comment
      explaining ADR-0012.
- [ ] Smoke: `UNIDRIVE_LOG=/tmp/test.log scripts/dev/log-watch.sh
      --summary` works on Linux without further env setup.

## Why a separate ticket from UD-400

UD-400 sweeps `os.name` branches in **non-test Kotlin code**.
This is a shell script. Same spirit, different scope. Atomic
commit lets `git log` show the rationale without dragging the
Kotlin sweep along.
---
id: UD-769
title: Drop windows-latest from CI core matrix (or demote to allowed-to-fail) per ADR-0011/0012
category: tooling
priority: medium
effort: XS
status: open
code_refs:
  - .github/workflows/build.yml:39
opened: 2026-05-02
---
## Problem

`.github/workflows/build.yml:39`:

```yaml
strategy:
  fail-fast: false
  matrix:
    os: [ubuntu-latest, windows-latest]
```

[ADR-0011](adr/0011-shell-win-removal.md) +
[ADR-0012](adr/0012-linux-mvp-protocol-removal.md) make Linux the
MVP target. macOS and Windows are explicitly community-best-effort
(README §"Status", in slim form: "Linux-first").

Yet CI runs the full `core` job on `windows-latest` and `fail-fast:
false` means a Windows-only failure produces a red overall build
status even when Linux is green. This:

- **Lies about the support matrix.** Anyone reading the build
  badge and expanding the run sees "we test on Windows" — we
  don't, in any meaningful sense; we just smoke that the JVM
  modules compile.
- **Burns CI time** (Windows runners are 5-10× slower than
  ubuntu-latest for Gradle cold start; observed in
  run 25247884883 — Windows job ran 5+ minutes when Linux took
  3).
- **Adds noise to PRs.** Every PR sits with a half-failed
  status until the Windows run finishes, even though the
  failure is rarely a real defect.

## Proposed action

Two acceptable paths:

**A) Drop windows-latest from the matrix entirely.** Honest,
fast, matches ADR-0012. Loses the "compiles on Windows"
canary, which is real but cheap to restore later.

**B) Keep it as a separate, allowed-to-fail job** with an
`continue-on-error: true` or a `if: github.event_name ==
'schedule'` guard so PR builds don't block on Windows. Restore
the Windows runner only on the main branch / nightly schedule.

Recommend **A** for v0.1.0. Re-add via **B** if a future ADR
re-opens Windows as a tier (per ADR-0012 §"Re-opening criteria").

## Acceptance criteria

- [ ] `core` matrix does not include `windows-latest` for PR
      builds.
- [ ] If kept (Option B), windows-latest is `continue-on-error:
      true` AND only runs on `push: branches: [main]` or a
      `schedule:` trigger.
- [ ] README badge stays accurate (linux-first claim is honest).
- [ ] No regression on ubuntu-latest + gitleaks jobs.

## Why this is its own ticket

It's a one-line CI config change but with policy implications
(does the project still claim "best-effort Windows builds"?).
A discrete commit with a clear `git log` entry is worth more
than folding it into a generic "CI hygiene" sweep.
---
id: UD-770
title: SPECS.md §2.2 reports 15 MCP tools; code lists 23 (UD-216 admin verbs added; doc not updated)
category: tooling
priority: medium
effort: XS
status: open
code_refs:
  - docs/SPECS.md:54
  - core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:36-62
opened: 2026-05-02
---
## Problem

`docs/SPECS.md:54` claims the MCP server exposes 15 tools,
verified against `core/app/mcp/.../Main.kt:20-24`:

> | `app/mcp` | MCP server with 15 tools |
> [`core/app/mcp/`](../core/app/mcp) | 💻 **15 tools verified**
> (`core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt:20-24`) |

The actual count in `Main.kt` (verified at filing time) is **23**:
the original 15 user-facing tools (status, sync, get, free, pin,
conflicts, ls, config, trash, versions, share, relocate,
watchEvents, quota, backup) **plus 8 admin verbs** added under
[UD-216](#ud-216): authBegin, authComplete, logout, profileList,
profileAdd, profileRemove, profileSet, identity.

`Main.kt:53` even has the comment `// UD-216: admin verbs for
end-to-end LLM-driven management.` admitting the count moved.

The SPECS.md row is also marked 💻 **15 tools verified**, which
is now a false attestation — the verification was correct at the
time it was made, but the doc didn't follow the code.

## Risk

- `SPECS.md` is the **normative intent catalog** per CLAUDE.md.
  The whole point of SPECS.md is to flag doc-vs-code drift with
  📄 / 💻 / ✅ / ⚠ labels. A wrong 💻 attestation is precisely
  the failure mode SPECS.md exists to prevent.
- LLM clients picking up unidrive's MCP server expecting "15
  tools" per the docs will be surprised by the 8 admin verbs;
  not breaking, but inelegant.

## Proposed action

1. Update `docs/SPECS.md:54` row:
   - Tool count: `15` → `23` (or whatever `Main.kt` lists at
     edit time — re-verify, do not trust this ticket).
   - Line range: `Main.kt:20-24` → the actual `listOf(...)`
     range. At filing time the `tools = listOf(...)` block
     spans `Main.kt:36-62`.
   - Verification label: stays 💻 (code-verified) once corrected.
2. Cross-check: does any other doc (README, ARCHITECTURE.md,
   docs/EXTENSIONS.md, docs/MCP* if such exists) cite "15
   tools"? Sweep and update.
3. Add a `// SPECS.md row N — keep tool count in sync` marker
   comment near the `listOf(...)` block in `Main.kt` so future
   adds prompt a SPECS.md edit. (Optional; only if the maintainer
   thinks comment-pinning beats a CI check.)

## Acceptance criteria

- [ ] `docs/SPECS.md:54` (or its current line) reports the
      actual tool count, with the actual line range.
- [ ] `grep -rn "15 tools" docs/` returns no stale claims.
- [ ] No code changes required (this is doc drift only).

## Why this is its own ticket

Tiny but high-trust: SPECS.md is the source of truth for
intent-vs-code accuracy. Drift here erodes confidence in every
other ✅ / ⚠ label across the catalog. Worth its own atomic
commit so a future reader can `git log -- docs/SPECS.md` and
see exactly when the count was reconciled.
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
id: UD-006
title: Lift formatSize/formatBytes (IEC byte formatting) into :app:core/io; eliminate 5+ duplicates
category: architecture
priority: high
effort: S
status: open
code_refs:
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/CliProgressReporter.kt:284
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/VersionsCommand.kt:53
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/TrashCommand.kt:49
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt:374
  - core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusAudit.kt:46
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
opened: 2026-05-02
---
## Problem

`formatSize` / `formatBytes` (IEC-binary byte formatting: `B`/`KiB`/`MiB`/`GiB`/`TiB`) is duplicated across 5 files:

| File | Line | Status |
|---|---|---|
| `core/app/cli/src/main/kotlin/.../CliProgressReporter.kt` | 284 (companion) | canonical |
| `core/app/cli/src/main/kotlin/.../VersionsCommand.kt` | 53 | duplicate |
| `core/app/cli/src/main/kotlin/.../TrashCommand.kt` | 49 | duplicate |
| `core/app/cli/src/main/kotlin/.../RelocateCommand.kt` | 374 | duplicate |
| `core/app/cli/src/main/kotlin/.../StatusAudit.kt` | 46 | duplicate |
| `core/providers/webdav/src/main/kotlin/.../WebDavProvider.kt` | (added by UD-???) | duplicate (this PR added a 6th, deliberately, per the SPI-contract spec §3.5) |

All implement the same algorithm. Highest-ROI cleanup: lift to `:app:core` and replace 5 (or 6, including the WebDAV one this PR introduced) call-sites.

## Proposed action

1. Create `core/app/core/src/main/kotlin/org/krost/unidrive/io/ByteFormatter.kt`:

   ```kotlin
   package org.krost.unidrive.io

   /**
    * Format a byte count using IEC binary prefixes (KiB, MiB, ...).
    * One decimal place above the 1 KiB threshold.
    */
   fun formatSize(bytes: Long): String {
       val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
       var size = bytes.toDouble()
       var unit = 0
       while (size >= 1024 && unit < units.lastIndex) {
           size /= 1024
           unit++
       }
       return "%.1f %s".format(size, units[unit])
   }
   ```

2. Replace each duplicate with `import org.krost.unidrive.io.formatSize`.

3. Verify byte-identical output for representative sizes (1, 1023, 1024, 999_999_999, 50 GiB) before deleting any duplicate.

## Why architecture-range

This is a cross-module helper lift; the tooling category is for build/CI scripts. Architecture range fits cross-module API surface.

## Acceptance criteria

- [ ] `core/app/core/src/main/kotlin/org/krost/unidrive/io/ByteFormatter.kt` exists.
- [ ] All 5 (or 6) duplicates replaced with `import org.krost.unidrive.io.formatSize`.
- [ ] Output byte-identical for the 5 fixture sizes above (verify via test).
- [ ] Build green; `./gradlew build test` reports the same baseline.

## Out of scope

Locale-aware formatting, decimal-prefix variants (KB vs KiB), or any
behaviour change to the algorithm itself.
---
id: UD-007
title: Default logout() on CloudProvider; remove 4-provider boilerplate (WebDAV, S3, Rclone, LocalFs)
category: architecture
priority: medium
effort: XS
status: open
code_refs:
  - core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt
  - core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProvider.kt
  - core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt
opened: 2026-05-02
---
## Problem

4 of 7 in-tree providers (WebDAV, S3, Rclone, LocalFs) implement `logout()` as the same boilerplate:

```kotlin
override suspend fun logout() { isAuthenticated = false }
```

The `CloudProvider` interface should provide this as the default. SFTP, OneDrive, and Internxt would override to add `api.close()` / `tokenManager.logout()` etc.

## Proposed action

1. In `CloudProvider.kt`, change `logout()` from abstract to default-implemented:

   ```kotlin
   /**
    * Forget any cached credentials / authenticated state. Default
    * implementation flips the in-memory flag; providers that own
    * external resources (token files, network connections) override
    * to release them.
    */
   suspend fun logout() {
       // Default no-op; subclasses override to release resources.
       // Note: subclasses that track an in-memory `isAuthenticated`
       // flag must reset it themselves in their override.
   }
   ```

2. Decide: should `isAuthenticated` be lifted into `CloudProvider`? If yes, the default `logout()` can flip it. If not, keep the default a no-op and let each provider's override handle its own state.

3. Delete the boilerplate `override suspend fun logout() { isAuthenticated = false }` in WebDAV, S3, Rclone, LocalFs.

4. Verify SFTP, OneDrive, Internxt overrides still do the right thing (they'll need to set `isAuthenticated = false` themselves now if it was previously inherited from the boilerplate; depends on §2 decision).

## Acceptance criteria

- [ ] `logout()` has a default implementation in `CloudProvider`.
- [ ] WebDAV, S3, Rclone, LocalFs no longer override `logout()`.
- [ ] SFTP, OneDrive, Internxt overrides still close their external
      resources.
- [ ] Existing `logout`-related tests still pass; no test weakened.

## Out of scope

Lifting `isAuthenticated` is a separate decision that can be made
inside this ticket OR deferred. Document the choice in the ticket
resolution note.
---
id: UD-008
title: Abstract SnapshotDeltaProvider helper (or base class) — eliminate 5x deletedItem lambda duplication and shared delta() boilerplate
category: architecture
priority: high
effort: M
status: open
code_refs:
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProvider.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt
  - core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProvider.kt
  - core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SnapshotDeltaEngine.kt
opened: 2026-05-02
---
## Problem

All 5 snapshot-based providers (SFTP, WebDAV, S3, Rclone, LocalFs) follow an identical `delta()` structure:

```kotlin
override suspend fun delta(cursor, onPageProgress): DeltaPage {
    val heartbeat = onPageProgress?.let { cb -> ScanHeartbeat(cb) }
    val currentEntries = api.listAll(onProgress = { count -> heartbeat?.tick(count) })
    val snapshotEntries = buildSnapshotEntries(currentEntries)
    val itemsByPath = currentEntries.associate { it.path to it.toCloudItem() }
    return computeSnapshotDelta(
        currentEntries = snapshotEntries,
        currentItemsByPath = itemsByPath,
        prevCursor = cursor,
        entrySerializer = XxxSnapshotEntry.serializer(),
        hasChanged = { prev, curr -> /* provider-specific */ },
        deletedItem = { path, entry ->
            CloudItem(id = path, name = path.substringAfterLast("/"), path = path,
                      size = 0, isFolder = entry.isFolder, modified = null,
                      created = null, hash = null, mimeType = null, deleted = true)
        },
    )
}
```

Per-provider variations:
- `listAll()` — API call shape
- `buildSnapshotEntries()` — entry-type mapping
- `toCloudItem()` — `CloudItem` mapping
- `hasChanged` — change-detection predicate

The `deletedItem` lambda is **byte-identical across all 5** providers.

## Proposed action

Two options:

**A) Abstract base class `SnapshotDeltaProvider<E>`** that captures the boilerplate. Concrete providers implement only the four variations:

```kotlin
abstract class SnapshotDeltaProvider<E>(
    private val entrySerializer: KSerializer<E>,
) : CloudProvider {
    protected abstract suspend fun listAll(heartbeat: ScanHeartbeat?): List<RawEntry<E>>
    protected abstract fun hasChanged(prev: E, curr: E): Boolean

    final override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?): DeltaPage {
        // shared boilerplate — calls listAll, builds maps, calls computeSnapshotDelta
    }
}
```

**B) Standalone helper function `snapshotDelta(...)`** that takes the four variation points as lambda parameters. Providers retain their flat structure but call into the helper:

```kotlin
override suspend fun delta(cursor, onPageProgress): DeltaPage =
    snapshotDelta(
        cursor = cursor,
        progress = onPageProgress,
        listAll = { hb -> api.listAll(...) },
        toEntry = ::buildSnapshotEntry,
        toCloudItem = RawEntry<E>::toCloudItem,
        hasChanged = ::hasChanged,
        entrySerializer = MySnapshotEntry.serializer(),
    )
```

Recommend **B** (helper function) over **A** (abstract base): `CloudProvider` is already an interface, and Kotlin doesn't love abstract-class diamond inheritance when interfaces gain abstract methods. Helper-function pattern keeps providers as plain classes.

Lift `deletedItem` into the helper; per-provider duplicates disappear.

## Acceptance criteria

- [ ] Decision A vs B documented in the ticket resolution.
- [ ] `deletedItem` lambda exists once (in the helper or the base class), zero duplicates across providers.
- [ ] All 5 snapshot providers' `delta()` methods shrink to ≤10 lines, mostly delegating.
- [ ] No test regressions; existing `Reconciler` / `delta` tests still pass.

## Why architecture-range

It's a structural change to how snapshot providers compose with the
sync engine. Architecture range; needs an ADR or short design doc
before any code moves (decision A vs B is the kind of thing future
readers will ask about).

## Out of scope

Refactoring OneDrive (it uses `/delta` natively, not snapshot mode)
or Internxt (its delta path is also custom and is being audited
separately under UD-354).
---
id: UD-009
title: Lift defaultTokenPath() into :app:core/io; eliminate 5-provider duplicate (SFTP/WebDAV/S3/OneDrive/Internxt)
category: architecture
priority: medium
effort: XS
status: open
code_refs:
  - core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProviderFactory.kt
  - core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt
  - core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ProviderFactory.kt
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProviderFactory.kt
  - core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProviderFactory.kt
opened: 2026-05-02
---
## Problem

5 providers (SFTP, WebDAV, S3, OneDrive, Internxt) each define an identical helper:

```kotlin
fun defaultTokenPath(): Path {
    val home = System.getenv("HOME") ?: System.getProperty("user.home")
    return Paths.get(home, ".config", "unidrive", "<provider-id>")
}
```

Only the last path segment varies by provider id.

## Proposed action

Lift to `:app:core` as a single helper:

```kotlin
// core/app/core/src/main/kotlin/org/krost/unidrive/io/TokenPath.kt
package org.krost.unidrive.io

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Default per-profile token storage directory:
 *   $HOME/.config/unidrive/<providerId>
 *
 * The caller passes its own `id` (typically `factory.id`) so this
 * function does not need to know which providers exist.
 */
fun defaultTokenPath(providerId: String): Path {
    val home = System.getenv("HOME") ?: System.getProperty("user.home")
    return Paths.get(home, ".config", "unidrive", providerId)
}
```

Replace the 5 duplicates with `org.krost.unidrive.io.defaultTokenPath(id)`.

## Acceptance criteria

- [ ] `core/app/core/src/main/kotlin/org/krost/unidrive/io/TokenPath.kt` exists.
- [ ] All 5 duplicates removed.
- [ ] Behaviour byte-identical: `defaultTokenPath("onedrive")` produces the same path string the old `OneDrive`-specific helper did.
- [ ] No test regressions.

## Why architecture-range

Cross-module helper lift. Same category as UD-006.

## Out of scope

Honouring `XDG_CONFIG_HOME` (currently the helper hardcodes `~/.config`; that's a separate ticket if/when XDG compliance becomes a goal).
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
id: UD-818
title: Replace 5x duplicate ProviderFactory required-fields tests with parameterised TestFactory driven by ProviderRegistry + credentialPrompts() (post UD-006/spi-contract)
category: tests
priority: medium
effort: S
status: open
code_refs:
  - core/providers/s3/src/test/kotlin/org/krost/unidrive/s3/S3ProviderFactoryTest.kt
  - core/providers/sftp/src/test/kotlin/org/krost/unidrive/sftp/SftpProviderFactoryTest.kt
  - core/providers/webdav/src/test/kotlin/org/krost/unidrive/webdav/WebDavProviderFactoryTest.kt
  - core/providers/localfs/src/test/kotlin/org/krost/unidrive/localfs/LocalFsProviderFactoryTest.kt
  - core/providers/rclone/src/test/kotlin/org/krost/unidrive/rclone/RcloneProviderFactoryTest.kt
opened: 2026-05-02
---
## Problem

5 provider-factory test files follow the same shape:

| File | Pattern |
|---|---|
| `S3ProviderFactoryTest.kt` | `fullProps()` helper → for each required field: test it missing → test it blank → assert `ConfigurationException` |
| `SftpProviderFactoryTest.kt` | same |
| `WebDavProviderFactoryTest.kt` | same |
| `LocalFsProviderFactoryTest.kt` | same |
| `RcloneProviderFactoryTest.kt` | same |

Each test class is ~5-15 lines of unique-per-provider data wrapped in identical assertion scaffolding.

## Proposed action

Two options:

**A) Parametric base class.** A `ProviderFactoryRequiredFieldsTestBase<F : ProviderFactory>` in `:app:core/src/testFixtures/` that takes `(factory, requiredKeys, fullProps)` and drives the same tests. Each provider's test class extends it with a 5-line constructor.

**B) JUnit 5 `@TestFactory` style.** A single test in `:app:core/src/test/` that iterates over `ProviderRegistry.all()`, queries each factory's `credentialPrompts()` (now the SPI capability — see UD-006 / refactor-provider-spi-contract), filters to required prompts, and runs the missing-field assertions.

Recommend **B** (TestFactory pattern) — it leverages the new SPI capability `credentialPrompts()` introduced in this session's refactor, so the test stays current as new providers are added without requiring a new test-class subclass per provider. Adding a 9th provider gets free coverage.

## Acceptance criteria

- [ ] Decision A vs B documented.
- [ ] Per-provider factory test classes shrink dramatically OR are deleted.
- [ ] Coverage of "required field missing/blank → ConfigurationException" still in place for all providers (verify by running coverage report).
- [ ] No new provider can be added without automatically getting required-field coverage.

## Why tests-range

It's a test-architecture refactor.

## Out of scope

Other test patterns (the wizard tests, integration tests, capability-matrix tests). This is just the missing-field assertion family.
---
id: UD-012
title: Replace 8x "onedrive" historical default in SyncConfig.kt with ProviderRegistry-driven default (or document via ADR)
category: architecture
priority: medium
effort: S
status: open
code_refs:
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:304
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:313
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:359
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:433
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:436
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:441
  - core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt:461
opened: 2026-05-02
---
## Problem

`SyncConfig.kt` hardcodes `"onedrive"` as the universal fallback default at 8 sites (verified 2026-05-02 against the post-refactor/provider-spi-contract tree):

| Line | Role |
|---|---|
| 304 | `providerId: String = "onedrive"` (default param of `RawProvider.defaults`-helper-style ctor) |
| 313 | `"onedrive" to "OneDrive"` — display-name override map |
| 359 | `fun defaults(providerId: String = "onedrive")` |
| 433 | `if (!Files.exists(configFile)) return "onedrive"` (resolveDefaultProfile waterfall) |
| 436 | `raw.general.default_profile?.takeIf { it.isNotBlank() } ?: "onedrive"` |
| 441 | `"onedrive"` (last fallback in resolveDefaultProfile) |
| 461 | `profileName: String = "onedrive"` (default param) |

Plus one help-text reference:
- `core/app/cli/src/main/kotlin/.../Main.kt:474` — `RCLONE_BINARY ... default: "rclone"` (refers to the rclone *binary* on PATH, not the provider id; mentioned for completeness).

The `SyncConfig` references are the "single-provider-era" artefact: the project had only OneDrive when this default was chosen. With 7 in-tree providers today, the choice is undocumented and inconsistent (display-name map at 313 only covers one provider; the rest get their default-display from elsewhere).

## Proposed action

Replace with a registry-driven default. Two acceptable shapes:

**A) `ProviderRegistry.defaultProvider()`** that returns either:
- the first SPI-discovered provider (deterministic by classpath order, usually `localfs` since it's pure-Java); or
- a config-driven choice (`general.default_provider_when_none` in `config.toml`).

Then `SyncConfig.kt` calls `ProviderRegistry.defaultProvider()` instead of literal `"onedrive"`. Display-name map at line 313 is removed in favour of `ProviderRegistry.getMetadata(id)?.displayName`.

**B) Keep "onedrive" but document why** in an ADR. If OneDrive is genuinely the canonical default for the project's target audience, that's a defensible position — but it should be a deliberate choice with `// allow: per ADR-XXXX` markers on each site.

Recommend **A**. The architecture has moved on; the literal hasn't. A registry-driven choice is also testable (parametric tests can swap the default per-test).

## Acceptance criteria

- [ ] Decision A vs B documented (ideally in a short ADR, e.g. ADR-0015 "default provider resolution").
- [ ] If A: `SyncConfig.kt` has zero `"onedrive"` literals; all 8 sites consult `ProviderRegistry.defaultProvider()` or `ProviderRegistry.getMetadata`.
- [ ] If B: each surviving literal has `// allow: per ADR-XXXX` marker (CI guard's allow-list mechanism).
- [ ] Either way, `bash scripts/ci/check-no-provider-string-dispatch.sh` passes.
- [ ] No regression in `resolveDefaultProfile` waterfall semantics; existing tests still green.

## Why architecture-range

It's a structural decision (what is the project's default provider?) with cross-module implications. UD-004 / UD-008 / UD-011 are all architecture-range; this fits.

## Out of scope

XDG-config-honouring resolution, multi-default support (different default per environment), profile-cycling. Those are independent later work.

## Status of the CI guard

`scripts/ci/check-no-provider-string-dispatch.sh` (added in
refactor/provider-spi-contract) currently flags these 8 sites. Until
this ticket is resolved, **the lines are tagged with `// allow:
UD-012` markers** so CI doesn't fail on pre-existing technical debt.
Removing those markers is part of this ticket's done-criterion.
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
id: UD-700
title: log-watch.sh hardcodes Windows log path; fails on Linux/macOS by default
category: tooling
priority: low
effort: XS
status: open
opened: 2026-05-04
---
`scripts/dev/log-watch.sh` hardcodes a Windows-only default log path and fails immediately on Linux/macOS.

## Repro (2026-05-04 session)

```
$ bash scripts/dev/log-watch.sh --summary
log not found: /home/gernot/AppData/Local/unidrive/unidrive.log
```

The actual log on Linux lives at `~/.local/share/unidrive/unidrive.log` (XDG `$XDG_DATA_HOME`).

## Root cause

`scripts/dev/log-watch.sh:22`:

```bash
LOG="${UNIDRIVE_LOG:-$HOME/AppData/Local/unidrive/unidrive.log}"
```

The default path is Windows-only. The escape hatch (`UNIDRIVE_LOG=… bash log-watch.sh …`) works but every Linux/macOS contributor — i.e. all of them — has to discover this and set it manually every time.

## Proposed fix

Detect platform and pick the right XDG-style path. The skill `unidrive-log-anomalies` invokes this script unconditionally at session start; right now it silently fails on Linux every session.

```bash
default_log_path() {
  case "$(uname -s)" in
    Darwin)         echo "$HOME/Library/Logs/unidrive/unidrive.log" ;;
    Linux)          echo "${XDG_DATA_HOME:-$HOME/.local/share}/unidrive/unidrive.log" ;;
    MINGW*|MSYS*|CYGWIN*) echo "$HOME/AppData/Local/unidrive/unidrive.log" ;;
    *)              echo "${XDG_DATA_HOME:-$HOME/.local/share}/unidrive/unidrive.log" ;;
  esac
}
LOG="${UNIDRIVE_LOG:-$(default_log_path)}"
```

Verify the macOS path against whatever the daemon actually writes (likely `~/Library/Logs/unidrive/`, but could be `~/Library/Application Support/unidrive/` if the logger config says so — check `core/app/cli/src/main/resources/logback.xml` or whichever logger config the JVM uses, and align).

## Acceptance

- `bash scripts/dev/log-watch.sh --summary` works out of the box on Linux without setting `UNIDRIVE_LOG`.
- macOS path is verified against the daemon's actual writer config.
- Windows path remains the default for `MINGW*`/`MSYS*`/`CYGWIN*`.
- `unidrive-log-anomalies` skill produces a real summary at session start on Linux instead of "log not found".
- Bonus: if no log file exists at the resolved default *and* `UNIDRIVE_LOG` is unset, print a one-line hint pointing to where the daemon would write it on this OS, instead of just `log not found:`.

## Notes

- Pure scripting fix, no Kotlin changes.
- Could also affect the JFR scripts (`scripts/dev/unidrive-jfr.sh` / `.ps1`) — worth a quick grep for sibling paths while in there.
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
id: UD-203
title: Capture x-request-id (and provider equivalents) in provider exception types for log correlation
category: core
priority: medium
effort: S
status: open
opened: 2026-05-04
---
**Source:** internxt/sdk research (agent `a4990ef`, 2026-05-04). The SDK's single best observability lever is `AxiosResponseError.xRequestId` — extracted from the `x-request-id` response header at error normalization time. unidrive's provider exception types should carry the same field so `unidrive.log` ERROR lines correlate with server-side support tickets.

## Code refs

- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiException.kt` (or wherever the type lives) — add `xRequestId: String?`
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtApiService.kt` — `parseError`-style helper, populate from response headers
- `core/providers/onedrive/...` — same pattern (already partially captures `request-id`?)
- `core/providers/hidrive/...`, `core/providers/s3/...` — extend pattern
- BACKLOG.md line 1100s — the existing error-parsing audit ticket already enumerates per-provider error fields; this extends it to capture the request-id specifically

## Header-name matrix per provider

| Provider | Response header | Notes |
|----------|-----------------|-------|
| Internxt | `x-request-id` | confirmed by SDK research |
| OneDrive | `request-id` and/or `client-request-id` | Microsoft Graph standard |
| S3 | `x-amz-request-id` + `x-amz-id-2` | both useful for AWS support |
| HiDrive | TBD — verify | |
| WebDAV | none standard — skip | |
| SFTP | n/a (not HTTP) | |

## Acceptance

- Provider-specific exception types each gain a `requestId: String?` (or provider-specific name) field.
- Error-construction sites populate it from response headers when available; null otherwise.
- Logging interceptor surfaces `requestId=<value>` on every ERROR log line that originated from a provider exception.
- Unit tests: forced 500 from each provider's mock, with synthetic header → exception carries the value, log line contains it.
- `docs/providers/<provider>-robustness.md` documents the header name being captured.

## Notes

- This is the cheapest observability win in the research reports. ~30 LOC per provider plus the logging interceptor.
- Pairs with UD-330 — when retries fail and the budget gives up, the final ERROR log should include the last-attempt's `requestId`.
- Out of scope: client-side request-id generation (we'd send our own correlator). Useful but separate ticket.

## Cross-refs

- internxt/sdk research — `Top 5 ideas to steal` #3.
- BACKLOG ticket near line 1095-1130 (existing error-parsing audit) — this is the captured-fields extension for the request-id specifically.
---
id: UD-204
title: Per-call HTTP timeout matrix across providers (metadata vs upload vs download vs auth)
category: core
priority: medium
effort: S
status: open
opened: 2026-05-04
---
**Source:** internxt/sdk research (agent `a4990ef`, 2026-05-04). The SDK's axios setup omits `timeout`, so a slow-loris server hangs everything indefinitely. unidrive's OkHttp clients across providers should have an explicit per-call timeout matrix, calibrated by call class (metadata vs upload-PUT vs download-GET).

## Code refs

- `core/app/core/src/main/kotlin/org/krost/unidrive/http/` — shared HTTP helpers
- `core/providers/*/src/main/kotlin/.../*ApiService.kt` — per-provider OkHttpClient builders (verify each has timeouts set, document the matrix)

## Proposed timeout matrix

| Call class | Connect | Read | Write | Call (overall) | Notes |
|------------|---------|------|-------|----------------|-------|
| Metadata (list/get/move/delete) | 5 s | 15 s | 5 s | 30 s | Internxt API typical |
| Upload PUT (single shard, multipart part) | 10 s | 5 min | 5 min | 10 min | 50 MB at modest throughput |
| Download GET (shard) | 10 s | 5 min | n/a | 10 min | Same shape |
| Auth (login, refresh) | 5 s | 10 s | 5 s | 30 s | Fast or fail |

Overall `callTimeout` (OkHttp 3.12+) bounds the whole request including retries within one budget. Read timeout bounds inter-byte gaps. Connect timeout bounds initial socket establishment.

## Acceptance

- All provider OkHttp clients explicitly set `connectTimeout`, `readTimeout`, `writeTimeout`, `callTimeout`.
- The four call classes (metadata / upload / download / auth) each have their own client or per-call override (OkHttp `newBuilder().callTimeout(...)`).
- Unit test: slow-loris mock that drips 1 byte/sec on a metadata call gets aborted within `readTimeout` (15s); same on an upload PUT gets aborted within `callTimeout` (10min).
- Documented in `docs/providers/<provider>-robustness.md` per provider.
- Tunable via env or config for power users (`UNIDRIVE_HTTP_UPLOAD_CALL_TIMEOUT_S`, etc.) — not exposed in CLI.

## Notes

- This is mostly a doc-and-verify ticket if the timeouts are already set; an audit will tell.
- Belt-and-braces against `ThrottleBudget` / `HttpRetryBudget` — those handle backpressure and retry, timeouts handle "the connection is just dead, give up."
- **Circuit breaker is explicitly out of scope** — premature for current scale. Note in the ticket as a future ticket if the timeout-only model proves insufficient.

## Cross-refs

- internxt/sdk research — `Cons / footguns` per area, recurring "no timeout" theme.
- UD-228 / UD-330 — retry budget rollout; timeouts are the floor under retry.
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
id: UD-211
title: Watcher: debounce + atomic-edit detection for vim/JetBrains-style rename-on-save
category: core
priority: medium
effort: M
status: open
opened: 2026-05-04
---
**Source:** drive-desktop research (agent `a876235`, 2026-05-04). drive-desktop's watcher debounces events at 2s and post-event-verifies existence to handle Windows' atomic-edit pattern (delete + create with different `internalId`). The same problem exists on Linux: editors like vim, Emacs, JetBrains IDEs save by writing a temp file and then `rename(2)`-ing over the original — inotify emits `IN_MOVED_FROM` + `IN_MOVED_TO` (or `IN_DELETE` + `IN_CREATE` depending on the editor's strategy), not a single `IN_MODIFY`.

Without debounce + atomic-edit detection, unidrive's local watcher can:
- Emit a DELETE upload action for the original file (cloud loses the file).
- Followed by a CREATE upload action for the new file (cloud gets it back, but with a new UUID and lost version history).

Net effect: every `:w` in vim creates a "delete + create" cycle in the cloud, churning version history and (on providers without true atomic semantics) creating a window where the file is missing.

## Code refs

- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/watcher/` — wherever the inotify integration lives
- Look for existing `WatchService` or `java.nio.file.WatchService` usage; may be using the JDK polling watcher fallback on some platforms
- Cross-platform abstraction: macOS uses `FSEvents`, Linux uses `inotify`, Windows uses `ReadDirectoryChangesW` — same logical surface

## Proposed shape

Two-phase coalescer:

```kotlin
class AtomicEditCoalescer(
    private val window: Duration = Duration.ofSeconds(2),
) {
    private val pendingByPath = ConcurrentHashMap<Path, MutableList<WatchEvent>>()

    fun onEvent(event: WatchEvent) {
        val path = canonicalize(event.path)
        val list = pendingByPath.computeIfAbsent(path) { mutableListOf() }
        synchronized(list) { list += event }
        scheduleFlush(path, window)
    }

    private fun scheduleFlush(path: Path, after: Duration) { /* coroutine delay */ }

    private fun flush(path: Path) {
        val events = pendingByPath.remove(path) ?: return
        // Heuristics:
        // 1. (DELETE, CREATE) within window + path exists → MODIFY
        // 2. (CREATE) only + path didn't exist before → CREATE
        // 3. (DELETE) only + path doesn't exist → DELETE
        // 4. (MOVED_FROM tmpA, MOVED_TO origPath) → MODIFY of origPath
        // 5. Multiple MODIFY → single MODIFY
        emit(coalesced)
    }
}
```

Post-flush existence verification: before emitting DELETE, `Files.exists(path)` — if it does exist (race re-create or atomic rename completed), emit MODIFY instead.

## Acceptance

- Integration test: simulated vim atomic save (write `.foo.swp`, rename to `foo`) emits **one MODIFY** event for `foo`, not DELETE + CREATE.
- Integration test: real `:w` in vim against a watched file (test harness can drive vim via `expect` or simply reproduce the syscall sequence) — one MODIFY.
- Real DELETE (no rename, no recreate) emits one DELETE.
- Real CREATE of a fresh file emits one CREATE.
- Rapid 5-event burst (multiple writes within 2s) coalesces to one MODIFY.
- Window is configurable via env (`UNIDRIVE_WATCHER_DEBOUNCE_MS`); default 2000ms.
- Test on at least Linux + macOS; Windows path covered by inheritance from the abstraction.

## Notes

- 2s debounce is conservative. drive-desktop uses 2000ms; faster would be desktop-feel but riskier on slow editors. Make it configurable.
- This ticket touches the watcher layer, **not** the sync engine. Reconciler/Executor are agnostic — they receive coalesced events.
- Pairs with UD-210 (re-stat-during-upload). UD-210 catches mutations *during* upload; this ticket catches editor-save patterns *before* they become bogus actions. Different defences, both needed.
- Out of scope: debounce-based event compression for very high-volume directories (e.g. logs that rotate every second). Default behaviour: emit each MODIFY past the debounce; high-volume tuning is a separate ticket.

## Cross-refs

- drive-desktop `src/node-win/watcher/on-event.ts` — direct shape source.
- B3 — sibling defence (mid-upload mutation).
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
