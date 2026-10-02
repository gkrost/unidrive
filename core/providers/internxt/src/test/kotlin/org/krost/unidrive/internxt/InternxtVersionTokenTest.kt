package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteConflictException
import org.krost.unidrive.internxt.model.InternxtFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// #464: Internxt has no etag or hash. The write token the engine hands back to upload() is "<uuid>|<modificationTime>|<size>" of
// the listing; upload() compares it with fresh metadata right before the replace. These tests pin the two halves: the token
// changes when the cloud copy changes, and a stale token stops the replace before anything is sent.
class InternxtVersionTokenTest {
    private fun file(
        uuid: String = "uuid-1",
        mtime: String? = "2026-10-01T10:00:00.000Z",
        size: String = "100",
    ) = InternxtFile(uuid = uuid, plainName = "a", type = "bin", size = size, modificationTime = mtime)

    @Test
    fun `the token changes with the uuid, the modification time and the size`() {
        val base = InternxtProvider.internxtVersionToken(file())

        assertEquals(base, InternxtProvider.internxtVersionToken(file()))
        assertNotEquals(base, InternxtProvider.internxtVersionToken(file(uuid = "uuid-2")))
        assertNotEquals(base, InternxtProvider.internxtVersionToken(file(mtime = "2026-10-01T10:00:01.000Z")))
        assertNotEquals(base, InternxtProvider.internxtVersionToken(file(size = "101")))
        assertTrue(InternxtProvider.internxtVersionToken(file(mtime = null)).isNotEmpty()) // a missing time still gives a token
    }

    private fun providerWithMeta(
        metaJson: String,
        startCalls: AtomicInteger,
        metaCalls: AtomicInteger,
    ): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(tokenPath = java.nio.file.Files.createTempDirectory("ud-token-")))
        val authService = InternxtProvider::class.java.getDeclaredField("authService").also { it.isAccessible = true }.get(provider)
        val creds = authService.javaClass.getDeclaredField("credentials").also { it.isAccessible = true }
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        creds.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = "header.$payload.signature",
                mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "6928426c1a2316b856c9ab81",
            ),
        )
        val api = InternxtProvider::class.java.getDeclaredField("api").also { it.isAccessible = true }.get(provider) as InternxtApiService
        val client = InternxtApiService::class.java.getDeclaredField("httpClient").also { it.isAccessible = true }
        (client.get(api) as? io.ktor.client.HttpClient)?.close()
        client.set(
            api,
            io.ktor.client.HttpClient(
                io.ktor.client.engine.mock.MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/files/uuid-1/meta") -> {
                            metaCalls.incrementAndGet()
                            respond(metaJson, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                        }
                        url.contains("/files/start") -> {
                            startCalls.incrementAndGet()
                            error("the replace must not start when the base token is stale: $url")
                        }
                        else -> error("unexpected URL: $url")
                    }
                },
            ),
        )
        return provider
    }

    @Test
    fun `a stale base token stops the replace before any byte is sent`() =
        runTest {
            val local = java.nio.file.Files.createTempFile("ud-token-local-", ".bin").also { java.nio.file.Files.write(it, ByteArray(100)) }
            val startCalls = AtomicInteger()
            val metaCalls = AtomicInteger()
            // The cloud copy changed after the caller listed it: a newer modification time than the token carries.
            val provider =
                providerWithMeta(
                    """{"uuid":"uuid-1","plainName":"a","type":"bin","size":"100","modificationTime":"2026-10-02T09:00:00.000Z"}""",
                    startCalls,
                    metaCalls,
                )
            val staleToken = InternxtProvider.internxtVersionToken(file(mtime = "2026-10-01T10:00:00.000Z"))

            assertFailsWith<RemoteConflictException> {
                provider.upload(local, "/a.bin", existingRemoteId = "uuid-1", ifMatchETag = staleToken, onProgress = null)
            }

            assertEquals(1, metaCalls.get(), "the fresh metadata was read once")
            assertEquals(0, startCalls.get(), "nothing was sent")
        }
}
