package org.krost.unidrive.sync

import org.krost.unidrive.HashAlgorithm
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class HashVerifierTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("unidrive-hash-test")
    }

    // Dropbox's content_hash, restated from its published definition: SHA-256 over the SHA-256s of 4 MiB blocks.
    private fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `dropbox content hash of an empty file is the SHA-256 of no block digests`() {
        val file = createFile("empty.bin", "")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", HashVerifier.computeDropboxContentHash(file))
    }

    @Test
    fun `dropbox content hash of a short file hashes its one block digest`() {
        val file = createFile("short.txt", "hello")
        val expected = hex(sha256(sha256("hello".toByteArray())))
        assertEquals(expected, HashVerifier.computeHash(file, HashAlgorithm.DropboxContentHash))
        assertTrue(HashVerifier.verify(file, expected.uppercase(), HashAlgorithm.DropboxContentHash))
        assertTrue(HashVerifier.matches(file, expected, HashAlgorithm.DropboxContentHash))
        assertFalse(HashVerifier.matches(file, hex(sha256("hello".toByteArray())), HashAlgorithm.DropboxContentHash))
    }

    @Test
    fun `dropbox content hash splits at exactly 4 MiB`() {
        val block = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val tail = byteArrayOf(7)
        val file = tempDir.resolve("boundary.bin")
        Files.write(file, block + tail)
        val exact = tempDir.resolve("exact.bin")
        Files.write(exact, block)

        assertEquals(hex(sha256(sha256(block) + sha256(tail))), HashVerifier.computeDropboxContentHash(file))
        assertEquals(hex(sha256(sha256(block))), HashVerifier.computeDropboxContentHash(exact))
    }

    @Test
    fun `verify returns true when hash is null`() {
        val file = createFile("test.txt", "content")
        assertTrue(HashVerifier.verify(file, null, algorithm = HashAlgorithm.QuickXor))
    }

    @Test
    fun `verify returns true for unknown provider`() {
        val file = createFile("test.txt", "content")
        assertTrue(HashVerifier.verify(file, "somehash", algorithm = null))
        assertTrue(HashVerifier.verify(file, "somehash", algorithm = null))
        assertTrue(HashVerifier.verify(file, "somehash", algorithm = null))
    }

    @Test
    fun `verify S3 MD5 returns true when correct`() {
        val file = createFile("test.txt", "Hello, World!")
        val hash = HashVerifier.computeMd5Hex(file)
        assertTrue(HashVerifier.verify(file, hash, algorithm = HashAlgorithm.Md5Hex))
    }

    @Test
    fun `verify S3 MD5 returns false when incorrect`() {
        val file = createFile("test.txt", "Hello, World!")
        assertFalse(HashVerifier.verify(file, "deadbeef", algorithm = HashAlgorithm.Md5Hex))
    }

    @Test
    fun `verify S3 skips multipart ETags`() {
        val file = createFile("test.txt", "Hello, World!")
        // Multipart ETags contain "-" and should be skipped
        assertTrue(HashVerifier.verify(file, "abc123-3", algorithm = HashAlgorithm.Md5Hex))
        assertTrue(HashVerifier.verify(file, "def456-5", algorithm = HashAlgorithm.Md5Hex))
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
        assertTrue(HashVerifier.verify(file, hash1, algorithm = HashAlgorithm.QuickXor))
    }

    @Test
    fun `verify QuickXorHash returns false when incorrect`() {
        val file = createFile("test.txt", "Hello, World!")
        // Arbitrary base64 that won't match
        assertFalse(
            HashVerifier.verify(file, "AAAAAAAAAAAAAAAAAAAAAAAAAAAA", algorithm = HashAlgorithm.QuickXor),
        )
    }

    @org.junit.Test
    fun `null algorithm is treated as skip-verification`() {
        val tempFile =
            java.nio.file.Files
                .createTempFile("hashverifier-null", ".bin")
        java.nio.file.Files
            .write(tempFile, byteArrayOf(0x00, 0x01, 0x02))
        try {
            val result =
                HashVerifier.verify(
                    localPath = tempFile,
                    remoteHash = "ignored-because-no-algorithm",
                    algorithm = null,
                )
            kotlin.test.assertTrue(result, "null algorithm must skip verification (return true)")
        } finally {
            java.nio.file.Files
                .deleteIfExists(tempFile)
        }
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
