package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `include_shared` must read every page of `sharedWithMe` (an item past page 1 would look deleted
 * to the engine) and must hash shared items the way the rest of the provider does
 * (`quickXorHash` first, `sha256Hash` as the fallback).
 */
class SharedWithMePaginationTest {
    private fun sharedItem(
        id: String,
        hashes: String = "",
    ): String = """{"id":"$id","name":"n-$id","size":10,"file":{"mimeType":"text/plain"$hashes}}"""

    private fun page(
        items: List<String>,
        nextLink: String? = null,
    ): String {
        val next = if (nextLink != null) ""","@odata.nextLink":"$nextLink"""" else ""
        return """{"value":[${items.joinToString(",")}]$next}"""
    }

    private fun serviceWith(engine: MockEngine): GraphApiService {
        val service =
            GraphApiService(
                config = OneDriveConfig(),
                tokenProvider = { _ -> "test-token" },
            )
        val field = GraphApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
        return service
    }

    private fun providerWith(engine: MockEngine): OneDriveProvider {
        val provider = OneDriveProvider(OneDriveConfig(includeShared = true))
        val graphApiField = OneDriveProvider::class.java.getDeclaredField("graphApi")
        graphApiField.isAccessible = true
        (graphApiField.get(provider) as? GraphApiService)?.close()
        graphApiField.set(provider, serviceWith(engine))
        return provider
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `listSharedWithMe follows nextLink and requests the server link verbatim`() =
        runTest {
            val seen = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    seen += request.url.toString()
                    val body =
                        when (seen.size) {
                            1 -> page(listOf(sharedItem("a"), sharedItem("b")), "https://graph.microsoft.com/v1.0/me/drive/sharedWithMe?\$skiptoken=PAGE2")
                            2 -> page(listOf(sharedItem("c")))
                            else -> error("unexpected request ${seen.size}")
                        }
                    respond(body, HttpStatusCode.OK, json)
                }
            val service = serviceWith(engine)

            val items = service.listSharedWithMe()

            assertEquals(listOf("a", "b", "c"), items.map { it.id })
            assertEquals(2, seen.size)
            assertTrue(seen[1].contains("skiptoken=PAGE2"), "second request must be the nextLink, was ${seen[1]}")
            service.close()
        }

    @Test
    fun `listSharedWithMe stops when the server repeats a nextLink`() =
        runTest {
            val loop = "https://graph.microsoft.com/v1.0/me/drive/sharedWithMe?\$skiptoken=LOOP"
            var calls = 0
            val engine =
                MockEngine { _ ->
                    calls++
                    check(calls <= 10) { "nextLink loop was not broken" }
                    respond(page(listOf(sharedItem("x$calls")), loop), HttpStatusCode.OK, json)
                }
            val service = serviceWith(engine)

            val items = service.listSharedWithMe()

            // First request (no skiptoken) returns LOOP; the second request returns LOOP again and ends the walk.
            assertEquals(2, calls)
            assertEquals(2, items.size)
            service.close()
        }

    @Test
    fun `deltaWithShared returns shared items from every page with quickXor preferred over sha256`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body =
                        when {
                            url.contains("sharedWithMe") && url.contains("skiptoken=PAGE2") ->
                                page(listOf(sharedItem("s3", ""","hashes":{"sha256Hash":"SHA3"}""")))
                            url.contains("sharedWithMe") ->
                                page(
                                    listOf(
                                        sharedItem("s1", ""","hashes":{"quickXorHash":"QX1","sha256Hash":"SHA1"}"""),
                                        sharedItem("s2", ""","hashes":{"quickXorHash":"QX2"}"""),
                                    ),
                                    "https://graph.microsoft.com/v1.0/me/drive/sharedWithMe?\$skiptoken=PAGE2",
                                )
                            else -> """{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"}"""
                        }
                    respond(body, HttpStatusCode.OK, json)
                }
            val provider = providerWith(engine)

            val result = provider.deltaWithShared(null)

            val page = assertIs<CapabilityResult.Success<org.krost.unidrive.DeltaPage>>(result).value
            val byId = page.items.associateBy { it.id }
            assertEquals(setOf("shared:s1", "shared:s2", "shared:s3"), byId.keys)
            assertEquals("QX1", byId["shared:s1"]?.hash)
            assertEquals("QX2", byId["shared:s2"]?.hash)
            assertEquals("SHA3", byId["shared:s3"]?.hash, "sha256Hash is the fallback when quickXorHash is absent")
            provider.close()
        }
}
