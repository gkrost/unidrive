package org.krost.unidrive.tracking

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.cli.ext.CliServices
import org.krost.unidrive.cli.ext.Formatter
import org.krost.unidrive.cli.ext.ProfileView
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The global `-p` accepts a provider TYPE (e.g. "internxt"), which the root
 * command maps to the real profile name. TsContext must key its per-profile
 * paths (config dir, tracking.db) on the RESOLVED name, not the raw type —
 * otherwise `unidrive -p internxt ts …` writes to a stray "internxt" directory
 * instead of the actual profile.
 */
class TsContextProfileResolutionTest {
    private val base = createTempDirectory("ts-ctx")

    /** A services fake that maps the provider type "internxt" to profile "my_inxt". */
    private fun services(globalProfile: String?) =
        object : CliServices {
            override fun resolveProfile(name: String): ProfileView {
                // Mirror the root command's type→profile mapping for one profile.
                val resolved = if (name == "internxt") "my_inxt" else name
                return ProfileView(name = resolved, type = "internxt", rawEndpoint = null, rawHost = null, rawUrl = null)
            }

            override fun createProvider(profileName: String): CloudProvider = error("unused")

            override fun loadSyncConfig(profileName: String): SyncConfig =
                SyncConfig.load(base.resolve("config.toml"), profileName)

            override fun configBaseDir(): Path = base

            override fun isProviderAuthenticated(profileName: String): Boolean = false

            override fun listProfileNames(): List<String> = listOf("my_inxt")

            override fun resolvedGlobalProfile(): String? = globalProfile

            override val unidriveVersion: String = "test"
            override val formatter: Formatter =
                object : Formatter {
                    override fun bold(s: String) = s

                    override fun dim(s: String) = s

                    override fun underline(s: String) = s
                }
        }

    @Test
    fun `global -p given as a provider type resolves to the real profile name`() {
        java.nio.file.Files.writeString(
            base.resolve("config.toml"),
            """
            [providers.my_inxt]
            type = "internxt"
            sync_root = "${base.resolve("root").toString().replace('\\', '/')}"
            """.trimIndent(),
        )
        // User ran `unidrive -p internxt ts sync` (type, not exact profile name).
        val ctx = TsContext.build(services(globalProfile = "internxt"), profileOpt = null)

        assertEquals("my_inxt", ctx.profileName, "the type must resolve to the real profile name")
        assertTrue(
            ctx.trackingDb.toString().contains("my_inxt"),
            "tracking.db must live under the resolved profile dir, not a stray 'internxt' one; was ${ctx.trackingDb}",
        )
        assertTrue(ctx.configDir.endsWith("my_inxt"), "config dir must be the resolved profile dir; was ${ctx.configDir}")
    }
}
