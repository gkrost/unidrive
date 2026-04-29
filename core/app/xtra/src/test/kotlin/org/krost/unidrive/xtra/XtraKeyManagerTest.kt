package org.krost.unidrive.xtra

import java.nio.file.Files
import java.security.SecureRandom
import kotlin.test.*

class XtraKeyManagerTest {
    @Test
    fun `generate and load key pair`() {
        val tmpDir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())
        assertTrue(Files.exists(tmpDir.resolve("xtra.key")))

        val km2 = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km2.load("testpass1234".toCharArray())
        assertTrue(km2.isLoaded)

        val original = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = km2.wrapKey(original)
        val unwrapped = km2.unwrapKey(wrapped)
        assertContentEquals(original, unwrapped)
    }

    @Test
    fun `wrong passphrase fails`() {
        val tmpDir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km.generate("correctpass1".toCharArray())

        val km2 = XtraKeyManager(tmpDir.resolve("xtra.key"))
        assertFailsWith<Exception> { km2.load("wrongpass123".toCharArray()) }
    }

    @Test
    fun `isLoaded false before load`() {
        val tmpDir = Files.createTempDirectory("xtra-key-test")
        assertFalse(XtraKeyManager(tmpDir.resolve("xtra.key")).isLoaded)
    }

    @Test
    fun `export and import round-trip`() {
        val tmpDir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km.generate("mypass12345".toCharArray())

        val exported = km.exportKey()
        val km2 = XtraKeyManager(tmpDir.resolve("xtra2.key"))
        km2.importKey(exported)
        km2.load("mypass12345".toCharArray())

        val data = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = km.wrapKey(data)
        val unwrapped = km2.unwrapKey(wrapped)
        assertContentEquals(data, unwrapped)
    }

    @Test
    fun `changePassphrase preserves key identity`() {
        val tmpDir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km.generate("oldpass12345".toCharArray())

        // Encrypt something with the original key
        val original = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val wrapped = km.wrapKey(original)

        // Change passphrase
        km.changePassphrase("newpass12345".toCharArray())

        // Load with new passphrase
        val km2 = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km2.load("newpass12345".toCharArray())

        // Decrypt with the reloaded key — must produce same data
        val unwrapped = km2.unwrapKey(wrapped)
        assertContentEquals(original, unwrapped)
    }

    @Test
    fun `changePassphrase old passphrase no longer works`() {
        val tmpDir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(tmpDir.resolve("xtra.key"))
        km.generate("oldpass12345".toCharArray())
        km.changePassphrase("newpass12345".toCharArray())

        val km2 = XtraKeyManager(tmpDir.resolve("xtra.key"))
        assertFailsWith<Exception> { km2.load("oldpass12345".toCharArray()) }
    }
}
