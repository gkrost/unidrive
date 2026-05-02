# Provider SPI Contract Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the 7 provider-name string-equality dispatch sites in `:app:cli`, `:app:mcp`, `:app:sync` by extending the `ProviderFactory` and `CloudProvider` SPI surfaces with capability methods, then migrating each consumer to consult the SPI instead of hardcoding provider knowledge.

**Architecture:** Six new capability methods split by lifecycle — three on `ProviderFactory` (factory-time: `credentialPrompts`, `envVarMappings`, `supportsInteractiveAuth`) and three on `CloudProvider` (runtime: `hashAlgorithm`, `statusFields`, `transportWarning`). All have default implementations so the SPI widening is source-compatible. Three new tiny supporting types (`PromptSpec`, `StatusField`, `HashAlgorithm`) live in `:app:core`.

**Tech Stack:** Kotlin 2.3.21, Gradle composite build at `core/`, JUnit 4 + kotlin-test, ServiceLoader-discovered SPI.

**Spec:** [`docs/specs/2026-05-02-provider-spi-contract-extension-design.md`](../specs/2026-05-02-provider-spi-contract-extension-design.md) v0.2.0.

**Branch:** `refactor/provider-spi-contract` off `dev` off `main`. PR target: `dev`.

---

## File Structure

| Path | Action | Responsibility |
|---|---|---|
| `core/app/core/src/main/kotlin/org/krost/unidrive/PromptSpec.kt` | Create | One-line config-prompt schema for the `profile add` wizard |
| `core/app/core/src/main/kotlin/org/krost/unidrive/StatusField.kt` | Create | Generic key/value pair for provider-contributed `status` extras |
| `core/app/core/src/main/kotlin/org/krost/unidrive/HashAlgorithm.kt` | Create | Sealed class enumerating known integrity-check algorithms |
| `core/app/core/src/main/kotlin/org/krost/unidrive/ProviderFactory.kt` | Modify | Add 3 default-implementation methods |
| `core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt` | Modify | Add 3 default-implementation methods |
| `core/providers/{s3,sftp,webdav,onedrive,rclone}/.../*ProviderFactory.kt` | Modify | Override capability methods (per §3.4 of spec) |
| `core/providers/{s3,sftp,webdav,onedrive,rclone}/.../*Provider.kt` | Modify | Override runtime capability methods (where applicable) |
| `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/HashVerifier.kt` | Rewrite | Algorithm-driven, no provider-name knowledge |
| `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ProfileCommand.kt` | Modify | Replace 2 dispatch sites (lines 115-144 + 157) with SPI calls |
| `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt` | Modify | Replace env-var dispatch (lines 355-365) with SPI call |
| `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt` | Modify | Replace `includeShared` block (line 165) with `provider.statusFields()` |
| `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusAudit.kt` | Modify | `AuditReport` field swap + renderer iterates `extraFields` |
| `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt` | Modify | Replace WebDAV check (line 191) with `provider.transportWarning()` |
| `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt` | Modify | Replace OneDrive check (line 80) with `factory.supportsInteractiveAuth()` |
| `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StatusAuditTest.kt` | Modify | 5 fixtures: `includeShared` arg → `extraFields` arg |
| `core/app/core/src/test/kotlin/org/krost/unidrive/ProviderFactoryContractTest.kt` | Create | Per-provider snapshot of `credentialPrompts`/`envVarMappings`/`supportsInteractiveAuth` |
| `core/app/core/src/test/kotlin/org/krost/unidrive/CloudProviderContractTest.kt` | Create | Per-provider check that overrides differ from defaults where expected |
| `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/HashVerifierTest.kt` | Modify (or create if absent) | Algorithm-parameterised; null-algorithm test |
| `scripts/ci/check-no-provider-string-dispatch.sh` | Create | Anti-regression grep for new violations |
| `.github/workflows/build.yml` | Modify | Wire the new check script into CI |

**Order rationale:** Tasks 1-3 build the contract surface (types + interfaces); Task 4 implements the SPI extensions on each in-tree provider before consumers touch them, so each consumer migration in Tasks 5-11 has a working backend. Task 12 adds the CI guard last so it doesn't fire false positives mid-migration. Task 13 is the spec-completion update.

---

## Task 1: Add `HashAlgorithm` sealed class

**Files:**
- Create: `core/app/core/src/main/kotlin/org/krost/unidrive/HashAlgorithm.kt`

- [ ] **Step 1: Create the file with the three known algorithms**

```kotlin
package org.krost.unidrive

/**
 * Algorithms the SPI exposes for post-transfer integrity verification.
 *
 * A provider returns one of these from [CloudProvider.hashAlgorithm]
 * to declare which hash format its `remoteHash` strings carry.
 * Returning null from `hashAlgorithm()` means the provider has no
 * verifiable hash; callers MUST treat that as "skip verification"
 * rather than "verification passed".
 *
 * Add new variants here when a new provider needs an algorithm not
 * already represented.
 */
sealed class HashAlgorithm {
    /** OneDrive's QuickXorHash, encoded Base64. */
    object QuickXor : HashAlgorithm()

    /**
     * Plain MD5, lowercase hex. Matches simple S3 ETags. Multipart
     * S3 ETags (containing `-`) cannot be verified this way and
     * callers must skip verification when the remote hash matches
     * `<hex>-<n>`.
     */
    object Md5Hex : HashAlgorithm()

    /** SHA-256, lowercase hex. Reserved for future providers. */
    object Sha256Hex : HashAlgorithm()
}
```

- [ ] **Step 2: Verify the file compiles in isolation**

Run from `core/`:
```bash
./gradlew :app:core:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/app/core/src/main/kotlin/org/krost/unidrive/HashAlgorithm.kt
git commit -m "feat(spi): add HashAlgorithm sealed class for integrity-check declaration"
```

---

## Task 2: Add `PromptSpec` and `StatusField` data classes

**Files:**
- Create: `core/app/core/src/main/kotlin/org/krost/unidrive/PromptSpec.kt`
- Create: `core/app/core/src/main/kotlin/org/krost/unidrive/StatusField.kt`

- [ ] **Step 1: Create `PromptSpec.kt`**

