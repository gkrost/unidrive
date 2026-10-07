package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.Capability
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.SyncConfig
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #603 (U4, the hosting contract): a profile's `mode = "mirror" | "mount"` decides what serves it.
 * A profile without a mode is refused — never defaulted; a command for the wrong mode is refused
 * before anything is touched; a mirror daemon answers the mount verbs with wrong_mode and keeps the
 * legacy refresh; a mount daemon reports mode + capabilities and always enumerates on refresh.run.
 */
class HostingContractTest {
    // ── the config surface ───────────────────────────────────────────────────

    @Test
    fun `mode parses as mirror and mount, any case`() {
        assertEquals(ProfileMode.MIRROR, ProfileMode.fromConfig("mirror"))
        assertEquals(ProfileMode.MOUNT, ProfileMode.fromConfig("Mount"))
        assertEquals(ProfileMode.MIRROR, ProfileMode.fromConfig(" MIRROR "))
    }

    @Test
    fun `an absent mode is null, never defaulted`() {
        assertNull(ProfileMode.fromConfig(null))
        assertNull(ProfileMode.fromConfig(""))
    }

    @Test
    fun `an unknown mode names the two legal values`() {
        val error =
            runCatching { ProfileMode.fromConfig("sync") }.exceptionOrNull()
                ?: error("an unknown mode must be refused")
        assertTrue(
            error.message!!.contains("mirror") && error.message!!.contains("mount"),
            "the refusal names both modes; was: ${error.message}",
        )
    }

    @Test
    fun `resolveProfile carries the profile's mode and stays null without one`() {
        val type = SyncConfig.KNOWN_TYPES.first()
        val withMode =
            SyncConfig.parseRaw("""[providers.p]
type = "$type"
mode = "mount"
""")
        assertEquals(ProfileMode.MOUNT, SyncConfig.resolveProfile("p", withMode).mode)

        val withoutMode =
            SyncConfig.parseRaw("""[providers.p]
type = "$type"
""")
        assertNull(SyncConfig.resolveProfile("p", withoutMode).mode, "modeless is null — the caller refuses")
    }

    @Test
    fun `the modeless refusal names the profile, the command and the way out`() {
        val message = ProfileMode.modelessMessage("legacy_drive", "daemon run")
        assertTrue(message.contains("legacy_drive"), "names the profile")
        assertTrue(message.contains("daemon run"), "names what was refused")
        assertTrue(message.contains("mirror") && message.contains("mount"), "names the two modes")
        assertTrue(message.contains("no mode"), "says what is missing")
    }

    // ── a live mirror daemon refuses the mount verbs and reports its mode ────

    @Test
    fun `a mirror daemon answers wrong_mode to the mount verbs and reports mirror in daemon status`() =
        runBlocking {
            val tempDir = Files.createTempDirectory("hosting-contract-mirror")
            val socketPath = tempDir.resolve("daemon.sock")
            val runtime =
                DaemonRuntime(
                    profileMode = ProfileMode.MIRROR,
                    profileName = "mirror_profile",
                    lockFile = tempDir.resolve(".lock"),
                    dbPath = tempDir.resolve("state.db"),
                    syncRoot = tempDir.resolve("sync-root"),
                    socketPath = socketPath,
                    providerFactory = { StubProvider() },
                )
            val daemonJob = launch { runtime.start() }
            try {
                repeat(50) {
                    if (Files.exists(socketPath)) return@repeat
                    delay(50)
                }
                assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

                for (verb in HydrationIpcHandler.VERBS + "sync.enumerate") {
                    val reply = exchange(tempDir, socketPath, "mirror_profile", """{"verb":"$verb"}""")
                    val obj = Json.parseToJsonElement(reply) as JsonObject
                    assertEquals(
                        "wrong_mode",
                        (obj["error"] as? JsonPrimitive)?.content,
                        "$verb must be refused on a mirror profile",
                    )
                    assertEquals(
                        false,
                        (obj["ok"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
                        "$verb's refusal is not a success",
                    )
                }

                val status = exchange(tempDir, socketPath, "mirror_profile", """{"verb":"daemon.status"}""")
                val statusObj = Json.parseToJsonElement(status) as JsonObject
                assertEquals("mirror", statusObj["mode"]!!.jsonPrimitive.content, "daemon.status reports the mode")
                assertEquals(
                    JsonArray(ProfileMode.MIRROR.capabilities.map { JsonPrimitive(it) }),
                    statusObj["capabilities"],
                    "daemon.status reports the mode's capabilities",
                )

                // refresh.run still answers on a mirror profile — the legacy reconcile path, as a job.
                val refresh = exchange(tempDir, socketPath, "mirror_profile", """{"verb":"refresh.run"}""")
                assertEquals(true, (Json.parseToJsonElement(refresh) as JsonObject)["ok"]!!.jsonPrimitive.content.toBoolean())
            } finally {
                runtime.close()
                daemonJob.join()
                runCatching { tempDir.toFile().deleteRecursively() }
            }
        }

    @Test
    fun `a mount daemon reports mount and its capabilities in daemon status`() =
        runBlocking {
            val tempDir = Files.createTempDirectory("hosting-contract-mount")
            val socketPath = tempDir.resolve("daemon.sock")
            val runtime =
                DaemonRuntime(
                    profileMode = ProfileMode.MOUNT,
                    profileName = "mount_profile",
                    lockFile = tempDir.resolve(".lock"),
                    dbPath = tempDir.resolve("state.db"),
                    syncRoot = tempDir,
                    socketPath = socketPath,
                    providerFactory = { StubProvider() },
                )
            val daemonJob = launch { runtime.start() }
            try {
                repeat(50) {
                    if (Files.exists(socketPath)) return@repeat
                    delay(50)
                }
                val status = exchange(tempDir, socketPath, "mount_profile", """{"verb":"daemon.status"}""")
                val statusObj = Json.parseToJsonElement(status) as JsonObject
                assertEquals("mount", statusObj["mode"]!!.jsonPrimitive.content)
                assertEquals(
                    JsonArray(ProfileMode.MOUNT.capabilities.map { JsonPrimitive(it) }),
                    statusObj["capabilities"],
                )
            } finally {
                runtime.close()
                daemonJob.join()
                runCatching { tempDir.toFile().deleteRecursively() }
            }
        }

    /** One authenticated request/reply on a fresh connection (the corpus test's pattern). */
    private suspend fun exchange(
        profileDir: Path,
        socketPath: Path,
        profileName: String,
        request: String,
    ): String {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, profileDir, profileName), IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
            return readFirstLine(channel, timeoutMs = 5_000)
        } finally {
            channel.close()
        }
    }

    private suspend fun readFirstLine(
        channel: SocketChannel,
        timeoutMs: Long,
    ): String {
        val buffer = ByteBuffer.allocate(64 * 1024)
        val line = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = channel.read(buffer)
            if (n > 0) {
                line.append(String(buffer.array(), 0, buffer.position()))
                buffer.clear()
                val idx = line.indexOf("\n")
                if (idx >= 0) return line.substring(0, idx)
            }
            delay(10)
        }
        error("no reply within ${timeoutMs}ms; got: $line")
    }

    /** Minimal provider: authenticated, empty drive (the corpus test's stub). */
    private class StubProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op: already authenticated */ }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }
}
