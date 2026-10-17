package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #940: the official Internxt clients record a shard as RIPEMD-160(SHA-256(ciphertext)) (40 hex characters); the
 * check used to know only SHA-256 hex and refused every such object as a "SHA-256 mismatch" (310 files of one account,
 * all downloaded in full first). Each recorded shape is verified with its own algorithm, a real mismatch still
 * refuses (#295), and a value in no known shape is reported as such and read unverified instead of being called a
 * mismatch.
 */
class InternxtShardHashDownloadTest {
    private val fileUuid = "file-uuid-1"
    private val fileId = "bridge-file-id-1"
    private val bucket = "6928426c1a2316b856c9ab81"
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val index64 = "11".repeat(32)
    private val shardUrl = "https://shard-host.invalid/get/shard-0"

    // RIPEMD-160(SHA-256("0123456789A")), computed independently with Node's crypto module.
    private val knownBody = "0123456789A".toByteArray()
    private val knownBodyChain = "539cef25668d4b91379ec682f19cd7a1169f2701"

    private var declaredSize = "0"
    private var shardBody: ByteArray = knownBody
    private var shardContentLength: String? = null
    private var recordedHash = ""
    private val requestLog = mutableListOf<String>()
    private lateinit var destination: Path

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun chainHex(bytes: ByteArray) =
        Ripemd160.digest(MessageDigest.getInstance("SHA-256").digest(bytes)).joinToString("") { "%02x".format(it) }

    private fun newProvider(): InternxtProvider {
        destination = Files.createTempDirectory("ud-shard-hash").resolve("out.bin")
        val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = Files.createTempFile("ud-shard-token", ".tmp")))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"exp":9999999999}""".toByteArray())
        credsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = "header.$payloadB64.signature",
                mnemonic = mnemonic,
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = bucket,
            ),
        )
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val httpClientField = InternxtApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(api) as? HttpClient)?.close()
        httpClientField.set(api, HttpClient(MockEngine { request -> handle(request) }))
        return provider
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val host = request.url.host
        requestLog += "${request.method} $host$path"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        return when {
            path == "/drive/files/$fileUuid/meta" && request.method == HttpMethod.Get ->
                respond(
                    """{"uuid":"$fileUuid","bucket":"$bucket","fileId":"$fileId","size":"$declaredSize","status":"EXISTS"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            path == "/buckets/$bucket/files/$fileId/info" && request.method == HttpMethod.Get ->
                respond(
                    """{"bucket":"$bucket","id":"$fileId","index":"$index64","shards":[{"index":0,"hash":"$recordedHash","url":"$shardUrl"}]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            host == "shard-host.invalid" && request.method == HttpMethod.Get -> {
                val headers =
                    buildList<Pair<String, List<String>>> {
                        add(HttpHeaders.ContentType to listOf("application/octet-stream"))
                        shardContentLength?.let { add(HttpHeaders.ContentLength to listOf(it)) }
                    }.toTypedArray()
                respond(shardBody, HttpStatusCode.OK, headersOf(*headers))
            }
            else -> respond("""{"error":"unexpected: ${request.method} $host$path"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    private fun shardRequests() = requestLog.count { it.contains("shard-host.invalid") }

    private fun assertNothingLeft() {
        assertTrue(Files.notExists(destination), "unverified plaintext must not be published")
        assertTrue(
            Files.list(destination.parent).use { paths -> paths.noneMatch { it.fileName.toString().contains("unidrive-tmp") } },
            "no temp file either",
        )
    }

    private inline fun <T> capturingProviderLog(block: (ListAppender<ILoggingEvent>) -> T): T {
        val logger = LoggerFactory.getLogger(InternxtProvider::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            return block(appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun warnings(appender: ListAppender<ILoggingEvent>) = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    // ---- SHA-256 hex (64 characters): what unidrive's own uploads record -----------------------------------------

    @Test
    fun `a 64 hex shard hash that matches the ciphertext downloads`() =
        runTest {
            recordedHash = sha256Hex(knownBody)
            val provider = newProvider()

            assertEquals(11L, provider.downloadById(fileUuid, "/sha.bin", destination))
            assertTrue(Files.exists(destination))
        }

    @Test
    fun `a 64 hex shard hash that does not match refuses the content and names SHA-256`() {
        recordedHash = "00".repeat(32)
        val provider = newProvider()

        val e = assertFailsWith<RemoteContentRefusedDownloadException> { runTest { provider.downloadById(fileUuid, "/sha.bin", destination) } }

        assertTrue("SHA-256 mismatch" in e.message.orEmpty(), "got: ${e.message}")
        assertTrue("refusing unverified content" in e.message.orEmpty())
        assertEquals("SHA-256 mismatch", e.reason)
        assertTrue(PermanentDownloadFailureException::class.java.isAssignableFrom(e.javaClass), "stays quarantinable like every permanent download failure")
        assertEquals(1, shardRequests())
        assertNothingLeft()
    }

    // ---- RIPEMD-160(SHA-256) hex (40 characters): what every official client records ---------------------------------

    // The regression of #940: the 40 hex the official clients record verifies when it is right.
    @Test
    fun `a 40 hex shard hash of the known RIPEMD-160 vector downloads`() =
        runTest {
            recordedHash = knownBodyChain
            val provider = newProvider()

            assertEquals(11L, provider.downloadById(fileUuid, "/official.bin", destination))
            assertTrue(Files.exists(destination), "the object verified, so it is published")
        }

    @Test
    fun `a 40 hex shard hash downloads and decrypts a real encrypted object to its plaintext`() =
        runTest {
            val plaintext = "content stored by an official Internxt client".toByteArray()
            val crypto = InternxtCrypto()
            val indexBytes = InternxtCrypto.hexToBytes(index64)
            val fileKey = crypto.deriveFileKey(crypto.deriveBucketKey(crypto.mnemonicToSeed(mnemonic), bucket), indexBytes)
            shardBody = crypto.createContentEncryptCipher(fileKey, indexBytes.copyOfRange(0, 16)).doFinal(plaintext)
            declaredSize = plaintext.size.toString()
            shardContentLength = shardBody.size.toString()
            recordedHash = chainHex(shardBody)
            val provider = newProvider()

            provider.downloadById(fileUuid, "/official.txt", destination)

            assertTrue(Files.readAllBytes(destination).contentEquals(plaintext))
        }

    @Test
    fun `a 40 hex shard hash that does not match refuses the content and does not call it a SHA-256 mismatch`() {
        recordedHash = "00".repeat(20)
        val provider = newProvider()

        val e = assertFailsWith<RemoteContentRefusedDownloadException> { runTest { provider.downloadById(fileUuid, "/official.bin", destination) } }

        assertFalse("SHA-256 mismatch" in e.message.orEmpty(), "the expected value is not a SHA-256: ${e.message}")
        assertTrue("RIPEMD-160(SHA-256) mismatch" in e.message.orEmpty(), "got: ${e.message}")
        assertTrue("refusing unverified content" in e.message.orEmpty())
        assertEquals("RIPEMD-160(SHA-256) mismatch", e.reason)
        assertNothingLeft()
    }

    @Test
    fun `a 40 hex hash of other content is refused even when it is the SHA-256 chain of something else`() {
        recordedHash = chainHex("some other ciphertext".toByteArray())
        val provider = newProvider()

        assertFailsWith<RemoteContentRefusedDownloadException> { runTest { provider.downloadById(fileUuid, "/official.bin", destination) } }
        assertNothingLeft()
    }

    // ---- a value in no known shape: not a mismatch ----------------------------------------------------------------

    @Test
    fun `a shard hash in an unknown shape downloads unverified and warns once with its length and class`() =
        runTest {
            recordedHash = "ab".repeat(64) // SHA-512 sized
            val provider = newProvider()

            capturingProviderLog { appender ->
                assertEquals(11L, provider.downloadById(fileUuid, "/unknown.bin", destination))
                Files.delete(destination)
                assertEquals(11L, provider.downloadById(fileUuid, "/unknown.bin", destination), "a repeat reads it unverified again")

                val warned = warnings(appender)
                assertEquals(1, warned.size, "once per object: $warned")
                assertTrue("length 128, hex" in warned.single(), "the shape is named: ${warned.single()}")
                assertTrue("WITHOUT the integrity check" in warned.single())
                assertFalse(recordedHash in warned.single(), "never the value")
            }
        }

    @Test
    fun `an empty shard hash is read unverified, not called a mismatch`() =
        runTest {
            recordedHash = ""
            val provider = newProvider()

            capturingProviderLog { appender ->
                assertEquals(11L, provider.downloadById(fileUuid, "/nohash.bin", destination))
                assertTrue("length 0, empty" in warnings(appender).single())
            }
        }

    // The right length for a known shape but not hex: malformed, so unrecognised like any other unknown shape.
    @Test
    fun `a malformed shard hash of a known length is read unverified with a warning`() =
        runTest {
            recordedHash = "z".repeat(40)
            val provider = newProvider()

            capturingProviderLog { appender ->
                assertEquals(11L, provider.downloadById(fileUuid, "/malformed.bin", destination))
                assertTrue("length 40, base64" in warnings(appender).single())
            }
        }

    // ---- diagnostics and the early refusal ---------------------------------------------------------------------------

    @Test
    fun `a refusal is logged once per object with the recorded shape and the algorithm, never the value`() {
        recordedHash = "cd".repeat(20)
        val provider = newProvider()

        capturingProviderLog { appender ->
            repeat(3) { assertFailsWith<RemoteContentRefusedDownloadException> { runTest { provider.downloadById(fileUuid, "/official.bin", destination) } } }

            val warned = warnings(appender)
            assertEquals(1, warned.size, "a pin retries the same object: one WARN, the rest DEBUG: $warned")
            assertTrue("length 40, hex" in warned.single(), warned.single())
            assertTrue("RIPEMD-160(SHA-256)" in warned.single(), warned.single())
            assertFalse(recordedHash in warned.single(), "never the value")
        }
    }

    // A stream cipher keeps the length: an object longer than the drive reports can only fail the completeness
    // guard, so the Content-Length says so before the first byte is read.
    @Test
    fun `a stored object longer than the declared size is refused from its Content-Length before streaming`() {
        recordedHash = knownBodyChain
        declaredSize = "5"
        shardContentLength = "11"
        val provider = newProvider()

        val e = assertFailsWith<RemoteIncompleteDownloadException> { runTest { provider.downloadById(fileUuid, "/long.bin", destination) } }

        assertTrue("longer than the size the drive reports" in e.message.orEmpty(), "got: ${e.message}")
        assertEquals(11L, e.storedBytes)
        assertEquals(5L, e.declaredBytes)
        assertNothingLeft()
    }
}