```kotlin
package org.krost.unidrive

/**
 * One prompt issued by the `profile add` wizard.
 *
 * Returned in order from [ProviderFactory.credentialPrompts]; the
 * CLI iterates the list and asks each in sequence, populating the
 * `properties` map under [key] before calling [ProviderFactory.create].
 */
data class PromptSpec(
    /** Config-key this prompt populates (e.g. "bucket", "host"). */
    val key: String,
    /** Human-readable label shown to the user (e.g. "S3 bucket"). */
    val label: String,
    /** True for password-style (no echo); false for free-text. */
    val isMasked: Boolean = false,
    /** Optional default suggested in the prompt (e.g. "auto"). */
    val default: String? = null,
    /** Whether the user MUST supply a value (true) or may skip (false). */
    val required: Boolean = true,
)
```

- [ ] **Step 2: Create `StatusField.kt`**

```kotlin
package org.krost.unidrive

/**
 * Provider-contributed key/value pair rendered in `unidrive status`
 * output after the shared fields.
 *
 * Returned from [CloudProvider.statusFields]. The renderer formats
 * each entry as `"${label}:".padEnd(18) + value`. Provider keeps
 * full control over what appears in its own rows.
 */
data class StatusField(
    /** Field label as shown in status output. */
    val label: String,
    /** Field value, already formatted for display. */
    val value: String,
)
```

- [ ] **Step 3: Verify compile**

```bash
./gradlew :app:core:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/app/core/src/main/kotlin/org/krost/unidrive/PromptSpec.kt \
        core/app/core/src/main/kotlin/org/krost/unidrive/StatusField.kt
git commit -m "feat(spi): add PromptSpec + StatusField for capability return shapes"
```

---

## Task 3: Extend `ProviderFactory` and `CloudProvider` interfaces

**Files:**
- Modify: `core/app/core/src/main/kotlin/org/krost/unidrive/ProviderFactory.kt`
- Modify: `core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt`

- [ ] **Step 1: Add 3 default-implementation methods to `ProviderFactory`**

Edit `core/app/core/src/main/kotlin/org/krost/unidrive/ProviderFactory.kt`. After the existing `describeConnection` method (currently the last method in the interface body, ending with `} = "$id provider"`), add **before** the closing `}` of the interface:

```kotlin
    /**
     * Schema for the interactive 'profile add' wizard. Each entry
     * describes one prompt the CLI should issue. Empty list (the
     * default) means this provider has no provider-specific prompts —
     * the wizard collects only the universal name + sync_root + type.
     *
     * Order matters: prompts are issued in list order. Implementations
     * with dependent prompts (e.g. "host" before "port") must order
     * accordingly.
     */
    fun credentialPrompts(): List<PromptSpec> = emptyList()

    /**
     * Mapping of environment-variable name to config-property key for
     * credentials this provider can pick up from the environment.
     *
     * Used by the CLI to warn when an env var is set but the matching
     * config key is also present in `config.toml` (env is ignored in
     * that case). Empty (the default) means this provider does not
     * recognise any environment variables.
     */
    fun envVarMappings(): Map<String, String> = emptyMap()

    /**
     * Whether this provider has an interactive auth flow (typically
     * OAuth) that the CLI `auth` subcommand and the MCP
     * `auth_begin` / `auth_complete` tools should drive.
     *
     * Default false: most providers receive credentials via config
     * and have no interactive begin/complete handshake.
     */
    fun supportsInteractiveAuth(): Boolean = false
```

- [ ] **Step 2: Add 3 default-implementation methods to `CloudProvider`**

Edit `core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt`. Insert before the closing `}` of the `CloudProvider` interface body:

```kotlin
    /**
     * Algorithm this provider uses for `remoteHash` strings. Returning
     * null means the provider has no verifiable hash; callers MUST
     * treat that as "skip verification" rather than "verification
     * passed". Default null.
     */
    fun hashAlgorithm(): HashAlgorithm? = null

    /**
     * Provider-specific status fields to render in `unidrive status`
     * after the shared fields (quota, tracked files, etc.). Empty
     * list (the default) means this provider contributes no extras.
     */
    fun statusFields(): List<StatusField> = emptyList()

    /**
     * Optional warning surfaced when relocating large data INTO this
     * provider. `planSize` is the total byte count being moved.
     * Returning null (the default) means "no provider-specific
     * warning". Used by `relocate` to flag known transport ceilings
     * (e.g. WebDAV's nginx-mod_dav throughput cliff).
     */
    fun transportWarning(planSize: Long): String? = null
```

- [ ] **Step 3: Verify compile (existing 7 providers should still build because all new methods have defaults)**

```bash
./gradlew :app:core:compileKotlin :providers:s3:compileKotlin :providers:sftp:compileKotlin :providers:webdav:compileKotlin :providers:onedrive:compileKotlin :providers:rclone:compileKotlin :providers:localfs:compileKotlin :providers:internxt:compileKotlin
```
Expected: BUILD SUCCESSFUL for all 8 modules.

- [ ] **Step 4: Verify the wider build still succeeds with no test changes**

```bash
./gradlew build test 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL. Test count must remain at the current baseline (1450 passing / 0 failures). The default implementations preserve all current behaviour.

- [ ] **Step 5: Commit**

```bash
git add core/app/core/src/main/kotlin/org/krost/unidrive/ProviderFactory.kt \
        core/app/core/src/main/kotlin/org/krost/unidrive/CloudProvider.kt
git commit -m "feat(spi): add 6 capability methods to ProviderFactory + CloudProvider

