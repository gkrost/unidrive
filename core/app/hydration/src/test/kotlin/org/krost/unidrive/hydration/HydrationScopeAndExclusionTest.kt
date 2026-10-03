package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Write-path guards: the sync_path scope and the exclude_patterns keep-local
 * rule. A scoped profile shows its scope as the whole drive, so a write
 * outside it would create cloud data the mounted view can never show —
 * refused with the typed `outside_scope` token. An excluded name (editors'
 * *.tmp, ~$ scratch) is accepted but deliberately never uploaded: the reply
 * and the skipped event must make that visible instead of letting the file
 * present as in-sync.
 */
class HydrationScopeAndExclusionTest {
    private val SCOPE = "/_INBOX"

    private fun scopedEnv(recoveryUploadScope: kotlinx.coroutines.CoroutineScope? = null) =
        HydrationTestEnv(recoveryUploadScope = recoveryUploadScope, syncPaths = listOf(SCOPE))

    @Test
    fun `mkdir outside the sync scope is refused and never reaches the provider`() = runTest {
        val env = scopedEnv()
        val r = env.hydration.mkdir("/outside")
        assertIs<MkdirResult.Failed>(r)
        assertEquals(HydrationError.OUTSIDE_SCOPE_TOKEN, r.error.message)
        assertTrue(
            env.syncEngine.createdFolders().isEmpty(),
            "a refused mkdir must not create anything in the cloud",
        )
    }

    @Test
    fun `mkdir inside the sync scope creates the folder in the cloud`() = runTest {
        val env = scopedEnv()
        val r = env.hydration.mkdir("/_INBOX/new")
        assertIs<MkdirResult.Ok>(r)
        assertEquals(listOf("/_INBOX/new"), env.syncEngine.createdFolders())
    }

    @Test
    fun `create outside the sync scope is refused and writes no row or cache`() = runTest {
        val env = scopedEnv()
        val r = env.hydration.create("conn1", "h1", "/outside/x.txt")
        assertIs<CreateResult.Failed>(r)
        assertEquals(HydrationError.OUTSIDE_SCOPE_TOKEN, r.error.message)
        assertNull(env.stateDb.remoteSizeOf("/outside/x.txt"), "a refused create must not write a row")
        assertFalse(Files.exists(env.syncEngine.resolveCachePath("/outside/x.txt")))
    }

    @Test
    fun `open_write_begin outside the sync scope is refused`() = runTest {
        val env = scopedEnv()
        val r = env.hydration.openWriteBegin("conn1", "/outside/x.txt", "h1")
        assertIs<OpenResult.Failed>(r)
        assertEquals(HydrationError.OUTSIDE_SCOPE_TOKEN, r.error.message)
    }

    @Test
    fun `rename with the destination outside the scope is refused and moves nothing`() = runTest {
        val env = scopedEnv()
        env.stateDb.insertFolderEntry(SCOPE)
        env.stateDb.insertCreatedRow("/_INBOX/a.txt")
        val r = env.hydration.rename("/_INBOX/a.txt", "/outside/b.txt")
        assertIs<RenameResult.Failed>(r)
        assertEquals(HydrationError.OUTSIDE_SCOPE_TOKEN, r.error.message)
        assertEquals(0L, env.stateDb.remoteSizeOf("/_INBOX/a.txt"), "the source row must be untouched")
        assertNull(env.stateDb.remoteSizeOf("/outside/b.txt"), "nothing may be written to the out-of-scope path")
    }

    @Test
    fun `rename with the source outside the scope is refused`() = runTest {
        val env = scopedEnv()
        env.stateDb.insertCreatedRow("/outside/s.txt")
        val r = env.hydration.rename("/outside/s.txt", "/_INBOX/t.txt")
        assertIs<RenameResult.Failed>(r)
        assertEquals(HydrationError.OUTSIDE_SCOPE_TOKEN, r.error.message)
        assertEquals(0L, env.stateDb.remoteSizeOf("/outside/s.txt"), "the source row must be untouched")
    }

