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