All methods have default implementations so existing providers and
out-of-tree implementors compile unchanged. No behaviour change yet —
consumers still dispatch by string equality. Subsequent tasks
override per-provider and migrate each consumer."
```

---

## Task 4: Add S3 + SFTP + WebDAV credential prompts and env-var mappings

**Files:**
- Modify: `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ProviderFactory.kt`
- Modify: `core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProviderFactory.kt`
- Modify: `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt`

This task adds the three factory-time capabilities for the providers that currently have wizard logic in `ProfileCommand.kt`. Migration of `ProfileCommand` itself happens in Task 7.

- [ ] **Step 1: Add overrides to `S3ProviderFactory`**

In `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ProviderFactory.kt`, add this block before the closing `}` of the class body:

```kotlin
    override fun credentialPrompts(): List<org.krost.unidrive.PromptSpec> =
        listOf(
            org.krost.unidrive.PromptSpec(key = "bucket", label = "S3 bucket"),
            org.krost.unidrive.PromptSpec(key = "region", label = "S3 region", default = "auto", required = false),
            org.krost.unidrive.PromptSpec(
                key = "endpoint",
                label = "S3 endpoint",
                default = "https://s3.amazonaws.com",
                required = false,
            ),
            org.krost.unidrive.PromptSpec(key = "access_key_id", label = "Access key ID"),
            org.krost.unidrive.PromptSpec(key = "secret_access_key", label = "Secret access key", isMasked = true),
        )

    override fun envVarMappings(): Map<String, String> =
        mapOf(
            "S3_BUCKET" to "bucket",
            "AWS_ACCESS_KEY_ID" to "access_key_id",
            "AWS_SECRET_ACCESS_KEY" to "secret_access_key",
        )
```

- [ ] **Step 2: Add overrides to `SftpProviderFactory`**

In `core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProviderFactory.kt`, add before the closing `}` of the class body:

```kotlin
    override fun credentialPrompts(): List<org.krost.unidrive.PromptSpec> =
        listOf(
            org.krost.unidrive.PromptSpec(key = "host", label = "SFTP host"),
            org.krost.unidrive.PromptSpec(key = "port", label = "Port", default = "22", required = false),
            org.krost.unidrive.PromptSpec(
                key = "user",
                label = "Username",
                default = System.getProperty("user.name") ?: "root",
                required = false,
            ),
            org.krost.unidrive.PromptSpec(key = "remote_path", label = "Remote path", default = "", required = false),
            org.krost.unidrive.PromptSpec(
                key = "identity",
                label = "Identity file",
                default = "~/.ssh/id_ed25519",
                required = false,
            ),
        )

    override fun envVarMappings(): Map<String, String> =
        mapOf(
            "SFTP_HOST" to "host",
            "SFTP_USER" to "user",
            "SFTP_PASSWORD" to "password",
        )
```

- [ ] **Step 3: Add overrides to `WebDavProviderFactory`**

In `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt`, add before the closing `}` of the class body:

```kotlin
    override fun credentialPrompts(): List<org.krost.unidrive.PromptSpec> =
        listOf(
            org.krost.unidrive.PromptSpec(key = "url", label = "WebDAV URL"),
            org.krost.unidrive.PromptSpec(key = "user", label = "Username"),
            org.krost.unidrive.PromptSpec(key = "password", label = "Password", isMasked = true),
        )

    override fun envVarMappings(): Map<String, String> =
        mapOf(
            "WEBDAV_URL" to "url",
            "WEBDAV_USER" to "user",
            "WEBDAV_PASSWORD" to "password",
        )
```

- [ ] **Step 4: Verify compile + tests still pass**

```bash
./gradlew :providers:s3:test :providers:sftp:test :providers:webdav:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL. No test changes — these are additive overrides.

- [ ] **Step 5: Commit**

```bash
git add core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3ProviderFactory.kt \
        core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpProviderFactory.kt \
        core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProviderFactory.kt
git commit -m "feat(s3,sftp,webdav): override credentialPrompts + envVarMappings

Lifts the prompt schema and env-var mapping from the CLI's
hardcoded when() arms into the providers themselves. Consumer
migration (ProfileCommand, Main.kt env-var warner) follows in
later tasks."
```

---

## Task 5: Add OneDrive + Rclone factory overrides

**Files:**
- Modify: `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProviderFactory.kt`
- Modify: `core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProviderFactory.kt`

- [ ] **Step 1: Add `supportsInteractiveAuth` override to `OneDriveProviderFactory`**

In `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProviderFactory.kt`, add before the closing `}` of the class body:

```kotlin
    override fun supportsInteractiveAuth(): Boolean = true
```

- [ ] **Step 2: Add `envVarMappings` override to `RcloneProviderFactory`**

In `core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProviderFactory.kt`, add before the closing `}` of the class body:

```kotlin
    override fun envVarMappings(): Map<String, String> =
        mapOf("RCLONE_REMOTE" to "rclone_remote")
```

- [ ] **Step 3: Verify**

```bash
./gradlew :providers:onedrive:test :providers:rclone:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProviderFactory.kt \
        core/providers/rclone/src/main/kotlin/org/krost/unidrive/rclone/RcloneProviderFactory.kt
git commit -m "feat(onedrive,rclone): override supportsInteractiveAuth + envVarMappings

OneDrive: declares the OAuth begin/complete capability that
AuthTool currently gates by string match.
Rclone: declares its single env var (RCLONE_REMOTE)."
```

---

## Task 6: Add `hashAlgorithm`, `statusFields`, `transportWarning` overrides on runtime providers

**Files:**
- Modify: `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProvider.kt`
- Modify: `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt`
- Modify: `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt`

- [ ] **Step 1: Add `hashAlgorithm` and `statusFields` to `OneDriveProvider`**

In `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProvider.kt`, add inside the `OneDriveProvider` class body:

```kotlin
    override fun hashAlgorithm(): org.krost.unidrive.HashAlgorithm =
        org.krost.unidrive.HashAlgorithm.QuickXor

    override fun statusFields(): List<org.krost.unidrive.StatusField> =
        listOf(
            org.krost.unidrive.StatusField(
                label = "Include shared",
                value = config.includeShared.toString(),
            ),
        )
```

- [ ] **Step 2: Add `hashAlgorithm` to `S3Provider`**

In `core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt`, add inside the class body:

```kotlin
    override fun hashAlgorithm(): org.krost.unidrive.HashAlgorithm =
        org.krost.unidrive.HashAlgorithm.Md5Hex
```

- [ ] **Step 3: Add `transportWarning` to `WebDavProvider`**

In `core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt`, add inside the class body:

```kotlin
    override fun transportWarning(planSize: Long): String? {
        val fiftyGiB = 50L * 1024 * 1024 * 1024
        if (planSize <= fiftyGiB) return null
        return "Plan size ${formatSize(planSize)} exceeds 50 GiB on a WebDAV target. " +
            "Throughput ceiling on nginx-mod_dav is typically < 30 MiB/s LAN; " +
            "expect this to take many hours. Consider sftp / rclone-native if the " +
            "same NAS exposes them."
    }

    private fun formatSize(bytes: Long): String {
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

(The `formatSize` helper is duplicated from `RelocateCommand`'s private helper — same shape — so the warning string matches the current user-facing format. We accept the duplication for this PR; a follow-up could lift `formatSize` into `:app:core/io` if it surfaces in more places.)

- [ ] **Step 4: Verify compile + tests**

```bash
./gradlew :providers:onedrive:test :providers:s3:test :providers:webdav:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProvider.kt \
        core/providers/s3/src/main/kotlin/org/krost/unidrive/s3/S3Provider.kt \
        core/providers/webdav/src/main/kotlin/org/krost/unidrive/webdav/WebDavProvider.kt
git commit -m "feat(onedrive,s3,webdav): override hashAlgorithm, statusFields, transportWarning

OneDrive: QuickXor + Include-shared status row.
S3: Md5Hex (matches non-multipart ETags).
WebDAV: 50 GiB nginx-mod_dav transport warning, message text
preserved verbatim from RelocateCommand."
```

---

## Task 7: Migrate `ProfileCommand` (lines 115-144 + 157)

**Files:**
- Modify: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ProfileCommand.kt`

- [ ] **Step 1: Read the surrounding context**

Open `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ProfileCommand.kt`. The two dispatch sites are at lines ~115-144 (`when (type) { "s3" -> ... "sftp" -> ... "webdav" -> ... }` populating `creds`) and line ~157 (`if (type in setOf("onedrive")) { println("  Next: run ...") }`).

- [ ] **Step 2: Replace the wizard `when` block (lines ~115-144)**

Locate the block:

```kotlin
        // Step 4: Credential prompts per type
        val creds = mutableMapOf<String, String>()
        when (type) {
            "s3" -> {
                creds["bucket"] = promptRequired(console, "S3 bucket")
                // … etc
            }
            "sftp" -> { /* … */ }
            "webdav" -> { /* … */ }
        }
```

Replace it with:

```kotlin
        // Step 4: Credential prompts per type — driven by SPI capability
        val creds = mutableMapOf<String, String>()
        val factory = org.krost.unidrive.ProviderRegistry.get(type)
            ?: error("ProviderRegistry returned null for type=$type after resolveId; impossible state.")
        for (prompt in factory.credentialPrompts()) {
            val value = when {
                prompt.isMasked -> String(console.readPassword("${prompt.label}: ") ?: charArrayOf())
                prompt.default != null -> promptOptional(console, prompt.label, prompt.default)
                prompt.required -> promptRequired(console, prompt.label)
                else -> promptOptional(console, prompt.label, "")
            }
            if (prompt.required && value.isBlank()) {
                System.err.println("Error: ${prompt.label} is required.")
                System.exit(1)
            }
            // Don't write empty optional values into config
            if (value.isNotBlank()) {
                creds[prompt.key] = value
            }
        }
```

- [ ] **Step 3: Replace the line-157 post-add hint**

Locate:

```kotlin
        if (type in setOf("onedrive")) {
            println("  Next: run ${AnsiHelper.bold("unidrive -p $name auth")}")
        }
```

Replace with:

```kotlin
        if (factory.supportsInteractiveAuth()) {
            println("  Next: run ${AnsiHelper.bold("unidrive -p $name auth")}")
        }
```

(Reuses the `factory` local introduced in Step 2.)

- [ ] **Step 4: Verify compile**

```bash
./gradlew :app:cli:compileKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run :app:cli:test**

```bash
./gradlew :app:cli:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 0 failures. (No test exercises the wizard interactively today; the SPI delegation is behaviour-preserving for s3/sftp/webdav, and now also extends correctly to providers that previously got no prompts.)

- [ ] **Step 6: Commit**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ProfileCommand.kt
git commit -m "refactor(cli): drive ProfileCommand wizard from ProviderFactory.credentialPrompts

Replaces two dispatch sites:
- Lines ~115-144: hardcoded when(type) { 's3' -> ... 'sftp' -> ...
  'webdav' -> ... } collecting creds → for-loop over
  factory.credentialPrompts().
- Line ~157: if (type in setOf('onedrive')) post-add hint →
  if (factory.supportsInteractiveAuth()).

Both sites now consult the SPI; adding an 8th provider with its own
prompts no longer requires editing this file."
```

---

## Task 8: Migrate `Main.kt` env-var warning (lines 355-365)

**Files:**
- Modify: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt`

- [ ] **Step 1: Locate the dispatch block**

Around line 355, the function builds an `envMappings` map by `when(type)`:

```kotlin
        val envMappings =
            when (type) {
                "s3" -> mapOf("S3_BUCKET" to "bucket", "AWS_ACCESS_KEY_ID" to "access_key_id", "AWS_SECRET_ACCESS_KEY" to "secret_access_key")
                "sftp" -> mapOf("SFTP_HOST" to "host", "SFTP_USER" to "user", "SFTP_PASSWORD" to "password")
                "webdav" -> mapOf("WEBDAV_URL" to "url", "WEBDAV_USER" to "user", "WEBDAV_PASSWORD" to "password")
                "rclone" -> mapOf("RCLONE_REMOTE" to "rclone_remote")
                else -> emptyMap()
            }
```

- [ ] **Step 2: Replace with SPI call**

Replace the entire `val envMappings = when (type) { ... }` block with:

```kotlin
        val envMappings = org.krost.unidrive.ProviderRegistry.get(type)?.envVarMappings() ?: emptyMap()
```

- [ ] **Step 3: Verify compile**

```bash
./gradlew :app:cli:compileKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:cli:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt
git commit -m "refactor(cli): drive env-var warner from ProviderFactory.envVarMappings

One-line replacement of the hardcoded when(type) env-var map with
ProviderRegistry.get(type)?.envVarMappings(). Same behaviour for
s3/sftp/webdav/rclone; future providers contribute their own envs."
```

---

## Task 9: Migrate `AuthTool` (line 80)

**Files:**
- Modify: `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt`

- [ ] **Step 1: Locate the gate**

Around line 80 in `handleAuthBegin`:

