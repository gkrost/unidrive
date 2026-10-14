package org.krost.unidrive.tracking

import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.cli.ext.CliServices
import org.krost.unidrive.cli.ext.Formatter
import org.krost.unidrive.cli.ext.ProfileView
import org.krost.unidrive.sync.SyncConfig
import picocli.CommandLine
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #168: `ts sync` and `ts claim` must authenticate the provider BEFORE the first remote operation.
 * The fix (5e8f9bb, since moved by #780) had no test because the live tests authenticate in setup, so
 * a regression — running the engine against an unauthenticated provider — would surface only there.
 * The recording provider answers nothing: the first engine operation throws, after writing its name
 * into the event list, so the test pins exactly the order create → authenticate → first operation.
 */
class TsAuthenticateOrderTest {
    private class ProviderTouched : RuntimeException()

    private val events = mutableListOf<String>()

    /** Records every call; the first engine operation raises [ProviderTouched] to stop the pass. */
    private val recording =
        object : CloudProvider {
            override val id: String = "recording"
            override val displayName: String = "recording"
            override var isAuthenticated: Boolean = false

            // Delta is required or enumerateRemote logs a warning and returns an empty remote
            // view without ever touching the provider — the order under test would not happen.
            override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

            override suspend fun authenticate() {
                events += "authenticate"
            }

            private fun touched(op: String): Nothing {
                events += op
                throw ProviderTouched()
            }

            override suspend fun listChildren(path: String): List<CloudItem> = touched("listChildren")

            override suspend fun getMetadata(path: String): CloudItem = touched("getMetadata")

            override suspend fun download(
                remotePath: String,
                destination: Path,
            ): Long = touched("download")

            override suspend fun upload(
                localPath: Path,
                remotePath: String,
                existingRemoteId: String?,
                ifMatchETag: String?,
                onProgress: ((Long, Long) -> Unit)?,
            ): CloudItem = touched("upload")

            override suspend fun delete(
                remotePath: String,
                ifMatchETag: String?,
            ) = touched("delete")

            override suspend fun createFolder(path: String): CloudItem = touched("createFolder")

            override suspend fun move(
                fromPath: String,
                toPath: String,
            ): CloudItem = touched("move")

            override suspend fun delta(
                cursor: String?,
                onPageProgress: ((Int) -> Unit)?,
                scanContext: org.krost.unidrive.ScanContext?,
            ): DeltaPage = touched("delta")

            override suspend fun quota(): QuotaInfo = touched("quota")
        }

    private val base = createTempDirectory("ts-auth-order")

    private fun services() =
        object : CliServices {
            override fun resolveProfile(name: String): ProfileView = ProfileView(name = name, type = "internxt", rawEndpoint = null, rawHost = null, rawUrl = null)

            override fun createProvider(profileName: String): CloudProvider {
                events += "create"
                return recording
            }

            override fun loadSyncConfig(profileName: String): SyncConfig = SyncConfig.load(base.resolve("config.toml"), profileName)

            override fun configBaseDir(): Path = base

            override fun isProviderAuthenticated(profileName: String): Boolean = false

            override fun listProfileNames(): List<String> = listOf("my_inxt")

            override fun resolvedGlobalProfile(): String? = null

            override val unidriveVersion: String = "test"
            override val formatter: Formatter =
                object : Formatter {
                    override fun bold(s: String) = s

                    override fun dim(s: String) = s

                    override fun underline(s: String) = s
                }
        }

    /** Runs one `ts <sub>` pass and returns the first exception the command let escape (none if it completed). */
    private fun runCommand(
        sub: String,
        vararg args: String,
    ) {
        val ts = TsCommand().apply { servicesRef = services() }
        val cmd = CommandLine(ts)
        cmd.setExecutionExceptionHandler { ex, _, _ -> throw if (ex is ProviderTouched) ex else RuntimeException(ex) }
        java.nio.file.Files.writeString(
            base.resolve("config.toml"),
            """
            [providers.my_inxt]
            type = "internxt"
            sync_root = "${base.resolve("root").toString().replace('\\', '/')}"
            """.trimIndent(),
        )
        cmd.execute(sub, *args)
    }

    private fun assertAuthenticateCameFirst() {
        val firstOp = events.firstOrNull { it != "create" && it != "authenticate" }
        assertTrue(firstOp != null, "the engine touched the provider at all: $events")
        assertEquals(
            listOf("create", "authenticate"),
            events.take(2),
            "authenticate must happen before the first remote operation ($firstOp); events=$events",
        )
    }

    @Test
    fun `ts sync authenticates before the first remote operation`() {
        try {
            runCommand("sync", "-p", "my_inxt", "--dry-run")
        } catch (_: ProviderTouched) {
            // expected: the recording provider stops the pass at its first operation
        }
        assertAuthenticateCameFirst()
    }

    @Test
    fun `ts claim authenticates before the first remote operation`() {
        try {
            runCommand("claim", "-p", "my_inxt", "/Documents/foo.txt")
        } catch (_: ProviderTouched) {
            // expected: the recording provider stops the pass at its first operation
        }
        assertAuthenticateCameFirst()
    }
}
