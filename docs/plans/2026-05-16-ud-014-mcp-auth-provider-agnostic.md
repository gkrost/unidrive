# UD-014 — Provider-agnostic MCP interactive-auth — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lift OneDrive-specific device-flow plumbing out of `:app:mcp` into the OneDrive module via three new `ProviderFactory` SPI methods, drop the `// allow: UD-014` narrowing guard in `AuthTool`, and preserve today's MCP wire format exactly.

**Architecture:** `:app:core` adds `BeginAuthResult` / `CompleteAuthResult` types and three default SPI methods (`beginInteractiveAuth` / `completeInteractiveAuth` / `cancelInteractiveAuth`). `core/providers/onedrive` overrides all three and owns the in-process device-flow registry; `OAuthService` gets an `internal` test constructor that accepts a Ktor `HttpClient` so the contract test can drive a `MockEngine`. `:app:mcp` collapses its registry to a thin handle→provider-type router and uses only SPI types in `AuthTool`.

**Tech Stack:** Kotlin 1.9, Gradle composite build, Ktor (server-side HTTP + `MockEngine` for tests), kotlinx-serialization, kotlin-test + JUnit, ServiceLoader for provider discovery, ktlint with line-number-anchored baselines.

**Spec:** [`docs/specs/2026-05-16-ud-014-mcp-auth-provider-agnostic-design.md`](../specs/2026-05-16-ud-014-mcp-auth-provider-agnostic-design.md). The spec is the source of truth for *what*; this plan covers *how*, in bite-sized steps.

**Branch:** `spec/UD-014-mcp-auth-provider-agnostic` (already created off `main@71711cd` with the spec commit at `e4d8a76`/`9407fb4`).

**Working directory invariant:** every command in this plan is run from the repo root (`/home/gernot/dev/git/unidrive`) unless stated otherwise. Gradle invocations live under `core/` (it's the composite root); use `(cd core && ./gradlew …)` for those.

---

## File map

| File | Action | Responsibility |
|---|---|---|
| `core/app/core/src/main/kotlin/org/krost/unidrive/InteractiveAuth.kt` | **Create** | `BeginAuthResult` data class + `CompleteAuthResult` sealed class. ~40 lines. |
| `core/app/core/src/main/kotlin/org/krost/unidrive/ProviderFactory.kt` | **Modify** | Append three default SPI methods. ~30 added lines, no existing-line edits. |
| `core/app/core/src/test/kotlin/org/krost/unidrive/InteractiveAuthSpiContractTest.kt` | **Create** | Cross-cutting SPI snapshot test. Two cases. |
| `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt` | **Modify** | Add `internal` test constructor accepting a pre-built `HttpClient`. Existing constructor unchanged in behaviour. |
| `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveDeviceFlowRegistry.kt` | **Create** | `OneDriveDeviceFlowState` + `OneDriveDeviceFlowRegistry` (lift target). ~40 lines. |
| `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProviderFactory.kt` | **Modify** | Add `override suspend fun beginInteractiveAuth` / `completeInteractiveAuth` / `cancelInteractiveAuth`. ~120 added lines, no existing-line edits. |
| `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/OneDriveInteractiveAuthContractTest.kt` | **Create** | Fixture-driven contract test for OneDrive's three overrides. Seven assertions. |
| `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt` | **Rewrite** | Replace OneDrive-specific body with agnostic version using SPI types. Net diff: ~-100 / +80 lines. Drops `OAuthService` / `OneDriveConfig` / `DeviceCodeResponse` / `AuthenticationException` imports. |
| `core/app/mcp/src/test/kotlin/org/krost/unidrive/mcp/AdminToolsTest.kt` | (no change expected) | Three `auth_*` cases continue to pass by virtue of the wire-format invariants. |

Existing files where the **only** changes are baseline regeneration:
- `core/app/mcp/config/ktlint/baseline.xml`
- `core/providers/onedrive/config/ktlint/baseline.xml`
- `core/app/core/config/ktlint/baseline.xml` (if any drift)

---

## Task 1: Add `BeginAuthResult` / `CompleteAuthResult` to `:app:core`

**Files:**
- Create: `core/app/core/src/main/kotlin/org/krost/unidrive/InteractiveAuth.kt`

- [ ] **Step 1.1: Create the SPI carrier file**

Write the file with this exact content:

```kotlin
package org.krost.unidrive

import java.time.Instant

/**
 * Result of [ProviderFactory.beginInteractiveAuth].
 *
 * The map-carrier shape (rather than a typed subclass per flow) keeps
 * :app:mcp provider-agnostic at the type level: each provider populates
 * its own JSON-payload keys, and the MCP handler embeds them verbatim.
 * UD-014 rationale: see docs/specs/2026-05-16-ud-014-*.md §8.
 */
data class BeginAuthResult(
    /** Opaque handle the caller passes to completeInteractiveAuth. */
    val continuationHandle: String,
    /**
     * Provider-supplied JSON-payload keys (string values to keep the
     * wire format unambiguous). OneDrive populates verification_uri,
     * user_code, interval_seconds, expires_in, message.
     *
     * CONTRACT: must be an insertion-order-preserving Map (i.e. a
     * [LinkedHashMap] built via [linkedMapOf] or `buildMap { … }`).
     * The MCP handler emits these keys in iteration order, so a
     * non-ordered map would produce nondeterministic JSON key order
     * across runs.
     */
    val fields: Map<String, String>,
    /** Wall-clock deadline after which the handle is invalid. */
    val expiresAt: Instant,
    /** Provider-suggested polling interval, if applicable. */
    val retryAfterSeconds: Long? = null,
)

/** Outcome of [ProviderFactory.completeInteractiveAuth]. */
sealed class CompleteAuthResult {
    /** Tokens persisted to disk under profileDir; caller may proceed. */
    data object Success : CompleteAuthResult()

    /** Auth not finished — poll again after [retryAfterSeconds]. */
    data class Pending(
        val retryAfterSeconds: Long,
    ) : CompleteAuthResult()

    /** Terminal failure with a user-displayable message. */
    data class Failure(
        val message: String,
    ) : CompleteAuthResult()
}
```

- [ ] **Step 1.2: Confirm the file compiles in isolation**

Run: `(cd core && ./gradlew :app:core:compileKotlin -q)`
Expected: BUILD SUCCESSFUL, no warnings about the new file.

- [ ] **Step 1.3: Commit**

```bash
git add core/app/core/src/main/kotlin/org/krost/unidrive/InteractiveAuth.kt
git commit -m "feat(UD-014): add BeginAuthResult / CompleteAuthResult SPI carriers

Provider-agnostic types in :app:core that ProviderFactory.beginInteractiveAuth
and completeInteractiveAuth (added next) will use. fields is a map carrier
(see UD-014 spec §8) to keep :app:mcp free of per-provider when-branches.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 2: Add three default SPI methods to `ProviderFactory`

**Files:**
- Modify: `core/app/core/src/main/kotlin/org/krost/unidrive/ProviderFactory.kt:103` (after the existing `supportsInteractiveAuth` method)

- [ ] **Step 2.1: Read the current closing brace of `ProviderFactory`**

The interface currently ends at line 104 with `}`. The new methods are appended *before* that closing brace.

- [ ] **Step 2.2: Insert the three SPI methods**

Use Edit to replace this block:

```kotlin
    fun supportsInteractiveAuth(): Boolean = false
}
```

with:

```kotlin
    fun supportsInteractiveAuth(): Boolean = false

    /**
     * Begin an interactive auth flow. Only invoked when
     * [supportsInteractiveAuth] returns true. The throwing default is
     * the sentinel that pairs with the capability gate — UD-014.
     */
    suspend fun beginInteractiveAuth(profileDir: java.nio.file.Path): BeginAuthResult =
        throw UnsupportedOperationException("$id has no interactive auth flow")

    /**
     * Resume an interactive auth flow with the handle from
     * [beginInteractiveAuth]. Returns Success / Pending / Error.
     */
    suspend fun completeInteractiveAuth(
        profileDir: java.nio.file.Path,
        continuationHandle: String,
    ): CompleteAuthResult =
        throw UnsupportedOperationException("$id has no interactive auth flow")

    /**
     * Abandon an in-flight interactive auth flow. Default no-op.
     * Implementations release per-handle resources (HTTP clients, timers).
     */
    suspend fun cancelInteractiveAuth(continuationHandle: String) { /* no-op */ }
}
```

Note: `java.nio.file.Path` is already imported at the top of the file (line 3). No new imports needed.

- [ ] **Step 2.3: Confirm `:app:core` still compiles**

Run: `(cd core && ./gradlew :app:core:compileKotlin -q)`
Expected: BUILD SUCCESSFUL. If a warning fires about `suspend` defaults, that's fine — the interface itself isn't `suspend`, only the methods are.

- [ ] **Step 2.4: Confirm every dependent module still compiles (the safe-default property)**

Run: `(cd core && ./gradlew compileKotlin -q)`
Expected: BUILD SUCCESSFUL across all modules. This is the canary that the three throwing/no-op defaults don't break any existing provider that doesn't override them (the spec's "no business-logic change for non-OAuth providers" claim).

- [ ] **Step 2.5: Commit**

```bash
git add core/app/core/src/main/kotlin/org/krost/unidrive/ProviderFactory.kt
git commit -m "feat(UD-014): add begin/complete/cancel InteractiveAuth SPI methods

