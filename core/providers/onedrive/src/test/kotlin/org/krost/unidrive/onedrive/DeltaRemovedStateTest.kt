package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Graph's delta endpoint signals two distinct removals on a `@microsoft.graph.removed`
 * facet: `state="deleted"` (hard delete, recycle bin) versus `state="removed"` (soft —
 * the item still exists but this user lost access, e.g. a shared link revoked or the
 * item moved out of scope). Only a hard delete may map to `CloudItem.deleted=true`; a
 * soft removal must not, or a still-valid local copy of a shared item gets reaped.
 *
 * Same MockEngine-swap idiom as DeltaCursorExpiredTest: build a real provider, replace
 * its private graphApi's httpClient with a mock that returns a crafted delta page.
 */
class DeltaRemovedStateTest {
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

        val freshGraphApi =
            GraphApiService(
                config = OneDriveConfig(),
                tokenProvider = { _ -> "test-token" },
            )
        val httpClientField = GraphApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(freshGraphApi) as? HttpClient)?.close()
        httpClientField.set(freshGraphApi, HttpClient(engine))

        val graphApiField = OneDriveProvider::class.java.getDeclaredField("graphApi")
        graphApiField.isAccessible = true
        (graphApiField.get(provider) as? GraphApiService)?.close()
        graphApiField.set(provider, freshGraphApi)
        return provider
    }

    private fun deltaBody(removedFacet: String): String =
        """
        {
          "value": [
            {
              "id": "ITEM-1",
              "name": "shared.txt",
              "size": 12,
              "file": { "mimeType": "text/plain" },
              "parentReference": { "path": "/drive/root:/Shared" },
              $removedFacet
            }
          ],
          "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"
        }
        """.trimIndent()

    @Test
    fun `delta_soft_removed_share_is_not_treated_as_deleted`() =
        runTest {
            val provider = mockedProvider(deltaBody(""""@microsoft.graph.removed": { "state": "removed" }"""))

            val page = provider.delta(null)
            val item = page.items.find { it.id == "ITEM-1" }
            assertNotNull(item, "soft-removed item should still be present in the delta page")
            assertFalse(item.deleted, "a soft removal (state=removed) must NOT mark the item deleted")

            provider.close()
        }

    @Test
    fun `delta_hard_delete_is_still_treated_as_deleted`() =
        runTest {
            val provider = mockedProvider(deltaBody(""""@microsoft.graph.removed": { "state": "deleted" }"""))

            val page = provider.delta(null)
            val item = page.items.find { it.id == "ITEM-1" }
            assertNotNull(item, "hard-deleted item should be present in the delta page")
            assertTrue(item.deleted, "a hard delete (state=deleted) must mark the item deleted")

            provider.close()
        }

    @Test
    fun `delta_deleted_facet_is_still_treated_as_deleted`() =
        runTest {
            val provider = mockedProvider(deltaBody(""""deleted": { "state": "deleted" }"""))

            val page = provider.delta(null)
            val item = page.items.find { it.id == "ITEM-1" }
            assertNotNull(item, "item carrying the deleted facet should be present")
            assertTrue(item.deleted, "the standalone deleted facet must mark the item deleted")

            provider.close()
        }

    @Test
    fun `delta_tombstone_without_parent_path_survives_root_filter`() =
        runTest {
            // Graph's documented minimal tombstone shape carries just id + removal
            // marker; parentReference, when present at all, has no path. The
            // null-path root heuristic must not swallow these — the engine resolves
            // a pathless tombstone via its remote id. The true root item (no
            // deletion facet) is still filtered.
            val body =
                """
                {
                  "value": [
                    {"id": "root-id", "name": "root", "size": 0, "folder": {"childCount": 2}},
                    {"id": "TOMB-NO-PATH", "@microsoft.graph.removed": {"state": "deleted"}, "parentReference": {"driveId": "b!drive"}},
                    {"id": "TOMB-NO-PARENT", "deleted": {"state": "deleted"}}
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"
                }
                """.trimIndent()
            val provider = mockedProvider(body)

            val page = provider.delta(null)
            assertTrue(page.items.none { it.id == "root-id" }, "the true root item must still be filtered")

            val noPath = page.items.find { it.id == "TOMB-NO-PATH" }
            assertNotNull(noPath, "tombstone whose parentReference lacks a path must survive the root filter")
            assertTrue(noPath.deleted, "surviving tombstone must surface as deleted")

            val noParent = page.items.find { it.id == "TOMB-NO-PARENT" }
            assertNotNull(noParent, "tombstone without any parentReference must survive the root filter")
            assertTrue(noParent.deleted, "surviving tombstone must surface as deleted")

            provider.close()
        }

    @Test
    fun `delta_removed_facet_without_state_is_treated_as_deleted`() =
        runTest {
            // Pre-2017 schema fallback: the removed facet is present but carries no
            // state field. We cannot prove it is a soft removal, so the safe (and
            // historically observed) reading is a hard removal — keep deleting it.
            val provider = mockedProvider(deltaBody(""""@microsoft.graph.removed": {}"""))

            val page = provider.delta(null)
            val item = page.items.find { it.id == "ITEM-1" }
            assertNotNull(item, "item carrying a stateless removed facet should be present")
            assertTrue(item.deleted, "a removed facet with no state must default to deleted")

            provider.close()
        }
}
