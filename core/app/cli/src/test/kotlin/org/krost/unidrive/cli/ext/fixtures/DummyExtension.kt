package org.krost.unidrive.cli.ext.fixtures

import org.krost.unidrive.cli.ext.CliExtension
import org.krost.unidrive.cli.ext.CliExtensionRegistrar
import picocli.CommandLine.Command

@Command(name = "dummy", description = ["Test-only extension subcommand."])
class DummyCommand : Runnable {
    override fun run() {}
}

class DummyExtension : CliExtension {
    override val id = "dummy"

    override fun register(registrar: CliExtensionRegistrar) {
        registerCalls += 1
        registrar.addSubcommand("", DummyCommand())
    }

    companion object {
        /** Observable — tests assert register() was invoked exactly once. */
        @Volatile var registerCalls: Int = 0
    }
}
