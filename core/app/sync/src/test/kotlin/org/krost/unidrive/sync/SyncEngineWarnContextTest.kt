package org.krost.unidrive.sync

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.ConflictPolicy
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UD-253 regression, reshaped by #530: SyncEngine failure WARN lines emitted
 * from catch blocks must include the exception class name in the message, but
 * NOT the Throwable — the console appender's threshold is WARN, so a throwable
 * there printed the full JVM stack trace to stdout for every expected per-file
 * failure. The throwable lives on the matching DEBUG event (file appender
 * takes DEBUG, the console drops it).
 */
class SyncEngineWarnContextTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-warnctx-test")
        val dbPath = Files.createTempDirectory("unidrive-warnctx-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        logger = LoggerFactory.getLogger(SyncEngine::class.java) as Logger
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
    }

    @AfterTest
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        db.close()
    }

    @Test
    fun `WARN on upload failure carries exception class and message, the throwable moves to DEBUG`() =
        runTest {
            val provider = FakeCloudProvider()
            // FakeCloudProvider throws ProviderException("Network timeout on upload")
            // for the first N upload calls. Three is enough to also exercise the
            // "consecutive failures" branch that rethrows.
            provider.uploadFailCount = 3
            val engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                )

            // Seed: one local file queued for upload. Remote is empty → reconciler
            // classifies it as local-new → Upload action → fails.
            Files.writeString(syncRoot.resolve("doc.txt"), "hello")

            runCatching { engine.syncOnce() }

            val failureWarn =
                appender.list.firstOrNull {
                    it.level.levelStr == "WARN" &&
                        (
                            it.formattedMessage.contains("Upload failed") ||
                                it.formattedMessage.contains("Action failed")
                        )
                }
            assertNotNull(
                failureWarn,
                "expected a failure WARN; got ${appender.list.map { "${it.level}: ${it.formattedMessage}" }}",
            )
            assertTrue(
                failureWarn.formattedMessage.contains("ProviderException"),
                "UD-253: WARN must include exception class name; message was: " +
                    "'${failureWarn.formattedMessage}'",
            )
            assertNull(
                failureWarn.throwableProxy,
                "#530: the WARN must NOT carry the Throwable — the console appender's WARN " +
                    "threshold would print the full stack trace; message was: " +
                    "'${failureWarn.formattedMessage}'",
            )

            val matchingDebug =
                appender.list.firstOrNull {
                    it.level.levelStr == "DEBUG" && it.throwableProxy != null &&
                        it.formattedMessage.contains("doc.txt")
                }
            assertNotNull(
                matchingDebug,
                "#530: the stack trace must live on a DEBUG event for the failed item; " +
                    "got ${appender.list.filter { it.level.levelStr == "DEBUG" }.map { it.formattedMessage }}",
            )
        }
}
