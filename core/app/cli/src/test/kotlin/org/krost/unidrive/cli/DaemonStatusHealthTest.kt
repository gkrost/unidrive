package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #658: the `uploads`, `cache` and `provider_health` objects of `daemon.status` (docs/dev/specs/unidrive-daemon-design.md
 * §4.3). Unknown stays unknown: a value the daemon does not have is `null` (or the whole object is absent), never 0.
 */
class DaemonStatusHealthTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-status-health-test")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private class FlakyProvider : CloudProvider {
        val outage = AtomicBoolean(false)
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")

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
            if (outage.get()) throw ProviderException("the provider is unreachable")
            return DeltaPage(items = emptyList(), cursor = "cursor-1", hasMore = false)
        }

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private fun runtime(
        provider: CloudProvider,
        mode: ProfileMode = ProfileMode.MOUNT,
        excludePatterns: List<String> = emptyList(),
        cacheBudget: Long = HydrationImpl.DEFAULT_CACHE_MAX_BYTES,
    ) = DaemonRuntime(
        profileMode = mode,
        profileName = "test_profile",
        lockFile = tempDir.resolve(".lock"),
        dbPath = tempDir.resolve("state.db"),
        syncRoot = tempDir,
        socketPath = socketPath,
        providerFactory = { provider },
        providerHasQuota = false,
        excludePatterns = excludePatterns,
        hydrationCacheMaxBytes = cacheBudget,
    )

    private fun status(): JsonObject {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "test_profile"), IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap("""{"verb":"daemon.status"}""".toByteArray() + "\n".toByteArray()))
            val collected = StringBuilder()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && !collected.contains('\n')) {
                val buf = ByteBuffer.allocate(4096)
                val n = channel.read(buf)
                if (n > 0) {
                    buf.flip()
                    collected.append(String(buf.array(), 0, buf.limit()))
                } else {
                    Thread.sleep(20)
                }
            }
            check(collected.contains('\n')) { "no reply line within 5s; collected: $collected" }
            return Json.parseToJsonElement(collected.toString().substringBefore('\n')).jsonObject
        } finally {
            channel.close()
        }
    }

    private fun send(request: String) {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "test_profile"), IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
            val buf = ByteBuffer.allocate(4096)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && channel.read(buf) <= 0) Thread.sleep(20)
        } finally {
            channel.close()
        }
    }

    private suspend fun awaitStatus(what: String, predicate: (JsonObject) -> Boolean): JsonObject {
        var last: JsonObject? = null
        return try {
            withTimeout(20_000) {
                while (true) {
                    val s = status()
                    last = s
                    if (predicate(s)) return@withTimeout s
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            error("status never showed $what; last: $last")
        }
    }

    private fun localRow(path: String, lastSynced: Instant) =
        SyncEntry(
            path = path,
            remoteId = null,
            remoteHash = null,
            remoteSize = 0L,
            remoteModified = null,
            localMtime = lastSynced.toEpochMilli(),
            localSize = 0L,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = lastSynced,
        )

    @Test
    fun `a fresh mount daemon reports nothing pending and an unknown provider contact`() = runBlocking {
        val runtime = runtime(FlakyProvider(), cacheBudget = 123_456L)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            val s = status()

            val uploads = s.getValue("uploads").jsonObject
            assertEquals(setOf("pending", "in_flight", "failed", "oldest_pending_age_ms"), uploads.keys)
            assertEquals(0, uploads.getValue("pending").jsonPrimitive.long)
            assertEquals(0, uploads.getValue("in_flight").jsonPrimitive.long)
            assertEquals(0, uploads.getValue("failed").jsonPrimitive.long)
            assertEquals(JsonNull, uploads.getValue("oldest_pending_age_ms"), "nothing pending has no age; never 0")
            val cache = s.getValue("cache").jsonObject
            assertEquals(setOf("bytes", "budget_bytes"), cache.keys)
            assertEquals(123_456L, cache.getValue("budget_bytes").jsonPrimitive.long)
            val provider = s.getValue("provider_health").jsonObject
            assertEquals(JsonNull, provider.getValue("last_contact_ms"), "no round trip yet: unknown, never 0")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `the cache size shows up once the daemon has measured it`() = runBlocking {
        val runtime = runtime(FlakyProvider())
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            // The start-up sweep (or the first status request's own measurement) walks the cache. The runtime uses the default
            // hydration cache root for profile "test_profile", which other daemon tests of this module share, so the size is
            // whatever they left there: the claim is that a measured size appears (a number, never negative), not that it is 0.
            val s = awaitStatus("a measured cache") { it.getValue("cache").jsonObject.getValue("bytes") != JsonNull }
            val bytes = s.getValue("cache").jsonObject.getValue("bytes").jsonPrimitive.long
            assertTrue(bytes >= 0, "a measured cache size is a byte count: $bytes")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `rows awaiting upload are counted, keep-local rows are not, and the oldest sets the age`() = runBlocking {
        val now = Instant.now()
        StateDatabase(tempDir.resolve("state.db")).also { db ->
            db.initialize()
            db.upsertEntry(localRow("/docs/old.txt", now.minusSeconds(3_600)))
            db.upsertEntry(localRow("/docs/new.txt", now.minusSeconds(10)))
            db.upsertEntry(localRow("/docs/scratch.tmp", now.minusSeconds(86_400)))
            db.close()
        }
        val runtime = runtime(FlakyProvider(), excludePatterns = listOf("*.tmp"))
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            val uploads = status().getValue("uploads").jsonObject

            assertEquals(2, uploads.getValue("pending").jsonPrimitive.long, "the excluded row is not counted")
            val age = uploads.getValue("oldest_pending_age_ms").jsonPrimitive.long
            assertTrue(age in 3_600_000L..3_700_000L, "the age is the oldest counted row's (an hour), not the excluded day-old one: $age")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a provider outage keeps the last contact instead of resetting it`() = runBlocking {
        val provider = FlakyProvider()
        val runtime = runtime(provider)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            send("""{"verb":"sync.enumerate"}""")
            val ok = awaitStatus("a completed enumeration") {
                it.getValue("provider_health").jsonObject.getValue("last_contact_ms") != JsonNull
            }
            val contact = ok.getValue("provider_health").jsonObject.getValue("last_contact_ms").jsonPrimitive.long
            assertEquals(
                ok.getValue("enumeration").jsonObject.getValue("last_success_at_ms").jsonPrimitive.long,
                contact,
                "an enumeration that completed is a provider round trip that worked",
            )
            assertTrue(contact > 0)

            provider.outage.set(true)
            delay(30)
            send("""{"verb":"sync.enumerate"}""")
            val failed = awaitStatus("a failed enumeration") { it.getValue("enumeration").jsonObject.getValue("state").jsonPrimitive.content == "failed" }

            assertEquals(
                contact,
                failed.getValue("provider_health").jsonObject.getValue("last_contact_ms").jsonPrimitive.long,
                "the outage does not move the last successful contact",
            )
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a mirror daemon has no upload queue or cache to report and says so by leaving them out`() = runBlocking {
        val runtime = runtime(FlakyProvider(), mode = ProfileMode.MIRROR)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            val s = status()

            assertFalse("uploads" in s, "a mirror profile constructs no hydration layer: unknown, not zero")
            assertFalse("cache" in s)
            assertNotNull(s["provider_health"], "provider contact is known to every mode")
            assertEquals(JsonNull, s.getValue("provider_health").jsonObject.getValue("last_contact_ms"))
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }
}
