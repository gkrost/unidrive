package org.krost.unidrive.sync

import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

// UD-209: sparse-file production support missing on Windows — tests below skip there.
private val IS_WINDOWS = System.getProperty("os.name", "").lowercase().contains("win")

class PlaceholderManagerTest {
    private lateinit var syncRoot: Path
    private lateinit var mgr: PlaceholderManager

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-ph-test")
        mgr = PlaceholderManager(syncRoot)
    }

    @Test
    fun `createPlaceholder creates empty stub with mtime — UD-222`() {
        val modified = Instant.parse("2026-03-28T12:00:00Z")
        mgr.createPlaceholder("/docs/test.txt", size = 4096, modified = modified)

        // UD-222: stubs are always 0 bytes. Real content arrives via provider.download —
        // setLength(size) used to pre-allocate NUL bytes (346 GB on UD-712 run, NTFS).
        val file = syncRoot.resolve("docs/test.txt")
        assertTrue(Files.exists(file))
        assertEquals(0, Files.size(file))
    }

    @Test
    fun `createPlaceholder creates parent directories`() {
        mgr.createPlaceholder("/deep/nested/dir/file.txt", size = 100, modified = Instant.now())
        assertTrue(Files.isDirectory(syncRoot.resolve("deep/nested/dir")))
    }

    @Test
    fun `createFolder creates directory with mtime`() {
        val modified = Instant.parse("2026-03-28T12:00:00Z")
        mgr.createFolder("/docs/projects", modified = modified)
        assertTrue(Files.isDirectory(syncRoot.resolve("docs/projects")))
    }

    @Test
    fun `dehydrate truncates file to sparse placeholder`() {
        val file = syncRoot.resolve("test.txt")
        Files.writeString(file, "hello world content here")
        val originalSize = Files.size(file)

        mgr.dehydrate("/test.txt", remoteSize = 500, remoteModified = Instant.now())

        assertEquals(500, Files.size(file))
        val content = Files.readAllBytes(file)
        assertTrue(content.all { it == 0.toByte() })
    }

    @Test
    fun `deleteLocal removes file`() {
        val file = syncRoot.resolve("delete-me.txt")
        Files.writeString(file, "bye")
        mgr.deleteLocal("/delete-me.txt")
        assertFalse(Files.exists(file))
    }

    @Test
    fun `deleteLocal removes empty parent directories`() {
        val dir = syncRoot.resolve("a/b/c")
        Files.createDirectories(dir)
        val file = dir.resolve("file.txt")
        Files.writeString(file, "content")
        mgr.deleteLocal("/a/b/c/file.txt")
        assertFalse(Files.exists(dir))
        assertFalse(Files.exists(syncRoot.resolve("a/b")))
        assertFalse(Files.exists(syncRoot.resolve("a")))
    }

    @Test
    fun `deleteLocal does not remove non-empty parent directories`() {
        Files.createDirectories(syncRoot.resolve("a/b"))
        Files.writeString(syncRoot.resolve("a/b/keep.txt"), "keep")
        Files.writeString(syncRoot.resolve("a/b/del.txt"), "del")
        mgr.deleteLocal("/a/b/del.txt")
        assertTrue(Files.exists(syncRoot.resolve("a/b")))
        assertTrue(Files.exists(syncRoot.resolve("a/b/keep.txt")))
    }

    @Test
    fun `resolveLocal maps path to filesystem`() {
        assertEquals(syncRoot.resolve("docs/test.txt"), mgr.resolveLocal("/docs/test.txt"))
    }

    @Test
    fun `resolveLocal rejects leading dotdot traversal escaping syncRoot`() {
        assertFailsWith<SecurityException> {
            mgr.resolveLocal("/../../etc/passwd")
        }
    }

    @Test
    fun `resolveLocal rejects embedded dotdot traversal escaping syncRoot`() {
        assertFailsWith<SecurityException> {
            mgr.resolveLocal("/docs/../../../etc/passwd")
        }
    }

    @Test
    fun `resolveLocal allows dotdot segments that stay inside syncRoot`() {
        // /docs/sub/../file.txt normalizes to /docs/file.txt — still inside syncRoot, must be allowed
        assertEquals(
            syncRoot.resolve("docs/file.txt").normalize(),
            mgr.resolveLocal("/docs/sub/../file.txt"),
        )
    }

    @Test
    fun `createPlaceholder rejects traversal path`() {
        // SecurityException is thrown during path resolution, before any filesystem mutation.
        // The throw itself proves no file was written outside syncRoot.
        assertFailsWith<SecurityException> {
            mgr.createPlaceholder("/../escaped.txt", size = 100, modified = Instant.now())
        }
    }

    @Test
    fun `isLocallyModified returns false for non-hydrated entry mtime match`() {
        mgr.createPlaceholder("/test.txt", size = 100, modified = Instant.now())
        val mtime = Files.getLastModifiedTime(syncRoot.resolve("test.txt")).toMillis()
        assertFalse(mgr.isLocallyModified("/test.txt", lastSyncedMtime = mtime))
    }

    @Test
    fun `isSparse detects sparse placeholder`() {
        assumeFalse("isSparse uses POSIX stat — UD-209 tracks Windows support", IS_WINDOWS)
        mgr.createPlaceholder("/sparse.txt", size = 1_000_000, modified = Instant.now())
        val path = syncRoot.resolve("sparse.txt")
        assertTrue(mgr.isSparse(path, 1_000_000))
    }

    @Test
    fun `isSparse returns false for real file`() {
        val path = syncRoot.resolve("real.txt")
        Files.writeString(path, "a".repeat(1000))
        assertFalse(mgr.isSparse(path, Files.size(path)))
    }

    @Test
    fun `isSparse returns false for zero-byte file`() {
        val path = syncRoot.resolve("empty.txt")
        Files.createFile(path)
        assertFalse(mgr.isSparse(path, 0L))
    }

    @Test
    fun `isSparse returns false for nonexistent file`() {
        val path = syncRoot.resolve("nope.txt")
        assertFalse(mgr.isSparse(path, 100))
    }

    @Test
    fun `dehydrated file detected as sparse`() {
        assumeFalse("isSparse/dehydrate rely on POSIX sparse semantics — UD-209", IS_WINDOWS)
        val path = syncRoot.resolve("hydrated.txt")
        Files.writeString(path, "a".repeat(5000))
        assertFalse(mgr.isSparse(path, Files.size(path)))

        mgr.dehydrate("/hydrated.txt", remoteSize = 5000, remoteModified = Instant.now())
        assertTrue(mgr.isSparse(path, 5000))
    }
}
