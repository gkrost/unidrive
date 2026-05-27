package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.HashAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * #112 / PR #193 review: the touch-only upload-skip optimization computes a local hash
 * using `CloudProvider.hashAlgorithm()` and compares it against the stored `remoteHash`.
 * For OneDrive, `hashAlgorithm()` returns `HashAlgorithm.QuickXor`, so `toCloudItem()`
 * MUST store the `quickXorHash` value in `remoteHash` — not the `sha256Hash`. When Graph
 * supplies both fields (the common case for personal OneDrive), the old precedence
 * (`sha256Hash ?: quickXorHash`) caused #112's compare to always mismatch: QuickXor local
 * hash vs. SHA-256 stored hash → every touch-only change was re-uploaded anyway.
 *
 * Tests use the same MockEngine-swap idiom as [DeltaRemovedStateTest].
 */
class OneDriveHashAlignmentTest {

    private fun mockedProvider(responseBody: String): OneDriveProvider {
        val engine = MockEngine { _ ->
            respond(
                content = responseBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OneDriveProvider()
        val freshGraphApi = GraphApiService(
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

    /**
     * The realistic personal OneDrive case: Graph returns both sha256Hash and quickXorHash.
     * `toCloudItem()` must store quickXorHash in `CloudItem.hash` so it is consistent with
     * `hashAlgorithm()` returning QuickXor. Without this alignment, #112's touch-only skip
     * computes a QuickXor hash locally and compares it against a SHA-256 stored value →
     * always mismatch → touch-only files are always re-uploaded.
     */
    @Test
    fun `toCloudItem stores quickXorHash when both sha256Hash and quickXorHash are present`() =
        runTest {
            val qxorValue = "dGVzdHF4b3I="   // base64 placeholder (valid base64)
            val sha256Value = "abc123def456"  // hex placeholder
            val provider = mockedProvider(
                """
                {
                  "value": [
                    {
                      "id": "FILE-1",
                      "name": "photo.jpg",
                      "size": 1024,
                      "file": {
                        "mimeType": "image/jpeg",
                        "hashes": {
                          "quickXorHash": "$qxorValue",
                          "sha256Hash": "$sha256Value"
                        }
                      },
                      "parentReference": { "path": "/drive/root:" }
                    }
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"
                }
                """.trimIndent(),
            )

            val page = provider.delta(null)
            val item = page.items.find { it.id == "FILE-1" }
            assertNotNull(item)
            assertEquals(
                qxorValue,
                item.hash,
                "remoteHash must be quickXorHash so it matches hashAlgorithm()=QuickXor; " +
                    "got '${item.hash}' — this is the P2 gap from PR #193",
            )
            assertEquals(HashAlgorithm.QuickXor, provider.hashAlgorithm())
            provider.close()
        }

    /**
     * When only sha256Hash is present (e.g. SharePoint/business drives without quickXorHash),
     * fall back to sha256Hash so the field is not empty.  The #112 fallback guard
     * (`!entry.remoteHash.isNullOrEmpty()`) will still prevent a hash-compare attempt when
     * the algorithm doesn't match, but having a non-null hash preserves diagnostic info.
     */
    @Test
    fun `toCloudItem falls back to sha256Hash when quickXorHash is absent`() =
        runTest {
            val sha256Value = "deadbeefcafe"
            val provider = mockedProvider(
                """
                {
                  "value": [
                    {
                      "id": "FILE-2",
                      "name": "doc.pdf",
                      "size": 4096,
                      "file": {
                        "mimeType": "application/pdf",
                        "hashes": {
                          "sha256Hash": "$sha256Value"
                        }
                      },
                      "parentReference": { "path": "/drive/root:" }
                    }
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"
                }
                """.trimIndent(),
            )

            val page = provider.delta(null)
            val item = page.items.find { it.id == "FILE-2" }
            assertNotNull(item)
            assertEquals(sha256Value, item.hash, "should fall back to sha256Hash when quickXorHash absent")
            provider.close()
        }

    /**
     * When only quickXorHash is present (personal OneDrive before SHA-256 was added),
     * use it directly.
     */
    @Test
    fun `toCloudItem uses quickXorHash when only quickXorHash is present`() =
        runTest {
            val qxorValue = "cXVpY2t4b3I="
            val provider = mockedProvider(
                """
                {
                  "value": [
                    {
                      "id": "FILE-3",
                      "name": "video.mp4",
                      "size": 102400,
                      "file": {
                        "mimeType": "video/mp4",
                        "hashes": {
                          "quickXorHash": "$qxorValue"
                        }
                      },
                      "parentReference": { "path": "/drive/root:" }
                    }
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"
                }
                """.trimIndent(),
            )

            val page = provider.delta(null)
            val item = page.items.find { it.id == "FILE-3" }
            assertNotNull(item)
            assertEquals(qxorValue, item.hash, "quickXorHash-only item must use it directly")
            provider.close()
        }
}
