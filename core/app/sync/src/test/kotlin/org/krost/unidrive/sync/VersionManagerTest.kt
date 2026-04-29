package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.io.path.*
import kotlin.test.*

class VersionManagerTest {
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("version-manager-test")
    }

    @AfterTest
    fun tearDown() {
        if (::syncRoot.isInitialized && syncRoot.exists()) {
            syncRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot creates version copy`() {
        val file = syncRoot.resolve("docs/test.txt")
        file.parent.createDirectories()
        file.writeText("hello")

        val manager = VersionManager(syncRoot)
        val dest = manager.snapshot("docs/test.txt")

        assertNotNull(dest)
        assertTrue(dest.exists(), "Version file should exist")
        assertTrue(file.exists(), "Original file should still exist")
        assertEquals("hello", dest.readText())
        assertTrue(dest.toString().contains(".unidrive-versions"))
    }

    @Test
    fun `snapshot preserves original file`() {
        val file = syncRoot.resolve("report.txt")
        file.writeText("original content")

        val manager = VersionManager(syncRoot)
        manager.snapshot("report.txt")

        assertTrue(file.exists())
        assertEquals("original content", file.readText())
    }

    @Test
    fun `listVersions returns versions sorted newest first`() {
        val file = syncRoot.resolve("multi.txt")
        file.writeText("v1")

        val manager = VersionManager(syncRoot)
        manager.snapshot("multi.txt")

        Thread.sleep(1100)

        file.writeText("v2")
        manager.snapshot("multi.txt")

        val versions = manager.listVersions("multi.txt")
        assertEquals(2, versions.size)
        assertTrue(versions[0].timestamp.isAfter(versions[1].timestamp))
    }

    @Test
    fun `listVersions returns empty for unversioned file`() {
        val manager = VersionManager(syncRoot)
        assertTrue(manager.listVersions("nonexistent.txt").isEmpty())
    }

    @Test
    fun `restore overwrites current file`() {
        val file = syncRoot.resolve("restore-me.txt")
        file.writeText("original")

        val manager = VersionManager(syncRoot)
        manager.snapshot("restore-me.txt")

        val versions = manager.listVersions("restore-me.txt")
        val ts = versions[0].timestamp

        file.writeText("modified")
        assertEquals("modified", file.readText())

        val restored = manager.restore("restore-me.txt", ts)
        assertTrue(restored)
        assertEquals("original", file.readText())
    }

    @Test
    fun `restore with specific timestamp`() {
        val file = syncRoot.resolve("specific.txt")
        file.writeText("first")

        val manager = VersionManager(syncRoot)
        manager.snapshot("specific.txt")
        val v1Ts = manager.listVersions("specific.txt")[0].timestamp

        Thread.sleep(1100)

        file.writeText("second")
        manager.snapshot("specific.txt")

        file.writeText("current")

        val restored = manager.restore("specific.txt", v1Ts)
        assertTrue(restored)
        assertEquals("first", file.readText())
    }

    @Test
    fun `pruneByCount keeps only N newest`() {
        val file = syncRoot.resolve("prune-count.txt")

        val manager = VersionManager(syncRoot)
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val versionsDir = syncRoot.resolve(".unidrive-versions/prune-count.txt")
        versionsDir.createDirectories()

        for (i in 1..5) {
            val ts = fmt.format(Instant.now().minus((5 - i).toLong(), ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS))
            val vFile = versionsDir.resolve(ts)
            vFile.writeText("version $i")
        }

        assertEquals(5, manager.listVersions("prune-count.txt").size)
        manager.pruneByCount("prune-count.txt", 3)
        val remaining = manager.listVersions("prune-count.txt")
        assertEquals(3, remaining.size)
    }

    @Test
    fun `pruneByAge removes old versions`() {
        val manager = VersionManager(syncRoot)
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val versionsDir = syncRoot.resolve(".unidrive-versions/old-file.txt")
        versionsDir.createDirectories()

        val oldTs = fmt.format(Instant.now().minus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS))
        versionsDir.resolve(oldTs).writeText("old")

        assertEquals(1, manager.listVersions("old-file.txt").size)
        manager.pruneByAge(30)
        assertEquals(0, manager.listVersions("old-file.txt").size)
    }

    @Test
    fun `pruneByAge keeps recent versions`() {
        val file = syncRoot.resolve("recent.txt")
        file.writeText("fresh")

        val manager = VersionManager(syncRoot)
        manager.snapshot("recent.txt")

        assertEquals(1, manager.listVersions("recent.txt").size)
        manager.pruneByAge(30)
        assertEquals(1, manager.listVersions("recent.txt").size)
    }

    @Test
    fun `pruneAll removes everything`() {
        val manager = VersionManager(syncRoot)

        syncRoot.resolve("a.txt").writeText("a")
        syncRoot.resolve("b.txt").writeText("b")
        manager.snapshot("a.txt")
        manager.snapshot("b.txt")

        assertTrue(manager.listAll().isNotEmpty())
        manager.pruneAll()
        assertTrue(manager.listAll().isEmpty())
        assertFalse(syncRoot.resolve(".unidrive-versions").exists())
    }

    @Test
    fun `snapshot handles non-existent file gracefully`() {
        val manager = VersionManager(syncRoot)
        val result = manager.snapshot("missing.txt")
        assertNull(result)
    }
}
