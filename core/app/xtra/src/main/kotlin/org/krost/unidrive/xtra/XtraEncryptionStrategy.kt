package org.krost.unidrive.xtra

import java.io.InputStream
import java.io.OutputStream

interface XtraEncryptionStrategy {
    val algorithmId: Byte
    val ivLength: Int
    val wrappedKeyLength: Int

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    )

    fun decrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    )
}
