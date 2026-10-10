package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine tells the provider when a pass is a preview, through ScanContext.readOnly, so a provider can
 * skip the state it would persist during delta (OneDrive's delta_last_seen).
 */
class DryRunScanContextTest {
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        // Dry-runs require a disposable database; an in-memory one serves both passes.
        db = StateDatabase(Files.createTempDirectory("ud-400-db").resolve("state.db"), inMemory = true)
        db.initialize()
        provider = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("ud-400-root"),
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a dry-run gather is read-only for the provider and a real one is not`() =
        runTest {
            engine.syncOnce(dryRun = true)
            assertEquals(true, provider.lastScanContext?.readOnly)

            engine.syncOnce()
            assertEquals(false, provider.lastScanContext?.readOnly)
        }
}
