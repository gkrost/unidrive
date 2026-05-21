package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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

    /** /devicecode succeeds; /token returns 4xx with an unrecognised error
     *  code → DevicePollOutcome.Failed (the "else" arm in pollOnceForToken). */
    private fun deviceCodeThenFailedEngine(): MockEngine {
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
                        content = ByteReadChannel("""{"error":"invalid_grant"}"""),
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf("Content-Type" to listOf("application/json")),
                    )
            }
        }
    }

    /** /devicecode succeeds; /token returns 200 with a body that fails JSON
     *  deserialisation → SerializationException escapes pollOnceForToken
     *  and is caught by the generic exception arm in completeInteractiveAuth. */
    private fun deviceCodeThenMalformedTokenEngine(): MockEngine {
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
                        content = ByteReadChannel("""{"this": "is not a TokenResponse shape"}"""),
                        status = HttpStatusCode.OK,
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
            // UD-375: fields carry typed JsonElement values. Strings stay
            // string-typed; interval_seconds + expires_in are JSON numbers
            // (Long-backed JsonPrimitive) so MCP clients can parse them
            // numerically.
            assertEquals(
                "https://microsoft.com/devicelogin",
                result.fields["verification_uri"]?.jsonPrimitive?.contentOrNull,
            )
            assertEquals("USR-456", result.fields["user_code"]?.jsonPrimitive?.contentOrNull)
            assertEquals(5L, result.fields["interval_seconds"]?.jsonPrimitive?.long)
            assertEquals(900L, result.fields["expires_in"]?.jsonPrimitive?.long)
            assertNotNull(result.fields["message"]?.jsonPrimitive?.contentOrNull)
            // Numeric fields must be JSON numbers (un-quoted on the wire),
            // not JSON strings — guards UD-375 regression.
            assertEquals(false, (result.fields["interval_seconds"] as JsonPrimitive).isString)
            assertEquals(false, (result.fields["expires_in"] as JsonPrimitive).isString)
            assertTrue(result.expiresAt.isAfter(java.time.Instant.now()))
            assertEquals(5L, result.retryAfterSeconds)

            // Cleanup so the next test starts with an empty registry.
            factory.cancelInteractiveAuth(result.continuationHandle)
        }

    @Test
    fun begin_wire_format_keeps_numerics_unquoted_after_mcp_jsonbuilder() =
        runBlocking {
            // UD-375 regression test. Mirrors `AuthTool.handleAuthBegin`'s
            // JSON-building exactly (same kotlinx.serialization JsonObjectBuilder
            // pattern, same `for ((k, v) in result.fields) put(k, v)` loop).
            // If a future refactor reverts BeginAuthResult.fields to
            // Map<String, String> OR adds a `.toString()` call on values, the
            // wire numerics would re-acquire quotes and break MCP clients
            // parsing them as JSON numbers.
            val factory = factoryWithEngine(deviceCodeOnlyEngine())
            val result = factory.beginInteractiveAuth(tmpProfileDir)

            val wirePayload =
                buildJsonObject {
                    put("profile", "test")
                    put("continuation_handle", result.continuationHandle)
                    for ((k, v) in result.fields) put(k, v)
                }.toString()

            // Numerics: unquoted (JSON numbers).
            assertTrue(
                wirePayload.contains("\"interval_seconds\":5"),
                "interval_seconds must serialize as a JSON number, got: $wirePayload",
            )
            assertTrue(
                wirePayload.contains("\"expires_in\":900"),
                "expires_in must serialize as a JSON number, got: $wirePayload",
            )
            // Strings: quoted.
            assertTrue(
                wirePayload.contains("\"user_code\":\"USR-456\""),
                "user_code must serialize as a JSON string, got: $wirePayload",
            )

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
    fun complete_authorization_pending_returns_pending_and_preserves_handle() =
        runBlocking {
            val factory = factoryWithEngine(deviceCodeThenPendingEngine())
            val begin = factory.beginInteractiveAuth(tmpProfileDir)
            val outcome = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)

            assertTrue(outcome is CompleteAuthResult.Pending)
            assertEquals(5L, outcome.retryAfterSeconds)
            // Pending must NOT drain the registry — the handle stays live for
            // the next poll. Dual of registry_is_empty_after_each_terminal_outcome.
            assertEquals(1, OneDriveDeviceFlowRegistry.sizeForTest(), "Pending must preserve registry entry")

            // Handle is still resolvable for the next poll.
            val second = factory.completeInteractiveAuth(tmpProfileDir, begin.continuationHandle)
            assertTrue(second is CompleteAuthResult.Pending)
            assertEquals(1, OneDriveDeviceFlowRegistry.sizeForTest(), "second Pending must still preserve entry")

            // Cleanup
            factory.cancelInteractiveAuth(begin.continuationHandle)
            assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest())
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

            // Expired-token error path (DevicePollOutcome.Failed via expired_token)
            val e = factoryWithEngine(deviceCodeThenExpiredEngine())
            val eBegin = e.beginInteractiveAuth(tmpProfileDir)
            e.completeInteractiveAuth(tmpProfileDir, eBegin.continuationHandle)
            assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest(), "registry must drain after Failure(expired)")

            // Failed-with-unrecognised-error path (DevicePollOutcome.Failed via else arm)
            val f = factoryWithEngine(deviceCodeThenFailedEngine())
            val fBegin = f.beginInteractiveAuth(tmpProfileDir)
            val fOutcome = f.completeInteractiveAuth(tmpProfileDir, fBegin.continuationHandle)
            assertTrue(fOutcome is CompleteAuthResult.Failure)
            assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest(), "registry must drain after Failure(poll-Failed)")

            // Malformed-token-body path (SerializationException escapes
            // pollOnceForToken → generic catch arm in completeInteractiveAuth).
            val m = factoryWithEngine(deviceCodeThenMalformedTokenEngine())
            val mBegin = m.beginInteractiveAuth(tmpProfileDir)
            val mOutcome = m.completeInteractiveAuth(tmpProfileDir, mBegin.continuationHandle)
            assertTrue(mOutcome is CompleteAuthResult.Failure)
            assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest(), "registry must drain after Failure(poll-exception)")

            // saveToken-fails path: pre-create a *subdirectory* named token.json
            // at the path CredentialStore will try to write. The atomic-write
            // (Files.move REPLACE_EXISTING to a directory target) will throw,
            // exercising the saveToken catch arm in completeInteractiveAuth.
            // This is more reliable cross-platform than setReadOnly on the
            // parent directory (which can silently no-op as root or on FS
            // modes that allow file creation in mode 0555 dirs).
            //
            // We need a fresh profileDir for this arm so the prior arms'
            // token.json doesn't pre-exist as a regular file.
            val svProfileDir = Files.createTempDirectory("ud-014-savefail-")
            try {
                Files.createDirectory(svProfileDir.resolve("token.json"))
                val sv = factoryWithEngine(deviceCodeThenTokenEngine())
                val svBegin = sv.beginInteractiveAuth(svProfileDir)
                val svOutcome = sv.completeInteractiveAuth(svProfileDir, svBegin.continuationHandle)
                assertTrue(
                    svOutcome is CompleteAuthResult.Failure,
                    "token.json-as-directory must fail saveToken; got $svOutcome",
                )
                assertTrue(
                    svOutcome.message.contains("save failed", ignoreCase = true) ||
                        svOutcome.message.lowercase().contains("token"),
                    "save-failure message should mention save/token; got '${svOutcome.message}'",
                )
                assertEquals(
                    0,
                    OneDriveDeviceFlowRegistry.sizeForTest(),
                    "registry must drain after Failure(save-failed)",
                )
            } finally {
                svProfileDir.toFile().deleteRecursively()
            }

            // Cancel path
            val c = factoryWithEngine(deviceCodeOnlyEngine())
            val cBegin = c.beginInteractiveAuth(tmpProfileDir)
            c.cancelInteractiveAuth(cBegin.continuationHandle)
            assertEquals(0, OneDriveDeviceFlowRegistry.sizeForTest(), "registry must drain after cancel")
        }
}
