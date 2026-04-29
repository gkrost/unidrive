package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.ProviderRegistry

/**
 * UD-283: validates the optional `profile` argument on an MCP tool call
 * against the active [ProfileContext].
 *
 * The MCP server is single-profile by design — its [ProfileContext] is
 * fixed at process startup (see `Main.kt:19`). We can't switch profiles
 * mid-session without a substantial refactor of the dispatch layer. The
 * minimum-viable contract that matches the CLI's behaviour:
 *
 *   - `args["profile"]` absent          → use the active context (no error)
 *   - `args["profile"] == ctx.profileName` → use the active context (no error)
 *   - `args["profile"]` is a name in `rawConfig.providers` but != ctx
 *     → error "Profile mismatch: this MCP server is configured for X.
 *       Restart with -p Y to use Y."
 *   - `args["profile"]` doesn't match any configured profile
 *     → error "Unknown profile: Y. Configured profiles: ..."
 *
 * Both error cases return a JSON-RPC `-32602 Invalid params` response with
 * `error.data` carrying the structured recovery context (configuredProfiles,
 * activeProfile, supportedProviderTypes). An LLM caller can read
 * `error.data` to reformulate its tool call without parsing the message
 * string.
 *
 * Returns `null` when validation passes. Otherwise returns a builder that
 * takes the JSON-RPC `id` and produces the error-response wire string —
 * letting the dispatch layer keep its `id`-handling consolidated.
 */
internal fun profileMismatchError(
    args: JsonObject,
    ctx: ProfileContext,
): ((JsonElement) -> String)? {
    val requested = args["profile"]?.jsonPrimitive?.contentOrNull ?: return null
    if (requested == ctx.profileName) return null

    val configured =
        ctx.rawConfig.providers.keys
            .toList()
            .sorted()
    val supportedTypes = ProviderRegistry.knownTypes.toList().sorted()
    val activeProfile = ctx.profileName

    val message =
        buildString {
            if (requested in configured) {
                append("Profile mismatch: requested '")
                append(requested)
                append("', but this MCP server is configured for the active profile '")
                append(activeProfile)
                append("'. Restart the MCP server with `unidrive-mcp -p ")
                append(requested)
                append("` to use it, or configure a per-profile MCP entry in your client.")
            } else {
                append("Unknown profile: ")
                append(requested)
                if (configured.isNotEmpty()) {
                    append(". Configured profiles: ")
                    append(configured.joinToString(", "))
                }
                append(". Supported provider types: ")
                append(supportedTypes.joinToString(", "))
                append(".")
            }
        }

    val data =
        buildJsonObject {
            put("requestedProfile", requested)
            put("activeProfile", activeProfile)
            putJsonArray("configuredProfiles") {
                configured.forEach { add(it) }
            }
            putJsonArray("supportedProviderTypes") {
                supportedTypes.forEach { add(it) }
            }
        }

    return { id -> buildErrorResponse(id, ErrorCodes.INVALID_PARAMS, message, data) }
}
