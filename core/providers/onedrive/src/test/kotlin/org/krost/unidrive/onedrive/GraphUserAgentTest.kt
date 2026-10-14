package org.krost.unidrive.onedrive

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphUserAgentTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ud-user-agent")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val item = """{"id":"i1","name":"f.bin","size":4,"file":{"mimeType":"application/octet-stream"},"@microsoft.graph.downloadUrl":"https://cdn.example/f"}"""

    private fun service(
        engine: MockEngine,
        config: OneDriveConfig = OneDriveConfig(tokenPath = dir),
    ) = GraphApiService(config = config, engine = engine, tokenProvider = { _ -> "test-token" })

    @Test
    fun `delta, download url and upload url requests all carry the configured user agent`() =
        runTest {
            val seen = mutableMapOf<String, String?>()
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    seen[request.method.value + " " + url.substringBefore('?')] = request.headers[HttpHeaders.UserAgent]
                    when {
                        url.endsWith("/delta") -> respond("""{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=t"}""", HttpStatusCode.OK, json)
                        url == "https://cdn.example/f" -> respond("DATA", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                        url.contains("/items/i1") -> respond(item, HttpStatusCode.OK, json)
                        url.endsWith("/createUploadSession") -> respond("""{"uploadUrl":"https://upload.example/s","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, json)
                        url == "https://upload.example/s" -> respond(item, HttpStatusCode.Created, json)
                        else -> respond("{}", HttpStatusCode.OK, json)
                    }
                }
            val svc = service(engine)
            val local = Files.createTempFile(dir, "up", ".bin").also { Files.write(it, ByteArray(16)) }

            svc.getDelta()
            svc.downloadFile("i1", dir.resolve("out.bin"))
            svc.uploadLargeFile(local, "/f.bin")
            svc.close()

            assertTrue(seen.keys.any { it.endsWith("/delta") }, "delta request not observed: $seen")
            assertTrue(seen.keys.contains("GET https://cdn.example/f"), "download url request not observed: $seen")
            assertTrue(seen.keys.contains("PUT https://upload.example/s"), "upload url request not observed: $seen")
            seen.forEach { (req, ua) -> assertEquals(OneDriveConfig.DEFAULT_USER_AGENT, ua, "User-Agent on $req") }
        }

    @Test
    fun `a custom configured user agent is what is sent`() =
        runTest {
            var ua: String? = null
            val engine = MockEngine { request ->
                ua = request.headers[HttpHeaders.UserAgent]
                respond("""{"value":[]}""", HttpStatusCode.OK, json)
            }
            val svc = service(engine, OneDriveConfig(tokenPath = dir, userAgent = "ISV|Example|Probe/9.9"))

            svc.listChildren("/")
            svc.close()

            assertEquals("ISV|Example|Probe/9.9", ua)
        }
}
