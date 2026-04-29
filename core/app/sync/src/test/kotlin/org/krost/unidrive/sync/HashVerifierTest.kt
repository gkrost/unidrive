package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class HashVerifierTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("unidrive-hash-test")
    }

    @Test
    fun `verify returns true when hash is null`() {
        val file = createFile("test.txt", "content")
        assertTrue(HashVerifier.verify(file, null, "onedrive"))
    }

    @Test
    fun `verify returns true for unknown provider`() {
        val file = createFile("test.txt", "content")
        assertTrue(HashVerifier.verify(file, "somehash", "sftp"))
        assertTrue(HashVerifier.verify(file, "somehash", "webdav"))
        assertTrue(HashVerifier.verify(file, "somehash", "rclone"))
    }

    @Test
    fun `verify S3 MD5 returns true when correct`() {
        val file = createFile("test.txt", "Hello, World!")
        val hash = HashVerifier.computeMd5Hex(file)
        assertTrue(HashVerifier.verify(file, hash, "s3"))
    }

    @Test
    fun `verify S3 MD5 returns false when incorrect`() {
        val file = createFile("test.txt", "Hello, World!")
        assertFalse(HashVerifier.verify(file, "deadbeef", "s3"))
    }

    @Test
    fun `verify S3 skips multipart ETags`() {
        val file = createFile("test.txt", "Hello, World!")
        // Multipart ETags contain "-" and should be skipped
        assertTrue(HashVerifier.verify(file, "abc123-3", "s3"))
        assertTrue(HashVerifier.verify(file, "def456-5", "s3"))
    }

    @Test
    fun `computeMd5Hex produces known value`() {
        val file = createFile("test.txt", "Hello, World!")
        // Known MD5 for "Hello, World!" is 65a8e27d8879283831b664bd8b7f0ad4
        val hash = HashVerifier.computeMd5Hex(file)
        assertEquals("65a8e27d8879283831b664bd8b7f0ad4", hash)
    }

    @Test
    fun `computeQuickXorHash round-trip`() {
        val file = createFile("test.txt", "Hello, World!")
        val hash1 = HashVerifier.computeQuickXorHash(file)
        assertTrue(HashVerifier.verify(file, hash1, "onedrive"))
    }

    @Test
    fun `verify QuickXorHash returns false when incorrect`() {
        val file = createFile("test.txt", "Hello, World!")
        // Arbitrary base64 that won't match
        assertFalse(HashVerifier.verify(file, "AAAAAAAAAAAAAAAAAAAAAAAAAAAA", "onedrive"))
    }

    private fun createFile(
        name: String,
        content: String,
    ): Path {
        val file = tempDir.resolve(name)
        Files.writeString(file, content)
        return file
    }
}
