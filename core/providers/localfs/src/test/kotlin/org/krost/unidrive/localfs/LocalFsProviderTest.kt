package org.krost.unidrive.localfs

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LocalFsProviderTest {
    private lateinit var rootDir: Path
    private lateinit var tokenDir: Path
    private lateinit var provider: LocalFsProvider

    @BeforeTest
    fun setUp() {
        rootDir = Files.createTempDirectory("localfs-provider-test")
        tokenDir = Files.createTempDirectory("localfs-token-test")
        provider = LocalFsProvider(LocalFsConfig(rootPath = rootDir, tokenPath = tokenDir))
    }

    @AfterTest
    fun tearDown() {
        provider.close()
        // Recursive cleanup
        if (Files.exists(rootDir)) {
            Files.walk(rootDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        if (Files.exists(tokenDir)) {
            Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // -- Upload + Download roundtrip ----------------------------------------------

    @Test
    fun `upload and download roundtrip preserves content`() =
        runTest {
            provider.authenticate()

            val sourceFile = Files.createTempFile("upload-source", ".txt")
            try {
                Files.writeString(sourceFile, "hello world")
                provider.upload(sourceFile, "test.txt")

                val dest = Files.createTempFile("download-dest", ".txt")
                try {
                    val size = provider.download("test.txt", dest)
                    assertEquals("hello world", Files.readString(dest))
                    assertEquals(11L, size)
                } finally {
                    Files.deleteIfExists(dest)
                }
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    // -- createFolder + listChildren -----------------------------------------------

    @Test
    fun `createFolder and listChildren returns created folder`() =
        runTest {
            provider.authenticate()

            val item = provider.createFolder("subdir")
            assertTrue(item.isFolder)
            assertEquals("subdir", item.name)

            val children = provider.listChildren("")
            assertEquals(1, children.size)
            assertEquals("subdir", children[0].name)
            assertTrue(children[0].isFolder)
        }

    // -- delete + verify gone ------------------------------------------------------

    @Test
    fun `delete removes file`() =
        runTest {
            provider.authenticate()

            val sourceFile = Files.createTempFile("del-source", ".txt")
            try {
                Files.writeString(sourceFile, "to be deleted")
                provider.upload(sourceFile, "deleteme.txt")
                assertTrue(Files.exists(rootDir.resolve("deleteme.txt")))

                provider.delete("deleteme.txt")
                assertFalse(Files.exists(rootDir.resolve("deleteme.txt")))
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    // -- move + verify old gone, new exists ----------------------------------------

    @Test
    fun `move relocates file from old path to new path`() =
        runTest {
            provider.authenticate()

            val sourceFile = Files.createTempFile("move-source", ".txt")
            try {
                Files.writeString(sourceFile, "moving content")
                provider.upload(sourceFile, "original.txt")
                assertTrue(Files.exists(rootDir.resolve("original.txt")))

                provider.move("original.txt", "moved.txt")
                assertFalse(Files.exists(rootDir.resolve("original.txt")))
                assertTrue(Files.exists(rootDir.resolve("moved.txt")))
                assertEquals("moving content", Files.readString(rootDir.resolve("moved.txt")))
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    // -- delta: initial snapshot ---------------------------------------------------

    @Test
    fun `delta with null cursor returns all files as initial snapshot`() =
        runTest {
            provider.authenticate()

            val sourceFile = Files.createTempFile("delta-source", ".txt")
            try {
                Files.writeString(sourceFile, "delta content")
                provider.upload(sourceFile, "file1.txt")
                provider.createFolder("dir1")

                val page = provider.delta(null)
                assertFalse(page.hasMore)
                assertTrue(page.cursor.isNotBlank())
                assertTrue(page.items.any { it.name == "file1.txt" })
                assertTrue(page.items.any { it.name == "dir1" })
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    // -- delta: add file, second delta sees change ----------------------------------

    @Test
    fun `delta detects new file added after initial snapshot`() =
        runTest {
            provider.authenticate()

            val sourceFile = Files.createTempFile("delta-add", ".txt")
            try {
                Files.writeString(sourceFile, "first")
                provider.upload(sourceFile, "existing.txt")

                val firstPage = provider.delta(null)
                val cursor = firstPage.cursor

                // Add a new file
                Files.writeString(sourceFile, "second")
                provider.upload(sourceFile, "newfile.txt")

                val secondPage = provider.delta(cursor)
                assertTrue(secondPage.items.any { it.name == "newfile.txt" && !it.deleted })
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    // -- delta: delete file, delta sees deletion ------------------------------------

    @Test
    fun `delta detects file deletion after snapshot`() =
        runTest {
            provider.authenticate()

            val sourceFile = Files.createTempFile("delta-del", ".txt")
            try {
                Files.writeString(sourceFile, "will be deleted")
                provider.upload(sourceFile, "gone.txt")

                val firstPage = provider.delta(null)
                val cursor = firstPage.cursor

                // Delete the file
                provider.delete("gone.txt")

                val secondPage = provider.delta(cursor)
                assertTrue(secondPage.items.any { it.name == "gone.txt" && it.deleted })
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    // -- quota returns non-zero values ---------------------------------------------

    @Test
    fun `quota returns non-zero total and remaining`() =
        runTest {
            provider.authenticate()

            val quota = provider.quota()
            assertTrue(quota.total > 0, "Total space should be > 0")
            assertTrue(quota.remaining > 0, "Remaining space should be > 0")
        }

    // -- path traversal rejected ---------------------------------------------------

    @Test
    fun `path traversal with dot-dot is rejected`() =
        runTest {
            provider.authenticate()

            val sourceFile = Files.createTempFile("traversal-source", ".txt")
            try {
                Files.writeString(sourceFile, "malicious")
                assertFailsWith<IllegalArgumentException> {
                    provider.upload(sourceFile, "../escape.txt")
                }
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    @Test
    fun `path traversal in download is rejected`() =
        runTest {
            provider.authenticate()

            val dest = Files.createTempFile("traversal-dest", ".txt")
            try {
                assertFailsWith<IllegalArgumentException> {
                    provider.download("../../etc/passwd", dest)
                }
            } finally {
                Files.deleteIfExists(dest)
            }
        }

    // -- UD-304: sync-engine canonical paths (leading slash) ---------------------

    @Test
    fun `safePath treats leading slash as root-relative, not absolute`() {
        // SyncEngine feeds paths like "/docs/notes.md". Java's Path.resolve on an
        // absolute path replaces the base — so without stripping the leading '/'
        // this would resolve to /docs (POSIX) or C:\docs (Windows) and fail the
        // containment check, aborting every sync against a localfs provider.
        val resolved = provider.safePath("/docs/notes.md")
        val expected = rootDir.resolve("docs/notes.md").normalize()
        assertEquals(expected, resolved)
    }

    @Test
    fun `safePath handles mixed slashes and repeated separators`() {
        // A defensive belt-and-braces check: '///' or '\\' leading runs still
        // land inside root, not at drive root.
        val resolved = provider.safePath("///docs///notes.md")
        val expected = rootDir.resolve("docs/notes.md").normalize()
        assertEquals(expected, resolved)
    }

    @Test
    fun `download with sync-engine canonical leading-slash path succeeds`() =
        runTest {
            provider.authenticate()
            // Seed a file inside the sync root using the usual FS APIs…
            Files.createDirectories(rootDir.resolve("docs"))
            val target = rootDir.resolve("docs/hello.txt")
            Files.writeString(target, "hi")

            // …then fetch via the canonical sync-engine path form.
            val dest = Files.createTempFile("ud304-dl", ".txt")
            try {
                val bytes = provider.download("/docs/hello.txt", dest)
                assertEquals(2L, bytes)
                assertEquals("hi", Files.readString(dest))
            } finally {
                Files.deleteIfExists(dest)
            }
        }

    // -- authenticate creates rootPath if missing ----------------------------------

    @Test
    fun `authenticate creates root directory if missing`() =
        runTest {
            val missingRoot = rootDir.resolve("nonexistent-subdir")
            assertFalse(Files.exists(missingRoot))

            val p = LocalFsProvider(LocalFsConfig(rootPath = missingRoot, tokenPath = tokenDir))
            p.authenticate()
            assertTrue(Files.exists(missingRoot))
            assertTrue(p.isAuthenticated)
        }

    // -- share returns file URI ---------------------------------------------------

    @Test
    fun `share returns Success with file URI for existing file`() =
        runTest {
            provider.authenticate()

            val sourceFile = Files.createTempFile("share-source", ".txt")
            try {
                Files.writeString(sourceFile, "shareable")
                provider.upload(sourceFile, "shared.txt")

                val result = provider.share("shared.txt")
                assertIs<CapabilityResult.Success<String>>(result)
                assertTrue(result.value.startsWith("file://"))
                assertTrue(result.value.contains("shared.txt"))
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    @Test
    fun `share returns Unsupported for nonexistent file`() =
        runTest {
            provider.authenticate()
            val result = provider.share("nonexistent.txt")
            assertIs<CapabilityResult.Unsupported>(result)
            assertTrue(result.reason.contains("No such file"))
        }

    // -- getMetadata --------------------------------------------------------------

    @Test
    fun `getMetadata returns correct attributes`() =
        runTest {
            provider.authenticate()

            val sourceFile = Files.createTempFile("meta-source", ".txt")
            try {
                Files.writeString(sourceFile, "metadata content")
                provider.upload(sourceFile, "meta.txt")

                val item = provider.getMetadata("meta.txt")
                assertEquals("meta.txt", item.name)
                assertFalse(item.isFolder)
                assertEquals(16L, item.size)
                assertNotNull(item.modified)
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    // -- upload to nested path creates parent dirs ---------------------------------

    @Test
    fun `upload to nested path creates parent directories`() =
        runTest {
            provider.authenticate()

            val sourceFile = Files.createTempFile("nested-source", ".txt")
            try {
                Files.writeString(sourceFile, "nested content")
                provider.upload(sourceFile, "a/b/c/nested.txt")

                assertTrue(Files.exists(rootDir.resolve("a/b/c/nested.txt")))
                assertEquals("nested content", Files.readString(rootDir.resolve("a/b/c/nested.txt")))
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }
}
