# Spec: Extend Provider SPI contract; remove provider-name string-equality dispatch from `:app:cli`, `:app:mcp`, `:app:sync`

- **Version:** 0.2.0
- **Date:** 2026-05-02
- **Status:** Reviewed → ready for implementation plan
- **Author:** brainstormed in collaboration with Claude; reviewed by user
- **Branch:** `refactor/provider-spi-contract` off `dev` off `main`
- **Changes since 0.1.0:** added 7th dispatch site
  (`ProfileCommand.kt:157`); expanded §3.3 with `AuditReport` /
  `StatusAudit` migration surface; renamed `PromptSpec.masked` →
  `isMasked`.

## 1. Problem

The README sells the SPI promise:

> Adding a ninth provider is one module, not a fork.

Audit of `:app:cli`, `:app:mcp`, `:app:sync` (this session, 2026-05-02)
shows **7 critical sites** that contradict the promise by dispatching
behaviour from a hardcoded provider-name string-equality check. To add
a ninth provider today you'd edit those 7 files in three modules in
addition to dropping in the `:providers:nine` module. That is a fork
in everything but name.

The 7 sites:

| Site | What it dispatches on `id` |
|---|---|
| [`HashVerifier.kt:24-28`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/HashVerifier.kt) | Hash-verification algorithm — `"onedrive"` → QuickXorHash, `"s3"` → MD5/ETag, else → unverified pass |
| [`ProfileCommand.kt:115-144`](../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ProfileCommand.kt) | Credential prompt schema — `"s3"` / `"sftp"` / `"webdav"` each have hardcoded `when` arms; other providers silently get no prompts |
| [`ProfileCommand.kt:157`](../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ProfileCommand.kt) | Post-add UX hint — `if (type in setOf("onedrive")) println("  Next: run unidrive -p $name auth")`. Any future interactive-auth provider would silently miss the hint. |
| [`Main.kt:355-365`](../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt) | Env-var → config-key mapping — `"s3"` → `AWS_ACCESS_KEY_ID` etc. |
| [`AuthTool.kt:80`](../../core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt) | "Only OneDrive supports OAuth begin" — refuses Internxt and any future OAuth provider |
| [`StatusCommand.kt:165`](../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt) | OneDrive-specific "include shared folders" status decoration |
| [`RelocateCommand.kt:191`](../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt) | WebDAV-specific bulk-transport warning ("nginx-mod_dav < 30 MiB/s") |

Each violates the same architectural rule: **the SPI consumer must
not know which providers exist or what their per-provider quirks are.
That information lives with the provider.**

## 2. Non-goals

- The 19 LOWER-severity sites flagged in the same audit (the
  `"onedrive"` monoculture defaults in `SyncConfig.kt`, the picocli
  description literals in `ProviderCommand.kt`, the dead
  `isKnownType`/`defaultTypes` in `ProviderRegistry`, the
  config-schema HiDrive residue, the Dockerfile/check-docs/backlog-
  sync stale lists). Those are filed as separate UD-### tickets for
  follow-up waves.
- Re-architecting `SyncEngine.doSyncOnce` (filed UD-004).
- Adding new capabilities (encryption-at-rest, audit log, etc.).
- Removing `ProviderRegistry`-style dispatch from the `:providers:*`
  modules themselves (which legitimately know their own name).

## 3. Design

### 3.1 Contract split — what goes where

The brainstorm settled on **option B**: methods placed by lifecycle.
Per the brainstorm, the lifecycle distinction is "what you can
ask before vs. after a `CloudProvider` instance exists with
credentials":

**`ProviderFactory` (factory-time, no credentials needed) — three new methods:**

```kotlin
interface ProviderFactory {
    // … existing: id, metadata, create, isAuthenticated, checkCredentialHealth, describeConnection

    /**
     * Schema for the interactive 'profile add' wizard. Each entry
     * describes one prompt the CLI should issue. Empty list = no
     * provider-specific prompts (the default; localfs uses this).
     */
    fun credentialPrompts(): List<PromptSpec> = emptyList()

    /**
     * Mapping of environment-variable name → config-property key
     * for credentials this provider can pick up from env. Used by
     * the CLI to warn when env vars are set but the matching config
     * key is also set (env is ignored). Empty = no env-var
     * recognition (the default).
     */
    fun envVarMappings(): Map<String, String> = emptyMap()

    /**
     * Whether this provider has an interactive auth flow (typically
     * OAuth) that the MCP `auth_begin` / `auth_complete` tools and
     * the CLI `auth` subcommand should drive. Default false: most
     * providers have credentials supplied via config and do not need
     * an interactive begin/complete handshake.
     */
    fun supportsInteractiveAuth(): Boolean = false
}
```

