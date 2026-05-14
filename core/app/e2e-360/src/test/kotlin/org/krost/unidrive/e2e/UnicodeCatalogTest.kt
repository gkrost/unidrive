package org.krost.unidrive.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnicodeCatalogTest {

    @Test
    fun `catalog has approximately 149k codepoints`() {
        val size = UnicodeCatalog.allGraphicCodepoints().size
        assertTrue(size in 140_000..160_000, "Expected ~149k but got $size")
    }

    @Test
    fun `catalog excludes surrogates`() {
        val codepoints = UnicodeCatalog.allGraphicCodepoints()
        assertTrue(codepoints.none { it in 0xD800..0xDFFF }, "Surrogates found in catalog")
    }

    @Test
    fun `catalog excludes noncharacters`() {
        val codepoints = UnicodeCatalog.allGraphicCodepoints()
        assertTrue(codepoints.none { it and 0xFFFF == 0xFFFE }, "FFFE noncharacter found")
        assertTrue(codepoints.none { it and 0xFFFF == 0xFFFF }, "FFFF noncharacter found")
    }

    @Test
    fun `sample returns requested count`() {
        val sample = UnicodeCatalog.sample(50, seed = 42L)
        assertEquals(50, sample.size)
    }
}