```kotlin
    if (ctx.profileInfo.type != "onedrive") {
        return buildToolResult(
            "unidrive_auth_begin currently supports only provider type 'onedrive' " +
                "(got '${ctx.profileInfo.type}').",
            isError = true,
        )
    }
```

- [ ] **Step 2: Replace with SPI capability check**

Replace with:

```kotlin
    val factory = org.krost.unidrive.ProviderRegistry.get(ctx.profileInfo.type)
    if (factory == null || !factory.supportsInteractiveAuth()) {
        return buildToolResult(
            "Provider '${ctx.profileInfo.type}' does not support interactive auth " +
                "(unidrive_auth_begin / unidrive_auth_complete).",
            isError = true,
        )
    }
```

- [ ] **Step 3: Same gate for `handleAuthComplete`**

Search the file for the symmetrical block in `handleAuthComplete` (likely a few methods below `handleAuthBegin`). Replace the same `if (ctx.profileInfo.type != "onedrive")` pattern with the same `factory.supportsInteractiveAuth()` check, adjusting only the tool-name in the error message (`unidrive_auth_complete` instead of `unidrive_auth_begin`). If `handleAuthComplete` does NOT have a symmetrical guard, do not add one — record the absence as a finding.

- [ ] **Step 4: Verify compile**

```bash
./gradlew :app:mcp:compileKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run tests**

```bash
./gradlew :app:mcp:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt
git commit -m "refactor(mcp): drive AuthTool gate from ProviderFactory.supportsInteractiveAuth

Replaces the type != 'onedrive' check in handleAuthBegin (and
handleAuthComplete if symmetrical) with a capability call. Future
OAuth-style providers no longer need editing this file to be
recognised."
```

---

## Task 10: Migrate `RelocateCommand` (line 191)

**Files:**
- Modify: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt`

- [ ] **Step 1: Locate the WebDAV check**

Around line 191:

```kotlin
        if (!confirmTransport && toProviderObj.id == "webdav") {
            val fiftyGiB = 50L * 1024 * 1024 * 1024
            val warnings = mutableListOf<String>()
            if (sourceSize > fiftyGiB) {
                warnings.add(
                    "Plan size ${formatSize(sourceSize)} exceeds 50 GiB on a WebDAV target. " +
                        "Throughput ceiling on nginx-mod_dav is typically < 30 MiB/s LAN; " +
                        "expect this to take many hours. Consider sftp / rclone-native if the " +
                        "same NAS exposes them.",
                )
            }
            // … (rest of the warnings handling)
        }
```

- [ ] **Step 2: Replace the conditional + warning construction with the SPI call**

Replace the `if (!confirmTransport && toProviderObj.id == "webdav") { ... if (sourceSize > fiftyGiB) { warnings.add(...) } ... }` block with:

```kotlin
        if (!confirmTransport) {
            val warnings = mutableListOf<String>()
            toProviderObj.transportWarning(sourceSize)?.let { warnings.add(it) }
            // … (preserve the rest of the surrounding warnings handling exactly as it was)
        }
```

