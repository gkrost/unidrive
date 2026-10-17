package org.krost.unidrive.internxt

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JDK has no RIPEMD-160, so the module carries its own (Ripemd160). The expected values are the published
 * reference vectors (Bosselaers' RIPEMD-160 page, ISO/IEC 10118-3), not output of this implementation; the chained
 * ones are RIPEMD-160(SHA-256(x)) as the Internxt network records a shard, computed independently with Node's
 * crypto module (the same chain as inxt-js `HashStream.getHash`).
 */
class Ripemd160Test {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun ripemd(text: String) = hex(Ripemd160.digest(text.toByteArray(Charsets.US_ASCII)))

    @Test
    fun `matches the published reference vectors`() {
        assertEquals("9c1185a5c5e9fc54612808977ee8f548b2258d31", ripemd(""))
        assertEquals("0bdc9d2d256b3ee9daae347be6f4dc835a467ffe", ripemd("a"))
        assertEquals("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc", ripemd("abc"))
        assertEquals("5d0689ef49d2fae572b881b123a85ffa21595f36", ripemd("message digest"))
        assertEquals("f71c27109c692c1b56bbdceb5b9d2865b3708dbc", ripemd("abcdefghijklmnopqrstuvwxyz"))
        assertEquals(
            "12a053384a9c0c88e405a06c27dcf49ada62eb2b",
            ripemd("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        assertEquals(
            "b0e20b6e3116640286ed3a87a5713079b21f5189",
            ripemd("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"),
        )
        assertEquals("9b752e45573d4b39f4dbd3323cab82bf63326bfb", ripemd("1234567890".repeat(8)))
    }

    @Test
    fun `matches the reference vector for one million a`() {
        assertEquals("52783243c1697bdbe16d37f97f68f08325dc1528", hex(Ripemd160.digest(ByteArray(1_000_000) { 'a'.code.toByte() })))
    }

    // Lengths either side of the padding rule: 55 bytes still fit one block with the length, 56 need a second
    // one, 63/64/65 straddle a full block, 119/120 and 128 repeat that for two blocks. Values from Node's crypto.
    @Test
    fun `pads correctly around the block boundaries`() {
        val expected =
            mapOf(
                55 to "c35538a4ab9792cab98479aa3ae2cc435c699b64",
                56 to "af13b5ead9b74a9a6b97c4a612ddfd0baf61ff11",
                63 to "9a195cb1828886c55c6dfe9e042399833dfbcd00",
                64 to "bab3f04cc25d952a882718636600f5b22307ac41",
                65 to "8421cbafb84e777fbd88c89863cee557103515d5",
                119 to "96e1ae9b6b79fa79e7eb3097e35b64399176d7e0",
                120 to "78dbb5007c86a1da8bdeb77419000efbdf277e9c",
                128 to "cd3b1f43f0c17230521fc14608a2d15240ee9e66",
            )
        for ((length, digest) in expected) assertEquals(digest, ripemd("x".repeat(length)), "length $length")
    }

    @Test
    fun `chained over SHA-256 it gives the hash the network records for a shard`() {
        fun chain(bytes: ByteArray) = hex(Ripemd160.digest(MessageDigest.getInstance("SHA-256").digest(bytes)))
        assertEquals("b472a266d0bd89c13706a4132ccfb16f7c3b9fcb", chain(ByteArray(0)))
        assertEquals("bb1be98c142444d7a56aa3981c3942a978e4dc33", chain("abc".toByteArray()))
        assertEquals("539cef25668d4b91379ec682f19cd7a1169f2701", chain("0123456789A".toByteArray()))
    }
}
