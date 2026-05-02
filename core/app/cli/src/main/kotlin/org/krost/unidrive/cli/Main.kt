package org.krost.unidrive.cli

import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.RawSyncConfig
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.Vault
import org.slf4j.MDC
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Command(
    name = "unidrive",
    description = ["UniDrive - unified cloud storage client"],
    mixinStandardHelpOptions = true,
    versionProvider = Main.VersionProvider::class,
    subcommands = [
        AuthCommand::class,
        LogoutCommand::class,
        SyncCommand::class,
        SweepCommand::class,
        StatusCommand::class,
        GetCommand::class,
        LsCommand::class,
        FreeCommand::class,
        PinCommand::class,
        UnpinCommand::class,
        QuotaCommand::class,
        LogCommand::class,
        ProfileCommand::class,
        VaultCommand::class,
        ConflictsCommand::class,
        ProviderCommand::class,
        RelocateCommand::class,
        ShareCommand::class,
        TrashCommand::class,
        VersionsCommand::class,
        BackupCommand::class,
    ],
)
class Main : Runnable {
    @Option(names = ["-c", "--config-dir"], description = ["Config directory path"])
    var configDir: String? = null

    // UD-252: no picocli-level defaultValue. When the user omits -p we resolve
    // the default via [SyncConfig.resolveDefaultProfile] in [resolveCurrentProfile]
    // so the CLI honours [general] default_profile from config.toml. The MCP jar
    // uses the same resolver, keeping the two invocation surfaces in lock-step.
    @Option(names = ["-p", "--provider"], description = ["Provider profile name or type (see 'provider list')"])
    var provider: String? = null

    // UD-271: scope = INHERIT propagates -v / --verbose down to every subcommand
    // and sub-subcommand spec at picocli parse time. Pre-fix `unidrive auth -v`
    // and `unidrive relocate --from X -v` rejected the flag with a misleading
    // "Possible solutions: --version" hint that pointed users at a worse bug
    // (silent --version exit on subcommands). With INHERIT the same boolean
    // field receives the value regardless of where on the command line it
    // appears, so existing `parent.verbose` consumers (SyncCommand) keep working.
    @Option(
        names = ["-v", "--verbose"],
        description = ["Verbose output"],
        scope = CommandLine.ScopeType.INHERIT,
    )
    var verbose: Boolean = false

    @Spec
    lateinit var spec: CommandSpec

    private val baseConfigDir: Path
        get() {
            if (configDir != null) return Paths.get(configDir!!)
            val appData = System.getenv("APPDATA")
            return if (appData != null) {
                Paths.get(appData, "unidrive")
            } else {
                Paths.get(System.getenv("HOME") ?: System.getProperty("user.home"), ".config", "unidrive")
            }
        }

    private var _profile: ProfileInfo? = null
    private var _vaultData: Map<String, Map<String, String>>? = null

    /**
     * UD-211: clear the memoised profile + vault caches so the next call to
     * [resolveCurrentProfile] / [loadVaultData] re-parses against the current
     * [provider]. [org.krost.unidrive.cli.ext.internal.CliServicesImpl.withProfile]
     * is the primary caller; any future multi-profile-per-JVM code path must
     * invoke this on provider mutation or it will read stale data from the
     * first profile ever resolved in this JVM.
     */
    fun invalidateProfileCaches() {
        _profile = null
        _vaultData = null
    }

