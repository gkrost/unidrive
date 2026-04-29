package org.krost.unidrive.xtra

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * RSA-2048 key pair manager for XtraEncryption.
 *
 * Key file format: `salt (16 bytes) || iv (12 bytes) || AES-256-GCM(PKCS8 private key bytes)`
 * Key derivation: PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt → 256-bit AES key.
 */
class XtraKeyManager(
    private val keyPath: Path,
) {
    companion object {
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val KEY_LENGTH_BITS = 256
        private const val PBKDF2_ITERATIONS = 600_000
        private const val GCM_TAG_BITS = 128
        private const val RSA_KEY_SIZE = 2048
    }

    private var privateKey: java.security.PrivateKey? = null
    private var publicKey: java.security.PublicKey? = null

    val isLoaded: Boolean
        get() = privateKey != null && publicKey != null

    fun generate(passphrase: CharArray) {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(RSA_KEY_SIZE, SecureRandom())
        val pair = keyGen.generateKeyPair()

        privateKey = pair.private
        val rsaPriv = pair.private as RSAPrivateCrtKey
        publicKey =
            KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(rsaPriv.modulus, rsaPriv.publicExponent),
            )

        val pkcs8Bytes = pair.private.encoded
        val salt = randomBytes(SALT_LENGTH)
        val iv = randomBytes(IV_LENGTH)
        val aesKey = deriveKey(passphrase, salt)
        val encrypted = aesEncrypt(aesKey, iv, pkcs8Bytes)

        Files.createDirectories(keyPath.parent)
        Files.write(keyPath, salt + iv + encrypted)
        setRestrictedPermissions(keyPath)
    }

    fun load(passphrase: CharArray) {
        val blob = Files.readAllBytes(keyPath)
        val salt = blob.copyOfRange(0, SALT_LENGTH)
        val iv = blob.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val encrypted = blob.copyOfRange(SALT_LENGTH + IV_LENGTH, blob.size)

        val aesKey = deriveKey(passphrase, salt)
        val pkcs8Bytes = aesDecrypt(aesKey, iv, encrypted)

        val kf = KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))
        privateKey = priv

        val rsaPriv = priv as RSAPrivateCrtKey
        publicKey = kf.generatePublic(RSAPublicKeySpec(rsaPriv.modulus, rsaPriv.publicExponent))
    }

    fun changePassphrase(newPassphrase: CharArray) {
        val priv = checkNotNull(privateKey) { "Key not loaded — call load() first" }
        require(newPassphrase.size >= 8) { "Passphrase must be at least 8 characters" }
        val pkcs8Bytes = priv.encoded
        val salt = randomBytes(SALT_LENGTH)
        val iv = randomBytes(IV_LENGTH)
        val aesKey = deriveKey(newPassphrase, salt)
        val encrypted = aesEncrypt(aesKey, iv, pkcs8Bytes)
        Files.write(keyPath, salt + iv + encrypted)
        setRestrictedPermissions(keyPath)
    }

    fun wrapKey(fileKey: ByteArray): ByteArray {
        val pub = checkNotNull(publicKey) { "Key not loaded" }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pub, OAEPParameterSpec.DEFAULT)
        return cipher.doFinal(fileKey)
    }

    fun unwrapKey(wrapped: ByteArray): ByteArray {
        val priv = checkNotNull(privateKey) { "Key not loaded" }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, priv, OAEPParameterSpec.DEFAULT)
        return cipher.doFinal(wrapped)
    }

    fun exportKey(): ByteArray = Files.readAllBytes(keyPath)

    fun importKey(data: ByteArray) {
        Files.createDirectories(keyPath.parent)
        Files.write(keyPath, data)
        setRestrictedPermissions(keyPath)
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun aesEncrypt(
        key: SecretKey,
        iv: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(plaintext)
    }

    private fun aesDecrypt(
        key: SecretKey,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun setRestrictedPermissions(path: Path) {
        try {
            val perms =
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-------")
            Files.setPosixFilePermissions(path, perms)
        } catch (_: UnsupportedOperationException) {
            // Windows — skip
        }
    }
}