**`CloudProvider` (runtime, configured instance) — three new methods:**

```kotlin
interface CloudProvider {
    // … existing: existing sync/list/upload/etc. methods

    /**
     * Algorithm this provider exposes for post-transfer integrity
     * verification, if any. Returning null means "this provider does
     * not publish a verifiable hash for downloaded objects; skip
     * verification". HashVerifier consults this instead of dispatching
     * by provider id.
     */
    fun hashAlgorithm(): HashAlgorithm? = null

    /**
     * Provider-specific status fields to render in `unidrive status`
     * output, in addition to the shared fields (quota, tracked files,
     * etc.). Empty list = no extra fields (the default).
     */
    fun statusFields(): List<StatusField> = emptyList()

    /**
     * Optional warning to surface when relocating large data INTO
     * this provider. `planSize` is the total byte count being moved.
     * Returning null means "no provider-specific warning". Used by
     * `relocate` to flag known transport ceilings (e.g. nginx-mod_dav
     * throughput) at plan time.
     */
    fun transportWarning(planSize: Long): String? = null
}
```

### 3.2 Supporting types

Three new tiny types live in `:app:core` next to `ProviderFactory`:

```kotlin
// Used by ProviderFactory.credentialPrompts()
data class PromptSpec(
    /** Config-key this prompt populates (e.g. "bucket", "host"). */
    val key: String,
    /** Human-readable label shown to the user (e.g. "S3 bucket"). */
    val label: String,
    /** True for password-style (no echo), false for free-text. */
    val isMasked: Boolean = false,
    /** Optional default suggested in the prompt (e.g. "auto"). */
    val default: String? = null,
    /** Whether the user MUST supply a value (true) or may skip (false). */
    val required: Boolean = true,
)

// Used by CloudProvider.statusFields()
data class StatusField(
    /** Field label as shown in status output. */
    val label: String,
    /** Field value, already formatted for display. */
    val value: String,
)

// Used by CloudProvider.hashAlgorithm()
sealed class HashAlgorithm {
    /** OneDrive's QuickXorHash (Base64). */
    object QuickXor : HashAlgorithm()
    /** Plain MD5 hex (matches simple S3 ETags; multipart ETags must be skipped). */
    object Md5Hex : HashAlgorithm()
    /** SHA-256 hex. Reserved for future providers. */
    object Sha256Hex : HashAlgorithm()
}
```

`HashVerifier` becomes a pure-utility helper that, given a
`HashAlgorithm` and a path + remote-hash string, returns true/false.
No provider-name knowledge.

### 3.3 Migration of the 7 sites

| Site | Before | After |
|---|---|---|
| `HashVerifier.kt:24-28` | `when(providerId) { "onedrive" → … }` | `verifier.verify(provider.hashAlgorithm(), localPath, remoteHash)` |
| `ProfileCommand.kt:115-144` | Three hardcoded `when` arms with prompts | `factory.credentialPrompts().forEach { prompt(it) }` |
| `ProfileCommand.kt:157` | `if (type in setOf("onedrive")) println("  Next: run … auth")` | `if (factory.supportsInteractiveAuth()) println("  Next: run … auth")` — same capability used by AuthTool migration; one source of truth |
| `Main.kt:355-365` | Hardcoded env-var maps per `when(type)` | `factory.envVarMappings().forEach { (env, key) → check(env, key) }` |
| `AuthTool.kt:80` | `if (type != "onedrive") return error` | `if (!factory.supportsInteractiveAuth()) return error` (with a clearer message: "Provider 'X' does not support interactive auth") |
| `StatusCommand.kt:165` | `if (type == "onedrive") includeShared = …` | See §3.3.1 below — multi-file change, not a one-line swap |
| `RelocateCommand.kt:191` | `if (toProvider.id == "webdav" && size > 50 GiB) warn(...)` | `toProvider.transportWarning(size)?.let { warn(it) }` (WebDAV implements its own 50 GiB nginx-mod_dav check) |

#### 3.3.1 StatusCommand / AuditReport / StatusAudit migration (multi-file)

The `includeShared` field is not a one-line swap because the
rendering chain is typed end-to-end:

- **`StatusAudit.kt:26`** — `data class AuditReport` has a dedicated
  `val includeShared: Boolean?` field.
- **`StatusAudit.kt:122-124`** — renderer prints
  `"Include shared:   ${report.includeShared}"` with a typed label.
- **`StatusAuditTest.kt`** — five fixtures pass `includeShared`
  explicitly.
- **`StatusCommand.kt:164-169`** — `buildAuditReport` computes the
  value separately (from `profile.rawProvider?.include_shared`)
  before constructing the report.

Migration shape (chosen to preserve the testable-pure-data pattern):