    fun resolveCurrentProfile(): ProfileInfo {
        if (_profile != null) return _profile!!
        val configFile = baseConfigDir.resolve("config.toml")
        val configText = if (Files.exists(configFile)) Files.readString(configFile) else "[general]\n"
        val raw = SyncConfig.parseRaw(configText)
        // UD-242: unified config-missing wording. See `reportConfigMissing` — one line for
        // normal users, plus an 8-line diagnostic block gated behind -v/--verbose for
        // diagnosing MSIX sandboxing, broken symlinks, and similar filtered-FS issues
        // (originally UD-219 diagnostic scaffolding, now opt-in).
        if (raw.providers.isEmpty()) {
            reportConfigMissingAndExit(configFile)
        }
        val dupError = SyncConfig.detectDuplicateSyncRoots(raw)
        if (dupError != null) {
            System.err.println("Error: $dupError")
            System.exit(1)
        }

        // UD-252: fall back to the shared default resolver when -p was omitted.
        // The resolver honours `[general] default_profile` and otherwise returns
        // "onedrive" (the historical CLI default). Keeping the fallback in one
        // place guarantees CLI and MCP jars resolve identically.
        val profileName = provider ?: SyncConfig.resolveDefaultProfile(baseConfigDir)
        _profile =
            try {
                SyncConfig.resolveProfile(profileName, raw)
            } catch (e: IllegalArgumentException) {
                // UD-237: when a bare name doesn't match, see if the user typed a
                // provider TYPE (e.g. `-p Internxt`) and there's exactly one
                // configured profile of that type — auto-select it + emit a
                // one-line "matched by type" notice so they know what happened.
                val byType = tryResolveByType(profileName, raw)
                if (byType != null) {
                    System.err.println("Using profile '${byType.name}' (matched by type '$profileName')")
                    byType
                } else {
                    System.err.println(e.message)
                    val typed = profilesOfType(profileName, raw)
                    if (typed.size > 1) {
                        System.err.println(
                            "Profiles of type '$profileName': ${typed.joinToString(", ")}",
                        )
                    }
                    System.exit(1)
                    throw e
                }
            }
        // UD-212: stamp the SLF4J MDC so every log line in this process carries the active
        // profile. `logback.xml` pulls this via `%X{profile:-*}`. For coroutines spawned under
        // runBlocking, the SyncCommand runBlocking wraps itself in MDCContext() to propagate
        // the MDC across dispatcher workers.
        MDC.put("profile", _profile!!.name)
        return _profile!!
    }

    /**
     * UD-242: unified "no unidrive config" error surface used by every subcommand that
     * requires a config.toml. Prints a single actionable line by default; `-v`/`--verbose`
     * appends the legacy 8-line diagnostic dump (APPDATA / HOME / Files.exists / Files.size
     * plus the MSIX-sandbox hint) that used to always print.
     *
     * Does not return — calls [System.exit] with code 1.
     */
    fun reportConfigMissingAndExit(configFile: Path): Nothing {
        // UD-235: query parent-process command so the renderer can surface the
        // MSIX-sandbox hint when the caller is a packaged-app child.
        val parentCmd =
            try {
                ProcessHandle
                    .current()
                    .parent()
                    .flatMap { it.info().command() }
                    .orElse(null)
            } catch (_: Exception) {
                null
            }
        System.err.print(renderConfigMissingMessage(configFile, verbose, parentCmd))
        System.exit(1)
        throw IllegalStateException("unreachable")
    }

    private fun loadVaultData(): Map<String, Map<String, String>> {
        if (_vaultData != null) return _vaultData!!
        val vault = Vault(baseConfigDir.resolve("vault.enc"))
        if (!vault.exists()) {
            _vaultData = emptyMap()
            return _vaultData!!
        }
        val envPass = System.getenv("UNIDRIVE_VAULT_PASS")
        val passphrase =
            if (envPass != null) {
                envPass.toCharArray()
            } else {
                val console = System.console()
                if (console == null) {
                    // Headless mode (systemd daemon, piped input): skip vault, use config as-is
                    System.err.println("Warning: vault.enc exists but no interactive terminal.")
                    System.err.println("Credentials from vault will not be available.")
                    System.err.println("Set UNIDRIVE_VAULT_PASS env var for non-interactive vault access.")
                    _vaultData = emptyMap()
                    return _vaultData!!
                }
                console.readPassword("Vault passphrase: ") ?: run {
                    System.err.println("Error: could not read passphrase.")
                    System.exit(1)
                    throw IllegalStateException("unreachable")
                }
            }
        _vaultData =
            try {
                vault.read(passphrase)
            } catch (e: javax.crypto.AEADBadTagException) {
                System.err.println("Error: Wrong vault passphrase.")
                System.exit(1)
                throw IllegalStateException("unreachable")
            } catch (e: java.security.GeneralSecurityException) {
                System.err.println("Error: Vault decryption failed: ${e.message}")
                System.exit(1)
                throw IllegalStateException("unreachable")
            }
        return _vaultData!!
    }

