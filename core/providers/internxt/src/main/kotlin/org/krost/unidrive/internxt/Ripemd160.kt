package org.krost.unidrive.internxt

/**
 * RIPEMD-160 (Dobbertin, Bosselaers, Preneel 1996). The JDK ships no provider for it and the module carries no
 * crypto library; the Internxt network records every shard as RIPEMD-160(SHA-256(ciphertext)), so the download
 * check needs exactly this one hash of a 32-byte input. Checked against the reference vectors in Ripemd160Test.
 */
internal object Ripemd160 {
    const val DIGEST_SIZE = 20
    private const val BLOCK_SIZE = 64
    private const val ROUNDS = 80

    // Message word order and rotation amounts, left line then right line (one row of 16 per round).
    private val R_LEFT =
        intArrayOf(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
            3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
            1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
            4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13,
        )
    private val R_RIGHT =
        intArrayOf(
            5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
            6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
            15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
            8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
            12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11,
        )
    private val S_LEFT =
        intArrayOf(
            11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
            7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
            11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
            11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
            9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6,
        )
    private val S_RIGHT =
        intArrayOf(
            8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
            9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
            9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
            15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
            8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11,
        )
    private val K_LEFT = intArrayOf(0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC.toInt(), 0xA953FD4E.toInt())
    private val K_RIGHT = intArrayOf(0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0x00000000)

    fun digest(input: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        val padded = pad(input)
        val x = IntArray(16)
        for (block in 0 until padded.size / BLOCK_SIZE) {
            for (i in 0 until 16) x[i] = littleEndianInt(padded, block * BLOCK_SIZE + i * 4)

            var al = h0
            var bl = h1
            var cl = h2
            var dl = h3
            var el = h4
            var ar = h0
            var br = h1
            var cr = h2
            var dr = h3
            var er = h4
            for (j in 0 until ROUNDS) {
                val round = j / 16
                var t = Integer.rotateLeft(al + f(j, bl, cl, dl) + x[R_LEFT[j]] + K_LEFT[round], S_LEFT[j]) + el
                al = el
                el = dl
                dl = Integer.rotateLeft(cl, 10)
                cl = bl
                bl = t
                t = Integer.rotateLeft(ar + f(ROUNDS - 1 - j, br, cr, dr) + x[R_RIGHT[j]] + K_RIGHT[round], S_RIGHT[j]) + er
                ar = er
                er = dr
                dr = Integer.rotateLeft(cr, 10)
                cr = br
                br = t
            }
            val t = h1 + cl + dr
            h1 = h2 + dl + er
            h2 = h3 + el + ar
            h3 = h4 + al + br
            h4 = h0 + bl + cr
            h0 = t
        }

        val out = ByteArray(DIGEST_SIZE)
        intArrayOf(h0, h1, h2, h3, h4).forEachIndexed { i, word ->
            for (b in 0 until 4) out[i * 4 + b] = (word ushr (8 * b)).toByte()
        }
        return out
    }

    private fun f(
        j: Int,
        x: Int,
        y: Int,
        z: Int,
    ): Int =
        when (j / 16) {
            0 -> x xor y xor z
            1 -> (x and y) or (x.inv() and z)
            2 -> (x or y.inv()) xor z
            3 -> (x and z) or (y and z.inv())
            else -> x xor (y or z.inv())
        }

    // MD4-style padding: 0x80, zeros up to 56 mod 64, then the message length in bits as a little-endian long.
    private fun pad(input: ByteArray): ByteArray {
        val zeros = (BLOCK_SIZE + 56 - (input.size + 1) % BLOCK_SIZE) % BLOCK_SIZE
        val padded = ByteArray(input.size + 1 + zeros + 8)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        val bits = input.size.toLong() * 8
        for (b in 0 until 8) padded[padded.size - 8 + b] = (bits ushr (8 * b)).toByte()
        return padded
    }

    private fun littleEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
