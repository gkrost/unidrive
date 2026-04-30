package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.ConflictPolicy
import org.slf4j.MDC
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * UD-254 regression: every `syncOnce` call must populate the `scan` MDC
 * key for the duration of the call and clear it on completion so
 * downstream log lines inherit a unique-per-pass id for diagnosis.
 *
 * Tests are deliberately narrow — they verify the MDC contract, not the
 * sync behaviour (SyncEngineTest covers that).
 */
class SyncScanIdTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-scanid-test")
        val dbPath = Files.createTempDirectory("unidrive-scanid-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        engine =
            SyncEngine(
                provider = SyncEngineTest.FakeCloudProvider(),
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
            )
        MDC.remove("scan")
    }

    @AfterTest
    fun tearDown() {
        db.close()
        MDC.remove("scan")
    }

    @Test
    fun `syncOnce clears scan MDC on exit`() =
        runTest {
            assertNull(MDC.get("scan"), "scan MDC must be unset before call")
            engine.syncOnce(reason = SyncReason.MANUAL)
            assertNull(MDC.get("scan"), "scan MDC must be cleared after successful syncOnce")
        }

    @Test
    fun `syncOnce clears scan MDC on exception`() =
        runTest {
            val failingProvider = SyncEngineTest.FakeCloudProvider()
            failingProvider.deltaFailCount = 999 // fail every call
            val failingEngine =
                SyncEngine(
                    provider = failingProvider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                )
            assertNull(MDC.get("scan"))
            try {
                failingEngine.syncOnce(reason = SyncReason.WATCH_POLL)
            } catch (_: Exception) {
                // expected
            }
            assertNull(MDC.get("scan"), "scan MDC must be cleared even when syncOnce throws")
        }

    @Test
    fun `syncOnce preserves a prior scan MDC value`() =
        runTest {
            MDC.put("scan", "outer-id")
            try {
                engine.syncOnce(reason = SyncReason.MANUAL)
                assertEquals(
                    "outer-id",
                    MDC.get("scan"),
                    "nested syncOnce must restore the outer scan id",
                )
            } finally {
                MDC.remove("scan")
            }
        }

    @Test
    fun `two syncOnce calls produce different scan ids inside`() =
        runTest {
            val observed = mutableSetOf<String>()
            val capturer =
                object : ProgressReporter {
                    override fun onScanProgress(
                        phase: String,
                        count: Int,
                    ) {
                        MDC.get("scan")?.let { observed += it }
                    }

                    override fun onActionCount(total: Int) {}

                    override fun onActionProgress(
                        index: Int,
                        total: Int,
                        action: String,
                        path: String,
                    ) {}

                    override fun onTransferProgress(
                        path: String,
                        bytesTransferred: Long,
                        totalBytes: Long,
                    ) {}

                    override fun onSyncComplete(
                        downloaded: Int,
                        uploaded: Int,
                        conflicts: Int,
                        durationMs: Long,
                        actionCounts: Map<String, Int>,
                        failed: Int,
                    ) {}

                    override fun onWarning(message: String) {}
                }
            val e =
                SyncEngine(
                    provider = SyncEngineTest.FakeCloudProvider(),
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = capturer,
                )
            e.syncOnce(reason = SyncReason.MANUAL)
            e.syncOnce(reason = SyncReason.MANUAL)
            assertEquals(
                2,
                observed.size,
                "each syncOnce must generate a fresh scan id; observed=$observed",
            )
        }
}