    private fun mergeVaultCreds(profile: ProfileInfo): RawProvider? {
        val rp = profile.rawProvider
        val vaultCreds = loadVaultData()[profile.name] ?: return rp
        if (rp == null) {
            return RawProvider(
                access_key_id = vaultCreds["access_key_id"],
                secret_access_key = vaultCreds["secret_access_key"],
                password = vaultCreds["password"],
                client_id = vaultCreds["client_id"],
                client_secret = vaultCreds["client_secret"],
            )
        }
        return rp.copy(
            access_key_id = rp.access_key_id ?: vaultCreds["access_key_id"],
            secret_access_key = rp.secret_access_key ?: vaultCreds["secret_access_key"],
            password = rp.password ?: vaultCreds["password"],
            client_id = rp.client_id ?: vaultCreds["client_id"],
            client_secret = rp.client_secret ?: vaultCreds["client_secret"],
        )
    }

    fun createProvider(): CloudProvider {
        val profile = resolveCurrentProfile()
        val tokenPath = baseConfigDir.resolve(profile.name)
        val rp = mergeVaultCreds(profile)
        return createProviderFrom(profile.type, rp, tokenPath)
    }

    fun resolveProfile(name: String): ProfileInfo? {
        val configFile = baseConfigDir.resolve("config.toml")
        val raw =
            if (Files.exists(configFile)) {
                SyncConfig.parseRaw(Files.readString(configFile))
            } else {
                SyncConfig.parseRaw("[general]\n")
            }
        val resolved = SyncConfig.resolveProfile(name, raw)
        return resolved
    }

    fun createProviderFor(
        profile: ProfileInfo,
        configDir: Path,
    ): CloudProvider {
        val tokenPath = configDir
        val rp =
            mergeVaultCreds(profile)
                ?: throw IllegalStateException("Profile '${profile.name}' has no provider configuration")
        return createProviderFrom(profile.type, rp, tokenPath)
    }

    private fun createProviderFrom(
        type: String,
        rp: RawProvider?,
        tokenPath: Path,
    ): CloudProvider {
        val factory =
            ProviderRegistry.get(type)
                ?: run {
                    System.err.println("Unknown provider type: $type. Supported: ${ProviderRegistry.knownTypes.joinToString(", ")}")
                    System.exit(1)
                    throw IllegalArgumentException("Unknown provider type: $type")
                }

        val properties = rawProviderToProperties(rp)
        val envWarnings = getEnvWarnings(type, properties)
        for (warn in envWarnings) {
            System.err.println(warn)
        }

        if (verbose) {
            printVerboseProviderInfo(factory, properties, tokenPath)
        }

        return try {
            factory.create(properties, tokenPath)
        } catch (e: org.krost.unidrive.ConfigurationException) {
            System.err.println("Configuration error: ${e.message}")
            System.exit(1)
            throw e
        }
    }

    private fun printVerboseProviderInfo(
        factory: ProviderFactory,
        properties: Map<String, String?>,
        profileDir: Path,
    ) {
        val desc = factory.describeConnection(properties, profileDir)
        System.err.println("[verbose] Provider: $desc")
    }

