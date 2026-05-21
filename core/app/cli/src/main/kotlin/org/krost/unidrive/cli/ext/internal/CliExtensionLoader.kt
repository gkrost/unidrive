package org.krost.unidrive.cli.ext.internal

import org.krost.unidrive.cli.ext.CliExtension
import org.krost.unidrive.cli.ext.CliServices
import picocli.CommandLine
import java.util.ServiceLoader

/**
 * Discovers [CliExtension] implementations on the classpath via
 * [ServiceLoader] and registers them against the given picocli root.
 *
 * A broken extension logs to stderr and is skipped — it never prevents
 * CLI startup.
 */
internal object CliExtensionLoader {
    fun loadInto(
        root: CommandLine,
        services: CliServices,
    ) {
        val discovered = ServiceLoader.load(CliExtension::class.java).toList()
        registerAll(root, services, discovered)
    }

    internal fun registerAll(
        root: CommandLine,
        services: CliServices,
        extensions: List<CliExtension>,
    ) {
        for (ext in extensions) {
            val registrar = CliExtensionRegistrarImpl(root, services, ext.id)
            try {
                ext.register(registrar)
            } catch (t: Throwable) {
                System.err.println(
                    "Extension '${ext.id}' failed to register: ${t.message ?: t.javaClass.simpleName}",
                )
            }
        }
    }
}
