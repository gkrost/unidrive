package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A drive that refuses an entry by NAME (OneDrive: the name compares as equal to a sibling's). The provider
 * either lands the entry under a server name (CloudItem.nameOverridden) or refuses it (NameRefusedException).
 * What the mount does with each:
 *  - a landed entry keeps its local name; the row remembers the server path (SyncEntry.remotePath), the children
 *    of a landed folder are addressed beneath it, and enumeration, update, rename and delete find the right item;
 *  - a refused entry is blocked with a machine reason; only that subtree is blocked (blocked_by_parent for the
 *    rest), nothing is retried, and the local file is left alone.
 */
class NameRefusalTest {
    /** A tree drive: parents must exist; [override] and [refuse] script the name rule per requested remote path. */
    private class NameRuleProvider : CloudProvider {
        override val id = "fake-names"
        override val displayName = "Fake (names)"
        override var isAuthenticated = true
        val items = linkedMapOf<String, CloudItem>()
        val bytes = hashMapOf<String, ByteArray>()
        val calls = mutableListOf<String>()
        val override = hashMapOf<String, String>()
        val refuse = hashMapOf<String, String?>()
        private var n = 0

        fun callsOf(prefix: String): List<String> = calls.filter { it.startsWith("$prefix ") }

        private fun requireParent(path: String) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty() && items[parent]?.isFolder != true) throw ProviderException("Folder not found: $parent in $path")
        }

        private fun place(requested: String, isFolder: Boolean, content: ByteArray): CloudItem {
            requireParent(requested)
            refuse[requested]?.let { throw NameRefusedException("refused: $requested", sibling = it.takeIf { s -> s.isNotEmpty() }) }
            if (refuse.containsKey(requested)) throw NameRefusedException("refused: $requested")
            val parent = requested.substringBeforeLast('/', "")
            val landedName = override[requested]
            val landed = if (landedName != null) "$parent/$landedName" else requested
            val item =
                CloudItem(
                    id = "id-${++n}",
                    name = landed.substringAfterLast('/'),
                    path = landed,
                    size = content.size.toLong(),
                    isFolder = isFolder,
                    modified = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(n.toLong()),
                    created = null,
                    hash = if (isFolder) null else "h$n",
                    mimeType = null,
                    nameOverridden = landedName != null,
                )
            items[landed] = item
            bytes[landed] = content
            return item
        }

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem {
            calls += "metadata $path"
            return items[path] ?: throw ProviderException("Item not found: $path")
        }

        override suspend fun download(remotePath: String, destination: Path): Long {
            calls += "download $remotePath"
            Files.write(destination, bytes.getValue(remotePath))
            return bytes.getValue(remotePath).size.toLong()
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            calls += "upload $remotePath"
            val content = Files.readAllBytes(localPath)
            if (existingRemoteId != null && items.containsKey(remotePath)) {
                val old = items.getValue(remotePath)
                val item = old.copy(size = content.size.toLong(), hash = "h${++n}")
                items[remotePath] = item
                bytes[remotePath] = content
                return item
            }
            return place(remotePath, false, content)
        }

        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
            calls += "delete $remotePath"
            items.keys.filter { it == remotePath || it.startsWith("$remotePath/") }.forEach {
                items.remove(it)
                bytes.remove(it)
            }
        }

        override suspend fun createFolder(path: String): CloudItem {
            calls += "createFolder $path"
            return place(path, true, ByteArray(0))
        }

        override suspend fun move(fromPath: String, toPath: String): CloudItem {
            calls += "move $fromPath $toPath"
            requireParent(toPath)
            val moving = items.keys.filter { it == fromPath || it.startsWith("$fromPath/") }
            if (moving.isEmpty()) throw ProviderException("Item not found: $fromPath")
            for (old in moving) {
                val item = items.remove(old)!!
                val newPath = toPath + old.removePrefix(fromPath)
                items[newPath] = item.copy(path = newPath, name = newPath.substringAfterLast('/'), nameOverridden = false)
                bytes.remove(old)?.let { bytes[newPath] = it }
            }
            return items.getValue(toPath)
        }

        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = items.values.toList(), cursor = "c", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private lateinit var provider: NameRuleProvider
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private lateinit var hydration: HydrationImpl
    private val events = mutableListOf<HydrationEvent>()
    private lateinit var collector: Job

    @BeforeTest
    fun setUp() {
        provider = NameRuleProvider()
        db = StateDatabase(dbPath = Files.createTempDirectory("ud-name-db").resolve("state.db"), inMemory = true)
        db.initialize()
        engine = SyncEngine(provider = provider, db = db, syncRoot = Files.createTempDirectory("ud-name-sync"), cacheRoot = Files.createTempDirectory("ud-name-cache"))
        hydration =
            HydrationImpl(
                syncEngine = engine,
                stateDb = db,
                recoveryUploadScope = CoroutineScope(Dispatchers.IO),
                uploadRetryDelaysMs = listOf(10L, 10L),
                failedReplayDelayMs = 0L,
            )
        collector = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            hydration.events.collect { synchronized(events) { events += it } }
        }
    }

    @AfterTest
    fun tearDown() {
        collector.cancel()
        runBlocking { hydration.shutdownUploads() }
        db.close()
    }

    // Writes [content] to the cache copy of [path] (creating the row when there is none) and waits for the upload's end.
    private fun write(path: String, content: String): HydrationEvent.Completed {
        val handle = "h-${path.hashCode()}-${System.nanoTime()}"
        val cachePath =
            if (db.getEntry(path) == null) {
                assertIs<CreateResult.Ok>(runBlocking { hydration.create("c", handle, path) }).cachePath
            } else {
                engine.resolveCachePath(path)
            }
        Files.writeString(cachePath, content)
        assertIs<OpenResult.Ok>(runBlocking { hydration.openForWrite("c", handle, path, cachePath, null) })
        awaitUntil { synchronized(events) { events.any { it is HydrationEvent.Completed && it.handleId == handle } } }
        return synchronized(events) { events.filterIsInstance<HydrationEvent.Completed>().first { it.handleId == handle } }
    }

    private fun mkdir(path: String) = runBlocking { hydration.mkdir(path) }

    private fun cacheText(path: String): String = Files.readString(engine.resolveCachePath(path))

    private fun row(path: String): SyncEntry = assertNotNull(db.getEntry(path), "row $path")

    private val folderName = "ⵜ"

    // ── landed under a server name ───────────────────────────────────────────────────────────────

    @Test
    fun `a file landed under a server name keeps its local name and the row remembers the server path`() {
        provider.override["/dir/f.txt"] = "f~ud1.txt"
        assertEquals(MkdirResult.Ok, mkdir("/dir"))

        val done = write("/dir/f.txt", "v1")

        assertTrue(done.ok)
        val r = row("/dir/f.txt")
        assertNotNull(r.remoteId)
        assertEquals("/dir/f~ud1.txt", r.remotePath, "the server path is remembered; the local path stays")
        assertNull(db.getEntry("/dir/f~ud1.txt"), "no second local entry under the server name")
        assertEquals("v1", cacheText("/dir/f.txt"))
        assertEquals(listOf("upload /dir/f.txt"), provider.callsOf("upload"), "one request, no retry")

        // An update goes to the server path, replacing in place.
        assertTrue(write("/dir/f.txt", "v2").ok)
        assertEquals(listOf("upload /dir/f.txt", "upload /dir/f~ud1.txt"), provider.callsOf("upload"))
        assertEquals("v2", String(provider.bytes.getValue("/dir/f~ud1.txt")))
    }

    @Test
    fun `renaming and deleting a landed file address the server item`() {
        provider.override["/dir/f.txt"] = "f~ud1.txt"
        mkdir("/dir")
        write("/dir/f.txt", "v1")
        write("/dir/other.txt", "o")

        assertEquals(RenameResult.Ok, runBlocking { hydration.rename("/dir/f.txt", "/dir/g.txt", replace = false) })

        assertEquals(listOf("move /dir/f~ud1.txt /dir/g.txt"), provider.callsOf("move"))
        assertNull(row("/dir/g.txt").remotePath, "under an ordinary name the row needs no override")

        // And the other way: delete a landed file.
        provider.override["/dir/h.txt"] = "h~ud2.txt"
        write("/dir/h.txt", "h")
        assertEquals(UnlinkResult.Ok, runBlocking { hydration.unlink("/dir/h.txt") })
        assertEquals(listOf("delete /dir/h~ud2.txt"), provider.callsOf("delete"))
        assertNull(db.getEntry("/dir/h.txt"))
    }

    @Test
    fun `a folder landed under a server name takes its children and sub-folders beneath it`() {
        provider.override["/african/$folderName"] = "$folderName~ud9"
        assertEquals(MkdirResult.Ok, mkdir("/african"))

        assertEquals(MkdirResult.Ok, mkdir("/african/$folderName"))
        val folder = row("/african/$folderName")
        assertNotNull(folder.remoteId)
        assertEquals("/african/$folderName~ud9", folder.remotePath)
        assertTrue(provider.items.containsKey("/african/$folderName~ud9"))

        assertTrue(write("/african/$folderName/a.txt", "A").ok)
        assertEquals("/african/$folderName~ud9/a.txt", row("/african/$folderName/a.txt").remotePath)
        assertEquals(MkdirResult.Ok, mkdir("/african/$folderName/sub"))
        assertEquals("/african/$folderName~ud9/sub", row("/african/$folderName/sub").remotePath)
        assertTrue(write("/african/$folderName/sub/b.txt", "B").ok)
        assertTrue(provider.items.containsKey("/african/$folderName~ud9/sub/b.txt"))
        assertEquals("A", cacheText("/african/$folderName/a.txt"))
    }

    @Test
    fun `enumeration after the resolution neither duplicates nor resets the mapped entries`() {
        provider.override["/african/$folderName"] = "$folderName~ud9"
        mkdir("/african")
        mkdir("/african/$folderName")
        write("/african/$folderName/a.txt", "A")
        val before = db.getAllEntries().map { it.path }.toSet()
        val hydratedBefore = row("/african/$folderName/a.txt")

        // Another device adds a file beneath the landed folder.
        provider.items["/african/$folderName~ud9/new.txt"] =
            CloudItem("id-new", "new.txt", "/african/$folderName~ud9/new.txt", 3, false, Instant.parse("2026-02-01T00:00:00Z"), null, "hn", null)
        val result = runBlocking { engine.enumerateRemoteIntoState(reset = true) }

        assertTrue(result.ok)
        val after = db.getAllEntries().map { it.path }.toSet()
        assertEquals(before + "/african/$folderName/new.txt", after, "no entry under the server name; the new file appears under the local name")
        assertEquals("/african/$folderName~ud9/new.txt", row("/african/$folderName/new.txt").remotePath)
        val a = row("/african/$folderName/a.txt")
        assertEquals(hydratedBefore.remoteId, a.remoteId)
        assertTrue(a.isHydrated, "the enumeration did not reset the row")
        assertEquals(hydratedBefore.localMtime, a.localMtime)
    }

    @Test
    fun `renaming and removing a landed folder move and delete the server folder with its children`() {
        provider.override["/african/$folderName"] = "$folderName~ud9"
        mkdir("/african")
        mkdir("/african/$folderName")
        write("/african/$folderName/a.txt", "A")

        assertEquals(RenameResult.Ok, runBlocking { hydration.rename("/african/$folderName", "/african/plain", replace = false) })

        assertEquals(listOf("move /african/$folderName~ud9 /african/plain"), provider.callsOf("move"))
        assertNull(row("/african/plain").remotePath)
        assertNull(row("/african/plain/a.txt").remotePath, "the child's server path follows the folder's rename")

        provider.override["/african/$folderName"] = "$folderName~ud9"
        mkdir("/african/$folderName")
        assertEquals(RmdirResult.Ok, runBlocking { hydration.rmdir("/african/$folderName") })
        assertEquals(listOf("delete /african/$folderName~ud9"), provider.callsOf("delete"))
    }

    // ── refused by name: blocked ──────────────────────────────────────────────────────────────────

    @Test
    fun `a file refused by name is blocked with its reason, without retries, and the local file stays`() {
        provider.refuse["/dir/f.txt"] = "sibling.txt"
        mkdir("/dir")

        val done = write("/dir/f.txt", "precious")

        assertEquals(false, done.ok)
        assertEquals("similar_name: sibling.txt", done.error?.message)
        assertEquals(1, provider.callsOf("upload").size, "no retry ladder for a refusal by name")
        val block = assertNotNull(db.nameBlock("/dir/f.txt"))
        assertEquals("similar_name", block.reason)
        assertEquals("sibling.txt", block.sibling)
        assertEquals("precious", cacheText("/dir/f.txt"), "the local bytes are untouched")
        val failed = synchronized(events) { events.filterIsInstance<HydrationEvent.Failed>() }
        assertTrue(failed.isNotEmpty())
        assertTrue(failed.all { it.retryScheduled != true })
        val health = hydration.uploadHealth()
        assertEquals(1, health.blocked.count)
        assertEquals(
            listOf(HydrationImpl.BlockedItem("/dir/f.txt", "similar_name", sibling = "sibling.txt", parent = null)),
            health.blocked.items,
        )
        assertEquals(1, health.failed, "still a failed pending upload as well")
        val listed = assertIs<ListResult.Ok>(runBlocking { hydration.list("/dir") }).entries.single()
        assertEquals("similar_name", listed.blocked)
    }

    @Test
    fun `a folder refused by name is accepted locally and its subtree is blocked by parent`() {
        provider.refuse["/african/$folderName"] = "other"
        mkdir("/african")

        assertEquals(MkdirResult.Ok, mkdir("/african/$folderName"), "accepted locally, so the client can fill it")
        assertEquals(MkdirResult.Ok, mkdir("/african/$folderName/sub"))
        val done = write("/african/$folderName/a.txt", "A")
        write("/african/$folderName/sub/b.txt", "B")

        assertEquals(false, done.ok)
        assertEquals("blocked_by_parent: /african/$folderName", done.error?.message)
        assertEquals(1, provider.callsOf("createFolder").filter { it.contains(folderName) }.size, "the refused folder is asked once; its children never reach the drive")
        assertTrue(provider.callsOf("upload").isEmpty())
        assertEquals("A", cacheText("/african/$folderName/a.txt"))

        val blocked = hydration.uploadHealth().blocked
        assertEquals(4, blocked.count)
        assertEquals(
            listOf(
                HydrationImpl.BlockedItem("/african/$folderName", "similar_name", sibling = "other", parent = null),
                HydrationImpl.BlockedItem("/african/$folderName/a.txt", "blocked_by_parent", sibling = null, parent = "/african/$folderName"),
                HydrationImpl.BlockedItem("/african/$folderName/sub", "blocked_by_parent", sibling = null, parent = "/african/$folderName"),
                HydrationImpl.BlockedItem("/african/$folderName/sub/b.txt", "blocked_by_parent", sibling = null, parent = "/african/$folderName"),
            ),
            blocked.items,
        )
        assertEquals(0, blocked.more)
        assertNull(db.nameBlock("/african/$folderName/a.txt"), "the child carries no block of its own: the parent's is the reason")
    }

    @Test
    fun `the blocked list is capped and counts the rest`() {
        provider.refuse["/x"] = null
        mkdir("/x")
        val total = HydrationImpl.BLOCKED_ITEMS_CAP + 11
        for (i in 1..total) {
            db.upsertEntry(
                SyncEntry(
                    path = "/x/f%03d.txt".format(i), remoteId = null, remoteHash = null, remoteSize = 0L, remoteModified = null,
                    localMtime = 1L, localSize = 1L, isFolder = false, isPinned = false, isHydrated = true, lastSynced = Instant.now(),
                ),
            )
        }

        val blocked = hydration.uploadHealth().blocked

        assertEquals(total + 1, blocked.count)
        assertEquals(HydrationImpl.BLOCKED_ITEMS_CAP, blocked.items.size)
        assertEquals(12, blocked.more)
        assertEquals("/x", blocked.items.first().path, "the blocked folder itself comes first")
    }

    @Test
    fun `a blocked folder is retried at the next replay and its subtree follows`() {
        provider.refuse["/african/$folderName"] = "other"
        mkdir("/african")
        mkdir("/african/$folderName")
        mkdir("/african/$folderName/sub")
        write("/african/$folderName/a.txt", "A")
        assertTrue(provider.items.keys.none { it.contains(folderName) })

        provider.refuse.clear() // the sibling is gone
        runBlocking { hydration.replayPendingUploads() }
        awaitUntil { db.getEntry("/african/$folderName/a.txt")?.remoteId != null }

        assertNull(db.nameBlock("/african/$folderName"))
        assertNotNull(row("/african/$folderName").remoteId)
        assertNotNull(row("/african/$folderName/sub").remoteId)
        assertTrue(provider.items.containsKey("/african/$folderName/sub"))
        assertTrue(provider.items.containsKey("/african/$folderName/a.txt"))
        assertEquals(0, hydration.uploadHealth().blocked.count)
    }

    @Test
    fun `deleting a blocked entry never reaches the drive`() {
        provider.refuse["/dir/f.txt"] = "sibling.txt"
        mkdir("/dir")
        write("/dir/f.txt", "x")
        provider.calls.clear()

        assertEquals(UnlinkResult.Ok, runBlocking { hydration.unlink("/dir/f.txt") })

        assertTrue(provider.callsOf("delete").isEmpty())
        assertNull(db.getEntry("/dir/f.txt"))
    }

    private fun awaitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not reached in ${timeoutMs}ms" }
            Thread.sleep(20)
        }
    }
}
