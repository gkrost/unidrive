package org.krost.unidrive.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FsLimitsTest {

    @Test
    fun `ext4 max name length for ASCII is 255`() {
        val codepoints = List(300) { 'a'.code }
        assertEquals(255, FsLimits.maxNameLength(codepoints, FsType.EXT4))
    }

    @Test
    fun `ext4 max name length for 4-byte UTF-8 is 63`() {
        // U+1F600 encodes as 4 bytes in UTF-8; 255 / 4 = 63
        val codepoints = List(100) { 0x1F600 }
        assertEquals(63, FsLimits.maxNameLength(codepoints, FsType.EXT4))
    }

    @Test
    fun `ntfs max name length is 255`() {
        val codepoints = List(300) { 0x1F600 }
        assertEquals(255, FsLimits.maxNameLength(codepoints, FsType.NTFS))
    }

    @Test
    fun `shouldSkip returns true for reserved Windows names`() {
        assertTrue(FsLimits.shouldSkipName("CON", FsType.NTFS))
        assertTrue(FsLimits.shouldSkipName("NUL.txt", FsType.NTFS))
        assertFalse(FsLimits.shouldSkipName("normal.txt", FsType.NTFS))
    }

    @Test
    fun `shouldSkip returns true for names with reserved chars`() {
        assertTrue(FsLimits.shouldSkipName("file<name>.txt", FsType.NTFS))
        assertFalse(FsLimits.shouldSkipName("file-name.txt", FsType.EXT4))
    }
}
