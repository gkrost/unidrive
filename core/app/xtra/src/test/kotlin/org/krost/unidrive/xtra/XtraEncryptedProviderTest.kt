package org.krost.unidrive.xtra

import org.krost.unidrive.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class XtraEncryptedProviderTest {
    @Test
    fun `upload encrypts and download decrypts round-trip`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val km = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())

        val storage = mutableMapOf<String, ByteArray>()
        val inner = InMemoryProvider(storage)
        val xtra = XtraEncryptedProvider(inner, km)

        val localFile = tmpDir.resolve("test.txt")
        Files.writeString(localFile, "Hello, X-tra!")

        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/test.txt") }

        // Verify stored data has XTRA header
        val stored = storage["/test.txt"]!!
        assertEquals("XTRA", String(stored.copyOfRange(0, 4)))

        // Download should decrypt
        val dest = tmpDir.resolve("downloaded.txt")
        kotlinx.coroutines.runBlocking { xtra.download("/test.txt", dest) }
        assertEquals("Hello, X-tra!", Files.readString(dest))
    }

    @Test
    fun `downloadById also decrypts`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val km = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())

        val storage = mutableMapOf<String, ByteArray>()
        val inner = InMemoryProvider(storage)
        val xtra = XtraEncryptedProvider(inner, km)

        val localFile = tmpDir.resolve("test2.txt")
        Files.writeString(localFile, "ById test")
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/test2.txt") }

        val dest = tmpDir.resolve("byid.txt")
        kotlinx.coroutines.runBlocking { xtra.downloadById("fake-id", "/test2.txt", dest) }
        assertEquals("ById test", Files.readString(dest))
    }

    @Test
    fun `download non-encrypted file throws`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val km = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())

        val storage = mutableMapOf<String, ByteArray>()
        storage["/plain.txt"] = "not encrypted".toByteArray()
        val inner = InMemoryProvider(storage)
        val xtra = XtraEncryptedProvider(inner, km)

        val dest = tmpDir.resolve("plain.txt")
        assertFailsWith<ProviderException> {
            kotlinx.coroutines.runBlocking { xtra.download("/plain.txt", dest) }
        }
    }

    @Test
    fun `empty file round-trip`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val km = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())

        val storage = mutableMapOf<String, ByteArray>()
        val xtra = XtraEncryptedProvider(InMemoryProvider(storage), km)

        val emptyFile = tmpDir.resolve("empty.dat")
        Files.createFile(emptyFile)
        assertEquals(0L, Files.size(emptyFile))

        kotlinx.coroutines.runBlocking { xtra.upload(emptyFile, "/empty.dat") }

        // Should have XTRA header even for empty files
        val stored = storage["/empty.dat"]!!
        assertEquals("XTRA", String(stored.copyOfRange(0, 4)))

        val dest = tmpDir.resolve("empty-dl.dat")
        kotlinx.coroutines.runBlocking { xtra.download("/empty.dat", dest) }
        assertEquals(0L, Files.size(dest))
    }

    @Test
    fun `large file round-trip`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val km = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())

        val storage = mutableMapOf<String, ByteArray>()
        val xtra = XtraEncryptedProvider(InMemoryProvider(storage), km)

        val localFile = tmpDir.resolve("large.bin")
        val original = ByteArray(500_000).also { java.security.SecureRandom().nextBytes(it) }
        Files.write(localFile, original)

        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/large.bin") }
        val dest = tmpDir.resolve("large-dl.bin")
        kotlinx.coroutines.runBlocking { xtra.download("/large.bin", dest) }
        assertContentEquals(original, Files.readAllBytes(dest))
    }
}

private class InMemoryProvider(
    private val storage: MutableMap<String, ByteArray>,
) : CloudProvider {
    override val id = "test"
    override val displayName = "Test"
    override val isAuthenticated = true

    override fun capabilities(): Set<org.krost.unidrive.Capability> = emptySet()

    override suspend fun authenticate() {}

    override suspend fun logout() {}

    override suspend fun listChildren(path: String) = emptyList<CloudItem>()

    override suspend fun getMetadata(path: String) = CloudItem("1", "test", path, 0, false, null, null, null, null)

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val data = storage[remotePath] ?: throw ProviderException("Not found: $remotePath")
        Files.write(destination, data)
        return data.size.toLong()
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = download(remotePath, destination)

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        storage[remotePath] = Files.readAllBytes(localPath)
        return CloudItem("1", localPath.fileName.toString(), remotePath, Files.size(localPath), false, null, null, null, null)
    }

    override suspend fun delete(remotePath: String) {
        storage.remove(remotePath)
    }

    override suspend fun createFolder(path: String) = CloudItem("1", path, path, 0, true, null, null, null, null)

    override suspend fun move(
        from: String,
        to: String,
    ) = CloudItem("1", to, to, 0, false, null, null, null, null)

    override suspend fun delta(cursor: String?) = DeltaPage(emptyList(), "", false)

    override suspend fun quota() = QuotaInfo(0, 0, 0)
}
