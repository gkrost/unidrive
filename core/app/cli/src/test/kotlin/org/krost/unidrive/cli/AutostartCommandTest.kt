package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProfileMode
import kotlin.test.Test
import kotlin.test.assertEquals

class AutostartCommandTest {
    @Test
    fun `mount profile starts daemon with its profile and config directory`() {
        assertEquals(
            listOf("--config-dir", "/tmp/unidrive-config", "--provider", "work", "--verbose", "daemon", "run"),
            autostartArguments(ProfileMode.MOUNT, "work", "/tmp/unidrive-config", verbose = true),
        )
    }

    @Test
    fun `mirror profile starts continuous sync without inventing a profile config directory`() {
        assertEquals(
            listOf("--provider", "personal", "sync", "--watch"),
            autostartArguments(ProfileMode.MIRROR, "personal", configDir = null, verbose = false),
        )
    }
}