1. **Add `extraFields: List<StatusField> = emptyList()` to `AuditReport`.**
   Keep `includeShared: Boolean?` for one release as `@Deprecated`
   alias that delegates to `extraFields.find { it.label == "Include shared" }?.value?.toBoolean()`.
   *Actually — no.* This is a v0.0.x preview project; deprecation
   ladders aren't owed. Replace `includeShared` outright with
   `extraFields: List<StatusField>`.
2. **Rewrite renderer** to iterate `extraFields` after the fixed
   fields; each entry renders as `"${field.label}:".padEnd(18) + field.value`.
3. **Update `buildAuditReport`** to collect extras from
   `provider.statusFields()` instead of computing `includeShared`
   inline. The `provider.statusFields()` call is on the
   already-instantiated `CloudProvider`, so OneDrive's instance
   reads its own `includeShared` flag (received at `create()` time
   from `properties["include_shared"]`).
4. **Update `StatusAuditTest.kt`** — the five fixtures that pass
   `includeShared` become fixtures that pass
   `extraFields = listOf(StatusField("Include shared", "true"))`
   (or empty list for the negative cases). Test count and assertions
   per fixture unchanged in shape; only the field name and one
   constructor arg shift.
5. **OneDrive provider** owns its `includeShared: Boolean` constructor
   arg (already does today via `OneDriveProviderFactory.create()` reading
   `properties["include_shared"]`); its `statusFields()` returns
   `listOf(StatusField("Include shared", includeShared.toString()))`
   when the flag was explicitly configured, empty list otherwise.

This is still a focused change (one data class field, one renderer,
one test file, one provider) — but it is **5 file edits, not 1**.
Implementation plan must call this out.

#### 3.3.2 Anti-regression grep result expectation

After migration, **`grep -nE '"(localfs\|onedrive\|rclone\|s3\|sftp\|webdav\|internxt)"' core/app/{cli,mcp,sync}/src/main/`** should return only:
- string-literal CONSTANTS in factory-internal code (none expected),
- the deliberate fallback default `"onedrive"` in `SyncConfig.kt`
  (covered by separate ticket),
- legitimate doc-string examples (e.g. `ProviderFactory.kt:13`
  comment).

### 3.4 Per-provider migration

For each of the 7 in-tree providers (`localfs`, `s3`, `sftp`, `webdav`,
`onedrive`, `rclone`, `internxt`), implement only the new methods that
are non-default:

| Provider | New overrides |
|---|---|
| `localfs` | none — defaults are correct (no creds, no env, no interactive auth, no hash from filesystem, no status extras, no transport warning) |
| `s3` | `credentialPrompts()` (5 prompts), `envVarMappings()` (3 entries), `hashAlgorithm()` returns `Md5Hex` |
| `sftp` | `credentialPrompts()` (5 prompts), `envVarMappings()` (3 entries) |
| `webdav` | `credentialPrompts()` (3 prompts), `envVarMappings()` (3 entries), `transportWarning()` (50 GiB nginx-mod_dav check) |
| `onedrive` | `supportsInteractiveAuth()` returns true, `hashAlgorithm()` returns `QuickXor`, `statusFields()` returns the "shared folders" entry |
| `rclone` | `envVarMappings()` (1 entry: `RCLONE_REMOTE`) |
| `internxt` | (none in this PR — Internxt's interactive-auth status is governed by UD-354 verification, out of scope) |

### 3.5 Backwards compatibility

Per the brainstorm decision (option A): every new method has a
default implementation in the interface. Provider implementors only
override what they need. Out-of-tree providers (none known to exist,
but the SPI is public) compile unchanged.

The one exception called out in the brainstorm — `verifyHash`'s
"silent pass on missing implementation" footgun — is dodged by
phrasing the capability as `hashAlgorithm(): HashAlgorithm?` instead
of `verifyHash(...): Boolean`. The default `null` means "I have no
hash to verify against; skip" — which is explicit, not silent.

## 4. Test plan

### 4.1 Per-capability tests on the contract

`ProviderFactoryContractTest` (new, `:app:core` test sources):

- For each in-tree provider, snapshot `credentialPrompts()` /
  `envVarMappings()` / `supportsInteractiveAuth()` and assert
  expected shape. Catches accidental schema drift.

`CloudProviderContractTest` (new, `:app:core` test sources, uses
fakes — no live calls):

- For each capability that has a provider override, assert the
  override differs from the default.

### 4.2 Tests adapted / replaced

- `CommandRegistrationTest > ServiceLoader discovers all in-tree providers`
  — unchanged, still asserts SPI registration invariant.
- `ProviderInfoCommandTest > knownTypes contains every runtime ServiceLoader provider`
  — unchanged.
- The `ProfileCommand` "wizard for type X" implicit test surface
  becomes a parametric test: for every provider that returns
  non-empty `credentialPrompts()`, the wizard issues the right
  number of prompts. Catches "added a provider, forgot to wire it
  into the wizard" — which the current `when` arm does not catch.
- `HashVerifier` becomes algorithm-parameterised; existing test
  fixtures stay; one new test asserts that `null` algorithm means
  "skip verification" (matches today's "else → true" behaviour).

### 4.3 Smoke gate

After migration, run:

```bash
./gradlew build test
```

Must report 0 failures. Same baseline as the previous session
(1450 tests / 0 failures / 0 errors / 9 skipped across 143 classes).

### 4.4 Anti-regression grep

Add to `scripts/ci/check-boundaries.sh` (or a new
`scripts/ci/check-no-provider-string-dispatch.sh`):

```bash
hits=$(grep -rnE '"(localfs|onedrive|rclone|s3|sftp|webdav|internxt)"' \
    core/app/{cli,mcp,sync}/src/main/ \
    | grep -v '// allow:' || true)
test -z "$hits" || { echo "$hits"; exit 1; }
```

`// allow: <reason>` is the explicit opt-out marker for the deliberate
exceptions (e.g. the `SyncConfig.kt` historical default). New
provider-name dispatch fails CI loudly.

## 5. Risks & failure modes

- **Risk:** `picocli` annotation literals in `ProviderCommand.kt`
  cannot reference `ProviderRegistry` at class-construction time.
  Out of scope for this spec; covered by a separate ticket
  (filing it as part of wave 2).
- **Risk:** Test-double providers in
  `:app:sync:test` rely on the old method shapes.
  Mitigation: those test-doubles override only what their tests need;
  defaults cover the rest. Run `./gradlew :app:sync:test` early in
  the migration to catch any breakage.
- **Risk:** Out-of-tree providers (none known) break.
  Mitigation: every new method has a default implementation. The
  contract widening is source-compatible.
- **Risk:** `WebDAV.transportWarning()` keeps the 50 GiB threshold
  inside `:providers:webdav` where it's appropriate, but the
  helpful "consider sftp / rclone-native" message in the original
  text mentions sibling providers — that is, the current message
  knows about *other* providers' existence. The migration preserves
  the message verbatim; the residual provider-name knowledge in
  the **message string** is acceptable (it's user-facing copy, not
  dispatch logic).

## 6. Out-of-scope follow-ups (filed as separate tickets in wave 2)

These come from the same audit but are not in this PR's scope.
Filing wave 2 happens after this spec is approved.

- **`ProviderCommand.kt` picocli description drift** — the literal
  `"hidrive, internxt, localfs, ..."` in `@Command(description=...)`
  can't see the registry. Needs custom help-renderer or a CI
  parity test. (Will get its own UD-### in wave 2.)
- **`ProviderRegistry.defaultTypes` + `isKnownType`** — dead-code +
  silent-fallback hazard. (Will get its own UD-### in wave 2.)
- **`SyncConfig.kt` 9× `"onedrive"` historical default** — separate
  ADR-or-ticket on what the canonical default should be. (Will
  get its own UD-### in wave 2.)
- **`docs/config-schema/config.{example.toml,schema.json}` HiDrive
  residue** — completion of HiDrive archival. (Will get its own
  UD-### in wave 2.)
- **`core/docker/Dockerfile:35` `provider-hidrive/` COPY** — broken
  Docker build line. (Will get its own UD-### in wave 2 or fix
  inline before this PR merges if scope allows.)
- **`core/check-docs.sh:45` and `scripts/backlog-sync.kts:30`** —
  hardcoded provider lists / hardcoded scan roots. (Wave 2.)

## 7. Acceptance criteria

- [ ] All 6 critical sites use SPI capability methods, not string-
      equality on provider id.
- [ ] All 7 in-tree providers compile and pass their existing
      `:providers:*:test` suites.
- [ ] `./gradlew build test` reports 0 failures.
- [ ] `CommandRegistrationTest > ServiceLoader discovers all in-tree
      providers` and
      `ProviderInfoCommandTest > knownTypes contains every runtime
      ServiceLoader provider` still pass.
- [ ] Anti-regression grep (§4.4) passes.
- [ ] No public API removed from `CloudProvider` or
      `ProviderFactory` (additive only).
- [ ] Spec status moves Draft → Done with implementation notes
      appended.

## 8. Out-of-band

This work goes on `refactor/provider-spi-contract` off `dev` off
`main`. PR target: `dev`, not `main`. `dev` is the new long-lived
integration branch; promotion `dev` → `main` is the user's call.