Default beginInteractiveAuth/completeInteractiveAuth throw
UnsupportedOperationException — paired with the supportsInteractiveAuth
gate so the path is unreachable for non-OAuth providers. Default
cancelInteractiveAuth is a no-op. All three are suspend.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 3: Add internal test constructor to `OAuthService`

**Files:**
- Modify: `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt:19-36`

**Why:** The contract test in Task 6 (via the factory hook `newOAuthServiceForBegin` introduced in Step 6.2) needs to construct an `OAuthService` with a Ktor `MockEngine`-backed `HttpClient` so no real network call happens. The existing primary constructor builds the `HttpClient` internally; this task makes the `HttpClient` a default-valued constructor arg so production call sites are unchanged and test code can pass a custom one.

- [ ] **Step 3.1: Read the current class header**

Lines 19–36 are the class declaration and the `httpClient` initialiser. Today:

```kotlin
class OAuthService(
    private val config: OneDriveConfig,
) : AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(OAuthService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient =
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
                socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
                requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
            }
        }
```

- [ ] **Step 3.2: Refactor to allow injection**

Use Edit to replace the existing class header + httpClient initialiser (the snippet from Step 3.1) with:

```kotlin
class OAuthService(
    private val config: OneDriveConfig,
    private val httpClient: HttpClient =
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
                socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
                requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
            }
        },
) : AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(OAuthService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
```

This makes the second parameter have a default value (the existing behaviour). Production call sites (`OAuthService(config)`) work unchanged because `httpClient` defaults to the same `HttpClient { … }` block. Test call sites pass a `MockEngine`-backed client explicitly. **No new visibility annotations needed** — the parameter is just a default-valued constructor arg; Kotlin classes with default-valued constructor args expose the `(config, httpClient)` arity automatically.

- [ ] **Step 3.3: Verify all existing call sites still compile**

Run: `(cd core && ./gradlew :providers:onedrive:compileKotlin -q)`
Expected: BUILD SUCCESSFUL. The four production call sites (`AuthTool.kt:107`, plus three test files: `OAuthServiceTokenShapeTest.kt`, `OneDriveIntegrationTest.kt`, `LiveGraphIntegrationTest.kt` — verify via grep) all use `OAuthService(config)` and continue to work via the default value.

```bash
grep -rn "OAuthService(" core/providers/onedrive core/app/mcp --include="*.kt"
```

Confirm each call site is either `OAuthService(config)` (unchanged) or already passes both args (none today).

- [ ] **Step 3.4: Run the OneDrive unit tests**

Run: `(cd core && ./gradlew :providers:onedrive:test -q --tests "org.krost.unidrive.onedrive.*" -x integrationTest 2>&1 | tail -20)`
Expected: BUILD SUCCESSFUL or tests pass; if the test task name differs (`test` is canonical for this project), use whatever the existing `:providers:onedrive` test task is. If `LiveGraphIntegrationTest` fails because `UNIDRIVE_INTEGRATION_TESTS` isn't set, that's a skip, not a failure — confirm with the test output.

- [ ] **Step 3.5: Commit**

```bash
git add core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OAuthService.kt
git commit -m "refactor(UD-014): make OAuthService HttpClient injectable

Second constructor arg with default = the existing HttpClient { … } block,
so production call sites (OAuthService(config)) work unchanged. Lets the
upcoming OneDriveInteractiveAuthContractTest inject Ktor MockEngine.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 4: Lift the device-flow registry into the OneDrive module

**Files:**
- Create: `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveDeviceFlowRegistry.kt`

This is the lift target. The current `:app:mcp/.../AuthTool.kt:24-45` defines `DeviceFlowState` and `DeviceFlowRegistry`; we recreate them here under provider-scoped names. The MCP-side definitions stay put for now (they'll be deleted in Task 7).

- [ ] **Step 4.1: Create the registry file**

Write the file with this exact content:

```kotlin
package org.krost.unidrive.onedrive

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * UD-014: lifted from :app:mcp/AuthTool.kt. Per-handle device-flow state
 * for OneDrive's interactive auth. The provider owns this registry
 * because it owns the OAuthService (and its HttpClient) lifecycle.
 *
 * Lifecycle invariant: every terminal outcome of completeInteractiveAuth
 * (Success, Failure from poll, Failure from save, Failure from expiry) must
 * call [OneDriveDeviceFlowRegistry.remove] and close the resulting state's
 * oauthService. Pending leaves the state in place for the next poll.
 */
