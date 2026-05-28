package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two byte-forms of the same visible filename "foo.txt"-with-diaereses. The
 * co-daemon may relay a decomposed (NFD) path for a row that was ingested/stored
 * composed (NFC). Explicit \u escapes are used so the two forms are unambiguously
 * different byte sequences — a literal accented char on both sides would test nothing.
 *   NFC: "ö" is the single precomposed code point (LATIN SMALL LETTER O WITH DIAERESIS).
 *   NFD: "ö" is "o" + COMBINING DIAERESIS.
 */
private const val NFC_NAME = "f\u00F6\u00F6.txt"
private const val NFD_NAME = "fo\u0308o\u0308.txt"
private const val NFC_PATH = "/$NFC_NAME"
private const val NFD_PATH = "/$NFD_NAME"

/**
 * NFC-completeness at the IPC boundary: the co-daemon path fields (path/old_path/
 * new_path) are NFC-normalized in HydrationIpcHandler before reaching Hydration, so
 * an NFD-form request resolves the NFC-stored row, remote object, and cache file.
 */
class HydrationIpcHandlerNfcTest {

    private class RecordingProvider : CloudProvider {
        override val id = "fake-nfc"
        override val displayName = "Fake (NFC test)"
        override var isAuthenticated = true

        var lastDeletedPath: String? = null
        val downloadedPaths = mutableListOf<String>()

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        override suspend fun getMetadata(path: String): CloudItem = throw ProviderException("Item not found: $path")
        override suspend fun download(remotePath: String, destination: Path): Long {
            Files.createDirectories(destination.parent)
            Files.write(destination, "hello".toByteArray())
            return 5L
        }
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long {
            downloadedPaths += remotePath
            return download(remotePath, destination)
        }
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")
        override suspend fun delete(remotePath: String) {
            lastDeletedPath = remotePath
        }
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")
        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)
        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private class Env {
        val provider = RecordingProvider()
        val db: StateDatabase
        val engine: SyncEngine
        val handler: HydrationIpcHandler

        init {
            val cacheRoot = Files.createTempDirectory("unidrive-nfc-cache")
            val dbPath = Files.createTempDirectory("unidrive-nfc-db").resolve("state.db")
            db = StateDatabase(dbPath = dbPath, inMemory = true)
            db.initialize()
            engine = SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("unidrive-nfc-sync"),
                cacheRoot = cacheRoot,
            )
            handler = HydrationIpcHandler(HydrationImpl(syncEngine = engine, stateDb = db))
        }

        fun insertUploadedFile(path: String, size: Long) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = "rid-$path",
                    remoteHash = "hash",
                    remoteSize = size,
                    remoteModified = Instant.parse("2026-05-28T09:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
        }
    }

    @Test
    fun nfd_unlink_resolves_the_nfc_stored_entry() = runTest {
        // Precondition: the two forms differ at the byte level (else the test is vacuous).
        assertTrue(NFC_NAME != NFD_NAME, "NFC and NFD test names must be different byte sequences")

        val env = Env()
        // Row stored under the COMPOSED (NFC) name, as the ingestion chokepoint stores it.
        env.insertUploadedFile(NFC_PATH, size = 5)

        // unlink arrives under the DECOMPOSED (NFD) name from the co-daemon.
        val reply = env.handler.handle("conn1", """{"verb":"hydration.unlink","path":"$NFD_PATH"}""")

        assertEquals("""{"ok":true}""", reply.trim(), "NFD unlink of an NFC-stored file must succeed")
        // The provider.delete call must have fired with the NFC form — proving the
        // boundary normalized the NFD request so it matched the NFC-stored cloud object
        // (an un-normalized NFD delete would 404 on the NFC name).
        assertEquals(NFC_PATH, env.provider.lastDeletedPath, "provider.delete must receive the NFC path")
        // The NFC-stored row is now tombstoned (no longer alive).
        assertNull(env.db.getEntry(NFC_PATH), "the NFC-stored row must be removed by the NFD unlink")
    }

    @Test
    fun nfd_open_read_hits_the_nfc_cache_file() = runTest {
        assertTrue(NFC_NAME != NFD_NAME, "NFC and NFD test names must be different byte sequences")

        val env = Env()
        // Row stored under the COMPOSED (NFC) name.
        env.insertUploadedFile(NFC_PATH, size = 5)

        // open_read arrives under the DECOMPOSED (NFD) name.
        val reply = env.handler.handle("conn1", """{"verb":"hydration.open_read","handle_id":"h1","path":"$NFD_PATH"}""")

        assertTrue(reply.contains("\"ok\":true"), "NFD open_read of an NFC-stored file must succeed: $reply")
        // The download (provider call) must have fired with the NFC remote path.
        assertEquals(listOf(NFC_PATH), env.provider.downloadedPaths, "downloadById must receive the NFC remote path")
        // The cache file resolved by SyncEngine.resolveCachePath must carry the NFC byte
        // form in its last segment — an un-normalized NFD path would resolve a different,
        // NFD-named cache file and miss the NFC one.
        val cachePath = env.engine.resolveCachePath(NFC_PATH)
        assertTrue(Files.exists(cachePath), "the NFC-named cache file must exist after hydration")
        assertTrue(
            reply.contains(NFC_NAME) && !reply.contains(NFD_NAME),
            "the returned cache_path must carry the NFC name, not the NFD form: $reply",
        )
    }
}
