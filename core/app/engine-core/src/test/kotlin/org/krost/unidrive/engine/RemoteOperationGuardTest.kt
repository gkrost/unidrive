package org.krost.unidrive.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #560 U2: the guards SyncEngine and the mount operations share, pinned on their own.
class RemoteOperationGuardTest {
    private fun guard(
        standingScope: List<String> = emptyList(),
        syncPaths: List<String> = emptyList(),
        excludes: List<String> = emptyList(),
        cap: Int = 2,
    ) = RemoteOperationGuard(
        standingScope = standingScope,
        syncPaths = syncPaths,
        excludePatterns = excludes,
        maxConcurrentTransfers = cap,
        matchesGlob = { path, pattern -> path.endsWith(pattern.removePrefix("*")) },
    )

    @Test
    fun `an empty standing scope tracks the whole drive`() {
        val g = guard(syncPaths = listOf("/A"))
        assertEquals(emptyList(), g.trackScope)
        assertFalse(g.isOutOfScope("/elsewhere/file.txt"))
        assertTrue(g.isTracked("/elsewhere/file.txt"))
    }

    @Test
    fun `a standing scope plus a per-run sync path is the tracked scope, ancestors included`() {
        val g = guard(standingScope = listOf("/Inbox"), syncPaths = listOf("/Work/Projects"))
        assertEquals(listOf("/Inbox", "/Work/Projects"), g.trackScope)
        assertTrue(g.isTracked("/Inbox/a.txt"))
        assertTrue(g.isTracked("/Work"), "a folder leading to the scope is tracked")
        assertTrue(g.isOutOfScope("/Work"), "but it is not inside the scope")
        assertFalse(g.isOutOfScope("/Work/Projects/x"))
        assertFalse(g.isTracked("/Other/b.txt"))
        assertFalse(g.isTracked("/Inboxes/c.txt"), "scope entries match on a path boundary")
    }

    @Test
    fun `excluded paths are matched with the injected glob matcher`() {
        val g = guard(excludes = listOf("*.tmp"))
        assertTrue(g.isExcludedPath("/a/b.tmp"))
        assertFalse(g.isExcludedPath("/a/b.txt"))
        assertFalse(guard().isExcludedPath("/a/b.tmp"))
    }

    @Test
    fun `the transfer budget never runs more than the cap at once`() =
        runTest {
            val g = guard(cap = 2)
            val running = AtomicInteger(0)
            val peak = AtomicInteger(0)
            (1..6)
                .map {
                    async {
                        g.withTransferPermit {
                            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                            delay(10)
                            running.decrementAndGet()
                        }
                    }
                }.awaitAll()
            assertEquals(2, peak.get())
            assertEquals(2, g.transferBudget.availablePermits)
        }

    @Test
    fun `only the two typed not-found shapes count as already gone`() {
        assertTrue(RemoteErrors.isAlreadyGone(ProviderException("Folder not found: a in /a/b")))
        assertTrue(RemoteErrors.isAlreadyGone(ProviderException("Item not found: /a/b")))
        assertFalse(RemoteErrors.isAlreadyGone(ProviderException("HTTP 500: Item not found: /a/b")))
        assertFalse(RemoteErrors.isAlreadyGone(RuntimeException("Item not found: /a/b")))
    }
}
