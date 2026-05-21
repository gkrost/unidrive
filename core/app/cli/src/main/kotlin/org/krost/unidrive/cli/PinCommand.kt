package org.krost.unidrive.cli

import org.krost.unidrive.sync.StateDatabase
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec

@Command(name = "pin", description = ["Add eager-download rule"], mixinStandardHelpOptions = true)
class PinCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", description = ["Glob pattern to pin"])
    lateinit var pattern: String

    override fun run() {
        if (pattern.isEmpty()) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "glob pattern must not be empty",
            )
        }
        val lock = parent.acquireProfileLock()
        try {
            val dbPath = parent.providerConfigDir().resolve("state.db")
            val db = StateDatabase(dbPath)
            db.initialize()
            db.addPinRule(pattern, pinned = true)
            println("Pinned: $pattern (will download on next sync)")
            db.close()
        } finally {
            lock.unlock()
        }
    }
}

@Command(name = "unpin", description = ["Remove eager-download rule"], mixinStandardHelpOptions = true)
class UnpinCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", description = ["Glob pattern to unpin"])
    lateinit var pattern: String

    override fun run() {
        if (pattern.isEmpty()) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "glob pattern must not be empty",
            )
        }
        val lock = parent.acquireProfileLock()
        try {
            val dbPath = parent.providerConfigDir().resolve("state.db")
            val db = StateDatabase(dbPath)
            db.initialize()
            db.removePinRule(pattern)
            println("Unpinned: $pattern")
            db.close()
        } finally {
            lock.unlock()
        }
    }
}
