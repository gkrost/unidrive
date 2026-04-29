package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.onedrive.OneDriveProvider
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand

@Command(name = "auth", description = ["Authenticate with a cloud provider"], mixinStandardHelpOptions = true)
class AuthCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["-d", "--device-code"], description = ["Use device code flow (no browser required, OneDrive only)"])
    var deviceCode: Boolean = false

    override fun run() {
        val lock = parent.acquireProfileLock()
        val provider = parent.createProvider()
        try {
            runBlocking {
                if (deviceCode && provider is OneDriveProvider) {
                    provider.authenticateWithDeviceCode()
                } else {
                    if (deviceCode && provider !is OneDriveProvider) {
                        println("Note: Device code flow is only available for OneDrive. Using browser flow.")
                    }
                    provider.authenticate()
                }
            }
            println("Authenticated to ${provider.displayName}")
        } catch (e: AuthenticationException) {
            parent.handleAuthError(e, provider)
        } finally {
            lock.unlock()
        }
    }
}
