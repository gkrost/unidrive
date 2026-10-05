package org.krost.unidrive.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * #529: a download stages `<name>.unidrive-tmp` (13 characters) or `<name>.hydrating-<uuid>`
 * (48) beside its destination — a name near the 255-byte component limit cannot take the suffix
 * and failed every download. The helper keeps the suffix (sweepers match `*.unidrive-tmp` /
 * `*.hydrating-*`) and shortens the prefix instead.
 */
class StagingNamesTest {
    @Test
    fun `a short name takes the suffix as before`() {
        assertEquals("file.txt.unidrive-tmp", stagingSiblingName("file.txt", ".unidrive-tmp"))
    }

    @Test
    fun `a 255-character name falls back to a short sibling that keeps the suffix`() {
        val name = "d2-" + "y".repeat(248) + ".txt" // the live case: exactly 255 characters
        assertEquals(255, name.length)

        val staged = stagingSiblingName(name, ".unidrive-tmp")

        assertTrue(staged.length <= 255, "the staged name must fit the component limit; got ${staged.length}")
        assertTrue(staged.endsWith(".unidrive-tmp"), "sweepers of *.unidrive-tmp must still match: $staged")
        assertTrue(staged.startsWith(".ud-"), "the short form is recognisable: $staged")
        assertNotEquals(name, staged)
    }

    @Test
    fun `the fallback is unique per call so concurrent long-name downloads do not collide`() {
        val name = "y".repeat(250)
        val a = stagingSiblingName(name, ".unidrive-tmp")
        val b = stagingSiblingName(name, ".unidrive-tmp")
        assertNotEquals(a, b, "two concurrent stagings of the same long name must not share a temp")
    }

    @Test
    fun `the limit is counted in utf-8 bytes, not characters`() {
        // 130 two-byte characters = 260 bytes: over the limit at 143 characters, like ext4 counts.
        val name = "ä".repeat(130)
        val staged = stagingSiblingName(name, ".unidrive-tmp")
        assertTrue(staged.startsWith(".ud-"), "a name whose byte count crosses 255 must fall back: $staged")
        assertTrue(staged.toByteArray(Charsets.UTF_8).size <= 255)
    }

    @Test
    fun `the hydrating suffix with its uuid works in both forms`() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals("file.bin.hydrating-$uuid", stagingSiblingName("file.bin", ".hydrating-$uuid"))

        val long = "z".repeat(210)
        val staged = stagingSiblingName(long, ".hydrating-$uuid")
        assertTrue(staged.length <= 255, "got ${staged.length}")
        assertTrue(staged.contains(".hydrating-"), "the daemon-start sweeper matches *.hydrating-*: $staged")
    }
}
