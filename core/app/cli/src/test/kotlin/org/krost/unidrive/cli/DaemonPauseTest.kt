package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #778: `daemon.pause` and `daemon.resume` against a live daemon on a real socket: scope, the `paused`
 * object of `daemon.status`, the `paused` and `resumed` events, and what a paused daemon leaves undone.
 */
class DaemonPauseTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path

    // A profile of its own, so the hydration cache this test fills is one it can delete again.
    private val profile = "pause_test_" + UUID.randomUUID().toString().take(8)

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-pause-test")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
        runCatching { SyncEngine.hydrationCacheRoot(SyncEngine.defaultHydrationCacheRoot(), profile).toFile().deleteRecursively() }
    }

    private class Provider : CloudProvider {
        val deltaCalls = AtomicInteger()
        val quotaCalls = AtomicInteger()
        val downloads = AtomicInteger()
        val content = mutableMapOf<String, ByteArray>()
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(remotePath: String, destination: Path): Long {
            downloads.incrementAndGet()
            val bytes = content.getValue(remotePath)
            Files.createDirectories(destination.parent)
            Files.write(destination, bytes)
            return bytes.size.toLong()
        }

        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = download(remotePath, destination)

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage {
            deltaCalls.incrementAndGet()
            return DeltaPage(items = emptyList(), cursor = "cursor-1", hasMore = false)
        }

        override suspend fun quota(): QuotaInfo {
            quotaCalls.incrementAndGet()
            return QuotaInfo(total = 100L, used = 10L, remaining = 90L)
        }
    }

    private fun runtime(
        provider: CloudProvider,
        mode: ProfileMode = ProfileMode.MOUNT,
        pollIntervalMs: Long = 0,
        providerHasQuota: Boolean = false,
        quotaRefreshMs: Long = 15 * 60_000L,
    ) = DaemonRuntime(
        profileMode = mode,
        profileName = profile,
        lockFile = tempDir.resolve(".lock"),
        dbPath = tempDir.resolve("state.db"),
        syncRoot = tempDir,
        socketPath = socketPath,
        providerFactory = { provider },
        pollIntervalMs = pollIntervalMs,
        providerHasQuota = providerHasQuota,
        quotaRefreshMs = quotaRefreshMs,
    )

    private fun connect(scope: IpcAuth.Scope = IpcAuth.Scope.FULL): SocketChannel =
        IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, profile), scope).also { it.configureBlocking(false) }

    private fun SocketChannel.send(request: String) {
        write(ByteBuffer.wrap((request + "\n").toByteArray(Charsets.UTF_8)))
    }

    private suspend fun ask(request: String, scope: IpcAuth.Scope = IpcAuth.Scope.FULL): JsonObject {
        val channel = connect(scope)
        try {
            channel.send(request)
            val line = awaitIpcLine(channel, "reply to $request") { !it.contains("\"event\"") }
            return Json.parseToJsonElement(line).jsonObject
        } finally {
            channel.close()
        }
    }

    private suspend fun status() = ask("""{"verb":"daemon.status"}""")

    private suspend fun pause(reason: String = "metered", auto: Boolean = true) =
        ask("""{"verb":"daemon.pause","reason":"$reason","auto":$auto}""")

    private suspend fun resume() = ask("""{"verb":"daemon.resume"}""")

    private fun JsonObject.ok() = getValue("ok").jsonPrimitive.boolean

    private fun JsonObject.error() = getValue("error").jsonPrimitive.content

    private fun coldRow(path: String) =
        SyncEntry(
            path = path,
            remoteId = "rid-$path",
            remoteHash = null,
            remoteSize = 5L,
            remoteModified = Instant.now(),
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.now(),
        )

    @Test
    fun `pause and resume need the full scope`() = runBlocking {
        val runtime = runtime(Provider())
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            val denied = ask("""{"verb":"daemon.pause","reason":"user","auto":false}""", IpcAuth.Scope.READ)
            assertEquals("forbidden", denied.error())
            assertEquals("read", denied.getValue("scope").jsonPrimitive.content)
            assertNull(status()["paused"], "a read connection could not pause the daemon")

            pause("user", auto = false)
            val deniedResume = ask("""{"verb":"daemon.resume"}""", IpcAuth.Scope.READ)
            assertEquals("forbidden", deniedResume.error())
            assertTrue(status().containsKey("paused"), "a read connection could not resume it either")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `status carries paused only while paused, and uploads and quota keep reporting`() = runBlocking {
        val runtime = runtime(Provider(), providerHasQuota = true)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            val running = status()
            assertFalse("paused" in running, "absent while running")
            assertTrue("pause" in running.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }, "a client can tell an engine that pauses")

            val before = System.currentTimeMillis()
            val reply = pause("metered", auto = true)
            val after = System.currentTimeMillis()
            assertTrue(reply.ok())
            assertEquals(reply.getValue("paused"), status().getValue("paused"), "the verb answers the object the status shows")

            val paused = status()
            val pausedObject = paused.getValue("paused").jsonObject
            assertEquals(setOf("reason", "since_ms", "auto"), pausedObject.keys)
            assertEquals("metered", pausedObject.getValue("reason").jsonPrimitive.content)
            assertTrue(pausedObject.getValue("auto").jsonPrimitive.boolean)
            assertTrue(pausedObject.getValue("since_ms").jsonPrimitive.long in before..after, "since is when the pause began")
            for (key in listOf("uploads", "cache", "provider_health", "quota")) {
                assertTrue(key in paused, "$key keeps reporting while paused")
            }

            assertTrue(resume().ok())
            assertFalse("paused" in status(), "absent again once resumed")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a second pause keeps the first since and takes the new reason, a resume while running is a no-op`() = runBlocking {
        val runtime = runtime(Provider())
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            assertTrue(resume().ok(), "resume while running succeeds")
            assertFalse("paused" in status())

            val first = pause("user", auto = false).getValue("paused").jsonObject
            delay(20)
            val second = pause("power_saver", auto = true).getValue("paused").jsonObject

            assertEquals(first.getValue("since_ms"), second.getValue("since_ms"), "the pause began at the first request")
            assertEquals("power_saver", second.getValue("reason").jsonPrimitive.content)
            assertTrue(second.getValue("auto").jsonPrimitive.boolean)
            assertEquals(second, status().getValue("paused").jsonObject)
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a garbage reason is refused with a structured error and the daemon keeps running`() = runBlocking {
        val runtime = runtime(Provider())
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            assertEquals("missing_reason", ask("""{"verb":"daemon.pause","auto":true}""").error())
            assertEquals("invalid_reason", ask("""{"verb":"daemon.pause","reason":"Not A Word!","auto":true}""").error())
            assertEquals("invalid_reason", ask("""{"verb":"daemon.pause","reason":"${"x".repeat(200)}"}""").error())
            assertEquals("invalid_auto", ask("""{"verb":"daemon.pause","reason":"user","auto":"yes"}""").error())

            assertFalse("paused" in status())
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `the paused and resumed events reach sync and hydration subscribers, once per change`() = runBlocking {
        val runtime = runtime(Provider())
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        val syncSub = connect(IpcAuth.Scope.FULL)
        val hydrationSub = connect(IpcAuth.Scope.READ)
        try {
            syncSub.send("""{"verb":"sync.subscribe"}""")
            awaitIpcLine(syncSub, "sync.subscribe reply") { !it.contains("\"event\"") }
            hydrationSub.send("""{"verb":"hydration.subscribe"}""")
            awaitIpcLine(hydrationSub, "hydration.subscribe reply") { !it.contains("\"event\"") }

            syncSub.send("""{"verb":"daemon.pause","reason":"metered","auto":true}""")
            val syncPaused = awaitIpcLine(syncSub, "paused event on sync.subscribe") { it.contains("\"event\":\"paused\"") }
            val hydrationPaused = awaitIpcLine(hydrationSub, "paused event on hydration.subscribe") { it.contains("\"event\":\"paused\"") }
            for (line in listOf(syncPaused, hydrationPaused)) {
                val event = Json.parseToJsonElement(line).jsonObject
                assertEquals(setOf("event", "reason", "since_ms", "auto"), event.keys)
                assertEquals("metered", event.getValue("reason").jsonPrimitive.content)
                assertTrue(event.getValue("auto").jsonPrimitive.boolean)
            }

            // The same pause again is not news; a changed reason is; the resume is.
            syncSub.send("""{"verb":"daemon.pause","reason":"metered","auto":true}""")
            syncSub.send("""{"verb":"daemon.pause","reason":"user","auto":false}""")
            val changed = awaitIpcLine(syncSub, "paused event for the changed reason") { it.contains("\"event\":\"paused\"") && it.contains("\"reason\":\"user\"") }
            assertTrue(changed.contains("\"auto\":false"))
            syncSub.send("""{"verb":"daemon.resume"}""")
            val resumed = awaitIpcLine(syncSub, "resumed event on sync.subscribe") { it.contains("\"event\":\"resumed\"") }
            assertEquals("""{"event":"resumed"}""", resumed)
            awaitIpcLine(hydrationSub, "resumed event on hydration.subscribe") { it.contains("\"event\":\"resumed\"") }

            syncSub.send("""{"verb":"daemon.resume"}""")
            syncSub.send("""{"verb":"daemon.status"}""")
            val lines = mutableListOf<String>()
            val status = awaitIpcLine(syncSub, "status after the no-op resume") { lines += it; it.startsWith("""{"ok":true,"protocol_version""") }
            assertTrue(status.isNotEmpty())
            assertTrue(lines.none { it.contains("\"event\":\"resumed\"") }, "a resume while running is silent: $lines")
        } finally {
            syncSub.close()
            hydrationSub.close()
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a timed pause shows its end in the status and the daemon resumes by itself and says so`() = runBlocking {
        val runtime = runtime(Provider())
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        val syncSub = connect(IpcAuth.Scope.FULL)
        try {
            syncSub.send("""{"verb":"sync.subscribe"}""")
            awaitIpcLine(syncSub, "sync.subscribe reply") { !it.contains("\"event\"") }

            val until = System.currentTimeMillis() + 1_500
            val reply = ask("""{"verb":"daemon.pause","reason":"user","auto":false,"until_ms":$until}""")
            assertEquals(until, reply.getValue("paused").jsonObject.getValue("until_ms").jsonPrimitive.long)
            assertEquals(reply.getValue("paused"), status().getValue("paused"), "the status shows the end instant")
            assertTrue(awaitIpcLine(syncSub, "paused event") { it.contains("\"event\":\"paused\"") }.contains("\"until_ms\":$until"))

            val resumed = awaitIpcLine(syncSub, "resumed event from the timer") { it.contains("\"event\":\"resumed\"") }
            assertEquals("""{"event":"resumed","reason":"timeout"}""", resumed)
            assertTrue(System.currentTimeMillis() >= until, "not before the instant")
            assertFalse("paused" in status())
        } finally {
            syncSub.close()
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `the poll does not enumerate while paused and runs again at once when resumed`() = runBlocking {
        val provider = Provider()
        val runtime = runtime(provider, pollIntervalMs = 100)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            withTimeout(20_000) { while (provider.deltaCalls.get() < 2) delay(20) }
            pause()
            delay(500) // a tick that began before the pause has ended by now
            val frozen = provider.deltaCalls.get()

            delay(1_000) // ten poll intervals
            assertEquals(frozen, provider.deltaCalls.get(), "no cloud enumeration while paused")

            resume()
            withTimeout(20_000) { while (provider.deltaCalls.get() <= frozen) delay(20) }
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `sync enumerate and refresh run are refused as paused and work again after resume`() = runBlocking {
        val provider = Provider()
        val runtime = runtime(provider)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            pause()

            assertEquals("paused", ask("""{"verb":"sync.enumerate"}""").error())
            assertEquals("paused", ask("""{"verb":"refresh.run"}""").error())
            delay(200)
            assertEquals(0, provider.deltaCalls.get(), "nothing reached the cloud")

            resume()
            assertTrue(ask("""{"verb":"sync.enumerate"}""").ok())
            withTimeout(20_000) { while (provider.deltaCalls.get() < 1) delay(20) }
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a cold file is refused as paused over the wire and served after resume`() = runBlocking {
        val provider = Provider()
        val path = "/pause-${UUID.randomUUID()}.txt"
        provider.content[path] = "hello".toByteArray()
        StateDatabase(tempDir.resolve("state.db")).also { db ->
            db.initialize()
            db.upsertEntry(coldRow(path))
            db.close()
        }
        val runtime = runtime(provider)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            pause("user", auto = false)

            val read = ask("""{"verb":"hydration.open_read","handle_id":"h1","path":"$path"}""")
            val hydrate = ask("""{"verb":"hydration.hydrate","path":"$path"}""")
            assertFalse(read.ok())
            assertEquals("paused", read.error())
            assertEquals("paused", hydrate.error())
            assertEquals(0, provider.downloads.get(), "no cloud download starts while paused")

            resume()
            val served = ask("""{"verb":"hydration.open_read","handle_id":"h2","path":"$path"}""")
            assertTrue(served.ok(), "$served")
            assertEquals(1, provider.downloads.get())
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `the quota is not fetched from the cloud while paused`() = runBlocking {
        val provider = Provider()
        val runtime = runtime(provider, providerHasQuota = true, quotaRefreshMs = 1)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            status()
            withTimeout(20_000) { while (provider.quotaCalls.get() < 1) { status(); delay(20) } }
            pause()
            delay(200)
            val frozen = provider.quotaCalls.get()

            repeat(10) { status(); delay(20) }
            assertEquals(frozen, provider.quotaCalls.get(), "a status request does not reach the cloud while paused")

            resume()
            withTimeout(20_000) { while (provider.quotaCalls.get() <= frozen) { status(); delay(20) } }
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a mirror daemon pauses too and refuses its refresh`() = runBlocking {
        val provider = Provider()
        val runtime = runtime(provider, mode = ProfileMode.MIRROR)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            assertTrue("pause" in status().getValue("capabilities").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(pause("user", auto = false).ok())

            val paused = status()
            assertEquals("user", paused.getValue("paused").jsonObject.getValue("reason").jsonPrimitive.content)
            assertFalse("uploads" in paused, "a mirror profile has no upload queue to report")
            assertEquals("paused", ask("""{"verb":"refresh.run"}""").error())

            assertTrue(resume().ok())
            assertTrue(ask("""{"verb":"refresh.run"}""").ok())
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a paused daemon stops cleanly and the next start runs`() = runBlocking {
        val runtime = runtime(Provider())
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        pause("metered", auto = true)

        runtime.close()
        withTimeout(30_000) { daemonJob.join() }

        assertFalse(Files.exists(socketPath), "the socket is gone")
        val next = runtime(Provider())
        val nextJob = launch { next.start() }
        awaitDaemonSocket(socketPath, nextJob)
        try {
            assertFalse("paused" in status(), "the pause lives in memory only: a restart runs, the client re-applies its rules")
        } finally {
            next.close()
            nextJob.join()
        }
    }
}
