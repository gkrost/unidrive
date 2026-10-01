package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.*

/**
 * #300: the engine's manual batch ([StateDatabase.beginBatch] .. [StateDatabase.commitBatch] /
 * [StateDatabase.rollbackBatch], or [StateDatabase.batch]) owns the connection's one SQLite transaction,
 * and it stays open across network I/O. A write from another thread (an IPC hydration verb running while
 * `unidrive sync` is in Pass 1) used to join that transaction: the engine's rollback discarded it, and it
 * was not durable until the engine committed.
 */
class StateDatabaseBatchIsolationTest {
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempDirectory("unidrive-batch-isolation").resolve("state.db")
        db = StateDatabase(dbFile)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun entry(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = "hash-$path",
            remoteSize = 100,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = 1711627200000,
            localSize = 100,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.now(),
        )

    /**
     * Runs [block] on a fresh thread and waits for it. A write that is queued behind the open batch instead
     * of running at once would time out here, which fails the test rather than hanging the build.
     */
    private fun onOtherThread(block: () -> Unit) {
        var failure: Throwable? = null
        val t =
            Thread {
                try {
                    block()
                } catch (e: Throwable) {
                    failure = e
                }
            }
        t.start()
        t.join(10_000)
        assertFalse(t.isAlive, "the call from the other thread must not wait for the open batch")
        failure?.let { throw it }
    }

    /** Alive paths as a second, independent connection to the same file sees them: committed rows only. */
    private fun committedPaths(): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT path FROM sync_entries WHERE status='EXISTS'").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    @Test
    fun `a write from another thread survives the rollback of the open batch`() {
        db.beginBatch()
        db.upsertEntry(entry("/engine.txt"))

        onOtherThread { db.upsertEntry(entry("/ipc.txt")) }
        db.rollbackBatch()

        assertNotNull(db.getEntry("/ipc.txt"), "the other thread's write was rolled back with the engine's batch")
    }

    @Test
    fun `a write from another thread is durable while the batch is still open`() {
        db.beginBatch()
        db.upsertEntry(entry("/engine.txt"))

        onOtherThread { db.upsertEntry(entry("/ipc.txt")) }

        assertTrue("/ipc.txt" in committedPaths(), "the other thread's write must not wait for the engine's commit")
        db.rollbackBatch()
    }

    @Test
    fun `the batch stays atomic for its owner after another thread wrote`() {
        db.beginBatch()
        db.upsertEntry(entry("/before.txt"))

        onOtherThread { db.upsertEntry(entry("/ipc.txt")) }
        db.upsertEntry(entry("/after.txt"))
        db.rollbackBatch()

        assertNotNull(db.getEntry("/ipc.txt"), "the other thread's write must be kept")
        assertNull(db.getEntry("/after.txt"), "the owner's writes after the interruption still belong to its batch")
    }

    @Test
    fun `the owner commits what it wrote after another thread wrote`() {
        db.beginBatch()
        db.upsertEntry(entry("/before.txt"))

        onOtherThread { db.upsertEntry(entry("/ipc.txt")) }
        db.upsertEntry(entry("/after.txt"))
        db.commitBatch()

        assertEquals(setOf("/before.txt", "/ipc.txt", "/after.txt"), committedPaths())
    }

    @Test
    fun `a batch from another thread does not join the open batch`() {
        db.beginBatch()
        db.upsertEntry(entry("/engine.txt"))

        onOtherThread {
            db.batch {
                db.upsertEntry(entry("/ipc-a.txt"))
                db.upsertEntry(entry("/ipc-b.txt"))
            }
        }
        db.rollbackBatch()

        assertNotNull(db.getEntry("/ipc-a.txt"))
        assertNotNull(db.getEntry("/ipc-b.txt"))
    }

    @Test
    fun `a failing batch on another thread rolls back only itself`() {
        db.beginBatch()
        db.upsertEntry(entry("/engine.txt"))

        onOtherThread {
            runCatching {
                db.batch {
                    db.upsertEntry(entry("/ipc.txt"))
                    error("the other thread's block fails")
                }
            }
        }
        db.upsertEntry(entry("/engine2.txt"))
        db.commitBatch()

        assertNull(db.getEntry("/ipc.txt"), "a failed batch of another thread was committed by the engine")
        assertEquals(setOf("/engine.txt", "/engine2.txt"), committedPaths())
    }

    @Test
    fun `a second thread cannot open a batch while one is open`() {
        db.beginBatch()
        var rejected: Throwable? = null
        onOtherThread {
            try {
                db.beginBatch()
            } catch (e: IllegalStateException) {
                rejected = e
            }
        }
        db.rollbackBatch()

        assertNotNull(rejected, "two batches cannot share the one transaction of the connection")
    }

    // Single-threaded callers must see no difference (the unchanged behaviour the fix has to keep).

    @Test
    fun `rollbackBatch discards the writes made inside the batch`() {
        db.upsertEntry(entry("/kept.txt"))
        db.beginBatch()
        db.upsertEntry(entry("/gone.txt"))
        db.setSyncState("k", "v")
        db.rollbackBatch()

        assertNull(db.getEntry("/gone.txt"))
        assertNull(db.getSyncState("k"))
        assertNotNull(db.getEntry("/kept.txt"))
    }

    @Test
    fun `commitBatch makes the writes durable and leaves the connection in autocommit`() {
        db.beginBatch()
        db.upsertEntry(entry("/a.txt"))
        assertFalse("/a.txt" in committedPaths(), "an open batch must not be visible to other connections")
        db.commitBatch()
        assertTrue("/a.txt" in committedPaths())

        db.upsertEntry(entry("/b.txt"))
        assertTrue("/b.txt" in committedPaths(), "after the batch every write commits on its own again")
    }

    @Test
    fun `a batch nested in a manual batch joins it`() {
        db.beginBatch()
        db.batch { db.upsertEntry(entry("/nested.txt")) }
        db.rollbackBatch()

        assertNull(db.getEntry("/nested.txt"))
    }

    @Test
    fun `a read from another thread does not end the open batch`() {
        db.beginBatch()
        db.upsertEntry(entry("/engine.txt"))

        onOtherThread { db.getEntry("/engine.txt") }
        db.rollbackBatch()

        assertNull(db.getEntry("/engine.txt"), "a reader committed the engine's open batch")
    }

    @Test
    fun `the batch can be ended from another thread than the one that began it`() {
        db.beginBatch()
        db.upsertEntry(entry("/a.txt"))

        onOtherThread { db.commitBatch() }

        assertTrue("/a.txt" in committedPaths())
        db.upsertEntry(entry("/b.txt"))
        assertTrue("/b.txt" in committedPaths())
    }
}