    private fun rawProviderToProperties(rp: RawProvider?): Map<String, String?> {
        if (rp == null) return emptyMap()
        return mapOf(
            "bucket" to rp.bucket,
            "region" to rp.region,
            "endpoint" to rp.endpoint,
            "access_key_id" to rp.access_key_id,
            "secret_access_key" to rp.secret_access_key,
            "host" to rp.host,
            "port" to rp.port?.toString(),
            "user" to rp.user,
            "remote_path" to rp.remote_path,
            "root_path" to (rp.sync_root ?: rp.root_path), // localfs uses sync_root or root_path in config
            "identity" to rp.identity,
            "password" to rp.password,
            "client_id" to rp.client_id,
            "client_secret" to rp.client_secret,
            "url" to rp.url,
            "rclone_remote" to rp.rclone_remote,
            "rclone_path" to rp.rclone_path,
            "rclone_binary" to rp.rclone_binary,
            "rclone_config" to rp.rclone_config,
            "authority_url" to rp.authority_url,
            "trust_all_certs" to rp.trust_all_certs?.toString(),
        )
    }

    private fun getEnvWarnings(
        type: String,
        properties: Map<String, String?>,
    ): List<String> {
        val warnings = mutableListOf<String>()
        val envMappings = org.krost.unidrive.ProviderRegistry.get(type)?.envVarMappings() ?: emptyMap()
        for ((envVar, key) in envMappings) {
            if (System.getenv(envVar) != null && properties[key] != null) {
                warnings.add("Warning: Profile has credentials in config.toml. Ignoring $envVar environment variable.")
            }
        }
        return warnings
    }

    private fun requireEnv(
        name: String,
        usageBlock: String,
    ): String =
        System.getenv(name) ?: run {
            System.err.println("Missing required environment variable: $name\n")
            System.err.println(usageBlock)
            System.exit(1)
            throw IllegalStateException("unreachable")
        }

    private fun warnIfEnvSet(
        envName: String,
        profileName: String,
    ) {
        if (System.getenv(envName) != null) {
            System.err.println("Warning: Profile '$profileName' has credentials in config.toml.")
            System.err.println("Ignoring $envName environment variable. To use env vars instead,")
            System.err.println("remove the credentials from config.toml.\n")
        }
    }

    class VersionProvider : CommandLine.IVersionProvider {
        override fun getVersion(): Array<String> = arrayOf("unidrive ${BuildInfo.versionString()}")
    }

