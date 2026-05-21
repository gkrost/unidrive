package org.krost.unidrive.cli

import java.time.Instant
import kotlin.test.*

class BuildInfoTest {
    @Test
    fun `COMMIT is not blank`() {
        assertTrue(BuildInfo.COMMIT.isNotBlank(), "BuildInfo.COMMIT must not be blank")
    }

    // UD-733: BuildInfo also surfaces DIRTY + BUILD_INSTANT so the startup
    // banner can WARN on uncommitted builds and anchor every log to a build
    // instant. Tests pin the contract — generated file is overwritten on
    // every build, so a typo in the generator would break this until next
    // commit cycle.

    @Test
    fun `UD-733 - BUILD_INSTANT parses as a valid ISO-8601 instant`() {
        // Throws DateTimeParseException if generator emits a malformed string.
        Instant.parse(BuildInfo.BUILD_INSTANT)
    }

    @Test
    fun `UD-733 - versionString includes -dirty suffix when DIRTY=true`() {
        if (BuildInfo.DIRTY) {
            assertTrue(
                BuildInfo.versionString().contains("-dirty"),
                "DIRTY=true builds must surface '-dirty' in versionString; was '${BuildInfo.versionString()}'",
            )
        } else {
            assertTrue(
                !BuildInfo.versionString().contains("-dirty"),
                "DIRTY=false builds must NOT include '-dirty' in versionString; was '${BuildInfo.versionString()}'",
            )
        }
    }

    @Test
    fun `UD-733 - versionString embeds COMMIT`() {
        assertTrue(
            BuildInfo.versionString().contains(BuildInfo.COMMIT),
            "versionString must contain the COMMIT short SHA; was '${BuildInfo.versionString()}'",
        )
    }
}