(Concretely: the WebDAV-specific `if (sourceSize > fiftyGiB)` block plus the warning-text inside it are deleted; the warnings list is now populated by the provider's `transportWarning` returning non-null. Whatever code already runs after the warnings list is built — display, abort, or continue — stays unchanged.)

- [ ] **Step 3: Verify compile**

```bash
./gradlew :app:cli:compileKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:cli:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RelocateCommand.kt
git commit -m "refactor(cli): drive RelocateCommand transport warning from CloudProvider

Replaces the hardcoded if (toProvider.id == 'webdav') { 50 GiB
nginx-mod_dav warning } block with toProvider.transportWarning(size).
WebDAV's provider now owns the threshold + message text; future
providers can declare their own transport cliffs without touching
RelocateCommand."
```

---

## Task 11: Migrate `StatusCommand` + `StatusAudit` + `StatusAuditTest` (multi-file, per spec §3.3.1)

**Files:**
- Modify: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusAudit.kt`
- Modify: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt`
- Modify: `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StatusAuditTest.kt`

This task is the multi-file change called out in spec §3.3.1. Five edits in one task because they must land together to keep the build green.

- [ ] **Step 1: In `StatusAudit.kt`, replace the `includeShared` field with `extraFields`**

Change the `data class AuditReport` declaration. Find the line:

```kotlin
    val includeShared: Boolean?,
```

Replace with:

```kotlin
    val extraFields: List<org.krost.unidrive.StatusField> = emptyList(),
```

(The `?` and the explicit `Boolean` go away; the new field has a sensible empty default so callers that don't care don't need to pass it.)

- [ ] **Step 2: In `StatusAudit.kt`, replace the renderer block (lines ~122-124)**

Find:

```kotlin
        if (report.includeShared != null) {
            lines.add("Include shared:   ${report.includeShared}")
        }
```

Replace with:

```kotlin
        for (field in report.extraFields) {
            lines.add("${field.label}:".padEnd(18) + field.value)
        }
```

(`padEnd(18)` is chosen to match the existing fixed-field column width — `"Include shared:   "` is 18 characters including the trailing spaces. Verify by inspecting the surrounding fixed-field lines and adjusting the pad value if a different column width is used.)

- [ ] **Step 3: In `StatusCommand.kt`, replace the `includeShared` block (around line 165)**

Find:

```kotlin
        val includeShared =
            if (profile.type == "onedrive") {
                profile.rawProvider?.include_shared == true
            } else {
                null
            }
```

Replace with:

```kotlin
        val extraFields = provider.statusFields()
```

Then in the `AuditReport(...)` constructor call below it, change `includeShared = includeShared,` to `extraFields = extraFields,`.

- [ ] **Step 4: In `StatusAuditTest.kt`, update the 5 fixtures**

For each fixture that constructs an `AuditReport`, change `includeShared = true` (or `= false`, or `= null`) to one of:

- For previously-true: `extraFields = listOf(org.krost.unidrive.StatusField("Include shared", "true"))`
- For previously-false: `extraFields = listOf(org.krost.unidrive.StatusField("Include shared", "false"))`
- For previously-null: omit the arg (defaults to `emptyList()`)

For each test that asserts the rendered output contains `"Include shared:   true"` or similar, the assertion already matches the new renderer's output verbatim (`"Include shared:".padEnd(18) + "true"` produces `"Include shared:   true"`). Verify.

- [ ] **Step 5: Verify compile + run StatusAuditTest**

```bash
./gradlew :app:cli:test --tests "*.StatusAuditTest" 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL, all StatusAudit tests pass.

- [ ] **Step 6: Run full :app:cli:test**

```bash
./gradlew :app:cli:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusAudit.kt \
        core/app/cli/src/main/kotlin/org/krost/unidrive/cli/StatusCommand.kt \
        core/app/cli/src/test/kotlin/org/krost/unidrive/cli/StatusAuditTest.kt
git commit -m "refactor(cli): drive status extras from CloudProvider.statusFields

AuditReport.includeShared (OneDrive-specific) → AuditReport.extraFields
(generic List<StatusField>). StatusCommand.buildAuditReport collects
extras from provider.statusFields() instead of dispatching by type
string. StatusAudit renderer iterates extras after the fixed fields.
StatusAuditTest fixtures shifted to the new field; assertions
unchanged because the rendered output is byte-identical for OneDrive.

OneDrive provider's statusFields() already added in Task 6 returns
the 'Include shared' row; behaviour preserved."
```

---

## Task 12: Migrate `HashVerifier`

**Files:**
- Modify: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/HashVerifier.kt`
- Modify (or create if absent): `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/HashVerifierTest.kt`

- [ ] **Step 1: Rewrite `HashVerifier.verify` to take an algorithm**

Replace the entire `verify` method body with:

```kotlin
    /**
     * Verify that the downloaded file matches the remote hash.
     *
     * Returns true if hash matches, OR if the algorithm is null
     * (provider has no verifiable hash), OR if the remoteHash is
     * null/empty. Returns false only on actual mismatch.
     */
    fun verify(
        localPath: java.nio.file.Path,
        remoteHash: String?,
        algorithm: org.krost.unidrive.HashAlgorithm?,
    ): Boolean {
        if (remoteHash.isNullOrEmpty()) return true
        if (algorithm == null) return true

        return when (algorithm) {
            org.krost.unidrive.HashAlgorithm.QuickXor -> verifyQuickXorHash(localPath, remoteHash)
            org.krost.unidrive.HashAlgorithm.Md5Hex -> verifyS3ETag(localPath, remoteHash)
            org.krost.unidrive.HashAlgorithm.Sha256Hex -> verifySha256Hex(localPath, remoteHash)
        }
    }

    private fun verifySha256Hex(
        localPath: java.nio.file.Path,
        expectedHex: String,
    ): Boolean {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.nio.file.Files.newInputStream(localPath).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        val computed = md.digest().joinToString("") { "%02x".format(it) }
        return computed.equals(expectedHex, ignoreCase = true)
    }
```

(`verifySha256Hex` is added so the `when` is exhaustive over the sealed class. It's a no-cost addition; future providers using SHA-256 get verification for free.)

- [ ] **Step 2: Delete the old `providerId: String` parameter and the `when (providerId)` block**

The replacement above is the whole new `verify` body. The old `verify` signature took `providerId: String` and dispatched by string; that's gone.

- [ ] **Step 3: Update all callers of `HashVerifier.verify`**

```bash
grep -rn "HashVerifier.verify\|HashVerifier\.verify" core/ --include="*.kt" 2>/dev/null | grep -v build/ | grep -v /test/
```

For each call site (likely in `SyncEngine.kt` or `Reconciler.kt`), change the argument list from `verify(localPath, remoteHash, providerId = provider.id)` (or similar) to `verify(localPath, remoteHash, algorithm = provider.hashAlgorithm())`.

- [ ] **Step 4: Update or create `HashVerifierTest.kt`**

If the file exists, update existing tests to pass `algorithm = HashAlgorithm.QuickXor` instead of `providerId = "onedrive"` etc.

Add one new test asserting the null-algorithm contract:

```kotlin
    @org.junit.Test
    fun `null algorithm is treated as skip-verification`() {
        val tempFile = java.nio.file.Files.createTempFile("hashverifier-null", ".bin")
        java.nio.file.Files.write(tempFile, byteArrayOf(0x00, 0x01, 0x02))
        try {
            val result = HashVerifier.verify(
                localPath = tempFile,
                remoteHash = "ignored-because-no-algorithm",
                algorithm = null,
            )
            kotlin.test.assertTrue(result, "null algorithm must skip verification (return true)")
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile)
        }
    }
```

- [ ] **Step 5: Verify compile + tests**

```bash
./gradlew :app:sync:test 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/HashVerifier.kt \
        core/app/sync/src/test/kotlin/org/krost/unidrive/sync/HashVerifierTest.kt
# Plus any caller files updated in Step 3:
git add <caller files printed by Step 3>
git commit -m "refactor(sync): drive HashVerifier from CloudProvider.hashAlgorithm

Replaces when(providerId) { 'onedrive' -> QuickXor; 's3' -> MD5 }
with when(algorithm: HashAlgorithm). Callers pass
provider.hashAlgorithm() instead of provider.id. Null algorithm =
skip verification (explicit, not silent pass)."
```

---

## Task 13: Add `ProviderFactoryContractTest` and `CloudProviderContractTest`

**Files:**
- Create: `core/app/core/src/test/kotlin/org/krost/unidrive/ProviderFactoryContractTest.kt`
- Create: `core/app/core/src/test/kotlin/org/krost/unidrive/CloudProviderContractTest.kt`

These tests pin the per-provider override snapshots so accidental schema drift fails CI.

- [ ] **Step 1: Create `ProviderFactoryContractTest.kt`**

```kotlin
package org.krost.unidrive

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the SPI capability surface each in-tree ProviderFactory exposes.
 * If a provider's credentialPrompts / envVarMappings /
 * supportsInteractiveAuth changes shape, this test fails loudly so
 * the change is deliberate.
 */
class ProviderFactoryContractTest {
    @Test
    fun `s3 credential prompts schema`() {
        val factory = ProviderRegistry.get("s3")!!
        val keys = factory.credentialPrompts().map { it.key }
        assertEquals(
            listOf("bucket", "region", "endpoint", "access_key_id", "secret_access_key"),
            keys,
        )
        // The secret-key prompt MUST be masked.
        val secret = factory.credentialPrompts().single { it.key == "secret_access_key" }
        assertTrue(secret.isMasked, "secret_access_key prompt must be masked")
    }

    @Test
    fun `s3 env-var mappings schema`() {
        val factory = ProviderRegistry.get("s3")!!
        assertEquals(
            mapOf(
                "S3_BUCKET" to "bucket",
                "AWS_ACCESS_KEY_ID" to "access_key_id",
                "AWS_SECRET_ACCESS_KEY" to "secret_access_key",
            ),
            factory.envVarMappings(),
        )
    }

    @Test
    fun `sftp credential prompts schema`() {
        val factory = ProviderRegistry.get("sftp")!!
        val keys = factory.credentialPrompts().map { it.key }
        assertEquals(listOf("host", "port", "user", "remote_path", "identity"), keys)
    }

    @Test
    fun `webdav credential prompts schema`() {
        val factory = ProviderRegistry.get("webdav")!!
        val keys = factory.credentialPrompts().map { it.key }
        assertEquals(listOf("url", "user", "password"), keys)
        val pwd = factory.credentialPrompts().single { it.key == "password" }
        assertTrue(pwd.isMasked, "password prompt must be masked")
    }

    @Test
    fun `onedrive supports interactive auth`() {
        val factory = ProviderRegistry.get("onedrive")!!
        assertTrue(factory.supportsInteractiveAuth(), "OneDrive must declare interactive auth support")
    }

    @Test
    fun `localfs sftp s3 webdav rclone do not declare interactive auth`() {
        for (id in listOf("localfs", "sftp", "s3", "webdav", "rclone")) {
            val factory = ProviderRegistry.get(id)!!
            assertEquals(
                false,
                factory.supportsInteractiveAuth(),
                "$id must NOT declare interactive auth (config-driven only)",
            )
        }
    }

    @Test
    fun `rclone env-var mapping schema`() {
        val factory = ProviderRegistry.get("rclone")!!
        assertEquals(mapOf("RCLONE_REMOTE" to "rclone_remote"), factory.envVarMappings())
    }

    @Test
    fun `localfs has no provider-specific prompts or env vars`() {
        val factory = ProviderRegistry.get("localfs")!!
        assertEquals(emptyList(), factory.credentialPrompts())
        assertEquals(emptyMap(), factory.envVarMappings())
    }
}
```

- [ ] **Step 2: Create `CloudProviderContractTest.kt`**

These tests use the provider factories' `create()` to build provider instances; some (like OneDrive, S3) need credentials. Use minimal stub configs that satisfy `create()` without doing network calls.

```kotlin
package org.krost.unidrive

import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the runtime SPI capability shape each provider exposes.
 * Verifies overrides differ from the default where expected
 * (otherwise the override is dead).
 */
class CloudProviderContractTest {
    @Test
    fun `onedrive declares QuickXor hash algorithm`() {
        val tmp = Files.createTempDirectory("onedrive-contract")
        try {
            val provider = ProviderRegistry.get("onedrive")!!.create(
                properties = mapOf("client_id" to "test-client"),
                tokenPath = tmp,
            )
            assertEquals(HashAlgorithm.QuickXor, provider.hashAlgorithm())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `s3 declares Md5Hex hash algorithm`() {
        val tmp = Files.createTempDirectory("s3-contract")
        try {
            val provider = ProviderRegistry.get("s3")!!.create(
                properties = mapOf(
                    "bucket" to "test-bucket",
                    "access_key_id" to "AKIA",
                    "secret_access_key" to "secret",
                ),
                tokenPath = tmp,
            )
            assertEquals(HashAlgorithm.Md5Hex, provider.hashAlgorithm())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `localfs has null hash algorithm (no integrity check)`() {
        val tmp = Files.createTempDirectory("localfs-contract")
        try {
            val provider = ProviderRegistry.get("localfs")!!.create(
                properties = mapOf("local_root" to tmp.toString()),
                tokenPath = tmp,
            )
            assertNull(provider.hashAlgorithm(), "localfs must declare null algorithm")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `webdav transport warning fires only above 50 GiB`() {
        val tmp = Files.createTempDirectory("webdav-contract")
        try {
            val provider = ProviderRegistry.get("webdav")!!.create(
                properties = mapOf(
                    "url" to "https://example.invalid/dav",
                    "user" to "u",
                    "password" to "p",
                ),
                tokenPath = tmp,
            )
            // Below threshold: no warning.
            assertNull(provider.transportWarning(10L * 1024 * 1024 * 1024))
            // Above threshold: warning present.
            val warning = provider.transportWarning(60L * 1024 * 1024 * 1024)
            assertNotNull(warning)
            assertEquals(true, warning.contains("nginx-mod_dav"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `s3 has no transport warning for any plan size`() {
        val tmp = Files.createTempDirectory("s3-transport")
        try {
            val provider = ProviderRegistry.get("s3")!!.create(
                properties = mapOf(
                    "bucket" to "test-bucket",
                    "access_key_id" to "AKIA",
                    "secret_access_key" to "secret",
                ),
                tokenPath = tmp,
            )
            assertNull(provider.transportWarning(1L * 1024 * 1024 * 1024 * 1024))  // 1 TiB
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
```

If any `create()` call fails because the test stub config is insufficient, the implementer should iterate: read the provider's `create()` requirements and add the missing keys. The point is to construct an instance, not exercise live behaviour.

- [ ] **Step 3: Run the new tests**

```bash
./gradlew :app:core:test --tests "*ContractTest" 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL, all new tests pass.

- [ ] **Step 4: Commit**

```bash
git add core/app/core/src/test/kotlin/org/krost/unidrive/ProviderFactoryContractTest.kt \
        core/app/core/src/test/kotlin/org/krost/unidrive/CloudProviderContractTest.kt
git commit -m "test(spi): pin per-provider capability snapshots in two contract tests

ProviderFactoryContractTest: credentialPrompts keys/order, envVar
mappings, supportsInteractiveAuth flag.
CloudProviderContractTest: hashAlgorithm per provider,
transportWarning threshold semantics on WebDAV.

Catches accidental schema drift (e.g. silently changing prompt
order, dropping the masked flag, removing a provider's hash
algorithm)."
```

---

## Task 14: Add anti-regression CI guard

**Files:**
- Create: `scripts/ci/check-no-provider-string-dispatch.sh`
- Modify: `.github/workflows/build.yml`

- [ ] **Step 1: Create the check script**

```bash
#!/usr/bin/env bash
# check-no-provider-string-dispatch.sh — fail CI if any of :app:cli,
# :app:mcp, :app:sync introduces a hardcoded provider-name string
# literal (the SPI-violation pattern UD-XXX eliminated).
#
# Allow-list: lines containing `// allow: <reason>` are ignored.
# This is the explicit opt-out marker for the small set of deliberate
# exceptions (e.g. SyncConfig.kt's historical "onedrive" default).
set -euo pipefail
cd "$(dirname "$0")/../.."

PROVIDER_NAMES='localfs|onedrive|rclone|s3|sftp|webdav|internxt'
SEARCH_PATHS=(
    core/app/cli/src/main
    core/app/mcp/src/main
    core/app/sync/src/main
)

hits=$(grep -rnE "\"($PROVIDER_NAMES)\"" "${SEARCH_PATHS[@]}" --include='*.kt' \
    | grep -v '// allow:' \
    || true)

if [[ -n "$hits" ]]; then
    echo "FAIL: hardcoded provider-name string literal in :app:{cli,mcp,sync}/main:"
    echo "$hits"
    echo
    echo "Either:"
    echo "  - Migrate to the SPI capability (factory.X() / provider.X()), OR"
    echo "  - If the literal is deliberate, append '// allow: <reason>' to the line."
    exit 1
fi

echo "OK: no hardcoded provider-name dispatch in :app:{cli,mcp,sync}/main."
```

Save as `scripts/ci/check-no-provider-string-dispatch.sh` and make executable:

```bash
chmod +x scripts/ci/check-no-provider-string-dispatch.sh
```

- [ ] **Step 2: Run the script locally to confirm it passes on the migrated tree**

```bash
bash scripts/ci/check-no-provider-string-dispatch.sh
```
Expected: `OK: no hardcoded provider-name dispatch in :app:{cli,mcp,sync}/main.`

If it fails, the implementer must either:
- Migrate the offending line (preferred), or
- Add `// allow: <reason>` if the literal is deliberate (e.g. `SyncConfig.kt`'s historical default — but that file lives in `:app:sync/main`, so it WILL appear here and SHOULD be marked allow with a reason like `// allow: historical default per UD-008 follow-up`).

- [ ] **Step 3: Wire into `.github/workflows/build.yml`**

Find the existing `Test-hygiene lint` step (around line 56-58). After it, add a new step:

```yaml
      - name: SPI dispatch boundary check
        if: matrix.os == 'ubuntu-latest'
        shell: bash
        run: bash scripts/ci/check-no-provider-string-dispatch.sh
```

(Same `if` guard as the test-hygiene step — Linux-only is fine; the check is platform-independent.)

- [ ] **Step 4: Verify the workflow file parses (syntax check)**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/build.yml'))" && echo OK
```
Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add scripts/ci/check-no-provider-string-dispatch.sh \
        .github/workflows/build.yml
git commit -m "ci: fail build on hardcoded provider-name string dispatch in :app:{cli,mcp,sync}

Anti-regression guard. If anyone adds a new when(provider.id) {
'foo' -> ... } block, CI catches it. Allow-list via '// allow:
<reason>' for the small set of deliberate exceptions."
```

---

## Task 15: Update spec to v0.3.0 (Done)

**Files:**
- Modify: `docs/specs/2026-05-02-provider-spi-contract-extension-design.md`

- [ ] **Step 1: Bump version + status; append §9 implementation notes**

Open the spec. Change:

```
- **Version:** 0.2.0
- **Date:** 2026-05-02
- **Status:** Reviewed → ready for implementation plan
```

to:

```
- **Version:** 0.3.0
- **Date:** 2026-05-02
- **Status:** Done — implemented on `refactor/provider-spi-contract`
```

At the end of the spec file, append:

```markdown

## 9. Implementation notes (2026-05-02, v0.3.0)

Executed via subagent-driven-development against the plan at
`docs/plans/2026-05-02-provider-spi-contract-extension.md`.

**Outcomes:**
- 6 new SPI methods landed across `ProviderFactory` and
  `CloudProvider`, all with default implementations.
- 7 dispatch sites in `:app:cli`, `:app:mcp`, `:app:sync`
  migrated to consult the SPI.
- 3 supporting types added (`PromptSpec`, `StatusField`,
  `HashAlgorithm`).
- 5 in-tree providers gained capability overrides
  (s3/sftp/webdav: prompts + env; onedrive: interactive auth +
  hash + status; rclone: env; webdav: transport warning).
- Anti-regression CI guard wired in
  `scripts/ci/check-no-provider-string-dispatch.sh`.
- `./gradlew build test` reports BUILD SUCCESSFUL with the same
  test count baseline (1450 + ContractTests added).
```

- [ ] **Step 2: Commit**

```bash
git add docs/specs/2026-05-02-provider-spi-contract-extension-design.md
git commit -m "wip(docs): mark provider-SPI-contract spec as Done (v0.3.0)

Refactor complete. 7 dispatch sites migrated to SPI capability
methods; 6 new methods with default implementations; 3 supporting
types; anti-regression CI guard. Build green, no test count
regression."
```

---

## Done criteria

All of the following must be true:

1. `./gradlew build test` from `core/` reports BUILD SUCCESSFUL with 0 failures.
2. Test count is at least at the previous baseline (1450); new ContractTests add to the count, no test removed.
3. `bash scripts/ci/check-no-provider-string-dispatch.sh` returns exit 0.
4. `grep -rnE '"(localfs|onedrive|rclone|s3|sftp|webdav|internxt)"' core/app/{cli,mcp,sync}/src/main/ --include='*.kt' | grep -v '// allow:'` returns nothing (same check, manual form).
5. The 7 site references in spec §1 are all migrated to SPI calls (verifiable by reading each file).
6. Spec status line reads `Done — implemented on refactor/provider-spi-contract`.
7. Branch `refactor/provider-spi-contract` is pushed to origin and a PR exists targeting `dev` (PR creation is out-of-band, by user; not a task step).
