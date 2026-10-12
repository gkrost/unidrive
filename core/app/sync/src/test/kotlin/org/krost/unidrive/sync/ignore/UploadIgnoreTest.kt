package org.krost.unidrive.sync.ignore

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #660: [UploadIgnore], the ignore rules as the upload path asks them, and the cache cap of [IgnoreTree] it relies on
 * in a long-running daemon.
 */
class UploadIgnoreTest {
    private val logger = LoggerFactory.getLogger(UploadIgnore::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var now = 1_000_000L

    @BeforeTest
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(appender)
    }

    private fun ignore(
        src: IgnoreFileSource,
        settings: IgnoreSettings = IgnoreSettings(),
    ) = UploadIgnore(src, settings, revalidateAfterMs = 5_000, warnEveryMs = 60_000, clock = { now })

    private fun warnings() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    @Test
    fun answersForLogicalPaths() {
        val u = ignore(MemoryIgnoreSource(mapOf("proj/.unidrive.ignore" to "build/\n")))
        assertTrue(u.isIgnored("/proj/build/out.o", false))
        assertTrue(u.isIgnored("/proj/build", true))
        assertFalse(u.isIgnored("/proj/build", false), "`build/` names folders only")
        assertFalse(u.isIgnored("/proj/src/a.kt", false))
        assertTrue(u.isIgnored("/proj/.git/config", false), "the content defaults")
        val v = u.explain("/proj/build/out.o")
        assertEquals("proj/.unidrive.ignore", v.rule?.source)
        assertEquals("proj/build", v.decidedAt)
        assertFalse(u.explain("/").ignored)
        assertTrue(u.isRuleFile("/proj/.unidrive.ignore"))
        assertFalse(u.isRuleFile("/proj/.gitignore"), "only with the opt-in")
        assertTrue(ignore(MemoryIgnoreSource(), IgnoreSettings(honorGitignore = true)).isRuleFile("/proj/.gitignore"))
    }

    @Test
    fun aChangedRuleFileTakesEffectAfterTheRevalidationIntervalWithoutARestart() {
        val src = MemoryIgnoreSource(mapOf(".unidrive.ignore" to "*.log\n"))
        val u = ignore(src)
        assertTrue(u.isIgnored("/a.log", false))
        src.remove(".unidrive.ignore")
        now += 1_000
        assertTrue(u.isIgnored("/a.log", false), "within the interval the rules read stay")
        now += 5_000
        assertFalse(u.isIgnored("/a.log", false), "removing the file makes its paths eligible again")
        src.put(".unidrive.ignore", "*.out\n")
        u.ruleFileWritten("/.unidrive.ignore")
        assertTrue(u.isIgnored("/b.out", false), "a rule file written through the engine applies at once")
    }

    @Test
    fun anUnreadableRuleFileHoldsBackItsFolderWarnsRateLimitedAndIsListed() {
        val src = MemoryIgnoreSource()
        src.fake("work/.unidrive.ignore", RuleFileProbe.Unreadable("access denied"))
        val u = ignore(src)

        assertTrue(u.isIgnored("/work/a.txt", false))
        assertTrue(u.isIgnored("/work/deep/b.txt", false))
        assertFalse(u.isIgnored("/other/c.txt", false))
        assertEquals(IgnoreLayer.UNREADABLE, u.explain("/work/a.txt").layer)
        assertEquals(listOf(HeldBackDir("/work", "/work/.unidrive.ignore", "access denied")), u.heldBack())
        assertEquals(1, warnings().size, "one WARN")
        assertTrue(warnings().single().contains("/work/.unidrive.ignore") && warnings().single().contains("access denied"))

        // Asked again in later scans: no new WARN within the interval, one after it.
        repeat(3) {
            now += 6_000
            u.isIgnored("/work/a.txt", false)
            u.invalidateForTest("work")
        }
        assertEquals(1, warnings().size, "rate-limited")
        now += 60_000
        u.invalidateForTest("work")
        u.isIgnored("/work/a.txt", false)
        assertEquals(2, warnings().size)

        // Fixed: the folder is released at the next revalidation.
        src.put("work/.unidrive.ignore", "*.bak\n")
        now += 5_000
        assertEquals(emptyList(), u.heldBack())
        assertFalse(u.isIgnored("/work/a.txt", false))
    }