internal data class OneDriveDeviceFlowState(
    val deviceCode: String,
    val expiresAtMillis: Long,
    val oauthService: OAuthService,
)

internal object OneDriveDeviceFlowRegistry {
    private val states: ConcurrentHashMap<String, OneDriveDeviceFlowState> = ConcurrentHashMap()

    fun put(state: OneDriveDeviceFlowState): String {
        val handle = UUID.randomUUID().toString()
        states[handle] = state
        return handle
    }

    fun get(handle: String): OneDriveDeviceFlowState? = states[handle]

    fun remove(handle: String): OneDriveDeviceFlowState? = states.remove(handle)

    /** UD-014 test-only: lets OneDriveInteractiveAuthContractTest assert
     *  the registry-is-empty-after-each-terminal-outcome invariant. */
    internal fun sizeForTest(): Int = states.size
}
```

- [ ] **Step 4.2: Compile**

Run: `(cd core && ./gradlew :providers:onedrive:compileKotlin -q)`
Expected: BUILD SUCCESSFUL. `OAuthService` is imported via same-package implicit access.

- [ ] **Step 4.3: Commit**

```bash
git add core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveDeviceFlowRegistry.kt
git commit -m "feat(UD-014): lift DeviceFlowRegistry into OneDrive module

OneDriveDeviceFlowState + OneDriveDeviceFlowRegistry, internal to the
module. Identical structure to the current AuthTool-side registry,
renamed for clarity; sizeForTest() exposes the registry-empty invariant
to the upcoming contract test.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 5: Implement OneDrive's three SPI overrides

**Files:**
- Modify: `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProviderFactory.kt:122` (after the existing `supportsInteractiveAuth` override)

- [ ] **Step 5.1: Add imports**

At the top of the file (after the existing imports at lines 1–11), add:

```kotlin
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.CompleteAuthResult
import java.time.Instant
```

Use Edit to replace the existing import block (lines 1–11) with the same imports plus the four new lines, sorted by package.

- [ ] **Step 5.2: Append the three overrides**

Use Edit to replace this single line:

```kotlin
    override fun supportsInteractiveAuth(): Boolean = true
}
```

with the block that adds the three overrides (full code below; this is the longest single edit in the plan):

```kotlin
    override fun supportsInteractiveAuth(): Boolean = true

    override suspend fun beginInteractiveAuth(profileDir: Path): BeginAuthResult {
        val oauth = OAuthService(OneDriveConfig(tokenPath = profileDir))
        val deviceCode =
            try {
                oauth.getDeviceCode()
            } catch (e: Exception) {
                // No handle issued yet — close the HttpClient here, the
                // registry-cleanup paths cannot reach it.
                oauth.close()
                throw e
            }

        val state =
            OneDriveDeviceFlowState(
                deviceCode = deviceCode.deviceCode,
                expiresAtMillis = System.currentTimeMillis() + deviceCode.expiresIn * 1000L,
                oauthService = oauth,
            )
        val handle = OneDriveDeviceFlowRegistry.put(state)

        return BeginAuthResult.of(
            continuationHandle = handle,
            fields =
                linkedMapOf(
                    "verification_uri" to deviceCode.verificationUri,
                    "user_code" to deviceCode.userCode,
                    "interval_seconds" to deviceCode.interval.toString(),
                    "expires_in" to deviceCode.expiresIn.toString(),
                    "message" to deviceCode.message,
                ),
            expiresAt = Instant.ofEpochMilli(state.expiresAtMillis),
            retryAfterSeconds = deviceCode.interval,
        )
    }

    override suspend fun completeInteractiveAuth(
        profileDir: Path,
        continuationHandle: String,
    ): CompleteAuthResult {
        val state =
            OneDriveDeviceFlowRegistry.get(continuationHandle)
                ?: return CompleteAuthResult.Failure("Unknown or expired continuation_handle. Call auth_begin again.")

        if (System.currentTimeMillis() > state.expiresAtMillis) {
            OneDriveDeviceFlowRegistry.remove(continuationHandle)
            state.oauthService.close()
            return CompleteAuthResult.Failure("Device code expired. Call auth_begin again.")
        }

        val oauth = state.oauthService
        val outcome: OAuthService.DevicePollOutcome =
            try {
                oauth.pollOnceForToken(state.deviceCode)
            } catch (e: AuthenticationException) {
                OneDriveDeviceFlowRegistry.remove(continuationHandle)
                oauth.close()
                return CompleteAuthResult.Failure(e.message ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                OneDriveDeviceFlowRegistry.remove(continuationHandle)
                oauth.close()
                return CompleteAuthResult.Failure(e.message ?: e.javaClass.simpleName)
            }

        return when (outcome) {
            is OAuthService.DevicePollOutcome.Pending ->
                CompleteAuthResult.Pending(outcome.retryAfterSeconds)
            is OAuthService.DevicePollOutcome.Success -> {
                try {
                    oauth.saveToken(outcome.token)
                } catch (e: Exception) {
                    OneDriveDeviceFlowRegistry.remove(continuationHandle)
                    oauth.close()
                    return CompleteAuthResult.Failure("Token received but save failed: ${e.message}")
                }
                OneDriveDeviceFlowRegistry.remove(continuationHandle)
                oauth.close()
                CompleteAuthResult.Success
            }
            is OAuthService.DevicePollOutcome.Failed -> {
                OneDriveDeviceFlowRegistry.remove(continuationHandle)
                oauth.close()
                CompleteAuthResult.Failure(outcome.message)
            }
        }
    }

    override suspend fun cancelInteractiveAuth(continuationHandle: String) {
        OneDriveDeviceFlowRegistry.remove(continuationHandle)?.oauthService?.close()
    }
}
```

- [ ] **Step 5.3: Compile**

Run: `(cd core && ./gradlew :providers:onedrive:compileKotlin -q)`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5.4: Commit (overrides only — contract test is the next task)**

```bash
git add core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProviderFactory.kt
git commit -m "feat(UD-014): OneDriveProviderFactory overrides beginInteractiveAuth + completeInteractiveAuth + cancelInteractiveAuth

Mechanical lift of AuthTool.handleAuthBegin/handleAuthComplete bodies
into the provider. Lifecycle invariant: every terminal outcome
(Success/Failure-from-poll/Failure-from-save/Failure-from-expiry) does
remove+close on OneDriveDeviceFlowRegistry; Pending leaves state in
place. saveToken wrapped in try/catch so a disk-write failure
surfaces as CompleteAuthResult.Failure rather than dangling state.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 6: OneDrive contract test (the ticket's acceptance test)

**Files:**
- Create: `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/OneDriveInteractiveAuthContractTest.kt`

This task is itself test-driven: write each assertion, run it, watch it fail or pass, commit. The seven assertions are orthogonal (per CLAUDE.md "orthogonal invariant decomposition") — one named test per invariant.

- [ ] **Step 6.1: Write the test skeleton + first assertion (capability)**

Create the file with this content:

```kotlin
package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.CompleteAuthResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UD-014 acceptance test. Drives OneDriveProviderFactory's three
 * interactive-auth overrides against Ktor MockEngine — no live network.
 *
 * Each @Test asserts ONE invariant; multiple invariants share a fixture
 * builder but never share a test name.
 */
