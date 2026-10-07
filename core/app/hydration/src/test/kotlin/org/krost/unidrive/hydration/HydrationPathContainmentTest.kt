package org.krost.unidrive.hydration

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.krost.unidrive.hydration.ContainmentEnv.Companion.tokenOf
import org.krost.unidrive.hydration.ContainmentEnv.Companion.windows
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hydration verbs keep every file they touch inside the profile's hydration cache folder
 * (`<cacheRoot>/unidrive/hydration/<cacheKey>`): a logical path whose cache file would lie outside the folder is
 * `invalid_path` for every verb that would read, write or delete that file, and the cache budget passes leave such
 * a row alone. `mkdir`, `create` and `rename` also refuse a new name the host's file system cannot hold.
 */
class HydrationPathContainmentTest {
    private val envs = mutableListOf<ContainmentEnv>()

    private fun env(): ContainmentEnv = ContainmentEnv().also { envs += it }

    @AfterTest
    fun tearDown() {
        envs.forEach { it.close() }
    }

    @Test
    fun `open_write_begin refuses a logical cache path linked to an outside file without truncating it`() =
        runBlocking<Unit> {
            val env = env()
            env.db.upsertEntry(env.fileRow("/doc.txt"))
            val target = env.write(env.base.resolve("outside.txt"), "keep these bytes")
            Files.createDirectories(env.cacheDir)
            try {
                Files.createSymbolicLink(env.cacheDir.resolve("doc.txt"), target)
            } catch (e: Exception) {
                Assume.assumeTrue("symbolic links are not available here: ${e.message}", false)
            }

            val result = env.hydration.openWriteBegin("c1", "/doc.txt", "h1")

            assertEquals("keep these bytes", Files.readString(target))
            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(result))
        }

    // ---- logical paths whose cache file would lie outside the folder -------------------------------------------

    @Test
    fun `create and open_write_begin refuse a path whose cache file would lie outside the cache folder`() =
        runBlocking<Unit> {
            val env = env()
            // A cloud folder named ".." (whether a provider allows one is not something to rely on).
            env.db.upsertEntry(env.folderRow("/.."))
            env.write(env.beside, "precious")

            val created = env.hydration.create("c1", "h1", "/../outside.txt")
            env.db.upsertEntry(env.fileRow("/../outside.txt"))
            val begun = env.hydration.openWriteBegin("c1", "/../outside.txt", "h2")

            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(created), "create: $created")
            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(begun), "open_write_begin: $begun")
            assertEquals("precious", Files.readString(env.beside), "the file beside the cache folder is untouched")
        }

    @Test
    fun `open_read hydrate dehydrate and open_write refuse a row whose cache file would lie outside the cache folder`() =
        runBlocking<Unit> {
            val env = env()
            env.write(env.beside, "precious")
            env.write(env.cacheDir.resolveSibling("local-only.txt"), "not a cache file")
            env.db.upsertEntry(env.fileRow("/../local-only.txt", remoteId = null))
            env.db.upsertEntry(env.fileRow("/../outside.txt"))
            val inside = env.write(env.engine.resolveCachePath("/inside.txt"), "bytes")

            val read = env.hydration.openForRead("c1", "h1", "/../local-only.txt")
            val hydrated = env.hydration.hydrate("/../local-only.txt")
            val dehydrated = env.hydration.dehydrate("/../outside.txt")
            val written = env.hydration.openForWrite("c1", "h2", "/../outside.txt", inside)

            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(read), "open_read: $read")
            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(hydrated), "hydrate: $hydrated")
            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(dehydrated), "dehydrate: $dehydrated")
            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(written), "open_write: $written")
            assertFalse(env.hydration.hasUploadSlot("/../outside.txt"), "no upload is queued")
            assertEquals("precious", Files.readString(env.beside), "the file beside the cache folder is untouched")
            assertTrue(env.db.getEntry("/../outside.txt")?.isHydrated == true, "the row is untouched")
        }

    @Test
    fun `rename refuses a destination whose cache file would lie outside the cache folder`() =
        runBlocking<Unit> {
            val env = env()
            env.db.upsertEntry(env.folderRow("/.."))
            env.provider.seed("/a.txt", "mine".toByteArray())
            env.db.upsertEntry(env.fileRow("/a.txt"))
            val cache = env.write(env.engine.resolveCachePath("/a.txt"), "mine")

            val r = env.hydration.rename("/a.txt", "/../moved.txt")

            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(r), "rename: $r")
            assertTrue(env.provider.items.containsKey("/a.txt"), "nothing was moved in the cloud")
            assertEquals("mine", Files.readString(cache), "the cache file stays where it is")
            assertNotNull(env.db.getEntry("/a.txt"), "the row stays where it is")
            assertNull(env.db.getEntry("/../moved.txt"))
            assertFalse(Files.exists(env.cacheDir.resolveSibling("moved.txt")))
        }

    @Test
    fun `the cache budget passes leave a row alone whose path does not resolve inside the cache`() =
        runBlocking<Unit> {
            val env = env()
            env.write(env.beside, "bytes")
            val mtime = Files.getLastModifiedTime(env.beside).toMillis()
            // A synced row whose recorded baseline matches the file beside the folder: disposable if it were inside.
            env.db.upsertEntry(env.fileRow("/../outside.txt", localMtime = mtime, localSize = 5, cacheBacked = true))
            val mount = MountEngine.over(env.engine)

            assertEquals(CacheDisposition.PROTECTED, mount.cacheDisposition("/../outside.txt"))
            assertNull(mount.evictCacheCopy("/../outside.txt"))
            assertTrue(Files.exists(env.beside), "a file outside the cache folder is never evicted")
        }

    @Test
    fun `a name the platform cannot hold is still reported by the verb, not thrown`() =
        runBlocking<Unit> {
            val env = env()
            env.db.upsertEntry(env.folderRow("/docs"))
            // '<' cannot be part of a Windows path; on POSIX it is an ordinary character.
            env.db.upsertEntry(env.fileRow("/docs/a<b.txt"))

            val read = env.hydration.openForRead("c1", "h1", "/docs/a<b.txt")

            assertTrue(read is OpenResult.Failed, "open_read: $read")
        }

    // ---- names the host file system cannot hold ------------------------------------------------------------------

    @Test
    fun `mkdir create and rename refuse a name with a NUL character`() =
        runBlocking<Unit> {
            val env = env()
            env.db.upsertEntry(env.fileRow("/src.txt", remoteId = null))
            env.write(env.engine.resolveCachePath("/src.txt"), "mine")
            val bad = "/a" + Char(0) + "b"

            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(env.hydration.mkdir(bad)), "mkdir")
            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(env.hydration.create("c1", "h1", bad)), "create")
            assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(env.hydration.rename("/src.txt", bad)), "rename")
            assertNotNull(env.db.getEntry("/src.txt"))
            assertNull(env.db.getEntry(bad))
        }

    @Test
    fun `mkdir create and rename apply the Windows name rules on Windows only`() =
        runBlocking<Unit> {
            val env = env()
            for (folder in listOf("/m", "/c", "/r")) env.db.upsertEntry(env.folderRow(folder))

            for ((i, name) in listOf("trailing.", "trailing ", "a:b", "CON", "x?.txt").withIndex()) {
                env.db.upsertEntry(env.fileRow("/src$i.txt", remoteId = null))
                env.write(env.engine.resolveCachePath("/src$i.txt"), "mine")
                val results =
                    listOf(
                        "mkdir" to env.hydration.mkdir("/m/$name"),
                        "create" to env.hydration.create("c1", "h$i", "/c/$name"),
                        "rename" to env.hydration.rename("/src$i.txt", "/r/$name"),
                    )
                for ((verb, r) in results) {
                    if (windows) {
                        assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(r), "$verb '$name': $r")
                    } else {
                        assertNotEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(r), "$verb '$name': $r")
                    }
                }
            }
        }
}
