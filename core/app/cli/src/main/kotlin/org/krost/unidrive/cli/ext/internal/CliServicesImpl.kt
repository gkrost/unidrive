package org.krost.unidrive.cli.ext.internal

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.cli.BuildInfo
import org.krost.unidrive.cli.Main
import org.krost.unidrive.cli.ext.CliServices
import org.krost.unidrive.cli.ext.Formatter
import org.krost.unidrive.cli.ext.ProfileView
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * Delegates from the public [CliServices] facade to the concrete [Main]
 * instance. Public CLI internals are unchanged; this class is the only
 * place the mapping lives.
 *
 * Every method that "acts on the current profile" inside Main is mediated
 * here by temporarily setting [Main.provider] to the requested profile
 * name and restoring it afterwards. This mirrors the pattern that
 * BenchmarkCommand.runSingleProfile used to run by hand, lifted into
 * one place.
 *
 * ## Multi-profile-per-JVM is safe (UD-211 resolved)
 *
 * [Main] memoises `resolveCurrentProfile()` in a private `_profile` cache
 * (and `_vaultData` likewise). Prior to UD-211, [withProfile] mutated
 * `Main.provider` but could not invalidate those caches, so back-to-back
 * calls with different profile names silently returned stale data from
 * the first profile. [withProfile] now calls
 * [Main.invalidateProfileCaches] around each save/set/restore boundary,
 * so it is safe to invoke this facade repeatedly against different
 * profiles inside a single JVM (e.g. for benchmark `--all` or any other
 * multi-profile extension).
 */
internal class CliServicesImpl(
    private val main: Main,
) : CliServices {
    override val formatter: Formatter = AnsiHelperFormatter()

    override val unidriveVersion: String get() = BuildInfo.versionString()

    override fun configBaseDir(): Path = main.configBaseDir()

    override fun loadSyncConfig(profileName: String): SyncConfig = withProfile(profileName) { main.loadSyncConfig() }

    override fun createProvider(profileName: String): CloudProvider = withProfile(profileName) { main.createProvider() }

    override fun isProviderAuthenticated(profileName: String): Boolean = withProfile(profileName) { main.isProviderAuthenticated() }

    override fun resolveProfile(name: String): ProfileView =
        withProfile(name) {
            val p = main.resolveCurrentProfile()
            ProfileView(
                name = p.name,
                type = p.type,
                rawEndpoint = p.rawProvider?.endpoint,
                rawHost = p.rawProvider?.host,
                rawUrl = p.rawProvider?.url,
            )
        }

    override fun listProfileNames(): List<String> {
        val cfg = main.configBaseDir().resolve("config.toml")
        if (!Files.exists(cfg)) return emptyList()
        val raw = SyncConfig.parseRaw(Files.readString(cfg))
        return raw.providers.keys.toList()
    }

    /**
     * Save `main.provider`, set it to [name], run [block], restore.
     *
     * Safe for back-to-back calls against different profiles (UD-211):
     * we bust [Main]'s memoised `_profile` / `_vaultData` caches both
     * when entering the inner profile and when restoring the saved one,
     * so neither the inner block nor a subsequent outer call can observe
     * stale data from an earlier profile resolution.
     */
    private inline fun <T> withProfile(
        name: String,
        block: () -> T,
    ): T {
        val saved = main.provider
        main.provider = name
        main.invalidateProfileCaches() // UD-211: cache was keyed on the previous provider
        return try {
            block()
        } finally {
            main.provider = saved
            main.invalidateProfileCaches() // UD-211: don't let the inner profile shadow `saved`
        }
    }
}
