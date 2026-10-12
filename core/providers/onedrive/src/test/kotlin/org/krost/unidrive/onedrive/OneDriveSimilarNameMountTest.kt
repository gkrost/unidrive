package org.krost.unidrive.onedrive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.hydration.CreateResult
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.MkdirResult
import org.krost.unidrive.hydration.OpenResult
import org.krost.unidrive.hydration.RmdirResult
import org.krost.unidrive.hydration.UnlinkResult
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real OneDriveProvider under the real mount front-end (HydrationImpl over a SyncEngine host and a state.db),
 * against [SimilarNameGraph]: the corpus case in miniature. Names OneDrive compares as equal to a sibling's land
 * under a disambiguated server name, the local tree keeps its names, an enumeration afterwards neither duplicates
 * nor resets anything, and what the drive refuses for good is blocked with its reason without a request storm.
 */
class OneDriveSimilarNameMountTest {
    private lateinit var tmp: Path
    private lateinit var graph: SimilarNameGraph
    private lateinit var provider: OneDriveProvider
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private lateinit var hydration: HydrationImpl
    private val completed = java.util.concurrent.CopyOnWriteArrayList<HydrationEvent.Completed>()
    private lateinit var collector: Job

    private val ethiopic = "አአ"
    private val nko = "ߒ"
    private val tifinagh = "g2-c-ⵜ.txt"
    private val vai = "g2-c-ꕙ.txt"

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("ud-similar-mount")
        graph = SimilarNameGraph()
        val config = OneDriveConfig(tokenPath = tmp.resolve("token"))
        provider = OneDriveProvider(config)
        val api = GraphApiService(config = config, tokenProvider = { _ -> "test-token" })
        GraphApiService::class.java.getDeclaredField("httpClient").apply {
            isAccessible = true
            (get(api) as? io.ktor.client.HttpClient)?.close()
            set(api, graph.client())
        }
        OneDriveProvider::class.java.getDeclaredField("graphApi").apply {
            isAccessible = true
            (get(provider) as? GraphApiService)?.close()
            set(provider, api)
        }
        db = StateDatabase(dbPath = tmp.resolve("state.db"), inMemory = true).also { it.initialize() }
        engine = SyncEngine(provider = provider, db = db, syncRoot = tmp.resolve("sync"), cacheRoot = tmp.resolve("cache"))
        hydration = HydrationImpl(syncEngine = engine, stateDb = db, recoveryUploadScope = CoroutineScope(Dispatchers.IO), uploadRetryDelaysMs = listOf(10L, 10L), failedReplayDelayMs = 0L)
        collector =
            CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                hydration.events.collect { if (it is HydrationEvent.Completed) completed += it }
            }
    }

    @AfterTest
    fun tearDown() {
        collector.cancel()
        runBlocking { hydration.shutdownUploads() }
        db.close()
        provider.close()
        tmp.toFile().deleteRecursively()
    }

    private fun write(path: String, content: String): HydrationEvent.Completed {
        val handle = "h-${System.nanoTime()}"
        val cache =
            if (db.getEntry(path) == null) assertIs<CreateResult.Ok>(runBlocking { hydration.create("c", handle, path) }).cachePath else engine.resolveCachePath(path)
        Files.writeString(cache, content)
        assertIs<OpenResult.Ok>(runBlocking { hydration.openForWrite("c", handle, path, cache, null) })
        val deadline = System.currentTimeMillis() + 30_000
        while (completed.none { it.handleId == handle }) {
            check(System.currentTimeMillis() < deadline) { "upload of $path did not end" }
            Thread.sleep(20)
        }
        return completed.first { it.handleId == handle }
    }

    private fun mkdir(path: String) = runBlocking { hydration.mkdir(path) }

    @Test
    fun `colliding files and folders land under server names, the local tree keeps its names, and enumeration changes nothing`() {
        graph.seed("/african", isFolder = true)
        graph.seed("/african/$ethiopic", isFolder = true)
        graph.seed("/african/$tifinagh", bytes = "first".toByteArray())
        runBlocking { engine.enumerateRemoteIntoState(reset = true) }

        // A folder and a file whose names the drive compares as equal to existing siblings.
        assertEquals(MkdirResult.Ok, mkdir("/african/$nko"))
        assertTrue(write("/african/$nko/a.txt", "inside").ok)
        assertEquals(MkdirResult.Ok, mkdir("/african/$nko/sub"))
        assertTrue(write("/african/$nko/sub/b.txt", "deeper").ok)
        assertTrue(write("/african/$vai", "second").ok)

        val serverFolder = OneDriveServerNames.disambiguate(nko)
        val serverFile = OneDriveServerNames.disambiguate(vai)
        assertEquals("inside", String(assertNotNull(graph.find("/african/$serverFolder/a.txt")).bytes))
        assertEquals("deeper", String(assertNotNull(graph.find("/african/$serverFolder/sub/b.txt")).bytes))
        assertEquals("second", String(assertNotNull(graph.find("/african/$serverFile")).bytes))
        assertEquals("first", String(assertNotNull(graph.find("/african/$tifinagh")).bytes), "the sibling is untouched")
        assertNull(graph.find("/african/$nko"), "the refused names do not exist on the drive")

        val localBefore = db.getAllEntries().map { it.path }.toSet()
        val aBefore = assertNotNull(db.getEntry("/african/$nko/a.txt"))
        val result = runBlocking { engine.enumerateRemoteIntoState(reset = true) }

        assertTrue(result.ok)
        assertEquals(localBefore, db.getAllEntries().map { it.path }.toSet(), "no duplicate local entry under a server name")
        val aAfter = assertNotNull(db.getEntry("/african/$nko/a.txt"))
        assertEquals(aBefore.remoteId, aAfter.remoteId)
        assertTrue(aAfter.isHydrated)
        assertEquals("inside", Files.readString(engine.resolveCachePath("/african/$nko/a.txt")), "the local file is as written")

        // Update, rename and delete address the server items.
        assertTrue(write("/african/$vai", "second v2").ok)
        assertEquals("second v2", String(graph.find("/african/$serverFile")!!.bytes))
        assertEquals(UnlinkResult.Ok, runBlocking { hydration.unlink("/african/$vai") })
        assertNull(graph.find("/african/$serverFile"), "the server item went")
        assertNotNull(graph.find("/african/$tifinagh"), "DATA LOSS: the delete reached the similar sibling")
        assertEquals(RmdirResult.Ok, runBlocking { hydration.rmdir("/african/$nko") })
        assertNull(graph.find("/african/$serverFolder"), "the server folder went with its children")
        assertNotNull(graph.find("/african/$ethiopic"), "DATA LOSS: the delete reached the similar sibling folder")
    }

    @Test
    fun `a name refused under every name is blocked with its reason and does not storm the drive`() {
        graph.seed("/african", isFolder = true)
        graph.seed("/african/$tifinagh", bytes = "first".toByteArray())
        // The disambiguated name is taken by something that is not ours: the second attempt is refused as well.
        graph.seed("/african/${OneDriveServerNames.disambiguate(vai)}", bytes = "taken".toByteArray())
        runBlocking { engine.enumerateRemoteIntoState(reset = true) }
        graph.requests.clear()

        val done = write("/african/$vai", "precious")

        assertEquals(false, done.ok)
        assertEquals("similar_name: $tifinagh", done.error?.message)
        assertEquals(2, graph.requestsOf("PUT").size, "one attempt per name, no retry ladder")
        assertEquals("precious", Files.readString(engine.resolveCachePath("/african/$vai")), "the local file is untouched")
        assertEquals("taken", String(graph.find("/african/${OneDriveServerNames.disambiguate(vai)}")!!.bytes), "nothing was overwritten")
        val blocked = hydration.uploadHealth().blocked
        assertEquals(1, blocked.count)
        assertEquals(HydrationImpl.BlockedItem("/african/$vai", "similar_name", sibling = tifinagh, parent = null), blocked.items.single())
    }
}
