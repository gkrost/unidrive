package org.krost.unidrive.sftp

import kotlinx.coroutines.test.runTest
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Integration-style tests for [SftpProvider] and [SftpApiService] using an
 * embedded Apache MINA SSHD server running on localhost.  No external SSH
 * server required.
 *
 * The VirtualFileSystemFactory maps `/` in the SFTP session to a host temp
 * directory.  A `/data` subdirectory is created as the sync root, and the
 * provider is configured with `remotePath = "/data"` so that [SftpApiService]
 * produces absolute server paths (`/data/foo.txt`).
 *
 * Covers: authenticate, logout, upload, download, listChildren, getMetadata,
 * createFolder, delete, move, delta (initial + incremental).
 */
class SftpProviderEmbeddedTest {
    companion object {
        private const val TEST_USER = "testuser"
        private const val TEST_PASS = "testpass"
    }

    private lateinit var sshd: SshServer
    private lateinit var vfsRoot: Path // host dir mapped to VFS "/"
    private lateinit var dataDir: Path // vfsRoot/data — the sync root
    private lateinit var tempDir: Path // scratch space for local files
    private var port: Int = 0

    @BeforeTest
    fun setUp() {
        vfsRoot = Files.createTempDirectory("sftp-test-vfs")
        dataDir = vfsRoot.resolve("data")
        Files.createDirectories(dataDir)
        tempDir = Files.createTempDirectory("sftp-test-tmp")

        sshd =
            SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"
                this.port = 0 // random port
                keyPairProvider = SimpleGeneratorHostKeyProvider(tempDir.resolve("host.ser"))
                setPasswordAuthenticator { username, password, _ ->
                    username == TEST_USER && password == TEST_PASS
                }
                subsystemFactories = listOf(SftpSubsystemFactory())
                fileSystemFactory =
                    org.apache.sshd.common.file.virtualfs
                        .VirtualFileSystemFactory(vfsRoot)
            }
        sshd.start()
        port = sshd.port
    }

    @AfterTest
    fun tearDown() {
        sshd.stop(true)
        Files.walk(vfsRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun createProvider(remotePath: String = "/data"): SftpProvider {
        val config =
            SftpConfig(
                host = "127.0.0.1",
                port = port,
                username = TEST_USER,
                password = TEST_PASS,
                identityFile = null,
                remotePath = remotePath,
                knownHostsFile = null, // accept any host key for tests
                maxConcurrency = 2,
            )
        return SftpProvider(config)
    }

    // ── authenticate / logout ───────────────────────────────────────────────

    @Test
    fun `authenticate sets isAuthenticated to true`() =
        runTest {
            val provider = createProvider()
            try {
                assertFalse(provider.isAuthenticated)
                provider.authenticate()
                assertTrue(provider.isAuthenticated)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `logout sets isAuthenticated to false`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                assertTrue(provider.isAuthenticated)
                provider.logout()
                assertFalse(provider.isAuthenticated)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `authenticate with wrong password throws`() =
        runTest {
            val config =
                SftpConfig(
                    host = "127.0.0.1",
                    port = port,
                    username = TEST_USER,
                    password = "wrong",
                    identityFile = null,
                    knownHostsFile = null,
                )
            val provider = SftpProvider(config)
            try {
                assertFailsWith<org.krost.unidrive.AuthenticationException> {
                    provider.authenticate()
                }
                assertFalse(provider.isAuthenticated)
            } finally {
                provider.close()
            }
        }

    // ── createFolder ────────────────────────────────────────────────────────

    @Test
    fun `createFolder returns folder CloudItem with correct fields`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                val item = provider.createFolder("/testdir")
                assertTrue(item.isFolder)
                assertEquals("testdir", item.name)
                assertEquals("/testdir", item.path)
                assertEquals(0L, item.size)
                assertNotNull(item.modified)
                assertNotNull(item.created)
                assertNull(item.hash)
                assertNull(item.mimeType)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `createFolder with nested path creates parent directories on server`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                // createFolder("/a/b/c") calls ensureParentDirs which creates /data/a and /data/a/b
                val item = provider.createFolder("/a/b/c")
                assertTrue(item.isFolder)
                assertEquals("c", item.name)
                assertTrue(Files.isDirectory(dataDir.resolve("a")), "Parent dir /a should exist")
                assertTrue(Files.isDirectory(dataDir.resolve("a/b")), "Parent dir /a/b should exist")
            } finally {
                provider.close()
            }
        }

    // ── upload / download ───────────────────────────────────────────────────

    @Test
    fun `upload and download round-trip preserves content`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()

                val localFile = tempDir.resolve("upload.txt")
                val content = "Hello SFTP world!\nLine 2\n"
                Files.writeString(localFile, content)

                val item = provider.upload(localFile, "/upload.txt")
                assertEquals("upload.txt", item.name)
                assertEquals("/upload.txt", item.path)
                assertFalse(item.isFolder)
                assertEquals(content.length.toLong(), item.size)
                assertNotNull(item.modified)
                assertEquals("application/octet-stream", item.mimeType)

                val downloadDest = tempDir.resolve("downloaded.txt")
                val bytes = provider.download("/upload.txt", downloadDest)
                assertEquals(content.length.toLong(), bytes)
                assertEquals(content, Files.readString(downloadDest))
            } finally {
                provider.close()
            }
        }

    @Test
    fun `upload to nested path creates parent directories`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()

                val localFile = tempDir.resolve("nested.txt")
                Files.writeString(localFile, "nested content")

                val item = provider.upload(localFile, "/deep/nested/file.txt")
                assertEquals("file.txt", item.name)
                assertEquals("/deep/nested/file.txt", item.path)
                assertTrue(Files.exists(dataDir.resolve("deep/nested/file.txt")))
            } finally {
                provider.close()
            }
        }

    @Test
    fun `upload with progress callback fires completion`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()

                val localFile = tempDir.resolve("progress.bin")
                val data = ByteArray(1024) { it.toByte() }
                Files.write(localFile, data)

                var progressCalled = false
                provider.upload(localFile, "/progress.bin") { sent, total ->
                    progressCalled = true
                    assertEquals(data.size.toLong(), total)
                    assertEquals(data.size.toLong(), sent)
                }
                assertTrue(progressCalled, "Progress callback should have been invoked")
            } finally {
                provider.close()
            }
        }

    // ── listChildren ────────────────────────────────────────────────────────

    @Test
    fun `listChildren returns direct children only`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()

                Files.createDirectories(dataDir.resolve("docs/sub"))
                Files.writeString(dataDir.resolve("docs/readme.txt"), "readme")
                Files.writeString(dataDir.resolve("docs/notes.txt"), "notes")
                Files.writeString(dataDir.resolve("docs/sub/deep.txt"), "deep")

                val children = provider.listChildren("/docs")
                val names = children.map { it.name }.sorted()
                assertEquals(listOf("notes.txt", "readme.txt", "sub"), names)

                val sub = children.first { it.name == "sub" }
                assertTrue(sub.isFolder)
                val readme = children.first { it.name == "readme.txt" }
                assertFalse(readme.isFolder)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `listChildren of empty directory returns empty list`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.createDirectories(dataDir.resolve("empty"))
                val children = provider.listChildren("/empty")
                assertTrue(children.isEmpty())
            } finally {
                provider.close()
            }
        }

    @Test
    fun `listChildren of root returns top-level items`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.writeString(dataDir.resolve("top.txt"), "top")
                Files.createDirectories(dataDir.resolve("folder"))

                val children = provider.listChildren("/")
                val names = children.map { it.name }
                assertTrue("folder" in names, "Expected 'folder' in $names")
                assertTrue("top.txt" in names, "Expected 'top.txt' in $names")
            } finally {
                provider.close()
            }
        }

    // ── getMetadata ─────────────────────────────────────────────────────────

    @Test
    fun `getMetadata returns correct item for file`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                val content = "metadata test"
                Files.writeString(dataDir.resolve("meta.txt"), content)

                val item = provider.getMetadata("/meta.txt")
                assertEquals("meta.txt", item.name)
                assertEquals("/meta.txt", item.path)
                assertFalse(item.isFolder)
                assertEquals(content.length.toLong(), item.size)
                assertNotNull(item.modified)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `getMetadata returns correct item for directory`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.createDirectories(dataDir.resolve("metadir"))

                val item = provider.getMetadata("/metadir")
                assertEquals("metadir", item.name)
                assertTrue(item.isFolder)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `getMetadata throws SftpException for nonexistent path`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                assertFailsWith<SftpException> {
                    provider.getMetadata("/does-not-exist.txt")
                }
            } finally {
                provider.close()
            }
        }

    // ── delete ──────────────────────────────────────────────────────────────

    @Test
    fun `delete removes a file`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.writeString(dataDir.resolve("todelete.txt"), "bye")
                assertTrue(Files.exists(dataDir.resolve("todelete.txt")))

                provider.delete("/todelete.txt")
                assertFalse(Files.exists(dataDir.resolve("todelete.txt")))
            } finally {
                provider.close()
            }
        }

    @Test
    fun `delete removes an empty directory`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.createDirectories(dataDir.resolve("emptydir"))

                provider.delete("/emptydir")
                assertFalse(Files.exists(dataDir.resolve("emptydir")))
            } finally {
                provider.close()
            }
        }

    @Test
    fun `delete of nonexistent path does not throw`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                provider.delete("/ghost.txt")
            } finally {
                provider.close()
            }
        }

    // ── move ────────────────────────────────────────────────────────────────

    @Test
    fun `move renames a file`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.writeString(dataDir.resolve("original.txt"), "data")

                val item = provider.move("/original.txt", "/renamed.txt")
                assertEquals("renamed.txt", item.name)
                assertEquals("/renamed.txt", item.path)
                assertFalse(item.isFolder)
                assertFalse(Files.exists(dataDir.resolve("original.txt")))
                assertTrue(Files.exists(dataDir.resolve("renamed.txt")))
                assertEquals("data", Files.readString(dataDir.resolve("renamed.txt")))
            } finally {
                provider.close()
            }
        }

    @Test
    fun `move to nested path creates parent directories`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.writeString(dataDir.resolve("moveme.txt"), "move data")

                val item = provider.move("/moveme.txt", "/new/dir/moved.txt")
                assertEquals("moved.txt", item.name)
                assertTrue(Files.exists(dataDir.resolve("new/dir/moved.txt")))
                assertFalse(Files.exists(dataDir.resolve("moveme.txt")))
            } finally {
                provider.close()
            }
        }

    // ── delta ───────────────────────────────────────────────────────────────

    @Test
    fun `delta with null cursor returns all items`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.writeString(dataDir.resolve("a.txt"), "aaa")
                Files.createDirectories(dataDir.resolve("sub"))
                Files.writeString(dataDir.resolve("sub/b.txt"), "bbb")

                val page = provider.delta(null)
                assertFalse(page.hasMore)
                assertTrue(page.cursor.isNotEmpty())

                val paths = page.items.map { it.path }.sorted()
                assertTrue("/a.txt" in paths, "Expected /a.txt in $paths")
                assertTrue("/sub" in paths, "Expected /sub in $paths")
                assertTrue("/sub/b.txt" in paths, "Expected /sub/b.txt in $paths")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `delta detects new files since previous cursor`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.writeString(dataDir.resolve("existing.txt"), "old")

                val page1 = provider.delta(null)
                val cursor = page1.cursor

                Files.writeString(dataDir.resolve("newfile.txt"), "new content")

                val page2 = provider.delta(cursor)
                val newPaths = page2.items.map { it.path }
                assertTrue("/newfile.txt" in newPaths, "Delta should detect the new file, got $newPaths")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `delta detects deleted files since previous cursor`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.writeString(dataDir.resolve("willdelete.txt"), "temp")

                val page1 = provider.delta(null)
                val cursor = page1.cursor

                Files.delete(dataDir.resolve("willdelete.txt"))

                val page2 = provider.delta(cursor)
                val deletedItems = page2.items.filter { it.deleted }
                val deletedPaths = deletedItems.map { it.path }
                assertTrue("/willdelete.txt" in deletedPaths, "Delta should detect the deletion, got $deletedPaths")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `delta detects modified files since previous cursor`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                val file = dataDir.resolve("mutable.txt")
                Files.writeString(file, "v1")

                val page1 = provider.delta(null)
                val cursor = page1.cursor

                // Change size to ensure detection (mtime may have same second resolution)
                Files.writeString(file, "version two with more content")

                val page2 = provider.delta(cursor)
                val changedPaths = page2.items.map { it.path }
                assertTrue("/mutable.txt" in changedPaths, "Delta should detect the modified file, got $changedPaths")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `delta with no changes returns empty items`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.writeString(dataDir.resolve("stable.txt"), "stable")

                val page1 = provider.delta(null)
                val cursor = page1.cursor

                val page2 = provider.delta(cursor)
                assertTrue(page2.items.isEmpty(), "Delta with no changes should return empty items")
            } finally {
                provider.close()
            }
        }

    // ── remotePath prefix ───────────────────────────────────────────────────

    @Test
    fun `provider with remotePath scopes operations to subdirectory`() =
        runTest {
            Files.createDirectories(dataDir.resolve("syncroot"))
            Files.writeString(dataDir.resolve("syncroot/scoped.txt"), "scoped")
            Files.writeString(dataDir.resolve("outside.txt"), "outside")

            val provider = createProvider(remotePath = "/data/syncroot")
            try {
                provider.authenticate()
                val children = provider.listChildren("/")
                val names = children.map { it.name }
                assertTrue("scoped.txt" in names, "Should see files in syncroot, got $names")
                assertFalse("outside.txt" in names, "Should NOT see files outside syncroot")
            } finally {
                provider.close()
            }
        }

    // ── toCloudItem mapping ─────────────────────────────────────────────────

    @Test
    fun `uploaded file CloudItem has correct mimeType`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                val localFile = tempDir.resolve("binary.dat")
                Files.write(localFile, byteArrayOf(0, 1, 2, 3))

                val item = provider.upload(localFile, "/binary.dat")
                assertEquals("application/octet-stream", item.mimeType)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `folder CloudItem has null mimeType`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.createDirectories(dataDir.resolve("typedir"))

                val children = provider.listChildren("/")
                assertTrue(children.isNotEmpty(), "Expected at least one child")
                val folder = children.firstOrNull { it.name == "typedir" }
                assertNotNull(folder, "Expected 'typedir' in children: ${children.map { it.name }}")
                assertTrue(folder.isFolder)
                assertNull(folder.mimeType)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `delta items all have non-empty paths starting with slash`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                Files.writeString(dataDir.resolve("check.txt"), "content")

                val page = provider.delta(null)
                for (item in page.items) {
                    assertTrue(item.path.isNotEmpty(), "CloudItem path should never be empty")
                    assertTrue(item.path.startsWith("/"), "CloudItem path should start with /")
                }
            } finally {
                provider.close()
            }
        }

    // ── quota ───────────────────────────────────────────────────────────────

    @Test
    fun `quota returns zeros via authenticated provider`() =
        runTest {
            val provider = createProvider()
            try {
                provider.authenticate()
                val q = provider.quota()
                assertEquals(0L, q.total)
                assertEquals(0L, q.used)
                assertEquals(0L, q.remaining)
            } finally {
                provider.close()
            }
        }
}
