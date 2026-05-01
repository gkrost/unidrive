package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Unit tests for [S3Provider] using a fake [S3ApiService] stub.
 * No network calls — all S3 API interactions are simulated in-memory.
 */
class S3ProviderTest {
    private val config =
        S3Config(
            bucket = "test-bucket",
            region = "us-east-1",
            endpoint = "https://s3.example.com",
            accessKey = "AKID",
            secretKey = "SECRET",
        )

    /** In-memory fake that records calls and stores objects as byte arrays. */
    private class FakeS3Api(
        config: S3Config,
    ) : S3ApiService(config) {
        val objects = mutableMapOf<String, ByteArray>()
        val metadata = mutableMapOf<String, S3Object>()
        var listAllResult: MutableList<S3Object> = mutableListOf()
        var deleteThrows: Exception? = null
        var copyThrows: Exception? = null
        var presignResult: String = "https://presigned.example.com/obj"
        var authThrows: Exception? = null

        override suspend fun listAll(
            prefix: String,
            onProgress: ((itemsSoFar: Int) -> Unit)?,
        ): List<S3Object> {
            if (authThrows != null && prefix.contains("auth-check")) throw authThrows!!
            return if (prefix.isEmpty()) {
                listAllResult.toList()
            } else {
                listAllResult.filter { it.key.startsWith(prefix) }
            }
        }

        override suspend fun getObject(
            key: String,
            destination: Path,
        ): Long {
            val data = objects[key] ?: throw S3Exception("Not found: $key", 404)
            Files.createDirectories(destination.parent)
            Files.write(destination, data)
            return data.size.toLong()
        }

        override suspend fun putObject(
            key: String,
            localPath: Path,
            onProgress: ((Long, Long) -> Unit)?,
        ): String? {
            val data = Files.readAllBytes(localPath)
            objects[key] = data
            val etag = "etag-${key.hashCode()}"
            val size = data.size.toLong()
            // Update listing unless it's a folder marker (zero-byte)
            metadata[key] =
                S3Object(
                    key = key,
                    etag = etag,
                    size = size,
                    lastModified = "2025-01-15T10:00:00Z",
                    isFolder = key.endsWith("/"),
                )
            listAllResult.removeAll { it.key == key }
            listAllResult.add(metadata[key]!!)
            onProgress?.invoke(size, size)
            return etag
        }

        override suspend fun deleteObject(key: String) {
            deleteThrows?.let { throw it }
            objects.remove(key)
            metadata.remove(key)
            listAllResult.removeAll { it.key == key }
        }

        override suspend fun copyObject(
            fromKey: String,
            toKey: String,
        ) {
            copyThrows?.let { throw it }
            val data = objects[fromKey] ?: throw S3Exception("Source not found: $fromKey", 404)
            objects[toKey] = data.copyOf()
            val source = metadata[fromKey]
            metadata[toKey] =
                S3Object(
                    key = toKey,
                    etag = source?.etag,
                    size = data.size.toLong(),
                    lastModified = source?.lastModified,
                    isFolder = toKey.endsWith("/"),
                )
            listAllResult.removeAll { it.key == toKey }
            listAllResult.add(metadata[toKey]!!)
        }

        override fun presign(
            key: String,
            expirySeconds: Int,
        ): String = presignResult

        override fun close() { /* no-op */ }
    }

    private lateinit var fakeApi: FakeS3Api
    private lateinit var provider: S3Provider

    @BeforeTest
    fun setUp() {
        fakeApi = FakeS3Api(config)
        provider = S3Provider(config, fakeApi)
    }

    @AfterTest
    fun tearDown() {
        provider.close()
    }

    // ── Identity ────────────────────────────────────────────────────────────

    @Test
    fun `id and displayName are correct`() {
        assertEquals("s3", provider.id)
        assertEquals("S3 / S3-compatible", provider.displayName)
    }

    // ── canAuthenticate ─────────────────────────────────────────────────────

    @Test
    fun `canAuthenticate is true when credentials present`() {
        assertTrue(provider.canAuthenticate)
    }