    companion object {
        val KNOWN_PROVIDERS: List<Pair<String, String>>
            get() = ProviderRegistry.all().map { it.id to it.metadata.displayName }

        val ONEDRIVE_USAGE =
            """
            |Microsoft OneDrive uses browser-based OAuth2 authentication.
            |No environment variables are required.
            |
            |Authenticate:
            |  unidrive -p onedrive auth
            |
            |A browser window will open for you to sign in with your Microsoft account.
            |Token stored at ~/.config/unidrive/onedrive/token.json
            """.trimMargin()

        val S3_USAGE =
            """
            |S3 provider requires the following environment variables:
            |
            |  S3_BUCKET              (required)  Bucket name
            |  AWS_ACCESS_KEY_ID      (required)  Access key ID
            |  AWS_SECRET_ACCESS_KEY  (required)  Secret access key
            |  S3_REGION              (optional)  Region, default: auto
            |  S3_ENDPOINT            (optional)  Custom endpoint URL (for non-AWS S3-compatible storage)
            |
            |Example (AWS):
            |  export S3_BUCKET=my-bucket
            |  export S3_REGION=eu-central-1
            |  export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            |  export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
            |  unidrive -p s3 auth
            |
            |Note: each S3 bucket is one account. For multiple accounts set different
            |environment variable sets and run separate unidrive invocations.
            """.trimMargin()

        val SFTP_USAGE =
            """
            |SFTP provider requires the following environment variables:
            |
            |  SFTP_HOST         (required)  Hostname or IP address
            |  SFTP_USER         (optional)  Username, default: current OS user
            |  SFTP_PORT         (optional)  Port, default: 22
            |  SFTP_REMOTE_PATH  (optional)  Remote base path, default: home directory
            |  SFTP_IDENTITY     (optional)  Path to private key file (default: ~/.ssh/id_ed25519 or id_rsa)
            |  SFTP_PASSWORD     (optional)  Password (prefer key-based auth)
            |
            |Example:
            |  export SFTP_HOST=myserver.example.com
            |  export SFTP_USER=alice
            |  export SFTP_REMOTE_PATH=/backups/unidrive
            |  unidrive -p sftp auth
            |
            |Note: for multiple SFTP accounts, set a different SFTP_HOST per invocation.
            """.trimMargin()

        val WEBDAV_USAGE =
            """
            |WebDAV provider requires the following environment variables:
            |
            |  WEBDAV_URL        (required)  Base URL of the WebDAV endpoint
            |                               e.g. https://cloud.example.com/remote.php/dav/files/alice
            |  WEBDAV_USER       (required)  Username
            |  WEBDAV_PASSWORD   (required)  Password or app token
            |
            |Example (Nextcloud):
            |  export WEBDAV_URL=https://nextcloud.example.com/remote.php/dav/files/alice
            |  export WEBDAV_USER=alice
            |  export WEBDAV_PASSWORD=app-token-here
            |  unidrive -p webdav auth
            |
            |Note: for multiple WebDAV accounts, set a different WEBDAV_URL per invocation.
            """.trimMargin()

        val RCLONE_USAGE =
            """
            |Rclone provider requires a configured rclone remote.
            |
            |  RCLONE_REMOTE  (required)  rclone remote name with colon (e.g. "gdrive:")
            |  RCLONE_PATH    (optional)  subpath within the remote
            |  RCLONE_BINARY  (optional)  path to rclone binary (default: "rclone")
            |  RCLONE_CONFIG  (optional)  path to rclone.conf
            |
            |Configure remotes with: rclone config
            |
            |Example:
            |  export RCLONE_REMOTE=gdrive:
            |  unidrive -p rclone auth
            """.trimMargin()
    }

    /** Print a friendly auth-error message and exit with code 1. */
    fun handleAuthError(
        e: AuthenticationException,
        provider: CloudProvider,
    ) {
        System.err.print(renderAuthError(e, provider.id, provider.displayName, verbose))
        System.exit(1)
    }

    /**
     * Render the auth-error output to a string (no I/O, no exit) so it can be
     * unit-tested. Default (non-verbose) output is the two-line form the CLI has
     * always emitted; under `-v` it appends UD-210 cause details so operators
     * can tell "wrong password" from "host-key mismatch" from "missing
     * authorized_keys" without tailing a log or patching the CLI.
     */
    internal fun renderAuthError(
        e: AuthenticationException,
        providerId: String,
        providerDisplayName: String,
        verbose: Boolean,
    ): String =
        buildString {
            appendLine("Not authenticated to $providerDisplayName.")
            appendLine()
            appendLine("Run: unidrive -p $providerId auth")
            if (verbose) {
                appendLine("Cause: ${e.message}")
                val cause = e.cause
                if (cause != null) {
                    appendLine("Root cause: ${cause.javaClass.name}: ${cause.message}")
                }
            }
        }

    /**
     * Checks whether the current provider is likely authenticated, without
     * throwing or performing network calls.
     * - Token-based (onedrive): token file exists
     * - Env-var / config-based (s3, sftp, webdav): required fields present
     * - External config (rclone): rclone remote configured
     */
    fun isProviderAuthenticated(): Boolean {
        val profile = resolveCurrentProfile()
        val profileDir = baseConfigDir.resolve(profile.name)
        val rp = profile.rawProvider

        val factory = ProviderRegistry.get(profile.type) ?: return false
        val properties = rawProviderToProperties(rp)
        return factory.isAuthenticated(properties, profileDir)
    }

