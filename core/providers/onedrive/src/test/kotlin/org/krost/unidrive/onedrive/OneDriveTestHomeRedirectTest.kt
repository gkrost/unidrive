package org.krost.unidrive.onedrive

import org.krost.unidrive.io.defaultTokenPath
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every test JVM must run with its home redirected into the build directory
 * (the `Test` task config in the root build), so a config built with defaults —
 * `OneDriveConfig()`'s `defaultTokenPath("onedrive")` — reads and writes
 * `build/test-home/.config/unidrive/onedrive`, never the developer's real
 * profile directory: a test run must not reset a real delta cursor (#453).
 */
class OneDriveTestHomeRedirectTest {
    @Test
    fun `the default token path stays inside the test home redirect`() {
        val redirect =
            assertNotNull(
                System.getProperty("unidrive.test.home"),
                "unidrive.test.home is not set: this test JVM ran without the HOME redirect, " +
                    "so defaults would touch the real ~/.config/unidrive/onedrive",
            )
        assertTrue(
            defaultTokenPath("onedrive").startsWith(Path.of(redirect)),
            "the default token path must stay inside the redirect '$redirect'",
        )
    }
}
