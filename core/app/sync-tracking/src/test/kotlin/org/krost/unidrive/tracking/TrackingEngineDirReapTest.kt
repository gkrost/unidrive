package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Empty-directory reaping: after a pass deletes a file from the remote (because
 * its local copy was deleted), the engine deletes the now-empty remote
 * directory that contained it. The engine tracks files only, so without this a
 * locally-deleted folder leaves an empty remote-directory shell behind.
 *
 * Each test pins one invariant. The safety-critical one is
 * `dir with surviving sibling is not reaped`: some providers trash a folder by
 * cascading its whole subtree, so reaping a non-empty directory would take
 * untracked children with it. The engine must verify emptiness via
 * listChildren BEFORE deleting.
 */
class TrackingEngineDirReapTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    private val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("ts-reap")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = FakeTrackingProvider()
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    /**
     * Sync a single remote file into the tracking set, then delete its local
     * copy so the next pass plans a del-remote. Returns the tracking handle the
     * caller closes.
     */
    private fun seedAndDeleteLocal(remotePath: String) {
        provider.files[remotePath] = "payload".toByteArray()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, tracking, syncRoot).syncOnce()
        } finally {
            tracking.close()
        }
        val local = syncRoot.resolve(remotePath.removePrefix("/"))
        Files.delete(local)
        // Force a full enumeration on the next pass so remote absence is authoritative.
        val t = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            t.saveDeltaCursor(null)
        } finally {
            t.close()
        }
    }

    /**
     * INVARIANT: deleting the only file in a nested tree reaps the whole empty
     * directory chain, deepest-first. Regression mode: empty remote-directory
     * shells accumulate after every folder deletion.
     */
    @Test
    fun `empty dir tree is reaped deepest-first`() {
        seedAndDeleteLocal("/a/b/c/file.txt")

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = TrackingEngine(provider, tracking, syncRoot, batchGuard = permissive).syncOnce()

            assertEquals(
                listOf("/a/b/c", "/a/b", "/a"),
                report.reapedDirs,
                "the empty directory chain must be reaped deepest-first",
            )
            // The remote file and all its directories are gone.
            assertTrue(provider.files.isEmpty(), "remote should be empty after reaping; was ${provider.files.keys}")
            // Each directory delete was issued against the provider.
            assertTrue(provider.deletedPaths.containsAll(listOf("/a/b/c", "/a/b", "/a")))
        } finally {
            tracking.close()
        }
    }

    /**
     * SAFETY INVARIANT: a directory that still holds a surviving file is NOT
     * reaped. Regression mode: a provider that cascade-trashes a folder would
     * take the surviving (possibly untracked) sibling with it — data loss.
     */
    @Test
    fun `dir with surviving sibling is not reaped`() {
        // Two files share a directory; only one is deleted locally.
        provider.files["/shared/keep.txt"] = "keep".toByteArray()
        provider.files["/shared/gone.txt"] = "gone".toByteArray()
        val tracking0 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, tracking0, syncRoot).syncOnce()
        } finally {
            tracking0.close()
        }
        Files.delete(syncRoot.resolve("shared/gone.txt"))
        val tcur = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            tcur.saveDeltaCursor(null)
        } finally {
            tcur.close()
        }

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = TrackingEngine(provider, tracking, syncRoot, batchGuard = permissive).syncOnce()

            assertTrue(report.reapedDirs.isEmpty(), "a non-empty directory must NOT be reaped; reaped=${report.reapedDirs}")
            assertTrue(provider.files.containsKey("/shared/keep.txt"), "the surviving sibling must remain on the remote")
            assertFalse(provider.deletedPaths.contains("/shared"), "the directory delete must not have been issued")
        } finally {
            tracking.close()
        }
    }

    /**
     * INVARIANT: dry-run reaps nothing (it deletes nothing, so nothing is
     * emptied). Regression mode: a dry-run silently mutates the remote.
     */
    @Test
    fun `dry-run reaps nothing`() {
        seedAndDeleteLocal("/a/b/file.txt")

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report =
                TrackingEngine(provider, tracking, syncRoot, batchGuard = permissive, dryRun = true).syncOnce()

            assertTrue(report.reapedDirs.isEmpty(), "dry-run must reap nothing")
            assertTrue(provider.deletedPaths.isEmpty(), "dry-run must not issue any delete")
            assertTrue(provider.files.containsKey("/a/b/file.txt"), "dry-run must leave the remote untouched")
        } finally {
            tracking.close()
        }
    }

    /**
     * INVARIANT: a provider error reaping one directory is best-effort — the
     * pass completes and other directories are still reaped. Regression mode:
     * one un-reapable directory aborts the whole sync pass.
     */
    @Test
    fun `reaping tolerates a provider error without failing the pass`() {
        seedAndDeleteLocal("/x/y/file.txt")
        // Make the deepest directory's delete fail; its parent must still reap.
        provider.failDeleteDirs += "/x/y"

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = TrackingEngine(provider, tracking, syncRoot, batchGuard = permissive).syncOnce()

            assertFalse(report.reapedDirs.contains("/x/y"), "the failing directory must not be reported reaped")
            assertTrue(report.reapedDirs.contains("/x"), "a sibling-free parent must still be reaped after a child error")
            // The pass produced a normal report (no exception thrown out of syncOnce).
            assertTrue(report.remoteEnumerationComplete)
        } finally {
            tracking.close()
        }
    }

    /**
     * INVARIANT: a root-level file deletion reaps nothing — the sync root is
     * never a reap candidate. Regression mode: the entire remote could be
     * trashed.
     */
    @Test
    fun `sync root is never reaped`() {
        seedAndDeleteLocal("/top.txt")

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = TrackingEngine(provider, tracking, syncRoot, batchGuard = permissive).syncOnce()

            assertTrue(report.reapedDirs.isEmpty(), "a root-level file must not reap any directory")
            assertFalse(provider.deletedPaths.contains("/"), "the sync root must never be deleted")
        } finally {
            tracking.close()
        }
    }

    /**
     * CONVERGENCE INVARIANT (the review finding): a directory whose listing lags
     * on the pass that empties it (a just-trashed file still shown) is NOT
     * reaped that pass, but IS reaped on a later pass once the listing settles —
     * because the deferral is persisted. The file's tracking row is gone after
     * its delete, so without persistence the directory would never be revisited.
     */
    @Test
    fun `a dir whose listing lags is reaped on a later pass`() {
        seedAndDeleteLocal("/a/b/file.txt")
        // The directories report a phantom child on their first listing this pass.
        provider.lagDirsOnce += "/a/b"
        provider.lagDirsOnce += "/a"

        // Pass 1: empties the file, but the lagging listing blocks the reap.
        val t1 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val r1 = TrackingEngine(provider, t1, syncRoot, batchGuard = permissive).syncOnce()
            assertTrue(r1.reapedDirs.isEmpty(), "lagging listing must defer the reap")
            assertEquals(
                setOf("/a/b", "/a"),
                t1.pendingReaps(),
                "deferred directories must be persisted for retry",
            )
        } finally {
            t1.close()
        }

        // Pass 2: nothing new deleted, but the persisted candidates retry and
        // (listing now settled) reap. Deepest-first.
        val t2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val r2 = TrackingEngine(provider, t2, syncRoot, batchGuard = permissive).syncOnce()
            assertEquals(listOf("/a/b", "/a"), r2.reapedDirs, "persisted candidates must reap once the listing settles")
            assertTrue(t2.pendingReaps().isEmpty(), "a reaped directory must be cleared from the pending set")
        } finally {
            t2.close()
        }
    }

    /**
     * CONVERGENCE INVARIANT: a directory whose delete fails is persisted and
     * retried on the next pass, succeeding once the provider recovers.
     */
    @Test
    fun `a dir whose delete fails is retried and reaped next pass`() {
        seedAndDeleteLocal("/x/y/file.txt")
        provider.failDeleteDirs += "/x/y"

        val t1 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val r1 = TrackingEngine(provider, t1, syncRoot, batchGuard = permissive).syncOnce()
            assertFalse(r1.reapedDirs.contains("/x/y"), "the failing delete must not report a reap")
            assertTrue(t1.pendingReaps().contains("/x/y"), "the failed directory must be persisted for retry")
        } finally {
            t1.close()
        }

        provider.failDeleteDirs.clear() // provider recovers
        val t2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val r2 = TrackingEngine(provider, t2, syncRoot, batchGuard = permissive).syncOnce()
            assertTrue(r2.reapedDirs.contains("/x/y"), "the recovered directory must reap on retry")
            assertFalse(t2.pendingReaps().contains("/x/y"), "a reaped directory must be cleared from the pending set")
        } finally {
            t2.close()
        }
    }

    /**
     * RETIREMENT INVARIANT: a persisted candidate that turns out to hold a
     * TRACKED file (legitimately populated, not lag) is dropped from the pending
     * set rather than retried forever.
     */
    @Test
    fun `a persisted candidate with a tracked child is retired not retried`() {
        // Sync two files in the same dir; delete one locally.
        provider.files["/dir/keep.txt"] = "keep".toByteArray()
        provider.files["/dir/gone.txt"] = "gone".toByteArray()
        val t0 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, t0, syncRoot).syncOnce()
            // Pre-seed a stale pending-reap for the dir (as if an earlier pass deferred it).
            t0.addPendingReap("/dir")
        } finally {
            t0.close()
        }
        Files.delete(syncRoot.resolve("dir/gone.txt"))
        val tcur = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            tcur.saveDeltaCursor(null)
        } finally {
            tcur.close()
        }

        val t1 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val r1 = TrackingEngine(provider, t1, syncRoot, batchGuard = permissive).syncOnce()
            assertTrue(r1.reapedDirs.isEmpty(), "a dir with a surviving tracked file must not be reaped")
            assertFalse(
                t1.pendingReaps().contains("/dir"),
                "a dir with a tracked child must be retired from the pending set, not retried forever",
            )
            assertTrue(provider.files.containsKey("/dir/keep.txt"), "the tracked sibling must survive")
        } finally {
            t1.close()
        }
    }
}