    /**
     * Offline credential health check for a given profile — no network calls.
     * Delegates to the provider factory's [ProviderFactory.checkCredentialHealth].
     */
    fun checkCredentialHealth(
        profile: ProfileInfo,
        configDir: Path,
    ): CredentialHealth {
        val factory = ProviderRegistry.get(profile.type) ?: return CredentialHealth.Missing("Unknown provider type: ${profile.type}")
        val rp = mergeVaultCreds(profile)
        val properties = rawProviderToProperties(rp)
        return factory.checkCredentialHealth(properties, configDir)
    }

    /** Returns the base config directory (exposed for multi-provider status). */
    fun configBaseDir(): Path = baseConfigDir

    fun loadSyncConfig(): SyncConfig = SyncConfig.load(baseConfigDir.resolve("config.toml"), resolveCurrentProfile().name)

    fun providerConfigDir(): Path = baseConfigDir.resolve(resolveCurrentProfile().name)

    /**
     * Acquire the per-profile process lock. Returns the lock on success.
     * If another unidrive process holds the lock, prints a message and exits.
     */
    fun acquireProfileLock(): org.krost.unidrive.sync.ProcessLock {
        val lockFile = providerConfigDir().resolve(".lock")
        java.nio.file.Files
            .createDirectories(lockFile.parent)
        val lock =
            org.krost.unidrive.sync
                .ProcessLock(lockFile)
        if (!lock.tryLock()) {
            val profile = resolveCurrentProfile()
            // UD-272: surface the holder's PID + an OS-appropriate kill hint
            // so the user doesn't have to grep `tasklist | findstr java` to
            // pick the right one from 3+ JVMs on a typical dev host.
            val holderPid = lock.readHolderPid()
            val pidPart = if (holderPid != null) " (PID $holderPid)" else ""
            System.err.println("Another unidrive process$pidPart is running for profile '${profile.name}'.")
            if (holderPid != null) {
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                val killCmd = if (isWindows) "taskkill /PID $holderPid /F" else "kill $holderPid"
                System.err.println("Stop it with `$killCmd`, or wait for it to finish.")
            } else {
                System.err.println("Stop it first, or wait for it to finish.")
            }
            System.exit(1)
        }
        return lock
    }

    override fun run() {
        val profile = resolveCurrentProfile()
        val authenticated = isProviderAuthenticated()
        val alwaysAvailable = setOf("log", "profile", "vault", "provider")
        val cmd = spec.commandLine()

        // Print standard PicoCLI help header (synopsis + options)
        val help = cmd.helpFactory.create(spec, cmd.colorScheme)
        System.out.print(help.headerHeading())
        System.out.print(help.header())
        System.out.print(help.synopsisHeading())
        System.out.print(help.synopsis(0))
        System.out.print(help.descriptionHeading())
        System.out.print(help.description())
        System.out.print(help.optionListHeading())
        System.out.print(help.optionList())

        // Print subcommands with context-aware dimming
        System.out.println("Commands:")
        for ((name, sub) in cmd.subcommands) {
            val desc =
                sub.commandSpec
                    .usageMessage()
                    .description()
                    .firstOrNull() ?: ""
            val shouldDim =
                when {
                    name in alwaysAvailable -> false
                    name == "auth" -> authenticated // dim auth when already done
                    name == "logout" -> !authenticated // dim logout when not authed
                    else -> !authenticated
                }
            if (shouldDim) {
                println(AnsiHelper.dim("  %-8s %s".format(name, desc)))
            } else {
                println("  ${AnsiHelper.bold("%-8s".format(name))} $desc")
            }
        }

        if (!authenticated) {
            println()
            println("Tip: run 'unidrive -p ${profile.name} auth' first to authenticate.")
        }
    }
}

/**
 * UD-242: pure (no I/O, no exit) renderer for the config-missing error. Factored
 * out so unit tests can assert on the wording without trapping System.exit or
 * redirecting stderr. The single-line default form is what scripts grep for;
 * the verbose block is what operators paste into a bug report when diagnosing
 * MSIX sandboxing or a filtered FS view.
 */

