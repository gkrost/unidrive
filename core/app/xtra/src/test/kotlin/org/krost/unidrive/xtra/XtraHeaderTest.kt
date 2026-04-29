package org.krost.unidrive.xtra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.test.*

class XtraHeaderTest {
    @Test fun `write and read round-trip`() {
        val wrappedKey = ByteArray(256).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val buf = ByteArrayOutputStream()
        XtraHeader.write(buf, algorithmId = 0x01, wrappedKey = wrappedKey, iv = iv)
        val result = XtraHeader.read(ByteArrayInputStream(buf.toByteArray()))
        assertTrue(result is XtraHeader.ReadResult.Valid)
        val valid = result as XtraHeader.ReadResult.Valid
        assertEquals(0x01.toByte(), valid.algorithmId)
        assertContentEquals(wrappedKey, valid.wrappedKey)
        assertContentEquals(iv, valid.iv)
    }

    @Test fun `non-encrypted file returns NotEncrypted`() {
        val result = XtraHeader.read(ByteArrayInputStream("Just a normal file".toByteArray()))
        assertTrue(result is XtraHeader.ReadResult.NotEncrypted)
    }

    @Test fun `truncated header returns Corrupted`() {
        val buf = ByteArrayOutputStream()
        buf.write("XTRA".toByteArray())
        val result = XtraHeader.read(ByteArrayInputStream(buf.toByteArray()))
        assertTrue(result is XtraHeader.ReadResult.Corrupted)
    }

    @Test fun `header size correct`() {
        val buf = ByteArrayOutputStream()
        XtraHeader.write(buf, 0x01, ByteArray(256), ByteArray(12))
        // magic(4) + version(1) + algo(1) + wkLen(2) + key(256) + ivLen(2) + iv(12) = 278
        assertEquals(278, buf.size())
    }

    @Test fun `empty input returns NotEncrypted`() {
        val result = XtraHeader.read(ByteArrayInputStream(ByteArray(0)))
        assertTrue(result is XtraHeader.ReadResult.NotEncrypted)
    }
}
