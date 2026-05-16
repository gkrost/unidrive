package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.CompleteAuthResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UD-014 acceptance test. Drives OneDriveProviderFactory's three
 * interactive-auth overrides against Ktor MockEngine — no live network.
 *
 * Each @Test asserts ONE invariant; multiple invariants share a fixture
 * builder but never share a test name. Orthogonal invariant decomposition
 * per CLAUDE.md.
 */
class OneDriveInteractiveAuthContractTest {
    private lateinit var tmpProfileDir: Path

    @BeforeTest
    fun setUp() {
        tmpProfileDir = Files.createTempDirectory("ud-014-")
    }

    @AfterTest
    fun tearDown() {
        tmpProfileDir.toFile().deleteRecursively()
    }

    /** Subclass the factory to inject a MockEngine-backed HttpClient
     *  into the OAuthService created inside beginInteractiveAuth. */
    private fun factoryWithEngine(engine: MockEngine): OneDriveProviderFactory =
        object : OneDriveProviderFactory() {
            override fun newOAuthServiceForBegin(profileDir: Path): OAuthService =
                OAuthService(OneDriveConfig(tokenPath = profileDir), HttpClient(engine))
        }

    /** Canned /devicecode response matching Azure's documented shape. */
    private fun deviceCodeResponseBody(): String =
        """
        {
          "device_code": "DC-abc-123",
          "user_code": "USR-456",
          "verification_uri": "https://microsoft.com/devicelogin",
          "expires_in": 900,
          "interval": 5,
          "message": "To sign in, use a web browser to open https://microsoft.com/devicelogin and enter USR-456."
        }
        """.trimIndent()

