package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProfileMode
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

/** Runs the long-lived process appropriate for the selected profile's fixed mode. */
@Command(
    name = "autostart",
    description = ["Run the selected profile's mirror watcher or mount daemon"],
    mixinStandardHelpOptions = true,
)
class AutostartCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: Main

    override fun call(): Int {
        val profile = parent.resolveCurrentProfile()
        val mode = parent.requireAnyProfileMode(profile, "autostart")
        return CommandLine(Main()).execute(
            *autostartArguments(mode, profile.name, parent.configDir, parent.verbose).toTypedArray(),
        )
    }
}

internal fun autostartArguments(
    mode: ProfileMode,
    profileName: String,
    configDir: String?,
    verbose: Boolean,
): List<String> = buildList {
    configDir?.let {
        add("--config-dir")
        add(it)
    }
    add("--provider")
    add(profileName)
    if (verbose) add("--verbose")
    when (mode) {
        ProfileMode.MOUNT -> addAll(listOf("daemon", "run"))
        ProfileMode.MIRROR -> addAll(listOf("sync", "--watch"))
    }
}
