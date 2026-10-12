package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #586: `credential_health` in `daemon.status` over a live socket (docs/dev/specs/unidrive-daemon-design.md §4.3).
 * The mapping of each check result is pinned in [CredentialHealthMonitorTest]; this pins the wiring: the check runs
 * off the request path, is cached for its TTL, and a provider that rejects the credentials turns the state to
 * `expired` without waiting for the next check.
 */
class DaemonCredentialHealthTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-credential-health-test")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private class Provider : CloudProvider {
        /** The next `delta` fails: the credentials are rejected (an authentication failure after the token refresh). */
        val rejectCredentials = AtomicBoolean(false)
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
            if (rejectCredentials.get()) throw AuthenticationException("Authentication expired. Please re-authenticate.")
            return DeltaPage(items = emptyList(), cursor = "cursor-1", hasMore = false)
        }

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private fun runtime(
        provider: CloudProvider,
        check: (() -> CredentialHealth)?,
        ttlMs: Long = 60_000L,
        mode: ProfileMode = ProfileMode.MOUNT,
    ) = DaemonRuntime(
        profileMode = mode,
        profileName = "test_profile",
        lockFile = tempDir.resolve(".lock"),
        dbPath = tempDir.resolve("state.db"),
        syncRoot = tempDir,
        socketPath = socketPath,
        providerFactory = { provider },
        providerHasQuota = false,
        credentialHealthCheck = check,
        credentialHealthTtlMs = ttlMs,
    )

    private fun request(line: String): String {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "test_profile"), IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((line + "\n").toByteArray()))
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
            return collected.toString().substringBefore('\n')
        } finally {
            channel.close()
        }
    }

    private fun status(): JsonObject = Json.parseToJsonElement(request("""{"verb":"daemon.status"}""")).jsonObject

    private fun JsonObject.credentialHealth(): JsonObject = getValue("credential_health").jsonObject

    private fun JsonObject.credentialState(): String = credentialHealth().getValue("state").jsonPrimitive.content

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
            throw IllegalStateException("status never showed $what; last: $last", e)
        }
    }

    @Test
    fun `a daemon with no credential check says unknown and keeps authenticated as it was`() = runBlocking<Unit> {
        val runtime = runtime(Provider(), check = null)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            val s = status()

            assertEquals(setOf("state", "expires_in_ms", "checked_at_ms"), s.credentialHealth().keys)
            assertEquals("unknown", s.credentialState(), "no check can run: never reported as ok")
            assertEquals(JsonNull, s.credentialHealth().getValue("expires_in_ms"))
            assertEquals(JsonNull, s.credentialHealth().getValue("checked_at_ms"))
            assertTrue(s.getValue("authenticated").jsonPrimitive.boolean, "authenticated is unchanged: credentials are loaded")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `an expiring credential is reported with its time left, on a mount and on a mirror daemon`() = runBlocking<Unit> {
        for (mode in listOf(ProfileMode.MOUNT, ProfileMode.MIRROR)) {
            Files.deleteIfExists(socketPath)
            val runtime =
                runtime(
                    Provider(),
                    check = { CredentialHealth.ExpiresIn(hours = 3, message = "3h", remainingMs = 3 * 3_600_000L + 5_000) },
                    mode = mode,
                )
            val daemonJob = launch { runtime.start() }
            awaitDaemonSocket(socketPath, daemonJob)
            try {
                val s = awaitStatus("a finished credential check ($mode)") { it.credentialState() != "unknown" }

                assertEquals("expiring", s.credentialState())
                val left = s.credentialHealth().getValue("expires_in_ms").jsonPrimitive.long
                assertTrue(left in 3 * 3_600_000L - 60_000..3 * 3_600_000L + 5_000, "time left at the reply: $left")
                assertTrue(s.credentialHealth().getValue("checked_at_ms").jsonPrimitive.long > 0)
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }
    }

    @Test
    fun `each wire state of the offline check reaches the status`() = runBlocking<Unit> {
        val cases =
            listOf(
                CredentialHealth.Ok to "ok",
                CredentialHealth.Missing("No token.json") to "missing",
                CredentialHealth.ExpiresIn(0, "JWT expired") to "expired",
                CredentialHealth.Warning("token.json exists but could not be parsed") to "unknown",
            )
        for ((health, wire) in cases) {
            Files.deleteIfExists(socketPath)
            val calls = AtomicInteger()
            val runtime = runtime(Provider(), check = { calls.incrementAndGet(); health })
            val daemonJob = launch { runtime.start() }
            awaitDaemonSocket(socketPath, daemonJob)
            try {
                // A check that finished stamps checked_at_ms, which tells a finished `unknown` from the unchecked one.
                val s = awaitStatus("a finished credential check for $health") { it.credentialHealth().getValue("checked_at_ms") != JsonNull }

                assertEquals(wire, s.credentialState(), "$health")
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }
    }

    @Test
    fun `status polls inside the ttl never run the check again`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val runtime = runtime(Provider(), check = { calls.incrementAndGet(); CredentialHealth.Ok })
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            awaitStatus("a finished credential check") { it.credentialState() == "ok" }
            repeat(10) { assertEquals("ok", status().credentialState()) }

            assertEquals(1, calls.get(), "eleven polls, one provider-side check: the rest were served from the cache")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a check past its ttl is repeated off the poll`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val runtime = runtime(Provider(), check = { calls.incrementAndGet(); CredentialHealth.Ok }, ttlMs = 100)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            awaitStatus("a finished credential check") { it.credentialState() == "ok" }
            delay(150)
            val deadline = System.currentTimeMillis() + 10_000
            while (calls.get() < 2 && System.currentTimeMillis() < deadline) {
                status()
                delay(50)
            }

            assertTrue(calls.get() >= 2, "an expired cache is refreshed by a later poll")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a slow credential check does not delay the status reply`() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val runtime =
            runtime(
                Provider(),
                check = {
                    started.countDown()
                    release.await(60, TimeUnit.SECONDS)
                    CredentialHealth.Ok
                },
            )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS), "the check started with the daemon")

            val t0 = System.nanoTime()
            val whileBlocked = status()
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000

            assertEquals("unknown", whileBlocked.credentialState(), "the answer is not there yet: unknown, not ok")
            assertEquals(JsonNull, whileBlocked.credentialHealth().getValue("checked_at_ms"))
            assertTrue(elapsedMs < 3_000, "the reply came while the check was blocked ($elapsedMs ms)")

            release.countDown()
            awaitStatus("the released check") { it.credentialState() == "ok" }
        } finally {
            release.countDown()
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a provider that rejects the credentials turns the state to expired before the next check, and a success clears it`() =
        runBlocking<Unit> {
            val provider = Provider()
            // A long ttl: only the rejection hook can move the state below.
            val runtime = runtime(provider, check = { CredentialHealth.Ok }, ttlMs = 3_600_000L)
            val daemonJob = launch { runtime.start() }
            awaitDaemonSocket(socketPath, daemonJob)
            try {
                request("""{"verb":"sync.enumerate"}""")
                awaitStatus("a completed enumeration") { it.getValue("provider_health").jsonObject.getValue("last_contact_ms") != JsonNull }
                assertEquals("ok", awaitStatus("the credential check") { it.credentialState() == "ok" }.credentialState())

                provider.rejectCredentials.set(true)
                delay(30)
                request("""{"verb":"sync.enumerate"}""")
                val rejected = awaitStatus("the rejection") { it.credentialState() == "expired" }
                assertEquals(0L, rejected.credentialHealth().getValue("expires_in_ms").jsonPrimitive.long)
                assertTrue(rejected.getValue("authenticated").jsonPrimitive.boolean, "authenticated stays what it was")

                provider.rejectCredentials.set(false)
                delay(30)
                request("""{"verb":"sync.enumerate"}""")
                awaitStatus("the provider answering again") { it.credentialState() == "ok" }
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }
}