class OneDriveInteractiveAuthContractTest {
    private lateinit var tmpProfileDir: Path

    @BeforeTest
    fun setUp() {
        tmpProfileDir = Files.createTempDirectory("ud-014-")
    }

    @AfterTest
    fun tearDown() {
        tmpProfileDir.toFile().deleteRecursively()
    }

    private fun factoryWithEngine(engine: MockEngine): OneDriveProviderFactory {
        // The factory creates its own OAuthService inside beginInteractiveAuth,
        // so we patch via a tiny subclass that injects the HttpClient. This
        // overrides only the http transport; all other logic is real.
        TODO("implemented in Step 6.2")
    }

    @Test
    fun factory_declares_interactive_auth_support() {
        val factory = OneDriveProviderFactory()
        assertTrue(factory.supportsInteractiveAuth())
    }
}
```

The `factoryWithEngine` is a `TODO` — Step 6.2 finishes it, because the helper requires a small extension point we add to the factory.

- [ ] **Step 6.2: Decide on the injection seam — add a `protected` HttpClient factory hook to `OneDriveProviderFactory`**

The factory currently constructs `OAuthService(OneDriveConfig(tokenPath = profileDir))` directly inside `beginInteractiveAuth`. To inject the engine without polluting production code, refactor that one line to use an `internal open` hook:

In `OneDriveProviderFactory.kt`, replace **just the first line of `beginInteractiveAuth`**:

Find:
```kotlin
        val oauth = OAuthService(OneDriveConfig(tokenPath = profileDir))
```

Replace with:
```kotlin
        val oauth = newOAuthServiceForBegin(profileDir)
```

And add a new `internal open` method on the class, immediately before the closing brace:

```kotlin
    /** UD-014 seam: subclassed in OneDriveInteractiveAuthContractTest
     *  to inject a Ktor MockEngine-backed HttpClient. Production code
     *  uses the default. */
    internal open fun newOAuthServiceForBegin(profileDir: Path): OAuthService =
        OAuthService(OneDriveConfig(tokenPath = profileDir))
```

Compile: `(cd core && ./gradlew :providers:onedrive:compileKotlin -q)` → BUILD SUCCESSFUL.

Then finish the test helper — replace the `TODO` in `OneDriveInteractiveAuthContractTest.kt` with:

```kotlin
    private fun factoryWithEngine(engine: MockEngine): OneDriveProviderFactory =
        object : OneDriveProviderFactory() {
            override fun newOAuthServiceForBegin(profileDir: Path): OAuthService =
                OAuthService(OneDriveConfig(tokenPath = profileDir), HttpClient(engine))
        }
```

This subclasses the factory in test code only; production behaviour is untouched.

- [ ] **Step 6.3: Open the factory class for subclassing**

`OneDriveProviderFactory` is currently `class` (final). To subclass it in tests, change line 13 from `class OneDriveProviderFactory : ProviderFactory {` to `open class OneDriveProviderFactory : ProviderFactory {`. One-word edit.

Compile: `(cd core && ./gradlew :providers:onedrive:compileKotlin -q)` → BUILD SUCCESSFUL.

Then run the existing capability test:
```
(cd core && ./gradlew :providers:onedrive:test --tests "org.krost.unidrive.onedrive.OneDriveInteractiveAuthContractTest.factory_declares_interactive_auth_support" -q 2>&1 | tail -10)
```
Expected: PASS.

- [ ] **Step 6.4: Add `begin_returns_well_formed_payload`**

Append to the test class:

```kotlin
    /** Canned /devicecode response matching Azure's documented shape. */
    private fun deviceCodeResponseBody(): String =
        """
        {
          "device_code": "DC-abc-123",
          "user_code": "USR-456",
          "verification_uri": "https://microsoft.com/devicelogin",
          "expires_in": 900,
          "interval": 5,
          "message": "To sign in, use a web browser to open https://microsoft.com/devicelogin and enter USR-456."
        }
        """.trimIndent()

    private fun deviceCodeOnlyEngine(): MockEngine =
        MockEngine { _ ->
            respond(
                content = ByteReadChannel(deviceCodeResponseBody()),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("application/json")),
            )
        }

    @Test
    fun begin_returns_well_formed_payload() = runBlocking {
        val factory = factoryWithEngine(deviceCodeOnlyEngine())
        val result: BeginAuthResult = factory.beginInteractiveAuth(tmpProfileDir)

        assertTrue(result.continuationHandle.isNotEmpty())
        assertEquals("https://microsoft.com/devicelogin", result.fields["verification_uri"])
        assertEquals("USR-456", result.fields["user_code"])
        assertEquals("5", result.fields["interval_seconds"])
        assertEquals("900", result.fields["expires_in"])
        assertNotNull(result.fields["message"])
        assertTrue(result.expiresAt.isAfter(java.time.Instant.now()))
        assertEquals(5L, result.retryAfterSeconds)

        // Cleanup: cancel so the registry doesn't carry state into the next test.
        factory.cancelInteractiveAuth(result.continuationHandle)
    }
```

Run: `(cd core && ./gradlew :providers:onedrive:test --tests "*.OneDriveInteractiveAuthContractTest.begin_returns_well_formed_payload" -q 2>&1 | tail -10)`
Expected: PASS.

- [ ] **Step 6.5: Add `complete_success_persists_token_and_forgets_handle`**

This needs a *two-step* MockEngine: first request → `/devicecode` JSON; second request → `/token` JSON with a real-looking access_token. Append:

```kotlin
    /** Two-step engine: /devicecode then /token. Track call count via a counter. */
    private fun deviceCodeThenTokenEngine(): MockEngine {
        var call = 0
        return MockEngine { _ ->
            call += 1
            when (call) {
                1 -> respond(
                    content = ByteReadChannel(deviceCodeResponseBody()),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type" to listOf("application/json")),
                )
                2 -> respond(
                    content = ByteReadChannel(
                        """
                        {
                          "access_token": "${"x".repeat(200)}",
                          "token_type": "Bearer",
                          "expires_in": 3600,
                          "refresh_token": "rt-789",
                          "scope": "Files.ReadWrite.All offline_access openid"
                        }
                        """.trimIndent(),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type" to listOf("application/json")),
                )
                else -> error("Unexpected request #$call")
            }
        }
    }

    @Test
    fun complete_success_persists_token_and_forgets_handle() = runBlocking {
        val factory = factoryWithEngine(deviceCodeThenTokenEngine())
        val begin = factory.beginInteractiveAuth(tmpProfileDir)
        val outcome = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)

        assertEquals(CompleteAuthResult.Success, outcome)
        assertTrue(Files.exists(tmpProfileDir.resolve("token.json")))

        // Same handle on a second call must now be unknown.
        val second = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)
        assertTrue(second is CompleteAuthResult.Failure)
        assertTrue((second as CompleteAuthResult.Failure).message.contains("Unknown or expired"))
    }
