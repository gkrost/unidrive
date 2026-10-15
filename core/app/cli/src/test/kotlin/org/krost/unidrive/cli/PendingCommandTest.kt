package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.engine.CachePaths
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `pending list` / `pending drop` (#753): the safe recovery of stranded pending upload rows. Every test
 * drives the real command line against a temp config directory with a real state.db and a temp cache
 * root (`user.home` is pointed into the temp dir for the run); the daemon is a fake: a process lock
 * held by the test. No test starts a daemon or touches the user's real folders.
 */
class PendingCommandTest {
    private lateinit var dir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err
    private val originalHome = System.getProperty("user.home")
    private var holder: ProcessLock? = null

    private val profileName = "p1"
    private val profileDir: Path get() = dir.resolve(profileName)
    private val cacheRoot: Path
        get() = SyncEngine.hydrationCacheRoot(SyncEngine.defaultHydrationCacheRoot(), profileName)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("pending-rows")
        System.setProperty("user.home", dir.toString())
        Files.createDirectories(dir.resolve("roots").resolve("mirror"))
        Files.createDirectories(profileDir)
        Files.writeString(
            dir.resolve("config.toml"),
            """
            [general]
            default_profile = "$profileName"

            [providers.$profileName]
            type = "localfs"
            mode = "mirror"
            sync_root = "${dir.resolve("roots").resolve("mirror").toString().replace('\\', '/')}"
            root_path = "${dir.resolve("remote").toString().replace('\\', '/')}"
            """.trimIndent() + "\n",
        )
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        holder?.unlock()
        System.setOut(originalOut)
        System.setErr(originalErr)
        System.setProperty("user.home", originalHome)
        dir.toFile().deleteRecursively()
    }

    private fun seedRow(
        path: String,
        remoteId: String? = "local:${path.hashCode()}",
        localSize: Long = 10,
    ) {
        val db = StateDatabase(profileDir.resolve("state.db"))
        try {
            db.initialize()
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = remoteId,
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = 1_000,
                    localSize = localSize,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
                    status = org.krost.unidrive.sync.model.EntryStatus.EXISTS,
                ),
            )
        } finally {
            db.close()
        }
    }

    private fun seedCache(
        path: String,
        content: String,
    ) {
        val file = CachePaths.resolveInside(cacheRoot, path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun run(vararg args: String): Int {
        out.reset()
        err.reset()
        return CommandLine(Main()).execute("-c", dir.toString(), "pending", *args)
    }

    @Test
    fun `list shows path, size, age and cache state, and the json form matches`() {
        seedRow("/stuck.txt", localSize = 42)
        seedCache("/stuck.txt", "bytes")

        assertEquals(0, run("list"))
        val text = out.toString(Charsets.UTF_8)
        assertTrue("/stuck.txt" in text, text)
        assertTrue("size=42" in text, text)
        assertTrue("cache=content(5B)" in text, text)

        out.reset()
        assertEquals(0, run("list", "--json"))
        val json = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        assertEquals(1, json.getValue("count").jsonPrimitive.long)
        val row = json.getValue("rows").toString().let { Json.parseToJsonElement(it) }.let { Json.parseToJsonElement(it.toString()) }
        // The row object carries the same facts the text shows.
        assertTrue("\"/stuck.txt\"" in row.toString(), row.toString())
        assertTrue("\"local_size\":42" in row.toString(), row.toString())
        assertTrue("\"cache_present\":true" in row.toString(), row.toString())
        assertTrue("\"cache_size\":5" in row.toString(), row.toString())
    }

    @Test
    fun `drop with a prefix and --yes removes only local rows under it and is idempotent`() {
        seedRow("/keep-me/local.txt", remoteId = "remote-123")
        seedRow("/drop/one.txt")
        seedRow("/drop/two.txt")

        assertEquals(0, run("drop", "/drop/", "--yes"))
        val first = out.toString(Charsets.UTF_8)
        assertTrue("Dropped 2 row(s)." in first, first)

        val db = StateDatabase(profileDir.resolve("state.db"))
        try {
            db.initialize()
            assertEquals(null, db.getEntry("/drop/one.txt"), "the local: row is gone")
            assertEquals(null, db.getEntry("/drop/two.txt"), "the local: row is gone")
            assertEquals("remote-123", db.getEntry("/keep-me/local.txt")?.remoteId, "the row with a real remote id stays")
        } finally {
            db.close()
        }

        assertEquals(0, run("drop", "/drop/", "--yes"))
        assertTrue("Nothing to drop." in out.toString(Charsets.UTF_8), "the second run changes nothing")
    }

    @Test
    fun `drop json emits one parseable object`() {
        seedRow("/drop/x.txt")
        assertEquals(0, run("drop", "/drop/", "--yes", "--json"))
        val json = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        assertEquals(1, json.getValue("dropped").jsonPrimitive.long)
    }

    @Test
    fun `an invalid row path cannot move the cache root`() {
        seedRow("/")
        seedCache("/child.txt", "preserve")

        assertEquals(1, run("drop", "/", "--yes"))
        assertTrue(Files.exists(cacheRoot.resolve("child.txt")))
        val db = StateDatabase(profileDir.resolve("state.db"))
        try {
            db.initialize()
            assertTrue(db.getEntry("/") != null)
        } finally {
            db.close()
        }
    }

    @Test
    fun `drop asks before acting and aborts without it`() {
        seedRow("/drop/x.txt")
        val stdin = System.`in`
        try {
            System.setIn("n\n".byteInputStream())
            assertEquals(0, run("drop", "/drop/"))
            val text = out.toString(Charsets.UTF_8)
            assertTrue("Aborted" in text, text)
            seedCache("/drop/x.txt", "keep")
            val db = StateDatabase(profileDir.resolve("state.db"))
            try {
                db.initialize()
                val left = db.pendingRowDetails()
            assertEquals(1, left.size, "nothing was dropped; stdout was: " + text)
            assertEquals("/drop/x.txt", left.single().path)
            } finally {
                db.close()
            }
        } finally {
            System.setIn(stdin)
        }
    }

    @Test
    fun `zero-byte selects rows whose local size is zero and whose cache is missing or empty`() {
        seedRow("/zero/missing.txt", localSize = 0)
        seedRow("/zero/empty-cache.txt", localSize = 0)
        seedRow("/zero/with-content.txt", localSize = 0)
        seedRow("/zero/sized.txt", localSize = 5)
        seedCache("/zero/empty-cache.txt", "")
        seedCache("/zero/with-content.txt", "real bytes")

        assertEquals(0, run("drop", "--zero-byte", "--yes"))
        val db = StateDatabase(profileDir.resolve("state.db"))
        try {
            db.initialize()
            val left = db.pendingRowDetails().map { it.path }
            assertTrue("/zero/missing.txt" !in left, "no cache file: stranded, dropped; left=$left")
            assertTrue("/zero/empty-cache.txt" !in left, "an empty cache file: stranded, dropped; left=$left")
            assertTrue("/zero/with-content.txt" in left, "a cache with content stays (moved aside, not dropped blind); left=$left")
            assertTrue("/zero/sized.txt" in left, "a non-zero local size stays; left=$left")
        } finally {
            db.close()
        }
    }

    @Test
    fun `a non-empty cache copy of a dropped row is moved aside, never deleted`() {
        seedRow("/drop/moved.txt", localSize = 0)
        seedCache("/drop/moved.txt", "the only copy")
        // local_size is 0 but the cache has content: --zero-byte skips it, a prefix drop does not.
        val asideBefore = Files.exists(cacheRoot.resolveSibling("dropped-pending"))

        assertEquals(0, run("drop", "/drop/moved.txt", "--yes"))
        val text = out.toString(Charsets.UTF_8)
        assertTrue("moved to" in text, text)
        val asideFolder =
            Files.list(cacheRoot.parent).use { stream ->
                stream.filter { it.fileName.toString().startsWith("dropped-pending") }.findFirst().orElse(null)
            }
        assertTrue(asideFolder != null || asideBefore, "the aside folder is a sibling of the cache")
        val movedFile =
            Files.walk(asideFolder).use { stream ->
                stream.filter { Files.isRegularFile(it) }.findFirst().orElse(null)
            }
        assertTrue(movedFile != null && Files.readString(movedFile) == "the only copy", "the content survives, moved not deleted")
    }

    @Test
    fun `a daemon holding the profile refuses with daemon_running and changes nothing`() {
        seedRow("/drop/x.txt")
        holder = ProcessLock(profileDir.resolve(".lock"))
        holder!!.tryLock(ProcessLock.Mode.DAEMON)

        assertEquals(1, run("drop", "/drop/x.txt", "--yes"))
        assertTrue("a daemon is running" in err.toString(Charsets.UTF_8), "the text path words the refusal: " + err.toString(Charsets.UTF_8))
        val db = StateDatabase(profileDir.resolve("state.db"))
        try {
            db.initialize()
            assertTrue(db.getEntry("/drop/x.txt") != null, "nothing changed")
        } finally {
            db.close()
        }
    }

    @Test
    fun `an unreadable state database refuses with state_unreadable`() {
        Files.write(profileDir.resolve("state.db"), "not a database at all".toByteArray())

        assertEquals(1, run("list"))
        assertTrue("could not be read" in err.toString(Charsets.UTF_8), "the text path words the refusal: " + err.toString(Charsets.UTF_8))
    }

    @Test
    fun `the json refusal carries the token`() {
        seedRow("/drop/x.txt")
        holder = ProcessLock(profileDir.resolve(".lock"))
        holder!!.tryLock(ProcessLock.Mode.DAEMON)
        try {
            assertEquals(1, run("drop", "/drop/x.txt", "--yes", "--json"))
            val json = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
            assertEquals("daemon_running", json.getValue("error").jsonPrimitive.content)
        } finally {
            holder?.unlock()
            holder = null
        }
    }
}
