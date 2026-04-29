package org.krost.unidrive.xtra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.test.*

class AesGcmStrategyTest {
    private val strategy = AesGcmStrategy()

    @Test fun `encrypt and decrypt round-trip`() {
        val plaintext = "Hello, X-tra Encryption!".toByteArray()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(strategy.ivLength).also { SecureRandom().nextBytes(it) }
        val encrypted = ByteArrayOutputStream()
        strategy.encrypt(ByteArrayInputStream(plaintext), encrypted, key, iv)
        assertFalse(encrypted.toByteArray().contentEquals(plaintext))
        val decrypted = ByteArrayOutputStream()
        strategy.decrypt(ByteArrayInputStream(encrypted.toByteArray()), decrypted, key, iv)
        assertContentEquals(plaintext, decrypted.toByteArray())
    }

    @Test fun `round-trip with large data`() {
        val plaintext = ByteArray(1_000_000).also { SecureRandom().nextBytes(it) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(strategy.ivLength).also { SecureRandom().nextBytes(it) }
        val encrypted = ByteArrayOutputStream()
        strategy.encrypt(ByteArrayInputStream(plaintext), encrypted, key, iv)
        val decrypted = ByteArrayOutputStream()
        strategy.decrypt(ByteArrayInputStream(encrypted.toByteArray()), decrypted, key, iv)
        assertContentEquals(plaintext, decrypted.toByteArray())
    }

    @Test fun `wrong key fails`() {
        val key1 = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val key2 = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(strategy.ivLength).also { SecureRandom().nextBytes(it) }
        val encrypted = ByteArrayOutputStream()
        strategy.encrypt(ByteArrayInputStream("secret".toByteArray()), encrypted, key1, iv)
        assertFailsWith<Exception> {
            strategy.decrypt(ByteArrayInputStream(encrypted.toByteArray()), ByteArrayOutputStream(), key2, iv)
        }
    }

    @Test fun `algorithm metadata`() {
        assertEquals(1.toByte(), strategy.algorithmId)
        assertEquals(12, strategy.ivLength)
        assertEquals(256, strategy.wrappedKeyLength)
    }
}
