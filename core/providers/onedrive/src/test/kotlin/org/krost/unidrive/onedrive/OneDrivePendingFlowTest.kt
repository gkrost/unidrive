package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CompleteAuthResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The CLI's `auth begin` and `auth complete` are separate processes, so the device-code flow has to
 * survive in the profile folder. "A new process" here is a fresh factory with an empty in-memory
 * registry.
 */
class OneDrivePendingFlowTest {
    private lateinit var profileDir: Path

    @BeforeTest
    fun setUp() {
        profileDir = Files.createTempDirectory("od-pending-")
    }

    @AfterTest
    fun tearDown() {
        profileDir.toFile().deleteRecursively()
    }

    private fun factory(engine: MockEngine) =
        object : OneDriveProviderFactory() {
            override fun newOAuthServiceForBegin(profileDir: Path): OAuthService =
                OAuthService(OneDriveConfig(tokenPath = profileDir), HttpClient(engine))
        }

    private val deviceCodeBody =
        """{"device_code":"DC-abc-123","user_code":"USR-456","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":5,"message":"go"}"""

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        MockEngine { _ ->
            respond(ByteReadChannel(body), status, headersOf("Content-Type" to listOf("application/json")))
        }

    private val pendingFile get() = profileDir.resolve(PENDING_DEVICE_FLOW_FILE)

    private fun beginInFirstProcess(): String =
        runBlocking {
            val handle = factory(json(deviceCodeBody)).beginInteractiveAuth(profileDir).continuationHandle
            // The first process ends: its in-memory state is gone, the profile folder is not.
            OneDriveDeviceFlowRegistry.remove(handle)?.oauthService?.close()
            handle
        }

    @Test
    fun `begin keeps the flow in the profile folder`() {
        val handle = beginInFirstProcess()

        assertTrue(Files.exists(pendingFile))
        val text = Files.readString(pendingFile)
        assertTrue(handle in text)
        assertTrue("DC-abc-123" in text, "the device code is what a later process redeems")
    }

    @Test
    fun `a later process completes the flow from the profile folder and then forgets it`() {
        val handle = beginInFirstProcess()
        val token =
            """{"access_token":"${"x".repeat(200)}","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-789","scope":"Files.ReadWrite.All offline_access openid"}"""

        val result = runBlocking { factory(json(token)).completeInteractiveAuth(profileDir, handle) }

        assertEquals(CompleteAuthResult.Success, result)
        assertTrue(Files.exists(profileDir.resolve("token.json")))
        assertFalse(Files.exists(pendingFile), "a terminal outcome deletes the pending flow")
        assertEquals(null, OneDriveDeviceFlowRegistry.get(handle))
    }

    @Test
    fun `a later process polling too early keeps the flow for the next poll`() {
        val handle = beginInFirstProcess()

        val result =
            runBlocking {
                factory(json("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest)).completeInteractiveAuth(profileDir, handle)
            }

        assertIs<CompleteAuthResult.Pending>(result)
        assertTrue(Files.exists(pendingFile))
        OneDriveDeviceFlowRegistry.remove(handle)?.oauthService?.close()
    }

    @Test
    fun `a handle that is not the pending one is unknown and the pending flow stays`() {
        beginInFirstProcess()

        val result = runBlocking { factory(json("{}")).completeInteractiveAuth(profileDir, "some-other-handle") }

        assertIs<CompleteAuthResult.Failure>(result)
        assertTrue(Files.exists(pendingFile))
    }

    @Test
    fun `an expired pending flow fails and is deleted`() {
        val handle = beginInFirstProcess()
        Files.writeString(pendingFile, """{"handle":"$handle","deviceCode":"DC-abc-123","expiresAtMillis":1000}""")

        val result = runBlocking { factory(json("{}")).completeInteractiveAuth(profileDir, handle) }

        val failure = assertIs<CompleteAuthResult.Failure>(result)
        assertTrue("expired" in failure.message)
        assertFalse(Files.exists(pendingFile))
        assertEquals(null, OneDriveDeviceFlowRegistry.get(handle))
    }
}
