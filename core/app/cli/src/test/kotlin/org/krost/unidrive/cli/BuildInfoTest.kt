package org.krost.unidrive.cli

import kotlin.test.*

class BuildInfoTest {
    @Test
    fun `COMMIT is not blank`() {
        assertTrue(BuildInfo.COMMIT.isNotBlank(), "BuildInfo.COMMIT must not be blank")
    }
}