/**
 * UD-237: list profile names whose declared (or inferred) provider type matches
 * [typeQuery] case-insensitively. Section type defaults to the profile name when
 * `[providers.<name>] type = "..."` is omitted, mirroring [SyncConfig.resolveProfile].
 */
internal fun profilesOfType(
    typeQuery: String,
    raw: RawSyncConfig,
): List<String> {
    val q = typeQuery.lowercase()
    return raw.providers.entries
        .filter { (name, section) ->
            val type = (section.type ?: name).lowercase()
            type == q
        }.map { it.key }
}

/**
 * UD-237: when `-p <value>` doesn't match a profile name, see if it matches a known
 * provider type (case-insensitive) AND exactly one profile of that type is configured.
 * Returns the auto-resolved [ProfileInfo] in that single-match case; null otherwise
 * (zero matches, multiple matches, or unknown type — caller falls through to the
 * existing error-list path with an extra hint when multiple).
 */
internal fun tryResolveByType(
    typeQuery: String,
    raw: RawSyncConfig,
): ProfileInfo? {
    val knownLower = SyncConfig.KNOWN_TYPES.map { it.lowercase() }.toSet()
    if (typeQuery.lowercase() !in knownLower) return null
    val matches = profilesOfType(typeQuery, raw)
    if (matches.size != 1) return null
    return SyncConfig.resolveProfile(matches.single(), raw)
}

/**
 * UD-235: heuristic — does this process command path look like an MSIX-packaged-app
 * parent? `WindowsApps` is the install root for every Store / MSIX app; `Claude` is
 * the known offender this ticket was filed for. Substring + case-insensitive on
 * purpose so we catch packaged-PowerShell launchers and similar variants. False
 * positives only cost one extra hint line; false negatives leave the user without
 * the diagnostic, so err permissive.
 */
internal fun looksLikeMsixParent(parentCommand: String?): Boolean {
    if (parentCommand.isNullOrBlank()) return false
    return listOf("WindowsApps", "Claude").any { parentCommand.contains(it, ignoreCase = true) }
}

/**
 * UD-235: best-effort NTFS file-id lookup for comparing this process's view of a path
 * against a sibling native shell's view. Java's [java.nio.file.attribute.BasicFileAttributes.fileKey]
 * returns the POSIX `(dev, ino)` tuple on Unix but is `null` on the default Windows
 * FileSystemProvider. On Windows we fall back to `fsutil file queryfileid` — the same
 * tool documented in `project_msix_sandbox.md` as the canonical comparison. Returns
 * null if both sources fail.
 */
internal fun ntfsFileId(configFile: Path): String? {
    if (!Files.exists(configFile)) return null
    val viaNio =
        try {
            Files
                .readAttributes(configFile, java.nio.file.attribute.BasicFileAttributes::class.java)
                .fileKey()
                ?.toString()
        } catch (_: Exception) {
            null
        }
    if (viaNio != null) return viaNio
    if (!System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)) return null
    return try {
        val proc =
            ProcessBuilder("fsutil", "file", "queryfileid", configFile.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start()
        val out =
            proc.inputStream
                .bufferedReader()
                .readText()
                .trim()
        if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            null
        } else if (proc.exitValue() != 0 || out.isBlank()) {
            null
        } else {
            out
        }
    } catch (_: Exception) {
        null
    }
}

