package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand

@Command(name = "quota", description = ["Show storage quota"], mixinStandardHelpOptions = true)
class QuotaCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        val provider = parent.createProvider()
        try {
            runBlocking {
                provider.authenticate()
                val quota = provider.quota()
                println("Storage Quota (${provider.displayName}):")
                println("  Used:      ${CliProgressReporter.formatSize(quota.used)}")
                println("  Total:     ${CliProgressReporter.formatSize(quota.total)}")
                println("  Remaining: ${CliProgressReporter.formatSize(quota.remaining)}")
            }
        } catch (e: AuthenticationException) {
            parent.handleAuthError(e, provider)
        }
    }
}
