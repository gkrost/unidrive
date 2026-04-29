package org.krost.unidrive.sync

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class ThrottledProviderTest {
    /** Minimal stub that records calls and reports given byte counts. */
    private class StubProvider(
        private val downloadBytes: Long = 0L,
        private val uploadBytes: Long = 0L,
    ) : CloudProvider {
        override val id = "stub"
        override val displayName = "Stub"
        override val isAuthenticated = true

        override fun capabilities(): Set<org.krost.unidrive.Capability> = emptySet()

        var downloadCalled = false
        var uploadCalled = false

        override suspend fun authenticate() {}

        override suspend fun logout() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String) = error("not used")

        override suspend fun delta(cursor: String?) = DeltaPage(emptyList(), "", false)

        override suspend fun quota() = QuotaInfo(0, 0, 0)

        override suspend fun delete(remotePath: String) {}

        override suspend fun createFolder(path: String) = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ) = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            downloadCalled = true
            return downloadBytes
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploadCalled = true
            return CloudItem(
                id = "id",
                name = "f",
                path = remotePath,
                size = uploadBytes,
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = null,
                mimeType = null,
            )
        }
    }

    @Test
    fun `delegates download to inner provider`() =
        runBlocking {
            val stub = StubProvider(downloadBytes = 100)
            val throttled = ThrottledProvider(stub, maxBytesPerSecond = 1_000_000)
            val tmpFile = Files.createTempFile("throttle-test", ".bin")
            try {
                val bytes = throttled.download("/file.bin", tmpFile)
                assertTrue(stub.downloadCalled)
                assertEquals(100L, bytes)
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

    @Test
    fun `delegates upload to inner provider`() =
        runBlocking {
            val stub = StubProvider(uploadBytes = 200)
            val throttled = ThrottledProvider(stub, maxBytesPerSecond = 1_000_000)
            val tmpFile = Files.createTempFile("throttle-test", ".bin")
            try {
                Files.write(tmpFile, ByteArray(200))
                val result = throttled.upload(tmpFile, "/file.bin")
                assertTrue(stub.uploadCalled)
                assertEquals(200L, result.size)
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

    @Test
    fun `throttle does not sleep when transfer is slow enough`() =
        runBlocking {
            // Rate = 1 byte/s; 0 bytes transferred → no sleep
            val stub = StubProvider(downloadBytes = 0)
            val throttled = ThrottledProvider(stub, maxBytesPerSecond = 1)
            val tmpFile = Files.createTempFile("throttle-test", ".bin")
            try {
                val start = System.currentTimeMillis()
                throttled.download("/file.bin", tmpFile)
                val elapsed = System.currentTimeMillis() - start
                assertTrue(elapsed < 500, "Should complete quickly for 0 bytes, took ${elapsed}ms")
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

    @Test
    fun `rejects non-positive maxBytesPerSecond`() {
        val stub = StubProvider()
        assertFailsWith<IllegalArgumentException> {
            ThrottledProvider(stub, maxBytesPerSecond = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ThrottledProvider(stub, maxBytesPerSecond = -1)
        }
    }
}
