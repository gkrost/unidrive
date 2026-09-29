package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ScanContext
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.time.Instant
import java.util.Base64
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternxtScopedDeltaTest {
    private fun provider(engine: MockEngine): InternxtProvider {
        val provider = InternxtProvider()
        val auth = InternxtProvider::class.java.getDeclaredField("authService").also { it.isAccessible = true }.get(provider)
        val credsField = auth.javaClass.getDeclaredField("credentials").also { it.isAccessible = true }
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        credsField.set(
            auth,
            InternxtCredentials(
                jwt = "header.$payload.signature",
                mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                rootFolderId = "root",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "6928426c1a2316b856c9ab81",
            ),
        )
        val api = InternxtProvider::class.java.getDeclaredField("api").also { it.isAccessible = true }.get(provider)
        val clientField = InternxtApiService::class.java.getDeclaredField("httpClient").also { it.isAccessible = true }
        (clientField.get(api) as? HttpClient)?.close()
        clientField.set(api, HttpClient(engine))
        return provider
    }


    @Test
    fun `a scoped full delta lists only the scope subtree and never the account-wide endpoints`() =
        runTest {
            val requested = Collections.synchronizedList(mutableListOf<String>())
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    requested += url
                    val body =
                        when {
                            url.endsWith("/folders/content/root") ->
                                """{"children":[
                                    {"uuid":"inbox","plainName":"_INBOX","status":"EXISTS"},
                                    {"uuid":"inboxx","plainName":"_INBOXX","status":"EXISTS"}],
                                  "files":[{"uuid":"t","plainName":"top","type":"txt","size":"1","status":"EXISTS"}]}"""
                            url.endsWith("/folders/content/inbox") ->
                                """{"children":[{"uuid":"sub","plainName":"sub","status":"EXISTS"}],
                                  "files":[{"uuid":"a","plainName":"a","type":"txt","size":"3","status":"EXISTS"}]}"""
                            url.endsWith("/folders/content/sub") ->
                                """{"children":[],"files":[{"uuid":"d","plainName":"deep","type":"txt","size":"4","status":"EXISTS"}]}"""
                            else -> error("unexpected request: $url")
                        }
                    respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
            val before = Instant.now()

            val page =
                provider(engine).delta(
                    cursor = null,
                    onPageProgress = null,
                    scanContext = ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = listOf("/_INBOX")),
                )

            assertEquals(
                setOf("/_INBOX", "/_INBOX/sub", "/_INBOX/a.txt", "/_INBOX/sub/deep.txt"),
                page.items.map { it.path }.toSet(),
            )
            assertTrue(page.complete)
            assertTrue(!page.hasMore)
            assertTrue(!Instant.parse(page.cursor).isBefore(before.minusSeconds(1)), "cursor is the walk start time: ${page.cursor}")
            assertEquals(3, requested.size, "root, _INBOX and sub only: $requested")
            assertTrue(requested.none { it.contains("/files") && !it.contains("/folders/content/") }, "no account-wide /files call")
            assertTrue(requested.none { it.endsWith("/folders") || it.contains("/folders?") }, "no account-wide /folders call")
        }
}
