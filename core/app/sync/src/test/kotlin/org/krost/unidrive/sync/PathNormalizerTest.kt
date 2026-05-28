package org.krost.unidrive.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// #171: the NFC canonicalization helper + the on-disk resolution fallback.
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

    // On a byte-preserving FS (Linux/CI), an NFD on-disk file must still resolve
    // via its NFC logical path so uploads read the real bytes (issue #211 P2).
    @Test
    fun `safeResolveLocal finds an NFD on-disk file via its NFC path`() {
        val root = Files.createTempDirectory("ud-nfc-resolve")
        val nfdName = "scho\u0308n.txt" // decomposed name on disk
        Files.createFile(root.resolve(nfdName))
        val resolved = safeResolveLocal(root, "/sch\u00F6n.txt") // NFC query
        assertTrue(Files.exists(resolved), "must resolve to the real on-disk NFD file")
        assertEquals(nfdName, resolved.fileName.toString())
    }
}
