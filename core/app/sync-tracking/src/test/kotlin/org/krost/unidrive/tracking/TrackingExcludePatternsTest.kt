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

/**
 * Default-ignore-list surface (c): the tracking-set engine must keep desktop/OS junk LOCAL —
 * never upload it, never adopt it into the tracking set, never tombstone it. Surfaces (a)
 * LocalScanner and (b) the mount upload path already consult the shared ignore list; this pins
 * the tracking engine's pass to the same `effectiveExcludePatterns`.
 *
 * Two orthogonal invariants, one test each:
 *  1. [excluded_file_is_not_uploaded_even_when_tracked_and_changed] — the `.directory.lock`
 *     upload-storm shape: a tracked junk file rewritten locally must NOT re-upload.
 *  2. [excluded_file_is_not_adopted_into_the_tracking_set] — junk present on both sides must not
 *     be adopted (it must never enter the managed set in the first place).
 *
 * Both pin a real (non-excluded) file alongside the junk to prove the filter doesn't over-match.
 * If either test is removed or loosened, junk can silently flow into the user's cloud account.
 */
class TrackingExcludePatternsTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    // The shared default ignore list globs the engine should honour (subset under test).
    private val excludes = listOf("**/.directory.lock", "**/Thumbs.db", "**/.DS_Store")

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-exclude")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = FakeTrackingProvider()
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    private fun engine(tracking: TrackingSet) =
        TrackingEngine(provider, tracking, syncRoot, excludePatterns = excludes)

    @Test
    fun excluded_file_is_not_uploaded_even_when_tracked_and_changed() {
        val old = "old".toByteArray()
        val new = "new-bytes".toByteArray()
        // Both files tracked + synced at `old`; both rewritten locally to `new` (a local change).
        Files.write(syncRoot.resolve(".directory.lock"), new)
        Files.write(syncRoot.resolve("notes.txt"), new)
        provider.files["/.directory.lock"] = old
        provider.files["/notes.txt"] = old

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            for (rel in listOf("/.directory.lock", "/notes.txt")) {
                tracking.upsert(
                    TrackingRecord(
                        path = rel,
                        providerId = "fake",
                        remoteFileId = "hash-${sha256Hex(old)}",
                        state = TrackState.TrackedSynced,
                        localHash = sha256Hex(old),
                        localSize = old.size.toLong(),
                        remoteEtag = sha256Hex(old),
                        remoteSize = old.size.toLong(),
                        lastSynced = Instant.now(),
                    ),
                )
            }

            engine(tracking).syncOnce()

            assertFalse(
                provider.uploadedPaths.contains("/.directory.lock"),
                "excluded junk must stay local — a tracked+changed .directory.lock must not re-upload (storm)",
            )
            assertTrue(
                provider.uploadedPaths.contains("/notes.txt"),
                "a real tracked+changed file must still upload; uploads=${provider.uploadedPaths}",
            )
        } finally {
            tracking.close()
        }
    }

    @Test
    fun excluded_file_is_not_adopted_into_the_tracking_set() {
        val content = "same-on-both-sides".toByteArray()
        // Junk + a real file present identically on both sides (the adoption trigger).
        for (name in listOf(".directory.lock", "Thumbs.db", "report.pdf")) {
            Files.write(syncRoot.resolve(name), content)
            provider.files["/$name"] = content
        }

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = engine(tracking).syncOnce()

            assertFalse(
                report.adopted.any { it == "/.directory.lock" || it == "/Thumbs.db" },
                "excluded junk must never be adopted into the tracking set; adopted=${report.adopted}",
            )
            assertTrue(
                report.adopted.contains("/report.pdf"),
                "a real both-sides-matching file must still adopt; adopted=${report.adopted}",
            )
        } finally {
            tracking.close()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
