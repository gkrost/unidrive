package org.krost.unidrive.onedrive

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * UD-014: lifted from :app:mcp/AuthTool.kt. Per-handle device-flow state
 * for OneDrive's interactive auth. The provider owns this registry
 * because it owns the OAuthService (and its HttpClient) lifecycle.
 *
 * Lifecycle invariant: every terminal outcome that resolves an existing
 * handle must call [OneDriveDeviceFlowRegistry.remove] and close the
 * resulting state's oauthService. The terminal arms are:
 *   - completeInteractiveAuth → Success
 *   - completeInteractiveAuth → Failure(expired)         (handle expired before poll)
 *   - completeInteractiveAuth → Failure(poll-exception)  (JSON parse / unexpected error)
 *   - completeInteractiveAuth → Failure(save-failed)     (saveToken threw)
 *   - completeInteractiveAuth → Failure(poll-Failed)     (DevicePollOutcome.Failed)
 *   - cancelInteractiveAuth                              (caller abandoned the flow)
 * Pending leaves the state in place for the next poll. The
 * "unknown-handle" Failure path returns without remove/close because no
 * state was ever registered.
 */
internal data class OneDriveDeviceFlowState(
    val deviceCode: String,
    val expiresAtMillis: Long,
    val oauthService: OAuthService,
    /** The profile folder holding the flow's [PendingDeviceFlow] file, so a cancel can delete it too. */
    val profileDir: java.nio.file.Path? = null,
)

internal object OneDriveDeviceFlowRegistry {
    private val states: ConcurrentHashMap<String, OneDriveDeviceFlowState> = ConcurrentHashMap()

    fun put(state: OneDriveDeviceFlowState): String {
        val handle = UUID.randomUUID().toString()
        states[handle] = state
        return handle
    }

    /** Re-registers a flow under the [handle] it was issued with (rebuilt from [PendingDeviceFlow] in a later process). */
    fun putWithHandle(
        handle: String,
        state: OneDriveDeviceFlowState,
    ) {
        states[handle] = state
    }

    fun get(handle: String): OneDriveDeviceFlowState? = states[handle]

    fun remove(handle: String): OneDriveDeviceFlowState? = states.remove(handle)

    /** UD-014 test-only: lets OneDriveInteractiveAuthContractTest assert
     *  the registry-is-empty-after-each-terminal-outcome invariant. */
    internal fun sizeForTest(): Int = states.size
}

/**
 * The device-code flow as it survives the process that began it: `auth begin` and `auth complete` of the
 * CLI are separate processes, so the registry above cannot carry the flow between them. Kept in the
 * profile folder through [org.krost.unidrive.auth.CredentialStore] (atomic write, owner-only folder and
 * file, like the token itself) and deleted at every terminal outcome. The device code is the secret
 * that redeems the token once the user has approved the sign-in.
 */
@kotlinx.serialization.Serializable
internal data class PendingDeviceFlow(
    val handle: String,
    val deviceCode: String,
    val expiresAtMillis: Long,
)

internal const val PENDING_DEVICE_FLOW_FILE = "device-flow.json"