```

Run: `(cd core && ./gradlew :providers:onedrive:test --tests "*.OneDriveInteractiveAuthContractTest.complete_success_persists_token_and_forgets_handle" -q 2>&1 | tail -10)`
Expected: PASS. The access_token shape passes `hasPlausibleAccessTokenShape()` (>= ~32 chars).

- [ ] **Step 6.6: Add `complete_authorization_pending_returns_pending`**

```kotlin
    private fun deviceCodeThenPendingEngine(): MockEngine {
        var call = 0
        return MockEngine { _ ->
            call += 1
            when (call) {
                1 -> respond(
                    content = ByteReadChannel(deviceCodeResponseBody()),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type" to listOf("application/json")),
                )
                else -> respond(
                    content = ByteReadChannel("""{"error":"authorization_pending"}"""),
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf("Content-Type" to listOf("application/json")),
                )
            }
        }
    }

    @Test
    fun complete_authorization_pending_returns_pending() = runBlocking {
        val factory = factoryWithEngine(deviceCodeThenPendingEngine())
        val begin = factory.beginInteractiveAuth(tmpProfileDir)
        val outcome = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)

        assertTrue(outcome is CompleteAuthResult.Pending)
        assertEquals(5L, (outcome as CompleteAuthResult.Pending).retryAfterSeconds)

        // Handle is still resolvable for the next poll.
        val second = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)
        assertTrue(second is CompleteAuthResult.Pending)

        // Cleanup
        factory.cancelInteractiveAuth(begin.continuationHandle)
    }
```

Run the single test. Expected: PASS.

- [ ] **Step 6.7: Add `complete_expired_token_returns_error_and_forgets_handle`**

```kotlin
    private fun deviceCodeThenExpiredEngine(): MockEngine {
        var call = 0
        return MockEngine { _ ->
            call += 1
            when (call) {
                1 -> respond(
                    content = ByteReadChannel(deviceCodeResponseBody()),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type" to listOf("application/json")),
                )
                else -> respond(
                    content = ByteReadChannel("""{"error":"expired_token"}"""),
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf("Content-Type" to listOf("application/json")),
                )
            }
        }
    }

    @Test
    fun complete_expired_token_returns_error_and_forgets_handle() = runBlocking {
        val factory = factoryWithEngine(deviceCodeThenExpiredEngine())
        val begin = factory.beginInteractiveAuth(tmpProfileDir)
        val outcome = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)

        assertTrue(outcome is CompleteAuthResult.Failure)
        assertTrue((outcome as CompleteAuthResult.Failure).message.contains("expired", ignoreCase = true))

        // Handle now unknown.
        val second = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)
        assertTrue(second is CompleteAuthResult.Failure)
        assertTrue((second as CompleteAuthResult.Failure).message.contains("Unknown or expired"))
    }
```

Run. Expected: PASS.

- [ ] **Step 6.8: Add `cancel_releases_handle`**

```kotlin
    @Test
    fun cancel_releases_handle() = runBlocking {
        val factory = factoryWithEngine(deviceCodeOnlyEngine())
        val begin = factory.beginInteractiveAuth(tmpProfileDir)

        factory.cancelInteractiveAuth(begin.continuationHandle)

        val outcome = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)
        assertTrue(outcome is CompleteAuthResult.Failure)
        assertTrue((outcome as CompleteAuthResult.Failure).message.contains("Unknown or expired"))
    }
```

Run. Expected: PASS.

- [ ] **Step 6.9: Add `registry_is_empty_after_each_terminal_outcome`**

This is the close-side counterpart of Risk #3. It uses `OneDriveDeviceFlowRegistry.sizeForTest()`.

```kotlin
    @Test
    fun registry_is_empty_after_each_terminal_outcome() = runBlocking {
        // Success path
        val s = factoryWithEngine(deviceCodeThenTokenEngine())
        val sBegin = s.beginInteractiveAuth(tmpProfileDir)
        s.completeInteractiveAuth(tmpProfileDir, sBegin.continuationHandle)
        assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest(), "registry must drain after Success")

        // Expired-token error path
        val e = factoryWithEngine(deviceCodeThenExpiredEngine())
        val eBegin = e.beginInteractiveAuth(tmpProfileDir)
        e.completeInteractiveAuth(tmpProfileDir, eBegin.continuationHandle)
        assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest(), "registry must drain after Failure(expired)")

        // Cancel path
        val c = factoryWithEngine(deviceCodeOnlyEngine())
        val cBegin = c.beginInteractiveAuth(tmpProfileDir)
        c.cancelInteractiveAuth(cBegin.continuationHandle)
        assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest(), "registry must drain after cancel")
    }
```

Note: the test depends on the OneDrive registry being **truly object-scoped** (not per-factory). It is. Run a clean factory between tests — `@BeforeTest` and the cancel cleanups in earlier tests guarantee the registry is empty at the start. If parallel test execution is configured, mark this test `@Order(LAST)` or accept that this test is order-sensitive; for now Gradle's default test ordering serialises the methods in this class.

Run. Expected: PASS.

- [ ] **Step 6.10: Run all OneDriveInteractiveAuthContractTest cases together**

Run: `(cd core && ./gradlew :providers:onedrive:test --tests "*.OneDriveInteractiveAuthContractTest" -q 2>&1 | tail -20)`
Expected: 7 tests pass.

- [ ] **Step 6.11: Commit the test + the test seam**

```bash
git add core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/OneDriveInteractiveAuthContractTest.kt \
        core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProviderFactory.kt
git commit -m "test(UD-014): OneDriveInteractiveAuthContractTest — 7 orthogonal invariants

Adds the ticket's acceptance test driven by Ktor MockEngine. Seven
named tests, one invariant each (capability, well-formed payload,
success-persists-token, pending-stays-resolvable, expired-error,
cancel-releases, registry-drains-after-terminal-outcomes).

Also: open OneDriveProviderFactory + internal newOAuthServiceForBegin
seam so the test can inject MockEngine without touching production
code paths.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 7: Rewrite `AuthTool.kt` to use only the SPI

**Files:**
- Modify: `core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt` (full rewrite)

- [ ] **Step 7.1: Replace the file contents**

Write the entire file (overwriting) with the agnostic version:

```kotlin
package org.krost.unidrive.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.CompleteAuthResult
import java.util.concurrent.ConcurrentHashMap

/**
 * UD-216 — Two-phase device-code auth over MCP, made provider-agnostic
 * by UD-014. The actual flow body lives behind ProviderFactory's
 * beginInteractiveAuth / completeInteractiveAuth / cancelInteractiveAuth
 * SPI methods; this file is just the JSON-RPC adapter.
 *
 * Per-handle device-flow state (HTTP clients, device codes, expiry) is
 * owned by the provider that issued the handle. This file's only
 * persistent state is the [McpHandleRouter] — a flat map from handle
 * to provider-type-id, used to route auth_complete back to the right
 * factory.
 */
internal object McpHandleRouter {
    private val routes: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    fun register(handle: String, providerType: String) {
        routes[handle] = providerType
    }

    fun providerFor(handle: String): String? = routes[handle]

    fun forget(handle: String) {
        routes.remove(handle)
    }
}

val authBeginTool =
    ToolDef(
        name = "unidrive_auth_begin",
        description =
            "Start the interactive auth flow for the current profile (if it supports interactive auth). " +
                "Returns a continuation handle plus provider-specific instructions (e.g. a verification URL " +
                "and user code for device-flow). Call unidrive_auth_complete with the handle to advance.",
        inputSchema = objectSchema(),
        handler = ::handleAuthBegin,
    )

val authCompleteTool =
    ToolDef(
        name = "unidrive_auth_complete",
        description =
            "Poll once for an outstanding auth flow started by unidrive_auth_begin. " +
                "Returns status=pending (retry later), status=ok (token persisted to disk), " +
                "or status=failed with an error message. The handle is cleared on ok/failed.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("continuation_handle", stringProp("Handle returned by unidrive_auth_begin"))
                    },
                required = listOf("continuation_handle"),
            ),
        handler = ::handleAuthComplete,
    )

private fun handleAuthBegin(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val factory = org.krost.unidrive.ProviderRegistry.get(ctx.profileInfo.type)
    if (factory == null || !factory.supportsInteractiveAuth()) {
        return buildToolResult(
            "Provider '${ctx.profileInfo.type}' does not support interactive auth " +
                "(unidrive_auth_begin / unidrive_auth_complete).",
            isError = true,
        )
    }

    val result: BeginAuthResult =
        try {
            runBlocking { factory.beginInteractiveAuth(ctx.profileDir) }
        } catch (e: Exception) {
            return buildToolResult(
                "Failed to start interactive auth: ${e.message ?: e.javaClass.simpleName}",
                isError = true,
            )
        }

    McpHandleRouter.register(result.continuationHandle, ctx.profileInfo.type)

    return buildToolResult(
        buildJsonObject {
            put("profile", ctx.profileName)
            put("continuation_handle", result.continuationHandle)
            for ((k, v) in result.fields) put(k, v)
        }.toString(),
    )
}

private fun handleAuthComplete(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val handle =
        args["continuation_handle"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'continuation_handle' argument", isError = true)

    val providerType =
        McpHandleRouter.providerFor(handle)
            ?: return buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", "Unknown or expired continuation_handle. Call unidrive_auth_begin again.")
                }.toString(),
                isError = true,
            )

    val factory =
        org.krost.unidrive.ProviderRegistry.get(providerType)
            ?: return buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", "Provider '$providerType' no longer registered.")
                }.toString(),
                isError = true,
            )

    val outcome: CompleteAuthResult =
        try {
            runBlocking { factory.completeInteractiveAuth(ctx.profileDir, handle) }
        } catch (e: Exception) {
            McpHandleRouter.forget(handle)
            return buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", e.message ?: e.javaClass.simpleName)
                }.toString(),
            )
        }

    return when (outcome) {
        is CompleteAuthResult.Pending ->
            buildToolResult(
                buildJsonObject {
                    put("status", "pending")
                    put("retry_after_seconds", outcome.retryAfterSeconds)
                }.toString(),
            )
        CompleteAuthResult.Success -> {
            McpHandleRouter.forget(handle)
            buildToolResult(
                buildJsonObject {
                    put("status", "ok")
                    put("profile", ctx.profileName)
                }.toString(),
            )
        }
        is CompleteAuthResult.Failure -> {
            McpHandleRouter.forget(handle)
            buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", outcome.message)
                }.toString(),
            )
        }
    }
}
```

The old `DeviceFlowState`, `DeviceFlowRegistry`, `OAuthService` imports, `OneDriveConfig` imports, `DeviceCodeResponse` import, `AuthenticationException` import, and the `// allow: UD-014` block are all gone.

- [ ] **Step 7.2: Compile `:app:mcp`**

Run: `(cd core && ./gradlew :app:mcp:compileKotlin -q)`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7.3: Run the existing `AdminToolsTest` to confirm wire-format invariants hold**

Run: `(cd core && ./gradlew :app:mcp:test --tests "*.AdminToolsTest" -q 2>&1 | tail -20)`
Expected: all cases pass — in particular:
- `auth_begin rejects providers that do not support interactive auth` (capability gate)
- `auth_complete without continuation_handle returns error` (early arg-check)
- `auth_complete with unknown handle returns failed status` (now via `McpHandleRouter.providerFor` returning null)

If any case fails, **stop and investigate** — the failure indicates a wire-format regression that violates the spec's §5 invariant. Do not edit the test to match the new output; edit the code to restore the invariant.

- [ ] **Step 7.4: Commit**

```bash
git add core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt
git commit -m "refactor(UD-014): make AuthTool provider-agnostic; drop OneDrive narrowing guard

Replaces the OneDrive-specific body with calls to the new ProviderFactory
SPI (begin/complete/cancel InteractiveAuth). DeviceFlowRegistry collapses
to McpHandleRouter — a flat handle→provider-type map. Imports of
OAuthService, OneDriveConfig, DeviceCodeResponse, AuthenticationException
are gone. The // allow: UD-014 marker and the if (type != \"onedrive\")
block are deleted.

Wire format unchanged: AdminToolsTest's auth_* cases pass without edits.
Adds 'Provider X no longer registered' as a new error path (defensive
guard against a registry race between begin and complete).

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 8: Add the cross-cutting SPI snapshot test

**Files:**
- Create: `core/app/core/src/test/kotlin/org/krost/unidrive/InteractiveAuthSpiContractTest.kt`

- [ ] **Step 8.1: Locate a known non-OAuth factory**

The test needs to assert "non-OAuth factory uses default throwing sentinels." `LocalFsProviderFactory` is the canonical choice — it's a simple, dependency-free local filesystem provider with no auth.

Confirm: `grep -l "class LocalFsProviderFactory" core/providers/localfs/src/main/kotlin/` → returns the path. Confirm it doesn't override `supportsInteractiveAuth`.

- [ ] **Step 8.2: Write the test**

```kotlin
package org.krost.unidrive

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.localfs.LocalFsProviderFactory
import org.krost.unidrive.onedrive.OneDriveProviderFactory
import java.nio.file.Files
import java.util.ServiceLoader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * UD-014 cross-cutting invariant: every factory that declares
 * supportsInteractiveAuth() == true MUST override beginInteractiveAuth
 * (so the throwing default never reaches users). The complementary
 * invariant: a known non-OAuth factory MUST keep the throwing default.
 *
 * If this test is removed, a future provider could declare capability
 * support without an override and silently ship a misexecuting flow.
 */
