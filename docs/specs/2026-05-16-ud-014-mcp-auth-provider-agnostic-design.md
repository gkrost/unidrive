# UD-014 — Provider-agnostic MCP interactive-auth via `ProviderFactory.beginInteractiveAuth()`

**Status:** design
**Opened:** 2026-05-16
**Ticket:** [UD-014](../backlog/BACKLOG.md#ud-014)
**Predecessor:** [2026-05-02 provider-SPI contract extension](2026-05-02-provider-spi-contract-extension-design.md) — added `supportsInteractiveAuth()` capability gate; this design removes the OneDrive narrowing guard that PR #9 added on top.

---

## 1. Problem

`core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt` is the MCP entry point for the `unidrive_auth_begin` / `unidrive_auth_complete` tools. Today it has a two-step gate (lines 80–104):

1. **Capability check** (correct, long-term): `if (factory == null || !factory.supportsInteractiveAuth()) return error`.
2. **OneDrive-specific narrowing** (stopgap, added by Codex review on PR #9): `if (ctx.profileInfo.type != "onedrive") return error`. Body below the gate hardcodes `OneDriveConfig`, `OAuthService`, `DeviceCodeResponse`, `OAuthService.DevicePollOutcome` — i.e. `:app:mcp` has a hard compile-time dependency on the OneDrive provider.

Without step 2, any future provider whose factory returns `true` from `supportsInteractiveAuth()` would enter the handler and **execute OneDrive's OAuth flow against a non-OneDrive profile**. The narrowing guard prevents that misexecution, at the cost of making `:app:mcp` un-extensible.

The proper fix: make the auth flow itself provider-agnostic in the SPI, then drop step 2.

## 2. Goals

- Add `beginInteractiveAuth` / `completeInteractiveAuth` / `cancelInteractiveAuth` to the `ProviderFactory` SPI, with safe defaults.
- Lift the OneDrive-specific device-flow plumbing out of `:app:mcp` into the OneDrive module.
- Reduce `:app:mcp`'s handle-registry to a router (handle → provider-type-id), with zero OneDrive imports.
- Preserve the exact wire format of the MCP `auth_begin` / `auth_complete` responses (no regression for existing MCP clients).
- Add a fixture-driven OneDrive contract test plus a cross-cutting SPI snapshot test.

## 3. Non-goals

- Implementing Internxt's `beginInteractiveAuth` (separate ticket if/when needed).
- Touching CLI `AuthCommand.kt`. CLI uses `CloudProvider.authenticate()` (blocking-poll inside the provider) — a genuinely different shape that doesn't need single-shot semantics.
- Adding a `unidrive_auth_cancel` MCP tool. The SPI method is wired up but unused by `AuthTool`; file as follow-up if the LLM caller ever needs to abandon mid-flow.
- Persisting handles across MCP restarts (same process-scoped semantics as today).
- Rewriting `OAuthService` or `OneDriveConfig` — the lift is mechanical.

## 4. Architecture

Three modules change, each in a tight role:

| Module | Role after UD-014 |
|---|---|
| `:app:core` | SPI — adds three default methods on `ProviderFactory` + two carrier types (`BeginAuthResult`, `CompleteAuthResult`). All defaults safe; `supportsInteractiveAuth()` gate (already in tree) keeps throwing defaults unreached. |
| `core/providers/onedrive` | Resource owner — overrides all three methods on `OneDriveProviderFactory`. Owns the in-process device-flow registry and the `OAuthService` lifecycle. |
| `:app:mcp` | Protocol surface — `handleAuthBegin` / `handleAuthComplete` lose all OneDrive imports. `DeviceFlowRegistry` collapses to a `ConcurrentHashMap<String, String>` (handle → provider-type-id) used only to route `complete` back to the right factory. |

### 4.1 SPI types (new file `core/app/core/src/main/kotlin/org/krost/unidrive/InteractiveAuth.kt`)

```kotlin
package org.krost.unidrive

import java.time.Instant

/**
 * Result of [ProviderFactory.beginInteractiveAuth].
 *
 * The map-carrier shape (rather than a typed subclass per flow) keeps
 * :app:mcp provider-agnostic at the type level: each provider populates
 * its own JSON-payload keys, and the MCP handler embeds them verbatim.
 * See §"Rationale — map carrier vs typed subclass" below.
 */
data class BeginAuthResult(
    /** Opaque handle the caller passes to completeInteractiveAuth. */
    val continuationHandle: String,
    /** Provider-supplied JSON-payload keys (string values to keep the
     *  wire format unambiguous). OneDrive populates verification_uri,
     *  user_code, interval_seconds, expires_in, message.
     *
     *  CONTRACT: must be an insertion-order-preserving Map (i.e. a
     *  [LinkedHashMap] built via [linkedMapOf] or `buildMap { … }`).
     *  The MCP handler emits these keys in iteration order, so a
     *  non-ordered map would produce nondeterministic JSON key order
     *  across runs. Use [linkedMapOf], not [mapOf] with > 5 entries,
     *  to keep this guarantee explicit. */
    val fields: Map<String, String>,
    /** Wall-clock deadline after which the handle is invalid. */
    val expiresAt: Instant,
    /** Provider-suggested polling interval, if applicable. */
    val retryAfterSeconds: Long? = null,
)

sealed class CompleteAuthResult {
    /** Tokens persisted to disk under profileDir; caller may proceed. */
    data object Success : CompleteAuthResult()

    /** Auth not finished — poll again after [retryAfterSeconds]. */
    data class Pending(val retryAfterSeconds: Long) : CompleteAuthResult()

    /** Terminal failure with a user-displayable message. */
    data class Failure(val message: String) : CompleteAuthResult()
}
```

### 4.2 SPI methods (added to `ProviderFactory`)

```kotlin
/**
 * Begin an interactive auth flow. Only invoked when
 * [supportsInteractiveAuth] returns true. The throwing default is the
 * sentinel that pairs with the capability gate.
 */
suspend fun beginInteractiveAuth(profileDir: Path): BeginAuthResult =
    throw UnsupportedOperationException("$id has no interactive auth flow")

/**
 * Resume an interactive auth flow with the handle from
 * [beginInteractiveAuth]. Returns Success / Pending / Error.
 */
suspend fun completeInteractiveAuth(
    profileDir: Path,
    continuationHandle: String,
): CompleteAuthResult =
    throw UnsupportedOperationException("$id has no interactive auth flow")

/**
 * Abandon an in-flight interactive auth flow. Default no-op.
 * Implementations release per-handle resources (HTTP clients, timers).
 */
suspend fun cancelInteractiveAuth(continuationHandle: String) { /* no-op */ }
```

**Why `profileDir` is also passed to `completeInteractiveAuth`:** OneDrive happens to capture `profileDir` inside the device-flow state at `begin` time, so the second parameter is redundant for *that* provider. The SPI keeps it because other providers may take a stateless-complete approach where the handle is opaque and the path is needed to know where to persist tokens. Forcing every provider through a stateful registry would over-constrain the SPI; passing the path makes the contract self-sufficient regardless of how each provider chooses to remember (or not remember) state.

### 4.3 OneDrive overrides (the lift)

Lift the current MCP-side `DeviceFlowState` + `DeviceFlowRegistry` (with the `OAuthService` field) into the OneDrive module, renamed to `OneDriveDeviceFlowState` / `OneDriveDeviceFlowRegistry`. Three `override` methods on `OneDriveProviderFactory`:

- `beginInteractiveAuth`: instantiate `OAuthService(OneDriveConfig(profileDir))`; call `getDeviceCode()` inside a `try { … } catch (e: Exception) { oauth.close(); throw e }` block — **the `HttpClient` must be closed if `getDeviceCode()` throws** because no handle has been issued yet, so the registry-based cleanup paths can't reach it. Only after a successful `getDeviceCode()` do we register the state under a UUID handle and return `BeginAuthResult` with the five OneDrive fields and a 15-minute `expiresAt`.
- `completeInteractiveAuth`: look up state by handle; on miss → `Failure("Unknown or expired…")`; on expiry → `Failure("Device code expired…")` + cleanup; otherwise call `pollOnceForToken` and translate `OAuthService.DevicePollOutcome` (Pending/Success/Failed) to `CompleteAuthResult`. **On `Success`, before returning, call `oauth.saveToken(outcome.token)` to persist credentials under `profileDir`** — this is the step that fulfills the `CompleteAuthResult.Success` KDoc contract ("Tokens persisted to disk"). `saveToken` is `suspend` and may throw; the `Success` path wraps it in `try { saveToken(…) } catch (e: Exception) { remove + close(); return Failure("Token received but save failed: ${e.message}") }`. **Every terminal path (Success, Failure from poll, Failure from save, Failure from expiry) must `remove + close()`**; the `Pending` path leaves the state in place for the next poll.
- `cancelInteractiveAuth`: `remove + close()` if present.

Detailed override bodies are mechanical translations of `AuthTool.handleAuthBegin` and `handleAuthComplete` as they stand in `main` at the start of this design (commit `71711cd` baseline). No business-logic change — the close-on-early-failure and the `saveToken` call are both already in the current MCP-side code at `AuthTool.kt:113` and `AuthTool.kt:207` respectively.

### 4.4 `:app:mcp` after the lift

`AuthTool.kt` drops these imports:
- `org.krost.unidrive.onedrive.OAuthService`
- `org.krost.unidrive.onedrive.OneDriveConfig`
- `org.krost.unidrive.onedrive.model.DeviceCodeResponse`
- `org.krost.unidrive.AuthenticationException`

And gains:
- `org.krost.unidrive.BeginAuthResult` (transitively, via `ProviderFactory.beginInteractiveAuth`)
- `org.krost.unidrive.CompleteAuthResult`

The handlers become:

```kotlin
internal object McpHandleRouter {
    private val routes = ConcurrentHashMap<String, String>()
    fun register(handle: String, providerType: String) { routes[handle] = providerType }
    fun providerFor(handle: String): String? = routes[handle]
    fun forget(handle: String) { routes.remove(handle) }
}

private fun handleAuthBegin(args: JsonObject, ctx: ProfileContext): JsonElement {
    val factory = ProviderRegistry.get(ctx.profileInfo.type)
    if (factory == null || !factory.supportsInteractiveAuth()) {
        return buildToolResult(
            "Provider '${ctx.profileInfo.type}' does not support interactive auth " +
                "(unidrive_auth_begin / unidrive_auth_complete).",
            isError = true,
        )
    }
    val result = try {
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

private fun handleAuthComplete(args: JsonObject, ctx: ProfileContext): JsonElement {
    val handle = args["continuation_handle"]?.jsonPrimitive?.content
        ?: return buildToolResult("Missing 'continuation_handle' argument", isError = true)
    val providerType = McpHandleRouter.providerFor(handle)
        ?: return buildToolResult(
            buildJsonObject {
                put("status", "failed")
                put("error", "Unknown or expired continuation_handle. Call unidrive_auth_begin again.")
            }.toString(),
            isError = true,
        )
    val factory = ProviderRegistry.get(providerType)
        ?: return buildToolResult(
            buildJsonObject {
                put("status", "failed")
                put("error", "Provider '$providerType' no longer registered.")
            }.toString(),
            isError = true,
        )
    val outcome = try {
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
        is CompleteAuthResult.Pending -> buildToolResult(
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

The old `DeviceFlowState`, `DeviceFlowRegistry`, and the `// allow: UD-014` block are deleted. The `unidrive_auth_begin` tool description is broadened: drop "OneDrive only", say "for the current profile (if it supports interactive auth)".

## 5. Wire format invariants (regression-zero)

| Tool call | Today's keys | After UD-014 |
|---|---|---|
| `auth_begin` success | `profile`, `verification_uri`, `user_code`, `continuation_handle`, `interval_seconds`, `expires_in`, `message` | identical (re-derived from `BeginAuthResult.fields`) |
| `auth_complete` pending | `status=pending`, `retry_after_seconds` | identical |
| `auth_complete` ok | `status=ok`, `profile` | identical |
| `auth_complete` failed | `status=failed`, `error` | identical |
| `auth_begin` unsupported-provider error | text containing `"does not support interactive auth"` | identical |
| `auth_complete` missing-arg error | text containing `continuation_handle` | identical |
| `auth_complete` unknown-handle error | `status=failed`, `error` containing `continuation_handle` | identical |

This is the property that lets the existing `AdminToolsTest` cases continue to pass without shape edits.

**JSON key-order note:** today's `auth_begin` success payload emits `profile, verification_uri, user_code, continuation_handle, interval_seconds, expires_in, message`. After UD-014 the order becomes `profile, continuation_handle, verification_uri, user_code, interval_seconds, expires_in, message` (continuation_handle hoisted, then the provider's `fields` map in insertion order). This is a key-order change, not a key-set change. MCP clients parse by key, not by position, so this is not a wire regression. Documented here so the change is intentional rather than accidental. The `BeginAuthResult.fields` KDoc (§4.1) requires an insertion-order-preserving `Map` so the post-UD-014 order is deterministic across runs.

## 6. Tests

**Existing — must keep passing without shape change:**
- `AdminToolsTest.auth_begin rejects providers that do not support interactive auth` — passes against `localfs` (capability gate unchanged).
- `AdminToolsTest.auth_complete without continuation_handle returns error` — passes (early arg-check unchanged).
- `AdminToolsTest.auth_complete with unknown handle returns failed status` — passes; the unknown-handle path now lives in `McpHandleRouter.providerFor` returning `null`. JSON shape preserved.

**New 1 — `OneDriveInteractiveAuthContractTest`** (the ticket's acceptance test).

Location: `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/OneDriveInteractiveAuthContractTest.kt`.

Mechanism: add an internal-visibility test constructor `OAuthService(config: OneDriveConfig, httpClient: HttpClient)` so the test can inject a Ktor `MockEngine`. Internal-only API surface change; not exposed to other modules.

Assertions (orthogonal invariants, named per-test):
- `factory_declares_interactive_auth_support` — `OneDriveProviderFactory.supportsInteractiveAuth() == true`.
- `begin_returns_well_formed_payload` — `BeginAuthResult` has non-empty `continuationHandle`; `fields` contains `verification_uri`, `user_code`, `interval_seconds`, `expires_in`, `message`; `expiresAt` in the future; `retryAfterSeconds == 5` (matches fixture).
- `complete_success_persists_token_and_forgets_handle` — canned 2xx token response drives `CompleteAuthResult.Success`, `token.json` materialises under `profileDir`, calling `completeInteractiveAuth` again with the same handle returns `Failure("Unknown or expired…")`.
- `complete_authorization_pending_returns_pending` — `{"error":"authorization_pending"}` drives `Pending(retryAfterSeconds=5)`; handle still resolvable on next call.
- `complete_expired_token_returns_error_and_forgets_handle` — `{"error":"expired_token"}` drives `Failure("…expired…")` and second call returns `Failure("Unknown or expired…")`.
- `cancel_releases_handle` — `cancelInteractiveAuth(handle)`; subsequent `completeInteractiveAuth` returns `Failure("Unknown or expired…")`.
- `registry_is_empty_after_each_terminal_outcome` — after `Success`, `Failure(expired)`, `Failure(poll-failed)`, and `cancel`, the OneDrive device-flow registry reports zero entries. This is the close-side counterpart of Risk #3: removal alone is provable via "same handle returns Failure", but the registry-size assertion catches a regression where a future override would `remove` but skip `close()`. The test exposes the registry size via an `internal` test-only accessor on `OneDriveDeviceFlowRegistry`.

**New 2 — `InteractiveAuthSpiContractTest`** (the cross-cutting invariant).

Location: `core/app/core/src/test/kotlin/org/krost/unidrive/InteractiveAuthSpiContractTest.kt`.

For every factory discovered via `ServiceLoader<ProviderFactory>`:
- `interactive_auth_capability_and_override_agree` — if `supportsInteractiveAuth()` returns `true`, then calling `beginInteractiveAuth(tmpProfileDir)` must not throw `UnsupportedOperationException` (the throwing-default sentinel). Network exceptions are tolerated and indicate the override exists. The test catches `Throwable` (not just `Exception`) so a coroutine `CancellationException` from `runBlocking { … }` cannot escape unfiltered; it re-throws `UnsupportedOperationException` and tolerates everything else.
- `non_oauth_factory_uses_default_throwing_sentinels` — pick a known non-OAuth factory (e.g. `LocalFsProviderFactory`), confirm `supportsInteractiveAuth() == false`, and confirm `beginInteractiveAuth(tmpProfileDir)` throws `UnsupportedOperationException`. Catches accidental override-without-capability-flip and accidental flip-of-default.

The two tests enforce orthogonal invariants and are deliberately separate (per CLAUDE.md "orthogonal invariant decomposition"). If the contract test is removed, the OneDrive flow could silently regress without anyone noticing; if the SPI snapshot is removed, a future provider could declare support and ship a misexecuting override.

## 7. Risks

| # | Likelihood × blast | Risk | Mitigation |
|---|---|---|---|
| 1 | MID × LOW | `runBlocking` topology shift — `AuthTool` keeps the runBlocking (now wraps a `suspend` SPI method); OneDrive override is itself `suspend`. Net thread topology unchanged. | None needed; verify by `./gradlew test` on `:app:mcp` and on `:providers:onedrive`. |
| 2 | MID × MID | `AuthenticationException` import leaves `:app:mcp`. The two pre-existing catch arms in `AuthTool` are shape-identical (`e.message ?: e.javaClass.simpleName`); collapsing them in `:app:mcp` is correct. | The contract test asserts that an `AuthenticationException` thrown inside `pollOnceForToken` reaches the user as `CompleteAuthResult.Failure(message=<original message>)`. |
| 3 | LOW × HIGH | Lifetime leak on `OAuthService.HttpClient`. Every terminal path through `completeInteractiveAuth` must `remove + close()`. | The contract test's "after Success, the same handle returns Error" property indirectly proves removal. An assertion that `OneDriveDeviceFlowRegistry` reports zero entries after each terminal outcome closes the gap. |
| 4 | LOW × MID | `McpHandleRouter` state is process-scoped. MCP restart mid-flow invalidates handles. | Same as today; documented in the `McpHandleRouter` KDoc. |
| 5 | LOW × LOW | Internxt accidentally activating. After UD-014, even an accidental `supportsInteractiveAuth()=true` flip yields a clear `UnsupportedOperationException` from the throwing default, not a misexecuted OneDrive flow. | Strict improvement over today; no mitigation needed. |

## 8. Rationale

### Why a map carrier, not a typed subclass

Considered: `BeginAuthResult` as `sealed class`, with `DeviceFlowBeginResult` exposing structured fields and `AuthTool` narrowing via `when (result) { is DeviceFlowBeginResult -> … }`.

Rejected because: the moment `AuthTool.kt` does `when` over flow shapes, every new auth-flow forces an edit to `:app:mcp` — exactly the leak UD-014 is fixing. The point is that adding a new OAuth provider must not require touching `:app:mcp`. The map is the seam that buys that property. Cost (stringly-typed values) is paid once at the OneDrive side and is invisible at the SPI boundary. If a future requirement genuinely needs typed access on the MCP side (e.g. for a different `Content-Type` per flow), it would be added via a new generic field on `BeginAuthResult` (`val contentType: String? = null`), not by reintroducing a `when`.

### Why provider owns the handle registry

Considered: opaque-blob handles encoding `(deviceCode, expiresAt, providerType)` so `completeInteractiveAuth` is stateless on the provider side and MCP keeps only a flat map.

Rejected because: would force a new `OAuthService` + `HttpClient` on every poll (one TCP/TLS handshake per call). The current `pollOnceForToken` reuses the keep-alive connection from `getDeviceCode`. Stateless complete is appealing for MCP-restart survival, but MCP restarts mid-flow are rare; cheap connection reuse is the common case.

Also considered: MCP keeps a generic `AutoCloseable` carrier registry. Rejected because the map values would be typed `Any`/`AutoCloseable` — still a leak in spirit, and MCP becomes responsible for closing provider resources.

The chosen approach (each provider owns its own registry, MCP keeps a flat `handle → providerType` router) honours "provider owns the auth state" — the same principle that put `OAuthService` in the OneDrive module to begin with.

### Why `MockEngine` injection, not a `DeviceCodeClient` abstraction

Considered: factor begin/complete behind a thin internal `DeviceCodeClient` interface inside the OneDrive module, then test the override against a stub `DeviceCodeClient`.

Rejected because: two abstractions for one transport. `OAuthService` already *is* the abstraction over the raw HTTP; adding `DeviceCodeClient` on top adds a layer the rest of the code has to thread through, with the only consumer being the test. YAGNI — wait until a second provider has its own device-code flow with materially different HTTP shape; then extract `DeviceCodeClient` as a shared `:app:core` helper if and only if the two implementations want to share code. Today, OneDrive is the sole device-code shape.

### Why CLI is out of scope

`core/app/cli/src/main/kotlin/org/krost/unidrive/cli/AuthCommand.kt` uses `CloudProvider.authenticate()` — a blocking 15-minute poll loop that completes inside the provider's `authenticate()` method. That model assumes a human at a terminal waiting on `stdout`. The MCP shape is single-shot: each `complete` call does exactly one poll because the LLM caller cannot sit in `runBlocking { while(true) poll() }`. These are genuinely different shapes that justify different code paths; unifying them is a separate (and larger) ticket if ever warranted.

## 9. Acceptance criteria

- [ ] `BeginAuthResult` / `CompleteAuthResult` added in `:app:core` (`core/app/core/src/main/kotlin/org/krost/unidrive/InteractiveAuth.kt`).
- [ ] `ProviderFactory.{begin,complete,cancel}InteractiveAuth` added with the defaults specified in §4.2.
- [ ] `OneDriveProviderFactory` overrides all three, with `OneDriveDeviceFlowState` + `OneDriveDeviceFlowRegistry` lifted from `:app:mcp`.
- [ ] `OAuthService` gains an internal-visibility test constructor `(OneDriveConfig, HttpClient)`; production constructor unchanged.
- [ ] `AuthTool.kt` no longer imports `OAuthService`, `OneDriveConfig`, `DeviceCodeResponse`, or `AuthenticationException`.
- [ ] `// allow: UD-014` marker and the `if (type != "onedrive")` block deleted from `AuthTool.kt`.
- [ ] `DeviceFlowState` and `DeviceFlowRegistry` deleted from `:app:mcp`; replaced with `McpHandleRouter`.
- [ ] `OneDriveInteractiveAuthContractTest` added with the six assertions in §6 (New 1).
- [ ] `InteractiveAuthSpiContractTest` added with the two assertions in §6 (New 2).
- [ ] All three existing `AdminToolsTest` `auth_*` cases pass unchanged.
- [ ] `./gradlew build` green; ktlint baselines synced via `scripts/dev/ktlint-sync.sh --module :app:mcp` and `--module :providers:onedrive` and `--module :app:core`.
- [ ] BACKLOG → CLOSED transform for UD-014 via `python3 scripts/dev/backlog.py close UD-014 --commit <sha> --note "..."` in a separate follow-up commit.

## 10. Implementation order

(For the writing-plans handoff — not normative here.)

1. `:app:core` — add `InteractiveAuth.kt` + three SPI methods. Sanity: `./gradlew :app:core:test`.
2. `core/providers/onedrive` — internal `OAuthService(config, httpClient)` constructor; `OneDriveDeviceFlowRegistry`; three overrides on `OneDriveProviderFactory`. Add `OneDriveInteractiveAuthContractTest`. Sanity: `./gradlew :providers:onedrive:test`.
3. `:app:mcp` — replace `AuthTool` body with the agnostic version; delete `DeviceFlowRegistry`/`DeviceFlowState`. Sanity: `./gradlew :app:mcp:test`.
4. `:app:core` — add `InteractiveAuthSpiContractTest`. Sanity: full `./gradlew build`.
5. ktlint baseline sync per module.
6. Commit. Then close UD-014 in a follow-up commit.

## 11. Open follow-ups (filed, not done here)

- **`unidrive_auth_cancel` MCP tool** — SPI hook is in place; no consumer yet. File if/when LLM-driven cancel becomes a UX requirement.
- **Internxt `beginInteractiveAuth`** — Internxt has OAuth in `InternxtProvider.authenticate()` but a fundamentally different shape (no device flow). Separate ticket.
- **`DeviceCodeClient` extraction** — only worth doing once a second provider implements device-flow auth; until then, YAGNI per §8.