    @Test
    fun `rename onto an excluded destination is refused with excluded and moves nothing`() = runTest {
        // A rename MOVES the remote object. Exclusion means the sync engine never plans an
        // action for the name (Reconciler and LocalScanner skip it), so a synced file moved
        // onto an excluded name silently leaves every sync action forever — no re-download,
        // no conflict handling, no reaping, and later mount edits stay keep-local. The row
        // itself would still list (flagged excluded); the harm is the silent one-way exit
        // from sync, not a vanishing view. Creating an excluded name is the other case
        // (accepted keep-local: nothing exists in the cloud to strand).
        val env = HydrationTestEnv(recoveryUploadScope = this, excludePatterns = listOf("*.tmp"))
        env.stateDb.insertFolderEntry("/new")
        env.stateDb.insertUnhydratedEntry("/new/keep.txt", 100)

        val r = env.hydration.rename("/new/keep.txt", "/new/scratch.tmp")

        assertIs<RenameResult.Failed>(r)
        assertEquals(HydrationError.EXCLUDED_TOKEN, r.error.message)
        assertEquals(0, env.syncEngine.movedPairs().size, "a refused rename must not move anything in the cloud")
        assertEquals(100L, env.stateDb.remoteSizeOf("/new/keep.txt"), "the source row must be untouched")
        assertNull(env.stateDb.remoteSizeOf("/new/scratch.tmp"), "nothing may be written to the excluded path")
    }

    @Test
    fun `a replace-rename onto an excluded destination is refused before the destination is deleted`() = runTest {
        // Ordering pin: the excluded guard sits BEFORE the replace-destination deletion. If it
        // ever moves below deleteReplaceDestination, a replace=true rename onto an excluded
        // name would first destroy the existing destination (row + cloud copy) and THEN
        // refuse — losing cloud content to a refusal.
        val env = HydrationTestEnv(recoveryUploadScope = this, excludePatterns = listOf("*.tmp"))
        env.stateDb.insertFolderEntry("/new")
        env.stateDb.insertUnhydratedEntry("/new/keep.txt", 100)
        env.stateDb.insertUnhydratedEntry("/new/target.tmp", 200)

        val r = env.hydration.rename("/new/keep.txt", "/new/target.tmp", replace = true)

        assertIs<RenameResult.Failed>(r)
        assertEquals(HydrationError.EXCLUDED_TOKEN, r.error.message)
        assertEquals(0, env.syncEngine.deletedPaths().size, "the replace destination must NOT be deleted")
        assertEquals(200L, env.stateDb.remoteSizeOf("/new/target.tmp"), "the destination row must survive the refusal")
        assertEquals(100L, env.stateDb.remoteSizeOf("/new/keep.txt"), "the source row must be untouched")
    }

