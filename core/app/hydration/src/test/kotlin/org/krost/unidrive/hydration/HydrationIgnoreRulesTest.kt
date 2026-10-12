package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.ignore.CacheIgnoreSource
import org.krost.unidrive.sync.ignore.IgnoreLayer
import org.krost.unidrive.sync.ignore.IgnoreSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #660: the mount's uploads follow the ignore rules (docs/dev/specs/ignore-layering-design.md). An ignored path is
 * keep-local: written, kept and listed, never uploaded. Downloads and the listing of cloud items are not touched.
 * The rule files are read from the hydration cache.
 */
class HydrationIgnoreRulesTest {
    private fun CoroutineScope.env(
        ignore: IgnoreSettings? = IgnoreSettings(),
        excludePatterns: List<String> = emptyList(),
        cacheMaxBytes: Long = HydrationImpl.DEFAULT_CACHE_MAX_BYTES,
    ) = HydrationTestEnv(recoveryUploadScope = this, ignore = ignore, excludePatterns = excludePatterns, cacheMaxBytes = cacheMaxBytes)

    /** Writes [content] to [path] through the mount (create, the client's bytes, the hand-over); the hand-over's reply. */
    private suspend fun HydrationTestEnv.write(
        path: String,
        content: String,
    ): OpenResult.Ok {
        val created = hydration.create("conn1", "h-$path", path)
        assertIs<CreateResult.Ok>(created, "create $path")
        Files.writeString(created.cachePath, content)
        val opened = hydration.openForWrite("conn1", "h-$path", path, created.cachePath)
        assertIs<OpenResult.Ok>(opened, "open_write $path")
        return opened
    }

    private suspend fun HydrationTestEnv.listed(prefix: String): Map<String, ListResult.Entry> {
        val r = hydration.list(prefix)
        assertIs<ListResult.Ok>(r)
        return r.entries.associateBy { it.path }
    }

    @Test
    fun `an ignored file is not uploaded and the cloud copy below the rule stays listed and downloadable`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertFolderEntry("/proj/build")
            env.stateDb.insertFolderEntry("/proj/src")
            env.syncEngine.seedCacheContent("/proj/.unidrive.ignore", "build/\n")
            env.stateDb.insertUnhydratedEntry("/proj/build/old.bin", 1)
            env.syncEngine.seedRemoteContent("/proj/build/old.bin", "c")

            assertTrue(env.write("/proj/build/out.bin", "local build output").excluded)
            assertFalse(env.write("/proj/src/Main.kt", "fun main() {}").excluded)
            advanceUntilIdle()

