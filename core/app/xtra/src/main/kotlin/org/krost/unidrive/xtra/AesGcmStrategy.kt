package org.krost.unidrive.xtra

import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesGcmStrategy : XtraEncryptionStrategy {
    override val algorithmId: Byte = 1
    override val ivLength: Int = 12
    override val wrappedKeyLength: Int = 256

    private companion object {
        const val BUFFER_SIZE = 65536
        const val TAG_BITS = 128
    }

    override fun encrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val buf = ByteArray(BUFFER_SIZE)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            val chunk = cipher.update(buf, 0, n)
            if (chunk != null) output.write(chunk)
        }
        output.write(cipher.doFinal())
    }

    override fun decrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val buf = ByteArray(BUFFER_SIZE)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            val chunk = cipher.update(buf, 0, n)
            if (chunk != null) output.write(chunk)
        }
        output.write(cipher.doFinal())
    }
}
