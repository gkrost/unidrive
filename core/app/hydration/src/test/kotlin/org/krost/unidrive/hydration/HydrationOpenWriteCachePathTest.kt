package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.krost.unidrive.hydration.ContainmentEnv.Companion.tokenOf
import org.krost.unidrive.hydration.ContainmentEnv.Companion.windows
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `open_write` uploads the cache path a client hands back only when it lies inside the profile's hydration cache
 * folder: on normalised paths and, as far as it exists, on real paths, so a link inside the folder cannot lead out
 * of it. Another spelling of a file inside is accepted. Anything else is `invalid_path`, before any state is
 * touched or upload queued.
 */
class HydrationOpenWriteCachePathTest {
    private val envs = mutableListOf<ContainmentEnv>()

    private fun env(): ContainmentEnv = ContainmentEnv().also { envs += it }

    @AfterTest
    fun tearDown() {
        envs.forEach { it.close() }
    }

    @Test
    fun `open_write refuses a cache path outside the profile's cache folder and queues nothing`() =
        runBlocking<Unit> {
            val env = env()
            env.db.upsertEntry(env.fileRow("/doc.txt"))
            val before = env.db.getEntry("/doc.txt")
            // An upload, if one were queued, would wait here in its slot.
            env.provider.uploadGate = CompletableDeferred()
            val elsewhere = env.write(env.base.resolve("elsewhere.txt"), "not a cache file")
            val prefixSibling = env.write(env.cacheDir.resolveSibling("profile-other").resolve("doc.txt"), "x")
            val outside =
                listOf(
                    "a file elsewhere" to elsewhere,
                    "a folder whose name starts like the cache folder" to prefixSibling,
                    "a spelling that climbs out of the folder" to env.cacheDir.resolve("..").resolve("profile-other").resolve("doc.txt"),
                    "the cache folder itself" to env.cacheDir,
                )

            for ((what, cachePath) in outside) {
                val r = env.hydration.openForWrite("c1", "h1", "/doc.txt", cachePath)

                assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(r), "$what: $r")
                assertFalse(env.hydration.hasUploadSlot("/doc.txt"), "$what: no upload is queued")
                assertEquals(before, env.db.getEntry("/doc.txt"), "$what: the row is untouched")
            }
            assertFalse(env.provider.remote.containsKey("/doc.txt"), "nothing reached the provider")
        }

    @Test
    fun `open_write accepts the cache path the engine hands out and other spellings inside the folder`() =
        runBlocking<Unit> {
            val env = env()
            env.db.upsertEntry(env.fileRow("/doc.txt", remoteId = null))
            val cache = env.write(env.engine.resolveCachePath("/doc.txt"), "edited")
            val staged = env.write(env.cacheDir.resolve("staging").resolve("doc.txt"), "staged")
            val spellings =
                mutableListOf(
                    "the engine's cache path" to cache,
                    "a redundant dot segment" to env.cacheDir.resolve(".").resolve("doc.txt"),
                    "a dot-dot that stays inside" to env.cacheDir.resolve("staging").resolve("..").resolve("doc.txt"),
                    "another file inside the folder" to staged,
                    "a path that does not exist yet" to env.cacheDir.resolve("new").resolve("doc.txt"),
                )
            if (windows) {
                // Windows file names are case-insensitive: another letter case names the same file.
                spellings += "another letter case" to Paths.get(cache.toString().uppercase())
            }

            for ((i, spelling) in spellings.withIndex()) {
                val (what, cachePath) = spelling
                val r = env.hydration.openForWrite("c1", "h$i", "/doc.txt", cachePath)

                assertTrue(r is OpenResult.Ok, "$what must be accepted: $r")
                // One upload at a time: the next spelling must not race this one's row update.
                withTimeout(10_000) { while (env.hydration.hasUploadSlot("/doc.txt")) delay(10) }
            }
            assertNotNull(env.provider.remote["/doc.txt"], "the accepted writes were uploaded")
        }

    @Test
    fun `open_write refuses a link inside the cache folder that leads out of it`() =
        runBlocking<Unit> {
            val env = env()
            env.db.upsertEntry(env.fileRow("/doc.txt"))
            val target = env.write(env.base.resolve("elsewhere.txt"), "not a cache file")
            val outDir = Files.createDirectories(env.base.resolve("outdir"))
            env.write(outDir.resolve("doc.txt"), "not a cache file either")
            val fileLink = env.engine.resolveCachePath("/doc.txt")
            val dirLink = env.cacheDir.resolve("linked")
            Files.createDirectories(env.cacheDir)
            try {
                Files.createSymbolicLink(fileLink, target)
                Files.createSymbolicLink(dirLink, outDir)
            } catch (e: Exception) {
                Assume.assumeTrue("symbolic links are not available here: ${e.message}", false)
            }
            env.provider.uploadGate = CompletableDeferred()

            for ((what, cachePath) in listOf(
                "a file link" to fileLink,
                "a file below a folder link" to dirLink.resolve("doc.txt"),
                "a new file below a folder link" to dirLink.resolve("new.txt"),
            )) {
                val r = env.hydration.openForWrite("c1", "h1", "/doc.txt", cachePath)

                assertEquals(HydrationError.INVALID_PATH_TOKEN, tokenOf(r), "$what: $r")
                assertFalse(env.hydration.hasUploadSlot("/doc.txt"), "$what: no upload is queued")
            }
        }

    @Test
    fun `the IPC handler answers a cache path that is not a valid local path with invalid_path`() =
        runBlocking<Unit> {
            val env = env()
            env.db.upsertEntry(env.fileRow("/doc.txt"))
            // A JSON escape built at runtime: backslash, u, four hex digits (here NUL).
            val nul = Char(92) + "u0000"

            val reply =
                HydrationIpcHandler(env.hydration).handle(
                    "c1",
                    """{"verb":"hydration.open_write","handle_id":"h1","path":"/doc.txt","cache_path":"cache/a${nul}b"}""",
                )
            val outside =
                HydrationIpcHandler(env.hydration).handle(
                    "c1",
                    """{"verb":"hydration.open_write","handle_id":"h2","path":"/doc.txt","cache_path":"elsewhere.txt"}""",
                )

            assertEquals("""{"ok":false,"error":"invalid_path"}""", reply.trim())
            assertEquals("""{"ok":false,"error":"invalid_path"}""", outside.trim())
            assertFalse(env.hydration.hasUploadSlot("/doc.txt"), "no upload is queued")
        }
}