    @Test
    fun `canAuthenticate is false when bucket is blank`() {
        val p = S3Provider(config.copy(bucket = ""), fakeApi)
        assertFalse(p.canAuthenticate)
    }

    @Test
    fun `canAuthenticate is false when accessKey is blank`() {
        val p = S3Provider(config.copy(accessKey = ""), fakeApi)
        assertFalse(p.canAuthenticate)
    }

    @Test
    fun `canAuthenticate is false when secretKey is blank`() {
        val p = S3Provider(config.copy(secretKey = ""), fakeApi)
        assertFalse(p.canAuthenticate)
    }

    // ── authenticate ────────────────────────────────────────────────────────

    @Test
    fun `authenticate sets isAuthenticated on success`() =
        runTest {
            assertFalse(provider.isAuthenticated)
            provider.authenticate()
            assertTrue(provider.isAuthenticated)
        }

    @Test
    fun `authenticate throws and stays unauthenticated on failure`() =
        runTest {
            fakeApi.authThrows = AuthenticationException("bad creds")
            assertFailsWith<AuthenticationException> { provider.authenticate() }
            assertFalse(provider.isAuthenticated)
        }

    // ── logout ──────────────────────────────────────────────────────────────

    @Test
    fun `logout clears isAuthenticated`() =
        runTest {
            provider.authenticate()
            assertTrue(provider.isAuthenticated)
            provider.logout()
            assertFalse(provider.isAuthenticated)
        }

    // ── download ────────────────────────────────────────────────────────────

    @Test
    fun `download writes content to destination`() =
        runTest {
            fakeApi.objects["docs/file.txt"] = "hello world".toByteArray()

            val dest = Files.createTempFile("s3-download-test", ".txt")
            try {
                val size = provider.download("/docs/file.txt", dest)
                assertEquals(11L, size)
                assertEquals("hello world", Files.readString(dest))
            } finally {
                Files.deleteIfExists(dest)
            }
        }

    // ── upload ──────────────────────────────────────────────────────────────

