package org.krost.unidrive.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * #560 U6 (cutover): a mirror `sync` constructs no hydration runtime — no `HydrationImpl`, no
 * hydration verbs on its IPC server, no hydration event fan-out, no hydration upload slots. The
 * predecessor of this test pinned the coordinated wiring it took to build; this one pins its
 * absence, the lightest check that fails if the coordination ever creeps back.
 * The test task runs in the module directory (see SyncFullTreeRefusalSmokeTest).
 */
class SyncCommandNoHydrationWiringTest {
    private val source: String =
        File("src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt").readText()

    @Test
    fun `sync - a mirror profile - constructs no hydration runtime`() {
        assertFalse(source.contains("HydrationImpl("), "SyncCommand must not build a HydrationImpl")
        assertFalse(source.contains("HydrationIpcHandler"), "SyncCommand must not register hydration verbs")
        assertFalse(source.contains("hydrationRef"), "SyncCommand must not keep a hydration upload-slot hook")
    }
}