    @Test
    fun `rename of a missing source onto an excluded name answers old_path_not_found`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, excludePatterns = listOf("*.tmp"))
        env.stateDb.insertFolderEntry("/new")

        assertEquals(RenameResult.OldPathNotFound, env.hydration.rename("/new/ghost.txt", "/new/scratch.tmp"))
    }

    @Test
    fun `a never-uploaded file may be renamed onto an excluded name locally`() = runTest {
        // Nothing exists in the cloud for it, so nothing can be stranded: the rename is local
        // (an app that writes `x` and renames it to `x.tmp` must not get an error).
        val env = HydrationTestEnv(recoveryUploadScope = this, excludePatterns = listOf("*.tmp"))
        env.stateDb.insertFolderEntry("/new")
        env.stateDb.insertLocalOnlyHydratedEntry("/new/draft.txt")

        // This env's provider fake does not implement the ghost-probe a local-only rename runs
        // afterwards, so the outcome past the guard is a generic failure here (the local rename
        // itself is covered by HydrationImplRenameTest): what matters is that the excluded
        // guard does not fire for a source that never reached the cloud.
        val r = env.hydration.rename("/new/draft.txt", "/new/draft.tmp")
        if (r is RenameResult.Failed) {
            assertTrue(r.error.message != HydrationError.EXCLUDED_TOKEN, "the excluded guard must not fire for a never-uploaded source")
        }
        assertEquals(0, env.syncEngine.movedPairs().size, "a local-only rename must not touch the cloud")
    }

    @Test
    fun `create accepts an excluded file and open_write never uploads it`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, excludePatterns = listOf("*.tmp"))
        env.stateDb.insertFolderEntry("/new")

        val created = env.hydration.create("conn1", "h1", "/new/scratch.tmp")
        assertIs<CreateResult.Ok>(created)
        assertTrue(created.excluded, "an excluded create must report excluded:true")
        assertEquals(0L, env.stateDb.remoteSizeOf("/new/scratch.tmp"))

        Files.writeString(created.cachePath, "local scratch bytes")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        val opened = env.hydration.openForWrite("conn1", "h1", "/new/scratch.tmp", created.cachePath)
        assertIs<OpenResult.Ok>(opened)
        assertTrue(opened.excluded, "an excluded open_write must report excluded:true")
        advanceUntilIdle()

        // The keep-local contract: no upload ran, and the event stream said so
        // — skipped, never hydrating/hydrated, and a Completed that cannot be
        // mistaken for a successful transfer.
        assertNull(env.syncEngine.remoteContentSeen("/new/scratch.tmp"), "an excluded file must never be uploaded")
        assertEquals(0, events.filterIsInstance<HydrationEvent.Hydrating>().size)
        assertEquals(0, events.filterIsInstance<HydrationEvent.Hydrated>().size)
        assertIs<HydrationEvent.Skipped>(events.first())
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertFalse(completed.ok)
        assertEquals(HydrationError.EXCLUDED_TOKEN, completed.error?.message)
        assertEquals("h1", completed.handleId)
        collector.cancel()
    }

    @Test
    fun `excluded open_write advances the local watermark so the recovery scanner stops replaying it`() = runTest {
        // The engine's keep-local branch in uploadFromCache advances localMtime so the
        // co-daemon's crash-recovery scanner (replays open_write for cache files newer
        // than last_synced) does not replay the file on every mount. The excluded
        // short-circuit in open_write must not skip that bookkeeping.
        val env = HydrationTestEnv(recoveryUploadScope = this, excludePatterns = listOf("*.tmp"))
        env.stateDb.insertFolderEntry("/new")
        val created = env.hydration.create("conn1", "h1", "/new/scratch.tmp")
        assertIs<CreateResult.Ok>(created)
        Files.writeString(created.cachePath, "edited after create")
        Files.setLastModifiedTime(created.cachePath, java.nio.file.attribute.FileTime.fromMillis(4_102_444_800_000L))

        env.hydration.openForWrite("conn1", "h1", "/new/scratch.tmp", created.cachePath)
        advanceUntilIdle()

        assertEquals(
            4_102_444_800_000L,
            env.stateDb.localMtimeOf("/new/scratch.tmp"),
            "last_synced must reflect the cache mtime or every restart replays the excluded file",
        )
        assertNull(env.syncEngine.remoteContentSeen("/new/scratch.tmp"), "an excluded file must never be uploaded")
    }

    @Test
    fun `open_write_begin on an excluded path reports excluded`() = runTest {
        val env = HydrationTestEnv(excludePatterns = listOf("*.tmp"))
        env.stateDb.insertFolderEntry("/new")
        val created = env.hydration.create("conn1", "h1", "/new/scratch.tmp")
        assertIs<CreateResult.Ok>(created)
        val r = env.hydration.openWriteBegin("conn1", "/new/scratch.tmp", "h2")
        assertIs<OpenResult.Ok>(r)
        assertTrue(r.excluded)
    }

    @Test
    fun `create inside the scope is not flagged excluded`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, syncPaths = listOf(SCOPE))
        env.stateDb.insertFolderEntry(SCOPE)
        val r = env.hydration.create("conn1", "h1", "/_INBOX/real.txt")
        assertIs<CreateResult.Ok>(r)
        assertFalse(r.excluded)
    }

    @Test
    fun `list marks excluded entries`() = runTest {
        val env = HydrationTestEnv(excludePatterns = listOf("*.tmp"))
        env.stateDb.insertCreatedRow("/new/keep.txt")
        env.stateDb.insertCreatedRow("/new/scratch.tmp")

        val r = env.hydration.list("/new")
        assertIs<ListResult.Ok>(r)
        val byPath = r.entries.associateBy { it.path }
        assertFalse(byPath.getValue("/new/keep.txt").excluded)
        assertTrue(byPath.getValue("/new/scratch.tmp").excluded)
    }
}
