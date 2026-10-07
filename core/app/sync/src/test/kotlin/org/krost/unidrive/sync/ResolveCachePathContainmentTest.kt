package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SyncEngine.resolveCachePath] keeps every cache file inside the profile's hydration cache folder
 * (`<cacheRoot>/unidrive/hydration/<cacheKey>`), the way [safeResolveLocal] keeps local files inside the sync root:
 * the result is normalised, and a path that would resolve outside the folder throws [SecurityException]. A pass
 * over rows (the enumeration's reap) skips the cache side of such a row instead of failing.
 */
class ResolveCachePathContainmentTest {
    private val windows = System.getProperty("os.name", "").lowercase().contains("win")
    private val bs = Char(92)

    private lateinit var base: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: EnumerateRemoteIntoStateTest.EnumerateFakeProvider
    private lateinit var engine: SyncEngine
    private lateinit var cacheDir: Path

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("ud-cache-containment")
        db = StateDatabase(base.resolve("state.db"))
        db.initialize()
        provider = EnumerateRemoteIntoStateTest.EnumerateFakeProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createDirectories(base.resolve("root")),
                cacheRoot = base.resolve("cache"),
                cacheKey = "profile",
            )
        cacheDir = base.resolve("cache").resolve("unidrive").resolve("hydration").resolve("profile")
    }

    @AfterTest
    fun tearDown() {
        db.close()
        base.toFile().deleteRecursively()
    }

    @Test
    fun `a normal path resolves below the profile's cache folder`() {
        assertEquals(cacheDir.resolve("docs").resolve("a.txt"), engine.resolveCachePath("/docs/a.txt"))
        assertEquals(cacheDir.resolve("a.txt"), engine.resolveCachePath("a.txt"))
    }

    @Test
    fun `the root resolves to the cache folder itself`() {
        assertEquals(cacheDir, engine.resolveCachePath("/"))
        assertEquals(cacheDir, engine.resolveCachePath(""))
    }

    @Test
    fun `a path that climbs out of the cache folder is refused`() {
        for (path in listOf("/..", "/../outside.txt", "/docs/../../outside.txt", "/../../../../../x/y.txt", "../outside.txt")) {
            assertFailsWith<SecurityException>(path) { engine.resolveCachePath(path) }
        }
    }

    @Test
    fun `a dot-dot that stays inside resolves to the normalised file`() {
        assertEquals(cacheDir.resolve("b.txt"), engine.resolveCachePath("/docs/../b.txt"))
        assertEquals(cacheDir.resolve("docs").resolve("b.txt"), engine.resolveCachePath("/docs/./b.txt"))
    }

    @Test
    fun `an absolute-looking path stays inside or is refused`() {
        // Leading slashes are the logical root, never the file system's.
        assertEquals(cacheDir.resolve("etc").resolve("passwd"), engine.resolveCachePath("//etc/passwd"))
        val driveForm = "/C:/Windows/win.ini"
        val driveRelative = "/q:x.txt"
        val uncForm = "/$bs${bs}server${bs}share${bs}x.txt"
        val backslashClimb = "/a$bs..$bs..$bs..${bs}x.txt"
        for (path in listOf(driveForm, driveRelative, uncForm, backslashClimb)) {
            if (windows) {
                // On Windows these name a drive, a share or a parent folder.
                assertFailsWith<SecurityException>(path) { engine.resolveCachePath(path) }
            } else {
                // On POSIX they are ordinary (odd) names.
                assertTrue(engine.resolveCachePath(path).startsWith(cacheDir), path)
            }
        }
    }

    @Test
    fun `NFC and NFD names resolve inside and keep their spelling`() {
        val nfc = "f" + Char(0x00F6) + ".txt"
        val nfd = "fo" + Char(0x0308) + ".txt"
        assertTrue(nfc != nfd)

        val nfcCache = engine.resolveCachePath("/docs/$nfc")
        val nfdCache = engine.resolveCachePath("/docs/$nfd")

        assertEquals(cacheDir.resolve("docs").resolve(nfc), nfcCache)
        assertEquals(nfc, nfcCache.fileName.toString())
        assertEquals(cacheDir.resolve("docs").resolve(nfd), nfdCache)
        assertEquals(nfd, nfdCache.fileName.toString())
    }

    @Test
    fun `a reap of a row whose path does not resolve inside the cache neither fails nor touches files outside it`() =
        runTest {
            provider.putRemote("/stay.txt", "S")
            engine.enumerateRemoteIntoState(reset = false)
            // A row that names a path above the cache folder (a cloud item called ".." would make one).
            val odd = "/../outside.txt"
            db.upsertEntry(
                SyncEntry(
                    path = odd,
                    remoteId = "id-odd",
                    remoteHash = "h",
                    remoteSize = 5,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            // The file the unchecked resolution named for that row: beside the cache folder, not in it.
            val beside = cacheDir.resolveSibling("outside.txt")
            Files.createDirectories(beside.parent)
            Files.writeString(beside, "bytes")
            Files.setLastModifiedTime(beside, FileTime.fromMillis(0))

            val result = engine.enumerateRemoteIntoState(reset = true)

            assertTrue(result.ok, "the enumeration must not fail on the row: ${result.error}")
            assertTrue(Files.exists(beside), "a file outside the cache folder is never evicted")
            assertNull(db.getEntry(odd), "the row is still reaped (its remote item is gone)")
            assertNotNull(db.getEntry("/stay.txt"))
        }
}