    @Test
    fun anOversizedRuleFileHoldsBackToo() {
        val u = ignore(MemoryIgnoreSource(mapOf("big/.unidrive.ignore" to "x".repeat(64))), IgnoreSettings(maxRuleFileBytes = 16))
        assertTrue(u.isIgnored("/big/file.txt", false))
        assertTrue(u.heldBack().single().reason.contains("larger than 16 bytes"))
    }

    @Test
    fun aRuleFileOfTheCloudThatTheCacheDoesNotHoldIsNotDownloadedButHoldsBack() {
        val cache = Files.createTempDirectory("unidrive-ignore-cache")
        Files.createDirectories(cache.resolve("local"))
        Files.writeString(cache.resolve("local/.unidrive.ignore"), "*.o\n")
        val inCloud = setOf("/remote/.unidrive.ignore", "/local/.unidrive.ignore")
        val asked = mutableListOf<String>()
        val src =
            CacheIgnoreSource(cache) { p ->
                asked += p
                p in inCloud
            }
        val u = ignore(src)

        assertTrue(u.isIgnored("/local/x.o", false), "a cached rule file is read")
        assertFalse(u.isIgnored("/local/x.c", false))
        assertTrue(u.isIgnored("/remote/x.c", false), "held back: not downloaded")
        assertEquals(CacheIgnoreSource.NOT_DOWNLOADED, u.heldBack().single().reason)
        assertFalse(u.isIgnored("/elsewhere/x.c", false), "no rule file anywhere: nothing held back")
        assertFalse("/local/.unidrive.ignore" in asked, "the cache copy answers before the cloud is asked")
    }

    @Test
    fun theCacheOfRuleFilesIsCappedLeastRecentlyUsedFirst() {
        val src = MemoryIgnoreSource((1..5).associate { "d$it/.unidrive.ignore" to "*.x\n" })
        val tree = IgnoreTree(src, IgnoreSettings(maxCachedDirs = 4))
        // The root and d1..d3: four directories, the cap.
        for (d in 1..3) assertTrue(tree.explainEntry("d$d/a.x").ignored)
        assertEquals(4, tree.cachedDirs)
        assertTrue(tree.explainEntry("d1/b.x").ignored, "d1 is used again")
        assertTrue(tree.explainEntry("d4/a.x").ignored, "d4 is the fifth: the least recently used one goes")
        assertTrue(tree.cachedDirs <= 4)
        val readsBefore = src.reads.toMap()
        assertTrue(tree.explainEntry("d1/c.x").ignored)
        assertEquals(readsBefore["d1/.unidrive.ignore"], src.reads["d1/.unidrive.ignore"], "d1 stayed cached")
        assertTrue(tree.explainEntry("d2/c.x").ignored)
        assertEquals(readsBefore.getValue("d2/.unidrive.ignore") + 1, src.reads["d2/.unidrive.ignore"], "d2 was forgotten and read again")
    }

    @Test
    fun aHeldBackFolderIsNeverTrimmedFromTheCache() {
        val src = MemoryIgnoreSource()
        src.fake("held/.unidrive.ignore", RuleFileProbe.Unreadable("I/O error"))
        val tree = IgnoreTree(src, IgnoreSettings(maxCachedDirs = 2))
        assertTrue(tree.explainEntry("held/a").ignored)
        for (d in 1..20) tree.explainEntry("d$d/a")
        assertEquals(listOf("held"), tree.heldBack().map { it.dir }, "the count in daemon.status does not drift down")
    }

    private fun UploadIgnore.invalidateForTest(dir: String) = ruleFileWritten("/$dir/.unidrive.ignore")
}
