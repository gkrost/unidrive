package org.krost.unidrive.cli

import picocli.CommandLine
import picocli.CommandLine.Command

/**
 * UD-236: git-style three-verb model.
 *
 * `unidrive refresh` runs the Gather + Reconcile + Pass-1-metadata phases of a
 * sync but skips the byte-transfer Pass 2. Result: state.db reflects the latest
 * remote view, conflicts are resolved, deletes / moves / placeholder ops have
 * happened — but downloads and uploads stay PENDING. Pending transfers persist
 * as the existing UD-901 (`remoteId=null`) and UD-225 (`isHydrated=false`) DB
 * rows; a follow-up `unidrive apply` (or any plain `unidrive sync`) drains
 * them via the recovery loops in `Reconciler.reconcile`.
 *
 * Useful when you want to know "what would sync do?" and inspect the plan
 * without committing wall-clock to byte transfers — the ETA before pulling
 * trigger.
 */
@Command(
    name = "refresh",
    description = [
        "Update state.db with remote changes; defer byte transfers (UD-236).",
        "Runs Gather + Reconcile + Pass-1 metadata. Pending transfers carry over to the next `apply` or `sync`.",
    ],
    mixinStandardHelpOptions = true,
)
class RefreshCommand : SyncCommand() {
    override var skipTransfers: Boolean = true
    override var skipRemoteGather: Boolean = false

    override fun run() {
        if (watch) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--watch is not supported with `refresh`. Use `unidrive sync --watch` for the combined continuous loop.",
            )
        }
        super.run()
    }
}
