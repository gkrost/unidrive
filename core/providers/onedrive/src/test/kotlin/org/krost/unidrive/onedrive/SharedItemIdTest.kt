package org.krost.unidrive.onedrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharedItemIdTest {
    @Test
    fun `the parts of a shared id survive a colon or a percent sign`() {
        val ref = SharedItemRef("drive:one%", "item:two%3A")

        assertEquals(ref, SharedItemId.parse(ref.id))
    }

    @Test
    fun `an id of the own drive is not a shared id`() {
        assertEquals(null, SharedItemId.parse("01ABCDEF!123"))
    }

    @Test
    fun `a shared id with a missing part is rejected as a missing item`() {
        for (bad in listOf("shared:onlyone", "shared::item", "shared:drive:", "shared:a:b:c")) {
            assertFailsWith<GraphApiException>(bad) { SharedItemId.parse(bad) }
        }
    }
}
