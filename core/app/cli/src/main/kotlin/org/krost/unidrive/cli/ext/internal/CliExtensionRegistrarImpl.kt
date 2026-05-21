package org.krost.unidrive.cli.ext.internal

import org.krost.unidrive.cli.ext.CliExtensionRegistrar
import org.krost.unidrive.cli.ext.CliServices
import picocli.CommandLine

/**
 * Registrar implementation handed to a single [org.krost.unidrive.cli.ext.CliExtension]
 * during its `register()` call. Scoped to one extension so that
 * collision messages can name the offending extension ids.
 */
internal class CliExtensionRegistrarImpl(
    private val root: CommandLine,
    override val services: CliServices,
    private val extensionId: String,
) : CliExtensionRegistrar {
    override fun addSubcommand(
        parentCommandName: String,
        command: Any,
    ) {
        val parent =
            resolveParent(parentCommandName)
                ?: throw IllegalStateException(
                    "Extension '$extensionId': unknown parent command '$parentCommandName'",
                )
        val cmdName = extractCommandName(command)
        val previousOwner = ownerOf(parent, cmdName)
        if (previousOwner != null) {
            throw IllegalStateException(
                "Extension '$extensionId': subcommand '$cmdName' under '${parent.commandName}' " +
                    "is already registered by extension '$previousOwner'",
            )
        }
        parent.addSubcommand(cmdName, command)
        ownership[qualify(parent, cmdName)] = extensionId
    }

    private fun resolveParent(name: String): CommandLine? = if (name.isEmpty()) root else root.subcommands[name]

    private fun extractCommandName(command: Any): String {
        val ann =
            command::class.java.getAnnotation(picocli.CommandLine.Command::class.java)
                ?: error("Command class ${command::class.qualifiedName} missing @Command annotation")
        return ann.name.ifEmpty {
            error("Command class ${command::class.qualifiedName} has empty @Command(name=\"\")")
        }
    }

    private fun ownerOf(
        parent: CommandLine,
        cmdName: String,
    ): String? {
        if (!parent.subcommands.containsKey(cmdName)) return null
        return ownership[qualify(parent, cmdName)] ?: "unidrive (built-in)"
    }

    private fun qualify(
        parent: CommandLine,
        cmdName: String,
    ) = "${parent.commandName}/$cmdName"

    companion object {
        /** Shared ownership map across all extension registrars. */
        private val ownership = mutableMapOf<String, String>()

        /** Test-only: reset ownership between tests. Visible for package. */
        internal fun resetForTesting() {
            ownership.clear()
        }
    }
}