internal fun renderConfigMissingMessage(
    configFile: Path,
    verbose: Boolean = false,
    parentProcessCommand: String? = null,
): String =
    buildString {
        appendLine(
            "Error: no unidrive config found. " +
                "Run 'unidrive profile add <type> <name>' to create your first profile.",
        )
        appendLine("  Searched: $configFile")
        if (verbose) {
            val configExists = Files.exists(configFile)
            val configSize =
                if (configExists) {
                    try {
                        Files.size(configFile)
                    } catch (_: Exception) {
                        -1L
                    }
                } else {
                    -1L
                }
            appendLine()
            appendLine("[verbose] Diagnostic:")
            appendLine("  APPDATA env:  ${System.getenv("APPDATA") ?: "(unset)"}")
            appendLine("  HOME env:     ${System.getenv("HOME") ?: "(unset)"}")
            appendLine("  Files.exists: $configExists")
            appendLine("  Files.size:   $configSize")
            if (!configExists) {
                appendLine()
                appendLine("No config.toml at that path. Create one via 'unidrive profile add <type> <name>',")
                appendLine("or copy docs/config-schema/config.example.toml to the path above.")
            } else if (configSize == 0L) {
                appendLine()
                appendLine("config.toml exists but is empty.")
            } else {
                appendLine()
                appendLine("config.toml is visible to this process but parsed as empty.")
                appendLine("If you believe the file has content, this shell/launcher may be imposing a")
                appendLine("filtered filesystem view over %APPDATA% (known: Store-packaged PowerShell,")
                appendLine("some launcher sandboxes). Try running from cmd.exe, PowerShell 5.1")
                appendLine("(powershell.exe), or an MSI-installed PowerShell 7 (pwsh.exe).")
            }
        }
        // UD-235: MSIX sandbox hint. Emitted regardless of -v because the user
        // who hit this needs the workaround immediately; the verbose gate only
        // governs the generic filesystem-state block above.
        if (looksLikeMsixParent(parentProcessCommand)) {
            val fileId = ntfsFileId(configFile) ?: "(unavailable — file may not exist at this path view)"
            appendLine()
            appendLine("Hint: this process is an MSIX-packaged-app child (Claude Desktop / similar).")
            appendLine("wcifs virtualization may be redirecting %APPDATA% reads to a sandbox-private")
            appendLine("copy, so the file a sibling native shell sees at this path may differ from")
            appendLine("what we see here. Workaround:")
            appendLine("  1. Pass `-c <non-virtualized-path>`, e.g. `-c C:\\Users\\<you>\\unidrive-config`.")
            appendLine("  2. Or set the UNIDRIVE_CONFIG_DIR env var to a path outside %APPDATA%.")
            appendLine("  Parent process: $parentProcessCommand")
            appendLine("  NTFS FileID at this path (compare against your native shell's view): $fileId")
        }
    }

fun main(args: Array<String>) {
    org.slf4j.MDC.put("build", BuildInfo.COMMIT)
    // UD-733: startup banner gives every captured log a version anchor at
    // line 1 — operators reading `unidrive.log` see the build SHA without
    // having to resolve the per-line MDC `[c1dfdf7]` prefix back to a git
    // commit. WARN if the build was made from a dirty worktree so users
    // don't file bug reports against transient WIP state.
    val log = org.slf4j.LoggerFactory.getLogger("org.krost.unidrive.cli.Main")
    log.info("unidrive {} starting (built {})", BuildInfo.versionString(), BuildInfo.BUILD_INSTANT)
    if (BuildInfo.DIRTY) {
        log.warn(
            "Build was made from a dirty git worktree (uncommitted changes). " +
                "Bug reports against this build cannot be reproduced from main.",
        )
    }
    val mainInstance = Main()
    val cmd = CommandLine(mainInstance)
    cmd.executionExceptionHandler =
        CommandLine.IExecutionExceptionHandler { ex, _, _ ->
            System.err.println("Error: ${ex.message ?: ex.javaClass.simpleName}")
            1
        }
    val services =
        org.krost.unidrive.cli.ext.internal
            .CliServicesImpl(mainInstance)
    org.krost.unidrive.cli.ext.internal.CliExtensionLoader
        .loadInto(cmd, services)
    try {
        val exitCode = cmd.execute(*args)
        System.exit(exitCode)
    } catch (e: Exception) {
        System.err.println("Error: ${e.message ?: e.javaClass.simpleName}")
        System.exit(1)
    }
}
