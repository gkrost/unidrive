package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.sync.model.SyncEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cloud object whose stored copy is shorter than the size the drive reports is refused with `remote_incomplete`
 * (HydrationOpenReadNotFoundTest). The daemon remembers the refusal per remote id, so a second read or pin attempt is
 * answered from memory instead of asking the provider again, until the object's cloud version changes, the entry is
 * older than its TTL, or the daemon restarts (the memory lives in memory only).
 */
class HydrationRemoteIncompleteMemoryTest {
    private val token = "remote_incomplete: got 100 of 500 bytes"

    private fun env(): HydrationTestEnv {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/short.bin", remoteSize = 500, remoteId = "id-short")
        env.syncEngine.truncateRemote("id-short", storedBytes = 100, declaredBytes = 500)
        return env
    }

    private fun memoryOf(env: HydrationTestEnv): RemoteIncompleteMemory = MountEngine.over(env.engine).remoteIncomplete

    private fun tokenOf(result: OpenResult): String {
        assertTrue(result is OpenResult.Failed, "expected a refusal: $result")
        return result.error.message
    }

    @Test
    fun `a second read of a truncated object is refused from memory without asking the provider`() = runTest {
        val env = env()

        val first = env.hydration.openForRead("conn1", "h1", "/short.bin")
        val second = env.hydration.openForRead("conn1", "h2", "/short.bin")

        assertEquals(token, tokenOf(first))
        assertEquals(token, tokenOf(second), "the repeat carries the same token with the same numbers")
        assertEquals(1, env.syncEngine.downloadAttempts(), "ONE provider request for two reads")
        assertEquals(1, MountEngine.over(env.engine).remoteIncompleteCount())
    }

    @Test
    fun `a pin attempt after a read is refused from memory too`() = runTest {
        val env = env()

        env.hydration.openForRead("conn1", "h1", "/short.bin")
        val pin = env.hydration.hydrate("/short.bin")

        assertTrue(pin is HydrateResult.Failed)
        assertEquals(token, pin.error.message)
        assertEquals(1, env.syncEngine.downloadAttempts(), "read and pin share the memory")
    }

    @Test
    fun `two consecutive hydrate attempts make one provider request`() = runTest {
        val env = env()

        val first = env.hydration.hydrate("/short.bin")
        val second = env.hydration.hydrate("/short.bin")

        assertTrue(first is HydrateResult.Failed && second is HydrateResult.Failed)
        assertEquals(token, second.error.message)
        assertEquals(1, env.syncEngine.downloadAttempts())
    }

    @Test
    fun `the refusal over IPC is the same reply both times`() = runTest {
        val env = env()
        val handler = HydrationIpcHandler(env.hydration)
        val request = """{"verb":"hydration.open_read","handle_id":"h1","path":"/short.bin"}"""

        val first = handler.handle("conn1", request)
        val second = handler.handle("conn1", request)

        assertEquals("""{"ok":false,"error":"$token"}""", first.trim())
        assertEquals(first, second)
        assertEquals(1, env.syncEngine.downloadAttempts())
    }

    @Test
    fun `a change of the cloud size, hash or modification time clears the memory`() = runTest {
        val changes = listOf<Pair<String, (HydrationTestEnv) -> Unit>>(
            "size" to { e -> e.stateDb.insertUnhydratedEntry("/short.bin", remoteSize = 700, remoteId = "id-short") },
            "hash" to { e ->
                e.stateDb.insertUnhydratedEntry("/short.bin", remoteSize = 500, remoteId = "id-short", remoteHash = "other")
            },
            "mtime" to { e ->
                e.stateDb.insertUnhydratedEntry(
                    "/short.bin",
                    remoteSize = 500,
                    remoteId = "id-short",
                    remoteModified = Instant.parse("2026-04-01T00:00:00Z"),
                )
            },
        )
        for ((what, change) in changes) {
            val env = env()
            env.hydration.openForRead("conn1", "h1", "/short.bin")
            assertEquals(1, memoryOf(env).count)

            change(env) // the enumeration reports a new cloud version of the object
            val again = env.hydration.openForRead("conn1", "h2", "/short.bin")

            assertTrue(again is OpenResult.Failed)
            assertEquals(2, env.syncEngine.downloadAttempts(), "a changed $what: the provider is asked again")
        }
    }

    @Test
    fun `a changed object that is whole now reads and leaves nothing in the memory`() = runTest {
        val env = env()
        env.hydration.openForRead("conn1", "h1", "/short.bin")
        assertEquals(1, memoryOf(env).count)

        env.syncEngine.repairRemote("id-short")
        env.syncEngine.seedRemoteContent("/short.bin", "x".repeat(700))
        env.stateDb.insertUnhydratedEntry("/short.bin", remoteSize = 700, remoteId = "id-short")
        val again = env.hydration.openForRead("conn1", "h2", "/short.bin")

        assertTrue(again is OpenResult.Ok, "a whole object reads: $again")
        assertEquals(0, memoryOf(env).count)
    }

