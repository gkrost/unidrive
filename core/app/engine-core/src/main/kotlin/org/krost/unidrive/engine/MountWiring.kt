package org.krost.unidrive.engine

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.Logger
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * #560 U3: everything the mount front-end (`MountEngine`, :app:hydration) runs on, handed over by the
 * engine that builds the shared core ([MountHost], today `SyncEngine`). The instances are the ones the
 * mirror pass uses, so the two front-ends of one process share, as they did when the mount operations
 * lived in `SyncEngine`:
 *  - [guard]: scope, excludes and the daemon-wide transfer budget (UD-263);
 *  - [gather]: the recently-uploaded marks (#301), the collided paths (#401) and the view invalidation
 *    the gather reports to ([RemoteGather.invalidateView]);
 *  - [enumeration]: the single-flight remote enumeration and its tracker.
 *
 * One wiring per host. [frontEnd] keeps the one mount front-end built over it, so every adapter (the
 * CLI, `HydrationImpl`'s compatibility constructor, tests) shares its per-path hydrate locks and its
 * rescan guard.
 */
class MountWiring(
    val provider: CloudProvider,
    val db: StateDatabase,
    val guard: RemoteOperationGuard,
    val gather: RemoteGather,
    val enumeration: RemoteEnumeration,
    // The hydration-cache file of a logical path (the layout belongs to the host: the mirror's
    // Reconciler and the enumeration's reap read the same paths).
    val cachePathOf: (path: String) -> Path,
    val options: Options,
    // UD-113: the audit log of mutations, or null when none is configured.
    val auditLog: AuditSink?,
    // Byte progress of an upload, (path, transferred, total), for the host's progress reporter.
    val onTransferProgress: (path: String, transferred: Long, total: Long) -> Unit,
    // #301: whether a hydration upload of the path is queued or in flight. Late-bound in the CLI
    // (the hydration layer is built after the engine); the same lambda guards the enumeration's reap.
    val uploadInFlight: (path: String) -> Boolean,
    // The host's logger, so the moved log lines keep their logger name.
    val log: Logger,
) {
    /** Fixed per host. */
    data class Options(
        // The per-run --sync-path entries (the rescan's pending-row filter).
        val syncPaths: List<String> = emptyList(),
        // Verify a hydration download against the remote hash.
        val verifyIntegrity: Boolean = false,
    )

    private val frontEndRef = AtomicReference<Any?>(null)

    /**
     * The one mount front-end of this wiring: built by [build] on the first call, returned as is
     * afterwards. The type is the caller's; engine-core does not know the mount front-end.
     */
    fun <T : Any> frontEnd(build: (MountWiring) -> T): T {
        frontEndRef.get()?.let {
            @Suppress("UNCHECKED_CAST")
            return it as T
        }
        val built = build(this)
        @Suppress("UNCHECKED_CAST")
        return if (frontEndRef.compareAndSet(null, built)) built else frontEndRef.get() as T
    }
}

/** An engine that owns the shared core and can hand the mount front-end its [MountWiring] (#560 U3). */
interface MountHost {
    val mountWiring: MountWiring
}

/** UD-113: where a mount operation records a mutation (the host's `AuditLog`). */
interface AuditSink {
    fun emit(
        action: String,
        path: String,
        result: String,
        size: Long? = null,
        oldHash: String? = null,
        newHash: String? = null,
    )
}

/**
 * The one-way remote-to-state.db refresh as the daemon's verbs call it (`sync.enumerate`, the poller,
 * a mount-routed `refresh.run`). Both front-ends implement it over the same [RemoteEnumeration], so a
 * caller may hold either; the single-flight guard is the enumeration's.
 */
interface EnumerationEntryPoint {
    suspend fun enumerateRemoteIntoState(reset: Boolean): EnumerateResult
}
