package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.RemoteObjectStatus
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InternxtRemoteObjectAuditTest {
    private val requestLog = mutableListOf<String>()
    private val activeRequests = AtomicInteger()
    private val maxActiveRequests = AtomicInteger()

    private fun newProvider(): InternxtProvider {
        val tokenPath = Files.createTempDirectory("ud-audit-token")
        val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = tokenPath))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credentialsField = authService.javaClass.getDeclaredField("credentials")
        credentialsField.isAccessible = true
        val payload = """{"exp":9999999999}"""
        val payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        credentialsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = "header.$payloadB64.signature",
                mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder",
                email = "audit@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "bucket-1",
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
        val uuid = path.removeSuffix("/meta").substringAfterLast('/')
        val active = activeRequests.incrementAndGet()
        maxActiveRequests.updateAndGet { maxOf(it, active) }
        try {
            requestLog += "${request.method} ${request.url.host}$path"
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            return when {
                request.method != HttpMethod.Get -> respond("unexpected method", HttpStatusCode.MethodNotAllowed)
                path.startsWith("/drive/files/") && path.endsWith("/meta") ->
                    respond(
                        """{"uuid":"$uuid","bucket":"bucket-1","fileId":"bridge-$uuid","size":"100","status":"EXISTS"}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                path.endsWith("/bridge-bridge-missing/info") -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
                path.endsWith("/bridge-bridge-empty/info") ->
                    respond("""{"bucket":"bucket-1","id":"bridge-bridge-empty","size":100,"index":"${"11".repeat(32)}","shards":[]}""", HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/bridge-bridge-healthy/info") ->
                    respond(info("bridge-bridge-healthy", 100, 1), HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/bridge-bridge-truncated/info") ->
                    respond(info("bridge-bridge-truncated", 100, 1), HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/bridge-bridge-healthy") -> respond(mirror(100), HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/bridge-bridge-truncated") -> respond(mirror(40), HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/bridge-bridge-empty") -> respond("[]", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        } finally {
            activeRequests.decrementAndGet()
        }
    }

    private fun info(id: String, size: Long, shardCount: Int): String =
        """{"bucket":"bucket-1","id":"$id","size":$size,"index":"${"11".repeat(32)}","shards":[${(0 until shardCount).joinToString(",") { """{"index":$it,"hash":"hash-$it","url":"https://shard.invalid/$it"}""" }}]}"""

    private fun mirror(size: Long): String =
        """[{"index":0,"hash":"hash-0","size":$size,"healthy":true,"farmer":{"address":"node.invalid","port":443},"url":"https://node.invalid/shard"}]"""

    private fun item(name: String, size: Long = 100) =
        CloudItem(
            id = name,
            name = name,
            path = "/$name.bin",
            size = size,
            isFolder = false,
            modified = Instant.EPOCH,
            created = Instant.EPOCH,
            hash = null,
            mimeType = null,
        )

    @Test
    fun `audit classifies bridge availability and shard totals without downloading`() =
        runTest {
            val provider = newProvider()
            try {
                val report =
                    requireNotNull(provider.remoteObjectAuditor).auditRemoteObjects(
                        listOf(item("bridge-healthy"), item("bridge-missing"), item("bridge-empty"), item("bridge-truncated")),
                    )

                assertEquals(4, report.checkedCount)
                assertEquals(
                    listOf(RemoteObjectStatus.HEALTHY, RemoteObjectStatus.MISSING, RemoteObjectStatus.EMPTY, RemoteObjectStatus.TRUNCATED),
                    report.objects.map { it.status },
                )
                assertEquals(100L, report.objects.first().driveSize)
                assertEquals(100L, report.objects.first().shardBytes)
                assertEquals(40L, report.objects.last().shardBytes)
                assertEquals(1, maxActiveRequests.get(), "audit requests must remain sequential")
                assertTrue(
                    testScheduler.currentTime >= 500L * (requestLog.size - 1),
                    "audit requests must be spaced by at least 500 ms: ${requestLog.size} requests in ${testScheduler.currentTime} ms",
                )
                assertFalse(requestLog.any { it.contains("shard.invalid") }, "audit must never fetch shard bodies")
                assertTrue(requestLog.all { it.startsWith("GET ") })
            } finally {
                provider.close()
            }
        }
}
