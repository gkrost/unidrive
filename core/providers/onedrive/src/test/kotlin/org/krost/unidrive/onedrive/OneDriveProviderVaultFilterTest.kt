package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-315: verifies that `OneDriveProvider.listChildren` and `.delta` filter out OneDrive's
 * Personal Vault stub.
 *
 * We build a real `OneDriveProvider`, then swap its private `graphApi`'s `httpClient` for one
 * backed by a Ktor `MockEngine` that returns canned JSON (same pattern as
 * `ListChildrenPaginationTest`). The filter pipeline runs end-to-end; only the transport is
 * faked.
 */
class OneDriveProviderVaultFilterTest {
    private fun mockedProvider(responseBody: String): OneDriveProvider {
        val engine =
            MockEngine { _ ->
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val provider = OneDriveProvider()

        // Build a fresh GraphApiService with a non-refreshing token provider, then swap its
        // internal httpClient for the MockEngine. Install that service into the provider via
        // reflection (same pattern as ListChildrenPaginationTest).
        val freshGraphApi =
            GraphApiService(
                config = OneDriveConfig(),
                tokenProvider = { _ -> "test-token" },
            )
        val httpClientField = GraphApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(freshGraphApi) as? HttpClient)?.close()
        httpClientField.set(freshGraphApi, HttpClient(engine))

        // Replace the provider's private `graphApi` with our mocked instance.
        val graphApiField = OneDriveProvider::class.java.getDeclaredField("graphApi")
        graphApiField.isAccessible = true
        (graphApiField.get(provider) as? GraphApiService)?.close()
        graphApiField.set(provider, freshGraphApi)
        return provider
    }

    @Test
    fun `listChildren filters out German-locale Personal Vault stub`() =
        runTest {
            // Mixed payload: one real folder, the Vault (no facets, size=0, German name), one real file.
            val body =
                """
                {
                  "value": [
                    {
                      "id": "real-folder",
                      "name": "Dokumente",
                      "size": 0,
                      "folder": {"childCount": 3},
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "vault-stub",
                      "name": "Persönlicher Tresor",
                      "size": 0,
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "real-file",
                      "name": "readme.txt",
                      "size": 128,
                      "file": {"mimeType": "text/plain"},
                      "parentReference": {"path": "/drive/root:"}
                    }
                  ]
                }
                """.trimIndent()

            val provider = mockedProvider(body)
            val children = provider.listChildren("/")

            assertEquals(2, children.size, "Vault must be filtered; 2 real items remain")
            assertTrue(children.any { it.name == "Dokumente" }, "Real folder must pass through")
            assertTrue(children.any { it.name == "readme.txt" }, "Real file must pass through")
            assertFalse(
                children.any { it.name == "Persönlicher Tresor" },
                "Persönlicher Tresor must not appear in listChildren output",
            )
            provider.close()
        }

    @Test
    fun `listChildren filters out facet-less zero-size stub by fallback rule`() =
        runTest {
            // No name match — a hypothetical future-locale vault. The belt-and-braces
            // zero-facet + size=0 rule should still catch it.
            val body =
                """
                {
                  "value": [
                    {
                      "id": "future-locale-vault",
                      "name": "SomeFutureLocaleVault",
                      "size": 0,
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "real-file",
                      "name": "notes.md",
                      "size": 42,
                      "file": {"mimeType": "text/markdown"},
                      "parentReference": {"path": "/drive/root:"}
                    }
                  ]
                }
                """.trimIndent()

            val provider = mockedProvider(body)
            val children = provider.listChildren("/")

            assertEquals(1, children.size, "Fallback rule must filter facet-less stub")
            assertEquals("notes.md", children[0].name)
            provider.close()
        }

    @Test
    fun `delta filters out Personal Vault alongside root-item filter`() =
        runTest {
            // Delta response includes:
            //  - the root item itself (filtered by isRootItem — parentReference.path is null)
            //  - the vault (filtered by isPersonalVault)
            //  - one real file
            val body =
                """
                {
                  "value": [
                    {
                      "id": "root-id",
                      "name": "root",
                      "size": 0,
                      "root": {},
                      "folder": {"childCount": 10}
                    },
                    {
                      "id": "vault-stub",
                      "name": "Personal Vault",
                      "size": 0,
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "real-file",
                      "name": "report.pdf",
                      "size": 2048,
                      "file": {"mimeType": "application/pdf"},
                      "parentReference": {"path": "/drive/root:"}
                    }
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"
                }
                """.trimIndent()

            val provider = mockedProvider(body)
            val page = provider.delta(cursor = null)

            assertEquals(1, page.items.size, "Only the real file survives delta filter")
            assertEquals("report.pdf", page.items[0].name)
            assertFalse(
                page.items.any { it.name == "Personal Vault" },
                "Personal Vault must be filtered from delta output",
            )
            provider.close()
        }
}
