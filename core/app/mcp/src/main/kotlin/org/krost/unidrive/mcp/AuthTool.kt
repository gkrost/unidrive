package org.krost.unidrive.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.onedrive.OAuthService
import org.krost.unidrive.onedrive.OneDriveConfig
import org.krost.unidrive.onedrive.model.DeviceCodeResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * UD-216 — Two-phase device-code auth over MCP.
 *
 * The CLI's `auth --device-code` blocks for up to 15 minutes polling the
 * provider every ~5s. That model does not map onto single-shot MCP tool
 * calls, so we split the flow into `_begin` (issues the user-facing URL +
 * user code, returns an opaque handle) and `_complete` (one poll per call).
 *
 * Handles live in an in-process map. If the MCP server restarts mid-flow
 * the caller just starts over — device-codes are cheap and expire in 15 min
 * anyway.
 */
internal data class DeviceFlowState(
    val profileName: String,
    val providerType: String,
    val deviceCode: String,
    val intervalSeconds: Long,
    val expiresAtMillis: Long,
    val oauthService: OAuthService,
)

internal object DeviceFlowRegistry {
    private val states: ConcurrentHashMap<String, DeviceFlowState> = ConcurrentHashMap()

    fun put(state: DeviceFlowState): String {
        val handle = UUID.randomUUID().toString()
        states[handle] = state
        return handle
    }

    fun get(handle: String): DeviceFlowState? = states[handle]

    fun remove(handle: String): DeviceFlowState? = states.remove(handle)
}

val authBeginTool =
    ToolDef(
        name = "unidrive_auth_begin",
        description =
            "Start the OAuth device-code flow for the current profile (OneDrive only). " +
                "Returns a verification URL, user code, and an opaque continuation handle. " +
                "Call unidrive_auth_complete with the handle to poll for token issuance.",
        inputSchema = objectSchema(),
        handler = ::handleAuthBegin,
    )

val authCompleteTool =
    ToolDef(
        name = "unidrive_auth_complete",
        description =
            "Poll once for an outstanding device-code flow started by unidrive_auth_begin. " +
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
    if (ctx.profileInfo.type != "onedrive") {
        return buildToolResult(
            "unidrive_auth_begin currently supports only provider type 'onedrive' " +
                "(got '${ctx.profileInfo.type}').",
            isError = true,
        )
    }

    val config = OneDriveConfig(tokenPath = ctx.profileDir)
    val oauth = OAuthService(config)

    val deviceCode: DeviceCodeResponse =
        try {
            runBlocking { oauth.getDeviceCode() }
        } catch (e: Exception) {
            oauth.close()
            return buildToolResult(
                "Failed to start device-code flow: ${e.message ?: e.javaClass.simpleName}",
                isError = true,
            )
        }

    val state =
        DeviceFlowState(
            profileName = ctx.profileName,
            providerType = ctx.profileInfo.type,
            deviceCode = deviceCode.deviceCode,
            intervalSeconds = deviceCode.interval,
            expiresAtMillis = System.currentTimeMillis() + deviceCode.expiresIn * 1000L,
            oauthService = oauth,
        )
    val handle = DeviceFlowRegistry.put(state)

    return buildToolResult(
        buildJsonObject {
            put("profile", ctx.profileName)
            put("verification_uri", deviceCode.verificationUri)
            put("user_code", deviceCode.userCode)
            put("continuation_handle", handle)
            put("interval_seconds", deviceCode.interval)
            put("expires_in", deviceCode.expiresIn)
            put("message", deviceCode.message)
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

    val state =
        DeviceFlowRegistry.get(handle)
            ?: return buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", "Unknown or expired continuation_handle. Call unidrive_auth_begin again.")
                }.toString(),
                isError = true,
            )

    if (System.currentTimeMillis() > state.expiresAtMillis) {
        DeviceFlowRegistry.remove(handle)
        state.oauthService.close()
        return buildToolResult(
            buildJsonObject {
                put("status", "failed")
                put("error", "Device code expired. Call unidrive_auth_begin again.")
            }.toString(),
        )
    }

    val oauth = state.oauthService
    val outcome: OAuthService.DevicePollOutcome =
        try {
            runBlocking { oauth.pollOnceForToken(state.deviceCode) }
        } catch (e: AuthenticationException) {
            DeviceFlowRegistry.remove(handle)
            oauth.close()
            return buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", e.message ?: e.javaClass.simpleName)
                }.toString(),
            )
        } catch (e: Exception) {
            DeviceFlowRegistry.remove(handle)
            oauth.close()
            return buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", e.message ?: e.javaClass.simpleName)
                }.toString(),
            )
        }

    return when (outcome) {
        is OAuthService.DevicePollOutcome.Pending ->
            buildToolResult(
                buildJsonObject {
                    put("status", "pending")
                    put("retry_after_seconds", outcome.retryAfterSeconds)
                }.toString(),
            )
        is OAuthService.DevicePollOutcome.Success -> {
            try {
                runBlocking { oauth.saveToken(outcome.token) }
            } catch (e: Exception) {
                DeviceFlowRegistry.remove(handle)
                oauth.close()
                return buildToolResult(
                    buildJsonObject {
                        put("status", "failed")
                        put("error", "Token received but save failed: ${e.message}")
                    }.toString(),
                )
            }
            DeviceFlowRegistry.remove(handle)
            oauth.close()
            buildToolResult(
                buildJsonObject {
                    put("status", "ok")
                    put("profile", state.profileName)
                }.toString(),
            )
        }
        is OAuthService.DevicePollOutcome.Failed -> {
            DeviceFlowRegistry.remove(handle)
            oauth.close()
            buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", outcome.message)
                }.toString(),
            )
        }
    }
}