    private fun deviceCodeOnlyEngine(): MockEngine =
        MockEngine { _ ->
            respond(
                content = ByteReadChannel(deviceCodeResponseBody()),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("application/json")),
            )
        }

    /** Two-step engine: /devicecode then /token. */
    private fun deviceCodeThenTokenEngine(): MockEngine {
        var call = 0
        return MockEngine { _ ->
            call += 1
            when (call) {
                1 ->
                    respond(
                        content = ByteReadChannel(deviceCodeResponseBody()),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type" to listOf("application/json")),
                    )
                2 ->
                    respond(
                        content =
                            ByteReadChannel(
                                """
                                {
                                  "access_token": "${"x".repeat(200)}",
                                  "token_type": "Bearer",
                                  "expires_in": 3600,
                                  "refresh_token": "rt-789",
                                  "scope": "Files.ReadWrite.All offline_access openid"
                                }
                                """.trimIndent(),
                            ),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type" to listOf("application/json")),
                    )
                else -> error("Unexpected request #$call")
            }
        }
    }

    private fun deviceCodeThenPendingEngine(): MockEngine {
        var call = 0
        return MockEngine { _ ->
            call += 1
            when (call) {
                1 ->
                    respond(
                        content = ByteReadChannel(deviceCodeResponseBody()),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type" to listOf("application/json")),
                    )
                else ->
                    respond(
                        content = ByteReadChannel("""{"error":"authorization_pending"}"""),
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf("Content-Type" to listOf("application/json")),
                    )
            }
        }
    }

    private fun deviceCodeThenExpiredEngine(): MockEngine {
        var call = 0
        return MockEngine { _ ->
            call += 1
            when (call) {
                1 ->
                    respond(
                        content = ByteReadChannel(deviceCodeResponseBody()),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type" to listOf("application/json")),
                    )
                else ->
                    respond(
                        content = ByteReadChannel("""{"error":"expired_token"}"""),
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf("Content-Type" to listOf("application/json")),
                    )
            }
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    fun factory_declares_interactive_auth_support() {
        val factory = OneDriveProviderFactory()
        assertTrue(factory.supportsInteractiveAuth())
    }

    @Test
    fun begin_returns_well_formed_payload() =
        runBlocking {
            val factory = factoryWithEngine(deviceCodeOnlyEngine())
            val result: BeginAuthResult = factory.beginInteractiveAuth(tmpProfileDir)

            assertTrue(result.continuationHandle.isNotEmpty())
            assertEquals("https://microsoft.com/devicelogin", result.fields["verification_uri"])
            assertEquals("USR-456", result.fields["user_code"])
            assertEquals("5", result.fields["interval_seconds"])
            assertEquals("900", result.fields["expires_in"])
            assertNotNull(result.fields["message"])
            assertTrue(result.expiresAt.isAfter(java.time.Instant.now()))
            assertEquals(5L, result.retryAfterSeconds)

            // Cleanup so the next test starts with an empty registry.
            factory.cancelInteractiveAuth(result.continuationHandle)
        }

    @Test
    fun complete_success_persists_token_and_forgets_handle() =
        runBlocking {
            val factory = factoryWithEngine(deviceCodeThenTokenEngine())
            val begin = factory.beginInteractiveAuth(tmpProfileDir)
            val outcome = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)

            assertEquals(CompleteAuthResult.Success, outcome)
            assertTrue(Files.exists(tmpProfileDir.resolve("token.json")))

            // Same handle on a second call must now be unknown.
            val second = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)
            assertTrue(second is CompleteAuthResult.Failure)
            assertTrue(second.message.contains("Unknown or expired"))
        }

    @Test
    fun complete_authorization_pending_returns_pending() =
        runBlocking {
            val factory = factoryWithEngine(deviceCodeThenPendingEngine())
            val begin = factory.beginInteractiveAuth(tmpProfileDir)
            val outcome = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)

            assertTrue(outcome is CompleteAuthResult.Pending)
            assertEquals(5L, outcome.retryAfterSeconds)

            // Handle is still resolvable for the next poll.
            val second = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)
            assertTrue(second is CompleteAuthResult.Pending)

            // Cleanup
            factory.cancelInteractiveAuth(begin.continuationHandle)
        }

    @Test
    fun complete_expired_token_returns_error_and_forgets_handle() =
        runBlocking {
            val factory = factoryWithEngine(deviceCodeThenExpiredEngine())
            val begin = factory.beginInteractiveAuth(tmpProfileDir)
            val outcome = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)

            assertTrue(outcome is CompleteAuthResult.Failure)
            assertTrue(outcome.message.contains("expired", ignoreCase = true))

            // Handle now unknown.
            val second = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)
            assertTrue(second is CompleteAuthResult.Failure)
            assertTrue(second.message.contains("Unknown or expired"))
        }

    @Test
    fun cancel_releases_handle() =
        runBlocking {
            val factory = factoryWithEngine(deviceCodeOnlyEngine())
            val begin = factory.beginInteractiveAuth(tmpProfileDir)

            factory.cancelInteractiveAuth(begin.continuationHandle)

            val outcome = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)
            assertTrue(outcome is CompleteAuthResult.Failure)
            assertTrue(outcome.message.contains("Unknown or expired"))
        }

    @Test
    fun registry_is_empty_after_each_terminal_outcome() =
        runBlocking {
            // Success path
            val s = factoryWithEngine(deviceCodeThenTokenEngine())
            val sBegin = s.beginInteractiveAuth(tmpProfileDir)
            s.completeInteractiveAuth(tmpProfileDir, sBegin.continuationHandle)
            assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest(), "registry must drain after Success")

            // Expired-token error path
            val e = factoryWithEngine(deviceCodeThenExpiredEngine())
            val eBegin = e.beginInteractiveAuth(tmpProfileDir)
            e.completeInteractiveAuth(tmpProfileDir, eBegin.continuationHandle)
            assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest(), "registry must drain after Failure(expired)")

            // Cancel path
            val c = factoryWithEngine(deviceCodeOnlyEngine())
            val cBegin = c.beginInteractiveAuth(tmpProfileDir)
            c.cancelInteractiveAuth(cBegin.continuationHandle)
            assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest(), "registry must drain after cancel")
        }
}
