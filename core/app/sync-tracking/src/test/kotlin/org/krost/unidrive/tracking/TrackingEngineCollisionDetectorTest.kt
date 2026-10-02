package org.krost.unidrive.tracking

import org.krost.unidrive.CloudItem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #401: where two live remote items share one path, the path-keyed tracking set
 * cannot represent both. The standing detector in walkDelta must mark the pass
 * incomplete (which suppresses delete actions) instead of letting the tracking
 * store keep whichever twin the provider emitted last. Detector-only by design —
 * the tracking store is frozen.
 */
class TrackingEngineCollisionDetectorTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-collide")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = FakeTrackingProvider()
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    private fun twinItem(path: String, id: String) =
        CloudItem(
            id = id,
            name = path.substringAfterLast('/'),
            path = path,
            size = 3,
            isFolder = false,
            modified = Instant.parse("2026-05-21T00:00:00Z"),
            created = Instant.parse("2026-05-21T00:00:00Z"),
            hash = null,
            mimeType = null,
        )

    @Test
    fun `two live items at one path mark the pass incomplete and suppress deletes`() {
        provider.files["/dup.txt"] = "abc".toByteArray()
        provider.extraDeltaItems = listOf(twinItem("/dup.txt", "twin-b"))

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = TrackingEngine(provider, tracking, syncRoot).syncOnce()

            assertFalse(
                report.remoteEnumerationComplete,
                "the collision detector must mark the pass incomplete",
            )
            assertTrue(
                report.effectivePlan.none { it is ReconcileAction.PropagateRemoteDelete },
                "no delete may fire while a path collision is unresolved",
            )
            assertTrue(report.collisions.isEmpty(), "the detector is a completeness signal, not a claim collision")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `an unchanged namespace keeps the pass complete`() {
        provider.files["/solo.txt"] = "xyz".toByteArray()

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = TrackingEngine(provider, tracking, syncRoot).syncOnce()
            assertTrue(report.remoteEnumerationComplete)
        } finally {
            tracking.close()
        }
    }
}
