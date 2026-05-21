package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CapabilityResult
import picocli.CommandLine
import picocli.CommandLine.ArgGroup
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec

@Command(name = "share", description = ["Generate a shareable link for a file or folder"], mixinStandardHelpOptions = true)
class ShareCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Parameters(index = "0", description = ["Remote path to share"])
    lateinit var path: String

    @Option(names = ["-e", "--expiry"], description = ["Link expiry in hours (default: 24)"], defaultValue = "24")
    var expiryHours: Int = 24

    @Option(names = ["--password"], description = ["Password-protect the share link"])
    var password: String? = null

    // UD-243: list and revoke are distinct modes — picking both is
    // contradictory. @ArgGroup(exclusive = true) rejects the combination at
    // parse time with exit 2 instead of silently letting --list win in the
    // when-branch below.
    @ArgGroup(exclusive = true, multiplicity = "0..1")
    var mode: Mode? = null

    class Mode {
        @Option(names = ["--list"], description = ["List active share links"])
        var listShares: Boolean = false

        @Option(names = ["--revoke"], description = ["Revoke a share link by ID"])
        var revokeId: String? = null
    }

    val listShares: Boolean
        get() = mode?.listShares == true

    val revokeId: String?
        get() = mode?.revokeId

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        if (expiryHours <= 0) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--expiry must be > 0 (got $expiryHours)",
            )
        }
        runBlocking {
            val provider =
                try {
                    parent.createProvider()
                } catch (e: AuthenticationException) {
                    parent.handleAuthError(e, parent.createProvider())
                    return@runBlocking
                } catch (e: Exception) {
                    System.err.println("Error: ${e.message}")
                    System.exit(1)
                    return@runBlocking
                }

            try {
                when {
                    listShares -> {
                        when (val result = provider.listShares(path)) {
                            is CapabilityResult.Success -> {
                                val shares = result.value
                                if (shares.isEmpty()) {
                                    println("No active share links for: $path")
                                } else {
                                    println("%-36s  %-6s  %-14s  %-4s  %-20s  %s".format("ID", "Type", "Scope", "Pwd", "Expiry", "URL"))
                                    println("-".repeat(120))
                                    for (s in shares) {
                                        println(
                                            "%-36s  %-6s  %-14s  %-4s  %-20s  %s".format(
                                                s.id,
                                                s.type,
                                                s.scope,
                                                if (s.hasPassword) "yes" else "no",
                                                s.expiration ?: "-",
                                                s.url,
                                            ),
                                        )
                                    }
                                }
                            }
                            is CapabilityResult.Unsupported -> {
                                System.err.println("Unsupported: ${result.reason}")
                                System.exit(1)
                            }
                        }
                    }
                    revokeId != null -> {
                        when (val result = provider.revokeShare(path, revokeId!!)) {
                            is CapabilityResult.Success -> println("Share link revoked: $revokeId")
                            is CapabilityResult.Unsupported -> {
                                System.err.println("Unsupported: ${result.reason}")
                                System.exit(1)
                            }
                        }
                    }
                    else -> {
                        when (val result = provider.share(path, expiryHours, password)) {
                            is CapabilityResult.Success -> println(result.value)
                            is CapabilityResult.Unsupported -> {
                                System.err.println("Unsupported: ${result.reason}")
                                System.exit(1)
                            }
                        }
                    }
                }
            } catch (e: AuthenticationException) {
                parent.handleAuthError(e, provider)
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}")
                System.exit(1)
            }
        }
    }
}
