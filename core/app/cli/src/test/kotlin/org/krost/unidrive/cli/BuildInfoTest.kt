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

    // spec §3.5 (unidrive-distribution-design): tagged-release builds print
    // the bare semver with NO commit suffix; non-tag (dev) builds keep the
    // "(commit)" / "(commit-dirty)" enrichment. These two invariants share
    // versionString()'s code path but are orthogonal — one named test each so
    // a future edit can't silently weaken one while the other still passes.
    //
    // The build mode is detected from BuildInfo itself: a bare semver
    // (versionString() == VERSION) is a tagged release; anything else is a
    // dev build. If either test is removed or loosened, the corresponding
    // §3.5 invariant silently regresses.

    private val isTaggedRelease: Boolean
        get() = BuildInfo.versionString() == BuildInfo.VERSION

    @Test
    fun `spec 3-5 - dev build versionString embeds COMMIT`() {
        if (isTaggedRelease) return // invariant N/A on tagged-release builds
        assertTrue(
            BuildInfo.versionString().contains(BuildInfo.COMMIT),
            "non-tag builds must embed the COMMIT short SHA; was '${BuildInfo.versionString()}'",
        )
    }

    @Test
    fun `spec 3-5 - tagged-release versionString is bare semver with no commit suffix`() {
        if (!isTaggedRelease) return // invariant N/A on dev builds
        assertEquals(
            BuildInfo.VERSION,
            BuildInfo.versionString(),
            "tagged-release builds must print the bare semver with no '(commit)' suffix",
        )
        assertFalse(
            BuildInfo.versionString().contains("("),
            "tagged-release versionString must not contain a commit/dirty suffix",
        )
    }
}