class InteractiveAuthSpiContractTest {
    private lateinit var tmpProfileDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tmpProfileDir = Files.createTempDirectory("ud-014-spi-")
    }

    @AfterTest
    fun tearDown() {
        tmpProfileDir.toFile().deleteRecursively()
    }

    @Test
    fun interactive_auth_capability_and_override_agree() {
        val factories = ServiceLoader.load(ProviderFactory::class.java).toList()
        assertTrue(factories.isNotEmpty(), "ServiceLoader returned no factories")

        for (factory in factories) {
            if (!factory.supportsInteractiveAuth()) continue
            try {
                runBlocking { factory.beginInteractiveAuth(tmpProfileDir) }
            } catch (e: UnsupportedOperationException) {
                fail(
                    "Factory '${factory.id}' declares supportsInteractiveAuth=true " +
                        "but did not override beginInteractiveAuth (throwing default reached): ${e.message}",
                )
            } catch (e: Throwable) {
                // Any other Throwable (network error, missing config, ...) is
                // tolerated — it proves the override exists.
            }
        }
    }

    @Test
    fun non_oauth_factory_uses_default_throwing_sentinels() {
        val localfs = LocalFsProviderFactory()
        assertFalse(
            localfs.supportsInteractiveAuth(),
            "LocalFsProviderFactory must not declare interactive-auth support",
        )

        try {
            runBlocking { localfs.beginInteractiveAuth(tmpProfileDir) }
            fail("LocalFsProviderFactory.beginInteractiveAuth should throw UnsupportedOperationException by default")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message?.contains("no interactive auth flow") == true)
        }
    }
}
```

Note: this test is in `:app:core` but imports both `LocalFsProviderFactory` and `OneDriveProviderFactory`. `:app:core` does not normally depend on provider modules. Add the test-only dependencies in `core/app/core/build.gradle.kts`:

- [ ] **Step 8.3: Wire up test dependencies in `core/app/core/build.gradle.kts`**

Look at the existing `dependencies { ... }` block. Add (or extend an existing `testImplementation` line):

```kotlin
    testImplementation(project(":providers:localfs"))
    testImplementation(project(":providers:onedrive"))
```

If these `testImplementation(project(...))` lines already exist for some other reason (e.g. a pre-existing cross-module contract test), don't duplicate them. Confirm via:

```bash
grep -nE "providers:localfs|providers:onedrive" core/app/core/build.gradle.kts
```

- [ ] **Step 8.4: Compile and run the new test**

Run: `(cd core && ./gradlew :app:core:test --tests "*.InteractiveAuthSpiContractTest" -q 2>&1 | tail -20)`
Expected: both tests pass. The first iterates over all `ServiceLoader`-discovered factories; today the only one that declares `supportsInteractiveAuth=true` is OneDrive, and Task 5 made sure it overrides `beginInteractiveAuth`.

If the test fails because `OneDriveProviderFactory.beginInteractiveAuth(tmpProfileDir)` does a real HTTP call to `login.microsoftonline.com/.../devicecode` and that throws something other than `UnsupportedOperationException` (e.g. `ConnectException` in offline test runners), that's a **pass** — the override exists. The test catches `Throwable` exactly for this reason.

- [ ] **Step 8.5: Commit**

```bash
git add core/app/core/src/test/kotlin/org/krost/unidrive/InteractiveAuthSpiContractTest.kt \
        core/app/core/build.gradle.kts
git commit -m "test(UD-014): InteractiveAuthSpiContractTest — capability/override agreement

ServiceLoader-driven snapshot: any factory that declares
supportsInteractiveAuth=true must override beginInteractiveAuth.
Companion test: LocalFsProviderFactory keeps the throwing default
(orthogonal invariant — catches accidental flips).

Adds testImplementation deps on :providers:localfs + :providers:onedrive
in :app:core (only the test depends on them; production code does not).

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 9: Delete dead code

Now that everything is wired and tested, the old MCP-side `DeviceFlowState` / `DeviceFlowRegistry` are gone (Task 7 overwrote the file entirely). Verify nothing else references them.

- [ ] **Step 9.1: Sanity-grep for dead refs**

Run: `grep -rn "DeviceFlowState\|DeviceFlowRegistry" core/app core/providers --include="*.kt"`

Expected output: only references in `OneDriveDeviceFlowState` / `OneDriveDeviceFlowRegistry` (the renamed copies from Task 4) and in `OneDriveInteractiveAuthContractTest.kt` (Step 6.9). No references to the bare `DeviceFlowState` / `DeviceFlowRegistry` in `:app:mcp` should remain.

If any remain, edit them out — they're orphans.

- [ ] **Step 9.2: Sanity-grep for the `allow: UD-014` marker**

Run: `grep -rn "UD-014" core/app core/providers --include="*.kt"`

Expected output: zero matches in `.kt` files (the BACKLOG.md entry still has the ticket prose, but no code mentions UD-014 as a known-issue marker anymore). If any are present and are *not* docstring references to this ticket as motivation (e.g. "UD-014: lifted from …"), delete the marker.

- [ ] **Step 9.3: Confirm no orphan imports**

Run: `(cd core && ./gradlew :app:mcp:compileKotlin -q --warning-mode=all 2>&1 | grep -E "unused|imported|warning" | head -5)`

