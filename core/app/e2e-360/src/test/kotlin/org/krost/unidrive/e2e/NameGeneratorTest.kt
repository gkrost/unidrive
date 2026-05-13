package org.krost.unidrive.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameGeneratorTest {

    @Test
    fun `generates names with correct ASCII ratio`() {
        val gen = NameGenerator(asciiRatio = 50, seed = 123L)
        val names = List(100) { gen.nextFileName("txt") }
        // ASCII word pool words are all ASCII; unicode pool contains non-ASCII
        val asciiCount = names.count { name -> name.all { it.code < 128 } }
        assertTrue(asciiCount in 30..70, "Expected ASCII ratio ~50%, got $asciiCount/100")
    }

    @Test
    fun `generates unique names`() {
        val gen = NameGenerator(asciiRatio = 50, seed = 42L)
        val names = List(50) { gen.nextFileName("txt") }
        assertEquals(50, names.toSet().size, "Expected all names to be unique")
    }

    @Test
    fun `folder names are valid`() {
        val gen = NameGenerator(asciiRatio = 50, seed = 99L)
        val names = List(20) { gen.nextFolderName() }
        names.forEach { name ->
            assertTrue(name.isNotBlank(), "Folder name must not be blank")
            assertFalse('/' in name, "Folder name must not contain /")
            assertFalse('\\' in name, "Folder name must not contain \\")
        }
    }
}
