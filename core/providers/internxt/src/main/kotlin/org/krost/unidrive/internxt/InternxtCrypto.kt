package org.krost.unidrive.internxt

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class InternxtCrypto {
    companion object {
        private const val PBKDF2_ITERATIONS = 2145
        private const val SALT_SIZE = 64
        private const val IV_SIZE = 16
        private const val TAG_SIZE = 16
        private const val KEY_SIZE = 32
        private const val GCM_TAG_BITS = 128
        private const val BIP39_ITERATIONS = 2048
        private const val BIP39_KEY_LENGTH = 512

        fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            }
            return data
        }

        /**
         * Construct the HTTP Basic auth header for the Internxt bridge.
         * Format: Basic base64("${bridgeUser}:${sha256hex(bridgeUserId)}")
         */
        fun bridgeAuthHeader(
            bridgeUser: String,
            bridgeUserId: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(bridgeUserId.toByteArray(Charsets.UTF_8))
            val hashHex = hashBytes.joinToString("") { "%02x".format(it) }
            val credentials = "$bridgeUser:$hashHex"
            val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
            return "Basic $encoded"
        }
    }

    private val random = SecureRandom()

    /**
     * Encrypt a filename using AES-256-GCM with PBKDF2-SHA512 key derivation.
     * Output: base64(salt[64] || iv[16] || authTag[16] || ciphertext)
     */
    fun encryptName(
        plaintext: String,
        password: String,
        salt: ByteArray = ByteArray(SALT_SIZE).also { random.nextBytes(it) },
        iv: ByteArray = ByteArray(IV_SIZE).also { random.nextBytes(it) },
    ): String {
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // GCM appends auth tag to ciphertext. Split: last 16 bytes = tag, rest = ciphertext.
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - TAG_SIZE)
        val tag = encrypted.copyOfRange(encrypted.size - TAG_SIZE, encrypted.size)

        // Wire format: salt || iv || tag || ciphertext
        val output = salt + iv + tag + ciphertext
        return Base64.getEncoder().encodeToString(output)
    }

    /**
     * Decrypt a filename encrypted with encryptName.
     * Input: base64(salt[64] || iv[16] || authTag[16] || ciphertext)
     */
    fun decryptName(
        encdata: String,
        password: String,
    ): String {
        val data = Base64.getDecoder().decode(encdata)
        require(data.size >= SALT_SIZE + IV_SIZE + TAG_SIZE + 1) { "Encrypted data too short" }

        val salt = data.copyOfRange(0, SALT_SIZE)
        val iv = data.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val tag = data.copyOfRange(SALT_SIZE + IV_SIZE, SALT_SIZE + IV_SIZE + TAG_SIZE)
        val ciphertext = data.copyOfRange(SALT_SIZE + IV_SIZE + TAG_SIZE, data.size)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))

        // GCM expects ciphertext + tag concatenated
        val input = ciphertext + tag
        val decrypted = cipher.doFinal(input)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * BIP39 mnemonic to 512-bit seed.
     * PBKDF2-SHA512 with "mnemonic" as salt, 2048 iterations.
     */
    fun mnemonicToSeed(mnemonic: String): ByteArray {
        val spec =
            PBEKeySpec(
                mnemonic.toCharArray(),
                "mnemonic".toByteArray(Charsets.UTF_8),
                BIP39_ITERATIONS,
                BIP39_KEY_LENGTH,
            )
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
    }

    /**
     * Derive a bucket key from a BIP39 seed and bucket ID.
     * SHA-512(seed || hex_bytes(bucketId)) -> first 32 bytes.
     * Matches @internxt/inxt-js GetFileDeterministicKey / GenerateFileBucketKey.
     */
    fun deriveBucketKey(
        seed: ByteArray,
        bucketId: String,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(seed)
        digest.update(hexToBytes(bucketId))
        return digest.digest().copyOfRange(0, KEY_SIZE)
    }

    /**
     * Derive a per-file encryption key.
     * SHA-512(bucketKey[0:32] || fileIndex) -> first 32 bytes.
     */
    fun deriveFileKey(
        bucketKey: ByteArray,
        fileIndex: ByteArray,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(bucketKey.copyOfRange(0, KEY_SIZE))
        digest.update(fileIndex)
        return digest.digest().copyOfRange(0, KEY_SIZE)
    }

    /**
     * Encrypt file content using AES-256-CTR.
     */
    fun createContentEncryptCipher(
        fileKey: ByteArray,
        iv: ByteArray,
    ): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), IvParameterSpec(iv))
        return cipher
    }

    /**
     * Decrypt file content using AES-256-CTR.
     */
    fun createContentDecryptCipher(
        fileKey: ByteArray,
        iv: ByteArray,
    ): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, "AES"), IvParameterSpec(iv))
        return cipher
    }

    // --- CryptoJS-compatible AES (OpenSSL EVP_BytesToKey, AES-256-CBC) ---

    private val SALTED_PREFIX = "Salted__".toByteArray(Charsets.US_ASCII)

    /**
     * Decrypt data encrypted by CryptoJS.AES.encrypt(plaintext, passphrase).
     * Format: "Salted__" + 8-byte salt + ciphertext (base64-encoded).
     * Key derivation: OpenSSL EVP_BytesToKey with MD5.
     */
    fun cryptoJsDecrypt(
        base64Ciphertext: String,
        passphrase: String,
    ): String {
        val data = Base64.getDecoder().decode(base64Ciphertext)
        // Check "Salted__" prefix
        val prefix = data.copyOfRange(0, 8)
        require(prefix.contentEquals(SALTED_PREFIX)) { "Missing Salted__ prefix" }
        val salt = data.copyOfRange(8, 16)
        val ciphertext = data.copyOfRange(16, data.size)

        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Encrypt data in CryptoJS.AES.encrypt(plaintext, passphrase) compatible format.
     * Output: base64("Salted__" + salt + ciphertext)
     */
    fun cryptoJsEncrypt(
        plaintext: String,
        passphrase: String,
    ): String {
        val salt = ByteArray(8).also { random.nextBytes(it) }
        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val output = SALTED_PREFIX + salt + encrypted
        return Base64.getEncoder().encodeToString(output)
    }

    /**
     * OpenSSL EVP_BytesToKey: derives 32-byte key + 16-byte IV from passphrase + salt using MD5.
     */
    private fun evpBytesToKey(
        passphrase: ByteArray,
        salt: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        val derived = ByteArray(48) // 32 (key) + 16 (iv)
        var offset = 0
        var prev: ByteArray? = null
        while (offset < 48) {
            md5.reset()
            if (prev != null) md5.update(prev)
            md5.update(passphrase)
            md5.update(salt)
            prev = md5.digest()
            val toCopy = minOf(prev.size, 48 - offset)
            System.arraycopy(prev, 0, derived, offset, toCopy)
            offset += toCopy
        }
        return Pair(derived.copyOfRange(0, 32), derived.copyOfRange(32, 48))
    }

    // --- Internxt login password hashing ---

    /**
     * Hash the password for Internxt login, matching CryptoJS-based flow in drive-desktop-linux.
     *
     * 1. Decrypt sKey (hex) with CRYPTO_KEY to get the salt (hex string)
     * 2. PBKDF2(password, salt, iterations=10000, keySize=256) -> hex hash
     * 3. Encrypt the hash with CRYPTO_KEY -> base64 -> hex
     */
    fun hashPassword(
        password: String,
        sKey: String,
        cryptoKey: String,
    ): String {
        // sKey is hex → decode to bytes → base64 string → decrypt with cryptoKey
        val sKeyBytes = hexToBytes(sKey)
        val sKeyBase64 = Base64.getEncoder().encodeToString(sKeyBytes)
        val saltHex = cryptoJsDecrypt(sKeyBase64, cryptoKey)

        // Parse salt hex → bytes, PBKDF2
        val salt = hexToBytes(saltHex)
        val spec = PBEKeySpec(password.toCharArray(), salt, 10000, 256)
        val hashedBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        val hashedHex = bytesToHex(hashedBytes)

        // Encrypt the hash with cryptoKey → base64 → hex
        val encryptedBase64 = cryptoJsEncrypt(hashedHex, cryptoKey)
        val encryptedBytes = Base64.getDecoder().decode(encryptedBase64)
        return bytesToHex(encryptedBytes)
    }

    /**
     * Decrypt the mnemonic returned by the Internxt login API.
     * The mnemonic is hex-encoded, CryptoJS-encrypted with the plaintext password.
     */
    fun decryptMnemonic(
        encryptedMnemonicHex: String,
        password: String,
    ): String {
        val bytes = hexToBytes(encryptedMnemonicHex)
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return cryptoJsDecrypt(base64, password)
    }

    // --- Hex utilities ---

    private fun hexToBytes(hex: String): ByteArray = Companion.hexToBytes(hex)

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun deriveKey(
        password: String,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
    }
}
