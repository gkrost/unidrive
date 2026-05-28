package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// #171: the NFC canonicalization helper.
class PathNormalizerTest {
    @Test
    fun `decomposed input normalizes to composed NFC`() {
        val nfc = "/sch\u00F6n.txt" // composed (U+00F6)
        val nfd = "/scho\u0308n.txt" // decomposed (o + U+0308)
        assertNotEquals(nfc, nfd, "precondition: byte-different inputs")
        assertEquals(nfc, PathNormalizer.nfc(nfd))
    }

    @Test
    fun `already-NFC and ASCII are unchanged and idempotent`() {
        val nfc = "/sch\u00F6n.txt"
        assertEquals(nfc, PathNormalizer.nfc(nfc))
        assertEquals(PathNormalizer.nfc(nfc), PathNormalizer.nfc(PathNormalizer.nfc(nfc)))
        assertEquals("/foo/bar.txt", PathNormalizer.nfc("/foo/bar.txt"))
    }
}
