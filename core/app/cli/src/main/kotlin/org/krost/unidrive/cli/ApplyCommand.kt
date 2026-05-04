package org.krost.unidrive.cli

import picocli.CommandLine
import picocli.CommandLine.Command

/**
 * UD-236: git-style three-verb model.
 *
 * `unidrive apply` skips the remote Gather phase (no `provider.delta()` call) and
 * drains whatever pending transfers the previous `unidrive refresh` (or any prior
 * interrupted sync) left in state.db. The Reconciler's UD-225 / UD-901 recovery
 * loops surface unhydrated rows (download pending) and `remoteId=null` rows
 * (upload pending) as DownloadContent / Upload actions, then Pass 1 + Pass 2
 * execute as usual.
 *
 * No-op when the DB has no pending entries — apply is safe to run repeatedly.
 *
 * Trade-off: apply does NOT see remote changes that landed since the last
 * refresh. If you want fresh remote state too, run `unidrive sync` (which is
 * refresh + apply combined).
 */
@Command(
    name = "apply",
    description = [
        "Drain pending transfers from a prior `refresh` (UD-236).",
        "Skips remote gather; uses local scan + recovery loops to surface pending downloads/uploads.",
    ],
    mixinStandardHelpOptions = true,
)
class ApplyCommand : SyncCommand() {
    override var skipTransfers: Boolean = false
    override var skipRemoteGather: Boolean = true

    override fun run() {
        if (watch) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--watch is not supported with `apply`. Use `unidrive sync --watch` for the combined continuous loop.",
            )
        }
        if (reset) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--reset is not valid with `apply`: a fresh DB has nothing pending to apply. Run `unidrive sync --reset` instead.",
            )
        }
        super.run()
    }
}
