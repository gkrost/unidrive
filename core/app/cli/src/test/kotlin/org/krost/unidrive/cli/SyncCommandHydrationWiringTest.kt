package org.krost.unidrive.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #560 U1: `unidrive sync` is also a hydration server today — SyncCommand builds a HydrationImpl on its engine,
 * registers every `HydrationIpcHandler.VERBS` verb on its IPC server and wires the engine's `uploadInFlight` hook to
 * it. Running the command needs a provider, a sync root and the per-user IPC socket and data directories, so this
 * pins the wiring in the source instead: the lightest check that fails if an extraction drops it by accident.
 * #560 U4 removes this wiring on purpose (a mirror `sync` no longer constructs hydration) and deletes this test.
 */
class SyncCommandHydrationWiringTest {
    // The test task runs in the module directory (see SyncFullTreeRefusalSmokeTest).
    private val source: String =
        File("src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt").readText()

    private fun assertWired(
        pattern: String,
        what: String,
    ) {
        assertTrue(Regex(pattern).containsMatchIn(source), "SyncCommand no longer $what (pattern: $pattern)")
    }

    @Test
    fun `current wiring - sync constructs HydrationImpl on its engine and registers every hydration verb`() {
        assertWired("""HydrationImpl\(\s*(syncEngine\s*=\s*)?engine,\s*(stateDb\s*=\s*)?db,""", "builds HydrationImpl(engine, db, ...)")
        assertWired(
            """for \(verb in HydrationIpcHandler\.VERBS\)\s*\{\s*ipcServer\.registerHandler\(verb\)""",
            "registers HydrationIpcHandler.VERBS",
        )
        assertWired("""hydration\.events\.collect\s*\{\s*hydrationIpc\.dispatchEvent""", "fans hydration events out")
    }

    @Test
    fun `current wiring - the sync engine's uploadInFlight asks the hydration layer`() {
        assertWired("""uploadInFlight\s*=\s*\{\s*path\s*->\s*hydrationRef\?\.hasUploadSlot\(path\)""", "late-binds uploadInFlight")
    }
}
