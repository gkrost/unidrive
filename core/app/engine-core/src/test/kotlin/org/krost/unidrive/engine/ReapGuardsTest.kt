package org.krost.unidrive.engine

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule the enumeration's reap and `refresh.run`'s reset share: a row whose hydration cache may
 * hold the only copy of an edit that has not reached the cloud is not dropped.
 */
class ReapGuardsTest {
    private lateinit var cacheRoot: Path
    private val inFlight = mutableSetOf<String>()

    @BeforeTest
    fun setUp() {
        cacheRoot = Files.createTempDirectory("unidrive-reap-guards")
    }

    @AfterTest
    fun tearDown() {
        cacheRoot.toFile().deleteRecursively()
    }

    private fun guards(cachePathOf: (String) -> Path = { cacheRoot.resolve(it.removePrefix("/")) }) =
        RemoteEnumeration.ReapGuards(uploadInFlight = { it in inFlight }, cachePathOf = cachePathOf)

    private val watermark = Instant.parse("2026-03-01T00:00:00Z")

    private fun row(
        path: String,
        remoteId: String? = "id-$path",
        hydrated: Boolean = true,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 1,
        remoteModified = null,
        localMtime = 1,
        localSize = 1,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = watermark,
    )

    private fun cacheFile(
        path: String,
        modified: Instant,
    ): Path {
        val file = cacheRoot.resolve(path.removePrefix("/"))
        Files.createDirectories(file.parent)
        Files.writeString(file, "bytes")
        Files.setLastModifiedTime(file, FileTime.from(modified))
        return file
    }

    @Test
    fun `a cache copy newer than the row's last-synced watermark is an unsynced edit`() {
        cacheFile("/edited.txt", watermark.plusSeconds(60))
        assertTrue(guards().holdsUnsyncedEdit("/edited.txt", row("/edited.txt")))
    }

    @Test
    fun `a cache copy no newer than the watermark is not`() {
        cacheFile("/clean.txt", watermark.minusSeconds(60))
        cacheFile("/same.txt", watermark)
        assertFalse(guards().holdsUnsyncedEdit("/clean.txt", row("/clean.txt")))
        assertFalse(guards().holdsUnsyncedEdit("/same.txt", row("/same.txt")))
    }

    @Test
    fun `no cache copy and no upload under way is not`() {
        assertFalse(guards().holdsUnsyncedEdit("/absent.txt", row("/absent.txt")))
    }

    @Test
    fun `an upload under way counts, with or without a row`() {
        inFlight += "/uploading.txt"
        assertTrue(guards().holdsUnsyncedEdit("/uploading.txt", row("/uploading.txt")))
        assertTrue(guards().holdsUnsyncedEdit("/uploading.txt", null))
    }

    @Test
    fun `a row that never reached the cloud counts`() {
        assertTrue(guards().holdsUnsyncedEdit("/new.txt", row("/new.txt", remoteId = null)))
        assertFalse(
            guards().holdsUnsyncedEdit("/partial.bin", row("/partial.bin", remoteId = null, hydrated = false)),
            "a sparse partial download has no bytes to upload",
        )
    }

    @Test
    fun `no row and no upload is not, whatever the cache holds`() {
        cacheFile("/orphan.txt", watermark.plusSeconds(60))
        assertFalse(guards().holdsUnsyncedEdit("/orphan.txt", null))
    }

    @Test
    fun `a cache path that cannot be resolved is not an unsynced edit`() {
        val guards = guards(cachePathOf = { throw IllegalArgumentException("no such cache name") })
        assertFalse(guards.holdsUnsyncedEdit("/odd.txt", row("/odd.txt")))
    }
}