    @Test
    fun `upload stores file and returns CloudItem`() =
        runTest {
            val sourceFile = Files.createTempFile("s3-upload-test", ".txt")
            try {
                Files.writeString(sourceFile, "upload content")
                val item = provider.upload(sourceFile, "/docs/report.txt")

                assertEquals("docs/report.txt", item.id)
                assertEquals("report.txt", item.name)
                assertEquals("/docs/report.txt", item.path)
                assertEquals(14L, item.size)
                assertFalse(item.isFolder)
                assertNotNull(item.hash)
                assertTrue(fakeApi.objects.containsKey("docs/report.txt"))
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    @Test
    fun `upload invokes progress callback`() =
        runTest {
            val sourceFile = Files.createTempFile("s3-progress-test", ".txt")
            try {
                Files.writeString(sourceFile, "abc")
                var reported = false
                provider.upload(sourceFile, "/test.txt") { sent, total ->
                    reported = true
                    assertEquals(3L, sent)
                    assertEquals(3L, total)
                }
                assertTrue(reported)
            } finally {
                Files.deleteIfExists(sourceFile)
            }
        }

    // ── delete ──────────────────────────────────────────────────────────────

    @Test
    fun `delete removes object via api`() =
        runTest {
            fakeApi.objects["file.txt"] = byteArrayOf()
            fakeApi.listAllResult.add(S3Object("file.txt", "e", 0, null, false))
            provider.delete("/file.txt")
            assertFalse(fakeApi.objects.containsKey("file.txt"))
        }

    // ── createFolder ────────────────────────────────────────────────────────

    @Test
    fun `createFolder creates zero-byte marker with trailing slash`() =
        runTest {
            val item = provider.createFolder("/photos")
            assertTrue(item.isFolder)
            assertEquals("photos", item.name)
            assertEquals("/photos", item.path)
            assertEquals(0L, item.size)
            // The marker key has a trailing slash
            assertTrue(fakeApi.objects.containsKey("photos/"))
        }

    // ── move ────────────────────────────────────────────────────────────────

    @Test
    fun `move copies then deletes source`() =
        runTest {
            fakeApi.objects["old.txt"] = "data".toByteArray()
            fakeApi.metadata["old.txt"] = S3Object("old.txt", "e", 4, null, false)

            val item = provider.move("/old.txt", "/new.txt")
            assertEquals("new.txt", item.id)
            assertEquals("new.txt", item.name)
            assertEquals("/new.txt", item.path)
            assertFalse(item.isFolder)
            // Source removed, destination exists
            assertFalse(fakeApi.objects.containsKey("old.txt"))
            assertTrue(fakeApi.objects.containsKey("new.txt"))
            assertEquals("data", String(fakeApi.objects["new.txt"]!!))
        }

    @Test
    fun `move rolls back copy when delete fails`() =
        runTest {
            fakeApi.objects["src.txt"] = "data".toByteArray()
            fakeApi.metadata["src.txt"] = S3Object("src.txt", "e", 4, null, false)
            fakeApi.deleteThrows = S3Exception("delete failed", 500)

            assertFailsWith<S3Exception> { provider.move("/src.txt", "/dst.txt") }
            // Rollback should have attempted to delete the copy too.
            // Since deleteThrows affects all deletes, the rollback delete also throws,
            // but the original exception is propagated.
            assertTrue(fakeApi.objects.containsKey("src.txt"))
        }

    // ── listChildren ────────────────────────────────────────────────────────

    @Test
    fun `listChildren returns items under prefix`() =
        runTest {
            fakeApi.listAllResult.addAll(
                listOf(
                    S3Object("docs/a.txt", "e1", 10, "2025-01-01T00:00:00Z", false),
                    S3Object("docs/b.txt", "e2", 20, "2025-01-02T00:00:00Z", false),
                ),
            )

            val items = provider.listChildren("/docs")
            assertEquals(2, items.size)
            assertTrue(items.any { it.name == "a.txt" })
            assertTrue(items.any { it.name == "b.txt" })
        }

    @Test
    fun `listChildren returns empty for nonexistent prefix`() =
        runTest {
            val items = provider.listChildren("/empty")
            assertTrue(items.isEmpty())
        }

    // ── getMetadata ─────────────────────────────────────────────────────────

    @Test
    fun `getMetadata returns item for exact key match`() =
        runTest {
            fakeApi.listAllResult.add(
                S3Object("docs/file.txt", "etag1", 100, "2025-03-01T12:00:00Z", false),
            )

            val item = provider.getMetadata("/docs/file.txt")
            assertEquals("file.txt", item.name)
            assertEquals(100L, item.size)
            assertFalse(item.isFolder)
        }

    @Test
    fun `getMetadata throws S3Exception for missing object`() =
        runTest {
            assertFailsWith<S3Exception> { provider.getMetadata("/nonexistent.txt") }
        }

    // ── delta ───────────────────────────────────────────────────────────────

    @Test
    fun `delta with null cursor returns all items as initial snapshot`() =
        runTest {
            fakeApi.listAllResult.addAll(
                listOf(
                    S3Object("a.txt", "e1", 10, "2025-01-01T00:00:00Z", false),
                    S3Object("folder/", null, 0, null, true),
                ),
            )

            val page = provider.delta(null)
            assertFalse(page.hasMore)
            assertTrue(page.cursor.isNotBlank())
            assertEquals(2, page.items.size)
            assertTrue(page.items.any { it.name == "a.txt" && !it.deleted })
            assertTrue(page.items.any { it.name == "folder" && it.isFolder })
        }

    @Test
    fun `delta detects new file since previous cursor`() =
        runTest {
            fakeApi.listAllResult.add(S3Object("existing.txt", "e1", 10, "2025-01-01T00:00:00Z", false))
            val firstPage = provider.delta(null)
            val cursor = firstPage.cursor

            // Add a new file
            fakeApi.listAllResult.add(S3Object("new.txt", "e2", 20, "2025-01-02T00:00:00Z", false))

            val secondPage = provider.delta(cursor)
            assertFalse(secondPage.hasMore)
            assertTrue(secondPage.items.any { it.name == "new.txt" && !it.deleted })
        }

    @Test
    fun `delta detects deleted file since previous cursor`() =
        runTest {
            fakeApi.listAllResult.add(S3Object("gone.txt", "e1", 10, "2025-01-01T00:00:00Z", false))
            val firstPage = provider.delta(null)
            val cursor = firstPage.cursor

            // Remove the file
            fakeApi.listAllResult.clear()

            val secondPage = provider.delta(cursor)
            assertTrue(secondPage.items.any { it.name == "gone.txt" && it.deleted })
        }

    @Test
    fun `delta detects modified file by changed etag`() =
        runTest {
            fakeApi.listAllResult.add(S3Object("file.txt", "etag-v1", 10, "2025-01-01T00:00:00Z", false))
            val cursor = provider.delta(null).cursor

            // Modify: same key, different etag
            fakeApi.listAllResult.clear()
            fakeApi.listAllResult.add(S3Object("file.txt", "etag-v2", 15, "2025-01-02T00:00:00Z", false))

            val page = provider.delta(cursor)
            assertTrue(page.items.any { it.name == "file.txt" && !it.deleted })
        }

    @Test
    fun `delta with no changes returns empty items`() =
        runTest {
            fakeApi.listAllResult.add(S3Object("stable.txt", "e1", 10, "2025-01-01T00:00:00Z", false))
            val cursor = provider.delta(null).cursor

            // No changes
            val page = provider.delta(cursor)
            assertTrue(page.items.isEmpty())
        }

    // ── quota ───────────────────────────────────────────────────────────────

    @Test
    fun `quota returns zeroes for S3`() =
        runTest {
            val q = provider.quota()
            assertEquals(0L, q.total)
            assertEquals(0L, q.used)
            assertEquals(0L, q.remaining)
        }

    // ── share ───────────────────────────────────────────────────────────────

    @Test
    fun `share returns Success with presigned URL when authenticated`() =
        runTest {
            provider.authenticate()
            fakeApi.presignResult = "https://presigned.example.com/file.txt"
            val result = provider.share("/file.txt", expiryHours = 12)
            assertIs<CapabilityResult.Success<String>>(result)
            assertEquals("https://presigned.example.com/file.txt", result.value)
        }

    @Test
    fun `share returns Unsupported when not authenticated`() =
        runTest {
            assertFalse(provider.isAuthenticated)
            val result = provider.share("/file.txt")
            assertIs<CapabilityResult.Unsupported>(result)
            assertEquals(Capability.Share, result.capability)
        }

    // ── toCloudItem conversion ──────────────────────────────────────────────

    @Test
    fun `listChildren converts S3Object to CloudItem with correct fields`() =
        runTest {
            fakeApi.listAllResult.add(
                S3Object("prefix/file.txt", "\"abc\"", 42, "2025-06-15T08:30:00Z", false),
            )

            val items = provider.listChildren("/prefix")
            assertEquals(1, items.size)
            val item = items[0]
            assertEquals("prefix/file.txt", item.id)
            assertEquals("file.txt", item.name)
            assertEquals("/prefix/file.txt", item.path)
            assertEquals(42L, item.size)
            assertFalse(item.isFolder)
            assertNotNull(item.modified)
            assertEquals("\"abc\"", item.hash)
            assertEquals("application/octet-stream", item.mimeType)
        }

    @Test
    fun `folder S3Object converts with null mimeType`() =
        runTest {
            fakeApi.listAllResult.add(
                S3Object("prefix/subfolder/", null, 0, null, true),
            )

            val items = provider.listChildren("/prefix")
            assertEquals(1, items.size)
            val item = items[0]
            assertTrue(item.isFolder)
            assertNull(item.mimeType)
            assertEquals("subfolder", item.name)
        }

    // ── close ───────────────────────────────────────────────────────────────

    @Test
    fun `close delegates to api`() {
        // Should not throw
        provider.close()
    }
}