            assertNull(env.syncEngine.remoteContentSeen("/proj/build/out.bin"), "an ignored file is never uploaded")
            assertEquals("fun main() {}", env.syncEngine.remoteContentSeen("/proj/src/Main.kt"), "its neighbour is")
            val build = env.listed("/proj/build")
            assertEquals("id-/proj/build/old.bin", build.getValue("/proj/build/old.bin").remoteId, "the cloud item stays listed")
            assertTrue(build.getValue("/proj/build/old.bin").excluded, "and is marked")
            assertTrue(build.getValue("/proj/build/out.bin").excluded)
            assertTrue(env.listed("/proj").getValue("/proj/build").excluded, "the folder itself matches `build/`")
            assertEquals(HydrateResult.Ok, env.hydration.hydrate("/proj/build/old.bin"), "downloads are not held back")
            assertEquals("c", Files.readString(env.syncEngine.resolveCachePath("/proj/build/old.bin")))
        }

    @Test
    fun `git is ignored by default and a root rule re-includes it`() =
        runTest {
            val plain = env()
            plain.stateDb.insertFolderEntry("/repo")
            plain.stateDb.insertFolderEntry("/repo/.git")
            assertTrue(plain.write("/repo/.git/config", "[core]").excluded)
            advanceUntilIdle()
            assertNull(plain.syncEngine.remoteContentSeen("/repo/.git/config"))

            val opted = env()
            opted.syncEngine.seedCacheContent("/.unidrive.ignore", "!.git\n")
            opted.stateDb.insertFolderEntry("/repo")
            opted.stateDb.insertFolderEntry("/repo/.git")
            assertFalse(opted.write("/repo/.git/config", "[core]").excluded)
            advanceUntilIdle()
            assertEquals("[core]", opted.syncEngine.remoteContentSeen("/repo/.git/config"))
        }

    @Test
    fun `a rule file written through the mount applies to the next write`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/w")
            assertFalse(env.write("/w/first.log", "uploaded").excluded)
            env.write("/w/.unidrive.ignore", "*.log\n")
            assertTrue(env.write("/w/second.log", "kept here").excluded)
            advanceUntilIdle()
            assertEquals("*.log\n", env.syncEngine.remoteContentSeen("/w/.unidrive.ignore"), "the rule file itself uploads")
            assertNull(env.syncEngine.remoteContentSeen("/w/second.log"))
        }

    @Test
    fun `gitignore files apply only with the opt-in`() =
        runTest {
            for (honor in listOf(false, true)) {
                val env = env(IgnoreSettings(honorGitignore = honor))
                env.stateDb.insertFolderEntry("/p")
                env.syncEngine.seedCacheContent("/p/.gitignore", "*.log\n")
                assertEquals(honor, env.write("/p/app.log", "log line").excluded, "honor_gitignore=$honor")
                advanceUntilIdle()
                assertEquals(if (honor) null else "log line", env.syncEngine.remoteContentSeen("/p/app.log"))
            }
        }

    @Test
    fun `an ignored local-only file is not a leftover at the next start`() =
        runTest {
            // A restart (remount, redeploy) replays the pending uploads and sweeps the cache. An ignored file written
            // through the mount must come out of both as what it is: a local-only item, kept in place, listed, never
            // uploaded and not counted as owed to the cloud.
            val env = env(cacheMaxBytes = 1)
            env.stateDb.insertFolderEntry("/proj")
            env.syncEngine.seedCacheContent("/proj/.unidrive.ignore", "node_modules/\n")
            env.stateDb.insertFolderEntry("/proj/node_modules")
            env.write("/proj/node_modules/dep.js", "module.exports = 1")
            advanceUntilIdle()

            assertEquals(0, env.hydration.replayPendingUploads(), "the start-up replay does not take it")
            env.hydration.sweepCache()
            advanceUntilIdle()

            val cache = env.syncEngine.resolveCachePath("/proj/node_modules/dep.js")
            assertEquals("module.exports = 1", Files.readString(cache), "the cache sweep keeps its only copy")
            assertNull(env.syncEngine.remoteContentSeen("/proj/node_modules/dep.js"))
            val row = env.listed("/proj/node_modules").getValue("/proj/node_modules/dep.js")
            assertTrue(row.excluded, "listed as ignored, not as an unknown local file")
            assertEquals(0, env.hydration.uploadHealth().pending, "not owed to the cloud")
        }

    @Test
    fun `a rule file the cache does not hold yet holds its folder back until it is downloaded`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertUnhydratedEntry("/proj/.unidrive.ignore", 1)
            env.syncEngine.seedRemoteContent("/proj/.unidrive.ignore", "*")

            assertTrue(env.write("/proj/notes.txt", "draft").excluded, "held back: the rules there are not known")
            advanceUntilIdle()
            assertNull(env.syncEngine.remoteContentSeen("/proj/notes.txt"))
            val ignore = assertNotNull(env.engine.uploadIgnore)
            val held = ignore.heldBack().single()
            assertEquals("/proj", held.dir)
            assertEquals("/proj/.unidrive.ignore", held.file)
            assertEquals(CacheIgnoreSource.NOT_DOWNLOADED, held.reason)
            assertEquals(IgnoreLayer.UNREADABLE, ignore.explain("/proj/notes.txt").layer)

            // Opening the rule file downloads it (one byte, "*": everything there is ignored), which releases the
            // folder; the held-back file is now decided by the rule.
            assertEquals(HydrateResult.Ok, env.hydration.hydrate("/proj/.unidrive.ignore"))
            assertTrue(ignore.heldBack().isEmpty())
            assertEquals(IgnoreLayer.UNIDRIVE_IGNORE, ignore.explain("/proj/notes.txt").layer)
        }

    @Test
    fun `a held-back file uploads at the next start once its folder is released`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertUnhydratedEntry("/proj/.unidrive.ignore", 1)
            env.syncEngine.seedRemoteContent("/proj/.unidrive.ignore", "#")
            assertTrue(env.write("/proj/notes.txt", "draft").excluded)
            advanceUntilIdle()

            assertEquals(HydrateResult.Ok, env.hydration.hydrate("/proj/.unidrive.ignore"))
            assertEquals(1, env.hydration.replayPendingUploads())
            advanceUntilIdle()
            assertEquals("draft", env.syncEngine.remoteContentSeen("/proj/notes.txt"), "holding back only delayed it")
        }

    @Test
    fun `an edit of a synced file made while it is ignored is kept and uploads once it is no longer ignored`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertFolderEntry("/proj/build")
            env.write("/proj/.unidrive.ignore", "build/\n")
            advanceUntilIdle()
            env.syncEngine.seedCacheContent("/proj/build/report.txt", "v1")
            val cache = env.syncEngine.resolveCachePath("/proj/build/report.txt")
            env.stateDb.insertUploadedRow("/proj/build/report.txt", Files.getLastModifiedTime(cache).toMillis(), 2)
            val baseline = env.stateDb.localMtimeOf("/proj/build/report.txt")

            Files.writeString(cache, "v2, edited")
            Files.setLastModifiedTime(cache, java.nio.file.attribute.FileTime.fromMillis(baseline!! + 60_000))
            val opened = env.hydration.openForWrite("conn1", "h1", "/proj/build/report.txt", cache)
            assertIs<OpenResult.Ok>(opened)
            assertTrue(opened.excluded)
            advanceUntilIdle()
            assertNull(env.syncEngine.remoteContentSeen("/proj/build/report.txt"))
            assertEquals(baseline, env.stateDb.localMtimeOf("/proj/build/report.txt"), "the cloud's version stays the baseline")
            assertEquals(CacheDisposition.PROTECTED, env.engine.mount.cacheDisposition("/proj/build/report.txt"), "never evicted")

            val rules = env.syncEngine.resolveCachePath("/proj/.unidrive.ignore")
            Files.writeString(rules, "# nothing ignored here any more\n")
            assertIs<OpenResult.Ok>(env.hydration.openForWrite("conn1", "h2", "/proj/.unidrive.ignore", rules))
            advanceUntilIdle()
            assertEquals(1, env.hydration.replayPendingUploads(), "the next start sends the edit")
            advanceUntilIdle()
            assertEquals("v2, edited", env.syncEngine.remoteContentSeen("/proj/build/report.txt"))
        }

    @Test
    fun `exclude_patterns stay plain patterns beside the ignore rules and no rule file overrides them`() =
        runTest {
            val env = env(excludePatterns = listOf("/Videos/**", "*.tmp"))
            env.syncEngine.seedCacheContent("/.unidrive.ignore", "!Videos\n!Videos/**\n!*.tmp\n")
            env.stateDb.insertFolderEntry("/Videos")
            env.stateDb.insertFolderEntry("/docs")

            assertTrue(env.write("/Videos/clip.mp4", "frames").excluded)
            assertTrue(env.write("/docs/scratch.tmp", "scratch").excluded)
            advanceUntilIdle()
            assertNull(env.syncEngine.remoteContentSeen("/Videos/clip.mp4"))
            assertNull(env.syncEngine.remoteContentSeen("/docs/scratch.tmp"))
            assertEquals("/Videos/**", env.engine.mount.matchingExcludePattern("/Videos/clip.mp4"))
            assertFalse(assertNotNull(env.engine.uploadIgnore).isIgnored("/Videos/clip.mp4", false), "the rules re-include it")
        }

    @Test
    fun `with the ignore rules off the built-in defaults stay plain excludes`() =
        runTest {
            val env = env(ignore = null)
            env.syncEngine.seedCacheContent("/.unidrive.ignore", "!.git\n")
            env.stateDb.insertFolderEntry("/repo")
            env.stateDb.insertFolderEntry("/repo/.git")
            assertNull(env.engine.uploadIgnore)
            assertTrue(env.write("/repo/.git/config", "[core]").excluded, "no rule file is read")
        }
}
