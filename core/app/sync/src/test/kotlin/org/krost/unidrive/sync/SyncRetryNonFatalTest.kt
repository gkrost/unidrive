package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * UD-248 regression: three consecutive per-action failures in the
 * sequential pass must no longer abort the whole sync. Prior behaviour
 * rethrew `ProviderException` at the 3rd failure, which triggered the
 * watch loop to restart `syncOnce` and re-enumerate the entire remote
 * tree on every provider-side transient 500 burst — expensive for
 * 22k+-file profiles (observed in the 2026-04-19 internxt capture).
 *
 * The test exercises Pass 1 (sequential actions — deletes in this
 * case) since that's where the `>= 3 throw` lived. Pass 2's upload
 * path has always been non-fatal on per-item failures.
 */
class SyncRetryNonFatalTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-retry-nofatal-test")
        val dbPath = Files.createTempDirectory("unidrive-retry-nofatal-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        )

    @Test
    fun `syncOnce does not throw on 3 consecutive sequential-pass failures`() =
        runTest {
            // Seed DB with three entries whose remote-side has been deleted
            // (provider.deltaItems is empty, DB has state → reconciler emits
            // three DeleteLocal/DeleteRemote actions).
            //
            // Simpler repro: seed DB entries that the reconciler will want to
            // remove locally; make delete fail 3+ times to trip the
            // consecutive counter in Pass 1.
            val now = java.time.Instant.now()
            for (i in 1..5) {
                db.upsertEntry(
                    org.krost.unidrive.sync.model.SyncEntry(
                        path = "/stale$i.txt",
                        remoteId = "id-stale$i",
                        remoteHash = "h$i",
                        remoteSize = 10,
                        remoteModified = now,
                        localMtime = now.toEpochMilli(),
                        localSize = 10,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = now,
                    ),
                )
            }
            provider.deltaCursor = "c1" // fresh cursor so delta returns "no items"
            provider.deleteFailCount = 3 // fail the first 3 deletes

            val outcome = runCatching { engine().syncOnce(reason = SyncReason.MANUAL) }
            assertFalse(
                outcome.isFailure,
                "UD-248: 3 consecutive Pass-1 action failures must NOT abort the sync pass; " +
                    "got ${outcome.exceptionOrNull()?.javaClass?.simpleName}: " +
                    "${outcome.exceptionOrNull()?.message}",
            )
        }
}