Expected: no unused-import warnings related to `OAuthService`, `OneDriveConfig`, `DeviceCodeResponse`, `AuthenticationException`. If any surface, edit `AuthTool.kt` to remove them. (Task 7's rewrite removed them already; this is a paranoia check.)

- [ ] **Step 9.4: No commit needed if everything was clean.**

If Steps 9.1–9.3 found anything, commit the cleanup:

```bash
git add core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt
git commit -m "chore(UD-014): drop orphan refs to DeviceFlowState / OneDrive imports

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 10: Full build, ktlint sync, final sanity

- [ ] **Step 10.1: Full Gradle build**

Run: `(cd core && ./gradlew build -q 2>&1 | tail -30)`

Expected: `BUILD SUCCESSFUL`. This runs unit tests + ktlint + jacoco across every module. If ktlint complains about the new files, that's expected — Step 10.2 fixes it.

If any *test* fails (not ktlint), stop and investigate. Likely suspects: a wire-format regression in `AdminToolsTest`, or a ServiceLoader-discovery surprise in `InteractiveAuthSpiContractTest`.

- [ ] **Step 10.2: Sync ktlint baselines for the three affected modules**

Run each in order (one at a time, so you can review the diff):

```bash
scripts/dev/ktlint-sync.sh --module :app:core
scripts/dev/ktlint-sync.sh --module :providers:onedrive
scripts/dev/ktlint-sync.sh --module :app:mcp
```

Each invocation runs `ktlintFormat` then regenerates baselines atomically. Review the resulting `git diff` — only the three baseline.xml files (or the new files we just added, if any auto-format adjustments happened) should change.

- [ ] **Step 10.3: Commit the ktlint sync**

```bash
git add core/app/core/config/ktlint/baseline.xml \
        core/providers/onedrive/config/ktlint/baseline.xml \
        core/app/mcp/config/ktlint/baseline.xml \
        core/app/core/src core/providers/onedrive/src core/app/mcp/src
git commit -m "style(UD-014): ktlint baseline sync for :app:core, :providers:onedrive, :app:mcp

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

(If any of the four `git add` paths have no changes, drop them — `git add` is fine with missing paths only via `git add -A` style; here use only paths that exist.)

- [ ] **Step 10.4: Final build, all green**

Run: `(cd core && ./gradlew build -q 2>&1 | tail -10)`
Expected: `BUILD SUCCESSFUL`.

---

## Task 11: Move UD-014 to CLOSED.md (separate commit)

Per CLAUDE.md commit etiquette: "Always commit the ticket move (BACKLOG → CLOSED) separately from the code change, with `resolved_by: commit <sha>` pointing at the immediately-preceding code commit."

- [ ] **Step 11.1: Note the SHA of the last code-touching commit (the ktlint sync from Step 10.3, or Task 9's cleanup if no ktlint changes, or Task 8 otherwise)**

Run: `git log --oneline -5`

The "code commit" is the most recent non-docs commit that completes the UD-014 work. Note its SHA.

- [ ] **Step 11.2: Close the ticket via the backlog script**

Run: `python3 scripts/dev/backlog.py close UD-014 --commit <SHA> --note "AuthTool now provider-agnostic via ProviderFactory.{begin,complete,cancel}InteractiveAuth; OneDrive owns the device-flow registry; AdminToolsTest passes unchanged; contract + SPI snapshot tests added."`

This atomically moves the entry from `BACKLOG.md` to `CLOSED.md`. The script refuses if the ID isn't in BACKLOG.md or if the close has already been recorded — that's a safety net, not a problem.

- [ ] **Step 11.3: Commit the move**

```bash
git add docs/backlog/BACKLOG.md docs/backlog/CLOSED.md
git commit -m "docs(UD-014): close — landed in <SHA>

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

- [ ] **Step 11.4: Confirm the branch graph**

Run: `git log --oneline main..HEAD`

Expected: a sequence ending in the close commit. Counts roughly:
- 1× spec wip (already on branch from before)
- 1× spec review fixes (already on branch from before)
- 8× feat/refactor/test commits (Tasks 1–8, possibly +1 from Task 9, +1 from Task 10's ktlint sync)
- 1× docs close

---

## Task 12: PR

- [ ] **Step 12.1: Push the branch**

```bash
git push -u origin spec/UD-014-mcp-auth-provider-agnostic
```

- [ ] **Step 12.2: Open the PR with `gh`**

```bash
gh pr create --title "UD-014: provider-agnostic MCP interactive-auth via ProviderFactory.beginInteractiveAuth()" \
  --body "$(cat <<'EOF'
## Summary
- Adds `BeginAuthResult` / `CompleteAuthResult` + three default SPI methods on `ProviderFactory` (`begin` / `complete` / `cancel` InteractiveAuth).
- Lifts OneDrive's device-flow plumbing (`OAuthService` lifecycle, `DeviceFlowState`, `DeviceFlowRegistry`) out of `:app:mcp` into `core/providers/onedrive`.
- `AuthTool.kt` becomes provider-agnostic: drops `OAuthService` / `OneDriveConfig` / `DeviceCodeResponse` / `AuthenticationException` imports and the `// allow: UD-014` narrowing guard. Wire format identical (verified by unchanged `AdminToolsTest` cases).
- Adds two contract tests with seven + two orthogonal invariants (per CLAUDE.md "orthogonal invariant decomposition").

Spec: [`docs/specs/2026-05-16-ud-014-mcp-auth-provider-agnostic-design.md`](docs/specs/2026-05-16-ud-014-mcp-auth-provider-agnostic-design.md).

## Test plan
- [x] `./gradlew build` green (unit + ktlint + jacoco)
- [x] `AdminToolsTest` `auth_*` cases pass unchanged (wire-format invariants)
- [x] `OneDriveInteractiveAuthContractTest` — 7 named tests, all green, no live network
- [x] `InteractiveAuthSpiContractTest` — 2 named tests, ServiceLoader-driven snapshot
- [x] ktlint baselines synced for `:app:core`, `:providers:onedrive`, `:app:mcp`
- [x] `LiveGraphIntegrationTest` skips cleanly when `UNIDRIVE_INTEGRATION_TESTS` is unset (no regression in the live-test gate)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 12.3: Return the PR URL to the user.**

---

## Self-review

**Spec coverage:** every spec acceptance-criterion (§9) maps to a step:
- BeginAuthResult/CompleteAuthResult in `:app:core` → Task 1
- Three SPI methods with defaults → Task 2
- OneDriveProviderFactory overrides → Task 5
- `OAuthService` internal test constructor → Task 3
- `AuthTool.kt` drops imports → Task 7 (rewrite)
- `// allow: UD-014` deleted → Task 7
- `DeviceFlowState`/`DeviceFlowRegistry` deleted from `:app:mcp` → Task 7 (overwrites file) + Task 9 (verify orphan grep)
- `OneDriveInteractiveAuthContractTest` 6 named tests → Task 6 (Steps 6.1, 6.4–6.9) — **note: spec §6 lists 6 named tests, plan delivers 7 (registry-empty as a separate test). The extra test is the close-side counterpart of Risk #3 that the review explicitly asked for; consistent with the spec edit at line 269.**
- `InteractiveAuthSpiContractTest` 2 named tests → Task 8
- Existing `AdminToolsTest` cases pass unchanged → Step 7.3 explicitly confirms
- `./gradlew build` green + ktlint sync → Task 10
- BACKLOG→CLOSED in a follow-up commit → Task 11

**Placeholder scan:** zero `TBD`/`TODO`/`fill in`/`similar to`. The one `TODO()` in Step 6.1 is intentional and explicitly replaced in Step 6.2. All commands have expected output. All code blocks are complete.

**Type consistency:**
- `BeginAuthResult.fields` is `Map<String, String>` everywhere.
- `CompleteAuthResult.Success` is `data object` everywhere (test compares with `assertEquals(CompleteAuthResult.Success, outcome)`, which works on singleton objects).
- `OneDriveDeviceFlowRegistry.sizeForTest()` is referenced in Steps 4.1 (definition) and 6.9 (usage); same signature.
- `newOAuthServiceForBegin(profileDir: Path): OAuthService` introduced in Step 6.2 (factory side) and Step 6.2 (test side) — both signatures match.
- `McpHandleRouter.register/providerFor/forget` defined in Step 7.1, used same file.
- `OAuthService` second-constructor-arg default in Task 3 is the same `HttpClient { … }` block from the original file — production call sites continue to work.

**Scope:** the plan is single-PR sized. Each task produces a coherent commit. Task 6 is the longest (11 steps) but each step is bite-sized.

No issues found.