    @Test
    fun `after the TTL one real attempt revalidates, and a whole object is forgotten`() = runTest {
        val env = env()
        env.hydration.openForRead("conn1", "h1", "/short.bin")
        assertEquals(1, env.syncEngine.downloadAttempts())

        memoryOf(env).ttlMs = 0 // the entry is older than its TTL
        env.syncEngine.repairRemote("id-short") // same row, but the stored copy was completed in the meantime
        env.syncEngine.seedRemoteContent("/short.bin", "x".repeat(500))
        val revalidated = env.hydration.openForRead("conn1", "h2", "/short.bin")

        assertEquals(2, env.syncEngine.downloadAttempts(), "one revalidation request after the TTL")
        assertTrue(revalidated is OpenResult.Ok, "the object is whole now: $revalidated")
        assertEquals(0, memoryOf(env).count, "a successful revalidation drops the entry")
    }

    @Test
    fun `after the TTL a still truncated object is refused by the provider and remembered afresh`() = runTest {
        val env = env()
        env.hydration.openForRead("conn1", "h1", "/short.bin")

        memoryOf(env).ttlMs = 0
        val again = env.hydration.openForRead("conn1", "h2", "/short.bin")
        memoryOf(env).ttlMs = RemoteIncompleteMemory.DEFAULT_TTL_MS
        val repeat = env.hydration.openForRead("conn1", "h3", "/short.bin")

        assertEquals(token, tokenOf(again))
        assertEquals(token, tokenOf(repeat))
        assertEquals(2, env.syncEngine.downloadAttempts(), "the revalidation asked once, the repeat after it did not")
        assertEquals(1, memoryOf(env).count)
    }

    @Test
    fun `a restart forgets the memory`() = runTest {
        val before = env()
        before.hydration.openForRead("conn1", "h1", "/short.bin")
        assertEquals(1, memoryOf(before).count)

        // A new daemon run is a new engine over the same cloud: nothing is remembered, the provider is asked once more.
        val after = env()
        assertEquals(0, memoryOf(after).count)
        after.hydration.openForRead("conn1", "h1", "/short.bin")
        assertEquals(1, after.syncEngine.downloadAttempts())
    }

    @Test
    fun `each object is asked about once however many reads meet it`() = runTest {
        val env = env()
        env.stateDb.insertUnhydratedEntry("/other.bin", remoteSize = 500, remoteId = "id-other")
        env.syncEngine.truncateRemote("id-other", storedBytes = 100, declaredBytes = 500)

        repeat(3) { env.hydration.openForRead("conn1", "h$it", "/short.bin") }
        repeat(3) { env.hydration.openForRead("conn1", "g$it", "/other.bin") }

        assertEquals(2, env.syncEngine.downloadAttempts(), "one request per object")
        assertEquals(2, MountEngine.over(env.engine).remoteIncompleteCount())
    }

    @Test
    fun `other failures are not remembered`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/flaky.bin", remoteSize = 5)
        env.syncEngine.makeNextDownloadThrow(IllegalStateException("boom"))

        val first = env.hydration.openForRead("conn1", "h1", "/flaky.bin")
        env.syncEngine.seedRemoteContent("/flaky.bin", "hello")
        val second = env.hydration.openForRead("conn1", "h2", "/flaky.bin")

        assertTrue(first is OpenResult.Failed)
        assertTrue(second is OpenResult.Ok, "a transient failure is retried at once: $second")
        assertEquals(0, MountEngine.over(env.engine).remoteIncompleteCount())
    }

    private fun row(remoteId: String?) =
        SyncEntry(
            path = "/w.bin",
            remoteId = remoteId,
            remoteHash = "h",
            remoteSize = 500,
            remoteModified = null,
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.now(),
        )

    @Test
    fun `the WARN is due once per object and run, never again after a revalidation or a repair`() {
        val memory = RemoteIncompleteMemory()
        val entry = row("id-w")
        val refusal = RemoteIncompleteDownloadException("short", storedBytes = 100, declaredBytes = 500)

        assertTrue(memory.remember(entry, refusal), "the first refusal of the object in this run is the WARN")
        assertFalse(memory.remember(entry, refusal), "the refusal after a revalidation is not")
        memory.forget(entry)
        assertFalse(memory.remember(entry, refusal), "nor is one after the object was seen whole and truncated again")
    }

    @Test
    fun `a row without a remote id is never remembered`() {
        val memory = RemoteIncompleteMemory()
        val refusal = RemoteIncompleteDownloadException("short", storedBytes = 100, declaredBytes = 500)

        assertFalse(memory.remember(row(null), refusal))
        assertNull(memory.recall(row(null)))
        assertEquals(0, memory.count)
    }
}
