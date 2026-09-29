package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.SyncReason
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test double for the RPC-handler routing/encoding tests. Subclasses the
 * (open) [SyncEngine] with throwaway in-temp deps and overrides the two
 * entry points the daemon RPC layer routes between, recording which one ran.
 * The heavyweight base constructor is satisfied with a never-touched stub
 * provider + a real temp StateDatabase; no engine internals run.
 */
class RecordingEngine(
    private val enumerateResult: EnumerateResult = EnumerateResult(ok = true),
    private val gate: CompletableDeferred<Unit>? = null,
) : SyncEngine(
        provider = NoopProvider,
        db = freshDb(),
        syncRoot = Files.createTempDirectory("ud-recording-root"),
    ) {
    @Volatile var enumerateCalled = false
    @Volatile var syncOnceCalled = false
    @Volatile var lastReset: Boolean? = null
    val enumerateCount = java.util.concurrent.atomic.AtomicInteger(0)

    override suspend fun enumerateRemoteIntoState(reset: Boolean): EnumerateResult {
        enumerateCalled = true
        enumerateCount.incrementAndGet()
        lastReset = reset
        gate?.await()
        return enumerateResult
    }

    override suspend fun syncOnce(
        dryRun: Boolean,
        forceDelete: Boolean,
        reason: SyncReason,
        skipTransfers: Boolean,
        skipRemoteGather: Boolean,
    ) {
        syncOnceCalled = true
        lastReset = null
        gate?.await()
    }

    companion object {
        private fun freshDb(): StateDatabase {
            val dir = Files.createTempDirectory("ud-recording-db")
            return StateDatabase(dir.resolve("state.db")).also { it.initialize() }
        }
    }

    private object NoopProvider : CloudProvider {
        override val id = "noop"
        override val displayName = "Noop"
        override var isAuthenticated = true

        override fun capabilities() = emptySet<org.krost.unidrive.Capability>()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String): CloudItem = error("noop")

        override suspend fun download(remotePath: String, destination: Path): Long = error("noop")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("noop")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("noop")

        override suspend fun createFolder(path: String): CloudItem = error("noop")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("noop")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(emptyList(), "x", false)

        override suspend fun quota() = QuotaInfo(0, 0, 0)
    }
}
