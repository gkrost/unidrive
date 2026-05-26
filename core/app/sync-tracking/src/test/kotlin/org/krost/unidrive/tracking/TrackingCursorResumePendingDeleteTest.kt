package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingCursorResumePendingDeleteTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-cursor-resume")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = FakeTrackingProvider().apply { incrementalAware = true }
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    private val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)

    @Test
    fun pending_delete_local_retries_the_local_delete_on_cursor_resume() {
        val rel = "/doc.txt"
        val content = "synced-content".toByteArray()
        // Crash state: remote vanished, local delete not finished — local file STILL present.
        Files.write(syncRoot.resolve("doc.txt"), content)
        val hash = sha256Hex(content)
        // provider.files is empty (remote gone); /doc.txt is OMITTED from the incremental delta
        // (its deletion tombstone was already consumed on the prior pass).

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            tracking.saveDeltaCursor("prior-cursor") // non-null cursor → next pass is incremental
            tracking.upsert(
                TrackingRecord(
                    path = rel,
                    providerId = "fake",
                    remoteFileId = "hash-$hash",
                    state = TrackState.PendingDeleteLocal,
                    localHash = hash,
                    localSize = content.size.toLong(),
                    remoteEtag = hash,
                    remoteSize = content.size.toLong(),
                    lastSynced = Instant.now(),
                ),
            )

            val report =
                TrackingEngine(provider, tracking, syncRoot, batchGuard = permissive).syncOnce()

            assertTrue(
                report.plan.any { it is ReconcileAction.PropagateRemoteDelete && it.path == rel },
                "cursor-resume must re-derive remote-gone and retry the local delete; plan=${report.plan}",
            )
            assertFalse(
                Files.exists(syncRoot.resolve("doc.txt")),
                "stale local file must be reaped on cursor-resume — the PendingDeleteLocal retry was masked",
            )
        } finally {
            tracking.close()
        }
    }

    @Test
    fun pending_delete_remote_still_deletes_the_remote_on_cursor_resume_no_orphan() {
        val rel = "/gone-local.txt"
        val content = "was-synced".toByteArray()
        val hash = sha256Hex(content)
        // Crash state: local vanished (so we propagate the delete to the remote), but the engine
        // crashed before provider.delete completed — the remote is STILL present, and OMITTED
        // from the incremental delta (unchanged-present). Local file is absent.
        provider.files[rel] = content

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            tracking.saveDeltaCursor("prior-cursor")
            tracking.upsert(
                TrackingRecord(
                    path = rel,
                    providerId = "fake",
                    remoteFileId = "hash-$hash",
                    state = TrackState.PendingDeleteRemote,
                    localHash = hash,
                    localSize = content.size.toLong(),
                    remoteEtag = hash,
                    remoteSize = content.size.toLong(),
                    lastSynced = Instant.now(),
                ),
            )

            val report =
                TrackingEngine(provider, tracking, syncRoot, batchGuard = permissive).syncOnce()

            assertTrue(
                report.plan.any { it is ReconcileAction.PropagateLocalDelete && it.path == rel },
                "cursor-resume must retry the remote delete (localGone-driven); plan=${report.plan}",
            )
            assertTrue(
                provider.deletedPaths.contains(rel),
                "remote must be deleted on retry — a 'preserve absence for both states' fix would " +
                    "instead conclude both-gone and orphan the still-present remote",
            )
        } finally {
            tracking.close()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
